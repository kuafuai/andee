<p align="center">
  <a href="./DISCLAIMER.md"><img alt="English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./DISCLAIMER.zh-CN.md"><img alt="简体中文" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
</p>

# Disclaimer

> **This file covers what [LICENSE](LICENSE) does not.**
> Apache-2.0 already says the software comes with no warranty (§7, §8) — that is not repeated here.
> What is here are the four things it cannot reach and that actually cause trouble:
> **where your data goes, what this can really do, who bears the consequences, and what is not allowed.**
>
> Prohibited uses are in **[docs/acceptable-use.md](docs/acceptable-use.md)**. Read that too.
> Last updated: 2026-09-29

---

## 1. What this software is

An **experimental** piece of software that you install on **your own device**. It is not the open-source edition of a finished commercial product — it is the experiment itself.

- **Provided "AS IS"**, with no warranty of any kind — not of quality, stability, security, or fitness for any purpose.
- **Commercial use is permitted** (see [LICENSE](LICENSE), Part A.1). What is licensed is the **right**, not the **quality**. Commercial use carries no warranty either.
- **There is no SLA.** No availability commitment, no support commitment, no fix deadline, no maintenance commitment.
- **Do not treat it as a dependable assistant.** It gets things wrong. And when it gets things wrong, it is holding your accounts, your messages, and your money.

## 2. Where your data goes (read this before deciding to use it)

**The short version: the maintainers receive nothing about you. "Your data never leaves the device" is nevertheless false.**

**There is no telemetry in this repository.** No analytics SDK, no crash reporting, no tracking, no device fingerprinting. A search of `app/build.gradle` and `app/src/main/` for `firebase` / `analytics` / `sentry` / `crashlytics` returns zero. If you install this and use it, we do not know.

**But data does leave the device, to wherever it is configured to go:**

| What | Goes to | Default |
|---|---|---|
| Your speech (ASR) and its speech (TTS) | **Volcengine / ByteDance cloud** | `openspeech.bytedance.com` |
| The conversation, **and every tool result** | The "brain" you configured | `brain=hub`; in local mode, `api.deepseek.com` |
| The private notebook (`Notebook` — what it remembers about you, what it promised) | **On this device only** | Filtered out of the hub payload |

The second row needs spelling out: **tool results are part of the conversation.** The SMS text it read, your contacts, your call log, notification contents, your location, and its reading of the screen all travel to the endpoint you configured.

**So "local mode" does not mean "processed locally."** Local mode swaps the hub for whichever provider you typed in. It does not keep a single byte on the device.

Therefore:
- *"We do not monitor or store your data"* — **true.** We have neither the capability nor the endpoint.
- *"Your data never leaves the device"* — **false. Do not claim it.**

## 3. What it can actually do (not hypotheticals — current state of the code)

**It can operate any app.** Its hands are "read the screen + tap by coordinates + type text + system actions," and the OS security policy does not stop a finger. §1 of the system prompt explicitly forbids it from treating "no dedicated tool" as "cannot be done," and lists what it reaches, including **ordering, booking and unsubscribing inside an app, changing a password, and filling in a form.**

**It can spend money.** There is no payment tool — it does not need one; it can tap. The only thing standing between it and a completed payment is Android's own **`FLAG_SECURE`** (the screen blanks out on sensitive pages, so it cannot see). **That is the platform's protection, not this project's**, and not every app's payment flow sets it. This project has **no guardrail whatsoever** on completing a transfer by tapping: of sixty-odd tools, exactly one — `send_sms` — is marked sensitive.

**It can speak as you.** `send_sms`, `dial`, `open_url` (any link), and typing into any social or community app. Everything it sends is sent under your name.

**It can see and hear.** `read_sms`, `get_call_log`, `search_contacts`, `get_notifications`, `get_location`, `take_screenshot`, `camera_turn`, `start_meeting` (records and transcribes).

**It can hold and auto-fill your passwords.** The vault (`list_vault` / `get_vault` / `fill_secret`) stores credentials and types them into login fields. It never sees the plaintext — but **it can put that plaintext into any input box.**

**It really moves.** With a robot dog attached, that is not an animation — **that is a physical object moving on a real floor, and hitting something is a real collision.** It keeps going until you stop it.

**It is influenced by text on the screen.** It reads the screen to work, so text on the screen is an instruction to it. **Anyone who can get you to open a page, or send you a message, may be able to get it to do something you did not intend** (prompt injection). See [SECURITY.md](SECURITY.md), known limitation 3.

## 4. You therefore bear the following

| Area | What is yours |
|---|---|
| **Money** | Every order, transfer, subscription and fee it produces. **That is your act, not ours.** |
| **Content** | Every message, post, comment, email and call it makes for you. It carries your name, and your liability. |
| **Privacy** | Yours, and that of the people around you. It may read it, it may appear on screen, it may be uploaded to the places in §2. |
| **Accounts** | Automating a third-party app may violate that platform's own terms. Consequences include bans, restrictions and frozen funds. |
| **People** | With attached hardware, hitting a person or property is your responsibility. |
| **Compliance** | Ensuring your use is lawful **in your jurisdiction** is yours alone. |

## 5. No warranty, and limitation of liability

This software is provided **"AS IS"**, without warranty of any kind, express or implied, including merchantability, fitness for a particular purpose, non-infringement, security and stability. The full text is in [LICENSE](LICENSE).

**To the maximum extent permitted by applicable law**, the authors and contributors are not liable for any direct, indirect, incidental, special, punitive or consequential damages, including but not limited to **loss of funds, loss of data, account bans, business interruption, reputational harm, or injury to persons or property** — even if advised that such damage was possible.

## 6. Third parties

This software operates third-party services: WeChat, Alipay, banks, arbitrary apps, and whichever model and speech providers you configure. **Your relationship with those services is governed by their terms, not by ours.**

This project is **not affiliated with, authorised by, sponsored by or endorsed by** any of those companies. Before automating a service, satisfy yourself that the service permits it.

## 7. If you do not agree

Do not install it, do not run it, delete it. Using it means you have read, understood and accepted this document and [docs/acceptable-use.md](docs/acceptable-use.md).

---

**Other languages:** [简体中文](DISCLAIMER.zh-CN.md)
