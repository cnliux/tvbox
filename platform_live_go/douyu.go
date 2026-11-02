package platformlive

// 斗鱼直播解析：稳定房间号转换为带防盗链参数的临时播放地址。
//
// 对应 Python app/src/main/python/douyu_resolver.py 的移植：
// - 房间详情 https://www.douyu.com/betard/{rid}；
// - 签名参数来自 swf_api/homeH5Enc 下发的混淆 JS（正则改写后由 goja 执行）；
// - getH5Play 按 CDN 顺序取同一清晰度下的所有线路。

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
	"time"
)

const douyuUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

const douyuMobileUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) " +
	"AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1"

const douyuDeviceID = "10000000000000000000000000001501"

var (
	douyuServerScriptPattern = regexp.MustCompile(`(vdwdae325w_64we[\s\S]*?function ub98484234[\s\S]*?)function`)
	douyuEvalPattern         = regexp.MustCompile(`eval.*?;\}`)
	douyuVersionPattern      = regexp.MustCompile(`v=(\d+)`)
	douyuReturnRT            = regexp.MustCompile(`return rt;\}\);?`)
	douyuFunctionHead        = regexp.MustCompile(`\(function\s*\(`)
	douyuCryptoMD5           = regexp.MustCompile(`CryptoJS\.MD5\(cb\)\.toString\(\)`)
	douyuRoomLinkPattern     = regexp.MustCompile(`(?:www|m)\.douyu\.com/(\d+)`)
)

// douyuStreamCandidate 同一清晰度下的 CDN 线路（内部使用，绑定 cdn 信息）。
type douyuStreamCandidate struct {
	cdn      string
	url      string
	protocol string
}

type douyuResolver struct {
	session *httpSession
	// 以下钩子仅用于单元测试注入（等价 Python 测试里的 Mock）。
	playArgsFn   func(roomID string) (map[string]string, error)
	playInfoFn   func(roomID string, playArgs map[string]string, cdn string, rate int64) (map[string]interface{}, error)
}

type douyuAdapter struct{ resolver *douyuResolver }

func newDouyuAdapter(timeoutSeconds int64) *douyuAdapter {
	return &douyuAdapter{resolver: newDouyuResolver(timeoutSeconds)}
}

func (a *douyuAdapter) SiteID() string      { return "douyu" }
func (a *douyuAdapter) SiteName() string    { return "斗鱼" }
func (a *douyuAdapter) Description() string { return "分类直播、房间浏览与多 CDN 播放" }

func (a *douyuAdapter) NormalizeRoomID(value string) (string, error) {
	return parseDouyuRoomID(value)
}

// wrapDouyuFatal 把非业务型错误（网络等普通 error）包装成斗鱼请求失败文案，
// 对齐 Python 服务端 DouyuAdapter 里对 requests.RequestException 的处理。
func wrapDouyuFatal(err error) error {
	if err == nil {
		return nil
	}
	var resolverErr *ResolverError
	if errors.As(err, &resolverErr) {
		return err
	}
	return newError("斗鱼请求失败：%s", err.Error())
}

func (a *douyuAdapter) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	parents, categories, err := a.resolver.getCategoryTree()
	if err != nil {
		return nil, nil, wrapDouyuFatal(err)
	}
	return parents, categories, nil
}

func (a *douyuAdapter) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	result, err := a.resolver.getCategoryRooms(categoryID, page)
	if err != nil {
		return LiveRoomsPage{}, wrapDouyuFatal(err)
	}
	return result, nil
}

func (a *douyuAdapter) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	stream, err := a.resolver.resolve(roomInput)
	if err != nil {
		return nil, wrapDouyuFatal(err)
	}
	candidates := make([]LiveStreamCandidate, 0, len(stream.candidates))
	for _, item := range stream.candidates {
		candidates = append(candidates, LiveStreamCandidate{
			CDN:      item.cdn,
			URL:      item.url,
			Protocol: item.protocol,
		})
	}
	headers := map[string]string{}
	for key, value := range stream.headers {
		headers[key] = value
	}
	return &ResolvedLiveStream{
		RoomID:     stream.roomID,
		Title:      stream.title,
		Anchor:     stream.anchor,
		Quality:    stream.quality,
		Candidates: candidates,
		Headers:    headers,
		IsLoop:     stream.isLoop,
	}, nil
}

type douyuResolved struct {
	roomID     string
	title      string
	anchor     string
	quality    string
	candidates []douyuStreamCandidate
	isLoop     bool
	headers    map[string]string
}

// parseDouyuRoomID 接收房间号或斗鱼页面 URL，返回安全的数字房间号。
func parseDouyuRoomID(value string) (string, error) {
	text := TextStrict(value)
	if match := douyuRoomLinkPattern.FindStringSubmatch(text); match != nil {
		text = match[1]
	}
	if text == "" {
		return "", newError("斗鱼房间号必须是数字或完整的斗鱼直播间链接")
	}
	for _, c := range text {
		if c < '0' || c > '9' {
			return "", newError("斗鱼房间号必须是数字或完整的斗鱼直播间链接")
		}
	}
	return text, nil
}

func newDouyuResolver(timeoutSeconds int64) *douyuResolver {
	return &douyuResolver{session: newHTTPSession(int(timeoutSeconds), douyuUserAgent, false)}
}

func (r *douyuResolver) resolve(roomInput string) (*douyuResolved, error) {
	requestedRoomID, err := parseDouyuRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	detail, err := r.getRoomDetail(requestedRoomID)
	if err != nil {
		return nil, err
	}
	roomID := requestedRoomID
	if raw, ok := detail["room_id"]; ok {
		if id, valid := ToInt(raw); valid && id > 0 {
			roomID = strconv.FormatInt(id, 10)
		}
	}
	showStatus, _ := ToInt(detail["show_status"])
	isLoop := toFloat(detail["videoLoop"]) == 1
	if showStatus != 1 {
		title := Text(detail["room_name"], roomID)
		return nil, newError("房间未开播：%s", title)
	}
	title := Text(detail["room_name"], fmt.Sprintf("斗鱼直播 %s", roomID))
	anchor := Text(detail["owner_name"], "")
	quality, candidates, err := r.getBestPlayUrls(roomID)
	if err != nil {
		return nil, err
	}
	if len(candidates) == 0 {
		return nil, newError("未能获取到可播放的斗鱼直播地址")
	}
	return &douyuResolved{
		roomID:     roomID,
		title:      title,
		anchor:     anchor,
		quality:    quality,
		candidates: candidates,
		isLoop:     isLoop,
		headers: map[string]string{
			"Referer":    fmt.Sprintf("https://m.douyu.com/%s", roomID),
			"Origin":     "https://m.douyu.com",
			"User-Agent": douyuMobileUserAgent,
		},
	}, nil
}

func (r *douyuResolver) getCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	data, err := r.session.getWithParams("https://m.douyu.com/api/cate/list", nil, map[string]string{
		"Referer":    "https://m.douyu.com/",
		"User-Agent": douyuMobileUserAgent,
	})
	if err != nil {
		return nil, nil, newError("斗鱼请求失败：%s", err.Error())
	}
	payload, err := parseJSONBytes(data)
	if err != nil {
		return nil, nil, newError("斗鱼未返回有效的直播分类")
	}
	root := jsonObject(payload)
	if root == nil {
		return nil, nil, newError("斗鱼未返回有效的直播分类")
	}
	nested := jsonObject(root["data"])
	if nested == nil {
		return nil, nil, newError("斗鱼未返回有效的直播分类")
	}

	parentNames := map[string]string{}
	for _, raw := range jsonArray(nested["cate1Info"]) {
		item, ok := raw.(map[string]interface{})
		if !ok || item["cate1Id"] == nil {
			continue
		}
		if name := Text(item["cate1Name"], "其他"); name != "" {
			parentNames[Text(item["cate1Id"], "")] = name
		}
	}

	var parentCategories []LiveParentCategory
	var categories []LiveCategory
	for _, raw := range jsonArray(nested["cate1Info"]) {
		item, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		parentID := Text(item["cate1Id"], "")
		name := Text(item["cate1Name"], "")
		if parentID == "" || name == "" {
			continue
		}
		cover := Text(item["icon"], Text(item["cate1Icon"], ""))
		parentCategories = append(parentCategories, LiveParentCategory{
			ID:    parentID,
			Name:  name,
			Cover: cover,
		})
	}
	for _, raw := range jsonArray(nested["cate2Info"]) {
		item, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		categoryID := Text(item["cate2Id"], "")
		name := Text(item["cate2Name"], "")
		parentID := Text(item["cate1Id"], "")
		if categoryID == "" || name == "" {
			continue
		}
		parentName, exists := parentNames[parentID]
		if !exists {
			parentName = "其他"
		}
		categories = append(categories, LiveCategory{
			ID:         categoryID,
			Name:       name,
			ParentID:   parentID,
			ParentName: parentName,
			Cover:      Text(item["icon"], ""),
		})
	}
	return parentCategories, categories, nil
}

func (r *douyuResolver) getCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	normalizedCategoryID := strings.TrimSpace(categoryID)
	if !isDigits(normalizedCategoryID) {
		return LiveRoomsPage{}, newError("斗鱼分类 ID 必须为数字")
	}
	normalizedPage := page
	if normalizedPage < 1 {
		normalizedPage = 1
	}
	target := fmt.Sprintf("https://www.douyu.com/gapi/rkc/directory/mixList/2_%s/%d", normalizedCategoryID, normalizedPage)
	data, err := r.session.get(target, map[string]string{
		"Referer":    "https://www.douyu.com/directory",
		"User-Agent": douyuUserAgent,
	})
	if err != nil {
		return LiveRoomsPage{}, newError("斗鱼请求失败：%s", err.Error())
	}
	payload, err := parseJSONBytes(data)
	if err != nil {
		return LiveRoomsPage{}, newError("斗鱼未返回有效的分类房间列表")
	}
	root := jsonObject(payload)
	if root == nil {
		return LiveRoomsPage{}, newError("斗鱼未返回有效的分类房间列表")
	}
	nested := jsonObject(root["data"])
	if nested == nil {
		return LiveRoomsPage{}, newError("斗鱼未返回有效的分类房间列表")
	}
	rooms := []LiveRoom{}
	for _, raw := range jsonArray(nested["rl"]) {
		item, ok := raw.(map[string]interface{})
		if !ok || Text(item["type"], "") != "1" {
			continue
		}
		roomID := Text(item["rid"], "")
		if roomID == "" {
			continue
		}
		rooms = append(rooms, LiveRoom{
			RoomID:       roomID,
			Title:        Text(item["rn"], ""),
			Anchor:       Text(item["nn"], ""),
			Cover:        Text(item["rs16"], ""),
			Online:       PositiveInt(item["ol"], 0),
			CategoryID:   normalizedCategoryID,
			CategoryName: Text(item["c2name"], ""),
		})
	}
	pageCount := int64(1)
	if value, valid := ToInt(nested["pgcnt"]); valid && value > 1 {
		pageCount = value
	}
	return LiveRoomsPage{Rooms: rooms, PageCount: pageCount}, nil
}

func isDigits(value string) bool {
	if value == "" {
		return false
	}
	for _, c := range value {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func (r *douyuResolver) getRoomDetail(roomID string) (map[string]interface{}, error) {
	data, err := r.session.get(fmt.Sprintf("https://www.douyu.com/betard/%s", roomID), map[string]string{
		"Referer": fmt.Sprintf("https://www.douyu.com/%s", roomID),
	})
	if err != nil {
		return nil, newError("斗鱼请求失败：%s", err.Error())
	}
	payload, err := parseJSONBytes(data)
	if err != nil {
		return nil, newError("斗鱼未返回有效的房间信息")
	}
	root := jsonObject(payload)
	if root == nil {
		return nil, newError("斗鱼未返回有效的房间信息")
	}
	room := jsonObject(root["room"])
	if room == nil {
		return nil, newError("斗鱼未返回有效的房间信息")
	}
	return room, nil
}

// getBestPlayUrls 按斗鱼返回的 CDN 顺序收集同一清晰度的全部可用线路。
// 签名仅计算一次，随后复用到每个 CDN 的 getH5Play 请求。
func (r *douyuResolver) getBestPlayUrls(roomID string) (string, []douyuStreamCandidate, error) {
	playArgs, err := r.getPlayArgs(roomID)
	if err != nil {
		return "", nil, err
	}
	firstPlayInfo, err := r.getPlayInfo(roomID, playArgs, "", -1)
	if err != nil {
		return "", nil, err
	}
	qualities := parseDouyuQualities(firstPlayInfo)
	cdns := orderDouyuCdns(firstPlayInfo["cdnsWithName"])
	if len(qualities) == 0 {
		qualities = []douyuQuality{{rate: 0, name: "自动"}}
	}
	if len(cdns) == 0 {
		cdns = []string{""}
	}

	for _, quality := range qualities {
		candidates := []douyuStreamCandidate{}
		seen := map[string]bool{}
		for _, cdn := range cdns {
			playInfo, err := r.getPlayInfo(roomID, playArgs, cdn, quality.rate)
			if err != nil {
				// 与 Python 行为一致：仅解析类错误跳过尝试其他 CDN；
				// 网络错误会中断整个解析。
				var resolverErr *ResolverError
				if errors.As(err, &resolverErr) {
					continue
				}
				return "", nil, err
			}
			url := buildDouyuStreamURL(playInfo)
			if url == "" || seen[url] {
				continue
			}
			seen[url] = true
			protocol := "flv"
			if strings.Contains(strings.ToLower(url), ".m3u8") {
				protocol = "hls"
			}
			candidateCDN := cdn
			if candidateCDN == "" {
				candidateCDN = "默认"
			}
			candidates = append(candidates, douyuStreamCandidate{
				cdn:      candidateCDN,
				url:      url,
				protocol: protocol,
			})
		}
		if len(candidates) > 0 {
			return quality.name, candidates, nil
		}
	}
	return "", nil, nil
}

type douyuQuality struct {
	rate int64
	name string
}

func (r *douyuResolver) getPlayInfo(roomID string, playArgs map[string]string, cdn string, rate int64) (map[string]interface{}, error) {
	if r.playInfoFn != nil {
		return r.playInfoFn(roomID, playArgs, cdn, rate)
	}
	form := url.Values{}
	for key, value := range playArgs {
		form.Set(key, value)
	}
	form.Set("cdn", cdn)
	form.Set("rate", strconv.FormatInt(rate, 10))
	form.Set("ver", "Douyu_223061205")
	form.Set("iar", "1")
	form.Set("ive", "1")
	form.Set("hevc", "0")
	form.Set("fa", "0")
	data, err := r.session.postForm(fmt.Sprintf("https://www.douyu.com/lapi/live/getH5Play/%s", roomID), form, map[string]string{
		"Referer":    fmt.Sprintf("https://m.douyu.com/%s", roomID),
		"User-Agent": douyuMobileUserAgent,
	})
	if err != nil {
		// 保留为普通 error：与 Python 的 requests.RequestException 一致，
		// 在逐 CDN 的循环里冒泡中断，由 adapter 统一加"斗鱼请求失败"前缀。
		return nil, fmt.Errorf("%s", err.Error())
	}
	payload, err := parseJSONBytes(data)
	if err != nil {
		return nil, newError("斗鱼未返回播放信息")
	}
	root := jsonObject(payload)
	if root == nil {
		return nil, newError("斗鱼未返回播放信息")
	}
	errorCode, _ := ToInt(root["error"])
	if errorCode != 0 {
		message := Text(root["msg"], Text(root["error"], ""))
		return nil, newError("斗鱼取流失败：%s", message)
	}
	playData := jsonObject(root["data"])
	if playData == nil {
		return nil, newError("斗鱼未返回播放信息")
	}
	return playData, nil
}

// getPlayArgs 抓取并执行斗鱼混淆签名脚本，生成 getH5Play 的基础参数。
func (r *douyuResolver) getPlayArgs(roomID string) (map[string]string, error) {
	if r.playArgsFn != nil {
		return r.playArgsFn(roomID)
	}
	data, err := r.session.get(fmt.Sprintf("https://www.douyu.com/swf_api/homeH5Enc?rids=%s", roomID), map[string]string{
		"Referer":    fmt.Sprintf("https://m.douyu.com/%s", roomID),
		"User-Agent": douyuMobileUserAgent,
	})
	if err != nil {
		return nil, newError("斗鱼请求失败：%s", err.Error())
	}
	payload, err := parseJSONBytes(data)
	if err != nil {
		return nil, newError("斗鱼未返回签名脚本")
	}
	root := jsonObject(payload)
	if root == nil {
		return nil, newError("斗鱼未返回签名脚本")
	}
	nested := jsonObject(root["data"])
	if nested == nil {
		return nil, newError("斗鱼未返回签名脚本")
	}
	ciphertext, ok := jsonString(nested[fmt.Sprintf("room%s", roomID)])
	if !ok || ciphertext == "" {
		return nil, newError("斗鱼未返回签名脚本")
	}
	match := douyuServerScriptPattern.FindStringSubmatchIndex(ciphertext)
	if match == nil {
		return nil, newError("斗鱼签名脚本格式已变化")
	}
	runtimeJS := ciphertext[match[2]:match[3]]
	runtimeJS = douyuEvalPattern.ReplaceAllString(runtimeJS, "strc;}")

	signatureTemplate, err := runJSScriptScriptExpression(runtimeJS, "ub98484234()")
	if err != nil {
		return nil, newError("执行斗鱼签名脚本失败：%s", err.Error())
	}
	versionMatch := douyuVersionPattern.FindStringSubmatch(signatureTemplate)
	if versionMatch == nil {
		return nil, newError("斗鱼签名脚本未包含版本号")
	}
	version := versionMatch[1]
	timestamp := strconv.FormatInt(time.Now().Unix(), 10)
	digest := md5.Sum([]byte(fmt.Sprintf("%s%s%s%s", roomID, douyuDeviceID, timestamp, version)))
	md5Value := hex.EncodeToString(digest[:])

	signJS := douyuReturnRT.ReplaceAllString(signatureTemplate, "return rt;}")
	if !douyuFunctionHead.MatchString(signJS) {
		return nil, newError("斗鱼签名模板格式已变化：未找到 sign 函数头")
	}
	signJS = douyuFunctionHead.ReplaceAllString(signJS, "function sign(")
	signJS = douyuCryptoMD5.ReplaceAllString(signJS, strconv.Quote(md5Value))

	combined := fmt.Sprintf("%s;%s", runtimeJS, signJS)
	expression := fmt.Sprintf(
		"sign(%s,%s,%s)",
		strconv.Quote(roomID),
		strconv.Quote(douyuDeviceID),
		strconv.Quote(timestamp),
	)
	signedQuery, err := runJSScriptScriptExpression(combined, expression)
	if err != nil {
		return nil, newError("计算斗鱼播放签名失败：%s", err.Error())
	}
	if signedQuery == "" {
		return nil, newError("斗鱼未生成有效播放签名")
	}
	values, err := url.ParseQuery(signedQuery)
	if err != nil {
		return nil, newError("斗鱼未生成有效播放签名")
	}
	result := map[string]string{}
	for key, list := range values {
		if len(list) > 0 {
			result[key] = list[len(list)-1]
		}
	}
	return result, nil
}

func parseDouyuQualities(playInfo map[string]interface{}) []douyuQuality {
	qualities := []douyuQuality{}
	for _, raw := range jsonArray(playInfo["multirates"]) {
		item, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		rate, _ := ToInt(item["rate"])
		qualities = append(qualities, douyuQuality{
			rate: rate,
			name: Text(item["name"], "自动"),
		})
	}
	// 斗鱼 rate 数值越大代表越高画质；优先尝试最高档位，失败后再降级。
	sort.SliceStable(qualities, func(i, j int) bool {
		return qualities[i].rate > qualities[j].rate
	})
	return qualities
}

func orderDouyuCdns(cdnItems interface{}) []string {
	items := jsonArray(cdnItems)
	cdns := []string{}
	seen := map[string]bool{}
	for _, raw := range items {
		item, ok := raw.(map[string]interface{})
		if !ok {
			continue
		}
		cdn := Text(item["cdn"], "")
		if cdn == "" || seen[cdn] {
			continue
		}
		seen[cdn] = true
		cdns = append(cdns, cdn)
	}
	sort.SliceStable(cdns, func(i, j int) bool {
		return !strings.HasPrefix(cdns[i], "scdn") && strings.HasPrefix(cdns[j], "scdn")
	})
	return cdns
}

func buildDouyuStreamURL(playInfo map[string]interface{}) string {
	baseURL := TextStrict(playInfo["rtmp_url"])
	baseURL = strings.TrimSuffix(baseURL, "/")
	streamPath := pythonUnquote(TextStrict(playInfo["rtmp_live"]))
	streamPath = strings.TrimPrefix(streamPath, "/")
	if baseURL == "" || streamPath == "" {
		return ""
	}
	return fmt.Sprintf("%s/%s", baseURL, streamPath)
}

func toFloat(value interface{}) float64 {
	if num, ok := jsonFloat(value); ok {
		return num
	}
	return 0
}

// pythonUnquote 语义对齐 urllib.parse.unquote：解码 %XX，不做 '+' 转换。
func pythonUnquote(value string) string {
	if !strings.Contains(value, "%") {
		return value
	}
	if decoded, err := url.PathUnescape(value); err == nil {
		return decoded
	}
	return value
}
