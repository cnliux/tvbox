package platformlive

// flv_proxy_metrics_test.go —— session metrics / close reason 单元测试。
// 覆盖：开始/结束、枚举引用、LastError 空/非空、Snapshot 倒序、并发安全、
// 流量字段读写、快照为副本。

import (
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"
)

func TestMetricsStartEndSingleSnapshot(t *testing.T) {
	r := NewMetricsRegistry()
	r.StartSession("s1")
	time.Sleep(2 * time.Millisecond)
	r.EndSession("s1", CloseReasonUpstreamEOF, nil)
	got := r.Snapshot()
	if len(got) != 1 {
		t.Fatalf("期望 1 条快照，得到 %d", len(got))
	}
	if got[0].CloseReason != CloseReasonUpstreamEOF {
		t.Fatalf("CloseReason = %q, 期望 %q", got[0].CloseReason, CloseReasonUpstreamEOF)
	}
	if got[0].StartedAt.IsZero() || got[0].ClosedAt.IsZero() {
		t.Fatalf("时间戳未记录: %+v", got[0])
	}
}

func TestMetricsAllCloseReasonsReferencable(t *testing.T) {
	all := []SessionCloseReason{
		CloseReasonUpstreamEOF,
		CloseReasonUpstreamError,
		CloseReasonClientDisconnect,
		CloseReasonReconnectExhausted,
		CloseReasonServerShutdown,
		CloseReasonIdleTimeout,
		CloseReasonUnknown,
	}
	if len(all) != 7 {
		t.Fatalf("枚举数量 = %d, 期望 7", len(all))
	}
	seen := map[SessionCloseReason]bool{}
	for _, c := range all {
		if c == "" {
			t.Fatalf("枚举值不应为空串")
		}
		if seen[c] {
			t.Fatalf("枚举值重复: %q", c)
		}
		seen[c] = true
	}
}

func TestMetricsEndSessionNilErrorEmptyLastError(t *testing.T) {
	r := NewMetricsRegistry()
	r.StartSession("e1")
	r.EndSession("e1", CloseReasonClientDisconnect, nil)
	got := r.Snapshot()
	if len(got) != 1 || got[0].LastError != "" {
		t.Fatalf("LastError 应为空, 得到 %+v", got)
	}
}

func TestMetricsEndSessionWithErrorSetsLastError(t *testing.T) {
	r := NewMetricsRegistry()
	r.StartSession("e2")
	r.EndSession("e2", CloseReasonUpstreamError, errors.New("boom upstream"))
	got := r.Snapshot()
	if len(got) != 1 || got[0].LastError != "boom upstream" {
		t.Fatalf("LastError 未记录: %+v", got)
	}
}

func TestMetricsSnapshotReverseOrderByClosedAt(t *testing.T) {
	r := NewMetricsRegistry()
	for i := 0; i < 5; i++ {
		id := fmt.Sprintf("m%d", i)
		r.StartSession(id)
		time.Sleep(3 * time.Millisecond)
		r.EndSession(id, CloseReasonUpstreamEOF, nil)
	}
	got := r.Snapshot()
	if len(got) != 5 {
		t.Fatalf("期望 5 条, 得到 %d", len(got))
	}
	for i := 1; i < len(got); i++ {
		if got[i].ClosedAt.After(got[i-1].ClosedAt) {
			t.Fatalf("Snapshot 未按 ClosedAt 倒序: index %d", i)
		}
	}
}

func TestMetricsConcurrentStartEndNoRace(t *testing.T) {
	r := NewMetricsRegistry()
	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			id := fmt.Sprintf("c%d", i)
			r.StartSession(id)
			r.EndSession(id, CloseReasonServerShutdown, nil)
			_ = r.Snapshot()
		}(i)
	}
	wg.Wait()
	if got := r.Snapshot(); len(got) != 32 {
		t.Fatalf("期望 32 条已结束会话, 得到 %d", len(got))
	}
}

func TestMetricsTrafficFieldsRoundTrip(t *testing.T) {
	r := NewMetricsRegistry()
	r.StartSession("t1")
	e := r.sessions["t1"]
	e.m.BytesUp = 1024
	e.m.BytesDown = 1 << 20
	e.m.ReconnectCount = 3
	r.EndSession("t1", CloseReasonIdleTimeout, nil)
	got := r.Snapshot()
	if len(got) != 1 {
		t.Fatalf("期望 1 条, 得到 %d", len(got))
	}
	m := got[0]
	if m.BytesUp != 1024 || m.BytesDown != 1<<20 || m.ReconnectCount != 3 {
		t.Fatalf("字段读回失败: %+v", m)
	}
}

func TestMetricsSnapshotIsCopy(t *testing.T) {
	r := NewMetricsRegistry()
	r.StartSession("cp1")
	r.EndSession("cp1", CloseReasonReconnectExhausted, nil)
	got := r.Snapshot()
	got[0].BytesDown = 999999
	got[0].CloseReason = CloseReasonUnknown
	again := r.Snapshot()
	if len(again) != 1 {
		t.Fatalf("期望 1 条, 得到 %d", len(again))
	}
	if again[0].BytesDown == 999999 || again[0].CloseReason != CloseReasonReconnectExhausted {
		t.Fatalf("Snapshot 未返回副本: %+v", again[0])
	}
}

func TestMetricsEndSessionIdempotentAndUnknownID(t *testing.T) {
	r := NewMetricsRegistry()
	r.EndSession("ghost", CloseReasonUnknown, nil) // 不存在的 id：无副作用
	r.StartSession("dup")
	time.Sleep(1 * time.Millisecond)
	r.EndSession("dup", CloseReasonUpstreamEOF, nil)
	first := time.Time{}
	for _, m := range r.Snapshot() {
		if m.CloseReason == CloseReasonUpstreamEOF {
			first = m.ClosedAt
		}
	}
	time.Sleep(2 * time.Millisecond)
	r.EndSession("dup", CloseReasonClientDisconnect, errors.New("second")) // 幂等
	for _, m := range r.Snapshot() {
		if m.CloseReason == CloseReasonUpstreamEOF {
			if !m.ClosedAt.Equal(first) || m.LastError != "" {
				t.Fatalf("EndSession 应幂等: %+v", m)
			}
		}
	}
}

func TestMetricsDefaultRegistryExists(t *testing.T) {
	if DefaultMetrics == nil {
		t.Fatalf("DefaultMetrics 未初始化")
	}
	id := fmt.Sprintf("global-%d", time.Now().UnixNano())
	DefaultMetrics.StartSession(id)
	DefaultMetrics.EndSession(id, CloseReasonUnknown, nil)
	found := false
	for _, m := range DefaultMetrics.Snapshot() {
		if m.CloseReason == CloseReasonUnknown && !m.ClosedAt.IsZero() {
			found = true
		}
	}
	if !found {
		t.Fatalf("DefaultMetrics 未记录会话")
	}
}
