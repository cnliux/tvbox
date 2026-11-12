package platformlive

import (
	"testing"
	"time"
)

// TestSetPacerTargetBufferMs 验证运行时目标缓冲 setter/getter 生效，且负值被忽略。
func TestSetPacerTargetBufferMs(t *testing.T) {
	defer SetPacerTargetBufferMs(5000) // 还原默认，避免影响其它测试

	if got := GetPacerTargetBufferMs(); got != 5000 {
		t.Fatalf("默认目标缓冲应为 5000，实际 %d", got)
	}

	SetPacerTargetBufferMs(8000)
	if got := GetPacerTargetBufferMs(); got != 8000 {
		t.Fatalf("设置 8000 后应读到 8000，实际 %d", got)
	}

	SetPacerTargetBufferMs(0)
	if got := GetPacerTargetBufferMs(); got != 0 {
		t.Fatalf("关闭（0）后应读到 0，实际 %d", got)
	}

	// 负值必须被忽略，保持上一次有效值。
	SetPacerTargetBufferMs(-1000)
	if got := GetPacerTargetBufferMs(); got != 0 {
		t.Fatalf("负值应被忽略，实际 %d", got)
	}

	// 确认初始化默认值非负、且类型可用。
	SetPacerTargetBufferMs(5000)
	if GetPacerTargetBufferMs() != 5000 {
		t.Fatal("还原 5000 失败")
	}
	_ = time.Second
}
