package platformlive

// 上游 FLV 标签长度损坏的软重连回归守卫。
//
// 真机监控（虎牙·一起看）观测到上游偶发 14~16MB 的非法 tag 长度（正常几十 KB）。
// 旧实现把它当普通硬失败：600ms 等待 + 失败计数，连续发生时播放器缓冲耗尽，
// Kotlin 12s 看门狗强制重建播放器/代理，表现为一次可见断流。
// 这里锁定新行为：代理立即重新解析新签名续流，不计失败、不换线、不清空缓冲。

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func TestMalformedFlvTagTriggersSoftReconnect(t *testing.T) {
	resetHuyaTuning()
	defer resetHuyaTuning()

	var sessions int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt64(&sessions, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		_, _ = w.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0, 0, 0, 9, 0, 0, 0, 0})
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		if n == 1 {
			// 第一条上游会话：仅写一个非法 tag 头（size=0xFFFFFF ≈ 16MB）即结束。
			_, _ = w.Write([]byte{byte(flvTagTypeVideo), 0xFF, 0xFF, 0xFF, 0, 0, 0, 0, 0, 0, 0})
			return
		}
		// 后续会话：正常帧，保持连接打开直到测试结束。
		var frame bytes.Buffer
		writeTestFLVEx(&frame, []testFrame{{ts: 0, video: true, keyframe: true}})
		_, _ = w.Write(frame.Bytes()[flvProxyHeaderSize+flvProxyPrevTagSizeLen:])
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		<-r.Context().Done()
	}))
	defer upstream.Close()

	downstream := &fakeResponseWriter{}
	proxy := &flvProxy{
		service:    testProxyService(upstream.URL),
		site:       "huya",
		tuning:     siteTuningFor("huya"),
		roomID:     "1001",
		httpClient: &http.Client{Transport: &http.Transport{DisableCompression: true}},
		client:     &flvProxyClient{writer: downstream, flusher: downstream, done: make(chan struct{})},
	}
	proxy.ctx, proxy.cancel = context.WithCancel(context.Background())
	defer proxy.cancel()
	go proxy.streamLoop()

	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		proxy.statsMu.Lock()
		malformed := proxy.malformedTags
		reconnects := proxy.reconnects
		lastError := proxy.lastError
		switches := proxy.candidateSwitches
		idle := proxy.idleTimeouts
		proxy.statsMu.Unlock()
		if malformed >= 1 && reconnects >= 1 && atomic.LoadInt64(&sessions) >= 2 {
			if lastError != "" {
				t.Fatalf("坏 tag 软重连后不应残留 lastError：%q（统计=%s）", lastError, proxy.statsJSON())
			}
			if switches != 0 || idle != 0 {
				t.Fatalf("坏 tag 不应触发换线/空闲重连：switches=%d idle=%d", switches, idle)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("4 秒内未完成坏 tag 软重连（统计=%s）", proxy.statsJSON())
}

// TestTruncatedFlvTagTriggersSoftReconnect：CDN 在一个 FLV tag 数据中途关连接是
// 直播会话的正常截断形态。真机房间 2 观测到 unexpected EOF 被当硬失败后 10s 无画面，
// 这里锁定它按干净 EOF 走软续拉，不计失败、不换线、不留 lastError。
func TestTruncatedFlvTagTriggersSoftReconnect(t *testing.T) {
	resetHuyaTuning()
	defer resetHuyaTuning()

	var sessions int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt64(&sessions, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		_, _ = w.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0, 0, 0, 9, 0, 0, 0, 0})
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		if n == 1 {
			// 声明 100 字节数据但只给 10 字节后关连接 → io.ErrUnexpectedEOF。
			_, _ = w.Write([]byte{byte(flvTagTypeVideo), 0, 0, 100, 0, 0, 0, 0, 0, 0, 0})
			_, _ = w.Write(make([]byte, 10))
			return
		}
		var frame bytes.Buffer
		writeTestFLVEx(&frame, []testFrame{{ts: 0, video: true, keyframe: true}})
		_, _ = w.Write(frame.Bytes()[flvProxyHeaderSize+flvProxyPrevTagSizeLen:])
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		<-r.Context().Done()
	}))
	defer upstream.Close()

	downstream := &fakeResponseWriter{}
	proxy := &flvProxy{
		service:    testProxyService(upstream.URL),
		site:       "huya",
		tuning:     siteTuningFor("huya"),
		roomID:     "1002",
		httpClient: &http.Client{Transport: &http.Transport{DisableCompression: true}},
		client:     &flvProxyClient{writer: downstream, flusher: downstream, done: make(chan struct{})},
	}
	proxy.ctx, proxy.cancel = context.WithCancel(context.Background())
	defer proxy.cancel()
	go proxy.streamLoop()

	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		proxy.statsMu.Lock()
		reconnects := proxy.reconnects
		lastError := proxy.lastError
		switches := proxy.candidateSwitches
		proxy.statsMu.Unlock()
		if reconnects >= 1 && atomic.LoadInt64(&sessions) >= 2 {
			if lastError != "" || switches != 0 {
				t.Fatalf("会话截断不应留下硬失败：lastError=%q switches=%d（统计=%s）",
					lastError, switches, proxy.statsJSON())
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("4 秒内未完成截断软重连（统计=%s）", proxy.statsJSON())
}

// TestProgressWatchdogRecoversFrozenSegment：覆盖「既不报错、也不吐数据」的冻结。
// 真机虎牙计划交接后就是这种形态：坏 tag/空闲读超时统计都为 0，但标签数完全不动，
// 最后只能靠 Kotlin 12s 硬恢复。看门狗必须在硬阈值之前强制软重连。
func TestProgressWatchdogRecoversFrozenSegment(t *testing.T) {
	resetHuyaTuning()
	defer resetHuyaTuning()
	oldStall := flvProxyProgressStallMs.Load()
	flvProxyProgressStallMs.Store(300)
	defer func() { flvProxyProgressStallMs.Store(oldStall) }()

	var sessions int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(&sessions, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		_, _ = w.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0, 0, 0, 9, 0, 0, 0, 0})
		var frame bytes.Buffer
		writeTestFLVEx(&frame, []testFrame{{ts: 0, video: true, keyframe: true}})
		_, _ = w.Write(frame.Bytes()[flvProxyHeaderSize+flvProxyPrevTagSizeLen:])
		if flusher, ok := w.(http.Flusher); ok {
			flusher.Flush()
		}
		// 只给一帧，然后保持连接打开但再也不吐数据（半开冻结）。
		<-r.Context().Done()
	}))
	defer upstream.Close()

	downstream := &fakeResponseWriter{}
	proxy := &flvProxy{
		service:    testProxyService(upstream.URL),
		site:       "huya",
		tuning:     siteTuningFor("huya"),
		roomID:     "1003",
		httpClient: &http.Client{Transport: &http.Transport{DisableCompression: true}},
		client:     &flvProxyClient{writer: downstream, flusher: downstream, done: make(chan struct{})},
	}
	proxy.ctx, proxy.cancel = context.WithCancel(context.Background())
	defer proxy.cancel()
	go proxy.streamLoop()

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		proxy.statsMu.Lock()
		idle := proxy.idleTimeouts
		lastError := proxy.lastError
		switches := proxy.candidateSwitches
		proxy.statsMu.Unlock()
		if idle >= 2 && atomic.LoadInt64(&sessions) >= 2 {
			if lastError != "" || switches != 0 {
				t.Fatalf("看门狗软重连不应留下硬失败：lastError=%q switches=%d", lastError, switches)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("5 秒内进度看门狗未触发软重连（统计=%s）", proxy.statsJSON())
}
