<p align="center">
  <a href="./CONTRIBUTING.md"><img alt="English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./CONTRIBUTING.zh-CN.md"><img alt="简体中文" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
</p>

# Contributing

Thanks for wanting to get involved. Straight talk first: this is a **small team with a commercial edition**, so we are fairly blunt about what gets merged and what gets closed — better that than you writing a lot of code for nothing.

This guide will itself lag behind the code. If you find something that no longer matches reality, just send a PR to fix it.

---

## First, work out what you want to do

| What you want to do | Where to go |
|---|---|
| You don't know where to start | Look for issues labelled `good first issue` |
| Report a bug | Open an issue with the bug template — **device information is mandatory**, see below |
| Propose a feature | Open an issue with the feature template first. **Do not write a PR directly** |
| Report a security vulnerability | **Do not open a public issue.** Use the private channel in [SECURITY.md](SECURITY.md) |
| Add or change UI copy and translations | Edit both `res/values/` and `res/values-en/` — see "Three kinds of Chinese" below |

> **One exception**: typos, comment fixes, obvious slips — send a PR directly, no issue needed.

---

## Bug reports: device information matters more than anything else

The failure mode this project hits most often is **"the system says it's there, but it isn't"** — vendor ROMs behave very differently, and the same call works on AOSP and silently fails on MagicOS. A bug report without device information is one we basically cannot reproduce, so we will have to close it.

Please always include:

- **Device model + Android version + ROM** (e.g. `HONOR AMM-AN00 / Android 15 / MagicOS`)
- Screen resolution and density (`adb shell wm size` / `wm density`)
- **Brain mode**: `hub` or `local`
- Whether the accessibility service is enabled
- **logcat output.** At minimum:

  ```bash
  adb logcat -s Body:* Screen:* Wake:* Dog:* GroundingLog:*
  ```

- Reproduction steps, as specific as you can make them

The three kinds of bug we care about, highest priority first: **core function unusable** (accessibility down, taps do nothing, app crashes) → **works but gives the wrong result** → **copy or visual polish**.

---

## Sending a PR

The flow:

1. Fork, branch off `main`
2. **Confirm in an issue first that you want to do this** (typos excepted)
3. Change the code, and add tests where the change has observable behaviour or regression risk
4. Build locally: `./gradlew :app:assembleDebug`
5. Reference the issue in the PR description with `Fixes #<issue number>`
6. Wait for review

Things that will be closed, up front:

- Functional changes with no linked issue (it may be a direction we do not intend to take)
- Too many unrelated things in one go — please split them
- Large diffs that are purely formatting or renaming — those cannot be reviewed

---

## Read this before you touch the persona

**This project is not "an app driven by a brain."** In its own words, from the first line of `LocalPrompt.TEXT`:
*"You are not a tablet's assistant — you are an individual, and the tablet is just what carries you."* So the persona, the looks, and the mood are not interface decoration. They are part of **who he is** — changing them is not the same kind of act as rewording a button.

Three hard rules (all of them are in the code; do not route around them):

1. **A personality is a way of speaking, not extra permissions.** `LocalPrompt.withPersona()` automatically wraps your text in the §11 subordination clause — **do not restate it inside a look, and do not write a personality that "needs it loosened."** The reason is in the KDoc: give the model an unguarded `"you are an imp"` and it will help itself to the neighbouring licence — under-reporting, embellishing, being deliberately unhelpful, and calling that a personality. **When things go wrong the costume has to come off.**
2. **A new look is only a tone of voice.** `BallLook.persona` goes at the **very front** of the system prompt, and the price is a lost prefix cache on every swipe — a price worth paying, because a tone attached to a user message is gone two turns later. So: **adding a look means adding a tone — not capabilities, not rules.** Capabilities live in §1–§10.
3. **When you change the mood table, ask whether it still reads as alive.** The `SLEEPING` comment in `Mood.kt` is the standard to hold to: the sleeping ball used to be desaturated overall, and it looked **switched off** rather than **tired**, so it was changed back to "sleeping is a shape" (`^ ^` eyes plus a snot bubble), letting colour off the hook. **Express state with shape, not with colour or brightness.**

One more thing that is not a rule but that you should know: when a look ships a `voice`, the timbre **is decided by the character**, and there is no separate switch for the user to tune it. That is deliberate — timbre is part of who he is, not a configurable parameter.

---

## Three kinds of Chinese, only one of which should be touched

Chinese in this repository falls into three categories. Work out which one you are looking at before you change any copy:

1. **What the user reads** — every window, card, bubble, settings row, error line. Goes through **two** tables, `res/values/` (Chinese) and `res/values-en/` (English), and lookups **must** go through `i18n/AppLocale` (the ball and the cards are Service-held overlays, which the official per-app locale API cannot reach, so `context.getString` is not an option).
2. **What the model reads** — prompts, tool schemas, tool results. **Currently English**, in one place only: `brain/LocalPrompt.kt`.
3. **What is matched against system text** — call keywords, the unread-count regex, clipboard labels. **Never translate these** — they are compared against the characters WeChat and Android themselves produce; translating them breaks the feature.

**A default value cannot be a Kotlin literal.** The test is simple: will this value end up **on screen**? If yes, it must go through the string tables plus `AppLocale`.

Before changing text the model reads, note that the `LocalPrompt` §9 rule (answer in the language of the user's last message) is the only thing holding back an English prompt from dragging the reply language over. Do not delete it as a side effect.

---

## AI-assisted contributions

**Using AI to write code is welcome.** Three ground rules:

1. **You must understand and verify every change you submit.** "The tool generated it" is not an excuse.
2. **Write the problem, the approach, and real test results in your own words.** Do not paste model output as your PR description.
3. **Unreviewed, mass-produced low-quality submissions will be closed.**

If the PR was produced by an automated agent, add the source as the **last line** of the PR description, for example:

```
From Codex
```

That way, when something goes wrong, we know which tool to look at — and we can measure how much of the contribution is AI.

---

## Code style

- Kotlin, no Compose (Compose cannot be attached to an overlay window; all UI is hand-written `View`)
- No Hilt/Dagger — there is only one service, and injection is written by hand
- Keep third-party dependencies out where you can; if you add one, justify it in the PR
- **Comments explain "why", not "what".** This repository is unusually comment-dense, but every comment records "what we measured at the time and why we did not write it the other way" — that is the most valuable part of it. Please keep it that way.

---

## Contribution licensing (please read)

This project uses **Apache 2.0 plus additional conditions** (see [LICENSE](LICENSE)) — it is not standard Apache 2.0. By submitting a contribution you agree to **clause A.2** of the LICENSE:

- Your contribution is licensed to this project and all its users under the same licence; and
- You additionally grant the copyright holder permission to use your contribution **under other licence terms** (this is what makes the dual-licensing model possible).

You keep the copyright to your own contribution. If you are contributing on behalf of an employer, please make sure you are entitled to.

**If you do not accept A.2, please do not send a PR** — we need this clause to sustain the dual-licensing model, and we would rather say so up front.

---

## Language

- **Code comments and commit messages: English preferred** for new files. When editing an existing file, follow the language that file already uses — do not translate the whole thing as a drive-by.
- **Issues and PR descriptions may be in Chinese or English.** We do not impose an English-only rule — that would turn away half the people who could help. If you write in Chinese, one line of English summary is appreciated so non-Chinese contributors can follow along.

---

## Stuck?

Open an issue and ask, or ask in the PR. Do not sit on it — we would much rather answer a "dumb question" than have you write a mountain of code and only then find out the direction was wrong.

---

**Other languages:** [简体中文](CONTRIBUTING.zh-CN.md)
