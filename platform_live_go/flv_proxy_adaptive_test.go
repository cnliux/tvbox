package platformlive

import (
	"bufio"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// ───────────────────── 房间自适应核心状态 ─────────────────────

// TestRoomAdaptiveInstantRetryBudget：短会话但推过数据 → 立即重连有配额
// （封顶防风暴），配额用尽后回到正常退避。
func TestRoomAdaptiveInstantRetryBudget(t *testing.T) {
	var a roomAdaptive
	for i := 0; i < adaptiveInstantRetryMax; i++ {
		if !a.beginInstantRetry() {
			t.Fatalf("第 %d 次立即重连应在配额内", i+1)
		}
	}
	if a.beginInstantRetry() {
		t.Fatal("配额用尽后不应再允许立即重连")
	}
	// 一次达到正常长度的会话（≥ shortSessionMs）应清零配额。
	a.noteSession("https://cdn/test.flv?wsSecret=a", 60000, 4000, true)
	if !a.beginInstantRetry() {
		t.Fatal("稳定会话后配额应清零")
	}
}

// TestRoomAdaptiveNoteSessionIgnoresEmpty：空段（没推过数据）不记健康度、
// 也不清零配额（冷房间的 7ms 空段正是这种形态，必须继续走耐心退避）。
func TestRoomAdaptiveNoteSessionIgnoresEmpty(t *testing.T) {
	var a roomAdaptive
	if !a.beginInstantRetry() {
		t.Fatal("初始应允许立即重连")
	}
	a.noteSession("https://cdn/test.flv", 7, 4000, false)
	if a.bestSession() != 0 {
		t.Fatalf("空段不应记入房间健康度，实际 %d", a.bestSession())
	}
	if !a.beginInstantRetry() {
		t.Fatal("空段不应清零立即重连配额")
	}
}

// TestRoomAdaptiveShortBackoffWarmRoom：房间交付过健康长会话后短段退避减半；
// 冷房间保持档位默认。
func TestRoomAdaptiveShortBackoffWarmRoom(t *testing.T) {
	var a roomAdaptive
	base, max := 2*time.Second, 8*time.Second
	gotBase, gotMax := a.shortBackoffFor(base, max)
	if gotBase != base || gotMax != max {
		t.Fatalf("冷房间应保持默认退避，实际 base=%s max=%s", gotBase, gotMax)
	}
	a.noteSession("https://cdn/test.flv", 120000, 4000, true)
	if !a.roomHealthy() {
		t.Fatal("120s 会话后房间应视为健康")
	}
	gotBase, gotMax = a.shortBackoffFor(base, max)
	if gotBase != time.Second || gotMax != 4*time.Second {
		t.Fatalf("健康房间退避应减半，实际 base=%s max=%s", gotBase, gotMax)
	}
}

// TestRoomAdaptiveShortBackoffFloor：退避减半有下限，不会低到无意义的值。
func TestRoomAdaptiveShortBackoffFloor(t *testing.T) {
	var a roomAdaptive
	a.noteSession("https://cdn/test.flv", 120000, 4000, true)
	gotBase, gotMax := a.shortBackoffFor(600*time.Millisecond, 1500*time.Millisecond)
	if gotBase < adaptiveMinBackoff {
		t.Fatalf("退避基数不应低于下限，实际 %s", gotBase)
	}
	if gotMax < gotBase*2 {
		t.Fatalf("退避上限不应低于基数的两倍，实际 max=%s base=%s", gotMax, gotBase)
	}
}

// TestRoomAdaptiveURLHealthLRU：URL 健康历史按 FIFO 淘汰，条目数有界。
func TestRoomAdaptiveURLHealthLRU(t *testing.T) {
	var a roomAdaptive
	for i := 0; i < adaptiveURLHealthMax+3; i++ {
		a.noteSession("https://cdn/session.flv?n="+string(rune('a'+i)), int64(1000+i), 4000, true)
	}
	a.mu.Lock()
	count := len(a.urlBest)
	order := len(a.urlOrder)
	a.mu.Unlock()
	if count != adaptiveURLHealthMax || order != adaptiveURLHealthMax {
		t.Fatalf("URL 健康历史应有界（%d 条），实际 map=%d order=%d",
			adaptiveURLHealthMax, count, order)
	}
}

// ───────────────────── tag 间隔采样与自适应空闲超时 ─────────────────────

// TestAdaptiveTagGapSampling：段内采样峰值、段开始清零、段结束折叠进 EWMA
//（新峰值立即生效，历史峰值随会话衰减）。
func TestAdaptiveTagGapSampling(t *testing.T) {
	p := newManualDouyuProxy("douyu")
	defer p.cancel()
	p.resetTagGap()
	t0 := time.Now()
	p.noteTagGap(t0)
	p.noteTagGap(t0.Add(3 * time.Second)) // 段内峰值 3s
	p.noteTagGap(t0.Add(3500 * time.Millisecond))
	p.statsMu.Lock()
	peak := p.adaptiveGapPeakMs
	p.statsMu.Unlock()
	if peak != 3000 {
		t.Fatalf("段内峰值应为 3000ms，实际 %d", peak)
	}
	// 段结束：峰值 3000 折叠进 EWMA。
	p.foldTagGap()
	p.statsMu.Lock()
	ewma := p.adaptiveGapEwmaMs
	p.statsMu.Unlock()
	if ewma != 3000 {
		t.Fatalf("首段峰值应直接成为 EWMA，实际 %d", ewma)
	}
	// 第二段峰值 1s：EWMA 应衰减到 max(1000, 3000×2/3=2000)。
	p.resetTagGap()
	t1 := time.Now()
	p.noteTagGap(t1)
	p.noteTagGap(t1.Add(time.Second))
	p.foldTagGap()
	p.statsMu.Lock()
	ewma = p.adaptiveGapEwmaMs
	p.statsMu.Unlock()
	if ewma != 2000 {
		t.Fatalf("EWMA 应衰减为 2000ms，实际 %d", ewma)
	}
}

// TestAdaptiveIdleReadTimeout：默认 15s；房间 tag 间隔大时按 3×EWMA 抬高；
// 手动 override（调试按钮）永远优先；封顶 40s。
func TestAdaptiveIdleReadTimeout(t *testing.T) {
	p := newManualDouyuProxy("douyu")
	defer p.cancel()
	// 未采样：默认 15s。
	if got := p.adaptiveIdleReadTimeout(); got != 15*time.Second {
		t.Fatalf("未采样应回落默认 15s，实际 %s", got)
	}
	// EWMA 6s → 3×6=18s。
	p.statsMu.Lock()
	p.adaptiveGapEwmaMs = 6000
	p.statsMu.Unlock()
	if got := p.adaptiveIdleReadTimeout(); got != 18*time.Second {
		t.Fatalf("EWMA 6s 应抬高到 18s，实际 %s", got)
	}
	// 封顶 40s。
	p.statsMu.Lock()
	p.adaptiveGapEwmaMs = 30000
	p.statsMu.Unlock()
	if got := p.adaptiveIdleReadTimeout(); got != 40*time.Second {
		t.Fatalf("自适应空闲超时应封顶 40s，实际 %s", got)
	}
	// 手动 override 优先（且作为下限：override 小于自适应值时仍用 override）。
	SetDouyuIdleReadTimeoutMs(20000)
	defer SetDouyuIdleReadTimeoutMs(0)
	if got := p.adaptiveIdleReadTimeout(); got != 20*time.Second {
		t.Fatalf("手动 override 应优先生效，实际 %s", got)
	}
}

// TestIdleReadConnProvider：idleFn 非空时每次 Read 动态取超时并生效
//（net.Pipe 上 50ms 内无数据 → 读超时）。
func TestIdleReadConnProvider(t *testing.T) {
	server, client := net.Pipe()
	defer server.Close()
	defer client.Close()
	conn := &idleReadConn{Conn: client, idle: time.Hour, idleFn: func() time.Duration {
		return 50 * time.Millisecond
	}}
	started := time.Now()
	_, err := conn.Read(make([]byte, 16))
	elapsed := time.Since(started)
	if err == nil {
		t.Fatal("无数据时应读超时")
	}
	if !isNetworkTimeout(err) {
		t.Fatalf("应为网络超时错误，实际 %v", err)
	}
	if elapsed > 2*time.Second {
		t.Fatalf("idleFn 应覆盖静态 idle（50ms 超时），实际 %s", elapsed)
	}
}

// ───────────────────── 档位开关 ─────────────────────

// TestAdaptiveTuningFlags：只有斗鱼启用房间自适应；虎牙/通用档绝不借用。
func TestAdaptiveTuningFlags(t *testing.T) {
	if !siteTuningFor("douyu").adaptiveReconnect {
		t.Fatal("douyu 档应启用 adaptiveReconnect")
	}
	if siteTuningFor("huya").adaptiveReconnect {
		t.Fatal("huya 档不应启用 adaptiveReconnect")
	}
	if siteTuningFor("bilibili").adaptiveReconnect {
		t.Fatal("通用档不应启用 adaptiveReconnect")
	}
}

// ───────────────────── 端到端：瞬态短会话立即重连 ─────────────────────

// TestDouyuAdaptiveInstantRetryOnTransientSession：第一段正常推数据后很快
// 被 CDN 关流（短会话但 dataPushed）→ 代理必须立即发起第二段（无 2s 退避），
// 第二段恢复为长会话。这是斗鱼「开机黑屏 3~6s」的自适应修复路径。
func TestDouyuAdaptiveInstantRetryOnTransientSession(t *testing.T) {
	var reqCount int64
	var req2At atomic.Int64
	var req1At atomic.Int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt64(&reqCount, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		if n == 1 {
			req1At.Store(time.Now().UnixNano())
			// 短会话：推 4 个标签后立刻关流（瞬态踢连接）。
			writeTestFLVEx(w, []testFrame{
				{ts: 1000, video: true, keyframe: true},
				{ts: 1000, audio: true},
				{ts: 1033, video: true},
				{ts: 1033, audio: true},
			})
			return
		}
		req2At.Store(time.Now().UnixNano())
		// 第二段：长会话，保持连接直到客户端离开。
		writeTestFLVEx(w, []testFrame{
			{ts: 1200, video: true, keyframe: true},
			{ts: 1200, audio: true},
		})
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		select {
		case <-r.Context().Done():
		case <-time.After(10 * time.Second):
		}
	}))
	defer upstream.Close()

	service := &Service{byID: map[string]Adapter{"douyu": &fakeProxyAdapter{streamURL: upstream.URL}}}
	proxy, err := newFlvProxy(service, "douyu", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()
	resp, err := http.Get(proxy.url)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer resp.Body.Close()
	reader := bufio.NewReader(resp.Body)
	// 读到第二段的帧即证明重连成功。
	frames, rerr := readTestFLVTagsEx(reader, 6)
	if rerr != nil && len(frames) < 6 {
		t.Fatalf("输出不足：%v", rerr)
	}
	// 第二段是否发生以 reqCount 为准；req1At/req2At 在 Windows 粗时钟粒度下
	// 可能落到同一刻度（gap==0 即 0ms 内完成重连，恰是「立即重连」的预期形态），
	// 不能以 gap<=0 判定未发生。
	if atomic.LoadInt64(&reqCount) < 2 {
		t.Fatalf("第二段请求未发生：reqCount=%d req1At=%d req2At=%d frames=%d rerr=%v",
			atomic.LoadInt64(&reqCount), req1At.Load(), req2At.Load(), len(frames), rerr)
	}
	if gapNs := req2At.Load() - req1At.Load(); gapNs > int64(1500*time.Millisecond) {
		t.Fatalf("瞬态短会话应立即重连（自适应），实际第二段距第一段 %dms（≥1500 说明仍在退避）", gapNs/int64(time.Millisecond))
	}
	if p := proxy.adaptive.instantStreakSnapshot(); p < 1 {
		t.Fatalf("立即重连应登记配额使用，实际 %d", p)
	}
	// statsJSON 暴露自适应字段。
	stats := map[string]interface{}{}
	if err := json.Unmarshal([]byte(proxy.statsJSON()), &stats); err != nil {
		t.Fatalf("statsJSON 解析失败：%v", err)
	}
	if v, _ := stats["adaptiveInstantReconnects"].(float64); v < 1 {
		t.Fatalf("statsJSON 应有 adaptiveInstantReconnects>=1，实际 %v", stats["adaptiveInstantReconnects"])
	}
	if v, _ := stats["adaptiveIdleReadTimeoutMs"].(float64); v != 15000 {
		t.Fatalf("statsJSON adaptiveIdleReadTimeoutMs 默认应为 15000，实际 %v", stats["adaptiveIdleReadTimeoutMs"])
	}
}
