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
	"hash/fnv"
	"io"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"sync/atomic"
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
	// pacer 单帧最大等待：超过说明时间戳跳变或续段重绑（如长空窗后追帧），
	// 直接重置墙钟基准避免无限期等待。这里提到 6s，配合下方 5s 目标缓冲使用。
	flvProxyPacerMaxDelay = 6 * time.Second
)

// pacer 目标缓冲：故意比实时慢约 N 毫秒推送，让下游始终保有 N 毫秒已接收数据。
// 上游抖动 / 重连空窗期间播放器仍有缓冲可播，抵抗「一起看」续流风暴。
// 实测旧包 pacer 把缓冲压到 ~2s 时抗抖动能力差（重连风暴 dropped 0→119），
// 默认 5s 与用户目标 4~6s 对齐。运行时可由 SetPacerTargetBufferMs 调整（调试用）。
var flvProxyPacerTargetBufferMs atomic.Int64

func init() {
	flvProxyPacerTargetBufferMs.Store(5000)
}

const (
	// FLV Tag 类型（tagHeader[0] & 0x1f）。
	flvTagTypeAudio = 8
	flvTagTypeVideo = 9
	// FLV 视频标签：codecID = data[0] & 0x0f，7 表示 AVC(H.264)。
	flvVideoCodecAVC = 7
	// 重连回放去重窗口：只保留最近 N 毫秒已转发帧的内容指纹。
	// 不能用时间戳判断回放：新流时间戳从 0 重启，代理又会把输出时间戳强制顺延，
	// 输出时间轴始终严格单调，回放帧在时间轴上完全看不出来。
	// 真实「虎牙·一起看」房间实测回放缓冲长达 6.08 秒（旧窗口 5 秒会漏掉最早一段），
	// 放宽到 12 秒留足余量，内存仍恒定（约 400~700 条记录）。
	flvProxyReplayWindowMs = int64(12000)
	// 续段的指纹去重必须贯穿整个会话：真实 CDN 既会「先给一个全新关键帧、
	// 再跟上几秒旧缓冲」，也会在会话中途（尤其短会话重连风暴时）零散重发旧帧。
	// 只保护开头一段会在后续漏掉（实测 330 帧 / 5.16 秒）。这里只保留一个很大的
	// 硬上限防止异常流不退出，正常 120 秒会话（约 3600 帧）不会触及。
	flvProxyReplayGuardMaxFrames = 7200
	// 同地址快速续拉最多连续 N 次，之后强制重新解析并退避，避免 CDN 短会话
	// 时在同地址上每秒重连多次（PC 侧曾观测到 200 秒 51 次会话）。
	flvProxyMaxFastReconnectStreak = 3
	// 短/空段（上游在数秒内即 EOF）退避与换线：监控曾出现 3 分钟 48 次重连的风暴，
	// 根因是短段 EOF 立即无退避重连且不计入失败，同一条坏线路被高速反复重连。
	// 连续出现短段时按指数退避，达阈值则视为当前候选线路不稳定，切换到下一条 CDN。
	flvProxyShortSessionMs   = 5000
	flvProxyMaxShortStreak   = 4
	flvProxyShortBackoffBase = 500 * time.Millisecond
	flvProxyShortBackoffMax  = 5 * time.Second
)

// flvSiteTuning 把「一起看」续流代理的关键参数按平台分档，
// 让斗鱼与虎牙不再共用同一套调参——它们的回放缓冲形态差异很大：
// - 虎牙「一起看」续流会「先给一个全新关键帧，再跟 6 秒旧缓冲」，回放窗口必须够长；
// - 斗鱼「一起看」回放缓冲通常更短、时间戳更稳，可收紧去重窗口、更快遗忘陈旧指纹。
// 历史调参（常量）全部保留为 Huya/默认档位的取值，保证已有实测结论不回退；
// Douyu 档位在「不破坏正确性」前提下做差异化收紧，后续可按真机实测继续微调。
type flvSiteTuning struct {
	name                 string
	replayWindowMs       int64
	replayGuardMaxFrames int
	spliceStepMs         int64
	pacerMaxDelay        time.Duration
}

// siteTuningFor 按平台返回专属调参；未知平台回退到与历史默认一致的保护档，避免行为漂移。
func siteTuningFor(site string) flvSiteTuning {
	switch strings.ToLower(strings.TrimSpace(site)) {
	case "douyu":
		// 斗鱼「一起看」回放缓冲通常明显短于虎牙（虎牙实测 6.08s 才需 12s 窗口），
		// 指纹去重窗口收紧到 8s：既覆盖斗鱼回放，又更快遗忘陈旧指纹、
		// 减少误伤新帧与内存占用。其余沿用历史安全值。
		return flvSiteTuning{
			name:                 "douyu-yqk",
			replayWindowMs:       8000,
			replayGuardMaxFrames: flvProxyReplayGuardMaxFrames,
			spliceStepMs:         flvProxySpliceStepMs,
			pacerMaxDelay:        flvProxyPacerMaxDelay,
		}
	case "huya":
		return flvSiteTuning{
			name:                 "huya-yqk",
			replayWindowMs:       flvProxyReplayWindowMs,
			replayGuardMaxFrames: flvProxyReplayGuardMaxFrames,
			spliceStepMs:         flvProxySpliceStepMs,
			pacerMaxDelay:        flvProxyPacerMaxDelay,
		}
	default:
		// 其它平台（B站/抖音/快手等）沿用历史默认，行为不变。
		return flvSiteTuning{
			name:                 "default",
			replayWindowMs:       flvProxyReplayWindowMs,
			replayGuardMaxFrames: flvProxyReplayGuardMaxFrames,
			spliceStepMs:         flvProxySpliceStepMs,
			pacerMaxDelay:        flvProxyPacerMaxDelay,
		}
	}
}

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
	// 按平台分档的续流调参（斗鱼/虎牙「一起看」各自独立，避免共用一套参数）。
	tuning         flvSiteTuning
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

	// 音/视频各自一条输出时间轴：索引 0=音频(tag 8)、1=视频(tag 9)。
	// FLV 两轨时间戳本来就是交错的，用单一全局 lastTs 比较会把落后几十毫秒
	// 的那一轨整帧丢掉（「一起看」持续掉帧的根因之一）。
	tsMu      sync.Mutex
	haveOut   [2]bool
	lastOutTs [2]int64

	// 最近已转发帧的内容指纹，用于重连时识别并丢弃 CDN 带回的旧缓冲（回放）。
	// 只存指纹不存帧体，按输出时间戳淘汰窗口外记录。
	recentMu   sync.Mutex
	recent     map[uint64]int64
	recentList []flvReplayEntry

	statsMu           sync.Mutex
	startedAt         time.Time
	upstreamSessions  int64
	reconnects        int64
	candidateSwitches int64
	tagsWritten       int64
	bytesWritten      int64
	lastSessionMs     int64
	lastError         string
	// pacer 是否按墙钟平滑输出；离线测试可用 TVBOX_LIVE_PROXY_NO_PACER=1 关闭。
	pacerEnabled bool
	// 诊断字段（仅 TVBOX_LIVE_PROXY_DIAG=1 时输出）：每次重连的回溯量列表
	diagEnabled bool
	diagLastTs  []int64 // 最近每段起始的 lastTs（重连前）
	diagSrcTs   []int64 // 对应段的源首帧 sourceTs

	// 诊断：区分“重连后时间戳连续”还是“归零重置（新 FLV 段）”，这是决定去重/重绑策略的关键。
	segLastOutTsBefore int64
	segFirstSrcTs      int64
	segGap             int64
	segReset           bool
	lastSrcTs          [2]int64 // 音/视频各自最近一次转发的源时间戳
	videoBackwardInSeg int64    // 段内源时间戳大幅回退次数（=CDN 带回旧缓冲/重放）

	// 诊断计数器：区分视频帧与音频帧的转发/丢弃，便于发现“画面冻结但声音在播”。
	videoForwarded       int64
	audioForwarded       int64
	videoDroppedReplay   int64
	audioDroppedReplay   int64
	videoDroppedAwaitKey int64
	dropReplayActive     bool
	guardActiveNow       bool
	// 双轨时间戳过滤实际拦下的帧数、pacer 累计等待时长（现场定位掉帧用）。
	tsRejectedVideo      int64
	tsRejectedAudio      int64
	pacerWaitMs          int64
	lastVideoForwardedAt time.Time
	lastAudioForwardedAt time.Time
	recentEvents         []string
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
		tuning:         siteTuningFor(site),
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
	proxy.diagEnabled = os.Getenv("TVBOX_LIVE_PROXY_DIAG") != ""
	proxy.pacerEnabled = os.Getenv("TVBOX_LIVE_PROXY_NO_PACER") == ""
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
	// 连续短/空段计数：用于指数退避与触发候选切换，正常长度的段会清零。
	shortStreak := 0
	useInitial := p.initialURL != ""
	// CDN 关闭的是「本次连接」，播放地址与签名往往仍然有效。正常关流后先用同一地址
	// 立即续拉一次，避免每次重连都完整重跑平台解析（斗鱼要重新拉签名 JS、用 goja
	// 执行、逐个 CDN 取流，耗时数秒，播放器缓冲耗尽就表现为可见的断流重连）。
	// 只有快速续拉失败（签名过期 / 403）才回退到原来的重新解析路径。
	var fastReconnectCandidate *LiveStreamCandidate
	var fastReconnectHeaders map[string]string
	fastReconnectStreak := 0
	for p.hasClient() && p.ctx.Err() == nil {
		var candidate LiveStreamCandidate
		var headers map[string]string
		fastReconnect := false
		switch {
		case useInitial:
			candidate = LiveStreamCandidate{CDN: "本地首段", URL: p.initialURL, Protocol: "flv"}
			headers = p.initialHeaders
			useInitial = false
		case fastReconnectCandidate != nil && fastReconnectStreak < flvProxyMaxFastReconnectStreak:
			candidate = *fastReconnectCandidate
			headers = fastReconnectHeaders
			fastReconnectCandidate = nil
			fastReconnect = true
			fastReconnectStreak++
		default:
			// 走到重新解析（含连续快速续拉达到上限）时清零并丢弃旧候选，
			// 本轮一定使用重新解析出来的地址。
			fastReconnectStreak = 0
			fastReconnectCandidate = nil
			fastReconnectHeaders = nil
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

		beforeTags := p.tagCount()
		err := p.pull(candidate, headers)
		if p.ctx.Err() != nil {
			return
		}
		if err == nil {
			p.bumpReconnect()
			// 只有本段确实推送过数据才允许「同地址快速续拉」；空流立即 EOF 时
			// 必须重新解析，否则会在无效地址上空转。
			if p.tagCount() > beforeTags {
				fastReconnectCandidate = &candidate
				fastReconnectHeaders = headers
			}
			// 短/空段（上游很快关闭）：监控曾出现 3 分钟 48 次重连的风暴，
			// 根因是短段 EOF 立即无退避重连且不计入失败。这里加指数退避并视为
			// 当前线路不稳定，连续多次则切换候选，避免在坏线路上高速空转。
			if p.lastSessionMs < flvProxyShortSessionMs {
				shortStreak++
				backoff := flvProxyShortBackoffBase * time.Duration(1<<uint(shortStreak-1))
				if backoff > flvProxyShortBackoffMax {
					backoff = flvProxyShortBackoffMax
				}
				p.event(fmt.Sprintf("shortSession sessionMs=%d streak=%d backoff=%s",
					p.lastSessionMs, shortStreak, backoff))
				if shortStreak >= flvProxyMaxShortStreak {
					shortStreak = 0
					failures = 0
					candidateIndex++
					p.bumpCandidateSwitch(fmt.Errorf("短段频发已切换线路 sessionMs=%d", p.lastSessionMs))
				}
				select {
				case <-p.ctx.Done():
					return
				case <-time.After(backoff):
				}
				continue
			}
			shortStreak = 0
			continue
		}
		if fastReconnect {
			// 同地址快速续拉失败：签名过期或被 CDN 拒绝。不计入硬失败次数，
			// 直接进入下一轮重新解析拿新签名，避免误触发「切换候选线路」。
			fastReconnectStreak = 0
			select {
			case <-p.ctx.Done():
				return
			case <-time.After(flvProxyRetryDelay):
			}
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

	// 续段（已存在上一帧）需要丢弃回放：重连后 CDN 会带回上一段末尾的缓冲，
	// 而新流时间戳从 0 重启、代理又把输出时间戳强制顺延，时间轴上完全看不出回放，
	// 只能按「帧内容指纹」识别并丢弃已转发过的帧。
	// 真实「一起看」流是「先给一个全新关键帧，再跟 6 秒旧缓冲」：若一见到新关键帧
	// 就解除去重，旧缓冲会整段被转发。因此把「等未见过的首关键帧（解码前提）」与
	// 「指纹去重保护期」拆成两个独立状态，保护期内对每一帧都做指纹检查。
	isContinuation := p.hasLastTs()
	dropReplay := isContinuation
	guardActive := isContinuation
	guardFrames := 0

	var offset int64
	var haveBase bool
	// 被丢弃的回放帧在源时间轴上占了几毫秒：转发新内容时必须扣掉，
	// 否则输出时间轴会撕出与回放等长的空洞（播放器表现为卡住几秒）。
	var droppedSourceMs int64
	var prevSourceTs int64
	var havePrevSourceTs bool
	// pacer：按输出媒体时间平滑发送，避免上游突发把数秒数据瞬间灌给播放器。
	var pacerMediaStart int64
	var pacerWallStart time.Time
	var pacerStarted bool

	// 诊断：记录本段相对上一段的落差，判断重连后是“连续时间戳”还是“归零重置”。
	p.tsMu.Lock()
	lastOutBefore := p.maxOutTsLocked()
	p.tsMu.Unlock()
	p.statsMu.Lock()
	p.segLastOutTsBefore = lastOutBefore
	p.segFirstSrcTs = -1
	p.segGap = 0
	p.segReset = false
	p.dropReplayActive = dropReplay
	p.guardActiveNow = guardActive
	p.statsMu.Unlock()
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
		isSeq := tagType == flvTagTypeVideo && isAvcSequenceHeader(data)
		isKey := tagType == flvTagTypeVideo && isVideoKeyframe(data)
		fp := flvFrameFingerprint(tagType, data)
		replayed := guardActive && !isSeq && p.isReplayedFrame(fp)
		// 被丢弃帧所占的源时间要从后续输出里扣掉（音视频同 ts 时 delta=0，自然跳过）。
		if havePrevSourceTs && sourceTs > prevSourceTs && replayed {
			droppedSourceMs += sourceTs - prevSourceTs
		}
		prevSourceTs = sourceTs
		havePrevSourceTs = true

		// 1) 指纹去重贯穿整个续段：对每一帧（含音频、含新关键帧之后）都检查，
		//    命中即丢弃。AVC 序列头是解码参数、内容可能与上一段完全相同，单独放行。
		if guardActive && !isSeq {
			guardFrames++
			if replayed {
				p.statsMu.Lock()
				if tagType == flvTagTypeVideo {
					p.videoDroppedReplay++
				} else if tagType == flvTagTypeAudio {
					p.audioDroppedReplay++
				}
				p.statsMu.Unlock()
				continue
			}
			if guardFrames >= p.tuning.replayGuardMaxFrames {
				guardActive = false
				p.statsMu.Lock()
				p.guardActiveNow = false
				p.statsMu.Unlock()
				p.event(fmt.Sprintf("guardAutoReleased frame=%d", guardFrames))
			}
		}

		if dropReplay {
			// 2) AVC 序列头：编码参数变化时才转发一次（解码前提）。用受控时间戳，
			//    不让它成为 spliceBase 锚点，避免时间轴上撕出回放长度的空洞。
			if isSeq {
				ts := p.controlledTs()
				if !p.acceptTs(1, ts) { // AVC 序列头属于视频轨
					continue
				}
				if err := p.writeTag(tagHeader, data, ts); err != nil {
					return err
				}
				p.rememberFrame(fp, ts)
				p.incForwarded(tagType, sourceTs)
				continue
			}
			// 3) 未见过的视频帧仍须等关键帧才能独立解码。
			if tagType == flvTagTypeVideo && !isKey {
				p.statsMu.Lock()
				p.videoDroppedAwaitKey++
				p.statsMu.Unlock()
				continue
			}
			// 4) 未转发过的关键帧 = 真正追上直播线，解除回放丢弃。
			if isKey {
				dropReplay = false
				p.statsMu.Lock()
				p.dropReplayActive = false
				p.statsMu.Unlock()
				p.event("dropReplayCleared")
			}
		}

		adjusted := sourceTs
		if !haveBase {
			prefixLastTs := lastOutBefore
			adjusted = p.spliceBase(sourceTs)
			offset = adjusted - sourceTs
			haveBase = true
			gap := sourceTs - p.segLastOutTsBefore
			p.statsMu.Lock()
			if p.segFirstSrcTs < 0 {
				p.segFirstSrcTs = sourceTs
				p.segGap = gap
				// 源首帧比上一段输出小很多 => 时间戳归零重置（新 FLV 段）；
				// 否则视为连续时间戳（重连后续流，正常情况）。
				p.segReset = isContinuation && (gap < -1000)
			}
			p.statsMu.Unlock()
			if p.diagEnabled {
				p.statsMu.Lock()
				p.diagLastTs = append(p.diagLastTs, prefixLastTs)
				p.diagSrcTs = append(p.diagSrcTs, sourceTs)
				if len(p.diagLastTs) > 20 {
					p.diagLastTs = p.diagLastTs[len(p.diagLastTs)-20:]
					p.diagSrcTs = p.diagSrcTs[len(p.diagSrcTs)-20:]
				}
				p.statsMu.Unlock()
				fmt.Printf("[DIAG] session=%d lastTs=%d firstSourceTs=%d gap=%d reset=%v\n",
					p.upstreamSessions, prefixLastTs, sourceTs, gap, p.segReset)
			}
		} else {
			adjusted = sourceTs - droppedSourceMs + offset
		}
		// 丢弃整段回放后，源时间轴被压缩，扣除量可能与帧间隔有 1~2ms 误差；
		// 兜底保证输出严格不回退（acceptTs 只允许相等，这里保证至少 +1ms）。
		// 仅续段启用：首段没有「上一帧」，不能把首帧 0 抬成 1。
		if isContinuation {
			if minimum := p.controlledTs(); adjusted < minimum {
				adjusted = minimum
			}
		}
		track := -1
		switch tagType {
		case flvTagTypeAudio:
			track = 0
		case flvTagTypeVideo:
			track = 1
		}
		if !p.acceptTs(track, adjusted) {
			p.statsMu.Lock()
			if track == 1 {
				p.tsRejectedVideo++
			} else {
				p.tsRejectedAudio++
			}
			p.statsMu.Unlock()
			continue
		}
		// pacer：上游常以突发方式一次送来数秒数据，原样灌给播放器会造成
		// 快进/跳帧感。按输出媒体时间对齐墙钟，让下游始终以实时速度收到帧。
		// 额外叠加「目标缓冲」偏移：故意比实时慢约 flvProxyPacerTargetBufferMs 推送，
		// 让本地始终保有数秒已接收数据，上游抖动 / 重连空窗时播放器仍有缓冲可播。
		// 单帧等待超过 pacerMaxDelay 说明时间戳跳变/长空窗后追帧，重置基准而非空等。
		if p.pacerEnabled && p.ctx.Err() == nil {
			if !pacerStarted {
				pacerStarted = true
				pacerMediaStart = adjusted
				pacerWallStart = time.Now()
			}
		deadline := pacerWallStart.Add(
			time.Duration(adjusted-pacerMediaStart+flvProxyPacerTargetBufferMs.Load()) * time.Millisecond)
			if delay := time.Until(deadline); delay > p.tuning.pacerMaxDelay {
				pacerMediaStart, pacerWallStart = adjusted, time.Now()
				p.event("pacerRebased")
			} else if delay > 0 {
				timer := time.NewTimer(delay)
				select {
				case <-p.ctx.Done():
					timer.Stop()
					return newError("代理已关闭")
				case <-timer.C:
				}
				p.statsMu.Lock()
				p.pacerWaitMs += delay.Milliseconds()
				p.statsMu.Unlock()
			}
		}
		if err := p.writeTag(tagHeader, data, adjusted); err != nil {
			return err
		}
		p.rememberFrame(fp, adjusted)
		p.incForwarded(tagType, sourceTs)
	}
}

// hasLastTs 返回是否已记录上一帧时间戳（用于判断当前是否处于“续段”）。
func (p *flvProxy) hasLastTs() bool {
	p.tsMu.Lock()
	defer p.tsMu.Unlock()
	return p.haveOut[0] || p.haveOut[1]
}

// maxOutTsLocked 返回两条轨道里最近一次输出的最大时间戳；调用方须持有 tsMu。
func (p *flvProxy) maxOutTsLocked() int64 {
	max := int64(-1)
	for i := 0; i < 2; i++ {
		if p.haveOut[i] && p.lastOutTs[i] > max {
			max = p.lastOutTs[i]
		}
	}
	return max
}

// flvReplayEntry 记录一帧的指纹与输出时间戳，用于按窗口淘汰。
type flvReplayEntry struct {
	fp uint64
	ts int64
}

// flvFrameFingerprint 用 tag 类型 + 帧体做 FNV-1a 指纹；tag 类型参与哈希，
// 避免音视频帧恰好同内容时互相误判。
func flvFrameFingerprint(tagType byte, data []byte) uint64 {
	h := fnv.New64a()
	var typeByte [1]byte
	typeByte[0] = tagType
	_, _ = h.Write(typeByte[:])
	_, _ = h.Write(data)
	return h.Sum64()
}

// rememberFrame 登记一帧已转发，并淘汰回放窗口外的旧记录。
func (p *flvProxy) rememberFrame(fp uint64, ts int64) {
	p.recentMu.Lock()
	defer p.recentMu.Unlock()
	if p.recent == nil {
		p.recent = make(map[uint64]int64)
	}
	p.recent[fp] = ts
	p.recentList = append(p.recentList, flvReplayEntry{fp: fp, ts: ts})
	cutoff := ts - p.tuning.replayWindowMs
	for len(p.recentList) > 0 && p.recentList[0].ts < cutoff {
		entry := p.recentList[0]
		// 同一指纹若在窗口内被更新过，保留较新的那条记录。
		if p.recent[entry.fp] <= entry.ts {
			delete(p.recent, entry.fp)
		}
		p.recentList = p.recentList[1:]
	}
}

// isReplayedFrame 判断该帧是否已在窗口内转发过（即 CDN 重连带回的旧缓冲）。
func (p *flvProxy) isReplayedFrame(fp uint64) bool {
	p.recentMu.Lock()
	defer p.recentMu.Unlock()
	_, ok := p.recent[fp]
	return ok
}

// controlledTs 给「重连后编码参数变化的 AVC 序列头」一个受控时间戳：
// 不能让它成为 spliceBase 锚点，否则被丢弃的回放长度会撕成时间轴上的空洞。
func (p *flvProxy) controlledTs() int64 {
	p.tsMu.Lock()
	defer p.tsMu.Unlock()
	return p.maxOutTsLocked() + 1
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
// 用两轨中的最大值做锚点，避免音视频交错时把落后的一轨抬到未来时刻。
func (p *flvProxy) spliceBase(sourceTs int64) int64 {
	p.tsMu.Lock()
	defer p.tsMu.Unlock()
	if !p.haveOut[0] && !p.haveOut[1] {
		return sourceTs
	}
	return p.maxOutTsLocked() + p.tuning.spliceStepMs
}

// acceptTs 只在**同一轨道内部**拒绝时间戳回退，允许相等时间戳。
// track: 0=音频、1=视频、-1=script/其它（不参与时间轴）。
func (p *flvProxy) acceptTs(track int, ts int64) bool {
	p.tsMu.Lock()
	defer p.tsMu.Unlock()
	if track < 0 || track > 1 {
		return true
	}
	if p.haveOut[track] && ts < p.lastOutTs[track] {
		return false
	}
	p.lastOutTs[track] = ts
	p.haveOut[track] = true
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

// incForwarded 记录一次成功转发，并维护各轨道最近源时间戳用于诊断段内回退。
func (p *flvProxy) incForwarded(tagType byte, sourceTs int64) {
	p.statsMu.Lock()
	defer p.statsMu.Unlock()
	track := int(tagType) - 8
	if track >= 0 && track < 2 {
		// 段内源时间戳大幅回退（>1s）= CDN 带回旧缓冲/重放，记录下来。
		if p.lastSrcTs[track] != 0 && sourceTs < p.lastSrcTs[track]-1000 {
			p.videoBackwardInSeg++
		}
		p.lastSrcTs[track] = sourceTs
	}
	switch tagType {
	case flvTagTypeVideo:
		p.videoForwarded++
		p.lastVideoForwardedAt = time.Now()
	case flvTagTypeAudio:
		p.audioForwarded++
		p.lastAudioForwardedAt = time.Now()
	}
}

// event 记录一条诊断事件到环形缓冲（最近 60 条），供 Kotlin 侧周期性打印。
func (p *flvProxy) event(msg string) {
	p.statsMu.Lock()
	defer p.statsMu.Unlock()
	p.recentEvents = append(p.recentEvents, fmt.Sprintf("%d %s", time.Now().UnixMilli(), msg))
	if len(p.recentEvents) > 60 {
		p.recentEvents = p.recentEvents[len(p.recentEvents)-60:]
	}
}

// tagCount 返回已写给播放器的标签总数，用于判断一段上游是否真的推过数据。
func (p *flvProxy) tagCount() int64 {
	p.statsMu.Lock()
	defer p.statsMu.Unlock()
	return p.tagsWritten
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
		"url":                  p.url,
		"client":               p.hasClient(),
		"site":                 p.site,
		"tuning":               p.tuning.name,
		"uptimeSec":            int64(time.Since(p.startedAt).Seconds()),
		"upstreamSessions":     p.upstreamSessions,
		"reconnects":           p.reconnects,
		"candidateSwitches":    p.candidateSwitches,
		"tagsWritten":          p.tagsWritten,
		"bytesWritten":         p.bytesWritten,
		"lastSessionMs":        p.lastSessionMs,
		"lastError":            p.lastError,
		"videoForwarded":       p.videoForwarded,
		"audioForwarded":       p.audioForwarded,
		"videoDroppedReplay":   p.videoDroppedReplay,
		"audioDroppedReplay":   p.audioDroppedReplay,
		"videoDroppedAwaitKey": p.videoDroppedAwaitKey,
		"videoBackwardInSeg":   p.videoBackwardInSeg,
		// 双轨时间戳过滤拦下的帧数：旧版全局时间戳会在这里大量丢帧。
		"tsRejectedVideo": p.tsRejectedVideo,
		"tsRejectedAudio": p.tsRejectedAudio,
		// pacer 累计等待时长，持续增长说明上游突发已被平滑。
		"pacerWaitMs":      p.pacerWaitMs,
		"dropReplayActive": p.dropReplayActive,
		"guardActiveNow":   p.guardActiveNow,
		"segGap":           p.segGap,
		"segReset":         p.segReset,
		"lastVideoForwardedAgoMs": func() int64 {
			if p.lastVideoForwardedAt.IsZero() {
				return -1
			}
			return time.Since(p.lastVideoForwardedAt).Milliseconds()
		}(),
		"lastAudioForwardedAgoMs": func() int64 {
			if p.lastAudioForwardedAt.IsZero() {
				return -1
			}
			return time.Since(p.lastAudioForwardedAt).Milliseconds()
		}(),
	}
	if len(p.recentEvents) > 0 {
		// recentEvents 复制到快照，避免外部修改底层切片。
		ev := make([]string, len(p.recentEvents))
		copy(ev, p.recentEvents)
		payload["recentEvents"] = ev
	}
	if p.diagEnabled && len(p.diagLastTs) > 0 {
		// 汇总最近20次重连的回溯量分布
		type entry struct{ lastTs, srcTs int64 }
		var entries []entry
		n := len(p.diagLastTs)
		for i := 0; i < n; i++ {
			idx := len(p.diagLastTs) - n + i
			entries = append(entries, entry{p.diagLastTs[idx], p.diagSrcTs[idx]})
		}
		payload["diagReconnects"] = entries
	}
	p.statsMu.Unlock()
	data, err := json.Marshal(payload)
	if err != nil {
		return "{}"
	}
	return string(data)
}
