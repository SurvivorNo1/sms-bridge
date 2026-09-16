package io.github.survivorno1.smsbridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 第二通道：SMS_RECEIVED 广播。
 * ROM 的「验证码保护」只拦第三方对 content://sms 的读取，广播通常照发。
 * 服务开着时把到达的短信存进 app 私有缓存（24 小时 / 200 条自动清理），查询时与收件箱合并去重。
 */
object Capture {
    private const val MAX_AGE_MS = 24L * 3600 * 1000
    private const val MAX_COUNT = 200
    private const val FILE = "capture.json"

    private val items = ArrayList<Msg>() // 新的在前
    private var file: File? = null

    @Synchronized
    fun init(c: Context) {
        if (file != null) return
        file = File(c.filesDir, FILE)
        load()
    }

    @Synchronized
    fun add(m: Msg) {
        if (items.any { it.ts == m.ts && it.from == m.from && it.body == m.body }) return
        items.add(0, m)
        prune()
        save()
    }

    @Synchronized
    fun all(): List<Msg> { prune(); return ArrayList(items) }

    @Synchronized
    fun size(): Int { prune(); return items.size }

    @Synchronized
    fun clear() { items.clear(); file?.delete() }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        items.removeAll { it.ts < cutoff }
        while (items.size > MAX_COUNT) items.removeAt(items.size - 1)
    }

    private fun save() {
        val f = file ?: return
        val arr = JSONArray()
        for (m in items) arr.put(JSONObject().put("ts", m.ts).put("from", m.from).put("body", m.body))
        runCatching { f.writeText(arr.toString()) }
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            items.clear()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(Msg(o.getLong("ts"), o.optString("from"), o.optString("body")))
            }
            prune()
        }
    }
}

/** 开关打开时才收集；关着的时候短信到了也当没看见。 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!BridgeService.running) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (parts.isEmpty()) return
        val from = parts[0].displayOriginatingAddress ?: ""
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val ts = parts[0].timestampMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
        Capture.init(context)
        Capture.add(Msg(ts, from, body))
    }
}
