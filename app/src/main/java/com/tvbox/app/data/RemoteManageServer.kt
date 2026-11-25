package com.tvbox.app.data

import com.tvbox.app.util.TLog
import com.tvbox.app.domain.RemoteLoginThrottle
import com.tvbox.app.domain.RemoteManagePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class RemoteManageSession(
    val url: String,
    val host: String,
    val port: Int,
)

/** 视频源管理列表里的一行（包含已屏蔽的源）。 */
data class RemoteManageVideoSource(
    val id: String,
    val name: String,
    val url: String,
    val blocked: Boolean,
    val isHome: Boolean,
    val latencyMs: Long?,
)

data class RemoteManageSite(
    val id: String,
    val name: String,
    val blocked: Boolean,
)

/** 远程管理网页一次渲染所需的全部数据快照。 */
data class RemoteManageSnapshot(
    val videoSources: List<RemoteManageVideoSource> = emptyList(),
    val customLiveUrls: List<String> = emptyList(),
    /** 电视直播默认源下标（-1 表示未设置），与设置页同一份配置。 */
    val liveDefaultIndex: Int = -1,
    /** 节目单（XMLTV）地址；空表示未配置，直播页将显示「暂无节目单」。 */
    val epgUrl: String = "",
    val sites: List<RemoteManageSite> = emptyList(),
    val blockedVideoCategories: List<String> = emptyList(),
    val knownVideoCategories: List<String> = emptyList(),
    /** 网页最近一次“获取分类”的视频源；为空表示尚未获取。 */
    val categorySourceId: String = "",
    val categorySourceName: String = "",
)

/** 手机网页提交的一次管理操作。 */
data class RemoteManageRequest(
    val action: String,
    val id: String = "",
    val name: String = "",
    val url: String = "",
    val value: String = "",
    val content: String = "",
)

/**
 * 局域网远程管理服务。
 *
 * 安全边界（默认拒绝未授权写操作）：
 *  - GET /blocked-categories.json 是只读状态端点，有意保持开放，便于局域网内读取屏蔽分类；
 *  - GET /（管理页面）与所有 POST 状态变更端点必须携带本次会话 token。token 由
 *    [start] 随机生成（也可用 [controlToken] 外部配置），只出现在电视端展示的完整 URL 中，
 *    页面表单通过隐藏字段透传，LAN 上无法从开放端点猜到；
 *  - [allowUnauthenticatedControl] 为 true 会显式关闭写操作鉴权，仅限本机调试，默认 false。
 *
 * 注意：调用方展示的地址必须保留完整 query（含 token），否则手机端会被 403 拒绝。
 */
class RemoteManageServer(
    private val snapshot: () -> RemoteManageSnapshot,
    private val onSubmit: suspend (RemoteManageRequest) -> String?,
    /** 可选固定 token；留空则每次 start() 随机生成，避免长期有效的局域网凭据。 */
    private val controlToken: String = "",
    /** 显式开启无 token 控制（危险）；默认 false，即所有写操作都要鉴权。 */
    private val allowUnauthenticatedControl: Boolean = false,
    /** S1-01：默认只听 127.0.0.1；显式 opt-in 才监听 0.0.0.0。 */
    private val lanEnabled: Boolean = false,
) {
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val activeClients = AtomicInteger(0)

    /** 在途客户端 socket，stop() 时主动关闭，让阻塞在 readLine() 的协程立即退出。 */
    private val activeSockets = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    @Volatile
    private var sessionToken: String = ""

    /** S1-01：同网段离线爆破退避；只按来源 IP 记失败，不记录任何身份信息。 */
    private val loginThrottle = RemoteLoginThrottle()

    /**
     * 服务私有的协程作用域：acceptLoop 与每个客户端都作为它的子协程。
     * 不能复用外部传入的 viewModelScope —— 关闭远程管理对话框时 stop() 只该停掉本服务，
     * 且外部 scope 仍存活会让在途 client 协程继续跑 15s 并调用 onSubmit 修改设置。
     */
    @Volatile
    private var serverScope: CoroutineScope = newServerScope()

    /**
     * 服务世代号：stop() 递增一次，start() 全程持有自己的世代并在关键节点校验。
     * 若 start() 执行期间外部又调用了 stop()（打开对话框后立即关闭），世代失配，
     * start() 会关闭刚创建的 socket/scope 并抛错退出，避免留下无引用可停的存活 HTTP 服务。
     */
    private val generation = AtomicInteger(0)

    private fun newServerScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun start(): RemoteManageSession = withContext(Dispatchers.IO) {
        stop()
        val myGeneration = generation.get()
        val socket = createServerSocket(lanEnabled)
        if (generation.get() != myGeneration) {
            runCatching { socket.close() }
            throw IllegalStateException("远程管理服务已停止")
        }
        serverSocket = socket
        // stop() 取消了旧 scope，这里重建一个可复用的（CoroutineScope 取消后不能再次 launch）。
        val scope = newServerScope()
        serverScope = scope
        sessionToken = when {
            controlToken.isNotBlank() -> controlToken
            allowUnauthenticatedControl -> ""
            else -> newSessionToken()
        }
        val host = RemoteManagePolicy.displayHost(lanEnabled, localIpv4Address())
        scope.launch {
            acceptLoop(socket, myGeneration)
        }
        if (generation.get() != myGeneration) {
            runCatching { socket.close() }
            runCatching { scope.cancel() }
            throw IllegalStateException("远程管理服务已停止")
        }
        val tokenQuery = if (sessionToken.isEmpty()) {
            ""
        } else {
            "?$TOKEN_QUERY_KEY=${URLEncoder.encode(sessionToken, StandardCharsets.UTF_8.name())}"
        }
        RemoteManageSession(
            url = "http://$host:${socket.localPort}/$tokenQuery",
            host = host,
            port = socket.localPort,
        )
    }

    fun stop() {
        // 先关监听 socket 让 accept() 立即抛异常退出循环，再关闭在途客户端并取消整个私有作用域。
        generation.incrementAndGet()
        runCatching { serverSocket?.close() }
        serverSocket = null
        activeSockets.toList().forEach { socket -> runCatching { socket.close() } }
        activeSockets.clear()
        runCatching { serverScope.cancel() }
    }

    /** 别名，方便生命周期组件以 shutdown() 语义调用。 */
    fun shutdown() = stop()

    private suspend fun acceptLoop(socket: ServerSocket, myGeneration: Int) {
        val loopScope = serverScope
        while (loopScope.isActive && !socket.isClosed && generation.get() == myGeneration) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            if (activeClients.incrementAndGet() > MAX_CONCURRENT_CLIENTS) {
                activeClients.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            // 作为 serverScope 的子协程：stop() 时会被一并取消，关闭对话框后不再改配置。
            loopScope.launch {
                try {
                    runCatching { handleClient(client) }
                } finally {
                    activeClients.decrementAndGet()
                    runCatching { client.close() }
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            activeSockets.add(socket)
            try {
                runCatching { socket.soTimeout = CLIENT_READ_TIMEOUT_MS }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine().orEmpty()
                if (requestLine.isBlank()) return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0).orEmpty()
                val target = parts.getOrNull(1).orEmpty()
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }

                val clientIp = runCatching { socket.inetAddress?.hostAddress.orEmpty() }.getOrDefault("")

                when (method) {
                    "GET" -> when {
                        // 只读状态端点：有意开放，不要求 token（见类注释的安全边界）。
                        target.startsWith("/blocked-categories.json") ->
                            writeJsonResponse(socket, blockedCategoriesJson())
                        // S1-01：先做 IP 退避判断，退避期内连常量时间比较都不做。
                        loginThrottle.isLocked(clientIp) ->
                            writeHtmlResponse(socket, "尝试次数过多，请 30 秒后重试。", status = "429 Too Many Requests")
                        // S1-01：Header 优先，query 仅兼容旧版二维码。
                        !isAuthorized(
                            headers[TOKEN_HEADER].orEmpty()
                                .ifBlank { queryValue(target, TOKEN_QUERY_KEY).orEmpty() }
                        ) -> {
                            loginThrottle.recordFailure(clientIp)
                            writeHtmlResponse(
                                socket,
                                "管理页面需要有效 token，请使用电视端显示的完整地址访问。",
                                status = "403 Forbidden",
                            )
                        }
                        else -> {
                            loginThrottle.recordSuccess(clientIp)
                            writeHtmlResponse(socket, page(queryValue(target, "message"), sessionToken))
                        }
                    }
                    "POST" -> {
                        val declaredLength = headers["content-length"]?.toLongOrNull() ?: 0L
                        if (declaredLength < 0) {
                            writeHtmlResponse(socket, "请求体长度不合法，已拒绝。", status = "400 Bad Request")
                            return
                        }
                        if (declaredLength > MAX_REQUEST_BODY_BYTES) {
                            writeHtmlResponse(socket, "请求体过大，已拒绝。", status = "413 Payload Too Large")
                            return
                        }
                        if (loginThrottle.isLocked(clientIp)) {
                            writeHtmlResponse(socket, "尝试次数过多，请 30 秒后重试。", status = "429 Too Many Requests")
                            return
                        }
                        val body = readBody(reader, declaredLength.toInt())
                        val form = parseForm(body)
                        // S1-01：Header 优先，表单隐藏字段仅兼容旧版页面。
                        val suppliedToken = headers[TOKEN_HEADER].orEmpty()
                            .ifBlank { form[TOKEN_QUERY_KEY].orEmpty() }
                        if (!isAuthorized(suppliedToken)) {
                            loginThrottle.recordFailure(clientIp)
                            writeHtmlResponse(socket, "token 无效或已过期，请刷新电视端页面后重试。", status = "403 Forbidden")
                            return
                        }
                        loginThrottle.recordSuccess(clientIp)
                        val request = RemoteManageRequest(
                            action = form["action"].orEmpty(),
                            id = form["id"].orEmpty(),
                            name = form["name"].orEmpty(),
                            url = form["url"].orEmpty(),
                            value = form["value"].orEmpty(),
                            content = form["content"].orEmpty(),
                        )
                        // onSubmit 抛错时只回稳定文案，不把异常原文（可能含 URL/凭据）写进页面。
                        val message = runCatching { onSubmit(request) }
                            .getOrElse { "操作失败（${it.javaClass.simpleName}）" }
                            ?: "操作成功"
                        val tokenPart = if (sessionToken.isEmpty()) {
                            ""
                        } else {
                            "$TOKEN_QUERY_KEY=${URLEncoder.encode(sessionToken, StandardCharsets.UTF_8.name())}&"
                        }
                        val location = "/?$tokenPart" +
                            "message=" + URLEncoder.encode(message, StandardCharsets.UTF_8.name())
                        writeRedirect(socket, location)
                    }
                    else -> writeHtmlResponse(socket, "不支持的请求。", status = "405 Method Not Allowed")
                }
            } finally {
                activeSockets.remove(socket)
            }
        }
    }

    /** 常量时间比较，避免通过响应耗时逐字节猜 token。 */
    private fun isAuthorized(supplied: String): Boolean {
        if (allowUnauthenticatedControl && sessionToken.isEmpty()) return true
        if (sessionToken.isEmpty()) return false
        return MessageDigest.isEqual(
            sessionToken.toByteArray(StandardCharsets.UTF_8),
            supplied.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun newSessionToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { value ->
                val byte = value.toInt() and 0xFF
                append(digits[byte shr 4]).append(digits[byte and 0x0F])
            }
        }
    }

    /** snapshot() 由外部注入，可能因设置读取失败而抛错；失败时退化为空白页面而不是断开连接。 */
    private fun safeSnapshot(): RemoteManageSnapshot =
        runCatching { snapshot() }.getOrDefault(RemoteManageSnapshot())

    private fun readBody(reader: BufferedReader, contentLength: Int): String {
        if (contentLength <= 0) return ""
        val safeLength = contentLength.coerceAtMost(MAX_REQUEST_BODY_BYTES.toInt())
        val bodyChars = CharArray(safeLength)
        var readTotal = 0
        while (readTotal < safeLength) {
            val read = reader.read(bodyChars, readTotal, safeLength - readTotal)
            if (read <= 0) break
            readTotal += read
        }
        return String(bodyChars, 0, readTotal)
    }

    private fun writeHtmlResponse(socket: Socket, body: String, status: String = "200 OK") {
        writeHttpResponse(socket, status, "text/html; charset=utf-8", body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeJsonResponse(socket: Socket, json: String) {
        writeHttpResponse(socket, "200 OK", "application/json; charset=utf-8", json.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * 统一响应写出：头部与 body 走同一个输出流，且不能提前关闭它。
     *
     * 旧实现用 `BufferedWriter(...).use {}` 写头部，use 结束会级联关闭底层
     * `socket.getOutputStream()`，随后再对该流写 body 会抛 "Stream closed" 或丢失响应体；
     * 这里改为一次性写出并只 flush，不关闭（socket 由 handleClient 的 `client.use` 统一关闭）。
     */
    private fun writeHttpResponse(socket: Socket, status: String, contentType: String, body: ByteArray) {
        val out = java.io.BufferedOutputStream(socket.getOutputStream())
        val header = (
            "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        out.write(header)
        out.write(body)
        out.flush()
    }

    private fun blockedCategoriesJson(): String {
        val categories = safeSnapshot().blockedVideoCategories
        val items = categories.joinToString(", ") { category ->
            "\"" + category.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        return "{\n  \"version\": 1,\n  \"updatedAtMs\": ${System.currentTimeMillis()},\n" +
            "  \"categories\": [$items]\n}\n"
    }

    private fun writeRedirect(socket: Socket, location: String) {
        // 同样不提前关闭底层输出流，交给 client.use 统一释放。
        val out = java.io.BufferedOutputStream(socket.getOutputStream())
        out.write(
            (
                "HTTP/1.1 303 See Other\r\n" +
                    "Location: $location\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(StandardCharsets.UTF_8),
        )
        out.flush()
    }

    private fun page(message: String?, token: String): String {
        val data = safeSnapshot()
        val html = """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>聚合视频 远程管理</title>
              <style>
                :root { --bg:#10141f; --card:#1a2130; --line:#2b3446; --ink:#f5f7fb; --dim:#8f9bb3; --accent:#28d0b0; --danger:#ffb4ab; }
                * { box-sizing:border-box; }
                body { margin:0; font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif; background:var(--bg); color:var(--ink); }
                main { max-width:820px; margin:0 auto; padding:20px 16px 48px; }
                header.top { margin-bottom:4px; }
                h1 { font-size:22px; margin:0; }
                .hint { color:var(--dim); font-size:13px; line-height:1.6; margin:6px 0 0; }
                /* ── 标签页：视频源 / IPTV 分离，纯客户端切换，无网络依赖 ── */
                nav.tabs { display:flex; gap:8px; margin:18px 0 14px; position:sticky; top:0; background:var(--bg); padding:8px 0; z-index:2; }
                nav.tabs a { flex:1; text-align:center; text-decoration:none; color:var(--dim); font-weight:700; font-size:14px; padding:11px 0; border-radius:10px; background:#171d2a; border:1px solid var(--line); }
                nav.tabs a:hover { color:var(--ink); }
                nav.tabs a.active { color:var(--accent); border-color:var(--accent); background:#13232a; }
                section.panel { display:none; background:var(--card); border:1px solid #2f3a4f; border-radius:14px; padding:16px; margin-top:14px; }
                section.panel.active { display:block; }
                .panel > h2 { font-size:16px; margin:0 0 4px; display:flex; align-items:center; gap:8px; }
                .panel > h2 .count { font-size:12px; color:var(--dim); font-weight:400; }
                .panel > p.lead { color:var(--dim); font-size:13px; margin:4px 0 10px; line-height:1.5; }
                .row { display:flex; align-items:center; gap:8px; padding:10px 0; border-top:1px solid var(--line); flex-wrap:wrap; }
                .row:first-of-type { border-top:0; }
                .grow { flex:1; min-width:0; }
                .name { font-weight:700; word-break:break-all; }
                .url { color:var(--dim); font-size:12px; word-break:break-all; margin-top:2px; }
                .tag { color:#6ad6a8; font-size:11px; background:#1c3a33; border-radius:6px; padding:2px 7px; margin-right:4px; }
                .tag.off { color:var(--danger); background:#3a1f22; }
                input, textarea { box-sizing:border-box; border:1px solid #4a5568; border-radius:8px; padding:10px; font-size:14px; background:#171d2a; color:#fff; font-family:inherit; }
                input.text, textarea.text { width:100%; }
                textarea { margin-top:6px; resize:vertical; }
                button { border:0; border-radius:8px; padding:9px 13px; font-size:13px; font-weight:700; color:#051b17; background:var(--accent); cursor:pointer; }
                button.ghost { background:#2a3446; color:var(--ink); }
                button.danger { background:#4a2a2f; color:var(--danger); }
                .form-row { display:flex; gap:8px; margin-top:10px; flex-wrap:wrap; }
                .form-row input { flex:1; min-width:140px; }
                .message { background:#1c3a33; border:1px solid #2f6b58; color:#8ff0cf; border-radius:10px; padding:10px 12px; margin-top:12px; font-size:14px; }
                .empty { color:var(--dim); font-size:13px; }
                .chips { display:flex; flex-wrap:wrap; gap:8px; margin-top:8px; }
                .chip { background:#2a3446; color:var(--ink); border-radius:999px; padding:8px 14px; font-weight:600; font-size:13px; border:0; }
                .chip.blocked { background:#5a2a2a; color:var(--danger); text-decoration:line-through; }
                a { color:#6ad6a8; }
                footer { color:#6f7b93; font-size:12px; margin-top:24px; text-align:center; }
                .divider { height:1px; background:var(--line); margin:14px 0; }
              </style>
            </head>
            <body>
              <main>
                <header class="top">
                  <h1>聚合视频 远程管理</h1>
                </header>
                <p class="hint">同一局域网内访问，修改会立即同步到电视并保存。视频源与 IPTV 分区管理，互不影响。地址含本次会话令牌，请勿转发。</p>
                ${message?.takeIf { it.isNotBlank() }?.let { "<div class=\"message\">${it.htmlEscape()}</div>" }.orEmpty()}

                <nav class="tabs">
                  <a href="#video" data-tab="video" class="active">视频源</a>
                  <a href="#iptv" data-tab="iptv">IPTV 直播</a>
                  <a href="#platform" data-tab="platform">平台站点</a>
                </nav>

                <!-- ═══════════ 视频源（点播 / MacCms 接口）— 独立分区 ═══════════ -->
                <section class="panel active" id="video">
                  <h2>视频源 <span class="count">${data.videoSources.size} 个 · 点播接口</span></h2>
                  <p class="lead">点播影片的数据来源。可设首页、屏蔽/删除、测速排序，支持订阅地址导入。</p>
                  ${if (data.videoSources.isEmpty()) {
                      "<p class=\"empty\">暂无视频源，请在下方添加或导入订阅。</p>"
                  } else {
                      data.videoSources.joinToString("") { source ->
                          itemRow(
                              title = source.name,
                              subtitle = source.url,
                              tag = videoSourceTag(source),
                              actions = videoSourceActions(source),
                          )
                      }
                  }}
                  <div class="form-row">
                    ${toggleForm("source-test", "", "全部测速并排序")}
                  </div>
                  <div class="divider"></div>
                  ${addForm("video-add", "接口名称", "MacCms 地址，例如 https://example.com/api.php/provide/vod", "添加接口")}
                  <p class="url" style="margin-top:12px">通过订阅地址导入（支持 JSON 与 TVBox 格式）</p>
                  <form method="post">
                    <input type="hidden" name="action" value="source-import" />
                    <input class="text" name="url" placeholder="https://example.com/config.json" autocomplete="off" />
                    <textarea class="text" name="content" rows="3" placeholder="也可以直接粘贴配置内容"></textarea>
                    <div class="form-row"><button>导入订阅</button></div>
                  </form>
                  ${categoryBlock(data)}
                </section>

                <!-- ═══════════ IPTV（直播源 / EPG 节目单）— 与视频源完全独立 ═══════════ -->
                <section class="panel" id="iptv">
                  <h2>IPTV 直播源 <span class="count">${data.customLiveUrls.size} 个 · M3U / TXT</span></h2>
                  <p class="lead">电视直播频道来源，与点播视频源相互独立、互不影响。留空则使用内置频道。</p>
                  ${if (data.customLiveUrls.isEmpty()) {
                      "<p class=\"empty\">暂无自定义直播源。</p>"
                  } else {
                      data.customLiveUrls.joinToString("") { url ->
                          itemRow(
                              title = "直播源",
                              subtitle = url,
                              tag = "",
                              actions = "<form method=\"post\"><input type=\"hidden\" name=\"action\" value=\"live-delete\" />" +
                                  "<input type=\"hidden\" name=\"url\" value=\"${url.htmlEscape()}\" />" +
                                  "<button class=\"danger\">删除</button></form>",
                          )
                      }
                  }}
                  ${addForm("live-add", "", "直播源地址（M3U/TXT），每行一个", "添加直播源")}
                  <div class="divider"></div>
                  ${liveDefaultBlock(data)}
                  <div class="divider"></div>
                  ${epgBlock(data)}
                </section>

                <!-- ═══════════ 平台站点（虎牙/斗鱼等） ═══════════ -->
                <section class="panel" id="platform">
                  <h2>平台直播站点 <span class="count">${data.sites.size} 个</span></h2>
                  ${if (data.sites.isEmpty()) {
                      "<p class=\"empty\">站点列表暂不可用。</p>"
                  } else {
                      data.sites.joinToString("") { site ->
                          itemRow(
                              title = site.name,
                              subtitle = if (site.blocked) "已删除（可在本页恢复）" else "可用",
                              tag = if (site.blocked) "已删除" else "启用",
                              actions = toggleForm(
                                  action = "site-toggle",
                                  id = site.id,
                                  value = "",
                                  label = if (site.blocked) "恢复" else "删除",
                                  valueName = "value",
                              ),
                          )
                      }
                  }}
                </section>

                <footer>聚合视频 局域网远程管理 · 写操作需会话 token</footer>
              </main>
              <script>
                // 标签页切换：点击后只显示对应分区，其余隐藏（IPTV 与视频源完全分离）。
                (function () {
                  var links = document.querySelectorAll('nav.tabs a[data-tab]');
                  var panels = document.querySelectorAll('section.panel');
                  function show(tab) {
                    links.forEach(function (a) { a.classList.toggle('active', a.dataset.tab === tab); });
                    panels.forEach(function (p) { p.classList.toggle('active', p.id === tab); });
                  }
                  links.forEach(function (a) {
                    a.addEventListener('click', function (e) {
                      e.preventDefault();
                      show(a.dataset.tab);
                      if (history.replaceState) history.replaceState(null, '', '#' + a.dataset.tab);
                    });
                  });
                  // 带 hash 进入时定位到对应分区。
                  var initial = (location.hash || '').replace('#', '');
                  if (initial && document.getElementById(initial)) show(initial);
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
        // token 只通过隐藏字段透传给本页 POST；匿名可访问的只读端点不含它。
        return if (token.isEmpty()) {
            html
        } else {
            html.replace(
                "<form method=\"post\">",
                "<form method=\"post\"><input type=\"hidden\" name=\"$TOKEN_QUERY_KEY\" value=\"${token.htmlEscape()}\" />",
            )
        }
    }

    private fun videoSourceTag(source: RemoteManageVideoSource): String {
        val parts = mutableListOf<String>()
        if (source.isHome) parts += "首页"
        if (source.blocked) parts += "已屏蔽"
        if (source.latencyMs != null) parts += "${source.latencyMs}ms"
        return parts.joinToString(" · ")
    }

    private fun videoSourceActions(source: RemoteManageVideoSource): String = buildString {
        if (!source.blocked && !source.isHome) {
            append(hiddenForm("source-home", source.id, "设为首页"))
        }
        append(hiddenForm("source-categories", source.id, "分类"))
        append(
            hiddenForm(
                if (source.blocked) "source-unblock" else "source-block",
                source.id,
                if (source.blocked) "取消屏蔽" else "屏蔽",
            ),
        )
        append(hiddenForm("source-delete", source.id, "删除"))
    }

    /** 直播默认源设置：与 App 内设置页共用同一份配置（同一时间只有一个默认源）。 */
    private fun liveDefaultBlock(data: RemoteManageSnapshot): String {
        if (data.customLiveUrls.isEmpty()) return ""
        val rows = data.customLiveUrls.mapIndexed { index, url ->
            val isDefault = index == data.liveDefaultIndex
            val action = if (isDefault) {
                "<form method=\"post\"><input type=\"hidden\" name=\"action\" value=\"live-default-clear\" />" +
                    "<button class=\"ghost\">取消默认</button></form>"
            } else {
                "<form method=\"post\"><input type=\"hidden\" name=\"action\" value=\"live-default-set\" />" +
                    "<input type=\"hidden\" name=\"value\" value=\"$index\" />" +
                    "<button class=\"ghost\">设为默认</button></form>"
            }
            itemRow(
                title = "源${index + 1}",
                subtitle = url,
                tag = if (isDefault) "默认" else "",
                actions = action,
            )
        }.joinToString("")
        return """
            <div class="sub">默认直播源（优先使用；不可用时自动切换备用源）</div>
            $rows
        """
    }

    /** 节目单（EPG）地址设置：直播页的节目单与 EPG 卡片来自该 XMLTV 接口。 */
    private fun epgBlock(data: RemoteManageSnapshot): String {
        return """
            <div class="sub">节目单（EPG）</div>
            <form method="post">
              <input type="hidden" name="action" value="live-epg" />
              <div class="form-row">
                <input class="text" name="url" value="${data.epgUrl.htmlEscape()}" placeholder="https://example.com/epg.xml" autocomplete="off" />
                <button>保存</button>
              </div>
            </form>
            <p class="empty">留空则直播页显示「暂无节目单」，不影响播放；M3U 源自带 url-tvg 时会自动采用。</p>
        """
    }

    private fun itemRow(title: String, subtitle: String, tag: String, actions: String): String {
        val tagHtml = if (tag.isBlank()) {
            ""
        } else {
            val off = tag.contains("已删除") || tag.contains("已关闭") || tag.contains("已屏蔽")
            "<span class=\"tag${if (off) " off" else ""}\">${tag.htmlEscape()}</span>"
        }
        val subtitleHtml = if (subtitle.isBlank()) "" else "<div class=\"url\">${subtitle.htmlEscape()}</div>"
        return """
            <div class="row">
              <div class="grow">
                <div class="name">${title.htmlEscape()}</div>
                $subtitleHtml
                $tagHtml
              </div>
              $actions
            </div>
        """.trimIndent()
    }

    private fun categoryBlock(data: RemoteManageSnapshot): String {
        val heading = if (data.categorySourceId.isBlank()) {
            "<p class=\"url\" style=\"margin-top:16px\">屏蔽分类</p>" +
                "<p class=\"empty\">先点击上方视频源的“分类”按钮获取该源分类，再点分类按钮即可屏蔽/恢复。</p>"
        } else {
            "<p class=\"url\" style=\"margin-top:16px\">“${data.categorySourceName.htmlEscape()}”的分类（点击屏蔽/恢复）</p>" +
                if (data.knownVideoCategories.isEmpty()) {
                    "<p class=\"empty\">该源没有返回分类。</p>"
                } else {
                    "<div class=\"chips\">" + data.knownVideoCategories.joinToString("") { category ->
                        val blocked = category in data.blockedVideoCategories
                        "<form method=\"post\"><input type=\"hidden\" name=\"action\" value=\"category-toggle\" />" +
                            "<input type=\"hidden\" name=\"name\" value=\"${category.htmlEscape()}\" />" +
                            "<button class=\"chip${if (blocked) " blocked" else ""}\">${category.htmlEscape()}</button></form>"
                    } + "</div>"
                }
        }
        val blockedList = if (data.blockedVideoCategories.isEmpty()) {
            "<p class=\"empty\">尚未屏蔽任何分类。</p>"
        } else {
            "<div class=\"form-row\">" + data.blockedVideoCategories.joinToString("") { category ->
                "<form method=\"post\"><input type=\"hidden\" name=\"action\" value=\"category-unblock\" />" +
                    "<input type=\"hidden\" name=\"name\" value=\"${category.htmlEscape()}\" />" +
                    "<button class=\"chip blocked\">${category.htmlEscape()} · 恢复</button></form>"
            } + "</div>"
        }
        return heading +
            "<p class=\"url\" style=\"margin-top:12px\">已屏蔽 ${data.blockedVideoCategories.size} 个分类" +
            "（常驻 blocked_categories.json）：</p>" +
            blockedList +
            "<form method=\"post\"><input type=\"hidden\" name=\"action\" value=\"category-block\" />" +
            "<div class=\"form-row\"><input name=\"name\" placeholder=\"手动输入分类名，例如 伦理片\" required />" +
            "<button class=\"ghost\">添加屏蔽</button></div></form>" +
            "<p class=\"url\" style=\"margin-top:12px\"><a href=\"/blocked-categories.json\" target=\"_blank\">" +
            "查看屏蔽分类 JSON</a></p>"
    }

    private fun hiddenForm(action: String, id: String, label: String): String {
        return "<form method=\"post\"><input type=\"hidden\" name=\"action\" value=\"$action\" />" +
            "<input type=\"hidden\" name=\"id\" value=\"${id.htmlEscape()}\" />" +
            "<button class=\"ghost\">$label</button></form>"
    }


    private fun toggleForm(
        action: String,
        value: String,
        label: String,
        id: String = "",
        valueName: String = "value",
    ): String {
        val idInput = if (id.isBlank()) "" else "<input type=\"hidden\" name=\"id\" value=\"${id.htmlEscape()}\" />"
        return "<form method=\"post\"><input type=\"hidden\" name=\"action\" value=\"$action\" />$idInput" +
            "<input type=\"hidden\" name=\"$valueName\" value=\"${value.htmlEscape()}\" />" +
            "<button class=\"ghost\">$label</button></form>"
    }

    private fun addForm(action: String, namePlaceholder: String, urlPlaceholder: String, label: String): String {
        val nameInput = if (namePlaceholder.isBlank()) {
            ""
        } else {
            "<input name=\"name\" placeholder=\"${namePlaceholder.htmlEscape()}\" autocomplete=\"off\" />"
        }
        return """
            <form method="post">
              <input type="hidden" name="action" value="$action" />
              <div class="form-row">
                $nameInput
                <input name="url" placeholder="${urlPlaceholder.htmlEscape()}" autocomplete="off" required />
                <button>$label</button>
              </div>
            </form>
        """.trimIndent()
    }

    private fun queryValue(target: String, key: String): String? {
        val queryStart = target.indexOf('?')
        if (queryStart < 0) return null
        val query = target.substring(queryStart + 1)
        val raw = query.split('&').firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "") ?: return null
        // 畸形百分号编码不能让整个 GET 处理链抛异常。
        return runCatching { raw.urlDecode() }.getOrNull()
    }

    private fun parseForm(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return runCatching {
            body.split('&')
                .mapNotNull { pair ->
                    val separator = pair.indexOf('=')
                    if (separator < 0) return@mapNotNull null
                    pair.substring(0, separator).urlDecode() to pair.substring(separator + 1).urlDecode()
                }
                .toMap()
        }.getOrElse { error ->
            TLog.w("TVBoxRemoteManage") { "表单解析失败：${error.message}" }
            emptyMap()
        }
    }

    private fun createServerSocket(lan: Boolean): ServerSocket {
        // S1-01：默认只听回环。先设置 reuseAddress 再 bind：stop() 后可立即复用同端口。
        val host = RemoteManagePolicy.bindHost(lan)
        fun bind(port: Int): ServerSocket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(host, port))
        }
        return runCatching { bind(RemoteManagePolicy.PREFERRED_PORT) }.getOrElse { bind(0) }
    }

    private fun localIpv4Address(): String {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
            .getOrNull().orEmpty()
        return interfaces
            .asSequence()
            .flatMap { nic ->
                runCatching { nic.inetAddresses?.toList() }.getOrNull().orEmpty().asSequence()
            }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
            ?: "127.0.0.1"
    }

    private fun String.urlDecode(): String {
        return URLDecoder.decode(this, StandardCharsets.UTF_8.name())
    }

    private fun String.htmlEscape(): String {
        return replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private companion object {
        const val MAX_REQUEST_BODY_BYTES = 1_000_000L
        const val MAX_CONCURRENT_CLIENTS = 8
        const val CLIENT_READ_TIMEOUT_MS = 15_000
        const val TOKEN_QUERY_KEY = "token"
        const val TOKEN_HEADER = "x-remote-token"
    }
}
