package io.github.survivorno1.smsbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Msg(val ts: Long, val from: String, val body: String)

/**
 * 短信缓存：只放开关打开期间收到的短信，最多 24 小时 / 200 条。
 * 持久化到 app 私有目录（Android 沙箱，其他 app 读不到），防止进程被系统杀掉后丢数据。
 * 关开关时整个清空。
 */
object Store {
    private const val MAX_AGE_MS = 24L * 3600 * 1000
    private const val MAX_COUNT = 200
    private const val FILE = "store.json"

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
        items.add(0, m)
        prune()
        save()
    }

    @Synchronized
    fun range(from: Long, to: Long): List<Msg> {
        prune()
        return items.filter { it.ts in from..to }
    }

    @Synchronized
    fun search(q: String, regex: Boolean, since: Long): List<Msg> {
        prune()
        val re = if (regex) Regex(q, RegexOption.IGNORE_CASE) else null
        return items.filter { m ->
            m.ts >= since && (
                if (re != null) re.containsMatchIn(m.body) || re.containsMatchIn(m.from)
                else m.body.contains(q, ignoreCase = true) || m.from.contains(q, ignoreCase = true)
            )
        }
    }

    @Synchronized
    fun size(): Int { prune(); return items.size }

    @Synchronized
    fun clear() {
        items.clear()
        file?.delete()
    }

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
