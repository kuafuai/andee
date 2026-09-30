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
  white-labelling that removes the branding). Note that GitHub reports this as `NOASSERTION`.
- `SECURITY.md` — threat model, the five known security limitations, and a private reporting channel.
- `docs/permissions.md` + `docs/permissions.zh-CN.md` — why the app asks for the permissions it asks
  for, each one tied to a concrete tool and to what happens if you refuse it. One file per language.
- `CONTRIBUTING.md` + `CONTRIBUTING.zh-CN.md` — code style, the rules for the three kinds of Chinese
  in this codebase, and the contribution licensing terms. One file per language.
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

- **Positioning corrected throughout the docs.** Both READMEs and `CLAUDE.md` described this as
  "the body side of a Physical Agent system" — i.e. an assistant that a brain drives. That
  contradicts the project's own first instruction, in `LocalPrompt.TEXT`: *"you are not a tablet's
  assistant, you are an individual, and the tablet is only what carries you."* The docs now lead
  with the individual, and give the body / persona / notebook mechanisms that back it up in code
  (fifteen moods and idle fidgets, `BallLook.persona` reaching the head of the system prompt, the
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

### Removed

- `brain/andee-system-prompt.md` — an unused mirror of the cloud brain's prompt. It had no runtime
  role (no build step references it, no code loads it). **The cloud brain mode itself is not
  retired**; only this repo's copy of its prompt was removed. Recover it with
  `git show <rev>:brain/andee-system-prompt.md`.
- `dog/NRF24_Dongle/README.md` reference in the README — the dongle firmware is not in this
  repository and never was. The README now says so.

### Known issues

- `tools/verify_faces_lab.py` reports 7/16 assertions passing against the committed
  `reports/ball-faces-lab.html`. This is pre-existing (identical before and after the portability
  changes above) and is not caused by the open-sourcing work. Left as-is; worth resolving before
  contributors are pointed at the ball-face lab.
- The debug WebSocket server (`net/BodyWsServer`) binds `0.0.0.0:9008` with no authentication. See
  `SECURITY.md`. Not fixed.
