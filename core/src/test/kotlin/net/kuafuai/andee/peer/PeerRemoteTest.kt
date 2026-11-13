package net.kuafuai.andee.peer

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The phone using the Mac's agent over the link, with both ends in one process and a real folder behind the Mac's
 * [PeerRemote.Agent]: the owner's switch, the five methods and nothing else, a folder's listing, a file in chunks
 * (nothing is read until it is asked for), and the folder's walls.
 */
class PeerRemoteTest {

    /** A worker's folder on disk, and the calls the Mac received. */
    private class Mac(val root: File) : PeerRemote.Agent {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        var up = true
        override fun available() = up
        override fun call(method: String, args: JSONObject): JSONObject {
            calls += method to args
            if (method == "dsh.delegate" && args.optString("task") == "boom") throw IllegalStateException("no worker for you")
            return JSONObject().put("handed_over", true).put("worker", "7")
        }
        private fun inside(worker: String, path: String): File {
            require(worker == "7") { "There is no worker #$worker." }
            val base = root.canonicalFile
            val f = File(base, path).canonicalFile
            require(f == base || f.path.startsWith(base.path + File.separator)) { "$path is not inside worker #$worker's folder." }
            return f
        }
        override fun list(worker: String, path: String): JSONObject {
            val d = inside(worker, path)
            require(d.isDirectory) { "$path is not a folder." }
            val arr = org.json.JSONArray()
            d.listFiles()!!.sortedBy { it.name }.forEach { arr.put(JSONObject().put("name", it.name).put("dir", it.isDirectory).put("size", it.length())) }
            return JSONObject().put("entries", arr)
        }
        override fun read(worker: String, path: String, offset: Long, length: Int): PeerRemote.Chunk {
            val f = inside(worker, path)
            require(f.isFile) { "$path is not a file." }
            java.io.RandomAccessFile(f, "r").use { r ->
                r.seek(offset)
                val buf = ByteArray(length)
                val n = r.read(buf).coerceAtLeast(0)
                return PeerRemote.Chunk(buf.copyOf(n), f.length(), offset + n >= f.length())
            }
        }
    }

    /** The phone's channel to the Mac and back, delivered synchronously like a frame arriving. */
    private class Wire(val server: PeerRemote.Server, val client: PeerRemote.Client) {
        val phone = PeerProtocol.Peer("phone-id", "Phone", ByteArray(4))
        val mac = PeerProtocol.Peer("mac-id", "Mac", ByteArray(4))
        inner class Side(val seenAs: PeerProtocol.Peer, val deliver: (JSONObject, Side) -> Unit) : PeerChannel {
            lateinit var back: Side
            @Volatile var open = true
            override val peer get() = seenAs
            override val isOpen get() = open
            override fun send(msg: JSONObject): Boolean { if (!open) return false; deliver(JSONObject(msg.toString()), back); return true }
            override fun close() { open = false }
        }
        // phoneToMac is the phone's handle: sending reaches the Mac's server, which replies on its own handle.
        val phoneToMac = Side(mac) { m, macSide -> server.onMessage(macSide, m) }
        val macToPhone = Side(phone) { m, _ -> client.onMessage(m) }
        init { phoneToMac.back = macToPhone; macToPhone.back = phoneToMac }
    }

    private fun rig(allowed: Boolean = true, dir: File = Files.createTempDirectory("worker7").toFile()): Triple<Mac, PeerRemote.Client, Wire> {
        val mac = Mac(dir)
        val server = PeerRemote.Server({ mac }, { allowed })
        lateinit var wire: Wire
        val client = PeerRemote.Client { wire.phoneToMac }
        wire = Wire(server, client)
        return Triple(mac, client, wire)
    }

    @Test fun theMacCarriesTheFiveMethodsStraightToItsAgent() {
        val (mac, phone, _) = rig()
        val r = phone.call("mac-id", "dsh.delegate", JSONObject().put("task", "write a report"))
        assertEquals("7", r.getString("worker"))
        assertEquals(listOf("dsh.delegate"), mac.calls.map { it.first })
        assertEquals("write a report", mac.calls[0].second.getString("task"))
        for (m in PeerRemote.METHODS) phone.call("mac-id", m, JSONObject())
        assertEquals(1 + PeerRemote.METHODS.size, mac.calls.size)
    }

    @Test fun nothingButThoseMethodsIsCarried() {
        val (mac, phone, _) = rig()
        for (m in listOf("screen.tap", "shell.run", "note.remember", "peer.ask", "dsh.delegate/../x")) {
            try { phone.call("mac-id", m, JSONObject()); fail("$m should be refused") } catch (e: IllegalStateException) {
                assertTrue(e.message, e.message!!.contains("not a method this computer carries"))
            }
        }
        assertTrue("none of them reached the agent", mac.calls.isEmpty())
    }

    @Test fun withoutTheOwnersSwitchNothingRuns() {
        val (mac, phone, _) = rig(allowed = false)
        try { phone.call("mac-id", "dsh.delegate", JSONObject().put("task", "x")); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("has not allowed"))
        }
        try { phone.list("mac-id", "7", ""); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("has not allowed")) }
        try { phone.get("mac-id", "7", "a.txt", 0); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("has not allowed")) }
        assertTrue(mac.calls.isEmpty())
    }

    @Test fun anAgentThatIsNotThereIsSaidSo() {
        val (mac, phone, _) = rig()
        mac.up = false
        try { phone.call("mac-id", "dsh.status", JSONObject()); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("no agent on this computer"))
        }
    }

    @Test fun anErrorFromTheAgentReachesThePhonesModelAsText() {
        val (_, phone, _) = rig()
        try { phone.call("mac-id", "dsh.delegate", JSONObject().put("task", "boom")); fail() } catch (e: IllegalStateException) {
            assertEquals("no worker for you", e.message)
        }
    }

    @Test fun aFolderIsListedAndAFileComesInPiecesOnlyWhenAsked() {
        val dir = Files.createTempDirectory("worker7").toFile()
        val big = ByteArray(PeerRemote.CHUNK * 2 + 123) { (it % 251).toByte() }
        File(dir, "big.bin").writeBytes(big)
        File(dir, "notes.txt").writeText("hello 你好")
        File(dir, "sub").mkdirs(); File(dir, "sub/deep.md").writeText("# deep")
        val (_, phone, _) = rig(dir = dir)

        val ls = phone.list("mac-id", "7", "")
        assertEquals(listOf("big.bin", "notes.txt", "sub"), (0 until ls.getJSONArray("entries").length()).map { ls.getJSONArray("entries").getJSONObject(it).getString("name") })
        assertEquals(big.size.toLong(), ls.getJSONArray("entries").getJSONObject(0).getLong("size"))
        assertEquals("deep.md", phone.list("mac-id", "7", "sub").getJSONArray("entries").getJSONObject(0).getString("name"))

        // The big file, as a phone would stream it: piece after piece until the Mac says it ended.
        val got = java.io.ByteArrayOutputStream()
        var off = 0L
        var pieces = 0
        while (true) {
            val c = phone.get("mac-id", "7", "big.bin", off)
            got.write(c.bytes); off += c.bytes.size; pieces++
            assertEquals(big.size.toLong(), c.size)
            if (c.eof) break
        }
        assertEquals(3, pieces)
        assertArrayEquals(big, got.toByteArray())
        // A range from the middle, without having read what is before it: what a preview that jumps does.
        assertArrayEquals(big.copyOfRange(1000, 1100), phone.get("mac-id", "7", "big.bin", 1000, 100).bytes)
        assertEquals("hello 你好", String(phone.get("mac-id", "7", "notes.txt", 0).bytes, Charsets.UTF_8))
    }

    @Test fun nothingOutsideTheWorkersFolderCanBeListedOrRead() {
        val parent = Files.createTempDirectory("workers").toFile()
        val dir = File(parent, "7").also { it.mkdirs() }
        File(parent, "secret.txt").writeText("not yours")
        File(dir, "ok.txt").writeText("fine")
        // A link inside the folder that points out of it.
        runCatching { Files.createSymbolicLink(File(dir, "escape").toPath(), File(parent, "secret.txt").toPath()) }
        val (_, phone, _) = rig(dir = dir)
        for (bad in listOf("../secret.txt", "sub/../../secret.txt", "escape")) {
        try { phone.get("mac-id", "7", bad, 0); fail("$bad should be refused") } catch (e: IllegalStateException) {
        assertTrue("$bad: ${e.message}", e.message!!.contains("not inside"))
        }
        }
        // An absolute path is read as relative to the folder (File(base, "/etc/passwd") is base/etc/passwd): it cannot
        // reach /etc, and there is no such file in the folder.
        try { phone.get("mac-id", "7", "/etc/passwd", 0); fail() } catch (e: IllegalStateException) {
        assertTrue(e.message, e.message!!.contains("not a file") || e.message!!.contains("not inside"))
        }
        try { phone.list("mac-id", "7", ".."); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not inside")) }
        assertEquals("fine", String(phone.get("mac-id", "7", "ok.txt", 0).bytes))
    }

    @Test fun aDroppedLinkIsAnHonestErrorNotAHandover() {
        val (mac, phone, wire) = rig()
        wire.phoneToMac.open = false
        try { phone.call("mac-id", "dsh.delegate", JSONObject().put("task", "x")); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("cannot be reached"))
        }
        assertTrue(mac.calls.isEmpty())
    }

    @Test fun onlyAnOwnDeviceCanBeGivenTheSwitch() {
        val mem = object : net.kuafuai.andee.config.NotebookStore {
            var s: String? = null
            override fun read() = s
            override fun write(plain: String): Boolean { s = plain; return true }
            override fun wipe() { s = null }
        }
        val s = PeerStore(mem) { "Mac" }
        val phone = PeerProtocol.newIdentity("phone-1", "Phone")
        s.add(PeerProtocol.Peer("phone-1", "Phone", phone.publicBytes))
        assertFalse("a new pairing is a friend, and off", s.dshAllowed("phone-1"))
        s.setDshAllowed("phone-1", true)
        assertFalse("a friend's Andee never gets it, whatever was written", s.dshAllowed("phone-1"))
        s.setTrust("phone-1", PeerStore.Trust.OWN)
        s.setDshAllowed("phone-1", true)
        assertTrue(s.dshAllowed("phone-1"))
        assertTrue("it survives a restart", PeerStore(mem) { "x" }.dshAllowed("phone-1"))
        s.setTrust("phone-1", PeerStore.Trust.FRIEND)
        assertFalse("filing it as a friend takes the switch away", s.dshAllowed("phone-1"))
        s.setTrust("phone-1", PeerStore.Trust.OWN)
        assertFalse("and it does not come back by itself", s.dshAllowed("phone-1"))
    }
}
