package net.kuafuai.andee.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class TurnCancellationTest {
    @Test
    fun `new turn cancels queued tools before they can act`() {
        val turns = TurnCancellation()
        val queued = mutableListOf<Runnable>()
        val executor = Executor { queued += it }
        val old = turns.next()
        val actions = mutableListOf<String>()
        val first = turns.submit(old, executor) { actions += "old first" }
        val second = turns.submit(old, executor) { actions += "old second" }

        val latest = turns.next()
        val replacement = turns.submit(latest, executor) { actions += "new" }
        queued.forEach { it.run() }

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        replacement.get(1, TimeUnit.SECONDS)
        assertEquals(listOf("new"), actions)
    }

    @Test
    fun `new turn interrupts a tool even after its caller stopped waiting`() {
        val turns = TurnCancellation()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val hold = CountDownLatch(1)
        try {
            val old = turns.next()
            val tool = turns.submit(old, executor) {
                started.countDown()
                try {
                    hold.await()
                } catch (_: InterruptedException) {
                    interrupted.countDown()
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { tool.get(1, TimeUnit.MILLISECONDS) }

            val latest = turns.next()
            assertTrue(interrupted.await(2, TimeUnit.SECONDS))
            assertTrue(tool.isCancelled)
            assertEquals("new", turns.submit(latest, executor) { "new" }.get(2, TimeUnit.SECONDS))
        } finally {
            hold.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `registration after cancellation is cancelled immediately`() {
        val turns = TurnCancellation()
        val old = turns.next()
        turns.next()
        var cancelled = false
        turns.onCancel(old) { cancelled = true }.close()
        assertTrue(cancelled)
    }

    @Test
    fun `old cleanup cannot untrack the replacement`() {
        val turns = TurnCancellation()
        val old = turns.next()
        var oldStops = 0
        var newStops = 0
        val oldRegistration = turns.onCancel(old) { oldStops++ }
        val latest = turns.next()
        turns.onCancel(latest) { newStops++ }
        oldRegistration.close()
        turns.next()
        assertEquals(1, oldStops)
        assertEquals(1, newStops)
    }

    @Test
    fun `finished calls are not cancelled and old callbacks stay silent`() {
        val turns = TurnCancellation()
        val old = turns.next()
        var stops = 0
        var spoken = ""
        turns.onCancel(old) { stops++ }.close()
        val latest = turns.next()
        turns.ifCurrent(old) { spoken = "old" }
        assertEquals("", spoken)
        turns.ifCurrent(latest) { spoken = "new" }
        assertEquals("new", spoken)
        assertEquals(0, stops)
    }

    @Test
    fun `cancelled batch gets only its missing tool results`() {
        val history = mutableListOf(
            JSONObject().put("role", "assistant").put(
                "tool_calls", JSONArray()
                    .put(JSONObject().put("id", "first"))
                    .put(JSONObject().put("id", "second")),
            ),
            JSONObject().put("role", "tool").put("tool_call_id", "first").put("content", "done"),
        )
        completeCancelledToolCalls(history)
        assertEquals(3, history.size)
        assertEquals("second", history.last().getString("tool_call_id"))
        assertTrue(history.last().getString("content").contains("stopped"))
        completeCancelledToolCalls(history)
        assertEquals(3, history.size)
    }

    @Test
    fun `a plain conversation needs no tool cancellation messages`() {
        val history = mutableListOf(JSONObject().put("role", "user").put("content", "Open WeChat"))
        completeCancelledToolCalls(history)
        assertEquals(1, history.size)
    }

    @Test
    fun `a stop before HTTP registration prevents the request`() {
        val llm = LlmClient("http://127.0.0.1:1", "test", "test", false, "low")
        val failure = assertThrows(IOException::class.java) {
            llm.complete(emptyList(), JSONArray()) { true }
        }
        assertEquals("Canceled", failure.message)
    }
}
