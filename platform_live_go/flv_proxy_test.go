package platformlive

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"
)

// fakeProxyAdapter 只提供 Resolve，用于验证代理的缝流行为。
type fakeProxyAdapter struct {
	streamURL string
	headers   map[string]string
	resolves  int64
}

func (f *fakeProxyAdapter) SiteID() string                               { return "fake" }
func (f *fakeProxyAdapter) SiteName() string                             { return "测试平台" }
func (f *fakeProxyAdapter) Description() string                          { return "" }
func (f *fakeProxyAdapter) NormalizeRoomID(value string) (string, error) { return Text(value, ""), nil }
func (f *fakeProxyAdapter) GetCategoryTree() ([]LiveParentCategory, []LiveCategory, error) {
	return nil, nil, nil
}
func (f *fakeProxyAdapter) GetCategoryRooms(string, int64) (LiveRoomsPage, error) {
	return LiveRoomsPage{}, nil
}
func (f *fakeProxyAdapter) Resolve(roomInput string) (*ResolvedLiveStream, error) {
	atomic.AddInt64(&f.resolves, 1)
	return &ResolvedLiveStream{
		RoomID:     roomInput,
		Candidates: []LiveStreamCandidate{{CDN: "测试线路", URL: f.streamURL, Protocol: "flv"}},
		Headers:    f.headers,
	}, nil
}

// writeTestFLV 输出一段“上游会话”：FLV 头 + 若干音视频标签。
func writeTestFLV(writer io.Writer, timestamps []int64) {
	_, _ = writer.Write([]byte{'F', 'L', 'V', 0x01, 0x05, 0x00, 0x00, 0x00, 0x09, 0x00, 0x00, 0x00, 0x00})
	for _, ts := range timestamps {
		size := 3
		header := []byte{0x09, 0x00, 0x00, byte(size), byte(ts >> 16), byte(ts >> 8), byte(ts), byte(ts >> 24), 0, 0, 0}
		_, _ = writer.Write(header)
		_, _ = writer.Write([]byte{0x17, 0x01, 0x00})
		prev := uint32(flvProxyTagHeaderSize + size)
		_, _ = writer.Write([]byte{byte(prev >> 24), byte(prev >> 16), byte(prev >> 8), byte(prev)})
	}
}

func readTestFLVTags(reader io.Reader, limit int) ([]int64, error) {
	header := make([]byte, flvProxyHeaderSize+flvProxyPrevTagSizeLen)
	if _, err := io.ReadFull(reader, header); err != nil {
		return nil, err
	}
	result := make([]int64, 0, limit)
	for len(result) < limit {
		tagHeader := make([]byte, flvProxyTagHeaderSize)
		if _, err := io.ReadFull(reader, tagHeader); err != nil {
			return result, err
		}
		size := int(tagHeader[1])<<16 | int(tagHeader[2])<<8 | int(tagHeader[3])
		ts := int64(tagHeader[4])<<16 | int64(tagHeader[5])<<8 | int64(tagHeader[6]) | int64(tagHeader[7])<<24
		if _, err := io.ReadFull(reader, make([]byte, size+flvProxyPrevTagSizeLen)); err != nil {
			return result, err
		}
		result = append(result, ts)
	}
	return result, nil
}

// 验证：上游两段各自从 0 开始的 FLV，被代理缝成一条时间戳单调递增的连续流。
func TestFlvProxyStitchesSessions(t *testing.T) {
	var sessions int64
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt64(&sessions, 1)
		w.Header().Set("Content-Type", "video/x-flv")
		w.WriteHeader(http.StatusOK)
		writeTestFLV(w, []int64{0, 33, 66})
	}))
	defer upstream.Close()

	adapter := &fakeProxyAdapter{streamURL: upstream.URL + flvProxyPath}
	service := &Service{byID: map[string]Adapter{"fake": adapter}}
	proxy, err := newFlvProxy(service, "fake", "1001", 0, "", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()

	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, proxy.url, nil)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatalf("连接本地代理失败：%v", err)
	}
	defer response.Body.Close()

	tags, err := readTestFLVTags(response.Body, 6)
	if err != nil && len(tags) < 6 {
		t.Fatalf("读取代理输出失败：%v（状态=%d 类型=%s 已读到 %d 个标签，代理统计=%s）",
			err, response.StatusCode, response.Header.Get("Content-Type"), len(tags), proxy.statsJSON())
	}
	expected := []int64{0, 33, 66, 99, 132, 165}
	for index, want := range expected {
		if tags[index] != want {
			t.Fatalf("第 %d 个标签时间戳=%d，期望 %d（实际序列 %v）", index, tags[index], want, tags)
		}
	}
	if got := atomic.LoadInt64(&adapter.resolves); got < 2 {
		t.Fatalf("上游关流后应重新解析，实际 Resolve 次数=%d", got)
	}
	if got := atomic.LoadInt64(&sessions); got < 2 {
		t.Fatalf("上游应有至少两次拉流，实际=%d", got)
	}

	var stats map[string]interface{}
	if err := json.Unmarshal([]byte(proxy.statsJSON()), &stats); err != nil {
		t.Fatalf("统计 JSON 解析失败：%v", err)
	}
	if reconnect, _ := stats["reconnects"].(float64); reconnect < 1 {
		t.Fatalf("统计未记录续流次数：%v", stats)
	}
}

// 验证：带平台前缀的房间主键（Kotlin 侧 room.id 形如 "huya:31297160"）
// 会被归一化成纯房间号，避免续流时 NormalizeRoomID 报“房间号格式无效”。
func TestFlvProxyStripsSitePrefix(t *testing.T) {
	service := &Service{byID: map[string]Adapter{"fake": &fakeProxyAdapter{}}}
	proxy, err := newFlvProxy(service, "fake", "fake:1001", 0, "http://127.0.0.1:1/live.flv", nil)
	if err != nil {
		t.Fatalf("newFlvProxy: %v", err)
	}
	defer proxy.close()
	if proxy.roomID != "1001" {
		t.Fatalf("房间号应去掉平台前缀，实际 %q", proxy.roomID)
	}
}

// 验证时间戳顺延与回退判据。
func TestFlvProxySpliceTimestamps(t *testing.T) {
	proxy := &flvProxy{}
	if got := proxy.spliceBase(0); got != 0 {
		t.Fatalf("首段应沿用原始时间戳，实际 %d", got)
	}
	if !proxy.acceptTs(0) {
		t.Fatal("首帧应被接受")
	}
	base := proxy.spliceBase(0)
	if want := int64(flvProxySpliceStepMs); base != want {
		t.Fatalf("续段首帧应接在上一帧之后 %dms，实际 %d", want, base)
	}
	if !proxy.acceptTs(base) {
		t.Fatal("续段首帧应被接受")
	}
	if proxy.acceptTs(base - 10) {
		t.Fatal("时间戳回退的标签应被丢弃")
	}
	if !proxy.acceptTs(base) {
		t.Fatal("同一时刻的音视频标签应被接受")
	}
}
