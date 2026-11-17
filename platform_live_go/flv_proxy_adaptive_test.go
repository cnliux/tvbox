package platformlive

import (
	"bufio"
	"bytes"
	"testing"
)

// ───────────────────── 自适应回放窗口 ─────────────────────

// TestReplayWindowStartsAtSiteDefault：未命中回放时窗口必须等于平台档位默认值，
// 不能一上来就放大——窗口越大，每帧查重的内存与 CPU 成本越高。
func TestReplayWindowStartsAtSiteDefault(t *testing.T) {
	for _, site := range []string{"huya", "douyu", "generic"} {
		proxy := &flvProxy{tuning: siteTuningFor(site)}
		if got, want := proxy.effectiveReplayWindowMs(), proxy.tuning.replayWindowMs; got != want {
			t.Fatalf("%s 初始窗口 = %d，期望档位默认 %d", site, got, want)
		}
	}
}

// TestReplayWindowExpandsWhenSpanNearsWindow：回放跨度接近当前窗口时翻倍。
// 这正是异常房间（133 帧回放）需要的：固定 12s 窗口装不下更长的回吐缓冲。
func TestReplayWindowExpandsWhenSpanNearsWindow(t *testing.T) {
	proxy := &flvProxy{tuning: siteTuningFor("huya")}
	base := proxy.effectiveReplayWindowMs()

	// 跨度 = 窗口的 80%，应触发扩窗。
	proxy.noteReplaySpan(1_000)
	proxy.noteReplaySpan(1_000 + base*8/10)

	got := proxy.effectiveReplayWindowMs()
	if got <= base {
		t.Fatalf("跨度接近窗口未扩窗：base=%d got=%d", base, got)
	}
	if got > flvReplayWindowCapMs {
		t.Fatalf("扩窗超出上限：got=%d cap=%d", got, flvReplayWindowCapMs)
	}
	if proxy.replayExpansions == 0 {
		t.Fatal("扩窗计数未增加")
	}
}

// TestReplayWindowStableForSmallSpan：小跨度回放不应把窗口撑大。
func TestReplayWindowStableForSmallSpan(t *testing.T) {
	proxy := &flvProxy{tuning: siteTuningFor("huya")}
	base := proxy.effectiveReplayWindowMs()
	proxy.noteReplaySpan(0)
	proxy.noteReplaySpan(1_000) // 跨度 1s，远小于 12s 窗口
	if got := proxy.effectiveReplayWindowMs(); got != base {
		t.Fatalf("小跨度不应扩窗：base=%d got=%d", base, got)
	}
}

// TestReplayWindowNeverExceedsCap：连续大跨度回放也不能突破硬上限。
func TestReplayWindowNeverExceedsCap(t *testing.T) {
	proxy := &flvProxy{tuning: siteTuningFor("huya")}
	for i := int64(0); i < 10; i++ {
		proxy.noteReplaySpan(i * 60_000)
	}
	if got := proxy.effectiveReplayWindowMs(); got > flvReplayWindowCapMs {
		t.Fatalf("窗口突破上限：got=%d cap=%d", got, flvReplayWindowCapMs)
	}
}

// ───────────────────── 音频回拉 ─────────────────────

// TestAudioResyncPullsBackWhenLeading：音频领先视频超过阈值时回拉，
// 且回拉后差值落在阈值一半附近（不是一次性拉平造成可闻跳变）。
func TestAudioResyncPullsBackWhenLeading(t *testing.T) {
	proxy := &flvProxy{tuning: siteTuningFor("huya")}
	proxy.haveOut[0], proxy.haveOut[1] = true, true
	proxy.lastOutTs[0], proxy.lastOutTs[1] = 1_000, 1_000

	// 音频领先 2.2 秒（对应实测最严重的 2247ms）。
	adjusted := proxy.resyncAudio(1_000 + 2_200)
	drift := adjusted - proxy.lastOutTs[1]
	if drift > flvAvMaxDriftMs {
		t.Fatalf("回拉后仍领先 %dms，超过阈值 %d", drift, flvAvMaxDriftMs)
	}
	if drift <= 0 {
		t.Fatalf("回拉过度，音频跑到视频之后 %dms", drift)
	}
	if proxy.audioResyncShifts.Load() != 1 {
		t.Fatalf("回拉次数 = %d，期望 1", proxy.audioResyncShifts.Load())
	}
	if proxy.audioShiftMs.Load() >= 0 {
		t.Fatalf("音频偏移应为负（回拉），实际 %d", proxy.audioShiftMs.Load())
	}
}

// TestAudioResyncKeepsOffsetForLaterFrames：回拉是整体偏移，后续帧沿用同一偏移，
// 不能在每帧上反复重新计算（那会把音频越拉越前）。
func TestAudioResyncKeepsOffsetForLaterFrames(t *testing.T) {
	proxy := &flvProxy{tuning: siteTuningFor("huya")}
	proxy.haveOut[0], proxy.haveOut[1] = true, true
	proxy.lastOutTs[0], proxy.lastOutTs[1] = 1_000, 1_000

	first := proxy.resyncAudio(3_200)
	shiftAfterFirst := proxy.audioShiftMs.Load()
	// 下一帧源时间戳 +20ms，输出也应只 +20ms，偏移不变。
	second := proxy.resyncAudio(3_220)
	if proxy.audioShiftMs.Load() != shiftAfterFirst {
		t.Fatalf("后续帧不应再次调整偏移：%d -> %d", shiftAfterFirst, proxy.audioShiftMs.Load())
	}
	if delta := second - first; delta != 20 {
		t.Fatalf("后续帧间隔被改动：%d，期望 20", delta)
	}
}

// TestAudioResyncClampsInsteadOfDropping：回拉后若落到已交付音频之前，
// 必须钳到已交付位置而不是返回更小值（否则该帧会被 validateTs 丢弃、时间轴停住）。
func TestAudioResyncClampsInsteadOfDropping(t *testing.T) {
	proxy := &flvProxy{tuning: siteTuningFor("huya")}
	proxy.haveOut[0], proxy.haveOut[1] = true, true
	proxy.lastOutTs[0], proxy.lastOutTs[1] = 5_000, 5_000
	// 先制造一个较大的负偏移。
	proxy.audioShiftMs.Store(-3_000)

	got := proxy.resyncAudio(5_010)
	if got < proxy.lastOutTs[0] {
		t.Fatalf("回拉后低于已交付音频位置：got=%d last=%d", got, proxy.lastOutTs[0])
	}
}

// TestAudioResyncNoopWhenAligned：正常对齐时不得改动音频时间戳。
func TestAudioResyncNoopWhenAligned(t *testing.T) {
	proxy := &flvProxy{tuning: siteTuningFor("huya")}
	proxy.haveOut[0], proxy.haveOut[1] = true, true
	proxy.lastOutTs[0], proxy.lastOutTs[1] = 1_000, 1_020

	got := proxy.resyncAudio(1_030)
	if got != 1_030 {
		t.Fatalf("对齐时不应改动时间戳：got=%d want=1030", got)
	}
	if proxy.audioResyncShifts.Load() != 0 {
		t.Fatal("对齐时不应触发回拉")
	}
}

// ───────────────────── 端到端：续段音频领先能被收敛 ─────────────────────

// TestContinuationAudioDriftIsCorrected：续段后音频持续领先视频约 2 秒时，
// 经 consume 输出的音频与视频偏移必须被压回阈值内。这是三个异常房间
// （A/V 偏移 1636~2247ms）在生产路径上的回归测试。
func TestContinuationAudioDriftIsCorrected(t *testing.T) {
	proxy := newManualDouyuProxy("huya")
	proxy.pacerEnabled = false
	proxy.pacerTargetMs.Store(0)
	// 上一段已交付到 1000ms。
	proxy.haveOut[0], proxy.haveOut[1] = true, true
	proxy.lastOutTs[0], proxy.lastOutTs[1] = 1_000, 1_000

	var flv bytes.Buffer
	flv.Write(replayFLVHeader())
	// 新段：视频从 0 起（关键帧），音频从 2000 起 —— 模拟音频整体领先。
	flv.Write(replayTagBytes(flvTagTypeVideo, 0, replayAVCSequenceHeader()))
	flv.Write(replayTagBytes(flvTagTypeVideo, 0, []byte{0x17, 1, 0, 0, 9, 9, 9, 9}))
	for i := 0; i < 40; i++ {
		audioTs := int64(2_000 + i*21)
		videoTs := int64(i * 33)
		flv.Write(replayTagBytes(flvTagTypeAudio, audioTs, []byte{0xAF, 1, byte(i), 7, 7, 7}))
		flv.Write(replayTagBytes(flvTagTypeVideo, videoTs, []byte{0x27, 1, 0, 0, byte(i), 8, 8}))
	}

	if err := proxy.consume(bufio.NewReader(bytes.NewReader(flv.Bytes()))); err != nil {
		t.Fatalf("consume: %v", err)
	}
	tags := parseContinuityTags(t, proxy.client.writer.(*fakeResponseWriter).buf.Bytes())
	var lastAudio, lastVideo int64 = -1, -1
	for _, tag := range tags {
		switch {
		case tag.tagType == flvTagTypeAudio:
			lastAudio = tag.ts
		case tag.tagType == flvTagTypeVideo && !isAvcSequenceHeader(tag.data):
			lastVideo = tag.ts
		}
	}
	if lastAudio < 0 || lastVideo < 0 {
		t.Fatalf("输出缺少音视频帧：audio=%d video=%d", lastAudio, lastVideo)
	}
	if drift := lastAudio - lastVideo; drift > flvAvMaxDriftMs {
		t.Fatalf("音频仍领先 %dms（阈值 %d），回拉未生效", drift, flvAvMaxDriftMs)
	}
	if proxy.audioResyncShifts.Load() == 0 {
		t.Fatal("未触发音频回拉")
	}
}
