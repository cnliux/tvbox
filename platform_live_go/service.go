package platformlive

import (
	"encoding/json"
	"os"
	"strings"
	"sync"
)

// PlatformSite 平台元信息（Python server.PlatformSite 的等价结构）。
type PlatformSite struct {
	ID          string
	Name        string
	Description string
}

// CatalogChannel 频道目录条目（Python server.CatalogChannel）。
type CatalogChannel struct {
	ID     string
	Name   string
	Site   string
	RoomID string
	Group  string
}

// Service 组合各平台 resolver 的统一入口，等于 Python server.LiveService。
type Service struct {
	cache    *streamCache
	adapters []Adapter
	byID     map[string]Adapter

	clientType string // "web"/"mobile"/"tv"，影响抖音等平台的 UA 与请求参数

	catalogMu               sync.Mutex
	catalogProvider         func() (string, error)
	catalogParsed           bool
	channels                []CatalogChannel
	channelsErr             error
}

// NewService 构建全部平台 adapter（顺序与 Python dict 插入序一致）。
// clientType: "web"=PC网页/桌面, "mobile"=手机, "tv"=Android TV；空值默认为 "web"。
func NewService(timeoutSeconds int64, cacheSeconds int64, catalogProvider func() (string, error), clientType string) *Service {
	if clientType == "" {
		clientType = "web"
	}
	service := &Service{
		cache:        newStreamCache(cacheSeconds),
		catalogProvider: catalogProvider,
		clientType:   clientType,
		byID:         map[string]Adapter{},
	}
	huyaCookie := os.Getenv("TVBOX_PLATFORM_LIVE_HUYA_COOKIE")
	bilibiliCookie := os.Getenv("TVBOX_PLATFORM_LIVE_BILIBILI_COOKIE")
	douyinCookie := os.Getenv("TVBOX_PLATFORM_LIVE_DOUYIN_COOKIE")
	kuaishouCookie := os.Getenv("TVBOX_PLATFORM_LIVE_KUAISHOU_COOKIE")
	kuaishouKww := os.Getenv("TVBOX_PLATFORM_LIVE_KUAISHOU_KWW")
	service.adapters = []Adapter{
		newDouyuAdapter(timeoutSeconds),
		newHuyaResolver(timeoutSeconds, huyaCookie),
		newBilibiliResolver(timeoutSeconds, bilibiliCookie),
		newDouyinResolver(timeoutSeconds, douyinCookie, clientType),
		newKuaishouResolver(timeoutSeconds, kuaishouCookie, kuaishouKww),
		newCCResolver(timeoutSeconds),
		newMissevanResolver(timeoutSeconds),
		newShowroomResolver(timeoutSeconds),
		newInkeResolver(timeoutSeconds),
		newWeiboResolver(timeoutSeconds),
	}
	for _, adapter := range service.adapters {
		service.byID[strings.ToLower(adapter.SiteID())] = adapter
	}
	return service
}

// Sites 平台列表，顺序与 Python 实现一致。
func (s *Service) Sites() []PlatformSite {
	result := make([]PlatformSite, 0, len(s.adapters))
	for _, adapter := range s.adapters {
		result = append(result, PlatformSite{
			ID:          adapter.SiteID(),
			Name:        adapter.SiteName(),
			Description: adapter.Description(),
		})
	}
	return result
}

func (s *Service) adapter(site string) (Adapter, error) {
	normalized := strings.TrimSpace(strings.ToLower(site))
	if adapter, ok := s.byID[normalized]; ok && normalized != "" {
		return adapter, nil
	}
	return nil, newError("暂不支持直播平台：%s", site)
}

// Categories 输出结构与 Python service.categories 一致。
func (s *Service) Categories(site string) (map[string]interface{}, error) {
	adapter, err := s.adapter(site)
	if err != nil {
		return nil, err
	}
	parents, categories, err := adapter.GetCategoryTree()
	if err != nil {
		return nil, err
	}
	parentList := make([]map[string]interface{}, 0, len(parents))
	for _, item := range parents {
		parentList = append(parentList, map[string]interface{}{
			"id":    item.ID,
			"name":  item.Name,
			"cover": item.Cover,
		})
	}
	categoryList := make([]map[string]interface{}, 0, len(categories))
	for _, item := range categories {
		categoryList = append(categoryList, map[string]interface{}{
			"id":         item.ID,
			"name":       item.Name,
			"parentId":   item.ParentID,
			"parentName": item.ParentName,
			"cover":      item.Cover,
		})
	}
	return map[string]interface{}{
		"site":             adapter.SiteID(),
		"parentCategories": parentList,
		"categories":       categoryList,
	}, nil
}

// Rooms 输出结构与 Python service.rooms 一致；page 至少为 1。
func (s *Service) Rooms(site, categoryID string, page int64) (map[string]interface{}, error) {
	adapter, err := s.adapter(site)
	if err != nil {
		return nil, err
	}
	normalizedPage := page
	if normalizedPage < 1 {
		normalizedPage = 1
	}
	result, err := adapter.GetCategoryRooms(categoryID, normalizedPage)
	if err != nil {
		return nil, err
	}
	rooms := make([]map[string]interface{}, 0, len(result.Rooms))
	for _, room := range result.Rooms {
		rooms = append(rooms, map[string]interface{}{
			"roomId":       room.RoomID,
			"title":        room.Title,
			"anchor":       room.Anchor,
			"cover":        room.Cover,
			"online":       room.Online,
			"categoryId":   room.CategoryID,
			"categoryName": room.CategoryName,
		})
	}
	return map[string]interface{}{
		"site":      adapter.SiteID(),
		"categoryId": categoryID,
		"page":       normalizedPage,
		"pageCount":  result.PageCount,
		"hasMore":    normalizedPage < result.PageCount,
		"rooms":      rooms,
	}, nil
}

// Catalog 解析频道目录 JSON；与 Python 实现相同：仅保留斗鱼条目并按 id 去重。
func (s *Service) Catalog() ([]CatalogChannel, error) {
	s.catalogMu.Lock()
	defer s.catalogMu.Unlock()
	if !s.catalogParsed {
		s.catalogParsed = true
		s.channels, s.channelsErr = s.parseCatalog()
	}
	if s.channelsErr != nil {
		return nil, s.channelsErr
	}
	return s.channels, nil
}

func (s *Service) parseCatalog() ([]CatalogChannel, error) {
	provide := s.catalogProvider
	if provide == nil {
		return nil, newError("未提供频道目录")
	}
	raw, err := provide()
	if err != nil {
		return nil, newError("频道目录读取失败：%s", err.Error())
	}
	var payload interface{}
	decoder := json.NewDecoder(strings.NewReader(raw))
	// json.loads 接受 UTF-8 BOM 由 Python 特性（utf-8-sig）处理；Go 的 Decode
	// 允许前置 BOM 进入语法错误，因此这里先去掉。
	if err := decoder.Decode(&payload); err != nil {
		return nil, newError("频道目录 JSON 格式错误：%s", err.Error())
	}
	items, ok := payload.([]interface{})
	if !ok {
		return nil, newError("频道目录必须是 JSON 数组")
	}
	channels := make([]CatalogChannel, 0, len(items))
	seen := map[string]bool{}
	for _, item := range items {
		entry, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		site := strings.ToLower(Text(entry["site"], ""))
		if site != "douyu" {
			continue
		}
		id := Text(entry["id"], "")
		name := Text(entry["name"], "")
		roomID, err := parseDouyuRoomID(Text(entry["roomId"], ""))
		if err != nil {
			continue
		}
		if id == "" || name == "" || seen[id] {
			continue
		}
		seen[id] = true
		channels = append(channels, CatalogChannel{
			ID:     id,
			Name:   name,
			Site:   "douyu",
			RoomID: roomID,
			Group: func() string {
				value := strings.TrimSpace(Text(entry["group"], "默认"))
				if value == "" {
					return "默认"
				}
				return value
			}(),
		})
	}
	return channels, nil
}

// Resolve 输出结构与 Python service.resolve 一致。
func (s *Service) Resolve(site, roomInput string, forceRefresh bool) (map[string]interface{}, error) {
	adapter, err := s.adapter(site)
	if err != nil {
		return nil, err
	}
	roomID, err := adapter.NormalizeRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	var stream *ResolvedLiveStream
	var cacheRemaining int64
	if forceRefresh {
		stream, cacheRemaining, err = s.cache.resolveFresh(adapter.SiteID(), roomID, adapter.Resolve)
	} else {
		stream, cacheRemaining, err = s.cache.getOrResolve(adapter.SiteID(), roomID, adapter.Resolve)
	}
	if err != nil {
		return nil, err
	}
	candidates := make([]map[string]interface{}, 0, len(stream.Candidates))
	for _, candidate := range stream.Candidates {
		candidates = append(candidates, map[string]interface{}{
			"cdn":      candidate.CDN,
			"url":      candidate.URL,
			"protocol": candidate.Protocol,
		})
	}
	headers := map[string]string{}
	for key, value := range stream.Headers {
		headers[key] = value
	}
	if headers == nil {
		headers = map[string]string{}
	}
	return map[string]interface{}{
		"live":                  true,
		"roomId":                stream.RoomID,
		"title":                 stream.Title,
		"anchor":                stream.Anchor,
		"quality":               stream.Quality,
		"protocol":              stream.Protocol(),
		"isLoop":                stream.IsLoop,
		"url":                   stream.URL(),
		"headers":               headers,
		"streams":               candidates,
		"cacheSecondsRemaining": cacheRemaining,
	}, nil
}
