package net.kuafuai.andee.ui

import android.content.Intent
import android.net.Uri
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import net.kuafuai.andee.BodyCoreService
import net.kuafuai.andee.R
import net.kuafuai.andee.ScreenBodyService
import net.kuafuai.andee.config.FactoryReset
import net.kuafuai.andee.config.Notebook
import net.kuafuai.andee.config.VoiceConfig
import net.kuafuai.andee.device.SelfCheck
import net.kuafuai.andee.i18n.AppLocale

/**
 * The one screen in this app a user can reach while the app is not working.
 *
 * ### What this class used to be, and what it is now
 *
 * The checklist was a fullscreen page: this Activity folded the assistant away,
 * perked the ball, and drew the rows over its own blurred backdrop. It is now a
 * **card**, opened from `✓` in the assistant's control bar and rendered by
 * [SelfCheckUi] **inside the assistant's own window** — see that class for why
 * two buttons sitting next to each other have to open the same kind of surface.
 *
 * So on a working device this Activity is never *seen*: the icon starts it, it
 * hands the request to the service and finishes — see the next section. All the
 * drawing it used to do lives in [SelfCheckUi] now, and what is left here is the
 * case the card cannot cover. There is exactly one exception, and it is not a
 * door the user opened: the card hands this page the trips it cannot carry,
 * below.
 *
 * ### Why it still has to exist, and why it still has to be in the launcher
 *
 * Everything else here is an overlay owned by [BodyCoreService]. That used to
 * make this page the only way in *and* the only place that could explain why
 * there was no way in: the overlays belonged to an accessibility service, so
 * with accessibility off there was no ball, no card, and no surface on which to
 * describe the absence. The body no longer dies with that grant — a revoked
 * service now costs screen control and nothing else, and the ball stays up —
 * but that has not made this page redundant. It is still the only surface that
 * runs *before* the body does, and it is still the only one that can *render*
 * [net.kuafuai.andee.device.SelfCheck.Level.FAIL] on `accessibility` rather
 * than merely believe it: a red row about accessibility has to be drawn by
 * something the accessibility service is not holding.
 *
 * It also does the one job no other component can. It starts the body, and it
 * is the single context in the app allowed to claim the microphone type for it
 * — the platform refuses a microphone-typed foreground service created from the
 * background, and every other starter is background (the ROM handing
 * accessibility back, the notification's own action, boot). See
 * `BodyCoreService.promoteForMicrophone`. That is also why the launcher entry
 * matters: a door you can only reach from inside the broken room is not a door.
 *
 * ### The icon opens two doors, and the body decides which
 *
 * Being the launcher entry point does not make this page what the icon *means*.
 * With the body up, tapping the icon does what a long-press on the ball does —
 * `dispatcher.expand()` — and this Activity finishes without drawing a frame;
 * on a working device it is therefore still never *seen*, only started and
 * dropped. With the body down it falls through to the checklist, because there
 * is nothing to unfold and this is the only surface that can say why.
 *
 * One icon, two behaviours, and the branch is not a heuristic: [onCreate] asks
 * whether the ball is actually on screen — and asks in the one way that also
 * puts it there if the only thing wrong was a grant that arrived too late (see
 * `BodyCoreService.ensureOverlays`). It also has to allow for being a frame
 * early: starting the body is asynchronous, so a device that is perfectly well
 * can still read "no body yet" here. See the second branch in [onCreate]. All
 * of that is the load-bearing half of the original argument, unchanged — the
 * launcher entry exists so that a device whose assistant is not running still
 * has somewhere to go — and the other half still holds too: a device whose
 * assistant *is* running does not pay for the launcher's existence by being
 * taken to a diagnostic page every time the user taps the app.
 *
 * See `Theme.Body.Stage.NoPreview` for the other half of making the first door
 * invisible: without it the platform paints a black starting window for a page
 * that is about to finish, and "tap the icon, then the card grows" reads as
 * "tap the icon, the screen goes black, then the card grows".
 *
 * ### A host, not a second implementation
 *
 * All this class does now is give [SelfCheckUi] a `stage` to put the card in
 * instead of a window to make. That is the whole reason the look cannot drift:
 * there is one builder, and it is the same one the working device sees. The two
 * hosts differ in exactly two things — where the card goes, and whether we may
 * ask the compositor to blur — and both of those are [SelfCheckUi]'s to decide.
 *
 * ### And the carrier for the card's errands (2026-10-13)
 *
 * The one thing the *card* cannot do is survive a trip, so this page now comes
 * up in the middle of a working device after all: when a row on the card sends
 * the user to a system screen, the card hands the Intent over rather than
 * closing the list. See [showForErrand], which is where that argument lives —
 * including why the destination must be launched from here (it lands in *this*
 * page's task, so back has somewhere to return to) and why a rebuilt page must
 * not re-run it.
 *
 * So "on a working device this Activity is never seen" needs one amendment: it
 * is never seen *as the assistant's app* — every open of it that the user did
 * not ask for by hand still ends in `expand()` and `finish()` — but it is now
 * what carries a row's trip, and the user lands on it when they come back from
 * Settings. That is deliberate: the list is what they left, and coming back to
 * it is the whole point.
 *
 * ### The backdrop is what makes the card's fill honest
 *
 * [SelfCheckUi] hands the card the *frosted* fill unconditionally when it is
 * hosted, on the reasoning that the blur behind it is real and it did not have
 * to ask for it. That is only true because of [installBackdrop]: without it,
 * "frosted" is 60% black over this Activity's black window, which is a black
 * rectangle. Same blurred image and the same *derived* scrim the assistant's
 * card brings up, via the same `CENTER_CROP` recipe [TextInputActivity] uses —
 * see [Backdrop] for why "same object, same scrim" beats "a similar dark
 * colour".
 *
 * ### Language
 *
 * [SelfCheckUi] carries the picker and writes the preference; this class only
 * has to notice a change made *somewhere else*. There is exactly one such path
 * and it is reachable from this card: the 打开设置 row opens the assistant's
 * settings sheet, and *its* picker writes the same key. [LangToggle]'s own
 * callback has already told the service by the time the sheet closes; this is
 * the other direction, and [onResume] is where it is caught.
 *
 * Note this page is no longer the thing that paints the rows, so the old
 * `lctx`-as-`var` rule now lives in [SelfCheckUi]. [paintedLang] survives because
 * the *comparison* it makes is still the only way to notice the other path, not
 * because this class draws anything with it.
 */
class SelfCheckActivity : StageActivity() {

    companion object {
        /**
         * Start-intent extra: ask for the screen-capture consent as soon as
         * this page is up.
         *
         * Set by the body's notification action, which is the only way in —
         * `MediaProjectionManager.createScreenCaptureIntent()` needs an
         * Activity, and this is the only one the app has. See
         * [requestScreenCapture].
         */
        const val EXTRA_SCREEN_CAPTURE = "screen_capture"

        /**
         * Start-intent extra: the screen the check card was asked to open, and
         * could not carry itself.
         *
         * An `Intent` extra rather than a set of fields, because it *is* one:
         * the card built it with `SelfCheckUi.intentFor` (the same call that
         * decided there was anything to open) and this page must launch
         * *that*, not a reconstruction of it. See [showForErrand].
         */
        private const val EXTRA_ERRAND = "errand"

        /**
         * When to look again after a resume with red rows still on the page.
         *
         * Two, at a spacing wide enough to be a different moment for the
         * platform and close enough that the user has not moved on. See
         * [settleRecheck].
         */
        private val SETTLE_AT_MS = longArrayOf(1_500L, 5_000L)

        /**
         * Bring this page up to carry an errand the check card cannot make
         * itself, and open [errand] from here.
         *
         * ### Why the errand needs a page at all
         *
         * The card is a `TYPE_APPLICATION_OVERLAY`, which outranks every
         * Activity on the device, so a card left up would sit on the Settings
         * screen it had just sent the user to. Closing it works and **loses the
         * list**: a window gets no `onResume`, and the destination leaves with
         * `FLAG_ACTIVITY_NEW_TASK` into a task of its own, so pressing back has
         * nowhere of ours to return to. The report is exact about the shape of
         * it: from the page, 处理 → away → back lands on the list; from the
         * card, the same three taps land on the desktop with a ball in the
         * corner.
         *
         * An Activity has both properties the card lacks. It is *in the task*
         * the destination is started into (this page passes the Intent on
         * without `NEW_TASK`), and it gets [onResume] when the user comes back —
         * which is also where the row they just fixed is re-read and turns
         * green. So the trip is handed over rather than thrown away, and the
         * user comes back to the same card, on a stage, with the same rows.
         *
         * ### Two traps, both paid for once already
         *
         *  * **`NEW_TASK` is set here and nowhere else.** The caller is the
         *    service, and `startActivity` without the flag throws from a Service
         *    Context. It belongs on *this* Intent only — the errand inside must
         *    not have it, or the destination lands in a task of its own and the
         *    way back is gone again, which is the bug this whole path exists to
         *    fix.
         *  * **A re-creation must not re-open the errand.** The user is off in
         *    Settings for as long as they like and a ROM is free to rebuild this
         *    page meanwhile; re-running the errand there would throw them back
         *    into the screen they are trying to leave. [onCreate] launches it
         *    only on a fresh creation — see the `savedInstanceState` test — and
         *    drops the extra as it does, so nothing downstream can either.
         */
        fun showForErrand(context: android.content.Context, errand: Intent) {
            context.startActivity(
                Intent(context, SelfCheckActivity::class.java)
                    .putExtra(EXTRA_ERRAND, errand)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    /**
     * The card. Built here on the service-off path only, placed on [stage], and
     * gone with the Activity.
     *
     * `lateinit` rather than nullable because on this path it is assigned in
     * [onCreate] before anything can reach it. Both the callbacks that *could*
     * arrive first — [onResume] and [onDestroy] — check `isInitialized`, because
     * the other path through [onCreate] returns before this is ever assigned and
     * a lifecycle callback is not a good moment to throw.
     */
    private lateinit var ui: SelfCheckUi

    /**
     * The fullscreen backdrop. Built once and **never rebuilt** — see
     * [installBackdrop].
     */
    private var backdropView: ImageView? = null

    /**
     * The language this page is currently painted in, to notice a change made
     * somewhere else. See [onResume].
     */
    private var paintedLang = ""

    /**
     * Whether this page has already been resumed once.
     *
     * [onCreate] runs the check itself ([SelfCheckUi.start]), and the first
     * resume lands immediately behind it — same launch, no user in between, so
     * there is nothing new to read and a refresh there would be a local pass for
     * nothing. Worse than nothing, in fact: that first pass's probe is still on
     * its socket, and the refresh would overwrite the rows it is about to fill
     * in with the placeholders it is about to replace.
     *
     * Every resume *after* this one is the case the refresher is for. See
     * [onResume].
     */
    private var resumedOnce = false

    /**
     * The one delayed re-read a resume is allowed — see [settleRecheck].
     *
     * Main thread, and cleared with the Activity: a re-read posted after this
     * page is gone would repaint a card that is on its way out.
     */
    private val settle = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Our settings sheet while it is up — see [openOwnSettings]. Held only so a
     * second tap on 打开设置 does not stack a second window on the first.
     */
    private var settings: SettingsUi? = null

    /** The icon is a trampoline to an overlay, not a blank recent-task card. */
    private fun finishLauncherEntry() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The saved palette, before the first view this page draws. The service loads
        // it too, but this page is reachable from the launcher, so on a cold start
        // nothing guarantees the service's own [Theme.load] has run yet — and [Theme]
        // holds the *default* until then rather than the user's answer.
        Theme.load(this)

        // The notification's 允许看屏幕 action lands here — see
        // [requestScreenCapture]. Asked before the branch below, because the
        // point of the action is that the page it opens is a means to an end.
        if (intent?.getBooleanExtra(EXTRA_SCREEN_CAPTURE, false) == true) {
            requestScreenCapture()
        }

        // A working assistant ⇒ this icon means what every other icon on the
        // home screen means: open the app. That is the *same request* a
        // long-press on the ball makes, so it is the same call — one entry point
        // cannot be allowed to mean "show me the assistant" and the other
        // "show me a checklist", since which one the user gets would then depend
        // on which door happened to be on screen. `expand()` is idempotent, so
        // tapping the icon with the card already full is a no-op, exactly as it
        // is on the ball.
        //
        // Then `finish()` and nothing else: this Activity has no window worth
        // showing, and `Theme.Body.Stage.NoPreview` is what stops the platform
        // painting a black starting window for a page that is about to vanish.
        //
        // `ballWindow()` and not merely the instance, because a service that is
        // alive but has not attached its overlay yet is not something to unfold
        // — same guard `openSettingsForUser` uses for the same reason.
        //
        // And `ensureOverlays()` rather than a bare `isShown()` test, because
        // the interesting case is the one where the answer is *no but fixable*:
        // the service connected while the overlay grant was missing (a fresh
        // install, a package rename), `addView` was refused, and the grant
        // arrived afterwards. Asking the question is also the retry, so the icon
        // is the way back rather than a door that reports the room is empty —
        // see `BodyCoreService.ensureOverlays`. When it is still not fixable
        // we fall through to the checklist, which is where the red overlay row
        // is.
        //
        // The report this branch got wrong: on a phone where `addView` succeeds
        // under a denied appop and the window is merely hidden, `ensureOverlays`
        // used to answer "the ball is up", take `expand()`, `finish()`, and
        // leave an empty screen — so the icon did nothing, and did it again
        // every time. The grant is now asked directly, which sends that case
        // down to the checklist instead. See `SelfCheck.overlay`.
        //
        // This is also the only context in the app that may claim the
        // microphone type for that service — the platform refuses a
        // microphone-typed foreground service created from the background, and
        // a visible Activity is the one place the request is legal. Doing it
        // here rather than at boot is what makes the assistant keep working on
        // a device whose accessibility the ROM takes back later;
        // see `BodyCoreService.promoteForMicrophone`.
        BodyCoreService.start(this, microphone = true)

        // A re-creation is not a launch, and this is the one thing that tells
        // them apart.
        //
        // The user is sent out of this page by its own rows, so the platform
        // killing it while they are in a Settings screen and rebuilding it on
        // the way back is *routine* here — not an edge case. On that rebuild
        // `ensureOverlays` answers about the state the user has just changed:
        // grant the overlay, come back to a restarted Activity, and the two
        // branches below hand the screen to the assistant and throw the list
        // away, which is the same complaint as the `onResume` handover from the
        // other side. A saved instance means "there was a page here already, put
        // it back"; only a null one means the user tapped the icon on purpose.
        val restored = savedInstanceState != null

        // And a page that is already open outranks both.
        //
        // The way back from a system screen is often a *fresh launch*, not a
        // resume: the platform can put a Settings screen in a task of its own,
        // and a ROM is free to clear ours while the user is away. On that path
        // `restored` is false and the intent looks exactly like a tap on the
        // icon, so the branches below would take it for one. What tells them
        // apart is whether the user is mid-list, and only something written down
        // can still know that. See [SelfCheck.isPageOpen].
        val checkOpen = SelfCheck.isPageOpen(this)

        // And an errand outranks everything, because it is the one case where
        // this page has been *asked* for: the card handed it a screen to open
        // and the user is waiting to be sent there. Completing the icon's
        // branches instead would expand the assistant and throw the errand away
        // — a tap that does nothing, on the path the user just took because the
        // list told them to. See [showForErrand].
        val errand = intent?.getParcelableExtra<Intent>(EXTRA_ERRAND)
        val carrying = errand != null

        val svc = BodyCoreService.get()
        if (!restored && !checkOpen && !carrying && svc != null && svc.ensureOverlays()) {
            svc.noteOwnActivity()
            svc.dispatcherForNotification()?.expand()
            finishLauncherEntry()
            return
        }
        // Accessibility is ours and the overlay grant is in hand, so the ball
        // is on its way — this Activity is simply a frame early. `start` is
        // asynchronous and the new service's `onCreate` is queued behind this
        // one, so the test above reads null on the cold path that matters most
        // (first launch after a fresh grant). Falling through here would draw a
        // checklist for a device that is working, which is the exact report
        // this branch was rewritten to stop producing.
        if (!restored && !checkOpen && !carrying && svc == null && ScreenBodyService.get() != null && Settings.canDrawOverlays(this)) {
            finishLauncherEntry()
            return
        }

        // ---- Everything below is the case this class exists for ----
        //
        // Written down before a single row is drawn, because from here on the
        // question "was there a page?" has to survive the process: see
        // [SelfCheck.setPageOpen]. [onStageLeaving] is what clears it.
        SelfCheck.setPageOpen(this, true)
        //
        // No service: accessibility is off, or the overlay grant is missing, or
        // the app was just reinstalled. There is no ball, no card and no window
        // to fold, so the checklist is not a fallback here — it is the only
        // thing that can tell the user why the equipment in their hand does
        // nothing. The ball is therefore *not* perked (there is no ball) and the
        // backdrop is not decoration: it is what makes the card's frosted fill
        // honest, see [installBackdrop].
        paintedLang = VoiceConfig.uiLanguage(this)
        installBackdrop()
        ui = SelfCheckUi(
            context = this,
            host = stage,
            // The flag is not consulted here: this host has no assistant to
            // unfold *at this point* — but see [onStageLeaving], which is where
            // that assumption gets checked rather than trusted.
            onDismiss = { _ -> leave() },
            // The two rows a first-run user actually has to act on — 大脑 and
            // 语音密钥 — are `Fix.OurSettings`, and this page used to draw them
            // with no button at all on the argument that the sheet belongs to the
            // service. It does not: `SettingsUi` is a plain overlay built from a
            // Context, and the grant it needs is one of the rows right here. So
            // the door exists whenever that grant does, and only the
            // overlay-refused case falls back to words — see
            // [SelfCheckUi.canOpenSettings] and [openOwnSettings].
            onOpenSettings = if (Settings.canDrawOverlays(this)) {
                { openOwnSettings() }
            } else {
                null
            },
            // The card's picker writes the preference and repaints itself, but
            // this Activity is what *remembers* the language on screen —
            // otherwise the next [onResume] would read a mismatch it caused
            // itself and swap the whole card for nothing. The other callback
            // [SelfCheckUi] offers used to exist only to tell the service
            // something, which is what left this one free.
            onLocaleChanged = { paintedLang = VoiceConfig.uiLanguage(this) },
        )
        // Local half first and it is not an optimisation: `local` is what the
        // card's first painted frame is made of. See [SelfCheckUi.start].
        ui.show()
        ui.start()
        revealStage()

        // The trip this page was brought up to carry — see [showForErrand].
        //
        // After the card is up, so the page the user comes back to has already
        // drawn itself once, and **only on a fresh creation**: a rebuild with the
        // user in Settings carries the same Intent, and re-opening the errand
        // there would drag them back into the screen they are leaving. The extra
        // is dropped as it is launched rather than merely skipped, so no later
        // reader of `intent` — this class's own [onNewIntent] included — can
        // find an errand that has already been run.
        if (savedInstanceState == null && errand != null) {
            intent?.removeExtra(EXTRA_ERRAND)
            openErrand(errand)
        }
    }

    /**
     * Send the user where the row asked, from *this* page's task.
     *
     * Deliberately no `FLAG_ACTIVITY_NEW_TASK` — see [showForErrand] for the
     * whole argument. A failure is a toast rather than a silence, the same rule
     * every button on this card follows, and the page stays up behind it: the
     * list is exactly what the user wants in that case, and it is where the row
     * is that told them to go.
     */
    private fun openErrand(errand: Intent) {
        val ok = runCatching { startActivity(errand) }
            .onFailure { android.util.Log.w("Body", "self-check errand would not launch", it) }
            .isSuccess
        if (!ok) {
            runCatching {
                Toast.makeText(this, getString(R.string.check_fix_no_screen), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Give the screen back to the assistant — which, by now, may well exist.
     *
     * The first half is the original job: drop the ball off the ledge it was
     * parked on while this page covered the screen.
     *
     * The second half corrects the assumption the first version was written on
     * ("nothing to unfold: see [onCreate]"). That assumption is true when this
     * page *opens* and false by the time it *closes*, because the page's own
     * first row is the overlay grant and its own buttons send the user off to
     * fix the rows they can. So the device this page exists for is precisely the
     * one where the assistant comes up while the page is on screen — and the
     * user closes the page onto a lone ball in the corner, with the full card
     * they were just setting up nowhere to be found.
     *
     * That is the report: grant the permission, close the check, and get a ball
     * instead of the assistant. The card is not "somewhere else" on that device;
     * it is there and folded, and the one surface that knows the user has
     * finished with the diagnosis is this one, on its way out.
     *
     * Unfolding here rather than after the exit animation is deliberate:
     * [StageActivity.leave] hands the screen back synchronously before the
     * window shrinks, so the card grows into place *underneath* the shrinking
     * page rather than appearing after it — see that method's `onStageLeaving`.
     *
     * `BodyCoreService.get()` may be null, and that is the ordinary case this
     * page opens in: nothing to hand back, and nothing to unfold.
     */
    override fun onStageLeaving() {
        // The user is done with the list, and this is the only thing that says
        // so — everything else about the page survives on purpose, including the
        // process. Cleared *before* the overlays go up, so a launch on the far
        // side of this is a launch with no page. See [SelfCheck.setPageOpen].
        SelfCheck.setPageOpen(this, false)
        val svc = BodyCoreService.get()
        svc?.ballWindow()?.setPagePerched(false)
        // `ensureOverlays` before `expand`, not `expand` on its own.
        //
        // **This is now the only way off this page**, so it is the only thing
        // that puts an assistant on screen for a user who came in through it.
        // Nothing has shown this service's overlays at any point while the page
        // was up — the page is drawn precisely because they could not be shown
        // — and expanding a window that was never attached draws nothing. The
        // user would close the check onto a bare home screen.
        if (svc?.ensureOverlays() == true) svc.dispatcherForNotification()?.expand()
    }

    /**
     * The settings sheet, raised by this page rather than by the service.
     *
     * This is the path where there is no service — that is the whole reason the
     * page is on screen — so `BodyCoreService.openSettingsForUser` has nobody
     * to ask. `SettingsUi` never needed one: it is a `TYPE_APPLICATION_OVERLAY`
     * built from a Context, and it lands on top of this Activity exactly as it
     * lands on top of the assistant's card.
     *
     * The three wipes are the only thing the service was carrying, and each is
     * one static call plus a brain rebuild — so here they are just the static
     * call, because there is no brain to rebuild. That is not a degraded version
     * of `BodyCoreService.clearChatHistory`: what that method's second half is
     * *for* is a model still holding the conversation, and on this path nothing
     * is.
     *
     * Two sheets can never exist at once. The only way to this page is
     * [onCreate] falling through, which happens when the service is absent or
     * has no overlay — and in the second case `canDrawOverlays` is false too, so
     * this is not even offered.
     *
     * Repainting on dismiss rather than trusting [onResume]: an overlay does not
     * pause the Activity underneath it, so a language changed inside the sheet
     * would otherwise leave this card in the old one until something else
     * happened to resume it.
     */
    private fun openOwnSettings() {
        if (settings?.isShowing() == true) return
        val s = SettingsUi(
            context = this,
            onDismiss = {
                settings = null
                paintedLang = VoiceConfig.uiLanguage(this)
                // Re-run the list, not just repaint it: the user came here to
                // fill in a key, and the row they were sent from is exactly the
                // one that should be green when they get back. [SelfCheckUi.start]
                // is what 重新检查 calls.
                if (::ui.isInitialized) ui.start()
            },
            onFactoryReset = { FactoryReset.wipe(this) },
            onClearChat = { ChatHistory.wipe(this) },
            onClearNotes = { Notebook.wipe(this) },
            // This host is an Activity: it can open a browser itself, and the
            // card is hosted by the sheet that stays here when the user comes back.
            onOpenLink = { url -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
            onLocaleChanged = {
                paintedLang = VoiceConfig.uiLanguage(this)
                if (::ui.isInitialized) ui.repaintForLanguage()
            },
        )
        settings = s
        s.show()
    }

    /**
     * Catch a language change that did not come from this card's picker.
     *
     * This Activity paints nothing itself any more, so what it keeps
     * [paintedLang] for is the *comparison*: it is the language the card was
     * last drawn in, and a mismatch on resume means the preference moved behind
     * the card's back. The card's own picker is not that case — it repaints
     * itself and hands the new value over through `onLocaleChanged` — so what
     * lands here is a change made somewhere this page cannot see.
     *
     * Narrower than it looks, and deliberately kept anyway. The two doors out of
     * the card that *can* move the language — its own picker, and the settings
     * sheet at [openOwnSettings] — both hand the new value back directly, and the
     * sheet is an overlay that never pauses this Activity, so neither arrives
     * here. What is left is a change written while the user was off in a system
     * screen. It is one `SharedPreferences` read per resume — [AppLocale]
     * deliberately does not cache, see its doc.
     *
     * `paintedLang` is set in [onCreate] before the first resume, so this cannot
     * fire on open. Guarded on [ui] for the *other* path through [onCreate]: the
     * icon-as-app entry finishes before the card is ever built, and a lifecycle
     * callback arriving afterwards is not a good moment to throw.
     */
    /**
     * The screen-capture consent, and what the answer goes to.
     *
     * Registered as a field rather than from [onCreate] because the lifecycle
     * requires the callback to exist before the Activity is started, and this
     * page is started by an icon tap — there is no earlier moment.
     *
     * The grant goes to the *body*, not to this Activity: the capture has to
     * outlive this page, and on Android 14 it has to be re-asked for every
     * session, so the thing that owns it is the thing that is still running
     * tomorrow. Nothing is announced here either — the body's own notification
     * is what reports this state, and a second voice describing the same fact
     * is how two surfaces start disagreeing.
     */
    private val captureConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val granted = BodyCoreService.get()
            ?.offerScreenCapture(result.resultCode, result.data) == true
        android.util.Log.i("Body", "screen capture consent: granted=$granted")
    }

    /**
     * Ask the user to let Andee read the screen without accessibility.
     *
     * **Only reachable while the assistant is blind**, which is the only time
     * it is worth a system dialog: with accessibility on, `takeScreenshot` is
     * richer, free and already granted — an aim grid the brain can tap from and
     * the element list beside it — and asking for a second, weaker way in would
     * be asking the user to make a decision with no benefit.
     *
     * The caller is the body's notification action, `Intent` extra
     * [EXTRA_SCREEN_CAPTURE]. Failing to launch is not worth a card: the
     * platform simply has no such screen on some ROMs, and the notification
     * will go on offering it.
     */
    private fun requestScreenCapture() {
        val mgr = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            ?: return
        runCatching { captureConsent.launch(mgr.createScreenCaptureIntent()) }
            .onFailure {
                android.util.Log.w("Body", "screen capture consent would not launch", it)
            }
    }

    /**
     * The other half of [EXTRA_SCREEN_CAPTURE], and not a formality.
     *
     * This page is `singleTop`, and its two callers both routinely arrive while
     * it is already the top instance: the notification action is tapped from
     * the shade with the page up, and the launcher icon does the same. On that
     * path `onCreate` does not run, so an extra read only there would be a
     * button that works the first time and silently stops working afterwards —
     * and the first time is the one where the user had not yet refused.
     * [setIntent] keeps `intent` honest for anything that reads it later.
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SCREEN_CAPTURE, false)) requestScreenCapture()
        // An errand can only arrive here if this page was already up, which is
        // not a path the card can currently reach — it is the *other* door, and
        // the page covers the assistant it is opened from. Handled anyway, and
        // for the same reason the capture extra is: this class is `singleTop`,
        // so the day something else hands it one, the alternative to this branch
        // is a launch that silently does nothing. See [showForErrand].
        intent.getParcelableExtra<Intent>(EXTRA_ERRAND)?.let {
            intent.removeExtra(EXTRA_ERRAND)
            openErrand(it)
        }
    }

    override fun onResume() {
        super.onResume()

        // Order matters here, and it is the reverse of what it was.
        //
        // Every row on this page sends the user out — 去开启, 允许, 去下载 — so
        // the way back is a resume, and the first thing to do with one is
        // re-read the half that is cheap to re-read. A row the user just fixed
        // staying red reads as "the fix did not take", and the only other way
        // to correct it was 重新检查 at the bottom of the list. Being an
        // Activity is what makes noticing this possible at all: the same list
        // on a working device is an overlay owned by the service, gets no
        // resume callback, and is closed on the way out instead — see
        // `BodyCoreService.openSelfCheck`. Local only, deliberately, and
        // [SelfCheckUi.refreshLocal] carries that argument in full.
        //
        // **Nothing here closes the page.** This used to hand the screen to the
        // assistant as soon as it could draw one, which is a return from
        // granting the overlay — and it is also a return from fixing row one of
        // five, where it took the list away and sent the user back through the
        // launcher icon and 看自检 to get at row two. The page is now left up
        // until the user closes it, which is the one signal that actually means
        // "I am done here": ✕ or 完成, and [onStageLeaving] puts the assistant
        // back. On a page whose whole job is to walk somebody down a list, the
        // user is the only one who knows when the list is finished.
        //
        // Skipped on the first resume, which is `onCreate`'s own: the render
        // there is the freshest thing there is, and re-reading would be a second
        // pass over the same facts in the same frame.
        if (::ui.isInitialized) {
            val now = VoiceConfig.uiLanguage(this)
            if (paintedLang.isNotEmpty() && now != paintedLang) ui.repaintForLanguage()
            paintedLang = now

            if (resumedOnce) ui.refreshLocal()
            resumedOnce = true
            settleRecheck()
        }
    }

    /**
     * Re-read the local half after a resume that still has red rows.
     *
     * A resume is the user coming back from a screen that changed system state,
     * and **the platform's answer about that state settles late** — the same
     * call that reads correctly a few seconds later reads "not done yet" in the
     * frame the user returns on. Two of these rows work this way:
     *
     *  * the accessibility row reads the `enabled_accessibility_services`
     *    string, which a vendor Settings app may write on its way out rather
     *    than on the toggle;
     *  * the notification-access row is `OK` only once the listener is actually
     *    *bound*, and the bind lands after the setting is written — so the
     *    honest reading for that instant is 「已授权，但监听器没连上」.
     *
     * Either one is a red row the user has just fixed, which is the reading that
     * makes somebody believe the fix did not take. The report was "I granted it,
     * came back, it still said no, and then I hit re-check and it was fine".
     *
     * **Bounded, and that is the important half.** Two more passes at the
     * offsets in [SETTLE_AT_MS], then it stops: this page is a diagnosis, and a
     * page that keeps re-reading itself is a page whose numbers stop meaning
     * anything. A row that is still red after both is genuinely still red, and
     * the row's own button is the thing that fixes it.
     */
    private fun settleRecheck() {
        if (ui.outstanding() == 0) return
        for (at in SETTLE_AT_MS) {
            settle.postDelayed(
                {
                    if (::ui.isInitialized) ui.refreshLocal()
                },
                at,
            )
        }
    }

    override fun onDestroy() {
        // A re-read posted after this page is gone would repaint a card that is
        // on its way out — and `ui.destroy()` below takes the rows away anyway.
        settle.removeCallbacksAndMessages(null)
        // Our sheet is a window, not a child of this Activity, so nothing takes
        // it down with us — an Activity destroyed with it up would leave an
        // orphan overlay on screen with no way to close it.
        settings?.hide()
        settings = null
        // Guarded because this also runs when onCreate threw before the card was
        // built, and dying is not a good moment to throw a second exception.
        if (::ui.isInitialized) ui.destroy()
        super.onDestroy()
    }

    // ---- The backdrop ----

    /**
     * Decode-and-blur via [Backdrop], into a full-bleed `CENTER_CROP`
     * ImageView, with the scrim as a foreground.
     *
     * The same three lines [TextInputActivity] runs, deliberately: the scrim is
     * *derived from the image's own brightness* (see `Backdrop.scrim`), so
     * pairing this image with any other fill would put the card at a different
     * mean luminance than the ball's card, which shares the image.
     *
     * There is nothing to rebuild it *for* any more — the language switch swaps
     * the card, which is a child of [stage] and not of this — so it is added
     * once and left alone. The `ready` branch skips the round trip on a second
     * open in the same process.
     */
    private fun installBackdrop() {
        if (backdropView != null) return
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = matchParent()
        }
        if (Backdrop.ready()) {
            iv.setImageBitmap(Backdrop.bitmap())
            iv.foreground = ColorDrawable(Backdrop.scrim())
        } else {
            // Never blocks the main thread, and on a cold start the window is
            // already dark (`Theme.Body.Stage`), so the wait is invisible.
            Backdrop.warm(this) { bmp ->
                if (isFinishing || isDestroyed) return@warm
                iv.setImageBitmap(bmp)
                iv.foreground = ColorDrawable(Backdrop.scrim())
            }
        }
        stage.addView(iv)
        backdropView = iv
    }
}
