package io.github.survivorno1.smsbridge

import android.content.ContentResolver
import android.provider.Telephony

data class Msg(val ts: Long, val from: String, val body: String)

/** 短信来源抽象：正式实现读本机收件箱；测试用假数据。 */
interface SmsSource {
    /** [from, to] 内的短信，新的在前，最多 [limit] 条 */
    fun range(from: Long, to: Long, limit: Int = MAX_ROWS): List<Msg>

    /** since 之后、正文或发件人含关键词（或匹配正则）的短信，新的在前 */
    fun search(q: String, regex: Boolean, since: Long, limit: Int = MAX_ROWS): List<Msg>

    /** 调试：整个 content://sms（不限收件箱）里发件人含 q 的行，带 type/read/seen 原始字段 */
    fun raw(q: String, since: Long, limit: Int = 50): List<Map<String, Any?>> = emptyList()

    companion object { const val MAX_ROWS = 200 }
}

/** 直接查系统短信收件箱 content://sms/inbox，需要 READ_SMS。 */
class InboxSource(private val cr: ContentResolver) : SmsSource {
    private val cols = arrayOf(
        Telephony.Sms.DATE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY
    )

    override fun range(from: Long, to: Long, limit: Int): List<Msg> = query(
        "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.DATE} <= ?",
        arrayOf(from.toString(), to.toString()), limit
    )

    override fun search(q: String, regex: Boolean, since: Long, limit: Int): List<Msg> {
        if (regex) {
            val re = Regex(q, RegexOption.IGNORE_CASE)
            return query("${Telephony.Sms.DATE} >= ?", arrayOf(since.toString()), SmsSource.MAX_ROWS)
                .filter { re.containsMatchIn(it.body) || re.containsMatchIn(it.from) }
                .take(limit)
        }
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        return query(
            "${Telephony.Sms.DATE} >= ? AND (${Telephony.Sms.BODY} LIKE ? ESCAPE '\\' OR ${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\')",
            arrayOf(since.toString(), like, like), limit
        )
    }

    override fun raw(q: String, since: Long, limit: Int): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        cr.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "thread_id", "type", "read", "seen", "date", "date_sent", "address", "body"),
            "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\'",
            arrayOf(since.toString(), like), "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { c ->
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>()
                for (i in 0 until c.columnCount) row[c.getColumnName(i)] = when (c.getType(i)) {
                    android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    else -> c.getString(i)
                }
                out.add(row)
            }
        }
        return out
    }

    private fun query(sel: String, args: Array<String>, limit: Int): List<Msg> {
        val out = ArrayList<Msg>()
        cr.query(
            Telephony.Sms.Inbox.CONTENT_URI, cols, sel, args,
            "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )?.use { c ->
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            while (c.moveToNext()) {
                out.add(Msg(c.getLong(iDate), c.getString(iAddr) ?: "", c.getString(iBody) ?: ""))
            }
        }
        return out
    }
}
