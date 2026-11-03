package platformlive

import (
	"encoding/json"
	"net/url"
	"testing"
)

func TestParseMissevanRoomID(t *testing.T) {
	for input, want := range map[string]string{
		"123456":                               "123456",
		"https://fm.missevan.com/live/123456":  "123456",
		"https://fm.missevan.com/live/123456/": "123456",
	} {
		got, err := parseMissevanRoomID(input)
		if err != nil || got != want {
			t.Fatalf("parseMissevanRoomID(%q) = %q, %v; want %q", input, got, err, want)
		}
	}
	for _, input := range []string{
		"https://www.missevan.com/live/123456",
		"https://fm.missevan.com/sound/123456",
		"abc",
	} {
		if _, err := parseMissevanRoomID(input); err == nil {
			t.Fatalf("expected error for %q", input)
		}
	}
}

func TestMissevanCategoryTreeAndRooms(t *testing.T) {
	resolver := newMissevanResolver(2)
	resolver.apiFn = func(path string, params url.Values) (map[string]interface{}, error) {
		switch path {
		case "meta/data":
			return map[string]interface{}{
				"tabs": []interface{}{
					map[string]interface{}{"type": "catalog", "catalog_id": float64(7), "name": "广播剧"},
					map[string]interface{}{"type": "tag", "tag_id": float64(9), "name": "活动"},
					map[string]interface{}{"type": "other", "name": "忽略"},
				},
			}, nil
		case "chatroom/open/list":
			if params.Get("catalog_id") != "7" {
				t.Fatalf("catalog_id = %q; want 7", params.Get("catalog_id"))
			}
			return map[string]interface{}{
				"pagination": map[string]interface{}{"maxpage": float64(2)},
				"Datas": []interface{}{
					map[string]interface{}{
						"room_id":          float64(123456),
						"name":             "测试直播间",
						"creator_username": "主播",
						"cover_url":        "//image.example.com/cover.jpg",
						"statistics":       map[string]interface{}{"score": float64(88)},
						"status":           map[string]interface{}{"open": float64(1)},
						"catalog_name":     "广播剧",
					},
					map[string]interface{}{
						"room_id": "999",
						"name":    "离线房间",
						"status":  map[string]interface{}{"open": float64(0)},
					},
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
	if len(parents) != 1 || parents[0].ID != "missevan" {
		t.Fatalf("parents = %+v", parents)
	}
	if len(categories) != 2 || categories[0].ID != "catalog:7" || categories[1].ID != "tag:9" {
		t.Fatalf("categories = %+v", categories)
	}
	page, err := resolver.GetCategoryRooms("catalog:7", 1)
	if err != nil {
		t.Fatalf("GetCategoryRooms error: %v", err)
	}
	if page.PageCount != 2 || len(page.Rooms) != 1 {
		t.Fatalf("page = %+v", page)
	}
	if page.Rooms[0].RoomID != "123456" || page.Rooms[0].Cover != "https://image.example.com/cover.jpg" {
		t.Fatalf("room = %+v", page.Rooms[0])
	}
}

func TestMissevanResolveReturnsHLSAndFLV(t *testing.T) {
	resolver := newMissevanResolver(2)
	resolver.apiFn = func(path string, params url.Values) (map[string]interface{}, error) {
		if path != "live/123456" {
			t.Fatalf("path = %q", path)
		}
		return map[string]interface{}{
			"room": map[string]interface{}{
				"room_id":          float64(123456),
				"name":             "测试直播间",
				"creator_username": "旧主播名",
				"status":           map[string]interface{}{"open": float64(1)},
				"channel": map[string]interface{}{
					"hls_pull_url": "https://cdn.example.bilivideo.com/live.m3u8?expires=1234567890",
					"flv_pull_url": "//cdn.example.bilivideo.com/live.flv?expires=1234567890",
				},
			},
			"creator": map[string]interface{}{"nickname": "新主播名"},
		}, nil
	}
	stream, err := resolver.Resolve("123456")
	if err != nil {
		t.Fatalf("Resolve error: %v", err)
	}
	if stream.Anchor != "新主播名" || len(stream.Candidates) != 2 {
		t.Fatalf("stream = %+v", stream)
	}
	if stream.Candidates[0].Protocol != "hls" || stream.Candidates[1].URL != "https://cdn.example.bilivideo.com/live.flv?expires=1234567890" {
		t.Fatalf("candidates = %+v", stream.Candidates)
	}
}

func TestMissevanResolveRejectsOfflineRoom(t *testing.T) {
	resolver := newMissevanResolver(2)
	resolver.apiFn = func(path string, params url.Values) (map[string]interface{}, error) {
		return map[string]interface{}{
			"room": map[string]interface{}{"status": map[string]interface{}{"open": float64(0)}},
		}, nil
	}
	if _, err := resolver.Resolve("123456"); err == nil {
		t.Fatalf("expected offline error")
	}
}

func TestMissevanJSONNumberKeepsLargeIDs(t *testing.T) {
	payload, err := parseJSONPreserveNumbers([]byte(`{"room_id":180000000000000001}`))
	if err != nil {
		t.Fatalf("parseJSONPreserveNumbers error: %v", err)
	}
	if got := TextStrict(jsonObject(payload)["room_id"]); got != "180000000000000001" {
		t.Fatalf("room_id = %q", got)
	}
	if _, ok := ToInt(json.Number("180000000000000001")); !ok {
		t.Fatalf("ToInt(json.Number) failed")
	}
}
