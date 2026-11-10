package platformlive

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// fakeProxyAdapter 只提供 Resolve，用于验证代理的缝流行为。
type fakeProxyAdapter struct {
	streamURL string
	headers   map[string]string
	resolves  int64
}

func (f *fakeProxyAdapter) SiteID() string                               { return "fake" }
func (f *fakeProxyAdapter) SiteName() string                             { return "测试平台" }
func (f *fakeProxyAdapter) Description() string                          { return "" }
func (f *fakeProxyAdapter) NormalizeRoomID(value string) (string, error) { return Text(value, ""), nil }
func (f *fakeProxyAdapter) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	return nil, nil, nil
}
func (f *fakeProxyAdapter) GetCategoryRooms(string, int64) (LiveRoomsPage, error) {
	return LiveRoomsPage{}, nil
}
func (f *fakeProxyAdapter) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	atomic.AddInt64(&f.resolves, 1)
	return &ResolvedLiveStream{
		RoomID:     roomInput,
		Candidates: []LiveStreamCandidate{{CDN: "测试线路", URL: f.streamURL, Protocol: "flv"}},
		Headers:    f.headers,
	}, nil
}

// writeTestFLV 输出一段“上游会话”：FLV 头 + 若干音视频标签。
func writeTestFLV(writer io.Writer, timestamps []int64) {
	_, _ = writer.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00})
	for _, ts := range timestamps {
		size := 3
		header := []byte{0x09, 0x00, 0x00, byte(size), byte(ts >> 16), byte(ts >> 8), byte(ts), byte(ts >> 24), 0, 0, 0}
		_, _ = writer.Write(header)
		_, _ = writer.Write([]byte{0x17, 0x01, 0x00})
		prev := uint32(flvProxyTagHeaderSize + size)
		_, _ = writer.Write([]byte{byte(prev >> 24), byte(prev >> 16), byte(prev >> 8), byte(prev)})
	}
}

func readTestFLVTags(reader io.Reader, limit int) ([]int64, error) {
	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, err
	}
	result := make([]int64, 0, limit)
	for len(result) < limit {
		tagHeader := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(reader, tagHeader); err != nil {
			return result, err
		}
		size := int(tagHeader[1])<<16 | int(tagHeader[2])<<8 | int(tagHeader[3])
		ts := int64(tagHeader[4])<<16 | int64(tagHeader[5])<<8 | int64(tagHeader[6]) | int64(tagHeader[7])<<24
		if _, err := io.ReadFull(reader, make([]byte, size+flvProxyPrevTagSizeLen)); err != nil {
			return result, err
		}
		result = append(result, ts)
	}
	return result, nil
}

// 验证：上游两段各自从 0 开始的 FLV，被代理缝成一条时间戳单调递增的连续流。
func TestFlvProxyStitchesSessions(t *testing.T) {
	var sessions int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(&sessions, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		w.WriteHeader(http.StatusOK)
		writeTestFLV(w, []int64{0, 33, 66})
	}))
	defer upstream.Close()

	adapter := &fakeProxyAdapter{streamURL: upstream.URL + flvProxyPath}
	service := &Service{byID: map[string]Adapter{"fake": adapter}}
	proxy, err := newFlvProxy(service, "fake", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, proxy.url, nil)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer response.Body.Close()

	tags, err := readTestFLVTags(response.Body, 6)
	if err != nil && len(tags) < 6 {
		t.Fatalf("读取代理输出失败：%v（状态=%d 类型=%s 已读到 %d 个标签，代理统计=%s）",
			err, response.StatusCode, response.Header.Get("Content-Type"), len(tags), proxy.statsJSON())
	}
	expected := []int64{0, 33, 66, 99, 132, 165}
	for index, want := range expected {
		if tags[index] != want {
			t.Fatalf("第 %d 个标签时间戳=%d，期望 %d（实际序列 %v）", index, tags[index], want, tags)
		}
	}
	if got := atomic.LoadInt64(&adapter.resolves); got < 2 {
		t.Fatalf("上游关流后应重新解析，实际 Resolve 次数=%d", got)
	}
	if got := atomic.LoadInt64(&sessions); got < 2 {
		t.Fatalf("上游应有至少两次拉流，实际=%d", got)
	}

	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(proxy.statsJSON()), &stats); err != nil {
		t.Fatalf("统计 JSON 解析失败：%v", err)
	}
	if reconnect, _ := stats["reconnects"].(float64); reconnect < 1 {
		t.Fatalf("统计未记录续流次数：%v", stats)
	}
}

// 验证：带平台前缀的房间主键（Kotlin 侧 room.id 形如 "huya:31297160"）
// 会被归一化成纯房间号，避免续流时 NormalizeRoomID 报“房间号格式无效”。
func TestFlvProxyStripsSitePrefix(t *testing.T) {
	service := &Service{byID: map[string]Adapter{"fake": &fakeProxyAdapter{}}}
	proxy, err := newFlvProxy(service, "fake", "fake:1001", 0, "http://127.0.0.1:1/live.flv", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()
	if proxy.roomID != "1001" {
		t.Fatalf("房间号应去掉平台前缀，实际 %q", proxy.roomID)
	}
}

// 验证时间戳顺延与回退判据。
func TestFlvProxySpliceTimestamps(t *testing.T) {
	proxy := &flvProxy{}
	if got := proxy.spliceBase(0); got != 0 {
		t.Fatalf("首段应沿用原始时间戳，实际 %d", got)
	}
	if !proxy.acceptTs(0) {
		t.Fatal("首帧应被接受")
	}
	base := proxy.spliceBase(0)
	if want := int64(flvProxySpliceStepMs); base != want {
		t.Fatalf("续段首帧应接在上一帧之后 %dms，实际 %d", want, base)
	}
	if !proxy.acceptTs(base) {
		t.Fatal("续段首帧应被接受")
	}
	if proxy.acceptTs(base - 10) {
		t.Fatal("时间戳回退的标签应被丢弃")
	}
	if !proxy.acceptTs(base) {
		t.Fatal("同一时刻的音视频标签应被接受")
	}
}

// ───────────────────── 关键帧解析与重连衔接（修复重复画面） ─────────────────────

type testFrame struct {
	ts        int64
	video     bool
	audio     bool
	keyframe  bool // 仅 video 有效
	seqHeader bool // 仅 video 有效：AVC 序列头（AVCPacketType=0）
}

// writeTestFLVEx 输出一段“上游会话”，可指定每个标签的类型、是否关键帧、是否序列头。
func writeTestFLVEx(writer io.Writer, frames []testFrame) {
	_, _ = writer.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00})
	for _, f := range frames {
		var tagType byte
		var data []byte
		switch {
		case f.video:
			tagType = flvTagTypeVideo
			frameType := byte(1)
			if !f.keyframe {
				frameType = 2
			}
			avcPacketType := byte(1)
			if f.seqHeader {
				avcPacketType = 0
			}
			data = []byte{(frameType << 4) | flvVideoCodecAVC, avcPacketType, 0, 0}
		case f.audio:
			tagType = flvTagTypeAudio
			data = []byte{0xA0, 0, 0}
		default:
			continue
		}
		size := len(data)
		header := []byte{
			tagType, byte(size >> 16), byte(size >> 8), byte(size),
			byte(f.ts >> 16), byte(f.ts >> 8), byte(f.ts), byte(f.ts >> 24),
			0, 0, 0,
		}
		_, _ = writer.Write(header)
		_, _ = writer.Write(data)
		prev := uint32(flvProxyTagHeaderSize + size)
		_, _ = writer.Write([]byte{byte(prev >> 24), byte(prev >> 16), byte(prev >> 8), byte(prev)})
	}
}

type frameInfo struct {
	ts        int64
	video     bool
	audio     bool
	keyframe  bool
}

// readTestFLVTagsEx 读取代理输出，返回每个标签的类型与时间戳（用于精确断言）。
func readTestFLVTagsEx(reader io.Reader, limit int) ([]frameInfo, error) {
	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, err
	}
	out := make([]frameInfo, 0, limit)
	for len(out) < limit {
		th := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(reader, th); err != nil {
			return out, err
		}
		size := int(th[1])<<16 | int(th[2])<<8 | int(th[3])
		ts := int64(th[4])<<16 | int64(th[5])<<8 | int64(th[6]) | int64(th[7])<<24
		data := make([]byte, size)
		if _, err := io.ReadFull(reader, data); err != nil {
			return out, err
		}
		if _, err := io.ReadFull(reader, make([]byte, flvProxyPrevTagSizeLen)); err != nil {
			return out, err
		}
		tagType := th[0] & 0x1f
		fi := frameInfo{ts: ts}
		switch tagType {
		case flvTagTypeVideo:
			fi.video = true
			fi.keyframe = isVideoKeyframe(data)
		case flvTagTypeAudio:
			fi.audio = true
		}
		out = append(out, fi)
	}
	return out, nil
}

// 验证：上游关流后重连，新流时间戳从 0 重新开始且以非关键帧/序列头开头时，
// 代理输出严格单调递增、续段首帧严格接续上一段末帧（无回退/重复画面），
// 且前导非关键视频帧被丢弃、AVC 序列头与关键帧被保留。
func TestFlvProxyReconnectNoRollback(t *testing.T) {
	var reqCount int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt64(&reqCount, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		w.WriteHeader(http.StatusOK)
		switch {
		case n == 1:
			writeTestFLVEx(w, []testFrame{
				{ts: 0, video: true, keyframe: true},
				{ts: 33, video: true, keyframe: true},
				{ts: 66, video: true, keyframe: true},
			})
		default:
			writeTestFLVEx(w, []testFrame{
				{ts: 0, video: true, keyframe: false}, // 重连瞬间前导非关键帧：应丢弃
				{ts: 0, video: true, keyframe: true, seqHeader: true}, // AVC 序列头：应保留
				{ts: 0, audio: true},
				{ts: 33, video: true, keyframe: true}, // 首个可解码关键帧：应转发并解除等待
				{ts: 66, video: true, keyframe: false},
				{ts: 66, audio: true},
			})
		}
	}))
	defer upstream.Close()

	adapter := &fakeProxyAdapter{streamURL: upstream.URL + flvProxyPath}
	service := &Service{byID: map[string]Adapter{"fake": adapter}}
	proxy, err := newFlvProxy(service, "fake", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, proxy.url, nil)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer response.Body.Close()

	frames, err := readTestFLVTagsEx(response.Body, 8) // 上段 3 + 续段转发 5（丢弃 1）
	if err != nil && len(frames) < 8 {
		t.Fatalf("读取代理输出失败：%v（已读 %d，统计=%s）", err, len(frames), proxy.statsJSON())
	}

	// 1) 全局非递减（允许同刻音视频并存）。
	for i := 1; i < len(frames); i++ {
		if frames[i].ts < frames[i-1].ts {
			t.Fatalf("时间戳回退：第 %d 帧 %d < 第 %d 帧 %d", i, frames[i].ts, i-1, frames[i-1].ts)
		}
	}

	// 2) 续段首帧严格接续上一段末帧：无回退、无重复画面。
	seg1 := frames[:3]
	seg2 := frames[3:]
	seg1Max := seg1[len(seg1)-1].ts
	seg2Min := seg2[0].ts
	if seg2Min <= seg1Max {
		t.Fatalf("续段首帧 %d 未严格接续上一段末帧 %d（出现回退/重复画面）", seg2Min, seg1Max)
	}
	for _, f := range seg2 {
		if f.ts <= seg1Max {
			t.Fatalf("续段存在回退/重复帧 ts=%d（上一段末帧=%d）", f.ts, seg1Max)
		}
	}

	// 3) 关键帧对齐：续段视频帧应为 3（序列头 + 关键帧 + 非关键），前导非关键帧被丢弃。
	var seg2Video int
	for _, f := range seg2 {
		if f.video {
			seg2Video++
		}
	}
	if seg2Video != 3 {
		t.Fatalf("续段视频帧数=%d，期望 3（前导非关键帧应被丢弃）", seg2Video)
	}

	if got := atomic.LoadInt64(&reqCount); got < 2 {
		t.Fatalf("上游关流后应重新拉流，实际请求次数=%d", got)
	}
	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(proxy.statsJSON()), &stats); err == nil {
		if reconnect, _ := stats["reconnects"].(float64); reconnect < 1 {
			t.Fatalf("统计未记录续流次数：%v", stats)
		}
	}
}

// 验证：首段（无连续性）即使以非关键帧开头也应一并转发，不应等待关键帧。
func TestFlvProxyFirstSegmentForwardsLeadingNonKeyframe(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-flv")
		w.WriteHeader(http.StatusOK)
		writeTestFLVEx(w, []testFrame{
			{ts: 0, video: true, keyframe: false},
			{ts: 33, video: true, keyframe: true},
		})
	}))
	defer upstream.Close()
	adapter := &fakeProxyAdapter{streamURL: upstream.URL + flvProxyPath}
	service := &Service{byID: map[string]Adapter{"fake": adapter}}
	proxy, err := newFlvProxy(service, "fake", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, proxy.url, nil)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer response.Body.Close()

	frames, err := readTestFLVTagsEx(response.Body, 2)
	if err != nil || len(frames) < 2 {
		t.Fatalf("读取代理输出失败：%v（已读 %d）", err, len(frames))
	}
	var video int
	for _, f := range frames {
		if f.video {
			video++
		}
	}
	if video != 2 {
		t.Fatalf("首段应将前导非关键帧与关键帧一并转发，实际视频帧数=%d", video)
	}
}

// 验证关键帧 / 序列头解析辅助函数。
func TestFlvProxyKeyframeHelpers(t *testing.T) {
	// AVC 序列头：codec=7，AVCPacketType=0
	if !isAvcSequenceHeader([]byte{(1 << 4) | flvVideoCodecAVC, 0, 0}) {
		t.Fatal("AVC 序列头未识别")
	}
	if isVideoKeyframe([]byte{(1 << 4) | flvVideoCodecAVC, 0, 0}) {
		t.Fatal("序列头不应判为关键帧")
	}
	// AVC 关键帧：codec=7，AVCPacketType=1，frameType=1
	if !isVideoKeyframe([]byte{(1 << 4) | flvVideoCodecAVC, 1, 0}) {
		t.Fatal("AVC 关键帧未识别")
	}
	// AVC 非关键帧：frameType=2
	if isVideoKeyframe([]byte{(2 << 4) | flvVideoCodecAVC, 1, 0}) {
		t.Fatal("AVC 非关键帧不应判为关键帧")
	}
	// 过短数据应安全返回 false
	if isVideoKeyframe([]byte{0x17}) || isAvcSequenceHeader([]byte{0x17}) {
		t.Fatal("过短数据应返回 false")
	}
}

