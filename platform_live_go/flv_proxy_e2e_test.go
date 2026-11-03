package platformlive

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"testing"
	"time"
)

// 真实房间的连续代理验证（默认跳过）：
// TVBOX_LIVE_PROXY_E2E=1 go test -run TestLiveHuyaProxyContinuity -v
func TestLiveHuyaProxyContinuity(t *testing.T) {
	if os.Getenv("TVBOX_LIVE_PROXY_E2E") == "" {
		t.Skip("未设置 TVBOX_LIVE_PROXY_E2E，跳过真机联网验证")
	}
	duration := 6 * time.Minute
	service := NewService(8, 0, nil)
	proxy, err := newFlvProxy(service, "huya", "30682679", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()

	ctx, cancel := context.WithTimeout(context.Background(), duration+30*time.Second)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, proxy.url, nil)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("本地代理状态码=%d", response.StatusCode)
	}

	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(response.Body, header); err != nil {
		t.Fatalf("读取 FLV 头失败：%v", err)
	}

	startedAt := time.Now()
	lastArrival := startedAt
	lastTs := int64(-1)
	var tags int64
	var maxGap time.Duration
	var gapFrom, gapTo int64
	for time.Since(startedAt) < duration {
		tagHeader := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(response.Body, tagHeader); err != nil {
			t.Fatalf("读取标签失败：%v（已读 %d 个标签）", err, tags)
		}
		size := int(tagHeader[1])<<16 | int(tagHeader[2])<<8 | int(tagHeader[3])
		ts := int64(tagHeader[4])<<16 | int64(tagHeader[5])<<8 | int64(tagHeader[6]) | int64(tagHeader[7])<<24
		if _, err := io.ReadFull(response.Body, make([]byte, size+flvProxyPrevTagSizeLen)); err != nil {
			t.Fatalf("读取标签数据失败：%v", err)
		}
		now := time.Now()
		gap := now.Sub(lastArrival)
		if gap > maxGap && tags > 0 {
			maxGap = gap
			gapFrom, gapTo = lastTs, ts
		}
		lastArrival = now
		// 同一时间戳的音视频标签是合法的，只校验不允许回退。
		if tags > 0 && ts < lastTs {
			t.Fatalf("时间戳回退：上一帧 %d，当前 %d（第 %d 个标签）", lastTs, ts, tags)
		}
		lastTs = ts
		tags++
		if tags%500 == 0 {
			fmt.Printf("t=%.0fs tags=%d maxGap=%v stats=%s\n", time.Since(startedAt).Seconds(), tags, maxGap, proxy.statsJSON())
		}
	}
	fmt.Printf("结束：时长=%v tags=%d maxGap=%v(%d→%dms) lastTs=%d stats=%s\n",
		duration, tags, maxGap, gapFrom, gapTo, lastTs, proxy.statsJSON())
}
