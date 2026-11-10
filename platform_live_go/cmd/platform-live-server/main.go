// platform-live-server 是 platform_live_server（Python）的 Go 移植版 HTTP 服务：
// 接口路径、参数、JSON 结构与 Python 版保持一致，APK 内置解析则走 gomobile AAR。

package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"

	"github.com/cnliux/tvbox/platform_live_go"
	"github.com/cnliux/tvbox/platform_live_go/platformassets"
)

const (
	serviceName    = "tvbox-platform-live"
	serviceVersion = 5
)

func main() {
	host := flag.String("host", "0.0.0.0", "监听地址，默认允许局域网访问")
	port := flag.Int("port", 8868, "监听端口，默认 8868")
	catalogPath := flag.String("catalog", "", "频道目录 JSON 文件路径，留空使用内嵌目录")
	cacheSeconds := flag.Int("cache-seconds", 30, "成功直链的短缓存时间")
	timeoutSeconds := flag.Int("timeout", 10, "请求平台与验证 CDN 的超时秒数")
	flag.Parse()

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

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]interface{}{
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
		writeJSON(w, http.StatusOK, map[string]interface{}{"channels": payload})
	})
	mux.HandleFunc("/v1/live/sites", func(w http.ResponseWriter, r *http.Request) {
		sites := liveService().Sites()
		payload := make([]map[string]interface{}, 0, len(sites))
		for _, site := range sites {
			payload = append(payload, map[string]interface{}{
				"id": site.ID, "name": site.Name, "description": site.Description,
			})
		}
		writeJSON(w, http.StatusOK, map[string]interface{}{"sites": payload})
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
		writeJSON(w, http.StatusOK, result)
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
		writeJSON(w, http.StatusOK, result)
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
		writeJSON(w, http.StatusOK, result)
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
		writeJSON(w, http.StatusOK, map[string]interface{}{
			"site":      target.Site,
			"name":      target.SiteName,
			"roomId":    target.RoomID,
			"sourceUrl": target.SourceURL,
			"url":       target.URL,
		})
	})

	address := fmt.Sprintf("%s:%d", *host, *port)
	log.Printf("%s 已启动：http://%s:%d", serviceName, *host, *port)
	log.Println("健康检查：GET /health")
	log.Printf("播放解析：GET /v1/live/resolve?site=douyu&roomId=36252")
	log.Printf("分享口令解析：GET /v1/live/resolve-link?text=<分享链接>")
	if err := http.ListenAndServe(address, mux); err != nil {
		log.Fatalf("服务启动失败：%v", err)
	}
}
