package platformlive

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"time"
)

// httpSession 对应各 resolver 里的 requests.Session：UA 默认头 + Cookie Jar + 统一超时。
type httpSession struct {
	client      *http.Client
	userAgent   string
	defaultCookie string
}

func newHTTPSession(timeoutSeconds int, userAgent string, withCookieJar bool) *httpSession {
	timeout := max(time.Second, time.Duration(timeoutSeconds)*time.Second)
	client := &http.Client{
		Timeout: timeout,
	}
	if withCookieJar {
		if jar, err := cookiejar.New(nil); err == nil {
			client.Jar = jar
		}
	}
	return &httpSession{client: client, userAgent: userAgent}
}

// get 发送 GET 并返回状态码与响应原文；非 2xx 视为错误（等价 raise_for_status）。
func (s *httpSession) get(rawURL string, headers map[string]string) ([]byte, error) {
	return s.request(http.MethodGet, rawURL, headers, nil)
}

// getWithParams 组装 query（保证与 Python requests 传 params 的行为一致：无需字典序）。
func (s *httpSession) getWithParams(rawURL string, params url.Values, headers map[string]string) ([]byte, error) {
	encoded := params.Encode()
	if encoded != "" {
		if strings.Contains(rawURL, "?") {
			rawURL += "&" + encoded
		} else {
			rawURL += "?" + encoded
		}
	}
	return s.get(rawURL, headers)
}

// getWithCookies 发送 GET 并额外返回响应 Set-Cookie（key→value，后写覆盖）。
func (s *httpSession) getWithCookies(rawURL string, params url.Values, headers map[string]string) ([]byte, map[string]string, error) {
	encoded := params.Encode()
	if encoded != "" {
		if strings.Contains(rawURL, "?") {
			rawURL += "&" + encoded
		} else {
			rawURL += "?" + encoded
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), s.client.Timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, nil, fmt.Errorf("请求构造失败：%w", err)
	}
	for key, value := range headers {
		req.Header.Set(key, value)
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, nil, fmt.Errorf("读取响应失败：%w", err)
	}
	cookies := map[string]string{}
	for _, cookie := range resp.Cookies() {
		if cookie.Name != "" {
			cookies[cookie.Name] = cookie.Value
		}
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, cookies, statusError(resp.StatusCode, resp.Status, rawURL)
	}
	return data, cookies, nil
}

// postForm 发送 application/x-www-form-urlencoded。
func (s *httpSession) postForm(rawURL string, form url.Values, headers map[string]string) ([]byte, error) {
	body := strings.NewReader(form.Encode())
	if headers == nil {
		headers = map[string]string{}
	}
	if _, exists := headers["Content-Type"]; !exists {
		headers["Content-Type"] = "application/x-www-form-urlencoded"
	}
	return s.request(http.MethodPost, rawURL, headers, body)
}

// postRaw 发送自定义字节体。
func (s *httpSession) postRaw(rawURL string, body []byte, headers map[string]string) ([]byte, error) {
	return s.request(http.MethodPost, rawURL, headers, strings.NewReader(string(body)))
}

func (s *httpSession) request(method, rawURL string, headers map[string]string, body io.Reader) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), s.client.Timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, rawURL, body)
	if err != nil {
		return nil, fmt.Errorf("请求构造失败：%w", err)
	}
	if len(headers) > 0 {
		for key, value := range headers {
			req.Header.Set(key, value)
		}
	}
	resp, err := s.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("读取响应失败：%w", err)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, statusError(resp.StatusCode, resp.Status, rawURL)
	}
	return data, nil
}

func statusError(statusCode int, status, rawURL string) error {
	detail := status
	if detail == "" {
		detail = fmt.Sprintf("%d", statusCode)
	}
	return fmt.Errorf("%s（%s）", detail, rawURL)
}

// parseJSONBytes 把 JSON 解析到 map / slice / 原生类型的 interface{}。
func parseJSONBytes(data []byte) (interface{}, error) {
	var payload interface{}
	if err := json.Unmarshal(data, &payload); err != nil {
		return nil, err
	}
	return payload, nil
}

// jsonObject 断言 map[string]interface{}。
func jsonObject(value interface{}) map[string]interface{} {
	if raw, ok := value.(map[string]interface{}); ok {
		return raw
	}
	return nil
}

// jsonArray 断言 []interface{}。
func jsonArray(value interface{}) []interface{} {
	if raw, ok := value.([]interface{}); ok {
		return raw
	}
	return nil
}

// jsonString 断言 string 并返回内容。
func jsonString(value interface{}) (string, bool) {
	if raw, ok := value.(string); ok {
		return raw, true
	}
	return "", false
}

// jsonFloat 断言数字。
func jsonFloat(value interface{}) (float64, bool) {
	if raw, ok := value.(float64); ok {
		return raw, true
	}
	return 0, false
}
