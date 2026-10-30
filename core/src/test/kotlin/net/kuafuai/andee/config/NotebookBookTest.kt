package net.kuafuai.andee.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notebook's rules with the storage swapped for memory — the same code the
 * phone and the desktop both run.
 */
class NotebookBookTest {

    private class MemStore : NotebookStore {
        var blob: String? = null
        var writes = 0
        override fun read() = blob
        override fun write(plain: String): Boolean { blob = plain; writes++; return true }
        override fun wipe() { blob = null }
    }

    private fun fresh() = MemStore().let { it to NotebookBook(it) }

    @Test
    fun `remember then recall by keyword`() {
        val (_, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        b.remember("route", "settings then display", "", "way")
        assertEquals(listOf("fruit"), b.recall("mango", "", 10).map { it.name })
        assertEquals(2, b.memories().size)
    }

    @Test
    fun `overwriting by name keeps the old version in history`() {
        val (_, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        val (m, overwrote) = b.remember("Fruit", "likes durian now", "", "user")
        assertTrue(overwrote)
        assertEquals(1, b.memories().size)
        assertTrue(m.history.single().endsWith("likes mango"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown type is refused`() {
        fresh().second.remember("x", "y", "", "gossip")
    }

    @Test
    fun `forget deletes and reports whether it found the entry`() {
        val (_, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        assertTrue(b.forget("FRUIT"))
        assertFalse(b.forget("fruit"))
        assertTrue(b.memories().isEmpty())
    }

    @Test
    fun `the book survives a reload from the store`() {
        val (store, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        b.saveScene("english", "English practice", "speak English", "Only English.", emptyList())
        val reloaded = NotebookBook(store)
        assertEquals("likes mango", reloaded.memories().single().description)
        assertEquals("English practice", reloaded.scene("english")?.title)
    }

    @Test
    fun `todos keep their provenance through the store`() {
        val (store, b) = fresh()
        val t = Todo("t1", "pay", "once", 1L, "", 1L, 0L, "", "", "active", 0L, untrusted = true)
        b.addTodo(t)
        assertTrue(NotebookBook(store).todoById("t1")!!.untrusted)
        b.patchTodo("t1", status = "done")
        assertEquals(0, b.todos("active").size)
        assertNull(b.patchTodo("nope", status = "done"))
    }

    @Test
    fun `a write the store refuses fails loudly and leaves nothing behind`() {
        val store = object : NotebookStore {
            override fun read(): String? = null
            override fun write(plain: String) = false
            override fun wipe() {}
        }
        val b = NotebookBook(store)
        val failed = runCatching { b.remember("fruit", "likes mango", "", "user") }.isFailure
        assertTrue("remember must throw so the model is not told it saved", failed)
        assertTrue(b.memories().isEmpty())
    }

    @Test
    fun `wipe forgets the cached copy too`() {
        val (_, b) = fresh()
        b.remember("fruit", "likes mango", "", "user")
        b.wipe()
        assertTrue(b.memories().isEmpty())
    }

    @Test
    fun `a fresh notebook gets the nightly picture once, and again after a wipe`() {
        val (_, b) = fresh()
        val seeded = b.seedDefaults()
        assertEquals(1, seeded.size)
        val t = seeded.single()
        assertEquals("cron", t.mode)
        assertEquals(NotebookBook.NIGHTLY_PICTURE_CRON, t.cronExpr)
        assertTrue(t.nextRunAt > System.currentTimeMillis())
        assertNull(NotebookBook.bundledTodo(t.what))
        assertEquals(1, b.todos("pending").size)

        // Dropped by the user: the next start must not bring it back.
        b.patchTodo(t.id, status = "dropped")
        assertTrue(b.seedDefaults().isEmpty())
        assertEquals(0, b.todos("pending").size)

        b.wipe()
        assertEquals(1, b.seedDefaults().size)
        assertEquals(NotebookBook.NIGHTLY_PICTURE, b.todos("pending").single().what)
    }

    @Test
    fun `not seeded beside one already set on the same schedule`() {
        val (_, b) = fresh()
        b.addTodo(Todo("t1", "my own nine o'clock", "cron", 0L, NotebookBook.NIGHTLY_PICTURE_CRON, 1L, 0L, "", "", "active", 0L, false))
        assertTrue(b.seedDefaults().isEmpty())
        assertEquals(listOf("t1"), b.todos("pending").map { it.id })
    }

    @Test
    fun `the old Chinese wording is rewritten in place, not added beside`() {
        val (_, b) = fresh()
        b.seedDefaults()
        val id = b.todos("pending").single().id
        // Simulate a notebook seeded with the first wording.
        b.wipe()
        val old = "每晚回顾：把今天的事过一遍（对话、做过的事、日记），据此画一张「你和我」的图，要有意境。" +
            "画好放到屏幕上给用户看，再用一两句话说画的是什么。今天没什么事就画安静的一天，不要硬凑。"
        b.addTodo(Todo(id, old, "cron", 0L, NotebookBook.NIGHTLY_PICTURE_CRON, 1L, 0L, "", "", "active", 0L, false))
        b.seedDefaults()   // first pass on this book: sets the flag, rewrites, adds nothing beside it
        assertTrue(b.seedDefaults().isEmpty())
        val t = b.todos("pending").single()
        assertEquals(id, t.id)
        assertEquals(NotebookBook.NIGHTLY_PICTURE, t.what)
        assertTrue(t.what.contains("show_html"))

        // Already seeded (the flag is set) and still holding the old wording: rewritten all the same.
        b.addTodo(Todo("t9", old, "cron", 0L, "0 22 * * *", 1L, 0L, "", "", "active", 0L, false))
        assertTrue(b.seedDefaults().isEmpty())
        assertEquals(NotebookBook.NIGHTLY_PICTURE, b.todoById("t9")!!.what)
    }
}
