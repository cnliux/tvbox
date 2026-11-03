package platformlive

// live_url.go 把"分享口令 / 短链 / 直播间链接"解析成 (platform, roomId)。
// 对应 pure_live 的 LiveUrlTool：提取 URL -> 跟随短链重定向 -> 交给各平台
// adapter 的 NormalizeRoomID 提取房间号。
//
// 安全边界：只允许访问下表中已登记的平台域名（含短链域名），重定向的每一跳
// 也必须在白名单内，避免服务端被当成任意 URL 代理（SSRF）。短链响应体最多
// 读取 256 KB，最多跟随 6 跳。

import (
	"html"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

// LiveLinkTarget 是分享链接解析结果，可直接用于 /live/resolve。
type LiveLinkTarget struct {
	Site      string `json:"site"`
	SiteName  string `json:"name"`
	RoomID    string `json:"roomId"`
	SourceURL string `json:"sourceUrl"`
	URL       string `json:"url"`
}

type liveLinkExpanderFunc func(string) (string, error)

type liveDomainEntry struct {
	site    string
	domains []string
}

// 顺序只影响同一 host 命中多个平台时的优先级，当前域名之间互不重叠。
var livePlatformDomains = []liveDomainEntry{
	{site: "douyu", domains: []string{"douyu.com"}},
	{site: "huya", domains: []string{"huya.com"}},
	{site: "bilibili", domains: []string{"bilibili.com", "b23.tv"}},
	{site: "douyin", domains: []string{"douyin.com", "iesdouyin.com"}},
	{site: "kuaishou", domains: []string{"kuaishou.com"}},
	{site: "cc", domains: []string{"cc.163.com"}},
	{site: "missevan", domains: []string{"missevan.com"}},
	{site: "showroom", domains: []string{"showroom-live.com"}},
	{site: "inke", domains: []string{"inke.cn", "inke.com"}},
	{site: "weibo", domains: []string{"weibo.com", "m.weibo.cn", "t.cn"}},
}

var liveShortLinkHosts = map[string]bool{
	"b23.tv":         true,
	"t.cn":           true,
	"v.douyin.com":   true,
	"v.huya.com":     true,
	"v.kuaishou.com": true,
}

const (
	maxLiveLinkHops     = 6
	maxLiveLinkBodySize = 256 * 1024
)

const liveLinkUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

var (
	liveURLPattern       = regexp.MustCompile(`https?://[\x21-\x7E]+`)
	liveMetaTagPattern   = regexp.MustCompile(`(?is)<meta\s+[^>]*>`)
	liveMetaRefreshToken = regexp.MustCompile(`(?i)http-equiv\s*=\s*["']?refresh["']?`)
	liveMetaContentAttr  = regexp.MustCompile(`(?i)content\s*=\s*["']([^"']+)["']`)
	liveMetaURLValue     = regexp.MustCompile(`(?i)url\s*=\s*([^"';\s>]+)`)
	liveJSReplacePattern = regexp.MustCompile(`(?is)location\s*\.\s*(?:replace|assign)\s*\(\s*["']([^"']+)["']`)
	liveJSHrefPattern    = regexp.MustCompile(`(?is)(?:window\s*\.\s*)?location(?:\s*\.\s*href)?\s*=\s*["']([^"']+)["']`)
)

func liveHostOf(rawURL string) string {
	parsed, err := url.Parse(rawURL)
	if err != nil {
		return ""
	}
	return strings.ToLower(parsed.Hostname())
}

// liveSiteForHost 按域名后缀匹配平台，例如 fm.missevan.com -> missevan。
func liveSiteForHost(host string) string {
	normalized := strings.ToLower(strings.TrimSpace(host))
	if normalized == "" {
		return ""
	}
	for _, entry := range livePlatformDomains {
		for _, domain := range entry.domains {
			if normalized == domain || strings.HasSuffix(normalized, "."+domain) {
				return entry.site
			}
		}
	}
	return ""
}

func liveHostSupported(host string) bool {
	return liveSiteForHost(host) != ""
}

func isLiveShortLinkHost(host string) bool {
	return liveShortLinkHosts[strings.ToLower(host)]
}

func trimLiveURLSuffix(value string) string {
	return strings.TrimRight(value, ".,;:!?，。；、！？（）()[]{}<>》」】\"'")
}

func extractLiveURLs(text string) []string {
	matches := liveURLPattern.FindAllString(text, -1)
	urls := make([]string, 0, len(matches))
	seen := map[string]bool{}
	for _, raw := range matches {
		cleaned := trimLiveURLSuffix(strings.TrimSpace(raw))
		if cleaned == "" || seen[cleaned] {
			continue
		}
		seen[cleaned] = true
		urls = append(urls, cleaned)
	}
	return urls
}

func validateLiveLinkURL(raw string) (*url.URL, string, error) {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil || parsed.Host == "" {
		return nil, "", newError("直播链接格式无效")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return nil, "", newError("直播链接必须使用 http(s)")
	}
	if parsed.User != nil {
		return nil, "", newError("直播链接格式无效")
	}
	host := strings.ToLower(parsed.Hostname())
	site := liveSiteForHost(host)
	if site == "" {
		return nil, "", newError("暂不支持该直播间链接所属的平台")
	}
	return parsed, site, nil
}

func extractHTMLRedirect(body string) string {
	for _, tag := range liveMetaTagPattern.FindAllString(body, -1) {
		if !liveMetaRefreshToken.MatchString(tag) {
			continue
		}
		content := liveMetaContentAttr.FindStringSubmatch(tag)
		if len(content) != 2 {
			continue
		}
		target := liveMetaURLValue.FindStringSubmatch(content[1])
		if len(target) == 2 {
			return html.UnescapeString(strings.TrimSpace(target[1]))
		}
	}
	if match := liveJSReplacePattern.FindStringSubmatch(body); len(match) == 2 {
		return html.UnescapeString(strings.TrimSpace(match[1]))
	}
	if match := liveJSHrefPattern.FindStringSubmatch(body); len(match) == 2 {
		return html.UnescapeString(strings.TrimSpace(match[1]))
	}
	return ""
}

// expandLiveURL 手动跟随重定向（默认 client 会跟随，这里截断后逐跳校验域名）。
func expandLiveURL(raw string) (string, error) {
	current, _, err := validateLiveLinkURL(raw)
	if err != nil {
		return "", err
	}
	client := &http.Client{
		Timeout: 10 * time.Second,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	currentURL := current.String()
	for hop := 0; hop < maxLiveLinkHops; hop++ {
		request, requestErr := http.NewRequest(http.MethodGet, currentURL, nil)
		if requestErr != nil {
			return "", newError("短链展开失败：%s", requestErr.Error())
		}
		request.Header.Set("User-Agent", liveLinkUserAgent)
		request.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
		response, requestErr := client.Do(request)
		if requestErr != nil {
			return "", newError("短链展开失败：%s", requestErr.Error())
		}
		body, readErr := io.ReadAll(io.LimitReader(response.Body, maxLiveLinkBodySize))
		_ = response.Body.Close()
		if readErr != nil {
			return "", newError("短链展开失败：%s", readErr.Error())
		}
		if response.StatusCode >= 400 {
			return "", newError("短链展开失败（HTTP %d）", response.StatusCode)
		}
		location := strings.TrimSpace(response.Header.Get("Location"))
		if response.StatusCode >= 300 && response.StatusCode < 400 && location != "" {
			base, _ := url.Parse(currentURL)
			next, nextErr := base.Parse(html.UnescapeString(location))
			if nextErr != nil {
				return "", newError("短链跳转地址无效")
			}
			nextURL, _, nextErr := validateLiveLinkURL(next.String())
			if nextErr != nil {
				return "", newError("短链跳转到了不支持的地址")
			}
			currentURL = nextURL.String()
			continue
		}
		if response.StatusCode >= 200 && response.StatusCode < 300 {
			if target := extractHTMLRedirect(string(body)); target != "" {
				base, _ := url.Parse(currentURL)
				next, nextErr := base.Parse(html.UnescapeString(target))
				if nextErr != nil {
					return "", newError("短链跳转地址无效")
				}
				nextURL, _, nextErr := validateLiveLinkURL(next.String())
				if nextErr != nil {
					return "", newError("短链跳转到了不支持的地址")
				}
				currentURL = nextURL.String()
				continue
			}
		}
		if isLiveShortLinkHost(liveHostOf(currentURL)) {
			return "", newError("短链未返回可识别的直播间地址")
		}
		return currentURL, nil
	}
	return "", newError("短链跳转次数过多")
}

// ResolveLink 解析粘贴的分享口令/短链/直播链接；siteHint 可为空。
func (s *Service) ResolveLink(shareText string, siteHint string) (*LiveLinkTarget, error) {
	return s.resolveLink(shareText, siteHint, expandLiveURL)
}

func (s *Service) resolveLink(shareText string, siteHint string, expand liveLinkExpanderFunc) (*LiveLinkTarget, error) {
	text := strings.TrimSpace(shareText)
	if text == "" {
		return nil, newError("请粘贴直播间链接或分享口令")
	}
	if expand == nil {
		expand = expandLiveURL
	}
	urls := extractLiveURLs(text)
	var lastErr error
	for _, sourceURL := range urls {
		parsed, site, err := validateLiveLinkURL(sourceURL)
		if err != nil {
			lastErr = err
			continue
		}
		finalURL := parsed.String()
		host := strings.ToLower(parsed.Hostname())
		if isLiveShortLinkHost(host) {
			expanded, expandErr := expand(sourceURL)
			if expandErr != nil {
				lastErr = expandErr
				continue
			}
			expandedParsed, expandedSite, validateErr := validateLiveLinkURL(expanded)
			if validateErr != nil {
				lastErr = validateErr
				continue
			}
			finalURL = expandedParsed.String()
			site = expandedSite
		}
		adapter, ok := s.byID[site]
		if !ok {
			lastErr = newError("暂不支持直播平台：%s", site)
			continue
		}
		roomID, normalizeErr := adapter.NormalizeRoomID(finalURL)
		if normalizeErr != nil {
			lastErr = normalizeErr
			continue
		}
		return &LiveLinkTarget{
			Site:      adapter.SiteID(),
			SiteName:  adapter.SiteName(),
			RoomID:    roomID,
			SourceURL: sourceURL,
			URL:       finalURL,
		}, nil
	}
	if lastErr != nil {
		return nil, lastErr
	}
	normalizedHint := strings.ToLower(strings.TrimSpace(siteHint))
	if normalizedHint == "" {
		return nil, newError("未找到可识别的直播间链接；也可填写平台后直接输入房间号")
	}
	adapter, ok := s.byID[normalizedHint]
	if !ok {
		return nil, newError("暂不支持直播平台：%s", siteHint)
	}
	roomID, err := adapter.NormalizeRoomID(text)
	if err != nil {
		return nil, err
	}
	return &LiveLinkTarget{
		Site:     adapter.SiteID(),
		SiteName: adapter.SiteName(),
		RoomID:   roomID,
	}, nil
}
