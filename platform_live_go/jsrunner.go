package platformlive

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"

	"github.com/dop251/goja"
)

// goja 是纯 Go 的 ES5.1+/ES6 JS 解释器，负责两段平台签名脚本的执行：
// 1. douyin_sign.js 的 getABogus（抖音 a_bogus 签名）；
// 2. 斗鱼 room 签名脚本（homeH5Enc 下发的混淆脚本，Android 侧原本借道
//    Kotlin 的 DouyinSigner/WebView 执行，这里直接内化）。

var jsLock sync.Mutex

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
	jsLock.Lock()
	defer jsLock.Unlock()
	vm := newJSRuntime()
	program, err := goja.Compile("tvbox-script", script, false)
	if err != nil {
		return "", fmt.Errorf("JS 脚本编译失败：%w", err)
	}
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
func RunABogus(signScript, params, userAgent string) (string, error) {
	jsLock.Lock()
	defer jsLock.Unlock()
	vm := newJSRuntime()
	program, err := goja.Compile("douyin_sign", signScript, false)
	if err != nil {
		return fmt.Sprintf(`{"error":"抖音签名脚本编译失败：%s"}`, escapeJSON(err.Error())), nil
	}
	defer vm.ClearInterrupt()
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
	result, err := fn(goja.Undefined(), vm.ToValue(params), vm.ToValue(userAgent))
	if err != nil {
		return fmt.Sprintf(`{"error":"抖音签名失败：%s"}`, escapeJSON(err.Error())), nil
	}
	value := fmt.Sprintf("%v", result.Export())
	return fmt.Sprintf(`{"a_bogus":%s}`, escapeJSON(value)), nil
}

func escapeJSON(raw string) string {
	data, err := json.Marshal(raw)
	if err != nil {
		return `"签名序列化失败"`
	}
	return string(data)
}
