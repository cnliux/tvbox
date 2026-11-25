package platformlive

// FLV 代理 session 指标与关闭原因统计（方案 P32）。
//
// 背景：flv_proxy.go 的会话（上游拉流 + 本地下发）缺少统一的故障指标，关闭
// 路径（Close / abortProxy / server.Close）分散，无法在事后回答「这场直播为
// 什么断」。本文件只提供线程安全的指标数据结构与全局注册表；具体接线（在
// Close / abortProxy 等路径调用 StartSession / EndSession）由维护者后续补做，
// 不修改既有拉流逻辑。

import (
	"sync"
	"time"
)

// SessionCloseReason 标识一次 FLV 代理会话的结束原因，用于事后故障归因。
type SessionCloseReason string

const (
	// CloseReasonUpstreamEOF 上游正常结束（CDN 单次拉流时长上限等）。
	CloseReasonUpstreamEOF SessionCloseReason = "upstream_eof"
	// CloseReasonUpstreamError 上游返回错误（HTTP 非 200 / 连接被重置等）。
	CloseReasonUpstreamError SessionCloseReason = "upstream_error"
	// CloseReasonClientDisconnect 播放器主动断开本地连接。
	CloseReasonClientDisconnect SessionCloseReason = "client_disconnect"
	// CloseReasonReconnectExhausted 所有候选线路的重连次数耗尽。
	CloseReasonReconnectExhausted SessionCloseReason = "reconnect_exhausted"
	// CloseReasonServerShutdown 本地 HTTP server 被关闭（handle.Close 等）。
	CloseReasonServerShutdown SessionCloseReason = "server_shutdown"
	// CloseReasonIdleTimeout 进度看门狗判定长时间无有效数据。
	CloseReasonIdleTimeout SessionCloseReason = "idle_timeout"
	// CloseReasonUnknown 未知/未分类的结束原因，兜底避免空值。
	CloseReasonUnknown SessionCloseReason = "unknown"
)

// SessionMetrics 记录一次代理会话的生命周期与流量统计。
type SessionMetrics struct {
	StartedAt      time.Time
	ClosedAt       time.Time
	CloseReason    SessionCloseReason
	BytesUp        int64
	BytesDown      int64
	ReconnectCount int
	LastError      string
}

// MetricsRegistry 线程安全地维护会话指标；EndSession 只在首次时生效，保证
// 一个会话仅被记录一次（幂等，适配 Close 可被多次调用的现状）。
type MetricsRegistry struct {
	mu       sync.Mutex
	sessions map[string]*metricsEntry
}

// metricsEntry 在快照时携带 id，用于排序兜底（ClosedAt 相同时保持稳定）。
type metricsEntry struct {
	id string
	m  SessionMetrics
}

// NewMetricsRegistry 创建一个空的指标注册表。
func NewMetricsRegistry() *MetricsRegistry {
	return &MetricsRegistry{sessions: make(map[string]*metricsEntry)}
}

// DefaultMetrics 全局默认注册表；接线时直接使用，避免各处自建实例。
var DefaultMetrics = NewMetricsRegistry()

// StartSession 标记会话开始；若该 id 已存在则重新计时（重连场景）。
func (r *MetricsRegistry) StartSession(id string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.sessions == nil {
		r.sessions = make(map[string]*metricsEntry)
	}
	r.sessions[id] = &metricsEntry{id: id, m: SessionMetrics{StartedAt: time.Now()}}
}

// EndSession 标记会话结束；lastErr 为 nil 时 LastError 留空，否则记录其消息。
func (r *MetricsRegistry) EndSession(id string, reason SessionCloseReason, lastErr error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.sessions[id]
	if !ok || !e.m.ClosedAt.IsZero() {
		return
	}
	e.m.ClosedAt = time.Now()
	e.m.CloseReason = reason
	if e.m.CloseReason == "" {
		e.m.CloseReason = CloseReasonUnknown
	}
	if lastErr != nil {
		e.m.LastError = lastErr.Error()
	}
}

// Snapshot 返回所有已结束会话的副本，按 ClosedAt 倒序（最近结束在前）。
func (r *MetricsRegistry) Snapshot() []SessionMetrics {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]SessionMetrics, 0, len(r.sessions))
	for _, e := range r.sessions {
		if e.m.ClosedAt.IsZero() {
			continue
		}
		out = append(out, e.m)
	}
	// 插入排序：按 ClosedAt 倒序；相同时按 StartedAt 倒序兜底，保证稳定。
	for i := 1; i < len(out); i++ {
		for j := i; j > 0 && metricsBefore(out[j], out[j-1]); j-- {
			out[j], out[j-1] = out[j-1], out[j]
		}
	}
	return out
}

// metricsBefore 报告 a 是否应排在 b 之前（即 a 比 b 更晚结束）。
func metricsBefore(a, b SessionMetrics) bool {
	if !a.ClosedAt.Equal(b.ClosedAt) {
		return a.ClosedAt.After(b.ClosedAt)
	}
	return a.StartedAt.After(b.StartedAt)
}
