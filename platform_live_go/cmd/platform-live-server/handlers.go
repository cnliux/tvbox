package main

// HTTP 响应辅助：对齐 Python 版 create_handler 的错误/JSON 输出行为。

import (
	"net/http"
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

// fail / failMessage / singleQuery 已迁移到 errors.go（P29 v2 结构化错误契约）。
// writeJSON 由 writeEnvelope 取代：成功响应输出 {schemaVersion, requestId, data} 信封，
// 失败响应输出 {schemaVersion, requestId, error 对象 + 兼容 error 字符串}。

// writeJSON 输出成功响应：保留旧签名，内部转为 v2 信封（data 平铺到顶层）。
func writeJSON(w http.ResponseWriter, r *http.Request, body map[string]interface{}) {
	writeEnvelope(w, r, http.StatusOK, body, nil)
}
