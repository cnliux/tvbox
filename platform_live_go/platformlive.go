package platformlive

// gomobile bind 入口（生成 AAR 后由 Kotlin PlatformLiveRepository 调用）。
//
// 设计说明：
// - Android 侧通过 Init(strategy) 传入 assets 里的 douyin_sign.js 与频道目录
//   JSON 文本，因为 gomobile bind 的 .so 无法直接读取 APK assets；
// - 各 *_Json 函数与旧版 Python 桥接 tvbox_live_bridge 的 JSON 结构完全一致，
//   Kotlin 侧解析逻辑不需要变化；
// - 错误以 error 返回，gomobile 会转换为 Java 异常（消息保留中文描述）。

import (
	"encoding/json"
	"fmt"
	"log"
	"strconv"
	"sync"
)

type channelsJSON struct {
	Channels []catalogChannelJSON `json:"channels"`
}

type catalogChannelJSON struct {
	ID     string `json:"id"`
	Name   string `json:"name"`
	Site   string `json:"site"`
	RoomID string `json:"roomId"`
	Group  string `json:"group"`
}

type sitesJSON struct {
	Sites []siteJSON `json:"sites"`
}

type siteJSON struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Description string `json:"description"`
}

var (
	bridgeLock      sync.Mutex
	bridgeService   *Service
	bridgeInitErr   error
	bridgeHasInit   bool
)

// Init 初始化内置解析服务；重复调用为幂等操作。
//
// signScript 为 douyin_sign.js 原始文本（包含 Node 包装段也可以，内部会裁剪）；
// catalogJSON 为频道目录 JSON 数组文本；缓存与超时单位为秒。
// clientType 为运行端类型："web"=PC/桌面, "mobile"=手机, "tv"=Android TV；空值默认为 "web"。
func Init(signScript string, catalogJSON string, cacheSeconds int, timeoutSeconds int, clientType string) (err error) {
	defer recoverToError(&err)
	bridgeLock.Lock()
	defer bridgeLock.Unlock()
	if bridgeHasInit {
		return bridgeInitErr
	}
	bridgeHasInit = true
	SetDouyinSignScript(stripDouyinScript(signScript))
	service := NewService(int64(timeoutSeconds), int64(cacheSeconds), func() (string, error) {
		if catalogJSON == "" {
			return "", newError("频道目录不存在")
		}
		return catalogJSON, nil
	}, clientType)
	if _, err := service.Catalog(); err != nil {
		// Python 侧 catalog 解析失败会在 channels_json 调用时抛错；
		// 这里同样延迟到调用期触发，但记录解析结果状态。
		bridgeService = service
		return nil
	}
	bridgeService = service
	return nil
}

func bridgeCurrent() (*Service, error) {
	if !bridgeHasInit || bridgeService == nil {
		return nil, newError("直播解析器未初始化")
	}
	return bridgeService, nil
}

// SetPacerTargetBufferMs 运行时调整 pacer 目标缓冲（毫秒），用于「一起看」续流缓冲调试。
// 0 表示关闭预填（严格实时匀速）；负值被忽略。修改写回全局默认值，并通知
// 每个在播代理在下一帧按新值重新对齐墙钟基准，不产生整段时间轴跳变。
// 具体实现见 flv_proxy_pacer.go。
func SetPacerTargetBufferMs(ms int64) {
	defer recoverToErrorOnly()
	applyPacerTargetBufferMs(ms)
}

// GetPacerTargetBufferMs 返回当前 pacer 目标缓冲（毫秒）。
func GetPacerTargetBufferMs() (ms int64) {
	defer recoverToErrorOnly()
	return pacerTargetBufferMsDefault()
}

// ChannelsJson 等价 Python tvbox_live_bridge.channels_json。
func ChannelsJson() (payload string, err error) {
	defer recoverToError(&err)
	service, err := bridgeCurrent()
	if err != nil {
		return "", err
	}
	channels, err := service.Catalog()
	if err != nil {
		return "", err
	}
	out := make([]catalogChannelJSON, 0, len(channels))
	for _, channel := range channels {
		out = append(out, catalogChannelJSON{
			ID:     channel.ID,
			Name:   channel.Name,
			Site:   channel.Site,
			RoomID: channel.RoomID,
			Group:  channel.Group,
		})
	}
	return marshalIndentFree(channelsJSON{Channels: out})
}

// SitesJson 等价 Python tvbox_live_bridge.sites_json。
func SitesJson() (payload string, err error) {
	defer recoverToError(&err)
	service, err := bridgeCurrent()
	if err != nil {
		return "", err
	}
	sites := service.Sites()
	out := make([]siteJSON, 0, len(sites))
	for _, site := range sites {
		out = append(out, siteJSON{
			ID:          site.ID,
			Name:        site.Name,
			Description: site.Description,
		})
	}
	return marshalIndentFree(sitesJSON{Sites: out})
}

// CategoriesJson 等价 Python tvbox_live_bridge.categories_json。
func CategoriesJson(site string) (payload string, err error) {
	defer recoverToError(&err)
	service, err := bridgeCurrent()
	if err != nil {
		return "", err
	}
	result, err := service.Categories(site)
	if err != nil {
		return "", err
	}
	return marshalIndentFree(result)
}

// RoomsJson 等价 Python tvbox_live_bridge.rooms_json；page 参数保持字符串
// （对应 Python 侧 callAttr("rooms_json", site, category, page)）。
func RoomsJson(site string, categoryID string, page string) (payload string, err error) {
	defer recoverToError(&err)
	service, err := bridgeCurrent()
	if err != nil {
		return "", err
	}
	pageValue, convErr := strconv.Atoi(page)
	if convErr != nil {
		pageValue = 1
	}
	result, err := service.Rooms(site, categoryID, int64(pageValue))
	if err != nil {
		return "", err
	}
	return marshalIndentFree(result)
}

// ResolveJson 等价 Python tvbox_live_bridge.resolve_json。
func ResolveJson(site string, roomID string, refresh string) (payload string, err error) {
	defer recoverToError(&err)
	service, err := bridgeCurrent()
	if err != nil {
		return "", err
	}
	forceRefresh := strLowerTrim(refresh) == "1" || strLowerTrim(refresh) == "true" || strLowerTrim(refresh) == "yes"
	result, err := service.Resolve(site, roomID, forceRefresh)
	if err != nil {
		return "", err
	}
	return marshalIndentFree(result)
}

// ResolveLinkJson 把粘贴的分享口令/短链解析成 {site,name,roomId,sourceUrl,url}。
// siteHint 可空；空值表示完全由链接域名判断平台。
func ResolveLinkJson(shareText string, siteHint string) (payload string, err error) {
	defer recoverToError(&err)
	service, err := bridgeCurrent()
	if err != nil {
		return "", err
	}
	target, err := service.ResolveLink(shareText, siteHint)
	if err != nil {
		return "", err
	}
	return marshalIndentFree(target)
}

func marshalIndentFree(value interface{}) (string, error) {
	data, err := json.Marshal(value)
	if err != nil {
		return "", fmt.Errorf("直播解析序列化失败：%w", err)
	}
	return string(data), nil
}

// recoverToError 把 gomobile bind 导出函数里的 panic 转成 *ResolverError，
// 而不是让 panic 穿透 JNI 边界触发 native crash（进程闪退）。
//
// 背景：直播平台解析链路（斗鱼/虎牙/B站/抖音/快手等）依赖 goja JS 引擎、
// JSON 类型断言、网络响应解析与 FLV 字节流处理，任一处未预见的 panic
// （nil 解引用、切片越界、类型断言失败等）都会越过 gomobile bind 的边界，
// 在设备上表现为「点开直播就闪退」。这里统一兜底为可读错误，由 Kotlin 侧
// callBridge 捕获并展示「直播解析失败：…」，保住进程。
//
// 用法：`defer recoverToError(&err)`（err 为命名返回值的 error 字段）。
func recoverToError(err *error) {
	if r := recover(); r != nil {
		// panic 被 recover 吞掉后 UI 只会看到「直播解析失败」，用户报障时无证据可查；
		// 这里 log 到 stderr（RedirectRuntimeLogcat 会转进 logcat 与 go_stderr.log）留痕。
		log.Printf("TVBoxGoRuntime recover: 直播解析 panic: %v", r)
		*err = newError("直播解析异常：%v", r)
	}
}

// recoverToErrorOnly 同 recoverToError，但用于无 error 返回值的导出函数
// （如 SetPacerTargetBufferMs），panic 时静默丢弃——这类函数不改变可观测
// 的播放行为，崩溃防护优先于报错。
func recoverToErrorOnly() {
	if r := recover(); r != nil {
		log.Printf("TVBoxGoRuntime recover: 无返回值导出函数 panic: %v", r)
	}
}

// recoverTo 供其余 gomobile 导出函数（string/int64 等值返回）统一兜底：
// panic 时回写 fallback，避免未捕获 panic 穿透 JNI 边界导致进程闪退。
func recoverTo[T any](value *T, fallback T) {
	if r := recover(); r != nil {
		log.Printf("TVBoxGoRuntime recover: 值返回导出函数 panic: %v", r)
		*value = fallback
	}
}

func strLowerTrim(value string) string {
	runes := []rune(value)
	out := make([]rune, 0, len(runes))
	for _, r := range runes {
		space := r == ' ' || r == '\t' || r == '\n' || r == '\r'
		if space {
			continue
		}
		if r >= 'A' && r <= 'Z' {
			r += 'a' - 'A'
		}
		out = append(out, r)
	}
	return string(out)
}
