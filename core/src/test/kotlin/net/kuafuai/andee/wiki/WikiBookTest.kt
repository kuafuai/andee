package net.kuafuai.andee.wiki

import net.kuafuai.andee.config.NotebookBook
import net.kuafuai.andee.config.NotebookStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class WikiBookTest {

    private var now = 1_800_000_000_000L
    private val dir = File.createTempFile("wiki", "").also { it.delete(); it.mkdirs() }
    private val local = LocalWikiStore(dir)
    private val notebook = NotebookBook(object : NotebookStore {
        var s: String? = null
        override fun read() = s
        override fun write(plain: String): Boolean { s = plain; return true }
        override fun wipe() { s = null }
    })
    private val remote = FakeRemote()
    private val wiki = WikiBook(notebook, mapOf("local" to local, "feishu" to remote), { now })

    @After fun cleanup() { dir.deleteRecursively() }

    /** A backend whose documents can change or vanish behind our back, like a real online one. */
    private class FakeRemote : WikiStore {
        override val key = "feishu"
        override val capabilities = WikiStore.Capabilities(needsScreen = true, sharing = setOf("user"))
        val docs = HashMap<String, String>()
        override fun read(ref: String) = docs[ref] ?: throw WikiUnavailable("文档被删除了或没有权限")
        override fun write(ref: String?, title: String, body: String, changeNote: String): String { val r = ref ?: "https://x.feishu.cn/docx/${docs.size}"; docs[r] = body; return r }
        override fun share(ref: String, to: String, permission: String) = WikiStore.Share(true, "shared $ref with $to")
        override fun archive(ref: String) { docs.remove(ref) }
    }

    private fun w(title: String, body: String = "正文第一段。\n第二段。", id: String? = null, kind: String = "plan", level: Level? = null) =
        wiki.write(id, title, kind, body, "我推断的", "初稿", level, null)

    @Test fun anArticleIsAFileWithAHeaderAndAnIndexEntry() {
        val m = w("社区运营方案").getOrThrow()
        assertEquals(1, m.version); assertEquals(Status.DRAFT, m.status); assertEquals(Level.INTERNAL, m.level)
        val f = File(dir, m.ref)
        assertTrue(f.isFile)
        assertTrue(f.readText().startsWith("---\ntitle: 社区运营方案\nkind: plan\nconfidentiality: internal"))
        assertEquals("正文第一段。 第二段。", m.summary)
        assertEquals("正文第一段。\n第二段。", wiki.read(m.id).getOrThrow().body)
    }

    @Test fun aSecondArticleOnTheSameSubjectIsRefusedWithTheIdToRevise() {
        val a = w("社区运营方案").getOrThrow()
        val e = w("社区运营方案 v2").exceptionOrNull()
        assertNotNull(e); assertTrue(e!!.message!!.contains(a.id) && e.message!!.contains("Revise"))
        val b = w("社区运营方案", "更新后的正文。", id = a.id).getOrThrow()
        assertEquals(a.id, b.id); assertEquals(2, b.version)
        assertEquals(1, wiki.all().size)
        assertTrue(File(dir, ".history").listFiles()!!.any { it.name.endsWith(".v1.md") })           // v1 kept, not overwritten
    }

    @Test fun aWriteMustSayWhereItCameFromAndWhy() {
        assertTrue(wiki.write(null, "a", "plan", "x", "", "初稿", null, null).exceptionOrNull()!!.message!!.contains("where this comes from"))
        assertTrue(wiki.write(null, "a", "plan", "x", "查到的", "", null, null).exceptionOrNull()!!.message!!.contains("changeNote"))
        assertTrue(wiki.write(null, "a", "plan", "x".repeat(WikiBook.MAX_BODY + 1), "s", "n", null, null).exceptionOrNull()!!.message!!.contains("Too long"))
    }

    @Test fun searchFindsLiveArticlesAndArchivedOnesLeave() {
        val a = w("社区运营方案").getOrThrow()
        w("Discord 竞品调研", "他们每周办一次 AMA。", kind = "research").getOrThrow()
        assertEquals(listOf("Discord 竞品调研"), wiki.search("竞品").map { it.title })
        assertEquals(1, wiki.search("", kind = "plan").size)
        assertEquals(a.id, wiki.search(a.id).single().id)
        assertEquals(null, wiki.setStatus(a.id, Status.ARCHIVED, ""))
        assertTrue(wiki.search("社区").isEmpty())
        assertTrue(File(dir, "archive").listFiles()!!.isNotEmpty())
    }

    @Test fun staleNeedsAReason() {
        val a = w("方案").getOrThrow()
        assertTrue(wiki.setStatus(a.id, Status.STALE, "")!!.contains("why"))
        assertEquals(null, wiki.setStatus(a.id, Status.STALE, "已被 v3 方案取代"))
        assertEquals("已被 v3 方案取代", wiki.meta(a.id)!!.reason)
    }

    @Test fun anOutsideDocumentIsKnownByALeadNotACopy() {
        val m = wiki.know("社区周报（飞书）", "note", "feishu", "https://x.feishu.cn/docx/abc", "他共享给我的").getOrThrow()
        assertEquals(0, m.version); assertEquals(0L, m.verified)
        assertEquals(m.id, wiki.know("别的标题", "note", "feishu", "https://x.feishu.cn/docx/abc", "x").getOrThrow().id)   // same place: same article
        remote.docs["https://x.feishu.cn/docx/abc"] = "本周活跃 120 人，新增 14 人。"
        val a = wiki.read(m.id).getOrThrow()
        assertEquals("本周活跃 120 人，新增 14 人。", a.body)
        assertEquals("本周活跃 120 人，新增 14 人。", wiki.meta(m.id)!!.summary)     // refreshed by the read
        assertTrue(wiki.meta(m.id)!!.verified > 0)
    }

    @Test fun whatChangedOnlineIsPickedUpAndWhatVanishedIsMarkedLostNotRemembered() {
        val m = wiki.know("周报", "note", "feishu", "https://x/1", "共享").getOrThrow()
        remote.docs["https://x/1"] = "旧内容"
        wiki.read(m.id).getOrThrow()
        remote.docs["https://x/1"] = "别人改过的新内容"
        assertEquals("别人改过的新内容", wiki.read(m.id).getOrThrow().body)               // no local copy to disagree with
        remote.docs.remove("https://x/1")
        val e = wiki.read(m.id).exceptionOrNull()!!
        assertTrue(e.message!!.contains("lost") && e.message!!.contains("do not rely"))
        assertEquals(Status.LOST, wiki.meta(m.id)!!.status)
        assertTrue(wiki.contents().contains("失联"))
        remote.docs["https://x/1"] = "又恢复了"
        wiki.read(m.id).getOrThrow()
        assertEquals(Status.ACTIVE, wiki.meta(m.id)!!.status)                          // it heals when it is reachable again
    }

    @Test fun whatTheUserEditedIsNotQuietlyRewrittenByTheModel() {
        val a = w("方案").getOrThrow()
        wiki.write(a.id, "方案", "plan", "用户改过的正文", "", "", null, null, byUser = true).getOrThrow()
        assertTrue(w("方案", "模型想改", id = a.id).exceptionOrNull()!!.message!!.contains("edited"))
        assertTrue(wiki.setStatus(a.id, Status.ARCHIVED, "", byUser = false)!!.contains("theirs"))
    }

    @Test fun anUnknownLevelReadsAsInternalNeverPublic() {
        assertEquals(Level.INTERNAL, Level.of("whatever")); assertEquals(Level.INTERNAL, Level.of(null))
        assertEquals(Level.SECRET, Level.of("SECRET"))
    }

    @Test fun sharingASecretArticleAddsAReminderButDoesNotStop() {
        val s = w("融资计划", level = Level.SECRET).getOrThrow()
        val r = wiki.share(s.id, "老王", "read").getOrThrow()
        assertTrue(r.contains("老王") && r.contains("marked secret"))
        val p = w("公开的介绍", level = Level.PUBLIC, kind = "note").getOrThrow()
        assertFalse(wiki.share(p.id, "老王", "read").getOrThrow().contains("marked"))
    }

    @Test fun aRefCannotEscapeTheWikiFolder() {
        val bad = wiki.know("x", "note", "local", "../../etc/passwd", "s").getOrThrow()
        assertTrue(wiki.read(bad.id).isFailure)
        try { local.read("../secret.md"); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("outside")) }
    }

    @Test fun theLimitMeansTidyFirstNotSilentLoss() {
        repeat(WikiBook.MAX_ARTICLES) { wiki.know("t$it", "note", "feishu", "https://x/$it", "s").getOrThrow() }
        assertTrue(w("一篇新的 完全不同的主题").exceptionOrNull()!!.message!!.contains("Tidy"))
    }

    @Test fun theContentsPageAndPromptLineSummariseWithoutBodies() {
        w("社区运营方案").getOrThrow()
        assertTrue(wiki.contents().contains("# 目录") && wiki.contents().contains("社区运营方案"))
        val line = wiki.indexForPrompt()
        assertTrue(line.contains("社区运营方案") && line.contains("wiki_search") && !line.contains("第二段"))
    }
}
