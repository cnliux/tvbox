package platformlive

import (
	"testing"
	"time"
)

// TestSiteTuningHuyaMatchesLegacyConstants 保证虎牙「一起看」档位与历史调参完全一致，
// 已有实测结论（6.08s 回放缓冲需 ≥12s 去重窗口）不得回退。
func TestSiteTuningHuyaMatchesLegacyConstants(t *testing.T) {
	huya := siteTuningFor("huya")
	if huya.name != "huya-yqk" {
		t.Fatalf("huya tuning name = %q, want huya-yqk", huya.name)
	}
	if huya.replayWindowMs != flvProxyReplayWindowMs {
		t.Fatalf("huya replayWindowMs = %d, want legacy %d", huya.replayWindowMs, flvProxyReplayWindowMs)
	}
	if huya.replayGuardMaxFrames != flvProxyReplayGuardMaxFrames {
		t.Fatalf("huya replayGuardMaxFrames = %d, want legacy %d", huya.replayGuardMaxFrames, flvProxyReplayGuardMaxFrames)
	}
	if huya.spliceStepMs != flvProxySpliceStepMs {
		t.Fatalf("huya spliceStepMs = %d, want legacy %d", huya.spliceStepMs, flvProxySpliceStepMs)
	}
	if huya.pacerMaxDelay != flvProxyPacerMaxDelay {
		t.Fatalf("huya pacerMaxDelay = %v, want legacy %v", huya.pacerMaxDelay, flvProxyPacerMaxDelay)
	}
	// 虎牙重连策略必须与历史常量逐字一致。
	if huya.maxFastReconnectStreak != huyaMaxFastReconnectStreak {
		t.Fatalf("huya maxFastReconnectStreak = %d, want %d", huya.maxFastReconnectStreak, huyaMaxFastReconnectStreak)
	}
	if huya.shortSessionMs != huyaShortSessionMs {
		t.Fatalf("huya shortSessionMs = %d, want %d", huya.shortSessionMs, huyaShortSessionMs)
	}
	if huya.maxShortStreak != huyaMaxShortStreak {
		t.Fatalf("huya maxShortStreak = %d, want %d", huya.maxShortStreak, huyaMaxShortStreak)
	}
	if huya.shortBackoffBase != huyaShortBackoffBase || huya.shortBackoffMax != huyaShortBackoffMax {
		t.Fatalf("huya backoff = %v/%v, want %v/%v", huya.shortBackoffBase, huya.shortBackoffMax, huyaShortBackoffBase, huyaShortBackoffMax)
	}
}

// TestSiteTuningDouyuDistinctFromHuya 是本次需求的核心：斗鱼与虎牙不再共用同一套逻辑。
// 至少在「回放去重窗口」上必须可区分，且斗鱼档位针对其更短回放做了收紧。
func TestSiteTuningDouyuDistinctFromHuya(t *testing.T) {
	douyu := siteTuningFor("douyu")
	huya := siteTuningFor("huya")
	if douyu.name == huya.name {
		t.Fatalf("douyu and huya must have distinct tuning profiles, both = %q", douyu.name)
	}
	if douyu.replayWindowMs == huya.replayWindowMs {
		t.Fatalf("douyu replayWindowMs (%d) must differ from huya to decouple their logic", douyu.replayWindowMs)
	}
	if douyu.replayWindowMs <= 0 || douyu.replayWindowMs > huya.replayWindowMs {
		t.Fatalf("douyu replayWindowMs = %d must be positive and <= huya %d (tighter window for shorter buffer)", douyu.replayWindowMs, huya.replayWindowMs)
	}
	// 其余安全参数保持与历史一致，避免引入未知风险。
	if douyu.replayGuardMaxFrames != huya.replayGuardMaxFrames {
		t.Fatalf("douyu replayGuardMaxFrames = %d, want %d", douyu.replayGuardMaxFrames, huya.replayGuardMaxFrames)
	}
	if douyu.spliceStepMs != huya.spliceStepMs {
		t.Fatalf("douyu spliceStepMs = %d, want %d", douyu.spliceStepMs, huya.spliceStepMs)
	}
	if douyu.pacerMaxDelay != huya.pacerMaxDelay {
		t.Fatalf("douyu pacerMaxDelay = %v, want %v", douyu.pacerMaxDelay, huya.pacerMaxDelay)
	}
}

// TestDouyuReconnectPolicyCalmerThanHuya 是本次需求的核心：斗鱼 CDN 相对稳定，
// 重连思路必须与虎牙区分开——不做激进换线，信任线路、耐心退避、重新解析拿新签名。
func TestDouyuReconnectPolicyCalmerThanHuya(t *testing.T) {
	douyu := siteTuningFor("douyu")
	huya := siteTuningFor("huya")

	// 斗鱼最多一次同地址续拉（比虎牙的 3 次克制），EOF 后尽快重新解析拿新签名。
	if douyu.maxFastReconnectStreak >= huya.maxFastReconnectStreak {
		t.Fatalf("douyu maxFastReconnectStreak = %d must be < huya %d", douyu.maxFastReconnectStreak, huya.maxFastReconnectStreak)
	}
	// 斗鱼**永不**因短段自动换线（<=0），虎牙保持 4 次换线。
	if douyu.maxShortStreak != 0 {
		t.Fatalf("douyu maxShortStreak = %d, want 0 (never switch on short sessions)", douyu.maxShortStreak)
	}
	if huya.maxShortStreak <= 0 {
		t.Fatalf("huya maxShortStreak = %d must stay positive", huya.maxShortStreak)
	}
	// 斗鱼短段退避更耐心：起步与上限都不得低于虎牙。
	if douyu.shortBackoffBase < huya.shortBackoffBase || douyu.shortBackoffMax <= huya.shortBackoffMax {
		t.Fatalf("douyu backoff %v/%v must be gentler than huya %v/%v",
			douyu.shortBackoffBase, douyu.shortBackoffMax, huya.shortBackoffBase, huya.shortBackoffMax)
	}
	// 斗鱼短段判定阈值不得高于虎牙（斗鱼会话通常更长，短段即异常）。
	if douyu.shortSessionMs > huya.shortSessionMs {
		t.Fatalf("douyu shortSessionMs = %d must be <= huya %d", douyu.shortSessionMs, huya.shortSessionMs)
	}
}

// TestSiteTuningCaseInsensitiveAndGeneric 覆盖大小写/空白，并确认未知平台
// 走独立的通用中性档（不再沿用虎牙历史常量）。
func TestSiteTuningCaseInsensitiveAndGeneric(t *testing.T) {
	if siteTuningFor("DOUYU").name != "douyu-yqk" {
		t.Fatal("siteTuningFor should be case-insensitive for douyu")
	}
	if siteTuningFor(" HuYa ").name != "huya-yqk" {
		t.Fatal("siteTuningFor should trim and be case-insensitive for huya")
	}
	if siteTuningFor("bilibili").name != "generic-live" {
		t.Fatalf("其它平台应走通用中性档，实际 %q", siteTuningFor("bilibili").name)
	}
	// 通用档必须独立于虎牙按实测调出的历史常量。
	def := siteTuningFor("unknown")
	if def.replayWindowMs == flvProxyReplayWindowMs {
		t.Fatalf("通用档 replayWindowMs 不应沿用虎牙实测值 %d", flvProxyReplayWindowMs)
	}
	if def.replayGuardMaxFrames == flvProxyReplayGuardMaxFrames {
		t.Fatalf("通用档 replayGuardMaxFrames 不应沿用虎牙历史值 %d", flvProxyReplayGuardMaxFrames)
	}
	if def.pacerMaxDelay != genericPacerMaxDelay {
		t.Fatalf("通用档 pacerMaxDelay = %v, want %v", def.pacerMaxDelay, genericPacerMaxDelay)
	}
}

// TestNewFlvProxyAppliesSiteTuning 验证代理实例确实按站点装载了对应档位。
func TestNewFlvProxyAppliesSiteTuning(t *testing.T) {
	service := NewService(8, 0, nil, "")

	proxy, err := newFlvProxy(service, "douyu", "12345", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy(douyu) error: %v", err)
	}
	defer proxy.close()
	if proxy.tuning.name != "douyu-yqk" {
		t.Fatalf("proxy.tuning.name = %q, want douyu-yqk", proxy.tuning.name)
	}
	if proxy.tuning.replayWindowMs >= flvProxyReplayWindowMs {
		t.Fatalf("douyu proxy replayWindowMs = %d must be tighter than legacy %d", proxy.tuning.replayWindowMs, flvProxyReplayWindowMs)
	}

	huyaProxy, err := newFlvProxy(service, "huya", "67890", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy(huya) error: %v", err)
	}
	defer huyaProxy.close()
	if huyaProxy.tuning.name != "huya-yqk" {
		t.Fatalf("huya proxy.tuning.name = %q, want huya-yqk", huyaProxy.tuning.name)
	}
	if huyaProxy.tuning.replayWindowMs != flvProxyReplayWindowMs {
		t.Fatalf("huya proxy replayWindowMs = %d, want legacy %d", huyaProxy.tuning.replayWindowMs, flvProxyReplayWindowMs)
	}
}

var _ = time.Second
