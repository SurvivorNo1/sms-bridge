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
 * 极简 HTTP 服务。
 *
 * 免签名（不含任何短信内容）：
 *   GET /            健康页（HTML，人看）
 *   GET /health      健康检查（JSON，agent 看）
 * 须签名：
 *   GET /ping
 *   GET /sms/range?minutes=30 | from=<ms>&to=<ms>
 *   GET /sms/search?q=<kw>&minutes=30&re=0|1
 *
 * 签名：X-Timestamp=<ms>，X-Sign=hex(HMAC-SHA256(secret, "<ts>\nGET\n<path?query>"))
 * 成功响应：base64(iv[12] + AES-256-GCM(key=SHA256(secret), json))
 * 错误响应：明文 JSON {"ok":false,"error":..,"hint":..,"doc":..}，方便 agent 自行纠正
 * 只接受来自 WiFi / 热点网卡、且对端是私网地址的连接。
 */
class HttpServer(
    private val port: Int,
    private val secret: () -> String,
    private val source: SmsSource,
    /** 测试时关掉，允许 127.0.0.1 访问 */
    private val lanOnly: Boolean = true,
) {
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
        s.tcpNoDelay = true
        if (lanOnly && !fromLan(s)) {
            error(s, 403, "not_lan", "只接受来自同一 WiFi / 热点内私网地址的连接"); return
        }

        val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
        val reqLine = reader.readLine() ?: return
        val parts = reqLine.split(" ")
        if (parts.size < 2) { error(s, 400, "bad_request", "请求行不合法"); return }
        val method = parts[0]
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val l = reader.readLine() ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val path = target.substringBefore('?')
        val q = parseQuery(target.substringAfter('?', ""))
        val now = System.currentTimeMillis()

        if (method != "GET") { error(s, 405, "method_not_allowed", "只支持 GET"); return }

        // ---- 免签名 ----
        when (path) {
            "/", "/index.html" -> { respond(s, 200, "text/html; charset=utf-8", Doc.html(port)); return }
            "/health" -> {
                respond(s, 200, "application/json; charset=utf-8",
                    """{"ok":true,"service":"sms-bridge","version":"${Doc.VERSION}","now":$now,"auth":"signed endpoints: /ping /sms/range /sms/search","doc":"GET / for usage"}""")
                return
            }
        }
        if (path !in Doc.SIGNED) { error(s, 404, "not_found", "没有这个接口"); return }

        // ---- 签名校验 ----
        val tsRaw = headers["x-timestamp"]
        val ts = tsRaw?.toLongOrNull()
        val sign = headers["x-sign"]
        when {
            tsRaw == null || sign == null ->
                error(s, 401, "missing_auth", "缺少 X-Timestamp / X-Sign 请求头")
            ts == null ->
                error(s, 401, "bad_timestamp", "X-Timestamp 须为毫秒级 Unix 时间戳整数")
            abs(now - ts) > MAX_SKEW_MS ->
                error(s, 401, "timestamp_skew", "X-Timestamp 与手机时间相差 ${abs(now - ts) / 1000}s，超过 ${MAX_SKEW_MS / 1000}s；手机当前时间 $now")
            !Crypto.verify(secret(), ts, method, target, sign) ->
                error(s, 401, "bad_signature", "签名不匹配。签名串必须是 \"<ts>\\nGET\\n<path?query>\"，其中 path?query 与请求行完全一致（含 URL 编码后的参数）；密钥以 app 首页为准")
            else -> dispatch(s, path, q, now)
        }
    }

    private fun dispatch(s: Socket, path: String, q: Map<String, String>, now: Long) {
        val json = when (path) {
            "/ping" -> """{"ok":true,"now":$now}"""
            "/sms/range" -> {
                val to = q["to"]?.toLongOrNull() ?: now
                val from = q["from"]?.toLongOrNull()
                    ?: (to - (q["minutes"]?.toLongOrNull() ?: 30L) * 60_000)
                if (from > to) { error(s, 400, "bad_range", "from 必须 <= to（毫秒时间戳）"); return }
                val items = try { source.range(from, to) } catch (e: SecurityException) {
                    error(s, 500, "no_sms_permission", "手机未授予「读取短信」权限，请在 app 或系统设置里开启"); return
                }
                Json.items(items)
            }
            "/sms/search" -> {
                val kw = q["q"] ?: run { error(s, 400, "missing_q", "缺少参数 q（关键词或正则）"); return }
                val minutes = q["minutes"]?.toLongOrNull() ?: 30L
                val regex = q["re"] == "1"
                val items = try { source.search(kw, regex, now - minutes * 60_000) } catch (e: SecurityException) {
                    error(s, 500, "no_sms_permission", "手机未授予「读取短信」权限，请在 app 或系统设置里开启"); return
                } catch (e: Exception) {
                    error(s, 400, "bad_regex", "正则不合法：${e.message}"); return
                }
                Json.items(items)
            }
            else -> { error(s, 404, "not_found", "没有这个接口"); return }
        }
        respond(s, 200, "text/plain; charset=utf-8", Crypto.encrypt(secret(), json))
    }

    /** 对端是私网地址，且本端地址挂在 WiFi(wlan) 或热点(ap / swlan) 网卡上 */
    private fun fromLan(s: Socket): Boolean {
        val remote = s.inetAddress ?: return false
        if (remote !is Inet4Address || !remote.isSiteLocalAddress) return false
        val local = s.localAddress ?: return false
        val nif = runCatching { NetworkInterface.getByInetAddress(local) }.getOrNull() ?: return false
        return isLanIface(nif.name)
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

    private fun error(s: Socket, code: Int, err: String, hint: String) {
        respond(s, code, "application/json; charset=utf-8",
            """{"ok":false,"error":${Json.str(err)},"hint":${Json.str(hint)},"doc":"GET / 查看接口用法与签名算法"}""")
    }

    private fun respond(s: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray()
        val reason = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
        }
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out = s.getOutputStream()
        out.write(head.toByteArray())
        out.write(bytes)
        out.flush()
    }

    companion object {
        const val MAX_SKEW_MS = 5 * 60_000L

        fun isLanIface(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan")
        }

        /** 给 UI 显示：WiFi/热点网卡上的 IPv4 */
        fun lanIps(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && isLanIface(it.name) }
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
        list.joinToString(",", "{\"ok\":true,\"count\":${list.size},\"items\":[", "]}") {
            "{\"ts\":${it.ts},\"from\":${str(it.from)},\"body\":${str(it.body)}}"
        }
}

object Doc {
    const val VERSION = "0.2.0"
    val SIGNED = setOf("/ping", "/sms/range", "/sms/search")

    fun html(port: Int): String = """<!doctype html><html lang="zh"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>SMS Bridge</title>
<style>body{font:15px/1.6 system-ui,sans-serif;max-width:720px;margin:32px auto;padding:0 20px;color:#1b1f24}
h1{font-size:22px}code,pre{font-family:ui-monospace,Consolas,monospace;background:#f1f3f7;border-radius:6px}
code{padding:1px 5px}pre{padding:12px;overflow:auto}table{border-collapse:collapse;width:100%}
td,th{border-bottom:1px solid #e5e7eb;padding:6px 8px;text-align:left;vertical-align:top}
.ok{display:inline-block;background:#e6f4ea;color:#2e7d32;border-radius:999px;padding:2px 10px;font-weight:600}</style></head><body>
<h1>SMS Bridge <span class="ok">● 运行中</span> <small>v$VERSION</small></h1>
<p>本机短信的局域网查询服务。这一页和 <code>/health</code> 不需要签名，也不含任何短信内容；其余接口必须签名。</p>
<h2>接口</h2><table>
<tr><th>GET</th><th>参数</th><th>说明</th></tr>
<tr><td><code>/health</code></td><td>—</td><td>健康检查（JSON），免签名</td></tr>
<tr><td><code>/ping</code></td><td>—</td><td>验证签名是否正确，返回手机当前时间</td></tr>
<tr><td><code>/sms/range</code></td><td><code>minutes=30</code> 或 <code>from=&lt;ms&gt;&amp;to=&lt;ms&gt;</code></td><td>时间范围内的短信，新的在前，最多 200 条</td></tr>
<tr><td><code>/sms/search</code></td><td><code>q=关键词</code>，<code>minutes=30</code>，<code>re=1</code> 按正则</td><td>正文或发件人匹配的短信</td></tr>
</table>
<h2>签名</h2>
<pre>X-Timestamp: &lt;毫秒 Unix 时间戳&gt;         # 与手机相差不能超过 5 分钟
X-Sign: hex( HMAC-SHA256( key=SECRET, msg="&lt;ts&gt;\nGET\n&lt;path?query&gt;" ) )
# path?query 必须与请求行里的完全一致（含 URL 编码后的参数）</pre>
<h2>响应</h2>
<pre>成功：base64( iv[12] + AES-256-GCM( key=SHA-256(SECRET), json ) )，json 形如
      {"ok":true,"count":1,"items":[{"ts":1700000000000,"from":"10690","body":"..."}]}
失败：明文 JSON {"ok":false,"error":"bad_signature","hint":"...","doc":"..."}</pre>
<h2>Python 示例</h2>
<pre>import time, hmac, hashlib, base64, json, urllib.request
from Crypto.Cipher import AES   # pip install pycryptodome
HOST, SECRET = "手机IP:$port", "app 首页的密钥"
target = "/sms/range?minutes=30"
ts = int(time.time()*1000)
sign = hmac.new(SECRET.encode(), f"{ts}\nGET\n{target}".encode(), hashlib.sha256).hexdigest()
req = urllib.request.Request(f"http://{HOST}{target}", headers={"X-Timestamp": str(ts), "X-Sign": sign})
raw = base64.b64decode(urllib.request.urlopen(req).read())
key = hashlib.sha256(SECRET.encode()).digest()
print(json.loads(AES.new(key, AES.MODE_GCM, nonce=raw[:12]).decrypt_and_verify(raw[12:-16], raw[-16:])))</pre>
<p style="color:#6b7280;font-size:13px">只接受同一 WiFi / 热点内私网地址的连接；不联网上传、不发短信、不读通讯录。源码：github.com/SurvivorNo1/sms-bridge</p>
</body></html>"""
}
