package platformlive

// 哔哩哔哩直播解析：分类、房间列表、房间详情与 WBI 签名播放地址。
// 对应 Python app/src/main/python/bilibili_resolver.py 的移植，包含：
// - WBI 签名（wts/w_rid，mixin key 表重排 + md5）；
// - 429 限流重试（0.8s/1.6s 两次）与 play 接口 0.45s 限速；
// - 签名失败时回退裸参请求（保持默认无 Cookie 可用）。

import (
	"crypto/md5"
	"encoding/hex"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const bilibiliUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"

const bilibiliReferer = "https://live.bilibili.com/"

// bilibiliMixinKeyTable 逐字对应 Python MIXIN_KEY_TABLE。
var bilibiliMixinKeyTable = [64]int{
	46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
	27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
	37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
	22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
}

var bilibiliRoomIDPattern = regexp.MustCompile(`^\d+$`)

type bilibiliResolver struct {
	session *httpSession
	cookie  string

	wbiKeys       [2]string
	wbiKeysLoaded bool
	buvid         [2]string
	buvidLoaded   bool

	playLock        sync.Mutex
	lastPlayRequest time.Time

	// nowFn 可注入时间戳（对应测试 patch time.time）。
	nowFn func() int64
	// getJSONFn 测试注入钩子（等价 Python 测试里的 Mock）；未设置走真实请求。
	getJSONFn func(rawURL string, params url.Values) (map[string]interface{}, error)
}

func newBilibiliResolver(timeoutSeconds int64, cookie string) *bilibiliResolver {
	return &bilibiliResolver{
		session: newHTTPSession(int(timeoutSeconds), bilibiliUserAgent, true),
		cookie:  strings.TrimSpace(cookie),
		nowFn:   func() int64 { return time.Now().Unix() },
	}
}

func (r *bilibiliResolver) SiteID() string      { return "bilibili" }
func (r *bilibiliResolver) SiteName() string    { return "哔哩哔哩" }
func (r *bilibiliResolver) Description() string { return "分类直播、房间浏览与多线路播放" }

func (r *bilibiliResolver) NormalizeRoomID(value string) (string, error) {
	return r.parseRoomID(value)
}

func (r *bilibiliResolver) now() int64 {
	if r.nowFn != nil {
		return r.nowFn()
	}
	return time.Now().Unix()
}

func (r *bilibiliResolver) headers() map[string]string {
	cookie := r.cookie
	if !strings.Contains(cookie, "buvid3=") {
		buvid3, buvid4 := r.getBuvid()
		parts := make([]string, 0, 2)
		if buvid3 != "" {
			parts = append(parts, "buvid3="+buvid3)
		}
		if buvid4 != "" {
			parts = append(parts, "buvid4="+buvid4)
		}
		if extra := strings.Join(parts, ";"); extra != "" {
			if cookie != "" {
				cookie = cookie + ";" + extra
			} else {
				cookie = extra
			}
		}
	}
	result := map[string]string{
		"User-Agent": bilibiliUserAgent,
		"Referer":    bilibiliReferer,
	}
	if cookie != "" {
		result["Cookie"] = cookie
	}
	return result
}

// getBuvid 从 finger/spi 取 b_3/b_4；任何失败都缓存 ("","")（对齐 Python）。
func (r *bilibiliResolver) getBuvid() (string, string) {
	if r.buvidLoaded {
		return r.buvid[0], r.buvid[1]
	}
	r.buvidLoaded = true
	r.buvid = [2]string{"", ""}
	data, err := r.session.get("https://api.bilibili.com/x/frontend/finger/spi", map[string]string{
		"User-Agent": bilibiliUserAgent,
		"Referer":    bilibiliReferer,
	})
	if err != nil {
		return "", ""
	}
	payload, err := parseJSONBytes(data)
	if err != nil {
		return "", ""
	}
	if root := jsonObject(payload); root != nil {
		if nested := jsonObject(root["data"]); nested != nil {
			r.buvid = [2]string{Text(nested["b_3"], ""), Text(nested["b_4"], "")}
		}
	}
	return r.buvid[0], r.buvid[1]
}

func (r *bilibiliResolver) getJSON(rawURL string, params url.Values) (map[string]interface{}, error) {
	if r.getJSONFn != nil {
		return r.getJSONFn(rawURL, params)
	}
	return r.getJSONRequest(rawURL, params)
}

// getJSONRequest 对应 _get_json：429 重试两次，code!=0 视为接口失败。
func (r *bilibiliResolver) getJSONRequest(rawURL string, params url.Values) (map[string]interface{}, error) {
	retryDelays := []time.Duration{800 * time.Millisecond, 1600 * time.Millisecond}
	for attempt := 0; attempt <= len(retryDelays); attempt++ {
		data, err := r.session.getWithParams(rawURL, params, r.headers())
		if err != nil {
			if attempt < len(retryDelays) && strings.HasPrefix(err.Error(), "429") {
				time.Sleep(retryDelays[attempt])
				continue
			}
			return nil, newError("B站请求失败：%s", err.Error())
		}
		payload, err := parseJSONBytes(data)
		if err != nil {
			return nil, newError("B站接口返回格式异常")
		}
		root := jsonObject(payload)
		if root == nil {
			return nil, newError("B站接口返回格式异常")
		}
		code := int64(0)
		if rawCode, exists := root["code"]; exists {
			if parsed, ok := ToInt(rawCode); ok {
				code = parsed
			}
		}
		if code != 0 {
			return nil, newError("B站接口失败：%s", Text(root["message"], fmt.Sprintf("%v", root["code"])))
		}
		return root, nil
	}
	return nil, newError("B站接口重试失败")
}

func (r *bilibiliResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	parameters := url.Values{}
	parameters.Set("need_entrance", "1")
	parameters.Set("parent_id", "0")
	payload, err := r.getJSON("https://api.live.bilibili.com/room/v1/Area/getList", parameters)
	if err != nil {
		return nil, nil, err
	}
	data, ok := payload["data"].([]interface{})
	if !ok {
		return nil, nil, newError("B站未返回有效的直播分类")
	}
	var parents []LiveParentCategory
	var categories []LiveCategory
	for _, rawParent := range data {
		parent, ok := rawParent.(map[string]interface{})
		if !ok {
			continue
		}
		parentID := Text(parent["id"], "")
		parentName := Text(parent["name"], "")
		if parentID == "" || parentName == "" {
			continue
		}
		parents = append(parents, LiveParentCategory{
			ID:    parentID,
			Name:  parentName,
			Cover: Text(parent["pic"], ""),
		})
		children, ok := parent["list"].([]interface{})
		if !ok {
			continue
		}
		for _, rawChild := range children {
			child, ok := rawChild.(map[string]interface{})
			if !ok {
				continue
			}
			categoryID := Text(child["id"], "")
			name := Text(child["name"], "")
			if categoryID == "" || name == "" {
				continue
			}
			childParentID := Text(child["parent_id"], parentID)
			cover := Text(child["pic"], "")
			if cover != "" && !strings.Contains(cover, "@") {
				cover += "@100w.png"
			}
			categories = append(categories, LiveCategory{
				ID:         fmt.Sprintf("%s:%s", childParentID, categoryID),
				Name:       name,
				ParentID:   childParentID,
				ParentName: parentName,
				Cover:      cover,
			})
		}
	}
	return parents, categories, nil
}

func (r *bilibiliResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	normalizedPage := page
	if normalizedPage < 1 {
		normalizedPage = 1
	}
	parentID, areaID, err := bilibiliSplitCategoryID(Text(categoryID, ""))
	if err != nil {
		return LiveRoomsPage{}, err
	}
	parameters := url.Values{}
	parameters.Set("platform", "web")
	parameters.Set("parent_area_id", parentID)
	parameters.Set("area_id", areaID)
	parameters.Set("page", strconv.FormatInt(normalizedPage, 10))
	parameters.Set("page_size", "30")
	payload, err := r.getJSON("https://api.live.bilibili.com/room/v1/Area/getRoomList", parameters)
	if err != nil {
		return LiveRoomsPage{}, err
	}
	data, ok := payload["data"].([]interface{})
	if !ok {
		data = []interface{}{}
	}
	rooms := []LiveRoom{}
	for _, raw := range data {
		item, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		roomID := Text(item["roomid"], "")
		if roomID == "" {
			continue
		}
		cover := Text(bilibiliFirstTruthy(item["cover"], item["user_cover"], item["system_cover"]), "")
		if cover != "" && !strings.Contains(cover, "@") {
			cover += "@400w.jpg"
		}
		rooms = append(rooms, LiveRoom{
			RoomID:       roomID,
			Title:        Text(item["title"], fmt.Sprintf("B站直播 %s", roomID)),
			Anchor:       Text(item["uname"], ""),
			Cover:        cover,
			Online:       PositiveInt(item["online"], 0),
			CategoryID:   fmt.Sprintf("%s:%s", parentID, areaID),
			CategoryName: "",
		})
	}
	pageCount := normalizedPage
	if len(data) >= 30 {
		pageCount = normalizedPage + 1
	}
	return LiveRoomsPage{Rooms: rooms, PageCount: pageCount}, nil
}

func (r *bilibiliResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	roomID, err := r.parseRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	roomInfo, err := r.getRoomInfo(roomID)
	if err != nil {
		return nil, err
	}
	room := jsonObject(roomInfo["room_info"])
	if room == nil {
		return nil, newError("B站未返回有效的直播间信息")
	}
	if PositiveInt(room["live_status"], 0) != 1 {
		return nil, newError("房间未开播：%s", Text(room["title"], roomID))
	}
	playURL, err := r.getPlayURL(roomID, 0, false)
	if err != nil {
		return nil, err
	}
	qualityMap := map[int64]string{}
	for _, raw := range jsonArray(playURL["g_qn_desc"]) {
		item, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		qn := PositiveInt(item["qn"], 0)
		if qn > 0 {
			qualityMap[qn] = Text(item["desc"], "未知清晰度")
		}
	}
	accepted := bilibiliAcceptedQNs(playURL)
	if len(accepted) == 0 {
		for qn := range qualityMap {
			accepted = append(accepted, qn)
		}
		sort.Slice(accepted, func(i, j int) bool { return accepted[i] > accepted[j] })
	}
	if len(accepted) == 0 {
		return nil, newError("B站未返回可用画质")
	}
	selectedQN := accepted[0]
	selected, err := r.getPlayURL(roomID, selectedQN, true)
	if err != nil {
		return nil, err
	}
	candidates := bilibiliExtractCandidates(selected)
	if len(candidates) == 0 {
		return nil, newError("B站未返回可播放地址")
	}
	quality := "未知清晰度"
	if name, exists := qualityMap[selectedQN]; exists {
		quality = name
	}
	return &ResolvedLiveStream{
		RoomID:     roomID,
		Title:      Text(room["title"], fmt.Sprintf("B站直播 %s", roomID)),
		Anchor:     bilibiliAnchorName(roomInfo),
		Quality:    quality,
		Candidates: candidates,
		Headers: map[string]string{
			"Referer":    fmt.Sprintf("https://live.bilibili.com/%s", roomID),
			"User-Agent": bilibiliUserAgent,
		},
	}, nil
}

// getRoomInfo getInfoByRoom 失败时回退 Room/get_info（裸房号接口）。
func (r *bilibiliResolver) getRoomInfo(roomID string) (map[string]interface{}, error) {
	parameters := url.Values{}
	parameters.Set("room_id", roomID)
	payload, err := r.signedGet("https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom", parameters)
	var resolverErr *ResolverError
	if err != nil {
		if !errors.As(err, &resolverErr) {
			return nil, err
		}
		payload, err = r.getJSON("https://api.live.bilibili.com/room/v1/Room/get_info", parameters)
		if err != nil {
			return nil, err
		}
	}
	data := jsonObject(payload["data"])
	if data == nil {
		return nil, newError("B站房间信息格式异常")
	}
	if _, hasRoomInfo := data["room_info"]; !hasRoomInfo {
		if _, hasLiveStatus := data["live_status"]; hasLiveStatus {
			data = map[string]interface{}{"room_info": data}
		}
	}
	return data, nil
}

// getPlayURL getRoomPlayInfo；qn 未指定时 format/codec 取三档组合。
func (r *bilibiliResolver) getPlayURL(roomID string, qn int64, withQN bool) (map[string]interface{}, error) {
	parameters := url.Values{}
	parameters.Set("room_id", roomID)
	parameters.Set("protocol", "0,1")
	parameters.Set("platform", "web")
	if withQN {
		parameters.Set("format", "0,2")
		parameters.Set("codec", "0")
		parameters.Set("qn", strconv.FormatInt(qn, 10))
	} else {
		parameters.Set("format", "0,1,2")
		parameters.Set("codec", "0,1")
	}
	r.playLock.Lock()
	if !r.lastPlayRequest.IsZero() {
		if elapsed := time.Since(r.lastPlayRequest); elapsed < 450*time.Millisecond {
			time.Sleep(450*time.Millisecond - elapsed)
		}
	}
	r.lastPlayRequest = time.Now()
	r.playLock.Unlock()
	payload, err := r.signedGet("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo", parameters)
	if err != nil {
		return nil, err
	}
	play := jsonObject(jsonObject(jsonObject(payload["data"])["playurl_info"])["playurl"])
	if play == nil {
		return nil, newError("B站播放信息响应异常")
	}
	return play, nil
}

// bilibiliAcceptedQNs 取 stream[0].format[0].codec[0].accept_qn 去重降序。
func bilibiliAcceptedQNs(play map[string]interface{}) []int64 {
	streams := jsonArray(play["stream"])
	if len(streams) == 0 {
		return nil
	}
	stream, ok := streams[0].(map[string]interface{})
	if !ok {
		return nil
	}
	formats := jsonArray(stream["format"])
	if len(formats) == 0 {
		return nil
	}
	format, ok := formats[0].(map[string]interface{})
	if !ok {
		return nil
	}
	codecs := jsonArray(format["codec"])
	if len(codecs) == 0 {
		return nil
	}
	codec, ok := codecs[0].(map[string]interface{})
	if !ok {
		return nil
	}
	seen := map[int64]bool{}
	var result []int64
	for _, raw := range jsonArray(codec["accept_qn"]) {
		value := PositiveInt(raw, 0)
		if value > 0 && !seen[value] {
			seen[value] = true
			result = append(result, value)
		}
	}
	sort.Slice(result, func(i, j int) bool { return result[i] > result[j] })
	return result
}

// bilibiliExtractCandidates 展开 stream→format→codec→url_info，mcdn 线路排到最后。
func bilibiliExtractCandidates(play map[string]interface{}) []LiveStreamCandidate {
	streams, ok := play["stream"].([]interface{})
	if !ok {
		return nil
	}
	var raw []LiveStreamCandidate
	for _, rawStream := range streams {
		stream, ok := rawStream.(map[string]interface{})
		if !ok {
			continue
		}
		formats, ok := stream["format"].([]interface{})
		if !ok {
			continue
		}
		for _, rawFormat := range formats {
			format, ok := rawFormat.(map[string]interface{})
			if !ok {
				continue
			}
			codecs, ok := format["codec"].([]interface{})
			if !ok {
				continue
			}
			for _, rawCodec := range codecs {
				codec, ok := rawCodec.(map[string]interface{})
				if !ok {
					continue
				}
				base := Text(codec["base_url"], "")
				infos, ok := codec["url_info"].([]interface{})
				if !ok {
					continue
				}
				for _, rawInfo := range infos {
					info, ok := rawInfo.(map[string]interface{})
					if !ok {
						continue
					}
					target := strings.TrimSuffix(Text(info["host"], ""), "/") + base + Text(info["extra"], "")
					if target == "" {
						continue
					}
					raw = append(raw, LiveStreamCandidate{
						CDN:      bilibiliHostname(target),
						URL:      target,
						Protocol: InferProtocol(target),
					})
				}
			}
		}
	}
	var normal, delayed []LiveStreamCandidate
	for _, item := range raw {
		if strings.Contains(strings.ToLower(item.URL), "mcdn") {
			delayed = append(delayed, item)
		} else {
			normal = append(normal, item)
		}
	}
	merged := append(append([]LiveStreamCandidate{}, normal...), delayed...)
	return UniqueCandidates(merged)
}

// signedGet 先走 WBI 签名；任何解析类失败回退裸参（保持匿名请求可用）。
func (r *bilibiliResolver) signedGet(rawURL string, params url.Values) (map[string]interface{}, error) {
	query, signErr := r.wbiSignedParams(params)
	if signErr == nil {
		payload, err := r.getJSON(rawURL, query)
		if err == nil {
			return payload, nil
		}
		var resolverErr *ResolverError
		if errors.As(err, &resolverErr) {
			return r.getJSON(rawURL, params)
		}
		return nil, err
	}
	return r.getJSON(rawURL, params)
}

// wbiSignedParams 计算 wts/w_rid：img/sub 键取 wbi_img URL 文件名，mixin 表重排
// 取 32 位，参数按 key 排序、值去掉 [!'()*] 后以 Python quote(safe='~') 编码。
func (r *bilibiliResolver) wbiSignedParams(params url.Values) (url.Values, error) {
	imgKey, subKey, err := r.getWbiKeys()
	if err != nil {
		return nil, err
	}
	mixin := bilibiliMixinKey(imgKey + subKey)
	result := url.Values{}
	for key, values := range params {
		for _, value := range values {
			result.Add(key, value)
		}
	}
	result.Set("wts", strconv.FormatInt(r.now(), 10))
	keys := make([]string, 0, len(result))
	for key := range result {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	queryParts := make([]string, 0, len(keys)*2)
	for _, key := range keys {
		for _, value := range result[key] {
			queryParts = append(queryParts, key+"="+bilibiliQuote(bilibiliCleanWBIValue(value)))
		}
	}
	query := strings.Join(queryParts, "&")
	digest := md5.Sum([]byte(query + mixin))
	result.Set("w_rid", hex.EncodeToString(digest[:]))
	return result, nil
}

func bilibiliMixinKey(combined string) string {
	var builder strings.Builder
	for _, index := range bilibiliMixinKeyTable {
		if index < len(combined) {
			builder.WriteByte(combined[index])
		}
	}
	result := builder.String()
	if len(result) > 32 {
		result = result[:32]
	}
	return result
}

// bilibiliQuote 等价 urllib.parse.quote(value, safe='~')：
// 保留字母数字与 _.~-，空格编码为 %20（区别于 url.QueryEscape 的 +）。
func bilibiliQuote(value string) string {
	var builder strings.Builder
	for i := 0; i < len(value); i++ {
		c := value[i]
		if 'A' <= c && c <= 'Z' || 'a' <= c && c <= 'z' || '0' <= c && c <= '9' ||
			c == '_' || c == '.' || c == '-' || c == '~' {
			builder.WriteByte(c)
			continue
		}
		builder.WriteString(fmt.Sprintf("%%%02X", c))
	}
	return builder.String()
}

func bilibiliCleanWBIValue(value string) string {
	return strings.Map(func(r rune) rune {
		switch r {
		case '!', '\'', '(', ')', '*':
			return -1
		}
		return r
	}, value)
}

func bilibiliAnchorName(roomInfo map[string]interface{}) string {
	anchor := jsonObject(roomInfo["anchor_info"])
	if anchor == nil {
		return ""
	}
	if base := jsonObject(anchor["base_info"]); base != nil {
		return Text(bilibiliFirstTruthy(base["uname"], base["username"], base["uid"]), "")
	}
	return Text(bilibiliFirstTruthy(anchor["uname"], anchor["username"]), "")
}

func (r *bilibiliResolver) getWbiKeys() (string, string, error) {
	if r.wbiKeysLoaded {
		return r.wbiKeys[0], r.wbiKeys[1], nil
	}
	payload, err := r.getJSON("https://api.bilibili.com/x/web-interface/nav", nil)
	if err != nil {
		return "", "", err
	}
	wbiImg := jsonObject(jsonObject(payload["data"])["wbi_img"])
	if wbiImg == nil {
		return "", "", newError("B站 WBI 密钥获取失败")
	}
	rawImg, hasImg := wbiImg["img_url"]
	rawSub, hasSub := wbiImg["sub_url"]
	if !hasImg || !hasSub {
		return "", "", newError("B站 WBI 密钥获取失败")
	}
	imgKey := bilibiliWbiKey(Text(rawImg, ""))
	subKey := bilibiliWbiKey(Text(rawSub, ""))
	if imgKey == "" || subKey == "" {
		return "", "", newError("B站 WBI 密钥为空")
	}
	r.wbiKeys = [2]string{imgKey, subKey}
	r.wbiKeysLoaded = true
	return imgKey, subKey, nil
}

// bilibiliWbiKey 取 URL 末段去掉扩展名（rsplit("/",1)[-1].split(".",1)[0]）。
func bilibiliWbiKey(rawURL string) string {
	last := rawURL
	if index := strings.LastIndex(rawURL, "/"); index >= 0 {
		last = rawURL[index+1:]
	}
	if dot := strings.Index(last, "."); dot >= 0 {
		last = last[:dot]
	}
	return Text(last, "")
}

// bilibiliSplitCategoryID 先按 ":" 再按 "," 切分（parent:area）。
func bilibiliSplitCategoryID(categoryID string) (string, string, error) {
	values := strings.SplitN(categoryID, ":", 2)
	if len(values) == 2 && strings.TrimSpace(values[0]) != "" && strings.TrimSpace(values[1]) != "" {
		return strings.TrimSpace(values[0]), strings.TrimSpace(values[1]), nil
	}
	parts := strings.SplitN(categoryID, ",", 2)
	if len(parts) == 2 && strings.TrimSpace(parts[0]) != "" && strings.TrimSpace(parts[1]) != "" {
		return strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1]), nil
	}
	return "", "", newError("B站分类 ID 格式无效")
}

func (r *bilibiliResolver) parseRoomID(value string) (string, error) {
	raw := TextStrict(value)
	parsed, parseErr := url.Parse(raw)
	if parseErr == nil && parsed.Host != "" {
		host := strings.SplitN(strings.ToLower(parsed.Host), ":", 2)[0]
		if host != "live.bilibili.com" && host != "b23.tv" {
			return "", newError("直播间链接不属于 B 站")
		}
		raw = ""
		for _, part := range strings.Split(parsed.Path, "/") {
			if part != "" {
				raw = part
				break
			}
		}
	}
	if raw == "" || !bilibiliRoomIDPattern.MatchString(raw) {
		return "", newError("B站房间号必须为数字或直播间链接")
	}
	return raw, nil
}

func bilibiliHostname(rawURL string) string {
	parsed, err := url.Parse(rawURL)
	if err != nil {
		return "默认"
	}
	if host := parsed.Hostname(); host != "" {
		return host
	}
	return "默认"
}

// bilibiliFirstTruthy 复刻 Python `a or b or c`：首个真值，否则末项原样返回。
func bilibiliFirstTruthy(values ...interface{}) interface{} {
	for _, value := range values {
		if bilibiliTruthy(value) {
			return value
		}
	}
	if len(values) > 0 {
		return values[len(values)-1]
	}
	return nil
}

func bilibiliTruthy(value interface{}) bool {
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
