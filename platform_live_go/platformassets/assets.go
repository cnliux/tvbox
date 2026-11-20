// Package platformassets 打包平台直播所需的静态资源：
// - catalog.json 频道目录（与 app/src/main/assets 里的副本保持同步）；
// - douyin_sign.js 抖音 a_bogus 签名脚本（与 app/src/main/assets 保持同步）。
//
// 仅 cmd/platform-live-server 使用嵌入版本；APK 内通过 gomobile AAR 时，
// Kotlin 侧从 assets 读取后经 platformlive.Init 传入（避免 gomobile embed 兼容问题）。
package platformassets

import _ "embed"

//go:embed catalog.json
var catalogJSON string

//go:embed douyin_sign.js
var douyinSignJS string

// CatalogJSON 返回内嵌频道目录文本。
// gomobile 导出：panic 兜底返回空串，避免越界 JNI 崩溃。
func CatalogJSON() (s string) {
	defer recoverTo(&s, "")
	return catalogJSON
}

// DouyinSignJS 返回内嵌抖音签名脚本全文。
// gomobile 导出：panic 兜底返回空串，避免越界 JNI 崩溃。
func DouyinSignJS() (s string) {
	defer recoverTo(&s, "")
	return douyinSignJS
}

// recoverTo 是本包专用的 panic 兜底（gomobile 导出边界）。
func recoverTo[T any](value *T, fallback T) {
	if r := recover(); r != nil {
		*value = fallback
	}
}
