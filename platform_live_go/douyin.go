package platformlive

// douyin.go —— 抖音直播解析（完整移植自 Python douyin_resolver.py）。
// 包含：a_bogus 签名（通过 goja 执行 douyin_sign.js）、分类树、房间列表、
// 房间详情（三级回退：web_rid API → room_id API → HTML 提取）与多画质播放地址。

import (
	"crypto/rand"
	"encoding/json"
	"fmt"
	"math/big"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
)

const (
	douyinUserAgentPC = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 " +
		"(KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 " +
		"Core/1.116.567.400 QQBrowser/19.7.6764.400"
	douyinUserAgentMobile = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230901.001; wv) " +
		"AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/116.0.0.0 " +
		"Mobile Safari/537.36 DouyinWebview/24.1.0.330875"
	douyinUserAgentTV = "Mozilla/5.0 (Linux; Android 9; TV Build/PQ3A.190605.07212754; wv) " +
		"AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/83.0.4103.106 " +
		"Mobile Safari/537.36 DouyinWebview/22.4.0.2003"

	douyinReferer    = "https://live.douyin.com"
	douyinAPIBase    = "https://live.douyin.com"
	defaultTTwid     = "1%7CB1qls3GdnZhUov9o2NxOMxxYS2ff6OSvEWbv0ytbES4%7C1680522049%7C" +
		"280d802d6d478e3e78d0c807f7c487e7ffec0ae4e5fdd6a0fe74c3c6af149511"
	tokenLength = 107
)

// douyinClientConfig 按 clientType 返回对应 UA、API 参数模板和环境字符串。
type douyinClientConfig struct {
	userAgent    string
	apiParams    map[string]string // 固定参数（不含 msToken）
	windowEnvStr string            // douyin_sign.js 的 window_env_str 对应值
}

func douyinClientConfigFor(clientType string) douyinClientConfig {
	switch clientType {
	case "mobile":
		return douyinClientConfig{
			userAgent: douyinUserAgentMobile,
			apiParams: map[string]string{
				"aid":           "6383",
				"app_name":      "douyin_web",
				"live_id":       "1",
				"device_platform": "android",
				"language":      "zh-CN",
				"enter_from":    "link_share",
				"cookie_enabled": "true",
				"screen_width":  "1080",
				"screen_height": "2400",
				"browser_language": "zh-CN",
				"browser_platform": "Android",
				"browser_name":  "Chrome",
				"browser_version": "116.0.0.0",
				"browser_online": "true",
				"engine_name":   "Blink",
				"engine_version": "116.0.0.0",
				"os_name":       "Android",
				"os_version":    "13",
				"cpu_core_num":  "8",
				"device_memory": "8",
				"version_code":  "300503",
				"web_rid":       "",
			},
			windowEnvStr: "1080|2340|1080|2340|0|30|0|0|1080|2340|1080|2400|1065|2340|24|24|Android",
		}
	case "tv":
		return douyinClientConfig{
			userAgent: douyinUserAgentTV,
			apiParams: map[string]string{
				"aid":           "6383",
				"app_name":      "douyin_web",
				"live_id":       "1",
				"device_platform": "android",
				"language":      "zh-CN",
				"enter_from":    "link_share",
				"cookie_enabled": "true",
				"screen_width":  "1920",
				"screen_height": "1080",
				"browser_language": "zh-CN",
				"browser_platform": "Android",
				"browser_name":  "Chrome",
				"browser_version": "83.0.0.0",
				"browser_online": "true",
				"engine_name":   "Blink",
				"engine_version": "83.0.0.0",
				"os_name":       "Android",
				"os_version":    "9",
				"cpu_core_num":  "8",
				"device_memory": "4",
				"version_code":  "280303",
				"web_rid":       "",
			},
			windowEnvStr: "1920|1044|1920|1080|0|30|0|0|1920|1080|1920|1080|1905|1044|24|24|Android",
		}
	default: // web
		return douyinClientConfig{
			userAgent: douyinUserAgentPC,
			apiParams: map[string]string{
				"aid":           "6383",
				"app_name":      "douyin_web",
				"live_id":       "1",
				"device_platform": "webapp",
				"channel":       "channel_pc_web",
				"update_version_code": "170400",
				"pc_client_type": "1",
				"version_code":  "170400",
				"version_name":  "17.4.0",
				"cookie_enabled": "true",
				"screen_width":  "1536",
				"screen_height": "864",
				"browser_language": "zh-CN",
				"browser_platform": "Win32",
				"browser_name":  "Chrome",
				"browser_version": "123.0.0.0",
				"browser_online": "true",
				"engine_name":   "Blink",
				"engine_version": "123.0.0.0",
				"os_name":       "Windows",
				"os_version":    "10",
				"cpu_core_num":  "16",
				"device_memory": "8",
				"platform":      "PC",
				"downlink":      "10",
				"effective_type": "4g",
				"round_trip_time": "50",
				"web_rid":       "",
			},
			windowEnvStr: "1536|747|1536|834|0|30|0|0|1536|834|1536|864|1525|747|24|24|Win32",
		}
	}
}

// mergeMapCopy 深拷贝 src map 并覆盖/添加 dst 中的 key。
func mergeMapCopy(dst map[string]string) map[string]string {
	result := make(map[string]string, len(dst))
	for k, v := range dst {
		result[k] = v
	}
	return result
}

var (
	douyinRoomIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)
	douyinSignMu        sync.Mutex
	douyinSignScript    string
)

// SetDouyinSignScript 注入裁剪后的 douyin_sign.js 文本（Init 内部调用）。
func SetDouyinSignScript(script string) {
	douyinSignMu.Lock()
	defer douyinSignMu.Unlock()
	douyinSignScript = script
}

// getDouyinSignScript 取当前签名脚本文本。
func getDouyinSignScript() string {
	douyinSignMu.Lock()
	defer douyinSignMu.Unlock()
	return douyinSignScript
}

type douyinResolver struct {
	session  *httpSession
	cookie   string
	config   douyinClientConfig // 由 clientType 决定 UA + API 参数
}

func newDouyinResolver(timeoutSeconds int64, cookie string, clientType string) *douyinResolver {
	return &douyinResolver{
		session: newHTTPSession(int(timeoutSeconds), "", true),
		cookie:  strings.TrimSpace(cookie),
		config:  douyinClientConfigFor(clientType),
	}
}

func (r *douyinResolver) SiteID() string      { return "douyin" }
func (r *douyinResolver) SiteName() string    { return "抖音" }
func (r *douyinResolver) Description() string { return "分类直播、签名解析与多画质播放" }

// ---------- 请求工具 ----------

func (r *douyinResolver) headers(referer string) map[string]string {
	if referer == "" {
		referer = douyinReferer
	}
	result := map[string]string{
		"Accept":          "application/json, text/plain, */*",
		"Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
		"Referer":         referer,
		"User-Agent":      r.config.userAgent,
	}
	rawCookie := r.cookie
	if rawCookie == "" {
		rawCookie = defaultTTwid
	}
	result["Cookie"] = rawCookie
	return result
}

func (r *douyinResolver) getText(rawURL string, params url.Values, referer string) (string, error) {
	body, err := r.session.getWithParams(rawURL, params, r.headers(referer))
	if err != nil {
		return "", newError("抖音请求失败：%s", err.Error())
	}
	text := strings.TrimSpace(string(body))
	if text == "" {
		return "", newError("抖音接口返回为空")
	}
	return text, nil
}

func (r *douyinResolver) getJSON(rawURL string, params url.Values, referer string) (map[string]interface{}, error) {
	body, err := r.getText(rawURL, params, referer)
	if err != nil {
		return nil, err
	}
	payload, err := parseJSONBytes([]byte(body))
	if err != nil {
		return nil, newError("抖音接口返回格式异常")
	}
	root, ok := payload.(map[string]interface{})
	if !ok {
		return nil, newError("抖音接口返回格式异常")
	}
	statusCode := root["status_code"]
	switch v := statusCode.(type) {
	case float64:
		if v != 0 {
			msg := root["status_msg"]
			return nil, newError("抖音接口失败：%s", fmt.Sprintf("%v", msg))
		}
	case string:
		if v != "0" {
			msg := root["status_msg"]
			return nil, newError("抖音接口失败：%s", fmt.Sprintf("%v", msg))
		}
	}
	return root, nil
}

// ---------- 签名 ----------

func (r *douyinResolver) signedURL(baseURL string, params map[string]string) (string, error) {
	queryParts := make([]string, 0, len(params)+1)
	for k, v := range params {
		queryParts = append(queryParts, k+"="+url.QueryEscape(v))
	}
	msToken, err := r.randomToken(tokenLength)
	if err != nil {
		return "", newError("随机数生成失败：%s", err.Error())
	}
	queryParts = append(queryParts, "msToken="+url.QueryEscape(msToken))
	baseQuery := strings.Join(queryParts, "&")

	signResult, sigErr := RunABogus(getDouyinSignScript(), baseQuery, r.config.userAgent)
	if sigErr != nil {
		return "", newError("抖音签名助手执行失败：%s", sigErr.Error())
	}
	var sigResp map[string]interface{}
	if err = json.Unmarshal([]byte(signResult), &sigResp); err != nil {
		return "", newError("抖音签名助手返回格式异常")
	}
	aBogus := fmt.Sprintf("%v", sigResp["a_bogus"])
	if aBogus == "<nil>" || aBogus == "" {
		return "", newError("抖音签名失败：%v", sigResp["error"])
	}
	return baseURL + "?" + baseQuery + "&a_bogus=" + url.QueryEscape(aBogus), nil
}

func (r *douyinResolver) randomToken(length int) (string, error) {
	const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, length)
	for i := range b {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(alphabet))))
		if err != nil {
			return "", err
		}
		b[i] = alphabet[n.Int64()]
	}
	return string(b), nil
}

// ---------- NormalizeRoomID ----------

func (r *douyinResolver) NormalizeRoomID(value string) (string, error) {
	return r.parseRoomID(value)
}

func (r *douyinResolver) parseRoomID(value string) (string, error) {
	raw := strings.TrimSpace(value)
	parsed, parseErr := url.Parse(raw)
	if parseErr == nil && parsed.Host != "" {
		host := strings.SplitN(strings.ToLower(parsed.Host), ":", 1)[0]
		if host != "live.douyin.com" && host != "douyin.com" && host != "www.douyin.com" {
			return "", newError("直播间链接不属于抖音")
		}
		raw = ""
		for _, part := range strings.Split(parsed.Path, "/") {
			if part != "" {
				raw = part
				break
			}
		}
	}
	if raw == "" || !douyinRoomIDPattern.MatchString(raw) {
		return "", newError("抖音房间号格式无效")
	}
	return raw, nil
}

// ---------- GetCategoryTree ----------

func (r *douyinResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	html, err := r.getText(douyinAPIBase, nil, "")
	if err != nil {
		return nil, nil, err
	}
	renderData, err := r.extractCategoryRenderData(html)
	if err != nil {
		return nil, nil, err
	}
	rawCategories, ok := renderData["categoryData"].([]interface{})
	if !ok {
		return nil, nil, newError("抖音未返回有效直播分类")
	}
	var parents []LiveParentCategory
	var categories []LiveCategory
	for _, item := range rawCategories {
		itemMap, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		partitionRaw := itemMap["partition"]
		partition, _ := partitionRaw.(map[string]interface{})
		if partition == nil {
			partition = map[string]interface{}{}
		}
		parentID := r.partitionID(partition)
		parentType := parentPartitionType(partition)
		parentName := firstTruthy(partition["title"], partition["name"], "")
		if parentID == "" || parentName == "" {
			continue
		}
		parentKey := parentID + "," + parentType
		parents = append(parents, LiveParentCategory{
			ID:    parentKey,
			Name:  parentName,
			Cover: r.partitionImage(partition),
		})
		categories = append(categories, LiveCategory{
			ID:         parentKey,
			Name:       parentName,
			ParentID:   parentKey,
			ParentName: parentName,
			Cover:      r.partitionImage(partition),
		})
		childrenRaw := itemMap["sub_partition"]
		children, _ := childrenRaw.([]interface{})
		for _, child := range children {
			childMap, ok := child.(map[string]interface{})
			if !ok {
				continue
			}
			childPartitionRaw := childMap["partition"]
			childPartition, _ := childPartitionRaw.(map[string]interface{})
			if childPartition == nil {
				childPartition = map[string]interface{}{}
			}
			childID := r.partitionID(childPartition)
			childType := parentPartitionType(childPartition)
			if childType == "" {
				childType = parentType
			}
			childName := firstTruthy(childPartition["title"], childPartition["name"], "")
			if childID == "" || childName == "" {
				continue
			}
			childCover := r.partitionImage(childPartition)
			if childCover == "" {
				childCover = r.partitionImage(partition)
			}
			categories = append(categories, LiveCategory{
				ID:         childID + "," + childType,
				Name:       childName,
				ParentID:   parentKey,
				ParentName: parentName,
				Cover:      childCover,
			})
		}
	}
	return dedupeParents(parents), dedupeCategories(categories), nil
}

func parentPartitionType(p map[string]interface{}) string {
	v := firstTruthy(p["type"], "1")
	return fmt.Sprintf("%v", v)
}

// extractCategoryRenderData 从 HTML 中提取 categoryData JSON。
// 对齐 Python _extract_category_render_data。
func (r *douyinResolver) extractCategoryRenderData(html string) (map[string]interface{}, error) {
	normalized := strings.ReplaceAll(html, `\"`, `"`)
	normalized = strings.ReplaceAll(normalized, `\/`, `/`)
	normalized = strings.ReplaceAll(normalized, `\\`, `\`)
	marker := strings.Index(normalized, `"categoryData":`)
	if marker < 0 {
		return nil, newError("抖音分类数据解析失败")
	}
	start := strings.Index(normalized[marker:], "[")
	if start < 0 {
		return nil, newError("抖音分类数据解析失败")
	}
	start += marker
	raw, ok := extractJSONValue(normalized, start)
	if !ok || raw == "" {
		return nil, newError("抖音分类数据解析失败")
	}
	wrapped := `{"categoryData":` + raw + `}`
	var result map[string]interface{}
	if err := json.Unmarshal([]byte(wrapped), &result); err != nil {
		return nil, newError("抖音分类数据解析失败")
	}
	return result, nil
}

// partitionID 取 id_str / id / partition_id / partition。
func (r *douyinResolver) partitionID(v map[string]interface{}) string {
	return firstTruthy(v["id_str"], v["id"], v["partition_id"], v["partition"], "")
}

// partitionImage 递归查找图片 URL。
func (r *douyinResolver) partitionImage(v interface{}) string {
	switch val := v.(type) {
	case string:
		return strings.TrimSpace(val)
	case []interface{}:
		for _, item := range val {
			if result := r.partitionImage(item); result != "" {
				return result
			}
		}
		return ""
	case map[string]interface{}:
		for _, key := range []string{"icon", "icons", "cover", "background",
			"avatar_thumb", "image", "image_url", "url", "url_list", "static_icon"} {
			if result := r.partitionImage(val[key]); result != "" {
				return result
			}
		}
		for _, child := range val {
			if result := r.partitionImage(child); result != "" {
				return result
			}
		}
	}
	return ""
}

func firstTruthy(values ...interface{}) string {
	for _, v := range values {
		switch val := v.(type) {
		case string:
			trimmed := strings.TrimSpace(val)
			if trimmed != "" {
				return trimmed
			}
		default:
			if v != nil && fmt.Sprintf("%v", v) != "" && fmt.Sprintf("%v", v) != "<nil>" {
				return fmt.Sprintf("%v", v)
			}
		}
	}
	return ""
}

func dedupeParents(items []LiveParentCategory) []LiveParentCategory {
	seen := map[string]bool{}
	result := make([]LiveParentCategory, 0, len(items))
	for _, item := range items {
		if seen[item.ID] {
			continue
		}
		seen[item.ID] = true
		result = append(result, item)
	}
	return result
}

func dedupeCategories(items []LiveCategory) []LiveCategory {
	seen := map[string]bool{}
	result := make([]LiveCategory, 0, len(items))
	for _, item := range items {
		if seen[item.ID] {
			continue
		}
		seen[item.ID] = true
		result = append(result, item)
	}
	return result
}

// ---------- GetCategoryRooms ----------

func (r *douyinResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	normalizedPage := page
	if normalizedPage < 1 {
		normalizedPage = 1
	}
	pid, ptype, err := r.splitCategoryID(categoryID)
	if err != nil {
		return LiveRoomsPage{}, err
	}
	params := mergeMapCopy(r.config.apiParams)
	params["count"] = "15"
	params["offset"] = strconv.FormatInt((normalizedPage-1)*15, 10)
	params["partition"] = pid
	params["partition_type"] = ptype
	params["req_from"] = "2"
	signedURL, err := r.signedURL(douyinAPIBase+"/webcast/web/partition/detail/room/v2/", params)
	if err != nil {
		return LiveRoomsPage{}, err
	}
	payload, err := r.getJSON(signedURL, nil, "")
	if err != nil {
		return LiveRoomsPage{}, err
	}
	data, _ := payload["data"].(map[string]interface{})
	rawRooms, _ := data["data"].([]interface{})
	if !isList(rawRooms) {
		return LiveRoomsPage{}, newError("抖音分类接口返回异常，可能已触发访问限制")
	}
	rooms := make([]LiveRoom, 0, len(rawRooms))
	for _, item := range rawRooms {
		itemMap, ok := item.(map[string]interface{})
		if !ok {
			continue
		}
		roomRaw := itemMap["room"]
		room, ok := roomRaw.(map[string]interface{})
		if !ok {
			room = itemMap
		}
		ownerRaw := room["owner"]
		owner, _ := ownerRaw.(map[string]interface{})
		if owner == nil {
			owner = map[string]interface{}{}
		}
		webRid := firstTruthy(
			itemMap["web_rid"],
			room["web_rid"],
			owner["web_rid"],
			"",
		)
		if webRid == "" {
			continue
		}
		rooms = append(rooms, LiveRoom{
			RoomID:     webRid,
			Title:      firstTruthy(room["title"], "抖音直播 "+webRid),
			Anchor:     firstTruthy(owner["nickname"], ""),
			Cover:      r.firstImage(room["cover"]),
			Online:     r.onlineValue(room["room_view_stats"]),
			CategoryID: categoryID,
		})
	}
	pageCount := normalizedPage
	if int64(len(rawRooms)) >= 15 {
		pageCount = normalizedPage + 1
	}
	return LiveRoomsPage{Rooms: rooms, PageCount: pageCount}, nil
}

func (r *douyinResolver) splitCategoryID(value string) (string, string, error) {
	parts := strings.SplitN(value, ",", 2)
	if len(parts) == 2 && strings.TrimSpace(parts[0]) != "" && strings.TrimSpace(parts[1]) != "" {
		return strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1]), nil
	}
	parts = strings.SplitN(value, ":", 2)
	if len(parts) == 2 && strings.TrimSpace(parts[0]) != "" && strings.TrimSpace(parts[1]) != "" {
		return strings.TrimSpace(parts[0]), strings.TrimSpace(parts[1]), nil
	}
	return "", "", newError("抖音分类 ID 格式无效")
}

func (r *douyinResolver) firstImage(v interface{}) string {
	m, ok := v.(map[string]interface{})
	if !ok {
		return firstTruthy(fmt.Sprintf("%v", v), "")
	}
	urlList, ok := m["url_list"].([]interface{})
	if ok && len(urlList) > 0 {
		return firstTruthy(urlList[0], "")
	}
	return r.partitionImage(m)
}

func (r *douyinResolver) onlineValue(v interface{}) int64 {
	m, ok := v.(map[string]interface{})
	if !ok {
		return PositiveInt(v, 0)
	}
	raw := firstTruthy(m["display_value"], m["user_count"], m["total_user"], "")
	return PositiveInt(raw, 0)
}

// ---------- Resolve ----------

func (r *douyinResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	roomID, err := r.parseRoomID(roomInput)
	if err != nil {
		return nil, err
	}
	var detail map[string]interface{}
	var webRid = roomID
	if len(roomID) > 16 {
		detail, err = r.getRoomDataByRoomID(roomID)
		if err == nil {
			room, user := r.roomAndUser(detail)
			webRid = firstTruthy(user["web_rid"], roomID)
			if r.status(room) == 4 && webRid != roomID {
				detail = nil
			}
		}
	}
	if detail == nil {
		detail, err = r.getRoomDataByAPI(webRid)
		if err != nil {
			detail, err = r.getRoomDataByHTML(webRid)
			if err != nil {
				return nil, err
			}
		}
	}
	room, user := r.roomAndUser(detail)
	if room == nil {
		return nil, newError("抖音直播间数据为空，可能是房间不存在或被风控限制")
	}
	if r.status(room) != 2 {
		return nil, newError("房间未开播：%s", firstTruthy(room["title"], webRid))
	}
	candidates, quality := r.extractPlayCandidates(room)
	if len(candidates) == 0 {
		return nil, newError("抖音未返回可播放地址")
	}
	resolvedRoomID := firstTruthy(user["web_rid"], webRid)
	return &ResolvedLiveStream{
		RoomID:     resolvedRoomID,
		Title:      firstTruthy(room["title"], "抖音直播 "+resolvedRoomID),
		Anchor:     firstTruthy(user["nickname"], ""),
		Quality:    quality,
		Candidates: UniqueCandidates(candidates),
		Headers: map[string]string{
			"Referer":    douyinAPIBase + "/" + resolvedRoomID,
			"User-Agent": r.config.userAgent,
		},
	}, nil
}

func (r *douyinResolver) getRoomDataByAPI(webRid string) (map[string]interface{}, error) {
	params := mergeMapCopy(r.config.apiParams)
	params["web_rid"] = webRid
	params["msToken"] = ""
	signedURL, err := r.signedURL(douyinAPIBase+"/webcast/room/web/enter/", params)
	if err != nil {
		return nil, err
	}
	payload, err := r.getJSON(signedURL, nil, douyinAPIBase+"/"+webRid)
	if err != nil {
		return nil, err
	}
	data, _ := payload["data"].(map[string]interface{})
	if data == nil {
		return nil, newError("抖音直播间 API 数据为空")
	}
	rawList, ok := data["data"].([]interface{})
	if !ok || len(rawList) == 0 {
		return nil, newError("抖音直播间 API 数据为空")
	}
	return data, nil
}

func (r *douyinResolver) getRoomDataByRoomID(roomID string) (map[string]interface{}, error) {
	params := url.Values{}
	params.Set("type_id", "0")
	params.Set("live_id", "1")
	params.Set("room_id", roomID)
	params.Set("sec_user_id", "")
	params.Set("version_code", "99.99.99")
	params.Set("app_id", "6383")
	return r.getJSON("https://webcast.amemv.com/webcast/room/reflow/info/?"+params.Encode(), nil, douyinAPIBase)
}

func (r *douyinResolver) getRoomDataByHTML(webRid string) (map[string]interface{}, error) {
	html, err := r.getText(douyinAPIBase+"/"+url.QueryEscape(webRid), nil, douyinReferer)
	if err != nil {
		return nil, err
	}
	normalized := strings.ReplaceAll(html, `\"`, `"`)
	normalized = strings.ReplaceAll(normalized, `\/`, `/`)
	normalized = strings.ReplaceAll(normalized, `\\`, `\`)
	marker := strings.Index(normalized, `"state":`)
	if marker < 0 {
		return nil, newError("抖音直播间页面数据不可用")
	}
	start := strings.Index(normalized[marker:], "{")
	if start < 0 {
		return nil, newError("抖音直播间状态 JSON 无效")
	}
	start += marker
	raw, ok := extractJSONValue(normalized, start)
	if !ok || raw == "" {
		return nil, newError("抖音直播间状态 JSON 无效")
	}
	var parsed map[string]interface{}
	if err = json.Unmarshal([]byte(raw), &parsed); err != nil {
		return nil, newError("抖音直播间状态 JSON 无效")
	}
	state, ok := parsed["state"].(map[string]interface{})
	if !ok {
		return nil, newError("抖音直播间状态数据异常")
	}
	return state, nil
}

func (r *douyinResolver) roomAndUser(data map[string]interface{}) (map[string]interface{}, map[string]interface{}) {
	rawList, ok := data["data"].([]interface{})
	if ok && len(rawList) > 0 {
		room, _ := rawList[0].(map[string]interface{})
		if room == nil {
			room = map[string]interface{}{}
		}
		user, _ := data["user"].(map[string]interface{})
		if user == nil {
			user = map[string]interface{}{}
		}
		return room, user
	}
	nested := r.nestedRoom(data)
	return nested, map[string]interface{}{}
}

func (r *douyinResolver) nestedRoom(data map[string]interface{}) map[string]interface{} {
	if room, ok := data["room"].(map[string]interface{}); ok {
		return room
	}
	roomStore, _ := data["roomStore"].(map[string]interface{})
	if roomStore == nil {
		roomStore = map[string]interface{}{}
	}
	info, _ := roomStore["roomInfo"].(map[string]interface{})
	if info == nil {
		info = map[string]interface{}{}
	}
	room, ok := info["room"].(map[string]interface{})
	if ok {
		return room
	}
	return map[string]interface{}{}
}

func (r *douyinResolver) status(room map[string]interface{}) int {
	for _, key := range []string{"status", "live_status", "room_status"} {
		v := room[key]
		if m, ok := v.(map[string]interface{}); ok {
			if s, ok := m["status"].(float64); ok {
				return int(s)
			}
			if ls, ok := m["live_status"].(float64); ok {
				return int(ls)
			}
		}
		if f, ok := v.(float64); ok {
			return int(f)
		}
	}
	return 0
}

// ---------- 播放地址提取 ----------

func (r *douyinResolver) extractPlayCandidates(room map[string]interface{}) ([]LiveStreamCandidate, string) {
	streamURLRaw, _ := room["stream_url"].(map[string]interface{})
	if streamURLRaw == nil {
		streamURLRaw = map[string]interface{}{}
	}
	liveCoreRaw, _ := streamURLRaw["live_core_sdk_data"].(map[string]interface{})
	if liveCoreRaw == nil {
		liveCoreRaw = map[string]interface{}{}
	}
	pullDataRaw, _ := liveCoreRaw["pull_data"].(map[string]interface{})
	if pullDataRaw == nil {
		pullDataRaw = map[string]interface{}{}
	}
	optionsRaw, _ := pullDataRaw["options"].(map[string]interface{})
	if optionsRaw == nil {
		optionsRaw = map[string]interface{}{}
	}
	qualitiesRaw, _ := optionsRaw["qualities"].([]interface{})
	groups := map[int][]LiveStreamCandidate{}
	groupNames := map[int]string{}

	streamDataRaw := pullDataRaw["stream_data"]
	decodedStream := map[string]interface{}{}
	if sd, ok := streamDataRaw.(string); ok && strings.HasPrefix(sd, "{") {
		var parsed map[string]interface{}
		if err := json.Unmarshal([]byte(sd), &parsed); err == nil {
			if d, ok := parsed["data"].(map[string]interface{}); ok {
				decodedStream = d
			} else {
				decodedStream = parsed
			}
		}
	}
	for _, q := range qualitiesRaw {
		qMap, ok := q.(map[string]interface{})
		if !ok {
			continue
		}
		level := r.qualityLevel(qMap)
		name := firstTruthy(qMap["name"], qMap["desc"], "画质 "+strconv.Itoa(level))
		sdkKey := firstTruthy(qMap["sdk_key"], "")
		source := map[string]interface{}{}
		if sdkKey != "" {
			if s, ok := decodedStream[sdkKey].(map[string]interface{}); ok {
				source = s
			}
		}
		mainRaw, _ := source["main"].(map[string]interface{})
		if mainRaw == nil {
			mainRaw = map[string]interface{}{}
		}
		flv := firstTruthy(mainRaw["flv"], "")
		hls := firstTruthy(mainRaw["hls"], "")
		cands := r.urlCandidates([]interface{}{flv, hls})
		if len(cands) > 0 {
			groups[level] = cands
			groupNames[level] = name
		}
	}
	if len(groups) == 0 {
		groups, groupNames = r.fallbackQualityGroups(room, qualitiesRaw)
	}
	if len(groups) == 0 {
		groups, groupNames = r.recursiveQualityGroups(streamURLRaw, 0, "原画")
	}
	if len(groups) == 0 {
		return nil, ""
	}
	bestLevel := maxGroupKey(groups)
	return groups[bestLevel], groupNames[bestLevel]
}

func (r *douyinResolver) fallbackQualityGroups(room map[string]interface{}, qualities []interface{}) (map[int][]LiveStreamCandidate, map[int]string) {
	streamURLRaw, _ := room["stream_url"].(map[string]interface{})
	if streamURLRaw == nil {
		streamURLRaw = map[string]interface{}{}
	}
	flvRaw := streamURLRaw["flv_pull_url"]
	if flvRaw == nil {
		flvRaw = room["flv_pull_url"]
	}
	hlsRaw := streamURLRaw["hls_pull_url_map"]
	if hlsRaw == nil {
		hlsRaw = room["hls_pull_url_map"]
	}
	flvList := r.urlValues(flvRaw)
	hlsList := r.urlValues(hlsRaw)
	if len(flvList) == 0 && len(hlsList) == 0 {
		return map[int][]LiveStreamCandidate{}, map[int]string{}
	}
	groups := map[int][]LiveStreamCandidate{}
	groupNames := map[int]string{}
	qList := qualities
	if len(qList) == 0 {
		qList = []interface{}{map[string]interface{}{"level": 1, "name": "原画"}}
	}
	// 对每个 quality level，为该 level 计算 flv/hls 下标，选取对应候选
	for _, q := range qList {
		qMap, ok := q.(map[string]interface{})
		if !ok {
			continue
		}
		level := r.qualityLevel(qMap)
		name := firstTruthy(qMap["name"], qMap["desc"], "画质 "+strconv.Itoa(level))
		var cands []LiveStreamCandidate
		idx := len(flvList) - level
		if idx >= 0 && idx < len(flvList) {
			cands = append(cands, LiveStreamCandidate{
				CDN:      hostnameOf(flvList[idx]),
				URL:      flvList[idx],
				Protocol: InferProtocol(flvList[idx]),
			})
		}
		idx = len(hlsList) - level
		if idx >= 0 && idx < len(hlsList) {
			cands = append(cands, LiveStreamCandidate{
				CDN:      hostnameOf(hlsList[idx]),
				URL:      hlsList[idx],
				Protocol: InferProtocol(hlsList[idx]),
			})
		}
		if len(cands) > 0 {
			groups[level] = append(groups[level], cands...)
			groupNames[level] = name
		}
	}
	// 若仍无结果，退回全部 flv/hls（兜底）
	if len(groups) == 0 {
		for _, u := range flvList {
			groups[0] = append(groups[0], LiveStreamCandidate{CDN: hostnameOf(u), URL: u, Protocol: InferProtocol(u)})
		}
		for _, u := range hlsList {
			groups[0] = append(groups[0], LiveStreamCandidate{CDN: hostnameOf(u), URL: u, Protocol: InferProtocol(u)})
		}
		groupNames[0] = "原画"
	}
	return groups, groupNames
}

func (r *douyinResolver) recursiveQualityGroups(v interface{}, level int, name string) (map[int][]LiveStreamCandidate, map[int]string) {
	groups := map[int][]LiveStreamCandidate{}
	groupNames := map[int]string{}
	switch val := v.(type) {
	case string:
		if strings.HasPrefix(val, "http://") || strings.HasPrefix(val, "https://") || strings.HasPrefix(val, "rtmp://") {
			groups[level] = []LiveStreamCandidate{{CDN: hostnameOf(val), URL: val, Protocol: InferProtocol(val)}}
			groupNames[level] = name
		}
		return groups, groupNames
	case []interface{}:
		for _, item := range val {
			subG, subN := r.recursiveQualityGroups(item, level, name)
			for k, v := range subG {
				groups[k] = v
				groupNames[k] = subN[k]
			}
		}
		return groups, groupNames
	case map[string]interface{}:
		currentLevel := r.qualityLevel(val, level)
		currentName := firstTruthy(val["name"], val["shortName"], name)
		if u, ok := val["url"].(string); ok {
			if strings.HasPrefix(u, "http://") || strings.HasPrefix(u, "https://") || strings.HasPrefix(u, "rtmp://") {
				groups[currentLevel] = []LiveStreamCandidate{{CDN: hostnameOf(u), URL: u, Protocol: InferProtocol(u)}}
				groupNames[currentLevel] = currentName
			}
		}
		for key, child := range val {
			if key == "url" {
				continue
			}
			subG, subN := r.recursiveQualityGroups(child, currentLevel, currentName)
			for k, v := range subG {
				groups[k] = v
				groupNames[k] = subN[k]
			}
		}
	}
	return groups, groupNames
}

func (r *douyinResolver) qualityLevel(v interface{}, fallback ...int) int {
	if m, ok := v.(map[string]interface{}); ok {
		if f, ok := m["level"].(float64); ok {
			return int(f)
		}
	}
	if len(fallback) > 0 {
		return fallback[0]
	}
	return 0
}

func (r *douyinResolver) urlValues(v interface{}) []string {
	switch val := v.(type) {
	case map[string]interface{}:
		result := make([]string, 0, len(val))
		for _, item := range val {
			if s, ok := item.(string); ok && strings.TrimSpace(s) != "" {
				result = append(result, s)
			}
		}
		return result
	case []interface{}:
		result := make([]string, 0, len(val))
		for _, item := range val {
			if s, ok := item.(string); ok && strings.TrimSpace(s) != "" {
				result = append(result, s)
			}
		}
		return result
	case string:
		if strings.TrimSpace(val) != "" {
			return []string{val}
		}
	}
	return nil
}

func (r *douyinResolver) urlCandidates(values interface{}) []LiveStreamCandidate {
	result := make([]LiveStreamCandidate, 0)
	switch vals := values.(type) {
	case []interface{}:
		for _, v := range vals {
			result = append(result, r.urlCandidates(v)...)
		}
	case []string:
		for _, u := range vals {
			if isValidStreamURL(u) {
				result = append(result, LiveStreamCandidate{
					CDN:      hostnameOf(u),
					URL:      u,
					Protocol: InferProtocol(u),
				})
			}
		}
	default:
		if s, ok := vals.(string); ok && isValidStreamURL(s) {
			result = append(result, LiveStreamCandidate{
				CDN:      hostnameOf(s),
				URL:      s,
				Protocol: InferProtocol(s),
			})
		}
	}
	return result
}

func isValidStreamURL(s string) bool {
	return strings.HasPrefix(s, "http://") || strings.HasPrefix(s, "https://") || strings.HasPrefix(s, "rtmp://")
}

func hostnameOf(rawURL string) string {
	parsed, err := url.Parse(rawURL)
	if err != nil || parsed.Hostname() == "" {
		return "默认"
	}
	return parsed.Hostname()
}

func isList(v interface{}) bool {
	_, ok := v.([]interface{})
	return ok
}

func maxGroupKey(groups map[int][]LiveStreamCandidate) int {
	maxK := 0
	first := true
	for k := range groups {
		if first || k > maxK {
			maxK = k
			first = false
		}
	}
	return maxK
}
