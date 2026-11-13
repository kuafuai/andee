package net.kuafuai.andee.desktop.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkerFilesTest {

    private fun folder(): File = Files.createTempDirectory("worker").toFile().apply {
        File(this, "report.md").writeText("# Title\n\nbody")
        File(this, "data.csv").writeText("a,b\n1,2\n")
        File(this, "page.html").writeText("<h1>x</h1>")
        File(this, "photo.png").writeBytes(byteArrayOf(1, 2, 3))
        File(this, ".hidden").writeText("no")
        File(this, "sub").mkdirs()
        File(this, "sub/notes.txt").writeText("hello")
    }

    @Test fun foldersComeFirstThenFilesByName_andHiddenOnesAreLeftOut() {
        val names = WorkerFiles(folder()).list("").map { it.name to it.dir }
        assertEquals(listOf("sub" to true, "data.csv" to false, "page.html" to false, "photo.png" to false, "report.md" to false), names)
    }

    @Test fun aSubfolderIsListedByRelativePath() {
        assertEquals(listOf("notes.txt"), WorkerFiles(folder()).list("sub").map { it.name })
    }

    @Test fun markdownIsReadInTheWindowAsMarkdown_andOtherTextAsPlainText() {
        val f = WorkerFiles(folder())
        val md = f.open("report.md") as WorkerFiles.Opened.Text
        assertTrue(md.markdown); assertFalse(md.more); assertEquals("# Title\n\nbody", md.text)
        val csv = f.open("data.csv") as WorkerFiles.Opened.Text
        assertFalse(csv.markdown); assertEquals("a,b\n1,2\n", csv.text)
    }

    @Test fun aPageAndAPictureAreHandedToTheSystem() {
        val f = WorkerFiles(folder())
        assertTrue(f.open("page.html") is WorkerFiles.Opened.Page)
        assertTrue(f.open("photo.png") is WorkerFiles.Opened.Other)
    }

    @Test fun aLongTextFileIsCutAndSaysSo() {
        val d = folder()
        File(d, "big.log").writeText("x".repeat(WorkerFiles.MAX_TEXT_BYTES + 5000))
        val o = WorkerFiles(d).open("big.log") as WorkerFiles.Opened.Text
        assertTrue(o.more)
        assertEquals(WorkerFiles.MAX_TEXT_BYTES, o.text.length)
    }

    @Test fun nothingOutsideTheFolderCanBeListedOrRead() {
        val d = folder()
        val outside = File(d.parentFile, "secret-${d.name}.txt").apply { writeText("secret"); deleteOnExit() }
        val f = WorkerFiles(d)
        for (p in listOf("..", "../" + outside.name, "sub/../../" + outside.name)) {
            try { f.list(p); fail("list $p") } catch (_: IllegalArgumentException) {}
            try { f.open(p); fail("open $p") } catch (_: IllegalArgumentException) {}
        }
        // A leading slash is joined to the folder, not taken as the disk's root.
        try { f.open("/etc/hosts"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun aLinkThatLeavesTheFolderCountsAsOutside() {
        val d = folder()
        val outside = File(d.parentFile, "linked-${d.name}").apply { mkdirs(); File(this, "x.txt").writeText("x"); deleteOnExit() }
        Files.createSymbolicLink(File(d, "escape").toPath(), outside.toPath())
        val f = WorkerFiles(d)
        try { f.list("escape"); fail() } catch (_: IllegalArgumentException) {}
        try { f.open("escape/x.txt"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun sizesReadAsAPersonWouldSayThem() {
        assertEquals("12 B", WorkerFiles.size(12))
        assertEquals("104 KB", WorkerFiles.size(106_000))
        assertEquals("1.5 MB", WorkerFiles.size(1_572_864))
    }
}
