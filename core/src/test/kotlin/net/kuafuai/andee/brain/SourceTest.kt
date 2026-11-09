package net.kuafuai.andee.brain

import net.kuafuai.andee.config.NotebookBook
import net.kuafuai.andee.config.NotebookStore
import net.kuafuai.andee.config.People
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SourceTest {

    private var now = 1_800_000_000_000L
    private val book = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })
    private val people = People(book, { now }, ZoneId.of("Asia/Shanghai"))

    private fun n(chat: String?, title: String, latest: String, pkg: String = "com.tencent.mm") = JSONObject()
        .put("pkg", pkg).put("app", "微信").put("title", title).put("latest", latest).put("text", latest).put("when", now)
        .apply { if (chat != null) put("chat", chat) }

    @Test fun aGroupNotificationIsTheGroupAndTheSenderIsInTheText() {
        val s = Source.fromNotification(n("group", "B群", "小王: 报价多少？"))
        assertEquals(true, s.group)
        assertEquals("B群", s.conversation)
        assertEquals("小王", s.sender)
        assertEquals("报价多少？", s.quote)
        assertEquals("wechat", s.channel)
        assertEquals(People.Channel("wechat", "小王", "B群"), s.speakerChannel())
        // full-width colon, as the Chinese apps write it
        assertEquals("老李", Source.fromNotification(n("group", "B群", "老李：明天见")).sender)
        // the app's own tags in front of the sender
        val tagged = Source.fromNotification(n("group", "B群", "[有人@我] 小王: @Andee 报价多少？"))
        assertEquals("小王", tagged.sender)
        assertEquals("@Andee 报价多少？", tagged.quote)
        assertEquals("小王", Source.fromNotification(n("group", "B群", "[3条]小王: 在吗")).sender)
    }

    @Test fun aGroupMessageWithNoSenderIsSaidToHaveNoneNotGuessed() {
        val s = Source.fromNotification(n("group", "B群", "[图片]"))
        assertEquals("", s.sender)
        assertNull(s.speakerChannel())
        assertTrue(s.forPrompt().contains("sender not shown"))
    }

    @Test fun aPrivateChatIsTitledWithThePersonAndARepeatedNameIsDropped() {
        val s = Source.fromNotification(n("private", "王小明", "王小明: 在吗"))
        assertEquals(false, s.group)
        assertEquals("王小明", s.sender)
        assertEquals("在吗", s.quote)
        assertEquals(People.Channel("wechat", "王小明", ""), s.speakerChannel())
        // a colon that is part of the message, not a sender
        assertEquals("时间：明天", Source.fromNotification(n("private", "王小明", "时间：明天")).quote)
    }

    @Test fun theMacJoinsTitleAndSubtitleAndOnlyTheTitleIsTheConversation() {
        val s = Source.fromNotification(n("group", "B群 · 3条新消息", "小王: 好", pkg = "com.tencent.xinWeChat"))
        assertEquals("B群", s.conversation)
        assertEquals("wechat", s.channel)
    }

    @Test fun somethingThatIsNotAChatHasNobodyToAnswer() {
        val s = Source.fromNotification(n(null, "快递", "您的包裹已签收", pkg = "com.express"))
        assertNull(s.group)
        assertEquals("", s.sender)
        assertFalse(Source.worthRecording(s, startsTurn = true))
    }

    @Test fun onlyPeopleWhoSpokeToUsGoOnRecord() {
        val group = Source.fromNotification(n("group", "B群", "小王: 大家好"))
        val private = Source.fromNotification(n("private", "王小明", "在吗"))
        assertFalse("a group message the user was only told about: not everyone in the group gets a file", Source.worthRecording(group, startsTurn = false))
        assertTrue("a group message a turn starts from: that person asked something", Source.worthRecording(group, startsTurn = true))
        assertTrue(Source.worthRecording(private, startsTurn = false))
    }

    @Test fun theSpeakerIsRecordedOnceAndTheTurnIsToldWhoTheyAre() {
        val src = Source.fromNotification(n("group", "B群", "小王: 报价多少？"))
        val a = Source.recordSpeaker(people, src)
        val id = a.personId!!
        assertEquals("unconfirmed", people.get(id)!!.status)
        assertEquals(listOf("报价多少？"), people.get(id)!!.log.map { it.gist })
        now += 60_000
        val b = Source.recordSpeaker(people, Source.fromNotification(n("group", "B群", "小王: 还在吗")))
        assertEquals("same group, same nickname: the same record", id, b.personId)
        assertEquals(2, people.get(id)!!.log.size)
        val line = b.forPrompt()
        assertTrue(line.contains("group 「B群」") && line.contains("from 「小王」") && line.contains(id))
        assertTrue(line.contains("quote-reply"))
    }

    @Test fun someoneWhoMayBeSomeoneKnownIsPointedOutNotMerged() {
        val known = people.upsert("小王", null, People.Channel("wechat", "王小明"), null, null, null, null, null)
        val s = Source.recordSpeaker(people, Source.fromNotification(n("group", "C群", "小王: hi")))
        assertTrue(s.personId != known)                                  // a nickname in another group is not a guess
        assertTrue(s.personNote.contains("may also be") && s.personNote.contains(known))
    }

    @Test fun aPersonTheUserRemovedIsNotWrittenBackOrShown() {
        val id = Source.recordSpeaker(people, Source.fromNotification(n("private", "推销员", "优惠来了"))).personId!!
        people.deleteByUser(id)
        val s = Source.recordSpeaker(people, Source.fromNotification(n("private", "推销员", "再看看")))
        assertNull(s.personId)
        assertTrue(people.all().none { it.name == "推销员" })
    }

    @Test fun aSensitiveMessageStillLogsThatTheyWroteButNotWhat() {
        val s = Source.recordSpeaker(people, Source.fromNotification(n("private", "老赵", "我的银行卡号 6222021234567890123")))
        assertEquals(listOf("wrote (not kept: personal)"), people.get(s.personId!!)!!.log.map { it.gist })
    }
}
