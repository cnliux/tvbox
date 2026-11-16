package platformlive

// 斗鱼「一起看」续流参数的运行时调优。
//
// 背景：斗鱼匿名流地址带租约（expire=N 秒），CDN 到点主动断流。代理在到期前
// 主动重解析换签名，本应是一次「无感」的计划内切换；但若提前量/空闲超时等
// 参数与当前网络或 CDN 行为不匹配，就会表现为「总是重连」。
//
// 这些参数原先是 const，无法真机调整。这里把它们改成「默认值 + 可写全局」，
// 通过 gomobile 导出的 SetDouyu* 暴露给 Kotlin，使手机端按钮可以运行时调整，
// 新 value 立即影响之后建立的代理会话（租约提前量、空闲超时在每个会话读取）。
//
// 每个 setter 都做范围钳制，非法值被忽略，避免按钮误触把代理调到不可用状态。

import (
	"sync/atomic"
	"time"
)

const (
	// 租约续期提前量默认 45s（与历史常量 douyuLeaseRefreshLeadMs 一致）。
	defaultDouyuLeaseRefreshLeadMs = int64(45000)
	// 上游空闲读超时默认 15s（与 flvProxyIdleReadTimeout 一致）。
	defaultDouyuIdleReadTimeoutMs = int64(15000)
	// 短段退避基数默认 2s（与 douyu-yqk tuning 一致）。
	defaultDouyuShortBackoffBaseMs = int64(2000)
	// 短段退避上限默认 8s。
	defaultDouyuShortBackoffMaxMs = int64(8000)
)

var (
	douyuLeaseRefreshLeadOverride atomic.Int64 // <=0 表示用默认
	douyuIdleReadTimeoutOverride  atomic.Int64 // <=0 表示用默认
	douyuShortBackoffBaseOverride atomic.Int64 // <=0 表示用默认
	douyuShortBackoffMaxOverride  atomic.Int64 // <=0 表示用默认
)

// ── 租约续期提前量 ─────────────────────────────────────────────

// SetDouyuLeaseRefreshLeadMs 调整斗鱼租约到期前主动续期的提前量（毫秒）。
// 0 恢复默认 45000ms；有效范围 5000..120000ms。
func SetDouyuLeaseRefreshLeadMs(ms int64) {
	if ms != 0 && (ms < 5000 || ms > 120000) {
		return
	}
	douyuLeaseRefreshLeadOverride.Store(ms)
}

// GetDouyuLeaseRefreshLeadMs 返回当前生效的续期提前量（毫秒）。
func GetDouyuLeaseRefreshLeadMs() int64 {
	if v := douyuLeaseRefreshLeadOverride.Load(); v > 0 {
		return v
	}
	return defaultDouyuLeaseRefreshLeadMs
}

func douyuLeaseRefreshLead() int64 {
	return GetDouyuLeaseRefreshLeadMs()
}

// ── 上游空闲读超时 ─────────────────────────────────────────────

// SetDouyuIdleReadTimeoutMs 调整上游连续无数据多久判为半开连接并重连（毫秒）。
// 0 恢复默认 15000ms；有效范围 5000..60000ms。过短会在正常卡顿处误重连。
func SetDouyuIdleReadTimeoutMs(ms int64) {
	if ms != 0 && (ms < 5000 || ms > 60000) {
		return
	}
	douyuIdleReadTimeoutOverride.Store(ms)
}

// GetDouyuIdleReadTimeoutMs 返回当前生效的空闲读超时（毫秒）。
func GetDouyuIdleReadTimeoutMs() int64 {
	if v := douyuIdleReadTimeoutOverride.Load(); v > 0 {
		return v
	}
	return defaultDouyuIdleReadTimeoutMs
}

func douyuIdleReadTimeout() time.Duration {
	return time.Duration(GetDouyuIdleReadTimeoutMs()) * time.Millisecond
}

// ── 短段退避 ───────────────────────────────────────────────────

// SetDouyuShortBackoffBaseMs 调整斗鱼空/短段后的退避基数（毫秒）。
// 0 恢复默认 2000ms；有效范围 200..10000ms。
func SetDouyuShortBackoffBaseMs(ms int64) {
	if ms != 0 && (ms < 200 || ms > 10000) {
		return
	}
	douyuShortBackoffBaseOverride.Store(ms)
}

// GetDouyuShortBackoffBaseMs 返回当前生效的短段退避基数（毫秒）。
func GetDouyuShortBackoffBaseMs() int64 {
	if v := douyuShortBackoffBaseOverride.Load(); v > 0 {
		return v
	}
	return defaultDouyuShortBackoffBaseMs
}

// SetDouyuShortBackoffMaxMs 调整斗鱼空/短段后的退避上限（毫秒）。
// 0 恢复默认 8000ms；有效范围 1000..30000ms。
func SetDouyuShortBackoffMaxMs(ms int64) {
	if ms != 0 && (ms < 1000 || ms > 30000) {
		return
	}
	douyuShortBackoffMaxOverride.Store(ms)
}

// GetDouyuShortBackoffMaxMs 返回当前生效的短段退避上限（毫秒）。
func GetDouyuShortBackoffMaxMs() int64 {
	if v := douyuShortBackoffMaxOverride.Load(); v > 0 {
		return v
	}
	return defaultDouyuShortBackoffMaxMs
}
