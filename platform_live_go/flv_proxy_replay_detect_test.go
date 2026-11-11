package platformlive

import (
	"context"
	"fmt"
	"hash/fnv"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"sync/atomic"
	"testing"
	"time"
)

// 虎牙续流「重复画面」端到端检测器。
//
// 背景：虎牙 CDN 约 2 分钟关流后，代理重新解析拿新签名续拉。CDN 常会带回
// 上一段末尾的缓冲（约 0~2 秒），且新流时间戳从 0 重新开始。代理输出的时间戳
// 被 spliceBase 强制顺延、严格单调，因此**用时间戳看不出重复**；必须按 FLV
// video tag 的帧内容指纹，找出同一批编码帧第二次出现的连续区间。
//
// 运行：
//
//	go test -count=1 -v -run TestFlvProxyReplayDetection ./        # 只出检测报告
//	TVBOX_REPLAY_ASSERT=1 go test -count=1 -run TestFlvProxyReplayDetection ./  # 作为回归守卫
//
// 场景：
//   - 段 1：3 秒正常直播（90 帧，每帧 payload 唯一，每 30 帧一个关键帧）；
//   - 上游正常 EOF（模拟 CDN 关流），代理重新解析续拉；
//   - 段 2：新流时间戳从 0 开始，**先回放段 1 最后 45 帧（1.5 秒）**，再给出 30 帧新内容；
//   - 断言/报告：输出流里是否出现「同一帧第二次播放」以及重复持续了多少毫秒。

func replayFLVHeader() []byte {
	return []byte{'F', 'L', 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00}
}

func replayTagBytes(tagType byte, ts int64, payload []byte) []byte {
	header := make([]byte, flvProxyTagHeaderSize)
	header[0] = tagType
	header[1] = byte(len(payload) >> 16)
	header[2] = byte(len(payload) >> 8)
	header[3] = byte(len(payload))
	header[4] = byte(ts >> 16)
	header[5] = byte(ts >> 8)
	header[6] = byte(ts)
	header[7] = byte(ts >> 24)
	prev := uint32(flvProxyTagHeaderSize + len(payload))
	out := make([]byte, 0, flvProxyTagHeaderSize+len(payload)+flvProxyPrevTagSizeLen)
	out = append(out, header...)
	out = append(out, payload...)
	out = append(out, byte(prev>>24), byte(prev>>16), byte(prev>>8), byte(prev))
	return out
}

// replayVideoFrame 生成 payload 唯一的 AVC NALU 帧；frameType=1 为关键帧。
func replayVideoFrame(id int, keyframe bool) []byte {
	first := byte(0x27) // frameType=2(P帧) + codecID=7(AVC)
	if keyframe {
		first = 0x17 // frameType=1(关键帧) + codecID=7(AVC)
	}
	payload := make([]byte, 24)
	payload[0] = first
	payload[1] = 1 // AVCPacketType = NALU
	payload[5] = byte(id)
	payload[6] = byte(id >> 8)
	payload[7] = byte(id >> 16)
	for i := 8; i < len(payload); i++ {
		payload[i] = byte(id*31 + i*7)
	}
	return payload
}

// replayAVCSequenceHeader 是与两段完全相同的 SPS/PPS（重连后 CDN 会重发）。
func replayAVCSequenceHeader() []byte {
	return []byte{0x17, 0x00, 0x00, 0x00, 0x00, 0x67, 0x42, 0x00, 0x1e, 0x68, 0xce, 0x3c, 0x80}
}

func replayFingerprint(data []byte) uint64 {
	h := fnv.New64a()
	_, _ = h.Write(data)
	return h.Sum64()
}

type replayOutTag struct {
	tagType byte
	ts      int64
	fp      uint64
	isSeq   bool
}

type replayInterval struct {
	startIdx   int
	endIdx     int
	frames     int
	durationMs int64
}

// analyzeReplay 找「同一帧指纹第二次出现」的连续区间。AVC 序列头重发是解码必需，
// 不计入重复画面；只统计 video NALU 帧。
func analyzeReplay(tags []replayOutTag, mergeGap int) ([]replayInterval, int, int64) {
	seen := map[uint64]bool{}
	duplicateIdx := make([]int, 0)
	for index, tag := range tags {
		if tag.tagType != flvTagTypeVideo || tag.isSeq {
			continue
		}
		if seen[tag.fp] {
			duplicateIdx = append(duplicateIdx, index)
		} else {
			seen[tag.fp] = true
		}
	}
	if len(duplicateIdx) == 0 {
		return nil, 0, 0
	}
	intervals := make([]replayInterval, 0)
	start, end := duplicateIdx[0], duplicateIdx[0]
	flush := func() {
		intervals = append(intervals, replayInterval{
			startIdx:   start,
			endIdx:     end,
			frames:     end - start + 1,
			durationMs: tags[end].ts - tags[start].ts,
		})
	}
	for _, index := range duplicateIdx[1:] {
		if index-end <= mergeGap+1 {
			end = index
		} else {
			flush()
			start, end = index, index
		}
	}
	flush()
	maxDuration := int64(0)
	for _, interval := range intervals {
		if interval.durationMs > maxDuration {
			maxDuration = interval.durationMs
		}
	}
	return intervals, len(duplicateIdx), maxDuration
}

func TestFlvProxyReplayDetection(t *testing.T) {
	const (
		frameCount    = 90
		replayFrames  = 45 // 1.5 秒回放（30fps × 33ms）
		newFrames     = 30
		frameInterval = int64(33)
	)

	segment1 := append([]byte{}, replayFLVHeader()...)
	segment1 = append(segment1, replayTagBytes(flvTagTypeVideo, 0, replayAVCSequenceHeader())...)
	for i := 0; i < frameCount; i++ {
		segment1 = append(segment1, replayTagBytes(flvTagTypeVideo, int64(i)*frameInterval,
			replayVideoFrame(i, i%30 == 0))...)
	}

	// 段 2：时间戳从 0 重新开始；前 45 帧逐字节回放段 1 末尾 45 帧，再接 30 帧新内容。
	segment2 := append([]byte{}, replayFLVHeader()...)
	segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, 0, replayAVCSequenceHeader())...)
	for j := 0; j < replayFrames; j++ {
		id := frameCount - replayFrames + j
		segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, int64(j)*frameInterval,
			replayVideoFrame(id, id%30 == 0))...)
	}
	for j := 0; j < newFrames; j++ {
		id := 200 + j
		segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, int64(replayFrames+j)*frameInterval,
			replayVideoFrame(id, id%30 == 0))...)
	}

	var sessions int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		session := atomic.AddInt64(&sessions, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		w.WriteHeader(http.StatusOK)
		switch session {
		case 1:
			_, _ = w.Write(segment1)
		case 2:
			_, _ = w.Write(segment2)
		default:
			// 后续重连只给 FLV 头并保持连接，避免测试期间无限刷帧干扰统计。
			_, _ = w.Write(replayFLVHeader())
			if flusher, ok := w.(http.Flusher); ok {
				flusher.Flush()
			}
			<-r.Context().Done()
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

	client := &http.Client{Timeout: 5 * time.Second}
	request, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, proxy.url, nil)
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer response.Body.Close()

	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(response.Body, header); err != nil {
		t.Fatalf("读取代理输出 FLV 头失败：%v", err)
	}

	tags := make([]replayOutTag, 0, 256)
	for {
		tagHeader := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(response.Body, tagHeader); err != nil {
			break // 客户端超时 / 连接关闭，输出已录完
		}
		size := int(tagHeader[1])<<16 | int(tagHeader[2])<<8 | int(tagHeader[3])
		data := make([]byte, size)
		if _, err := io.ReadFull(response.Body, data); err != nil {
			break
		}
		if _, err := io.ReadFull(response.Body, make([]byte, flvProxyPrevTagSizeLen)); err != nil {
			break
		}
		ts := int64(tagHeader[4])<<16 | int64(tagHeader[5])<<8 | int64(tagHeader[6]) | int64(tagHeader[7])<<24
		tags = append(tags, replayOutTag{
			tagType: tagHeader[0] & 0x1f,
			ts:      ts,
			fp:      replayFingerprint(data),
			isSeq:   tagHeader[0]&0x1f == flvTagTypeVideo && isAvcSequenceHeader(data),
		})
	}

	rollback := 0
	videoFrames := 0
	for index, tag := range tags {
		if index > 0 && tag.ts < tags[index-1].ts {
			rollback++
		}
		if tag.tagType == flvTagTypeVideo && !tag.isSeq {
			videoFrames++
		}
	}
	intervals, duplicates, maxDurationMs := analyzeReplay(tags, 2)

	fmt.Println("──────── 虎牙续流重复画面检测报告 ────────")
	fmt.Printf("上游会话数            : %d（1=首段, 2=重连带回放）\n", atomic.LoadInt64(&sessions))
	fmt.Printf("代理输出 tag 数       : %d（其中视频帧 %d）\n", len(tags), videoFrames)
	fmt.Printf("输出时间戳回退次数    : %d（0 表示时间轴单调，符合预期）\n", rollback)
	fmt.Printf("重复画面帧数          : %d\n", duplicates)
	fmt.Printf("连续重复区间数        : %d\n", len(intervals))
	for _, interval := range intervals {
		fmt.Printf("  区间: 输出帧 %d~%d, 共 %d 帧, 持续约 %d ms (%.2f s)\n",
			interval.startIdx, interval.endIdx, interval.frames, interval.durationMs,
			float64(interval.durationMs)/1000)
	}
	fmt.Printf("最长重复时长          : %d ms (%.2f s)\n", maxDurationMs, float64(maxDurationMs)/1000)
	fmt.Printf("代理统计              : %s\n", proxy.statsJSON())
	fmt.Println("──────────────────────────────────────────")

	if os.Getenv("TVBOX_REPLAY_ASSERT") != "" {
		if rollback != 0 {
			t.Errorf("输出时间戳出现 %d 次回退", rollback)
		}
		if duplicates != 0 {
			t.Errorf("检测到 %d 个重复画面帧（最长 %.2f 秒），重连回放未被丢弃",
				duplicates, float64(maxDurationMs)/1000)
		}
	}
}

// TestFlvProxyReplayAfterFreshKeyframe 复现真实「虎牙·一起看」CDN 续流形态：
// 新流先给一个全新的关键帧，再跟上 6 秒旧缓冲，最后才是真正的新内容。
// 旧实现一见到新关键帧就解除去重，整段旧缓冲会被原样转发（真机实测
// 153 帧 / 6.08 秒重复）。修复后必须做到：0 重复帧、时间轴无回退、无空洞。
func TestFlvProxyReplayAfterFreshKeyframe(t *testing.T) {
	const (
		frameCount    = 240
		replayFrames  = 180 // 6 秒 @30fps
		newFrames     = 60
		frameInterval = int64(33)
	)

	segment1 := append([]byte{}, replayFLVHeader()...)
	segment1 = append(segment1, replayTagBytes(flvTagTypeVideo, 0, replayAVCSequenceHeader())...)
	for i := 0; i < frameCount; i++ {
		segment1 = append(segment1, replayTagBytes(flvTagTypeVideo, int64(i)*frameInterval,
			replayVideoFrame(i, i%30 == 0))...)
	}

	segment2 := append([]byte{}, replayFLVHeader()...)
	segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, 0, replayAVCSequenceHeader())...)
	// 1) 全新关键帧（未转发过）会误导旧实现提前解除去重。
	segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, 0, replayVideoFrame(900, true))...)
	// 2) 6 秒旧缓冲：逐字节回放段 1 末尾。
	for j := 0; j < replayFrames; j++ {
		id := frameCount - replayFrames + j
		segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, int64(j+1)*frameInterval,
			replayVideoFrame(id, id%30 == 0))...)
	}
	// 3) 真正的新内容。
	for j := 0; j < newFrames; j++ {
		id := 1000 + j
		segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, int64(replayFrames+j+1)*frameInterval,
			replayVideoFrame(id, id%30 == 0))...)
	}

	var sessions int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		session := atomic.AddInt64(&sessions, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		w.WriteHeader(http.StatusOK)
		switch session {
		case 1:
			_, _ = w.Write(segment1)
		case 2:
			_, _ = w.Write(segment2)
		default:
			_, _ = w.Write(replayFLVHeader())
			if flusher, ok := w.(http.Flusher); ok {
				flusher.Flush()
			}
			<-r.Context().Done()
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

	client := &http.Client{Timeout: 5 * time.Second}
	request, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, proxy.url, nil)
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer response.Body.Close()

	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(response.Body, header); err != nil {
		t.Fatalf("读取代理输出 FLV 头失败：%v", err)
	}

	tags := make([]replayOutTag, 0, 512)
	for {
		tagHeader := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(response.Body, tagHeader); err != nil {
			break
		}
		size := int(tagHeader[1])<<16 | int(tagHeader[2])<<8 | int(tagHeader[3])
		data := make([]byte, size)
		if _, err := io.ReadFull(response.Body, data); err != nil {
			break
		}
		if _, err := io.ReadFull(response.Body, make([]byte, flvProxyPrevTagSizeLen)); err != nil {
			break
		}
		ts := int64(tagHeader[4])<<16 | int64(tagHeader[5])<<8 | int64(tagHeader[6]) | int64(tagHeader[7])<<24
		tagType := tagHeader[0] & 0x1f
		tags = append(tags, replayOutTag{
			tagType: tagType,
			ts:      ts,
			fp:      replayFingerprint(data),
			isSeq:   tagType == flvTagTypeVideo && isAvcSequenceHeader(data),
		})
	}

	rollback := 0
	maxGap := int64(0)
	for index, tag := range tags {
		if index == 0 {
			continue
		}
		gap := tag.ts - tags[index-1].ts
		if gap < 0 {
			rollback++
		}
		if gap > maxGap {
			maxGap = gap
		}
	}
	intervals, duplicates, maxDurationMs := analyzeReplay(tags, 2)

	fmt.Println("──────── 真实回放形态回归（新关键帧 + 6 秒旧缓冲）────────")
	fmt.Printf("代理输出 tag 数       : %d\n", len(tags))
	fmt.Printf("输出时间戳回退次数    : %d\n", rollback)
	fmt.Printf("相邻帧最大时间戳间隔  : %d ms（回放 6 秒若未补偿会出现约 6000ms 空洞）\n", maxGap)
	fmt.Printf("重复画面帧数          : %d\n", duplicates)
	fmt.Printf("连续重复区间数        : %d / 最长 %d ms\n", len(intervals), maxDurationMs)
	fmt.Printf("代理统计              : %s\n", proxy.statsJSON())
	fmt.Println("────────────────────────────────────────────────────────")

	if len(tags) < frameCount {
		t.Fatalf("输出帧过少：%d（应至少包含首段 %d 帧）", len(tags), frameCount)
	}
	if rollback != 0 {
		t.Fatalf("输出时间戳出现 %d 次回退", rollback)
	}
	if duplicates != 0 {
		t.Fatalf("新关键帧之后的旧缓冲未被丢弃：%d 个重复帧（最长 %d ms）", duplicates, maxDurationMs)
	}
	if maxGap > 1000 {
		t.Fatalf("丢弃回放后输出时间轴出现 %d ms 空洞，播放器会卡住", maxGap)
	}
}

// TestFlvProxyReplayMidSession 覆盖第三轮真实复核发现的形态：续段已经稳定输出
// 上百帧「全新」帧之后，CDN 才在会话中途零散重发一段旧缓冲。
// 若去重只在续段开头保护一小段（旧实现），这段回放会被原样转发。
func TestFlvProxyReplayMidSession(t *testing.T) {
	const (
		frameCount    = 120
		freshFrames   = 150
		replayFrames  = 60
		newFrames     = 30
		frameInterval = int64(33)
	)

	segment1 := append([]byte{}, replayFLVHeader()...)
	segment1 = append(segment1, replayTagBytes(flvTagTypeVideo, 0, replayAVCSequenceHeader())...)
	for i := 0; i < frameCount; i++ {
		segment1 = append(segment1, replayTagBytes(flvTagTypeVideo, int64(i)*frameInterval,
			replayVideoFrame(i, i%30 == 0))...)
	}

	// 续段：先给大量全新帧，再在会话中途插一段旧缓冲，最后才是真正的新内容。
	segment2 := append([]byte{}, replayFLVHeader()...)
	segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, 0, replayAVCSequenceHeader())...)
	tsIndex := int64(0)
	for j := 0; j < freshFrames; j++ {
		segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, tsIndex*frameInterval,
			replayVideoFrame(1000+j, j%30 == 0))...)
		tsIndex++
	}
	for j := 0; j < replayFrames; j++ {
		id := frameCount - replayFrames + j
		segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, tsIndex*frameInterval,
			replayVideoFrame(id, id%30 == 0))...)
		tsIndex++
	}
	for j := 0; j < newFrames; j++ {
		segment2 = append(segment2, replayTagBytes(flvTagTypeVideo, tsIndex*frameInterval,
			replayVideoFrame(2000+j, j%30 == 0))...)
		tsIndex++
	}

	var sessions int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		session := atomic.AddInt64(&sessions, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		w.WriteHeader(http.StatusOK)
		switch session {
		case 1:
			_, _ = w.Write(segment1)
		case 2:
			_, _ = w.Write(segment2)
		default:
			_, _ = w.Write(replayFLVHeader())
			if flusher, ok := w.(http.Flusher); ok {
				flusher.Flush()
			}
			<-r.Context().Done()
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

	client := &http.Client{Timeout: 5 * time.Second}
	request, _ := http.NewRequestWithContext(context.Background(), http.MethodGet, proxy.url, nil)
	response, err := client.Do(request)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer response.Body.Close()

	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(response.Body, header); err != nil {
		t.Fatalf("读取代理输出 FLV 头失败：%v", err)
	}

	tags := make([]replayOutTag, 0, 512)
	for {
		tagHeader := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(response.Body, tagHeader); err != nil {
			break
		}
		size := int(tagHeader[1])<<16 | int(tagHeader[2])<<8 | int(tagHeader[3])
		data := make([]byte, size)
		if _, err := io.ReadFull(response.Body, data); err != nil {
			break
		}
		if _, err := io.ReadFull(response.Body, make([]byte, flvProxyPrevTagSizeLen)); err != nil {
			break
		}
		ts := int64(tagHeader[4])<<16 | int64(tagHeader[5])<<8 | int64(tagHeader[6]) | int64(tagHeader[7])<<24
		tagType := tagHeader[0] & 0x1f
		tags = append(tags, replayOutTag{
			tagType: tagType,
			ts:      ts,
			fp:      replayFingerprint(data),
			isSeq:   tagType == flvTagTypeVideo && isAvcSequenceHeader(data),
		})
	}

	rollback := 0
	for i := 1; i < len(tags); i++ {
		if tags[i].ts < tags[i-1].ts {
			rollback++
		}
	}
	_, duplicates, maxDurationMs := analyzeReplay(tags, 2)
	fmt.Printf("──────── 会话中途回放回归 ──────── 输出 %d tag / 重复帧 %d / 最长 %d ms / 回退 %d\n",
		len(tags), duplicates, maxDurationMs, rollback)
	if rollback != 0 {
		t.Fatalf("输出时间戳出现 %d 次回退", rollback)
	}
	if duplicates != 0 {
		t.Fatalf("会话中途重发的旧缓冲未被丢弃：%d 个重复帧（最长 %d ms）", duplicates, maxDurationMs)
	}
	if len(tags) < frameCount+freshFrames {
		t.Fatalf("输出帧过少：%d（应至少含首段 %d + 续段全新 %d 帧）", len(tags), frameCount, freshFrames)
	}
}
