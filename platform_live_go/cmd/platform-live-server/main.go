// platform-live-server 是 APK 内置 platform_live_go 的独立 HTTP 服务（用于本机调试与第三方工具接入）：
// 接口路径、参数、JSON 结构与 APK 内置解析保持一致，APK 内置解析则走 gomobile AAR。

package main

import (
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/cnliux/tvbox/platform_live_go"
	"github.com/cnliux/tvbox/platform_live_go/platformassets"
)

const (
	serviceName    = "tvbox-platform-live"
	serviceVersion = 5
)

func main() {
	host := flag.String("host", "127.0.0.1", "监听地址，默认仅本机；跨机访问需显式 --allow-lan")
	port := flag.Int("port", 8868, "监听端口，默认 8868")
	allowLAN := flag.Bool("allow-lan", false, "显式允许监听非本机地址（会扩大局域网攻击面）")
	token := flag.String("token", "", "Bearer 鉴权令牌；设置后 /v1/* 接口均要求 Authorization: Bearer <token>")
	allowOrigins := stringListFlag("allow-origin", nil, "允许的 CORS Origin，可重复传入；默认不允许任何跨域")
	ratePerMinute := flag.Int("rate-per-minute", 120, "每 IP 每分钟请求上限，<=0 表示禁用限流")
	maxConcurrency := flag.Int("max-concurrency", 32, "最大并发请求数，<=0 表示禁用")
	maxBodyKB := flag.Int("max-body-kb", 1024, "单请求体上限（KB），默认 1MB")
	catalogPath := flag.String("catalog", "", "频道目录 JSON 文件路径，留空使用内嵌目录")
	cacheSeconds := flag.Int("cache-seconds", 30, "成功直链的短缓存时间")
	timeoutSeconds := flag.Int("timeout", 10, "请求平台与验证 CDN 的超时秒数")
	flag.Parse()

	// P03：默认仅本机监听。要绑到非 loopback 地址必须显式 --allow-lan。
	if !isLoopback(*host) && !*allowLAN {
		log.Fatalf("拒绝监听非本机地址 %q：如需局域网访问请显式添加 --allow-lan（并建议设置 --token）", *host)
	}
	if !isLoopback(*host) && *token == "" {
		log.Printf("警告：正在以无鉴权方式监听非本机地址 %q，局域网内任意主机均可调用解析接口。建议设置 --token。", *host)
	}

	// 服务端沿用与 APK 相同的解析路径；抖音签名脚本使用内嵌副本，
	// 直接执行（goja），不再依赖外部 Node.js。
	platformlive.SetDouyinSignScript(platformlive.StripDouyinScript(platformassets.DouyinSignJS()))
	catalogProvider := func() (string, error) {
		if *catalogPath == "" {
			return platformassets.CatalogJSON(), nil
		}
		data, err := os.ReadFile(*catalogPath)
		if err != nil {
			return "", fmt.Errorf("频道目录不存在：%s", *catalogPath)
		}
		return string(data), nil
	}
	service := platformlive.NewService(int64(*timeoutSeconds), int64(*cacheSeconds), catalogProvider, "")
	setLiveService(service)

	cfg := serverConfig{
		Token:          strings.TrimSpace(*token),
		AllowedOrigins: *allowOrigins,
		MaxBodyBytes:   int64(*maxBodyKB) * 1024,
		RatePerMinute:  *ratePerMinute,
		MaxConcurrency: *maxConcurrency,
	}

	handler := wrapWithSecurity(newMux(), cfg)
	address := net.JoinHostPort(*host, strconv.Itoa(*port))
	srv := &http.Server{
		Addr:              address,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      60 * time.Second, // 平台解析可能较慢
		IdleTimeout:       120 * time.Second,
		MaxHeaderBytes:    1 << 16,
	}

	log.Printf("%s 已启动：http://%s", serviceName, address)
	if cfg.Token != "" {
		log.Println("鉴权：已启用 Bearer token")
	} else {
		log.Println("鉴权：未设置 token（本机使用可接受）")
	}
	log.Println("健康检查：GET /health")
	log.Printf("播放解析：GET /v1/live/resolve?site=douyu&roomId=36252")
	log.Printf("分享口令解析：GET /v1/live/resolve-link?text=<分享链接>")
	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("服务启动失败：%v", err)
	}
}

// newMux 构造业务路由。单独抽成函数以便测试（含“无 pprof 端点”回归断言）。
func newMux() *http.ServeMux {
	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, r, map[string]interface{}{
			"status": "ok", "service": serviceName, "version": serviceVersion,
		})
	})
	mux.HandleFunc("/v1/live/catalog", func(w http.ResponseWriter, r *http.Request) {
		channels, err := liveService().Catalog()
		if fail(w, err) {
			return
		}
		payload := make([]map[string]interface{}, 0, len(channels))
		for _, channel := range channels {
			payload = append(payload, map[string]interface{}{
				"id":     channel.ID,
				"name":   channel.Name,
				"site":   channel.Site,
				"roomId": channel.RoomID,
				"group":  channel.Group,
			})
		}
		writeJSON(w, r, map[string]interface{}{"channels": payload})
	})
	mux.HandleFunc("/v1/live/sites", func(w http.ResponseWriter, r *http.Request) {
		sites := liveService().Sites()
		payload := make([]map[string]interface{}, 0, len(sites))
		for _, site := range sites {
			payload = append(payload, map[string]interface{}{
				"id": site.ID, "name": site.Name, "description": site.Description,
			})
		}
		writeJSON(w, r, map[string]interface{}{"sites": payload})
	})
	mux.HandleFunc("/v1/live/categories", func(w http.ResponseWriter, r *http.Request) {
		site, ok := singleQuery(w, r, "site")
		if !ok {
			return
		}
		result, err := liveService().Categories(site)
		if fail(w, err) {
			return
		}
		writeJSON(w, r, result)
	})
	mux.HandleFunc("/v1/live/rooms", func(w http.ResponseWriter, r *http.Request) {
		site, ok := singleQuery(w, r, "site")
		if !ok {
			return
		}
		categoryID, ok := singleQuery(w, r, "categoryId")
		if !ok {
			return
		}
		pageRaw := r.URL.Query().Get("page")
		page := 1
		if pageRaw != "" {
			value, err := strconv.Atoi(pageRaw)
			if err != nil || value < 1 {
				failMessage(w, http.StatusBadRequest, "页码必须为正整数")
				return
			}
			page = value
		}
		result, err := liveService().Rooms(site, categoryID, int64(page))
		if fail(w, err) {
			return
		}
		writeJSON(w, r, result)
	})
	mux.HandleFunc("/v1/live/resolve", func(w http.ResponseWriter, r *http.Request) {
		site, ok := singleQuery(w, r, "site")
		if !ok {
			return
		}
		roomID, ok := singleQuery(w, r, "roomId")
		if !ok {
			return
		}
		forceRefresh := false
		switch r.URL.Query().Get("refresh") {
		case "1", "true":
			forceRefresh = true
		}
		result, err := liveService().Resolve(site, roomID, forceRefresh)
		if fail(w, err) {
			return
		}
		writeJSON(w, r, result)
	})

	mux.HandleFunc("/v1/live/resolve-link", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodPost {
			failMessage(w, http.StatusMethodNotAllowed, "仅支持 GET/POST")
			return
		}
		shareText := strings.TrimSpace(r.URL.Query().Get("text"))
		siteHint := strings.TrimSpace(r.URL.Query().Get("site"))
		if r.Method == http.MethodPost {
			if err := r.ParseForm(); err != nil {
				failMessage(w, http.StatusBadRequest, "表单参数解析失败")
				return
			}
			if shareText == "" {
				shareText = strings.TrimSpace(r.FormValue("text"))
			}
			if siteHint == "" {
				siteHint = strings.TrimSpace(r.FormValue("site"))
			}
		}
		if shareText == "" {
			failMessage(w, http.StatusBadRequest, "缺少参数：text")
			return
		}
		target, err := liveService().ResolveLink(shareText, siteHint)
		if fail(w, err) {
			return
		}
		writeJSON(w, r, map[string]interface{}{
			"site":      target.Site,
			"name":      target.SiteName,
			"roomId":    target.RoomID,
			"sourceUrl": target.SourceURL,
			"url":       target.URL,
		})
	})
	return mux
}
