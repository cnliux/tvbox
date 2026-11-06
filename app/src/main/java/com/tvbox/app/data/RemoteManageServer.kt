package com.tvbox.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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
 * 局域网远程管理服务：无需 token，手机与电视在同一网络时直接访问即可。
 * 支持视频源管理（导入/选择/屏蔽/删除/测速）、直播源、平台站点与屏蔽视频分类。
 */
class RemoteManageServer(
    private val scope: CoroutineScope,
    private val snapshot: () -> RemoteManageSnapshot,
    private val onSubmit: suspend (RemoteManageRequest) -> String?,
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val activeClients = AtomicInteger(0)

    suspend fun start(): RemoteManageSession = withContext(Dispatchers.IO) {
        stop()
        val socket = createServerSocket()
        serverSocket = socket
        val host = localIpv4Address()
        serverJob = scope.launch(Dispatchers.IO) {
            acceptLoop(socket)
        }
        RemoteManageSession(
            url = "http://$host:${socket.localPort}/",
            host = host,
            port = socket.localPort,
        )
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (scope.isActive && !socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            if (activeClients.incrementAndGet() > MAX_CONCURRENT_CLIENTS) {
                activeClients.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            scope.launch(Dispatchers.IO) {
                try {
                    runCatching { handleClient(client) }
                } finally {
                    activeClients.decrementAndGet()
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
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

            when (method) {
                "GET" -> if (target.startsWith("/blocked-categories.json")) {
                    writeJsonResponse(socket, blockedCategoriesJson())
                } else {
                    writeHtmlResponse(socket, page(queryValue(target, "message")))
                }
                "POST" -> {
                    val declaredLength = headers["content-length"]?.toLongOrNull() ?: 0L
                    if (declaredLength > MAX_REQUEST_BODY_BYTES) {
                        writeHtmlResponse(socket, "请求体过大，已拒绝。", status = "413 Payload Too Large")
                        return
                    }
                    val body = readBody(reader, declaredLength.toInt())
                    val form = parseForm(body)
                    val request = RemoteManageRequest(
                        action = form["action"].orEmpty(),
                        id = form["id"].orEmpty(),
                        name = form["name"].orEmpty(),
                        url = form["url"].orEmpty(),
                        value = form["value"].orEmpty(),
                        content = form["content"].orEmpty(),
                    )
                    val message = onSubmit(request) ?: "操作成功"
                    val location = "/?message=" +
                        URLEncoder.encode(message, StandardCharsets.UTF_8.name())
                    writeRedirect(socket, location)
                }
                else -> writeHtmlResponse(socket, "不支持的请求。", status = "405 Method Not Allowed")
            }
        }
    }

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
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)).use { writer ->
            writer.write("HTTP/1.1 $status\r\n")
            writer.write("Content-Type: text/html; charset=utf-8\r\n")
            writer.write("Content-Length: ${bytes.size}\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.flush()
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    private fun writeJsonResponse(socket: Socket, json: String) {
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)).use { writer ->
            writer.write("HTTP/1.1 200 OK\r\n")
            writer.write("Content-Type: application/json; charset=utf-8\r\n")
            writer.write("Content-Length: ${bytes.size}\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.flush()
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    private fun blockedCategoriesJson(): String {
        val categories = snapshot().blockedVideoCategories
        val items = categories.joinToString(", ") { category ->
            "\"" + category.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        return "{\n  \"version\": 1,\n  \"updatedAtMs\": ${System.currentTimeMillis()},\n" +
            "  \"categories\": [$items]\n}\n"
    }

    private fun writeRedirect(socket: Socket, location: String) {
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)).use { writer ->
            writer.write("HTTP/1.1 303 See Other\r\n")
            writer.write("Location: $location\r\n")
            writer.write("Content-Length: 0\r\n")
            writer.write("Connection: close\r\n")
            writer.write("\r\n")
            writer.flush()
        }
    }

    private fun page(message: String?): String {
        val data = snapshot()
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>TVBox 远程管理</title>
              <style>
                body { margin: 0; font-family: sans-serif; background: #10141f; color: #f5f7fb; }
                main { max-width: 720px; margin: 0 auto; padding: 22px 16px 48px; }
                h1 { font-size: 24px; margin: 0 0 6px; }
                h2 { font-size: 18px; margin: 0 0 10px; }
                p { color: #b8c0cf; line-height: 1.6; }
                .panel { background: #1a2130; border: 1px solid #2f3a4f; border-radius: 14px; padding: 16px; margin-top: 16px; }
                .row { display: flex; align-items: center; gap: 8px; padding: 8px 0; border-top: 1px solid #2b3446; flex-wrap: wrap; }
                .row:first-of-type { border-top: 0; }
                .grow { flex: 1; min-width: 0; }
                .name { font-weight: 700; word-break: break-all; }
                .url { color: #8f9bb3; font-size: 13px; word-break: break-all; }
                .tag { color: #6ad6a8; font-size: 12px; }
                .tag.off { color: #ffb4ab; }
                input, textarea { box-sizing: border-box; border: 1px solid #657084; border-radius: 8px; padding: 10px; font-size: 15px; background: #171d2a; color: #fff; font-family: inherit; }
                input.text, textarea.text { width: 100%; }
                textarea { margin-top: 6px; resize: vertical; }
                button { border: 0; border-radius: 8px; padding: 10px 14px; font-size: 14px; font-weight: 700; color: #051b17; background: #28d0b0; }
                button.ghost { background: #2a3446; color: #f5f7fb; }
                .form-row { display: flex; gap: 8px; margin-top: 12px; flex-wrap: wrap; }
                .form-row input { flex: 1; min-width: 140px; }
                .message { background: #1c3a33; border: 1px solid #2f6b58; color: #8ff0cf; border-radius: 10px; padding: 10px 12px; margin-top: 14px; }
                .empty { color: #8f9bb3; font-size: 14px; }
                .sub { color: #9fb0c9; font-size: 13px; font-weight: 700; margin-top: 18px; margin-bottom: 8px; letter-spacing: .3px; }
                .chips { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
                .chip { background: #2a3446; color: #f5f7fb; border-radius: 999px; padding: 9px 15px; font-weight: 600; }
                .chip.blocked { background: #5a2a2a; color: #ffb4ab; text-decoration: line-through; }
                a { color: #6ad6a8; }
                footer { color: #6f7b93; font-size: 12px; margin-top: 22px; text-align: center; }
              </style>
            </head>
            <body>
              <main>
                <h1>TVBox 远程管理</h1>
                <p>同一局域网内直接访问，无需 token。修改会立即同步到电视并保存。</p>
                ${message?.takeIf { it.isNotBlank() }?.let { "<div class=\"message\">${it.htmlEscape()}</div>" }.orEmpty()}

                <section class="panel">
                  <h2>视频源管理（共 ${data.videoSources.size} 个）</h2>
                  <p class="empty">可设首页源、屏蔽/取消屏蔽、删除、测速排序，并支持订阅地址导入。</p>
                  ${if (data.videoSources.isEmpty()) {
                      "<p class=\"empty\">暂无视频源，请在下方添加或导入。</p>"
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
                  ${addForm("video-add", "接口名称", "MacCms 地址，例如 https://example.com/api.php/provide/vod", "添加接口")}
                  <p class="url" style="margin-top:16px">通过订阅地址导入（支持 JSON 与 TVBox 格式）</p>
                  <form method="post">
                    <input type="hidden" name="action" value="source-import" />
                    <input class="text" name="url" placeholder="https://example.com/config.json" autocomplete="off" />
                    <textarea class="text" name="content" rows="4" placeholder="也可以直接粘贴配置内容"></textarea>
                    <div class="form-row"><button>导入订阅</button></div>
                  </form>
                  ${categoryBlock(data)}
                </section>

                <section class="panel">
                  <h2>直播源</h2>
                  ${if (data.customLiveUrls.isEmpty()) {
                      "<p class=\"empty\">暂无自定义直播源（M3U/TXT）。</p>"
                  } else {
                      data.customLiveUrls.joinToString("") { url ->
                          itemRow(
                              title = "自定义直播源",
                              subtitle = url,
                              tag = "",
                              actions = "<form method=\"post\"><input type=\"hidden\" name=\"action\" value=\"live-delete\" />" +
                                  "<input type=\"hidden\" name=\"url\" value=\"${url.htmlEscape()}\" />" +
                                  "<button class=\"ghost\">删除</button></form>",
                          )
                      }
                  }}
                  ${addForm("live-add", "", "直播源地址（M3U/TXT），每行一个", "添加直播源")}
                  ${liveDefaultBlock(data)}
                  ${epgBlock(data)}
                </section>

                <section class="panel">
                  <h2>平台直播站点</h2>
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

                <footer>TVBox 局域网远程管理 · 无需 token</footer>
              </main>
            </body>
            </html>
        """.trimIndent()
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
        return query.split('&').firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=', "")
            ?.urlDecode()
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
        }.getOrDefault(emptyMap())
    }

    private fun createServerSocket(): ServerSocket {
        return runCatching { ServerSocket(PREFERRED_PORT) }
            .getOrElse { ServerSocket(0) }
    }

    private fun localIpv4Address(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
        return interfaces
            .asSequence()
            .flatMap { it.inetAddresses.toList().asSequence() }
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
        const val PREFERRED_PORT = 9978
        const val MAX_REQUEST_BODY_BYTES = 1_000_000L
        const val MAX_CONCURRENT_CLIENTS = 8
        const val CLIENT_READ_TIMEOUT_MS = 15_000
    }
}
