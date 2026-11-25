//go:build android

package platformlive

/*
#include <android/log.h>
*/
import "C"

import (
	"bufio"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

var runtimeLogcatOnce sync.Once

// log rotation 常量定义在 log_rotation.go（无 build tag，可单测）。

// RedirectRuntimeLogcat 把 Go 进程的 stdout/stderr（fd 1/2）重定向到
// Android logcat（tag=TVBoxGoRuntime）+ 设备端文件 go_stderr.log。
//
// 背景：gomobile bind 的库模式没有任何 stderr 输出，Go runtime 的
// fatal error（panic 栈、OOM、SIGSEGV 时的 goroutine dump）全部直接写入
// fd 2，在设备上丢失 → TV 端闪退后拿不到任何 Go 侧栈。
// 注意：runtime 的崩溃输出不走 os.Stderr 变量，而是直接 write(2)，
// 因此必须在 fd 层做 dup 重定向，仅替换 os.Stderr 变量是无效的。
//
// 为什么还要落文件：fatal error 打印 goroutine dump 与 SIGABRT 之间只有
// 几十毫秒，dump 尚未读完的管道数据会随进程死亡一起丢失（实测只捞到
// OOM 那一行）。写文件则数据已在内核页缓存，进程死后仍然可读，
// 事后 adb pull /data/data/com.tvbox.app/files/go_stderr.log 即可拿到
// 完整崩溃栈。
//
// P2-11 改进：
//   - 去掉 O_SYNC（每写必刷盘会在日志 goroutine 上做同步磁盘 I/O，弱盒子掉帧），
//     改为 bufio.Writer + 1s tick flush（崩了最多丢 1s，可接受）；
//   - 单文件 8MB 轮转，防 go_stderr.log 无限涨满存储；
//   - 支持 stopRuntimeLogcat() 反向关闭，优雅 flush。
func RedirectRuntimeLogcat() {
	runtimeLogcatOnce.Do(func() {
		r, w, err := os.Pipe()
		if err != nil {
			return
		}
		// fd 1/2 都指向管道写端。arm64 没有 dup2 系统调用，用 dup3。
		for fd := uintptr(1); fd <= 2; fd++ {
			_, _, errno := syscall.Syscall(syscall.SYS_DUP3, uintptr(w.Fd()), fd, 0)
			if errno != 0 {
				return
			}
		}
		_ = w.Close() // fd1/fd2 已引用同一管道，原句柄不再需要

		// 文件优先放应用私有 files 目录；目录名固定为本应用包名。
		// 失败（目录不存在/权限）则退化为纯 logcat。
		var sink *os.File
		var logPath string
		if dir := "/data/data/com.tvbox.app/files"; dir != "" {
			if st, statErr := os.Stat(dir); statErr == nil && st.IsDir() {
				logPath = filepath.Join(dir, "go_stderr.log")
				if f, openErr := os.OpenFile(logPath,
					os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); openErr == nil {
					sink = f
				}
			}
		}
		tag := C.CString("TVBoxGoRuntime")

		stopCh := make(chan struct{})
		redirectStopCh = stopCh

		go func() {
			defer recoverToErrorOnly() // goroutine panic 兜底，避免重定向失败拖垮进程

			var buffered *bufio.Writer
			var flushedBytes int64
			if sink != nil {
				buffered = bufio.NewWriterSize(sink, 1<<16)
				// 从已有文件大小继续计数（APPEND 模式），用于轮转判断。
				if st, statErr := sink.Stat(); statErr == nil {
					flushedBytes = st.Size()
				}
			}

			// 1s 定时 flush：崩了最多丢 1s，避免每写必刷盘的同步磁盘 I/O。
			flushTicker := time.NewTicker(time.Second)
			defer flushTicker.Stop()
			flush := func() {
				if buffered != nil {
					_ = buffered.Flush()
				}
			}
			// 轮转：超过阈值时关旧文件、改名 .1、重开新文件。
			rotate := func() {
				if buffered == nil {
					return
				}
				_ = buffered.Flush()
				_ = sink.Close()
				_ = os.Remove(logPath + ".1")
				_ = os.Rename(logPath, logPath+".1")
				if f, openErr := os.OpenFile(logPath,
					os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); openErr == nil {
					sink = f
					buffered = bufio.NewWriterSize(sink, 1<<16)
					flushedBytes = 0
				} else {
					sink = nil
					buffered = nil
				}
			}

			reader := bufio.NewReaderSize(r, 1<<16)
			var lineBuf []byte
			tmp := make([]byte, 16384)
			for {
				select {
				case <-stopCh:
					flush()
					if sink != nil {
						_ = sink.Close()
					}
					return
				default:
				}

				n, readErr := reader.Read(tmp)
				if n > 0 {
					// S1-07：落文件与发 logcat 之前统一脱敏，避免签名参数被任何能
					// adb logcat / 读 files 目录的人拿到。与 Kotlin CrashLogRedactor 同口径。
					chunk := []byte(SanitizeLogLine(string(tmp[:n])))
					// 原始数据先整块写缓冲（进程死后 flush 前的部分最多丢 1s），再按行发 logcat。
					if buffered != nil {
						_, _ = buffered.Write(chunk)
						flushedBytes += int64(n)
						// P2-11：8MB 轮转，防文件无限涨满。
						if flushedBytes >= logRotateThresholdBytes {
							rotate()
						}
					}
					lineBuf = append(lineBuf, chunk...)
					for {
						idx := -1
						for i, c := range lineBuf {
							if c == '\n' {
								idx = i
								break
							}
						}
						if idx < 0 {
							break
						}
						line := lineBuf[:idx]
						lineBuf = lineBuf[idx+1:]
						if len(line) == 0 {
							continue
						}
						if len(line) > 3500 {
							// logcat 单条消息上限约 4KB，超长行截断（文件里是全的）。
							line = line[:3500]
						}
						lb := append(append([]byte{}, line...), 0)
						C.__android_log_write(C.ANDROID_LOG_WARN, tag, (*C.char)(unsafe.Pointer(&lb[0])))
					}
					if len(lineBuf) > 64*1024 {
						// 行异常超长且无换行，丢弃防止内存积压。
						lineBuf = lineBuf[:0]
					}
				}
				if readErr != nil {
					flush()
					if sink != nil {
						_ = sink.Close()
					}
					return
				}

				// 定时 flush（非阻塞）：有数据才刷，避免空转。
				select {
				case <-flushTicker.C:
					flush()
				default:
				}
			}
		}()
	})
}

// redirectStopCh 记录当前重定向 goroutine 的停止信号；stopRuntimeLogcat 关闭它。
var redirectStopCh chan struct{}

// stopRuntimeLogcat 优雅关闭运行时日志重定向：关闭停止信号，goroutine 会
// flush 缓冲并关闭文件。供进程退出/测试复位时调用；未初始化时为空操作。
func stopRuntimeLogcat() {
	if redirectStopCh != nil {
		select {
		case <-redirectStopCh:
			// 已关闭
		default:
			close(redirectStopCh)
		}
	}
}

func init() {
	RedirectRuntimeLogcat()
}
