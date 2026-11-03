package platformlive

import (
	"encoding/json"
	"net/url"
	"testing"
)

func TestParseInkeRoomID(t *testing.T) {
	for input, want := range map[string]string{
		"123456": "123456",
		"https://www.inke.cn/liveroom/index.html?uid=123456":     "123456",
		"https://inke.com/liveroom/index.html?uid=123456&id=999": "123456",
	} {
		got, err := parseInkeRoomID(input)
		if err != nil || got != want {
			t.Fatalf("parseInkeRoomID(%q) = %q, %v; want %q", input, got, err, want)
		}
	}
	for _, input := range []string{
		"https://www.inke.cn/liveroom/index.html",
		"https://www.inke.cn/liveroom/index.html?uid=abc",
		"https://example.com/liveroom/index.html?uid=123456",
	} {
		if _, err := parseInkeRoomID(input); err == nil {
			t.Fatalf("expected error for %q", input)
		}
	}
}

func TestInkeCategoryTreeAndShowcaseRooms(t *testing.T) {
	resolver := newInkeResolver(2)
	resolver.apiFn = func(path string, params url.Values) (map[string]interface{}, error) {
		switch path {
		case "Live_top_pc":
			return map[string]interface{}{
				"list": []interface{}{
					map[string]interface{}{"uid": float64(123456), "live_id": float64(999), "nick": "热门主播", "portrait": "https://img.inke.cn/a.jpg"},
				},
			}, nil
		case "Live_channel_pc":
			return map[string]interface{}{
				"list": []interface{}{
					map[string]interface{}{"tab_key": "music", "channel_name": "音乐", "list": []interface{}{}},
					map[string]interface{}{"tab_key": "game", "channel_name": "游戏", "list": []interface{}{}},
				},
			}, nil
		default:
			t.Fatalf("unexpected path %q", path)
			return nil, nil
		}
	}
	parents, categories, err := resolver.GetCategoryTree()
	if err != nil {
		t.Fatalf("GetCategoryTree error: %v", err)
	}
	if len(parents) != 1 || len(categories) != 2 || categories[0].ID != "channel:music" {
		t.Fatalf("parents=%+v categories=%+v", parents, categories)
	}
	page, err := resolver.GetCategoryRooms("top", 1)
	if err != nil {
		t.Fatalf("GetCategoryRooms error: %v", err)
	}
	if page.PageCount != 1 || len(page.Rooms) != 1 || page.Rooms[0].RoomID != "123456" {
		t.Fatalf("page = %+v", page)
	}
}

func TestInkeResolveReusesCurrentBroadcastFLV(t *testing.T) {
	const uid = "123456"
	const broadcastID = "180000000000000001"
	resolver := newInkeResolver(2)
	resolver.apiFn = func(path string, params url.Values) (map[string]interface{}, error) {
		switch path {
		case "live_share_pc":
			if params.Get("uid") != uid {
				t.Fatalf("uid = %q", params.Get("uid"))
			}
			return map[string]interface{}{
				"live_uid":  uid,
				"status":    float64(1),
				"liveid":    json.Number(broadcastID),
				"live_name": "测试直播",
				"media_info": map[string]interface{}{
					"inke_id":  uid,
					"nick":     "测试主播",
					"portrait": "https://img.inke.cn/a.jpg",
				},
			}, nil
		case "Live_top_pc":
			return map[string]interface{}{
				"list": []interface{}{
					map[string]interface{}{
						"uid":         uid,
						"live_id":     json.Number(broadcastID),
						"nick":        "测试主播",
						"stream_addr": "https://live-pull-ws.ikstatic.cn/live/" + broadcastID + "_t.flv?sign=abc",
					},
				},
			}, nil
		default:
			t.Fatalf("unexpected path %q", path)
			return nil, nil
		}
	}
	stream, err := resolver.Resolve(uid)
	if err != nil {
		t.Fatalf("Resolve error: %v", err)
	}
	if stream.Title != "测试直播" || stream.Anchor != "测试主播" || len(stream.Candidates) != 1 {
		t.Fatalf("stream = %+v", stream)
	}
	if stream.Candidates[0].Protocol != "flv" ||
		stream.Candidates[0].URL != "https://live-pull-ws.ikstatic.cn/live/"+broadcastID+"_t.flv?sign=abc" {
		t.Fatalf("candidate = %+v", stream.Candidates[0])
	}
}

func TestInkeResolveRejectsOfflineWithoutMedia(t *testing.T) {
	resolver := newInkeResolver(2)
	resolver.apiFn = func(path string, params url.Values) (map[string]interface{}, error) {
		if path != "live_share_pc" {
			t.Fatalf("path = %q", path)
		}
		return map[string]interface{}{
			"live_uid": "123456",
			"status":   float64(0),
		}, nil
	}
	if _, err := resolver.Resolve("123456"); err == nil {
		t.Fatalf("expected offline error")
	}
}

func TestInkeFlvURLRejectsStaleOrForeignLease(t *testing.T) {
	const broadcastID = "180000000000000001"
	cases := []struct {
		url  string
		want bool
	}{
		{"https://live-pull-ws.ikstatic.cn/live/" + broadcastID + "_t.flv?sign=abc", true},
		{"https://live-pull-ws.ikstatic.cn/live/999_t.flv?sign=abc", false},
		{"https://other.ikstatic.cn/live/" + broadcastID + "_t.flv", false},
		{"http://127.0.0.1/live/" + broadcastID + "_t.flv", false},
	}
	for _, c := range cases {
		if _, ok := inkeFlvURL(c.url, broadcastID); ok != c.want {
			t.Fatalf("inkeFlvURL(%q) = %v; want %v", c.url, ok, c.want)
		}
	}
}
