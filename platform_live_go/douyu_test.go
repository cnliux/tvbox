package platformlive

// 斗鱼解析的单元测试：移植 platform_live_server/test_douyu_resolver.py。

import "testing"

func TestParseDouyuRoomIDAcceptsNumericRoomID(t *testing.T) {
	value, err := parseDouyuRoomID("36252")
	if err != nil || value != "36252" {
		t.Fatalf("parseDouyuRoomID('36252') = %q, %v", value, err)
	}
}

func TestParseDouyuRoomIDExtractsFromDesktopOrMobileURL(t *testing.T) {
	for input, want := range map[string]string{
		"https://www.douyu.com/36252": "36252",
		"https://m.douyu.com/4258555": "4258555",
	} {
		value, err := parseDouyuRoomID(input)
		if err != nil || value != want {
			t.Fatalf("parseDouyuRoomID(%q) = %q, %v; want %q", input, value, err, want)
		}
	}
	// Python 版同样只接受 /<数字> 形式的链接，其余报错。
	if _, err := parseDouyuRoomID("https://www.douyu.com/topic?id=36252"); err == nil {
		t.Fatalf("expected error for non /<digits> douyu link")
	}
}

func TestParseDouyuRoomIDRejectsNonNumericInput(t *testing.T) {
	if _, err := parseDouyuRoomID("not-a-room"); err == nil {
		t.Fatalf("expected error for 'not-a-room'")
	}
}

func TestOrderDouyuCdnsPutsScdnLast(t *testing.T) {
	cdns := orderDouyuCdns([]interface{}{
		map[string]interface{}{"cdn": "scdn-late"},
		map[string]interface{}{"cdn": "hw-h5"},
		map[string]interface{}{"cdn": "hw-h5"},
		map[string]interface{}{"cdn": "tx-h5"},
	})
	want := []string{"hw-h5", "tx-h5", "scdn-late"}
	if len(cdns) != len(want) {
		t.Fatalf("orderDouyuCdns = %v; want %v", cdns, want)
	}
	for index := range want {
		if cdns[index] != want[index] {
			t.Fatalf("orderDouyuCdns = %v; want %v", cdns, want)
		}
	}
}

// 对齐 test_reuses_one_signature_for_all_cdn_candidates：
// 签名只算一次，多个 CDN 线路复用同一份 playArgs。
func TestDouyuBestPlayUrlsReusesOneSignature(t *testing.T) {
	resolver := newDouyuResolver(2)
	playArgsCalls := 0
	resolver.playArgsFn = func(roomID string) (map[string]string, error) {
		playArgsCalls++
		return map[string]string{"sign": "token"}, nil
	}
	playInfoCalls := 0
	var recordedRates []int64
	resolver.playInfoFn = func(roomID string, playArgs map[string]string, cdn string, rate int64) (map[string]interface{}, error) {
		playInfoCalls++
		recordedRates = append(recordedRates, rate)
		playArgsContract := playArgs["sign"]
		if playArgsContract != "token" {
			t.Fatalf("playArgs 未复用：%v", playArgs)
		}
		switch playInfoCalls {
		case 1:
			return map[string]interface{}{
				"multirates": []interface{}{map[string]interface{}{"rate": float64(4), "name": "原画"}},
				"cdnsWithName": []interface{}{
					map[string]interface{}{"cdn": "hw-h5"},
					map[string]interface{}{"cdn": "tx-h5"},
				},
			}, nil
		case 2:
			return map[string]interface{}{
				"rtmp_url":  "https://hw.example",
				"rtmp_live": "live.flv?token=one",
			}, nil
		default:
			return map[string]interface{}{
				"rtmp_url":  "https://tx.example",
				"rtmp_live": "live.flv?token=two",
			}, nil
		}
	}

	quality, candidates, err := resolver.getBestPlayUrls("36252")
	if err != nil {
		t.Fatalf("getBestPlayUrls error: %v", err)
	}
	if quality != "原画" {
		t.Fatalf("quality = %q; want 原画", quality)
	}
	if playArgsCalls != 1 {
		t.Fatalf("playArgs 调用次数 = %d; want 1", playArgsCalls)
	}
	if len(recordedRates) != 3 || recordedRates[0] != -1 {
		t.Fatalf("getBestPlayInfo 调用序 = %v", recordedRates)
	}
	wantCDNs := []string{"hw-h5", "tx-h5"}
	wantURLs := []string{
		"https://hw.example/live.flv?token=one",
		"https://tx.example/live.flv?token=two",
	}
	for index, want := range wantCDNs {
		if candidates[index].cdn != want {
			t.Fatalf("candidates[%d].cdn = %q; want %q", index, candidates[index].cdn, want)
		}
	}
	for index, want := range wantURLs {
		if candidates[index].url != want {
			t.Fatalf("candidates[%d].url = %q; want %q", index, candidates[index].url, want)
		}
	}
}

// 对齐 test_prioritizes_highest_quality_rate：multirates 按 rate 从大到小尝试。
func TestDouyuBestPlayUrlsPrioritizesHighestQualityRate(t *testing.T) {
	resolver := newDouyuResolver(2)
	resolver.playArgsFn = func(roomID string) (map[string]string, error) {
		return map[string]string{"sign": "token"}, nil
	}
	var ratesAfterFirst []int64
	callCount := 0
	resolver.playInfoFn = func(roomID string, playArgs map[string]string, cdn string, rate int64) (map[string]interface{}, error) {
		callCount++
		if callCount == 1 {
			return map[string]interface{}{
				"multirates": []interface{}{
					map[string]interface{}{"rate": float64(1), "name": "流畅"},
					map[string]interface{}{"rate": float64(4), "name": "原画"},
					map[string]interface{}{"rate": float64(2), "name": "高清"},
				},
				"cdnsWithName": []interface{}{map[string]interface{}{"cdn": "hw-h5"}},
			}, nil
		}
		ratesAfterFirst = append(ratesAfterFirst, rate)
		return map[string]interface{}{
			"rtmp_url":  "https://hw.example",
			"rtmp_live": "live.flv",
		}, nil
	}

	quality, candidates, err := resolver.getBestPlayUrls("36252")
	if err != nil {
		t.Fatalf("getBestPlayUrls error: %v", err)
	}
	if quality != "原画" {
		t.Fatalf("quality = %q; want 原画", quality)
	}
	if len(candidates) != 1 {
		t.Fatalf("candidates 数量 = %d; want 1", len(candidates))
	}
	if len(ratesAfterFirst) != 1 || ratesAfterFirst[0] != 4 {
		t.Fatalf("候选请求 rate = %v; want [4]", ratesAfterFirst)
	}
}
