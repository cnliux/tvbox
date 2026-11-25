package platformlive

// platformlive_lifecycle_test.go
//
// 覆盖 Init/Close/RegisterRequest 的状态机行为与并发安全，全部使用 -race 运行。
// 测试注释使用中文，便于对照 P30 方案的验收点逐条核对。

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

// newTestLifecycle 构造一个已就绪的管理器，供多数用例复用。
func newTestLifecycle(t *testing.T) *LifecycleManager {
	t.Helper()
	m := NewLifecycleManager()
	if err := m.Init(context.Background()); err != nil {
		t.Fatalf("Init 失败: %v", err)
	}
	if m.State() != StateReady {
		t.Fatalf("Init 后状态应为 Ready，实际 %s", m.State())
	}
	return m
}

func TestLifecycleInitialStateUninitialized(t *testing.T) {
	m := NewLifecycleManager()
	if got := m.State(); got != StateUninitialized {
		t.Fatalf("初始状态应为 Uninitialized，实际 %s", got)
	}
}

func TestLifecycleInitTransitionsToReady(t *testing.T) {
	m := NewLifecycleManager()
	if err := m.Init(context.Background()); err != nil {
		t.Fatalf("Init 不应报错: %v", err)
	}
	if got := m.State(); got != StateReady {
		t.Fatalf("Init 后状态应为 Ready，实际 %s", got)
	}
}

func TestLifecycleInitIdempotent(t *testing.T) {
	m := NewLifecycleManager()
	for i := 0; i < 3; i++ {
		if err := m.Init(context.Background()); err != nil {
			t.Fatalf("第 %d 次 Init 不应报错: %v", i, err)
		}
	}
	if got := m.State(); got != StateReady {
		t.Fatalf("重复 Init 后状态应仍为 Ready，实际 %s", got)
	}
}

func TestLifecycleInitIdempotentAfterClose(t *testing.T) {
	m := newTestLifecycle(t)
	if err := m.Close(time.Second); err != nil {
		t.Fatalf("Close 失败: %v", err)
	}
	// Close 之后再 Init 也应幂等返回 nil，不再重启。
	if err := m.Init(context.Background()); err != nil {
		t.Fatalf("Close 后 Init 应幂等返回 nil: %v", err)
	}
	if got := m.State(); got != StateStopped {
		t.Fatalf("Close 后再次 Init 状态应保持 Stopped，实际 %s", got)
	}
}

func TestLifecycleRegisterIncrementsActiveCount(t *testing.T) {
	m := newTestLifecycle(t)
	ctx, gen, err := m.RegisterRequest("req-1")
	if err != nil {
		t.Fatalf("RegisterRequest 失败: %v", err)
	}
	if ctx == nil {
		t.Fatal("RegisterRequest 返回的 ctx 不应为 nil")
	}
	if got := m.ActiveRequestCount(); got != 1 {
		t.Fatalf("注册后 ActiveRequestCount 应为 1，实际 %d", got)
	}
	m.UnregisterRequest("req-1", gen)
}

func TestLifecycleUnregisterDecrementsActiveCount(t *testing.T) {
	m := newTestLifecycle(t)
	_, gen, err := m.RegisterRequest("req-1")
	if err != nil {
		t.Fatalf("RegisterRequest 失败: %v", err)
	}
	m.UnregisterRequest("req-1", gen)
	if got := m.ActiveRequestCount(); got != 0 {
		t.Fatalf("注销后 ActiveRequestCount 应为 0，实际 %d", got)
	}
	// 重复注销（代际令牌已失效）应安全无副作用。
	m.UnregisterRequest("req-1", gen)
	if got := m.ActiveRequestCount(); got != 0 {
		t.Fatalf("重复注销后 ActiveRequestCount 应仍为 0，实际 %d", got)
	}
}

func TestLifecycleCloseTransitionsToStopped(t *testing.T) {
	m := newTestLifecycle(t)
	if err := m.Close(time.Second); err != nil {
		t.Fatalf("Close 失败: %v", err)
	}
	if got := m.State(); got != StateStopped {
		t.Fatalf("Close 后状态应为 Stopped，实际 %s", got)
	}
}

func TestLifecycleCloseIdempotent(t *testing.T) {
	m := newTestLifecycle(t)
	if err := m.Close(time.Second); err != nil {
		t.Fatalf("首次 Close 失败: %v", err)
	}
	// 第二次 Close 应幂等返回 nil。
	if err := m.Close(time.Second); err != nil {
		t.Fatalf("重复 Close 应返回 nil，实际 %v", err)
	}
	if got := m.State(); got != StateStopped {
		t.Fatalf("重复 Close 后状态应仍为 Stopped，实际 %s", got)
	}
}

func TestLifecycleCloseCancelsActiveRequests(t *testing.T) {
	m := newTestLifecycle(t)
	ctx, gen, err := m.RegisterRequest("long-poll")
	if err != nil {
		t.Fatalf("RegisterRequest 失败: %v", err)
	}
	done := make(chan struct{})
	go func() {
		<-ctx.Done()
		// 请求方收到取消信号后按真实生命周期注销自己，Close 才能在 timeout 内排空。
		m.UnregisterRequest("long-poll", gen)
		close(done)
	}()
	if err := m.Close(time.Second); err != nil {
		t.Fatalf("Close 失败: %v", err)
	}
	if got := m.ActiveRequestCount(); got != 0 {
		t.Fatalf("Close 排空后 ActiveRequestCount 应为 0，实际 %d", got)
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Close 未取消在飞请求的 context")
	}
}

func TestLifecycleCloseTimeoutForcesReturn(t *testing.T) {
	m := newTestLifecycle(t)
	if _, _, err := m.RegisterRequest("stuck"); err != nil {
		t.Fatalf("RegisterRequest 失败: %v", err)
	}
	start := time.Now()
	err := m.Close(30 * time.Millisecond)
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("存在未注销请求时 Close 应返回超时错误")
	}
	if elapsed > 2*time.Second {
		t.Fatalf("Close 应在 timeout 后强制返回，实际耗时 %s", elapsed)
	}
	if got := m.State(); got != StateStopped {
		t.Fatalf("Close 超时后状态仍应为 Stopped，实际 %s", got)
	}
}

func TestLifecycleRegisterFailsWhenNotReady(t *testing.T) {
	m := NewLifecycleManager()
	if _, _, err := m.RegisterRequest("req-1"); !errors.Is(err, ErrNotReady) {
		t.Fatalf("Uninitialized 状态注册应返回 ErrNotReady，实际 %v", err)
	}
	if err := m.Init(context.Background()); err != nil {
		t.Fatalf("Init 失败: %v", err)
	}
	if err := m.Close(time.Second); err != nil {
		t.Fatalf("Close 失败: %v", err)
	}
	if _, _, err := m.RegisterRequest("req-2"); !errors.Is(err, ErrNotReady) {
		t.Fatalf("Stopped 状态注册应返回 ErrNotReady，实际 %v", err)
	}
}

func TestLifecycleConcurrentInitCloseRegister(t *testing.T) {
	m := NewLifecycleManager()
	var wg sync.WaitGroup
	errCh := make(chan error, 64)
	// 并发 Init。
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := m.Init(context.Background()); err != nil {
				errCh <- err
			}
		}()
	}
	// 并发 Register/Unregister。
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			id := "req-concurrent"
			_, gen, err := m.RegisterRequest(id)
			if err != nil {
				// 非 Ready 阶段失败是合法的。
				return
			}
			m.UnregisterRequest(id, gen)
		}()
	}
	// 并发 Close。
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_ = m.Close(time.Second)
		}()
	}
	wg.Wait()
	close(errCh)
	for err := range errCh {
		t.Fatalf("并发 Init 出现意外错误: %v", err)
	}
	if got := m.State(); got != StateStopped {
		t.Fatalf("并发结束后状态应为 Stopped，实际 %s", got)
	}
}
