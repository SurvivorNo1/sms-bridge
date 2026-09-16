package io.github.survivorno1.smsbridge

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.math.abs

/**
 * 极简 HTTP 服务，只有三个 GET：
 *   /ping
 *   /sms/range?from=<ms>&to=<ms>          时间范围（也可用 minutes=N 代替 from）
 *   /sms/search?q=<kw>&minutes=N&re=0|1   关键词 / 正则，默认最近 30 分钟
 * 每个请求都要带 X-Timestamp / X-Sign；响应体整体 AES-GCM 加密后 base64。
 * 只接受来自 WiFi / 热点网卡、且对端是私网地址的连接。
 */
class HttpServer(private val port: Int, private val secret: () -> String) {
    private var server: ServerSocket? = null
    @Volatile private var stopped = false

    fun start() {
        stopped = false
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("0.0.0.0", port))
        server = ss
        Thread({ loop(ss) }, "sms-bridge-http").apply { isDaemon = true }.start()
    }

    fun stop() {
        stopped = true
        runCatching { server?.close() }
        server = null
    }

    private fun loop(ss: ServerSocket) {
        while (!stopped) {
            val s = try { ss.accept() } catch (_: Exception) { break }
            Thread {
                try { handle(s) } catch (_: Exception) {} finally { runCatching { s.close() } }
            }.start()
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 5000
        if (!fromLan(s)) { respond(s, 403, ""); return }

        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        val reqLine = reader.readLine() ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) { respond(s, 400, ""); return }
        val method = parts[0]
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val l = reader.readLine() ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }

        val ts = headers["x-timestamp"]?.toLongOrNull()
        val sign = headers["x-sign"] ?: ""
        if (method != "GET" || ts == null ||
            abs(System.currentTimeMillis() - ts) > 5 * 60_000 ||
            !Crypto.verify(secret(), ts, method, target, sign)
        ) { respond(s, 401, ""); return }

        val path = target.substringBefore('?')
        val q = parseQuery(target.substringAfter('?', ""))
        val now = System.currentTimeMillis()
        val json = when (path) {
            "/ping" -> """{"ok":true,"count":${Store.size()},"now":$now}"""
            "/sms/range" -> {
                val to = q["to"]?.toLongOrNull() ?: now
                val from = q["from"]?.toLongOrNull()
                    ?: (to - (q["minutes"]?.toLongOrNull() ?: 30L) * 60_000)
                Json.items(Store.range(from, to))
            }
            "/sms/search" -> {
                val kw = q["q"] ?: ""
                val minutes = q["minutes"]?.toLongOrNull() ?: 30L
                val regex = q["re"] == "1"
                val list = try { Store.search(kw, regex, now - minutes * 60_000) }
                catch (_: Exception) { respond(s, 400, ""); return }
                Json.items(list)
            }
            else -> { respond(s, 404, ""); return }
        }
        respond(s, 200, Crypto.encrypt(secret(), json))
    }

    /** 对端是私网地址，且本端地址挂在 WiFi(wlan) 或热点(ap / swlan) 网卡上 */
    private fun fromLan(s: Socket): Boolean {
        val remote = s.inetAddress ?: return false
        if (remote !is Inet4Address || !remote.isSiteLocalAddress) return false
        val local = s.localAddress ?: return false
        val nif = runCatching { NetworkInterface.getByInetAddress(local) }.getOrNull() ?: return false
        val n = nif.name.lowercase()
        return n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")
    }

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isEmpty()) return emptyMap()
        return qs.split('&').mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else {
                val k = URLDecoder.decode(kv.substring(0, i), "UTF-8")
                val v = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                k to v
            }
        }.toMap()
    }

    private fun respond(s: Socket, code: Int, body: String) {
        val bytes = body.toByteArray()
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        /** 给 UI 显示：WiFi/热点网卡上的 IPv4 */
        fun lanIps(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .filter { val n = it.name.lowercase(); n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        }.getOrDefault(emptyList())
    }
}

object Json {
    fun str(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
        }
        return sb.append('"').toString()
    }

    fun items(list: List<Msg>): String =
        list.joinToString(",", "{\"ok\":true,\"items\":[", "]}") {
            "{\"ts\":${it.ts},\"from\":${str(it.from)},\"body\":${str(it.body)}}"
        }
}
