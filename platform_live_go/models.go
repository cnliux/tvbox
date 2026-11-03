// Package platformlive 提供虎牙/斗鱼/哔哩哔哩/抖音/快手的手机直播解析。
//
// 本包是 platform_live_server（Python 版）的 Go 移植：
// - 通过 gomobile bind 导出 AAR 供 APK 内置使用（platformlive.go 的 *_Json）；
// - 通过 cmd/platform-live-server 以 HTTP 服务对外；
// JSON 结构与 Python 版完全一致（见 PlatformLiveRepository 的 DTO 解析）。
package platformlive

import (
	"encoding/json"
	"fmt"
	"math"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

// ResolverError 对应 Python 的 PlatformResolverError/DouyuResolverError。
type ResolverError struct{ Message string }

func (e *ResolverError) Error() string { return e.Message }

func newError(format string, args ...interface{}) *ResolverError {
	return &ResolverError{Message: fmt.Sprintf(format, args...)}
}

// LiveParentCategory 一级分类。
type LiveParentCategory struct {
	ID    string
	Name  string
	Cover string
}

// LiveCategory 二级分类。
type LiveCategory struct {
	ID         string
	Name       string
	ParentID   string
	ParentName string
	Cover      string
}

// LiveRoom 分类房间列表里的一条直播房间。
type LiveRoom struct {
	RoomID       string
	Title        string
	Anchor       string
	Cover        string
	Online       int64
	CategoryID   string
	CategoryName string
}

// LiveRoomsPage 分类房间列表分页结果。
type LiveRoomsPage struct {
	Rooms     []LiveRoom
	PageCount int64
}

// LiveStreamCandidate 同一清晰度下的一条 CDN 线路。
type LiveStreamCandidate struct {
	CDN      string
	URL      string
	Protocol string
}

// ResolvedLiveStream 单个房间的解析结果。
type ResolvedLiveStream struct {
	RoomID     string
	Title      string
	Anchor     string
	Quality    string
	Candidates []LiveStreamCandidate
	Headers    map[string]string
	IsLoop     bool
}

// URL 首选候选线路地址（兼容单线路调用方）。
func (s *ResolvedLiveStream) URL() string {
	if len(s.Candidates) == 0 {
		return ""
	}
	return s.Candidates[0].URL
}

// Protocol 首选候选线路协议（兼容单线路调用方）。
func (s *ResolvedLiveStream) Protocol() string {
	if len(s.Candidates) == 0 {
		return ""
	}
	return s.Candidates[0].Protocol
}

// Adapter 每个平台 resolver 暴露给 LiveService 的统一能力。
type Adapter interface {
	SiteID() string
	SiteName() string
	Description() string
	NormalizeRoomID(value string) (string, error)
	GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error)
	GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error)
	Resolve(roomInput string) (*ResolvedLiveStream, error)
}

// Text 对应 platform_common.text：None→fallback，否则去空白后为空时回退。
// float64（JSON 数字默认解码类型）为整数值时按整数格式化，
// 避免大整数（如 B 站房间号）被 fmt 格式化成科学计数法（2.3438368e+07）。
func Text(value interface{}, fallback string) string {
	switch raw := value.(type) {
	case nil:
		return fallback
	case string:
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			return fallback
		}
		return trimmed
	case float64:
		if raw == math.Trunc(raw) && math.Abs(raw) < 1e15 {
			return strconv.FormatInt(int64(raw), 10)
		}
		return strconv.FormatFloat(raw, 'f', -1, 64)
	case json.Number:
		return raw.String()
	default:
		trimmed := strings.TrimSpace(fmt.Sprintf("%v", raw))
		if trimmed == "" || trimmed == "<nil>" {
			return fallback
		}
		return trimmed
	}
}

// TextStrict 去空白（无 fallback 语义差异）。
func TextStrict(value interface{}) string {
	return Text(value, "")
}

// PositiveInt 对应 platform_common.positive_int。
func PositiveInt(value interface{}, fallback int64) int64 {
	result, ok := ToInt(value)
	if !ok {
		return fallback
	}
	if result <= 0 {
		return fallback
	}
	return result
}

// ToInt 把 JSON 解出的 interface{}（float64/string/json.Number 等）转换成 int64。
func ToInt(value interface{}) (int64, bool) {
	switch raw := value.(type) {
	case nil:
		return 0, false
	case float64:
		return int64(raw), true
	case float32:
		return int64(raw), true
	case int64:
		return raw, true
	case int:
		return int64(raw), true
	case int32:
		return int64(raw), true
	case string:
		var parsed int64
		if _, err := fmt.Sscanf(strings.TrimSpace(raw), "%d", &parsed); err == nil {
			return parsed, true
		}
		return 0, false
	case json.Number:
		if parsed, err := raw.Int64(); err == nil {
			return parsed, true
		}
		if parsed, err := raw.Float64(); err == nil {
			return int64(parsed), true
		}
		return 0, false
	default:
		return 0, false
	}
}

// InferProtocol 对应 platform_common.infer_protocol。
func InferProtocol(rawURL string) string {
	lower := strings.ToLower(rawURL)
	switch {
	case strings.Contains(lower, ".m3u8") || strings.Contains(lower, "hls"):
		return "hls"
	case strings.Contains(lower, ".flv"):
		return "flv"
	case strings.HasPrefix(lower, "rtmp://"):
		return "rtmp"
	default:
		return "http"
	}
}

// UniqueCandidates 对应 platform_common.unique_candidates：按 URL 去重。
func UniqueCandidates(items []LiveStreamCandidate) []LiveStreamCandidate {
	result := make([]LiveStreamCandidate, 0, len(items))
	seen := map[string]bool{}
	for _, item := range items {
		rawURL := Text(item.URL, "")
		if rawURL == "" || seen[rawURL] {
			continue
		}
		seen[rawURL] = true
		result = append(result, LiveStreamCandidate{
			CDN:      Text(item.CDN, "默认"),
			URL:      rawURL,
			Protocol: Text(item.Protocol, InferProtocol(rawURL)),
		})
	}
	return result
}

var genericRoomPattern = regexp.MustCompile(`^[A-Za-z0-9_-]+$`)

// ParseRoomInput 对应 platform_common.parse_room_input：从房间号或链接中提取末段。
func ParseRoomInput(value string, hosts []string, pattern *regexp.Regexp) (string, error) {
	raw := Text(value, "")
	if raw == "" {
		return "", newError("直播间号不能为空")
	}
	parsed, err := url.Parse(raw)
	if err != nil {
		return "", newError("直播间号格式无效")
	}
	if parsed.Scheme != "" && parsed.Host != "" {
		host := strings.ToLower(parsed.Host)
		host = strings.SplitN(host, ":", 2)[0]
		allowed := false
		for _, candidate := range hosts {
			if host == candidate {
				allowed = true
				break
			}
		}
		if !allowed {
			return "", newError("直播间链接不属于当前平台")
		}
		parts := make([]string, 0, 4)
		for _, part := range strings.Split(parsed.Path, "/") {
			if part != "" {
				parts = append(parts, part)
			}
		}
		if len(parts) > 0 {
			raw = parts[len(parts)-1]
		}
	}
	if pattern == nil {
		pattern = genericRoomPattern
	}
	if !pattern.MatchString(raw) {
		return "", newError("直播间号格式无效")
	}
	return raw, nil
}

// extractJSONValue 从 source 的 start 位置找到第一段平衡的 JSON 数组/对象文本。
// 行为对齐 huya/douyin/kuaishou resolvers 中手写的扫描器（引号感知跳过转义）。
func extractJSONValue(source string, start int) (string, bool) {
	opening := -1
	for index := start; index < len(source); index++ {
		c := source[index]
		if c == '[' || c == '{' {
			opening = index
			break
		}
	}
	if opening < 0 {
		return "", false
	}
	type frame struct{ open, close byte }
	var stack []frame
	inString := false
	escaped := false
	for index := opening; index < len(source); index++ {
		c := source[index]
		if inString {
			if escaped {
				escaped = false
			} else if c == '\\' {
				escaped = true
			} else if c == '"' {
				inString = false
			}
			continue
		}
		if c == '"' {
			inString = true
			continue
		}
		if c == '[' || c == '{' {
			stack = append(stack, frame{open: c})
			continue
		}
		if c == ']' || c == '}' {
			if len(stack) == 0 {
				return "", false
			}
			top := stack[len(stack)-1]
			if (c == ']' && top.open != '[') || (c == '}' && top.open != '{') {
				return "", false
			}
			stack = stack[:len(stack)-1]
			if len(stack) == 0 {
				return source[opening : index+1], true
			}
		}
	}
	return "", false
}

// mapKeys 保序返回 map 的键（等价 Python dict 迭代顺序：插入序）。
// 仅用于需要与 Python 输出顺序保持一致的地方。
func sortedStable(originalOrder []string, present map[string]bool) []string {
	result := make([]string, 0, len(originalOrder))
	for _, key := range originalOrder {
		if present[key] {
			result = append(result, key)
		}
	}
	return result
}
