package platformlive

// 虎牙直播解析：网页房间信息、anti-code 和 TUP3 CDN token。
// 对应 Python app/src/main/python/huya_resolver.py 的移植，包含：
// - 手写的 TARS/TUP 二进制编解码（wup.huya.com getCdnTokenInfoEx）；
// - mobile 页房间信息中 HNF_GLOBAL_INIT 的平衡 JSON 扫描；
// - anti-code 签名构造（md5(wsSecret)）与桌面/移动端双候选。

import (
	"crypto/md5"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/rand"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"
)

const huyaUserAgent = "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36 Edg/117.0.0.0"

const huyaHySDKUA = "HYSDK(Windows, 30000002)_APP(pc_exe&7060000&official)_SDK(trans&2.32.3.5646)"

var huyaParents = [][2]string{{"1", "网游"}, {"2", "单机"}, {"3", "手游"}, {"8", "娱乐"}}

var (
	huyaGlobalInitMarker = regexp.MustCompile(`window\.HNF_GLOBAL_INIT\s*=`)
	huyaRoomLinkPattern  = regexp.MustCompile(`(?:www|m)\.huya\.com/(\w+)`)
	huyaChannelIDPattern = regexp.MustCompile(`lChannelId":([0-9]+)`)
	huyaSubIDPattern     = regexp.MustCompile(`lSubChannelId":([0-9]+)`)
	huyaFunctionJunk     = regexp.MustCompile(`function.*?\(.*?\)\.\{[\s\S]*?\}`)
	huyaRoomIDPattern    = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
)

type huyaResolver struct {
	session *httpSession
	cookie  string
}

func newHuyaResolver(timeoutSeconds int64, cookie string) *huyaResolver {
	session := newHTTPSession(int(timeoutSeconds), huyaUserAgent, true)
	return &huyaResolver{session: session, cookie: strings.TrimSpace(cookie)}
}

func (r *huyaResolver) SiteID() string      { return "huya" }
func (r *huyaResolver) SiteName() string    { return "虎牙" }
func (r *huyaResolver) Description() string { return "分类直播、反盗链解析与多线路播放" }

func (r *huyaResolver) headers(mobile bool) map[string]string {
	headers := map[string]string{
		"User-Agent": huyaUserAgent,
		"Referer":    "https://m.huya.com/",
	}
	if !mobile {
		headers["User-Agent"] = huyaHySDKUA
		headers["Referer"] = "https://www.huya.com/"
	}
	if r.cookie != "" {
		headers["Cookie"] = r.cookie
	}
	return headers
}

func (r *huyaResolver) getJSON(rawURL string, params url.Values, mobile bool) (interface{}, error) {
	body, err := r.session.getWithParams(rawURL, params, r.headers(mobile))
	if err != nil {
		return nil, newError("虎牙请求失败：%s", err.Error())
	}
	payload, err := parseJSONBytes(body)
	if err != nil {
		return nil, newError("虎牙接口返回格式异常")
	}
	// requests 场景下部分接口返回 JSON 字符串（复刻处理）。
	if text, ok := payload.(string); ok {
		var nested interface{}
		if err := json.Unmarshal([]byte(text), &nested); err != nil {
			return nil, newError("虎牙接口 JSON 字符串无效")
		}
		payload = nested
	}
	return payload, nil
}

func (r *huyaResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	var parents []LiveParentCategory
	var categories []LiveCategory
	for _, parent := range huyaParents {
		parentID := parent[0]
		parentName := parent[1]
		parameters := url.Values{}
		parameters.Set("bussType", parentID)
		payload, err := r.getJSON("https://live.cdn.huya.com/liveconfig/game/bussLive", parameters, false)
		if err != nil {
			return nil, nil, err
		}
		root := jsonObject(payload)
		if root == nil {
			continue
		}
		for _, raw := range jsonArray(root["data"]) {
			item, ok := raw.(map[string]interface{})
			if !ok {
				continue
			}
			categoryID := huyaGameID(item["gid"])
			name := Text(item["gameFullName"], "")
			if categoryID == "" || name == "" {
				continue
			}
			categories = append(categories, LiveCategory{
				ID:         categoryID,
				Name:       name,
				ParentID:   parentID,
				ParentName: parentName,
				Cover:      fmt.Sprintf("https://huyaimg.msstatic.com/cdnimage/game/%s-MS.jpg", categoryID),
			})
		}
		parents = append(parents, LiveParentCategory{ID: parentID, Name: parentName})
	}
	return parents, categories, nil
}

func (r *huyaResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	normalizedPage := page
	if normalizedPage < 1 {
		normalizedPage = 1
	}
	parameters := url.Values{}
	parameters.Set("m", "LiveList")
	parameters.Set("do", "getLiveListByPage")
	parameters.Set("tagAll", "0")
	parameters.Set("gameId", Text(categoryID, ""))
	parameters.Set("page", strconv.FormatInt(normalizedPage, 10))
	payload, err := r.getJSON("https://www.huya.com/cache.php", parameters, false)
	if err != nil {
		return LiveRoomsPage{}, err
	}
	var values []interface{}
	pageCount := normalizedPage
	if root := jsonObject(payload); root != nil {
		if data := jsonObject(root["data"]); data != nil {
			values = jsonArray(data["datas"])
			if raw, valid := ToInt(data["totalPage"]); valid && raw > 0 {
				pageCount = raw
			}
		}
	}
	rooms := []LiveRoom{}
	for _, raw := range values {
		item, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		roomID := Text(item["profileRoom"], "")
		if roomID == "" {
			continue
		}
		defaultTitle := Text(item["introduction"], fmt.Sprintf("虎牙直播 %s", roomID))
		rooms = append(rooms, LiveRoom{
			RoomID:       roomID,
			Title:        defaultTitle,
			Anchor:       Text(item["nick"], ""),
			Cover:        Text(item["screenshot"], ""),
			Online:       PositiveInt(item["totalCount"], 0),
			CategoryID:   Text(categoryID, ""),
			CategoryName: "",
		})
	}
	return LiveRoomsPage{Rooms: rooms, PageCount: max(normalizedPage, pageCount)}, nil
}

func (r *huyaResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	roomID, err := r.parseRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	html, roomInfo, err := r.getRoomInfo(roomID)
	if err != nil {
		return nil, err
	}
	root := jsonObject(roomInfo["roomInfo"])
	if root == nil {
		return nil, newError("虎牙未返回有效的房间信息")
	}
	liveInfo := jsonObject(root["tLiveInfo"])
	if liveInfo == nil {
		liveInfo = map[string]interface{}{}
	}
	profileInfo := jsonObject(root["tProfileInfo"])
	if profileInfo == nil {
		profileInfo = map[string]interface{}{}
	}
	liveStatus, _ := ToInt(root["eLiveStatus"])
	if liveStatus != 2 {
		return nil, newError("房间未开播：%s", Text(liveInfo["sIntroduction"], roomID))
	}
	streamInfo := jsonObject(root["tLiveStreamInfo"])
	if streamInfo == nil {
		// 虎牙页面改版后 tLiveStreamInfo 嵌套在 tLiveInfo 内部（TARS
		// LiveInfo 结构成员），旧位置仅保留在部分页面版本中。
		streamInfo = jsonObject(liveInfo["tLiveStreamInfo"])
	}
	rawLines := jsonArray(nil)
	rawRates := jsonArray(nil)
	if streamInfo != nil {
		rawLines = jsonArray(jsonObject(streamInfo["vStreamInfo"])["value"])
		rawRates = jsonArray(jsonObject(streamInfo["vBitRateInfo"])["value"])
	}
	topSideID := positiveFromHTML(html, huyaChannelIDPattern)
	subSideID := positiveFromHTML(html, huyaSubIDPattern)
	presenterUID := topSideID
	if presenterUID == 0 && subSideID != 0 {
		presenterUID = subSideID
	}
	type huyaRate struct {
		bitRate int64
		name    string
	}
	var rates []huyaRate
	for _, raw := range rawRates {
		item, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		displayName := Text(item["sDisplayName"], "")
		if strings.Contains(displayName, "HDR") {
			continue
		}
		rates = append(rates, huyaRate{
			bitRate: PositiveInt(item["iBitRate"], 0),
			name:    Text(item["sDisplayName"], "原画"),
		})
	}
	if len(rates) == 0 {
		rates = []huyaRate{{bitRate: 0, name: "原画"}}
	}
	sort.SliceStable(rates, func(i, j int) bool { return rates[i].bitRate > rates[j].bitRate })
	rate, quality := rates[0].bitRate, rates[0].name

	var candidates []LiveStreamCandidate
	for _, raw := range rawLines {
		line, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		baseURL := Text(line["sFlvUrl"], "")
		baseURL = strings.TrimSuffix(baseURL, "/")
		streamName := Text(line["sStreamName"], "")
		antiCode := Text(line["sFlvAntiCode"], "")
		if baseURL == "" || streamName == "" || antiCode == "" {
			continue
		}
		token, tokenErr := r.getCDNToken(streamName)
		if tokenErr != nil {
			// 某些网络环境会拦截 wup.huya.com 的 TARS POST；页面
			// 自带的 anti-code 仍可生成可用候选，作为保守回退。
			token = antiCode
		}
		signed, signedErr := huyaBuildAntiCode(streamName, presenterUID, token)
		if signedErr != nil {
			continue
		}
		resolvedURL := fmt.Sprintf("%s/%s.flv?%s", baseURL, streamName, signed)
		if rate > 0 {
			resolvedURL += fmt.Sprintf("&ratio=%d", rate)
		}
		candidates = append(candidates, LiveStreamCandidate{
			CDN:      Text(line["sCdnType"], "默认"),
			URL:      resolvedURL,
			Protocol: "flv",
		})
		mobileSigned := strings.ReplaceAll(signed, "ctype=huya_pc_exe", "ctype=tars_mobile")
		mobileSigned = strings.ReplaceAll(mobileSigned, "t=0", "t=103")
		mobileURL := fmt.Sprintf("%s/%s.flv?%s", baseURL, streamName, mobileSigned)
		if rate > 0 {
			mobileURL += fmt.Sprintf("&ratio=%d", rate)
		}
		candidates = append(candidates, LiveStreamCandidate{
			CDN:      "移动端",
			URL:      mobileURL,
			Protocol: "flv",
		})
	}
	candidates = UniqueCandidates(candidates)
	if len(candidates) == 0 {
		return nil, newError("虎牙未返回可播放地址")
	}
	title := Text(liveInfo["sIntroduction"], Text(liveInfo["sRoomName"], fmt.Sprintf("虎牙直播 %s", roomID)))
	return &ResolvedLiveStream{
		RoomID:     Text(liveInfo["lProfileRoom"], roomID),
		Title:      title,
		Anchor:     Text(profileInfo["sNick"], ""),
		Quality:    quality,
		Candidates: candidates,
		Headers: map[string]string{
			"Referer":    fmt.Sprintf("https://m.huya.com/%s", roomID),
			"Origin":     "https://m.huya.com",
			"User-Agent": huyaUserAgent,
		},
	}, nil
}

func (r *huyaResolver) parseRoomID(value string) (string, error) {
	raw := Text(value, "")
	if match := huyaRoomLinkPattern.FindStringSubmatch(raw); match != nil {
		raw = match[1]
	}
	if raw == "" || !huyaRoomIDPattern.MatchString(raw) {
		return "", newError("虎牙房间号格式无效")
	}
	return raw, nil
}

// NormalizeRoomID 对齐 Adapter 接口，与 bilibili 实现一致复用 parseRoomID。
func (r *huyaResolver) NormalizeRoomID(value string) (string, error) {
	return r.parseRoomID(value)
}

// getRoomInfo 请求 m.huya.com 页面并提取 window.HNF_GLOBAL_INIT 的 JSON 载荷。
func (r *huyaResolver) getRoomInfo(roomID string) (string, map[string]interface{}, error) {
	target := fmt.Sprintf("https://m.huya.com/%s", roomID)
	body, err := r.session.get(target, r.headers(true))
	if err != nil {
		return "", nil, newError("虎牙房间请求失败：%s", err.Error())
	}
	html := string(body)
	raw, payload, err := huyaParseRoomInfoPayload(html)
	if err != nil {
		return "", nil, err
	}
	return raw, payload, nil
}

// huyaParseRoomInfoPayload 从虎牙房间 HTML 提取 HNF_GLOBAL_INIT JSON
// （供 getRoomInfo 与单元测试复用）。
func huyaParseRoomInfoPayload(html string) (string, map[string]interface{}, error) {
	marker := huyaGlobalInitMarker.FindStringIndex(html)
	if marker == nil {
		return "", nil, newError("虎牙房间页面结构已变化")
	}
	raw, ok := extractJSONValue(html, marker[1])
	if !ok {
		return "", nil, newError("虎牙房间信息 JSON 无效")
	}
	raw = huyaFunctionJunk.ReplaceAllString(raw, `""`)
	if !json.Valid([]byte(raw)) {
		return "", nil, newError("虎牙房间信息 JSON 无效")
	}
	var payload map[string]interface{}
	if err := json.Unmarshal([]byte(raw), &payload); err != nil {
		return "", nil, newError("虎牙房间信息 JSON 无效")
	}
	return html, payload, nil
}

// getCDNToken 通过 wup.huya.com 的 TUP/TARS 协议获取 CDN token。
func (r *huyaResolver) getCDNToken(streamName string) (string, error) {
	requestBody := huyaEncodeTupRequest(streamName)
	response, err := r.session.postRaw("https://wup.huya.com", requestBody, map[string]string{
		"Content-Type": "application/x-wup",
		"User-Agent":   huyaHySDKUA,
		"Origin":       "https://m.huya.com/",
		"Referer":      "https://m.huya.com/",
	})
	if err != nil {
		return "", newError("虎牙 CDN token 请求失败：%s", err.Error())
	}
	token, decodeErr := huyaDecodeTupResponse(response)
	if decodeErr != nil {
		return "", newError("虎牙 TARS token 响应解析失败")
	}
	if token == "" {
		return "", newError("虎牙未返回 CDN token")
	}
	return token, nil
}

func positiveFromHTML(html string, pattern *regexp.Regexp) int64 {
	match := pattern.FindStringSubmatch(html)
	if match == nil {
		return 0
	}
	return PositiveInt(match[1], 0)
}

// huyaGameID 复刻 _game_id：gid 期望整数（bussLive 返回 float 如 862.0）。
func huyaGameID(value interface{}) string {
	switch raw := value.(type) {
	case map[string]interface{}:
		return huyaGameID(raw["value"])
	default:
		text := Text(value, "")
		parts := strings.SplitN(text, ",", 2)
		result := strings.TrimSpace(parts[0])
		if strings.HasSuffix(result, ".0") {
			result = strings.TrimSuffix(result, ".0")
		}
		return result
	}
}

// huyaBuildAntiCode 复刻 _build_anti_code：基于 anti-code 参数生成 wsSecret。
func huyaBuildAntiCode(stream string, presenterUID int64, antiCode string) (string, error) {
	values, err := url.ParseQuery(antiCode)
	if err != nil {
		return "", newError("虎牙 anti-code 缺少必要参数")
	}
	fmValues := values["fm"]
	wsTimeValues := values["wsTime"]
	if len(fmValues) == 0 || len(wsTimeValues) == 0 {
		return "", newError("虎牙 anti-code 缺少必要参数")
	}
	ctype := "huya_pc_exe"
	if list := values["ctype"]; len(list) > 0 && list[0] != "" {
		ctype = list[0]
	} else if len(list) == 0 {
		// parse_qs keep_blank_values=true 下空值也会作为空串保留。
		ctype = "huya_pc_exe"
	}
	platformID := int64(0)
	if list := values["t"]; len(list) > 0 {
		platformID = PositiveInt(list[0], 0)
	}
	isWap := platformID == 103
	now := time.Now().UnixMilli()
	seqID := presenterUID + now
	convertUID := rotl64(uint64(presenterUID))
	calcUID := convertUID
	if isWap {
		calcUID = uint64(presenterUID)
	}
	secretPrefix, err := huyaSecretPrefix(fmValues[0])
	if err != nil {
		return "", newError("虎牙 anti-code fm 无效")
	}
	wsTime := wsTimeValues[0]
	secretHash := md5Hex(fmt.Sprintf("%d|%s|%d", seqID, ctype, platformID))
	wsSecret := md5Hex(fmt.Sprintf("%s_%d_%s_%s_%s", secretPrefix, calcUID, stream, secretHash, wsTime))
	wsTimeHex, err := strconv.ParseInt(wsTime, 16, 64)
	if err != nil {
		return "", newError("虎牙 anti-code 缺少必要参数")
	}
	ct := int64((float64(wsTimeHex) + randInt()) * 1000)
	uuidValue := int64((float64(ct%10_000_000_000) + randInt()) * 1000) % 0xFFFFFFFF
	var result []string
	result = append(result,
		fmt.Sprintf("wsSecret=%s", wsSecret),
		fmt.Sprintf("wsTime=%s", wsTime),
		fmt.Sprintf("seqid=%d", seqID),
		fmt.Sprintf("ctype=%s", ctype),
		"ver=1",
		fmt.Sprintf("fs=%s", firstValue(values, "fs")),
		fmt.Sprintf("fm=%s", fmValues[0]),
		fmt.Sprintf("t=%d", platformID),
	)
	if isWap {
		result = append(result,
			fmt.Sprintf("uid=%d", presenterUID),
			fmt.Sprintf("uuid=%d", uuidValue),
		)
	} else {
		result = append(result, fmt.Sprintf("u=%d", convertUID))
	}
	return strings.Join(result, "&"), nil
}

func firstValue(values url.Values, key string) string {
	if list := values[key]; len(list) > 0 {
		return list[0]
	}
	return ""
}

func randInt() float64 { return rand.Float64() }

func huyaSecretPrefix(encodedFM string) (string, error) {
	decoded, err := url.PathUnescape(encodedFM)
	if err != nil {
		decoded = encodedFM
	}
	data, err := base64.StdEncoding.DecodeString(decoded)
	if err != nil {
		return "", newError("虎牙 anti-code fm 无效")
	}
	text := string(data)
	parts := strings.SplitN(text, "_", 2)
	return parts[0], nil
}

func md5Hex(value string) string {
	digest := md5.Sum([]byte(value))
	return hex.EncodeToString(digest[:])
}

func rotl64(value uint64) uint64 {
	return value<<1 | value>>63
}

// ---------------------------------------------------------------------------
// TARS/TUP 二进制编解码（对齐 _encode_head/_TarsReader 等辅助实现）。
// ---------------------------------------------------------------------------

func tarsEncodeHead(typeID byte, tag byte) []byte {
	if tag < 15 {
		return []byte{typeID<<4 | tag}
	}
	return []byte{typeID<<4 | 0x0F, tag}
}

func tarsEncodeInt(value int64, tag byte) []byte {
	if value == 0 {
		return tarsEncodeHead(12, tag)
	}
	if value >= -128 && value <= 127 {
		return append(tarsEncodeHead(0, tag), byte(value))
	}
	if value >= -32768 && value <= 32767 {
		body := make([]byte, 2)
		binary.BigEndian.PutUint16(body, uint16(int16(value)))
		return append(tarsEncodeHead(1, tag), body...)
	}
	if value >= -2147483648 && value <= 2147483647 {
		body := make([]byte, 4)
		binary.BigEndian.PutUint32(body, uint32(int32(value)))
		return append(tarsEncodeHead(2, tag), body...)
	}
	body := make([]byte, 8)
	binary.BigEndian.PutUint64(body, uint64(value))
	return append(tarsEncodeHead(3, tag), body...)
}

func tarsEncodeString(value string, tag byte) []byte {
	data := []byte(value)
	if len(data) <= 255 {
		return append(append(tarsEncodeHead(6, tag), byte(len(data))), data...)
	}
	body := make([]byte, 4)
	binary.BigEndian.PutUint32(body, uint32(len(data)))
	return append(append(tarsEncodeHead(7, tag), body...), data...)
}

func tarsEncodeBytes(value []byte, tag byte) []byte {
	result := tarsEncodeHead(13, tag)
	result = append(result, tarsEncodeHead(0, 0)...)
	result = append(result, tarsEncodeInt(int64(len(value)), 1)...)
	return append(result, value...)
}

func tarsEncodeStruct(body []byte, tag byte) []byte {
	result := tarsEncodeHead(10, tag)
	result = append(result, body...)
	return append(result, tarsEncodeHead(11, 0)...)
}

func tarsEncodeMapBytes(values map[string][]byte, tag byte) []byte {
	body := tarsEncodeInt(int64(len(values)), 0)
	for key, value := range values {
		body = append(body, tarsEncodeString(key, 0)...)
		body = append(body, tarsEncodeBytes(value, 1)...)
	}
	return append(tarsEncodeHead(8, tag), body...)
}

func tarsEncodeMapStrings(values map[string]string, tag byte) []byte {
	body := tarsEncodeInt(int64(len(values)), 0)
	for key, value := range values {
		body = append(body, tarsEncodeString(key, 0)...)
		body = append(body, tarsEncodeString(value, 1)...)
	}
	return append(tarsEncodeHead(8, tag), body...)
}

func tarsEncodeUserID() []byte {
	body := []byte{}
	body = append(body, tarsEncodeInt(0, 0)...)
	body = append(body, tarsEncodeString("", 1)...)
	body = append(body, tarsEncodeString("", 2)...)
	body = append(body, tarsEncodeString("pc_exe&7060000&official", 3)...)
	body = append(body, tarsEncodeString("", 4)...)
	body = append(body, tarsEncodeInt(0, 5)...)
	body = append(body, tarsEncodeString("", 6)...)
	body = append(body, tarsEncodeString("", 7)...)
	return tarsEncodeStruct(body, 3)
}

func huyaEncodeTupRequest(streamName string) []byte {
	requestBody := []byte{}
	requestBody = append(requestBody, tarsEncodeString("", 0)...)
	requestBody = append(requestBody, tarsEncodeString(streamName, 1)...)
	requestBody = append(requestBody, tarsEncodeInt(0, 2)...)
	requestBody = append(requestBody, tarsEncodeUserID()...)
	requestBody = append(requestBody, tarsEncodeInt(66, 4)...)
	encodedRequest := tarsEncodeStruct(requestBody, 0)
	newData := tarsEncodeMapBytes(map[string][]byte{"tReq": encodedRequest}, 0)
	packet := []byte{}
	packet = append(packet, tarsEncodeInt(3, 1)...)
	packet = append(packet, tarsEncodeInt(0, 2)...)
	packet = append(packet, tarsEncodeInt(0, 3)...)
	packet = append(packet, tarsEncodeInt(0, 4)...)
	packet = append(packet, tarsEncodeString("liveui", 5)...)
	packet = append(packet, tarsEncodeString("getCdnTokenInfoEx", 6)...)
	packet = append(packet, tarsEncodeBytes(newData, 7)...)
	packet = append(packet, tarsEncodeInt(0, 8)...)
	packet = append(packet, tarsEncodeMapStrings(nil, 9)...)
	packet = append(packet, tarsEncodeMapStrings(nil, 10)...)
	frame := make([]byte, 4)
	binary.BigEndian.PutUint32(frame, uint32(len(packet)+4))
	return append(frame, packet...)
}

// tarsReader 复刻 _TarsReader 的游标行为。
type tarsReader struct {
	data []byte
	pos  int
	err  error
}

func newTarsReader(data []byte) *tarsReader { return &tarsReader{data: data} }

func (r *tarsReader) done() bool { return r.err != nil || r.pos >= len(r.data) }

func (r *tarsReader) head() (byte, byte) {
	if r.err != nil {
		return 0, 0
	}
	if r.pos >= len(r.data) {
		r.err = fmt.Errorf("TARS head missing")
		return 0, 0
	}
	value := r.data[r.pos]
	r.pos++
	typeID, tag := value>>4, value&0x0F
	if tag == 15 {
		if r.pos >= len(r.data) {
			r.err = fmt.Errorf("TARS head missing")
			return 0, 0
		}
		tag = r.data[r.pos]
		r.pos++
	}
	return typeID, tag
}

func (r *tarsReader) skip(typeID byte) {
	if r.err != nil {
		return
	}
	switch typeID {
	case 11, 12:
	case 0:
		r.pos++
	case 1:
		r.pos += 2
	case 2:
		r.pos += 4
	case 3:
		r.pos += 8
	case 6:
		if r.pos >= len(r.data) {
			r.err = fmt.Errorf("TARS data truncated")
			return
		}
		r.pos += int(r.data[r.pos]) + 1
	case 7:
		if r.pos+4 > len(r.data) {
			r.err = fmt.Errorf("TARS data truncated")
			return
		}
		length := int(binary.BigEndian.Uint32(r.data[r.pos : r.pos+4]))
		r.pos += 4 + length
	case 13:
		// 编码侧为 head(13,tag) + head(0,0) + encode_int(len,1) + data，
		// 解析时同样需要跳过 subtype 标记并按长度标记的类型读取。
		_, _ = r.head()
		lengthType, _ := r.head()
		length := r.intValue(lengthType)
		if r.err != nil {
			return
		}
		r.pos += int(length)
	case 8:
		innerType, _ := r.head()
		size := r.intValue(innerType)
		for i := int64(0); i < size*2; i++ {
			childType, _ := r.head()
			r.skip(childType)
		}
	case 9:
		innerType, _ := r.head()
		size := r.intValue(innerType)
		for i := int64(0); i < size; i++ {
			childType, _ := r.head()
			r.skip(childType)
		}
	case 10:
		for {
			childType, _ := r.head()
			if childType == 11 {
				break
			}
			r.skip(childType)
		}
	default:
		r.err = fmt.Errorf("unsupported TARS type %d", typeID)
	}
}

func (r *tarsReader) intValue(typeID byte) int64 {
	if r.err != nil {
		return 0
	}
	if typeID == 12 {
		return 0
	}
	need := map[byte]int{0: 1, 1: 2, 2: 4, 3: 8}[typeID]
	if need == 0 || r.pos+need > len(r.data) {
		r.err = fmt.Errorf("TARS int truncated")
		return 0
	}
	// 先在当前位置读取整数值，再推进游标；否则调用方
	// （bytesValue/mapBytes/skip）会把整数字节再次当作数据读取。
	var value int64
	switch need {
	case 1:
		value = int64(int8(r.data[r.pos]))
	case 2:
		value = int64(int16(binary.BigEndian.Uint16(r.data[r.pos:])))
	case 4:
		value = int64(int32(binary.BigEndian.Uint32(r.data[r.pos:])))
	default:
		value = int64(binary.BigEndian.Uint64(r.data[r.pos:]))
	}
	r.pos += need
	return value
}

func (r *tarsReader) stringValue(typeID byte) string {
	if r.err != nil {
		return ""
	}
	length := 0
	switch typeID {
	case 6:
		if r.pos >= len(r.data) {
			r.err = fmt.Errorf("TARS string truncated")
			return ""
		}
		length = int(r.data[r.pos])
		r.pos++
	case 7:
		if r.pos+4 > len(r.data) {
			r.err = fmt.Errorf("TARS string truncated")
			return ""
		}
		length = int(int32(binary.BigEndian.Uint32(r.data[r.pos:])))
		r.pos += 4
	default:
		r.err = fmt.Errorf("not a TARS string")
		return ""
	}
	if length < 0 || r.pos+length > len(r.data) {
		r.err = fmt.Errorf("TARS string truncated")
		return ""
	}
	value := r.data[r.pos : r.pos+length]
	r.pos += length
	return string(value)
}

func (r *tarsReader) firstString(typeID byte) string {
	if r.err != nil {
		return ""
	}
	switch typeID {
	case 6, 7:
		return r.stringValue(typeID)
	case 10:
		for {
			if r.err != nil {
				return ""
			}
			childType, childTag := r.head()
			if childType == 11 {
				return ""
			}
			if childTag == 0 && (childType == 6 || childType == 7) {
				return r.stringValue(childType)
			}
			r.skip(childType)
		}
	default:
		r.err = fmt.Errorf("not a TARS string or struct")
		return ""
	}
}

func (r *tarsReader) bytesValue(typeID byte) []byte {
	if typeID != 13 {
		r.err = fmt.Errorf("not a TARS simple list")
		return nil
	}
	subType, _ := r.head()
	if subType != 0 {
		r.err = fmt.Errorf("TARS simple list is not byte array")
		return nil
	}
	lengthType, _ := r.head()
	length := r.intValue(lengthType)
	if length < 0 || r.pos+int(length) > len(r.data) {
		r.err = fmt.Errorf("TARS bytes truncated")
		return nil
	}
	value := append([]byte(nil), r.data[r.pos:r.pos+int(length)]...)
	r.pos += int(length)
	return value
}

func (r *tarsReader) mapBytes() map[string][]byte {
	typeID, _ := r.head()
	if typeID != 8 {
		r.err = fmt.Errorf("not a TARS map")
		return nil
	}
	sizeType, _ := r.head()
	size := r.intValue(sizeType)
	result := map[string][]byte{}
	for i := int64(0); i < size; i++ {
		if r.err != nil {
			return result
		}
		keyType, _ := r.head()
		key := r.stringValue(keyType)
		valueType, _ := r.head()
		result[key] = r.bytesValue(valueType)
	}
	return result
}

// huyaDecodeTupResponse 从 TUP 响应包中提取 token 字符串（tag 0）。
func huyaDecodeTupResponse(data []byte) (string, error) {
	if len(data) < 4 {
		return "", fmt.Errorf("TUP packet too short")
	}
	reader := newTarsReader(data[4:])
	var buffer []byte
	for !reader.done() {
		typeID, tag := reader.head()
		if reader.err != nil {
			break
		}
		if tag == 7 {
			buffer = reader.bytesValue(typeID)
		} else {
			reader.skip(typeID)
		}
	}
	if reader.err != nil {
		return "", fmt.Errorf("TUP response has no payload")
	}
	if len(buffer) == 0 {
		return "", fmt.Errorf("TUP response has no payload")
	}
	inner := newTarsReader(buffer)
	values := inner.mapBytes()
	response := newTarsReader(values["tRsp"])
	token := ""
	for !response.done() {
		typeID, tag := response.head()
		if response.err != nil {
			break
		}
		if tag == 0 && typeID != 11 {
			token = response.firstString(typeID)
		} else {
			response.skip(typeID)
		}
	}
	if response.err != nil {
		return "", fmt.Errorf("TARS string decode failed")
	}
	return token, nil
}
