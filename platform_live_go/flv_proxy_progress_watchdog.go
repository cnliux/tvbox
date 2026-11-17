package platformlive

// 下游进度看门狗（与故障类型无关的通用兜底）。
//
// 真机监控发现：虎牙在计划交接后偶发「代理不报错、也不再写出任何标签」的冻结，
// 上游空闲读超时与坏 tag 都无法覆盖（既不超时也不设 lastError）。播放器侧只能
// 等 12s 硬看门狗重建整个播放器/代理，表现为一次可见断流。
//
// 这里以「是否有标签写出」为唯一健康信号：连续 flvProxyProgressStallMs 没有任何
// 进展就调用当前段的 cancel，令 io.ReadFull / HTTP 请求立即返回，随后由既有软重连
// 路径重新解析续流。阈值必须小于 Kotlin 硬阈值 12s，否则代理来不及自愈。

import (
	"time"
)

// 用 var 而非 const：单测可临时调小以在百毫秒级验证看门狗行为。
var flvProxyProgressStallMs int64 = 8000

// markProgress 在每帧成功写给下游后调用，刷新健康时间戳。
func (p *flvProxy) markProgress() {
	p.progressMu.Lock()
	p.lastProgressAt = time.Now()
	p.progressMu.Unlock()
}

// resetProgress 在每段上游会话开始时调用，避免上一段的旧时间戳误触发。
func (p *flvProxy) resetProgress() {
	p.progressMu.Lock()
	p.lastProgressAt = time.Now()
	p.progressMu.Unlock()
	p.progressStalled.Store(false)
}

// setSegmentCancel 登记当前段的取消函数（普通段是请求 context，预建段是连接 cancel）。
func (p *flvProxy) setSegmentCancel(cancel func()) {
	p.segmentCancelMu.Lock()
	p.segmentCancel = cancel
	p.segmentCancelMu.Unlock()
}

// cancelSegmentOnStall 由看门狗调用：立刻打断当前段，让 streamLoop 进入软重连。
func (p *flvProxy) cancelSegmentOnStall() {
	p.progressStalled.Store(true)
	p.segmentCancelMu.Lock()
	cancel := p.segmentCancel
	p.segmentCancelMu.Unlock()
	if cancel != nil {
		cancel()
	}
}

// watchProgress 每 2 秒检查一次，stop 关闭后退出。由 streamLoop 为每段启动。
func (p *flvProxy) watchProgress(stop <-chan struct{}) {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-stop:
			return
		case <-ticker.C:
			p.progressMu.Lock()
			last := p.lastProgressAt
			p.progressMu.Unlock()
			if last.IsZero() || time.Since(last) >= time.Duration(flvProxyProgressStallMs)*time.Millisecond {
				p.event("progressStallReconnect")
				p.cancelSegmentOnStall()
				return
			}
		}
	}
}
