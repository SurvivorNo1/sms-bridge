package io.github.survivorno1.smsbridge

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 请求签名：X-Sign = hex( HMAC-SHA256( secret, "<ts>\n<METHOD>\n<path?query>" ) )
 * 响应加密：base64( iv(12) + AES-256-GCM( key = SHA-256(secret), plaintext ) )
 */
object Crypto {
    fun hmacHex(secret: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun verify(secret: String, ts: Long, method: String, target: String, sign: String): Boolean {
        val expect = hmacHex(secret, "$ts\n$method\n$target")
        return MessageDigest.isEqual(expect.toByteArray(), sign.lowercase().toByteArray())
    }

    private fun key(secret: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())

    fun encrypt(secret: String, plain: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(secret), "AES"), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray())
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }
}
