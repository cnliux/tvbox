package platformlive

import (
	"bufio"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

// ───────── 通用档（其它平台）必须完全独立于虎牙 / 斗鱼 ─────────
//
// 用户要求：B站 / 抖音 / 快手等其它平台不得使用虎牙或斗鱼的方式。
// 这些测试同时守住三件事：三档互不相同、通用档不启用任一方的专属语义、
// 通用档按实际时间戳自适应且既不无条件平移也不按固定窗口强行同轨。

// TestGenericProfileIndependentFromHuyaAndDouyu：关键策略参数三档两两不同，
// 证明通用档没有「借用」虎牙或斗鱼的任何一套取值。
func TestGenericProfileIndependentFromHuyaAndDouyu(t *testing.T) {
	douyu := siteTuningFor("douyu")
	huya := siteTuningFor("huya")
	generic := siteTuningFor("bilibili")

	names := map[string]bool{douyu.name: true, huya.name: true, generic.name: true}
	if len(names) != 3 {
		t.Fatalf("三档档位名必须互不相同：douyu=%q huya=%q generic=%q", douyu.name, huya.name, generic.name)
	}

	type triple struct {
		label string
		h     interface{}
		d     interface{}
		g     interface{}
	}
	triples := []triple{
		{"replayWindowMs", huya.replayWindowMs, douyu.replayWindowMs, generic.replayWindowMs},
		{"replayGuardMaxFrames", huya.replayGuardMaxFrames, douyu.replayGuardMaxFrames, generic.replayGuardMaxFrames},
		{"maxFastReconnectStreak", huya.maxFastReconnectStreak, douyu.maxFastReconnectStreak, generic.maxFastReconnectStreak},
		{"shortSessionMs", huya.shortSessionMs, douyu.shortSessionMs, generic.shortSessionMs},
		{"maxShortStreak", huya.maxShortStreak, douyu.maxShortStreak, generic.maxShortStreak},
		{"shortBackoffBase", huya.shortBackoffBase, douyu.shortBackoffBase, generic.shortBackoffBase},
		{"shortBackoffMax", huya.shortBackoffMax, douyu.shortBackoffMax, generic.shortBackoffMax},
	}
	for _, tr := range triples {
		if tr.g == tr.h || tr.g == tr.d {
			t.Fatalf("通用档 %s 不得与虎牙/斗鱼任一档相同：huya=%v douyu=%v generic=%v",
				tr.label, tr.h, tr.d, tr.g)
		}
	}

	// 时间轴与租约语义：三档互斥，通用档只启用自适应。
	if generic.timelineContinuous || !generic.timelineAuto || generic.leaseAware {
		t.Fatalf("通用档应为 auto 且无连续轴/无租约，实际 continuous=%v auto=%v lease=%v",
			generic.timelineContinuous, generic.timelineAuto, generic.leaseAware)
	}
	if huya.timelineContinuous || huya.timelineAuto || huya.leaseAware {
		t.Fatal("虎牙档位不得被通用档改动（应保持 reset / 无自适应 / 无租约）")
	}
	if !douyu.timelineContinuous || douyu.timelineAuto || !douyu.leaseAware {
		t.Fatal("斗鱼档位不得被通用档改动（应保持 continuous / 租约）")
	}
	modes := map[string]bool{
		huya.timelineModeName():    true,
		douyu.timelineModeName():   true,
		generic.timelineModeName(): true,
	}
	if len(modes) != 3 || generic.timelineModeName() != "auto" {
		t.Fatalf("三档时间轴语义必须互不相同：huya=%s douyu=%s generic=%s",
			huya.timelineModeName(), douyu.timelineModeName(), generic.timelineModeName())
	}
}

// TestGenericProfileForAllOtherSites：只有精确匹配 douyu / huya 才走站点专属档，
// 其余一律通用中性档；大小写/空白变体不得把斗鱼虎牙误判成通用档。
func TestGenericProfileForAllOtherSites(t *testing.T) {
	for _, site := range []string{
		"bilibili", "douyin", "kuaishou", "yy", "douyin-live", "custom",
		"unknown", "default", "", "   ",
	} {
		profile := siteTuningFor(site)
		if profile.name != "generic-live" || profile.timelineModeName() != "auto" {
			t.Fatalf("site=%q 应走通用中性档，实际 name=%q mode=%q",
				site, profile.name, profile.timelineModeName())
		}
	}
	for _, site := range []string{"DOUYU", " douyu ", "DouYu"} {
		if got := siteTuningFor(site).name; got != "douyu-yqk" {
			t.Fatalf("site=%q 应仍为斗鱼专属档，实际 %q", site, got)
		}
	}
	for _, site := range []string{"HuYa", " HUYA ", "huya"} {
		if got := siteTuningFor(site).name; got != "huya-yqk" {
			t.Fatalf("site=%q 应仍为虎牙专属档，实际 %q", site, got)
		}
	}
}

// TestGenericTimelineAutoDecidesByActualTimestamps：通用档按新段实际首帧自适应，
// 既不套虎牙的「无条件 spliceBase 平移」，也不套斗鱼的「60s 固定同轨窗口」。
func TestGenericTimelineAutoDecidesByActualTimestamps(t *testing.T) {
	step := genericSpliceStepMs

	// 首段：没有已交付帧，原样沿用源时间戳。
	if got := genericTimelineOffset(0, 500, step); got != 0 {
		t.Fatalf("首段应 offset=0，实际 %d", got)
	}
	// 同轴 / 连续（含轻微落后、大幅领先）：不平移。
	for _, tc := range []struct{ last, src int64 }{
		{1066, 1200}, {1066, 1066}, {1066, 900}, {1066, 50000},
	} {
		if got := genericTimelineOffset(tc.last, tc.src, step); got != 0 {
			t.Fatalf("last=%d src=%d 应判定同轴 offset=0，实际 %d", tc.last, tc.src, got)
		}
	}
	// 对比虎牙：同样的同轴场景，虎牙仍无条件平移到 maxOut+step。
	huyaProxy := newManualDouyuProxy("huya")
	huyaProxy.haveOut[1] = true
	huyaProxy.lastOutTs[1] = 1066
	if got := huyaProxy.spliceBase(1200); got != 1066+flvProxySpliceStepMs {
		t.Fatalf("虎牙应对同轴场景也平移（用于对比），实际 %d", got)
	}

	// 明显回退（时间戳归零重启）：通用档平移续接，避免整段被 acceptTs 丢弃。
	if want, got := int64(120000)+step-110000, genericTimelineOffset(120000, 110000, step); got != want {
		t.Fatalf("归零重启应平移续接，want %d got %d", want, got)
	}
	// 对比斗鱼：同样 10s 回退仍落在斗鱼 60s 同轨窗口内 → 斗鱼 offset=0；
	// 通用档必须与之不同，否则等于把斗鱼的同轴假设借给了其它平台。
	if d := douyuContinuityOffset(120000, 110000); d != 0 {
		t.Fatalf("斗鱼固定窗口在此场景应 offset=0（用于对比），实际 %d", d)
	}
	if genericTimelineOffset(120000, 110000, step) == 0 {
		t.Fatal("通用档不得沿用斗鱼的固定同轨窗口")
	}
	// 容差是动态的（min(1000ms, lastOut/2)），刚好等于容差不平移，超过即判定重启。
	last := int64(1066)
	tolerance := genericTimelineTolerance(last)
	if got := genericTimelineOffset(last, last-tolerance, step); got != 0 {
		t.Fatalf("容差内回退不应平移，实际 %d", got)
	}
	if got := genericTimelineOffset(last, last-tolerance-1, step); got == 0 {
		t.Fatal("超过容差的回退必须平移续接")
	}
	// 关键回归：流刚开始（已交付 ts 很小）时，时间戳归零到 0 仍必须被识别为
	// 重启并平移；固定 1000ms 容差会把 0 误判为「同轴」，整段被 acceptTs 丢弃。
	if want, got := int64(66)+step, genericTimelineOffset(66, 0, step); got != want {
		t.Fatalf("流起始阶段归零重启应平移，want %d got %d", want, got)
	}
	if tolerance >= genericTimelineResetToleranceMs {
		t.Fatalf("小时间戳容差应被 lastOut/2 收窄，实际 %d", tolerance)
	}
}

// TestGenericDoesNotPatchURLOrLease：通用档绝不套用斗鱼的 URL 补丁 / 租约语义，
// 播放地址原样保留；同一地址在斗鱼档位下才被改写（对照组）。
func TestGenericDoesNotPatchURLOrLease(t *testing.T) {
	wsURL := "https://cdn.example.com/live/a.flv?wsSecret=x&wsTime=y&expire=300&fcdn=ws"
	service := &Service{byID: map[string]Adapter{"douyu": &fakeProxyAdapter{}}}

	proxy, err := newFlvProxy(service, "bilibili", "1001", 0, wsURL, nil)
	if err != nil {
		t.Fatalf("newFlvProxy(bilibili): %v", err)
	}
	defer proxy.close()
	if proxy.tuning.name != "generic-live" || proxy.tuning.leaseAware {
		t.Fatalf("通用档不应启用租约语义：name=%q lease=%v", proxy.tuning.name, proxy.tuning.leaseAware)
	}
	if proxy.initialURL != wsURL || !strings.Contains(proxy.initialURL, "expire=300") {
		t.Fatalf("通用档播放地址应原样保留，实际 %s", proxy.initialURL)
	}

	douyuProxy, err := newFlvProxy(service, "douyu", "1001", 0, wsURL, nil)
	if err != nil {
		t.Fatalf("newFlvProxy(douyu): %v", err)
	}
	defer douyuProxy.close()
	if strings.Contains(douyuProxy.initialURL, "expire=300") || !strings.Contains(douyuProxy.initialURL, "expire=0") {
		t.Fatalf("对照组：斗鱼档位应打 expire=0 补丁，实际 %s", douyuProxy.initialURL)
	}
}

// TestGenericStatsExposeIndependentProfile：statsJSON 暴露独立档位，
// 真机监控可直接确认其它平台没走虎牙/斗鱼路径。
func TestGenericStatsExposeIndependentProfile(t *testing.T) {
	service := &Service{byID: map[string]Adapter{}}
	proxy, err := newFlvProxy(service, "douyin", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy(douyin): %v", err)
	}
	defer proxy.close()
	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(proxy.statsJSON()), &stats); err != nil {
		t.Fatalf("statsJSON 解析失败：%v", err)
	}
	checks := []struct {
		key  string
		want interface{}
	}{
		{"tuning", "generic-live"},
		{"timelineMode", "auto"},
		{"timelineAuto", true},
		{"timelineContinuous", false},
		{"leaseAware", false},
		{"shortStreakMax", float64(-1)},
		{"fastReconnectMax", float64(2)},
	}
	for _, c := range checks {
		if stats[c.key] != c.want {
			t.Fatalf("statsJSON[%s] = %v, want %v", c.key, stats[c.key], c.want)
		}
	}
}

// TestGenericTimelineAutoEndToEnd：真实 consume 路径上，通用档遇到「时间戳归零
// 重启」的新段会平移续接（虎牙语义的数据驱动版本），而不是保持 0 被 acceptTs
// 整段丢弃（斗鱼同轴假设套错平台时的表现）。
func TestGenericTimelineAutoEndToEnd(t *testing.T) {
	var reqCount int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt64(&reqCount, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		if n == 1 {
			writeTestFLVEx(w, []testFrame{
				{ts: 1000, video: true, keyframe: true},
				{ts: 1000, audio: true},
				{ts: 1033, video: true},
				{ts: 1066, audio: true},
			})
			return
		}
		// 第二段：时间戳从 0 重启（非斗鱼同轴、非虎牙固定归零假设，纯数据驱动）。
		writeTestFLVEx(w, []testFrame{
			{ts: 0, video: true, keyframe: true},
			{ts: 0, audio: true},
			{ts: 33, video: true},
			{ts: 66, audio: true},
		})
	}))
	defer upstream.Close()

	service := &Service{byID: map[string]Adapter{"bilibili": &fakeProxyAdapter{streamURL: upstream.URL}}}
	proxy, err := newFlvProxy(service, "bilibili", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()
	resp, err := http.Get(proxy.url)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer resp.Body.Close()
	frames, err := readTestFLVTagsEx(bufio.NewReader(resp.Body), 8)
	if err != nil && len(frames) < 8 {
		t.Fatalf("读取输出不足：%v（已读 %d）", err, len(frames))
	}
	// 第二段必须被平移到 1066+step 之后，且严格递增；若沿用斗鱼同轴假设，
	// 这些帧会保持 0/33/66 并被 acceptTs 全部丢弃，凑不满 8 帧。
	minimum := int64(1066) + genericSpliceStepMs
	last := int64(-1)
	for i := 4; i < 8 && i < len(frames); i++ {
		if frames[i].ts < minimum {
			t.Fatalf("第 %d 帧 ts=%d 低于续接位置 %d（归零段未被平移）：%v",
				i, frames[i].ts, minimum, frames)
		}
		if frames[i].ts < last {
			t.Fatalf("输出时间轴回退：%d < %d（%v）", frames[i].ts, last, frames)
		}
		last = frames[i].ts
	}
	if len(frames) < 8 {
		t.Fatalf("归零重启段应被完整续接，实际只输出 %d 帧：%v", len(frames), frames)
	}
}

// TestGenericTimelineContinuousEndToEnd：同轴新段（源时间戳接续第一段）在通用档
// 下必须原样沿用，不得套用虎牙的 spliceBase 无条件平移（那会把音视频相对位置拉开）。
// 与上面的归零重启用例成对，证明通用档是「看数据」而非「猜平台」。
func TestGenericTimelineContinuousEndToEnd(t *testing.T) {
	var reqCount int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n := atomic.AddInt64(&reqCount, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		if n == 1 {
			writeTestFLVEx(w, []testFrame{
				{ts: 1000, video: true, keyframe: true},
				{ts: 1000, audio: true},
				{ts: 1033, video: true},
				{ts: 1066, audio: true},
			})
			return
		}
		writeTestFLVEx(w, []testFrame{
			{ts: 1200, video: true, keyframe: true},
			{ts: 1200, audio: true},
			{ts: 1233, video: true},
			{ts: 1233, audio: true},
		})
	}))
	defer upstream.Close()

	service := &Service{byID: map[string]Adapter{"bilibili": &fakeProxyAdapter{streamURL: upstream.URL}}}
	proxy, err := newFlvProxy(service, "bilibili", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()
	resp, err := http.Get(proxy.url)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer resp.Body.Close()
	frames, err := readTestFLVTagsEx(bufio.NewReader(resp.Body), 8)
	if err != nil && len(frames) < 8 {
		t.Fatalf("读取输出不足：%v（已读 %d）", err, len(frames))
	}
	wantSecond := []frameInfo{
		{ts: 1200, video: true, keyframe: true},
		{ts: 1200, audio: true},
		{ts: 1233, video: true},
		{ts: 1233, audio: true},
	}
	for i, want := range wantSecond {
		got := frames[4+i]
		if got.ts != want.ts || got.video != want.video || got.audio != want.audio {
			t.Fatalf("同轴第二段第 %d 帧应为 %+v，实际 %+v（全部 %v）", i, want, got, frames)
		}
	}
}
