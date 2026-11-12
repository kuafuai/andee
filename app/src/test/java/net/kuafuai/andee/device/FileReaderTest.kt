package net.kuafuai.andee.device

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parts of `read_file` that decide what the model is told a file says. A wrong
 * encoding is invisible on the device — the model just answers about gibberish.
 */
class FileReaderTest {

    @Test
    fun `utf-8 with and without a BOM`() {
        assertEquals("héllo 你好", FileReader.decodeText("héllo 你好".toByteArray(Charsets.UTF_8), sniff = true))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "abc".toByteArray()
        assertEquals("abc", FileReader.decodeText(bom, sniff = true))
    }

    @Test
    fun `utf-16 with a BOM is text even though it is full of NULs`() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "Hi 你".toByteArray(Charsets.UTF_16LE)
        assertEquals("Hi 你", FileReader.decodeText(le, sniff = true))
    }

    @Test
    fun `a GBK text file is read as Chinese, not as replacement characters`() {
        val gbk = "会议纪要：明天九点".toByteArray(charset("GB18030"))
        assertEquals("会议纪要：明天九点", FileReader.decodeText(gbk, sniff = true))
    }

    @Test
    fun `a read cut in the middle of a character is still that encoding`() {
        val utf8 = "abc你好".toByteArray(Charsets.UTF_8)
        assertEquals("abc你", FileReader.decodeText(utf8.copyOfRange(0, utf8.size - 1), sniff = true, cut = true))
        val gbk = "会议纪要明天".toByteArray(charset("GB18030"))
        assertEquals("会议纪要明", FileReader.decodeText(gbk.copyOfRange(0, gbk.size - 1), sniff = true, cut = true))
    }

    @Test
    fun `a complete file is never shortened to make it decode`() {
        // Latin-1 "café": the last byte is invalid UTF-8, but the file was not cut.
        val latin1 = byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())
        assertNull(FileReader.decodeText(latin1, sniff = true))
        assertEquals("caf�", FileReader.decodeText(latin1, sniff = false))
    }

    @Test
    fun `a text window fits what the brain keeps of a tool result, and says where to go on`() {
        // Every newline escapes to two characters, so a window sized by raw length would overflow.
        val text = "\n".repeat(50_000)
        val out = FileReader.window(JSONObject(), text, offset = 0, cut = false)
        val window = out.getString("text")
        assertTrue(JSONObject.quote(window).length <= 18_000)
        assertEquals(true, out.getBoolean("truncated"))
        assertEquals(window.length, out.getInt("next_offset"))
        // The whole result, note and all, stays under LocalBrain's 24 000 cut.
        assertTrue(out.toString().length < 24_000)
        val rest = FileReader.window(JSONObject(), text, offset = out.getInt("next_offset"), cut = false)
        assertEquals(window.length, rest.getInt("offset"))
    }

    @Test
    fun `the last window of a short text is not marked truncated`() {
        val out = FileReader.window(JSONObject(), "hello", offset = 0, cut = false)
        assertEquals("hello", out.getString("text"))
        assertFalse(out.has("truncated"))
    }

    @Test
    fun `binary is refused when sniffing, and empty is empty`() {
        assertNull(FileReader.decodeText(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x08), sniff = true))
        assertEquals("", FileReader.decodeText(ByteArray(0), sniff = true))
    }

    @Test
    fun `docx tab stops, field codes and deletions are not text`() {
        val xml = "<w:p><w:pPr><w:tabs><w:tab w:val=\"left\" w:pos=\"720\"/></w:tabs></w:pPr>" +
            "<w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:delText>old</w:delText></w:r>" +
            "<w:r><w:t>kept</w:t></w:r></w:p>"
        assertEquals("kept", FileReader.docxXmlToText(xml))
    }

    @Test
    fun `docx paragraphs, breaks, tabs and entities`() {
        val xml = "<w:document><w:body>" +
            "<w:p><w:r><w:t>Tom &amp; Jerry</w:t></w:r></w:p>" +
            "<w:p><w:r><w:t>a</w:t><w:tab/><w:t>b</w:t><w:br/><w:t>&lt;c&gt; &#20320;</w:t></w:r></w:p>" +
            "</w:body></w:document>"
        assertEquals("Tom & Jerry\na\tb\n<c> 你", FileReader.docxXmlToText(xml))
    }
}
