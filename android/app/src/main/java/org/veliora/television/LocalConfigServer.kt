package org.veliora.television

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * 只在本地网络提供临时手机片源配置页。
 * 二维码里是随机访问密钥；跨域请求不放行，后台不调用任何云服务器。
 */
internal class LocalConfigServer(
    private val context: Context,
    private val readSources: () -> String?,
    private val writeSources: (JSONArray) -> Boolean
) {
    companion object { private const val PORT = 9978 }
    private val key = ByteArray(24).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private val executor = Executors.newCachedThreadPool()
    private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        try {
            val socket = ServerSocket(PORT, 12, InetAddress.getByName("0.0.0.0"))
            server = socket
            running = true
            executor.execute {
                while (running) {
                    try {
                        val incoming = socket.accept()
                        executor.execute { incoming.use { handle(it) } }
                    } catch (_: Exception) {
                        if (!running) break
                    }
                }
            }
        } catch (_: Exception) { running = false }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        executor.shutdownNow()
    }

    fun phoneInfo(): String {
        val ip = findLocalAddress()
        if (!running || ip == null) {
            return JSONObject().put("error", "电视没有可访问的局域网地址，请检查网络连接").toString()
        }
        val url = "http://$ip:$PORT/pair?key=$key"
        return JSONObject().put("url", url).put("qr", createQr(url)).toString()
    }

    private fun findLocalAddress(): String? {
        return try {
            val all = Collections.list(NetworkInterface.getNetworkInterfaces())
                .filter { it.isUp && !it.isLoopback }
            val prioritized = all.sortedBy {
                when {
                    it.name.startsWith("wlan") || it.name.startsWith("wifi") -> 0
                    it.name.startsWith("eth") -> 1
                    else -> 2
                }
            }
            prioritized.asSequence().flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) { null }
    }

    private fun createQr(url: String): String {
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 2)
            val bits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, 400, 400, hints)
            val bmp = Bitmap.createBitmap(bits.width, bits.height, Bitmap.Config.ARGB_8888)
            for (y in 0 until bits.height) for (x in 0 until bits.width) {
                bmp.setPixel(x, y, if (bits.get(x, y)) Color.BLACK else Color.WHITE)
            }
            val output = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, output)
            bmp.recycle()
            "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 7000
            val input = socket.getInputStream()
            val header = readHeader(input) ?: return
            val lines = header.split("\r\n")
            val request = lines[0].split(' ')
            if (request.size < 2) return
            val method = request[0]
            val rawPath = request[1]
            val path = rawPath.substringBefore('?')
            val headers = mutableMapOf<String, String>()
            for (line in lines.drop(1)) {
                val sep = line.indexOf(':')
                if (sep > 0) headers[line.substring(0, sep).trim().lowercase()] =
                    line.substring(sep + 1).trim()
            }

            when {
                method == "GET" && path == "/pair" -> {
                    val requestedKey = rawPath.substringAfter("key=", "").substringBefore('&')
                    if (requestedKey != key) { respond(socket, 403, "无效的配对链接"); return }
                    val page = context.assets.open("mobile-config.html").bufferedReader().use { it.readText() }
                        .replace("__PAIRING_TOKEN__", key)
                    respond(socket, 200, page, "text/html; charset=utf-8")
                }
                path == "/api/sources" -> {
                    if (headers["x-veliora-key"] != key) {
                        respond(socket, 403, "{\"error\":\"配对失效，请重新扫码\"}", "application/json; charset=utf-8")
                        return
                    }
                    if (method == "GET") {
                        val data = readSources()
                        if (data == null) respond(socket, 503, "{\"error\":\"电视正在加载，请稍后重试\"}", "application/json; charset=utf-8")
                        else respond(socket, 200, data, "application/json; charset=utf-8")
                    } else if (method == "POST") {
                        val len = headers["content-length"]?.toIntOrNull() ?: -1
                        if (len !in 1..16000) {
                            respond(socket, 400, "{\"error\":\"请求数据大小无效\"}", "application/json; charset=utf-8")
                            return
                        }
                        val body = readBytes(input, len) ?: return
                        val sources = try { JSONObject(String(body, StandardCharsets.UTF_8)).getJSONArray("sources") }
                            catch (_: Exception) {
                                respond(socket, 400, "{\"error\":\"内容格式错误\"}", "application/json; charset=utf-8")
                                return
                            }
                        if (sources.length() > 5) {
                            respond(socket, 400, "{\"error\":\"最多保存5个片源\"}", "application/json; charset=utf-8")
                            return
                        }
                        val cleaned = JSONArray()
                        for (i in 0 until sources.length()) {
                            val item = sources.optJSONObject(i) ?: run {
                                respond(socket, 400, "{\"error\":\"片源格式无效\"}", "application/json; charset=utf-8")
                                return
                            }
                            val name = item.optString("name").trim()
                            val url = item.optString("url").trim().trimEnd('/')
                            val uri = try { java.net.URI(url) } catch (_: Exception) { null }
                            if (name.isBlank() || name.length > 45 ||
                                url.length > 1500 || uri?.scheme !in listOf("http", "https") ||
                                uri?.host.isNullOrBlank()) {
                                respond(socket, 400, "{\"error\":\"请检查名称与接口地址\"}", "application/json; charset=utf-8")
                                return
                            }
                            cleaned.put(JSONObject()
                                .put("name", name).put("url", url)
                                .put("isAdult", item.optBoolean("isAdult", false))
                                .put("enabled", item.optBoolean("enabled", true)))
                        }
                        if (writeSources(cleaned)) {
                            respond(socket, 200, "{\"ok\":true}", "application/json; charset=utf-8")
                        } else {
                            respond(socket, 503, "{\"error\":\"电视同步失败，请保持Veliora在前台重试\"}", "application/json; charset=utf-8")
                        }
                    } else respond(socket, 405, "不支持的请求")
                }
                else -> respond(socket, 404, "未找到页面")
            }
        } catch (_: Exception) { /* 客户端断开或超时 */ }
    }

    private fun readHeader(input: InputStream): String? {
        val output = ByteArrayOutputStream()
        var last4 = 0
        while (output.size() < 8192) {
            val ch = input.read()
            if (ch < 0) return null
            output.write(ch)
            last4 = (last4 shl 8) or ch
            if (last4 == 0x0d0a0d0a) return output.toString("UTF-8").trimEnd('\r','\n')
        }
        return null
    }

    private fun readBytes(input: InputStream, len: Int): ByteArray? {
        val data = ByteArray(len)
        var at = 0
        while (at < len) {
            val n = input.read(data, at, len - at)
            if (n <= 0) return null
            at += n
        }
        return data
    }

    private fun respond(socket: Socket, status: Int, value: String, type: String = "text/plain; charset=utf-8") {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; else -> "Service Unavailable"
        }
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            "X-Frame-Options: DENY\r\n" +
            "Access-Control-Allow-Origin: null\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().write(head.toByteArray(StandardCharsets.UTF_8))
        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }
}
