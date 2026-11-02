package platformlive

// 导出的签名脚本工具（供 cmd/platform-live-server 使用；gomobile 侧由
// Init 内部调用）。douyinSignScript 与 SetDouyinSignScript 位于 douyin.go。

// StripDouyinScript 去掉 douyin_sign.js 的 BOM 与 Node CLI 包装段。
func StripDouyinScript(script string) string { return stripDouyinScript(script) }
