package platformlive

import (
	"strings"
	"testing"
)

func TestExtractLiveURLsFromShareText(t *testing.T) {
	text := "7.98 复制打开抖音，看看【主播】的直播 https://v.douyin.com/abcDEF/ 口令，或 https://live.bilibili.com/123，谢谢！"
	urls := extractLiveURLs(text)
	if len(urls) != 2 {
		t.Fatalf("urls = %v", urls)
	}
	if urls[0] != "https://v.douyin.com/abcDEF/" || urls[1] != "https://live.bilibili.com/123" {
		t.Fatalf("urls = %v", urls)
	}
}

func TestLiveSiteForHost(t *testing.T) {
	cases := map[string]string{
		"www.douyu.com":         "douyu",
		"m.huya.com":            "huya",
		"live.bilibili.com":     "bilibili",
		"b23.tv":                "bilibili",
		"v.douyin.com":          "douyin",
		"www.kuaishou.com":      "kuaishou",
		"fm.missevan.com":       "missevan",
		"www.showroom-live.com": "showroom",
		"www.inke.cn":           "inke",
		"weibo.com":             "weibo",
		"t.cn":                  "weibo",
		"example.com":           "",
		"127.0.0.1":             "",
	}
	for host, want := range cases {
		if got := liveSiteForHost(host); got != want {
			t.Fatalf("liveSiteForHost(%q) = %q; want %q", host, got, want)
		}
	}
}

func TestValidateLiveLinkURLRejectsUnsupportedAndUnsafeHosts(t *testing.T) {
	for _, rawURL := range []string{
		"http://127.0.0.1/admin",
		"http://169.254.169.254/latest/meta-data",
		"http://example.com/live/123",
		"http://evil.com@www.douyu.com/123",
		"ftp://www.douyu.com/123",
	} {
		if _, _, err := validateLiveLinkURL(rawURL); err == nil {
			t.Fatalf("expected error for %q", rawURL)
		}
	}
	if _, site, err := validateLiveLinkURL("https://www.douyu.com/36252"); err != nil || site != "douyu" {
		t.Fatalf("valid douyu URL rejected: site=%q err=%v", site, err)
	}
}

func TestExtractHTMLRedirect(t *testing.T) {
	meta := `<html><head><meta http-equiv="refresh" content="0; url=https://live.bilibili.com/123"></head></html>`
	if got := extractHTMLRedirect(meta); got != "https://live.bilibili.com/123" {
		t.Fatalf("meta redirect = %q", got)
	}
	script := `<script>window.location.replace("https://www.showroom-live.com/r/room_key");</script>`
	if got := extractHTMLRedirect(script); got != "https://www.showroom-live.com/r/room_key" {
		t.Fatalf("js redirect = %q", got)
	}
	href := `<script>location.href = "https://fm.missevan.com/live/123456";</script>`
	if got := extractHTMLRedirect(href); got != "https://fm.missevan.com/live/123456" {
		t.Fatalf("href redirect = %q", got)
	}
}

func TestResolveLinkCanonicalMissevanLink(t *testing.T) {
	service := NewService(2, 0, nil, "")
	target, err := service.ResolveLink("复制这条信息 https://fm.missevan.com/live/123456 打开猫耳", "")
	if err != nil {
		t.Fatalf("ResolveLink error: %v", err)
	}
	if target.Site != "missevan" || target.RoomID != "123456" || target.SiteName != "猫耳 FM" {
		t.Fatalf("target = %+v", target)
	}
	if target.SourceURL != "https://fm.missevan.com/live/123456" {
		t.Fatalf("sourceURL = %q", target.SourceURL)
	}
}

func TestResolveLinkShortLinkUsesExpandedURL(t *testing.T) {
	service := NewService(2, 0, nil, "")
	expanded := ""
	target, err := service.resolveLink("https://b23.tv/abcdef", "", func(raw string) (string, error) {
		expanded = raw
		return "https://live.bilibili.com/123", nil
	})
	if err != nil {
		t.Fatalf("resolveLink error: %v", err)
	}
	if expanded != "https://b23.tv/abcdef" {
		t.Fatalf("expanded input = %q", expanded)
	}
	if target.Site != "bilibili" || target.RoomID != "123" || target.URL != "https://live.bilibili.com/123" {
		t.Fatalf("target = %+v", target)
	}
}

func TestResolveLinkShortLinkRejectsUnsupportedRedirect(t *testing.T) {
	service := NewService(2, 0, nil, "")
	_, err := service.resolveLink("https://b23.tv/abcdef", "", func(raw string) (string, error) {
		return "http://127.0.0.1/admin", nil
	})
	if err == nil || !strings.Contains(err.Error(), "暂不支持") {
		t.Fatalf("expected unsupported redirect error, got %v", err)
	}
}

func TestResolveLinkWithSiteHintAcceptsBareRoomID(t *testing.T) {
	service := NewService(2, 0, nil, "")
	target, err := service.resolveLink(" 36252 ", "douyu", nil)
	if err != nil {
		t.Fatalf("resolveLink error: %v", err)
	}
	if target.Site != "douyu" || target.RoomID != "36252" {
		t.Fatalf("target = %+v", target)
	}
}

func TestResolveLinkWithoutHintRejectsBareRoomID(t *testing.T) {
	service := NewService(2, 0, nil, "")
	if _, err := service.resolveLink("36252", "", nil); err == nil {
		t.Fatalf("expected ambiguous bare room id error")
	}
}
