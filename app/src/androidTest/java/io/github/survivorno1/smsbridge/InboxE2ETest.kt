package io.github.survivorno1.smsbridge

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.net.URLEncoder

/**
 * 在模拟器上跑：CI 先用 `adb emu sms send 10690001 "..."` 往系统收件箱注入一条真实短信，
 * 这里通过 InboxSource → HttpServer → 签名 → 解密，全链路把它查出来。
 */
@RunWith(AndroidJUnit4::class)
class InboxE2ETest {
    @get:Rule
    val perms: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.READ_SMS)

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun inboxReadable() {
        val src = InboxSource(ctx.contentResolver)
        val now = System.currentTimeMillis()
        val list = src.range(now - 24 * 3600_000, now + 60_000)
        assertTrue("收件箱应有 CI 注入的短信，实际 ${list.size} 条", list.any { it.body.contains("482913") })
        val hit = src.search("verification", regex = false, since = now - 24 * 3600_000)
        assertTrue(hit.any { it.body.contains("482913") })
        val re = src.search("code\\D*(\\d{6})", regex = true, since = now - 24 * 3600_000)
        assertTrue(re.any { it.body.contains("482913") })
    }

    @Test
    fun httpEndToEnd() {
        val secret = "e2e-secret"
        val port = 18765
        val server = HttpServer(port, { secret }, InboxSource(ctx.contentResolver), lanOnly = false)
        server.start()
        try {
            Thread.sleep(200)
            val target = "/sms/search?q=${URLEncoder.encode("verification code", "UTF-8")}&minutes=1440"
            val ts = System.currentTimeMillis()
            // 用裸 socket 发请求：Android 对测试进程的 HttpURLConnection 默认禁明文 HTTP
            val crlf = "\r\n"
            val raw = Socket("127.0.0.1", port).use { sock ->
                val req = "GET $target HTTP/1.1" + crlf +
                    "Host: 127.0.0.1" + crlf +
                    "X-Timestamp: $ts" + crlf +
                    "X-Sign: " + Crypto.sign(secret, ts, "GET", target) + crlf +
                    "Connection: close" + crlf + crlf
                sock.getOutputStream().write(req.toByteArray())
                sock.getOutputStream().flush()
                sock.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            assertTrue(raw, raw.startsWith("HTTP/1.1 200"))
            val body = raw.substringAfter(crlf + crlf)
            val json = Crypto.decrypt(secret, body)
            assertTrue(json, json.contains("482913"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun activityLaunches() {
        val intent = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val a = InstrumentationRegistry.getInstrumentation().startActivitySync(intent)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(!a.isFinishing)
        a.finish()
    }
}
