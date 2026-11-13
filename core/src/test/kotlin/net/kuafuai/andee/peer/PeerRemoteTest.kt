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
        var cancelReply = false
        override fun available() = up
        override fun call(method: String, args: JSONObject): JSONObject {
        calls += method to args
        if (method == "dsh.cancel" && cancelReply) return JSONObject().put("cancelled", true).put("worker", args.optString("id"))
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

    private fun rigWith(agent: PeerRemote.Agent): Triple<PeerRemote.Agent, PeerRemote.Client, Wire> {
        val server = PeerRemote.Server({ agent }, { true })
        lateinit var wire: Wire
        val client = PeerRemote.Client { wire.phoneToMac }
        wire = Wire(server, client)
        return Triple(agent, client, wire)
    }

    private fun rig(allowed: Boolean = true, dir: File = Files.createTempDirectory("worker7").toFile(), cancelReply: Boolean = false): Triple<Mac, PeerRemote.Client, Wire> {
    val mac = Mac(dir).also { it.cancelReply = cancelReply }
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

    @Test fun aFileIsFetchedPieceByPieceToDisk_andAHalfFileIsNeverKept() {
        val dir = Files.createTempDirectory("worker7").toFile()
        val big = ByteArray(PeerRemote.CHUNK * 2 + 77) { (it % 241).toByte() }
        File(dir, "report.pdf").writeBytes(big)
        val (mac, phone, wire) = rig(dir = dir)
        val inbox = Files.createTempDirectory("inbox").toFile()
        val host = RemoteAgentHost(phone, null, { "mac-id" }, inbox)

        val r = host.dispatch("dsh.fetch", JSONObject().put("id", "7").put("path", "report.pdf")) as JSONObject
        assertEquals(3, r.getInt("pieces"))
        assertEquals(big.size.toLong(), r.getLong("size"))
        assertArrayEquals(big, File(r.getString("path")).readBytes())
        assertTrue("no half file left beside it", File(inbox, "7").listFiles()!!.none { it.name.endsWith(".part") })

        // The link drops after the first piece: what was written is thrown away, not left looking like the file.
        var served = 0
        lateinit var wire2: Wire
        val flaky = object : PeerRemote.Agent by mac {
        override fun read(worker: String, path: String, offset: Long, length: Int): PeerRemote.Chunk {
        val c = mac.read(worker, path, offset, length)
        if (++served == 2) wire2.phoneToMac.open = false   // the reply to this piece is lost with the link
        return c
        }
        }
        val (_, phone2, w2) = rigWith(flaky)
        wire2 = w2
        try { RemoteAgentHost(phone2, null, { "mac-id" }, inbox).dispatch("dsh.fetch", JSONObject().put("id", "7").put("path", "report.pdf")); fail("a dropped link must be an error") } catch (e: IllegalStateException) { }
        assertTrue(File(inbox, "7").listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun withNoComputerOfferingItsAgentThePhoneSaysSoAndSendsNothing() {
        val (mac, phone, _) = rig()
        val host = RemoteAgentHost(phone, null, { null }, Files.createTempDirectory("inbox").toFile())
        try { host.dispatch("dsh.delegate", JSONObject().put("task", "x")); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("none of the paired devices offers its agent"))
        }
        assertTrue(mac.calls.isEmpty())
    }

    @Test fun theToolsAreTheSameNamesAsTheMacsAndAllResolve() {
        val names = (0 until RemoteAgentTools.TOOLS.length()).map { RemoteAgentTools.TOOLS.getJSONObject(it).getString("name") }
        assertEquals(listOf("delegate_task", "delegate_status", "delegate_cancel", "read_worker_file", "list_worker_files", "fetch_worker_file"), names)
        for (n in names) assertTrue(n, RemoteAgentTools.methodOf(n) != null)
        for (i in 0 until RemoteAgentTools.TOOLS.length()) {
            val t = RemoteAgentTools.TOOLS.getJSONObject(i)
            assertEquals(RemoteAgentTools.methodOf(t.getString("name")), t.getString("method"))
            assertFalse("no Finder on a phone", t.getString("description").contains("Finder"))
        }
    }

    @Test fun aJobHandedToTheComputerIsATaskHere_waitsOnTheWorker_andTheReportAnswersIt() {
        val mem = object : net.kuafuai.andee.config.NotebookStore {
            var s: String? = null
            override fun read() = s
            override fun write(plain: String): Boolean { s = plain; return true }
            override fun wipe() { s = null }
        }
        val tasks = net.kuafuai.andee.config.Tasks(net.kuafuai.andee.config.NotebookBook(mem))
        val link = RemoteAgentTasks { tasks }
        val (mac, phone, _) = rig()
        val host = RemoteAgentHost(phone, link, { "mac-id" }, Files.createTempDirectory("inbox").toFile())

        // No task named: one is opened, from the user's turn, and it waits on the worker the computer returned.
        val r = host.dispatch("dsh.delegate", JSONObject().put("task", "write a report").put("title", "周报").put("for_task", ""), fromUser = true) as JSONObject
        val id = r.getString("task")
        val t = tasks.get(id)!!
        assertEquals("周报", t.what)
        assertEquals("user", t.from.kind)
        assertEquals(RemoteAgentTasks.ORIGIN, t.origin)
        assertEquals("7", t.waits.single { it.kind == "worker" }.name)
        assertFalse("the computer was never told about our task", mac.calls.single().second.has("for_task"))
        assertTrue(r.getString("note").contains(id))

        // The report answers the wait, and the record rides on the turn.
        val record = link.answered("7", "Files: out.md. done")
        assertTrue(record, record.contains("<<<TASK $id"))
        assertEquals("ready", tasks.get(id)!!.status)

        // A job from a turn the user did not start is the device's own, not the user's.
        val r2 = host.dispatch("dsh.delegate", JSONObject().put("task", "tidy up"), fromUser = false) as JSONObject
        assertEquals("self", tasks.get(r2.getString("task"))!!.from.kind)

        // A task the model names that is not open is refused before anything is sent.
        val before = mac.calls.size
        try { host.dispatch("dsh.delegate", JSONObject().put("task", "x").put("for_task", "T99")); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("no task T99")) }
        assertEquals("nothing reached the computer", before, mac.calls.size)
    }

    @Test fun stoppingAJobAnswersItsWaitAsSeen_soItDoesNotComeBackAndStartItAgain() {
        val mem = object : net.kuafuai.andee.config.NotebookStore {
            var s: String? = null
            override fun read() = s
            override fun write(plain: String): Boolean { s = plain; return true }
            override fun wipe() { s = null }
        }
        val tasks = net.kuafuai.andee.config.Tasks(net.kuafuai.andee.config.NotebookBook(mem))
        val (_, phone, _) = rig(cancelReply = true)
        val host = RemoteAgentHost(phone, RemoteAgentTasks { tasks }, { "mac-id" }, Files.createTempDirectory("inbox").toFile())
        val id = (host.dispatch("dsh.delegate", JSONObject().put("task", "long job"), fromUser = true) as JSONObject).getString("task")
        host.dispatch("dsh.cancel", JSONObject().put("id", "7"))
        val t = tasks.get(id)!!
        assertEquals("the wait is answered", false, t.waits.single { it.kind == "worker" }.open)
        assertTrue("and seen, so nobody is woken by it", t.waits.single { it.kind == "worker" }.seen)
        assertTrue(t.waits.single().answer.contains("Stopped"))
    }

    @Test fun aJobThatNeverStartedLeavesNoTaskLookingLikeWorkInHand() {
        val mem = object : net.kuafuai.andee.config.NotebookStore {
            var s: String? = null
            override fun read() = s
            override fun write(plain: String): Boolean { s = plain; return true }
            override fun wipe() { s = null }
        }
        val tasks = net.kuafuai.andee.config.Tasks(net.kuafuai.andee.config.NotebookBook(mem))
        val (_, phone, _) = rig()
        val host = RemoteAgentHost(phone, RemoteAgentTasks { tasks }, { "mac-id" }, Files.createTempDirectory("inbox").toFile())
        try { host.dispatch("dsh.delegate", JSONObject().put("task", "boom")); fail() } catch (e: IllegalStateException) { }
        assertTrue("the task it opened is let go again", tasks.active().isEmpty())
    }

    @Test fun aWorkersProgressReachesThePhoneAndEndsWhenItEnds() {
        RemoteProgress.clear()
        val s = RemoteProgress.State("7", "周报", System.currentTimeMillis() - 65_000, step = 4, recent = listOf("searching", "reading a page"),
            todos = listOf(RemoteProgress.Todo("collect", "completed"), RemoteProgress.Todo("write", "in_progress")), tokens = 3000, files = 1)
        val back = RemoteProgress.parse(JSONObject(s.toJson().toString()))
        assertEquals(s.step, back.step); assertEquals(s.recent, back.recent); assertEquals(2, back.todos.size); assertEquals("write", back.current?.text)
        RemoteProgress.update(back)
        assertEquals(listOf("7"), RemoteProgress.running().map { it.worker })
        val line = RemoteProgress.line(RemoteProgress.of("7")!!, System.currentTimeMillis(), zh = false)
        assertTrue(line, line.startsWith("step 4 · 1:") && line.endsWith("· write"))
        RemoteProgress.end("7")
        assertTrue(RemoteProgress.running().isEmpty())
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

    @Test fun theSwitchIsSavedAndSurvivesARestartAndAPairingAgain() {
        val mem = object : net.kuafuai.andee.config.NotebookStore {
            var s: String? = null
            override fun read() = s
            override fun write(plain: String): Boolean { s = plain; return true }
            override fun wipe() { s = null }
        }
        val phone = PeerProtocol.newIdentity("phone-1", "Phone")
        val s = PeerStore(mem) { "Mac" }
        s.add(PeerProtocol.Peer("phone-1", "Phone", phone.publicBytes))
        s.setTrust("phone-1", PeerStore.Trust.OWN)
        // The click: the card calls setDshAllowed and then reads the row again.
        s.setDshAllowed("phone-1", true)
        assertTrue(s.dshAllowed("phone-1"))
        assertTrue("a fresh process over the same file sees it", PeerStore(mem) { "x" }.dshAllowed("phone-1"))
        // Pairing the same phone again (new QR, same id) must not quietly turn it off.
        s.add(PeerProtocol.Peer("phone-1", "Phone", phone.publicBytes))
        assertTrue("trust is kept on a second pairing", s.trust("phone-1") == PeerStore.Trust.OWN)
        assertTrue("and so is the switch", s.dshAllowed("phone-1"))
    }
}
