package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func okHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	})
}

func TestIsLoopback(t *testing.T) {
	cases := map[string]bool{
		"127.0.0.1":    true,
		"::1":          true,
		"[::1]":        true,
		"localhost":    true,
		"LOCALHOST":    true,
		"0.0.0.0":      false,
		"192.168.1.10": false,
		"10.0.0.5":     false,
		"example.com":  false,
		"":             true,
	}
	for host, want := range cases {
		if got := isLoopback(host); got != want {
			t.Errorf("isLoopback(%q) = %v, want %v", host, got, want)
		}
	}
}

func TestAuthMiddleware(t *testing.T) {
	h := authMiddleware(okHandler(), "s3cr3t")

	// /health 免鉴权
	r := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("health without token: got %d, want 200", w.Code)
	}

	// 无 token → 401
	r = httptest.NewRequest(http.MethodGet, "/v1/live/catalog", nil)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("no token: got %d, want 401", w.Code)
	}
	if w.Header().Get("WWW-Authenticate") != "Bearer" {
		t.Fatal("missing WWW-Authenticate header")
	}

	// 错误 token → 401
	r = httptest.NewRequest(http.MethodGet, "/v1/live/catalog", nil)
	r.Header.Set("Authorization", "Bearer wrong")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("wrong token: got %d, want 401", w.Code)
	}

	// 正确 token → 200
	r = httptest.NewRequest(http.MethodGet, "/v1/live/catalog", nil)
	r.Header.Set("Authorization", "Bearer s3cr3t")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("correct token: got %d, want 200", w.Code)
	}
}

func TestAuthMiddlewareDisabled(t *testing.T) {
	h := wrapWithSecurity(okHandler(), serverConfig{})
	r := httptest.NewRequest(http.MethodGet, "/v1/live/catalog", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("auth disabled: got %d, want 200", w.Code)
	}
}

func TestCORSMiddleware(t *testing.T) {
	h := corsMiddleware(okHandler(), []string{"https://allowed.example"})

	// 白名单内
	r := httptest.NewRequest(http.MethodGet, "/v1/live/catalog", nil)
	r.Header.Set("Origin", "https://allowed.example")
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "https://allowed.example" {
		t.Fatalf("allowed origin: got %q", got)
	}

	// 白名单外
	r = httptest.NewRequest(http.MethodGet, "/v1/live/catalog", nil)
	r.Header.Set("Origin", "https://evil.example")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("disallowed origin should not get ACAO, got %q", got)
	}

	// 预检请求不落到业务 handler
	r = httptest.NewRequest(http.MethodOptions, "/v1/live/catalog", nil)
	r.Header.Set("Origin", "https://allowed.example")
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusNoContent {
		t.Fatalf("preflight: got %d, want 204", w.Code)
	}
}

func TestRateLimitMiddleware(t *testing.T) {
	h := rateLimitMiddleware(okHandler(), 2)
	for i := 0; i < 2; i++ {
		r := httptest.NewRequest(http.MethodGet, "/health", nil)
		r.RemoteAddr = "1.2.3.4:12345"
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("request %d: got %d, want 200", i, w.Code)
		}
	}
	r := httptest.NewRequest(http.MethodGet, "/health", nil)
	r.RemoteAddr = "1.2.3.4:12345"
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("over limit: got %d, want 429", w.Code)
	}
	if w.Header().Get("Retry-After") != "60" {
		t.Fatal("missing Retry-After header")
	}

	// 不同 IP 独立计数
	r = httptest.NewRequest(http.MethodGet, "/health", nil)
	r.RemoteAddr = "5.6.7.8:9999"
	w = httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("different IP: got %d, want 200", w.Code)
	}
}

func TestConcurrencyMiddleware(t *testing.T) {
	h := concurrencyMiddleware(okHandler(), 1)
	r := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if w.Code != http.StatusOK {
		t.Fatalf("first request: got %d, want 200", w.Code)
	}
}

func TestMaxBytesMiddleware(t *testing.T) {
	seen := false
	h := maxBytesMiddleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen = true
		_, err := io.ReadAll(r.Body)
		if err == nil {
			t.Fatal("expected body too large error from MaxBytesReader")
		}
		w.WriteHeader(http.StatusOK)
	}), 16)

	body := strings.Repeat("a", 100)
	r := httptest.NewRequest(http.MethodPost, "/v1/live/resolve-link", strings.NewReader(body))
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	if !seen {
		t.Fatal("handler was not called")
	}
}

func TestHeaderMiddleware(t *testing.T) {
	h := headerMiddleware(okHandler())
	r := httptest.NewRequest(http.MethodGet, "/health", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	for header, want := range map[string]string{
		"X-Content-Type-Options": "nosniff",
		"X-Frame-Options":        "DENY",
		"Referrer-Policy":        "no-referrer",
	} {
		if got := w.Header().Get(header); got != want {
			t.Errorf("%s = %q, want %q", header, got, want)
		}
	}
	if w.Header().Get(requestIDHeader) == "" {
		t.Error("missing request ID header")
	}
}

// P35：回归断言 —— 服务不得注册 pprof 调试端点（Release 安全要求）。
func TestNoPprofEndpoint(t *testing.T) {
	h := newMux()
	for _, path := range []string{"/debug/pprof/", "/debug/pprof/heap", "/debug/pprof/cmdline"} {
		r := httptest.NewRequest(http.MethodGet, path, nil)
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		if w.Code != http.StatusNotFound {
			t.Errorf("pprof endpoint %s exposed: got %d, want 404", path, w.Code)
		}
	}
}

// 确认 failMessage 输出为合法 JSON（中间件错误路径共用）。
func TestFailMessageJSON(t *testing.T) {
	w := httptest.NewRecorder()
	failMessage(w, http.StatusUnauthorized, "未授权")
	var payload map[string]interface{}
	if err := json.Unmarshal(w.Body.Bytes(), &payload); err != nil {
		t.Fatalf("failMessage produced invalid JSON: %v", err)
	}
	if payload["errorMessage"] != "未授权" {
		t.Fatalf("unexpected errorMessage payload: %v", payload)
	}
	// P29 v2：顶层 error 为对象，含 code/message/retry/traceId。
	errObj, ok := payload["error"].(map[string]interface{})
	if !ok {
		t.Fatalf("顶层 error 应为 v2 对象，got %T", payload["error"])
	}
	if errObj["code"] != CodeInvalidArgument {
		t.Fatalf("error.code = %v, want %s", errObj["code"], CodeInvalidArgument)
	}
}
