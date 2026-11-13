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
	// 允许的最大「落后」量。帧的媒体时间早于墙钟太久，说明是交接后从
	// 预建连接里读出的积压旧帧；若继续按 delay<=0 立即写，几百帧会在一个
	// 循环里瞬间灌进播放器造成快进/抖动。超过预算就重新对齐到当前帧。
	flvProxyPacerCatchUpBudget = 250 * time.Millisecond
)

// flvPacer 保存一条本地输出流的墙钟基准。生命周期与 flvProxy 相同，
// 而不是与某一次 consume（某一段上游会话）相同——预填只在开播时做一次，
// 之后无论重连、租约续期还是计划内交接，都沿同一条实时匀速曲线继续。
type flvPacer struct {
	mu         sync.Mutex
	firstTs    int64
	mediaStart int64
	wallStart  time.Time
	started    bool
	haveFirst  bool
}

// pace 返回本帧应当等待多久再写出。rebased=true 表示基准被重置
//（时间戳跳变、落后的积压帧、或用户调整了目标缓冲），catchUp 进一步区分
// 是否由「积压旧帧追帧」触发。targetMs=0 时不做预填，直接实时匀速。
//
// 注意这是「通用 pacer 基础设施」，不含任何平台专属假设：三档 tuning 的
// 时间轴/租约/重连策略都不经由这里，只是共用同一条输出节奏。
func (pc *flvPacer) pace(adjusted, targetMs int64, maxDelay time.Duration, rebase bool) (time.Duration, bool, bool) {
	return pc.paceAt(adjusted, targetMs, maxDelay, rebase, time.Now())
}

// paceAt 与 pace 相同，但允许注入墙钟时间，便于离线测试精确模拟跨段时序。
func (pc *flvPacer) paceAt(adjusted, targetMs int64, maxDelay time.Duration, rebase bool, now time.Time) (time.Duration, bool, bool) {
	pc.mu.Lock()
	defer pc.mu.Unlock()
	if rebase {
		pc.started = false
		pc.haveFirst = false
	}
	if !pc.haveFirst {
		pc.haveFirst = true
		pc.firstTs = adjusted
	}
	if !pc.started {
		// 首段预填：目标窗口内按上游到达速度立即转发，让播放器真正攒下缓冲。
		if adjusted-pc.firstTs < targetMs {
			return 0, false, false
		}
		pc.started = true
		pc.mediaStart = adjusted
		pc.wallStart = now
	}
	deadline := pc.wallStart.Add(time.Duration(adjusted-pc.mediaStart) * time.Millisecond)
	delay := deadline.Sub(now)
	if delay > maxDelay {
		// 时间戳跳变：以当前帧为新基准，避免无限期空等。
		pc.mediaStart = adjusted
		pc.wallStart = now
		return 0, true, false
	}
	if delay < -flvProxyPacerCatchUpBudget {
		// 积压旧帧：只放行这一帧并重新对齐，不让整批过期数据瞬间灌入播放器。
		pc.mediaStart = adjusted
		pc.wallStart = now
		return 0, true, true
	}
	if delay < 0 {
		return 0, false, false
	}
	return delay, false, false
}

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
