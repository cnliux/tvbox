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

// TestSiteTuningCaseInsensitiveAndDefault 覆盖大小写/空白与未知平台回退。
func TestSiteTuningCaseInsensitiveAndDefault(t *testing.T) {
	if siteTuningFor("DOUYU").name != "douyu-yqk" {
		t.Fatal("siteTuningFor should be case-insensitive for douyu")
	}
	if siteTuningFor(" HuYa ").name != "huya-yqk" {
		t.Fatal("siteTuningFor should trim and be case-insensitive for huya")
	}
	if siteTuningFor("bilibili").name != "default" {
		t.Fatalf("unknown site should fall back to default, got %q", siteTuningFor("bilibili").name)
	}
	// 默认档位等于历史常量。
	def := siteTuningFor("unknown")
	if def.replayWindowMs != flvProxyReplayWindowMs {
		t.Fatalf("default replayWindowMs = %d, want legacy %d", def.replayWindowMs, flvProxyReplayWindowMs)
	}
	if def.pacerMaxDelay != flvProxyPacerMaxDelay {
		t.Fatalf("default pacerMaxDelay = %v, want legacy %v", def.pacerMaxDelay, flvProxyPacerMaxDelay)
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
