package platformlive

import "testing"

// S1-05：分享口令解析的出网白名单。validateLiveLinkURL 只允许已知直播平台域，
// 因此 RFC1918 / 链路本地 / 回环 / file:// 全部在出网前被拒（SSRF 探针打不通）。
func TestResolveLinkSSRF(t *testing.T) {
	blocked := []string{
		"http://127.0.0.1:9978/live.flv",
		"http://localhost/admin",
		"http://169.254.169.254/latest/meta-data/",
		"http://10.0.0.1/",
		"http://192.168.0.1/",
		"file:///data/data/com.tvbox.app/shared_prefs/app_settings.xml",
		"content://media/external/images/1",
	}
	for _, raw := range blocked {
		if _, _, err := validateLiveLinkURL(raw); err == nil {
			t.Errorf("validateLiveLinkURL(%q) 应拒绝 SSRF 目标，实际放行", raw)
		}
	}

	// 已知平台域仍应放行（确保白名单不是「全部拒绝」的假实现）。
	if _, site, err := validateLiveLinkURL("https://www.douyu.com/36252"); err != nil || site == "" {
		t.Errorf("合法直播链接被误拒：site=%q err=%v", site, err)
	}
}
