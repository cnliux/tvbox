package platformlive

// 虎牙「一起看」续流/重连参数的运行时调优。
//
// 虎牙与斗鱼的断流机理不同：虎牙 URL 是一次性 anti-code 签名（无 expire 租约），
// 断流根因是 CDN 对单条 HTTP-FLV 连接有约 120~137s 的会话上限。代理的对策是
// 「在预期关流前预建第二条连接、在关键帧处无感交接」+「短段耐心退避/换线」。
// 这些参数原先是 const，无法真机调整；这里改成「默认值 + 可写全局」，经
// gomobile 导出 SetHuya* 给 Kotlin，使手机端按钮可运行时调整。
//
// 生效粒度：与斗鱼不同，虎牙这几个参数在 streamLoop 的**使用点**直接读 override
// （见 effective* helper），因此对**在播会话**下一次循环即生效，无需重建代理。
// 每个 setter 做范围钳制，非法值被忽略，避免按钮误触把代理调到不可用状态。

import (
	"sync/atomic"
	"time"
)

const (
	// 预建提前量默认 12s（与 huyaPlannedRefreshLeadMs 一致）。
	defaultHuyaPlannedRefreshLeadMs = int64(12000)
	// 标称会话时长默认 125s（与 huyaNominalSessionMs 一致）。
	defaultHuyaNominalSessionMs = int64(125000)
	// 短段退避基数默认 500ms（与 huyaShortBackoffBase 一致）。
	defaultHuyaShortBackoffBaseMs = int64(500)
	// 短段退避上限默认 5s（与 huyaShortBackoffMax 一致）。
	defaultHuyaShortBackoffMaxMs = int64(5000)
	// 上游空闲读超时默认 8s。
	// 真机监控：虎牙第 3 次计划交接后预备连接偶发半开（无数据但无 EOF），
	// 原 15s 超时比 Kotlin 12s 硬看门狗还晚，代理来不及自愈就被整体重建。
	// 必须严格小于 Kotlin 硬阈值，才能把「可见断流」降级为「代理内部软重连」。
	defaultHuyaIdleReadTimeoutMs = int64(8000)
	// 连续短段换线阈值默认 4（与 huyaMaxShortStreak 一致）。
	defaultHuyaMaxShortStreak = int64(4)
)

var (
	huyaPlannedRefreshLeadOverride atomic.Int64 // <=0 表示用默认
	huyaNominalSessionOverride     atomic.Int64 // <=0 表示用默认
	huyaShortBackoffBaseOverride   atomic.Int64 // <=0 表示用默认
	huyaShortBackoffMaxOverride    atomic.Int64 // <=0 表示用默认
	huyaIdleReadTimeoutOverride    atomic.Int64 // <=0 表示用默认
	huyaMaxShortStreakOverride     atomic.Int64 // <=0 表示用默认
)

// ── 预建提前量 ─────────────────────────────────────────────────

// SetHuyaPlannedRefreshLeadMs 调整虎牙关流前多久开始预建第二条连接（毫秒）。
// 0 恢复默认 12000ms；有效范围 3000..60000ms。
// 调大:更早预建、更不易因解析/握手慢而错过交接，但第二条连接空转更久。
func SetHuyaPlannedRefreshLeadMs(ms int64) {
	if ms != 0 && (ms < 3000 || ms > 60000) {
		return
	}
	huyaPlannedRefreshLeadOverride.Store(ms)
}

// GetHuyaPlannedRefreshLeadMs 返回当前生效的预建提前量（毫秒）。
func GetHuyaPlannedRefreshLeadMs() int64 {
	if v := huyaPlannedRefreshLeadOverride.Load(); v > 0 {
		return v
	}
	return defaultHuyaPlannedRefreshLeadMs
}

func huyaPlannedRefreshLead() int64 { return GetHuyaPlannedRefreshLeadMs() }

// ── 标称会话时长 ───────────────────────────────────────────────

// SetHuyaNominalSessionMs 调整虎牙首段预期时长（毫秒），用于计算预建时刻。
// 0 恢复默认 125000ms；有效范围 60000..300000ms。
func SetHuyaNominalSessionMs(ms int64) {
	if ms != 0 && (ms < 60000 || ms > 300000) {
		return
	}
	huyaNominalSessionOverride.Store(ms)
}

// GetHuyaNominalSessionMs 返回当前生效的标称会话时长（毫秒）。
func GetHuyaNominalSessionMs() int64 {
	if v := huyaNominalSessionOverride.Load(); v > 0 {
		return v
	}
	return defaultHuyaNominalSessionMs
}

func huyaNominalSession() int64 { return GetHuyaNominalSessionMs() }

// ── 短段退避基数 ───────────────────────────────────────────────

// SetHuyaShortBackoffBaseMs 调整虎牙短段退避基数（毫秒）。
// 0 恢复默认 500ms；有效范围 100..5000ms。
func SetHuyaShortBackoffBaseMs(ms int64) {
	if ms != 0 && (ms < 100 || ms > 5000) {
		return
	}
	huyaShortBackoffBaseOverride.Store(ms)
}

// GetHuyaShortBackoffBaseMs 返回当前生效的短段退避基数（毫秒）。
func GetHuyaShortBackoffBaseMs() int64 {
	if v := huyaShortBackoffBaseOverride.Load(); v > 0 {
		return v
	}
	return defaultHuyaShortBackoffBaseMs
}

func huyaShortBackoffBaseCurrent() time.Duration {
	return time.Duration(GetHuyaShortBackoffBaseMs()) * time.Millisecond
}

// ── 短段退避上限 ───────────────────────────────────────────────

// SetHuyaShortBackoffMaxMs 调整虎牙短段退避上限（毫秒）。
// 0 恢复默认 5000ms；有效范围 500..30000ms；且不得小于当前生效的退避基数。
func SetHuyaShortBackoffMaxMs(ms int64) {
	if ms != 0 && (ms < 500 || ms > 30000) {
		return
	}
	if ms > 0 && ms < GetHuyaShortBackoffBaseMs() {
		return
	}
	huyaShortBackoffMaxOverride.Store(ms)
}

// GetHuyaShortBackoffMaxMs 返回当前生效的短段退避上限（毫秒）。
func GetHuyaShortBackoffMaxMs() int64 {
	if v := huyaShortBackoffMaxOverride.Load(); v > 0 {
		return v
	}
	maxMs := int64(defaultHuyaShortBackoffMaxMs)
	// 防御：若基数被调到超过默认上限，上限跟随基数，避免出现 base>max。
	if base := GetHuyaShortBackoffBaseMs(); base > maxMs {
		return base
	}
	return maxMs
}

func huyaShortBackoffMaxCurrent() time.Duration {
	return time.Duration(GetHuyaShortBackoffMaxMs()) * time.Millisecond
}

// ── 上游空闲读超时 ─────────────────────────────────────────────

// SetHuyaIdleReadTimeoutMs 调整虎牙上游连续无数据多久判为半开连接并重连（毫秒）。
// 0 恢复默认 8000ms；有效范围 3000..20000ms。
// 上限刻意压在 20s 以内：Kotlin 侧硬卡顿阈值是 12s，超时过长会让播放器先重建，
// 代理内部自愈失去意义（真机实测第 3 次交接后的半开连接正是这样被误判为硬卡死）。
func SetHuyaIdleReadTimeoutMs(ms int64) {
	if ms != 0 && (ms < 3000 || ms > 20000) {
		return
	}
	huyaIdleReadTimeoutOverride.Store(ms)
}

// GetHuyaIdleReadTimeoutMs 返回当前生效的空闲读超时（毫秒）。
func GetHuyaIdleReadTimeoutMs() int64 {
	if v := huyaIdleReadTimeoutOverride.Load(); v > 0 {
		return v
	}
	return defaultHuyaIdleReadTimeoutMs
}

func huyaIdleReadTimeout() time.Duration {
	return time.Duration(GetHuyaIdleReadTimeoutMs()) * time.Millisecond
}

// ── 连续短段换线阈值 ───────────────────────────────────────────

// SetHuyaMaxShortStreak 调整虎牙连续短段达到多少次即换线。
// 0 恢复默认 4；有效范围 1..10（虎牙档不允许按钮关掉换线，故不设 <=0 语义）。
func SetHuyaMaxShortStreak(count int64) {
	if count != 0 && (count < 1 || count > 10) {
		return
	}
	huyaMaxShortStreakOverride.Store(count)
}

// GetHuyaMaxShortStreak 返回当前生效的连续短段换线阈值。
func GetHuyaMaxShortStreak() int64 {
	if v := huyaMaxShortStreakOverride.Load(); v > 0 {
		return v
	}
	return defaultHuyaMaxShortStreak
}

func huyaMaxShortStreakCurrent() int { return int(GetHuyaMaxShortStreak()) }

// ── 使用点 helper：只作用于虎牙档，对在播会话即时生效 ───────────

// 以下 helper 以 tuning.name 严格限定虎牙档，斗鱼/通用档始终读构造时的拷贝值，
// 不受虎牙 setter 影响。streamLoop 在每次循环用它们取当前生效值。
func (p *flvProxy) effectivePlannedLeadMs() int64 {
	if p.tuning.name == "huya-yqk" {
		return huyaPlannedRefreshLead()
	}
	return p.tuning.plannedRefreshLeadMs
}

func (p *flvProxy) effectiveNominalSessionMs() int64 {
	if p.tuning.name == "huya-yqk" {
		return huyaNominalSession()
	}
	return p.tuning.nominalSessionMs
}

func (p *flvProxy) effectiveShortBackoff() (base, max time.Duration) {
	if p.tuning.name == "huya-yqk" {
		return huyaShortBackoffBaseCurrent(), huyaShortBackoffMaxCurrent()
	}
	return p.tuning.shortBackoffBase, p.tuning.shortBackoffMax
}

func (p *flvProxy) effectiveMaxShortStreak() int {
	if p.tuning.name == "huya-yqk" {
		return huyaMaxShortStreakCurrent()
	}
	return p.tuning.maxShortStreak
}
