package platformlive

// 猫耳 FM 直播解析（移植自 pure_live 的 missevan_site.dart / missevan_api.dart）。
//
// 契约相对简单，全部走匿名公开接口：
// - 分类：GET https://fm.missevan.com/api/v2/meta/data 的 tabs（catalog/tag）；
// - 房间列表：GET /api/v2/chatroom/open/list?p={page}&catalog_id/tag_id=...；
// - 播放：GET /api/v2/live/{roomId}，取 channel 下的 flv/hls 拉流地址。
// 返回 JSON 的 code 非 0 视为接口失败，500030004 表示房间不存在。

import (
	"net/url"
	"regexp"
	"strings"
)

const missevanOrigin = "https://fm.missevan.com"

const missevanUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

var missevanRoomIDPattern = regexp.MustCompile(`^[1-9][0-9]{0,17}$`)
var missevanExpiresPattern = regexp.MustCompile(`^[0-9]{10}$`)

type missevanResolver struct {
	session *httpSession
	// apiFn 仅用于单元测试注入，未设置时走真实 HTTP。
	apiFn func(path string, params url.Values) (map[string]interface{}, error)
}

func newMissevanResolver(timeoutSeconds int64) *missevanResolver {
	return &missevanResolver{
		session: newHTTPSession(int(timeoutSeconds), missevanUserAgent, true),
	}
}

func (r *missevanResolver) SiteID() string      { return "missevan" }
func (r *missevanResolver) SiteName() string    { return "猫耳 FM" }
func (r *missevanResolver) Description() string { return "广播剧/音频直播分类与播放" }

func missevanHeaders() map[string]string {
	return map[string]string{
		"User-Agent": missevanUserAgent,
		"Referer":    missevanOrigin + "/",
		"Origin":     missevanOrigin,
	}
}

func (r *missevanResolver) api(path string, params url.Values) (map[string]interface{}, error) {
	if r.apiFn != nil {
		return r.apiFn(path, params)
	}
	rawURL := missevanOrigin + "/api/v2/" + path
	body, err := r.session.getWithParams(rawURL, params, missevanHeaders())
	if err != nil {
		return nil, newError("猫耳请求失败：%s", err.Error())
	}
	payload, parseErr := parseJSONPreserveNumbers(body)
	if parseErr != nil {
		return nil, newError("猫耳接口返回格式异常")
	}
	envelope := jsonObject(payload)
	if envelope == nil {
		return nil, newError("猫耳接口返回格式异常")
	}
	code, ok := ToInt(envelope["code"])
	if !ok {
		return nil, newError("猫耳接口返回格式异常")
	}
	if code == 500030004 {
		return nil, newError("猫耳房间不存在")
	}
	if code != 0 {
		return nil, newError("猫耳接口请求失败（code=%d）", code)
	}
	info := jsonObject(envelope["info"])
	if info == nil {
		return nil, newError("猫耳接口返回数据为空")
	}
	return info, nil
}

func (r *missevanResolver) NormalizeRoomID(value string) (string, error) {
	return parseMissevanRoomID(value)
}

func parseMissevanRoomID(value string) (string, error) {
	raw := TextStrict(value)
	if raw == "" {
		return "", newError("猫耳房间号不能为空")
	}
	if missevanRoomIDPattern.MatchString(raw) {
		return raw, nil
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return "", newError("猫耳房间号必须为数字或直播间链接")
	}
	host := strings.ToLower(parsed.Hostname())
	if host != "fm.missevan.com" {
		return "", newError("直播间链接不属于猫耳 FM")
	}
	parts := strings.Split(strings.Trim(parsed.Path, "/"), "/")
	if len(parts) != 2 || parts[0] != "live" || !missevanRoomIDPattern.MatchString(parts[1]) {
		return "", newError("猫耳直播间链接格式无效")
	}
	return parts[1], nil
}

func missevanPicture(value interface{}) string {
	raw := TextStrict(value)
	if strings.HasPrefix(raw, "//") {
		raw = "https:" + raw
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" || parsed.Scheme != "https" {
		return ""
	}
	return raw
}

func missevanEnabled(value interface{}) bool {
	switch raw := value.(type) {
	case bool:
		return raw
	case nil:
		return false
	default:
		number, ok := ToInt(raw)
		return ok && number == 1
	}
}

func (r *missevanResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	info, err := r.api("meta/data", nil)
	if err != nil {
		return nil, nil, err
	}
	tabs := jsonArray(info["tabs"])
	if len(tabs) == 0 {
		return nil, nil, newError("猫耳分类数据为空")
	}
	parents := []LiveParentCategory{{ID: "missevan", Name: "猫耳 FM"}}
	categories := make([]LiveCategory, 0, len(tabs))
	seen := map[string]bool{}
	for _, raw := range tabs {
		tab := jsonObject(raw)
		if tab == nil {
			continue
		}
		kind := TextStrict(tab["type"])
		if kind != "catalog" && kind != "tag" {
			continue
		}
		id, ok := ToInt(tab[kind+"_id"])
		if !ok || id <= 0 {
			continue
		}
		name := TextStrict(tab["name"])
		if name == "" {
			continue
		}
		categoryID := kind + ":" + Text(id, "")
		if seen[categoryID] {
			continue
		}
		seen[categoryID] = true
		categories = append(categories, LiveCategory{
			ID:         categoryID,
			Name:       name,
			ParentID:   "missevan",
			ParentName: "猫耳 FM",
			Cover:      missevanPicture(tab["icon_url"]),
		})
	}
	if len(categories) == 0 {
		return nil, nil, newError("猫耳分类数据为空")
	}
	return parents, categories, nil
}

// parseMissevanCategoryID 解析 "catalog:123"/"tag:123"；空值或父分类表示不过滤。
func parseMissevanCategoryID(categoryID string) (string, string, error) {
	raw := TextStrict(categoryID)
	if raw == "" || raw == "missevan" || raw == "all" {
		return "", "", nil
	}
	parts := strings.SplitN(raw, ":", 2)
	if len(parts) != 2 {
		return "", "", newError("猫耳分类 ID 格式无效")
	}
	kind := parts[0]
	if kind != "catalog" && kind != "tag" {
		return "", "", newError("猫耳分类 ID 格式无效")
	}
	if !missevanRoomIDPattern.MatchString(parts[1]) {
		return "", "", newError("猫耳分类 ID 格式无效")
	}
	return kind, parts[1], nil
}

func (r *missevanResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	kind, id, err := parseMissevanCategoryID(categoryID)
	if err != nil {
		return LiveRoomsPage{}, err
	}
	params := url.Values{"p": {Text(page, "1")}}
	if kind != "" {
		params.Set(kind+"_id", id)
	}
	info, err := r.api("chatroom/open/list", params)
	if err != nil {
		return LiveRoomsPage{}, err
	}
	pagination := jsonObject(info["pagination"])
	maxPage, _ := ToInt(pagination["maxpage"])
	if maxPage < 1 {
		maxPage = 1
	}
	rows := jsonArray(info["Datas"])
	rooms := make([]LiveRoom, 0, len(rows))
	seen := map[string]bool{}
	for _, raw := range rows {
		row := jsonObject(raw)
		if row == nil {
			continue
		}
		roomID := TextStrict(row["room_id"])
		if !missevanRoomIDPattern.MatchString(roomID) || seen[roomID] {
			continue
		}
		status := jsonObject(row["status"])
		if !missevanEnabled(status["open"]) {
			continue
		}
		seen[roomID] = true
		statistics := jsonObject(row["statistics"])
		online, _ := ToInt(statistics["score"])
		rooms = append(rooms, LiveRoom{
			RoomID:       roomID,
			Title:        Text(row["name"], "猫耳直播 "+roomID),
			Anchor:       TextStrict(row["creator_username"]),
			Cover:        missevanPicture(row["cover_url"]),
			Online:       online,
			CategoryID:   categoryID,
			CategoryName: TextStrict(row["catalog_name"]),
		})
	}
	return LiveRoomsPage{Rooms: rooms, PageCount: maxPage}, nil
}

// normalizeMissevanMediaURL 只接受官方 bilivideo CDN 的绝对地址，并按官方
// 客户端做法升级到 https；HLS/FLV 的后缀必须与字段类型一致。
func normalizeMissevanMediaURL(value interface{}, kind string) (string, bool) {
	if kind != "hls" && kind != "flv" {
		return "", false
	}
	raw := TextStrict(value)
	if raw == "" {
		return "", false
	}
	if strings.HasPrefix(raw, "//") {
		raw = "https:" + raw
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" || parsed.User != nil || parsed.Fragment != "" {
		return "", false
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", false
	}
	if parsed.Port() != "" && parsed.Port() != "80" && parsed.Port() != "443" {
		return "", false
	}
	host := strings.ToLower(parsed.Hostname())
	if !strings.HasSuffix(host, ".bilivideo.com") {
		return "", false
	}
	path := strings.ToLower(parsed.Path)
	if kind == "hls" && !strings.HasSuffix(path, ".m3u8") {
		return "", false
	}
	if kind == "flv" && !strings.HasSuffix(path, ".flv") {
		return "", false
	}
	if expires := parsed.Query()["expires"]; len(expires) > 0 {
		if len(expires) != 1 || !missevanExpiresPattern.MatchString(expires[0]) {
			return "", false
		}
	}
	parsed.Scheme = "https"
	if parsed.Port() == "80" {
		parsed.Host = parsed.Hostname()
	}
	return parsed.String(), true
}

func (r *missevanResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	roomID, err := parseMissevanRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	info, err := r.api("live/"+roomID, nil)
	if err != nil {
		return nil, err
	}
	room := jsonObject(info["room"])
	if room == nil {
		return nil, newError("猫耳房间数据异常")
	}
	if !missevanEnabled(jsonObject(room["status"])["open"]) {
		return nil, newError("该房间当前未开播")
	}
	channel := jsonObject(room["channel"])
	if channel == nil {
		return nil, newError("猫耳未返回播放信息")
	}
	candidates := make([]LiveStreamCandidate, 0, 2)
	if rawURL, ok := normalizeMissevanMediaURL(channel["hls_pull_url"], "hls"); ok {
		candidates = append(candidates, LiveStreamCandidate{CDN: "HLS", URL: rawURL, Protocol: "hls"})
	}
	if rawURL, ok := normalizeMissevanMediaURL(channel["flv_pull_url"], "flv"); ok {
		candidates = append(candidates, LiveStreamCandidate{CDN: "FLV", URL: rawURL, Protocol: "flv"})
	}
	candidates = UniqueCandidates(candidates)
	if len(candidates) == 0 {
		return nil, newError("猫耳未返回有效播放地址")
	}
	anchor := TextStrict(room["creator_username"])
	if creator := jsonObject(info["creator"]); creator != nil {
		if value := TextStrict(creator["nickname"]); value != "" {
			anchor = value
		}
	}
	return &ResolvedLiveStream{
		RoomID:     roomID,
		Title:      Text(room["name"], "猫耳直播 "+roomID),
		Anchor:     anchor,
		Quality:    "原画",
		Candidates: candidates,
		Headers:    missevanHeaders(),
	}, nil
}
