package platformlive

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// ───────────────────── 斗鱼专属行为（独立于虎牙路径） ─────────────────────

// TestDouyuTimelineContinuous：斗鱼两次上游连接共享同一时间轴（pure_live 实测）。
// 第二段源时间戳接续第一段（非归零），代理必须原样沿用，不得做 spliceBase 平移——
// 平移正是斗鱼「音画不同步」的根因（时间轴被人为拉回/钳制，音视频相对偏移）。
func TestDouyuTimelineContinuous(t *testing.T) {
	var reqCount int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt64(&reqCount, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		if n == 1 {
			// 第一段：ts 1000..1066，正常关流。
			writeTestFLVEx(w, []testFrame{
				{ts: 1000, video: true, keyframe: true},
				{ts: 1000, audio: true},
				{ts: 1033, video: true},
				{ts: 1066, audio: true},
			})
			return
		}
		// 第二段（关键差异）：源时间戳「接续」第一段但留 134ms 空隙（斗鱼解析
		// 间隙直播边前移的真实形态）。连续模式输出必须原样保留 1200/1233；
		// 虎牙的归零重置路径会把首帧平移到 maxOut+33=1099 —— 两者可区分。
		writeTestFLVEx(w, []testFrame{
			{ts: 1200, video: true, keyframe: true},
			{ts: 1200, audio: true},
			{ts: 1233, video: true},
			{ts: 1233, audio: true},
		})
	}))
	defer upstream.Close()

	service := &Service{byID: map[string]Adapter{"douyu": &fakeProxyAdapter{streamURL: upstream.URL}}}
	proxy, err := newFlvProxy(service, "douyu", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()
	// 伪装客户端连上本地代理触发拉流。
	resp, err := http.Get(proxy.url)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer resp.Body.Close()
	reader := bufio.NewReader(resp.Body)
	frames, err := readTestFLVTagsEx(reader, 8)
	if err != nil && len(frames) < 8 {
		t.Fatalf("读取输出不足：%v（已读 %d）", err, len(frames))
	}
	// 断言第二段输出时间戳逐帧等于源时间戳（无任何平移痕迹）。
	wantSecond := []frameInfo{
		{ts: 1200, video: true, keyframe: true},
		{ts: 1200, audio: true},
		{ts: 1233, video: true},
		{ts: 1233, audio: true},
	}
	for i, want := range wantSecond {
		got := frames[4+i]
		if got.ts != want.ts || got.video != want.video || got.audio != want.audio {
			t.Fatalf("第二段第 %d 帧应为 %+v，实际 %+v（全部 %v）", i, want, got, frames)
		}
	}
	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(proxy.statsJSON()), &stats); err != nil {
		t.Fatalf("statsJSON 解析失败：%v", err)
	}
	if v, _ := stats["timelineContinuous"].(bool); !v {
		t.Fatalf("斗鱼应启用连续时间轴模式：%v", stats["timelineContinuous"])
	}
}

// TestDouyuTimelineContinuousOverlap：第二段源 ts 与第一段输出重叠（斗鱼重连后
// CDN 带回一小段旧缓冲，源 ts 落后于已交付位置）。连续模式下不得把整段平移到
// maxOutTs+33（虎牙行为会把时间轴往后撕出空洞），而应保持源 ts，同轨回退帧由
// acceptTs/指纹去重丢弃。这里断言：重叠段之后的新帧以源 ts 输出，不被平移。
func TestDouyuTimelineContinuousOverlap(t *testing.T) {
	got := douyuContinuityOffset(1066, 900) // 新段首帧落后已交付 166ms（小重叠）
	if got != 0 {
		t.Fatalf("对齐窗口内的小重叠应保持同轴（offset=0），实际 %d", got)
	}
	if got := douyuContinuityOffset(1066, 1066); got != 0 {
		t.Fatalf("同轴相接应 offset=0，实际 %d", got)
	}
	if got := douyuContinuityOffset(1066, 50000); got != 0 {
		t.Fatalf("新段领先 48934ms（窗口 60s 内）应 offset=0，实际 %d", got)
	}
	// 超出对齐窗口 = 另一条时间轴：平移续接（last+1-source）。
	if got := douyuContinuityOffset(1066, 500000); got != 1067-500000 {
		t.Fatalf("窗口外应平移续接，实际 %d", got)
	}
	if got := douyuContinuityOffset(0, 500); got != 0 {
		t.Fatalf("首段（无已交付位置）应 offset=0，实际 %d", got)
	}
}

// TestPatchDouyuURL：ws 短租线 expire=0 补丁只作用于斗鱼特征地址，
// 其它地址绝不修改。
func TestPatchDouyuURL(t *testing.T) {
	ws := "https://hwflv.douyucdn2.cn/live/xxx.flv?wsSecret=a&wsTime=b&expire=300&fcdn=ws"
	got := patchDouyuURL(ws)
	if !strings.Contains(got, "expire=0") || strings.Contains(got, "expire=300") {
		t.Fatalf("ws 短租线应改为 expire=0，实际 %s", got)
	}
	// 非 ws 线（如 hcdn/al）不动。
	hcd := "https://tc-tct.douyucdn2.cn/live/xxx.flv?wsSecret=a&wsTime=b&expire=300&fcdn=hcdn"
	if patchDouyuURL(hcd) != hcd {
		t.Fatalf("非 ws 线不应修改，实际 %s", patchDouyuURL(hcd))
	}
	// 无 expire 的地址不动。
	plain := "https://hwflv.douyucdn2.cn/live/xxx.flv?wsSecret=a&wsTime=b"
	if patchDouyuURL(plain) != plain {
		t.Fatalf("无 expire 地址不应修改，实际 %s", patchDouyuURL(plain))
	}
}

// TestDouyuLeaseDeadline：带 expire 的地址按「到期前 45s」返回主动续期时刻；
// 无 expire / 已过期的地址返回零值（走被动 EOF 路径）。
func TestDouyuLeaseDeadline(t *testing.T) {
	now := time.Now()
	long := "https://hwflv.douyucdn2.cn/live/xxx.flv?expire=300&fcdn=hcdn"
	d := douyuLeaseDeadline(long, now)
	if d.IsZero() {
		t.Fatal("expire=300 的地址应有主动续期时刻")
	}
	want := now.Add(time.Duration(300*1000-45000) * time.Millisecond)
	if d.Sub(want) > 2*time.Second || want.Sub(d) > 2*time.Second {
		t.Fatalf("续期时刻应在到期前 45s，实际偏差 %s", d.Sub(want))
	}
	noLease := "https://hwflv.douyucdn2.cn/live/xxx.flv?wsSecret=a"
	if !douyuLeaseDeadline(noLease, now).IsZero() {
		t.Fatal("无 expire 地址不应有续期时刻")
	}
	short := "https://hwflv.douyucdn2.cn/live/xxx.flv?expire=10&fcdn=hcdn"
	if !douyuLeaseDeadline(short, now).IsZero() {
		t.Fatal("租约已不足提前量的地址应回退被动路径")
	}
}

// fakeResponseWriter 是直连 pull 单测用的假下游（记录写入字节数即可）。
type fakeResponseWriter struct{ buf bytes.Buffer }

func (f *fakeResponseWriter) Header() http.Header         { return http.Header{} }
func (f *fakeResponseWriter) Write(b []byte) (int, error) { return f.buf.Write(b) }
func (f *fakeResponseWriter) WriteHeader(int)             {}

// newManualDouyuProxy 构造不走 HTTP 服务器的最小代理实例（仅供直连 pull 单测）。
func newManualDouyuProxy(site string) *flvProxy {
	proxy := &flvProxy{tuning: siteTuningFor(site)}
	proxy.ctx, proxy.cancel = context.WithCancel(context.Background())
	proxy.httpClient = &http.Client{Transport: &http.Transport{DisableCompression: true}}
	proxy.client = &fakeResponseWriter{}
	return proxy
}

// TestDouyuPullLeaseExpired：租约到期时 pull 返回 leaseExpired=true 且 err=nil，
// 会话时长已记录（streamLoop 据此做计划内切换而非退避/换线）。
func TestDouyuPullLeaseExpired(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-flv")
		writeTestFLVEx(w, []testFrame{
			{ts: 100, video: true, keyframe: true},
			{ts: 100, audio: true},
		})
		// 立即冲刷，确保客户端 Do 能拿到响应（否则会阻塞到取消）。
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		// 保持连接不主动关闭，等待租约取消。
		select {
		case <-r.Context().Done():
		case <-time.After(30 * time.Second):
		}
	}))
	defer upstream.Close()

	proxy := newManualDouyuProxy("douyu")
	defer proxy.cancel()
	// 1 秒后触发「租约到期」。
	deadline := time.Now().Add(1 * time.Second)
	started := time.Now()
	leaseExpired, err := proxy.pull(LiveStreamCandidate{CDN: "test", URL: upstream.URL, Protocol: "flv"}, nil, deadline)
	elapsed := time.Since(started)
	if err != nil {
		t.Fatalf("租约到期应视为正常结束，实际错误：%v", err)
	}
	if !leaseExpired {
		t.Fatal("应返回 leaseExpired=true")
	}
	if elapsed > 5*time.Second {
		t.Fatalf("租约取消应在 1s 左右生效，实际 %s", elapsed)
	}
	proxy.statsMu.Lock()
	sessionMs := proxy.lastSessionMs
	proxy.statsMu.Unlock()
	if sessionMs < 500 {
		t.Fatalf("租约取消路径应记录会话时长，实际 %dms", sessionMs)
	}
}

// TestDouyuPullNoLeaseUnchanged：无租约语义时 pull 行为与历史一致
// （上游主动关流 → err=nil、leaseExpired=false）。
func TestDouyuPullNoLeaseUnchanged(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-flv")
		writeTestFLVEx(w, []testFrame{{ts: 100, video: true, keyframe: true}})
	}))
	defer upstream.Close()

	proxy := newManualDouyuProxy("huya")
	defer proxy.cancel()
	leaseExpired, err := proxy.pull(LiveStreamCandidate{CDN: "test", URL: upstream.URL, Protocol: "flv"}, nil, time.Time{})
	if err != nil {
		t.Fatalf("正常 EOF 不应报错：%v", err)
	}
	if leaseExpired {
		t.Fatal("无租约语义不应返回 leaseExpired=true")
	}
}

// TestDouyuStatsExposePolicy：statsJSON 暴露时间轴语义与租约开关，
// 便于真机监控确认斗鱼/虎牙各走各的策略。
func TestDouyuStatsExposePolicy(t *testing.T) {
	service := &Service{byID: map[string]Adapter{"douyu": &fakeProxyAdapter{}}}
	proxy, err := newFlvProxy(service, "douyu", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()
	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(proxy.statsJSON()), &stats); err != nil {
		t.Fatalf("statsJSON 解析失败：%v", err)
	}
	if v, _ := stats["timelineContinuous"].(bool); !v {
		t.Fatal("斗鱼 statsJSON 应有 timelineContinuous=true")
	}
	if v, _ := stats["leaseAware"].(bool); !v {
		t.Fatal("斗鱼 statsJSON 应有 leaseAware=true")
	}
}
