import net.kuafuai.andee.config.NotebookBook
import net.kuafuai.andee.config.NotebookStore
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Quick smoke test for [net.kuafuai.andee.config.LifeTools]. */
class LifeToolsTest {
    private val store = object : NotebookStore {
        var data = "{}"
        override fun read() = data
        override fun write(plain: String): Boolean { data = plain; return true }
        override fun wipe() { data = "{}" }
    }
    private val book = NotebookBook(store).apply {
        localWikiStore = net.kuafuai.andee.wiki.LocalWikiStore(java.io.File.createTempFile("wiki", "").also { it.delete(); it.mkdirs() })
    }
    private val life = net.kuafuai.andee.config.LifeTools(book.selfhood, book.people, book.wiki)

    @Test
    fun `self_note starts empty`() {
        val r = life.handle("note.self", JSONObject().put("action", "get"))
        assertEquals("", r.optString("text"))
        assertTrue(r.has("note")) // "You have not written yourself down yet"
    }

    @Test
    fun `cares starts empty`() {
        val r = life.handle("note.cares", JSONObject().put("action", "list"))
        assertEquals(0, r.optJSONArray("cares")?.length() ?: -1)
    }

    @Test
    fun `diary read before any write returns empty`() {
        val r = life.handle("note.diary", JSONObject().put("action", "read"))
        assertEquals(0, r.optJSONArray("pages")?.length() ?: -1)
    }
}
