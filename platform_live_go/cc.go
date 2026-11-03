package platformlive

// 网易 CC 直播解析（移植自 pure_live 的 cc_site.dart / cc_catalog.dart）。
//
// 分类不是静态游戏列表：官网把直播入口放在配置中心，需要同时读取游戏
// 元数据（inf.ds.163.com）和直播入口配置（inf-act.ds.163.com）。播放流程：
// 1. GET api.cc.163.com/v1/activitylives/anchor/lives?anchor_ccid= 取 channel_id；
// 2. GET cc.163.com/live/channel/?channelids=&anchor_ccid= 取房间详情；
// 3. 取 quickplay / stream_list 中最高画质档位，把 CDN_FMT / cdn 下各线路
//    展开成多候选。live 线路常是相对 query，需要拼到房间的 m3u8 基址上。

import (
	"encoding/json"
	"net/url"
	"regexp"
	"sort"
	"strings"
)

const (
	ccOrigin          = "https://cc.163.com"
	ccActivityAPI     = "https://api.cc.163.com"
	ccGameCatalogURL  = "https://inf.ds.163.com/v1/web/game-center/basic/base-info-list/by-type"
	ccConfigURL       = "https://inf-act.ds.163.com/v1/act-web/pageConf/commonAppConfig"
	ccConfigID        = "67b32cdd1801fc391a6c2657"
	ccPageSize        = 30
	ccCatalogRowLimit = 2000
	ccConfigItemLimit = 256
)

const ccUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

var (
	ccRoomIDPattern       = regexp.MustCompile(`^[1-9][0-9]{0,31}$`)
	ccCategoryPathPattern = regexp.MustCompile(`^/n/ds_category/([1-9][0-9]{0,15})/$`)
	ccOfficialPathPattern = regexp.MustCompile(`^/([1-9][0-9]{0,15})/$`)
	ccQualityCleanPattern = regexp.MustCompile(`[^a-z0-9]+`)
)

type ccCatalog struct {
	categories    []LiveCategory
	parents       []LiveParentCategory
	officialRooms []LiveRoom
}

type ccResolver struct {
	session *httpSession
	// getFn / postFn 仅用于单元测试注入，未设置时走真实 HTTP。
	getFn  func(rawURL string, params url.Values) (interface{}, error)
	postFn func(rawURL string, body []byte) (interface{}, error)
}

func newCCResolver(timeoutSeconds int64) *ccResolver {
	return &ccResolver{
		session: newHTTPSession(int(timeoutSeconds), ccUserAgent, true),
	}
}

func (r *ccResolver) SiteID() string      { return "cc" }
func (r *ccResolver) SiteName() string    { return "网易CC直播" }
func (r *ccResolver) Description() string { return "网易 CC 分类、官方入口与多 CDN 播放" }

func ccPlayHeaders() map[string]string {
	return map[string]string{
		"User-Agent": ccUserAgent,
		"Referer":    ccOrigin + "/",
	}
}

func ccCatalogHeaders() map[string]string {
	return map[string]string{
		"User-Agent": ccUserAgent,
		"Referer":    "https://ds.163.com/glive/",
		"Origin":     "https://ds.163.com",
	}
}

func (r *ccResolver) getJSON(rawURL string, params url.Values) (interface{}, error) {
	if r.getFn != nil {
		return r.getFn(rawURL, params)
	}
	body, err := r.session.getWithParams(rawURL, params, ccPlayHeaders())
	if err != nil {
		return nil, newError("网易CC请求失败：%s", err.Error())
	}
	payload, parseErr := parseJSONPreserveNumbers(body)
	if parseErr != nil {
		return nil, newError("网易CC接口返回格式异常")
	}
	return payload, nil
}

func (r *ccResolver) postJSON(rawURL string, request map[string]interface{}) (interface{}, error) {
	body, err := json.Marshal(request)
	if err != nil {
		return nil, newError("网易CC请求构造失败")
	}
	if r.postFn != nil {
		return r.postFn(rawURL, body)
	}
	headers := ccCatalogHeaders()
	headers["Content-Type"] = "application/json"
	raw, requestErr := r.session.postRaw(rawURL, body, headers)
	if requestErr != nil {
		return nil, newError("网易CC请求失败：%s", requestErr.Error())
	}
	payload, parseErr := parseJSONPreserveNumbers(raw)
	if parseErr != nil {
		return nil, newError("网易CC接口返回格式异常")
	}
	return payload, nil
}

func (r *ccResolver) NormalizeRoomID(value string) (string, error) {
	return parseCCRoomID(value)
}

func parseCCRoomID(value string) (string, error) {
	raw := TextStrict(value)
	if raw == "" {
		return "", newError("网易CC房间号不能为空")
	}
	if ccRoomIDPattern.MatchString(raw) {
		return raw, nil
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return "", newError("网易CC房间号必须为数字或直播间链接")
	}
	if parsed.User != nil || parsed.Fragment != "" {
		return "", newError("网易CC直播间链接格式无效")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", newError("网易CC直播间链接格式无效")
	}
	if parsed.Port() != "" && parsed.Port() != "80" && parsed.Port() != "443" {
		return "", newError("网易CC直播间链接格式无效")
	}
	if strings.ToLower(parsed.Hostname()) != "cc.163.com" {
		return "", newError("直播间链接不属于网易CC")
	}
	parts := strings.Split(strings.Trim(parsed.Path, "/"), "/")
	if len(parts) != 1 || !ccRoomIDPattern.MatchString(parts[0]) {
		return "", newError("网易CC直播间链接格式无效")
	}
	return parts[0], nil
}

func ccImageURL(value interface{}) string {
	raw := TextStrict(value)
	if strings.HasPrefix(raw, "//") {
		raw = "https:" + raw
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" || parsed.User != nil || parsed.Fragment != "" {
		return ""
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return ""
	}
	return raw
}

func ccCatalogEnvelope(payload interface{}, err error) (map[string]interface{}, error) {
	if err != nil {
		return nil, err
	}
	envelope := jsonObject(payload)
	if envelope == nil {
		return nil, newError("网易CC分类接口返回格式异常")
	}
	code, ok := ToInt(envelope["code"])
	if !ok || code != 200 {
		return nil, newError("网易CC分类接口请求失败")
	}
	return envelope, nil
}

func ccText(value interface{}, limit int) string {
	raw := TextStrict(value)
	if raw == "" || len([]rune(raw)) > limit {
		return ""
	}
	return raw
}

func ccHidden(row map[string]interface{}) bool {
	value, ok := row["hidden"].(bool)
	return ok && value
}

// catalog 复刻 cc_catalog.dart：只信任配置里“直播入口列表”指向的条目。
func (r *ccResolver) catalog() (*ccCatalog, error) {
	gamePayload, err := r.getJSON(ccGameCatalogURL, url.Values{"gameType": {"NETEASE"}})
	if err != nil {
		return nil, err
	}
	games, err := ccCatalogEnvelope(gamePayload, nil)
	if err != nil {
		return nil, err
	}
	gameRows := jsonArray(games["result"])
	if len(gameRows) > ccCatalogRowLimit {
		return nil, newError("网易CC游戏目录异常")
	}
	metadata := map[string]map[string]interface{}{}
	for _, raw := range gameRows {
		game := jsonObject(raw)
		if game == nil {
			return nil, newError("网易CC游戏目录异常")
		}
		key := ccText(game["appKey"], 64)
		if key == "" {
			return nil, newError("网易CC游戏目录异常")
		}
		if _, exists := metadata[key]; exists {
			return nil, newError("网易CC游戏目录存在重复条目")
		}
		metadata[key] = game
	}
	configPayload, err := r.postJSON(ccConfigURL, map[string]interface{}{"id": ccConfigID})
	if err != nil {
		return nil, err
	}
	configEnvelope, err := ccCatalogEnvelope(configPayload, nil)
	if err != nil {
		return nil, err
	}
	config := jsonObject(configEnvelope["result"])
	if config == nil || TextStrict(config["id"]) != ccConfigID || ccHidden(config) {
		return nil, newError("网易CC分类配置已变更")
	}
	var liveGroup map[string]interface{}
	for _, raw := range jsonArray(config["itemList"]) {
		group := jsonObject(raw)
		if group == nil {
			return nil, newError("网易CC分类配置异常")
		}
		if TextStrict(group["name"]) == "直播入口列表" {
			if liveGroup != nil || ccHidden(group) {
				return nil, newError("网易CC分类配置异常")
			}
			liveGroup = group
		}
	}
	if liveGroup == nil {
		return nil, newError("网易CC分类配置缺少直播入口")
	}
	items := jsonArray(liveGroup["itemList"])
	if len(items) > ccConfigItemLimit {
		return nil, newError("网易CC直播入口配置异常")
	}
	catalog := &ccCatalog{}
	seen := map[string]bool{}
	for _, raw := range items {
		entry := jsonObject(raw)
		if entry == nil || ccHidden(entry) {
			continue
		}
		key := ccText(entry["name"], 64)
		game := metadata[key]
		if game == nil {
			return nil, newError("网易CC直播入口没有对应游戏")
		}
		entryURL, parseErr := url.Parse(ccText(entry["content"], 2048))
		if parseErr != nil || entryURL.Scheme != "https" || entryURL.Hostname() != "cc.163.com" ||
			entryURL.User != nil || entryURL.Port() != "" || entryURL.Fragment != "" {
			return nil, newError("网易CC直播入口地址异常")
		}
		name := ccText(game["name"], 200)
		if name == "" {
			return nil, newError("网易CC游戏名称异常")
		}
		cover := ccImageURL(game["icon"])
		if categoryID := ccCategoryPathPattern.FindStringSubmatch(entryURL.Path); len(categoryID) == 2 {
			id := "game:" + categoryID[1]
			if seen[id] {
				return nil, newError("网易CC直播入口存在重复分类")
			}
			seen[id] = true
			catalog.categories = append(catalog.categories, LiveCategory{
				ID:         id,
				Name:       name,
				ParentID:   "cc:games",
				ParentName: "直播分类",
				Cover:      cover,
			})
			continue
		}
		if officialID := ccOfficialPathPattern.FindStringSubmatch(entryURL.Path); len(officialID) == 2 {
			roomID := officialID[1]
			key := "official:" + roomID
			if seen[key] {
				return nil, newError("网易CC直播入口存在重复房间")
			}
			seen[key] = true
			catalog.officialRooms = append(catalog.officialRooms, LiveRoom{
				RoomID:       roomID,
				Title:        name,
				Anchor:       "网易CC",
				Cover:        cover,
				CategoryID:   "official:all",
				CategoryName: "官方房间/专题",
			})
			continue
		}
		return nil, newError("网易CC直播入口路由未知")
	}
	if len(catalog.categories) > 0 {
		catalog.parents = append(catalog.parents, LiveParentCategory{ID: "cc:games", Name: "直播分类"})
	}
	if len(catalog.officialRooms) > 0 {
		catalog.parents = append(catalog.parents, LiveParentCategory{ID: "cc:official", Name: "官方入口"})
		catalog.categories = append(catalog.categories, LiveCategory{
			ID:         "official:all",
			Name:       "官方房间/专题",
			ParentID:   "cc:official",
			ParentName: "官方入口",
		})
	}
	if len(catalog.categories) == 0 {
		return nil, newError("网易CC分类为空")
	}
	return catalog, nil
}

func (r *ccResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	catalog, err := r.catalog()
	if err != nil {
		return nil, nil, err
	}
	return catalog.parents, catalog.categories, nil
}

func ccOnline(row map[string]interface{}) int64 {
	for _, key := range []string{"webcc_visitor", "hot_score", "visitor", "total_visitor", "vision_visitor", "online_num"} {
		if value, ok := ToInt(row[key]); ok && value > 0 {
			return value
		}
	}
	return 0
}

func ccCategoryRooms(categoryID string) (string, bool) {
	raw := TextStrict(categoryID)
	if raw == "" || raw == "cc" || raw == "all" {
		return "", true
	}
	if strings.HasPrefix(raw, "game:") {
		id := strings.TrimPrefix(raw, "game:")
		return id, isDigits(id)
	}
	return "", false
}

func (r *ccResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	normalized := TextStrict(categoryID)
	if normalized == "official:all" {
		catalog, err := r.catalog()
		if err != nil {
			return LiveRoomsPage{}, err
		}
		if page > 1 {
			return LiveRoomsPage{Rooms: []LiveRoom{}, PageCount: 1}, nil
		}
		return LiveRoomsPage{Rooms: catalog.officialRooms, PageCount: 1}, nil
	}
	gameID, ok := ccCategoryRooms(normalized)
	if !ok || gameID == "" {
		return LiveRoomsPage{}, newError("网易CC分类 ID 格式无效")
	}
	offset := (page - 1) * ccPageSize
	payload, err := r.getJSON(ccOrigin+"/api/category/"+gameID+"/", url.Values{
		"format": {"json"},
		"tag_id": {"0"},
		"start":  {Text(offset, "0")},
		"size":   {Text(ccPageSize, "30")},
	})
	if err != nil {
		return LiveRoomsPage{}, err
	}
	result := jsonObject(payload)
	if result == nil || TextStrict(result["gametype"]) != gameID {
		return LiveRoomsPage{}, newError("网易CC分类接口返回异常")
	}
	rows := jsonArray(result["lives"])
	if len(rows) > 1000 {
		return LiveRoomsPage{}, newError("网易CC分类房间数量异常")
	}
	rooms := make([]LiveRoom, 0, len(rows))
	seen := map[string]bool{}
	for _, raw := range rows {
		row := jsonObject(raw)
		if row == nil {
			return LiveRoomsPage{}, newError("网易CC分类房间数据异常")
		}
		roomID := TextStrict(row["cuteid"])
		if !ccRoomIDPattern.MatchString(roomID) {
			return LiveRoomsPage{}, newError("网易CC分类房间号异常")
		}
		if seen[roomID] {
			continue
		}
		seen[roomID] = true
		rooms = append(rooms, LiveRoom{
			RoomID:       roomID,
			Title:        TextStrict(row["title"]),
			Anchor:       TextStrict(row["nickname"]),
			Cover:        ccImageURL(row["cover"]),
			Online:       ccOnline(row),
			CategoryID:   normalized,
			CategoryName: TextStrict(row["game_name"]),
		})
	}
	pageCount := page
	if len(rows) >= ccPageSize {
		pageCount = page + 1
	}
	return LiveRoomsPage{Rooms: rooms, PageCount: pageCount}, nil
}

// ccLineURL 展开 quickplay 的 CDN_FMT 行：绝对地址直接用，相对 query 拼到 m3u8。
func ccLineURL(baseURL string, value interface{}) string {
	raw := TextStrict(value)
	if strings.HasPrefix(raw, "//") {
		raw = "https:" + raw
	}
	parsed, err := url.Parse(raw)
	if err == nil && parsed.Host != "" {
		if parsed.User != nil || parsed.Fragment != "" {
			return ""
		}
		if parsed.Scheme != "http" && parsed.Scheme != "https" {
			return ""
		}
		return raw
	}
	if raw == "" {
		return baseURL
	}
	base, baseErr := url.Parse(baseURL)
	if baseErr != nil || base.Host == "" || base.Scheme != "http" && base.Scheme != "https" {
		return ""
	}
	suffix := strings.TrimLeft(raw, "?&")
	if suffix == "" {
		return baseURL
	}
	if strings.Contains(baseURL, "?") {
		return baseURL + "&" + suffix
	}
	return baseURL + "?" + suffix
}

func ccDirectURL(value interface{}) string {
	raw := TextStrict(value)
	if strings.HasPrefix(raw, "//") {
		raw = "https:" + raw
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" || parsed.User != nil || parsed.Fragment != "" {
		return ""
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return ""
	}
	return raw
}

func ccQualityRank(key string, bitrate int64) int64 {
	clean := ccQualityCleanPattern.ReplaceAllString(strings.ToLower(key), "")
	rank := int64(0)
	switch clean {
	case "blueray", "original", "origin", "source":
		rank = 6
	case "ultra":
		rank = 5
	case "high", "hd":
		rank = 4
	case "medium", "standard", "sd":
		rank = 3
	case "low", "ld":
		rank = 2
	}
	if rank == 0 {
		return bitrate
	}
	if bitrate > 999999 {
		bitrate = 999999
	}
	return rank*1000000 + bitrate
}

func ccQualityLabel(key string) string {
	switch ccQualityCleanPattern.ReplaceAllString(strings.ToLower(key), "") {
	case "blueray", "original", "origin", "source":
		return "原画"
	case "ultra":
		return "蓝光"
	case "high", "hd":
		return "高清"
	case "medium", "standard", "sd":
		return "标准"
	case "low", "ld":
		return "低清"
	default:
		return key
	}
}

type ccTier struct {
	key        string
	rank       int64
	bitrate    int64
	candidates []LiveStreamCandidate
}

func ccQualityTiers(room map[string]interface{}) []ccTier {
	rawData := jsonObject(room["quickplay"])
	if rawData == nil {
		rawData = jsonObject(room["stream_list"])
	}
	if rawData == nil {
		return nil
	}
	// 与 pure_live 一致：只有出现 resolution 字段才是“已生成的直链”结构，
	// 否则 quickplay / stream_list 的 CDN_FMT 都要拼到房间 m3u8 基址上。
	isLiveStream := true
	if _, exists := rawData["resolution"]; exists {
		isLiveStream = false
	}
	baseURL := TextStrict(room["m3u8"])
	tiers := make([]ccTier, 0, len(rawData))
	for key, raw := range rawData {
		if key == "resolution" {
			continue
		}
		value := jsonObject(raw)
		if value == nil {
			continue
		}
		cdn := jsonObject(value["CDN_FMT"])
		if !isLiveStream {
			cdn = jsonObject(value["cdn"])
		}
		if cdn == nil {
			continue
		}
		priority := map[string]bool{"hs": true, "ks": true, "ali": true, "fws": true, "wy": true}
		preferred := []LiveStreamCandidate{}
		other := []LiveStreamCandidate{}
		seen := map[string]bool{}
		for line, lineValue := range cdn {
			var rawURL string
			if isLiveStream {
				rawURL = ccLineURL(baseURL, lineValue)
			} else {
				rawURL = ccDirectURL(lineValue)
			}
			if rawURL == "" || seen[rawURL] {
				continue
			}
			seen[rawURL] = true
			candidate := LiveStreamCandidate{CDN: line, URL: rawURL, Protocol: InferProtocol(rawURL)}
			if priority[strings.ToLower(line)] {
				preferred = append(preferred, candidate)
			} else {
				other = append(other, candidate)
			}
		}
		candidates := append(preferred, other...)
		if len(candidates) == 0 {
			continue
		}
		bitrate, _ := ToInt(value["vbr"])
		tiers = append(tiers, ccTier{
			key:        key,
			rank:       ccQualityRank(key, bitrate),
			bitrate:    bitrate,
			candidates: candidates,
		})
	}
	sort.SliceStable(tiers, func(i, j int) bool {
		if tiers[i].rank != tiers[j].rank {
			return tiers[i].rank > tiers[j].rank
		}
		return tiers[i].key < tiers[j].key
	})
	return tiers
}

func (r *ccResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	roomID, err := parseCCRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	detailPayload, err := r.getJSON(ccActivityAPI+"/v1/activitylives/anchor/lives", url.Values{"anchor_ccid": {roomID}})
	if err != nil {
		return nil, err
	}
	detail := jsonObject(detailPayload)
	if detail == nil {
		return nil, newError("网易CC房间数据异常")
	}
	data := jsonObject(detail["data"])
	row := jsonObject(data[roomID])
	if row == nil {
		return nil, newError("网易CC房间不存在")
	}
	channelID := TextStrict(row["channel_id"])
	if !isDigits(channelID) {
		return nil, newError("网易CC房间频道异常")
	}
	channelPayload, err := r.getJSON(ccOrigin+"/live/channel/", url.Values{
		"channelids":  {channelID},
		"anchor_ccid": {roomID},
	})
	if err != nil {
		return nil, err
	}
	channel := jsonObject(channelPayload)
	if channel == nil {
		return nil, newError("网易CC房间数据异常")
	}
	rooms := jsonArray(channel["data"])
	if len(rooms) == 0 {
		return nil, newError("网易CC房间不存在")
	}
	room := jsonObject(rooms[0])
	if room == nil || TextStrict(room["ccid"]) != roomID {
		return nil, newError("网易CC房间数据异常")
	}
	status, ok := ToInt(room["status"])
	if !ok {
		return nil, newError("网易CC房间数据异常")
	}
	if status != 1 {
		return nil, newError("该房间当前未开播")
	}
	tiers := ccQualityTiers(room)
	if len(tiers) == 0 {
		return nil, newError("网易CC未返回有效播放地址")
	}
	best := tiers[0]
	return &ResolvedLiveStream{
		RoomID:     roomID,
		Title:      Text(room["title"], "网易CC直播 "+roomID),
		Anchor:     TextStrict(room["nickname"]),
		Quality:    ccQualityLabel(best.key),
		Candidates: UniqueCandidates(best.candidates),
		Headers:    ccPlayHeaders(),
	}, nil
}
