package platformlive

import (
	"strings"
	"sync"
)

// douyin.go —— 抖音 resolver 的编译占位实现。
// 说明：Python server.py 的 douyin 解析逻辑尚未完整移植到 Go；
// 此文件提供满足 Adapter 接口的最小实现，保证 go build/test 与
// gomobile bind 通过。抖音平台在运行期返回"暂未移植"错误。
// 后续补齐完整逻辑（douyin_sign.js 签名 + web API 解析）后替换，
// douyinSignScript 已由 Init 通过 SetDouyinSignScript 注入并保存。

const douyinUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
	"(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

var (
	douyinSignMu     sync.Mutex
	douyinSignScript string
)

// SetDouyinSignScript 注入裁剪后的 douyin_sign.js 文本（Init 内部调用）。
func SetDouyinSignScript(script string) {
	douyinSignMu.Lock()
	defer douyinSignMu.Unlock()
	douyinSignScript = script
}

// getDouyinSignScript 取当前签名脚本文本（供后续签名实现使用）。
func getDouyinSignScript() string {
	douyinSignMu.Lock()
	defer douyinSignMu.Unlock()
	return douyinSignScript
}

// douyinResolver 抖音平台 adapter 的占位实现。
type douyinResolver struct {
	session *httpSession
	cookie  string
}

func newDouyinResolver(timeoutSeconds int64, cookie string) *douyinResolver {
	return &douyinResolver{
		session: newHTTPSession(int(timeoutSeconds), douyinUserAgent, true),
		cookie:  strings.TrimSpace(cookie),
	}
}

func (r *douyinResolver) SiteID() string      { return "douyin" }
func (r *douyinResolver) SiteName() string    { return "抖音" }
func (r *douyinResolver) Description() string { return "抖音直播（Go 移植待完成，暂不可用）" }

// NormalizeRoomID 对齐 Adapter 接口（完整移植前返回未移植错误）。
func (r *douyinResolver) NormalizeRoomID(value string) (string, error) {
	return "", newError("抖音解析尚未移植到 Go")
}

func (r *douyinResolver) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	return nil, nil, newError("抖音解析尚未移植到 Go")
}

func (r *douyinResolver) GetCategoryRooms(categoryID string, page int64) (LiveRoomsPage, error) {
	return LiveRoomsPage{}, newError("抖音解析尚未移植到 Go")
}

func (r *douyinResolver) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	return nil, newError("抖音解析尚未移植到 Go")
}
