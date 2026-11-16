package net.kuafuai.andee.flow

import net.kuafuai.andee.config.Flow
import net.kuafuai.andee.config.FlowStep
import net.kuafuai.andee.screen.ScreenController
import org.json.JSONArray
import org.json.JSONObject

/**
 * The playback half of flows: walk a recorded [Flow] step by step, locally,
 * without a model in the loop. Every step is gated before it runs — a step
 * that cannot re-locate its element, or lands on a different app, or provably
 * changed nothing when the recording says it changed something, stops the
 * whole flow and hands back a structured failure the model can read, fix and
 * resume from ([run] with [fromStep]).
 *
 * The result is a **report**, not a boolean. A stop carries the step trace,
 * the screen as it stands right now and the step to resume from; a completed
 * run carries the final screen and `need_review`, because "every tap landed"
 * is not the same as "the job got done" — the brain has to look at the result.
 * That report is what turns a dead replay into a repair the assistant owns.
 *
 * Human steps pause the machine entirely: the note is spoken, and the flow
 * waits for the screen to change — the user's own hand is the confirmation.
 * An `ai` step is the other kind of pause: the script deliberately hands a
 * job it cannot record (read a CAPTCHA, decide between dialogs) back to the
 * model. That is a `paused` report, NOT a failure — nothing is broken, no
 * repair is due, and the run is not counted as a failed one. The model does
 * the job with its own tools, then `resume_flow` from the next step.
 * Nothing here ever guesses its way past a failure, and nothing records or
 * plays inside payment surfaces (the dispatcher refuses those turns up front).
 */
class FlowPlayer(
    private val screen: ScreenController,
    private val say: (text: String, listen: Boolean) -> Boolean,
    /**
     * Opens an app for a `launch` step. A callback rather than a Context so
     * this class stays screen-only; the dispatcher wires it to `device.launch`
     * and the card path wires it to the same controller. Returns that tool's
     * JSONObject, whose `error` (if any) becomes a step failure.
     */
    private val launch: (pkg: String, label: String) -> JSONObject = { _, _ ->
        error("a launch step needs a device launcher")
    },
) {

    /**
     * A step failed; the flow stopped. [step] is 1-based. [cls] is the
     * machine's classification of WHY — it rides in the report as
     * `failure_class` so the model does not have to guess between "the screen
     * is wrong" and "the script is missing a step", a guess that has already
     * produced a wrong repair once. One of: `launch_blocked` (a launch step
     * could not bring its app up), `wrong_screen` (the gate refused: the
     * foreground app is not the recorded one), `stale_anchor` (the element is
     * not where it was recorded), `screen_frozen` (it changed at record time
     * and did not now), `user_absent` (a human step timed out), `bad_step`
     * (the step itself cannot run: no text, no package, unknown kind).
     */
    class StepFailure(val step: Int, val reason: String, val cls: String = "bad_step") : Exception(reason)

    /**
     * An `ai` step handed the run back on purpose. Unlike [StepFailure],
     * nothing is wrong: the script simply cannot record this part. [step] is
     * 1-based, [instruction] is what the model is being asked to do.
     */
    class StepPause(val step: Int, val instruction: String) : Exception(instruction)

    /**
     * Run [flow] from [fromStep] (1-based; 1 = the whole flow). [replaces]
     * maps a 1-based step number to a text that overrides the recorded one —
     * how a parameterised flow gets its arguments (a different title, a
     * different file). Keys naming steps that are not `type` are ignored.
     *
     * Success: `{ok:true, done:true, trace, final_screen, need_review:true}`.
     * Stop:    `{ok:false, failed_at, reason, trace, current_screen,
     *           resume_from, hint}` — enough to take over without asking the
     *           user what happened.
     * Pause:   `{ok:true, done:false, paused:true, paused_at, instruction,
     *           trace, current_screen, resume_from, flow_handoff_required:true}`
     *           — an `ai` step. Not a failure: do not repair the script, do
     *           the job and continue from `resume_from`.
     */
    fun run(flow: Flow, fromStep: Int = 1, replaces: Map<Int, String> = emptyMap()): JSONObject {
        val steps = flow.steps
        val trace = JSONArray()
        // A resume whose from_step is one past the end (refit(mode=done) on
        // the last step) has already done those steps — the report must not
        // claim it ran zero.
        var done = (fromStep - 1).coerceAtMost(steps.size)
        val report = try {
            for (i in (fromStep - 1) until steps.size) {
                val n = i + 1
                val row = try {
                    executeStep(steps[i], n, replaces[n])
                } catch (p: StepPause) {
                    // A deliberate hand-off, not a broken step. It must reach
                    // the outer catch unchanged; wrapping it in StepFailure
                    // would turn the script's own decision into a "repair me".
                    throw p
                } catch (f: StepFailure) {
                    throw f
                } catch (t: Exception) {
                    // A gate inside ScreenController threw (a refused tap on a
                    // blind app, a gesture error) — same shape, same stop.
                    throw StepFailure(n, t.message ?: t.javaClass.simpleName)
                }
                trace.put(row.put("step", n).put("kind", steps[i].kind).put("status", "done"))
                done = n
            }
            FlowRuntime.clearHeals(flow.name)
            JSONObject()
                .put("ok", true)
                .put("done", true)
                .put("flow", flow.name)
                .put("steps_done", steps.size)
                .put("total_steps", steps.size)
                .put("trace", trace)
                .put("final_screen", screenState())
                .put("need_review", true)
                .put(
                    "review_hint",
                    "Every step landed. This is NOT proof the job succeeded — look at final_screen (and " +
                        "get_screen_element / a screenshot if it is ambiguous) and confirm the result the user " +
                        "wanted. If it is wrong, deal with it now, and write the repair back into the flow " +
                        "(refit_flow / edit_flow, including edit_flow's insert for a missing launch step) so " +
                        "next time it does not need you.",
                )
        } catch (f: StepFailure) {
            JSONObject()
                .put("ok", false)
                .put("done", false)
                .put("flow", flow.name)
                .put("failed_at", f.step)
                .put("reason", f.reason)
                // WHAT kind of stop this is, decided by the machine — the
                // model must act on it instead of re-diagnosing, because the
                // wrong diagnosis has already produced a wrong repair (a
                // duplicate launch step inserted into a flow whose opening
                // launch had simply been blocked).
                .put("failure_class", f.cls)
                .put("steps_done", done)
                .put("total_steps", steps.size)
                .put("flow_repair_required", true)
                .put("trace", trace)
                .put("current_screen", screenState())
                // The device's suggestion: re-run the step that stopped. The
                // brain may choose failed_at + 1 instead (when it performed
                // that step by hand) — refit_flow(mode=done) says which.
                .put("resume_from", f.step)
                .put("heal_attempt", FlowRuntime.healCount(flow.name, f.step))
                .put("hint", hintFor(f.cls))
        } catch (p: StepPause) {
            // The script asked for the model here. `resume_from` skips this
            // step: the model is about to do it by hand, so replaying it
            // would only pause again forever. No repair flag, no failed run —
            // the flow is healthy, it just needs a head for one step.
            JSONObject()
                .put("ok", true)
                .put("done", false)
                .put("paused", true)
                .put("flow", flow.name)
                .put("paused_at", p.step)
                .put("instruction", p.instruction)
                .put("steps_done", done)
                .put("total_steps", steps.size)
                .put("flow_handoff_required", true)
                .put("trace", trace)
                .put("current_screen", screenState())
                .put("resume_from", p.step + 1)
                .put(
                    "hint",
                    "This is NOT a failure — step ${p.step} is an `ai` step that the script deliberately " +
                        "handed to you. Do the job its `instruction` describes IN THIS TURN, with your own " +
                        "tools (get_screen_element / take_screenshot / submit_input / taps). Look at " +
                        "current_screen for where the flow left off. When it is done, call resume_flow with " +
                        "from_step=resume_from to run the remaining steps, then check final_screen. " +
                        "Do NOT repair the flow, do NOT edit_flow, do NOT restart from step 1, do NOT " +
                        "re-record, and do NOT count this as a failed run. If you genuinely cannot do what " +
                        "the instruction asks, say what blocked you and which step the flow is waiting at.",
                )
        }
        FlowRuntime.record(report)
        return report
    }

    /**
     * What is on the screen right now — the evidence for both a repair and
     * the end-of-run review. The tree dump is deliberately taken at the very
     * end: its settle pass waits out the animation the last tap started, so
     * the visible lines describe where the flow actually landed.
     *
     * Cheap on failure too, and worth it: without it the model's first move
     * after a stop is another tool call just to find out where it is.
     */
    private fun screenState(): JSONObject {
        val fp = runCatching { screen.screenFingerprint() }.getOrDefault(JSONObject())
        val out = JSONObject()
            .put("pkg", fp.optString("pkg"))
            .put("sig", fp.optInt("sig"))
            .put("blind", fp.optBoolean("blind"))
        runCatching {
            val tree = screen.dumpUiTree(verbose = false, withShot = false, capture = { JSONObject() })
            tree.optString("pkg").takeIf { it.isNotEmpty() }?.let { out.put("pkg", it) }
            out.put("nodes_useful", tree.optInt("nodes_useful"))
            val lines = tree.optString("elements").lines()
                .filter { it.isNotBlank() }.take(24)
            if (lines.isNotEmpty()) {
                out.put("visible", JSONArray().apply { lines.forEach { put(it.take(200)) } })
            }
        }
        return out
    }

    private fun executeStep(step: FlowStep, n: Int, replace: String?): JSONObject = when (step.kind) {
        "tap", "long_press" -> executeTap(step, n)
        "swipe" -> executeSwipe(step, n)
        "type" -> executeType(step, n, replace)
        "wait" -> executeWait(step, n)
        "human" -> executeHuman(step, n)
        "launch" -> executeLaunch(step, n)
        // No gesture, no gate: the step IS the hand-off. Its note is the
        // instruction; the model reads the screen itself when it wakes.
        "ai" -> throw StepPause(n, step.note)
        else -> throw StepFailure(n, "unknown step kind '${step.kind}'")
    }

    /**
     * The one gate that applies to every step kind: a step recorded in
     * another app must not run blindly in whatever is on the screen now —
     * that is how a flow sends a message to the wrong person. The recorded
     * package has to match, when the device can name it.
     */
    private fun gate(step: FlowStep, n: Int) {
        if (step.pkg.isNotEmpty()) {
            val now = livePkg()
            if (now.isNotEmpty() && now != step.pkg) {
                throw StepFailure(
                    n,
                    "the screen is not where this step was recorded: recorded on ${step.pkg}, now on $now. " +
                        "Bring the app back to where the flow expects it, or take over by hand.",
                    "wrong_screen",
                )
            }
        }
    }

    /**
     * [ScreenController.currentPkg] is a CACHED `lastDumpPkg`: it can be null
     * — or, worse, still say "home" after an app has already come forward.
     * A null used to mean "no opinion", so the gate waved the flow through and
     * its taps landed on the launcher; a stale value made a successful
     * `launch` step report failure. Read the LIVE window first, from
     * [screenFingerprint]'s `activeRoot()`, and only fall back to the cache
     * when the accessibility layer hands us no root at all. Empty still means
     * "the device cannot name it" — absence of evidence, not a verdict.
     */
    private fun livePkg(): String =
        runCatching { screen.screenFingerprint().optString("pkg") }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: screen.currentPkg().orEmpty()

    /**
     * The step that makes a flow runnable from anywhere: open the app the
     * rest of the steps were recorded in. It is the ONE step that must not
     * pass through [gate] — changing the foreground app is its whole job.
     *
     * Cold starts are slow, so poll briefly for the target package rather
     * than judging on the first frame. A mismatch is a stop, not a guess:
     * tapping a recorded coordinate on the launcher is how a flow does
     * something to the wrong app. When the device cannot name the foreground
     * app at all, we let it pass — absence of evidence, as in [gate].
     */
    private fun executeLaunch(step: FlowStep, n: Int): JSONObject {
        val pkg = step.pkg
        val label = step.note
        if (pkg.isEmpty() && label.isEmpty()) {
            throw StepFailure(n, "a launch step has neither a package nor a label to open", "bad_step")
        }
        val res = try {
            launch(pkg, label)
        } catch (f: StepFailure) {
            throw f
        } catch (t: Exception) {
            throw StepFailure(
                n,
                "could not launch ${pkg.ifEmpty { label }}: ${t.message ?: t.javaClass.simpleName}",
                "launch_blocked",
            )
        }
        res.optString("error").takeIf { it.isNotEmpty() }?.let {
            throw StepFailure(n, "could not launch ${pkg.ifEmpty { label }}: $it", "launch_blocked")
        }
        // The launch goes through our own pad Activity now, and a cold start
        // of a heavy app (Feishu measured ~10s) is slow: poll up to 15s for
        // the target package rather than judging on the first frames. A
        // mismatch after the full window is a stop, not a guess — see below.
        var now: String? = null
        val deadline = System.currentTimeMillis() + LAUNCH_SETTLE_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw StepFailure(n, "the launch wait was interrupted", "launch_blocked")
            }
            val p = livePkg()
            now = p
            if (p.isEmpty() || pkg.isEmpty() || p == pkg || p.startsWith("$pkg.") || pkg.startsWith("$p.")) break
        }
        val arrived = now.orEmpty()
        val mismatch = pkg.isNotEmpty() && arrived.isNotEmpty() && arrived != pkg &&
            !arrived.startsWith("$pkg.") && !pkg.startsWith("$arrived.")
        if (mismatch) {
            // One last opinion from the full tree before calling a launch a
            // failure. The fingerprint reads the ACTIVE window, which can
            // still be the launcher while the new activity is being posted.
            // A false "could not launch" makes the brain re-open the app and
            // hand-take-over a step that in fact succeeded.
            val confirm = runCatching {
                screen.dumpUiTree(verbose = false, withShot = false, capture = { JSONObject() })
                    .optString("pkg")
            }.getOrNull().orEmpty()
            if (confirm != pkg && !confirm.startsWith("$pkg.")) {
                throw StepFailure(
                    n,
                    "asked to launch $pkg but the screen is on ${confirm.ifEmpty { arrived }}. The app may " +
                        "not be installed, may have been blocked by the system, or a dialog is in the way — " +
                        "take over with launch_app / get_screen_element, then resume_flow from this step.",
                    "launch_blocked",
                )
            }
            return JSONObject().put("launched", pkg.ifEmpty { label }).put("now_pkg", confirm)
        }
        return JSONObject().put("launched", pkg.ifEmpty { label }).put("now_pkg", arrived)
    }

    private fun executeTap(step: FlowStep, n: Int): JSONObject {
        gate(step, n)
        val hasAnchor = step.viewId != null || step.bounds.isNotEmpty()
        if (hasAnchor) {
            val res = if (step.kind == "long_press") {
                screen.longPressByAnchor(
                    step.viewId, step.text, step.bounds, step.pkg,
                    step.x, step.y, step.durationMs.takeIf { it > 0 } ?: 600L,
                )
            } else {
                screen.tapByAnchor(step.viewId, step.text, step.bounds, step.pkg, step.x, step.y)
            }
            // The whole point of replay is that each step is verified, not
            // remembered blindly: an element that is not where it was
            // recorded is a stop, never a guess.
            if (!res.optBoolean("relocated")) {
                throw StepFailure(
                    n,
                    "the element ('${step.text.ifEmpty { step.viewId ?: step.bounds }}') is not where this step " +
                        "recorded it. Stop here and hand the rest to the model — do not tap the remembered spot.",
                    "stale_anchor",
                )
            }
            checkChanged(step, res, n)
            return JSONObject().put("relocated", true)
                .put("screen_changed", if (res.has("screen_changed")) res.optBoolean("screen_changed") else JSONObject.NULL)
        } else {
            // Coordinate-only step: gated on the package (done above) and
            // judged by whether the screen reacted. Aim in 0-1000 — the
            // recorded relative spot — so it lands on any resolution.
            val res = if (step.kind == "long_press") {
                screen.longPressAim(x = step.relX, y = step.relY, on = null, cell = null, part = null, durationMs = step.durationMs)
            } else {
                screen.tapAim(x = step.relX, y = step.relY, on = null, cell = null, part = null)
            }
            checkChanged(step, res, n)
            return JSONObject().put("relocated", false)
                .put("screen_changed", if (res.has("screen_changed")) res.optBoolean("screen_changed") else JSONObject.NULL)
        }
    }

    private fun executeSwipe(step: FlowStep, n: Int): JSONObject {
        gate(step, n)
        val res = screen.swipeNorm(step.relX, step.relY, step.relX2, step.relY2, step.durationMs, fling = false, screens = null)
        checkChanged(step, res, n)
        return JSONObject()
    }

    private fun executeType(step: FlowStep, n: Int, replace: String?): JSONObject {
        gate(step, n)
        val text = replace ?: step.note
        if (text.isEmpty()) throw StepFailure(n, "a type step has no text", "bad_step")
        val res = screen.typeText(text, null)
        if (res.has("performed") && !res.optBoolean("performed")) {
            throw StepFailure(
                n,
                "typing did not land — the input field is probably not focused. " +
                    "Take over: focus the field, then resume_flow from this step.",
                "bad_step",
            )
        }
        return JSONObject().put("typed", text.length)
    }

    private fun executeWait(step: FlowStep, n: Int): JSONObject {
        // No gate: a wait performs no touch, so there is nothing to land on
        // the wrong app. Its recorded package is whatever was on screen when
        // the wait was noted — often stale (measured: a wait after a launch
        // kept the PREVIOUS app's package, and gating on it stopped every
        // replay at step 2). Waiting anywhere waits the same.
        try {
            Thread.sleep(step.durationMs.coerceIn(1, FlowRecorder.WAIT_MAX_MS))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw StepFailure(n, "the wait was interrupted", "bad_step")
        }
        return JSONObject()
    }

    /**
     * The user's own hand is the step. Speak the note and watch the screen:
     * any change of fingerprint — a tap, a swipe, a page moving — counts as
     * "they did it". Timeout is a stop, and the model decides what to do.
     */
    private fun executeHuman(step: FlowStep, n: Int): JSONObject {
        gate(step, n)
        say(step.note, false)
        val before = screen.screenFingerprint()
        val startSig = before.optInt("sig")
        val startPkg = before.optString("pkg")
        val deadline = System.currentTimeMillis() + step.humanTimeoutSec.coerceIn(5, 300) * 1000L
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw StepFailure(n, "the wait for the user was interrupted", "bad_step")
            }
            val now = screen.screenFingerprint()
            if (now.optInt("sig") != startSig || now.optString("pkg") != startPkg) {
                return JSONObject().put("human_confirmed", true)
            }
        }
        throw StepFailure(
            n,
            "the user did not act in ${step.humanTimeoutSec}s. " +
                "Ask whether they are done, then resume_flow from this step once the screen is where it should be.",
            "user_absent",
        )
    }

    private fun checkChanged(step: FlowStep, res: JSONObject, n: Int) {
        // Only "recorded yes, now no" is a stop — the screen moved at record
        // time and stands still now, so the tap cannot have landed. The other
        // way round is noise (a toast, a clock), not a lie.
        if (step.changedAfter == true && res.has("screen_changed") && !res.optBoolean("screen_changed")) {
            throw StepFailure(
                n,
                "the tap went through but no visual change was detected, and it did change when this step " +
                    "was recorded. Something is different (an overlay, the element gone, or an action that " +
                    "now completes in place) — stop and look rather than push on. This is not proof the tap missed.",
                "screen_frozen",
            )
        }
    }

    /**
     * The guidance that rides with a failure report, keyed by [failureClass].
     * One hint per class: the machine already knows WHICH kind of stop this
     * is, and the model acting on that beats the model re-diagnosing it —
     * a launch blocked by the system once got misread as "the script is
     * missing its opening step" and the fix that followed would have put a
     * second launch step in front of the real one.
     */
    private fun hintFor(failureClass: String): String = when (failureClass) {
        "launch_blocked" ->
            "The flow HAS a launch step and it did NOT bring its app up. This is NOT a missing opening step — " +
                "do NOT insert another launch step. Read the reason: if it names Xiaomi's security center or a " +
                "chained-launch confirmation (小米安全中心 / 允许), the ROM popped a confirmation dialog for " +
                "this launch. launch_app taps its 「允许」 button on its own — only when the error still says " +
                "the dialog was not tapped, tell the user in one sentence to tap 「允许」 themselves, and do " +
                "NOT loop launch_app: retrying pops the same dialog. Otherwise take over in THIS turn: " +
                "launch_app (if the app still will not come up, system_action home then launch_app — a " +
                "foreground start is not blocked), confirm it is on screen, then resume_flow from " +
                "failed_at + 1 (the launch is already done by your hand). Do not restart from step 1 and do " +
                "not suggest re-recording. After the rest runs, check final_screen. If it still will not come " +
                "up after two tries, report the flow, the step, what you tried and what the user must do."
        else ->
            "Take over this step in THIS turn. Do not ask the user what happened, do not restart from " +
                "step 1, and do not suggest re-recording. The failure_class names what kind of stop this is; " +
                "act on it: wrong_screen → the foreground app is not the recorded one, fix the screen with " +
                "your own tools then resume_flow from failed_at; stale_anchor → get_screen_element, refit_flow " +
                "that step with the fresh eid, then use its resume_from; screen_frozen → look at the screen, " +
                "if the step did its job treat it as done and resume from failed_at + 1; user_absent → the " +
                "user did not act in time, ask whether they are done then resume_flow from failed_at; " +
                "bad_step → the step itself cannot run, edit_flow drop or retype it, then resume_flow. " +
                "The FLOW missing its opening step is the LAST reading, and only when get_flow shows step 1 " +
                "is NOT already a launch step (a tap/wait recorded with the app already open): then " +
                "edit_flow with insert:[{\"before\":1,\"kind\":\"launch\",\"pkg\":\"…\"}] " +
                "(list_installed_apps for the package) and get_flow again because step numbers shifted. " +
                "If step 1 IS already a launch step, this stop is not a missing opening — see the other " +
                "classes instead. After the rest of the flow runs, check final_screen. If a repair cannot be " +
                "made after two tries, report the flow, the step, the reason, what you tried and what the " +
                "user must do — never silently give up and never re-record."
    }

    companion object {
        /**
         * How long a launch step may wait for its app to reach the foreground.
         * The launch goes through our own pad Activity, and a cold start of a
         * heavy app (Feishu measured ~10s on the test tablet) is slow; judging
         * on the first frames was what turned a successful launch into a
         * false "could not launch".
         */
        private const val LAUNCH_SETTLE_MS = 15_000L
    }
}
