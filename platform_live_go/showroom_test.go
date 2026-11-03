package platformlive

import (
	"net/url"
	"testing"
)

func TestParseShowroomRoomID(t *testing.T) {
	for input, want := range map[string]string{
		"123456": "123456",
		"https://www.showroom-live.com/r/test_room":                 "test_room",
		"https://showroom-live.com/test_room":                       "test_room",
		"https://www.showroom-live.com/room/profile?room_id=123456": "123456",
	} {
		got, err := parseShowroomRoomID(input)
		if err != nil || got != want {
			t.Fatalf("parseShowroomRoomID(%q) = %q, %v; want %q", input, got, err, want)
		}
	}
	for _, input := range []string{
		"https://www.showroom-live.com/room/profile",
		"https://www.showroom-live.com/room",
		"https://example.com/r/test_room",
	} {
		if _, err := parseShowroomRoomID(input); err == nil {
			t.Fatalf("expected error for %q", input)
		}
	}
}

func TestShowroomCategoryTreeAndLocalPagination(t *testing.T) {
	resolver := newShowroomResolver(2)
	lives := make([]interface{}, 0, 35)
	for index := 1; index <= 35; index++ {
		lives = append(lives, map[string]interface{}{
			"room_id":      float64(index),
			"main_name":    "房间",
			"view_num":     float64(index),
			"image_square": "https://image.showroom-live.com/cover.jpg",
		})
	}
	lives = append(lives, map[string]interface{}{"cell_type": float64(7), "main_name": "暂无直播"})
	resolver.apiFn = func(path string, params url.Values) (interface{}, error) {
		if path != "/api/live/onlives" {
			t.Fatalf("path = %q", path)
		}
		return map[string]interface{}{
			"onlives": []interface{}{
				map[string]interface{}{"genre_id": float64(1), "genre_name": "音乐", "lives": lives},
			},
		}, nil
	}
	parents, categories, err := resolver.GetCategoryTree()
	if err != nil {
		t.Fatalf("GetCategoryTree error: %v", err)
	}
	if len(parents) != 1 || len(categories) != 1 || categories[0].ID != "1" {
		t.Fatalf("parents=%+v categories=%+v", parents, categories)
	}
	page, err := resolver.GetCategoryRooms("1", 2)
	if err != nil {
		t.Fatalf("GetCategoryRooms error: %v", err)
	}
	if page.PageCount != 2 || len(page.Rooms) != 5 {
		t.Fatalf("page = %+v", page)
	}
	if page.Rooms[0].RoomID != "31" {
		t.Fatalf("first room = %+v", page.Rooms[0])
	}
}

func TestShowroomResolvePrefersAdaptiveHLS(t *testing.T) {
	resolver := newShowroomResolver(2)
	resolver.apiFn = func(path string, params url.Values) (interface{}, error) {
		switch path {
		case "/api/room/profile":
			return map[string]interface{}{
				"room_id":      float64(123456),
				"main_name":    "测试房间",
				"is_onlive":    true,
				"image_square": "https://image.showroom-live.com/cover.jpg",
			}, nil
		case "/api/live/live_info":
			return map[string]interface{}{"room_id": float64(123456), "live_status": float64(2)}, nil
		case "/api/live/streaming_url":
			return map[string]interface{}{
				"streaming_url_list": []interface{}{
					map[string]interface{}{"type": "hls", "label": "低画质", "quality": float64(200), "url": "https://cdn.showroom-live.com/low.m3u8"},
					map[string]interface{}{"type": "hls_all", "label": "自动", "quality": float64(1000), "url": "https://cdn.showroom-live.com/all.m3u8"},
					map[string]interface{}{"type": "hls", "label": "原画", "quality": float64(1000), "url": "https://cdn.showroom-live.com/high.m3u8"},
					map[string]interface{}{"type": "dash", "url": "https://cdn.showroom-live.com/ignore.mpd"},
				},
			}, nil
		default:
			t.Fatalf("unexpected path %q", path)
			return nil, nil
		}
	}
	stream, err := resolver.Resolve("123456")
	if err != nil {
		t.Fatalf("Resolve error: %v", err)
	}
	if stream.Title != "测试房间" || stream.Quality != "原画" || len(stream.Candidates) != 3 {
		t.Fatalf("stream = %+v", stream)
	}
	if stream.Candidates[0].CDN != "自动" || stream.Candidates[0].URL != "https://cdn.showroom-live.com/all.m3u8" {
		t.Fatalf("candidates = %+v", stream.Candidates)
	}
}

func TestShowroomResolveRejectsOfflineRoom(t *testing.T) {
	resolver := newShowroomResolver(2)
	resolver.apiFn = func(path string, params url.Values) (interface{}, error) {
		switch path {
		case "/api/room/profile":
			return map[string]interface{}{"room_id": float64(123456), "is_onlive": false}, nil
		default:
			return map[string]interface{}{}, nil
		}
	}
	if _, err := resolver.Resolve("123456"); err == nil {
		t.Fatalf("expected offline error")
	}
}

func TestShowroomResolveBySlugUsesStatusEndpoint(t *testing.T) {
	resolver := newShowroomResolver(2)
	resolver.apiFn = func(path string, params url.Values) (interface{}, error) {
		switch path {
		case "/api/room/status":
			if params.Get("room_url_key") != "test_room" {
				t.Fatalf("room_url_key = %q", params.Get("room_url_key"))
			}
			return map[string]interface{}{"room_id": float64(123456)}, nil
		case "/api/room/profile":
			return map[string]interface{}{"room_id": float64(123456), "main_name": "测试房间", "is_onlive": true}, nil
		case "/api/live/live_info":
			return map[string]interface{}{"room_id": float64(123456), "live_status": float64(2)}, nil
		case "/api/live/streaming_url":
			return map[string]interface{}{
				"streaming_url_list": []interface{}{
					map[string]interface{}{"type": "hls", "label": "默认", "quality": float64(1000), "url": "https://cdn.showroom-live.com/live.m3u8"},
				},
			}, nil
		default:
			t.Fatalf("unexpected path %q", path)
			return nil, nil
		}
	}
	stream, err := resolver.Resolve("test_room")
	if err != nil {
		t.Fatalf("Resolve error: %v", err)
	}
	if stream.RoomID != "123456" || len(stream.Candidates) != 1 {
		t.Fatalf("stream = %+v", stream)
	}
}
