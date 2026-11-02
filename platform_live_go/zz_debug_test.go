package platformlive

import (
	"encoding/json"
	"fmt"
	"math/rand"
	"net/http"
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestDebugKuaishouRegisterDid(t *testing.T) {
	r := newKuaishouResolver(10, "", "")
	rid := "A311073278"
	roomURL := "https://live.kuaishou.com/u/" + rid
	// 1) getCookie
	_, cookies, err := r.session.getWithCookies(roomURL, nil, map[string]string{"User-Agent": kuaishouUserAgent, "Referer": "https://live.kuaishou.com/"})
	if err != nil {
		t.Fatalf("getCookie: %v", err)
	}
	r.mergeResponseCookies(cookies)
	did := r.cookieObj["did"]
	t.Logf("did=%q", did)

	// 2) registerDid (ksapisrv misc2)
	did2 := did
	payload := map[string]interface{}{
		"common": map[string]interface{}{
			"identity_package": map[string]interface{}{"device_id": did2, "global_id": ""},
			"app_package":      map[string]interface{}{"language": "zh-CN", "platform": 10, "container": "WEB", "product_name": "KS_GAME_LIVE_PC"},
			"device_package": map[string]interface{}{
				"os_version": "NT 6.1",
				"model":      "Windows",
				"ua":         "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36",
			},
			"need_encrypt":     "false",
			"network_package":  map[string]interface{}{"type": 3},
			"h5_extra_attr":    `{"sdk_name":"webLogger","sdk_version":"3.9.49","sdk_bundle":"log.common.js","app_version_name":"","host_product":"","resolution":"1600x900","screen_with":1600,"screen_height":900,"device_pixel_ratio":1,"domain":"https://live.kuaishou.com"}`,
			"global_attr":      "{}",
		},
		"logs": []map[string]interface{}{
			{
				"client_timestamp":   time.Now().UnixMilli(),
				"client_increment_id": rand.Intn(8999) + 1000,
				"session_id":         "1eb20f88-51ac-4ecf-8dc3-ace5aefcae4f",
				"time_zone":          "GMT+08:00",
				"event_package": map[string]interface{}{
					"task_event": map[string]interface{}{
						"type": 1, "status": 0, "operation_type": 1, "operation_direction": 0,
						"session_id": "1eb20f88-51ac-4ecf-8dc3-ace5aefcae4f",
						"url_package": map[string]interface{}{
							"page": "GAME_DETAL_PAGE", "identity": "5316c78e-f0b6-4be2-a076-c8f9d11ebc0a",
							"page_type": 2, "params": `{"game_id":1001,"game_name":"王者荣耀"}`,
						},
						"element_package": map[string]interface{}{},
					},
				},
			},
		},
	}
	payloadBytes, _ := json.Marshal(payload)
	req, _ := http.NewRequest("POST", "https://log-sdk.ksapisrv.com/rest/wd/common/log/collect/misc2?v=3.9.49&kpn=KS_GAME_LIVE_PC", strings.NewReader(string(payloadBytes)))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", kuaishouUserAgent)
	req.Header.Set("Cookie", r.cookieHeader())
	resp, err := http.DefaultClient.Do(req)
	if err == nil {
		respBody := make([]byte, 200)
		n, _ := resp.Body.Read(respBody)
		resp.Body.Close()
		t.Logf("registerDid status=%d body=%s", resp.StatusCode, string(respBody[:n]))
	} else {
		t.Logf("registerDid err=%v", err)
	}

	// 3) 重新请求房间页（带 did Cookie）
	body2, cookies2, err := r.session.getWithCookies(roomURL, nil, map[string]string{"User-Agent": kuaishouUserAgent, "Referer": "https://live.kuaishou.com/", "Cookie": r.cookieHeader()})
	if err != nil {
		t.Fatalf("room2: %v", err)
	}
	r.mergeResponseCookies(cookies2)
	living := regexp.MustCompile(`"isLiving":(true|false)`).FindString(string(body2))
	fmt.Println()
	t.Logf("after register: len=%d isLiving=%s", len(body2), living)
}
