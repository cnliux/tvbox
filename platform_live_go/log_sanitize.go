package platformlive

import "regexp"

// S1-07：Go stderr → Android logcat / go_stderr.log 的脱敏闸。
// 源 URL 常带 token=/wsSecret=/expire= 等签名参数；任何能 adb logcat 的人
// （含部分盒子的「工程模式」、第三方桌面）都不该因此拿到可用的源凭据。
var (
	logSecretQueryRe = regexp.MustCompile(`(?i)([?&](?:token|key|sign|signature|secret|wssecret|wstime|expire|expires|auth|authorization|access[_-]?token)=)[^&#\s]+`)
	logBearerRe      = regexp.MustCompile(`(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{8,}`)
	logApiKeyRe      = regexp.MustCompile(`(?i)\b(sk-[A-Za-z0-9_-]{16,}|AIza[0-9A-Za-z_-]{30,})\b`)
)

// SanitizeLogLine 遮盖日志行中的签名参数、Bearer token 与 API Key。
// 纯函数，无状态，可单测；与 Kotlin 侧 CrashLogRedactor.redact 口径一致。
func SanitizeLogLine(line string) string {
	line = logSecretQueryRe.ReplaceAllString(line, "${1}***")
	line = logBearerRe.ReplaceAllString(line, "${1}***")
	return logApiKeyRe.ReplaceAllString(line, "***")
}
