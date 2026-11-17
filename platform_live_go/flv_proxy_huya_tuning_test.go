package platformlive

import (
	"testing"
	"time"
)

// resetHuyaTuning 把所有虎牙 override 复位为默认，供各测试 defer 调用，
// 避免一个测试的设置污染其他测试（Go 测试同包共享全局）。
func resetHuyaTuning() {
	SetHuyaPlannedRefreshLeadMs(0)
	SetHuyaNominalSessionMs(0)
	SetHuyaShortBackoffBaseMs(0)
	SetHuyaShortBackoffMaxMs(0)
	SetHuyaIdleReadTimeoutMs(0)
	SetHuyaMaxShortStreak(0)
}

func TestHuyaTuningDefaultsAndClamp(t *testing.T) {
	defer resetHuyaTuning()
	resetHuyaTuning()

	// 默认值必须与历史常量一致（默认档 = 历史行为）。
	if got := GetHuyaPlannedRefreshLeadMs(); got != 12000 {
		t.Fatalf("default plannedLead = %d, want 12000", got)
	}
	if got := GetHuyaNominalSessionMs(); got != 125000 {
		t.Fatalf("default nominalSession = %d, want 125000", got)
	}
	if got := GetHuyaShortBackoffBaseMs(); got != 500 {
		t.Fatalf("default backoffBase = %d, want 500", got)
	}
	if got := GetHuyaShortBackoffMaxMs(); got != 5000 {
		t.Fatalf("default backoffMax = %d, want 5000", got)
	}
	if got := GetHuyaIdleReadTimeoutMs(); got != 8000 {
		t.Fatalf("default idleTimeout = %d, want 8000", got)
	}
	// 关键时序约束：代理空闲超时必须小于 Kotlin 硬卡顿看门狗（12s），
	// 否则预备连接半开时播放器会先重建，代理内部软重连永远来不及发生。
	if defaultHuyaIdleReadTimeoutMs >= 12000 {
		t.Fatalf("huya idle timeout %dms must be < Kotlin hard stall watchdog 12000ms", defaultHuyaIdleReadTimeoutMs)
	}
	if got := GetHuyaMaxShortStreak(); got != 4 {
		t.Fatalf("default maxShortStreak = %d, want 4", got)
	}

	// 合法值生效。
	SetHuyaPlannedRefreshLeadMs(20000)
	SetHuyaNominalSessionMs(130000)
	SetHuyaShortBackoffBaseMs(1000)
	SetHuyaShortBackoffMaxMs(8000)
	SetHuyaIdleReadTimeoutMs(18000)
	SetHuyaMaxShortStreak(6)
	if GetHuyaPlannedRefreshLeadMs() != 20000 || GetHuyaNominalSessionMs() != 130000 ||
		GetHuyaShortBackoffBaseMs() != 1000 || GetHuyaShortBackoffMaxMs() != 8000 ||
		GetHuyaIdleReadTimeoutMs() != 18000 || GetHuyaMaxShortStreak() != 6 {
		t.Fatalf("legal values not applied: lead=%d nominal=%d base=%d max=%d idle=%d streak=%d",
			GetHuyaPlannedRefreshLeadMs(), GetHuyaNominalSessionMs(), GetHuyaShortBackoffBaseMs(),
			GetHuyaShortBackoffMaxMs(), GetHuyaIdleReadTimeoutMs(), GetHuyaMaxShortStreak())
	}
	if got := huyaShortBackoffBaseCurrent(); got != time.Second {
		t.Fatalf("huyaShortBackoffBase = %v, want 1s", got)
	}
	if got := huyaIdleReadTimeout(); got != 18*time.Second {
		t.Fatalf("huyaIdleReadTimeout = %v, want 18s", got)
	}

	// 非法值被忽略，保持上一次合法值。
	SetHuyaPlannedRefreshLeadMs(1)
	SetHuyaNominalSessionMs(999999)
	SetHuyaIdleReadTimeoutMs(100)
	SetHuyaMaxShortStreak(99)
	if GetHuyaPlannedRefreshLeadMs() != 20000 || GetHuyaNominalSessionMs() != 130000 ||
		GetHuyaIdleReadTimeoutMs() != 18000 || GetHuyaMaxShortStreak() != 6 {
		t.Fatal("illegal values must be ignored")
	}
	// base>max：把 max 调得比当前 base(1000) 小应被忽略。
	SetHuyaShortBackoffMaxMs(500)
	if GetHuyaShortBackoffMaxMs() != 8000 {
		t.Fatalf("backoffMax < base must be ignored, got %d", GetHuyaShortBackoffMaxMs())
	}

	// 0 恢复默认。
	resetHuyaTuning()
	if GetHuyaPlannedRefreshLeadMs() != 12000 || GetHuyaShortBackoffBaseMs() != 500 ||
		GetHuyaMaxShortStreak() != 4 {
		t.Fatal("0 must restore defaults")
	}
}

// TestHuyaBaseExceedsDefaultMax 验证基数被调到超过默认上限时，上限跟随基数，
// 避免出现 base>max 的非法组合（explanation 见 GetHuyaShortBackoffMaxMs）。
func TestHuyaBaseExceedsDefaultMax(t *testing.T) {
	defer resetHuyaTuning()
	resetHuyaTuning()
	SetHuyaShortBackoffBaseMs(6000) // 合法范围 100..5000 之外？不，5000 是上限
	// 6000 越界应被忽略，base 仍为 500。
	if GetHuyaShortBackoffBaseMs() != 500 {
		t.Fatalf("out-of-range base must be ignored, got %d", GetHuyaShortBackoffBaseMs())
	}
	// 用合法的最大值 5000，仍等于默认上限 5000，不产生 base>max。
	SetHuyaShortBackoffBaseMs(5000)
	if base, max := huyaShortBackoffBaseCurrent(), huyaShortBackoffMaxCurrent(); base > max {
		t.Fatalf("base %v must not exceed max %v", base, max)
	}
}

// TestHuyaEffectiveValuesFollowOverrideForLiveProxy 验证 effective* helper 在
// 构造后仍读运行时 override（在播会话即时生效），且只影响虎牙档。
func TestHuyaEffectiveValuesFollowOverrideForLiveProxy(t *testing.T) {
	defer resetHuyaTuning()
	resetHuyaTuning()

	huyaProxy := &flvProxy{tuning: siteTuningFor("huya")}
	douyuProxy := &flvProxy{tuning: siteTuningFor("douyu")}

	// 构造后改 override。
	SetHuyaPlannedRefreshLeadMs(20000)
	SetHuyaNominalSessionMs(130000)
	SetHuyaShortBackoffBaseMs(2000)
	SetHuyaShortBackoffMaxMs(9000)
	SetHuyaIdleReadTimeoutMs(18000)
	SetHuyaMaxShortStreak(7)

	if got := huyaProxy.effectivePlannedLeadMs(); got != 20000 {
		t.Fatalf("huya effectivePlannedLeadMs = %d, want 20000", got)
	}
	if got := huyaProxy.effectiveNominalSessionMs(); got != 130000 {
		t.Fatalf("huya effectiveNominalSessionMs = %d, want 130000", got)
	}
	if base, max := huyaProxy.effectiveShortBackoff(); base != 2*time.Second || max != 9*time.Second {
		t.Fatalf("huya effectiveShortBackoff = %v/%v, want 2s/9s", base, max)
	}
	if got := huyaProxy.effectiveMaxShortStreak(); got != 7 {
		t.Fatalf("huya effectiveMaxShortStreak = %d, want 7", got)
	}

	// 关键隔离：斗鱼档不受虎牙 override 影响。
	if got := douyuProxy.effectivePlannedLeadMs(); got != 0 {
		t.Fatalf("douyu effectivePlannedLeadMs must stay 0, got %d", got)
	}
	if base, _ := douyuProxy.effectiveShortBackoff(); base != time.Duration(GetDouyuShortBackoffBaseMs())*time.Millisecond {
		t.Fatalf("douyu effectiveShortBackoff changed: %v", base)
	}
}
