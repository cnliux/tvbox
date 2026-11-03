package platformlive

import (
	"net/url"
	"strings"
	"testing"
)

const testWeiboLiveID = "1022:2321321234567890123456"

func TestParseWeiboLiveID(t *testing.T) {
	for input, want := range map[string]string{
		testWeiboLiveID: testWeiboLiveID,
		"https://weibo.com/l/wblive/p/show/1022%3A2321321234567890123456": testWeiboLiveID,
		"https://weibo.com/l/wblive/m/show/1022%3A2321321234567890123456": testWeiboLiveID,
	} {
		got, err := parseWeiboLiveID(input)
		if err != nil || got != want {
			t.Fatalf("parseWeiboLiveID(%q) = %q, %v; want %q", input, got, err, want)
		}
	}
	for _, input := range []string{
		"https://weibo.com/l/wblive/p/show/123",
		"https://weibo.com/u/123456",
		"https://example.com/l/wblive/p/show/" + testWeiboLiveID,
	} {
		if _, err := parseWeiboLiveID(input); err == nil {
			t.Fatalf("expected error for %q", input)
		}
	}
}

func TestWeiboDirectoryAndStaticCategories(t *testing.T) {
	resolver := newWeiboResolver(2)
	resolver.apiFn = func(path string, params url.Values) (map[string]interface{}, error) {
		if path != "/l/!/2/wblive/pc_recommend/list.json" {
			t.Fatalf("path = %q", path)
		}
		if params.Get("count") != "100" {
			t.Fatalf("count = %q", params.Get("count"))
		}
		return map[string]interface{}{
			"data": []interface{}{
				map[string]interface{}{
					"liveid":   testWeiboLiveID,
					"uid":      float64(123),
					"nickname": "测试主播",
					"cover":    "https://wx1.sinaimg.cn/cover.jpg",
				},
				map[string]interface{}{"liveid": "bad-id", "nickname": "忽略"},
			},
		}, nil
	}
	parents, categories, err := resolver.GetCategoryTree()
	if err != nil {
		t.Fatalf("GetCategoryTree error: %v", err)
	}
	if len(parents) != 1 || len(categories) != 1 || categories[0].ID != "weibo:live" {
		t.Fatalf("parents=%+v categories=%+v", parents, categories)
	}
	page, err := resolver.GetCategoryRooms("weibo:live", 1)
	if err != nil {
		t.Fatalf("GetCategoryRooms error: %v", err)
	}
	if len(page.Rooms) != 1 || page.Rooms[0].RoomID != testWeiboLiveID {
		t.Fatalf("page = %+v", page)
	}
}

func TestWeiboResolveReturnsPublicMedia(t *testing.T) {
	resolver := newWeiboResolver(2)
	resolver.apiFn = func(path string, params url.Values) (map[string]interface{}, error) {
		if path != "/l/!/2/wblive/room/show_pc_live.json" {
			t.Fatalf("path = %q", path)
		}
		if params.Get("live_id") != testWeiboLiveID {
			t.Fatalf("live_id = %q", params.Get("live_id"))
		}
		return map[string]interface{}{
			"liveId":              testWeiboLiveID,
			"title":               "测试直播",
			"status":              float64(1),
			"play_switch":         float64(1),
			"watch_limit":         float64(0),
			"width":               float64(1920),
			"height":              float64(1080),
			"user":                map[string]interface{}{"uid": float64(123), "screenName": "测试主播"},
			"live_origin_hls_url": "https://live.weibo.com/hls/live.m3u8",
			"live_origin_flv_url": "https://live.weibo.com/flv/live.flv",
		}, nil
	}
	stream, err := resolver.Resolve(testWeiboLiveID)
	if err != nil {
		t.Fatalf("Resolve error: %v", err)
	}
	if stream.Title != "测试直播" || stream.Anchor != "测试主播" || len(stream.Candidates) != 2 {
		t.Fatalf("stream = %+v", stream)
	}
	if stream.Candidates[0].Protocol != "hls" || stream.Candidates[1].Protocol != "flv" {
		t.Fatalf("candidates = %+v", stream.Candidates)
	}
}

func TestWeiboResolveRejectsNonPlayableStates(t *testing.T) {
	cases := []struct {
		name string
		item map[string]interface{}
	}{
		{
			name: "replay",
			item: map[string]interface{}{
				"liveId": testWeiboLiveID, "status": float64(3),
				"play_switch": float64(1), "watch_limit": float64(0),
				"user": map[string]interface{}{"uid": float64(1), "screenName": "主播"},
			},
		},
		{
			name: "offline",
			item: map[string]interface{}{
				"liveId": testWeiboLiveID, "status": float64(5),
				"play_switch": float64(1), "watch_limit": float64(0),
				"user": map[string]interface{}{"uid": float64(1), "screenName": "主播"},
			},
		},
		{
			name: "web disabled",
			item: map[string]interface{}{
				"liveId": testWeiboLiveID, "status": float64(1),
				"play_switch": float64(0), "watch_limit": float64(0),
				"user": map[string]interface{}{"uid": float64(1), "screenName": "主播"},
			},
		},
		{
			name: "app only",
			item: map[string]interface{}{
				"liveId": testWeiboLiveID, "status": float64(1),
				"play_switch": float64(1), "watch_limit": float64(8),
				"user": map[string]interface{}{"uid": float64(1), "screenName": "主播"},
			},
		},
		{
			name: "missing media",
			item: map[string]interface{}{
				"liveId": testWeiboLiveID, "status": float64(1),
				"play_switch": float64(1), "watch_limit": float64(0),
				"user": map[string]interface{}{"uid": float64(1), "screenName": "主播"},
			},
		},
	}
	for _, c := range cases {
		resolver := newWeiboResolver(2)
		resolver.apiFn = func(path string, params url.Values) (map[string]interface{}, error) {
			return c.item, nil
		}
		if _, err := resolver.Resolve(testWeiboLiveID); err == nil {
			t.Fatalf("%s: expected error", c.name)
		}
	}
}

func TestWeiboAlternateLiveIDFormat(t *testing.T) {
	alternate := "1042152:" + strings.Repeat("a", 32)
	if _, err := parseWeiboLiveID(alternate); err != nil {
		t.Fatalf("alternate live id rejected: %v", err)
	}
}
