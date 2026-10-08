# Changelog

Notable changes to Andee. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Versioning policy

**No release has been tagged yet.** In 207 commits the version has never moved off the values the
Android template shipped with. Rather than pretend otherwise, this file records the policy going
forward and states plainly where the project is:

- `versionCode` / `versionName` live in `app/build.gradle` and are currently **`1` / `1.0`**.
- The first tagged release should be **`v1.0.0`** with `versionName "1.0.0"` — note that `"1.0"` is
  not a valid SemVer string, so the two need to be reconciled at that point.
- Thereafter: releases are tagged `vMAJOR.MINOR.PATCH`, `versionName` mirrors the tag without the
  leading `v`, and `versionCode` increases by one per release. `versionCode` must increase
  **monotonically** or Android refuses the upgrade; the same number must never be reused for
  two different builds.
- MAJOR for a change that breaks the wire protocol or an existing configuration; MINOR for a new
  capability; PATCH for fixes.

**A version number does not currently identify a build.** Because `versionCode` has always been `1`,
"which build is this?" cannot be answered from the installed app. Until a release exists, identify
builds by the short commit hash they were built from. (Embedding the hash into `versionName` at
build time would close this hole; it is not implemented yet.)

## [Unreleased]

### Added

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
  actually goes** (no telemetry in the repo, but speech goes to Volcengine and the conversation plus
  every tool result goes to the brain endpoint you configure), **what the software is genuinely
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

### Changed

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
