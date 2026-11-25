package platformlive

// log rotation 的纯逻辑（无 android build tag，可在任意平台单测）。
// P2-11：单文件 8MB 轮转，防 go_stderr.log 无限涨满电视那点存储。

// logRotateThresholdBytes：单文件超过 8MB 轮转一次。
const logRotateThresholdBytes = 8 << 20

// logRotateKeepFiles：保留 go_stderr.log + go_stderr.log.1（一个历史文件）。
const logRotateKeepFiles = 1

// ShouldRotateLog 判断当前累计字节是否达到轮转阈值。
// thresholdBytes <= 0 表示关闭轮转（恒 false）。
func ShouldRotateLog(writtenBytes int64, thresholdBytes int64) bool {
	if thresholdBytes <= 0 {
		return false
	}
	return writtenBytes >= thresholdBytes
}

// RotatedLogPath 返回轮转后旧文件的目标路径（保留一个历史文件 .1）。
// 例如 go_stderr.log -> go_stderr.log.1。
func RotatedLogPath(path string) string {
	return path + ".1"
}

// LogRotateKeepFiles 返回轮转保留的文件数（当前=1：主文件 + .1）。
func LogRotateKeepFiles() int {
	return logRotateKeepFiles
}
