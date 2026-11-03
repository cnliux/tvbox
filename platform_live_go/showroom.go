package platformlive

// SHOWROOM 直播解析（移植自 pure_live 的 showroom_site.dart / showroom_api.dart）。
//
// SHOWROOM 的官网目录是一次性快照（/api/live/onlives），没有服务端分页，
// 因此这里按 30 条/页在本地切分。播放地址流程：
// 房间号/自定义 slug -> /api/room/profile + /api/live/live_info
// -> /api/live/streaming_url（仅保留 hls / hls_all 线路）。

import (
	"net/url"
	"regexp"
	"sort"
	"strings"
)

const showroomOrigin = "https://www.showroom-live.com"

const showroomUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

const showroomPageSize = 30

var showroomSlugPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,128}$`)

var showroomReservedSlugs = map[string]bool{
	"api": true, "room": true, "event": true, "ranking": true, "search": true,
	"login": true, "register": true, "mypage": true, "premium_live": true,
}

type showroomResolver struct {
	session *httpSession
	// apiFn 仅用于单元测试注入，未设置时走真实 HTTP。
	apiFn func(path string, params url.Values) (interface{}, error)
}

func newShowroomResolver(timeoutSeconds int64) *showroomResolver {
	return &showroomResolver{
		session: newHTTPSession(int(timeoutSeconds), showroomUserAgent, true),
	}
}

func (r *showroomResolver) SiteID() string   { return "showroom" }
func (r *showroomResolver) SiteName() string { return "SHOWROOM" }
func (r *showroomResolver) Description() string {
	return "日本直播分类、房间浏览与 HLS 播放"
}

func showroomHeaders() map[string]string {
	return map[string]string{
		"User-Agent": showroomUserAgent,
		"Accept":     "application/json, text/plain, */*",
		"Referer":    showroomOrigin + "/",
	}
}

func (r *showroomResolver) api(path string, params url.Values) (interface{}, error) {
	if r.apiFn != nil {
		return r.apiFn(path, params)
	}
	body, err := r.session.getWithParams(showroomOrigin+path, params, showroomHeaders())
	if err != nil {
		return nil, newError("SHOWROOM 请求失败：%s", err.Error())
	}
	payload, parseErr := parseJSONPreserveNumbers(body)
	if parseErr != nil {
		return nil, newError("SHOWROOM 接口返回格式异常")
	}
	return payload, nil
}

func (r *showroomResolver) apiObject(path string, params url.Values) (map[string]interface{}, error) {
	payload, err := r.api(path, params)
	if err != nil {
		return nil, err
	}
	object := jsonObject(payload)
	if object == nil {
		return nil, newError("SHOWROOM 接口返回数据异常")
	}
	return object, nil
}

func (r *showroomResolver) NormalizeRoomID(value string) (string, error) {
	return parseShowroomRoomID(value)
}

func parseShowroomRoomID(value string) (string, error) {
	raw := TextStrict(value)
	if raw == "" {
		return "", newError("SHOWROOM 房间号不能为空")
	}
	if number, ok := ToInt(raw); ok && number > 0 {
		return Text(number, ""), nil
	}
	if showroomSlugPattern.MatchString(raw) {
		if showroomReservedSlugs[strings.ToLower(raw)] {
			return "", newError("SHOWROOM 房间号不能使用保留字")
		}
		return raw, nil
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return "", newError("SHOWROOM 房间号必须为数字、slug 或直播间链接")
	}
	host := strings.ToLower(parsed.Hostname())
	if host != "showroom-live.com" && !strings.HasSuffix(host, ".showroom-live.com") {
		return "", newError("直播间链接不属于 SHOWROOM")
	}
	if parsed.User != nil {
		return "", newError("SHOWROOM 直播间链接格式无效")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", newError("SHOWROOM 直播间链接格式无效")
	}
	if parsed.Port() != "" && parsed.Port() != "80" && parsed.Port() != "443" {
		return "", newError("SHOWROOM 直播间链接格式无效")
	}
	parts := make([]string, 0, 2)
	for _, part := range strings.Split(parsed.Path, "/") {
		if part != "" {
			parts = append(parts, part)
		}
	}
	if len(parts) == 2 && parts[0] == "room" && parts[1] == "profile" {
		if number, ok := ToInt(parsed.Query().Get("room_id")); ok && number > 0 {
			return Text(number, ""), nil
		}
		return "", newError("SHOWROOM 直播间链接缺少有效的 room_id")
	}
	if len(parts) == 2 && parts[0] == "r" {
		parts = parts[1:]
	} else if len(parts) != 1 {
		return "", newError("SHOWROOM 直播间链接格式无效")
	}
	slug := parts[0]
	if !showroomSlugPattern.MatchString(slug) || showroomReservedSlugs[strings.ToLower(slug)] {
		return "", newError("SHOWROOM 直播间链接格式无效")
	}
	return slug, nil
}

func showroomTrustedHost(host string) bool {
	return host == "showroom-live.com" || strings.HasSuffix(host, ".showroom-live.com") ||
		host == "showroom-txlive.com" || strings.HasSuffix(host, ".showroom-txlive.com")
}

func showroomPicture(value interface{}) string {
	raw := TextStrict(value)
	if strings.HasPrefix(raw, "//") {
		raw = "https:" + raw
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil {
		return ""
	}
	if !showroomTrustedHost(strings.ToLower(parsed.Hostname())) {
		return ""
	}
	return raw
}

func showroomBool(value interface{}) bool {
	if raw, ok := value.(bool); ok {
		return raw
	}
	return false
}

type showroomStream struct {
	id        int64
	kind      string
	label     string
	quality   int64
	url       string
	isDefault bool
}

func parseShowroomStreams(value interface{}) []showroomStream {
	rows := jsonArray(value)
	streams := make([]showroomStream, 0, len(rows))
	seen := map[string]bool{}
	for _, raw := range rows {
		row := jsonObject(raw)
		if row == nil {
			continue
		}
		kind := TextStrict(row["type"])
		if kind != "hls" && kind != "hls_all" {
			continue
		}
		rawURL := TextStrict(row["url"])
		parsed, err := url.Parse(rawURL)
		if err != nil || parsed.Scheme != "https" || parsed.Host == "" || parsed.User != nil || parsed.Fragment != "" {
			continue
		}
		if !showroomTrustedHost(strings.ToLower(parsed.Hostname())) || seen[rawURL] {
			continue
		}
		seen[rawURL] = true
		id, _ := ToInt(row["id"])
		quality, _ := ToInt(row["quality"])
		streams = append(streams, showroomStream{
			id:        id,
			kind:      kind,
			label:     TextStrict(row["label"]),
			quality:   quality,
			url:       rawURL,
			isDefault: showroomBool(row["is_default"]),
		})
	}
	return streams
}

func showroomStreamsFromLive(row map[string]interface{}) []showroomStream {
	return parseShowroomStreams(row["streaming_url_list"])
}

func showroomQualityLabel(streams []showroomStream) string {
	best := int64(-1)
	for _, stream := range streams {
		if stream.quality > best {
			best = stream.quality
		}
	}
	switch {
	case best >= 1000:
		return "原画"
	case best >= 200:
		return "高清"
	default:
		return "标清"
	}
}

func showroomRoomFromLive(row map[string]interface{}, categoryID, categoryName string) (LiveRoom, bool) {
	roomID := TextStrict(row["room_id"])
	if roomID == "" {
		return LiveRoom{}, false
	}
	if _, ok := ToInt(roomID); !ok {
		return LiveRoom{}, false
	}
	name := Text(row["main_name"], "SHOWROOM "+roomID)
	online, _ := ToInt(row["view_num"])
	return LiveRoom{
		RoomID:       roomID,
		Title:        name,
		Anchor:       name,
		Cover:        showroomPicture(row["image_square"]),
		Online:       online,
		CategoryID:   categoryID,
		CategoryName: categoryName,
	}, true
}

func (r *showroomResolver) onlives() ([]map[string]interface{}, error) {
	payload, err := r.apiObject("/api/live/onlives", nil)
	if err != nil {
		return nil, err
	}
	rows := jsonArray(payload["onlives"])
	if len(rows) == 0 {
		return nil, newError("SHOWROOM 直播目录为空")
	}
	result := make([]map[string]interface{}, 0, len(rows))
	for _, raw := range rows {
		if group := jsonObject(raw); group != nil {
			result = append(result, group)
		}
	}
	if len(result) == 0 {
		return nil, newError("SHOWROOM 直播目录为空")
	}
	return result, nil
}

func (r *showroomResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	groups, err := r.onlives()
	if err != nil {
		return nil, nil, err
	}
	parents := []LiveParentCategory{{ID: "showroom", Name: "SHOWROOM"}}
	categories := make([]LiveCategory, 0, len(groups))
	seen := map[string]bool{}
	for _, group := range groups {
		id := TextStrict(group["genre_id"])
		name := TextStrict(group["genre_name"])
		if id == "" || name == "" || seen[id] {
			continue
		}
		seen[id] = true
		categories = append(categories, LiveCategory{
			ID:         id,
			Name:       name,
			ParentID:   "showroom",
			ParentName: "SHOWROOM",
		})
	}
	if len(categories) == 0 {
		return nil, nil, newError("SHOWROOM 分类数据为空")
	}
	return parents, categories, nil
}

func (r *showroomResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	groups, err := r.onlives()
	if err != nil {
		return LiveRoomsPage{}, err
	}
	normalized := TextStrict(categoryID)
	rooms := make([]LiveRoom, 0)
	seen := map[string]bool{}
	for _, group := range groups {
		genreID := TextStrict(group["genre_id"])
		genreName := TextStrict(group["genre_name"])
		if normalized != "" && normalized != "showroom" && normalized != "all" && normalized != genreID {
			continue
		}
		for _, raw := range jsonArray(group["lives"]) {
			row := jsonObject(raw)
			if row == nil {
				continue
			}
			// 空分类会用 cell_type=7 的提示占位，不是真实房间。
			if row["room_id"] == nil && row["cell_type"] != nil {
				continue
			}
			room, ok := showroomRoomFromLive(row, genreID, genreName)
			if !ok || seen[room.RoomID] {
				continue
			}
			seen[room.RoomID] = true
			rooms = append(rooms, room)
		}
	}
	if len(rooms) == 0 && normalized != "" && normalized != "showroom" && normalized != "all" {
		matched := false
		for _, group := range groups {
			if TextStrict(group["genre_id"]) == normalized {
				matched = true
				break
			}
		}
		if !matched {
			return LiveRoomsPage{}, newError("SHOWROOM 分类不存在")
		}
	}
	pageCount := int64((len(rooms) + showroomPageSize - 1) / showroomPageSize)
	if pageCount < 1 {
		pageCount = 1
	}
	start := int((page - 1) * showroomPageSize)
	if start >= len(rooms) {
		return LiveRoomsPage{Rooms: []LiveRoom{}, PageCount: pageCount}, nil
	}
	end := start + showroomPageSize
	if end > len(rooms) {
		end = len(rooms)
	}
	return LiveRoomsPage{Rooms: rooms[start:end], PageCount: pageCount}, nil
}

func (r *showroomResolver) resolveRoomID(value string) (string, error) {
	normalized, err := parseShowroomRoomID(value)
	if err != nil {
		return "", err
	}
	if _, ok := ToInt(normalized); ok {
		return normalized, nil
	}
	status, err := r.apiObject("/api/room/status", url.Values{"room_url_key": {normalized}})
	if err != nil {
		return "", err
	}
	roomID := TextStrict(status["room_id"])
	if _, ok := ToInt(roomID); !ok {
		return "", newError("SHOWROOM 房间不存在")
	}
	return roomID, nil
}

func (r *showroomResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	roomID, err := r.resolveRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	profile, err := r.apiObject("/api/room/profile", url.Values{"room_id": {roomID}})
	if err != nil {
		return nil, err
	}
	if !showroomBool(profile["is_onlive"]) {
		return nil, newError("该房间当前未开播")
	}
	liveInfo, err := r.apiObject("/api/live/live_info", url.Values{"room_id": {roomID}})
	if err != nil {
		return nil, err
	}
	status, _ := ToInt(liveInfo["live_status"])
	if status != 2 {
		return nil, newError("该房间当前未开播")
	}
	media, err := r.apiObject("/api/live/streaming_url", url.Values{
		"room_id":       {roomID},
		"abr_available": {"1"},
	})
	if err != nil {
		return nil, err
	}
	streams := parseShowroomStreams(media["streaming_url_list"])
	if len(streams) == 0 {
		return nil, newError("SHOWROOM 未返回有效播放地址")
	}
	sort.SliceStable(streams, func(i, j int) bool {
		// hls_all 是自适应总线路，优先；其余按画质从高到低。
		if streams[i].kind != streams[j].kind {
			return streams[i].kind == "hls_all"
		}
		return streams[i].quality > streams[j].quality
	})
	candidates := make([]LiveStreamCandidate, 0, len(streams))
	for _, stream := range streams {
		label := stream.label
		if label == "" {
			label = strings.ToUpper(stream.kind)
		}
		candidates = append(candidates, LiveStreamCandidate{CDN: label, URL: stream.url, Protocol: "hls"})
	}
	name := Text(profile["main_name"], Text(profile["room_name"], "SHOWROOM "+roomID))
	return &ResolvedLiveStream{
		RoomID:     roomID,
		Title:      name,
		Anchor:     name,
		Quality:    showroomQualityLabel(streams),
		Candidates: UniqueCandidates(candidates),
		Headers: map[string]string{
			"User-Agent": showroomUserAgent,
			"Referer":    showroomOrigin + "/",
		},
	}, nil
}
