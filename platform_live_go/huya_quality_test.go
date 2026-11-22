package platformlive

import "testing"

// 覆盖 huya_quality.go pickHuyaRate 的选流规则：
// 未设上限取最高；设上限选 ≤上限的最高非零码率；全高时取最低非零码率。
func TestPickHuyaRate(t *testing.T) {
	cases := []struct {
		name       string
		rates      []huyaRate
		maxBitRate int64
		wantRate   int64
		wantName   string
	}{
		{
			name:       "无上限取最高",
			rates:      []huyaRate{{8000, "蓝光8M"}, {2000, "超清"}, {550, "高清"}},
			maxBitRate: 0,
			wantRate:   8000,
			wantName:   "蓝光8M",
		},
		{
			name:       "上限内取最高非零",
			rates:      []huyaRate{{8000, "蓝光8M"}, {2000, "超清"}, {550, "高清"}},
			maxBitRate: 4000,
			wantRate:   2000,
			wantName:   "超清",
		},
		{
			name:       "全部高于上限取最低非零",
			rates:      []huyaRate{{8000, "蓝光8M"}, {2000, "超清"}, {550, "高清"}},
			maxBitRate: 300,
			wantRate:   550,
			wantName:   "高清",
		},
		{
			name:       "原画排在非零之后不参与上限匹配",
			rates:      []huyaRate{{8000, "蓝光8M"}, {2000, "超清"}, {0, "原画"}},
			maxBitRate: 4000,
			wantRate:   2000,
			wantName:   "超清",
		},
		{
			name:       "只含原画时回退原画",
			rates:      []huyaRate{{0, "原画"}},
			maxBitRate: 2000,
			wantRate:   0,
			wantName:   "原画",
		},
		{
			name:       "空表回退原画",
			rates:      nil,
			maxBitRate: 2000,
			wantRate:   0,
			wantName:   "原画",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rate, quality := pickHuyaRate(tc.rates, tc.maxBitRate)
			if rate != tc.wantRate || quality != tc.wantName {
				t.Fatalf("pickHuyaRate(%v, cap=%d) = (%d, %q), want (%d, %q)",
					tc.rates, tc.maxBitRate, rate, quality, tc.wantRate, tc.wantName)
			}
		})
	}
}
