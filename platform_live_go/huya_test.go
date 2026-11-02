package platformlive

// 虎牙解析的单元测试：移植 platform_live_server/test_platform_resolvers.py
// 中的 HuyaResolverTest。

import (
	"encoding/base64"
	"strings"
	"testing"
)

// 对齐 test_anti_code_contains_signed_fields。
func TestHuyaAntiCodeContainsSignedFields(t *testing.T) {
	fm := base64.StdEncoding.EncodeToString([]byte("secret_prefix_extra"))
	anti := "fm=" + fm + "&wsTime=65aa&ctype=huya_pc_exe&t=0&fs=1"
	result, err := huyaBuildAntiCode("stream", 1234, anti)
	if err != nil {
		t.Fatalf("huyaBuildAntiCode error: %v", err)
	}
	for _, want := range []string{"wsSecret=", "seqid=", "u=2468"} {
		if !strings.Contains(result, want) {
			t.Fatalf("anti-code 缺少字段 %q：%s", want, result)
		}
	}
}

// 对齐 test_tars_token_round_trip：编码器输出可被解码器取回 token。
func TestHuyaTarsTokenRoundTrip(t *testing.T) {
	responseBody := tarsEncodeStruct(tarsEncodeString("token", 0), 0)
	responseMap := tarsEncodeMapBytes(map[string][]byte{"tRsp": responseBody}, 0)
	packet := tarsEncodeBytes(responseMap, 7)
	packet = append(tarsEncodeInt(0, 1), packet...)
	framed := make([]byte, 4)
	frameLength := len(packet) + 4
	framed[0] = byte(frameLength >> 24)
	framed[1] = byte(frameLength >> 16)
	framed[2] = byte(frameLength >> 8)
	framed[3] = byte(frameLength)
	packet = append(framed, packet...)
	token, err := huyaDecodeTupResponse(packet)
	if err != nil {
		t.Fatalf("huyaDecodeTupResponse error: %v", err)
	}
	if token != "token" {
		t.Fatalf("token = %q; want token", token)
	}
}

// 对齐 test_extract_nested_global_init：HNF_GLOBAL_INIT 平衡 JSON 提取。
func TestHuyaExtractNestedGlobalInit(t *testing.T) {
	html := `<script>window.HNF_GLOBAL_INIT = {"roomInfo":{"eLiveStatus":2,"x":{"n":1}}};</script>`
	_, payload, err := huyaParseRoomInfoPayload(html)
	if err != nil {
		t.Fatalf("huyaParseRoomInfoPayload error: %v", err)
	}
	roomInfo := payload["roomInfo"].(map[string]interface{})
	nested := roomInfo["x"].(map[string]interface{})
	if got := nested["n"].(float64); got != 1 {
		t.Fatalf("payload[roomInfo][x][n] = %v; want 1", got)
	}
}

// 对齐 test_game_id_normalizes_float_gid。
func TestHuyaGameIDNormalizesFloatGID(t *testing.T) {
	cases := []struct {
		input interface{}
		want  string
	}{
		{float64(1), "1"},
		{float64(862), "862"},
		{"1,2", "1"},
		{map[string]interface{}{"value": "862,0"}, "862"},
		{nil, ""},
	}
	for _, c := range cases {
		if got := huyaGameID(c.input); got != c.want {
			t.Fatalf("huyaGameID(%v) = %q; want %q", c.input, got, c.want)
		}
	}
}
