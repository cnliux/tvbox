package platformlive

// 微博直播解析（移植自 pure_live 的 weibo_site.dart / weibo_api.dart）。
//
// 微博只暴露匿名推荐快照，没有平台号目录；播放流程：
// - 目录：GET /l/!/2/wblive/pc_recommend/list.json?count=100&uid=
// - 详情：GET /l/!/2/wblive/room/show_pc_live.json?live_id={liveId}
// - 仅 status=1 且 play_switch=1、watch_limit=0 时输出
//   live_origin_hls_url / live_origin_flv_url。
// 观看地址里的冒号可能是 %3A，解析时只解码这一处。

import (
	"net/url"
	"regexp"
	"strings"
)

const weiboOrigin = "https://weibo.com"

const weiboUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

var weiboLiveIDPattern = regexp.MustCompile(`^(?:1022:232132[0-9]{16}|1042152:[0-9a-f]{32})$`)
var weiboShowPathPattern = regexp.MustCompile(`^/l/wblive/[pm]/show/([^/]+)$`)

type weiboResolver struct {
	session *httpSession
	// apiFn 仅用于单元测试注入，未设置时走真实 HTTP。
	apiFn func(path string, params url.Values) (map[string]interface{}, error)
}

func newWeiboResolver(timeoutSeconds int64) *weiboResolver {
	return &weiboResolver{
		session: newHTTPSession(int(timeoutSeconds), weiboUserAgent, true),
	}
}

func (r *weiboResolver) SiteID() string      { return "weibo" }
func (r *weiboResolver) SiteName() string    { return "微博" }
func (r *weiboResolver) Description() string { return "微博直播推荐与公开场次播放" }

func weiboHeaders() map[string]string {
	return map[string]string{
		"User-Agent": weiboUserAgent,
		"Referer":    weiboOrigin + "/l/wblive/",
	}
}

func (r *weiboResolver) api(path string, params url.Values) (map[string]interface{}, error) {
	if r.apiFn != nil {
		return r.apiFn(path, params)
	}
	body, err := r.session.getWithParams(weiboOrigin+path, params, weiboHeaders())
	if err != nil {
		return nil, newError("微博请求失败：%s", err.Error())
	}
	payload, parseErr := parseJSONPreserveNumbers(body)
	if parseErr != nil {
		return nil, newError("微博接口返回格式异常")
	}
	envelope := jsonObject(payload)
	if envelope == nil {
		return nil, newError("微博接口返回格式异常")
	}
	code, codeOK := ToInt(envelope["code"])
	errorCode, errorOK := ToInt(envelope["error_code"])
	if !codeOK || !errorOK {
		return nil, newError("微博接口返回格式异常")
	}
	if code != 100000 || errorCode != 0 {
		return nil, newError("微博接口请求失败（code=%d, error_code=%d）", code, errorCode)
	}
	data := jsonObject(envelope["data"])
	if data == nil {
		return nil, newError("微博接口返回数据为空")
	}
	return data, nil
}

func (r *weiboResolver) NormalizeRoomID(value string) (string, error) {
	return parseWeiboLiveID(value)
}

func parseWeiboLiveID(value string) (string, error) {
	raw := TextStrict(value)
	if raw == "" {
		return "", newError("微博直播 ID 不能为空")
	}
	if strings.ContainsAny(raw, "\\ \t\r\n") {
		return "", newError("微博直播 ID 格式无效")
	}
	if weiboLiveIDPattern.MatchString(raw) {
		return raw, nil
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return "", newError("微博直播 ID 必须为直播 ID 或观看链接")
	}
	host := strings.ToLower(parsed.Hostname())
	if host != "weibo.com" && host != "www.weibo.com" {
		return "", newError("直播间链接不属于微博")
	}
	if parsed.User != nil {
		return "", newError("微博直播间链接格式无效")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", newError("微博直播间链接格式无效")
	}
	if parsed.Port() != "" && parsed.Port() != "80" && parsed.Port() != "443" {
		return "", newError("微博直播间链接格式无效")
	}
	matches := weiboShowPathPattern.FindStringSubmatch(parsed.Path)
	if len(matches) != 2 {
		return "", newError("微博直播间链接格式无效")
	}
	id := strings.ReplaceAll(matches[1], "%3a", ":")
	id = strings.ReplaceAll(id, "%3A", ":")
	if !weiboLiveIDPattern.MatchString(id) {
		return "", newError("微博直播 ID 格式无效")
	}
	return id, nil
}

func weiboURL(value interface{}) string {
	raw := TextStrict(value)
	if strings.ContainsAny(raw, "\\ \t\r\n") {
		return ""
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

func weiboPicture(value interface{}) string {
	return weiboURL(value)
}

func weiboLiveRoom(row map[string]interface{}, categoryID, categoryName string) (LiveRoom, bool) {
	liveID := TextStrict(row["liveid"])
	if !weiboLiveIDPattern.MatchString(liveID) {
		return LiveRoom{}, false
	}
	nickname := TextStrict(row["nickname"])
	if nickname == "" {
		return LiveRoom{}, false
	}
	return LiveRoom{
		RoomID:       liveID,
		Title:        nickname,
		Anchor:       nickname,
		Cover:        weiboPicture(row["cover"]),
		CategoryID:   categoryID,
		CategoryName: categoryName,
	}, true
}

func (r *weiboResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	parents := []LiveParentCategory{{ID: "weibo", Name: "微博"}}
	categories := []LiveCategory{{
		ID:         "weibo:live",
		Name:       "微博直播",
		ParentID:   "weibo",
		ParentName: "微博",
	}}
	return parents, categories, nil
}

func (r *weiboResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	normalized := TextStrict(categoryID)
	if normalized != "" && normalized != "weibo" && normalized != "all" && normalized != "weibo:live" {
		return LiveRoomsPage{}, newError("微博分类 ID 格式无效")
	}
	if page > 1 {
		return LiveRoomsPage{Rooms: []LiveRoom{}, PageCount: 1}, nil
	}
	data, err := r.api("/l/!/2/wblive/pc_recommend/list.json", url.Values{
		"count": {"100"},
		"uid":   {""},
	})
	if err != nil {
		return LiveRoomsPage{}, err
	}
	rows := jsonArray(data["data"])
	rooms := make([]LiveRoom, 0, len(rows))
	seen := map[string]bool{}
	for _, raw := range rows {
		row := jsonObject(raw)
		if row == nil {
			continue
		}
		room, ok := weiboLiveRoom(row, "weibo:live", "微博直播")
		if !ok || seen[room.RoomID] {
			continue
		}
		seen[room.RoomID] = true
		rooms = append(rooms, room)
	}
	return LiveRoomsPage{Rooms: rooms, PageCount: 1}, nil
}

func weiboRestrictionMessage(limit int64) string {
	switch limit {
	case 8:
		return "该直播仅限微博 App 内观看"
	case 10, 11:
		return "该直播仅好友或自己可见"
	case 12:
		return "该直播为付费内容，暂不支持播放"
	default:
		return "该直播限制观看，暂不支持播放"
	}
}

func (r *weiboResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	liveID, err := parseWeiboLiveID(roomInput)
	if err != nil {
		return nil, err
	}
	item, err := r.api("/l/!/2/wblive/room/show_pc_live.json", url.Values{"live_id": {liveID}})
	if err != nil {
		return nil, err
	}
	if TextStrict(item["liveId"]) != liveID {
		return nil, newError("微博直播数据异常")
	}
	status, ok := ToInt(item["status"])
	if !ok {
		return nil, newError("微博直播数据异常")
	}
	switch status {
	case 1:
	case 3:
		return nil, newError("该直播已结束（微博回放暂不支持）")
	case 5:
		return nil, newError("该房间当前未开播")
	default:
		return nil, newError("微博直播状态未知，暂不支持播放")
	}
	playSwitch, ok := ToInt(item["play_switch"])
	if !ok {
		return nil, newError("微博直播数据异常")
	}
	if playSwitch == 0 {
		return nil, newError("该直播暂不支持网页播放")
	}
	watchLimit, ok := ToInt(item["watch_limit"])
	if !ok {
		return nil, newError("微博直播数据异常")
	}
	if watchLimit != 0 {
		return nil, newError("%s", weiboRestrictionMessage(watchLimit))
	}
	candidates := make([]LiveStreamCandidate, 0, 2)
	if rawURL := weiboURL(item["live_origin_hls_url"]); rawURL != "" {
		candidates = append(candidates, LiveStreamCandidate{CDN: "HLS", URL: rawURL, Protocol: InferProtocol(rawURL)})
	}
	if rawURL := weiboURL(item["live_origin_flv_url"]); rawURL != "" {
		candidates = append(candidates, LiveStreamCandidate{CDN: "FLV", URL: rawURL, Protocol: InferProtocol(rawURL)})
	}
	candidates = UniqueCandidates(candidates)
	if len(candidates) == 0 {
		return nil, newError("微博未返回公开播放地址")
	}
	user := jsonObject(item["user"])
	nickname := ""
	if user != nil {
		nickname = TextStrict(user["screenName"])
	}
	title := TextStrict(item["title"])
	if title == "" {
		title = nickname
	}
	if title == "" {
		title = "微博直播 " + liveID
	}
	width, _ := ToInt(item["width"])
	height, _ := ToInt(item["height"])
	quality := "原画"
	if width > 0 && height > 0 {
		quality = TextStrict(item["quality"])
		if quality == "" {
			quality = "原画"
		}
	}
	return &ResolvedLiveStream{
		RoomID:     liveID,
		Title:      title,
		Anchor:     nickname,
		Quality:    quality,
		Candidates: candidates,
		Headers:    weiboHeaders(),
	}, nil
}
