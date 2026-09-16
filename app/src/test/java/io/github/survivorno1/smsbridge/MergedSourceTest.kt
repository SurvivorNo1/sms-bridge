package io.github.survivorno1.smsbridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 收件箱 ∪ 广播缓存的合并与去重（Capture 不 init 时只在内存里，正好用于单测）。 */
class MergedSourceTest {
    private val now = System.currentTimeMillis()

    private val inbox = object : SmsSource {
        val all = listOf(
            Msg(now - 5_000, "95588", "账户变动"),
            Msg(now - 60_000, "10690", "【A】验证码 111111"),
        )
        override fun range(from: Long, to: Long, limit: Int) = all.filter { it.ts in from..to }.take(limit)
        override fun search(q: String, regex: Boolean, since: Long, limit: Int) =
            all.filter { it.ts >= since && it.body.contains(q, true) }.take(limit)
    }

    @Before
    fun seed() {
        Capture.clear()
        // 同一条短信广播里的时间戳与入库时间差 3 秒 → 必须去重
        Capture.add(Msg(now - 63_000, "10690", "【A】验证码 111111"))
        // 只在广播通道里有的（被「验证码保护」藏起来的那种）
        Capture.add(Msg(now - 2_000, "10691", "【B】验证码 222222"))
        // 过期的不应出现
        Capture.add(Msg(now - 30L * 3600_000, "10692", "【C】验证码 333333"))
    }

    @After
    fun tidy() = Capture.clear()

    @Test
    fun rangeMergesAndDedups() {
        val list = MergedSource(inbox).range(now - 3600_000, now)
        assertEquals(3, list.size)
        assertEquals("【B】验证码 222222", list[0].body) // 最新在前
        assertEquals(1, list.count { it.body.contains("111111") })
        assertTrue(list.none { it.body.contains("333333") })
    }

    @Test
    fun searchCoversBothChannels() {
        val hit = MergedSource(inbox).search("验证码", false, now - 3600_000)
        assertEquals(2, hit.size)
        val re = MergedSource(inbox).search("222\\d{3}", true, now - 3600_000)
        assertEquals(1, re.size)
    }

    @Test
    fun captureIgnoresExactDuplicates() {
        val before = Capture.size()
        Capture.add(Msg(now - 2_000, "10691", "【B】验证码 222222"))
        assertEquals(before, Capture.size())
    }
}
