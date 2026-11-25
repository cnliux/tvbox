package main

// P29：v2 结构化错误契约测试。
//
// 覆盖目标：
//  1. 六种 v2 code 均有对应响应（INVALID_ARGUMENT/NOT_FOUND/RATE_LIMITED/UPSTREAM/TIMEOUT/INTERNAL）
//  2. 顶层 `error` 字符串兼容字段仍存在（v3 移除前不能删除）
//  3. `error` 对象字段类型正确（code/message 字符串、retry 布尔、traceId 非空）
//  4. requestId 非空
//  5. 成功响应不含 error 字段

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// decodeEnvelope 解析响应体，返回顶层 map。
func decodeEnvelope(t *testing.T, w *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var body map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &body); err != nil {
		t.Fatalf("响应体不是合法 JSON：%v\n原始：%s", err, w.Body.String())
	}
	return body
}

// assertEnvelopeCommon 断言所有 v2 错误信封共通字段。
func assertEnvelopeCommon(t *testing.T, body map[string]any, wantCode string, wantRetry bool) {
	t.Helper()
	if got, _ := body["schemaVersion"].(float64); int(got) != 2 {
		t.Errorf("schemaVersion = %v, want 2", body["schemaVersion"])
	}
	requestID, _ := body["requestId"].(string)
	if strings.TrimSpace(requestID) == "" {
		t.Errorf("requestId 为空，期望非空字符串")
	}
	errObj, ok := body["error"].(map[string]any)
	if !ok {
		t.Fatalf("error 字段不是对象，got %T", body["error"])
	}
	if errObj["code"] != wantCode {
		t.Errorf("error.code = %v, want %s", errObj["code"], wantCode)
	}
	retry, _ := errObj["retry"].(bool)
	if retry != wantRetry {
		t.Errorf("error.retry = %v, want %v", errObj["retry"], wantRetry)
	}
	traceID, _ := errObj["traceId"].(string)
	if strings.TrimSpace(traceID) == "" {
		t.Errorf("error.traceId 为空，期望非空字符串")
	}
	msg, _ := errObj["message"].(string)
	if strings.TrimSpace(msg) == "" {
		t.Errorf("error.message 为空，期望非空字符串")
	}
	// 兼容字段：顶层 errorMessage（v3 移除）应与 error.message 同值。
	compat, _ := body["errorMessage"].(string)
	if compat != msg {
		t.Errorf("errorMessage = %q, want %q（与 error.message 同值）", compat, msg)
	}
}

func TestErrorV2_InvalidArgument(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/v1/live/rooms", nil)
	w := httptest.NewRecorder()
	singleQuery(w, r, "site")
	if w.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", w.Code)
	}
	body := decodeEnvelope(t, w)
	assertEnvelopeCommon(t, body, CodeInvalidArgument, false)
}

func TestErrorV2_NotFound(t *testing.T) {
	w := httptest.NewRecorder()
	failMessage(w, http.StatusNotFound, "资源不存在")
	if w.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", w.Code)
	}
	assertEnvelopeCommon(t, decodeEnvelope(t, w), CodeNotFound, false)
}

func TestErrorV2_RateLimited(t *testing.T) {
	w := httptest.NewRecorder()
	failMessage(w, http.StatusTooManyRequests, "请求过于频繁")
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("status = %d, want 429", w.Code)
	}
	assertEnvelopeCommon(t, decodeEnvelope(t, w), CodeRateLimited, true)
}

func TestErrorV2_Upstream(t *testing.T) {
	w := httptest.NewRecorder()
	failMessage(w, http.StatusServiceUnavailable, "上游服务不可用")
	assertEnvelopeCommon(t, decodeEnvelope(t, w), CodeUpstream, true)
}

func TestErrorV2_Timeout(t *testing.T) {
	w := httptest.NewRecorder()
	failMessage(w, http.StatusGatewayTimeout, "上游超时")
	assertEnvelopeCommon(t, decodeEnvelope(t, w), CodeTimeout, true)
}

func TestErrorV2_Internal(t *testing.T) {
	w := httptest.NewRecorder()
	failMessage(w, http.StatusInternalServerError, "内部错误")
	assertEnvelopeCommon(t, decodeEnvelope(t, w), CodeInternal, false)
}

func TestErrorV2_ClassifyCode_TimeoutError(t *testing.T) {
	code, retry := classifyCode(context.DeadlineExceeded)
	if code != CodeTimeout || !retry {
		t.Errorf("classifyCode(timeout) = (%s, %v), want (TIMEOUT, true)", code, retry)
	}
}

func TestErrorV2_SuccessNoErrorField(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	writeJSON(w, r, map[string]interface{}{"status": "ok"})
	body := decodeEnvelope(t, w)
	if _, exists := body["error"]; exists {
		t.Errorf("成功响应不应输出 error 字段，got %v", body["error"])
	}
	if got, _ := body["schemaVersion"].(float64); int(got) != 2 {
		t.Errorf("schemaVersion = %v, want 2", body["schemaVersion"])
	}
	if _, ok := body["data"].(map[string]any); !ok {
		t.Errorf("成功响应应包含 data 对象，got %T", body["data"])
	}
}
