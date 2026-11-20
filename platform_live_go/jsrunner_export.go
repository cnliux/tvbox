package platformlive

// 导出的签名脚本工具（供 cmd/platform-live-server 使用；gomobile 侧由
// Init 内部调用）。douyinSignScript 与 SetDouyinSignScript 位于 douyin.go。

// StripDouyinScript 去掉 douyin_sign.js 的 BOM 与 Node CLI 包装段。
// gomobile 导出：panic 兜底返回原脚本，避免越界 JNI 崩溃。
func StripDouyinScript(script string) (s string) {
	defer recoverTo(&s, script)
	return stripDouyinScript(script)
}
