package platformlive

// 快手直播解析：分类翻页、网页 __INITIAL_STATE__ 房间状态与多画质播放地址。
// 对应 Python app/src/main/python/kuaishou_resolver.py 的移植，包含：
// - 手工 Cookie 头维护（初始 cookie + 每次响应 Set-Cookie 合并）；
// - kwfv1 推导 Kww 服务端校验头（unquote 后追加 ###ssrc）；
// - playList 里按画质 level/name 分组收集 URL，选 level 最高的一组。

import (
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

const kuaishouUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

const kuaishouDefaultReferer = "https://live.kuaishou.com/"

// kuaishouParentCategories 逐字对应 Python PARENT_CATEGORIES。
var kuaishouParentCategories = [][2]string{
	{"1", "热门"},
	{"2", "网游"},
	{"3", "单机"},
	{"4", "手游"},
	{"5", "棋牌"},
	{"6", "娱乐"},
	{"7", "综合"},
	{"8", "文化"},
}

var (
	kuaishouInitialStateMarker = regexp.MustCompile(`window\.__INITIAL_STATE__\s*=\s*`)
	kuaishouImagePattern       = regexp.MustCompile(`(?i)\.(?:png|jpe?g|webp|gif|svg|avif)(?:\?|$)`)
	kuaishouRoomIDPattern      = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
)

// kuaishouQualityKey 画质分组键：level（py 的 inherited_sort）+ 清晰度名。
type kuaishouQualityKey struct {
	level int64
	name  string
}

// kuaishouURLGroups 对应 Python 的 candidates_by_quality defaultdict(list)，
// order 记录分组首次出现顺序（复刻 py max 的迭代序语义）。
type kuaishouURLGroups struct {
	byKey map[kuaishouQualityKey][]LiveStreamCandidate
	order []kuaishouQualityKey
}

func newKuaishouURLGroups() *kuaishouURLGroups {
	return &kuaishouURLGroups{byKey: map[kuaishouQualityKey][]LiveStreamCandidate{}}
}

func (g *kuaishouURLGroups) add(key kuaishouQualityKey, candidate LiveStreamCandidate) {
	if _, exists := g.byKey[key]; !exists {
		g.order = append(g.order, key)
	}
	g.byKey[key] = append(g.byKey[key], candidate)
}

// best 返回 level 最大的分组；并列取首见（对齐 Python max(key=key[0])）。
func (g *kuaishouURLGroups) best() (kuaishouQualityKey, bool) {
	var result kuaishouQualityKey
	found := false
	for _, key := range g.order {
		if !found || key.level > result.level {
			result = key
			found = true
		}
	}
	return result, found
}

type kuaishouResolver struct {
	session *httpSession
	cookie  string
	cookieObj map[string]string
	kww     string
}

func newKuaishouResolver(timeoutSeconds int64, cookie string, kww string) *kuaishouResolver {
	customCookie := strings.TrimSpace(cookie)
	cookieObj := kuaishouParseCookie(customCookie)
	resolvedKww := strings.TrimSpace(kww)
	if resolvedKww == "" {
		resolvedKww = kuaishouResolveServerKww(cookieObj)
	}
	return &kuaishouResolver{
		session:     newHTTPSession(int(timeoutSeconds), kuaishouUserAgent, true),
		cookieObj:   cookieObj,
		cookie:      customCookie,
		kww:         resolvedKww,
	}
}

func (r *kuaishouResolver) SiteID() string      { return "kuaishou" }
func (r *kuaishouResolver) SiteName() string    { return "快手" }
func (r *kuaishouResolver) Description() string { return "分类直播、网页解析与多画质播放" }

func (r *kuaishouResolver) NormalizeRoomID(value string) (string, error) {
	return r.parseRoomID(value)
}

func (r *kuaishouResolver) headers(referer string) map[string]string {
	if referer == "" {
		referer = kuaishouDefaultReferer
	}
	result := map[string]string{
		"User-Agent": kuaishouUserAgent,
		"Accept":     "application/json, text/plain, */*",
		"Referer":    referer,
	}
	if cookie := r.cookieHeader(); cookie != "" {
		result["Cookie"] = cookie
	}
	if r.kww != "" {
		result["Kww"] = r.kww
	}
	return result
}

// cookieHeader 重组 "; " 连接的 Cookie 串（对应 _cookie_header）。
func (r *kuaishouResolver) cookieHeader() string {
	parts := make([]string, 0, len(r.cookieObj))
	for key, value := range r.cookieObj {
		parts = append(parts, key+"="+value)
	}
	return strings.Join(parts, "; ")
}

// getText 对应 _get_text：请求 Text；每次响应把 Set-Cookie 手工并入 cookie（等价
// requests _merge_response_cookies，且 raise_for_status 之前先合并）。
func (r *kuaishouResolver) getText(rawURL string, params url.Values, referer string) (string, error) {
	body, cookies, err := r.session.getWithCookies(rawURL, params, r.headers(referer))
	r.mergeResponseCookies(cookies)
	if err != nil {
		return "", newError("快手请求失败：%s", err.Error())
	}
	return string(body), nil
}

func (r *kuaishouResolver) getJSON(rawURL string, params url.Values, referer string) (map[string]interface{}, error) {
	body, err := r.getText(rawURL, params, referer)
	if err != nil {
		return nil, err
	}
	payload, err := parseJSONBytes([]byte(body))
	if err != nil {
		return nil, newError("快手接口返回格式异常")
	}
	root := jsonObject(payload)
	if root == nil {
		return nil, newError("快手接口返回格式异常")
	}
	return root, nil
}

func (r *kuaishouResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	var parents []LiveParentCategory
	var categories []LiveCategory
	for _, parent := range kuaishouParentCategories {
		parentID := parent[0]
		parentName := parent[1]
		parents = append(parents, LiveParentCategory{ID: parentID, Name: parentName})
		for page := int64(1); page <= 100; page++ {
			parameters := url.Values{}
			parameters.Set("type", parentID)
			parameters.Set("page", strconv.FormatInt(page, 10))
			parameters.Set("size", "30")
			payload, err := r.getJSON("https://live.kuaishou.com/live_api/category/data", parameters, "")
			if err != nil {
				return nil, nil, err
			}
			values := jsonArray(jsonObject(payload["data"])["list"])
			if len(values) == 0 {
				break
			}
			for _, raw := range values {
				item, ok := raw.(map[string]interface{})
				if !ok {
					continue
				}
				categoryID := Text(item["id"], "")
				name := Text(item["name"], "")
				if categoryID == "" || name == "" {
					continue
				}
				categories = append(categories, LiveCategory{
					ID:         categoryID,
					Name:       name,
					ParentID:   parentID,
					ParentName: parentName,
					Cover:      Text(item["poster"], ""),
				})
			}
			if len(values) < 30 {
				break
			}
		}
	}
	return parents, categories, nil
}

func (r *kuaishouResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	normalizedPage := page
	if normalizedPage < 1 {
		normalizedPage = 1
	}
	gameID := Text(categoryID, "")
	api := "https://live.kuaishou.com/live_api/gameboard/list"
	if utf8RuneLen(gameID) >= 7 {
		api = "https://live.kuaishou.com/live_api/non-gameboard/list"
	}
	parameters := url.Values{}
	parameters.Set("filterType", "0")
	parameters.Set("pageSize", "20")
	parameters.Set("gameId", gameID)
	parameters.Set("page", strconv.FormatInt(normalizedPage, 10))
	payload, err := r.getJSON(api, parameters, "")
	if err != nil {
		return LiveRoomsPage{}, err
	}
	var values []interface{}
	if data := jsonObject(payload["data"]); data != nil {
		values = jsonArray(data["list"])
	}
	rooms := []LiveRoom{}
	for _, raw := range values {
		item, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		author := jsonObject(item["author"])
		if author == nil {
			author = map[string]interface{}{}
		}
		roomID := Text(kuaishouFirstTruthy(author["id"], item["authorId"], item["userId"]), "")
		if roomID == "" {
			continue
		}
		cover := Text(item["poster"], "")
		if cover != "" && !kuaishouImagePattern.MatchString(cover) {
			cover += ".jpg"
		}
		rooms = append(rooms, LiveRoom{
			RoomID:       roomID,
			Title:        Text(kuaishouFirstTruthy(item["caption"], item["title"]), fmt.Sprintf("快手直播 %s", roomID)),
			Anchor:       Text(author["name"], ""),
			Cover:        cover,
			Online:       PositiveInt(item["watchingCount"], 0),
			CategoryID:   gameID,
			CategoryName: "",
		})
	}
	pageCount := normalizedPage
	if len(values) >= 20 {
		pageCount = normalizedPage + 1
	}
	return LiveRoomsPage{Rooms: rooms, PageCount: pageCount}, nil
}

func (r *kuaishouResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	roomID, err := r.parseRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	target := fmt.Sprintf("https://live.kuaishou.com/u/%s", roomID)
	html, err := r.getText(target, nil, kuaishouDefaultReferer)
	if err != nil {
		return nil, err
	}
	state, err := kuaishouParseInitialState(html)
	if err != nil {
		return nil, err
	}
	liveRoom := jsonObject(state["liveroom"])
	if liveRoom == nil {
		return nil, newError("快手直播间状态解析失败")
	}
	playItems := jsonArray(liveRoom["playList"])
	if len(playItems) == 0 {
		return nil, newError("快手直播间未返回播放信息")
	}
	rooms := make([]map[string]interface{}, 0, len(playItems))
	for _, raw := range playItems {
		if item := jsonObject(raw); item != nil {
			rooms = append(rooms, item)
		}
	}
	var selected map[string]interface{}
	for _, item := range rooms {
		if kuaishouIsLive(item) {
			selected = item
			break
		}
	}
	if selected == nil {
		if len(rooms) > 0 {
			selected = rooms[0]
		} else {
			selected = map[string]interface{}{}
		}
	}
	if !kuaishouIsLive(selected) {
		title := kuaishouRoomTitle(selected)
		if title == "" {
			title = roomID
		}
		return nil, newError("房间未开播：%s", title)
	}
	stream := selected
	if nested := jsonObject(selected["liveStream"]); nested != nil {
		stream = nested
	}
	author := jsonObject(selected["author"])
	if author == nil {
		author = map[string]interface{}{}
	}
	groups := newKuaishouURLGroups()
	kuaishouCollectURLs(groups, kuaishouFirstTruthy(stream["playUrls"], selected["playUrls"]), "默认", 0)
	if len(groups.byKey) == 0 {
		return nil, newError("快手未返回可播放地址")
	}
	qualityKey, _ := groups.best()
	candidates := UniqueCandidates(groups.byKey[qualityKey])
	if len(candidates) == 0 {
		return nil, newError("快手未返回有效播放地址")
	}
	title := kuaishouRoomTitle(selected)
	if title == "" {
		title = fmt.Sprintf("快手直播 %s", roomID)
	}
	return &ResolvedLiveStream{
		RoomID:     roomID,
		Title:      title,
		Anchor:     Text(author["name"], ""),
		Quality:    qualityKey.name,
		Candidates: candidates,
		Headers: map[string]string{
			"Referer":    target,
			"User-Agent": kuaishouUserAgent,
		},
	}, nil
}

// kuaishouCollectURLs 递归收集播放地址，分组键继承 (level, name)。
func kuaishouCollectURLs(groups *kuaishouURLGroups, value interface{}, name string, level int64) {
	switch raw := value.(type) {
	case nil:
		return
	case string:
		trimmed := strings.TrimSpace(raw)
		if strings.HasPrefix(trimmed, "http://") || strings.HasPrefix(trimmed, "https://") || strings.HasPrefix(trimmed, "rtmp://") {
			groups.add(kuaishouQualityKey{level: level, name: name}, LiveStreamCandidate{
				CDN:      kuaishouHostname(trimmed),
				URL:      trimmed,
				Protocol: InferProtocol(trimmed),
			})
		}
	case []interface{}:
		for _, item := range raw {
			kuaishouCollectURLs(groups, item, name, level)
		}
	case map[string]interface{}:
		childName := Text(kuaishouFirstTruthy(raw["name"], raw["shortName"]), name)
		childLevel := level
		if raw["level"] != nil {
			if parsed, ok := ToInt(raw["level"]); ok {
				childLevel = parsed
			}
		}
		if directURL, isStr := raw["url"].(string); isStr {
			kuaishouCollectURLs(groups, directURL, childName, childLevel)
		}
		for _, key := range kuaishouSortedKeys(raw) {
			if key == "url" {
				continue
			}
			kuaishouCollectURLs(groups, raw[key], childName, childLevel)
		}
	}
}

// kuaishouParseInitialState 提取 window.__INITIAL_STATE__ 之后的平衡 JSON。
func kuaishouParseInitialState(html string) (map[string]interface{}, error) {
	marker := kuaishouInitialStateMarker.FindStringIndex(html)
	if marker == nil {
		return nil, newError("快手直播页面结构已变化")
	}
	raw, ok := extractJSONValue(html, marker[1])
	if !ok {
		return nil, newError("快手直播状态 JSON 无效")
	}
	raw = strings.ReplaceAll(raw, "undefined", "null")
	var payload interface{}
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		return nil, newError("快手直播状态 JSON 无效")
	}
	state := jsonObject(payload)
	if state == nil {
		return nil, newError("快手直播状态格式异常")
	}
	return state, nil
}

func kuaishouIsLive(room map[string]interface{}) bool {
	stream := room
	if nested := jsonObject(room["liveStream"]); nested != nil {
		stream = nested
	}
	if Text(stream["id"], "") != "" {
		return true
	}
	return kuaishouFlagTrue(stream["isLiving"]) || kuaishouFlagTrue(stream["living"])
}

// kuaishouFlagTrue 对齐 Python `v is True or v == 1`。
func kuaishouFlagTrue(value interface{}) bool {
	if raw, ok := value.(bool); ok {
		return raw
	}
	if num, ok := jsonFloat(value); ok {
		return num == 1
	}
	return false
}

func kuaishouRoomTitle(room map[string]interface{}) string {
	stream := jsonObject(room["liveStream"])
	if stream == nil {
		stream = map[string]interface{}{}
	}
	game := jsonObject(room["gameInfo"])
	if game == nil {
		game = map[string]interface{}{}
	}
	author := jsonObject(room["author"])
	if author == nil {
		author = map[string]interface{}{}
	}
	for _, value := range []interface{}{
		room["caption"], room["title"], stream["caption"], stream["title"], game["name"], author["name"],
	} {
		if result := Text(value, ""); result != "" {
			return result
		}
	}
	return ""
}

// mergeResponseCookies 把响应 Set-Cookie 合入 cookie_obj，重组 Cookie 头；
// Kww 为空时再尝试从 kwfv1 推导。
func (r *kuaishouResolver) mergeResponseCookies(cookies map[string]string) {
	for key, value := range cookies {
		if key == "" {
			continue
		}
		r.cookieObj[key] = value
	}
	r.cookie = r.cookieHeader()
	if r.kww == "" {
		r.kww = kuaishouResolveServerKww(r.cookieObj)
	}
}

func kuaishouParseCookie(cookie string) map[string]string {
	values := map[string]string{}
	for _, item := range strings.Split(cookie, ";") {
		if !strings.Contains(item, "=") {
			continue
		}
		pair := strings.SplitN(item, "=", 2)
		key := strings.TrimSpace(pair[0])
		value := strings.TrimSpace(pair[1])
		if key != "" && value != "" {
			values[key] = value
		}
	}
	return values
}

// kuaishouResolveServerKww 取 kwfv1 → unquote（失败保持原样）→ 追加 ###ssrc。
func kuaishouResolveServerKww(cookieObj map[string]string) string {
	value := Text(cookieObj["kwfv1"], "")
	if value == "" {
		return ""
	}
	value = kuaishouUnquote(value)
	return value + "###ssrc"
}

// kuaishouUnquote 语义对齐 urllib.parse.unquote：不把 `+` 当空格，失败保持原样。
func kuaishouUnquote(value string) string {
	if !strings.Contains(value, "%") {
		return value
	}
	if decoded, err := url.PathUnescape(value); err == nil {
		return decoded
	}
	return value
}

func kuaishouHostname(rawURL string) string {
	parsed, err := url.Parse(rawURL)
	if err != nil {
		return "默认"
	}
	if host := parsed.Hostname(); host != "" {
		return host
	}
	return "默认"
}

// kuaishouFirstTruthy 复刻 Python `a or b or c`：首个真值，否则末项原样返回。
func kuaishouFirstTruthy(values ...interface{}) interface{} {
	for _, value := range values {
		if kuaishouTruthy(value) {
			return value
		}
	}
	if len(values) > 0 {
		return values[len(values)-1]
	}
	return nil
}

func kuaishouTruthy(value interface{}) bool {
	switch raw := value.(type) {
	case nil:
		return false
	case bool:
		return raw
	case string:
		return raw != ""
	case float64:
		return raw != 0
	case []interface{}:
		return len(raw) > 0
	case map[string]interface{}:
		return len(raw) > 0
	default:
		return true
	}
}

// kuaishouSortedKeys 以稳定顺序遍历 JSON 对象（Python 为插入序，这里取字典序保证确定性）。
func kuaishouSortedKeys(source map[string]interface{}) []string {
	keys := make([]string, 0, len(source))
	for key := range source {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
}

func (r *kuaishouResolver) parseRoomID(value string) (string, error) {
	raw := TextStrict(value)
	parsed, parseErr := url.Parse(raw)
	if parseErr == nil && parsed.Host != "" {
		host := strings.SplitN(strings.ToLower(parsed.Host), ":", 2)[0]
		if host != "live.kuaishou.com" && host != "kuaishou.com" {
			return "", newError("直播间链接不属于快手")
		}
		raw = ""
		for _, part := range strings.Split(parsed.Path, "/") {
			if part != "" && part != "u" {
				raw = part
				break
			}
		}
	}
	if raw == "" || !kuaishouRoomIDPattern.MatchString(raw) {
		return "", newError("快手房间号格式无效")
	}
	return raw, nil
}

// utf8RuneLen 对齐 Python len(str)（按字符而非字节数）。
func utf8RuneLen(value string) int { return len([]rune(value)) }
