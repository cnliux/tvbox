package platformlive

// 虎牙「还剩一点点卡顿」的三处根因回归守卫：
//  1. 计划内交接成功后 streamLoop 的 stopPrepare() 级联取消预建 HTTP 请求；
//  2. 续段 spliceBase 锁定 offset 之前累计的回放丢弃量被重复扣减；
//  3. pacer 状态是 consume 局部变量，每段（含每次交接）都重新做一次预填。
//
// 这些用例都能在旧实现上稳定失败，因此既是回归守卫也是根因证明。

import (
	"bufio"
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

type continuityOutTag struct {
	tagType byte
	ts      int64
	data    []byte
}

// parseContinuityTags 解析 writeTag 写给下游的「tagHeader + data + prevSize」序列。
func parseContinuityTags(t *testing.T, payload []byte) []continuityOutTag {
	t.Helper()
	reader := bytes.NewReader(payload)
	var tags []continuityOutTag
	for reader.Len() >= flvProxyTagHeaderSize {
		header := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(reader, header); err != nil {
			break
		}
		size := int(header[1])<<16 | int(header[2])<<8 | int(header[3])
		if size <= 0 || reader.Len() < size+flvProxyPrevTagSizeLen {
			break
		}
		data := make([]byte, size)
		if _, err := io.ReadFull(reader, data); err != nil {
			t.Fatalf("输出标签体不完整：%v", err)
		}
		if _, err := io.ReadFull(reader, make([]byte, flvProxyPrevTagSizeLen)); err != nil {
			t.Fatalf("输出标签尾不完整：%v", err)
		}
		ts := int64(header[4])<<16 | int64(header[5])<<8 | int64(header[6]) | int64(header[7])<<24
		tags = append(tags, continuityOutTag{tagType: header[0] & 0x1f, ts: ts, data: data})
	}
	return tags
}

// TestPreparedConnectionSurvivesPrepareContextCancel：预建连接一旦建立，
// 就不能被「等待预建」的 context 取消掐断——streamLoop 在刚完成交接时会
// 立即 stopPrepare()，旧实现会让下一段 consume 直接读到 context canceled。
func TestPreparedConnectionSurvivesPrepareContextCancel(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "video/x-flv")
		_, _ = w.Write(replayFLVHeader())
		var start bytes.Buffer
		writeTestFLVEx(&start, []testFrame{
			{ts: 0, video: true, seqHeader: true},
			{ts: 0, video: true, keyframe: true},
		})
		_, _ = w.Write(start.Bytes()[flvProxyHeaderSize+flvProxyPrevTagSizeLen:])
		flusher, _ := w.(http.Flusher)
		if flusher != nil {
			flusher.Flush()
		}
		ticker := time.NewTicker(15 * time.Millisecond)
		defer ticker.Stop()
		ts := int64(33)
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
				ts += 33
			}
		}
	}))
	defer upstream.Close()

	proxy := newManualDouyuProxy("huya")
	prepareCtx, cancelPrepare := context.WithCancel(context.Background())
	stream := proxy.prepareNextConnection(prepareCtx, func() (LiveStreamCandidate, map[string]string, error) {
		return LiveStreamCandidate{URL: upstream.URL, Protocol: "flv"}, nil, nil
	})
	if stream == nil {
		t.Fatal("预备连接未能建立")
	}
	defer stream.close()
	// 模拟 streamLoop 在 pullSegment 返回后调用 stopPrepare()。
	cancelPrepare()

	reader := stream.reader()
	// 跳过 FLV 头，再连续读若干标签（含 prefix 里的序列头/关键帧与之后的实时帧）。
	skip := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(reader, skip); err != nil {
		t.Fatalf("预备流 FLV 头不可读：%v", err)
	}
	done := make(chan error, 1)
	go func() {
		for i := 0; i < 8; i++ {
			header := make([]byte, flvProxyTagHeaderSize)
			if _, err := io.ReadFull(reader, header); err != nil {
				done <- err
				return
			}
			size := int(header[1])<<16 | int(header[2])<<8 | int(header[3])
			if size <= 0 {
				done <- io.ErrUnexpectedEOF
				return
			}
			if _, err := io.ReadFull(reader, make([]byte, size+flvProxyPrevTagSizeLen)); err != nil {
				done <- err
				return
			}
		}
		done <- nil
	}()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("交接后预建连接应仍可读取，实际：%v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("交接后预建连接读取超时")
	}
}

// TestContinuationDoesNotDoubleCountDroppedSourceMs：续段开头带回的回放帧
// 在 spliceBase 锁定 offset 之前就被丢弃，其时长已由平移吸收；若后续帧再
// 把这段时长扣一遍，输出时间戳会被拉回上一段交付位置之前，播放器表现为
// 时间戳停滞（旧实现靠 controlledTs 每帧 +1ms 兜住，画面卡顿数秒）。
func TestContinuationDoesNotDoubleCountDroppedSourceMs(t *testing.T) {
	proxy := newManualDouyuProxy("huya")
	proxy.pacerEnabled = false
	proxy.pacerTargetMs.Store(0)
	proxy.haveOut[1] = true
	proxy.lastOutTs[1] = 10000

	const replayFrames = 30
	replayPayloads := make([][]byte, 0, replayFrames)
	for i := 0; i < replayFrames; i++ {
		payload := replayVideoFrame(1000+i, i == 0)
		replayPayloads = append(replayPayloads, payload)
		proxy.rememberFrame(flvFrameFingerprint(flvTagTypeVideo, payload), 10000)
	}

	var flv bytes.Buffer
	flv.Write(replayFLVHeader())
	flv.Write(replayTagBytes(flvTagTypeVideo, 0, replayAVCSequenceHeader()))
	for i, payload := range replayPayloads {
		flv.Write(replayTagBytes(flvTagTypeVideo, int64(i*33), payload))
	}
	for i := 0; i < 6; i++ {
		ts := int64(replayFrames*33 + i*33)
		flv.Write(replayTagBytes(flvTagTypeVideo, ts, replayVideoFrame(2000+i, true)))
	}

	if err := proxy.consume(bufio.NewReader(bytes.NewReader(flv.Bytes()))); err != nil {
		t.Fatalf("续段消费失败：%v", err)
	}
	downstream, ok := proxy.client.writer.(*fakeResponseWriter)
	if !ok {
		t.Fatal("测试夹具下游类型异常")
	}
	tags := parseContinuityTags(t, downstream.buf.Bytes())
	var mediaTs []int64
	for _, tag := range tags {
		if tag.tagType == flvTagTypeVideo && !isAvcSequenceHeader(tag.data) {
			mediaTs = append(mediaTs, tag.ts)
		}
	}
	if len(mediaTs) < 6 {
		t.Fatalf("新内容应全部转发，实际只有 %d 帧（tsRejectedVideo=%d）",
			len(mediaTs), proxy.tsRejectedVideo)
	}
	if proxy.tsRejectedVideo != 0 {
		t.Fatalf("不应出现时间戳回退拒绝，实际 %d 帧", proxy.tsRejectedVideo)
	}
	for i := 1; i < len(mediaTs); i++ {
		if delta := mediaTs[i] - mediaTs[i-1]; delta < 20 {
			t.Fatalf("输出时间轴被过度压缩：第 %d 帧只前进 %dms（前一帧 %d，当前 %d）",
				i, delta, mediaTs[i-1], mediaTs[i])
		}
	}
	if mediaTs[0] <= 10000 {
		t.Fatalf("续段首帧应接在上一段输出之后，实际 %d", mediaTs[0])
	}
}

// TestPacerStatePersistsAcrossSegments：pacer 基准跨上游会话保持，
// 交接后不再重新做一次目标缓冲预填；落后积压帧触发一次追帧重对齐。
func TestPacerStatePersistsAcrossSegments(t *testing.T) {
	pc := &flvPacer{}
	t0 := time.Unix(0, 0)
	maxDelay := 6 * time.Second
	const target = int64(1000)

	// 首段：目标窗口内预填（立即转发），窗口结束进入实时匀速。
	if wait, _, _ := pc.paceAt(0, target, maxDelay, false, t0); wait != 0 {
		t.Fatalf("预填期不应等待，实际 %s", wait)
	}
	if wait, _, _ := pc.paceAt(500, target, maxDelay, false, t0.Add(500*time.Millisecond)); wait != 0 {
		t.Fatalf("预填期不应等待，实际 %s", wait)
	}
	if wait, _, _ := pc.paceAt(1000, target, maxDelay, false, t0.Add(time.Second)); wait > 0 {
		t.Fatalf("预填结束的第一帧应立即写出，实际等待 %s", wait)
	}

	// 「第二段」（交接）：墙钟与媒体时间同步前进，不应重置、也不应重新预填。
	wait, rebased, _ := pc.paceAt(2000, target, maxDelay, false, t0.Add(2*time.Second))
	if rebased || wait > 50*time.Millisecond {
		t.Fatalf("交接后应沿用原匀速曲线，实际 wait=%s rebased=%v", wait, rebased)
	}
	if !pc.started || !pc.haveFirst {
		t.Fatal("交接后 pacer 状态被重置（每段重新预填会让播放器周期性收到突发数据）")
	}

	// 墙钟空窗后读到积压旧帧：触发追帧重对齐，且只放行当前帧。
	wait, rebased, catchUp := pc.paceAt(2100, target, maxDelay, false, t0.Add(6*time.Second))
	if !rebased || !catchUp || wait != 0 {
		t.Fatalf("积压追帧应重对齐，实际 wait=%s rebased=%v catchUp=%v", wait, rebased, catchUp)
	}
	// 重对齐之后按新基准继续匀速，不再出现一批 wait=0 的突发。
	wait, rebased, _ = pc.paceAt(2133, target, maxDelay, false,
		t0.Add(6*time.Second+33*time.Millisecond))
	if rebased || wait > 100*time.Millisecond {
		t.Fatalf("重对齐后应恢复匀速，实际 wait=%s rebased=%v", wait, rebased)
	}
}
