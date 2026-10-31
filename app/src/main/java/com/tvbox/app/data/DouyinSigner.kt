package com.tvbox.app.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 在 WebView 中运行 `assets/douyin_sign.js`，为抖音网页接口生成 a_bogus 签名。
 *
 * 内置 Python 解析器（tvbox_live_bridge）通过 Chaquopy 以静态方法方式调用
 * [sign]，返回值与 Node.js 签名助手保持一致：JSON 字符串
 * `{"a_bogus": "..."}` 或 `{"error": "..."}`。
 */
object DouyinSigner {
    private const val TAG = "DouyinSigner"
    private const val ASSET_NAME = "douyin_sign.js"
    private const val NODE_WRAPPER_MARKER = "const readline = require('readline')"
    private const val PAGE_TIMEOUT_SECONDS = 10L
    private const val SCRIPT_TIMEOUT_SECONDS = 15L
    private const val SIGN_TIMEOUT_SECONDS = 20L
    private const val READY_TIMEOUT_MS = 30_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var appContext: Context? = null
    private var webView: WebView? = null
    private var scriptLoaded = false

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun sign(params: String, userAgent: String): String {
        val script = "try{JSON.stringify({a_bogus:getABogus(" +
            JSONObject.quote(params) + "," + JSONObject.quote(userAgent) + ")})}" +
            "catch(e){JSON.stringify({error:String(e&&e.message||e)})}"
        return try {
            synchronized(lock) {
                ensureWebViewReady()
                loadDouyinScript()
                unwrap(postRaw(script, SIGN_TIMEOUT_SECONDS))
            }
        } catch (error: Throwable) {
            JSONObject().put("error", error.message ?: "抖音签名失败").toString()
        }
    }

    /**
     * 通用 JS 求值：在 WebView 中执行 [script] 后返回 [expression] 的
     * JSON.stringify 结果。供内置斗鱼签名脚本（无 Node.js 环境）使用。
     * 失败时返回以 `__JS_ERROR__` 开头的错误描述。
     */
    @JvmStatic
    fun runJs(script: String, expression: String): String {
        return try {
            synchronized(lock) {
                ensureWebViewReady()
                // 两段执行：先运行脚本定义（语法错误在此暴露并记录日志），
                // 再单独求值表达式，便于定位坏脚本的具体来源。
                val probe = runCatching { postRaw("$script;'__SCRIPT_OK__'", SIGN_TIMEOUT_SECONDS) }
                val probeValue = probe.getOrNull()?.let { unwrapRaw(it) }
                if (probeValue != "__SCRIPT_OK__") {
                    Log.e(TAG, "runJs 脚本解析失败 len=${script.length} expr=$expression")
                    Log.e(TAG, "head=${script.take(300)}")
                    Log.e(TAG, "tail=${script.takeLast(300)}")
                    Log.e(TAG, "probe=${probe.getOrNull()?.take(200)} err=${probe.exceptionOrNull()?.message}")
                    val signIndex = script.indexOf("function sign(")
                    if (signIndex >= 0) {
                        Log.e(TAG, "signctx=${script.substring(signIndex, minOf(script.length, signIndex + 200))}")
                    }
                    // new Function 仅编译不执行，可捕获精确的 SyntaxError 消息。
                    val compile = runCatching {
                        postRaw(
                            "try{new Function(${JSONObject.quote(script)});'COMPILE_OK'}" +
                                "catch(e){'COMPILE_ERR:'+String(e&&e.message||e)}",
                            SIGN_TIMEOUT_SECONDS,
                        )
                    }
                    Log.e(TAG, "compile=${compile.getOrNull()} err=${compile.exceptionOrNull()?.message}")
                    return "__JS_ERROR__斗鱼签名脚本解析失败"
                }
                // evaluateJavascript 的回调值会在 JSON 字面量外再序列化一层：
                // 表达式返回字符串时，unwrapRaw 解一层后得到的仍是 JSON 字面量
                // 文本（形如 "..."），必须再解一层才能拿到原始内容，否则拼接
                // 出的后续脚本会带着转义引号而语法损坏。
                val resultRaw = postRaw(
                    "try{JSON.stringify(($expression))}catch(e){\"__JS_ERROR__\"+String(e&&e.message||e)}",
                    SIGN_TIMEOUT_SECONDS,
                )
                val resultValue = unwrapRaw(resultRaw)
                if (resultValue.startsWith("\"")) {
                    return JSONObject("{\"v\":$resultValue}").getString("v")
                }
                resultValue
            }
        } catch (error: Throwable) {
            "__JS_ERROR__${error.message ?: "JS 执行失败"}"
        }
    }

    private fun ensureWebViewReady() {
        if (webView != null) return
        val context = appContext ?: throw IllegalStateException("DouyinSigner 未初始化")
        webView = createWebViewBlocking(context)
        awaitDocumentReady()
    }

    /** 部分 WebView 实现在文档就绪前会立即回调 null，用探针脚本轮询确认。 */
    private fun awaitDocumentReady() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val probe = runCatching { postRaw("1+1", 3) }.getOrNull()
            if (probe == "2") return
            Thread.sleep(300)
        }
        throw IllegalStateException("WebView 文档未就绪")
    }

    private fun loadDouyinScript() {
        if (scriptLoaded) return
        postRaw(loadSignScript(), SCRIPT_TIMEOUT_SECONDS)
        scriptLoaded = true
    }

    private fun createWebViewBlocking(context: Context): WebView {
        val latch = CountDownLatch(1)
        val created = AtomicReference<WebView?>()
        val failure = AtomicReference<Throwable?>()
        mainHandler.post {
            try {
                val view = WebView(context)
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                created.set(view)
                // evaluateJavascript 会把脚本排队到文档就绪后执行，
                // 无需等待 onPageFinished（模拟器上可能长时间不回调）。
                view.loadUrl("about:blank")
            } catch (error: Throwable) {
                failure.set(error)
            }
            latch.countDown()
        }
        if (!latch.await(PAGE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IllegalStateException("WebView 初始化超时")
        }
        failure.get()?.let { throw it }
        return created.get() ?: throw IllegalStateException("WebView 创建失败")
    }

    private fun postRaw(script: String, timeoutSeconds: Long): String? {
        val latch = CountDownLatch(1)
        val value = AtomicReference<String?>()
        val failure = AtomicReference<Throwable?>()
        mainHandler.post {
            val view = webView
            if (view == null) {
                failure.set(IllegalStateException("WebView 不可用"))
                latch.countDown()
                return@post
            }
            try {
                view.evaluateJavascript(script) { result ->
                    value.set(result)
                    latch.countDown()
                }
            } catch (error: Throwable) {
                failure.set(error)
                latch.countDown()
            }
        }
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw IllegalStateException("抖音签名超时")
        }
        failure.get()?.let { throw it }
        return value.get()
    }

    /** evaluateJavascript 返回 JS 值的 JSON 字面量；字符串结果需要再解一层。 */
    private fun unwrap(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") throw IllegalStateException("抖音签名无返回")
        val inner = if (raw.startsWith("\"")) JSONObject("{\"v\":$raw}").getString("v") else raw
        return JSONObject(inner).toString()
    }

    private fun unwrapRaw(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return "__JS_ERROR__JS 执行无返回"
        return if (raw.startsWith("\"")) JSONObject("{\"v\":$raw}").getString("v") else raw
    }

    private fun loadSignScript(): String {
        val context = appContext ?: throw IllegalStateException("DouyinSigner 未初始化")
        val source = context.assets.open(ASSET_NAME).use { stream ->
            stream.readBytes().toString(Charsets.UTF_8)
        }.removePrefix("\uFEFF")
        val markerIndex = source.indexOf(NODE_WRAPPER_MARKER)
        return if (markerIndex >= 0) source.substring(0, markerIndex) else source
    }
}
