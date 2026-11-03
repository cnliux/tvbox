package platformlive

// 映客直播解析（移植自 pure_live 的 inke_site.dart / inke_api.dart）。
//
// 映客官网只暴露有限的精选位，没有搜索/全量分页：
// - 分类：GET /web/Live_channel_pc 的 tab_key / channel_name；
// - 目录：GET /web/Live_top_pc（热门）或分类组内的 list；
// - 播放：GET /web/live_share_pc?uid= 取当前场次 liveid，再在
//   Live_top_pc / Live_hot_pc / Live_channel_pc 三个精选位中查找
//   /live/{liveid}_t.flv，避免拿同一主播旧场次的失效地址。
//
// 注意 live_id 是 18 位大整数：统一用 json.Number 解析，避免 float64 精度丢失。

import (
	"net/url"
	"regexp"
	"strings"
)

const inkeOrigin = "https://www.inke.cn"
const inkeAPIOrigin = "https://webapi.busi.inke.cn"

const inkeUserAgent = "Mozilla/5.0"

var inkeRoomIDPattern = regexp.MustCompile(`^[1-9][0-9]{0,17}$`)
var inkeChannelPattern = regexp.MustCompile(`^[A-Za-z0-9](?:[A-Za-z0-9_-]{0,63})$`)

type inkeResolver struct {
	session *httpSession
	// apiFn 仅用于单元测试注入，未设置时走真实 HTTP。
	apiFn func(path string, params url.Values) (map[string]interface{}, error)
}

func newInkeResolver(timeoutSeconds int64) *inkeResolver {
	return &inkeResolver{
		session: newHTTPSession(int(timeoutSeconds), inkeUserAgent, true),
	}
}

func (r *inkeResolver) SiteID() string      { return "inke" }
func (r *inkeResolver) SiteName() string    { return "映客" }
func (r *inkeResolver) Description() string { return "映客官网精选直播目录与 FLV 播放" }

func inkeHeaders() map[string]string {
	return map[string]string{
		"User-Agent": inkeUserAgent,
		"Referer":    inkeOrigin + "/",
		"Origin":     inkeOrigin,
	}
}

func (r *inkeResolver) api(path string, params url.Values) (map[string]interface{}, error) {
	if r.apiFn != nil {
		return r.apiFn(path, params)
	}
	body, err := r.session.getWithParams(inkeAPIOrigin+"/web/"+path, params, inkeHeaders())
	if err != nil {
		return nil, newError("映客请求失败：%s", err.Error())
	}
	payload, parseErr := parseJSONPreserveNumbers(body)
	if parseErr != nil {
		return nil, newError("映客接口返回格式异常")
	}
	envelope := jsonObject(payload)
	if envelope == nil {
		return nil, newError("映客接口返回格式异常")
	}
	code, ok := ToInt(envelope["error_code"])
	if !ok {
		return nil, newError("映客接口返回格式异常")
	}
	// 唯一能确认“主播当前没有直播”的官方错误码；其他错误不能等同于未开播。
	if code == 1099999920 && path == "live_share_pc" {
		return nil, newError("该房间当前未开播")
	}
	if code != 0 {
		return nil, newError("映客接口请求失败（error_code=%d）", code)
	}
	data := jsonObject(envelope["data"])
	if data == nil {
		return nil, newError("映客接口返回数据为空")
	}
	return data, nil
}

func (r *inkeResolver) NormalizeRoomID(value string) (string, error) {
	return parseInkeRoomID(value)
}

func parseInkeRoomID(value string) (string, error) {
	raw := TextStrict(value)
	if raw == "" {
		return "", newError("映客房间号不能为空")
	}
	if inkeRoomIDPattern.MatchString(raw) {
		return raw, nil
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return "", newError("映客房间号必须为数字或直播间链接")
	}
	host := strings.ToLower(parsed.Hostname())
	switch host {
	case "inke.cn", "www.inke.cn", "inke.com", "www.inke.com":
	default:
		return "", newError("直播间链接不属于映客")
	}
	if parsed.User != nil {
		return "", newError("映客直播间链接格式无效")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", newError("映客直播间链接格式无效")
	}
	if parsed.Port() != "" && parsed.Port() != "80" && parsed.Port() != "443" {
		return "", newError("映客直播间链接格式无效")
	}
	if strings.Trim(parsed.Path, "/") != "liveroom/index.html" {
		return "", newError("映客直播间链接格式无效")
	}
	values := parsed.Query()["uid"]
	if len(values) != 1 || !inkeRoomIDPattern.MatchString(values[0]) {
		return "", newError("映客直播间链接缺少有效的 uid")
	}
	return values[0], nil
}

func inkePicture(value interface{}) string {
	raw := TextStrict(value)
	if strings.HasPrefix(raw, "//") {
		raw = "https:" + raw
	}
	parsed, err := url.Parse(raw)
	if err != nil || parsed.User != nil || parsed.Host == "" {
		return ""
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return ""
	}
	return raw
}

// inkeCard 把三个精选位里的同一行结构归一化。
func inkeCard(row map[string]interface{}, categoryID, categoryName string) (LiveRoom, bool) {
	uid := TextStrict(row["uid"])
	nick := TextStrict(row["nick"])
	if !inkeRoomIDPattern.MatchString(uid) || nick == "" {
		return LiveRoom{}, false
	}
	return LiveRoom{
		RoomID:       uid,
		Title:        nick,
		Anchor:       nick,
		Cover:        inkePicture(row["portrait"]),
		CategoryID:   categoryID,
		CategoryName: categoryName,
	}, true
}

func inkeRows(value interface{}) []map[string]interface{} {
	rows := jsonArray(value)
	result := make([]map[string]interface{}, 0, len(rows))
	for _, raw := range rows {
		if row := jsonObject(raw); row != nil {
			result = append(result, row)
		}
	}
	return result
}

func (r *inkeResolver) channels() ([]map[string]interface{}, error) {
	data, err := r.api("Live_channel_pc", nil)
	if err != nil {
		return nil, err
	}
	groups := inkeRows(data["list"])
	if len(groups) == 0 {
		return nil, newError("映客分类数据为空")
	}
	for _, group := range groups {
		key := TextStrict(group["tab_key"])
		if !inkeChannelPattern.MatchString(key) || TextStrict(group["channel_name"]) == "" {
			return nil, newError("映客分类数据异常")
		}
	}
	return groups, nil
}

func (r *inkeResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	groups, err := r.channels()
	if err != nil {
		return nil, nil, err
	}
	parents := []LiveParentCategory{{ID: "inke", Name: "映客"}}
	categories := make([]LiveCategory, 0, len(groups))
	seen := map[string]bool{}
	for _, group := range groups {
		key := TextStrict(group["tab_key"])
		if seen[key] {
			continue
		}
		seen[key] = true
		categories = append(categories, LiveCategory{
			ID:         "channel:" + key,
			Name:       TextStrict(group["channel_name"]),
			ParentID:   "inke",
			ParentName: "映客",
		})
	}
	return parents, categories, nil
}

func (r *inkeResolver) categoryRows(categoryID string, page int64) ([]map[string]interface{}, string, string, error) {
	normalized := TextStrict(categoryID)
	if page > 1 {
		return []map[string]interface{}{}, normalized, "", nil
	}
	if normalized == "" || normalized == "inke" || normalized == "all" || normalized == "top" {
		data, err := r.api("Live_top_pc", nil)
		if err != nil {
			return nil, "", "", err
		}
		return inkeRows(data["list"]), "top", "映客热门", nil
	}
	key := strings.TrimPrefix(normalized, "channel:")
	if !strings.HasPrefix(normalized, "channel:") || !inkeChannelPattern.MatchString(key) {
		return nil, "", "", newError("映客分类 ID 格式无效")
	}
	groups, err := r.channels()
	if err != nil {
		return nil, "", "", err
	}
	for _, group := range groups {
		if TextStrict(group["tab_key"]) == key {
			return inkeRows(group["list"]), normalized, TextStrict(group["channel_name"]), nil
		}
	}
	return nil, "", "", newError("映客分类不存在")
}

func (r *inkeResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	rows, normalized, categoryName, err := r.categoryRows(categoryID, page)
	if err != nil {
		return LiveRoomsPage{}, err
	}
	rooms := make([]LiveRoom, 0, len(rows))
	seen := map[string]bool{}
	for _, row := range rows {
		card, ok := inkeCard(row, normalized, categoryName)
		if !ok || seen[card.RoomID] {
			continue
		}
		seen[card.RoomID] = true
		rooms = append(rooms, card)
	}
	return LiveRoomsPage{Rooms: rooms, PageCount: 1}, nil
}

// inkeFlvURL 保留签名查询，只接受官方 FLV 主机与当前场次路径。
func inkeFlvURL(value interface{}, broadcastID string) (string, bool) {
	raw := TextStrict(value)
	parsed, err := url.Parse(raw)
	if err != nil || parsed.User != nil || parsed.Fragment != "" {
		return "", false
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", false
	}
	if parsed.Port() != "" && parsed.Port() != "80" && parsed.Port() != "443" {
		return "", false
	}
	if strings.ToLower(parsed.Hostname()) != "live-pull-ws.ikstatic.cn" {
		return "", false
	}
	if parsed.Path != "/live/"+broadcastID+"_t.flv" {
		return "", false
	}
	return raw, true
}

func (r *inkeResolver) showcaseMedia(uid, broadcastID string) ([]LiveStreamCandidate, error) {
	for _, path := range []string{"Live_top_pc", "Live_hot_pc", "Live_channel_pc"} {
		data, err := r.api(path, nil)
		if err != nil {
			return nil, err
		}
		var groups [][]map[string]interface{}
		switch path {
		case "Live_hot_pc":
			// 该接口的 list 是 map，值是行数组。
			for _, value := range jsonObject(data["list"]) {
				groups = append(groups, inkeRows(value))
			}
		case "Live_channel_pc":
			for _, group := range inkeRows(data["list"]) {
				groups = append(groups, inkeRows(group["list"]))
			}
		default:
			groups = append(groups, inkeRows(data["list"]))
		}
		candidates := make([]LiveStreamCandidate, 0, 2)
		for _, rows := range groups {
			for _, row := range rows {
				if TextStrict(row["uid"]) != uid || TextStrict(row["live_id"]) != broadcastID {
					continue
				}
				if rawURL, ok := inkeFlvURL(row["stream_addr"], broadcastID); ok {
					candidates = append(candidates, LiveStreamCandidate{CDN: "映客 CDN", URL: rawURL, Protocol: "flv"})
				}
			}
		}
		candidates = UniqueCandidates(candidates)
		if len(candidates) > 0 {
			return candidates, nil
		}
	}
	return nil, newError("映客当前精选目录未提供该场次的播放地址")
}

func (r *inkeResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	uid, err := parseInkeRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	info, err := r.api("live_share_pc", url.Values{"uid": {uid}})
	if err != nil {
		return nil, err
	}
	if TextStrict(info["live_uid"]) != uid {
		return nil, newError("映客房间数据异常")
	}
	status, ok := ToInt(info["status"])
	if !ok || status != 1 {
		return nil, newError("该房间当前未开播")
	}
	broadcastID := TextStrict(info["liveid"])
	if !inkeRoomIDPattern.MatchString(broadcastID) {
		return nil, newError("映客直播场次 ID 异常")
	}
	mediaInfo := jsonObject(info["media_info"])
	if mediaInfo == nil || TextStrict(mediaInfo["inke_id"]) != uid || TextStrict(mediaInfo["nick"]) == "" {
		return nil, newError("映客房间数据异常")
	}
	candidates, err := r.showcaseMedia(uid, broadcastID)
	if err != nil {
		return nil, err
	}
	nick := TextStrict(mediaInfo["nick"])
	title := TextStrict(info["live_name"])
	if title == "" {
		title = nick
	}
	return &ResolvedLiveStream{
		RoomID:     uid,
		Title:      title,
		Anchor:     nick,
		Quality:    "FLV",
		Candidates: candidates,
		Headers:    inkeHeaders(),
	}, nil
}
