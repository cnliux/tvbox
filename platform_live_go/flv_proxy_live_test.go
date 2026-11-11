package platformlive

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"testing"
	"time"
)

// TestFlvProxyLiveStreamReplay 用真实平台 CDN 流验证「续流后是否重复播放旧画面」。
//
// 与 TestFlvProxyReplayDetection 的区别：后者用 httptest 构造可控的回放场景做回归守卫，
// 本用例连的是真实虎牙/斗鱼线路，用于真机之外的端到端复核。Android 上不便录制代理输出
// （ServeHTTP 单客户端，外接录制会抢走播放器的 writer 并触发看门狗重建），因此放到 PC 跑。
//
// 默认跳过（需联网 + 数分钟）。手动运行：
//
//	TVBOX_LIVE_E2E=1 go test -count=1 -v -timeout 10m -run TestFlvProxyLiveStreamReplay ./
//	TVBOX_LIVE_E2E=1 TVBOX_LIVE_ASSERT=1 go test -count=1 -timeout 10m -run TestFlvProxyLiveStreamReplay ./
//
// 可选环境变量：
//
//	TVBOX_LIVE_SITE          平台 ID，默认 huya（可选 douyu）
//	TVBOX_LIVE_ROOM          房间号，默认 660000
//	TVBOX_LIVE_DURATION_SEC  录制秒数，默认 200（需覆盖 CDN 约 120~137 秒的关流周期）
//	TVBOX_LIVE_ASSERT        设为 1 时，出现重复帧即判定失败
func TestFlvProxyLiveStreamReplay(t *testing.T) {
	if os.Getenv("TVBOX_LIVE_E2E") == "" {
		t.Skip("未设置 TVBOX_LIVE_E2E，跳过真实 CDN 联网检测")
	}
	site := envOr("TVBOX_LIVE_SITE", "huya")
	roomID := envOr("TVBOX_LIVE_ROOM", "660000")
	durationSec := 200
	if raw := os.Getenv("TVBOX_LIVE_DURATION_SEC"); raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil && parsed > 0 {
			durationSec = parsed
		}
	}

	service := NewService(8, 0, nil, "")
	proxy, err := newFlvProxy(service, site, roomID, 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(durationSec)*time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, proxy.url, nil)
	if err != nil {
		t.Fatalf("构造请求失败：%v", err)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("连接本机代理失败：%v", err)
	}
	defer response.Body.Close()

	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(response.Body, header); err != nil {
		t.Fatalf("读取 FLV 头失败（平台可能未开播或本机网络不通）：%v", err)
	}

	tags := make([]replayOutTag, 0, 8192)
	for {
		tagHeader := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(response.Body, tagHeader); err != nil {
			break // 录制时间到 / 连接关闭
		}
		size := int(tagHeader[1])<<16 | int(tagHeader[2])<<8 | int(tagHeader[3])
		data := make([]byte, size)
		if _, err := io.ReadFull(response.Body, data); err != nil {
			break
		}
		if _, err := io.ReadFull(response.Body, make([]byte, flvProxyPrevTagSizeLen)); err != nil {
			break
		}
		ts := int64(tagHeader[4])<<16 | int64(tagHeader[5])<<8 | int64(tagHeader[6]) | int64(tagHeader[7])<<24
		tagType := tagHeader[0] & 0x1f
		tags = append(tags, replayOutTag{
			tagType: tagType,
			ts:      ts,
			fp:      replayFingerprint(data),
			isSeq:   tagType == flvTagTypeVideo && isAvcSequenceHeader(data),
		})
	}

	rollback := 0
	videoFrames := 0
	for index, tag := range tags {
		if index > 0 && tag.ts < tags[index-1].ts {
			rollback++
		}
		if tag.tagType == flvTagTypeVideo && !tag.isSeq {
			videoFrames++
		}
	}
	intervals, duplicates, maxDurationMs := analyzeReplay(tags, 2)

	fmt.Println("──────── 真实 CDN 续流重复画面检测报告 ────────")
	fmt.Printf("平台 / 房间            : %s / %s\n", site, roomID)
	fmt.Printf("录制时长              : %d s\n", durationSec)
	fmt.Printf("代理输出 tag 数       : %d（其中视频帧 %d）\n", len(tags), videoFrames)
	fmt.Printf("输出时间戳回退次数    : %d\n", rollback)
	fmt.Printf("重复画面帧数          : %d\n", duplicates)
	fmt.Printf("连续重复区间数        : %d\n", len(intervals))
	fmt.Printf("最长重复时长          : %d ms (%.2f s)\n", maxDurationMs, float64(maxDurationMs)/1000)
	fmt.Printf("代理统计              : %s\n", proxy.statsJSON())
	fmt.Println("──────────────────────────────────────────────")

	if len(tags) == 0 {
		t.Fatalf("未收到任何 FLV tag：平台未开播、解析失败或本机网络不通，请检查 TVBOX_LIVE_* 环境变量")
	}
	if os.Getenv("TVBOX_LIVE_ASSERT") == "1" && duplicates > 0 {
		t.Fatalf("检测到 %d 帧重复画面，最长 %.2f s", duplicates, float64(maxDurationMs)/1000)
	}
}

func envOr(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}
