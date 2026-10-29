# Changelog

Notable changes to Andee. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Versioning policy

- `versionCode` / `versionName` live in `app/build.gradle` and are currently **`18` / `"0.8.0"`**. The
  Mac's product version is read from that same `versionName`, so both bodies carry one number.
- Releases are tagged `vMAJOR.MINOR.PATCH`, `versionName` mirrors the tag without the leading `v`,
  and `versionCode` increases by one per release. `versionCode` must increase **monotonically** or
  Android refuses the upgrade; the same number must never be reused for two different builds.
- MAJOR for a change that breaks the wire protocol or an existing configuration; MINOR for a new
  capability; PATCH for fixes.

**The project starts at `0.x`, and that is the accurate number rather than a modest one.** Nothing is
frozen. The tool-schema contract a hub brain speaks has been rewritten repeatedly
(`net/ToolSchemas.kt` has been rewritten in most releases — it read "in every release so far" until
a release did not touch it, which is why the count is not written down here), the `local.properties`
build flags that decide the first-install language, the thinking default and whether CodeFlying is
compiled in have all been added since, and a saved scene is a shape that only appeared in `0.2.0`. Each of those is something a user or a second implementation would have to rebuild against,
so `0.x` is being honest that MINOR may break. The release that moves off `0` should be the one where
the wire contract is written down well enough that a second implementation could be built from it.

**A version number identifies a release, not a build.** `versionCode` now moves with each release,
so the installed app can say which *release* it came from — but nothing in it moves when `main`
does. A build made from a commit between two tags reports the older release's number, so for
anything not built at a tag, identify it by the short commit hash it was built from. (Embedding that
hash into `versionName` at build time would close this hole; it is not implemented yet.)

## [Unreleased]

## [0.8.0] - 2026-10-29

**It has something of its own now, and it can act on it.** Until this release every turn began with an
event from outside: the user spoke, an alarm fired, a notification arrived, the review ran. With no
event, nothing happened. This gives it a self to answer to — what it cares about, who it knows, what it
has been doing — and a heartbeat that wakes it to look at that.

The design and its reasoning, including the parts chosen against, are in
**[docs/design/inner-drive.zh-CN.md](../design/inner-drive.zh-CN.md)** (Chinese).

### Added

- **A self it writes itself** (`self_note`) — a first-person account of who it is and what it has been
  busy with, rewritten as it goes. Not a persona skin: it is what the next wake-up reads to become
  itself again, since it has no continuous experience. Versioned (up to 20) and readable in **小本本 → 自己**, alongside
  **在意**, **日记**, **人** and **维基** as tabs of the same book.
- **Things it cares about** (`cares`) — each with how often it means to look. The device computes
  *how overdue* each one is (ratio ≥ 1.0 means the time has come) and sorts by that; **the ratio is the
  device's arithmetic, never the model's**. This is what the heartbeat asks about, and the only thing it
  may pick up work from. Up to 7 active at once.
- **The people it knows** (`person`, `people`) — one entry per person, held in the encrypted notebook,
  because this is other people's information. Deliberately not called 通讯录: that word in this project
  means the system-contacts permission, which is a different thing.
- **A diary** (`diary`) — one page a day, written in the evening; 40–1200 characters, up to 60 pages.
- **A notebook it writes for itself, in plain files** — plans, research and write-ups land here, indexed
  in the notebook but stored as files, so any editor can open them and they can be shared. **This store
  is deliberately not encrypted** (its own decision, as working documents), so third-party details belong
  in the people/notebook side, not here.
- **A heartbeat** — about one look every 20 minutes, at most 40 a day, deciding whether anything it cares
  about has waited long enough to be worth doing. Most beats stop at a free gate without any model call at
  all; when one does go through, it costs roughly 500 tokens.
- **Work turns** — what a heartbeat may start: a full turn with **every tool a user's turn has**, capped
  at 6 a day with **at least an hour between them**, so a day's allowance cannot be spent in its first two
  hours. It touches no screen, speaks nothing, and sends nothing on your behalf.
- **A tap on the ball stops everything** — the running turn, the queue, and wakes that were waiting to
  start, plus any bubble on screen. One row says what was cut off and what was dropped, so a stop is not
  silent.
- **动态 (Mac) / the timeline (phone)** — every non-conversation event as one quiet line: a todo waking
  it, the review and what it folded, its writes to its own record, a heartbeat's thought and its work
  turns, bubbles, notifications it let pass, meetings, the camera, shell commands. A row with detail opens
  on click; 全部 / 只看对话 filters them. Several things that used to be fake assistant lines are events
  now, which is more honest and easier to scan.
- **A WeChat icon beside GitHub and Discord** that opens Andee's QR code, on both bodies.
- **An out-of-date build is told once at launch** and pointed at CodeFlying, on both bodies. "Behind"
  means a release strictly newer than this build — a build ahead of the release is current.
- **Android can use the system TTS** as its speaking provider, as an alternative to a cloud voice key.
- **13 new tools** — `self_note`, `cares`, `diary`, `person`, `people`, and eight `wiki_*`; `wiki_show`
  opens a written article from the timeline on the phone as well.

### Changed

- **The default step limit is 40, up from 25**, on both bodies.
- **Nothing asks whether you are there, or what time it is.** The heartbeat, bubbles, the morning plan and
  the muse no longer wait for input, an unlocked screen, or 08:00. The brakes are the switches, the
  budgets, and a tap on the ball. The settings copy that still described the old hours has been corrected
  in all four language/platform files — it had been left describing behaviour the code no longer had.

### Fixed

- **A quiet afternoon is one review row, not eighteen.** A stretch of conversation could be reviewed four
  times over (a sweep, the harvest straight after it, and both again after an app restart), and every
  write in each became its own timeline row. The Mac now records "this silence was already reviewed"
  across restarts, a harvest straight after a sweep writes only the hand-over summary, and a review is one
  row: *"复盘了一遍，记下 N 处"*. A rewrite that changed nothing is no event at all.
- **When its aim keeps missing, the device points the model at `tap_target`.** On 2026-10-29 the Mac
  missed a thin reply box six times with grid taps, and the model ended up asking the user to click it —
  while one `tap_target` call would have found it. Now a second coordinate tap that changes nothing, an
  e-number tap that changes nothing, and the same-spot refusal all carry a hint naming `tap_target`;
  nothing the device said before had mentioned it.
- **A todo paused or closed while its turn was waiting no longer runs**, and a paused plan bubble stays
  paused through a reconcile.
- **Reviews see every memory and pending todo before writing**, so they update instead of duplicating, and
  a todo that tries to number several things is refused.
- **The upgrade link is `codeflying.net/andee` (Chinese) / `codeflying.app/andee` (otherwise)**; the
  service's `download_url` is no longer used. The Mac logs its launch version check.
- **Android: 小本本 keeps the picked tab in view** after the card rebuilds, and the Mac settings section
  named 小本本 is renamed **复盘**, which is what it is.

### Notes

- **One device is one Andee.** The one on your phone and the one on your Mac are two individuals with
  their own narrative, cares and people; they can find each other over the peer channel but they do not
  share memory. This is not a limitation to be fixed — an individual does not live in two bodies, and it
  avoids cross-device sync, conflicts and doubled budgets as a class.
- **`docs/design/inner-drive.zh-CN.md` still says "草案，未实现" at the top.** That line is out of date —
  the classes it describes are all present. Recorded here rather than silently edited, because the
  document's status line is the author's to set.

## [0.7.1] - 2026-10-28

**Taps land where it means them to.** Our own numbers said the brain was a poor pointer: on the Mac
between 21 and 27 October, taps aimed by element number worked about 80% of the time, taps aimed at a
coordinate after zooming about 43%, and unaimed coordinate taps under 10%. Half of that was the model
being asked to do arithmetic it is bad at, and half was guards the phone had and the Mac did not.

The answer is to stop asking the brain for coordinates at all. It now describes what it wants in words,
a vision model that is actually good at pointing answers with a point, and the device taps there.

Reasoning, measurements and the options that were rejected are in
**[docs/design/click-accuracy.zh-CN.md](../design/click-accuracy.zh-CN.md)** (Chinese).

### Added

- **`tap_target`** — describe the thing to tap, in words; a vision model finds it and the device taps its
  centre. On both bodies, local brain only. The brain never produces a coordinate, so nothing can be off
  by its arithmetic.
  - **It uses the model you already configured for the brain**, on every backend, so there is nothing new
    to set up. `locator_model` overrides it if you want something faster.
  - **Measured** on our own screenshots, ten targets with known answers: 6.4 px mean error, 24 px worst,
    about 2 s and 1400 input tokens each. The alternative — the brain counting cells itself — is what this
    replaces.
  - **It degrades instead of gating.** A backend that will not take images withdraws the tool for that
    run, as do three unreadable answers in a row; one good answer resets the count. After that the brain
    falls back to aiming by cell, which is what it did before.
  - Two prompt choices were measured, not guessed: **asking in Chinese** (the same targets in English
    were off by 25–37 px on three of them) and **naming what it sees before deciding** (without it,
    "the red 删除 button" was answered confidently with the close button). When the target is not there,
    it says so.
- **`drag`** — press, move while holding, release: a real drag on the Mac, for things that have to be
  grabbed rather than scrolled (a slider, a splitter, a window by its title bar, a row to reorder). Both
  ends are aimed by coordinate or in words.

### Fixed

- **The Mac gained the guards the phone already had.** Each of these was a measured failure rather than a
  hypothetical one:
  - **A tap now reports whether the screen changed**, with a magenta ring marking where it landed. Without
    it the model could not tell a tap that missed from one that opened a menu.
  - **Elements a tap cannot reach are no longer offered.** A closed menu item used to be listed, and
    tapping its number hit the card underneath. Checked against Chrome: 123 visible elements, no
    misjudgements.
  - **Tapping by element number checks the element is still there** first, and refuses with a fresh list
    if it is not.
  - **The repeat-tap guard radius went from 120 pt to 30 pt** — the old one refused 9 of 56 taps, including
    corrections that were being aimed at something else.
  - **Apps that do not expose their UI are recognised.** WeChat's Mac window offers four elements in
    total; a window that large with so few elements is now treated as tree-blind, and a screenshot is
    attached automatically with a note to look at it.
  - **A window on another display is reported** rather than aimed at, and the scale comes from the main
    display instead of whichever screen has keyboard focus.
  - **Every click brings its app forward first.** On the Mac a click on an inactive window only activates
    it, so focus stayed on the page and a following paste went nowhere — which is how "create a new app"
    appeared to do nothing on the first tap.
  - **Hit testing only considers ordinary windows**, so the Dock's full-screen window no longer answers for
    every point.
- **The Mac can type into apps that hide their focused field** (WeChat is one: it reports no focused
  element, so `type_text` refused). Pastes with ⌘V only — deliberately no ⌘A, because with focus unknown
  select-all could take the chat history — refuses if Andee's own window is active, and says the result is
  unverified rather than claiming success. The model had been working around this with `osascript`, which
  is closed on notification turns: a message it could read, it could not answer.
- **A backend that answers with something other than the API now says so.** OkHttp turned a POST meeting a
  301 into a GET of the new address, and on a moved gateway that is an HTML page, so the turn died on
  "A JSONObject text must begin with '{'" — a message that tells nobody anything (measured: `codeflying.net`
  → `www.codeflying.net`, an nginx page). Redirects are no longer followed, and a non-JSON 200 is reported
  as an address that is wrong or has moved, naming where it pointed and showing what came back.
- **The cancel button in the Mac's destructive-action confirmations** was squeezed to a sliver because both
  buttons filled the row; each now takes half. It read vertically.
- **Clearing the conversation now clears all of it.** The Mac's 清空聊天记录 did three things where the
  shared routine does seven — it did not stop or drop the running brain, did not clear what the brain was
  built for, and left the progress caption up. A stop that only half-stops is how a cleared conversation
  carries on talking.

### Note on verification

- **`tap_target` is not yet verified on a phone.** The design document says so, and it bears repeating: it
  needs a phone whose voice and brain keys both work, and a WeChat comment or reply tried once. On the Mac
  it has been used in a real task twice (creating a new app in 码上飞), and landed both times — a sample of
  two, not proof.

## [0.7.0] - 2026-10-27

**It can speak first now, and it has a reason to.** Until this release every turn began with the user:
`todo` alarms, notification triage and the nightly review were all reflexes to something that had
already happened. This adds the two things that turn a reflex into an intention — a **way** to open its
mouth that costs almost nothing, and a **plan** that gives the opening a why.

The design is written up in **[docs/design/proactive-nudge.zh-CN.md](../design/proactive-nudge.zh-CN.md)**
(Chinese). Its rule is that the cost of interrupting is asymmetric: missing one chance to speak costs the
user a question, while one interruption too many gets the ball muted and silences the good ones with it.
So the default is silence, speaking needs a reason, and the reason is rationed.

### Added

- **Bubbles (`nudge`) — the lightest way to speak first.** A small silent bubble beside the ball, on both
  bodies. Nothing is read aloud, the microphone stays off, and the turn is not blocked: it returns at
  once. It fades if ignored, always yields to a card, and is rationed per hour, per day and per `kind` at
  a level the user picks (*off / few / normal*); a kind can be muted by long-press. The bubbles yield,
  never displace — the reasoning is that a bubble is an attempt to be useful, not a claim on attention.
  - With up to 3 short buttons, a tap comes back later as its own turn. The button meaning "no thanks"
    is marked `dismiss` by whoever sent the bubble rather than guessed from its label, and tapping it
    closes the bubble **without** a turn — a refusal needs no model to answer it.
  - Tapping the bubble's text (not a button) opens an ordinary conversation: the equivalent of the user
    saying "go on, then".
- **Plans (`plan_directions`, `day_plan`) — what it is trying to get done, and how today goes.**
  - *Directions* are the long-term layer: what it is helping with over these weeks. They may be created
    **only from what the user said or did**, and each carries a `source` naming when and what — a goal
    the model thinks would be good for someone is not theirs to add.
  - *Day plans* are per-day items, each pointing at a direction or a todo. A timed item becomes a bubble
    raised by the device with **no model call at all**; outcomes are written back to the plan.
  - **Visible and editable**: **Settings → 它的打算**. The user can pause or remove an entry; everything
    else is done by telling it, rather than through a form with a field for every case.
  - One plan per body. The phone and the Mac each keep their own.
- **PlanMind — a morning look and a spaced muse.** Once a day it takes a look at the day ahead and drafts
  a plan; for items with loose timing it thinks again at intervals. Each is a single call with no tools.
  The nightly review compares the day's outcomes before drafting tomorrow, and looks over the directions
  weekly.
- **`kitten` look and voice**, and stronger personas across the existing looks.

### Changed

- **The step limit no longer loses the task.** See *Fixed* below; this is listed because it changes what
  the user sees after a long task, which is now resumable by `继续` rather than silently truncated.

### Fixed

- **`继续` after the step limit now picks up where the task stopped.** Three things were wrong at once: the
  sentence the user heard never reached the history, so the model did not know it had been cut off; the
  trim protected only the newest turn, so a todo firing between the stop and the `继续` made the task
  "old" and it was dropped (measured 2026-10-22: 104 messages trimmed to 52, task gone); and tool calls
  left unanswered by the stopped turn made the next request fail with HTTP 400.
  - A turn stopped by the step limit or the repeat guard is now kept **unfinished**: its sentence goes
    into the history, its messages survive the trim, the harvest and the topic folds, and every user
    message says what was stopped until a later turn finishes it or an hour passes.
  - `ContinueAfterStepLimitTest` replays the failure against a scripted model, and fails on the old code.
- **Android: tapping the ball mid-task no longer ends listening immediately.** Listening was ended on
  every loss of audio focus — a rule that speaking needs, applied to the side that makes no sound. Mid
  task the driven app is in front, and its autoplay, message tone or voice clip took the floor, closing
  the turn the tap had just opened. Now a duck request is ignored, a call still ends the turn, and any
  other taker is asked to hand the floor back — at most twice a turn — while listening carries on.
  *Reasoned from the code, not from a capture: the Huawei test phone keeps no app logs and its voice key
  is invalid, so this is not yet verified on a device.*
- **macOS: the notification store is found on every version.** Andee looked for `usernoted`'s database in
  one place only — the group container macOS 15 uses. Earlier systems keep it under the per-user Darwin
  directory, where the file does not exist, and that was reported as missing Full Disk Access, so
  granting it and restarting changed nothing. Now each known location is tried in turn (the old one asked
  of the system with `confstr(_CS_DARWIN_USER_DIR)`, not derived from `$TMPDIR`, which the JVM
  replaces); **"no store anywhere" and "the store refuses us" are separate states**, and only the second
  is a permission problem — the first says so plainly and offers no button to System Settings; and the
  read happens in-process through `libsqlite3` instead of forking `/usr/bin/sqlite3`, which had swallowed
  the reason a read failed.
  *Verified on macOS 15.5 against the live store, including a row still in the WAL, and against a fake
  home for both failure states. The macOS 14 location is not verified on a device.*

## [0.6.2] - 2026-10-26

**The download list now says what each file is for, and lists them in the order you would want them.**
Every asset carries a label naming its platform and device — `Andee 0.6.2 · Android-arm64-v8a`,
`Andee 0.6.2 中文版 · Mac-Apple 芯片` — and the list reads Android before Mac, most common device first,
English group before Chinese.

No behaviour changed in the app itself. This is a packaging release: the same ten builds as 0.6.1,
named and ordered better.

### Changed

- **Asset file names gained a platform field**: `andee-<version>-<lang>-<platform>-<device>.<ext>`, so
  `andee-0.6.2-en-android-arm64-v8a.apk`. Three things depend on that shape, and each was decided by
  measuring rather than by preference:
  - **The order a reader sees is the alphabetical order of these names.** GitHub sorts the asset list by
    name and the upload order is discarded, so the display order has to be encoded in the names —
    `en` < `zh`, `android` < `mac`, `arm64-v8a` < `armeabi-v7a` < `x86_64`, `arm64` < `x64`. All four
    comparisons were checked before the shape was chosen.
  - **No Han characters.** GitHub deletes them from asset names, so a file named with the Chinese
    wording arrives with it removed and two separators left touching. So the name is ASCII and the
    Chinese wording goes in the label.
  - **The name is now stable across the three scripts** (build, verify, publish) that parse it.
- **Every asset has a label, both languages.** The Chinese ones carry the Chinese wording, which only
  survives in the label; the English ones carry a matching one so the two groups read as a set instead of
  the second half of the list looking bare.

### Fixed

- **`verify_variants.py` and `publish_variants.sh` followed the new naming.** Both parse file names to
  decide what a package is; both were updated in the same change, because a name change that lands in
  only one of them produces a release that either fails verification or publishes the wrong pairing.

## [0.6.1] - 2026-10-26

**One checkout now produces every shipping variant, and the packages cannot contain a key.** Four
builds come out of it — Android and macOS, each in Chinese and English — totalling ten files, because
Android splits by ABI and the Mac by architecture. A variant is two build settings, both non-secret and
both given on the command line; the credentials are not settable at all.

This exists because v0.6.0 shipped a Mac build with no server configured, and the Mac silently fell back
to a different backend rather than saying so. Two causes, both fixed here.

### Fixed

- **The Mac had no build-time default language**, so a package built as "Chinese" came up English on an
  English machine — the package was not the thing its name said it was. `DEFAULT_LANG` is now written
  into the generated config and read by `Strings.lang()`, ranking below a language the user has picked
  and above the system locale, exactly as the phone does it.
- **A second variant build in one checkout reused the first one's `BuildConfig`.** `-P` values are read
  during configuration, which AGP's up-to-date check for `generateDebugBuildConfig` does not see, so
  building Chinese and then English produced **English packages filed under the Chinese name** — the
  second run rewrote the config and re-packaged, but the first run's task had been skipped, leaving the
  APKs as whatever was on disk. The two settings are now declared inputs of that task.
  *Found by reading `DEFAULT_LANG` out of the finished APK, not by the build log — which said
  `BUILD SUCCESSFUL` both times.*
- **Credentials can no longer be baked in.** `CODEFLYING_KEY` and `GOOGLE_VOICE_KEY` are hard-coded to
  the empty string instead of copied from `local.properties`, and both build files **refuse** a
  `-PcodeflyingKey=` / `-PgoogleVoiceKey=` rather than ignoring it. Previously "the key ships empty"
  held only for as long as nobody uncommented one to test something.

### Added

- **`build_variants.sh`** — builds all four variants from one checkout, naming the Chinese packages in
  Chinese. It moves the runtime image aside between Mac architectures (the trap that produces a mixed
  bundle while reporting success) and checks all four architecture points before accepting a dmg.
- **`verify_variants.py`** — confirms each package is the variant its name claims, by reading the
  **artifact**: the APK's own dex, and `codeflying.properties` from inside the dmg's jar. It checks
  that the server matches the language, that the language matches the name, that the architecture
  matches, and that no credential is present anywhere — including `assets/andee_key.txt`, which is
  read before the build config and would override it.

### Changed

- **`-PcodeflyingDomain`, `-PdefaultLang` and `-PcodeflyingSsl` override `local.properties`** for both
  platforms. The variant is stated where it can be read back from the build log, and the properties
  file is never edited between builds — which is the step that eventually ships the wrong package.
- **Both shipping servers are HTTPS-only**, measured rather than assumed: `baas.kuafuai.net` answers
  200 on https and 404 on http, `myapp.codeflying.app` answers 200 on https and does not connect at all
  on http. Hence `codeflyingSsl=true` travels with the variant rather than being left to the file.

## [0.6.0] - 2026-10-26

**The assistant now has a second body.** Andee runs on macOS, on the same brain as the phone: the
agent loop, the notebook, the memory, the tools and the emotion ball are one implementation in a new
`core/` module, and each body supplies only the parts that are genuinely its own — the accessibility
service and the IME on Android, the accessibility API, real mouse and keyboard, and a sandboxed
terminal on the Mac. Two devices that run it can also be **friends**: they pair by QR code and ask each
other for help, on-body, over an encrypted link with no server in between.

This is the largest release so far by a wide margin — 53 commits, 217 files, +23,976 lines — and the
version number finally moves on capability rather than being argued about: MINOR, as the policy says.

Two behavioural changes ride along that a reader should not miss. **CodeFlying is now the default
backend** on a fresh install, replacing on-device as the starting point. And on both bodies the wake
word, the settings, and the self-check now describe the same app, so "phone" and "Mac" differ only in
what the body can physically do.

### Added

- **The Mac app** (`desktop/`, Compose Desktop). A floating ball above every window; the same chat,
  settings, self-check, notebook, scenes, expressions, artifacts, topic and vault cards as the phone,
  laid out as a sidebar with a glass panel on a real backdrop; voice in and out (the same four engines
  as Android, under `desktop/.../desktop/voice/`); notifications read and triaged the way the phone
  does; mouse, keyboard, screen and window access through the accessibility API; screenshot and
  screen-region zoom with the same grid and crosshair; and **a sandboxed terminal** (`run_command`) as
  one of its hands, with a policy file and a trash broker rather than a blanket shell.
- **`:core`, a platform-neutral module.** The brain loop, the four voice engines, the notebook's logic,
  the tool schemas, the emotion-ball renderer and the wire types moved out of `app/` so both bodies
  share one implementation. `BrainHost` is the interface a body implements; the brain never sees an
  Android or a desktop type. A golden-text test pins the model-facing strings *before* the split, so a
  refactor that changes what the model reads fails loudly.
- **Friends** (`core/peer/`). Two Andees pair by QR code — one-time, two minutes, three tries — and the
  code carries the showing device's key fingerprint, so the scanner pins that key and no other. Every
  link afterwards is a signed handshake against pinned keys with forward-secret session keys; replayed,
  altered or reordered frames drop the link. Two kinds, chosen by whoever shows the code: **my device**
  (another of your own; its requests run as your own) and **friend** (somebody else's — the owner is
  asked first, five minutes then it counts as no answer, and the work runs over `GuestHost` with no
  memory, no history, no vault, no personal data and no terminal: screen, ask, show a page, open an app
  or a link, and nothing else). Off until switched on; discovery is mDNS plus the last address that
  worked.
- **`device.permissions.request`** on the Mac — a permission concierge that asks for what a task needs
  and explains the grant in System Settings when macOS will not show the prompt itself.
- **Long jobs handed to the clock** on both bodies, so a slow task stops holding the conversation.

### Changed

- **CodeFlying is the default backend on a fresh install.** The previous rule hid the CodeFlying tab
  entirely unless the build was configured for it, and started on-device. Now the tab always leads, is
  what a fresh install starts on, and says the same thing with or without a key: what it is, how to get
  one, and a tappable link. The key row is always visible with show/hide and copy, and it displays the
  key *in effect* — saving it back unchanged stores nothing, so a build's key is not pinned as an
  override. **A build with no server configured, like the ones attached here, has nowhere to send and
  says so** until a key is typed or another backend is picked.
- **The Mac and the phone now describe themselves the same way.** The READMEs, this file and
  `docs/permissions.md` say the project is two bodies on one brain, and the permission tables gain a
  row for what a friend's request can and cannot reach.
- **The Mac reads CodeFlying's build settings from `local.properties`**, like the phone — a generated
  properties resource rather than a `BuildConfig`, because a JVM app has none.
- **The product version comes from the phone's `versionName`**, so both bodies carry one number. The
  bundle version macOS requires of a `.dmg` stays `1.0.0` and is not the product version.

### Fixed

- **The ball stops repainting a still window**, and the GPU draws it again — a Mac idle-cost fix that
  also removed a thread leak.
- **A tap-to-talk path on the Mac** where the microphone opened but nothing listened.
- **A long task no longer empties the history**, a new request interrupts the old one instead of
  queueing behind it, and voice ends on silence rather than on a timeout.
- **Words typed or spoken over a question card answer it** instead of starting a new turn.
- **A notebook save that did not happen fails** instead of answering "Saved."
- **A todo set from a notification stays untrusted when it fires**, the same rule its creation had.
- **Silent reviews no longer ask for thinking**, which was spending reasoning tokens on a turn nobody
  reads.

## [0.5.3] - 2026-10-22

**You can now tell it to stop, out loud, and it will.** "Exit Andee" / "Take a break" (and their
Chinese equivalents) ends the current task and closes the assistant — microphone and wake word
included — with no model call in the path. The rest of the delta is aiming: a large fix for apps that
hand accessibility a blank root, and a documentation pass on the grid it aims with.

The version number is **a deliberate departure from the policy below**. The policy says MINOR for a new
capability and this delta has one, which would make it `0.6.0`; it is `0.5.3` because the capability is
an escape hatch rather than a new way of working, and the two things it is most likely to be confused
with — asking how to exit, or cancelling a subscription — are deliberately *not* commands. Recording it
here rather than leaving a `feat` under a patch for a reader to wonder at.

**This release carries work from a teammate.** The voice-exit feature arrived as [PR
#14](https://github.com/kuafuai/andee/pull/14) from [@lirongkuafuai](https://github.com/lirongkuafuai),
closing [issue #13](https://github.com/kuafuai/andee/issues/13) — fifteen commits, reviewed and merged
rather than imported wholesale.

### Added

- **Voice exit: say it and it stops** (`asr/VoiceExitCommand.kt`). Recognized **on the device, without a
  model call** — an utterance that matches is handled before any card answer or brain submission, so it
  works even when the model is unreachable. It cancels the running task, stops the service and closes
  the assistant, which also **stops the microphone and the wake word**; the app icon starts it again.
  The match is **whole-utterance and user-initiated only**: *"how do I exit Andee"*, *"cancel my
  subscription"* and a quoted command all stay ordinary turns, and a phrase that arrives on an
  automatic follow-up window is ignored (that one has its own test). While a meeting is recording it
  refuses and says why — end the meeting and save the minutes first. Teardown is guarded so a late
  transcript, wake callback or TTS fallback cannot reopen an overlay after the exit was asked for.
- **`popup_open` / `popups` on tap, long-press and screenshot results**, read from the window list,
  which survives a withheld tree. The model could not tell an open menu from a closed one by eye and
  reopened one **six times**; a blind tap aimed *inside* a popup is now refused, with the reason.

### Fixed

- **Aiming on an app that withholds its tree** (measured on WeChat 8.0.79 / HONOR, where accessibility
  gets a blank root and every tap is aimed by eye off a screenshot downscaled to ~0.48×). The injection
  itself was already exact — the misses were all aiming, and this is a large fix:
  - **The grid labels moved to the edges.** Printed at each cell's centre, the `H` column's label sat
    exactly on WeChat's 「··」 button and **hid it**. They are now rulers — letters along the top,
    numbers down the left — and nothing printed inside a cell belongs to the device.
  - **A gesture on a tree-blind app must fall inside a zoom taken since the last one.** Measured:
    zoom-aimed taps changed the screen **33/41** times, screenshot-aimed **124/211**, and the misses
    opened profiles, followed people and deleted comments.
  - **A cell is now fifteen parts, not nine** (`3 × 5`: `top-left` … `bottom-right`). The old nine names
    still parse as aliases mapped to the outer rows, so anything written against the previous
    vocabulary aims where it used to.
  - **The markers are magenta (`#E000FF`), not red** — red is every unread badge in every Chinese app,
    and a red crosshair beside WeChat's red dot read as part of the app. The caption also moved above
    the ring, because below is where the next target is.
  - **Prompt side:** close a small menu by tapping its opener again rather than with `back` (the menu is
    unfocused, so `back` left Moments entirely), and keep list progress instead of restarting at item 1.

### Changed

- **The READMEs' description of aiming was corrected.** They said it taps by *"which third of it"* and
  that the crosshair is *red*; both stopped being true in this release. They now say "which part of it"
  and magenta. Found by re-reading the sentence against the code, not by a search.
- **The English README's language badge is back to ASCII.** A web edit on 2026-10-21 changed the badge
  label from `Chinese` to the Chinese characters for it, which put Han characters back into a file that
  is checked for zero of them. The link target was never in question and did not move. If a Chinese
  label on the English page is actually wanted, that is a decision to make deliberately — the badge is
  the one place a reader who cannot read English has to be able to see themselves — and not something
  to arrive at by accident.

## [0.5.2] - 2026-10-19

**Two fixes from watching it work on an app that hides its element tree, and a new hero image.** The
fixes are the substance: commenting on a WeChat Moments post took **64 steps and 341 seconds**, and the
same task now takes **20 steps and 58 seconds**, sent on the first tap. The version number is `0.5.2`
rather than `0.6.0` because the one `feat` commit in this delta is the README's cover art, not a
capability — nothing a user can do changed by it.

### Fixed

- **A swipe no longer coasts past what the model aimed at.** `swipe_by_coordinates` lifted the finger
  while it was still moving, so the list flung several screens beyond the target and the model lost its
  place — the largest single cost in the 64-step measurement. The stroke now **holds at its end point
  before lifting** (`SWIPE_HOLD_MS`), and `ACTION_CANCEL` ends a scroll without a fling, so the list
  moves about as far as the finger did and **the distance becomes the control**. A new `fling` parameter
  (default `false`) keeps the old behaviour for long jumps, and its tool text says to take a fresh look
  afterwards rather than assume where it stopped.
- **The tap right after blind typing is refused unless it came from a zoom.** On an app that withholds
  its tree, the reply box is a thin strip directly on top of the post below it, so a grid-cell tap aimed
  at it landed on that post and opened it — **twice out of two** on the same Moments reply box. After a
  `type_text` on such an app, only a tap carrying `on="zoom"` is accepted, with an error that says why;
  leaving the box clears the rule. Zoom-then-tap is how the same comment now sends on the first attempt.
- **`submit_input` no longer reports success on a box that cannot be submitted that way.** WeChat's
  comment box declares `IME_FLAG_NO_ENTER_ACTION` and sends only from its own button; firing the IME
  action there answered "done" while sending nothing. It now says it cannot, and names the app's own
  send button. `type_text` and `submit_input` also work when the tree has no focus node but the input
  connection is bound.
- **The tap ring is drawn where the tap landed.** The marker window is positioned in display pixels, but
  an API 30+ overlay fits the system-bar insets by default, so its frame began below the status bar and
  **every ring sat that far under the real tap**. It now carries the same `FLAG_LAYOUT_IN_SCREEN` /
  `fitInsetsTypes = 0` / cutout mode the ball window already had. The caption's 0-1000 numbers were
  always right.

### Changed

- **The tool text now corrects three things models kept getting wrong.** `zoom_screen_region`'s `x/y/w/h`
  are **0-1000 of the whole screen, never pixels of an image** — a screenshot arrives downscaled (often
  ~576 px wide as the model sees it), and every zoom now reports the rectangle it actually showed so a
  model can notice it was in the wrong space. `type_text`'s text states that a box which opened with the
  keyboard is **already focused** and should be typed into without a preliminary tap. `take_screenshot`'s
  text states that **the assistant's own ball is never in a capture** — the device hides its overlays for
  the instant of capture — because a model that saw a round blue shape blamed it for covering a button
  and tried to route around it.
- **The README's first image is now a phone with the character sitting on it** rather than a floating
  head. `images/brand/andee-device.png` is the new hero art and `tools/make_social_preview.py` places it
  at 720 px wide, bottom-right, keeping 60 px of white padding with a 40 px analytic edge ramp; the mood
  strip narrows to 512/88 so the phone's left corner clears the last cell, and the text-to-art gap check
  now measures the character's left edge instead of a hard-coded offset. All four generated images were
  regenerated; **the round avatar is byte-identical**, because it still uses the head-only asset. The
  character in this render has a body; the app still draws a floating sphere.

## [0.5.1] - 2026-10-19

**Two ways the assistant could quietly lose the ability to keep working, both fixed.** One, its
nightly review died with the process and never came back; the other, Xiaomi's power policy was
reaping it in the background with no way for the user to see why. The version number is
**a deliberate departure from the policy below**: both commits are `feat`, and the policy would have
made this `0.6.0`, but neither adds a capability — they restore two things that were already supposed
to happen, and a user who upgrades is not getting a new feature so much as getting the existing ones
to survive a night. Nothing in the wire contract or an existing configuration breaks.

### Fixed

- **The quiet-hour review survives the process being killed, and is re-armed after a reboot.** The
  sweep is an `AlarmManager` alarm, so it outlives the process — but when it landed there was often
  no service left to run it, and a reboot did not re-arm it at all. Worse, the review reads the
  conversation from memory, which dies with the process, so even a recovered sweep found **"0 messages
  to review"** and did nothing. Two halves: `Notebook.last_sweep_at`, written only by the sweep itself,
  is the one fact that separates *"already reviewed"* from *"died before it could"*; and
  `rebuildHistoryFromScrollback` rebuilds the dialogue from the persisted scrollback — user and
  assistant rows, each re-stamped with its own clock — when the in-memory history is empty. On start
  the service catches up: it waits out a quiet window that has not finished, skips a stretch that was
  already swept, and otherwise runs the review it missed. Measured on a device: a sweep after a restart
  listed the four prior turns and completed.
- **A Xiaomi-family device now has a self-check row for auto-start, which is what actually keeps it
  alive there.** MIUI reaps background processes on a timer (measured at one kill every 40–60 minutes
  on the maintainer's tablet), and without the auto-start grant `START_STICKY` cannot bring the process
  back — the symptom a user reports is *"it closes by itself after a while"*, with nothing anywhere
  saying why. The new row reads the vendor AppOps op (`OP_AUTO_START = 10008`, by reflection, since it
  has a number but no name this app can spell) and offers a one-tap door to MIUI's auto-start manager,
  probing the known components in order because MIUI has renamed that activity across versions. It is
  **`WARN`, not `FAIL`**: with auto-start off the app is fully alive right now — it is the *future*
  that degrades, which is exactly what `WARN` is for. The row **only exists on Xiaomi, Redmi and POCO**
  (matched on brand words, since the MIUI build property would need a hidden API and the words are
  free), because on every other ROM it would be a switch that does nothing. `Fix.Screen` gained an
  optional `component` for the vendor screen that has no action to name.

### Changed

- **`CLAUDE.md`'s self-check section no longer claims a count.** It said "Seven things in the design
  are load-bearing" and had listed fifteen for some releases; the sentence now says "a number of"
  instead, on the same reasoning the versioning policy above records — a count written into prose goes
  stale, and this one already had.

## [0.5.0] - 2026-10-16

**A finished conversation is now something the device puts away and can read back.** That is the new
capability this release is numbered for — a stretch of talking that has ended stops costing context on
every later request, and stops being lost with it. Two smaller changes ride along: a choice card's
question and answer now land in the scrollback, and the README explains what signing is actually
protecting.

### Added

- **Topics: a finished stretch of conversation is folded away and can be picked back up.** A topic that
  has ended was the most expensive thing in every later request and the least likely to be needed
  again. The nightly review now marks where topics begin and end among the user's numbered turns
  (`fold_topic`), the device validates the range and swaps it for a one-line marker **after the turn
  it names**, and the verbatim conversation is archived so it can be read back. Three tools, all
  `localOnly`: **`fold_topic`** (review-only — an ordinary turn that calls it is refused, because
  folding is irreversible from the model's side and a request is not the moment to spend it),
  **`list_topics`** (id, title, when it ended, how many messages), and **`get_topic`** (the whole
  back-and-forth as plain text, truncated past a limit with `truncated` saying so).
  The device refuses a fold **with a reason** — a turn number that is not in the list, a range that
  ends at the latest turn (nothing has started after it, so nothing says it ended), something inside
  still waiting on an answer, an overlap with a topic already put away, too short, or a scene active —
  and the reason names the condition rather than inviting a retry. **At most three topics per review**,
  and they are put away when the review ends rather than when the tool is called.
  In the scrollback a folded topic is **a card standing where its messages stood** — the summary shown
  by default, with **Continue**, **Transcript** and **Made here**. Continuing inlines the archived
  conversation into the next turn; the transcript opens fullscreen (and says so honestly when the text
  has been cleared and only the summary is left); *Made here* reopens the pages produced inside that
  topic, each with its version. The summary's fourth line is deliberately **what the model left out**,
  because a summary that reads complete is one whose details are lost — `list_topics`' own description
  tells the model never to answer a question about a folded topic from its one-liner alone, and never
  to say it did not happen. `debug.sweep` (loopback only) runs the review on demand for testing.

### Fixed

- **A choice card's question and answer now land in the scrollback.** `ui.ask` spoke the question and
  handed the chosen option straight to the brain, so the card came and went and the conversation showed
  the brain acting on a choice nobody could see being made. The question is an Andee row now and the
  answer — the button, or the words said instead — is a row of the user's. A timeout writes nothing,
  because nothing was chosen.

### Changed

- **The README says what signing is for.** The signature is the only thing stopping a fake "update"
  from installing over a user's copy and inheriting the accessibility service, the secure-settings
  grant and everything in the vault. That is now stated where signing is already discussed, along with
  why a per-machine debug key is what produces `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- **The tool count in the READMEs and the DISCLAIMER is no longer a number.** Both said "sixty-odd
  tools", which was accurate when written and had drifted to seventy-two. The sentences now describe
  what the tools do and say "a few dozen", because a count in prose is a count that goes stale — the
  same lesson the versioning policy above already learned once.

## [0.4.1] - 2026-10-15

A day of fixes and one feature pair, and the version number is **a deliberate departure from the
policy below**. The delta holds three `feat` commits — page versioning, the brain being able to see
its own pages, and the default palette — and the policy says MINOR for a new capability, which would
have made this `0.5.0`. It is `0.4.1` because the first two are two halves of repairing one
incomplete feature rather than a new capability: `show_html` could already make a page and the panel
could already list it, and what was missing was the model's knowledge of it and the device's
willingness to treat a change as a change. Nothing in the wire contract or an existing configuration
breaks either way, so neither number is wrong about compatibility — this one is about intent.

### Added

- **Revising a page makes a new *version* of it, not a second page.** Changing a page used to archive
  the result as a brand-new artifact, so two rounds of revision left three near-identical entries on
  the *Made for you* shelf with nothing saying which one was current. An entry now carries which
  artifact it belongs to and which revision of it, the panel shows one row per artifact with its
  version number (`v3`), and the older revisions stay on disk behind it: **Versions** opens the list,
  any of them can be opened, and **Go back to this** makes an old one current again — **as a new
  version**, so the history stays append-only and the rollback itself can be rolled back. The brain
  is told the same thing, so a request to change an earlier page reads the current source back and
  files its answer as the next version instead of rebuilding a lookalike beside it. `show_html`
  gained an optional `revise` for naming the page explicitly; the device also infers it from the
  `get_page` in the same turn, and from the panel row the user tapped. One artifact keeps at most 20
  revisions. Which artifact a revision belongs to is decided on the device and never asked of the
  model — and **the target is consumed rather than merely read**, a distinction found by hand on a
  device, where a target left standing folded a second, unrelated page in as a later version of the
  first.
- **The brain can see the pages it has made.** `show_html` draws a page and the device keeps it —
  the *Made for you* panel lists them, so to the user they are a shelf — but nothing put that record into the
  model's context, so a page built yesterday did not exist to the brain that built it. A request to
  revise earlier work could come back as a fresh table that merely resembled the old one, or as a
  flat "I have not made that". Two read-only tools close it: **`list_pages`** returns every page with
  its id, title, date, size and whether the file is still there, and **`get_page`** reads one back by
  id so a revision is a revision rather than a new page wearing the old name. Both are deliberately
  **outside the `screen.` prefix**, so neither folds the card nor stops at the lock screen: reading a
  file reaches neither. Alongside them the newest few ride on the per-turn user message as a short
  index — the same place and the same reason
  as the notebook index, since a tool only answers a question the model thought to ask. `available:
  false` on a page whose file the cap has taken is reported honestly rather than passed
  over: the title and date are still true, the contents are not there to read.

### Changed

- **The default appearance is light, not dark.** A fresh install now comes up with the light palette.
  A device that has touched the setting keeps whatever it chose — the rule reads only an explicit
  `night`, so nothing here can overrule a user who picked dark, and the dark palette stays one tap
  away in **Settings → Appearance**. Written this way round on purpose: the test is against the
  *non-default* value, which is what makes changing the default later a one-line edit rather than a
  migration. **Dark is not the older palette and is not being corrected** — it is the look this app
  was designed on, and light was derived from it as the inversion. `Theme.night`'s initial value
  moves with the default, so the frame drawn before the saved setting is read is already the right
  palette rather than flipping one frame in.
- **Every face got its props, its idle life and its finishing touches.** Andee's mouth dropped twice
  — `−0.185 → −0.235 → −0.285` — so there is a finger of sphere between its eyes and its smile
  instead of the two crowding together. Sleep stopped being a flattening pose: the `sqx`/`sqy` that
  squashed the resting and sleeping ball into a puddle are gone, and the floppy horns, the ⌣ eyes and
  (while sleeping) the bubble carry it instead.

### Fixed

- **Changing the model, the thinking switch or the reasoning effort no longer wipes the
  conversation.** Closing the settings card rebuilt the local brain on any `BrainConfig` difference,
  and a rebuild is the loss of the whole conversation — flipping the thinking switch reset the
  history mid-task, and the model then said it had no record of a page it had just built. Only a
  change of **who answers** (mode, key, endpoint) rebuilds now; model, thinking and reasoning effort
  are request parameters and are retuned in place, taking effect on the next request.

## [0.4.0] - 2026-10-14

Two new capabilities, both of them choices rather than defaults: **a second voice provider** (Google,
selectable beside the existing one, contributed as PR #8) and **a launcher icon that is the
character's thinking pose**. Behind them, three fixes to how the interface is drawn — the primary
button, the type scale on a phone, and a tracker string that had been calling every new device a
Volcengine device.

### Added

- **A second voice provider.** `VoiceConfig.voiceProvider` reads `huoshan` or `google`, the settings
  card grew a **Voice provider** picker above the two credential blocks, and `AsrController` /
  `TtsController` now build one of two engines behind a shared interface (`AsrEngine`, `TtsEngine`);
  the two are streaming on both sides and reduce to the same five calls, so nothing above them
  changed. Volcengine stays the fallback — anything that is not exactly `google` reads as Volcengine,
  and it is the leftmost option — because for every device that predates this row, Volcengine is what
  it has been running.
  Google's two halves are different protocols: recognition runs over the **Gemini Live API** (a
  WebSocket, so it needs the project *number* and a location) and synthesis is plain HTTP against the
  same Vertex Express key. Its defaults are a real region and two model names rather than a blank
  (`asia-northeast1`, `gemini-3.5-transcribe-live-preview`, `gemini-3.1-flash-tts-preview`), and every
  hint on its rows is the value a blank row resolves to — a card that advertises a model but silently
  calls another is lying in the one place the user goes to decide.
  **`GOOGLE_VOICE_KEY` non-empty at package time is also what makes Google the default provider.**
  That is deliberate: a build is packaged for whoever it is packaged for, and baking a key is the most
  explicit statement of that intent there is. The repository's own source defaults to the empty string,
  so a checkout with nothing configured builds exactly what it built before Google existed.
  The self-check moved with it, and the move is the interesting part: `voiceKey` now asks for the key
  of the provider **actually in use**, and `voiceReach` probes that provider's host. The rows name
  neither — a green tick earned by the Volcengine key while the device is talking to Google is the exact lie
  that having one checklist instead of two exists to prevent.
  *(Contributed by @kuafu-limanxiang in [#8](https://github.com/kuafuai/andee/pull/8).)*
- **The launcher icon is now the thinking pose, on white.** It was built from a sheet with a glow
  painted around the whole head and composited over that glow; it is now built from the cut-out of the
  character with a hand on its chin, on a plain `#ffffff`. Same character either way, so nothing about
  the identity changes — the icon is now the pose the app is in when it is thinking, and the background
  is literally white because these illustrations were drawn on white and the warm light on the ball's
  edge is painted on the ball itself. The inferred-glow treatment is not deleted; it is one flag away
  (`--bg glow`), because it is real work that came close. The icon set also came out smaller,
  405.4 → 258.3 KiB.

### Changed

- **The primary button is one flat fill instead of a blue-to-violet gradient.** A gradient inside a
  pill makes the button carry its own light source — the look of a landing page's call to action —
  and it was the only colour in the whole palette that did not answer to the theme, so on a light card
  it sat there like a sticker off another design. It is now `Palette.tint`, a step deeper than each
  theme's own accent, which keeps the button the loudest thing on a card for the reason that survives
  going flat: it is the only saturated fill on it. Violet went with the gradient — the interface has
  exactly one hue and it is blue. `Glass.TINT_START` and `Glass.TINT_END` are gone.
  The label had to move with the fill, and that is measurable rather than taste: it was `Glass.TITLE`,
  near-black by day and white at night, which works on a saturated ground at night and measures about
  **4:1** by day — under the line for body text. It is `Glass.ON_TINT` now, white in both themes,
  because both fills were picked deep enough to carry it. Seven call sites, one token each; no other
  surface changes.
- **Type is sized against the device now, not just against the tablet.** The six rungs were drawn on a
  711dp tablet where a card is 500dp wide with air around it. A phone is 392dp and the same card fills
  it edge to edge, so the same 26sp title read as a heading on one and a shout on the other — on the
  phone it showed up as a row title clipped to "Accessibilit…" with the next card pushed off screen
  entirely. Nothing about the type had grown: the box framing it lost a third of its width while
  padding, radii and line spacing stayed in dp, and that mismatch is what reads as clumsy rather than
  as large. Six rungs now carry a second column about three quarters of the first, chosen once from the
  device's short side against Android's own 600dp line (`sw600dp`) — the same line, not a second one.
  The tablet column is the original design and is untouched; a phone gets 20 / 15.5 / 13.5 / 12 / 10.5 /
  9.5. **MICRO moves least on purpose**: it is already the floor of what can be read, and a floor that
  moves with the screen is not a floor. The system font scale is deliberately not read — a phone with
  text turned up still renders these at that scale, and the phone column is set with that in mind.
- **Every English-facing markdown file is now free of Chinese characters.** The README had to be; the
  other fourteen did not, and they had accumulated UI labels, language badges (the Chinese badge text
  became `Chinese`, with its link target unchanged), and one piece of shorthand. The rule is mechanical now rather than a
  habit, and it is worth knowing what it deliberately excludes: **code identifiers, code comments and
  `CLAUDE.md` are out of scope** — the last one is written for AI assistants.

### Fixed

- **The voice self-check named a provider the device might not be using.** `voiceKey` asked for
  `apiKey` and `voiceReach` probed `openspeech.bytedance.com`, unconditionally — correct while there
  was one provider, and a wrong answer the moment there were two. Both follow the selected provider
  now.

## [0.3.2] - 2026-10-14

Wake word plus polish, and the first release that ships the APK with the release rather than telling
the reader to build one — see *Added* and `docs/releases/v0.3.2.md`.

### Added

- **The wake word now listens with the screen off, and stops the tools that cannot work locked.** The
  gate used to hold the microphone only while the screen was on — a rule with a real reason behind it,
  since a dark tablet on a bedside table should not be listening all night, but the screen being off is
  also the normal state of a phone in a pocket, so it meant the assistant could not be called from the
  one place a person actually keeps their phone. The gate now asks `wakeListeningAllowed()` — *the
  screen is on, or the user left the "listen while locked" row on* — and that setting **defaults to
  on**. Its cost is stated
  on the row that flips it rather than left for the status bar to mention: the microphone stays open,
  and so does the system's recording indicator.
  Woken with the keyguard up, Andee can hear and answer — `tts.speak` needs nothing from the screen —
  but it must not pretend it can act. A gesture aimed at a locked device is dropped by the input
  pipeline and reports nothing; the lock screen's own `FLAG_SECURE` means there were never any logs to
  search; `screen.ui_tree` returns the keyguard's empty placeholder, indistinguishable from the
  blank-tree case; `device.launch` opens an Activity behind the lock screen where nobody sees it. Every
  one of those would come back to the brain as "completed" and be repeated to the user as something
  that happened, so `dispatch()` refuses them outright with a sentence saying why and what to do
  instead. `screen.` is blocked by prefix so a new tool in that namespace inherits the guard; the names
  outside it — `device.launch`, `open_url`, `scan`, `look`, `permissions.request`, `ui.ask`,
  `ui.alert`, `ui.show_html` — are the same act by another road. `device.battery`, `device.volume`,
  `tts.speak` and the whole `note.` namespace stay open, because they work perfectly well locked. Read
  live rather than mirrored into a flag, because the caller that matters fires at any point of a turn:
  locking the phone mid-answer is a normal thing to do, not a state transition to track. Verified on
  the tablet over the 9008 tool port, including the device being unlocked again to prove the guard
  reads the device rather than a memory of it.
- **The voice segmenter now starts on the voice, not on the microphone.** Two symptoms reported
  separately, neither with a log line to explain it — "it takes three tries to enrol, but if I wait
  three seconds one is enough", and "it wakes on everything" — turned out to be one defect with two
  faces. `VoiceSegmenter` decides an utterance has started when the level holds for `START_FRAMES`
  frames, three of them, 30 ms, and the floor is seeded from the *first frame the microphone delivers*.
  Three frames is a click, a tap on the tablet's edge, or the recorder's own first read, so the segment
  started on the hardware rather than on the person. A segment that starts early comes out long and
  dirty and, past `MAX_FRAMES`, was **dropped in silence** — the caller's latch never counted down and
  the card sat on "listening…" for the rest of the window. That is the second and third try. The fix slows the
  decision down without throwing audio away: `START_FRAMES` 3 → 8 (80 ms) so a transient cannot open a
  segment, and `PREROLL_FRAMES` 15 → 30 (300 ms) so the onset is still in the ring when the decision
  lands. Delaying a decision is free; discarding the audio is not — which is why there is deliberately
  no warm-up that drops the first 200 ms. The obvious fix was proposed and withdrawn: a user who taps
  and speaks immediately would lose exactly the syllable the ring exists to protect.
- **Five of the character's faces now run along the bottom of both cover images**
  (`images/cover*.png`, `images/social-preview*.png`), one word under each — Listen, Look, Think,
  Act, Speak. They are
  the app's own illustrations rather than drawings made for the web, and they are the *gesture* moods,
  the set the web renderer cannot draw because they use props — so this is the first place the cover
  shows the character instead of describing it. The row is confined to the left column by the head's own
  geometry, which is why the whole column moved up 60 px rather than the band being full width.

### Changed

- **The APK now ships with the release.** Every release up to `0.3.1` said "no binary, on purpose",
  and the reason has not changed — the release build type has no `signingConfig` and the repository
  has no keystore, so `assembleRelease` emits an unsigned APK that will not install. What changed is
  the trade: a reader who cannot install anything has no way to try the app at all short of
  provisioning an Android SDK. The attached APK is the **debug** build — signed with the standard
  Android debug certificate and installs as-is — and it is deliberately **bare**: `CODEFLYING_ENABLED`
  is `false` with both `CODEFLYING_KEY` and `CODEFLYING_DOMAIN` empty, `DEFAULT_LANG` is `en`, and the
  thinking default is off. All four were read back out of the generated `BuildConfig` rather than
  assumed, and the artifacts were scanned for key material. **The cost is stated rather than buried**:
  a debug APK is `debuggable` and is signed with the shared Android debug key, so it cannot install
  over a release-signed build — moving from this to a signed release takes an uninstall, and an
  uninstall takes the notebook and the vault with it. The READMEs' advice (*hand out a signed release
  build, not the debug one*) is still right for anything actually distributed, and both now point at
  this stopgap so the two do not read as a contradiction.
- **The wake-word prose moved with the default.** `wake_done`, `check_detail_wake_on` and
  `settings_wake_enrolled` all said the wake word works "while the screen is on" — in both languages —
  and both READMEs said "it only listens while the screen is on, screen off hands the microphone
  back". The default is the thing that changed, so every sentence describing it had to move with it or
  it would have shipped stale.

### Fixed

- **The enrolment prompt appeared before the microphone existed.** The "now speak" prompt was set on the tapper's
  thread, before a recorder existed, so the user was invited to speak into a microphone that had not
  started. It now moves after `mic.start()` returns: the card reads "listening…" for the 100–300 ms the
  recorder takes to come up, and the prompt appears once something is listening. Nothing is taken from
  the user to pay for it.
- **The wake threshold was anchored on the worst take rather than the closest.** `thresholdFor` was
  anchored on the *worst* pair, so one dirty take set the door width for the whole system — 2.2755 and
  2.1083 against a measured band of non-wake speech at 2.21–2.59, with sixteen of sixty judgements
  within 0.06 of the line. `bestDistance` returns the nearest template, so the question the door width
  answers is "how far can a real attempt be from the nearest take" — and the evidence for that is the
  takes that landed close, not the one that did not. `MAX_THRESHOLD` 2.8 → 1.8, which had been picked
  as "the widest thing still callable a threshold" and never measured. Across three enrolments twenty
  minutes apart the spread between takes fell from 0.42 to 0.12.
- `VoiceSegmenter` gained a pure-Kotlin `Stats` (frames / loud / firstLoud / peak / floor / segments /
  droppedShort / droppedLong) with no Android dependency, keeping the DSP half of the wake pipeline
  JVM-testable; `WakeEnrollUi` logs it once per attempt and `WakeMic` logs the read that ends a capture
  early, both under the `Wake` tag so one filter sees the whole pipeline.

## [0.3.1] - 2026-10-13

**A patch release by decision, against this file's own definition.** The delta carries six `feat`
commits and the versioning policy above says a new capability is MINOR; the maintainer chose PATCH
deliberately. It is written down here rather than left for someone to reconstruct from the history,
because a version number that disagrees with the policy is exactly the kind of thing that reads as an
oversight later.

### Added

- **A day palette, alongside the night one.** The card had been one look — a translucent pane over a
  blurred photograph, a bright hairline, pale type — and half the requests on top of it wanted the
  opposite of those values. What blocked every one of them was that `Glass`'s colours were `const
  val`s: inlined into their readers at compile time, and so unable to vary at runtime at all. They are
  getters over a day and a night `Palette` now, **every name unchanged**, which is what let forty call
  sites stay where they are. Night is what the app shipped with and is the default; day is the
  inversion of it, with three values deliberately left alone because they name a position rather than
  a colour — the well stays recessed, the thumb stays raised, and the sheen flips end for end, because
  its job is a lit edge and the light does not come from below on either palette. A theme is a
  process-global with one listener, shaped after `AppLocale` for the same reason: almost no UI here
  lives in an Activity, so no system mechanism could reach a Service's overlays. The backdrop is the
  same photograph, blurred once and veiled in whichever direction the palette needs — black down to
  0.22 at night (the original composite to the digit) and **white** up to 0.62 by day. One image, two
  veils, no asset change. Four faults presented as a single element looking wrong and are the reason
  this is one commit: `CardUi`'s colours lived in a companion object and were evaluated once at class
  load, so the card kept whichever palette the process saw first *for the life of the process*; the
  window's own chrome is built once and never hears about a change, which is what `applyPaletteToChrome`
  is for; the row tints were cached; and the light card's dim greys could not survive being asked to
  sit on a photograph.
- **A "Looks & expressions" card in Settings**, showing every look and every expression on a ball of
  its own. A look chip is saved on tap; an expression chip only previews.
- **A generated page picks its own look.** Every page came back the same dark slate, and the reason
  was not taste — it was a HOUSE STYLE block in the `show_html` description, ending in "use these
  exact values, no others". Asking for a light page worked only because the block left itself a way
  out, and that is a lever the user had to pull every single time. It could not have been fixed by
  wiring the block to the theme: a tool description is a string and cannot branch on `Theme.night`, so
  on a device switched to day the cards, the ball and the backdrop all went light and the one surface
  the model composes stayed in the night palette. That fix was on the table and was turned down on
  purpose — a page's look is the model's business, not ours. Two things stay, and neither is a matter
  of taste: the `<meta viewport>`, because `HtmlActivity` sets `useWideViewPort` and a page without
  one is laid out at desktop width and scaled down, and the ~110×44 px the Done pill occupies in the
  top-right corner, because that pill is ours. **The cost is on the record**: for the small models this
  runs against, that description was the only lever on page quality, and nothing enforces any more
  that a generated page reads as part of Andee.
- **The local LLM endpoint and model follow the UI language.** The on-device tab's two LLM rows already had
  a hint that doubled as the value a blank row resolves to, and both were hardcoded to DeepSeek —
  self-consistent for a Chinese card and wrong for an English one. The cheapest possible fix, changing
  only the hint text, is the worst version of it: the box would advertise `https://api.openai.com/v1`
  and then, on a blank row, call `https://api.deepseek.com`. The hint is read exactly when a user is
  deciding what to type, so a hint that lies about the fallback lies in the one place it will be
  believed. Hint and fallback now come from one pair of resolvers keyed off `uiLanguage`: an English
  card advertises and calls `https://api.openai.com/v1` + `gpt-4o-mini`, a Chinese card stays on
  `https://api.deepseek.com` + `deepseek-flash`. CodeFlying is deliberately *not* switched by language,
  because its model belongs to the hosted backend and an English build inventing `gpt-4o-mini` there
  would name a model CodeFlying does not serve.
- **Errand trips from the check card are handed to the page instead of made by it**, so the list
  survives the trip. On the check *page* tapping a row's fix button sent the user to Settings and coming back
  landed on the list; from the check *card* the same three taps ended on the desktop with a ball in the
  corner, because the card was closed and the assistant folded before the destination launched — which
  does clear the screen, since an overlay outranks every Activity, but throws the list away: a window
  gets no `onResume`, and the destination went out with `FLAG_ACTIVITY_NEW_TASK` into a task of its
  own, so back from Settings had nowhere of ours to return to. `SelfCheckUi.onErrand` (was
  `onYieldScreen`) now hands the host the Intent and lets the host say whether it can carry it.
  `Fix.Grant` goes through the same door, so Allow on the microphone and notification rows stops ending on the launcher too.
- **The prop-and-pose layer is on every look now**, not just Andee — stars, a heart, a shield, a sweat
  bead, the sliced lid, and the idle life that lets a look wear its own expressions and nap while
  nothing is happening. The argument for keeping it to one look was that the others had been tuned
  without it; that is overruled, because a look that is not alive standing next to one that is reads as
  the broken one. Switching it on for Imp needed a fix nothing at the call site would have hinted at:
  the lid's eye mesh was cut at a fixed 0.38 and Imp ships a resting eye already cut at 0.48, so an
  angry imp would have *opened* its eyes, in two moods, silently.
- **Andee's horns stand higher**, fade to a peach tip and hang on two springs, so a wiggle or a turn
  swings them and they ring out; the mood sets where they rest. While idle it now wears a short run of
  safe faces and naps for under a minute instead of sleeping until touched — faces that report what the
  device is doing, or that read as an alarm, are left out on purpose. Hands are a round palm, a thumb
  and two capsule fingers, and the wink carries a floating heart instead of a hand, because a V, a
  pointing finger and a thumbs-up were each tried and each misread at this size.

### Changed

- **Both READMEs lead with what it is.** They opened by showing what the thing *does* under a headline
  that framed it as a phone upgrade. The H3, the hero and the lead now say what it *is* and what it can
  *do* second, and the "why it is not the assistant already in your phone" section moved above the
  examples, which are now explicitly what its hands look like. Two earlier drafts were self-defeating
  and both were caught by reading them: one claimed "not a tool, not a companion" and then proved it
  with a capability list, the other denied being an assistant that operates your phone while the whole
  section below was exactly that. Neither is fixable with an adjective; the negations are now scoped to
  a category, and the section opens with the axis that separates the two — its own schedule, notebook,
  judgement about when to speak, and limits, all of which happen in the time you did **not** ask for
  anything.
- **The English README no longer contains a single Chinese character** (`\p{Han}` returns nothing): the
  language badge, five look names, a dozen Chinese-language links, the language markers on the CodeFlying
  sites and a button quoted in Chinese. The Chinese README keeps its own language.

### Fixed

- **`POST_NOTIFICATIONS` was declared in the manifest since the beginning and never requested** — never
  checked, never asked for, so on Android 13+ everything this app posts was dropped in silence: the
  persistent notification that is the way back to it, and every reminder `Scheduler` fires. The
  codebase half knew: `Scheduler` carries a comment blaming this permission for its own failure to
  notify, and nothing anywhere tested the claim. The row that looked like it covered this does not —
  The notification-access row is the notification *listener*, which can be granted and green while every notification
  the app sends disappears. Two things this needed beyond a catalog entry: `Perm.api`, because on
  API 32 and below the permission does not exist and `checkSelfPermission` answers **denied** for
  anything it does not know, so an ungated row would report a missing permission on every Android 12
  device with no button and no way to fix it; and `SOLO_IDS`, because that set is a whitelist and a
  permission added by someone who never read it routes to handoff.
- **Three documents still described things the code had stopped doing.** The data-flow table in both
  `DISCLAIMER`s and both `permissions` documents named `api.deepseek.com` as the default destination
  for the conversation, with no mention of the language dependency added in this release — so an
  English reader was told their data goes somewhere it does not. `CLAUDE.md` still called the
  `show_html` house style "the one deliberate exception" to the single-palette rule, two paragraphs
  after the same file recorded that the house style had been removed; there is no exception now. And
  the `POST_NOTIFICATIONS` row in the permission table said only that a reminder would not arrive,
  which undersold it: refusing also kills the notification that is the route back to screen control.

### Removed

- **The raised mitts are gone.** Andee's expressions were built around a pair of mitts raised at the
  side — a fist under the chin for thinking, two fists up for celebrating, a hand on the belly for
  resting — and every one of them read as a lump at the eighty dp the ball is actually drawn at, with
  the parts sharing the body's own colour and material. Dropping them is the honest version of "this
  does not work at this size": the ball keeps the paws it hangs off the screen edge with. No expression
  collapses into CALM for it, because a mood is read through its own `Face` row and its body motion,
  neither of which ever came from the pose table. Two leftovers are deliberate and both say so where
  they live: WAVE keeps its fan of motion strokes with no hand to fan off them, and HOLDING keeps an
  empty row, because the signboard it waits behind is a real card in the ball's own window.
- **The `show_html` house style**, along with the `LocalPrompt` §7 sentence that pointed at it and then
  asserted "that style is what you look like". Both were describing the prescribed look being removed.

## [0.3.0] - 2026-10-12

The release where the assistant stops living inside the accessibility service. Everything else here
is smaller than that; the split is the reason it is a minor bump and the reason the upgrade notes
matter, because a ROM that revokes accessibility now costs one capability instead of the whole app.

### Added

- **The body is a foreground service; the accessibility service is a shim.** The body — ball, card,
  microphone, ASR/TTS, brain socket, scrollback, todos — used to be constructed inside
  `ScreenBodyService.onServiceConnected()`, and that class is an `AccessibilityService`. So a ROM
  that revokes accessibility (HyperOS, observed) took the whole assistant with it: no ball, no way to
  talk, no way back except knowing which switch in Settings to find. The failure mode was never
  "screen control is gone", it was "the app is gone". `BodyCoreService` now owns everything else and
  outlives the accessibility service, which is reduced to handing the screen capability over and
  taking it back — so a revoked service costs one capability, and the return trip is one tap on a
  notification that is still there. `screen.*` goes through a provider, so a call with no
  accessibility throws a readable error instead of no-op'ing or holding a controller built around a
  dead binding; the shot and gesture guards moved from an `init` block to `wireScreen()`, called on
  every attach, because left on the previous controller they would simply be absent on the new one.
  The launch is a persistent notification that carries whether screen control is up and the way back
  to Settings — posted by the body, so it survives the state it describes. The launcher is the one
  context allowed to claim the microphone foreground-service type, because a microphone-typed service
  cannot be created from the background and every other starter is background.
- **The screen can be read with `MediaProjection` when accessibility is gone.** The split stopped a
  revoked service from taking the whole assistant, except that the assistant went **blind** — every
  way of reading a screen went through the accessibility service, so "what is this screen?" was the
  one question it could not answer on exactly the devices this project exists for. Deliberately a
  fallback and deliberately weaker: `screen.screenshot` only, because `look_region` and `zoom_aim`
  exist to zoom a target for a tap and `dispatchGesture` has no non-root substitute, so there is no
  tap on this path. The result says which way in it used, and carries no aim grid and no element list
  — handing the brain a picture it believes it can aim with would be worse than handing it fewer
  fields. Asked for only while it is the only option: the consent is a notification action, offered
  only when Andee is blind and has no capture, and `ACTION_ACCESSIBILITY_SETTINGS` stays the first
  action because the projection buys reading, not acting.
- **Every mood has a `Pose` on top of its `Face` row** — a wink, eyes that look up and aside, lowered
  or worried lids, a squashed resting body, two puffy hands (open, fist, pointing) and pop-in props.
  Five gesture moods (wink, wave, protect, celebrate, rest) can be asked for by name or synonym in
  the model's own text, and the prompt says to keep them rare. Only a look with `BallLook.gestures`
  reads it, so the other five characters are unchanged. There is deliberately no open mouth:
  cross-fading it with the thin smile drew both at once and one poked through the other.
- **The default ball is now the blue horned Andee** — a cobalt head with curled horns and glassy
  eyes, first in `BallLooks.ALL`, so a fresh install and a factory reset both land on it. Its numbers
  were measured off the reference picture through the renderer's own camera rather than eyeballed.
  The launcher icon set, README covers, social previews and org avatar are regenerated from the same
  source art.
- **The CodeFlying per-user token is read from `assets/andee_key.txt`**, with `BuildConfig` as the
  fallback for dev builds. Production APKs are built once from shared source and the per-user `kft_`
  token is written into that asset at package time, so the base APK stays identical across users and
  the Gradle build cache no longer misses per user.
- **`-PdbgSuffix=` builds a parallel package** for device verification. The package on the tablet was
  signed with a key this checkout does not have, so `pm install -r` was refused and the only
  alternative was uninstalling — which throws away chat history, notebook and vault. Off unless the
  property is passed, so ordinary builds are byte-identical.
- Debug builds gain `BallLabActivity`, which shows one mood on a plain background for screenshots.

### Changed

- **The version check moved to `https://api.heyandee.com`, from `https://andee.kuafuai.net`.**
  `VERSION_API_BASE` is still a hardcoded constant in `device/SelfCheck.kt`, and still the only
  address this app calls that the user did not configure — see `docs/permissions.md` — so the move is
  one line of source plus the three places that quote it and have to stay in step: both `permissions`
  documents and the note in `network_security_config.xml`.
  The old host was on the mainland, where the domain had to be ICP-filed before TLS would reach it at
  all; the new address resolves outside the mainland and retires that particular trap. It does not
  retire the other one: a domain still has to resolve *and* serve 443, and a fresh record is not
  everywhere at once. Measured while making the move, `1.1.1.1` still answered `NXDOMAIN` while
  `223.5.5.5` already had the address — on the wrong resolver that reads as "not found" on the version row,
  which is the intended failure rather than a fault to report. The service itself was checked before
  the switch: the new host answers `GET /api/version` with the same envelope, and the same `data`
  fields the client parses.
- **The self-check page now stays up until the user closes it.** It is a list, and a user with five
  rows works through them one at a time — out to a Settings screen and back for each — but the page
  used to hand the screen to the assistant as soon as it could draw one, which happens on the *first*
  of those returns, so the list was taken away after row one. On a page whose whole job is to walk
  somebody down a list, the user is the only one who knows when the list is finished. Closing it with
  the Done button puts the assistant back. Two surfaces fighting for the screen was the real bug underneath and
  it stays fixed by the same move: nothing unfolds a card while the page is up.
- **Closing a page puts the assistant back at full size, unconditionally.** The fold that makes room
  for a page is ours — our card is a fullscreen opaque overlay and would cover it — so undoing it is
  ours too, and it was missing from `pageClosed`. The first cut restored the card only if it had been
  fullscreen when the page went up; the report was the other way round, and closing the self-check
  card drew the same complaint and got the same answer.
- Both READMEs point their CodeFlying links at the `/andee` one-click landing page rather than the
  generic official site, so every jump lands on the Andee entry page.

### Fixed

- **`ui.show_html` no longer needs accessibility**, which it had started to. `awaitUncovered()` asked
  its question through `requireScreen()`, and `awaitUncovered` runs off `ensureCompact()` — which
  `ui.show_html` calls too, because our card would cover the page it is about to draw. So with
  accessibility off, the one tool that draws a page answered "accessibility is off" and the assistant
  told the user it could not show them anything. Introduced by `f364490`, which turned a
  nullable-but-always-present field into a throwing provider and rewrote every call site
  mechanically; the other sixteen are inside `screen.*` branches and are meant to throw. Returning
  early is also right on the merits: the wait exists so that a *tap* lands on the app rather than on
  the folded card, and there are no taps without accessibility.
- **The self-check no longer mistakes a re-creation for a launch.** This page sends the user out
  through its own rows, so the platform killing it while they are away and rebuilding it on return is
  routine here rather than an edge case: grant the overlay, come back to a restarted Activity, and it
  used to unfold the card and finish before a single row was drawn. `savedInstanceState != null` is
  what tells the two apart. A related report — "I granted it, came back, it still said no, then
  re-check was fine" — is the platform's answer about a setting changed seconds ago not being ready
  in the frame the user returns on: the notification-access row is `OK` only once the listener is
  actually bound, and the read that finds it unbound is also the one that requests the rebind.
  `settleRecheck` takes two more local passes and stops, because a page that keeps re-reading itself
  is a page whose numbers stop meaning anything. (The retry is reasoned from the code path, not
  measured: writing the notification listener into `enabled_notification_listeners` does not stick,
  so the state cannot be manufactured from adb.)
- **The body stays off the screen while the check page is up.** The report was "the check said the
  overlay was off, I granted it, came back, and I was on the assistant instead of the check" — the
  check was never gone, the page was still the resumed Activity with the assistant's fullscreen card
  painted over it. Two things put it there, both in the body's birth: `window.setCompact(false)`, and
  `window.show()`, which is subtler — this window is fullscreen *even when the ball is compact* and
  swallows the touches it does not use, so a page with a ball in the corner is a page whose buttons
  cannot be pressed (measured: the Done button did nothing). Telling a fresh launch apart from a return needed
  `SelfCheck.isPageOpen`, written down in the prefs file the startup check already uses. The startup
  self-check card is skipped while the page is up as well, because that page *is* the list.
- **A just-fixed row stops showing as red when the card resumes.** The card's own rows send the user
  out to a system screen and the only thing that happened on the way back was a language comparison,
  so a row the user had just fixed stayed red until they found "check again" — and a stale red row reads as
  "the fix did not take". An Activity gets a resume callback and an overlay does not, which is why
  this is a change to one host rather than to the list; it is local-only on purpose, because the
  probes hold the card open for seconds. The first resume is skipped (a full pass just ran), the
  three probed rows keep their last reading (`SelfCheck.mergeLocal`), and `render` restores the
  scroll position — which only started to matter once redraws stopped being deliberate.
- **A perch slide dragged the whole card off-screen, and a voice failure repeated forever.**
  A card held by the folded ball makes the window card-sized, so a perch slide (task start, page up)
  dragged the whole card into the bottom-right corner, half off-screen and untouchable; the perch
  entry points now take the signboard down first. Separately, the auto follow-up mic reopens after
  every answer, so a device with no voice set up raised the same failure card and scrollback row each
  time — now shown once per failure streak, reset by a working voice turn, leaving settings, or the
  user pressing the ball.
- **`screen.screenshot`'s schema claimed something false.** It opened "every screenshot arrives with
  an 8x12 AIM GRID drawn on it" and promised the element list, both exactly untrue on the projection
  path — which is the only screen tool that still works there. The runtime `note` said so, but the
  schema is what the model reads *before* deciding to call it.
- **Three documents still described the pre-split architecture.** `README.md` and `README.zh-CN.md`
  said "this app *is* the accessibility service" and pointed a reader at `ScreenBodyService.kt` as
  the file to read first — both false since `f364490`, which is the whole point of the split. The
  permission table in `docs/permissions.md` / `.zh-CN.md` said of accessibility that "every screen
  action errors", which stopped being true in `82f08db`: `screen.screenshot` has a fallback and the
  row now says so, along with what the fallback cannot do.

### Removed

- `tools/check_launcher_icon.py`, `tools/cosmic_backdrop.py` and the old `make_launcher_icon.py`,
  replaced by a rewritten generator built on `tools/andee_head.py`. All three were written for the
  previous character and a star-field backdrop, neither of which is in use any more.
  `tools/make_social_preview.py` generates the social previews, both README covers and the org avatar
  from the same source art.

## [0.2.0] - 2026-10-10

Three new capabilities and two permission fixes. A minor bump rather than a patch, because the three
are new capabilities by this file's own definition — the first release where that rule has had to
decide anything. The permission pair is the part worth reading: the app had been telling the model
things about the system permission sheet that measuring on a device showed to be false, in one case
in the direction that made the model act on a grant nobody had given.

### Added

- **A single tap on the ball now stops whatever is running and then listens.** A tap used to mean
  "listen" and nothing else, which left a gap the moment the ball was busy: to redirect it the user
  had to stop it first (three taps) and then ask again (one more) — two gestures for one intent.
  Tapping something busy and having it stop and listen is what one person does to another, and it is
  the only reading of the tap that is not "talk over the thing you cannot hear". The stop is
  `stopEverything()`, reused rather than reimplemented, so the single tap and the triple tap cannot
  drift into two different stops and the hub gets its `task.stop` either way. It is guarded on
  `dispatcher.isTaskActive()`: on an idle ball there is nothing to stop, and the call would still
  clear a pending "keep editing", tell the hub to stop, and flash "stopped" ahead of "listening…" — three side effects
  for a tap that only ever meant "listen". Triple-tap still stops, and is still the *immediate* stop,
  because a single tap has to wait out the double-tap window before it knows it is not the first of
  two. No gesture moved and no string changed: what a tap *does* depends on what is running, which is
  not something a line of text can say. Verified on device (HONOR AMM-AN00) — an idle tap listens
  without flashing "stopped".
- **A device with no conversation is offered ready-made scenes instead of a dead line.** It used to
  show "no conversation yet" and nothing under it. There is nothing to be helpful *about* yet, and a ball in the
  corner has taught a newcomer nothing about what the thing does, so the one moment this app can be
  proactive without being early went unused. The card now shows a hint line — "tell me, or pick one" —
  then a bubble per preset from `ScenePresets`, each labelled with the scene's name and carrying a
  sentence the user could have said: the name is what a person picks between, the sentence is exactly
  the part a newcomer cannot invent. Both are fields of one `ScenePresets.Text`, so a bubble cannot
  promise one thing and send another. **Tapping a bubble adopts the scene before it speaks** — this
  was wrong first and is the part worth naming: a scene is something the notebook holds, so a bubble
  that only sent its sentence asked the model about a scene nobody had saved, and it correctly
  answered that it had never seen one, in front of the user. It is `adoptIfMissing` rather than
  `adopt` on purpose: a bubble is offered whether or not the preset was adopted, and `saveScene`
  replaces the whole row including `prompt`, so adopting twice would put the preset's text back over
  the user's own edited version with nothing to say it had happened. Pre-adopting the presets at
  install was the other candidate and is worse — a written-down preset is an ordinary scene, so
  deleting one would have it come back at the next install, the language would freeze to whatever the
  device spoke then, and it contradicts `LocalPrompt` §11. Emptiness is the whole safety story and it
  is mechanical: the moment there is a conversation the strip is `GONE` for good, because a
  suggestion strip parked over a live chat would be the app talking about itself instead of
  answering. Two numbers are enforced rather than asked for by `ScenePresetsTest`, which caps every
  summary in both languages and checks that every name a bubble can send is one `byName` can look
  back up. Verified on device (HONOR AMM-AN00) by reading the notebook over the 9008 tool port.
- **Long-pressing a history row opens the log behind it.** The scrollback says what was said;
  everything that earned the right to say it — which tools ran, with what arguments, what came back,
  what the model reasoned, and what each request cost in prompt tokens — was already in
  `files/brain_trace.jsonl` and reachable only with `run-as`. The new page is built from those
  records and opened through `HtmlActivity`, which is where folding the card, hanging the ball off
  the ledge and the Done pill were already solved. Long-press because the other two gestures on a row
  are taken — a single tap opens a page row's page, a double tap reads a message fullscreen — and the
  two detectors had to become **one**, because `setOnTouchListener` replaces rather than chains, so a
  second one would have silently killed the first. **The row and its log are joined by
  `<process start>-<gen>`, and `gen` alone would have been wrong:** it is an in-memory counter that
  restarts at 1 with every process while the trace file is persistent, so on the test device `"gen":1`
  accounted for 176 of 278 records, and installing the change reproduced the collision within 89
  seconds. A scrollback matching on `gen` would have opened a reinstall's first turn onto yesterday's
  first turn, with nothing on screen to say so; `BrainTraceTest` locks the join down, dash included.
  Two things the page got wrong before it was ever shown to anyone, both found by reading records
  back off the device rather than by reasoning about them: the `turn` card labelled its body "Said",
  so the user's own sentence appeared in the assistant's mouth on the first card of the page (the
  heading is where the origin belongs, and `kind` is the field that knows it), and the `end` card
  said "Turn ended" when `BrainTrace.end` is only written on an *abnormal* finish — two of three
  turns measured had none at all. Records written before this commit have no `turn` field, so a row
  from before the upgrade offers no gesture, and the same rule keeps hub-brain rows, scene-change
  rows and notification read-outs from offering a dead entry.

### Fixed

- **`request_permissions` answered `{"started": true}` no matter what actually happened.** That was a
  hard-coded `true` meaning only that `startActivity` had not thrown. After a second refusal Android
  11+ draws no dialog at all and the call still said true, so the brain read "it is on" and
  immediately called `get_location`. Measured on turn `1791621839492-4`: the request returned at
  +19.257s, `get_location` fired at +20.839s and answered `permission denied` 1 ms later — the model
  gave the user 1.582s to find a dialog that was never drawn, then spent 34.6s looking for a way
  around it. The reply now carries what happened rather than that something was attempted: `mode`
  (`solo` / `handoff` / `settings`, who answers the sheet), `result` (`granted` / `denied` /
  `no_dialog` / `waiting`) and `next`, one line saying what to do about that result. `mode` comes from
  `routeOf`, a pure function of the ids kept on a whitelist so a permission added to the catalog later
  falls on "the user was asked once more than necessary" rather than "the ball agreed on their
  behalf". `result` resolves the distinction that pays for all of it: a refusal and a silent block
  both read `PERMISSION_DENIED` and demand opposite responses, and
  `shouldShowRequestPermissionRationale` — readable only from an Activity — is what separates them.
  `PermissionRequestActivity` used to drop `grantResults` on the floor; it now hands the verdict back
  through arm/await/settle, and `PermissionsController` waits `DIALOG_GRACE_MS` for it, a grace
  period rather than a wait for the human. Verified on HONOR AMM-AN00 (location/solo/granted,
  contacts/handoff/waiting, steps/solo/waiting, each with the sheet really on screen); 37 unit tests
  pass, with `PermissionsRouteTest` pinning the routes from the id list without a device.
- **The permission sheet was drawn *underneath* the expanded card, so the user could never see it.**
  An expanded card is a fullscreen surface and our overlay sits above every activity, so
  `GrantPermissionsActivity` was drawn below it. Verified on device: the dialog really was up — it
  was both `ResumedActivity` and `mCurrentFocus` — while a screenshot showed nothing but the card. A
  question nobody can see is worse than no question, and it is exactly the state `waiting` promises.
  `device.*` deliberately never folds, on the reasoning that reading state is a conversation rather
  than an actuation; this branch is the exception that reasoning did not cover, because it addresses
  the user in a window of the system's own. `device.scan` and `device.vault.fill` already fold for
  the same reason, so this joins them — and only when `confirm=true`, because the first call is the
  script the brain reads back and that one belongs in the card.
- **Both READMEs gained a one-click build page and had its likely-reading fixed.** The nav and the
  CodeFlying paragraph now point at the `/andee` page — describe the app, tap the build button, get a
  pre-configured package with a QR code — and the "any app" feature line was reworded in both
  languages, because "not a list of integrations" was being read as a claim about integrations this
  app has rather than a claim about aim. The page itself is a separate service with no direct
  relationship to this project, as that paragraph already says.

### Changed

- **The prompt's rule about the system permission sheet was wrong, and is now the measured one.** It
  said a system permission sheet "does not take a tap you inject", and that turned out to be false.
  With the card folded, an injected tap on "Always allow" really does grant: `READ_CONTACTS` went
  false → true, and one tap on "Allow this time" produced an `ACCESS_FINE_LOCATION` carrying `ONE_TIME`,
  a flag only that button can set. The earlier four failures had one cause between them and it was our
  own card eating the touch, not the sheet refusing it. So the rule changes from "you cannot press it"
  to **"you cannot read it, so do not aim at it"**: the sheet exposes no accessibility elements at
  all, so any tap is blind, and the button at the bottom of this particular screen is
  "Deny and don't ask again", which nothing can undo. That is the real reason this step stays the user's, and
  it is a reason that survives being measured. `LocalPrompt` §0's `type_text`-only rule is stated as
  the general one — a tool saying it pressed something is not the thing being done — and §1's list of
  genuine exceptions gains the sheet, which is worth naming because putting it on screen is the whole
  of what firing does.

## [0.1.1] - 2026-10-09

### Fixed

- **Four strings sent the user to a menu label that no longer exists — and one to a gesture that does
  not open settings at all.** The settings card has been reorganised twice since these sentences were
  written, and the sentences stayed behind, which is the worst kind of copy bug: the user follows the
  instructions, finds nothing, and concludes the app is broken rather than the text.

  - `check_detail_brain_key_missing`, `check_detail_brain_hub_missing` and `brain_no_key` all said
    **⚙ → "Brain"**. The section header is `settings_section_brain`, which reads
    **"Backend"** — there is no label containing that word in the settings card at all. The
    Volcengine tab names use it only in prose ("the brain lives on a hub you run yourself"), never as a
    destination.
  - `check_detail_voice_key_missing` said **⚙ → "Speech (Volcengine)"** — a
    section that no longer exists. The Volcengine rows were moved into Backend → on-device, and
    `settings_section_voice` / `settings_section_voice_key` are still in both string files but
    referenced by nothing. The path is now the same **Backend → on-device** as the two brain keys, which is
    where the field actually is.
  - `brain_no_key` also said **"long-press the ball, open settings"**.
    `onLongPress` does not open settings; it calls `dispatcher.expand()`, which brings the full card
    back. Settings is the ⚙ button in that card's control bar. The sentence now names both steps,
    because the first one is genuinely required — the control bar only exists at full size.
  - `check_detail_brain_hub_missing` gained the missing precondition: **the hub tab is hidden unless
    Advanced → "Show cloud option" is on.** `showHub` gates the tab in the picker, but `brainMode` does not
    consult it, so a device can sit in hub mode with the tab that fixes it invisible — turn the
    toggle on, pick the hub, turn it off again, and the self-check would have pointed at nothing.

  Every label these strings now name was checked against its resource value rather than transcribed
  from the sentence being replaced — which is how the one remaining error was caught: the English
  label is **"Show cloud option"**, singular, and the first draft of this fix wrote it plural.
- **Both READMEs promised a first-run wizard that no longer exists.** "The first run is a stepped
  setup that walks you through both" — in both languages — `8dac568` deleted the wizard
  (`ui/FirstRunUi.kt`). A missing setup guide is indistinguishable from a broken install, so this
  read as a bug report rather than as stale prose. Both now say what the app does instead: the
  self-check runs on every start, the card names the missing key, and its button opens settings.
- `README.md` named the Volcengine field by its Chinese label, while the English
  build displays **Volcengine API key** (`settings_voice_api_key`). The Chinese README already had
  this right; only the English file was pointing at a word that never appears on an English screen.
- **A picture, a video or a clip now goes into a page, instead of being handed over as a URL.** The
  prompt said the opposite, and said it wrongly: *"this device cannot turn an image into a URL, so an
  `img` tag will not open"*. That is false for anything on the network — the page is served against a
  fake `https://` origin, the app holds `INTERNET`, and WebView loads an absolute `https://`
  subresource without complaint. What is true is narrower, and the old sentence flattened it into a
  blanket ban: a *local* file has no address a page can point at, and `http://` is refused. So the
  model, obeying its instructions, could not put a picture on screen — and what it did instead was
  hand the user a URL to open, on the device they were already holding. §7 now asks for the embedding
  directly (`<img>`, `<video controls>`, `<audio controls>`, absolute `https://` only), separates a
  media file from a *site* — the latter belongs in Chrome through `open_url`, not in an `iframe`,
  which most sites refuse — and says outright that passing over a bare URL is not an answer. The
  wrong half of the same sentence sat in the `show_html` description too, which is the text the model
  reads while composing; media is carved out there as well, because what that rule protects is a
  self-contained shell. Verified by compiling and by reading both strings back out of the compiled
  classes — the new text is present, all three old phrasings gone. **Not verified on a device**: none
  was attached, so a real `https` image and a real `.mp4` rendering in the page are still
  unconfirmed.
- **The launcher icon could become a permanent dead end, because the overlay was inferred from a view
  object instead of asked for as a grant.** First run, accessibility on, the overlay grant off: the checklist
  came up, the user closed it, and from then on the icon did nothing — no screen, no error, no way
  back in short of reinstalling. Nothing was removing the ball's window. On HONOR/MagicOS a denied
  `SYSTEM_ALERT_WINDOW` does not make `addView` throw: it succeeds, and the system hides the window
  instead — present in the window list with `alpha=0.0`, `mAppOpVisibility=false`,
  `isReadyForDisplay()=false`. So the view object outlives the grant, and `FloatingWindowUi.isShown()`
  — which is only `root != null`, a memory marker and nothing more — keeps answering true while the
  user can see nothing at all. `ensureOverlays()` opened with exactly that test, so
  `SelfCheckActivity` took its expand-and-finish branch, called `expand()` on a card that cannot be
  drawn, finished itself, and left an empty screen; the checklist branch, which is the one that names
  this failure in red, never ran. The grant is now asked **first and directly**, as
  `Settings.canDrawOverlays()` — the same predicate `SelfCheck.overlay` uses for the overlay row, so the
  page and the door can no longer contradict each other (which is what the user hit: the page said
  "off" while the door said it was on screen). It also covers revoking a grant that was already given, when `root`
  stays non-null for the life of the process. `showSelfCheckCard()` guarded with the same marker,
  where its own comment had claimed it already did. Verified on the device (LIO-TL00) on **both**
  paths, because this edits the working path's entry test too — grant denied now reaches the
  checklist (13 activity records), grant allowed still takes the expand branch (0 records); checking
  only the first would have shipped "the icon always shows the checklist".

### Changed

- **The overlay-permission row now leads the self-check list**, ahead of accessibility. Both are hard gates and
  the order between them was arbitrary; what settles it is that they fail in opposite directions.
  With the service off the app is visibly inert — nothing to run, and every row below is decoration.
  With the grant off it *looks* broken instead: the service runs perfectly well, silently cannot
  draw, and tapping the icon appears to do nothing at all. That second shape is the one users misread
  as "the app is dead", so it goes first.
- The accessibility row's off-state copy named the row's list position — *"the rest of this
   which the reorder above invalidated. It is now *"nothing else here matters"* —
  *"nothing else here matters either"*: same meaning, no longer positional. Same edit in both string
  files.

### Removed

- **The first-run wizard is gone** (`ui/FirstRunUi.kt`, its wiring in `ScreenBodyService`, the re-run-setup
  button on the self-check card, and its strings in both languages). The wizard and the startup
  self-check both wanted to put a card up on a first start, and when the check found a problem it
  covered the wizard, so a new user saw whichever lost the race. The wizard was removed rather than
  arbitrate between the two. The self-check's own prefs file stays, as does
  `PermissionRequestActivity`, which the check still uses; an old `wizard_done` flag on existing
  installs is simply never read. The wake word is now offered only from settings and as a note row on
  the self-check. The two READMEs had promised this wizard — see the *Fixed* entry above.
- `check_page_no_overlay` and the `SelfCheckUi.note()` helper it was the only caller of. The note
  explained that the settings sheet is drawn as an overlay — a fact the page does not need to teach,
  because the overlay row directly below it already carries the fix, in that row's own words and with a
  button. `fixButton()`'s comment used to point at "the note above the list"; it now states the rule
  on its own: a button that does nothing is worse than no button.

## [0.1.0] - 2026-10-09

The first tagged release. Because nothing was tagged before it, this section is the whole project
rather than a delta from a previous version — it reads as a long list of *Added* for that reason, not
because a release this size is the norm.

### Added

- **`docs/architecture.svg` + `docs/architecture.zh-CN.svg`, and a `## Architecture` section in both
  READMEs.** The README described pieces — the ball, the vault, the dog — and never showed how a turn
  actually moves. Drawing it forced two corrections to the diagram before it was right, both of them
  worth recording. The first was factual: the draft showed "two brains" when there are **three
  mutually exclusive backends**, and it marked the hosted one as the default when an unconfigured
  device reads as `BRAIN_LOCAL`. The second was structural and worse: the draft stopped at the
  backend and drew nothing after it — no agent loop, no outputs, which is most of the interesting
  half. No class is named after the harness; it is `LocalBrain` itself. The picture now shows the
  system prompt held constant with the volatile parts (clock, notebook index, scenes, photos)
  deliberately inlined into the user message so the provider's prefix cache still hits, the tool
  table rebuilt every turn, and the non-streaming step loop with its guards. Outputs come back
  through six channels, none of which had been drawn: speech, text, a full-screen page, saved
  artifacts, long text, and the dog. Both files are hand-built SVG with hardcoded colours, because
  GitHub renders a README image through `<img>` and `var(--color-*)` resolves to black there; both
  were rendered at 1:1 and read back to check for overflow, which caught three layout faults that
  reading the source alone would not have shown.
- **Both READMEs now ask for your use case (`## Showcase`).** The
  README described what the thing can do and never asked anyone to show what they did with it,
  which is how a project ends up with no idea whether the odd corners work. The section is an
  invitation and three things that make a post useful: the device and ROM (this app talks to Android
  at a level where ROMs disagree, so "worked on my phone" is not reproducible), the sentence you
  actually said, and a link if there is one. It goes to a Discussions **Show and tell** rather than
  the issue tracker, which follows the rule `config.yml` already sets — the tracker is for
  reproducible defects, and a story is not one. Two details are deliberate rather than decorative.
  **GitHub cannot play a video inside a README** (iframes are stripped, so a YouTube link stays a
  link), and the section hands over the thumbnail form that makes it look like a player anyway,
  rather than letting twenty people discover that separately. And it says plainly that **a
  screenshot of this app is a picture of your messages** — the one thing in a showcase that can do
  real harm is posting someone's private screen content, including your own.
- `LICENSE` — Apache-2.0 with additional conditions (Part A: no multi-tenant SaaS, no
  white-labelling that removes the branding, no embedding it in another product without written
  authorisation). Note that GitHub reports this as `NOASSERTION`.
- `SECURITY.md` — threat model, the five known security limitations, and a private reporting channel.
- `docs/permissions.md` + `docs/permissions.zh-CN.md` — why the app asks for the permissions it asks
  for, each one tied to a concrete tool and to what happens if you refuse it. One file per language.
- `CONTRIBUTING.md` + `CONTRIBUTING.zh-CN.md` — code style, the rules for the three kinds of Chinese
  in this codebase, and the contribution licensing terms. One file per language.
- **A `Signed-off-by:` sign-off is now required on every commit, and CI enforces it.**
  `.github/workflows/dco.yml` checks each commit on a pull request, and both `CONTRIBUTING` files
  plus the pull request template explain the one-line change (`git commit -s`). This is a
  [DCO](https://developercertificate.org/), not a CLA: the contributor keeps their copyright and
  certifies only that they had the right to submit the work. The reason it exists is LICENSE A.2 —
  the project is relicensed under terms that differ from stock Apache 2.0, and A.2 asks contributors
  to grant that. A term nobody has explicitly agreed to is a term a contributor can later dispute;
  a sign-off line is a per-commit record that they did. Deliberately *not* a CLA, which would need a
  signature process and an entity to hold it — friction that buys nothing here.
- `CODE_OF_CONDUCT.md` — Contributor Covenant v2.1.
- `THIRD_PARTY_NOTICES.md` — generated dependency license inventory: 93 components across 5
  licenses. Notably flags that **ML Kit and the `play-services-*` stubs are not open-source**.
- `tools/license_audit.py` — the generator behind the notices file.
- `.github/` — bug report and feature request issue forms, a pull request template, and issue
  routing with blank issues disabled.
- `README.zh-CN.md` — the Chinese setup guide, split out from `README.md`.
- `DISCLAIMER.md` + `DISCLAIMER.zh-CN.md` — the user-facing terms, one file per language (same
  convention as the two READMEs). Covers the four things Apache-2.0 does not: **where your data
  actually goes** (at the time this file was written, no telemetry in the repo, but speech goes to
  Volcengine and the conversation plus every tool result goes to the brain endpoint you configure),
  **what the software is genuinely
  capable of** (it can complete a payment by tapping; the only guardrail is Android's `FLAG_SECURE`),
  **who bears the consequences**, and third-party platform terms.
- `docs/acceptable-use.md` + `docs/acceptable-use.zh-CN.md` — the prohibited uses, one file per
  language. This is the part that matters legally: naming the prohibited uses is what keeps this
  project from being characterised as *providing assistance* to them. It calls out fund movement
  (including the Article 287-2 aiding-information-network-crime exposure) and publishing false
  information separately.
- `docs/permissions.md` gained a **"data goes where"** section — the honest version of
  "we do not collect your data", which is true of the maintainers and false of the device.
- Both READMEs now carry a warning above the fold and a `## Disclaimer and acceptable use` section;
  `SECURITY.md` states the triage boundary (steering it is a vulnerability; doing what it was built
  to do is documented behaviour).
- **A version row in the self-check list — the first thing this app ever sends anywhere.** It asks a
  version service whether a newer build exists and reports the answer as one more row in the same list
  the device diagnostics live in: one row, one renderer, no second settings page. The level mapping is
  the deliberate part. **Current → OK, newer release available → NOTE, forced update → WARN, and
  everything undetermined → NOTE.** An outdated app is not a broken one, and only FAIL and WARN may
  raise the startup card, so a vendor that popped a card every time it shipped a patch would be
  nagware — which is the exact failure `SelfCheck`'s level doc is written against. The forced case is
  the one real WARN, because the service is then saying this build is no longer supported. A
  statistics endpoint being unreachable is never the user's problem, so it never paints the card red.
  **The address is a hardcoded constant, not a setting.** `VERSION_API_BASE` lives in
  `device/SelfCheck.kt` (`https://andee.kuafuai.net`), with no settings row, no build flag and no
  `local.properties` key behind it, so turning the check off means editing that line and rebuilding.
  That is a deliberate trade rather than an oversight: a check which can be silently misconfigured is
  a check that silently stops working, and this one should be on for everyone or off for everyone.
  The price is that there is no build-time way to opt out, and `docs/permissions.md` now says so
  instead of pretending otherwise — which it had to, because the first cut of this feature did put the
  address in `local.properties` and then advertised a fresh clone as reporting nothing.
- **`res/xml/network_security_config.xml` deliberately did *not* gain the version host, on the second
  pass.** Android refuses cleartext HTTP to any host not named there, and what it produces —
  `CLEARTEXT communication to X not permitted by network security policy` — arrives as a version row
  reading "not found", a message about a policy rather than about anything the user did. So the first cut
  added the service's IP address to that file, which is the right move *for a `http://` address*. The
  address then became `https://andee.kuafuai.net`, which needs no exception at all, and the entry was
  deleted rather than left in place: an exception for a host nobody calls only widens what this app
  will talk to in the clear. `124.71.176.202` (the CodeFlying backend) is still listed and still needs
  to be. The rule to carry forward is that this file tracks the addresses actually in use — adding a
  line is part of pointing the app at plain HTTP, and removing it is part of moving away.

### Fixed

- **A failed voice turn crashed the app with `StackOverflowError` instead of reporting the failure.**
  `AsrController`'s listener implements `onError(msg)`, and its body called `onError(msg)` — written
  meaning the constructor's `(String) -> Unit` callback that routes the message to the owner. Kotlin
  resolves an unqualified call to the nearest scope first, so the line called *itself*: one recognizer
  error became infinite recursion until the stack ran out (`stack size 1039KB`, thousands of frames of
  `AsrController.kt:152`). Any Volcengine failure — a dead network, an expired token, a rejected handshake —
  took the whole process down, which is the opposite of what the callback is for. Now qualified
  `this@AsrController.onError(msg)`. The listener's three other callbacks are unaffected: none of them
  shares a name with an outer member.

### Changed

- **The APK went from ~39 MB to ~8 MB.** Two causes, both structural rather than a matter of
  trimming assets. First, every build produced one *fat* APK for all four ABIs, and ML Kit's
  `libbarhopper_v3.so` is ~4 MB, so it was paid for four times over. Second, the debug variant ships
  un-shrunk dex (16.7 MB of it, against 3.2 MB after R8), and `minifyEnabled` was off on release too.
  Fixed with an ABI split, `minifyEnabled` + `shrinkResources` on release, and deflated native libs.
  **The output paths changed**: there is no `app-debug.apk` any more, only `app-<abi>-debug.apk` and
  `app-<abi>-release-unsigned.apk`. `assembleRelease` also runs in CI now, as a job parallel to the
  debug one, because R8 and `lintVitalRelease` only run on release — and that is exactly how a
  release build that failed on every invocation sat in the repository unnoticed.
- **`assembleRelease` builds again.** It had been failing in `lintVitalRelease` on
  `res/xml/backup_rules.xml` and `res/xml/data_extraction_rules.xml`, both of which exclude
  `domain="device_root"`. That is a real domain — the framework has carried
  `DEVICE_ROOT_TREE_TOKEN` since device-protected storage landed, and the Auto Backup reference
  defines `device_root` as "like root but for the device-protected storage". AGP 8.3's
  `FullBackupContent` check validates against a five-value list that predates it, so the error was a
  false positive. Both lines now carry a scoped `tools:ignore`; the check still covers everything
  else in those files, including a genuine typo in a domain.
- **`SECURITY.md` no longer claims a safeguard the software does not have.** Its
  prompt-injection section stated that destructive or irreversible actions are
  *"gated behind `ask_user` / `confirm` flows"*. That is not true, and it was the
  one sentence in the repository that could be turned against the project:
  `ask_user` is an ordinary tool the model chooses to call, `confirm` exists on a
  single tool (`request_permissions`, where it prevents an unwanted permission
  dialog, not a payment), and nothing forces a confirmation before
  `tap_by_coordinates` — which is the path a payment is completed along and the
  very thing the disclaimer is about. `DISCLAIMER.md` and
  `docs/acceptable-use.md` had both already said the opposite in as many words
  ("no guardrail whatsoever", "no technical guardrail on that path"), so the three
  files contradicted each other and the security page was the one that was wrong.
  It now states the posture plainly: the checks on risky actions are instructions
  to the model rather than enforcement, and the device should be assumed drivable
  into anything its permissions and the user's own logged-in state allow —
  including moving money — with no prompt appearing. The same edit strips detail
  that made the page read as a recipe rather than a warning: the example request
  frame, the per-method enumeration of what a LAN peer can reach, and the note
  that the listening port is a fixed constant. The port number stays — it is what
  the recommended mitigation (firewall it) refers to. Two smaller changes follow
  from the same reasoning. The scope list no longer counts "an injection caused an
  action the user did not ask for" as a vulnerability, because that is the
  limitation §3 now describes rather than a promise the project can be held to;
  and the reporting promises are now conditional ("where we can", "no timeframe is
  promised"), so a small project is not bound to a reply it may not send. A line
  at the top states the out-loud version: nothing in the file is a promise that
  any particular action is prevented.
- **`LICENSE` Part A gained a fourth condition, (d): shipping the software inside another product
  now needs written authorisation.** Conditions (a)–(c) already stopped white-labelling, multi-tenant
  SaaS and dropping the attribution, but nothing stopped someone taking the code and folding it into
  a product they sell — which is the one path that makes the rest moot. (d) closes it, and is written
  as narrowly as it can be: running it yourself, running it across your own organisation's devices,
  forking it and distributing the source so others can build it, and charging to install, configure
  or support it for a client who runs it themselves are all still permitted. The line drawn is
  between a **service** and a **product** — doing work that uses the software is fine, shipping the
  software as part of something else is not. The heading and the lead sentence were corrected from
  "three conditions" to match. `README.md`, `DISCLAIMER.md` and `docs/acceptable-use.md` reference
  Part A without counting the clauses, so no copy drifted — the enumerations they do carry are
  illustrative, and only `LICENSE` itself states all four.
- **Positioning corrected throughout the docs.** Both READMEs and `CLAUDE.md` described this as
  "the body side of a Physical Agent system" — i.e. an assistant that a brain drives. That
  contradicts the project's own first instruction, in `LocalPrompt.TEXT`: *"you are not a tablet's
  assistant, you are an individual, and the tablet is only what carries you."* The docs now lead
  with the individual, and give the body / persona / notebook mechanisms that back it up in code
  (fourteen moods and idle fidgets, `BallLook.persona` reaching the head of the system prompt, the
  private `Notebook`, the `(to yourself …)` self-review turn, and §11's rule that the character is
  delivery and never a licence). No code changed — the code was already right; the prose was not.
- `README.md` is now the **English** front page. The detailed Chinese provisioning guide moved to
  `README.zh-CN.md`, and both cross-link.
- `gradle.properties` no longer pins `org.gradle.java.home`. It previously hardcoded a
  machine-specific JDK path, so the build failed immediately for everyone else while looking like
  an environment problem. The real constraint (JDK 17–20; Gradle 8.4 cannot run on Java 21+,
  Android Studio's bundled JBR 25 is rejected) is now documented instead of worked around.
- `tools/check_look_sync.py` derives the repository root from its own location instead of a
  hardcoded absolute path.
- `tools/dump_faces_ascii.py` and `tools/verify_faces_lab.py` locate Chrome by `CHROME_PATH`, then
  common install locations, then `PATH`, instead of hardcoding a macOS-only path.
- `CLAUDE.md` corrected: the Gradle JDK note, the stale claim that the checkout is `frontend/`, and
  the description of which README is which.
- **Every bilingual document now follows one file per language, and each language links only to its
  own counterparts.** `CONTRIBUTING.md` and `docs/permissions.md` were written in Chinese under the
  English default filename — the opposite of the convention the other four files follow. They are
  now split into `.md` (English) and `.zh-CN.md` (Chinese), and every Chinese page that used to
  point at an English file now points at the Chinese one, so a Chinese reader is never dropped onto
  an English page. `SECURITY.md` and `CODE_OF_CONDUCT.md` have no Chinese edition; the Chinese docs
  now say so explicitly instead of linking silently.

- **Both READMEs restructured and fact-checked against the code.** The 16-item feature list now sits
  above *Quick start* instead of below *What it looks like*, so the page leads with what the thing
  does; both nav bars follow. Three claims were corrected against the source: the ball has
  **fourteen** moods (`ui/ball/Mood.kt`), not fifteen; the tool list no longer names `files` (there
  is no file tool — `location` does exist); and the Kotlin line count is now 43k+. The
  two-credential block puts the **brain key first and marks it required**, with the voice key second
  and optional — voice used to lead, which buried the one whose absence stops the brain from running
  at all. The "Things you can just say" table no longer repeats the WeChat example: that one is told
  as a single moment above the table ("you are driving when your mother messages you"), which is what
  the rest of the list is a variation on. And a short note near the top now says plainly that
  **Android is the only supported platform today** — the "any device with a body" line is about what
  the software is written for, and was reading as though other platforms already shipped.

- **Four documents corrected where they contradicted the code — no behaviour changed.**
  `DISCLAIMER.md` / `DISCLAIMER.zh-CN.md` and `docs/permissions.md` / `docs/permissions.zh-CN.md` all
  listed `brain=hub` first in their "data goes where" table's *Default* column, which read as though
  the hub were the out-of-box mode. It is not: `VoiceConfig.brainConfig` reads
  `getString("brain", BRAIN_LOCAL)`, and the `brain` picker lists the on-device option leftmost for the same reason.
  The row now reads `brain=local` (the default) → `api.deepseek.com`, with hub mode as the other
  case. Separately, `CLAUDE.md`'s "Existing but not wired up" section claimed there was **no `tts.*`
  dispatcher namespace** — there is one (`tts.speak`, `CommandDispatcher`); what it lacks is a
  `ToolSchemas` entry, which is the actual reason the model cannot call it, and the distinction
  matters because the fix for "let it choose to speak" is adding a schema, not a branch. The
  matching comment at the head of `CommandDispatcher` ("will be added later") is corrected too. The
  error was load-bearing in the disclaimer especially: a reader who believed hub was the default
  would size the data-path risk wrongly.

- **CodeFlying is now introduced as what it is, and disclaimed.** *Quick start* used to name
  codeflying.app / codeflying.net as simply "the fastest route", which read as though it were this
  project's own or affiliated build channel — an impression a reader could act on (and later hold
  *this* project to). It is now described in its own terms first — **a general platform where you
  describe the app you want and it develops and publishes it** — followed by one plain sentence:
  **a separate service with no direct relationship to this project**, its own terms, pricing and data
  handling. Same treatment in `## Using Andee`; the Chinese getting-started section
  table carried it too, and has since been removed (see *Removed*). The practical guidance is
  unchanged (still the shortest path; still an APK you have to install; the free token allowance is
  still finite and then paid), because none of that was wrong — only the implied ownership was.

- **The English README now addresses an English reader — in its examples and in the panel names it
  points at.** Two different faults with one symptom: a reader concluding the page is not for them.
  The examples were the Chinese ones with the words swapped (`WeChat`, `Taobao`, `JD`), and an
  example is precisely what a reader uses to judge whether something is addressed to them. They are
  now `WhatsApp`, `Amazon` and `eBay` — WhatsApp rather than a platform-native messenger because this
  runs on Android only, and naming an iOS-only one would be a false claim rather than a foreign one.
  Two of the scene examples needed rewriting rather than substituting: "look after my WeChat" is
  a Chinese scene example carried across word for word, which is not a sentence English builds. Separately, the
  panel names were the Chinese labels, so a reader on an English device was being told to look for
  **a Chinese label** — a word their screen does not have. They now use the labels `values-en/` actually ships:
  **Scenes**, **Made for you**, **What it remembers**, **Voice API key**.
  The **measured** passage keeps every fact. Xiaomi Pad 5, MIUI, WeChat 8.0.78 and the 219-node count
  are what was observed, and changing the app name there to make the prose consistent would be
  inventing a measurement. Only its framing moved: it opened on WeChat, which a non-Chinese reader
  reads as someone else's app having someone else's problem, so it now opens on the general
  behaviour and names WeChat as the one we happened to measure.
- **The blanket "no telemetry at all" claim is gone, because the version row above
  made it false.** It appeared in nine places — `DISCLAIMER.md`, `DISCLAIMER.zh-CN.md`,
  `docs/permissions.md`, `docs/permissions.zh-CN.md`, both READMEs, and both README badges — and all
  nine were in the same voice: not "we anonymise it", not "opt out in settings", but a flat statement
  that the reader was invisible. A check that carries an IP, a phone model and a stable per-install ID
  is telemetry by any ordinary reading of the word, so keeping the sentence would have converted a
  product guarantee into a lie. The replacements keep the half that is still true and still
  checkable — no analytics SDK, no crash reporting, no event tracking, no advertising ID, all four
  confirmed by the same grep the old text quoted — and then state the single exception field by field,
  in a row of its own in the data table, next to an explicit list of what that request does *not*
  carry (conversation, screen, app list, contacts, location, crash log, advertising ID). `docs/permissions.md`
  is the canonical copy; the rest point at it. The badge went from `telemetry-none` to
  `telemetry-no content`, because "no content" is the claim that survives.
  Worth recording for whoever edits these next: the old sentence was true of the *repository* on the
  day it was written and was always going to be false the moment the project shipped anything that
  phoned home. This file had already annotated that distinction in the DISCLAIMER's own entry above —
  *"we do not collect your data", which is true of the maintainers and false of the device* — and the
  claim that had to be corrected anyway was the neighbouring one about the maintainers.

### Removed

- `brain/andee-system-prompt.md` — an unused mirror of the cloud brain's prompt. It had no runtime
  role (no build step references it, no code loads it). **The cloud brain mode itself is not
  retired**; only this repo's copy of its prompt was removed. Recover it with
  `git show <rev>:brain/andee-system-prompt.md`.
- `dog/NRF24_Dongle/README.md` reference in the README — the dongle firmware is not in this
  repository and never was. The README now says so.
- The Chinese getting-started section in `README.zh-CN.md`, heading included. Its visible content was three
  rows announcing that the demo video and the screenshots do not exist yet, which tells a reader
  nothing they cannot see for themselves. The heading went too: once the table was gone the section
  held nothing but an HTML comment, and a heading over nothing renders as exactly that. Every
  pointer to it went as well — the nav anchor, the header comment, and the sentence above. In its
  place, a slot for the demo video sits directly under the hero in both language files, and the
  asset checklist moved down beside the contributors block, which is where the English file already
  kept it.

### Known issues

- `tools/verify_faces_lab.py` reports 7/16 assertions passing against the committed
  `reports/ball-faces-lab.html`. This is pre-existing (identical before and after the portability
  changes above) and is not caused by the open-sourcing work. Left as-is; worth resolving before
  contributors are pointed at the ball-face lab.
- The debug WebSocket server (`net/BodyWsServer`) binds `0.0.0.0:9008` with no authentication. See
  `SECURITY.md`. Not fixed.
