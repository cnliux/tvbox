package com.tvbox.app.data

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tvbox.app.data.network.HttpClients
import com.tvbox.app.data.network.NetworkException
import com.tvbox.app.data.network.executeCancellable
import com.tvbox.app.util.logSafeUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

/** 局域网中发现的 DLNA/UPnP 媒体投放设备。 */
data class DlnaDevice(
    val name: String,
    val controlUrl: String,
    val location: String,
)

/**
 * 轻量 DLNA/UPnP 投屏实现：SSDP 发现 AVTransport 设备，再通过 SOAP 控制播放。
 *
 * 仅手机端使用；电视端不需要投屏入口。
 */
class DlnaCaster(context: Context) {
    private val appContext = context.applicationContext
    // 局域网 SOAP/描述请求：沿用统一客户端，但使用更短的 SOCKET 超时避免投屏卡住。
    private val client = HttpClients.default.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun discover(timeoutMs: Long = 3_500L): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val locations = LinkedHashSet<String>()
        val multicastLock = acquireMulticastLock()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1_200
                val group = InetAddress.getByName(SSDP_ADDRESS)
                repeat(2) {
                    SSDP_SEARCH_TARGETS.forEach { target ->
                        val payload = searchPayload(target).toByteArray(Charsets.US_ASCII)
                        socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        parseLocation(String(packet.data, 0, packet.length))?.let(locations::add)
                    } catch (_: SocketTimeoutException) {
                        // 继续等待下一轮响应，直到整体超时。
                    }
                }
            }
        } finally {
            runCatching { multicastLock?.release() }
        }
        locations
            .mapNotNull { location ->
                runCatching { loadDevice(location) }
                    .onFailure { error -> Log.w(TAG, "loadDevice failed: ${logSafeUrl(location)}", error) }
                    .getOrNull()
            }
            .distinctBy { it.controlUrl }
    }

    suspend fun play(device: DlnaDevice, videoUrl: String, title: String): Result<Unit> = withContext(Dispatchers.IO) {
        val metadata = buildMetadata(videoUrl, title)
        val setUri = sendSoap(
            device = device,
            action = "SetAVTransportURI",
            arguments = buildString {
                append("<InstanceID>0</InstanceID>")
                append("<CurrentURI>").append(escapeXml(videoUrl)).append("</CurrentURI>")
                append("<CurrentURIMetaData>").append(escapeXml(metadata)).append("</CurrentURIMetaData>")
            },
        )
        if (setUri.isFailure) {
            return@withContext setUri
        }
        sendSoap(device, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    }

    suspend fun pause(device: DlnaDevice): Result<Unit> = withContext(Dispatchers.IO) {
        sendSoap(device, "Pause", "<InstanceID>0</InstanceID>")
    }

    suspend fun stop(device: DlnaDevice): Result<Unit> = withContext(Dispatchers.IO) {
        sendSoap(device, "Stop", "<InstanceID>0</InstanceID>")
    }

    /** 页面销毁时使用：由调用方提供作用域，不等待结果，避免阻塞主线程。 */
    fun stopAsync(device: DlnaDevice, scope: CoroutineScope) {
        scope.launch { stop(device) }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? = runCatching {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return@runCatching null
        wifiManager.createMulticastLock("tvbox-dlna").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun parseLocation(response: String): String? {
        return response.lineSequence()
            .firstOrNull { line -> line.substringBefore(':', "").trim().equals("LOCATION", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun loadDevice(location: String): DlnaDevice? {
        val xml = try {
            client.executeCancellable(Request.Builder().url(location).build()).use { response ->
                response.body.string()
            }
        } catch (_: NetworkException.Http) {
            // 非 AVTransport 设备或不可达地址：与原来一样静默跳过。
            return null
        }
        val root = parseXml(xml) ?: return null
        val name = root.getElementsByTagName("friendlyName")
            .item(0)
            ?.textContent
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "DLNA 设备"
        val services = root.getElementsByTagName("service")
        for (index in 0 until services.length) {
            val service = services.item(index) as? Element ?: continue
            val serviceType = childText(service, "serviceType")
            if (!serviceType.endsWith(AV_TRANSPORT_SERVICE_SUFFIX)) continue
            val controlUrl = childText(service, "controlURL")
            if (controlUrl.isBlank()) continue
            val absolute = runCatching { URL(URL(location), controlUrl).toString() }.getOrNull() ?: continue
            return DlnaDevice(name = name, controlUrl = absolute, location = location)
        }
        return null
    }

    private suspend fun sendSoap(device: DlnaDevice, action: String, arguments: String): Result<Unit> {
        val envelope = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""")
            append("<s:Body>")
            append("<u:").append(action).append(" xmlns:u=\"").append(AV_TRANSPORT_SERVICE).append("\">")
            append(arguments)
            append("</u:").append(action).append(">")
            append("</s:Body></s:Envelope>")
        }
        val request = Request.Builder()
            .url(device.controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPAction", "\"$AV_TRANSPORT_SERVICE#$action\"")
            .post(envelope.toRequestBody(XML_MEDIA_TYPE))
            .build()
        return try {
            client.executeCancellable(request).use { }
            Result.success(Unit)
        } catch (error: NetworkException.Http) {
            Result.failure(IOException("投屏控制失败（${error.statusCode}）", error))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun buildMetadata(videoUrl: String, title: String): String = buildString {
        append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
        append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
        append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
        append("""<item id="0" parentID="-1" restricted="1">""")
        append("<dc:title>").append(escapeXml(title)).append("</dc:title>")
        append("<upnp:class>object.item.videoItem</upnp:class>")
        append("<res protocolInfo=\"http-get:*:video/*:*\">").append(escapeXml(videoUrl)).append("</res>")
        append("</item></DIDL-Lite>")
    }

    private fun parseXml(raw: String): Element? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Android 自带解析器不一定支持该特性，失败时保持默认配置即可。
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        factory.newDocumentBuilder()
            .parse(InputSource(StringReader(raw)))
            .documentElement
    }.getOrNull()

    private fun childText(parent: Element, tag: String): String {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index)
            if (node is Element && node.nodeName.equals(tag, ignoreCase = true)) {
                return node.textContent?.trim().orEmpty()
            }
        }
        return ""
    }

    private fun escapeXml(value: String): String = buildString {
        value.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    private companion object {
        const val TAG = "DlnaCaster"
        const val SSDP_ADDRESS = "239.255.255.250"
        const val SSDP_PORT = 1900
        const val AV_TRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
        const val AV_TRANSPORT_SERVICE_SUFFIX = "AVTransport:1"
        val XML_MEDIA_TYPE = "text/xml; charset=utf-8".toMediaType()

        fun searchPayload(searchTarget: String): String = buildString {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: 239.255.255.250:1900\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: ").append(searchTarget).append("\r\n")
            append("USER-AGENT: Android/UPnP/1.0 TVBox/1.0\r\n")
            append("\r\n")
        }

        /** 部分电视只响应 ssdp:all 或 AVTransport 搜索，因此同时发送多种 ST。 */
        val SSDP_SEARCH_TARGETS = listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "ssdp:all",
        )
    }
}
