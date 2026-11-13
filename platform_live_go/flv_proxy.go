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
	"net/url"
	"os"
	"strconv"
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
)

// pacer 目标缓冲的默认值、运行时配置与每个代理实例的状态见 flv_proxy_pacer.go；
// 传输层（空闲读超时 / 客户端接管 / 配置标签）见 flv_proxy_transport.go；
// 计划内预建双连接见 flv_proxy_handover.go。

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
	// 短/空段退避与换线：监控曾出现 3 分钟 48 次重连的风暴，根因是短段 EOF
	// 立即无退避重连且不计入失败，同一条坏线路被高速反复重连。
	// 注意：重连策略已按平台分档进 flvSiteTuning（见下方 huya* 常量与
	// siteTuningFor），这里的历史常量仅作为虎牙/默认档位的取值来源。
	huyaMaxFastReconnectStreak = 3
	huyaShortSessionMs         = int64(5000)
	huyaMaxShortStreak         = 4
	huyaShortBackoffBase       = 500 * time.Millisecond
	huyaShortBackoffMax        = 5 * time.Second
)

// ───────────── 通用档：其它平台（B站 / 抖音 / 快手等）的独立中性策略 ─────────────
//
// 用户明确要求：其它平台既不能用虎牙的方式，也不能用斗鱼的方式，否则容易出错。
// 虎牙的 12s 去重窗口来自「回放缓冲 6.08s」实测、归零时间轴来自虎牙 CDN 语义；
// 斗鱼的 8s 窗口 / 连续时间轴 / URL 租约补丁来自斗鱼 CDN 特性。把任何一方的
// 站点专属假设套到第三个平台，都会误判回放、错误平移时间戳或改坏播放地址。
//
// 因此通用档：
//   - 常量全部独立命名、独立取值，**不引用任何 huya* / douyu* 或按虎牙实测调出的
//     flvProxyReplayWindowMs 等历史常量**；
//   - 时间轴不预设「归零」也不预设「同轴」，按新段实际首帧自适应（timelineAuto）；
//   - 不改写 URL、不解析 expire 租约、不启用任何平台专属补丁；
//   - 快速续拉取独立的中性次数，短段只温和退避、绝不换线；
//   - 回放去重后的时间轴压缩是 FLV 通用正确性保证（避免空洞），
//     但不用虎牙的全局钳制，也不用斗鱼/虎牙的首帧对齐假设。
const (
	// 去重窗口取一个足够覆盖常见 2~5 秒回放缓冲的中性值，刻意不等于虎牙 12s / 斗鱼 8s。
	genericReplayWindowMs       = int64(10000)
	genericReplayGuardMaxFrames = 5400 // 通用防失控上限（约 180s @30fps），与虎牙实测无关
	genericSpliceStepMs         = int64(33)
	genericPacerMaxDelay        = 6 * time.Second
	// 同地址快速续拉 2 次（虎牙 3 次、斗鱼 1 次都是各自 CDN 的实测值，通用档取
	// 独立的中性次数）。保留该机制是 FLV 代理的通用正确性前提：正常关流后
	// 立即复用地址续拉，避免每次续流都重跑平台解析导致播放器缓冲耗尽。
	// 超过次数或遇到 403/签名过期则重新解析，不会在旧地址上空转。
	genericMaxFastReconnectStreak = 2
	genericShortSessionMs         = int64(3000)
	// -1 = 显式禁用「短段频发换线」（虎牙 4 次换线、斗鱼 0 次禁用都属平台专属策略，
	// 通用档用独立的取值与语义：没有任何平台实测依据，就不主动切线路）。
	genericMaxShortStreak   = -1
	genericShortBackoffBase = 1 * time.Second
	genericShortBackoffMax  = 4 * time.Second
	// 自适应时间轴容差上限：新段首帧比已交付位置落后不超过该值仍视为同轴
	// （两轨交错/抖动），只有明显回退（典型的时间戳归零重启）才平移续接。
	// 实际容差还会被「已交付位置的一半」约束，见 genericTimelineTolerance。
	genericTimelineResetToleranceMs = int64(1000)
)

// ───────────────────── 通用档常量结束 ─────────────────────

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
	// —— 重连策略（按平台分档的核心差异）——
	// 同地址快速续拉最大连续次数：EOF 后复用上次地址直接续拉，省去一次完整解析。
	// 0 = 完全关闭，EOF 后一律重新解析拿新签名。
	maxFastReconnectStreak int
	// 会话短于该毫秒数视为「短段」（上游很快 EOF）。
	shortSessionMs int64
	// 连续短段达到该次数才切换候选线路；<=0 表示**永不**因短段自动换线，
	// 短段只做退避 + 重新解析，信任当前线路（适合 CDN 稳定的平台）。
	maxShortStreak int
	// 短段指数退避的起始值与上限。
	shortBackoffBase time.Duration
	shortBackoffMax  time.Duration
	// —— 时间轴语义：三档互斥，其它平台绝不套用虎牙/斗鱼的假设 ——
	// timelineContinuous=false && timelineAuto=false（虎牙）：新一段源时间戳从 0
	//   重启，必须平移接续上一段输出（spliceBase = maxOutTs + step，历史行为，逐字保留）。
	// timelineContinuous=true（斗鱼）：同一房间两次连接共享同一时间轴（pure_live 实测），
	//   新连接时间戳天然连续，直接沿用源时间戳，不做 spliceBase 平移；
	//   仅当与已交付位置差超过 60s 对齐窗口（另一条时间轴）才平移续接。
	// timelineAuto=true（通用档）：不预设「归零」也不预设「同轴」，按新段首帧与
	//   已交付位置的实际关系自适应决定；既不无条件平移，也不按固定窗口强行同轨。
	timelineContinuous bool
	timelineAuto       bool
	// —— URL 租约（斗鱼专属）——
	// true（斗鱼）：解析出的播放地址可能带 expire=N（N 秒后 CDN 主动断流）。
	//   代理要①给 ws 短租线打 expire=0 补丁；②在租约到期前主动断开重解析，
	//   变「被动挨杀」为「计划内切换」。
	leaseAware bool
	// —— 计划内预建双连接（虎牙专属）——
	// plannedRefreshLeadMs>0 时，在上一段预期关流前这么久预解析并预建第二条
	// 连接，缓冲到首个关键帧后在当前段的关键帧处交接，消除解析/握手空窗。
	// nominalSessionMs 是首次会话的预期时长；之后按上一段实测时长学习。
	// 斗鱼（租约语义）与通用档（不预设周期）均为 0，绝不借用虎牙策略。
	plannedRefreshLeadMs int64
	nominalSessionMs     int64
}

// timelineModeName 返回档位的时间轴语义，供 statsJSON / 真机监控区分三档。
func (t flvSiteTuning) timelineModeName() string {
	switch {
	case t.timelineContinuous:
		return "continuous" // 斗鱼：同轴
	case t.timelineAuto:
		return "auto" // 通用档：按实际首帧自适应
	default:
		return "reset" // 虎牙：归零重启 + spliceBase 平移
	}
}

// siteTuningFor 返回**三档互斥**的调参：虎牙、斗鱼、其它平台（通用中性档）。
// 只有精确匹配 douyu / huya 才使用各自的站点专属策略；其余一律走通用档，
// 绝不把任一平台的实测假设或专属逻辑借给第三个平台。
func siteTuningFor(site string) flvSiteTuning {
	switch strings.ToLower(strings.TrimSpace(site)) {
	case "douyu":
		// 斗鱼「一起看」：CDN 相对稳定、回放缓冲更短，与虎牙走完全不同的重连思路——
		// 不做「快速续拉 ×3 + 短段频发就换线」的激进策略：
		// - EOF 后最多同地址续拉 1 次，随后一律重新解析拿新签名（斗鱼签名按会话发放）；
		// - 短段只做耐心退避（2s 起步、上限 8s），**永不因短段自动换线**，
		//   信任当前 CDN 线路；真正坏掉的线路由硬失败路径（HTTP 错误）换线兜底。
		return flvSiteTuning{
			name:                   "douyu-yqk",
			replayWindowMs:         8000,
			replayGuardMaxFrames:   flvProxyReplayGuardMaxFrames,
			spliceStepMs:           flvProxySpliceStepMs,
			pacerMaxDelay:          flvProxyPacerMaxDelay,
			maxFastReconnectStreak: 1,
			shortSessionMs:         4000,
			maxShortStreak:         0,
			shortBackoffBase:       2 * time.Second,
			shortBackoffMax:        8 * time.Second,
			timelineContinuous:     true,
			timelineAuto:           false,
			leaseAware:             true,
			plannedRefreshLeadMs:   0,
			nominalSessionMs:       0,
		}
	case "huya":
		return flvSiteTuning{
			name:                   "huya-yqk",
			replayWindowMs:         flvProxyReplayWindowMs,
			replayGuardMaxFrames:   flvProxyReplayGuardMaxFrames,
			spliceStepMs:           flvProxySpliceStepMs,
			pacerMaxDelay:          flvProxyPacerMaxDelay,
			maxFastReconnectStreak: huyaMaxFastReconnectStreak,
			shortSessionMs:         huyaShortSessionMs,
			maxShortStreak:         huyaMaxShortStreak,
			shortBackoffBase:       huyaShortBackoffBase,
			shortBackoffMax:        huyaShortBackoffMax,
			timelineContinuous:     false,
			timelineAuto:           false,
			leaseAware:             false,
			plannedRefreshLeadMs:   huyaPlannedRefreshLeadMs,
			nominalSessionMs:       huyaNominalSessionMs,
		}
	default:
		// 其它平台（B站 / 抖音 / 快手 / 自定义源等）：**通用中性档**。
		// 不引用任何 huya* / douyu* / 按虎牙实测调出的历史常量，也不启用
		// 归零平移、连续时间轴、URL 租约补丁等站点专属逻辑。
		return flvSiteTuning{
			name:                   "generic-live",
			replayWindowMs:         genericReplayWindowMs,
			replayGuardMaxFrames:   genericReplayGuardMaxFrames,
			spliceStepMs:           genericSpliceStepMs,
			pacerMaxDelay:          genericPacerMaxDelay,
			maxFastReconnectStreak: genericMaxFastReconnectStreak,
			shortSessionMs:         genericShortSessionMs,
			maxShortStreak:         genericMaxShortStreak,
			shortBackoffBase:       genericShortBackoffBase,
			shortBackoffMax:        genericShortBackoffMax,
			timelineContinuous:     false,
			timelineAuto:           true,
			leaseAware:             false,
		}
	}
}

// ───────────────────── 斗鱼专属：URL 租约与连续时间轴（勿动虎牙路径） ─────────────────────
//
// pure_live（github.com/liuchuancong/pure_live）实测结论：
//  1. 斗鱼匿名原画地址带 expire=300 —— CDN 在地址签发 300 秒后主动断流；
//     部分匿名/海外会话拿到的是 5 分钟即失效的 ws 线（expire=300&fcdn=ws），
//     追加 &expire=0 可让 CDN 按默认时长签发（simple_live 同款补丁）。
//  2. 同一斗鱼房间的两次上游连接共享同一条流时间戳时间轴，切换时在关键帧处
//     直接衔接即可；只有对齐窗口之外（另一条时间轴）才需要平移续接。
//
// 由此斗鱼的自动重连根因是「租约到期被动挨杀」：代理在 300 秒时被 CDN 强断，
// 再花数秒重新解析，播放器表现为周期性可见重连。对策是给 ws 短租线打补丁、
// 并在租约到期前主动断开重解析（计划内切换）。

const (
	// 斗鱼连续时间轴的对齐窗口：新段首帧与已交付位置相差不超过该值视为同轴。
	douyuTimelineAlignWindowMs = int64(60000)
	// 斗鱼租约提前续期提前量：在 CDN 断流前这么多毫秒主动重连。
	douyuLeaseRefreshLeadMs = int64(45000)
)

// parseURLExpireSec 解析播放地址 query 里的 expire=N（秒）。无或非法返回 0。
func parseURLExpireSec(rawURL string) int64 {
	uri, err := url.Parse(rawURL)
	if err != nil {
		return 0
	}
	value, err := strconv.ParseInt(uri.Query().Get("expire"), 10, 64)
	if err != nil || value <= 0 {
		return 0
	}
	return value
}

// patchDouyuURL 给斗鱼 ws 短租线追加 &expire=0（simple_live 同款补丁）：
// expire=300&fcdn=ws 的地址 5 分钟即失效，改为 expire=0 让 CDN 按默认时长签发。
// 其它地址原样返回，绝不修改。
func patchDouyuURL(rawURL string) string {
	uri, err := url.Parse(rawURL)
	if err != nil {
		return rawURL
	}
	query := uri.Query()
	if query.Get("expire") != "300" || query.Get("fcdn") != "ws" {
		return rawURL
	}
	query.Set("expire", "0")
	uri.RawQuery = query.Encode()
	return uri.String()
}

// douyuContinuityOffset 计算斗鱼新一段相对已交付位置的拼接偏移：
//   - 已无输出（首段）：偏移 0，完全沿用源时间戳；
//   - 新段首帧与已交付位置差在对齐窗口内：同一条时间轴，偏移 0 直接衔接；
//   - 差在窗口之外（另一条时间轴/长空窗）：平移到紧接已交付位置之后，
//     输出时间轴无缝续接（pure_live 语义：last+1-source）。
func douyuContinuityOffset(lastOutBefore, sourceTs int64) int64 {
	if lastOutBefore <= 0 {
		return 0
	}
	gap := sourceTs - lastOutBefore
	if gap >= -douyuTimelineAlignWindowMs && gap <= douyuTimelineAlignWindowMs {
		return 0
	}
	return lastOutBefore + 1 - sourceTs
}

// douyuLeaseDeadline 按地址租约计算「主动续期时刻」：
// 租约以地址签发时刻为锚，代理只知道自己开始拉流的时刻——解析发生在拉流前
// 数秒内，以拉流时刻近似签发时刻（提前量 45s 足以覆盖该误差）。
// 无租约语义的地址返回零值（不主动断开，交给被动 EOF 路径）。
func douyuLeaseDeadline(rawURL string, now time.Time) time.Time {
	expire := parseURLExpireSec(rawURL)
	if expire <= 0 {
		return time.Time{}
	}
	deadline := now.Add(time.Duration(expire*1000-douyuLeaseRefreshLeadMs) * time.Millisecond)
	if !deadline.After(now) {
		return time.Time{}
	}
	return deadline
}

// ───────────────────── 斗鱼专属辅助结束 ─────────────────────

// ───────────────────── 通用档：自适应时间轴（不预设平台语义） ─────────────────────
//
// 虎牙假设「新段时间戳必然归零」所以无条件平移；斗鱼假设「新段必然同轴」所以在
// 60s 窗口内一律不平移。两者的假设都只对各自平台成立。通用档不猜，只看数据：
//   - 首段（还没有已交付帧）：原样沿用源时间戳；
//   - 新段首帧在已交付位置附近或更靠后：视为同轴/连续，偏移 0 直接衔接
//     （避免虎牙式无条件平移把音视频相对位置拉开）；
//   - 新段首帧明显回退（超过容差，典型的时间戳归零重启）：平移到已交付位置
//     之后续接（避免斗鱼式固定窗口把归零段当成同轴、导致整段被 acceptTs 丢弃）。
//
// genericTimelineTolerance 返回「仍视为同轴」的最大回退量 = min(1000ms, lastOut/2)。
// 若直接用固定 1000ms，流刚开始时（已交付 ts=66）新段归零到 0 会落在
// 66-1000 之内、被误判为同轴，整段因时间戳回退被 acceptTs 丢弃。
// 按已交付位置的一半收窄，可在任意时刻正确识别归零重启。
func genericTimelineTolerance(lastOutBefore int64) int64 {
	if lastOutBefore <= 0 {
		return 0
	}
	half := lastOutBefore / 2
	if half < genericTimelineResetToleranceMs {
		return half
	}
	return genericTimelineResetToleranceMs
}

func genericTimelineOffset(lastOutBefore, sourceTs, stepMs int64) int64 {
	if lastOutBefore <= 0 {
		return 0
	}
	if sourceTs >= lastOutBefore-genericTimelineTolerance(lastOutBefore) {
		return 0
	}
	return lastOutBefore + stepMs - sourceTs
}

// ───────────────────── 通用档辅助结束 ─────────────────────

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
	service *Service
	site    string
	// 三档互斥的续流调参：虎牙 / 斗鱼 / 其它平台（通用中性档）。
	// 只有斗鱼与虎牙使用各自站点专属逻辑，其余平台一律不借用。
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

	// 下游播放器连接。新连接会「接管」写端并让旧连接退出，避免两个连接
	// 同时抢同一个 writer（旧实现会让旧连接饿死、新连接因缺解码参数黑屏）。
	mu         sync.Mutex
	client     *flvProxyClient
	clientSeq  atomic.Uint64
	writeMu    sync.Mutex
	configMu   sync.Mutex
	configTags map[byte][]byte

	clientReady chan struct{}

	// pacer 目标缓冲为「每个代理一份」，运行时调整写回全部在播实例。
	pacerTargetMs atomic.Int64
	pacerRebase   atomic.Bool
	// pacer 的墙钟/媒体基准必须跨上游会话保持：若每段 consume 都重新做一次
	// 目标缓冲预填，计划内交接（虎牙约每 2 分钟一次）就会周期性把数秒数据
	// 突发灌给播放器，正是「还剩一点点卡顿」的来源。
	pacer flvPacer

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
	plannedHandovers  int64
	idleTimeouts      int64
	clientTakeovers   int64
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
	// 交接后积压追帧 / 时间戳跳变导致 pacer 重新对齐的次数（真机定位卡顿用）。
	pacerRebases int64
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
	tuning := siteTuningFor(site)
	// 只有启用租约语义的档位（当前仅斗鱼）才改写首段地址：ws 短租线打
	// expire=0 补丁，避免 5 分钟租约到期后被 CDN 强杀。虎牙与通用档的
	// 地址一律原样保留，绝不套用斗鱼的 URL 补丁。
	if tuning.leaseAware {
		initialURL = patchDouyuURL(Text(initialURL, ""))
	}
	proxy := &flvProxy{
		service:        service,
		site:           site,
		tuning:         tuning,
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
				// 每次 Read 前刷新 15s 截止时刻：CDN 半开连接时自行断开重连，
				// 而不是让 consume 永久卡在 io.ReadFull（画面冻结且无兜底）。
				DialContext:     dialWithIdleReadTimeout(flvProxyIdleReadTimeout),
				DisableKeepAlives: true,
			},
		},
	}
	proxy.pacerTargetMs.Store(GetPacerTargetBufferMs())
	registerFlvProxy(proxy)
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
	unregisterFlvProxy(p)
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
	w.Header().Set("Content-Type", "video/x-flv")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Connection", "close")
	// 直播流不可 seek：明确告知，避免播放器发起 Range 后误判为可回退内容。
	w.Header().Set("Accept-Ranges", "none")
	if r.Method == http.MethodHead {
		// 探测请求只回头部，不注册客户端、不占用写端。
		w.WriteHeader(http.StatusOK)
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.WriteHeader(http.StatusOK)

	client := &flvProxyClient{
		gen:     p.clientSeq.Add(1),
		writer:  w,
		flusher: flusher,
		done:    make(chan struct{}),
	}
	p.mu.Lock()
	previous := p.client
	p.client = client
	p.mu.Unlock()
	if previous != nil {
		// 新连接接管：让旧 ServeHTTP 立刻返回，net/http 结束旧响应。
		previous.retire()
		p.statsMu.Lock()
		p.clientTakeovers++
		p.statsMu.Unlock()
		p.event("clientTakeover")
	}
	// 起步字节 = FLV 头 + 已缓存的真实 onMetaData / AVC / AAC 配置标签。
	// 这样解码器切换或播放器重连后不必等下一个 GOP 就能出画面。
	if err := p.writeToClient(client, p.initialClientPayload()); err != nil {
		p.dropClientIf(client)
		return
	}
	select {
	case p.clientReady <- struct{}{}:
	default:
	}

	select {
	case <-r.Context().Done():
	case <-client.done:
	}
	p.mu.Lock()
	if p.client == client {
		p.client = nil
	}
	p.mu.Unlock()
}

// writeToClient 合并为一次 Write + 一次 Flush，并由 writeMu 串行化，
// 避免接管瞬间两个 goroutine 交叉写坏 HTTP chunk 流。
func (p *flvProxy) writeToClient(client *flvProxyClient, payload []byte) error {
	p.writeMu.Lock()
	defer p.writeMu.Unlock()
	if _, err := client.writer.Write(payload); err != nil {
		return err
	}
	client.flusher.Flush()
	return nil
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
	// 计划内预建的第二条连接：一旦就绪，下一段直接消费它，不再经过解析/握手。
	var preparedNext *preparedStream
	for p.hasClient() && p.ctx.Err() == nil {
		var candidate LiveStreamCandidate
		var headers map[string]string
		var opts *pullOptions
		var preparedCh chan *preparedStream
		var stopPrepare context.CancelFunc
		fastReconnect := false
		leaseDeadline := time.Time{}

		if preparedNext != nil {
			opts = &pullOptions{usePrepared: preparedNext}
			preparedNext = nil
		} else {
			switch {
			case useInitial:
				candidate = LiveStreamCandidate{CDN: "本地首段", URL: p.initialURL, Protocol: "flv"}
				headers = p.initialHeaders
				useInitial = false
			case fastReconnectCandidate != nil && fastReconnectStreak < p.tuning.maxFastReconnectStreak:
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
				resolved, resolveHeaders, err := p.resolveCandidate(candidateIndex)
				if err != nil {
					p.setLastError(err.Error())
					select {
					case <-p.ctx.Done():
						return
					case <-time.After(flvProxyResolveRetry):
					}
					continue
				}
				candidate, headers = resolved, resolveHeaders
			}
			// 仅斗鱼：地址租约处理。ws 短租线打 expire=0 补丁；带 expire 的地址
			// 在到期前主动断开做计划内切换。虎牙与通用档 leaseAware=false，
			// 不改写 URL、不解析租约、不触发计划内切换。
			if p.tuning.leaseAware {
				candidate.URL = patchDouyuURL(candidate.URL)
				leaseDeadline = douyuLeaseDeadline(candidate.URL, time.Now())
			}
			// 仅虎牙：在预期关流前预解析 + 预建第二条连接，消除 EOF 后的解析空窗。
			// 斗鱼（租约）与通用档 plannedRefreshLeadMs=0，不进这条路径。
			if p.tuning.plannedRefreshLeadMs > 0 && !fastReconnect {
				prepareCtx, cancel := context.WithCancel(p.ctx)
				stopPrepare = cancel
				preparedCh = make(chan *preparedStream, 1)
				// 刻意不按上一段实际时长「学习」：计划内交接会让本段提前结束，
				// 若把缩短后的时长再喂回下一轮，预建时刻会逐轮前移形成正反馈
				// （125s → 113s → 101s …），反而制造更频繁的切换。
				// 虎牙 120~137s 的关流周期是 CDN 固有上限，用固定标称值即可。
				delayMs := p.tuning.nominalSessionMs - p.tuning.plannedRefreshLeadMs
				if delayMs < 1000 {
					delayMs = 1000
				}
				index := candidateIndex
				go func() {
					select {
					case <-prepareCtx.Done():
						return
					case <-time.After(time.Duration(delayMs) * time.Millisecond):
					}
					stream := p.prepareNextConnection(prepareCtx, func() (LiveStreamCandidate, map[string]string, error) {
						return p.resolveCandidate(index)
					})
					if stream == nil {
						return
					}
					select {
					case preparedCh <- stream:
					case <-prepareCtx.Done():
						stream.close()
					}
				}()
				opts = &pullOptions{prepared: preparedCh}
			}
		}

		beforeTags := p.tagCount()
		leaseExpired, handover, err := p.pullSegment(candidate, headers, leaseDeadline, opts)
		if stopPrepare != nil {
			stopPrepare()
		}
		if p.ctx.Err() != nil {
			return
		}
		if handover != nil {
			// 当前段已在关键帧处收尾，立刻接着消费预备连接。
			preparedNext = handover
			p.statsMu.Lock()
			p.plannedHandovers++
			p.statsMu.Unlock()
			p.event("plannedHandover")
			shortStreak = 0
			failures = 0
			fastReconnectStreak = 0
			fastReconnectCandidate = nil
			fastReconnectHeaders = nil
			continue
		}
		if preparedCh != nil {
			// 本段先 EOF 了：预备连接若已就绪，直接用于下一段，不浪费。
			select {
			case stream := <-preparedCh:
				if stream != nil {
					if preparedNext == nil {
						preparedNext = stream
						stream.take()
					} else {
						stream.close()
					}
				}
			default:
			}
		}
		if leaseExpired {
			// 斗鱼租约到期：计划内切换。地址即将失效，不做同地址快速续拉，
			// 立即重新解析拿新签名；不计入短段/失败/换线任何异常计数。
			p.bumpReconnect()
			p.event(fmt.Sprintf("leaseRenewal sessionMs=%d site=%s", p.lastSessionMs, p.tuning.name))
			shortStreak = 0
			fastReconnectStreak = 0
			fastReconnectCandidate = nil
			fastReconnectHeaders = nil
			continue
		}
		if err == nil {
			p.bumpReconnect()
			// 只有本段确实推送过数据才允许「同地址快速续拉」；空流立即 EOF 时
			// 必须重新解析，否则会在无效地址上空转。
			if p.tagCount() > beforeTags {
				fastReconnectCandidate = &candidate
				fastReconnectHeaders = headers
			}
			// 短/空段（上游很快关闭）：按三档各自的策略处理，互不相干。
			// 虎牙：500ms 指数退避、连续 4 次短段换线（CDN 易抖，需主动切）。
			// 斗鱼：2s 起步的耐心退避、**永不因短段换线**（CDN 稳定，信任线路，
			// 坏线路由硬失败路径兜底）。
			// 通用档：1s 起步的温和退避、显式禁用短段换线；EOF 后一律重新解析。
			if p.lastSessionMs < p.tuning.shortSessionMs {
				shortStreak++
				// 移位封顶防溢出：斗鱼档不换线时 shortStreak 会持续增长，
				// 1<<(n-1) 超过 63 位会变负数导致退避失效，这里封到 6（64 倍）足够。
				shift := uint(shortStreak - 1)
				if shift > 6 {
					shift = 6
				}
				backoff := p.tuning.shortBackoffBase * time.Duration(1<<shift)
				if backoff > p.tuning.shortBackoffMax {
					backoff = p.tuning.shortBackoffMax
				}
				p.event(fmt.Sprintf("shortSession sessionMs=%d streak=%d backoff=%s site=%s",
					p.lastSessionMs, shortStreak, backoff, p.tuning.name))
				if p.tuning.maxShortStreak > 0 && shortStreak >= p.tuning.maxShortStreak {
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

// resolveCandidate 把「重新解析 + 按候选下标选线」收敛成一个函数，
// 让正常续拉与计划内预建走完全相同的解析路径。
func (p *flvProxy) resolveCandidate(index int) (LiveStreamCandidate, map[string]string, error) {
	stream, err := p.resolve()
	if err != nil {
		return LiveStreamCandidate{}, nil, err
	}
	if len(stream.Candidates) == 0 {
		return LiveStreamCandidate{}, nil, newError("未返回可播放地址")
	}
	return stream.Candidates[index%len(stream.Candidates)], stream.Headers, nil
}

// pull 拉取一段上游 FLV 并原样缝进本地输出。
// 返回 leaseExpired=true 表示是租约到期触发的计划内断开（斗鱼主动续期），
// 上游流本身没有出错；err 为 nil 表示上游正常结束（含计划内断开）。
// leaseDeadline 为零值表示无租约语义（虎牙等平台行为完全不变）。
// pull 保留三参数签名（历史用例在用），委托给支持计划内交接的 pullSegment。
func (p *flvProxy) pull(candidate LiveStreamCandidate, headers map[string]string, leaseDeadline time.Time) (bool, error) {
	leaseExpired, _, err := p.pullSegment(candidate, headers, leaseDeadline, nil)
	return leaseExpired, err
}

// pullSegment 拉取一段上游 FLV 并缝进本地输出。
// 返回：leaseExpired=租约到期计划内断开；handover=已就绪的第二条连接；err=实际故障。
func (p *flvProxy) pullSegment(candidate LiveStreamCandidate, headers map[string]string, leaseDeadline time.Time, opts *pullOptions) (bool, *preparedStream, error) {
	// 计划内交接：本段直接消费已预建好的连接，不再发起新请求。
	if opts != nil && opts.usePrepared != nil {
		stream := opts.usePrepared
		stream.take()
		defer stream.close()
		err := p.consumeWithOptions(stream.reader(), nil)
		if err == errHandover {
			err = nil
		}
		return false, nil, err
	}

	requestCtx := p.ctx
	var leaseExpired atomic.Bool
	var cancel context.CancelFunc
	var leaseTimer *time.Timer
	if !leaseDeadline.IsZero() && leaseDeadline.After(time.Now()) {
		requestCtx, cancel = context.WithCancel(p.ctx)
		defer cancel()
		leaseTimer = time.AfterFunc(time.Until(leaseDeadline), func() {
			// 租约到期：主动取消本次上游读取（计划内切换，不是故障）。
			leaseExpired.Store(true)
			cancel()
		})
		defer leaseTimer.Stop()
	}
	request, err := http.NewRequestWithContext(requestCtx, http.MethodGet, candidate.URL, nil)
	if err != nil {
		return false, nil, newError("上游地址无效：%s", err.Error())
	}
	request.Header.Set("Accept", "*/*")
	for key, value := range headers {
		request.Header.Set(key, value)
	}
	// 预建连接 watcher 的生命周期必须绑定本次请求，段结束时立即退出。
	watchCtx, stopWatch := context.WithCancel(requestCtx)
	defer stopWatch()
	state := &handoverState{}
	if opts != nil && opts.prepared != nil {
		state.ready = opts.prepared
		state.watch(watchCtx)
	}

	response, err := p.httpClient.Do(request)
	if err != nil {
		if leaseExpired.Load() && p.ctx.Err() == nil {
			return true, nil, nil
		}
		return false, nil, newError("上游拉流失败：%s", err.Error())
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return leaseExpired.Load(), nil, newError("上游 HTTP %d", response.StatusCode)
	}
	reader := bufio.NewReaderSize(response.Body, 128*1024)
	consumeErr := p.consumeWithOptions(reader, state)
	if consumeErr == errHandover {
		// 交接已发生，立刻标记为「已取用」，避免 20s hold timeout 的定时器
		// 在 streamLoop 拿到它之前把这条连接关掉。
		prepared := state.prepared()
		if prepared != nil {
			prepared.take()
		}
		return false, prepared, nil
	}
	if consumeErr == errUpstreamIdle {
		// 上游静默：软重连，不记硬失败、不换线路。
		p.statsMu.Lock()
		p.idleTimeouts++
		p.statsMu.Unlock()
		p.event("upstreamIdleTimeout")
		return false, nil, nil
	}
	if consumeErr == nil && leaseExpired.Load() && p.ctx.Err() == nil {
		return true, nil, nil
	}
	if consumeErr != nil && leaseExpired.Load() && p.ctx.Err() == nil {
		// 读取因租约取消而中断：consume 已通过 defer 记录会话时长，
		// 对 streamLoop 呈现为一次干净的「计划内 EOF」。
		return true, nil, nil
	}
	return leaseExpired.Load(), nil, consumeErr
}

func (p *flvProxy) consume(reader *bufio.Reader) error {
	return p.consumeWithOptions(reader, nil)
}

// consumeWithOptions 与 consume 同路径，额外支持「计划内关键帧交接」信号。
func (p *flvProxy) consumeWithOptions(reader *bufio.Reader, handover *handoverState) error {
	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(reader, header); err != nil {
		if isNetworkTimeout(err) {
			return errUpstreamIdle
		}
		return newError("上游 FLV 头读取失败：%s", err.Error())
	}
	if header[0] != 'F' || header[1] != 'L' || header[2] != 'V' {
		return newError("上游返回的不是 FLV 流")
	}
	startedAt := time.Now()
	// 会话时长统一在退出时记录（含租约到期主动断开、读取出错等所有出口），
	// 保证 streamLoop 的短段判定在任何结束方式下都有 lastSessionMs 可用。
	defer p.recordSession(startedAt)
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
	// offset 锁定之前已累计的丢弃量。这段时间轴已经在 spliceBase 里被
	// 平移吸收，若后续帧再把它扣一遍，输出时间戳会被拉回上一段交付位置之前，
	// 触发 validateTs 拒绝 / controlledTs 钳制，续段表现为卡住数秒。
	var droppedAtBase int64
	var prevSourceTs int64
	var havePrevSourceTs bool
	// pacer 的墙钟基准保存在 p.pacer，跨上游会话保持，这里不再有
	// 任何「每段重新预填」的局部状态。

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
				return nil
			}
			if isNetworkTimeout(err) {
				return errUpstreamIdle
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
			if isNetworkTimeout(err) {
				return errUpstreamIdle
			}
			return newError("上游 FLV 数据读取失败：%s", err.Error())
		}
		if _, err := io.ReadFull(reader, make([]byte, flvProxyPrevTagSizeLen)); err != nil {
			if isNetworkTimeout(err) {
				return errUpstreamIdle
			}
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
				if !p.validateTs(1, ts) { // AVC 序列头属于视频轨
					continue
				}
				if err := p.writeTag(tagHeader, data, ts); err != nil {
					return err
				}
				p.commitTs(1, ts)
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
			switch {
			case p.tuning.timelineContinuous:
				// 斗鱼：同一房间两次上游连接共享同一条时间戳时间轴（pure_live 实测），
				// 新段源时间戳天然连续，直接沿用；仅当与已交付位置差超出对齐窗口
				//（另一条时间轴/长空窗）才平移续接。虎牙/通用档不进这条路径。
				adjusted = sourceTs + douyuContinuityOffset(lastOutBefore, sourceTs)
			case p.tuning.timelineAuto:
				// 通用档：不预设归零/同轴，按新段首帧相对已交付位置自适应决定偏移。
				// 既不套用虎牙的 spliceBase 无条件平移，也不套用斗鱼的 60s 同轨窗口。
				adjusted = sourceTs + genericTimelineOffset(lastOutBefore, sourceTs, p.tuning.spliceStepMs)
			default:
				// 虎牙：新流时间戳从 0 重启，平移接续上一段输出（历史行为，逐字保留）。
				adjusted = p.spliceBase(sourceTs)
			}
			offset = adjusted - sourceTs
			// 基准之前丢掉的回放帧已经通过 spliceBase/自适应偏移被平移吸收，
			// 后续帧只压缩「基准之后」新增的丢弃量，避免重复扣减。
			droppedAtBase = droppedSourceMs
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
		} else if p.tuning.timelineContinuous {
			// 仅斗鱼连续时间轴：源时间戳即输出时间戳。回放帧由指纹去重丢弃，
			// 不做 droppedSourceMs 压缩补偿——压缩会把直播帧拉回已交付位置、
			// 被 acceptTs 整批误杀（音画不同步的另一半根因）。
			adjusted = sourceTs + offset
		} else {
			// 虎牙 / 通用档：丢弃回放帧后压缩源时间轴，避免输出时间轴出现数秒
			// 空洞导致播放器卡住。这是 FLV 续流的通用正确性保证，不是任何平台
			// 的专属假设；两档真正的差异在首帧对齐方式与全局钳制策略。
			// 只扣基准之后的增量（droppedAtBase 见上方说明）。
			adjusted = sourceTs - (droppedSourceMs - droppedAtBase) + offset
		}
		// 丢弃整段回放后，源时间轴被压缩，扣除量可能与帧间隔有 1~2ms 误差；
		// 兜底保证输出严格不回退（acceptTs 只允许相等，这里保证至少 +1ms）。
		// 仅续段启用：首段没有「上一帧」，不能把首帧 0 抬成 1。
		// 斗鱼连续时间轴与通用自适应时间轴都不走该钳制：全局钳制会把音/视频
		// 同 ts 的帧错开 1ms，且真正的同轨回退已由 acceptTs 守住。
		// 该钳制是虎牙归零重置路径的历史行为，逐字保留、不外借给其它平台。
		if isContinuation && !p.tuning.timelineContinuous && !p.tuning.timelineAuto {
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
		if !p.validateTs(track, adjusted) {
			p.statsMu.Lock()
			if track == 1 {
				p.tsRejectedVideo++
			} else {
				p.tsRejectedAudio++
			}
			p.statsMu.Unlock()
			continue
		}
		// pacer：预填只在开播首段做一次，之后跨重连/租约续期/计划内交接
		// 保持同一条实时匀速曲线（状态在 p.pacer）。落后超过预算说明读到的是
		// 交接后预建连接里的积压旧帧，重新对齐基准，避免一整段数据瞬间灌入。
		if p.pacerEnabled && p.ctx.Err() == nil {
			targetRebased := p.pacerRebase.Swap(false)
			if targetRebased {
				p.event("pacerTargetRebased")
			}
			wait, rebased, catchUp := p.pacer.pace(
				adjusted, p.pacerTargetMs.Load(), p.tuning.pacerMaxDelay, targetRebased)
			if rebased {
				p.statsMu.Lock()
				p.pacerRebases++
				p.statsMu.Unlock()
				if catchUp {
					p.event("pacerCatchUpRebased")
				} else {
					p.event("pacerRebased")
				}
			}
			if wait > 0 {
				timer := time.NewTimer(wait)
				select {
				case <-p.ctx.Done():
					timer.Stop()
					return newError("代理已关闭")
				case <-timer.C:
				}
				p.statsMu.Lock()
				p.pacerWaitMs += wait.Milliseconds()
				p.statsMu.Unlock()
			}
		}
		if err := p.writeTag(tagHeader, data, adjusted); err != nil {
			return err
		}
		p.commitTs(track, adjusted)
		p.rememberFrame(fp, adjusted)
		p.incForwarded(tagType, sourceTs)
		// 计划内交接：只在成功写出一个关键帧之后离开，保证新段起点可独立解码。
		if isKey && handover.shouldHandover() {
			p.event("plannedHandoverAtKeyframe")
			return errHandover
		}
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
// acceptTs 保留原有「校验并提交」语义（历史测试与 AVC 序列头路径在用）。
// 常规帧改走 validateTs + commitTs：只有标签真正写给下游后才推进时间轴，
// 避免写失败/客户端断开时状态与输出不一致。
func (p *flvProxy) acceptTs(track int, ts int64) bool {
	if !p.validateTs(track, ts) {
		return false
	}
	p.commitTs(track, ts)
	return true
}

func (p *flvProxy) validateTs(track int, ts int64) bool {
	if track < 0 || track > 1 {
		return true
	}
	p.tsMu.Lock()
	defer p.tsMu.Unlock()
	return !p.haveOut[track] || ts >= p.lastOutTs[track]
}

func (p *flvProxy) commitTs(track int, ts int64) {
	if track < 0 || track > 1 {
		return
	}
	p.tsMu.Lock()
	p.lastOutTs[track] = ts
	p.haveOut[track] = true
	p.tsMu.Unlock()
}

func (p *flvProxy) writeTag(tagHeader []byte, data []byte, ts int64) error {
	tagHeader[4] = byte(ts >> 16)
	tagHeader[5] = byte(ts >> 8)
	tagHeader[6] = byte(ts)
	tagHeader[7] = byte(ts >> 24)
	prevSize := uint32(flvProxyTagHeaderSize + len(data))
	// 拼成一帧一次写出：30~60fps 下旧实现的 3 次 Write + 1 次 Flush
	// 每秒会产生上百次系统调用与 chunk 边界。
	frame := make([]byte, 0, flvProxyTagHeaderSize+len(data)+flvProxyPrevTagSizeLen)
	frame = append(frame, tagHeader...)
	frame = append(frame, data...)
	frame = append(frame, byte(prevSize>>24), byte(prevSize>>16), byte(prevSize>>8), byte(prevSize))

	p.mu.Lock()
	client := p.client
	p.mu.Unlock()
	if client == nil {
		return newError("本地播放器已断开")
	}
	if err := p.writeToClient(client, frame); err != nil {
		p.dropClientIf(client)
		return newError("本地写入失败：%s", err.Error())
	}
	p.rememberConfigTag(frame)
	p.statsMu.Lock()
	p.tagsWritten++
	p.bytesWritten += int64(len(frame))
	p.statsMu.Unlock()
	return nil
}

func (p *flvProxy) dropClientIf(client *flvProxyClient) {
	p.mu.Lock()
	if p.client == client {
		p.client = nil
	}
	p.mu.Unlock()
	if client != nil {
		client.retire()
	}
}

func (p *flvProxy) dropClient() {
	p.mu.Lock()
	client := p.client
	p.client = nil
	p.mu.Unlock()
	if client != nil {
		client.retire()
	}
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
		"url":    p.url,
		"client": p.hasClient(),
		"site":   p.site,
		"tuning": p.tuning.name,
		// 时间轴语义与租约策略：三档互斥，真机监控可直接确认各走各的路。
		"timelineContinuous": p.tuning.timelineContinuous, // 兼容既有监控脚本
		"timelineAuto":       p.tuning.timelineAuto,
		"timelineMode":       p.tuning.timelineModeName(),
		"leaseAware":         p.tuning.leaseAware,
		// 重连策略分档：真机监控可直接确认斗鱼/虎牙各走各的策略。
		"fastReconnectMax":     p.tuning.maxFastReconnectStreak,
		"shortSessionMs":       p.tuning.shortSessionMs,
		"shortStreakMax":       p.tuning.maxShortStreak, // <=0 表示不因短段换线
		"shortBackoffMs":       p.tuning.shortBackoffBase.Milliseconds(),
		"uptimeSec":            int64(time.Since(p.startedAt).Seconds()),
		"upstreamSessions":     p.upstreamSessions,
		"reconnects":           p.reconnects,
		"plannedHandovers":     p.plannedHandovers,
		"idleTimeouts":         p.idleTimeouts,
		"clientTakeovers":      p.clientTakeovers,
		"candidateSwitches":    p.candidateSwitches,
		"pacerTargetBufferMs":  p.pacerTargetMs.Load(),
		"plannedRefreshLeadMs": p.tuning.plannedRefreshLeadMs,
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
		// pacer 累计等待时长，持续增长说明上游突发已被平滑；
		// pacerRebases 上升说明发生过时间戳跳变或交接积压追帧的重新对齐。
		"pacerWaitMs":      p.pacerWaitMs,
		"pacerRebases":     p.pacerRebases,
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
