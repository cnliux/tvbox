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
	"unsafe"
)

var runtimeLogcatOnce sync.Once

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
		if dir := "/data/data/com.tvbox.app/files"; dir != "" {
			if st, statErr := os.Stat(dir); statErr == nil && st.IsDir() {
				if f, openErr := os.OpenFile(filepath.Join(dir, "go_stderr.log"),
					os.O_CREATE|os.O_WRONLY|os.O_APPEND|os.O_SYNC, 0o644); openErr == nil {
					sink = f
				}
			}
		}
		tag := C.CString("TVBoxGoRuntime")

		go func() {
			defer recoverToErrorOnly() // goroutine panic 兜底，避免重定向失败拖垮进程
			reader := bufio.NewReaderSize(r, 1<<16)
			var lineBuf []byte
			tmp := make([]byte, 16384)
			for {
				n, readErr := reader.Read(tmp)
				if n > 0 {
					// 原始数据先整块落文件（进程死后仍可读），再按行发 logcat。
					if sink != nil {
						_, _ = sink.Write(tmp[:n])
					}
					lineBuf = append(lineBuf, tmp[:n]...)
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
					if sink != nil {
						_ = sink.Close()
					}
					return
				}
			}
		}()
	})
}

func init() {
	RedirectRuntimeLogcat()
}
