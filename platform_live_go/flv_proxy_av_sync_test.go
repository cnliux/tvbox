package platformlive

import (
	"bufio"
	"bytes"
	"testing"
)

// TestHuyaContinuationKeepsAudioVideoSameTimestamp：
// 虎牙归零续段的旧实现用「两轨最大时间戳 + 1」做全局钳制，音频先写 1034ms 后，
// 同一源时间戳的视频关键帧会被抬到 1035ms。真机上这种 1ms 级偏移会在密集
// 交错帧里被反复放大，最终表现为画面渲染停滞 / 音画不同步。
// 修复后只在本轨内钳制，同刻音视频必须保持同刻。
func TestHuyaContinuationKeepsAudioVideoSameTimestamp(t *testing.T) {
	proxy := newManualDouyuProxy("huya")
	proxy.pacerEnabled = false
	proxy.pacerTargetMs.Store(0)
	// 模拟上一段已交付到 1000ms（两轨相同）。
	proxy.haveOut[0], proxy.haveOut[1] = true, true
	proxy.lastOutTs[0], proxy.lastOutTs[1] = 1000, 1000

	audioPayload := []byte{0xA0, 1, 0, 1, 1, 1, 1}
	videoPayload := []byte{0x17, 1, 0, 0, 2, 2, 2, 2}
	var flv bytes.Buffer
	flv.Write(replayFLVHeader())
	flv.Write(replayTagBytes(flvTagTypeVideo, 0, replayAVCSequenceHeader()))
	// 首帧同源时间戳：修复后两轨输出必须完全相同。
	flv.Write(replayTagBytes(flvTagTypeAudio, 0, audioPayload))
	flv.Write(replayTagBytes(flvTagTypeVideo, 0, videoPayload))
	flv.Write(replayTagBytes(flvTagTypeAudio, 21, []byte{0xA0, 1, 0, 3, 3, 3, 3}))
	flv.Write(replayTagBytes(flvTagTypeVideo, 33, []byte{0x17, 1, 0, 0, 4, 4, 4, 4}))

	if err := proxy.consume(bufio.NewReader(bytes.NewReader(flv.Bytes()))); err != nil {
		t.Fatalf("consume: %v", err)
	}
	downstream := proxy.client.writer.(*fakeResponseWriter)
	tags := parseContinuityTags(t, downstream.buf.Bytes())
	var firstAudioTs, firstVideoTs int64 = -1, -1
	for _, tag := range tags {
		if tag.tagType == flvTagTypeVideo && isAvcSequenceHeader(tag.data) {
			continue
		}
		if tag.tagType == flvTagTypeAudio && firstAudioTs < 0 {
			firstAudioTs = tag.ts
		}
		if tag.tagType == flvTagTypeVideo && firstVideoTs < 0 {
			firstVideoTs = tag.ts
		}
	}
	if firstAudioTs < 0 || firstVideoTs < 0 {
		t.Fatalf("输出缺少音视频帧：audio=%d video=%d", firstAudioTs, firstVideoTs)
	}
	// 续段开头会丢弃视频关键帧之前的音频，因此这里比较的是「关键帧之后的音频」
	// 与视频的偏移；应只保留源帧本身的间隔（21ms），不得被全局钳制放大。
	if delta := firstAudioTs - firstVideoTs; delta < 0 || delta > 50 {
		t.Fatalf("同刻源帧被全局钳制错开：audio=%d video=%d delta=%d", firstAudioTs, firstVideoTs, delta)
	}
	// 续段黑窗期的音频会被主动丢弃，视频不应被当成回放丢掉。
	if proxy.videoDroppedReplay != 0 {
		t.Fatalf("全新视频内容不应被误判回放，丢弃 %d 帧", proxy.videoDroppedReplay)
	}
}

// TestRepeatedAudioFramesAreNotReplay：静音 AAC 帧可能逐字节相同。
// 旧版单帧指纹命中即丢，会删掉正常音频并从输出时间轴扣掉这段时长，
// 直接造成丢帧和音画不同步。连续 3 帧命中才确认是 CDN 回放。
func TestRepeatedAudioFramesAreNotReplay(t *testing.T) {
	proxy := newManualDouyuProxy("huya")
	proxy.pacerEnabled = false
	proxy.pacerTargetMs.Store(0)
	// 用一帧全新关键帧解除续段开头的严格回放丢弃，保留 guardActive 验证阈值判定。
	proxy.haveOut[1] = true
	proxy.lastOutTs[1] = 1000

	payload := []byte{0xA0, 1, 0, 9, 9, 9, 9}
	proxy.rememberFrame(flvFrameFingerprint(flvTagTypeAudio, payload), 1000, 0)
	var flv bytes.Buffer
	flv.Write(replayFLVHeader())
	flv.Write(replayTagBytes(flvTagTypeVideo, 0, []byte{0x17, 1, 0, 0, 8, 8, 8, 8}))
	for i := 0; i < 6; i++ {
		flv.Write(replayTagBytes(flvTagTypeAudio, int64((i+1)*21), payload))
	}
	if err := proxy.consume(bufio.NewReader(bytes.NewReader(flv.Bytes()))); err != nil {
		t.Fatalf("consume: %v", err)
	}
	downstream := proxy.client.writer.(*fakeResponseWriter)
	tags := parseContinuityTags(t, downstream.buf.Bytes())
	audioTags := 0
	for _, tag := range tags {
		if tag.tagType == flvTagTypeAudio {
			audioTags++
		}
	}
	if audioTags != 6 {
		t.Fatalf("6 个正常重复音频帧应全部转发，实际 %d（audioDroppedReplay=%d）",
			audioTags, proxy.audioDroppedReplay)
	}
	if proxy.audioDroppedReplay != 0 {
		t.Fatalf("正常重复帧不应计入回放丢弃，实际 %d", proxy.audioDroppedReplay)
	}
}

// TestConsecutiveReplayStillDropped：确认阈值化没有把真正的 CDN 回放放过去。
// 连续 4 帧命中旧指纹后，至少后两帧必须被丢弃并压缩时间轴。
func TestConsecutiveReplayStillDropped(t *testing.T) {
	proxy := newManualDouyuProxy("huya")
	proxy.pacerEnabled = false
	proxy.pacerTargetMs.Store(0)
	proxy.haveOut[1] = true
	proxy.lastOutTs[1] = 1000

	var flv bytes.Buffer
	flv.Write(replayFLVHeader())
	flv.Write(replayTagBytes(flvTagTypeVideo, 0, []byte{0x17, 1, 0, 0, 6, 6, 6, 6}))
	payloads := make([][]byte, 4)
	for i := range payloads {
		payloads[i] = []byte{0xA0, 1, 0, byte(i + 1), 7, 7, 7}
		flv.Write(replayTagBytes(flvTagTypeAudio, int64((i+1)*21), payloads[i]))
	}
	// 回放同样的 4 帧（间隔 21ms），并再给一帧新内容。
	for i, payload := range payloads {
		flv.Write(replayTagBytes(flvTagTypeAudio, int64(105+i*21), payload))
	}
	flv.Write(replayTagBytes(flvTagTypeAudio, 189, []byte{0xA0, 1, 0, 99, 7, 7, 7}))

	if err := proxy.consume(bufio.NewReader(bytes.NewReader(flv.Bytes()))); err != nil {
		t.Fatalf("consume: %v", err)
	}
	if proxy.audioDroppedReplay < 2 {
		t.Fatalf("连续回放应至少丢 2 帧，实际 %d", proxy.audioDroppedReplay)
	}
}

// TestHuyaReconnectDropsAudioUntilFreshKeyframe：
// 续段视频在等新关键帧时若继续转发音频，画面恢复后声音会领先 1~2 秒。
// 真机 avOutOffsetMs=-2135ms 即来源于此。该用例锁定：黑窗期间音频必须一起丢弃，
// 视频关键帧写出后两轨才同时恢复，且首帧时间戳保持同刻。
func TestHuyaReconnectDropsAudioUntilFreshKeyframe(t *testing.T) {
	proxy := newManualDouyuProxy("huya")
	proxy.pacerEnabled = false
	proxy.pacerTargetMs.Store(0)
	proxy.haveOut[0], proxy.haveOut[1] = true, true
	proxy.lastOutTs[0], proxy.lastOutTs[1] = 1000, 1000

	var flv bytes.Buffer
	flv.Write(replayFLVHeader())
	// 重连带回的旧音频：在新关键帧之前必须全部丢弃。
	for i := 0; i < 4; i++ {
		flv.Write(replayTagBytes(flvTagTypeAudio, int64(i*21), []byte{0xA0, 1, 0, byte(i + 1), 3, 3, 3}))
	}
	// 全新视频关键帧：解除视频丢弃，也解除音频丢弃。
	flv.Write(replayTagBytes(flvTagTypeVideo, 84, []byte{0x17, 1, 0, 0, 9, 9, 9, 9}))
	flv.Write(replayTagBytes(flvTagTypeAudio, 105, []byte{0xA0, 1, 0, 20, 4, 4, 4}))
	flv.Write(replayTagBytes(flvTagTypeVideo, 117, []byte{0x17, 1, 0, 0, 21, 5, 5, 5}))

	if err := proxy.consume(bufio.NewReader(bytes.NewReader(flv.Bytes()))); err != nil {
		t.Fatalf("consume: %v", err)
	}
	downstream := proxy.client.writer.(*fakeResponseWriter)
	tags := parseContinuityTags(t, downstream.buf.Bytes())
	videoSeen := false
	for _, tag := range tags {
		if tag.tagType == flvTagTypeVideo && !isAvcSequenceHeader(tag.data) {
			videoSeen = true
			continue
		}
		if tag.tagType == flvTagTypeAudio && !videoSeen {
			t.Fatalf("新关键帧之前不应转发音频（ts=%d）", tag.ts)
		}
	}
	if proxy.audioDroppedReplay < 4 {
		t.Fatalf("黑窗期音频应全部丢弃，实际丢弃 %d", proxy.audioDroppedReplay)
	}
}
