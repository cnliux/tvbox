package platformlive

// 覆盖本轮修复的传输层 / 计划内切换 / pacer 语义回归。
// 与既有 35 个用例互补：这里只测「新行为」，时间轴与去重断言仍由历史用例守卫。

import (
	"bufio"
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

func testProxyService(url string) *Service {
	return &Service{byID: map[string]Adapter{"huya": &fakeProxyAdapter{streamURL: url},
		"douyu": &fakeProxyAdapter{streamURL: url}}}
}

type initialTag struct {
	typ byte
	ts  int64
}

// readInitialTags 从「FLV 头 + 若干标签」里解析出标签类型与时间戳。
func readInitialTags(t *testing.T, payload []byte) []initialTag {
	t.Helper()
	if len(payload) < flvProxyHeaderSize+flvProxyPrevTagSizeLen {
		t.Fatalf("起步字节过短：%d", len(payload))
	}
	reader := bytes.NewReader(payload[flvProxyHeaderSize+flvProxyPrevTagSizeLen:])
	tags := make([]initialTag, 0, 4)
	for reader.Len() > 0 {
		header := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(reader, header); err != nil {
			t.Fatalf("起步字节里的标签头不完整：%v", err)
		}
		size := int(header[1])<<16 | int(header[2])<<8 | int(header[3])
		if _, err := io.ReadFull(reader, make([]byte, size+flvProxyPrevTagSizeLen)); err != nil {
			t.Fatalf("起步字节里的标签体不完整：%v", err)
		}
		ts := int64(header[4])<<16 | int64(header[5])<<8 | int64(header[6]) | int64(header[7])<<24
		tags = append(tags, initialTag{typ: header[0] & 0x1f, ts: ts})
	}
	return tags
}

// 验证：HEAD 只回头部、不注册客户端，也不占用写端。
func TestFlvProxyHeadDoesNotRegisterClient(t *testing.T) {
	proxy, err := newFlvProxy(testProxyService("http://127.0.0.1:1/live.flv"), "huya", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()

	client := &http.Client{Timeout: 3 * time.Second}
	response, err := client.Head(proxy.url)
	if err != nil {
		t.Fatalf("HEAD 请求失败：%v", err)
	}
	_ = response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("HEAD 应返回 200，实际 %d", response.StatusCode)
	}
	if got := response.Header.Get("Content-Type"); got != "video/x-flv" {
		t.Fatalf("HEAD 应带 FLV Content-Type，实际 %q", got)
	}
	if proxy.hasClient() {
		t.Fatal("HEAD 不应注册下游客户端")
	}
}

// 验证：第二个播放器连接接管写端，旧连接被释放（不会双写、不会永久挂住）。
func TestFlvProxyClientTakeover(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-flv")
		_, _ = w.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0, 0, 0, 9, 0, 0, 0, 0})
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		<-r.Context().Done()
	}))
	defer upstream.Close()

	proxy, err := newFlvProxy(testProxyService(upstream.URL), "huya", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()

	first, err := http.Get(proxy.url)
	if err != nil {
		t.Fatalf("第一个客户端连接失败：%v", err)
	}
	defer first.Body.Close()
	deadline := time.Now().Add(2 * time.Second)
	for !proxy.hasClient() && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}

	second, err := http.Get(proxy.url)
	if err != nil {
		t.Fatalf("第二个客户端连接失败：%v", err)
	}
	defer second.Body.Close()

	// 旧连接必须被结束，否则它会一直占着一条 HTTP 响应却收不到数据。
	released := make(chan error, 1)
	go func() {
		_, err := io.Copy(io.Discard, first.Body)
		released <- err
	}()
	select {
	case <-released:
	case <-time.After(3 * time.Second):
		t.Fatal("新连接接管后，旧连接没有被释放")
	}

	proxy.statsMu.Lock()
	takeovers := proxy.clientTakeovers
	proxy.statsMu.Unlock()
	if takeovers < 1 {
		t.Fatal("未记录客户端接管次数")
	}
	if !proxy.hasClient() {
		t.Fatal("接管后写端不应为空")
	}
}

// 验证：真实的上游配置标签被缓存并在新客户端起步字节里补发。
func TestFlvProxyReplaysRealConfigTags(t *testing.T) {
	proxy := newManualDouyuProxy("huya")
	// onMetaData(script) + AVC 序列头 + 普通音频帧 + 普通视频关键帧。
	scriptHeader := make([]byte, flvProxyTagHeaderSize)
	scriptHeader[0] = flvTagTypeScriptData
	scriptHeader[3] = 2
	if err := proxy.writeTag(scriptHeader, []byte{0x02, 0x00}, 500); err != nil {
		t.Fatalf("写 script 标签失败：%v", err)
	}
	seqHeader := make([]byte, flvProxyTagHeaderSize)
	seqHeader[0] = flvTagTypeVideo
	seqHeader[3] = 4
	if err := proxy.writeTag(seqHeader, []byte{0x17, 0x00, 0x01, 0x02}, 500); err != nil {
		t.Fatalf("写 AVC 序列头失败：%v", err)
	}
	audioHeader := make([]byte, flvProxyTagHeaderSize)
	audioHeader[0] = flvTagTypeAudio
	audioHeader[3] = 3
	if err := proxy.writeTag(audioHeader, []byte{0xA0, 0x01, 0x00}, 533); err != nil {
		t.Fatalf("写音频帧失败：%v", err)
	}
	// 让输出时间轴前进到 900，补发标签必须跟着重写到 900，
	// 不能带着写入时的 500/533 造成新客户端时间轴回退。
	proxy.commitTs(1, 900)
	tags := readInitialTags(t, proxy.initialClientPayload())
	if len(tags) != 2 || tags[0].typ != flvTagTypeScriptData || tags[1].typ != flvTagTypeVideo {
		t.Fatalf("起步字节应只补发 script + AVC 序列头，实际 %+v", tags)
	}
	for _, tag := range tags {
		if tag.ts != 900 {
			t.Fatalf("补发标签时间戳应为 900，实际 %d", tag.ts)
		}
	}
}

// 验证：FLV header flags 按实际观察到的轨道推导。
func TestFlvProxyHeaderFlagsFollowTracks(t *testing.T) {
	proxy := newManualDouyuProxy("huya")
	if got := proxy.currentHeaderFlags(); got != 0x05 {
		t.Fatalf("未知轨道时保守返回 0x05，实际 %#x", got)
	}
	proxy.commitTs(0, 10)
	if got := proxy.currentHeaderFlags(); got != 0x04 {
		t.Fatalf("纯音频应为 0x04，实际 %#x", got)
	}
	proxy.commitTs(1, 10)
	if got := proxy.currentHeaderFlags(); got != 0x05 {
		t.Fatalf("音视频都有应为 0x05，实际 %#x", got)
	}
}

// 验证：上游半开连接（只给 FLV 头后静默）由空闲读超时断开，
// 且作为软重连处理——不计硬失败、不换线路、不会永久阻塞。
func TestFlvProxyUpstreamIdleIsSoftReconnect(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-flv")
		_, _ = w.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0, 0, 0, 9, 0, 0, 0, 0})
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		<-r.Context().Done()
	}))
	defer upstream.Close()

	proxy := newManualDouyuProxy("huya")
	proxy.httpClient = &http.Client{Transport: &http.Transport{
		DisableCompression: true,
		DialContext:        dialWithIdleReadTimeout(200 * time.Millisecond),
	}}
	started := time.Now()
	leaseExpired, handover, err := proxy.pullSegment(
		LiveStreamCandidate{URL: upstream.URL, Protocol: "flv"}, nil, time.Time{}, nil)
	if err != nil {
		t.Fatalf("上游静默应软重连，不应报错：%v", err)
	}
	if leaseExpired || handover != nil {
		t.Fatal("上游静默不应被当成租约到期或计划内交接")
	}
	if elapsed := time.Since(started); elapsed > 3*time.Second {
		t.Fatalf("空闲读超时未生效，耗时 %s", elapsed)
	}
	proxy.statsMu.Lock()
	idle := proxy.idleTimeouts
	proxy.statsMu.Unlock()
	if idle < 1 {
		t.Fatal("未记录上游空闲超时次数")
	}
}

// 验证：pacer「首段预填」不会像旧实现那样把首帧延迟一个目标缓冲时长，
// 窗口内的帧按上游到达速度立即交给播放器（真机 bufferedMs 才能真正增加）。
func TestPacerPrefillDoesNotDelayFirstFrames(t *testing.T) {
	proxy := newManualDouyuProxy("huya")
	proxy.pacerEnabled = true
	proxy.pacerTargetMs.Store(1000)
	var payload bytes.Buffer
	writeTestFLVEx(&payload, []testFrame{
		{ts: 0, video: true, keyframe: true},
		{ts: 33, video: true},
		{ts: 66, video: true},
		{ts: 99, video: true},
	})
	started := time.Now()
	if err := proxy.consume(bufio.NewReader(bytes.NewReader(payload.Bytes()))); err != nil {
		t.Fatalf("consume 失败：%v", err)
	}
	if elapsed := time.Since(started); elapsed > 500*time.Millisecond {
		t.Fatalf("预填窗口内的首帧不应被延迟（旧实现会等满 1s），实际 %s", elapsed)
	}
	proxy.statsMu.Lock()
	waited := proxy.pacerWaitMs
	proxy.statsMu.Unlock()
	if waited > 0 {
		t.Fatalf("预填窗口内不应产生 pacer 等待，实际 %dms", waited)
	}
}

// 验证：运行时调整目标缓冲会写回每个在播代理，并触发基准重对齐。
func TestPacerTargetPropagatesToActiveProxies(t *testing.T) {
	proxy, err := newFlvProxy(testProxyService("http://127.0.0.1:1/live.flv"), "huya", "2002", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()
	defer SetPacerTargetBufferMs(5000)

	SetPacerTargetBufferMs(3000)
	if got := GetPacerTargetBufferMs(); got != 3000 {
		t.Fatalf("全局默认值应为 3000，实际 %d", got)
	}
	if got := proxy.pacerTargetMs.Load(); got != 3000 {
		t.Fatalf("在播代理应同步为 3000，实际 %d", got)
	}
	if !proxy.pacerRebase.Load() {
		t.Fatal("在播代理应收到基准重对齐通知")
	}
}

// 验证：计划内预建只对虎牙启用，斗鱼（租约）与通用档不受影响。
func TestPlannedHandoverOnlyForHuya(t *testing.T) {
	if siteTuningFor("huya").plannedRefreshLeadMs <= 0 {
		t.Fatal("虎牙应启用计划内预建双连接")
	}
	if got := siteTuningFor("douyu").plannedRefreshLeadMs; got != 0 {
		t.Fatalf("斗鱼不得借用虎牙预建策略，实际 %d", got)
	}
	if got := siteTuningFor("bilibili").plannedRefreshLeadMs; got != 0 {
		t.Fatalf("通用档不得借用虎牙预建策略，实际 %d", got)
	}
}

// 验证：预备连接就绪后，当前段只在成功写出一个关键帧之后交接。
func TestFlvProxyHandoverAtKeyframe(t *testing.T) {
	preparedUpstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-flv")
		writeTestFLVEx(w, []testFrame{
			{ts: 0, video: true, seqHeader: true},
			{ts: 0, video: true, keyframe: true},
		})
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		<-r.Context().Done()
	}))
	defer preparedUpstream.Close()

	primaryUpstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-flv")
		_, _ = w.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0, 0, 0, 9, 0, 0, 0, 0})
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		// 留出时间让 watcher 收到「预备连接已就绪」信号。
		time.Sleep(200 * time.Millisecond)
		var tail bytes.Buffer
		writeTestFLVEx(&tail, []testFrame{
			{ts: 0, video: true, keyframe: true},
			{ts: 33, video: true, keyframe: true},
		})
		_, _ = w.Write(tail.Bytes()[flvProxyHeaderSize+flvProxyPrevTagSizeLen:])
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		<-r.Context().Done()
	}))
	defer primaryUpstream.Close()

	proxy := newManualDouyuProxy("huya")
	prepared := proxy.prepareNextConnection(context.Background(), func() (LiveStreamCandidate, map[string]string, error) {
		return LiveStreamCandidate{URL: preparedUpstream.URL, Protocol: "flv"}, nil, nil
	})
	if prepared == nil {
		t.Fatal("预备连接未能建立")
	}
	if len(prepared.buffer) == 0 {
		t.Fatal("预备连接应缓冲到首个关键帧")
	}
	ready := make(chan *preparedStream, 1)
	ready <- prepared

	leaseExpired, handover, err := proxy.pullSegment(
		LiveStreamCandidate{URL: primaryUpstream.URL, Protocol: "flv"}, nil, time.Time{},
		&pullOptions{prepared: ready})
	if err != nil {
		t.Fatalf("计划内交接不应报错：%v", err)
	}
	if leaseExpired {
		t.Fatal("计划内交接不是租约到期")
	}
	if handover != prepared {
		t.Fatal("应返回已就绪的预备连接")
	}
	handover.take()
	handover.close()
}

// 验证 streamLoop 层的编排：预建连接就绪后确实发生计划内交接，
// 而不是只在 pullSegment 单函数里能返回。用极短的标称会话时长加速。
func TestStreamLoopUsesPlannedHandover(t *testing.T) {
	var sessions int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(&sessions, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		_, _ = w.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0, 0, 0, 9, 0, 0, 0, 0})
		flusher, _ := w.(http.Flusher)
		if flusher != nil {
			flusher.Flush()
		}
		ticker := time.NewTicker(30 * time.Millisecond)
		defer ticker.Stop()
		ts := int64(0)
		for {
			select {
			case <-r.Context().Done():
				return
			case <-ticker.C:
				var frame bytes.Buffer
				writeTestFLVEx(&frame, []testFrame{{ts: ts, video: true, keyframe: true}})
				_, _ = w.Write(frame.Bytes()[flvProxyHeaderSize+flvProxyPrevTagSizeLen:])
				if flusher != nil {
					flusher.Flush()
				}
				ts += 30
			}
		}
	}))
	defer upstream.Close()

	service := &Service{byID: map[string]Adapter{
		"huya": &fakeProxyAdapter{streamURL: upstream.URL},
	}}
	downstream := &fakeResponseWriter{}
	proxy := &flvProxy{
		service:    service,
		site:       "huya",
		tuning:     siteTuningFor("huya"),
		roomID:     "1001",
		httpClient: &http.Client{Transport: &http.Transport{DisableCompression: true}},
		client:     &flvProxyClient{writer: downstream, flusher: downstream, done: make(chan struct{})},
	}
	proxy.tuning.plannedRefreshLeadMs = 60
	proxy.tuning.nominalSessionMs = 200
	proxy.ctx, proxy.cancel = context.WithCancel(context.Background())
	defer proxy.cancel()
	go proxy.streamLoop()

	deadline := time.Now().Add(4 * time.Second)
	for time.Now().Before(deadline) {
		proxy.statsMu.Lock()
		handovers := proxy.plannedHandovers
		proxy.statsMu.Unlock()
		if handovers >= 1 {
			if got := atomic.LoadInt64(&sessions); got < 2 {
				t.Fatalf("计划内交接应建立第二条上游连接，实际会话数 %d", got)
			}
			// 让交接后的第二条连接真正跑一段：旧实现里 stopPrepare() 会级联
			// 取消预建请求，使 consume 立刻读到 context canceled / 空闲超时。
			time.Sleep(300 * time.Millisecond)
			proxy.statsMu.Lock()
			lastErr := proxy.lastError
			idle := proxy.idleTimeouts
			proxy.statsMu.Unlock()
			if lastErr != "" || idle > 0 {
				t.Fatalf("计划内交接不应制造断流：lastError=%q idleTimeouts=%d 统计=%s",
					lastErr, idle, proxy.statsJSON())
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("4 秒内未发生计划内交接（会话数=%d，统计=%s）",
		atomic.LoadInt64(&sessions), proxy.statsJSON())
}
