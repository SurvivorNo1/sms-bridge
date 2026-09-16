package io.github.survivorno1.smsbridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder

/** 起真实的 HttpServer（关掉局域网限制），用假短信源，走完整的 HTTP + 签名 + 解密链路。 */
class HttpServerTest {
    private val secret = "test-secret"
    private var port = 0
    private lateinit var server: HttpServer
    private val now = System.currentTimeMillis()

    private val fake = object : SmsSource {
        val all = listOf(
            Msg(now - 60_000, "10690123", "【某公司】您的验证码为 482913，5分钟内有效"),
            Msg(now - 10 * 60_000, "95588", "您尾号1234的账户收到转账"),
            Msg(now - 3 * 3600_000, "10086", "【中国移动】流量提醒"),
        )
        override fun range(from: Long, to: Long, limit: Int) = all.filter { it.ts in from..to }.take(limit)
        override fun search(q: String, regex: Boolean, since: Long, limit: Int): List<Msg> {
            val re = if (regex) Regex(q, RegexOption.IGNORE_CASE) else null
            return all.filter { it.ts >= since }.filter {
                if (re != null) re.containsMatchIn(it.body) else it.body.contains(q, true) || it.from.contains(q, true)
            }.take(limit)
        }
    }

    @Before
    fun up() {
        port = ServerSocket(0).use { it.localPort }
        server = HttpServer(port, { secret }, fake, lanOnly = false)
        server.start()
        Thread.sleep(100)
    }

    @After
    fun down() = server.stop()

    private data class Resp(val code: Int, val body: String)

    private fun get(target: String, sign: Boolean = true, ts: Long = System.currentTimeMillis(), key: String = secret): Resp {
        val c = URL("http://127.0.0.1:$port$target").openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        if (sign) {
            c.setRequestProperty("X-Timestamp", ts.toString())
            c.setRequestProperty("X-Sign", Crypto.sign(key, ts, "GET", target))
        }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        return Resp(code, stream.readBytes().toString(Charsets.UTF_8))
    }

    private fun q(s: String) = URLEncoder.encode(s, "UTF-8")

    @Test
    fun healthNeedsNoAuth() {
        val r = get("/health", sign = false)
        assertEquals(200, r.code)
        assertTrue(r.body.contains("\"ok\":true"))
        assertTrue(r.body.contains("sms-bridge"))
        val home = get("/", sign = false)
        assertEquals(200, home.code)
        assertTrue(home.body.contains("<title>SMS Bridge</title>"))
        assertTrue(home.body.contains("HMAC-SHA256"))
    }

    @Test
    fun rangeDecrypts() {
        val r = get("/sms/range?minutes=30")
        assertEquals(200, r.code)
        val json = Crypto.decrypt(secret, r.body)
        assertTrue(json, json.contains("\"count\":2"))
        assertTrue(json, json.contains("482913"))
        assertTrue(json, !json.contains("流量提醒"))
    }

    @Test
    fun searchKeywordAndRegex() {
        val kw = Crypto.decrypt(secret, get("/sms/search?q=${q("验证码")}&minutes=30").body)
        assertTrue(kw, kw.contains("\"count\":1") && kw.contains("482913"))
        val re = Crypto.decrypt(secret, get("/sms/search?q=${q("验证码为\\s*(\\d{6})")}&minutes=30&re=1").body)
        assertTrue(re, re.contains("\"count\":1"))
        val wide = Crypto.decrypt(secret, get("/sms/search?q=${q("提醒")}&minutes=600").body)
        assertTrue(wide, wide.contains("流量提醒"))
    }

    @Test
    fun errorsAreAgentReadable() {
        val noAuth = get("/sms/range", sign = false)
        assertEquals(401, noAuth.code)
        assertTrue(noAuth.body, noAuth.body.contains("\"error\":\"missing_auth\"") && noAuth.body.contains("hint"))

        val badKey = get("/ping", key = "nope")
        assertEquals(401, badKey.code)
        assertTrue(badKey.body, badKey.body.contains("bad_signature"))

        val stale = get("/ping", ts = System.currentTimeMillis() - 10 * 60_000)
        assertEquals(401, stale.code)
        assertTrue(stale.body, stale.body.contains("timestamp_skew"))

        val noQ = get("/sms/search")
        assertEquals(400, noQ.code)
        assertTrue(noQ.body, noQ.body.contains("missing_q"))

        val badRe = get("/sms/search?q=${q("(")}&re=1")
        assertEquals(400, badRe.code)
        assertTrue(badRe.body, badRe.body.contains("bad_regex"))

        val nf = get("/nope")
        assertEquals(404, nf.code)
        assertTrue(nf.body, nf.body.contains("not_found"))
    }

    @Test
    fun signatureCoversQuery() {
        // 用另一个 query 签名再改 URL → 必须拒绝
        val ts = System.currentTimeMillis()
        val c = URL("http://127.0.0.1:$port/sms/range?minutes=600").openConnection() as HttpURLConnection
        c.setRequestProperty("X-Timestamp", ts.toString())
        c.setRequestProperty("X-Sign", Crypto.sign(secret, ts, "GET", "/sms/range?minutes=30"))
        assertEquals(401, c.responseCode)
    }
}
