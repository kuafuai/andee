package net.kuafuai.andee.peer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Two [PeerTasks] wired to each other through in-memory channels. The encrypted link has its own tests
 * ([PeerLinkTest]); this is only what travels over it — a task out, a report back, and the ways that must
 * fail loudly instead of pretending a job was handed over.
 */
class PeerTasksTest {

    private class Node(name: String, platform: String, realCan: Boolean = false) {
        val peer = PeerProtocol.Peer(name + "-id", name, ByteArray(4))
        val paired = mutableListOf<PeerProtocol.Peer>()
        val reachable = mutableSetOf<String>()
        val links = mutableMapOf<String, PeerChannel>()
        /** How to bring a link up to an id, when the test allows it. */
        val dial = mutableMapOf<String, () -> Unit>()
        val trust = mutableMapOf<String, PeerStore.Trust>()
        /** Devices this node offers its agent to: the owner's switch. */
        val offers = mutableSetOf<String>()
        val tasks = PeerTasks(object : PeerTasks.Net {
        override fun offersAgentTo(id: String) = id in offers
            override val platform = platform
            override val can = if (realCan) PeerTasks.canFor(platform) else "test $platform"
            override fun trust(id: String) = trust[id] ?: PeerStore.Trust.FRIEND
            override fun enabled() = true
            override fun paired() = paired.toList()
            override fun reachable(id: String) = id in reachable
            override fun channel(id: String) = links[id]
            override fun connect(id: String): Boolean { dial[id]?.invoke() ?: return false; return links[id]?.isOpen == true }
        })
    }

    /** One direction of a link: [from]'s handle on [to]. Delivery is synchronous, like a frame arriving. */
    private class Pipe(val to: Node, val peerSeenAs: PeerProtocol.Peer) : PeerChannel {
        lateinit var back: Pipe
        @Volatile var open = true
        override val peer get() = peerSeenAs
        override val isOpen get() = open
        override fun send(msg: JSONObject): Boolean {
            if (!open) return false
            to.tasks.onMessage(back, JSONObject(msg.toString()))
            return true
        }
        override fun close() { open = false; back.open = false }
    }

    private fun link(a: Node, b: Node) {
        val ab = Pipe(b, b.peer)   // a's channel: talks to b
        val ba = Pipe(a, a.peer)   // b's channel: talks to a
        ab.back = ba; ba.back = ab
        a.links[b.peer.id] = ab; b.links[a.peer.id] = ba
        a.tasks.onConnected(ab); b.tasks.onConnected(ba)
    }

    private fun pair(a: Node, b: Node) {
        a.paired += b.peer; b.paired += a.peer
        a.reachable += b.peer.id; b.reachable += a.peer.id
        a.dial[b.peer.id] = { link(a, b) }; b.dial[a.peer.id] = { link(b, a) }
    }

    private class Report(val task: String, val report: String, val done: Boolean, val trust: PeerStore.Trust)

    private class Asker : PeerTasks.Brain {
        val got = CountDownLatch(1)
        @Volatile var last: Report? = null
        override fun run(from: PeerProtocol.Peer, trust: PeerStore.Trust, text: String, reply: (String?) -> Unit, decline: (String) -> Unit) = false
        override fun answered(from: PeerProtocol.Peer, trust: PeerStore.Trust, task: String, report: String, done: Boolean) {
            last = Report(task, report, done, trust); got.countDown()
        }
    }

    /** Takes every task and answers it with [answer] once [release] is opened — or declines, with [declineWith]. */
    private class Doer(val answer: String?, val declineWith: String? = null) : PeerTasks.Brain {
        val release = CountDownLatch(1)
        @Volatile var heard: String? = null
        @Volatile var heardTrust: PeerStore.Trust? = null
        override fun run(from: PeerProtocol.Peer, trust: PeerStore.Trust, text: String, reply: (String?) -> Unit, decline: (String) -> Unit): Boolean {
            heard = text; heardTrust = trust
            Thread { release.await(5, TimeUnit.SECONDS); if (declineWith != null) decline(declineWith) else reply(answer) }.start()
            return true
        }
        override fun answered(from: PeerProtocol.Peer, trust: PeerStore.Trust, task: String, report: String, done: Boolean) {}
    }

    @Test
    fun aTaskGoesOutAndTheReportComesBack() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        val asker = Asker().also { phone.tasks.brain = it }
        val doer = Doer("[happy] Built it: ~/site/index.html [END]").also { mac.tasks.brain = it }

        val r = phone.tasks.dispatch("peer.ask", JSONObject().put("task", "make a landing page")) as JSONObject
        assertEquals("mac", r.getString("asked"))
        // Neither side said this is one of its own: a friend, both ways.
        assertEquals(PeerStore.Trust.FRIEND, doer.heardTrust)
        // The tool returned while the work is still going: it must not wait for it.
        assertEquals(1L, asker.got.count)
        assertTrue(doer.heard!!.contains("make a landing page"))

        doer.release.countDown()
        assertTrue(asker.got.await(5, TimeUnit.SECONDS))
        assertEquals("make a landing page", asker.last!!.task)
        // Face markers and the end tag are for the voice on the other side, not for this report.
        assertEquals("Built it: ~/site/index.html", asker.last!!.report)
        assertTrue(asker.last!!.done)
    }

    @Test
    fun aDeviceThatOffersItsAgentIsKnownToTheOther_andTakingItBackIsToo() {
        val mac = Node("mac", "mac"); val phone = Node("phone", "phone")
        pair(mac, phone)
        mac.trust[phone.peer.id] = PeerStore.Trust.OWN
        phone.trust[mac.peer.id] = PeerStore.Trust.OWN
        // Switch off (the default): the link comes up and the phone is told there is no agent for it.
        link(mac, phone)
        assertNull("nothing offered yet", phone.tasks.agentDevice())
        assertFalse(phone.tasks.offersAgent(mac.peer.id))
        // The owner turns it on at the Mac, with the link already up: the phone hears it at once.
        mac.offers += phone.peer.id
        mac.tasks.reannounce(phone.peer.id)
        assertEquals(mac.peer.id, phone.tasks.agentDevice())
        assertTrue(phone.tasks.offersAgent(mac.peer.id))
        // And back off.
        mac.offers -= phone.peer.id
        mac.tasks.reannounce(phone.peer.id)
        assertNull(phone.tasks.agentDevice())
    }

    @Test
    fun anOfferIsHeardOnTheNextLinkToo() {
        val mac = Node("mac", "mac"); val phone = Node("phone", "phone")
        pair(mac, phone)
        mac.offers += phone.peer.id          // switched on while the phone was away
        link(mac, phone)
        assertEquals(mac.peer.id, phone.tasks.agentDevice())
    }

    @Test
    fun anOfferedAgentIsACapabilityOfTheDevice_notAFriendsSkill() {
        val mac = Node("mac", "mac", realCan = true); val phone = Node("phone", "phone", realCan = true)
        pair(mac, phone)
        mac.trust[phone.peer.id] = PeerStore.Trust.OWN
        phone.trust[mac.peer.id] = PeerStore.Trust.OWN
        // Not offered: no paragraph, and the friend is described as before, coding agents and all.
        link(mac, phone)
        assertEquals("", phone.tasks.agentIndex())
        assertTrue(phone.tasks.dispatch("peer.list", JSONObject()).toString().contains("coding agents and a terminal"))

        mac.offers += phone.peer.id
        mac.tasks.reannounce(phone.peer.id)
        val para = phone.tasks.agentIndex()
        assertTrue(para, para.contains("delegate_task") && para.contains("It is your own, like a tool"))
        assertFalse("it is never told to ask a friend for it", para.contains("ask_friend"))
        // The friend's own line no longer claims the agent as that friend's skill, so nothing leads to asking it.
        val listed = phone.tasks.dispatch("peer.list", JSONObject()).toString()
        assertFalse(listed, listed.contains("coding agents"))
        assertTrue("what the Mac itself can still do is said", listed.contains("browser, files"))

        mac.offers -= phone.peer.id
        mac.tasks.reannounce(phone.peer.id)
        assertEquals("withdrawn: gone from the prompt", "", phone.tasks.agentIndex())
    }

    @Test
    fun theLinkComesUpOnDemandAndEachSideLearnsWhatTheOtherIs() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        mac.tasks.brain = Doer("ok")
        assertTrue(phone.links.isEmpty())
        phone.tasks.dispatch("peer.ask", JSONObject().put("task", "x"))
        assertEquals("mac", phone.tasks.platformOf(mac.peer.id))
        assertEquals("phone", mac.tasks.platformOf(phone.peer.id))
    }

    @Test
    fun aDeviceWithNoBrainRefusesAndTheAskerHearsWhy() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        val asker = Asker().also { phone.tasks.brain = it }
        phone.tasks.dispatch("peer.ask", JSONObject().put("task", "x"))
        assertTrue(asker.got.await(2, TimeUnit.SECONDS))
        assertFalse(asker.last!!.done)
        assertTrue(asker.last!!.report.contains("could not take"))
    }

    @Test
    fun anUnreachableDeviceIsAnErrorNotAHandover() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        phone.reachable.clear()
        try {
            phone.tasks.dispatch("peer.ask", JSONObject().put("task", "x"))
            fail("handed over to a device that is not there")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("Nothing was sent"))
        }
    }

    @Test
    fun withSeveralPairedTheDeviceMustBeNamed() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); val pad = Node("pad", "phone")
        pair(phone, mac); pair(phone, pad)
        try {
            phone.tasks.dispatch("peer.ask", JSONObject().put("task", "x")); fail()
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mac") && e.message!!.contains("pad"))
        }
        pad.tasks.brain = Doer("ok")
        val r = phone.tasks.dispatch("peer.ask", JSONObject().put("task", "x").put("device", "PAD")) as JSONObject
        assertEquals("pad", r.getString("asked"))
    }

    @Test
    fun aReportWaitsForTheLinkToComeBack() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        val asker = Asker().also { phone.tasks.brain = it }
        val doer = Doer("done").also { mac.tasks.brain = it }
        phone.tasks.dispatch("peer.ask", JSONObject().put("task", "long job"))

        // The phone walks out of range while the Mac works.
        phone.links.values.forEach { it.close() }; phone.links.clear(); mac.links.clear()
        mac.reachable.clear()
        doer.release.countDown()
        assertFalse(asker.got.await(1, TimeUnit.SECONDS))

        // It comes back and dials in: the waiting report goes out on that link.
        link(phone, mac)
        assertTrue(asker.got.await(5, TimeUnit.SECONDS))
        assertEquals("done", asker.last!!.report)
    }

    @Test
    fun aTurnCutOffThereReportsThatInsteadOfSuccess() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        val asker = Asker().also { phone.tasks.brain = it }
        val doer = Doer(null).also { mac.tasks.brain = it }
        phone.tasks.dispatch("peer.ask", JSONObject().put("task", "x"))
        doer.release.countDown()
        assertTrue(asker.got.await(5, TimeUnit.SECONDS))
        assertFalse(asker.last!!.done)
        assertTrue(asker.last!!.report.contains("cut off"))
    }

    @Test
    fun listingDialsADeviceThatIsHereSoItCanSayWhatItIs() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        val l = phone.tasks.dispatch("peer.list", JSONObject()) as JSONObject
        assertEquals("mac", l.getJSONArray("friends").getJSONObject(0).getString("is"))
    }

    @Test
    fun eachSideAppliesItsOwnTrust() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        mac.trust[phone.peer.id] = PeerStore.Trust.OWN
        val asker = Asker().also { phone.tasks.brain = it }
        val doer = Doer("ok").also { mac.tasks.brain = it }
        phone.tasks.dispatch("peer.ask", JSONObject().put("task", "x"))
        doer.release.countDown()
        assertTrue(asker.got.await(5, TimeUnit.SECONDS))
        // The Mac trusts the phone as its own; the phone never said the same of the Mac, so its report is a friend's.
        assertEquals(PeerStore.Trust.OWN, doer.heardTrust)
        assertEquals(PeerStore.Trust.FRIEND, asker.last!!.trust)
    }

    @Test
    fun anOwnerWhoSaysNoIsReportedAsNotDone() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        val asker = Asker().also { phone.tasks.brain = it }
        val doer = Doer("unused", declineWith = "the owner said no").also { mac.tasks.brain = it }
        phone.tasks.dispatch("peer.ask", JSONObject().put("task", "x"))
        doer.release.countDown()
        assertTrue(asker.got.await(5, TimeUnit.SECONDS))
        assertFalse(asker.last!!.done)
        assertEquals("the owner said no", asker.last!!.report)
    }

    @Test
    fun theListSaysWhatIsWaiting() {
        val phone = Node("phone", "phone"); val mac = Node("mac", "mac"); pair(phone, mac)
        mac.tasks.brain = Doer("ok")
        phone.tasks.dispatch("peer.ask", JSONObject().put("task", "order lunch"))
        val l = phone.tasks.dispatch("peer.list", JSONObject()) as JSONObject
        val row = l.getJSONArray("friends").getJSONObject(0)
        assertEquals("mac", row.getString("is"))
        assertEquals("test mac", row.getString("says_it_can"))
        assertTrue(row.getString("relation").contains("friend"))
        assertTrue(row.getJSONArray("waiting_on").getString(0).startsWith("order lunch"))
    }
}
