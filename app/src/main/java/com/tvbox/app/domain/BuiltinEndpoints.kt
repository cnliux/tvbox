package com.tvbox.app.domain

/**
 * S1-02：应用主动请求的内置 API 域名（由 tools/gen_nsc.py 生成，禁止手改）。
 * 与 network_security_config.xml 的 domain-config 一一对应；新增内置域必须登记在这里，
 * 否则 [BuiltinEndpointPolicyTest] 会断言 NSC 与枚举不一致。
 */
enum class BuiltinEndpoint(val host: String) {
    DoubanCom("douban.com"),
    DoubanioCom("doubanio.com"),
    D360kanCom("360kan.com"),
    D360Cn("360.cn"),
    GithubCom("github.com"),
    GithubusercontentCom("githubusercontent.com"),
    JsdelivrNet("jsdelivr.net"),
    GhProxyCom("gh-proxy.com"),
    GhProxyOrg("gh-proxy.org"),
    GhproxyNet("ghproxy.net"),
    ;

    companion object {
        /** 该 host 是否属于内置高敏域（含子域）。 */
        fun isBuiltin(host: String): Boolean {
            val normalized = host.trim().lowercase().trimEnd('.')
            return entries.any { normalized == it.host || normalized.endsWith("." + it.host) }
        }
    }
}
