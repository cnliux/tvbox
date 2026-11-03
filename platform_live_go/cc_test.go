package platformlive

import (
	"encoding/json"
	"net/url"
	"testing"
)

func TestParseCCRoomID(t *testing.T) {
	for input, want := range map[string]string{
		"123456":                     "123456",
		"https://cc.163.com/123456/": "123456",
		"https://cc.163.com/123456":  "123456",
	} {
		got, err := parseCCRoomID(input)
		if err != nil || got != want {
			t.Fatalf("parseCCRoomID(%q) = %q, %v; want %q", input, got, err, want)
		}
	}
	for _, input := range []string{
		"https://cc.163.com/n/ds_category/123/",
		"https://cc.163.com/123/456",
		"https://example.com/123/",
		"abc",
	} {
		if _, err := parseCCRoomID(input); err == nil {
			t.Fatalf("expected error for %q", input)
		}
	}
}

func TestCCCatalogAndOfficialRooms(t *testing.T) {
	resolver := newCCResolver(2)
	resolver.getFn = func(rawURL string, params url.Values) (interface{}, error) {
		if rawURL != ccGameCatalogURL {
			t.Fatalf("unexpected GET %q", rawURL)
		}
		if params.Get("gameType") != "NETEASE" {
			t.Fatalf("gameType = %q", params.Get("gameType"))
		}
		return map[string]interface{}{
			"code": float64(200),
			"result": []interface{}{
				map[string]interface{}{"appKey": "a", "name": "游戏A", "icon": "https://cc.163.com/a.png"},
				map[string]interface{}{"appKey": "b", "name": "游戏B", "icon": "https://cc.163.com/b.png"},
			},
		}, nil
	}
	resolver.postFn = func(rawURL string, body []byte) (interface{}, error) {
		if rawURL != ccConfigURL {
			t.Fatalf("unexpected POST %q", rawURL)
		}
		if !json.Valid(body) {
			t.Fatalf("invalid JSON body: %s", body)
		}
		return map[string]interface{}{
			"code": float64(200),
			"result": map[string]interface{}{
				"id": ccConfigID,
				"itemList": []interface{}{
					map[string]interface{}{
						"name": "直播入口列表",
						"itemList": []interface{}{
							map[string]interface{}{"name": "a", "content": "https://cc.163.com/n/ds_category/111/"},
							map[string]interface{}{"name": "b", "content": "https://cc.163.com/222/"},
							map[string]interface{}{"name": "a", "content": "https://cc.163.com/n/ds_category/333/", "hidden": true},
						},
					},
				},
			},
		}, nil
	}
	parents, categories, err := resolver.GetCategoryTree()
	if err != nil {
		t.Fatalf("GetCategoryTree error: %v", err)
	}
	if len(parents) != 2 || len(categories) != 2 {
		t.Fatalf("parents=%+v categories=%+v", parents, categories)
	}
	if categories[0].ID != "game:111" || categories[0].ParentID != "cc:games" ||
		categories[1].ID != "official:all" || categories[1].ParentID != "cc:official" {
		t.Fatalf("categories = %+v", categories)
	}
	page, err := resolver.GetCategoryRooms("official:all", 1)
	if err != nil {
		t.Fatalf("official rooms error: %v", err)
	}
	if len(page.Rooms) != 1 || page.Rooms[0].RoomID != "222" || page.Rooms[0].Title != "游戏B" {
		t.Fatalf("official page = %+v", page)
	}
}

func TestCCCategoryRooms(t *testing.T) {
	resolver := newCCResolver(2)
	resolver.getFn = func(rawURL string, params url.Values) (interface{}, error) {
		if rawURL != ccOrigin+"/api/category/111/" {
			t.Fatalf("unexpected GET %q", rawURL)
		}
		if params.Get("start") != "0" || params.Get("size") != "30" {
			t.Fatalf("params = %v", params)
		}
		return map[string]interface{}{
			"gametype": "111",
			"lives": []interface{}{
				map[string]interface{}{
					"cuteid":    json.Number("123456789012345678"),
					"title":     "测试直播间",
					"nickname":  "测试主播",
					"cover":     "https://cc.163.com/cover.jpg",
					"hot_score": float64(9999),
					"game_name": "游戏A",
				},
			},
		}, nil
	}
	page, err := resolver.GetCategoryRooms("game:111", 1)
	if err != nil {
		t.Fatalf("GetCategoryRooms error: %v", err)
	}
	if page.PageCount != 1 || len(page.Rooms) != 1 {
		t.Fatalf("page = %+v", page)
	}
	if page.Rooms[0].RoomID != "123456789012345678" || page.Rooms[0].Online != 9999 {
		t.Fatalf("room = %+v", page.Rooms[0])
	}
}

func TestCCResolveChoosesBestQualityAndExpandsRelativeCDN(t *testing.T) {
	const roomID = "123456"
	resolver := newCCResolver(2)
	resolver.getFn = func(rawURL string, params url.Values) (interface{}, error) {
		switch rawURL {
		case ccActivityAPI + "/v1/activitylives/anchor/lives":
			if params.Get("anchor_ccid") != roomID {
				t.Fatalf("anchor_ccid = %q", params.Get("anchor_ccid"))
			}
			return map[string]interface{}{
				"data": map[string]interface{}{
					roomID: map[string]interface{}{"channel_id": "8888"},
				},
			}, nil
		case ccOrigin + "/live/channel/":
			if params.Get("channelids") != "8888" || params.Get("anchor_ccid") != roomID {
				t.Fatalf("params = %v", params)
			}
			return map[string]interface{}{
				"data": []interface{}{
					map[string]interface{}{
						"ccid":     roomID,
						"status":   float64(1),
						"title":    "测试直播间",
						"nickname": "测试主播",
						"cover":    "https://cc.163.com/cover.jpg",
						"m3u8":     "https://cc.163.com/live/" + roomID + ".m3u8",
						"quickplay": map[string]interface{}{
							"high": map[string]interface{}{
								"vbr": float64(2000),
								"CDN_FMT": map[string]interface{}{
									"ks": "https://cdn.163.com/live/high.flv",
								},
							},
							"original": map[string]interface{}{
								"vbr": float64(6000),
								"CDN_FMT": map[string]interface{}{
									"hs": "?sign=original",
									"ks": "https://cdn.163.com/live/original.flv",
								},
							},
						},
					},
				},
			}, nil
		default:
			t.Fatalf("unexpected GET %q", rawURL)
			return nil, nil
		}
	}
	stream, err := resolver.Resolve(roomID)
	if err != nil {
		t.Fatalf("Resolve error: %v", err)
	}
	if stream.Quality != "原画" || len(stream.Candidates) != 2 {
		t.Fatalf("stream = %+v", stream)
	}
	urls := map[string]bool{}
	for _, candidate := range stream.Candidates {
		urls[candidate.URL] = true
	}
	if !urls["https://cc.163.com/live/"+roomID+".m3u8?sign=original"] ||
		!urls["https://cdn.163.com/live/original.flv"] {
		t.Fatalf("candidates = %+v", stream.Candidates)
	}
}

func TestCCResolveStreamListRelativeQueries(t *testing.T) {
	resolver := newCCResolver(2)
	resolver.getFn = func(rawURL string, params url.Values) (interface{}, error) {
		switch rawURL {
		case ccActivityAPI + "/v1/activitylives/anchor/lives":
			return map[string]interface{}{"data": map[string]interface{}{"5200": map[string]interface{}{"channel_id": "4941662"}}}, nil
		case ccOrigin + "/live/channel/":
			return map[string]interface{}{
				"data": []interface{}{
					map[string]interface{}{
						"ccid":   "5200",
						"status": float64(1),
						"title":  "重播房间",
						"m3u8":   "http://cgi.v.cc.163.com/redirect/video/5200.m3u8?secret=abc",
						"stream_list": map[string]interface{}{
							"original": map[string]interface{}{
								"vbr": float64(7999),
								"CDN_FMT": map[string]interface{}{
									"fws": "wsSecret=xyz&wsTime=123",
								},
							},
							"low": map[string]interface{}{
								"vbr": float64(600),
								"CDN_FMT": map[string]interface{}{
									"fws": "wsSecret=low&wsTime=123",
								},
							},
						},
					},
				},
			}, nil
		default:
			t.Fatalf("unexpected GET %q", rawURL)
			return nil, nil
		}
	}
	stream, err := resolver.Resolve("5200")
	if err != nil {
		t.Fatalf("Resolve error: %v", err)
	}
	if stream.Quality != "原画" || len(stream.Candidates) != 1 {
		t.Fatalf("stream = %+v", stream)
	}
	if stream.Candidates[0].URL != "http://cgi.v.cc.163.com/redirect/video/5200.m3u8?secret=abc&wsSecret=xyz&wsTime=123" {
		t.Fatalf("candidate URL = %q", stream.Candidates[0].URL)
	}
}

func TestCCResolveRejectsOfflineRoom(t *testing.T) {
	resolver := newCCResolver(2)
	resolver.getFn = func(rawURL string, params url.Values) (interface{}, error) {
		switch rawURL {
		case ccActivityAPI + "/v1/activitylives/anchor/lives":
			return map[string]interface{}{"data": map[string]interface{}{"123456": map[string]interface{}{"channel_id": "8888"}}}, nil
		case ccOrigin + "/live/channel/":
			return map[string]interface{}{
				"data": []interface{}{
					map[string]interface{}{"ccid": "123456", "status": float64(0), "title": "离线"},
				},
			}, nil
		default:
			t.Fatalf("unexpected GET %q", rawURL)
			return nil, nil
		}
	}
	if _, err := resolver.Resolve("123456"); err == nil {
		t.Fatalf("expected offline error")
	}
}
