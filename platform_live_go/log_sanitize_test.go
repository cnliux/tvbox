package platformlive

import (
	"strings"
	"testing"
)

func TestSanitizeLogLine(t *testing.T) {
	cases := []struct{ in, want string }{
		{
			"GET https://cdn.example.com/live.flv?token=abc123&expire=999",
			"GET https://cdn.example.com/live.flv?token=***&expire=***",
		},
		{
			"url=http://h/live?wsSecret=secretvalue&wsTime=123456",
			"url=http://h/live?wsSecret=***&wsTime=***",
		},
		{"Authorization: Bearer abcdefghijklmnop", "Authorization: Bearer ***"},
		{"key sk-abcdefghijklmnopqrstuvwxyz leaked", "key *** leaked"},
		{"normal line without secrets", "normal line without secrets"},
	}
	for _, tc := range cases {
		if got := SanitizeLogLine(tc.in); got != tc.want {
			t.Errorf("SanitizeLogLine(%q) = %q, want %q", tc.in, got, tc.want)
		}
		if strings.Contains(SanitizeLogLine(tc.in), "abc123") {
			t.Errorf("secret survived sanitization: %q", tc.in)
		}
	}
}
