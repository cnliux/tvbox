package platformlive

// pacer 目标缓冲的运行时配置与每个代理实例的独立状态。
//
// 语义修正（重要）：旧实现把「目标缓冲」当成统一的墙钟延迟
// （deadline = wallStart + (ts - mediaStart) + target），这只会给整条流增加
// 5 秒直播延迟，播放器 bufferedMs 并不会增加 —— 真机实测 1.5~2.8s 正是这个原因。
// 现在改成「首段预填」：目标窗口内的媒体帧按上游到达速度立即交给播放器，
// 让播放器真的攒下这段数据；窗口之后按媒体时间实时匀速输出，避免上游突发
// 把数秒数据瞬间灌给播放器造成快进/跳帧。0 = 关闭预填，严格实时匀速。

import (
	"sync"
	"sync/atomic"
	"time"
)

const (
	flvProxyDefaultPacerBufferMs = int64(5000)
	// pacer 单帧最大等待：超过说明时间戳跳变或续段重绑，直接重置墙钟基准。
	flvProxyPacerMaxDelay = 6 * time.Second
)

var (
	// 新建代理从这里取初值；在播代理另有自己的 pacerTargetMs。
	flvProxyPacerDefaultMs atomic.Int64
	flvProxyRegistryMu     sync.Mutex
	flvProxyRegistry       = map[*flvProxy]struct{}{}
)

func init() {
	flvProxyPacerDefaultMs.Store(flvProxyDefaultPacerBufferMs)
}

// SetPacerTargetBufferMs 调整 pacer 目标缓冲（毫秒）。0 表示关闭预填。
// 修改立即写回全局默认值，并通知所有在播代理在下一帧按新值重新对齐基准，
// 不再像旧实现那样让「已开播代理」沿用旧值、也不产生整段跳变。
func applyPacerTargetBufferMs(ms int64) {
	if ms < 0 {
		return
	}
	flvProxyPacerDefaultMs.Store(ms)
	flvProxyRegistryMu.Lock()
	proxies := make([]*flvProxy, 0, len(flvProxyRegistry))
	for proxy := range flvProxyRegistry {
		proxies = append(proxies, proxy)
	}
	flvProxyRegistryMu.Unlock()
	for _, proxy := range proxies {
		proxy.pacerTargetMs.Store(ms)
		proxy.pacerRebase.Store(true)
	}
}

func pacerTargetBufferMsDefault() int64 {
	return flvProxyPacerDefaultMs.Load()
}

func registerFlvProxy(proxy *flvProxy) {
	flvProxyRegistryMu.Lock()
	flvProxyRegistry[proxy] = struct{}{}
	flvProxyRegistryMu.Unlock()
}

func unregisterFlvProxy(proxy *flvProxy) {
	flvProxyRegistryMu.Lock()
	delete(flvProxyRegistry, proxy)
	flvProxyRegistryMu.Unlock()
}
