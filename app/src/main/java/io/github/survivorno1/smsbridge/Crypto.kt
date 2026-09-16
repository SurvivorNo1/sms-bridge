package io.github.survivorno1.smsbridge

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 请求签名：X-Sign = hex( HMAC-SHA256( secret, "<ts>\n<METHOD>\n<path?query>" ) )
 * 响应加密：base64( iv(12) + AES-256-GCM( key = SHA-256(secret), plaintext ) )
 * 纯 JVM 实现（不用 android.util.*），方便单元测试。
 */
object Crypto {
    fun hmacHex(secret: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun sign(secret: String, ts: Long, method: String, target: String): String =
        hmacHex(secret, "$ts\n$method\n$target")

    fun verify(secret: String, ts: Long, method: String, target: String, sign: String): Boolean {
        val expect = sign(secret, ts, method, target)
        return MessageDigest.isEqual(expect.toByteArray(), sign.lowercase().toByteArray())
    }

    private fun key(secret: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())

    fun encrypt(secret: String, plain: String, iv: ByteArray = randomIv()): String {
        require(iv.size == 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(secret), "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray())
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(secret: String, b64: String): String {
        val raw = Base64.getDecoder().decode(b64)
        val iv = raw.copyOfRange(0, 12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key(secret), "AES"), GCMParameterSpec(128, iv))
        return String(c.doFinal(raw, 12, raw.size - 12))
    }

    private fun randomIv() = ByteArray(12).also { SecureRandom().nextBytes(it) }
}
