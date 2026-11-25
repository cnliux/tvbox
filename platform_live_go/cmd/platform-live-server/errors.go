package main

// P29：Go 跨语言 v2 结构化错误契约。
//
// 响应体升级为信封结构：
//
//	{
//	  "schemaVersion": 2,
//	  "requestId": "…",
//	  "data":  { … },                                   // 成功时存在
//	  "error": { "code": "...", "message": "...",
//	             "retry": false, "traceId": "..." },    // 失败时存在
//	  "errorMessage": "…"                              // 兼容字段（v3 移除）
//	}
//
// 兼容策略：旧契约的 `error` 字段为字符串，为避免 map 同键冲突，
// v2 顶层 `error` 升级为对象；旧字符串迁移到顶层 `errorMessage`。
// `errorMessage` 计划在 v3 移除。

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"strconv"
	"strings"
)

// v2 错误码枚举（跨语言契约，Kotlin 侧按 code 映射领域错误）。
const (
	CodeInvalidArgument = "INVALID_ARGUMENT"
	CodeNotFound        = "NOT_FOUND"
	CodeRateLimited     = "RATE_LIMITED"
	CodeUpstream        = "UPSTREAM"
	CodeTimeout         = "TIMEOUT"
	CodeInternal        = "INTERNAL"
)

// schemaVersion 当前响应信封版本。
const schemaVersion = 2

// apiError 是 v2 结构化错误（跨语言字段固定为英文 key）。
type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
	Retry   bool   `json:"retry"`
	TraceID string `json:"traceId"`
}

// writeEnvelope 输出 v2 信封响应：成功时带 data，失败时带 error 对象 +
// 兼容的顶层 error 字符串（v3 移除）。requestId 复用 headerMiddleware 生成的值。
func writeEnvelope(w http.ResponseWriter, r *http.Request, status int, data interface{}, apiErr *apiError) {
	requestID := requestIDFromRequest(r)
	if apiErr == nil {
		// 成功响应：只输出 schemaVersion/requestId/data，不输出任何 error 字段。
		writeEnvelopeJSON(w, status, map[string]any{
			"schemaVersion": schemaVersion,
			"requestId":     requestID,
			"data":          data,
		})
		return
	}
	// 失败响应：顶层 error 输出 v2 对象，旧字符串契约迁移到顶层 errorMessage（v3 移除）。
	body := map[string]any{
		"schemaVersion": schemaVersion,
		"requestId":     requestID,
		"error":         apiErr,
		"errorMessage":  apiErr.Message,
	}
	writeEnvelopeJSON(w, status, body)
}

// writeEnvelopeJSON 序列化并写出信封响应，带 no-store 与长度头。
func writeEnvelopeJSON(w http.ResponseWriter, status int, body any) {
	raw, err := json.Marshal(body)
	if err != nil {
		raw = []byte(`{"schemaVersion":2,"requestId":"","error":"响应序列化失败"}`)
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(raw)))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(raw)
}

// newTraceID 生成 8 字节 hex 的 traceId；随机源失败时退化为 0，仅影响可读性。
func newTraceID() string {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return "0000000000000000"
	}
	return hex.EncodeToString(b)
}

// requestIDFromRequest 从 headerMiddleware 写入的 X-Request-ID 取请求 ID；
// 缺失时（直接构造 ResponseRecorder 等场景）现场生成，保证 requestId 非空。
func requestIDFromRequest(r *http.Request) string {
	if r != nil {
		if rid := strings.TrimSpace(r.Header.Get(requestIDHeader)); rid != "" {
			return rid
		}
	}
	return newRequestID()
}

// classifyCode 按错误内容判断 v2 code，错误消息不跨语言输出，只做分类。
func classifyCode(err error) (code string, retry bool) {
	if err == nil {
		return CodeInternal, false
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return CodeTimeout, true
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return CodeTimeout, true
	}
	msg := strings.ToLower(err.Error())
	if strings.Contains(msg, "timeout") || strings.Contains(msg, "超时") ||
		strings.Contains(msg, "deadline") {
		return CodeTimeout, true
	}
	// 其余业务/解析失败视为上游不可用，可安全重试。
	return CodeUpstream, true
}

// statusToCode 将 HTTP 状态映射到 v2 code（用于不带 error 的中间件错误）。
func statusToCode(status int) (code string, retry bool) {
	switch status {
	case http.StatusBadRequest, http.StatusMethodNotAllowed, http.StatusUnauthorized:
		return CodeInvalidArgument, false
	case http.StatusNotFound:
		return CodeNotFound, false
	case http.StatusTooManyRequests:
		return CodeRateLimited, true
	case http.StatusServiceUnavailable:
		return CodeUpstream, true
	case http.StatusGatewayTimeout:
		return CodeTimeout, true
	default:
		return CodeInternal, false
	}
}

// newAPIError 构造 v2 错误对象（traceId 在此生成）。
func newAPIError(code, message string, retryable bool) *apiError {
	return &apiError{
		Code:    code,
		Message: message,
		Retry:   retryable,
		TraceID: newTraceID(),
	}
}

// fail 沿用旧签名（供无 request 上下文的调用点复用）；内部以 nil request 走 writeEnvelope。
func fail(w http.ResponseWriter, err error) bool {
	return failRequest(w, nil, err)
}

// failRequest 是 v2 版本的 fail：超时 → TIMEOUT/502，其余解析失败 → UPSTREAM/400 或 INTERNAL/500。
func failRequest(w http.ResponseWriter, r *http.Request, err error) bool {
	if err == nil {
		return false
	}
	code, retry := classifyCode(err)
	if isResolverError(err) {
		status := http.StatusBadRequest
		if code == CodeTimeout {
			status = http.StatusBadGateway
		}
		writeEnvelope(w, r, status, nil, newAPIError(code, err.Error(), retry))
		return true
	}
	writeEnvelope(w, r, http.StatusInternalServerError, nil,
		newAPIError(code, "解析服务内部错误，请查看电脑终端日志", retry))
	return true
}

// failMessage 沿用旧签名，按 HTTP 状态映射 v2 code。
func failMessage(w http.ResponseWriter, status int, message string) {
	code, retry := statusToCode(status)
	writeEnvelope(w, nil, status, nil, newAPIError(code, message, retry))
}

// singleQuery 缺失参数时输出 400/INVALID_ARGUMENT。
func singleQuery(w http.ResponseWriter, r *http.Request, name string) (string, bool) {
	value := strings.TrimSpace(r.URL.Query().Get(name))
	if value == "" {
		writeEnvelope(w, r, http.StatusBadRequest, nil,
			newAPIError(CodeInvalidArgument, "缺少参数："+name, false))
		return "", false
	}
	return value, true
}
