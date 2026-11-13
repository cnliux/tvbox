package platformlive

// 本机 FLV 代理的传输层：上游空闲读超时、下游客户端接管、解码配置标签缓存。
//
// 这一层刻意与「时间轴 / 去重 / 平台分档」解耦：下面所有逻辑对所有平台一致生效，
// 不含任何虎牙 / 斗鱼 / 通用档的专属假设。

import (
	"context"
	"errors"
	"net"
	"net/http"
	"sync"
	"time"
)

const (
	// 上游每次 Read 的空闲超时：CDN 半开连接（TCP 还在、但不再吐数据）时，
	// 代理必须自行断开重连，否则 consume 会永久阻塞、播放器画面冻结。
	// 参考 livepurer 的 idleReadConn=15s：直播长连接不能设「整体超时」，
	// 只能设「连续无数据」超时，正常的会话可以持续数小时。
	flvProxyIdleReadTimeout = 15 * time.Second
	// 缓存给新客户端补发的解码配置标签上限（onMetaData / AVC seq / AAC seq）。
	flvProxyMaxConfigTagBytes = 512 << 10
	flvTagTypeScriptData      = 18
	flvAudioCodecAAC          = 10
)

// idleReadConn 在每次 Read 前刷新读截止时刻，实现「空闲超时」而非「总超时」。
// http.ResponseHeaderTimeout 只覆盖响应头，管不到 FLV 流中途的静默。
type idleReadConn struct {
	net.Conn
	idle time.Duration
}

func (c *idleReadConn) Read(buffer []byte) (int, error) {
	_ = c.Conn.SetReadDeadline(time.Now().Add(c.idle))
	return c.Conn.Read(buffer)
}

func dialWithIdleReadTimeout(idle time.Duration) func(context.Context, string, string) (net.Conn, error) {
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	return func(ctx context.Context, network, address string) (net.Conn, error) {
		conn, err := dialer.DialContext(ctx, network, address)
		if err != nil {
			return nil, err
		}
		return &idleReadConn{Conn: conn, idle: idle}, nil
	}
}

// isNetworkTimeout 判断错误是否来自读截止时刻（半开连接），与真正的 CDN 故障区分开。
func isNetworkTimeout(err error) bool {
	var netErr net.Error
	return errors.As(err, &netErr) && netErr.Timeout()
}

// errUpstreamIdle 是「上游静默」的软错误：不记入线路硬失败、不触发换线。
var errUpstreamIdle = errors.New("upstream idle read timeout")

// flvProxyClient 代表一个下游播放器连接。gen 用于「新连接接管」时辨认旧连接，
// done 被关闭后旧 ServeHTTP 立即返回，net/http 随即结束旧响应，避免两个连接
// 同时争抢同一个 writer（旧实现会让旧连接饿死、新连接缺解码参数而黑屏）。
type flvProxyClient struct {
	gen     uint64
	writer  http.ResponseWriter
	flusher http.Flusher
	done    chan struct{}
	once    sync.Once
}

func (c *flvProxyClient) retire() {
	c.once.Do(func() { close(c.done) })
}

// flvProxyFileHeaderWithFlags 生成 FLV 文件头 + PreviousTagSize0。
// flags: 0x01 仅视频、0x04 仅音频、0x05 音视频；按已观察到的轨道动态给出，
// 避免纯音频/纯视频流被播放器按「音视频都有」初始化。
func flvProxyFileHeaderWithFlags(flags byte) []byte {
	header := []byte{'F', 'L', 'V', 0x01, flags, 0x00, 0x00, 0x00, 0x09}
	return append(header, 0x00, 0x00, 0x00, 0x00)
}

func flvProxyFileHeader() []byte {
	// 首帧之前还不知道有几条轨道，保守按音视频都有；重连时改用 currentHeaderFlags()。
	return flvProxyFileHeaderWithFlags(0x05)
}

// currentHeaderFlags 按已实际转发的轨道推导 FLV header flags。
func (p *flvProxy) currentHeaderFlags() byte {
	p.tsMu.Lock()
	defer p.tsMu.Unlock()
	switch {
	case p.haveOut[0] && p.haveOut[1]:
		return 0x05
	case p.haveOut[0]:
		return 0x04
	case p.haveOut[1]:
		return 0x01
	default:
		return 0x05
	}
}

func isAacSequenceHeader(data []byte) bool {
	if len(data) < 2 {
		return false
	}
	return (data[0] >> 4) == flvAudioCodecAAC && data[1] == 0
}

// isConfigTag 判断一个标签是否是新播放器连接解码前必须拿到的配置标签：
// onMetaData(script) / AVC sequence header / AAC sequence header。
// 这里缓存并补发的是**上游真实标签**，不伪造 AMF 元数据。
func isConfigTag(tagType byte, data []byte) bool {
	switch tagType {
	case flvTagTypeScriptData:
		return true
	case flvTagTypeVideo:
		return isAvcSequenceHeader(data)
	case flvTagTypeAudio:
		return isAacSequenceHeader(data)
	default:
		return false
	}
}

// configTagKey 按标签类别去重：同类配置只保留最新一份。
func configTagKey(tagType byte) byte { return tagType }

// rememberConfigTag 在标签成功写给下游后缓存一份（带当前输出时间戳）。
func (p *flvProxy) rememberConfigTag(frame []byte) {
	tagType := frame[0] & 0x1f
	if !isConfigTag(tagType, frame[flvProxyTagHeaderSize:]) || len(frame) > flvProxyMaxConfigTagBytes {
		return
	}
	snapshot := append([]byte(nil), frame...)
	p.configMu.Lock()
	if p.configTags == nil {
		p.configTags = make(map[byte][]byte)
	}
	p.configTags[configTagKey(tagType)] = snapshot
	p.configMu.Unlock()
}

// initialClientPayload 生成新客户端的起步字节：FLV 头 + 已缓存的配置标签，
// 全部按当前输出时间轴重写时间戳，保证接管后立刻可解码、且时间轴不回退。
func (p *flvProxy) initialClientPayload() []byte {
	payload := flvProxyFileHeaderWithFlags(p.currentHeaderFlags())
	p.tsMu.Lock()
	ts := p.maxOutTsLocked()
	p.tsMu.Unlock()
	if ts < 0 {
		ts = 0
	}
	p.configMu.Lock()
	keys := []byte{flvTagTypeScriptData, flvTagTypeVideo, flvTagTypeAudio}
	for _, key := range keys {
		frame, ok := p.configTags[key]
		if !ok || len(frame) < flvProxyTagHeaderSize+flvProxyPrevTagSizeLen {
			continue
		}
		rewritten := append([]byte(nil), frame...)
		rewritten[4] = byte(ts >> 16)
		rewritten[5] = byte(ts >> 8)
		rewritten[6] = byte(ts)
		rewritten[7] = byte(ts >> 24)
		payload = append(payload, rewritten...)
	}
	p.configMu.Unlock()
	return payload
}
