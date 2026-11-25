package main

// HTTP 服务安全边界（对应优化方案 P03/P04）：
//   - Bearer token 鉴权（常量时间比较），/health 与 CORS 预检除外
//   - CORS Origin 显式白名单，默认拒绝跨域
//   - 每 IP 固定窗口限流
//   - 最大并发限制
//   - 请求体大小上限
//   - 安全响应头 + 每请求 ID
//
// 设计原则：全部为“默认安全、显式放宽”。未配置时行为收敛，不会对公网/局域网意外暴露端点。

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"flag"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

const requestIDHeader = "X-Request-ID"

type serverConfig struct {
	Token          string
	AllowedOrigins []string
	MaxBodyBytes   int64
	RatePerMinute  int
	MaxConcurrency int
}

// stringListFlag 支持重复传入的 flag（如 --allow-origin a --allow-origin b）。
type stringListFlagValue struct {
	values *[]string
	seen   bool
}

func (v *stringListFlagValue) String() string {
	if v.values == nil {
		return ""
	}
	return strings.Join(*v.values, ",")
}

func (v *stringListFlagValue) Set(s string) error {
	if s == "" {
		return nil
	}
	if !v.seen {
		*v.values = nil
		v.seen = true
	}
	*v.values = append(*v.values, s)
	return nil
}

// stringListFlag 用 -name 形式注册可重复字符串 flag。
func stringListFlag(name string, value []string, usage string) *[]string {
	values := append([]string(nil), value...)
	// 借助默认 FlagSet 注册，保持与 flag.String 等一致的用法。
	fv := &stringListFlagValue{values: &values}
	flag.Var(fv, name, usage)
	return &values
}

// isLoopback 判定 host 是否为本机地址（含 IPv4/IPv6 与 "localhost"）。
func isLoopback(host string) bool {
	h := strings.TrimSpace(strings.Trim(host, "[]"))
	if h == "" || strings.EqualFold(h, "localhost") {
		return true
	}
	ip := net.ParseIP(h)
	return ip != nil && ip.IsLoopback()
}

// wrapWithSecurity 按中间件链包裹业务 handler。顺序：请求 ID/安全头 → CORS → 限流 → 并发 → 鉴权 → body 上限 → 业务。
func wrapWithSecurity(next http.Handler, cfg serverConfig) http.Handler {
	var h http.Handler = next
	h = maxBytesMiddleware(h, cfg.MaxBodyBytes)
	h = authMiddleware(h, cfg.Token)
	h = concurrencyMiddleware(h, cfg.MaxConcurrency)
	h = rateLimitMiddleware(h, cfg.RatePerMinute)
	h = corsMiddleware(h, cfg.AllowedOrigins)
	h = headerMiddleware(h)
	return h
}

func headerMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rid := r.Header.Get(requestIDHeader)
		if rid == "" {
			rid = newRequestID()
		}
		w.Header().Set(requestIDHeader, rid)
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

func corsMiddleware(next http.Handler, allowed []string) http.Handler {
	allow := make(map[string]struct{}, len(allowed))
	for _, o := range allowed {
		allow[strings.TrimSpace(o)] = struct{}{}
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" {
			if _, ok := allow[origin]; ok {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Set("Vary", "Origin")
				w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
			}
			if r.Method == http.MethodOptions {
				if _, ok := allow[origin]; !ok {
					w.Header().Set("Access-Control-Allow-Origin", "")
				}
				w.WriteHeader(http.StatusNoContent)
				return
			}
		} else if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func authMiddleware(next http.Handler, token string) http.Handler {
	if token == "" {
		return next
	}
	// 预先计算摘要，避免每次请求对明文做逐字节比较之外的额外处理。
	want := sha256.Sum256([]byte(token))
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			next.ServeHTTP(w, r)
			return
		}
		got := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer"))
		gotSum := sha256.Sum256([]byte(got))
		if subtle.ConstantTimeCompare(want[:], gotSum[:]) != 1 {
			w.Header().Set("WWW-Authenticate", "Bearer")
			failMessage(w, http.StatusUnauthorized, "未授权：需要有效的 Bearer token")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func maxBytesMiddleware(next http.Handler, max int64) http.Handler {
	if max <= 0 {
		return next
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Body != nil {
			r.Body = http.MaxBytesReader(w, r.Body, max)
		}
		next.ServeHTTP(w, r)
	})
}

func concurrencyMiddleware(next http.Handler, max int) http.Handler {
	if max <= 0 {
		return next
	}
	sem := make(chan struct{}, max)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case sem <- struct{}{}:
			defer func() { <-sem }()
			next.ServeHTTP(w, r)
		default:
			failMessage(w, http.StatusServiceUnavailable, "服务繁忙，请稍后重试")
		}
	})
}

type ipWindow struct {
	windowStart time.Time
	count       int
}

func rateLimitMiddleware(next http.Handler, perMinute int) http.Handler {
	if perMinute <= 0 {
		return next
	}
	var mu sync.Mutex
	windows := make(map[string]*ipWindow)
	const window = time.Minute
	// 周期性清理过期窗口，避免长期运行下 map 无界增长。
	go func() {
		ticker := time.NewTicker(window)
		defer ticker.Stop()
		for range ticker.C {
			mu.Lock()
			now := time.Now()
			for ip, w := range windows {
				if now.Sub(w.windowStart) >= window {
					delete(windows, ip)
				}
			}
			mu.Unlock()
		}
	}()
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			ip = r.RemoteAddr
		}
		now := time.Now()
		mu.Lock()
		win, ok := windows[ip]
		if !ok || now.Sub(win.windowStart) >= window {
			win = &ipWindow{windowStart: now}
			windows[ip] = win
		}
		win.count++
		exceeded := win.count > perMinute
		mu.Unlock()
		if exceeded {
			w.Header().Set("Retry-After", "60")
			failMessage(w, http.StatusTooManyRequests, "请求过于频繁，请稍后重试")
			return
		}
		next.ServeHTTP(w, r)
	})
}

func newRequestID() string {
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		return fmt.Sprintf("%016x", time.Now().UnixNano())
	}
	return hex.EncodeToString(b)
}
