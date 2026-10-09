# Changelog

Notable changes to Andee. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Versioning policy

- `versionCode` / `versionName` live in `app/build.gradle` and are currently **`2` / `"0.1.1"`**.
- Releases are tagged `vMAJOR.MINOR.PATCH`, `versionName` mirrors the tag without the leading `v`,
  and `versionCode` increases by one per release. `versionCode` must increase **monotonically** or
  Android refuses the upgrade; the same number must never be reused for two different builds.
- MAJOR for a change that breaks the wire protocol or an existing configuration; MINOR for a new
  capability; PATCH for fixes.

**The project starts at `0.x`, and that is the accurate number rather than a modest one.** Nothing is
frozen. The tool-schema contract a hub brain speaks has been rewritten repeatedly
(`net/ToolSchemas.kt` alone has changed in eight commits since the first), the `local.properties`
build flags that decide the first-install language, the thinking default and whether CodeFlying is
compiled in have all been added since, and a saved scene is a shape that only appeared in this
release. Each of those is something a user or a second implementation would have to rebuild against,
so `0.x` is being honest that MINOR may break. The release that moves off `0` should be the one where
the wire contract is written down well enough that a second implementation could be built from it.

**A version number identifies a release, not a build.** `versionCode` now moves with each release,
so the installed app can say which *release* it came from — but nothing in it moves when `main`
does. A build made from a commit between two tags reports the older release's number, so for
anything not built at a tag, identify it by the short commit hash it was built from. (Embedding that
hash into `versionName` at build time would close this hole; it is not implemented yet.)

## [Unreleased]

## [0.1.1] - 2026-10-09

### Fixed

- **Four strings sent the user to a menu label that no longer exists — and one to a gesture that does
  not open settings at all.** The settings card has been reorganised twice since these sentences were
  written, and the sentences stayed behind, which is the worst kind of copy bug: the user follows the
  instructions, finds nothing, and concludes the app is broken rather than the text.

  - `check_detail_brain_key_missing`, `check_detail_brain_hub_missing` and `brain_no_key` all said
    **⚙ → 「大脑」 / "Brain"**. The section header is `settings_section_brain`, which reads
    **「后端」 / "Backend"** — there is no label containing 大脑 in the settings card at all. The
    火山 tab names use 大脑 only in prose ("the brain lives on a hub you run yourself"), never as a
    destination.
  - `check_detail_voice_key_missing` said **⚙ → 「语音（火山引擎）」 / "Speech (Volcengine)"** — a
    section that no longer exists. The Volcengine rows were moved into 后端 → 本机, and
    `settings_section_voice` / `settings_section_voice_key` are still in both string files but
    referenced by nothing. The path is now the same **后端 → 本机** as the two brain keys, which is
    where the field actually is.
  - `brain_no_key` also said **"长按小球打开设置" / "long-press the ball, open settings"**.
    `onLongPress` does not open settings; it calls `dispatcher.expand()`, which brings the full card
    back. Settings is the ⚙ button in that card's control bar. The sentence now names both steps,
    because the first one is genuinely required — the control bar only exists at full size.
  - `check_detail_brain_hub_missing` gained the missing precondition: **「云端」 is hidden unless
    高级 →「显示云端选项」 is on.** `showHub` gates the tab in the picker, but `brainMode` does not
    consult it, so a device can sit in hub mode with the tab that fixes it invisible — turn the
    toggle on, pick 云端, turn it off again, and the self-check would have pointed at nothing.

  Every label these strings now name was checked against its resource value rather than transcribed
  from the sentence being replaced — which is how the one remaining error was caught: the English
  label is **"Show cloud option"**, singular, and the first draft of this fix wrote it plural.
- **Both READMEs promised a first-run wizard that no longer exists.** "The first run is a stepped
  setup that walks you through both" / 「第一次启动会有一个分步引导」 — `8dac568` deleted the wizard
  (`ui/FirstRunUi.kt`). A missing setup guide is indistinguishable from a broken install, so this
  read as a bug report rather than as stale prose. Both now say what the app does instead: the
  self-check runs on every start, the card names the missing key, and its button opens settings.
- `README.md` named the Volcengine field as **火山 API key** — the Chinese label — while the English
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
  object instead of asked for as a grant.** First run, accessibility on, 悬浮球权限 off: the checklist
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
  `Settings.canDrawOverlays()` — the same predicate `SelfCheck.overlay` uses for the 悬浮窗 row, so the
  page and the door can no longer contradict each other (which is what the user hit: the page said
  没开 while the door said 在屏). It also covers revoking a grant that was already given, when `root`
  stays non-null for the life of the process. `showSelfCheckCard()` guarded with the same marker,
  where its own comment had claimed it already did. Verified on the device (LIO-TL00) on **both**
  paths, because this edits the working path's entry test too — grant denied now reaches the
  checklist (13 activity records), grant allowed still takes the expand branch (0 records); checking
  only the first would have shipped "the icon always shows the checklist".

### Changed

- **The 悬浮窗权限 row now leads the self-check list**, ahead of 无障碍服务. Both are hard gates and
  the order between them was arbitrary; what settles it is that they fail in opposite directions.
  With the service off the app is visibly inert — nothing to run, and every row below is decoration.
  With the grant off it *looks* broken instead: the service runs perfectly well, silently cannot
  draw, and tapping the icon appears to do nothing at all. That second shape is the one users misread
  as "the app is dead", so it goes first.
- 无障碍服务's off-state copy named the row's list position — 「后面几条都不用看」 / *"the rest of this
  list does not matter yet"* — which the reorder above invalidated. It is now 「其他都不用看」 /
  *"nothing else here matters either"*: same meaning, no longer positional. Same edit in both string
  files.

### Removed

- **The first-run wizard is gone** (`ui/FirstRunUi.kt`, its wiring in `ScreenBodyService`, the 重新设置
  button on the self-check card, and its strings in both languages). The wizard and the startup
  self-check both wanted to put a card up on a first start, and when the check found a problem it
  covered the wizard, so a new user saw whichever lost the race. The wizard was removed rather than
  arbitrate between the two. The self-check's own prefs file stays, as does
  `PermissionRequestActivity`, which the check still uses; an old `wizard_done` flag on existing
  installs is simply never read. The wake word is now offered only from settings and as a note row on
  the self-check. The two READMEs had promised this wizard — see the *Fixed* entry above.
- `check_page_no_overlay` and the `SelfCheckUi.note()` helper it was the only caller of. The note
  explained that the settings sheet is drawn as an overlay — a fact the page does not need to teach,
  because the 悬浮窗 row directly below it already carries the fix, in that row's own words and with a
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
- **Both READMEs now ask for your use case (`## Showcase` / `## 好玩的用法`).** The
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
- **A 版本 row in the self-check list — the first thing this app ever sends anywhere.** It asks a
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
  reading 查不到, a message about a policy rather than about anything the user did. So the first cut
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
  `AsrController.kt:152`). Any 火山 failure — a dead network, an expired token, a rejected handshake —
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
  `getString("brain", BRAIN_LOCAL)`, and the `brain` picker lists 本机 leftmost for the same reason.
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
  handling. Same treatment in `## Using Andee` / `## 三种使用方式`; the Chinese `## 从哪里开始看`
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
  帮我看着微信 carried across word for word, which is not a sentence English builds. Separately, the
  panel names were the Chinese labels, so a reader on an English device was being told to look for
  **产物** — a word their screen does not have. They now use the labels `values-en/` actually ships:
  **Scenes**, **Made for you**, **What it remembers**, **Voice API key**.
  The **measured** passage keeps every fact. Xiaomi Pad 5, MIUI, WeChat 8.0.78 and the 219-node count
  are what was observed, and changing the app name there to make the prose consistent would be
  inventing a measurement. Only its framing moved: it opened on WeChat, which a non-Chinese reader
  reads as someone else's app having someone else's problem, so it now opens on the general
  behaviour and names WeChat as the one we happened to measure.
- **The blanket "没有任何遥测 / no telemetry at all" claim is gone, because the version row above
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
  is the canonical copy; the rest point at it. The badge went from `遥测-无` / `telemetry-none` to
  `遥测-不含内容` / `telemetry-no content`, because "no content" is the claim that survives.
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
- The `## 从哪里开始看` section in `README.zh-CN.md`, heading included. Its visible content was three
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
