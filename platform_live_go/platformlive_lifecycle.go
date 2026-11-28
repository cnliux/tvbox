package platformlive

// platformlive_lifecycle.go
//
// 为 gomobile 导出的 Init/Close/Cancel 提供全局生命周期状态机：
// - 状态流转全部基于 atomic.CompareAndSwapInt32（CAS），避免锁竞争；
// - activeRequests 记录在飞请求的 cancel 函数与代际令牌，Close 时统一取消；
// - wg 计数在飞请求，UnregisterRequest 与 Close 通过代际令牌保证只减一次；
// - 所有注释使用中文，便于 Kotlin 侧对照 P30 方案审查。

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"sync/atomic"
	"time"
)

// LifecycleState 描述 LifecycleManager 的当前状态。
type LifecycleState int32

const (
	StateUninitialized LifecycleState = iota // 初始：尚未 Init
	StateInitializing                        // Init 进行中
	StateReady                               // 可正常接收请求
	StateStopping                            // Close 已触发，正在等待在飞请求退出
	StateStopped                             // Close 完成，不再接受新请求
)

// String 便于日志与测试失败信息打印。
func (s LifecycleState) String() string {
	switch s {
	case StateUninitialized:
		return "Uninitialized"
	case StateInitializing:
		return "Initializing"
	case StateReady:
		return "Ready"
	case StateStopping:
		return "Stopping"
	case StateStopped:
		return "Stopped"
	default:
		return fmt.Sprintf("Unknown(%d)", int32(s))
	}
}

// 生命周期相关错误（供上层 errors.Is 判断）。
var (
	// ErrNotReady 表示当前状态不允许注册新请求。
	ErrNotReady = errors.New("platformlive: 生命周期未就绪，无法注册请求")
	// ErrInvalidTransition 表示当前状态不允许执行该操作。
	ErrInvalidTransition = errors.New("platformlive: 生命周期状态不允许该操作")
)

// requestHandle 保存单个在飞请求的取消函数与代际令牌。
// generation 用于 UnregisterRequest 与 Close 之间协调 wg.Done 归属：
// 只有观察到最新代际的注销者才执行 wg.Done，确保每个请求只减一次。
type requestHandle struct {
	cancel     context.CancelFunc
	generation uint64
}

// LifecycleManager 管理全局 Init/Close 状态与在飞请求。
type LifecycleManager struct {
	state atomic.Int32
	mu    sync.RWMutex
	// stopCh 在 Init 成功后关闭，供派生 ctx 的 Done 信号兜底。
	stopCh chan struct{}
	// activeRequests 记录在飞请求，key 为调用方传入的请求 ID。
	activeRequests map[string]requestHandle
	// generation 单调递增，用于令牌校验。
	generation uint64
	// wg 计数在飞请求，Close 等待其归零或超时。
	wg sync.WaitGroup
}

// NewLifecycleManager 构造一个处于 Uninitialized 的管理器。
func NewLifecycleManager() *LifecycleManager {
	m := &LifecycleManager{
		stopCh:         make(chan struct{}),
		activeRequests: make(map[string]requestHandle),
	}
	m.state.Store(int32(StateUninitialized))
	return m
}

// Init 幂等地把状态从 Uninitialized 推进到 Ready。
// 重复调用、已在 Ready/Stopping/Stopped 的情况下均直接返回 nil。
func (m *LifecycleManager) Init(ctx context.Context) error {
	if ctx == nil {
		ctx = context.Background()
	}
	// CAS: Uninitialized -> Initializing，只有赢家执行真正的初始化。
	if !m.state.CompareAndSwap(int32(StateUninitialized), int32(StateInitializing)) {
		// 其余情况一律视为幂等成功（并发初始化中、已就绪、已关闭）。
		return nil
	}
	// 这里预留真实初始化钩子：gomobile 接线后可在 CAS 成功分支内调用
	// platformlive.go 的 Init(signScript, catalogJSON, ...)。
	_ = ctx
	m.mu.Lock()
	select {
	case <-m.stopCh:
		// 初始化期间已被 Close：不再重置 stopCh，交给 Close 收尾。
	default:
		close(m.stopCh)
	}
	m.mu.Unlock()
	// 必须用 CAS 而非 Store：并发 Close 会把状态推进到 Stopping/Stopped，
	// 若这里无条件 Store(Ready) 会把已关闭状态覆盖回 Ready——CI 偶发
	// 「并发结束后状态应为 Stopped，实际 Ready」即此竞态，线上则表现为
	// 退出后 lifecycle 假就绪、后续请求被误放行。
	m.state.CompareAndSwap(int32(StateInitializing), int32(StateReady))
	return nil
}

// State 返回当前生命周期状态快照。
func (m *LifecycleManager) State() LifecycleState {
	return LifecycleState(m.state.Load())
}

// RegisterRequest 注册一个可取消请求，返回带 cancel 的子 context 及代际令牌。
// 代际令牌用于 UnregisterRequest 时确认本地是否仍持有最新请求，避免与 Close 重复计数。
// 仅在 StateReady 时允许注册；否则返回 ErrNotReady。
func (m *LifecycleManager) RegisterRequest(id string) (context.Context, uint64, error) {
	// 快速路径：非 Ready 直接拒绝，避免无谓加锁。
	if m.State() != StateReady {
		return nil, 0, fmt.Errorf("%w: state=%s", ErrNotReady, m.State())
	}
	reqCtx, cancel := context.WithCancel(context.Background())
	m.mu.Lock()
	// 二次确认：Close 可能在我们拿锁前已进入 Stopping。
	if LifecycleState(m.state.Load()) != StateReady {
		m.mu.Unlock()
		cancel()
		return nil, 0, fmt.Errorf("%w: state=%s", ErrNotReady, m.State())
	}
	if m.activeRequests == nil {
		m.activeRequests = make(map[string]requestHandle)
	}
	if old, ok := m.activeRequests[id]; ok {
		// 同 ID 重复注册：取消旧请求，代际令牌失效，其 wg 由 Close 兜底。
		old.cancel()
	} else {
		m.wg.Add(1)
	}
	m.generation++
	gen := m.generation
	m.activeRequests[id] = requestHandle{cancel: cancel, generation: gen}
	m.mu.Unlock()
	return reqCtx, gen, nil
}

// UnregisterRequest 注销请求并释放其 cancel 函数，重复调用安全。
// 仅当本地仍持有最新代际令牌时才执行 wg.Done，避免与 Close 重复计数。
func (m *LifecycleManager) UnregisterRequest(id string, generation uint64) {
	m.mu.Lock()
	handle, ok := m.activeRequests[id]
	if !ok || handle.generation != generation {
		// 已被 Close 取消并清理，wg.Done 由 Close 负责。
		m.mu.Unlock()
		return
	}
	delete(m.activeRequests, id)
	m.wg.Done()
	m.mu.Unlock()
	handle.cancel()
}

// Close 将状态推进到 Stopped，并取消所有在飞请求。
// - 幂等：已 Stopped 直接返回 nil；Stopping 中重入返回 ErrInvalidTransition。
// - timeout 内未排空的请求会强制返回超时错误，但仍完成 Stopping -> Stopped。
func (m *LifecycleManager) Close(timeout time.Duration) error {
	// CAS: Ready -> Stopping，只有赢家执行关闭流程。
	if !m.state.CompareAndSwap(int32(StateReady), int32(StateStopping)) {
		// 初始化中或未初始化：同样推进到 Stopping。关闭是终态语义——
		// 若 Close 先于 Init 发生（状态仍 Uninitialized）而在此返回错误，
		// 后续 Init 就能 CAS 到 Initializing→Ready，把已关闭实例「复活」。
		// CI 偶发「并发结束后状态应为 Stopped，实际 Ready」即由此路径产生。
		if !m.state.CompareAndSwap(int32(StateInitializing), int32(StateStopping)) &&
			!m.state.CompareAndSwap(int32(StateUninitialized), int32(StateStopping)) {
			switch m.State() {
			case StateStopped:
				return nil
			case StateStopping:
				return ErrInvalidTransition
			default:
				return fmt.Errorf("%w: state=%s", ErrInvalidTransition, m.State())
			}
		}
	}

	// 取消所有在飞请求，触发其 context.Done()。
	// 注意：这里不删除映射、不执行 wg.Done——由请求方 UnregisterRequest 负责，
	// 这样 wg 才能真实反映「在飞请求是否已退出」，卡死请求才能触发超时。
	m.mu.Lock()
	for _, handle := range m.activeRequests {
		handle.cancel()
	}
	m.mu.Unlock()

	// 等待在飞请求排空，最多 timeout。
	done := make(chan struct{})
	go func() {
		m.wg.Wait()
		close(done)
	}()

	var closeErr error
	if timeout <= 0 {
		<-done
	} else {
		select {
		case <-done:
		case <-time.After(timeout):
			closeErr = fmt.Errorf("platformlive: Close 超时（%s），强制返回", timeout)
		}
	}

	m.state.Store(int32(StateStopped))
	return closeErr
}

// ActiveRequestCount 返回当前在飞请求数量。
func (m *LifecycleManager) ActiveRequestCount() int {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return len(m.activeRequests)
}

// defaultLifecycle 全局单例，供 gomobile 导出的 Init/Close 接线使用。
var defaultLifecycle = NewLifecycleManager()
