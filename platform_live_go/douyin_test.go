package platformlive

import (
	"encoding/json"
	"testing"
)

// ---------- NormalizeRoomID 测试 ----------

func TestDouyinNormalizeRoomID_Bare(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	id, err := resolver.NormalizeRoomID("test_web_rid")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if id != "test_web_rid" {
		t.Fatalf("id = %q; want test_web_rid", id)
	}
}

func TestDouyinNormalizeRoomID_DouyinLiveURL(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	cases := []struct{ input, want string }{
		{"https://live.douyin.com/abc123", "abc123"},
		{"https://www.douyin.com/xyz456", "xyz456"},
		{"https://douyin.com/stream789", "stream789"},
	}
	for _, c := range cases {
		got, err := resolver.NormalizeRoomID(c.input)
		if err != nil {
			t.Fatalf("[%s] unexpected error: %v", c.input, err)
		}
		if got != c.want {
			t.Fatalf("[%s] got = %q; want %q", c.input, got, c.want)
		}
	}
}

func TestDouyinNormalizeRoomID_RejectsNonDouyinHost(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	if _, err := resolver.NormalizeRoomID("https://example.com/123"); err == nil {
		t.Fatal("expected error for non-douyin host")
	}
}

func TestDouyinNormalizeRoomID_RejectsBadPattern(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	if _, err := resolver.NormalizeRoomID("bad room!"); err == nil {
		t.Fatal("expected error for invalid pattern")
	}
}

// ---------- extractCategoryRenderData 测试 ----------

func TestDouyinExtractCategoryRenderData_EscapedJSON(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	html := `<html>\"categoryData\":[{\"partition\":{\"id_str\":\"1\",\"type\":\"1\",\"title\":\"游戏\"},\"sub_partition\":[{\"partition\":{\"id_str\":\"2\",\"type\":\"1\",\"title\":\"网游\"}}]}]</html>`
	result, err := resolver.extractCategoryRenderData(html)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	rawList := result["categoryData"].([]interface{})
	item := rawList[0].(map[string]interface{})
	partition := item["partition"].(map[string]interface{})
	if partition["title"] != "游戏" {
		t.Fatalf("partition title = %v; want 游戏", partition["title"])
	}
	childPartition := item["sub_partition"].([]interface{})[0].(map[string]interface{})
	cp := childPartition["partition"].(map[string]interface{})
	if cp["id_str"] != "2" {
		t.Fatalf("child id = %v; want 2", cp["id_str"])
	}
}

func TestDouyinExtractCategoryRenderData_MissingMarker(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	if _, err := resolver.extractCategoryRenderData(`<html><body>no data</body></html>`); err == nil {
		t.Fatal("expected error for missing marker")
	}
}

// ---------- status 测试 ----------

func TestDouyinStatus_IntValue(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	cases := []struct {
		room   map[string]interface{}
		want   int
	}{
		{map[string]interface{}{"status": float64(2)}, 2},
		{map[string]interface{}{"live_status": float64(1)}, 1},
		{map[string]interface{}{"room_status": map[string]interface{}{"live_status": float64(4)}}, 4},
		{map[string]interface{}{}, 0},
		// 新格式：liveStatus 以字符串形式存在（2026-11 抖音页面结构变更）
		{map[string]interface{}{"liveStatus": "normal"}, 4},
		{map[string]interface{}{"liveStatus": "live"}, 2},
		{map[string]interface{}{"liveStatus": "ing"}, 2},
		{map[string]interface{}{"liveStatus": "closed"}, 4},
		{map[string]interface{}{"liveStatus": "end"}, 4},
	}
	for _, c := range cases {
		got := resolver.status(c.room)
		if got != c.want {
			t.Fatalf("status = %d; want %d", got, c.want)
		}
	}
}

// ---------- roomAndUser / nestedRoom 测试 ----------

func TestDouyinRoomAndUser_DataListFormat(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	data := map[string]interface{}{
		"data": []interface{}{
			map[string]interface{}{"title": "Test Stream", "web_rid": "user123"},
		},
		"user": map[string]interface{}{"nickname": "TestAnchor", "web_rid": "user123"},
	}
	room, user := resolver.roomAndUser(data)
	if room["title"] != "Test Stream" {
		t.Fatalf("room title = %v; want Test Stream", room["title"])
	}
	if user["nickname"] != "TestAnchor" {
		t.Fatalf("user nickname = %v; want TestAnchor", user["nickname"])
	}
}

func TestDouyinNestedRoom_RoomField(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	data := map[string]interface{}{
		"room": map[string]interface{}{"title": "ViaRoom"},
	}
	nested := resolver.nestedRoom(data)
	if nested["title"] != "ViaRoom" {
		t.Fatalf("nested title = %v; want ViaRoom", nested["title"])
	}
}

func TestDouyinNestedRoom_RoomStorePath(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	data := map[string]interface{}{
		"roomStore": map[string]interface{}{
			"roomInfo": map[string]interface{}{
				"room": map[string]interface{}{"title": "ViaStore"},
			},
		},
	}
	nested := resolver.nestedRoom(data)
	if nested["title"] != "ViaStore" {
		t.Fatalf("nested title = %v; want ViaStore", nested["title"])
	}
}

// ---------- extractPlayCandidates 测试 ----------

func TestDouyinExtractPlayCandidates_UsesBestQuality(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	room := map[string]interface{}{
		"status": float64(2),
		"stream_url": map[string]interface{}{
			"live_core_sdk_data": map[string]interface{}{
				"pull_data": map[string]interface{}{
					"options": map[string]interface{}{
						"qualities": []interface{}{
							map[string]interface{}{"level": 1, "name": "高清", "sdk_key": "low"},
							map[string]interface{}{"level": 4, "name": "原画", "sdk_key": "high"},
						},
					},
					"stream_data": func() string {
						raw, _ := json.Marshal(map[string]interface{}{
							"data": map[string]interface{}{
								"low":  map[string]interface{}{"main": map[string]interface{}{"flv": "https://low.flv", "hls": "https://low.m3u8"}},
								"high": map[string]interface{}{"main": map[string]interface{}{"flv": "https://high.flv", "hls": "https://high.m3u8"}},
							},
						})
						return string(raw)
					}(),
				},
			},
		},
	}
	candidates, quality := resolver.extractPlayCandidates(room)
	if quality != "原画" {
		t.Fatalf("quality = %q; want 原画", quality)
	}
	urls := make([]string, len(candidates))
	for i, c := range candidates {
		urls[i] = c.URL
	}
	expected := []string{"https://high.flv", "https://high.m3u8"}
	if len(urls) != len(expected) {
		t.Fatalf("candidates count = %d; want %d: %v", len(urls), len(expected), urls)
	}
	for i, e := range expected {
		if urls[i] != e {
			t.Fatalf("candidate[%d] = %q; want %q", i, urls[i], e)
		}
	}
}

func TestDouyinExtractPlayCandidates_FallbackToFlvHlsPullURL(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	// qualities 为空时回退到 flv_pull_url / hls_pull_url_map
	room := map[string]interface{}{
		"status": float64(2),
		"stream_url": map[string]interface{}{
			"flv_pull_url": map[string]interface{}{
				"cdn1": "https://cdn1.example.com/live.flv",
				"cdn2": "https://cdn2.example.com/live.flv",
			},
			"hls_pull_url_map": map[string]interface{}{
				"cdn1": "https://cdn1.example.com/live.m3u8",
				"cdn2": "https://cdn2.example.com/live.m3u8",
			},
		},
	}
	candidates, quality := resolver.extractPlayCandidates(room)
	if len(candidates) == 0 {
		t.Fatal("expected some candidates via fallback")
	}
	if quality == "" {
		t.Fatal("expected quality name from fallback")
	}
	// 最低 level（highest）的 URL 应被选中
	found := false
	for _, c := range candidates {
		if c.URL == "https://cdn1.example.com/live.flv" || c.URL == "https://cdn1.example.com/live.m3u8" {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("expected top-quality CDN URL in candidates, got: %v", candidates)
	}
}

func TestDouyinExtractPlayCandidates_RecursiveFallback(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	// 没有任何 qualities，也没有 flv/hls pull URL，走递归兜底
	room := map[string]interface{}{
		"status": float64(2),
		"stream_url": map[string]interface{}{
			"url": "https://fallback.example.com/live.flv",
		},
	}
	candidates, _ := resolver.extractPlayCandidates(room)
	if len(candidates) == 0 {
		t.Fatal("expected recursive fallback candidate")
	}
	if candidates[0].URL != "https://fallback.example.com/live.flv" {
		t.Fatalf("candidate URL = %q; want https://fallback.example.com/live.flv", candidates[0].URL)
	}
}

// ---------- splitCategoryID 测试 ----------

func TestDouyinSplitCategoryID_Comma(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	pid, ptype, err := resolver.splitCategoryID("1,1")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if pid != "1" || ptype != "1" {
		t.Fatalf("pid=%q ptype=%q; want 1,1", pid, ptype)
	}
}

func TestDouyinSplitCategoryID_Colon(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	pid, ptype, err := resolver.splitCategoryID("5:sub5")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if pid != "5" || ptype != "sub5" {
		t.Fatalf("pid=%q ptype=%q; want 5,sub5", pid, ptype)
	}
}

func TestDouyinSplitCategoryID_Empty(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	if _, _, err := resolver.splitCategoryID(""); err == nil {
		t.Fatal("expected error for empty category ID")
	}
}

// ---------- onlineValue 测试 ----------

func TestDouyinOnlineValue_MapFields(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	v := map[string]interface{}{"display_value": "10000+", "user_count": float64(9999)}
	if got := resolver.onlineValue(v); got != 10000 {
		t.Fatalf("online = %d; want 10000 (from display_value)", got)
	}
}

func TestDouyinOnlineValue_DirectInt(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	if got := resolver.onlineValue(float64(500)); got != 500 {
		t.Fatalf("online = %d; want 500", got)
	}
}

// ---------- firstImage 测试 ----------

func TestDouyinFirstImage_URLList(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	img := map[string]interface{}{"url_list": []interface{}{"https://img.test/a.jpg", "https://img.test/b.jpg"}}
	if got := resolver.firstImage(img); got != "https://img.test/a.jpg" {
		t.Fatalf("image = %q; want https://img.test/a.jpg", got)
	}
}

func TestDouyinFirstImage_NoURLList(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	img := map[string]interface{}{"url": "https://img.test/c.jpg"}
	if got := resolver.firstImage(img); got != "https://img.test/c.jpg" {
		t.Fatalf("image = %q; want https://img.test/c.jpg", got)
	}
}

// ---------- randomToken 测试 ----------

func TestDouyinRandomToken_Length(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	token, err := resolver.randomToken(107)
	if err != nil {
		t.Fatalf("randomToken error: %v", err)
	}
	if len(token) != 107 {
		t.Fatalf("token length = %d; want 107", len(token))
	}
	// 验证只含字母数字
	for _, c := range token {
		ok := (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
		if !ok {
			t.Fatalf("token contains invalid char %q", string(c))
		}
	}
}

// ---------- GetCategoryTree 结构测试（不发起网络请求）----------

func TestDouyinGetCategoryTree_ReturnsCategoriesFromHTML(t *testing.T) {
	resolver := newDouyinResolver(10, "", "web")
	// 用 mock 替代 getJSON（这里通过 getText + extractCategoryRenderData 直接验证）
	html := `<html>\"categoryData\":[{\"partition\":{\"id_str\":\"1\",\"type\":\"1\",\"title\":\"游戏\"},\"sub_partition\":[{\"partition\":{\"id_str\":\"2\",\"type\":\"1\",\"title\":\"网游\"}},{\"partition\":{\"id_str\":\"3\",\"type\":\"1\",\"title\":\"单机\"}}]}]</html>`
	renderData, err := resolver.extractCategoryRenderData(html)
	if err != nil {
		t.Fatalf("extract error: %v", err)
	}
	rawList := renderData["categoryData"].([]interface{})
	if len(rawList) != 1 {
		t.Fatalf("got %d root categories; want 1", len(rawList))
	}
	parentItem := rawList[0].(map[string]interface{})
	partition := parentItem["partition"].(map[string]interface{})
	if partition["title"] != "游戏" {
		t.Fatalf("parent title = %v; want 游戏", partition["title"])
	}
	children := parentItem["sub_partition"].([]interface{})
	if len(children) != 2 {
		t.Fatalf("got %d children; want 2", len(children))
	}
	child0 := children[0].(map[string]interface{})
	cp0 := child0["partition"].(map[string]interface{})
	if cp0["id_str"] != "2" {
		t.Fatalf("child[0] id = %v; want 2", cp0["id_str"])
	}
}

// ---------- clientType 行为测试 ----------

func TestDouyinClientConfig_WebUA(t *testing.T) {
	cfg := douyinClientConfigFor("web")
	if cfg.userAgent == "" {
		t.Fatal("web clientType should have non-empty UA")
	}
	if cfg.apiParams["device_platform"] != "webapp" {
		t.Fatalf("web device_platform = %q; want webapp", cfg.apiParams["device_platform"])
	}
	if cfg.windowEnvStr == "" {
		t.Fatal("web window_env_str should not be empty")
	}
}

func TestDouyinClientConfig_MobileUA(t *testing.T) {
	cfg := douyinClientConfigFor("mobile")
	if cfg.userAgent == "" {
		t.Fatal("mobile clientType should have non-empty UA")
	}
	if cfg.apiParams["device_platform"] != "android" {
		t.Fatalf("mobile device_platform = %q; want android", cfg.apiParams["device_platform"])
	}
	if cfg.windowEnvStr == "" {
		t.Fatal("mobile window_env_str should not be empty")
	}
}

func TestDouyinClientConfig_TVUA(t *testing.T) {
	cfg := douyinClientConfigFor("tv")
	if cfg.userAgent == "" {
		t.Fatal("tv clientType should have non-empty UA")
	}
	if cfg.apiParams["device_platform"] != "android" {
		t.Fatalf("tv device_platform = %q; want android", cfg.apiParams["device_platform"])
	}
	if cfg.windowEnvStr == "" {
		t.Fatal("tv window_env_str should not be empty")
	}
}

func TestDouyinClientConfig_DefaultIsWeb(t *testing.T) {
	webCfg := douyinClientConfigFor("web")
	tvCfg := douyinClientConfigFor("tv")
	if webCfg.userAgent == tvCfg.userAgent {
		t.Fatal("web and tv should have different UAs")
	}
}

func TestMergeMapCopy(t *testing.T) {
	src := map[string]string{"a": "1", "b": "2"}
	dst := mergeMapCopy(src)
	dst["b"] = "3"
	if src["b"] == "3" {
		t.Fatal("mergeMapCopy should return independent copy")
	}
	if dst["a"] != "1" || dst["b"] != "3" {
		t.Fatalf("dst = %+v", dst)
	}
}
