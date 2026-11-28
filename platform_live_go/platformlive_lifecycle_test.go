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

// 以下三个用例把 TestLifecycleConcurrentInitCloseRegister 依赖 goroutine 调度
// 碰运气的交错，改成直接构造临界状态的确定性验证。这样即使 shuffle 换序、
// CI 机器快慢不同，也必须稳定通过；一旦有人把 CAS 改回 Store 会立刻失败。

// TestLifecycleInitCannotResurrectTerminalState 锁定不变量：
// 处于 Stopping/Stopped 的实例，Init 必须幂等返回且不把状态改回 Ready。
// 旧实现收尾用 Store(Ready)，并发 Close 时会把终态覆盖成 Ready（CI 偶发红）。
func TestLifecycleInitCannotResurrectTerminalState(t *testing.T) {
	for _, terminal := range []LifecycleState{StateStopping, StateStopped} {
		m := NewLifecycleManager()
		m.state.Store(int32(terminal))
		if err := m.Init(context.Background()); err != nil {
			t.Fatalf("Init 在 %s 状态应幂等返回 nil，实际 %v", terminal, err)
		}
		if got := m.State(); got != terminal {
			t.Fatalf("Init 不得把 %s 复活为其它状态，实际 %s", terminal, got)
		}
	}
}

// TestLifecycleCloseFromInitializingIsTerminal 锁定不变量：
// Close 与 Init 交错时，无论 Init 是否处于 Initializing，Close 都必须推进到
// Stopped，且最后的 Init CAS(Initializing->Ready) 不能再覆盖终态。
func TestLifecycleCloseFromInitializingIsTerminal(t *testing.T) {
	m := NewLifecycleManager()
	m.state.Store(int32(StateInitializing))
	if err := m.Close(time.Second); err != nil {
		t.Fatalf("Initializing 状态下 Close 不应报错，实际 %v", err)
	}
	if got := m.State(); got != StateStopped {
		t.Fatalf("Initializing + Close 后状态应为 Stopped，实际 %s", got)
	}
	// 模拟迟到的 Init 收尾：CAS 必须失败，状态保持 Stopped。
	m.state.CompareAndSwap(int32(StateInitializing), int32(StateReady))
	if got := m.State(); got != StateStopped {
		t.Fatalf("迟到的 Init 收尾不得覆盖 Close 终态，实际 %s", got)
	}
}

// TestLifecycleCloseBeforeInitIsTerminal 锁定不变量：
// Close 先于 Init 发生（Uninitialized）时必须进入终态，后续 Init 不得复活。
func TestLifecycleCloseBeforeInitIsTerminal(t *testing.T) {
	m := NewLifecycleManager()
	if err := m.Close(time.Second); err != nil {
		t.Fatalf("Uninitialized 状态下 Close 应成功（关闭是终态语义），实际 %v", err)
	}
	if got := m.State(); got != StateStopped {
		t.Fatalf("Close 后状态应为 Stopped，实际 %s", got)
	}
	if err := m.Init(context.Background()); err != nil {
		t.Fatalf("Close 后 Init 应幂等返回 nil，实际 %v", err)
	}
	if got := m.State(); got != StateStopped {
		t.Fatalf("Close 后 Init 不得复活为 Ready，实际 %s", got)
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
