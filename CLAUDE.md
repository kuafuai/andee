# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**Framing first, because it changes how you write everything else.** This is not "an app that a brain drives". The project's own words — the first line of `LocalPrompt.TEXT` — are *"you are not a tablet's assistant, you are an individual, and the tablet is only what carries you."* The device is a **body** (eyes = camera, ears = ASR + offline wake word, hands = accessibility, optionally a robot dog as a leg), and the persona is a real input to the model (`BallLook.persona` → `LocalPrompt.withPersona`), not decoration. It also keeps a private notebook and a scheduler, and it reviews its own conversations when alone. Write code and docs that preserve an individual's continuity; **tool calls are how a body acts, not what the project is.** Calling this "the body side of an agent" in your own prose is the failure mode to avoid.

This is an **Android app** (Gradle root project `body`, package `net.kuafuai.andee`). The checkout is the **repository root**. Concretely it does tap / swipe / type / screenshot / read the UI tree / system keys, plus ASR and TTS. The brain is normally remote (a hub the operator runs, reached over a WebSocket), but it can also be **this device itself** — and that is the out-of-box default; see "The brain can also be this device".

Two orientation facts that are easy to get wrong:

- **The entry point is `ScreenBodyService`, an `AccessibilityService`.** Almost everything the user sees is a `WindowManager` overlay (the floating ball); there is no main UI, and the service is what builds the whole object graph. If the accessibility service isn't enabled in system settings, nothing works — **which is why there is now exactly one Activity in the launcher**, `ui/SelfCheckActivity`. It is not a home screen: it is the readiness checklist, and it exists because a device whose accessibility service is off would otherwise have no way in and no way to be told why. See "Startup self-check" below.
- **Docs are split by audience.** `README.md` is the **English** front page (what it is, architecture, build, permissions, security). `README.zh-CN.md` is the **Chinese** guide and is the authoritative setup/permissions walkthrough — read it for first-time device provisioning; it carries the exhaustive SDK setup, the wake word, and the robot-dog section. `docs/permissions.md` is the permission justification, with a Chinese edition at `docs/permissions.zh-CN.md`. The same one-file-per-language rule governs `DISCLAIMER`, `docs/acceptable-use` and `CONTRIBUTING` — English on the default filename, Chinese on `.zh-CN`, and each language linking only to its own counterparts. (`SECURITY.md` and `CODE_OF_CONDUCT.md` exist in English only.) It used to tell you to `cd body-native`; the checkout is the **repository root**. (Fixed 2026-09-29.)

## Build and run

```bash
./gradlew assembleDebug          # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # build + install to the attached device
./gradlew test                   # host-side JUnit (only template tests exist)
./gradlew connectedAndroidTest   # instrumented tests (needs a device; only a template test exists)
adb logcat -s Body:* BodyWs:* BodyWsClient:* Asr:* Tts:*   # the log tags this app uses
```

There is **no real test suite** — `ExampleUnitTest` / `ExampleInstrumentedTest` are unmodified templates. Verification is manual: install, watch logcat, drive the ball. There is no lint/format task configured beyond AGP defaults.

Toolchain notes that bite:

- `gradle.properties` **does not pin the Gradle JDK** — it used to hardcode a machine-specific `org.gradle.java.home`, which made the build fail instantly on every other machine. The build needs a **JDK in the 17–20 range**: AGP 8.3.1 requires 17+, and the Gradle 8.4 wrapper cannot run on 21+ (Android Studio's bundled JBR 25 dies with `Unsupported class file major version 69`). Set `JAVA_HOME`, or put `org.gradle.java.home=<path to JDK 17–20>` in **`~/.gradle/gradle.properties`** — never in the repo's copy. In Android Studio, set *Settings → Build Tools → Gradle → Gradle JDK* to a 17–20 JDK.
- `settings.gradle` puts **Aliyun mirrors first** for both plugin and dependency resolution. Keep them first when editing; they're there because the project is developed behind the GFW.
- `minSdk 30` is load-bearing, not arbitrary: `ACTION_IME_ENTER` and `AccessibilityService.takeScreenshot` are both API 30+.
- Deliberately **no Jetpack Compose, no Hilt/Dagger, no coroutines.** Overlays can't host Compose, so all UI is hand-written `View` code; there's exactly one service, so dependencies are constructor-injected by hand; concurrency is `Handler` + dedicated `Executor`s. Match this style rather than introducing the modern stack.

## Architecture

### Wiring

`ScreenBodyService.onServiceConnected()` builds the entire object graph and is the best single file to read first. It creates `FloatingWindowUi` → `AudioIO` → `TtsController` / `AsrController` → `ScreenController` → `CommandDispatcher`, then starts **both** networking paths.

### Two networking paths, one dispatcher

The app simultaneously runs an inbound server and an outbound client, both terminating in the same `CommandDispatcher`:

- **`BodyWsServer`** — listens on `0.0.0.0:9008`. For a brain or debug client on the same LAN (or via `adb forward`). Methods on this wire are the **internal** names: `screen.tap`, `screen.ui_tree`, …
- **`BodyWsClient`** — dials *out* to a hub (`hub_url`, e.g. `ws://<brain-ip>:9100`), sends a `register` envelope carrying `ToolSchemas.all()`, then serves `request` messages pushed back down the same socket. Reconnects with exponential backoff (500 ms → 30 s). This is how the body reaches a brain behind NAT. Skipped entirely when `hub_url` is blank — server-only is a valid mode.

Shared envelope shape: `{"type":"request","id":…,"method":…,"params":{…}}` in, `{"type":"response","id":…,"result"|"error":…}` out. Dispatcher contract: **return a JSON-serializable value or throw** — thrown exceptions are converted to `error` responses at the transport boundary, so don't catch-and-encode errors yourself inside dispatch handlers.

The device also talks unprompted, as `{"type":"event","kind":…,"device_id":…,"data":…}`, sent to the hub and broadcast to every server client. Three kinds exist: `asr.final` (the user said something), `task.stop` (they stopped it — triple-tap on the ball since the `■` button was removed, see "Startup self-check"), `notification` (relayed from the notification listener). Events are **never queued**: with the socket down they are dropped, because a stale "the user just said" is worse than silence. Unlike tools, event kinds are not in `ToolSchemas`, so **the brain side has to be taught each one separately** or it is silently discarded — which looks, from the tablet, exactly like the feature not working. That cost is why the meeting recorder asks for its minutes on `asr.final` instead of inventing a fourth kind.

### The brain can also be this device (`brain/`)

`brain = local` in `voice_prefs` swaps the remote brain for an agent loop running on the tablet: `LocalBrain` calls DeepSeek over plain HTTP (`LlmClient`, OkHttp, non-streaming) and drives the same `CommandDispatcher`. The two are **mutually exclusive** — `ScreenBodyService.startBrain()` builds one or the other, because two brains answering the same `asr.final` is two voices talking over each other. The local brain is the **out-of-box default** (since 2026-09-29); anything other than the literal string `"hub"` reads as local, the same fail-closed rule `groundingEnabled` uses — the direction flipped with the default, and the settings picker's leftmost option (the fallback) flipped with it.

What made this cheap is that `BodyWsClient` had already reduced "the brain" to a contract: **eat a line of text, emit `onProgress` / `onFinal`, call the dispatcher in between.** Those two lambdas now live in `ScreenBodyService.onBrainProgress` / `onBrainFinal` and both brains pass method references to them — that is deliberate, not tidiness: the TTS mute rules (meeting, call), the `acceptBrainOutput` gate, `stripMarkdown` and `ChatHistory` all live in there, and a second copy is where this feature would rot. Subtitles, edge glow, the fold, stop and the follow-up mic window are untouched by the switch.

Four things in `LocalBrain` are correctness, not style:

- **Tool calls run serially.** The cloud agent uses `asyncio.gather`; copying that breaks `passthroughForGesture`, which is not reentrant (see the overlay section).
- **Every dispatch has a deadline** (`dispatchWithTimeout`). `ui.ask` blocks to its timeout — and `ui.show_html` used to block until the user closed the page, up to 5 minutes, which is gone (see the page section). Over the hub, `body_hub`'s 30 s request timeout silently abandoned those; locally nothing does, so the loop times out and tells the model the call may still be running. It does **not** interrupt the work.
- **Images are moved out of tool results and rationed.** `png_base64` is extracted **recursively** (it nests under `after_shot` / `miss_shot`, and `ui_tree` attaches one when the element list is empty), replaced in place with `"<image attached>"`, and re-attached as a separate `{"role":"user","content":[text, image_url]}` message. Only the newest 2 image messages survive; every successful tap ships a full-screen PNG, so a 15-step task would otherwise carry 15. The accepted cost is that dropping a message invalidates DeepSeek's prefix cache from that point, and the 55 tool descriptions ahead of it are the biggest constant in the request.
- **The clock rides in on the user message** (`[现在 …]`), not the system prompt. There is no clock tool on this device, and the system prompt plus tool specs are the cached prefix — restamping them every turn would throw that away for one line. The **interface language** (`· 界面语言 zh`) rides in the same bracket, for the same reason: the system prompt is a constant and cannot name a language without invalidating the cache every time the user touches the language picker. Both `stamp()` and `uiLangTag()` follow the user's language setting rather than `Locale.CHINA` — an English interface whose own messages are stamped "周一" would feed the model a Chinese signal every turn, against `LocalPrompt` §9.
- **The model's language is a prompt rule, not a translation.** `values-en/` covers what the *user* reads; it does nothing about what the model *says back* — a system prompt written entirely in Chinese is a strong anchor, and an English-speaking user would get Chinese answers with a fully translated interface. §9 states the rule (follow the user's own words; fall back to the bracketed interface language; for silent turns and recordings, follow the material) instead of naming a language, which is what keeps the prompt constant. Note the split this rests on: the interface language and the conversation language are **independent** — the user may run an English UI in Chinese, and the prompt must not confuse the two.

`LocalPrompt.TEXT` is the system prompt, adapted from the *cloud* brain's text. Our mirror of that text (`brain/andee-system-prompt.md`) was deleted on 2026-09-29 — nothing loaded it and the two had already stopped needing to move together, so it was a file with no reader. Note what that did **not** do: the cloud mode is still in the code (`VoiceConfig.BRAIN_HUB` still exists; the local brain is the out-of-box default), and that side keeps its own copy of the prompt, which this repo never owned. The CRM sections and the commitment/playbook rituals are **gone rather than softened** from *this* prompt: `crm_*` does not exist here and nothing wakes this device up later, so a rule whose tools are absent just teaches the model to claim it did something it cannot do.

Guards: `MAX_ITERATIONS = 25` and an identical-`(name, arguments)`-three-times cut-off, both of which end with a spoken final answer — a ball that simply stops is the one failure the user cannot interpret. Neither replaces `ScreenController`'s per-location tap guard; that one counts coordinates, these count calls.

Not done on purpose: no streaming (an SSE parser plus incremental `tool_calls` reassembly, for a second or two of earlier TTS), no history on disk (service restart = new session), no context compaction, and `tools/boss.py` has no injection point in local mode because its whole mechanism is impersonating a device to the hub.

### The two-name problem (ToolSchemas)

The hub wire uses **LLM-facing tool names** (`get_screen_element`, `tap_screen_element`, `tap_by_coordinates`, `system_action`); `CommandDispatcher` uses **internal method names** (`screen.ui_tree`, `screen.tap_id`, `screen.tap`, `screen.global`). `ToolSchemas.methodOf()` translates between them, and `BodyWsClient` applies it before dispatching. `BodyWsServer` does **not** translate — it passes methods through raw.

Consequence: **adding a tool means editing both `ToolSchemas.kt` and `CommandDispatcher.kt`.** A schema entry with no matching dispatcher branch fails at call time with `unknown method`; a dispatcher branch with no schema entry is invisible to the brain. The `description` strings in `ToolSchemas` are prompt text read by an LLM — they are behavioral documentation, not code comments, and are meant to stay in sync with the equivalent Python `body_tools/` files on the brain side.

### The overlay-vs-gesture conflict

This is the subtlest invariant in the codebase. `ScreenController.tap/swipe/longPress` use `dispatchGesture`, which injects events into the **real touch pipeline** — so the app's own floating overlay would intercept them first. Every gesture-based command is therefore wrapped in `passthroughForGesture` (`CommandDispatcher`) or the equivalent in `ScreenBodyService.swipe`, which flips the overlay to non-touchable, **sleeps ~50 ms** (because `updateViewLayout` is posted to the UI thread and needs a frame or two to actually apply), runs the gesture, then restores. The window stays visible throughout.

Eid taps (`tap_screen_element` / `long_press_screen_element` → `tapByEid` / `longPressByEid`) are gestures too — they resolve to pixel centers and dispatch a real touch — so they go through the same dance. Accessibility-API commands (`type`, `submit_input`, `ui_tree`, `global`) do *not* touch the input layer and correctly skip this. If you add a tool, decide which category it's in.

The mirror-image problem is **accessibility** visibility, and it is not solved by the touch dance: we are an `AccessibilityService`, so our own overlays show up in our own dumps. Every overlay root therefore calls `hideFromAccessibility()` (`ui/OverlayA11y.kt`) before `addView` — keep that up on any new window, or the brain starts reading our toolbar as the app's UI. `tryOtherWindows` skips `service.packageName` as a second guard, and `dumpUiTree`'s `treeEmpty` treats a dump of our own package as no tree at all (that one is for our real Activities — `HtmlActivity` / `ScanActivity` / `LookActivity` / `SelfCheckActivity` — which are app windows, not overlays). Note there is no *touch* bug here to fix: `passthroughForGesture` means an injected tap can never land on the ball. Don't "fix" it by refusing taps inside the ball's rectangle — app content under the ball is legitimately tappable and passthrough makes tapping it work.

What makes `hideFromAccessibility()` actually work is **`accessibilityDataSensitive`, not `importantForAccessibility`** — and the difference is invisible until you look. `accessibility_service_config.xml` asks for `flagIncludeNotImportantViews`, which makes `View.includeForAccessibility()` return true no matter what `importantForAccessibility` says. So `NO_HIDE_DESCENDANTS` on its own hides our overlays from every service *except the one that needed it*: measured, with the vault form open, `get_screen_element` returned the whole form, labels and phone number included. `ACCESSIBILITY_DATA_SENSITIVE_YES` (API 34+, inherits down the subtree) is honoured against us because we do not declare `android:isAccessibilityTool` — and is strictly kinder than the blunt flag, since TalkBack does declare it and keeps the ball. Both are set; the old one is the pre-34 fallback. Don't casually drop `flagIncludeNotImportantViews` to "simplify" this — it is what makes unlabelled app views visible at all.

Hiding the two **focusable** cards (`SettingsUi`, `VaultUi`) then creates a second-order problem: they are the active window, and a hidden active window dumps as zero nodes — the exact signature `reviveNote()` reads as "the accessibility connection went stale, have the user toggle Andee off and on". That message is aimed at a human, so it has to be true. `OwnCard` in `ui/OverlayA11y.kt` (incremented in those two classes' `show`/`hide`; counted, because the vault opens on top of settings) lets `annotate` say the real thing instead — the user has our own card open, nothing is broken, wait or ask them to close it. Same rule as the meeting recorder's `stopped_by` and `device.look`'s grace message.

### Coordinate protocol: the model never does math

Ported from generic_bridge (phone.sh), whose finding is the founding rule here: **general-purpose models are bad at grounding** (AndroidLab: GPT-4o 31.2 vs GUI-tuned 49.5) but good at reasoning — so the design goal is that the model never computes a coordinate.

- `get_screen_element` returns an **element outline**, one line per element, in document order and indented by depth: `  e7*|Button|发送|[490,321]` — eid, `*` clickable marker, class, label, and the element's center in **0-1000 relative** coordinates computed by us from `getBoundsInScreen`. The indentation is the pruned tree rendered cheaply (~20% more characters than an unindented list, against ~170% for the nested JSON) and it is what makes "which one" answerable: on a 朋友圈 feed the `#comment_btn` under the line saying 好友3 *is* 好友3's comment button. Label falls back `text → desc → #<id tail>`; a `#` label is an internal resource name, never visible text. Ordering is document order, not useful-first — sorting scattered each post across the list and stacked twenty identical label-less `ImageView` lines at the bottom. Truncation is a safety valve (`MAX_ELEMENT_LINES = 300`, reported as `elements_dropped`) that drops decorative leaves from the tail first and never containers, so grouping survives it.
- `tap_screen_element` takes an **eid** (the old resource-id parameter is gone — WeChat nodes mostly have none, and e-numbers cover every listed element). `rebuildIndexAndList` rebuilds the eid→pixel-center index on every dump; `tapByEid` looks the number up and passes the **pixels straight to `tap()`** — no px→norm→px round-trip (an earlier lost version fed norm values into the pixel `tap()` and every e-tap landed in the top-left quadrant). Stale/unknown eids fail loudly with "re-dump, don't guess".
- `tap_by_coordinates` / `long_press_by_coordinates` / `swipe_by_coordinates` are the **last resort** for self-drawn panes (mini-program, WebView, game canvas) and take **0-1000 relative integers** — resolution-independent and immune to screenshot downscaling. Values outside 0-1000 are **rejected**, never clamped (a silent clamp to the edge is a mis-tap the model can't see). `ScreenController.tapNorm/longPressNorm/swipeNorm` convert to pixels against the local display.
- `take_screenshot` downscales to **1280 on the long side** before base64 — proportions are preserved and the 0-1000 protocol is scale-invariant, so this is free token savings.
- **Stuck-loop guard**: per-location counting, not consecutive-with-no-change (the real dead-loop interleaves menu opens, wrong pages and backs, and apps emit content events constantly — both naive conditions miss it). Gestures within 120 px of each other share a bucket; within 5 minutes, the 4th tap on the same bucket is refused with an error telling the brain to stop, re-observe, and ask the user. Swipes are exempt (list scrolling legitimately repeats); e-taps are exempt (re-tapping a confirmed element is normal operation).
- **NO_TREE policy** (empty-list hint): prefer `back`/`home` to exit and retry via a different route over blind coordinate taps — a wrong exit costs one tap, a wrong tap can cost the task.
- **Stale-service note**: `ScreenController.goodDumps` records which packages have actually produced a usable element list here. When a dump comes back with *zero nodes* from a package with such a history, `reviveNote()` appends the evidence to the hint — WeChat's tree is genuinely obtainable, but the accessibility connection sometimes goes stale and only a human toggling Andee off/on in 设置 → 无障碍 restores it. It is **evidence, not a rule**: the note states the facts and the brain decides whether to interrupt the user. It is deliberately not keyed to `com.tencent.mm`, and deliberately fires only on `nodes_total == 0` — a self-drawn pane still reports its containers, so mini-programs never trigger it.

### Text input: the service types for itself, ADBKeyboard is the spare

`type_text` has two channels and `ScreenController.typeText` tries them in this order:

1. **`screen/A11yIme.kt` (API 33+, the normal one).** `accessibility_service_config.xml` asks for `flagInputMethodEditor`, which makes `AccessibilityService.getInputMethod()` hand back the focused editor's own `InputConnection` — `commitText` down the same path a keyboard takes, which an app cannot refuse without refusing all keyboards. What it is *not* is an IME: nothing to install, nothing to enable, nothing to select, and **the user's own keyboard stays active the whole time**. That is the point. It costs the user nothing to set up, where the route below needs a third-party APK and an `adb` grant a person without a computer cannot obtain.
2. **`typeViaAdbKeyboard` (API 30–32, and any editor the first channel cannot reach).** Everything in the rest of this section describes it, and all of it is still live.

`typeViaA11yIme` returns **null only when nothing was typed** — that is what makes the fallback safe, and inverting it is how you get text typed twice. A non-null result that disagrees with the readback is a real failure, not a missing channel, and must not fall through. `submitInput` has the same shape: `ACTION_IME_ENTER` first, then `A11yIme.editorAction`, which asks the field's own `EditorInfo` whether it is a Send, a Search or a Next rather than assuming Enter.

The first channel also has a session-attach race worth knowing: a field that took focus a few frames ago may have no input session yet, and returning null there would send a `WRITE_SECURE_SETTINGS`-less device to the hard error this channel exists to retire. `awaitConnection` polls to `SESSION_WAIT_MS` (400 ms) — measured right after the service rebinds. Readback comes from `getSurroundingText`, i.e. **the editor's own buffer**, so the WebViews that hide their focused node's text stop being a false negative. Two smaller wins fall out, neither of them the reason: a vault password typed this way never leaves the process, and there is no ~0.75 s IME lend-and-give-back per call.

Verified on device 2026-09-30 with Baidu IME still the default throughout: Chrome's `url_bar` (native `EditText`), an in-page web `<input>`, and a `show_html` WebView.

#### The ADBKeyboard route, and the action names are bare

`ScreenController.typeViaAdbKeyboard` broadcasts to **ADBKeyboard**, which must be the device's active IME. It is an IME, so it types through `InputConnection.commitText()` too. That is why the older waterfall (`ACTION_SET_TEXT` → clipboard `ACTION_PASTE` → IME panel walking) is gone: `SET_TEXT` crossed fields on Radix forms and did nothing at all in CodeMirror.

The one thing to get right, because getting it wrong is invisible: **the broadcast actions have no package prefix.** ADBKeyboard registers its receiver at runtime with the bare strings `ADB_INPUT_TEXT` / `ADB_CLEAR_TEXT` (confirm with `adb shell dumpsys activity broadcasts | grep ADB_INPUT`). Sending `com.android.adbkeyboard.ADB_INPUT_TEXT` matches no filter, and since we also `setPackage()` the intent, it is delivered to nobody — no exception, no log, the field simply stays empty. That failure is indistinguishable from an OEM blocking the broadcast, and it cost a day: it was read as "MIUI drops app-to-app broadcasts", a hub-side relay was built to re-send the broadcast from the PC over `adb` as `shell`, and because the relay used the *correct* bare action it worked — so on a plugged-in device everything looked fine and only an unplugged one failed. If typing ever breaks again, check the action strings and the active IME before believing any theory about app identity or OEM policy.

Readback is a **poll**, not a sleep: after `commitText` the WebView still has to publish the new value to the accessibility tree, and reading too early returns `performed: false`, which makes the brain send the text a second time — so the fixed wait produced real double-typing. Reads try the focused node first and then scan the tree (WebViews often hide the focused node's text). A self-drawn editor can defeat both; that is an a11y false negative, and the error string says to trust a screenshot instead.

**The keyboard belongs to the person; the brain borrows it per call.** ADBKeyboard must be the *active* IME for any of the above to work, and it is a headless IME — no keyboard UI at all. So the device **rests on the user's own IME** (they can type in any app on the tablet), and `ui/ImeSwitch.lendToBrain` / `giveBack` bracket each `type_text`: ADBKeyboard for one tool call, then straight back. Two things that follow, both measured, both easy to get wrong:

 * **The switch is a `Settings.Secure.DEFAULT_INPUT_METHOD` write.** On MIUI an app calling `InputMethodManager.setInputMethod` *or* `switchToNextInputMethod` is silently refused (no exception, no log — read the setting back or you will believe it worked). The settings write needs `WRITE_SECURE_SETTINGS`, granted once at provisioning (`adb shell pm grant net.kuafuai.andee android.permission.WRITE_SECURE_SETTINGS`), and a reinstall keeps it. `lendToBrain` then waits ~350 ms before broadcasting: the setting and the *binding* are different events, and a broadcast into an IME that has not bound yet disappears without a trace. Expect ~0.75 s per `type_text`. **This is the grant an ordinary user cannot give**, which is the whole reason the accessibility channel above is now first.
 * **The person's keyboard is raised by the brain's own tap.** The brain taps a field, which asks for a soft keyboard; with a real IME active that request is honoured — on screen, over the app being driven. Nothing puts it back down: `hideSoftInputFromWindow(null, …)` is ignored, and `performGlobalAction(GLOBAL_ACTION_BACK)` is *not* consumed by the IME the way an injected key event is (it dismissed the page instead). Don't re-try either. It clears itself when the brain touches something that takes focus.

And one platform rule that shaped the ⌨ field: **a `TYPE_APPLICATION_OVERLAY` window cannot raise the soft keyboard on this ROM** — not via `showSoftInput`, not via `WindowInsetsController.show(ime())`, not even from a real finger tap. The same input inside an Activity raises it immediately. That is why `ui/TextInputActivity` is an Activity, why the card has to **fold** before it opens (a fullscreen overlay sits *above* application windows and eats every touch aimed at the field — the same reason `ui.show_html` folds), and why it handles the IME inset by hand: a translucent window is never resized for the keyboard.

A second rule, from the same ROM and with the same shape — *our windows are overlays, and the platform is allowed to hide them*: **a password `inputType` on one of our cards makes the field impossible to type into** (`ui/SecretInput.kt`). Honor routes password fields to its own secure keyboard, whose window carries `PRIVATE_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS` — which hides every `TYPE_APPLICATION_OVERLAY` while it is up, including the card holding the focused field. The IME loses its target the instant it arrives, so the keyboard flashes once and retracts, forever. Nothing in the app is wrong when this happens and the diagnostics all look healthy: `startInput` arrives with the right `inputType`, the connection binds, and `mInputShown` is simply `false` — `dumpsys window windows | grep HIDE_NON_SYSTEM_OVERLAY` is the line that says why. So **secret fields on the cards mask with a `PasswordTransformationMethod` and keep an ordinary `inputType`**, plus `TYPE_TEXT_FLAG_NO_SUGGESTIONS` for the part of the password variation worth keeping. Don't "fix" a masked field by giving it back its `TYPE_TEXT_VARIATION_PASSWORD`; `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` is not a way out either, since it still carries the password bit.

### Screen perception

`ScreenController.dumpUiTree()` does **bottom-up pruning** so the tree stays small enough for an LLM context: drop invisible/zero-size nodes, drop decorative leaves with no id/text/desc and no interactive flag, hoist single-child layout wrappers, keep containers with ≥2 surviving children. `verbose=true` disables all of it. Class names get common `android.widget.` / `androidx.*` prefixes stripped. `AccessibilityNodeInfo` objects are recycled in `finally` blocks throughout — preserve that when editing.

After pruning (non-verbose only), `rebuildIndexAndList` **replaces the nested tree with the element outline** the brain actually reads — the nested JSON was only ever scaffolding, and the indentation preserves the part of it that carried meaning. Stats fields (`nodes_total` / `nodes_kept` / `nodes_useful` / `surface` / windows diagnostics) survive; the tree bulk does not. Containers earn a line of their own here, which is why the trim drops leaves and never containers.

Screenshots return base64 PNG over the wire (downscaled to 1280 long side — see the coordinate protocol section); the top-bar tool buttons instead write to `getExternalFilesDir(null)/captures/` (no storage permission needed, reachable via `adb pull`) and return only a summary.

### Voice pipeline

`AsrController` / `TtsController` are thin state machines gluing `AudioIO` (raw PCM 16-bit LE) to hand-rolled ByteDance 火山 clients. `HuoshanAsr` and `HuoshanTts` implement **custom binary WebSocket framing** — 4-byte header + event/seq + gzipped JSON payload — ported from other projects (wxBot's Python, electron-vite's TypeScript respectively); the class KDoc documents the wire format and event-code sequence, and is the only spec available. Don't "simplify" the framing.

Non-obvious: `AsrController.stopImpl` deliberately blocks on `waitFinished(3000)` because 火山 sends the final transcript ~500 ms *after* the client's `isLast` — closing eagerly loses it.

### Meeting recorder

`MeetingRecorder` holds the microphone for a whole meeting and hands back a **verbatim transcript**. The device has no model, so it does not summarise: the brain turns the transcript into minutes and puts them on screen with the existing `show_html` — that split is why the class has no notion of a "summary" anywhere in it.

Three things about it are load-bearing:

- **Why it rotates sessions.** `HuoshanAsr` reports a final exactly once, when the server answers our `isLast`. One session for one meeting would therefore produce nothing at all until the meeting ended, and any dropped connection in between would take the whole hour with it. So a segment lives ~2 min (`SEGMENT_SOFT_MS`, cut at a pause; `SEGMENT_HARD_MS` regardless, for the meeting where nobody stops to breathe) and its final is appended to `files/meetings/meeting-<stamp>.txt` the moment it arrives.
- **Why the new session opens before the old one is retired.** Retiring blocks up to `FINAL_WAIT_MS`, connecting costs a second or two, and doing either with no live session would punch a hole in the recording. Opening first also makes a failed rotation free — the old segment is still receiving, so the meeting carries on. There is no chunk buffer and there should not need to be one.
- **Finals arrive out of order.** A retired segment's final races the live segment's, on different WebSocket IO threads. A `TreeMap` keyed by segment index releases only a contiguous run, so the transcript can never silently reorder what was said — which is also why every segment must `settle()` exactly once even on error or close, or the flush stalls forever on the missing index.

`meeting.*` is deliberately **not** under `screen.*`: recording is not operating the device, and the ball must stay where the user can reach it. Reaching it is the only control they have — the recorder holds the mic and the wake gate is closed, so they cannot ask for anything by voice. A ball tap (`startTurn`) or stop (`stopEverything`, from the ball's triple-tap since `■` was removed from the bar) therefore *ends the meeting and returns*. That is also a bug fix: `AsrController.isActive()` is literally `audio.isStreaming()`, which a meeting sets, so without the intercept a tap would fall into the "off" half of `asr.toggle()` and tear the recording down with no error anywhere.

When the *user* ends it the brain doesn't know, so the device asks for the minutes itself — and asks on **`asr.final`**, not on an event kind of its own. An event would have to be taught to the brain separately (see above) or it is silently dropped, which from the tablet looks exactly like the ball doing nothing; `asr.final` is the one channel every brain already answers, and it already lights the glow and starts a task. `ScreenBodyService.meetingMinutesQuery` composes that turn, and it is **prompt** — it is everything the brain knows about why a transcript just appeared, so it names `show_html` explicitly and says why the meeting stopped. The cost, which is the reason it reads the way it does, is that the request enters the brain's context as something the user said. The brain's own `stop_meeting` sends nothing — it already has the transcript in the tool result, and a second copy would have it write the minutes twice.

`stopped_by` is narrated to the user by the brain, so it has to be true: `user` (a ball triple-tap — the only stop control left since `■` was removed, see "Startup self-check"), `focus` (something took the audio floor — a phone call), `brain` (`stop_meeting`), `shutdown` (service going away). The focus case originally reported itself as `user`, which had the brain tell people they had touched a ball they hadn't.

**The device must not speak during a meeting**, and `ScreenBodyService`'s `onFinal` gates `tts.speak` on `!meeting.isActive()` for it. Two independent reasons, either sufficient. The speaker is an inch from the microphone, so a spoken reply lands in the transcript. And — the one that actually bit — `TtsController` holds its own `AudioFocusGate`: **a second `AudioFocusRequest` from this same process displaces the first**, because Android's focus stack is per request, not per app. So speaking fired the recorder's focus-loss callback and ended the meeting about a second after it started, as the brain read out "好的，开始录音了". Anything new that takes audio focus while a meeting can be running has the same problem.

Recording **unfolds the card to full screen** and writes the clock plus the tail of the transcript to the live row (`renderLive`, 1 Hz for the clock and on every partial for the words). The tail is a window, not a second copy: committing each of ~30 segments as its own `ChatHistory` row would evict the real conversation from an 80-row history, and the file on disk is the record. The unfold goes through `dispatcher.expand()` rather than `window.setCompact(false)` because the dispatcher caches whether it has already folded.

### The robot dog (`dog.*`): a peripheral on a one-way cable

The dog is not this device. It hangs off USB OTG on an Arduino Nano dongle that
forwards single ASCII characters over 2.4GHz to a robot the tablet cannot see,
and it *holds whatever it was last told until it is told something else*. So
"stop" is not an optimisation — it is half the protocol. `dog/DogLink.kt` is the
cable (find the dongle, get USB permission, send a char, read `X OK` /
`X NO-ACK`); `dog/DogMotion.kt` is the part that knows what a *move* is and
guarantees every one of them ends.

**Nothing in `dog.*` blocks for the length of the motion it starts, and that is
the load-bearing decision.** `BodyWsClient` serves requests on a
`newSingleThreadExecutor` — one at a time — so a `dog_move` that slept out its
own duration would also be holding back the `dog_stop` that interrupts it, and
a user shouting "停下" would wait for the dog to finish what the brain asked
for. So `dog_move` returns the moment the command is out and a scheduled timer
sends the `J`. Two consequences worth knowing before editing anything here:

- **A second `dog_move` replaces the first rather than queueing.** That is why
  `dog_sequence` exists: multi-step motion is a routine, not a series of calls.
  A brain that sends `dog_move` twice has asked for one move.
- **Timers race cancels.** Move #1's auto-stop can be inside its "write J" while
  move #2's "write K" is on its way out; if the J lands second, the dog stops
  the instant it starts. Every scheduled stop therefore carries the `generation`
  it was armed under, and the generation check plus the write happen inside one
  `motionLock` critical section, so a stop either wins outright or aborts.
  `onLost` deliberately does *not* bump the generation — doing so would
  invalidate the stop timer of a move whose cable came back, leaving that move
  with nothing to end it. It clears the state instead, and the armed timer
  no-ops because there is nothing left to stop.

`dog.*` is not under the `screen.` prefix, like `meeting.*` and for a stronger
reason: the dog is not this device, so driving it must not fold the card the
user happens to be reading.

**The tablet's own senses, and the dog's.** The dog is bolted to the
tablet, so the tablet's gyroscope *is* the dog's — `dog/DogSense.kt` reads it.
Two things hang off DogSense:

- **`dog_turn` is the one closed-loop motion.** It drives until the measured
  angle is reached instead of dividing a "full circle ≈ 8.7 s" constant that
  drifts with battery, floor and stride phase. It still does not block — the poll
  loop runs on `seqExec` and the tool returns as soon as the first command is
  out. The measured result therefore cannot be in the reply; it lands in
  `dog_status.last_turn` after a `COAST_MS` wait, because the dog is *still
  rotating* when the loop writes `J` and reporting the angle before it settles
  would report a number the dog never stopped at.
- **The fall guard is a reflex, deliberately not a decision.** A round trip to
  the brain is seconds; a dog that has gone over does not have seconds. So a
  motion whose tilt crosses `FALL_TILT_DEG` is stopped on the tablet within
  `GUARD_MS`, and the brain finds out via `aborted_by`.

Two traps in there. `SensorManager.getOrientation()`'s azimuth is **singular when
the device is near-vertical**, which is exactly how a tablet sits strapped to a
dog's back — so yaw is taken as the bearing of a device axis projected onto the
world horizontal, with the axis chosen once per turn as whichever is furthest
from vertical. And it is `TYPE_GAME_ROTATION_VECTOR`, **not**
`TYPE_ROTATION_VECTOR`: the latter fuses in a magnetometer sitting inches above
four servos. Losing north costs nothing because every question asked here is
relative.

`dog_turn` checks `link.status().open` after its first write and refuses before
starting the loop, the same way `move` does. Skipping that check is not a missing
nicety: a turn that "starts" with no dongle spends its whole timeout measuring
zero degrees and then reports the dog as stuck or out of range, sending the user
to inspect a machine that is fine.

**The dog's own senses ride home on the acknowledgement.** The 2.4G link is
half-duplex by configuration, not by nature: the dog's radio already sends an
ack frame for every packet it receives, so `Telem_Push` (dog firmware) attaches
four bytes to it — magic, distance, battery, validity flags. **No extra
transmission, no change to the main loop's timing.** That is why this route was
taken over switching the module to TX mode, which would mean going deaf to
remote commands for the duration.

Three consequences that shape the API:

- **It requires both ends, and the dongle negotiates which.** ACK payloads need
  matching `FEATURE`/`DYNPD` config at both ends; a mismatch is not "no
  telemetry" but a dead link, and a dead link is a dog that cannot be told to
  stop. So the sketch does not assume — it sends in `dpl`, and on failure
  reconfigures to `legacy` (plain 32-byte, original firmware) and retries,
  keeping whichever worked. `m=dpl|legacy` on every reply line says where it
  landed. Both ends also degrade alone: the dog's `NRF24L01_EnableAckPayload()`
  reads FEATURE back and reverts if the module refuses; the dongle reports only
  what actually arrived (`telemetry.supported`).
  **Flash the dongle first** — the reverse (new dog, old dongle) is the one
  combination with no fallback, because an old dongle has no negotiation and
  retries to exhaustion against ack payloads it cannot accept.
  *Verified on hardware 2026-09-24:* against a stock-v1 dog the dongle settled
  on `m=legacy` and got clean acks — **so the fallback does work**, `dpl` fails
  and `legacy` succeeds. With v2 flashed it negotiates `m=dpl` and telemetry
  arrives: 15/15 acks, `d=` tracking a hand at 7–48 cm, `b=23~25`. Every
  NO-ACK before that was **the dog being switched off**, which cost a control
  sketch, a raw-SPI register dump and a 12-combination rate×power sweep to
  establish — check the dog is powered before suspecting anything here. A few
  seconds of NO-ACK right after the dog powers on is the radio settling.
- **Every reading is stale by construction.** The dog speaks only when spoken
  to, so `telemetry.age_ms` is part of the reading, not metadata. `dog_sense`
  exists to refresh it — one frame, one ack, and the only `dog.*` call that is
  safe mid-motion because it re-sends the *current* frame rather than a new one.
- **`supported: false` is not zero.** An unflashed dongle, an unflashed dog and
  a flat battery must not read alike. Null propagates to the brain as "cannot
  see", and the schema text says to say so rather than report a number.

**Speed and stance are settings, and the dongle holds them.** On the dog they
are independent `if`s sitting *beside* the direction chain, so a frame carrying
only a speed byte falls through to the standing branch — changing speed would
stop the dog. The dongle therefore keeps one persistent frame and mutates only
the bytes a given command owns. `dog_speed` / `dog_pose` do not touch
`generation`, do not arm a stop, and take effect from the next motion onward.
The exception is `J`, which clears the stance too: the raised-stance branch runs
ahead of the wheel branches and only poses the legs, so a stop that preserved it
would look stopped without being stopped.

**The one boundary the app cannot close.** The dog has no failsafe of its own:
if the dongle is unplugged or loses power while the dog is moving, the tablet
cannot send the stop that ends the move, and the dog finishes it. `onDestroy`
sends `J` first, before anything else in teardown, and every motion has a
scheduled end — but a cable pulled mid-move is out of reach. Say so plainly
rather than implying the tablet always has the last word; closing it for real
means a watchdog in the dog's firmware.

Two smaller things that are load-bearing in `DogLink`: the port is opened
**lazily** (nothing happens on a tablet with no dongle plugged in), and opening
**costs seconds** because asserting DTR resets the Nano — so `ensureOpen` waits
out the reboot and reads the banner, which doubles as a diagnosis (`READY` means
dongle *and* radio are up; `ERR: NRF24L01 not responding` means the dongle is
alive but its radio module is not, i.e. fix the dongle's wiring, do not blame
the dog). Device discovery runs the library's table first and then a narrow
second pass, because Arduino clones carry bridges the library has never heard of
— CH9102 (`0x1a86:0x55d4`) is absent from its `UsbId` table and it is what the
newer Type-C Nano boards ship. That second pass only claims a QinHeng part
presenting one vendor-specific interface: a `Ch34xSerialDriver` can be built
around *any* device and will hand back a port, so trusting it blindly would let
us seize the tablet's own hub.

Lock order is `DogMotion.motionLock` → `DogLink.lock`, never the reverse —
which is why `onLost` is invoked outside `DogLink`'s lock (the detach broadcast
is the path that would otherwise deadlock). `DogSense` is a leaf: it holds only
its own registration lock and never calls back into either, so reading it from
inside `motionLock` is safe. `attach()` is still called outside, because
registering listeners is the one part of it that is not instant.

### Floating UI

`FloatingWindowUi` is the live overlay: a `TYPE_APPLICATION_OVERLAY` window holding the emotion ball, a top-right tool bar (`Tool` enum: swipes, back, home, ui-tree, screenshot), a gear button, and a subtitle band. Callers drive it through `setState(State)`, `setSubtitle(text, SubtitleKind)` and `setTouchable(bool)`; it posts everything to the main-thread `Handler` internally. Hit-testing on the ball is **circular**, sized to match the rendered sphere — touches outside the circle fall through to whatever is underneath.

The ball itself (`ui/ball/`) is hand-rolled **OpenGL ES 2.0** on a `TextureView` (not `GLSurfaceView`, so the subtitle and toolbar composite above it without z-order hacks) with its own `HandlerThread` GL context. `EmotionState` holds per-frame animation state and is ticked on the GL thread; `Mood` maps emotional/device states to face parameters. UI→GL communication is via `@Volatile` fields only. `gl/` contains hand-written mesh/shader helpers: `Mesh` uses a fixed **interleaved 8-float (32-byte) stride** (pos3/normal3/uv2) that shaders assume — changing it means updating every `bind()` offset.

Long-running work must stay off the main thread: `ScreenController.tap/swipe` block on `dispatchGesture` for up to ~2 s and will ANR the UI thread. `ScreenBodyService` runs top-bar tools on a dedicated `ScreenTools` executor, and `BodyWsClient` hands requests to a worker so the recv thread keeps answering pings.

### The full-screen faces

`HtmlActivity`, `LookActivity`, `ScanActivity` and `SelfCheckActivity` are the only places the user sees a *finished* artefact rather than the ball, and they share `Theme.Body.Fullscreen` (`Theme.Body.Stage` extends it, for the four that animate their own enter/exit — see `ui/Stage.kt`). `SelfCheckActivity` is on `Theme.Body.Stage.NoPreview` on top of that, because with the service up it finishes without drawing a frame and a starting window would flash black — see "The launcher icon opens two doors". That theme exists for two bugs that came free with `Theme.Body`: its `android:statusBarColor` is `?attr/colorPrimaryVariant` = **purple_700**, so a purple stripe sat over every one of them; and `DayNight`'s light `windowBackground` made each open **flash white** before the dark content drew. The theme pins `#0F172A`, transparent system bars, and dark bar icons. Put any new full-screen Activity on it.

`ui/CameraChrome.kt` is the shared chrome for the two camera screens (`ViewfinderView` / `StatusPill` / `closePill`), extracted because both had grown their own `FrameLayout + PreviewView + TextView` **sized in raw pixels** — legible on the phone it was written on, postage-stamp sized on the tablet. Everything in there is dp, and `HtmlActivity` uses the same `closePill` so the one control the user can always rely on looks identical everywhere.

Two things in it are not styling:

- **The shutter flash is a privacy promise.** `LookActivity.sampleFrame` pulses the viewfinder corners and moves the status line to 「看到了」 because the camera is the most invasive thing this device does, and before this the user had no way to tell *when* a frame was actually taken. `sampleFrame` runs on the dispatcher thread, so every UI touch in it goes through the companion `mainHandler`.
- **The first frame after a bind waits out the exposure ramp.** `EXPOSURE_SETTLE_MS` (1.5 s, measured on this hardware) is counted from `boundAt`, not from the request, so a monitoring loop sampling every 30 s never pays it twice. Shooting immediately returns a washed-out or near-black frame, and the brain has no way to tell that from the actual room — it just describes the dark. `rebind` clears `imageCapture` and re-stamps `boundAt` *before* republishing it, because switching lenses restarts the ramp and `sampleFrame` gates on `imageCapture` being non-null.
- **Closing is honest.** Both camera screens carry a ✕完成, and closing `LookActivity` — by pill or by back key — stamps `userClosedAt`. `device.look` reads it: within `USER_CLOSED_GRACE_MS` it returns "用户刚把摄像头关掉了,先问过他" instead of the old "camera not ready yet — retry", which was false *and* told the brain to put the lens straight back in the user's face. Same rule as the meeting recorder's `stopped_by`. `LookActivity.close()` (the brain's own stop) sets `closingByBrain` so it doesn't stamp.

`ScanActivity`'s cancel path writes `LastResult = {"error":"cancelled by user"}`. That is a bug fix, not politeness: `device.scan` polls `LastResult`, so backing out of a scan used to leave the poll spinning to its full 30 s timeout with nothing to report.

**Frosted glass is not available** for these pills, so don't try again. `RenderEffect` (API 31+) blurs a View's *own* content, not what is behind it; blurring behind requires `Window.setBackgroundBlurRadius`, which is window-level and cannot be scoped to one floating child. Translucent fill plus a 1 dp `#33FFFFFF` stroke is the approximation — and the stroke is load-bearing anyway, because the brain picks the page background and a dark pill on a dark page vanishes. (The *cards* do get a real blur — see `ui/Glass.kt` below. Each of them is a whole window, which is exactly the thing a pill is not.)

**`show_html` does not block.** It used to sit in a latch until the user tapped 完成 — up to five minutes — and because `LocalBrain` runs a turn on one thread and runs tools on it synchronously, that latch held the *conversation*: the user's next sentence was accepted and then queued behind it. It now returns as soon as the page is up, with `closed: false`, and the news that the user finished with the page arrives by a second route: the local brain gets a silent `LocalBrain.note()` (readable by the next turn, never spoken — a turn here would mean a model call and a sentence every time anyone dismisses a page), and a hub gets a `page.closed` event it may ignore. Two consequences worth knowing. `HtmlActivity` keeps exactly **one** page (`pageUp`), so a second `show_html` while one is up is **refused** rather than stacked — the shared static file would otherwise lose to whichever activity read it last. And the ball's perch, which used to be released by that same latch, is now released by the close callback.

The device injects **no CSS** into `show_html`; styling belongs to the brain. So the house style (slate tokens, the `#9DB4FF` accent, an explicitly-named CJK font stack) lives in the `show_html` **description** in `ToolSchemas` — that prompt is the only lever on page quality, which matters most for the small models this runs against. `HtmlActivity` only fades the page in once `onPageFinished` lands, with a 2.5 s backstop because a top-level script that throws can leave that callback pending forever, and half a page beats a black screen.

### The cards share one look (`ui/Glass.kt`)

`SettingsUi`, `VaultUi`, `WakeEnrollUi` and `SelfCheckUi` take every colour, drawable, interpolator and animation from `Glass`. They share it because they are reachable from one another — settings opens the vault and the wake-word card *on top of itself*, and the self-check card opens settings *on top of itself* — and two different dark palettes stacked like that read as a bug before they read as a style. A fifth card means using `Glass`, not picking hex values.

**The base is pure black and everything above it is white at a low alpha**, which is not only taste. These are overlays, and what is behind them is some other app — a white document or a night-mode terminal. A palette of *opaque* greys has to pick one of those to look right against; a stack of translucent whites over black darkens whatever is behind it and then lays the same relative steps on top, so the hierarchy (card → panel → recessed well → segment thumb) survives both.

**That hierarchy assumes a near-opaque black card underneath it, so a page on the backdrop needs a fourth surface: `Glass.glass`.** `GLASS` is `#1FFFFFFF` — `panel`'s shape and a different fill on purpose. `panel` is 6% white laid inside a 95%-black card; `glass` is 12% white laid on a blurred photo at ~13% mean luma under the scrim. No single alpha serves both: 6% white on the backdrop is invisible (a card that has quietly stopped existing), and 12% white on the card is a grey box floating in a black field. The sheen does more of the work here too — at that fill the top-lit edge is the only thing saying the rectangle sits *on* the image rather than being a window cut into it.

**It has no caller at the moment, and that is a fact worth recording rather than a reason to delete the two constants.** `glass` existed for one day of the self-check page being a fullscreen surface on `Backdrop`; that page is now a `Glass.card` and its rows are `panel` — see "Startup self-check" and `Glass.GLASS`'s own note. The distinction survives because the *fact* behind it did not go anywhere: `Backdrop` is still what `TextInputActivity` and the launcher self-check host paint themselves with, and anything placed on top of it needs this fill and not `panel`. Reach for `glass` whenever the parent is the backdrop, never as a general lighter `panel`. For a whole fullscreen surface (a page's own background) it is still `Backdrop` plus a `CENTER_CROP` `ImageView` — `glass` is for the cards that go *on* that. Put any new full-screen Activity on `Theme.Body.Fullscreen`.

**The blur is real, and `Glass.frost` gets exactly half of Android's blur API.** `FLAG_BLUR_BEHIND` + `LayoutParams.blurBehindRadius` is public and works. The other half — `backgroundBlurRadius`, the one clipped to the window's own rounded bounds, which is what would make the card itself a frosted *pane* — is a `@hide` field whose public door is `Window.setBackgroundBlurRadius`, and these overlays have no `Window`: they are bare Views handed to `WindowManager.addView`. Reaching for it does not compile. So the blur is unclipped and the whole screen behind the card softens, which for a centred modal is the effect you wanted anyway. It is also **optional at runtime** — `isCrossWindowBlurEnabled` is false on battery saver, on devices that cannot afford it, and whenever the developer option is off — so `frost()` returns whether the compositor agreed and the caller picks its fill from the answer: `CARD_FROSTED` (60% black) when something is blurring, `CARD_SOLID` (95%) when nothing is and a translucent card would just be a smear of the app below. Call it **before** `addView`; the flag has to be on the params the window is created with.

Translucency also moves the untrusted-touch number in the safe direction. Every overlay this app owns counts toward the 0.80 ceiling above which the platform silently discards the brain's injected gestures; the old cards were fully opaque, so this can only help.

Nothing here animates for decoration — each one answers a question the user would otherwise have to ask, and all of them are ≤320 ms so someone not looking for them doesn't notice:

- `enter` / `exit` say which card appeared and where it went. **`exit`'s completion has to run exactly once and has to run even when the animation never finishes**, because a detached view never delivers its end action and what hangs off that callback is `removeView` plus the `OwnCard` bookkeeping — leaking that count leaves the device permanently claiming one of our cards is covering the screen. Hence the run-once `Runnable` plus a `postDelayed` backstop, on a *shared* handler rather than the view's.
- `SettingsUi.hide()` parks the departing view in a `closing` field, so a `show()` landing during the 170 ms exit takes the old card down instead of stacking two windows.
- The segmented pickers have a **sliding thumb** rather than a jumping highlight: it says *this same control* changed value, instead of one control vanishing and another appearing. It is a free-floating `View` in a `FrameLayout` placed from measured cell geometry, so one implementation serves equal-width and wrap-width cells, and the cell text colours lerp in step with it. The non-animated `place()` path re-runs from an `OnLayoutChangeListener` and **must** no-op when nothing changed, or requesting layout from inside a layout pass starts a loop.
- `expand` / `collapse` say the rows that just vanished were folded away, not deleted. The brain section flips both halves *simultaneously* rather than in sequence, so the card's total height barely moves while one block shrinks and the other grows.
- The language swap cross-fades (`fadeOut` → rebuild → `fadeIn`) — same run-once backstop, same reason.

**Everything else the user sees takes the same palette**, so there is one place to change a colour: the unfolded card and its scrollback (`FloatingWindowUi`, `HistoryListView`), the assistant's own dialogs (`CardUi`), the camera and page chrome (`CameraChrome`, `HtmlActivity`) and `Theme.Body.Fullscreen`'s window background. The one deliberate exception is the `show_html` house style in `ToolSchemas` — that is prose handed to the brain about the page *it* composes, not our chrome.

Four things about that spread are not cosmetic:

- **The fullscreen card brings its own background (`ui/Backdrop.kt`).** Frost was the right answer for a modal over a document and the wrong one for this card: it is fullscreen, it stays up for minutes, and what came through it was the user's home screen — icons and all, dissolved behind the text. So the card now covers the window with one fixed image, blurred by us and darkened by a scrim. Three consequences: the compositor blur is *dropped* while it is up (nothing left behind to defrost — a full screen of GPU defocus nobody can see, and it makes the card look the same on the many devices where `isCrossWindowBlurEnabled` is false); the blur is a three-pass box blur on a 512-px-wide copy, not `RenderEffect` (see below) and not a downscale-upscale, which smears rather than frosts; and the scrim is *derived* from the image — `1 − 0.13 / meanLuma`, clamped to 55–85% black — so 0.13 is the composite the card already had, and swapping `res/drawable-nodpi/backdrop.jpg` needs no new constants. Decode and blur run on a worker at build time, and the window is built folded, so it never touches the main thread.
  - The scrim goes on the **image's foreground**, not the root's background. They look interchangeable and are not: the image is a child of the root, so a root background is behind the very thing it is meant to darken. The first version did that and shipped a card at raw photo brightness — measured on the tablet at mean luma 0.40 against a design of 0.13.
  - **The self-check page reuses that same image and that same `scrim()`**, via the same `ready()` / `warm()` handshake. It is a second surface in the same product, so "a lookalike dark background" was the one option not on the table — two surfaces that nearly match read as a rendering bug, where two that deliberately differ read as two things. `stage` also makes this cheap: the backdrop is added once and never rebuilt, so changing language swaps the content above it rather than re-decoding a bitmap.
- **The fullscreen card frosts, the corner ball must not, and `applyWindow()` owns the switch.** It is one window in two shapes, so `Glass.frost` has a four-argument overload that can take the flag back off — applied with `updateViewLayout` after the fact, which does work, unlike `backgroundBlurRadius`. A 168 dp blurred square following the ball around is a smudge, so it is dropped when folded. It is *also* dropped whenever `setVisibleForCapture(false)` is in force: the window is at alpha 0 there, but the compositor would still defocus what is behind it, and what is behind it is exactly the screenshot the brain is about to read. `blankedForCapture`'s existing 80 ms settle covers the params change.
- **`FLAG_LAYOUT_IN_SCREEN` is what makes fullscreen mean fullscreen.** Without it a `MATCH_PARENT` overlay is laid out in the *content* frame — the display minus the system bars — so the card stopped short of the top and the status bar sat on a strip of the user's wallpaper. With the flag the card runs under the bars, which is why `insetTopBar()` then pushes the control cluster down past the status bar and cutout; otherwise the clock lands on `⋯` and `✕`.
- **A child View cannot frost.** The scrollback rows and `CardUi` are children of a window, not windows, so they get translucent film + gradient + hairline and no blur — the same approximation, and for the same reason, as the pills above. `CardUi` in particular is a child of the ball's window and can never blur, which is why its base is `Glass.CARD_SOLID`: that is the fill the cards themselves fall back to when the compositor refuses, so it is the same value for the same reason rather than a second decision.

### Getting out of the way is the default

`CommandDispatcher.dispatch` folds the card into the corner ball before running **any** `screen.*` command — by prefix, so a new method there inherits it. It is not a tool anymore: `reveal_screen` is deleted, because a brain that forgot to call it left the user staring at our face instead of the app being operated, and its `seconds` deadline meant the assistant deciding how long the user gets to look. The fold has no deadline; the user taps the corner ball to talk (`dispatcher` does not expand), and long-press unfolds (`dispatcher.expand()`). **The service starts *unfolded*** — `onServiceConnected` calls `window.setCompact(false)` after `show()`, so the first thing the user sees is the full card, not a ball in the corner. It used to start folded, on the reasoning that a freshly enabled service should not freeze the tablet with a full-screen touchable window; what answers that worry is `ensureCompact()`, which folds the card before any `screen.*` command drives another app, and it was never touched. The only remaining unfold paths are therefore the service's own birth and a long-press on the ball. If you add a `screen.*` method that does *not* drive the device, keep it out of the agent-driving path.

Handlers otherwise must **not** block for the length of a timed effect — `reveal_screen` did, and a ten-second tool call reads to the brain as work still in progress, so it sat silent while the user stared at the thing the reveal was for. `screen.wait` is the one deliberate exception, because there the waiting *is* the request and returning early would answer the wrong question. It pays for that by being bounded: `WAIT_MAX_MS` is 20s against `body_hub`'s 30s request timeout, and the element dump plus screenshot that follow the sleep are inside the same request (~2.5s measured, more on a page that never settles). Anything with a duration that is *not* a wait still arms a `Handler` and returns.

The edge glow is a **separate** signal and tracks the task, not the fold: `beginTask()` on the first request out of the device (or the first `progress` frame) until `endTask()` on the final `message` frame or the user hitting stop. There is no idle timer in between — thinking makes no tool calls, and that is exactly when the light has to stay on. A 5-minute backstop exists only for a brain that vanishes mid-task.

### Startup self-check

Every failure this app has is silent by construction. A missing `SYSTEM_ALERT_WINDOW` grant means `FloatingWindowUi.show()` catches the `addView` failure and returns false — the service runs perfectly with nothing on screen. A backend that cannot be reached means the ball spins once and says nothing. A notification grant that survived a reinstall reads as **on** in Settings while the listener was never bound, so every notification is dropped. Each has been reported by the user as "坏了", and each is decidable before it happens.

`device/SelfCheck.kt` is that decision, in one list, and it is the **only** place the list exists — and there is **one renderer** for it (`ui/SelfCheckUi`) behind all three readers, so no two of them can disagree, which is the failure a second hand-written checklist would have within a week:

- **`ScreenBodyService.runStartupSelfCheck()`** — the nudge. Runs on a worker thread (the two probes do network I/O), and puts up **one** `CardUi.ask` listing everything wrong, with 去处理 → the card. Deduped by *content*, not by time: the signature is the set of `id:level`, an unchanged set stays quiet for `REPEAT_AFTER_MS` (6 h), and any change — a new failure, or one fixed — gets through immediately. A tablet that reboots on a dog twenty times a day must not nag twenty times, and a user must not have to wait out a window after fixing something. When everything is clean the record is *cleared*, so a relapse reports at once. It bails if `window.isShown()` is false: the overlay grant is one of the things being reported, and a card needs the very permission it is about to complain about.
- **`ui/SelfCheckUi`** — the card, and the only code that draws a row. `✓` in the control bar opens it through `ScreenBodyService.openSelfCheck()`. Fix button per row, paints from `SelfCheck.local()` (no network, instant) then fills in the probes behind it, and offers 重新检查 because the user is being sent to *other* apps to change things and a two-tap fix should not become a round trip through the launcher.
- **`ui/SelfCheckActivity`** — the launcher icon, and the **only** surface in this app that runs while the assistant does not: everything else is an overlay owned by an accessibility service, so with accessibility off there is no ball, no card, no window and no way in at all. It is also the only place the `accessibility` row can be *red and read*. With the service **up** it draws nothing — it calls `dispatcher.expand()`, the same thing a long-press on the ball does, and finishes; with the service **down** it hosts the same `SelfCheckUi` card on its `stage` instead of in a window. See "The launcher icon opens two doors".

Five things in the design are load-bearing:

- **Levels, and only two of them may interrupt.** `FAIL` (dead now — the complaint will be "没反应"), `WARN` (works, but something degrades silently), `NOTE` (a fact worth being able to read — the wake phrase is not taught yet, a probe was skipped), `OK`. `needsAttention` is `FAIL || WARN`, and `NOTE` **never** raises a card. Without that split this is nagware and the user learns to dismiss it unread.
- **`Fix` is a closed set of values, not a lambda.** `Screen(action, data)` / `Link(url)` / `Grant(permissions)` / `Adb(command)` / `OurSettings` / `Nothing`. `actionable` is defined from that set. Values rather than closures because the list is built off the main thread and consumed up to seconds later — an `Intent` described at 200 ms is still good at 3 s, where a captured `Context` would be a leak waiting to be filed as a bug. `Link` is its own member rather than `Screen(ACTION_VIEW, url)`: the button it produces is a different word (去下载 is not 去开启), and a Settings-labelled button that opens a browser is the small lie this list exists to avoid.
- **`Fix.Adb` deliberately has no button.** `WRITE_SECURE_SETTINGS` is `signature|privileged|development`, so `adb shell pm grant` is the only way and there is no switch in Settings to find. The row shows a **selectable** command and says it is an adb errand — a user hunting Settings for a switch that does not exist there is the failure mode being prevented.
- **`Fix.OurSettings` is the one door that can be missing, and the card says so instead of drawing it.** Our settings sheet is an overlay the *service* owns, so with accessibility off there is nothing for 打开设置 to open. `SelfCheckUi.serviceUp` reads that off the `accessibility` row — one source of truth, never a second test that could disagree with the red row directly above it — and when it is false the row gets **no** button and a note above the list says why. Same rule as `Fix.Adb`: a button that does nothing is worse than no button.
- **The keyboard row tests the exact component this app switches to.** `typeViaAdbKeyboard` is a broadcast to `com.android.adbkeyboard`, which has to be an *enabled* IME for the `commitText` to land — so "can the assistant type into other apps *this way*" is decided by two facts on the device that had no way of announcing themselves, and the symptom is an error that blames the app being driven. One row rather than two, because the three states are three steps of one errand and the button changes as they are completed: not there → 去下载, there but off → `INPUT_METHOD_SETTINGS` (the screen with the toggle, not `showInputMethodPicker`, which `ImeSwitch` refuses on purpose), enabled → OK. A permanent red "not installed" above a green "enabled" is what two rows would buy. Installed-ness is asked through `getInputMethodList()` — "is there a keyboard *in* that package", not "does the package exist", so a truncated APK does not send the user to a keyboard list that cannot contain it — and that only works because the manifest declares `QUERY_ALL_PACKAGES`; enabled-ness is the **exact** id, because a build with a renamed service is one this app cannot switch to and a green row beside a dead `type_text` is the lie this whole page exists to prevent. The download is the **v2.5-dev release asset**, not the `master/ADBKeyboard.apk` every blog post links to — upstream itself labels that one "[Old]", and v2.5-dev exists to fix it on android-36, which is what a newly bought tablet arrives on. Both APKs were read before choosing (`com.android.adbkeyboard.AdbIME`, `ADB_INPUT_TEXT`/`ADB_CLEAR_TEXT`/`ADB_EDITOR_CODE`, `commitText` — the same component and actions `ScreenController` names). It is **not** vendored into `assets/` and installed through a `FileProvider`, which would make it one tap instead of three: redistributing someone else's APK is a licensing decision rather than a build detail.
- **The two typing rows are graded by whether the other channel is open, and `imeChannelOpen()` is the single source for that.** `adbKeyboard` and `secureSettings` are `WARN` when the accessibility IME is unavailable and **`NOTE` when it is** — the feature is not broken, the row has become a spare for Android 12 and below. They are graded identically to each other for the reason they always were: one broken feature must not be two different colours. A third row, `typing_channel`, is `NOTE` at every value and exists only to name which channel is in play; without it a user reading two grey rows about a keyboard they never installed has no way to learn that typing works anyway. `imeChannelOpen()` reads `FLAG_INPUT_METHOD_EDITOR` off the **running service's** `serviceInfo` rather than trusting the config XML, and returns true when the service is not up (pre-33 is the only hard no) — a checklist should not invent a failure it cannot observe. Note `Fix.Adb` on the `secureSettings` row stays drawn even at `NOTE`: the command is still the only way to get the spare working.
- **The backend probe distinguishes three things the symptom cannot.** `GET /models` decides all of them without spending a token: URL wrong → the connection fails; key wrong → 401/403; **model name wrong → the name is not in the returned list**. That last one is the likeliest, because it is the one a person types by hand, and its error names *what was on offer* rather than a bare 400. Gateways with no `/models` (404/405) fall back to a `max_tokens: 1` + thinking-disabled chat request, and an empty list on a 200 is read as "reachable" rather than invented into a model complaint. The voice host gets a bare **TCP connect** and not a credential check — reproducing the 火山 handshake here would mean a second copy of that protocol, and what this row is for is the failure this device actually had (`ASR ws connect timeout`, network up, host unreachable), so a green row beside a failing ASR means the key, by elimination.

The service's own start passes `startBrain(announce = false)`, so the no-brain state is reported by the self-check card **with all the other rows** instead of by a second card arguing about the same fact. `reportNoBrain(what, card)` still writes the `ChatHistory` row either way — that is a record, not an interruption. A factory reset still announces normally: the user just wiped the config and is owed the news that the device is mute.

#### Why it is a card and not the page it used to be (2026-09-28)

`✓` sits one glyph away from `⚙` in the control bar and that is the whole argument: those two buttons open *our own surfaces* rather than acting on the screen, so they have to open the same kind of thing. The settings sheet is a centred `Glass.card`; a self-check that folded the assistant away and took the entire screen — with a background image of its own — made two adjacent buttons behave like two different products. It is now the same card chrome: same `cardWidth()`/`cardHeight()` (both `minOf(screen − 2×20dp, 98% / 96%)`), same radius, same `header()` (26sp title, 13sp subtitle, `LangToggle`, 36dp `✕`), same `footer()` shape, same 24dp form inset.

The `■` stop button left the bar in the same change, and `✓` took its **slot** rather than being added beside it. Stop was the only key in that bar the ball can already perform — triple-tap fires the same `onStopClick`, and has since the taps were reduced to one vocabulary — and the pinned tips have always said so (三连击停止), which is what makes deleting it a simplification rather than a removal: the user loses a control they had two of and keeps the one that also works while folded. The bar reads `⋯ ✓ ⚙ ✕`.

**Two hosts, one builder.** `SelfCheckUi`'s `host` is null for the service's own case (it makes a `TYPE_APPLICATION_OVERLAY` window exactly as `SettingsUi` does) and non-null for `SelfCheckActivity`, which hands it its `stage`. They differ in exactly two things:

- **Where the card goes**, which is why `cardWidth()`/`cardHeight()` are computed from a factored-out `screenW()`/`screenH()` rather than as "MATCH_PARENT minus a gutter" in the Activity branch. Those two are *nearly* the same number and not the same number, and "nearly" is how a card a user opens from two identical buttons ends up two different sizes.
- **Whether we may ask the compositor to blur.** The window host calls `Glass.frost()` and takes whatever it says (`CARD_FROSTED` or `CARD_SOLID`). The Activity host takes the frosted fill **unconditionally**, because that Activity is already showing a blurred, scrimmed `Backdrop` — the blur behind the card is real, we simply did not have to ask for it, and there is no window of ours to hand to `Glass.frost`. That reasoning is only honest because `installBackdrop()` is still there: without it, "frosted" is 60% black over the Activity's black window, i.e. a black rectangle.

`SelfCheckActivity` also stopped being the exit path it was. Both of its old callers now open the card, and `EXTRA_RESTORE_CARD` is gone with them — there is no fold to undo when nothing was folded.

#### The launcher icon opens two doors (2026-09-28)

The icon used to mean one thing — the checklist — and that was wrong, because on a working device the checklist is not what the user is asking for when they tap their app. It now branches in `onCreate`:

- **Service up** (`ScreenBodyService.get() != null && ballWindow() != null`) → `dispatcher.expand()` and `finish()`. That is *literally* the long-press-the-ball call, not a copy of it: two entry points must not be able to drift into meaning different things, and the icon is the one every other app on the device has trained the user to read as "open this".
- **Service down** → the card, as before. This is the case the Activity exists for and nothing about it changed: no ball, no card, no window, so this page is the only thing that can say *why* the equipment in the user's hand is doing nothing.

`ballWindow()` rather than just the instance, because a service that is alive but has not attached its overlay yet is not something to unfold — the same guard `openSettingsForUser` uses.

The icon became invisible on the working path thanks to `Theme.Body.Stage.NoPreview` (`android:windowDisablePreview` + `Theme.Body.Stage`), added for this Activity only. Without it the platform paints a `windowBackground` starting window before the first draw, so "tap the icon, the card grows" was "tap the icon, the screen goes black, the card grows" — a flash the long-press does not have. The cost is the *other* path: with the service off, the launcher now stays visible for as long as the card takes to build instead of being replaced by black immediately. That trade is taken deliberately — the working path is what happens every day, and `SelfCheck.local()` gives the card its first frame synchronously anyway.

#### The card is built in two passes, so anything drawn after `rebuild()` lands on the wrong card (2026-09-28)

Reported as: with the assistant's card up, tap `✓`, switch language, and **the whole list disappears** — close the card and everything is normal again, in the new language.

`SelfCheckUi.build()` only assembles the chrome: it leaves `hero` and `rows` empty and repoints those two fields at the *new* card. Filling them is `render()`'s job, and the caller did it a line after `rebuild()` returned. In the window host that is too early — `rebuild()` builds the replacement inside `Glass.fadeOut`'s end action (~110 ms later), so the list was drawn into the **outgoing** card's `hero`/`rows`, which were then removed with it, and the replacement arrived as a card with its header, its footer and nothing in between.

It survives testing on one host because the hosted path builds **synchronously** — the Activity branch calls `build()` on the current stack — and the user reaches that host from the launcher, not from `✓`. Two rules, and the second is the one that generalises:

- `rebuild()` now takes no arguments and owns the redraw: it captures `last ?: SelfCheck.local()` *before* branching and calls `render(again)` on each path after `build()` has run, inside the fade's end action where it belongs.
- **A `rebuild()` that cross-fades is asynchronous; anything that must happen to the *new* view tree has to happen in its `end action`, not at the call site.** `SettingsUi.rebuild()` has the same fade and does not have the bug only because its content is constructed inside `build()` itself.

`Glass.fadeOut` fires its `then` **twice** (once from `withEndAction`, once from a 300 ms `postDelayed` backstop) and has no `once` guard — the `if (root !== old) return@fadeOut` line at the top of `SelfCheckUi.rebuild` is what makes that harmless. `Glass.exit` carries an explicit `once` for the same reason.

**The rows are `Glass.panel` here, and that is a correction rather than a copy.** They were `Glass.GLASS` while the list was bare on the backdrop; inside a card the arithmetic inverts — `GLASS` is 12% white, which on a 60%-black card is a grey box, where `PANEL`'s 6% is the film that was measured for exactly this. A surface's fill is decided by what is directly underneath it and by nothing else; see the `Glass` section.

**Language is in the card's header, and the reason it is cheap is the same reason the findings can be reused.** `Finding` carries a `@StringRes Int` and a `detailArgs` list, never a finished sentence, so the list is **language-free** and sentences are rendered from it at draw time. Re-rendering the same list after a pick is therefore *correct* rather than a shortcut — and it is also free: had the rows held strings, switching language would mean re-running a probe that does network I/O, to change words about a fact that did not change. Three rules fall out of that:

- **`lctx` (`AppLocale.wrap`) is re-derived on every repaint, never cached in a `by lazy`.** Findings are language-free; the context you ask for a string is not. A lazily-created one keeps answering in the language the card opened in, which is precisely the half-translated surface `AppLocale`'s own docs warn about.
- **The language can change from two places while the card is up**, because the card's own 打开设置 row opens the settings sheet **on top of it**. So `SelfCheckUi.repaintForLanguage()` is called both from the card's `LangToggle` (which also tells the service, via `onLocaleChanged` → `FloatingWindowUi.onLocaleChanged()`, so the ball and the scrollback follow) and from `ScreenBodyService.openSettings()`'s `onDismiss` — the same news arriving from the other direction, repaint only and never re-announce, or the ball is rebuilt twice for one change. The Activity host has a third path and catches it in `onResume()` by comparing `VoiceConfig.uiLanguage()` against what it painted. That third path is narrow on purpose: with the service off there is no settings sheet to open (the 打开设置 row is not drawn at all — see `serviceUp`), so what reaches it is a change written while the user was off in a system screen, and the card's own picker hands its value to the host through `onLocaleChanged` so a resume after it does not rebuild the card for nothing.
- **It writes the same `lang` key through the same `VoiceConfig.save` as `SettingsUi`** — not a second preference, so "does it survive a restart" has one answer for both. Nothing caches the pick, and nothing needs to. The picker's two labels are `中` and `EN`, **hardcoded rather than pulled from resources** — a language picker that translates its own options is a picker you cannot read once you are in the wrong one.

It also has to sit *here* rather than only in Settings: this is where a user lands when the app is broken, and sending them through a different app's settings to change the language they are reading is the wrong trade for one row of chrome. `LangToggle` is a second *copy* of the control, never a second setting.

### Configuration

All settings live in one `SharedPreferences` file (`voice_prefs`), read/written through `VoiceConfig`. Three entry points: `VoiceConfig.load()` for the voice pipeline, `VoiceConfig.hubConfig()` for networking (blank URL means "don't dial out"), and `VoiceConfig.brainConfig()` for the local brain (`brain`, `llm_api_key`, `llm_base_url`, `llm_model`, `llm_thinking`, `llm_reasoning_effort`); none of them throws. `device_id` and `uid` are lazily generated once and persisted. Writes go through `save()`, which silently ignores keys not in `ALLOWED_KEYS` — add new keys there or they vanish. Changing anything in either config in `SettingsUi` restarts the brain on dismiss without bouncing the service.

`llm_api_key` **is** a preference, unlike the two constants below and deliberately so: the DeepSeek key is the user's own and gets rotated, so it has to be replaceable without a rebuild. Note the standing limitation this shares with every other *typed* field: `SettingsUi.saveAndHide()` and `save()` both skip blank values, so a field can be overwritten but never cleared. That is why the brain is an explicit `brain=hub|local` setting rather than "blank `hub_url` means go local" — the latter would have had no way back.

The settings card works around that limitation for the three settings that are really enums. `brain`, `llm_thinking` and `grounding` are **segmented pickers**, not text fields, and `saveAndHide()` writes every picker unconditionally (a picker's value is never blank, so `save()` can't drop it) while still writing only non-empty text fields. Two things to preserve when editing `SettingsUi`: the **leftmost option is the fallback** when the saved string matches nothing, which is why `grounding` lists 关 first and `brain` lists 本机 first — the same fail-closed direction `groundingEnabled` and `brainConfig` apply when reading; and a picker's key must be in `VoiceConfig.current()` or the row silently redraws as the leftmost option every time the card opens. The brain section shows only the half in play (`hub_url` / `device_name` *or* the DeepSeek rows, never both), and the 火山 endpoint group — which sits directly after the brain section — starts collapsed because the endpoints and resource IDs ship working defaults — the one row in it that has no default, and that the device cannot work without, is the key.

**The card is bilingual, and nothing else on the device is.** `lang` (`VoiceConfig.uiLanguage()`) is `zh` / `en`, and every string in `SettingsUi` exists twice through one helper, `t(zh, en)` — there are no string resources here, because there is no Activity and no layout XML to hang them off. Two deliberate asymmetries. Unset falls through to `Locale.getDefault()` rather than to Chinese: an English tablet opening a Chinese settings card is the same bug as the reverse, and once the user picks one the saved value outranks the system locale forever. And the language picker is the one setting **written the instant it is tapped**, then `rebuild()` swaps the whole view tree in place (reusing the old `LayoutParams`, `removeView` + `addView`, no `OwnCard` change) — the redraw in the new language *is* the confirmation, and a switch that waited for 保存 would look broken for as long as the user looked at it. Consequence, accepted: 取消 does not put the language back. `rebuild()` carries unsaved text across via `currentEdits()`, so the swap is not a data loss. Scope: `VaultUi`, `WakeEnrollUi`, the ball's subtitles and everything the brain says are still Chinese-only — `lang` is a settings-card setting, not a device locale.

`VoiceConfig.API_KEY` ships **empty**, and must stay that way in this repository. It used to hold a real 火山 key so a fresh install could hear and speak with no setup — a trade only available to a build nobody else reads. In public source a key is a key in a search index, and a bare UUID is not a shape GitHub's secret scanning recognises, so nothing would warn anyone it had leaked. The key is therefore the user's to supply, in ⚙ (`api_key`, which is in `ALLOWED_KEYS` and has a `SettingsUi` row for exactly this reason). `VoiceConfig.TTS_SPEAKER` stays a compiled-in constant — it is a voice name, not a credential, and it is only the fallback for the looks that ship without one (see `lookSpeaker`).

The consequence to design for is that **an empty key fails at the WebSocket handshake**, which surfaces as a connect timeout — a device that looks like it has a network problem when what it has is a blank field. That is why `SelfCheck.voiceKey` exists and sits immediately before `voiceReach`: it is free, offline, and it is what makes `voice_host` green + ASR failing a usable deduction. It is `WARN`, not `FAIL`, because the brain, the screen tools and the notebook all work mute.

### The vault: the brain can spend the user's credentials but never holds them

`config/Vault.kt` stores the user's own accounts — one `Entry` per account (label + phone/email/username/note + password) — so the body can log in *as them* when a task needs it. Three tools reach it: `list_vault` (masked overview), `get_vault` (one readable field in the clear), `fill_secret` (types the password into the focused field).

- **Its own `vault_prefs`, not `voice_prefs`.** `VoiceConfig.save()` is a flat `Map<String,String>` behind `ALLOWED_KEYS`; a growing record list does not fit it, and endpoints are plaintext while this must not be.
- **Keystore AES/GCM, hand-rolled, zero new dependencies.** Jetpack Security Crypto is deprecated, so it wasn't worth a dependency. `base64(iv || ciphertext)`, key never leaves the hardware, uninstall destroys it. `setUserAuthenticationRequired` is deliberately *off*: a biometric prompt mid-task would stop the very automation this exists for. Decrypt failure (new device, cleared Keystore) logs loudly and returns empty — an unreadable vault and an empty one look identical in the UI otherwise.
- **Fill-don't-read is the whole design, and it lives in one line.** `get_vault` refuses `field=password` outright rather than masking it — a masked value would get pasted into a form and reported as success. The other half is `ScreenController.typeViaAdbKeyboard(text, secret = true)`: normally that method echoes the typed text back so the brain can confirm the field took it, and the error branch quotes 30-char fragments of what it read. Either one sends the password to the remote model. With `secret = true` the success result carries only `performed`/`via`/`len` and the failure result reports lengths, never content. Readback itself is unchanged, so `fill_secret` still answers honestly. **This is the single most breakable part of the feature** — anything that starts logging or echoing typed text (a `GroundingLog` hook, a dispatcher-level param log) reopens it silently.
- **Verification codes are not a new mechanism.** The login playbook chains the *existing* `read_sms`; no new tool, no new event kind. The playbook prose lives in `list_vault`'s description because that description is the brain's only instruction manual.
- **The user sees the fill.** `device.vault.fill` pulses the ball and writes 「正在填入「X」的密码…」 to the transcript, same promise as the camera shutter flash. Not a `Toast`: `enqueueToast` is gated on the notification appop, which is `ignore` for a service with no launcher icon, so the toast is dropped with nothing in logcat — measured on this device. *(That premise changed on 2026-09-28: the app now has a launcher icon — see "Startup self-check". The transcript row is still the right design because the subtitle band only exists on the unfolded card and the pulse is the live tell, but the "toasts are silently dropped" fact has not been re-measured since and should not be relied on either way.)* The subtitle band itself only exists on the unfolded card, and the branch folds first, so the pulse is the live tell and the transcript row is the record. No confirmation dialog — the point is that the agent finishes the job — but never invisibly.
- Known, unchanged boundary: the password still reaches ADBKeyboard over `ADB_INPUT_TEXT`, a `setPackage`-pinned but nonetheless cross-process broadcast. That is the channel *all* input on this device uses; the vault doesn't add it. Separately, `grounding` mode screenshots to disk, so a login page capture can contain a password field.

## Existing but not wired up

Don't mistake these for live code paths or accidentally "fix" callers into existence:

- `BubbleUi`, `PillUi`, `MenuUi` — superseded by `FloatingWindowUi`, never instantiated.
- `TtsController.speak()` is **live** — `ScreenBodyService`'s hub `onFinal` reads every final answer aloud. It is not a tool, though: there is no `tts.*` dispatcher namespace, so the brain cannot choose to speak or stay quiet. Anything that needs the device silent has to gate the call site (see the meeting recorder). The long-press that used to trigger a test utterance is now explicitly reserved.
- `AudioIO`'s `startRecording`/`stop` buffer-everything path is MVP leftover; the ASR path uses `startStreaming` instead.
