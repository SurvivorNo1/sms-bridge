package io.github.survivorno1.smsbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨语言向量：期望值由电脑端 Python（hmac / pycryptodome）算出，
 * 保证手机端与 sms_code.py 的签名、加密逐字节一致。
 */
class CryptoTest {
    private val secret = "abc123"
    private val ts = 1700000000000L
    private val target = "/sms/search?q=%E9%AA%8C%E8%AF%81%E7%A0%81&minutes=30&re=0"

    @Test
    fun signMatchesPython() {
        assertEquals(
            "37aaffce8e96c41e076ac35c2e034f5395ddba70099d1479ae5931f5b74d5b95",
            Crypto.sign(secret, ts, "GET", target)
        )
        assertTrue(Crypto.verify(secret, ts, "GET", target, "37AAFFCE8E96C41E076AC35C2E034F5395DDBA70099D1479AE5931F5B74D5B95"))
        assertFalse(Crypto.verify(secret, ts, "GET", target + "&x=1", "37aaffce8e96c41e076ac35c2e034f5395ddba70099d1479ae5931f5b74d5b95"))
        assertFalse(Crypto.verify("wrong", ts, "GET", target, "37aaffce8e96c41e076ac35c2e034f5395ddba70099d1479ae5931f5b74d5b95"))
    }

    @Test
    fun encryptMatchesPython() {
        val iv = ByteArray(12) { it.toByte() }
        val plain = """{"ok":true,"items":[{"ts":1,"from":"10690","body":"验证码 123456"}]}"""
        assertEquals(
            "AAECAwQFBgcICQoLzN9LxxdqElE5kv9Q8BpylqdObpQCGPc2B+k5vzqSb6lHE00lGqM8LaZqFO3MjYYd4SLywfe9cUDYGYOFGxLY5e5OigiwT6wPCqQfB9ebnOTTgc6M2qzg",
            Crypto.encrypt(secret, plain, iv)
        )
    }

    @Test
    fun roundTrip() {
        val plain = "中文 & emoji 🙂 \"quotes\""
        assertEquals(plain, Crypto.decrypt(secret, Crypto.encrypt(secret, plain)))
    }

    @Test
    fun jsonEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", Json.str("a\"b\\c\nd"))
        assertEquals(
            """{"ok":true,"count":1,"items":[{"ts":5,"from":"x","body":"y"}]}""",
            Json.items(listOf(Msg(5, "x", "y")))
        )
    }

    @Test
    fun secretShape() {
        val s = Prefs.newSecret()
        assertEquals(32, s.length)
        assertTrue(s.matches(Regex("[A-Za-z0-9_-]+")))
    }
}
