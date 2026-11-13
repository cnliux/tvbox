package platformlive

// 「计划内切换」：在上一段被 CDN 关闭之前，提前解析并预建第二条上游连接，
// 缓冲到它的第一个关键帧，然后让当前段在自己的关键帧处交接。
//
// 参考 wzgrx/PureLive 的 FlvSplicer（refreshAt 预建双连接 + 关键帧对齐）与
// livepurer 的 OutLoop（下游单条流不断）。与两者的差别：我们保留帧指纹去重，
// 因此新连接带回的旧缓冲仍会被安全丢弃。
//
// 仅虎牙启用：它的 ~120s 会话上限是确定性周期，适合计划内切换；斗鱼继续走
// 自己的租约语义，通用档不预设会话周期（用户明确要求三档互不借用）。

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"io"
	"net/http"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// 虎牙实测会话 120~137s，取 125s 作为首次预期，之后按上一段实际时长学习。
	huyaNominalSessionMs = int64(125000)
	// 在预期关流前 12s 开始预建，既覆盖解析 + TLS 握手 + 首关键帧，又不让
	// 第二条连接空转太久（预建结果 20s 未被取用会自动释放）。
	huyaPlannedRefreshLeadMs = int64(12000)
	flvProxyHandoverMaxBuffer = 4 << 20
	flvProxyHandoverKeyframeTimeout = 12 * time.Second
	flvProxyHandoverHoldTimeout = 20 * time.Second
)

var errHandover = errors.New("planned keyframe handover")

type resolveCandidateFunc func() (LiveStreamCandidate, map[string]string, error)

// preparedStream 是一条已经连通、且已读到首个关键帧的上游会话。
// header+buffer 是「从 FLV 头到该关键帧」的原始字节，body 是接续的实时流，
// 两者拼成一个 bufio.Reader 后可以直接交给 consume()，与正常段完全同路径。
type preparedStream struct {
	candidate LiveStreamCandidate
	header    []byte
	buffer    []byte
	body      io.ReadCloser
	cancel    context.CancelFunc
	createdAt time.Time
	taken     atomic.Bool
	closeOnce sync.Once
}

func (s *preparedStream) reader() *bufio.Reader {
	prefix := make([]byte, 0, len(s.header)+len(s.buffer))
	prefix = append(prefix, s.header...)
	prefix = append(prefix, s.buffer...)
	return bufio.NewReaderSize(io.MultiReader(bytes.NewReader(prefix), s.body), 128*1024)
}

func (s *preparedStream) close() {
	s.closeOnce.Do(func() {
		if s.cancel != nil {
			s.cancel()
		}
		if s.body != nil {
			_ = s.body.Close()
		}
	})
}

func (s *preparedStream) take() {
	s.taken.Store(true)
}

// handoverState 在 pullSegment 与 consume 之间传递「预备流已就绪」的信号。
// consume 只会在成功写出一个视频关键帧之后检查它，保证交接点是可独立解码的。
type handoverState struct {
	ready  <-chan *preparedStream
	pending atomic.Bool
	stream  atomic.Pointer[preparedStream]
}

func (h *handoverState) watch(ctx context.Context) {
	if h == nil || h.ready == nil {
		return
	}
	go func() {
		select {
		case stream := <-h.ready:
			if stream != nil {
				h.stream.Store(stream)
				h.pending.Store(true)
			}
		case <-ctx.Done():
		}
	}()
}

func (h *handoverState) shouldHandover() bool {
	return h != nil && h.pending.Load()
}

func (h *handoverState) prepared() *preparedStream {
	if h == nil {
		return nil
	}
	return h.stream.Load()
}

type pullOptions struct {
	// prepared 为计划内预建结果通道；watch 会在 consume 期间监听它。
	prepared <-chan *preparedStream
	// usePrepared 非空时本段直接消费已备好的第二条连接，不再发起新请求。
	usePrepared *preparedStream
}

// prepareNextConnection 解析新地址、连上 CDN，并读到第一个视频关键帧。
// 任何一步失败都返回 nil，调用方回落到原有的 EOF 后续拉路径，不会让播放中断。
func (p *flvProxy) prepareNextConnection(ctx context.Context, resolve resolveCandidateFunc) *preparedStream {
	candidate, headers, err := resolve()
	if err != nil {
		p.event("prepareResolveFailed")
		return nil
	}
	if p.tuning.leaseAware {
		candidate.URL = patchDouyuURL(candidate.URL)
	}
	requestCtx, cancel := context.WithCancel(ctx)
	request, err := http.NewRequestWithContext(requestCtx, http.MethodGet, candidate.URL, nil)
	if err != nil {
		cancel()
		return nil
	}
	request.Header.Set("Accept", "*/*")
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response, err := p.httpClient.Do(request)
	if err != nil || response.StatusCode != http.StatusOK {
		if response != nil {
			_ = response.Body.Close()
		}
		cancel()
		p.event("prepareConnectFailed")
		return nil
	}
	reader := bufio.NewReaderSize(response.Body, 128*1024)
	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(reader, header); err != nil ||
		header[0] != 'F' || header[1] != 'L' || header[2] != 'V' {
		_ = response.Body.Close()
		cancel()
		p.event("prepareHeaderFailed")
		return nil
	}
	deadline := time.Now().Add(flvProxyHandoverKeyframeTimeout)
	buffer := make([]byte, 0, 64<<10)
	for {
		if time.Now().After(deadline) || len(buffer) > flvProxyHandoverMaxBuffer {
			_ = response.Body.Close()
			cancel()
			p.event("prepareKeyframeTimeout")
			return nil
		}
		tagHeader := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(reader, tagHeader); err != nil {
			_ = response.Body.Close()
			cancel()
			p.event("prepareReadFailed")
			return nil
		}
		size := int(tagHeader[1])<<16 | int(tagHeader[2])<<8 | int(tagHeader[3])
		if size <= 0 || size > flvProxyMaxTagSize {
			_ = response.Body.Close()
			cancel()
			return nil
		}
		tag := make([]byte, flvProxyTagHeaderSize+size+flvProxyPrevTagSizeLen)
		copy(tag, tagHeader)
		if _, err := io.ReadFull(reader, tag[flvProxyTagHeaderSize:]); err != nil {
			_ = response.Body.Close()
			cancel()
			p.event("prepareReadFailed")
			return nil
		}
		buffer = append(buffer, tag...)
		data := tag[flvProxyTagHeaderSize : flvProxyTagHeaderSize+size]
		if (tagHeader[0]&0x1f) == flvTagTypeVideo && isVideoKeyframe(data) {
			break
		}
	}
	stream := &preparedStream{
		candidate: candidate,
		header:    header,
		buffer:    buffer,
		body:      response.Body,
		cancel:    cancel,
		createdAt: time.Now(),
	}
	// 未被取用的预备连接自动释放，防止 CDN 侧残留长连接。
	time.AfterFunc(flvProxyHandoverHoldTimeout, func() {
		if !stream.taken.Load() {
			stream.close()
		}
	})
	p.event("prepareReady")
	return stream
}
