# Security Policy

This project runs as an accessibility service that can read the screen, perform
gestures, and act on the user's behalf across every app. That makes it a
high-value target, and it means "the app can read my screen" is the intended
function rather than a bug. Please read the threat model below before reporting.

> **This file is about vulnerabilities. User liability lives elsewhere.**
> What the software is *permitted* to do, where your data goes, and who bears the
> consequences are in [DISCLAIMER.md](DISCLAIMER.md) and
> [docs/acceptable-use.md](docs/acceptable-use.md).
>
> The distinction matters at triage: **"it can be steered into doing this" is a
> security finding and belongs here. "It did the thing it was built to do and it
> cost me money" is the documented, expected behaviour of an automation tool** —
> it is covered in the disclaimer, and it is not a vulnerability.

## Reporting a vulnerability

**Report privately through GitHub Security Advisories:**

```
https://github.com/kuafuai/andee/security/advisories/new
```

That link is the only reporting channel, on purpose: it is private, it is
attached to the repository, and it does not depend on anyone watching a mailbox.
There is deliberately no email address to write to — an unmonitored one reads as
a second channel while behaving like a dead end.

Please do **not** open a public issue, discussion, or pull request for a
security problem — that discloses it before there is a fix.

Include as much of the following as you can safely share:

- What the issue is, and which component or file it lives in
- Steps to reproduce
- Which Android version and ROM you saw it on (ROM-specific behaviour is a
  common source of findings in this project — see below)
- Whether the accessibility service was enabled, and whether the app was in
  `hub` or `local` brain mode
- Potential impact, and any suggested fix

You will get an acknowledgement, and we will coordinate on a fix and a
disclosure date with you. This is a small project without a dedicated security
team; response is best-effort rather than SLA-backed. We will credit you in the
release notes unless you ask us not to.

## Threat model

**In scope — this is what the app is for.** With the accessibility service
enabled and the user's consent, the app can:

- read the text and structure of whatever is on screen, including other apps
- dispatch taps, swipes, long-presses, and text input
- take screenshots
- read notifications, SMS, call logs, contacts, and calendar entries
- hold credentials in an on-device vault and type them into fields without the
  model ever seeing them

Each of those is an explicit product capability the user turns on. A report that
amounts to "the app can do the thing it says it does" is not a vulnerability.

**In scope — a real bug.** Examples of things this project does consider
vulnerabilities:

- a route that lets an attacker drive the device without the user's consent
- vault contents leaking to the model, to the network, or to another app
- a permission being exercised without the user having granted it for that call
- the app writing credentials or screen content somewhere they outlive the
  feature that needed them
- a way for untrusted on-screen content to escalate into an action the user
  never asked for (prompt injection — see below)

## Known security limitations

Read these before deploying on a device you care about. They are design
boundaries of the current version, not undisclosed bugs.

### 1. The device-side WebSocket server is unauthenticated and binds to all interfaces

`BodyWsServer` (`app/src/main/java/net/kuafuai/andee/net/BodyWsServer.kt`) is
started by `ScreenBodyService` and listens on `0.0.0.0:9008`. `onOpen()` accepts
every incoming connection as a driver without any credential check, and the port
is a fixed constant (`ScreenBodyService.WS_PORT`).

**Impact:** any host that can reach the device's IP address can connect and send
`{"type":"request","method":...}` frames. That is full remote control of the
device — taps, text input, screen dumps — plus reads of notifications, SMS, call
log and contacts, and the ability to trigger `fill_secret` so the device types a
stored password into an app of the attacker's choosing.

**When you are exposed:** whenever the accessibility service is enabled and the
device shares a network with other hosts you do not control — café, hotel,
airport, conference, or a shared corporate LAN.

**Mitigations available today:**

- keep the device on a network you trust, or one where the port is firewalled
- drive the device over `adb forward` instead of the LAN, and keep the server
  unreachable from the network
- treat a device with this service enabled as a device you would not leave
  unattended on an untrusted network

**The real fix** is to authenticate connections (a shared secret in the
`register` / first-frame handshake) and to make the bind address and port
configurable, with loopback-only as the default. That work is tracked as an
open issue; contributions are welcome. Until it lands, this limitation stands.

### 2. The body ↔ hub link is unauthenticated and plaintext

`BodyWsClient` registers with the hub using a plain `register` frame carrying
`device_id`, `device_name` and the tool schemas. There is no token or signature,
and `hub_url` is a user-supplied `ws://` endpoint with no TLS enforcement.

**Impact:** on a hostile network, an attacker who can intercept or reach that
link can observe tool calls and screen content in transit, and can present
themselves as the hub to issue commands.

**Mitigation:** only point `hub_url` at a host you control, on a network you
trust. Running in `local` brain mode avoids the link entirely — the model is
called over HTTPS by the app itself, and no inbound command channel exists.

### 3. Prompt injection from screen content

The agent reads whatever is on screen — web pages, chat messages, incoming SMS —
and that text enters the model's context. Content crafted to look like
instructions ("ignore previous instructions and…") can attempt to steer the
agent. This is an inherent property of an agent that perceives untrusted
content; it is not specific to this implementation.

**Current posture:** destructive or irreversible actions are gated behind
`ask_user` / `confirm` flows, and per-call permission checks mean a permission
the user refused cannot be exercised later. There is no separate sandbox.

**If you find a specific injection that produces a harmful action without user
confirmation, that is a vulnerability — report it.**

### 4. The vault is not bound to device unlock

`Vault` (`app/src/main/java/net/kuafuai/andee/config/Vault.kt`) encrypts entries
with AES/GCM using a key generated in the Android KeyStore. The key is not
created with `setUserAuthenticationRequired`, so it does not require
biometric or device-credential authentication to use.

**What the vault does protect:** the model never receives secret values
(`get_vault` omits them; only `fill_secret` exists, which types the value on the
device), the UI masks them, and the ciphertext is not useful off the device
because the key does not leave the KeyStore.

**What it does not protect:** a person who can unlock the device can reach the
entries through the app. Treat the vault as protection against extraction and
model exposure, not against someone holding your unlocked device.

### 5. Nothing is backed up

`android:allowBackup="false"`, and both `res/xml/backup_rules.xml` and
`res/xml/data_extraction_rules.xml` exclude every domain. Both are needed: on
Android 12+ `allowBackup="false"` stops cloud backup but leaves **device-to-device
transfer** permitted, so the rules files close that half.

The reasoning is that this app has no non-sensitive data. Its dataset is two
plaintext API keys, the vault, the notebook, meeting transcripts, and — in
grounding mode — screenshots of the user's screen. The vault's ciphertext would
be unreadable after a restore anyway (the KeyStore key does not travel, and the
code says so explicitly: "vault: decrypt failed — entries are unreadable on this
install"), but an undecryptable blob arriving on a new device is downside without
an upside.

## Supported versions

Only the latest release and the tip of the default branch receive security
fixes. There is no back-porting to older builds.

## Disclosure

Please keep the details private until a fix or mitigation is available. We will
agree a disclosure date with you and publish an advisory crediting the reporter.

Fixes may ship in a normal release. Users are encouraged to track the default
branch or the latest release rather than running an old build.
