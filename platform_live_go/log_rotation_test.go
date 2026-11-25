package platformlive

import "testing"

// P2-11 日志轮转纯逻辑单测：8MB 阈值判断与历史路径命名。
func TestShouldRotateLog(t *testing.T) {
	cases := []struct {
		name      string
		written   int64
		threshold int64
		want      bool
	}{
		{"below threshold", 7<<20, 8 << 20, false},
		{"at threshold", 8 << 20, 8 << 20, true},
		{"above threshold", 9<<20, 8 << 20, true},
		{"disabled when threshold zero", 9 << 20, 0, false},
		{"disabled when threshold negative", 9 << 20, -1, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := ShouldRotateLog(c.written, c.threshold); got != c.want {
				t.Fatalf("ShouldRotateLog(%d, %d) = %v, want %v", c.written, c.threshold, got, c.want)
			}
		})
	}
}

func TestRotatedLogPath(t *testing.T) {
	if got := RotatedLogPath("/data/data/com.tvbox.app/files/go_stderr.log"); got != "/data/data/com.tvbox.app/files/go_stderr.log.1" {
		t.Fatalf("RotatedLogPath = %q, want .1 suffix", got)
	}
}

func TestLogRotateKeepFiles(t *testing.T) {
	if LogRotateKeepFiles() < 1 {
		t.Fatalf("LogRotateKeepFiles must keep at least one history file")
	}
}
