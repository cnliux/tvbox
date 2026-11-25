package platformlive

import (
	"strings"
	"testing"
	"time"
)

// T2-12：恶意脚本最小验收。本 App 不执行用户订阅 JS，goja 只跑内置签名脚本；
// 这里验证「墙/表/闸」里能自动化的部分：无网络桥、超时中断、大小上限、栈溢出保护。
// 断言：全部在 600ms 内被拒、零出站连接、进程存活。

func TestEvilScriptsAreRejectedWithoutNetworkBridge(t *testing.T) {
	cases := []struct {
		name       string
		script     string
		expression string
	}{
		{"死循环", "while (true) {}", "1"},
		{"内存炸弹(超限脚本)", strings.Repeat(" ", maxJSScriptSize+1), "1"},
		{"环回地址 SSRF", "fetch('http://127.0.0.1:9977/')", "1"},
		{"内网地址 SSRF", "fetch('http://192.168.1.1/')", "1"},
		{"Java 反射逃逸", "Java.type('java.lang.Runtime')", "1"},
		{"深递归", "function f(n){return f(n+1)} f(0)", "1"},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			started := time.Now()
			_, err := runJSScriptScriptExpressionWithTimeout(tc.script, tc.expression, 250*time.Millisecond)
			elapsed := time.Since(started)
			if err == nil {
				t.Fatalf("恶意脚本未被拒绝：%s", tc.name)
			}
			if elapsed > 600*time.Millisecond {
				t.Fatalf("拒绝耗时过长：%s 用了 %s", tc.name, elapsed)
			}
		})
	}
}

func TestJSRuntimeExposesNoNetworkOrJavaBridge(t *testing.T) {
	vm := newJSRuntime()
	defer vm.ClearInterrupt()

	for _, name := range []string{"fetch", "XMLHttpRequest", "Java", "java", "require", "process", "Deno"} {
		value, err := vm.RunString("typeof " + name)
		if err != nil {
			t.Fatalf("探测 %s 失败：%v", name, err)
		}
		if got := value.String(); got != "undefined" {
			t.Fatalf("JS 运行时暴露了 %s（type=%s），沙箱墙被击穿", name, got)
		}
	}
}
