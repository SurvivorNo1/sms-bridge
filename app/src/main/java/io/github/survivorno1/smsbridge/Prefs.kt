package io.github.survivorno1.smsbridge

import android.content.Context
import java.security.SecureRandom
import java.util.Base64

/** 密钥与端口。存在 app 私有目录的 SharedPreferences 里，allowBackup=false 所以不会被云备份带走。 */
object Prefs {
    private const val FILE = "bridge"
    const val DEFAULT_PORT = 8765

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun secret(c: Context): String = sp(c).getString("secret", null) ?: regenerate(c)

    fun regenerate(c: Context): String {
        val s = newSecret()
        sp(c).edit().putString("secret", s).apply()
        return s
    }

    fun port(c: Context): Int = sp(c).getInt("port", DEFAULT_PORT)

    /** 24 字节随机 → 32 位 URL-safe base64，无填充 */
    fun newSecret(): String {
        val b = ByteArray(24)
        SecureRandom().nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
