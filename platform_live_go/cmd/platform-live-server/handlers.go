package main

// HTTP 响应辅助：对齐 Python 版 create_handler 的错误/JSON 输出行为。

import (
	"encoding/json"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"

	"github.com/cnliux/tvbox/platform_live_go"
)

var (
	liveServiceMu  sync.Mutex
	liveServiceRef *platformlive.Service
)

func setLiveService(service *platformlive.Service) {
	liveServiceMu.Lock()
	defer liveServiceMu.Unlock()
	liveServiceRef = service
}

func liveService() *platformlive.Service {
	liveServiceMu.Lock()
	defer liveServiceMu.Unlock()
	return liveServiceRef
}

func isResolverError(err error) bool {
	var resolverErr *platformlive.ResolverError
	if err == nil {
		return false
	}
	resolverErr, ok := err.(*platformlive.ResolverError)
	return ok && resolverErr != nil
}

// fail 输出业务错误：ResolverError → 400（等价 DouyuResolverError 分支）；其余 500。
func fail(w http.ResponseWriter, err error) bool {
	if err == nil {
		return false
	}
	if isResolverError(err) {
		failMessage(w, http.StatusBadRequest, err.Error())
		return true
	}
	failMessage(w, http.StatusInternalServerError, "解析服务内部错误，请查看电脑终端日志")
	if os.Getenv("TVBOX_PLATFORM_LIVE_DEBUG") != "" {
		log.Println(err)
	}
	return true
}

func failMessage(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]interface{}{"error": message})
}

// singleQuery 缺失参数时直接写 400 响应，等价 _single_query。
func singleQuery(w http.ResponseWriter, r *http.Request, name string) (string, bool) {
	value := strings.TrimSpace(r.URL.Query().Get(name))
	if value == "" {
		failMessage(w, http.StatusBadRequest, "缺少参数："+name)
		return "", false
	}
	return value, true
}

// writeJSON JSON 输出（ensure_ascii=false + 紧凑），带 no-store。
func writeJSON(w http.ResponseWriter, status int, body map[string]interface{}) {
	data, err := json.Marshal(body)
	if err != nil {
		data = []byte(`{"error":"解析服务内部错误"}`)
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write(data)
}
