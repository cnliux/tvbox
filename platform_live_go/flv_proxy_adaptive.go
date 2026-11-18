package platformlive

// 斗鱼「一起看」的房间级自适应重连策略。
//
// 背景：真机监控发现斗鱼各房间的上游表现差异很大，固定调参无法同时覆盖：
//  - 有的房间开机瞬态短会话（sessionMs=7/1805ms 后才稳定），固定 2s 起步退避
//    把入画时间拖到 3~6s（黑屏）；
//  - 有的房间 CDN 稳定、会话长达数分钟，偶尔被 CDN 踢一脚（短会话但推过数据），
//    这种瞬态等 2s 退避纯属浪费——立即重连通常一次就恢复；
//  - 不同房间走不同 CDN（hw-h5 / tc-tct 等），tag 到达间隔差异明显，
//    固定 15s 空闲读超时对稀疏房间偏紧。
//
// 因此这里维护「每个代理实例（= 每个房间）自己的历史」，在 douyu-yqk 档位的
// 固定策略之上做自适应修正（flvSiteTuning.adaptiveReconnect 才启用，
// 虎牙/通用档完全不经过本文件，行为不变）：
//  1. 短会话但确实推过数据 → 立即重连（不退避），连续次数封顶防风暴；
//  2. 房间历史上出现过健康长会话 → 短段退避减半（瞬态踢连接恢复更快）；
//  3. 按房间 tag 到达间隔自适应抬高空闲读超时（只抬高不降低，稀疏房间
//     不再被 15s 误杀；正常房间保持默认，故障检测仍由 8s 进度看门狗兜底）。

import (
	"sync"
	"time"
)

const (
	// 连续「短会话但推过数据 → 立即重连」的最大次数：超过后回到正常退避，
	// 防止把代理变成每秒多次的重连风暴。任何一次达到正常长度的会话都会清零。
	adaptiveInstantRetryMax = 5
	// 房间「健康」判定：该房间（任意地址）交付过 ≥ 该时长的会话，
	// 说明房间与线路本质可用，之后的短会话更可能是 CDN 瞬态。
	adaptiveHealthySessionMs = int64(15000)
	// URL 健康历史的条目上限（斗鱼签名地址按会话发放，历史条目只增不查后果，
	// 用简单 FIFO 淘汰控制内存）。
	adaptiveURLHealthMax = 8
	// 自适应空闲读超时的上限：再高失去意义（8s 进度看门狗才是故障兜底）。
	adaptiveIdleMaxMs = int64(40000)
	// 房间健康时短段退避的下限：低于该值没有意义（解析本身就要几百毫秒）。
	adaptiveMinBackoff = 500 * time.Millisecond
)

// roomAdaptive 保存一个代理实例（一个房间）的自适应历史。
// 所有方法并发安全：写入方是 streamLoop 单协程与 consume 转发协程，
// 读取方还包括 statsJSON 监控采样。
type roomAdaptive struct {
	mu sync.Mutex
	// 候选地址 → 该地址交付过的最长会话（毫秒）。只在「确实推过数据」时记录。
	urlBest map[string]int64
	// urlBest 的插入顺序，用于 FIFO 淘汰。
	urlOrder []string
	// 房间任意地址交付过的最长会话（毫秒）。
	bestSessionMs int64
	// 连续「短会话但推过数据 → 立即重连」已用次数。
	instantStreak int
}

// noteSession 在一段上游会话正常结束（含租约续期）后记录健康度。
// shortSessionMs 传当前档位的短段阈值：达到阈值视为一次稳定会话，
// 立即重连配额随之清零；dataPushed=false（空段）不记录健康度。
func (a *roomAdaptive) noteSession(url string, sessionMs, shortSessionMs int64, dataPushed bool) {
	if sessionMs >= shortSessionMs {
		a.mu.Lock()
		a.instantStreak = 0
		a.mu.Unlock()
	}
	if url == "" || !dataPushed {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if sessionMs > a.bestSessionMs {
		a.bestSessionMs = sessionMs
	}
	if a.urlBest == nil {
		a.urlBest = make(map[string]int64)
	}
	if _, exists := a.urlBest[url]; !exists {
		a.urlOrder = append(a.urlOrder, url)
		if len(a.urlOrder) > adaptiveURLHealthMax {
			delete(a.urlBest, a.urlOrder[0])
			a.urlOrder = a.urlOrder[1:]
		}
	}
	if sessionMs > a.urlBest[url] {
		a.urlBest[url] = sessionMs
	}
}

// beginInstantRetry 判断并登记一次「短会话但推过数据 → 立即重连」。
// 返回 false 表示配额已用完，调用方应回到正常退避路径。
func (a *roomAdaptive) beginInstantRetry() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.instantStreak >= adaptiveInstantRetryMax {
		return false
	}
	a.instantStreak++
	return true
}

// roomHealthy 报告该房间是否交付过健康长会话。
func (a *roomAdaptive) roomHealthy() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.bestSessionMs >= adaptiveHealthySessionMs
}

// shortBackoffFor 按房间历史修正短段退避：健康房间的短会话多为 CDN 瞬态，
// 退避减半以加快恢复；冷房间（从未有过长会话）保持档位默认的耐心退避，
// 避免对坏地址高速重试。
func (a *roomAdaptive) shortBackoffFor(base, max time.Duration) (time.Duration, time.Duration) {
	if !a.roomHealthy() {
		return base, max
	}
	base /= 2
	if base < adaptiveMinBackoff {
		base = adaptiveMinBackoff
	}
	max /= 2
	if max < base*2 {
		max = base * 2
	}
	return base, max
}

// bestSession 返回房间历史最长会话（毫秒），供监控。
func (a *roomAdaptive) bestSession() int64 {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.bestSessionMs
}

// instantStreakSnapshot 返回当前已用的立即重连次数（测试/监控用）。
func (a *roomAdaptive) instantStreakSnapshot() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.instantStreak
}

// adaptiveIdleReadTimeout 返回当前生效的上游空闲读超时。
// 规则：手动 override（手机端调试按钮）永远优先；否则 = max(默认值, 3×房间
// tag 间隔 EWMA)，封顶 40s。只抬高不降低：正常房间行为与历史完全一致，
// 稀疏房间（tag 间隔大的 CDN）不再被 15s 误判为半开连接。
// 真正的卡流检测由 8s 进度看门狗负责，与该值无关。
func (p *flvProxy) adaptiveIdleReadTimeout() time.Duration {
	p.statsMu.Lock()
	defer p.statsMu.Unlock()
	return p.adaptiveIdleReadTimeoutLocked()
}

// adaptiveIdleReadTimeoutLocked 是 adaptiveIdleReadTimeout 的已持锁变体
//（statsJSON 在持有 statsMu 的状态下取值，避免不可重入死锁）。调用方须持 statsMu。
func (p *flvProxy) adaptiveIdleReadTimeoutLocked() time.Duration {
	// 手机端调试按钮的手动 override 独占生效：明确设值时不做任何自适应缩放。
	if override := douyuIdleReadTimeoutOverride.Load(); override > 0 {
		return time.Duration(override) * time.Millisecond
	}
	ewma := p.adaptiveGapEwmaMs
	idle := douyuIdleReadTimeout()
	if ewma > 0 {
		if scaled := time.Duration(ewma*3) * time.Millisecond; scaled > idle {
			idle = scaled
		}
	}
	if idle > time.Duration(adaptiveIdleMaxMs)*time.Millisecond {
		idle = time.Duration(adaptiveIdleMaxMs) * time.Millisecond
	}
	return idle
}

// noteTagGap 在每次成功转发标签后记录与上一标签的墙钟间隔（consume 转发
// 协程独占写入，statsMu 保护）。会话结束时由 recordSession 折叠进 EWMA。
func (p *flvProxy) noteTagGap(now time.Time) {
	p.statsMu.Lock()
	defer p.statsMu.Unlock()
	if !p.adaptiveTagLastAt.IsZero() {
		if gap := now.Sub(p.adaptiveTagLastAt).Milliseconds(); gap > p.adaptiveGapPeakMs {
			p.adaptiveGapPeakMs = gap
		}
	}
	p.adaptiveTagLastAt = now
}

// resetTagGap 在每段会话开始时清空间隔采样（跨段的重连等待时间不算
// tag 间隔，否则峰值会被重连空窗污染）。
func (p *flvProxy) resetTagGap() {
	p.statsMu.Lock()
	p.adaptiveTagLastAt = time.Time{}
	p.adaptiveGapPeakMs = 0
	p.statsMu.Unlock()
}

// foldTagGap 在会话结束时把本段峰值间隔折叠进房间 EWMA：
// 新峰值立即生效（EWMA = max(峰值, 旧值×2/3)），历史峰值随会话衰减。
func (p *flvProxy) foldTagGap() {
	p.statsMu.Lock()
	defer p.statsMu.Unlock()
	if p.adaptiveGapPeakMs > 0 {
		decayed := p.adaptiveGapEwmaMs * 2 / 3
		if p.adaptiveGapPeakMs > decayed {
			p.adaptiveGapEwmaMs = p.adaptiveGapPeakMs
		} else {
			p.adaptiveGapEwmaMs = decayed
		}
		p.adaptiveGapPeakMs = 0
	}
	p.adaptiveTagLastAt = time.Time{}
}
