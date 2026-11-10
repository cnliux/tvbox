package platformlive

// 本机连续 FLV 代理（方案 A）。
//
// 背景：虎牙 CDN 对单次 HTTP-FLV 拉流有约 2 分钟的会话上限（实测 120~137 秒
// 被服务端正常关闭，HLS 的同一签名地址也会在约 142 秒后 403）。播放器直接
// 使用平台地址时，每 2 分钟必然经历一次 ENDED → 重新解析 → 重新 prepare，
// 表现为可见的断流。参考 zhangchaosd/livepurer 的服务端缝流做法：对下游只
// 保持一条永不结束的本地 FLV，上游正常 EOF 后立刻用新签名重新拉流，按时间
// 戳去重并顺延，播放器侧不再收到 ENDED。
//
// 结构：
//   - gomobile 侧通过 StartFlvProxy 拿到句柄，Kotlin 用 handle.LocalUrl() 播放；
//   - 只有在播放器连上本地地址后才开始拉上游（避免无人观看时浪费流量）；
//   - 上游每段 EOF 都重新解析（新 wsSecret/wsTime），候选失败才换线路。

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	flvProxyPath           = "/live.flv"
	flvProxyTagHeaderSize  = 11
	flvProxyHeaderSize     = 9
	flvProxyPrevTagSizeLen = 4
	// 续流时把新一段的首个标签接在上一段的最后一帧之后，保持单调时间轴。
	flvProxySpliceStepMs = 33
	flvProxyRetryDelay   = 600 * time.Millisecond
	flvProxyResolveRetry = 1500 * time.Millisecond
	flvProxyMaxTagSize   = 8 << 20
	flvProxyMaxFailures  = 2

	// FLV Tag 类型（tagHeader[0] & 0x1f）。
	flvTagTypeAudio = 8
	flvTagTypeVideo = 9
	// FLV 视频标签：codecID = data[0] & 0x0f，7 表示 AVC(H.264)。
	flvVideoCodecAVC = 7
)

// FlvProxyHandle 是本机连续 FLV 代理句柄（gomobile bind 后即 Java 对象）。
type FlvProxyHandle struct {
	proxy *flvProxy
}

// StartFlvProxy 启动本机连续 FLV 代理。
//
// site/roomID 为平台与房间号；candidateIndex 指定首选 CDN 线路下标；
// initialURL/headersJSON 可为空：非空时首段直接复用 Kotlin 已解析出的地址与
// 请求头，省掉一次重复解析；为空时交给代理自己解析。
func StartFlvProxy(site string, roomID string, candidateIndex int, initialURL string, headersJSON string) (*FlvProxyHandle, error) {
	service, err := bridgeCurrent()
	if err != nil {
		return nil, err
	}
	headers := map[string]string{}
	if headersJSON != "" {
		_ = json.Unmarshal([]byte(headersJSON), &headers)
	}
	proxy, err := newFlvProxy(service, site, roomID, candidateIndex, initialURL, headers)
	if err != nil {
		return nil, err
	}
	return &FlvProxyHandle{proxy: proxy}, nil
}

// LocalUrl 返回本地代理播放地址（形如 http://127.0.0.1:port/live.flv）。
func (h *FlvProxyHandle) LocalUrl() string {
	if h == nil || h.proxy == nil {
		return ""
	}
	return h.proxy.url
}

// Close 停止代理：关闭本地监听、断开上游连接并结束后台协程。
func (h *FlvProxyHandle) Close() {
	if h == nil || h.proxy == nil {
		return
	}
	h.proxy.close()
}

// Stats 返回代理运行统计（JSON），供 Kotlin 侧日志观察续流是否正常。
func (h *FlvProxyHandle) Stats() string {
	if h == nil || h.proxy == nil {
		return "{}"
	}
	return h.proxy.statsJSON()
}

type flvProxy struct {
	service        *Service
	site           string
	roomID         string
	candidateIndex int

	initialURL     string
	initialHeaders map[string]string

	listener net.Listener
	server   *http.Server
	url      string

	ctx    context.Context
	cancel context.CancelFunc
	done   chan struct{}

	httpClient *http.Client

	mu      sync.Mutex
	client  http.ResponseWriter
	flusher http.Flusher

	clientReady chan struct{}

	tsMu     sync.Mutex
	haveLast bool
	lastTs   int64

	statsMu           sync.Mutex
	startedAt         time.Time
	upstreamSessions  int64
	reconnects        int64
	candidateSwitches int64
	tagsWritten       int64
	bytesWritten      int64
	lastSessionMs     int64
	lastError         string
}

func newFlvProxy(service *Service, site, roomID string, candidateIndex int, initialURL string, initialHeaders map[string]string) (*flvProxy, error) {
	if service == nil {
		return nil, newError("直播解析器未初始化")
	}
	roomID = Text(roomID, "")
	if roomID == "" {
		return nil, newError("直播间号不能为空")
	}
	// Kotlin 侧传入的可能是 "huya:31297160" 这类带平台前缀的房间主键，
	// 续流时要交给 adapter.NormalizeRoomID 的是纯房间号。
	if prefix := Text(site, "") + ":"; prefix != ":" &&
		len(roomID) > len(prefix) && strings.HasPrefix(strings.ToLower(roomID), strings.ToLower(prefix)) {
		roomID = roomID[len(prefix):]
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, newError("本机直播代理启动失败：%s", err.Error())
	}
	ctx, cancel := context.WithCancel(context.Background())
	proxy := &flvProxy{
		service:        service,
		site:           site,
		roomID:         roomID,
		candidateIndex: candidateIndex,
		initialURL:     Text(initialURL, ""),
		initialHeaders: initialHeaders,
		listener:       listener,
		url:            fmt.Sprintf("http://127.0.0.1:%d%s", listener.Addr().(*net.TCPAddr).Port, flvProxyPath),
		ctx:            ctx,
		cancel:         cancel,
		done:           make(chan struct{}),
		clientReady:    make(chan struct{}, 1),
		startedAt:      time.Now(),
		httpClient: &http.Client{
			Transport: &http.Transport{
				// FLV 不能被压缩，避免 Accept-Encoding: gzip 导致整段缓冲。
				DisableCompression:    true,
				ResponseHeaderTimeout: 12 * time.Second,
				DialContext: (&net.Dialer{
					Timeout:   10 * time.Second,
					KeepAlive: 30 * time.Second,
				}).DialContext,
			},
		},
	}
	proxy.server = &http.Server{Handler: proxy}
	go func() {
		_ = proxy.server.Serve(listener)
	}()
	go proxy.run()
	return proxy, nil
}

func (p *flvProxy) close() {
	p.cancel()
	_ = p.server.Close()
	select {
	case <-p.done:
	case <-time.After(2 * time.Second):
	}
}

func (p *flvProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path != flvProxyPath {
		http.NotFound(w, r)
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "video/x-flv")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Connection", "close")
	w.WriteHeader(http.StatusOK)
	// 先给出 FLV 头，播放器可立即按 FLV 解析，不必等上游首段到达。
	_, _ = w.Write(flvProxyFileHeader())
	flusher.Flush()

	p.mu.Lock()
	p.client = w
	p.flusher = flusher
	p.mu.Unlock()
	select {
	case p.clientReady <- struct{}{}:
	default:
	}

	<-r.Context().Done()

	p.mu.Lock()
	if p.client == w {
		p.client = nil
		p.flusher = nil
	}
	p.mu.Unlock()
}

// flvProxyFileHeader 生成 FLV 文件头 + PreviousTagSize0。
func flvProxyFileHeader() []byte {
	header := []byte{'F', 'L', 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09}
	header = append(header, 0x00, 0x00, 0x00, 0x00)
	return header
}

func (p *flvProxy) hasClient() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.client != nil
}

func (p *flvProxy) run() {
	defer close(p.done)
	for {
		if !p.waitClient() {
			return
		}
		p.streamLoop()
	}
}

// waitClient 阻塞到播放器连上本地代理；返回 false 表示代理已关闭。
func (p *flvProxy) waitClient() bool {
	for {
		if p.ctx.Err() != nil {
			return false
		}
		if p.hasClient() {
			return true
		}
		select {
		case <-p.ctx.Done():
			return false
		case <-p.clientReady:
		case <-time.After(500 * time.Millisecond):
		}
	}
}

// streamLoop 在播放器连接存续期间不断拉流：正常 EOF 立即重解析续拉，
// 连续硬失败才换下一条候选线路。
func (p *flvProxy) streamLoop() {
	candidateIndex := p.candidateIndex
	failures := 0
	useInitial := p.initialURL != ""
	for p.hasClient() && p.ctx.Err() == nil {
		var candidate LiveStreamCandidate
		var headers map[string]string
		if useInitial {
			candidate = LiveStreamCandidate{CDN: "本地首段", URL: p.initialURL, Protocol: "flv"}
			headers = p.initialHeaders
			useInitial = false
		} else {
			stream, err := p.resolve()
			if err != nil {
				p.setLastError(err.Error())
				select {
				case <-p.ctx.Done():
					return
				case <-time.After(flvProxyResolveRetry):
				}
				continue
			}
			if len(stream.Candidates) == 0 {
				p.setLastError("未返回可播放地址")
				select {
				case <-p.ctx.Done():
					return
				case <-time.After(flvProxyResolveRetry):
				}
				continue
			}
			candidate = stream.Candidates[candidateIndex%len(stream.Candidates)]
			headers = stream.Headers
		}

		err := p.pull(candidate, headers)
		if p.ctx.Err() != nil {
			return
		}
		if err == nil {
			// 服务端正常关流（虎牙约 2 分钟一次）：立刻重新解析拿新签名续拉。
			p.bumpReconnect()
			continue
		}
		p.setLastError(err.Error())
		failures++
		if failures >= flvProxyMaxFailures {
			failures = 0
			candidateIndex++
			p.bumpCandidateSwitch(err)
		}
		select {
		case <-p.ctx.Done():
			return
		case <-time.After(flvProxyRetryDelay):
		}
	}
}

// resolve 重新解析房间地址（绕过缓存，保证拿到新的 wsSecret/wsTime）。
func (p *flvProxy) resolve() (*ResolvedLiveStream, error) {
	adapter, err := p.service.adapter(p.site)
	if err != nil {
		return nil, err
	}
	roomID, err := adapter.NormalizeRoomID(p.roomID)
	if err != nil {
		return nil, err
	}
	return adapter.Resolve(roomID)
}

// pull 拉取一段上游 FLV 并原样缝进本地输出；返回 nil 表示上游正常结束。
func (p *flvProxy) pull(candidate LiveStreamCandidate, headers map[string]string) error {
	request, err := http.NewRequestWithContext(p.ctx, http.MethodGet, candidate.URL, nil)
	if err != nil {
		return newError("上游地址无效：%s", err.Error())
	}
	request.Header.Set("Accept", "*/*")
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	response, err := p.httpClient.Do(request)
	if err != nil {
		return newError("上游拉流失败：%s", err.Error())
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return newError("上游 HTTP %d", response.StatusCode)
	}
	reader := bufio.NewReaderSize(response.Body, 128*1024)
	return p.consume(reader)
}

func (p *flvProxy) consume(reader *bufio.Reader) error {
	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(reader, header); err != nil {
		return newError("上游 FLV 头读取失败：%s", err.Error())
	}
	if header[0] != 'F' || header[1] != 'L' || header[2] != 'V' {
		return newError("上游返回的不是 FLV 流")
	}
	startedAt := time.Now()
	p.statsMu.Lock()
	p.upstreamSessions++
	p.statsMu.Unlock()

	// 续段（已存在上一帧）时，等待首个关键帧后再开始转发：上游重连瞬间新流常以
	// 非关键帧开头，或重连瞬间重发了一段缓冲内容。直接转发这些无法独立解码的帧
	// 会导致播放器缺关键帧，只能停在上帧 / 花屏，表现为“重复画面”。等到关键帧
	// （含其前的 AVC 序列头）再衔接，保证解码干净、时间轴严格顺延。
	awaitKeyframe := p.hasLastTs()

	var offset int64
	var haveBase bool
	for {
		tagHeader := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(reader, tagHeader); err != nil {
			if err == io.EOF || err == io.ErrUnexpectedEOF {
				p.recordSession(startedAt)
				return nil
			}
			return newError("上游 FLV 读取失败：%s", err.Error())
		}
		size := int(tagHeader[1])<<16 | int(tagHeader[2])<<8 | int(tagHeader[3])
		if size <= 0 || size > flvProxyMaxTagSize {
			return newError("上游 FLV 标签长度异常：%d", size)
		}
		sourceTs := int64(tagHeader[4])<<16 | int64(tagHeader[5])<<8 | int64(tagHeader[6]) | int64(tagHeader[7])<<24
		data := make([]byte, size)
		if _, err := io.ReadFull(reader, data); err != nil {
			return newError("上游 FLV 数据读取失败：%s", err.Error())
		}
		if _, err := io.ReadFull(reader, make([]byte, flvProxyPrevTagSizeLen)); err != nil {
			return newError("上游 FLV 尾部读取失败：%s", err.Error())
		}

		tagType := tagHeader[0] & 0x1f
		if awaitKeyframe && tagType == flvTagTypeVideo {
			if isAvcSequenceHeader(data) {
				// AVC 序列头（SPS/PPS）解码必需，必须保留，但仍等待关键帧。
			} else if isVideoKeyframe(data) {
				awaitKeyframe = false
			} else {
				// 关键帧到达前的非关键视频帧：无法独立解码，丢弃，避免花屏/重复画面。
				continue
			}
		}

		adjusted := sourceTs
		if !haveBase {
			// 偏移锚定到「首个被转发的帧」：前导被丢弃的非关键帧不会污染基准。
			adjusted = p.spliceBase(sourceTs)
			offset = adjusted - sourceTs
			haveBase = true
		} else {
			adjusted = sourceTs + offset
		}
		if !p.acceptTs(adjusted) {
			// 时间戳回退（重连瞬间重复数据或上游非单调）：丢弃该帧。
			continue
		}
		if err := p.writeTag(tagHeader, data, adjusted); err != nil {
			return err
		}
	}
}

// hasLastTs 返回是否已记录上一帧时间戳（用于判断当前是否处于“续段”）。
func (p *flvProxy) hasLastTs() bool {
	p.tsMu.Lock()
	defer p.tsMu.Unlock()
	return p.haveLast
}

// isAvcSequenceHeader 判断 FLV 视频标签是否为 AVC 序列头（AVCPacketType=0），
// 内含 SPS/PPS，是后续关键帧解码的前提，衔接时不可丢弃。
func isAvcSequenceHeader(data []byte) bool {
	if len(data) < 2 {
		return false
	}
	if (data[0] & 0x0f) != flvVideoCodecAVC {
		return false
	}
	return data[1] == 0
}

// isVideoKeyframe 判断 FLV 视频标签是否为可随机接入的关键帧（AVC NALU 关键帧）。
// 非 AVC 编码时以 frameType==1 作为关键帧判据。
func isVideoKeyframe(data []byte) bool {
	if len(data) < 2 {
		return false
	}
	frameType := data[0] >> 4
	if (data[0] & 0x0f) == flvVideoCodecAVC {
		// AVC：AVCPacketType==1（NALU）且 frameType==1（关键帧）。
		return data[1] == 1 && frameType == 1
	}
	return frameType == 1
}

// spliceBase 计算新一段首帧的输出时间戳：首段沿用原值，续段接在上一帧之后。
func (p *flvProxy) spliceBase(sourceTs int64) int64 {
	p.tsMu.Lock()
	defer p.tsMu.Unlock()
	if !p.haveLast {
		return sourceTs
	}
	return p.lastTs + flvProxySpliceStepMs
}

// acceptTs 只拒绝真正的时间戳回退；同一时刻的音视频标签允许并存。
func (p *flvProxy) acceptTs(ts int64) bool {
	p.tsMu.Lock()
	defer p.tsMu.Unlock()
	if p.haveLast && ts < p.lastTs {
		return false
	}
	p.lastTs = ts
	p.haveLast = true
	return true
}

func (p *flvProxy) writeTag(tagHeader []byte, data []byte, ts int64) error {
	tagHeader[4] = byte(ts >> 16)
	tagHeader[5] = byte(ts >> 8)
	tagHeader[6] = byte(ts)
	tagHeader[7] = byte(ts >> 24)
	prevSize := uint32(flvProxyTagHeaderSize + len(data))
	prev := []byte{byte(prevSize >> 24), byte(prevSize >> 16), byte(prevSize >> 8), byte(prevSize)}

	p.mu.Lock()
	writer := p.client
	flusher := p.flusher
	p.mu.Unlock()
	if writer == nil {
		return newError("本地播放器已断开")
	}
	if _, err := writer.Write(tagHeader); err != nil {
		p.dropClient()
		return newError("本地写入失败：%s", err.Error())
	}
	if _, err := writer.Write(data); err != nil {
		p.dropClient()
		return newError("本地写入失败：%s", err.Error())
	}
	if _, err := writer.Write(prev); err != nil {
		p.dropClient()
		return newError("本地写入失败：%s", err.Error())
	}
	if flusher != nil {
		flusher.Flush()
	}
	p.statsMu.Lock()
	p.tagsWritten++
	p.bytesWritten += int64(flvProxyTagHeaderSize + len(data) + flvProxyPrevTagSizeLen)
	p.statsMu.Unlock()
	return nil
}

func (p *flvProxy) dropClient() {
	p.mu.Lock()
	p.client = nil
	p.flusher = nil
	p.mu.Unlock()
}

func (p *flvProxy) setLastError(message string) {
	p.statsMu.Lock()
	p.lastError = message
	p.statsMu.Unlock()
}

func (p *flvProxy) recordSession(startedAt time.Time) {
	p.statsMu.Lock()
	p.lastSessionMs = time.Since(startedAt).Milliseconds()
	p.statsMu.Unlock()
}

func (p *flvProxy) bumpReconnect() {
	p.statsMu.Lock()
	p.reconnects++
	p.lastError = ""
	p.statsMu.Unlock()
}

func (p *flvProxy) bumpCandidateSwitch(err error) {
	p.statsMu.Lock()
	p.candidateSwitches++
	p.lastError = err.Error()
	p.statsMu.Unlock()
}

func (p *flvProxy) statsJSON() string {
	p.statsMu.Lock()
	payload := map[string]interface{}{
		"url":               p.url,
		"client":            p.hasClient(),
		"uptimeSec":         int64(time.Since(p.startedAt).Seconds()),
		"upstreamSessions":  p.upstreamSessions,
		"reconnects":        p.reconnects,
		"candidateSwitches": p.candidateSwitches,
		"tagsWritten":       p.tagsWritten,
		"bytesWritten":      p.bytesWritten,
		"lastSessionMs":     p.lastSessionMs,
		"lastError":         p.lastError,
	}
	p.statsMu.Unlock()
	data, err := json.Marshal(payload)
	if err != nil {
		return "{}"
	}
	return string(data)
}
