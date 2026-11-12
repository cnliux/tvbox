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
func Init(signScript string, catalogJSON string, cacheSeconds int, timeoutSeconds int, clientType string) error {
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
// 0 表示关闭目标缓冲（严格实时匀速）；负值被忽略。修改立即对所有在播/新建代理生效。
func SetPacerTargetBufferMs(ms int64) {
	if ms < 0 {
		return
	}
	flvProxyPacerTargetBufferMs.Store(ms)
}

// GetPacerTargetBufferMs 返回当前 pacer 目标缓冲（毫秒）。
func GetPacerTargetBufferMs() int64 {
	return flvProxyPacerTargetBufferMs.Load()
}

// ChannelsJson 等价 Python tvbox_live_bridge.channels_json。
func ChannelsJson() (string, error) {
	service, err := bridgeCurrent()
	if err != nil {
		return "", err
	}
	channels, err := service.Catalog()
	if err != nil {
		return "", err
	}
	payload := make([]catalogChannelJSON, 0, len(channels))
	for _, channel := range channels {
		payload = append(payload, catalogChannelJSON{
			ID:     channel.ID,
			Name:   channel.Name,
			Site:   channel.Site,
			RoomID: channel.RoomID,
			Group:  channel.Group,
		})
	}
	return marshalIndentFree(channelsJSON{Channels: payload})
}

// SitesJson 等价 Python tvbox_live_bridge.sites_json。
func SitesJson() (string, error) {
	service, err := bridgeCurrent()
	if err != nil {
		return "", err
	}
	sites := service.Sites()
	payload := make([]siteJSON, 0, len(sites))
	for _, site := range sites {
		payload = append(payload, siteJSON{
			ID:          site.ID,
			Name:        site.Name,
			Description: site.Description,
		})
	}
	return marshalIndentFree(sitesJSON{Sites: payload})
}

// CategoriesJson 等价 Python tvbox_live_bridge.categories_json。
func CategoriesJson(site string) (string, error) {
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
func RoomsJson(site string, categoryID string, page string) (string, error) {
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
func ResolveJson(site string, roomID string, refresh string) (string, error) {
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
func ResolveLinkJson(shareText string, siteHint string) (string, error) {
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
