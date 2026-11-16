package platformlive

import (
	"testing"
	"time"
)

// 调优 setter 默认值与范围钳制：按钮误触的非法值不得污染全局。
func TestDouyuTuningDefaultsAndClamp(t *testing.T) {
	// 恢复默认
	defer func() {
		SetDouyuLeaseRefreshLeadMs(0)
		SetDouyuIdleReadTimeoutMs(0)
		SetDouyuShortBackoffBaseMs(0)
		SetDouyuShortBackoffMaxMs(0)
	}()

	if got := GetDouyuLeaseRefreshLeadMs(); got != defaultDouyuLeaseRefreshLeadMs {
		t.Fatalf("默认续期提前量 = %d, 期望 %d", got, defaultDouyuLeaseRefreshLeadMs)
	}
	if got := GetDouyuIdleReadTimeoutMs(); got != defaultDouyuIdleReadTimeoutMs {
		t.Fatalf("默认空闲超时 = %d, 期望 %d", got, defaultDouyuIdleReadTimeoutMs)
	}

	// 合法值生效
	SetDouyuLeaseRefreshLeadMs(60000)
	if got := GetDouyuLeaseRefreshLeadMs(); got != 60000 {
		t.Fatalf("设置后续期提前量 = %d, 期望 60000", got)
	}
	SetDouyuIdleReadTimeoutMs(30000)
	if got := douyuIdleReadTimeout(); got != 30*time.Second {
		t.Fatalf("设置后空闲超时 = %s, 期望 30s", got)
	}

	// 非法值被忽略（保持上一个合法值）
	SetDouyuLeaseRefreshLeadMs(1) // < 5000
	if got := GetDouyuLeaseRefreshLeadMs(); got != 60000 {
		t.Fatalf("非法提前量不应生效，实际 = %d", got)
	}

	// 0 恢复默认
	SetDouyuLeaseRefreshLeadMs(0)
	if got := GetDouyuLeaseRefreshLeadMs(); got != defaultDouyuLeaseRefreshLeadMs {
		t.Fatalf("0 应恢复默认，实际 = %d", got)
	}
}

// 续期提前量变化时，租约续期时刻随之变化（可调性端到端验证）。
func TestDouyuLeaseDeadlineFollowsOverride(t *testing.T) {
	defer SetDouyuLeaseRefreshLeadMs(0)
	now := time.Unix(1700000000, 0)
	url := "https://hwflv.douyucdn2.cn/live/xxx.flv?expire=300&fcdn=ws"

	SetDouyuLeaseRefreshLeadMs(30000)
	d30 := douyuLeaseDeadline(url, now)
	SetDouyuLeaseRefreshLeadMs(90000)
	d90 := douyuLeaseDeadline(url, now)

	if !d30.After(now) || !d90.After(now) {
		t.Fatal("expire=300 且提前量 < 300s 时应有续期时刻")
	}
	// 提前量越大，续期越早（deadline 越小）
	if !d90.Before(d30) {
		t.Fatalf("提前量 90s 的续期时刻应早于 30s：d90=%s d30=%s", d90, d30)
	}
}
