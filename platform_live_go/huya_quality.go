package platformlive

// 虎牙清晰度（码率上限）运行时偏好。
//
// 背景（真机 192.168.1.37 / p230 实测）：huya.go 之前固定取 vBitRateInfo 中
// 最高码率（通常为蓝光 8M，1080p60）。弱性能盒子（Amlogic OMX AVC）实时解码
// 只能渲出约 34fps，丢帧率高达 40%+，直接表现为「卡顿 + 画面持续落后声音
// （音画不同步）→ 缓冲耗尽周期性 BUFFERING」。因此需要把「选最高」改为
// 「可选码率上限」，由 Kotlin 侧在检测到持续高丢帧时自动降档。
//
// 生效粒度：Resolve 每次被调用时读取 override，因此改完再触发一次重解析
// 即按新上限选流；在播会话不受影响（由上层主动重连/重解析完成切换）。
// 沿用 flv_proxy_huya_tuning.go 的「默认值 + 可写全局」模式。

import "sync/atomic"

// huyaRate 一条虎牙清晰度候选（iBitRate=0 表示「原画」，通常码率最高）。
type huyaRate struct {
	bitRate int64
	name    string
}

var huyaMaxBitRateOverride atomic.Int64 // <=0 表示不设上限（默认取最高码率）

// SetHuyaMaxBitRate 设置虎牙选流的码率上限（对应 vBitRateInfo.iBitRate，如
// 蓝光 8000、超清 2000、高清 550）。Resolve 会选「≤上限的最高码率」；若所有
// 可用码率都高于上限，则选最低码率，保证弱设备始终可播。
// 0 表示恢复默认（取最高码率）；非法值（<100 的非零值）被忽略。
func SetHuyaMaxBitRatePref(bitRate int64) {
	defer recoverToErrorOnly()
	if bitRate != 0 && bitRate < 100 {
		return
	}
	huyaMaxBitRateOverride.Store(bitRate)
}

// GetHuyaMaxBitRatePref 返回当前码率上限；0 表示未设上限。
func GetHuyaMaxBitRatePref() (bitRate int64) {
	defer recoverToErrorOnly()
	return huyaMaxBitRateOverride.Load()
}

// pickHuyaRate 从已按码率降序排列的候选中选流：
// 未设上限时取最高；设了上限时选「≤上限的最高**非零**码率」；
// 全部高于上限时取最低非零码率（bitRate=0 是「原画」，通常最高），
// 保证弱设备始终有可实时解码的流。
func pickHuyaRate(rates []huyaRate, maxBitRate int64) (rate int64, quality string) {
	if len(rates) == 0 {
		return 0, "原画"
	}
	rate, quality = rates[0].bitRate, rates[0].name
	if maxBitRate <= 0 {
		return rate, quality
	}
	picked := false
	for _, item := range rates {
		if item.bitRate > 0 && item.bitRate <= maxBitRate {
			rate, quality = item.bitRate, item.name
			picked = true
			break
		}
	}
	if !picked {
		for i := len(rates) - 1; i >= 0; i-- {
			if rates[i].bitRate > 0 {
				rate, quality = rates[i].bitRate, rates[i].name
				break
			}
		}
	}
	return rate, quality
}
