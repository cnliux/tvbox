package platformlive

import "testing"

// kuaishouURLGroups 画质分组展开为候选线路的逻辑测试。
func TestKuaishouExpandQualityGroups(t *testing.T) {
	groups := newKuaishouURLGroups()
	// 最佳画质（蓝光 level=3）：单一 CDN 两条不同地址，去重后 2 条。
	groups.add(kuaishouQualityKey{level: 3, name: "蓝光"}, LiveStreamCandidate{CDN: "蓝光CDN", URL: "https://a.example.com/1.flv", Protocol: "flv"})
	groups.add(kuaishouQualityKey{level: 3, name: "蓝光"}, LiveStreamCandidate{CDN: "蓝光CDN", URL: "https://a.example.com/2.flv", Protocol: "flv"})
	// 高清（level=2）
	groups.add(kuaishouQualityKey{level: 2, name: "高清"}, LiveStreamCandidate{CDN: "高清CDN", URL: "https://a.example.com/3.flv", Protocol: "flv"})
	// 标清（level=1）
	groups.add(kuaishouQualityKey{level: 1, name: "标清"}, LiveStreamCandidate{CDN: "标清CDN", URL: "https://a.example.com/4.flv", Protocol: "flv"})

	best, _ := groups.best()
	candidates := kuaishouExpandQualityGroups(groups, best)

	// 最佳档 2 条 + 高清 1 + 标清 1 = 4 条
	if len(candidates) != 4 {
		t.Fatalf("candidates = %+v", candidates)
	}
	// 首条是最佳画质默认线路，CDN 名不带后缀
	if candidates[0].CDN != "蓝光CDN" {
		t.Fatalf("candidates[0].CDN = %q, want 蓝光CDN", candidates[0].CDN)
	}
	// 其余档位 CDN 名带画质后缀，按 level 降序
	if candidates[2].CDN != "高清CDN·高清" {
		t.Fatalf("candidates[2].CDN = %q", candidates[2].CDN)
	}
	if candidates[3].CDN != "标清CDN·标清" {
		t.Fatalf("candidates[3].CDN = %q", candidates[3].CDN)
	}
}

func TestKuaishouExpandQualityGroupsSingleQuality(t *testing.T) {
	groups := newKuaishouURLGroups()
	groups.add(kuaishouQualityKey{level: 3, name: "蓝光"}, LiveStreamCandidate{CDN: "蓝光CDN", URL: "https://a.example.com/1.flv", Protocol: "flv"})
	best, _ := groups.best()
	candidates := kuaishouExpandQualityGroups(groups, best)
	if len(candidates) != 1 || candidates[0].CDN != "蓝光CDN" {
		t.Fatalf("candidates = %+v", candidates)
	}
}

func TestKuaishouExpandQualityGroupsEmpty(t *testing.T) {
	groups := newKuaishouURLGroups()
	best, _ := groups.best()
	if got := kuaishouExpandQualityGroups(groups, best); got != nil {
		t.Fatalf("got = %+v, want nil", got)
	}
}
