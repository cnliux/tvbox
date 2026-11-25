package platformlive

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/dop251/goja"
)

// goja 是纯 Go 的 ES5.1+/ES6 JS 解释器，负责两段平台签名脚本的执行：
// 1. douyin_sign.js 的 getABogus（抖音 a_bogus 签名）；
// 2. 斗鱼 room 签名脚本（homeH5Enc 下发的混淆脚本，Android 侧原本借道
//    Kotlin 的 DouyinSigner/WebView 执行，这里直接内化）。

var jsLock sync.Mutex

// jsExecTimeout 单次 JS 执行上限：斗鱼签名脚本来自远端 homeH5Enc 下发，
// 一旦死循环会永久占死 jsLock，阻塞所有直播解析，必须用 vm.Interrupt 强制终止。
const jsExecTimeout = 8 * time.Second

// maxJSScriptSize 远端脚本大小上限：明显异常/被篡改的巨型脚本直接拒绝执行。
const maxJSScriptSize = 2 << 20 // 2MB

// watchdogInterrupt 超时后中断 VM；正常完成后由调用方 close(done) 结束看门狗。
// timeout 由调用方注入，便于单测用 250ms 验证「死循环必被中断」，生产仍用 jsExecTimeout。
func watchdogInterrupt(vm *goja.Runtime, done <-chan struct{}, timeout time.Duration) {
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-done:
	case <-timer.C:
		vm.Interrupt(fmt.Errorf("JS 执行超时（>%s）", timeout))
	}
}

func newJSRuntime() *goja.Runtime {
	vm := goja.New()
	// 提供无操作的 console，脚本错误分支里引用了 console['error']。
	nop := func(call goja.FunctionCall) goja.Value { return goja.Undefined() }
	logValue := vm.ToValue(nop)
	console := map[string]interface{}{
		"log":   logValue,
		"info":  logValue,
		"warn":  logValue,
		"error": logValue,
	}
	vm.Set("console", console)
	return vm
}

// runJSScriptScriptExpression 编译并执行 script，随后求值 expression，
// 返回该表达式的字符串值（对应 Kotlin DouyinSigner.runJs 的语义）。
func runJSScriptScriptExpression(script, expression string) (string, error) {
	return runJSScriptScriptExpressionWithTimeout(script, expression, jsExecTimeout)
}

// runJSScriptScriptExpressionWithTimeout 是注入执行预算的版本，仅用于测试与灰度调参。
func runJSScriptScriptExpressionWithTimeout(script, expression string, timeout time.Duration) (string, error) {
	if len(script) > maxJSScriptSize {
		return "", fmt.Errorf("JS 脚本过大（%d 字节），已拒绝执行", len(script))
	}
	jsLock.Lock()
	defer jsLock.Unlock()
	vm := newJSRuntime()
	defer vm.ClearInterrupt()
	program, err := goja.Compile("tvbox-script", script, false)
	if err != nil {
		return "", fmt.Errorf("JS 脚本编译失败：%w", err)
	}
	done := make(chan struct{})
	go watchdogInterrupt(vm, done, timeout)
	defer close(done)
	if _, err := vm.RunProgram(program); err != nil {
		return "", fmt.Errorf("JS 脚本执行失败：%w", err)
	}
	value, err := vm.RunString(expression)
	if err != nil {
		return "", fmt.Errorf("JS 表达式执行失败：%w", err)
	}
	if goja.IsUndefined(value) {
		return "", fmt.Errorf("JS 表达式无返回")
	}
	exported := value.Export()
	switch result := exported.(type) {
	case string:
		return result, nil
	default:
		return fmt.Sprintf("%v", result), nil
	}
}

// douyinSignNodeWrapper 标记 douyin_sign.js 文件末尾的 Node CLI 包装段。
const douyinSignNodeWrapper = "const readline = require('readline')"

// stripDouyinScript 去掉 BOM 与 Node CLI 包装段。
func stripDouyinScript(source string) string {
	source = strings.TrimPrefix(source, "\uFEFF")
	marker := strings.Index(source, douyinSignNodeWrapper)
	if marker >= 0 {
		return strings.TrimSuffix(source[:marker], "\n")
	}
	return source
}

// RunABogus 运行 signScript（douyin_sign.js），返回与 Kotlin/Node 签名助手
// 一致的 JSON 字符串：{"a_bogus":"..."} 或 {"error":"..."}。
// gomobile 导出：panic 兜底返回 error，避免越界 JNI 崩溃。
func RunABogus(signScript, params, userAgent string) (result string, err error) {
	defer recoverToError(&err)
	if len(signScript) > maxJSScriptSize {
		return fmt.Sprintf(`{"error":"抖音签名脚本过大（%d 字节），已拒绝执行"}`, len(signScript)), nil
	}
	jsLock.Lock()
	defer jsLock.Unlock()
	vm := newJSRuntime()
	program, err := goja.Compile("douyin_sign", signScript, false)
	if err != nil {
		return fmt.Sprintf(`{"error":"抖音签名脚本编译失败：%s"}`, escapeJSON(err.Error())), nil
	}
	defer vm.ClearInterrupt()
	done := make(chan struct{})
	go watchdogInterrupt(vm, done, jsExecTimeout)
	defer close(done)
	if _, err := vm.RunProgram(program); err != nil {
		return fmt.Sprintf(`{"error":"抖音签名脚本执行失败：%s"}`, escapeJSON(err.Error())), nil
	}
	getABogus, err := vm.RunString("typeof getABogus !== 'undefined' ? getABogus : undefined")
	if err != nil || getABogus == nil || goja.IsUndefined(getABogus) {
		return `{"error":"抖音签名脚本未提供 getABogus"}`, nil
	}
	fn, ok := goja.AssertFunction(getABogus)
	if !ok {
		return `{"error":"getABogus 不是函数"}`, nil
	}
	value, err2 := fn(goja.Undefined(), vm.ToValue(params), vm.ToValue(userAgent))
	if err2 != nil {
		return fmt.Sprintf(`{"error":"抖音签名失败：%s"}`, escapeJSON(err2.Error())), nil
	}
	valueStr := fmt.Sprintf("%v", value.Export())
	return fmt.Sprintf(`{"a_bogus":%s}`, escapeJSON(valueStr)), nil
}

func escapeJSON(raw string) string {
	data, err := json.Marshal(raw)
	if err != nil {
		return `"签名序列化失败"`
	}
	return string(data)
}
