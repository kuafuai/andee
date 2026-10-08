<p align="center">
  <a href="./permissions.md"><img alt="English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./permissions.zh-CN.md"><img alt="简体中文" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
</p>

# Permissions

This page explains why Andee asks for the permissions it asks for, what each one buys you, and what happens if you refuse it.

The reason it is this detailed is straightforward: **the combination of permissions Andee requests overlaps heavily with the detection signature of an Android banking trojan** — accessibility + overlay + notification listener + SMS + call log + contacts + installed apps. Any security researcher who glances at the manifest will flag it. That is reasonable alarm, not a misjudgement.

So this page has exactly one job: **to separate "looks the same" from "is the same", and to make every claim checkable in the code.**

---

## First, where the difference lies

| | Typical Chinese app / malware | Andee |
|---|---|---|
| When permissions are requested | All at once, on first launch | **On demand** — only when you use the feature that needs one |
| What happens when you refuse | The app is unusable / nagged repeatedly | **Feature degrades**, everything else keeps working, and it tells you why it could not do the thing |
| Permission ↔ feature mapping | Not explained | See the table below; every entry maps to a concrete tool |
| Where sensitive data goes | Not stated | Credentials are encrypted on-device (`config/Vault.kt`) and unreadable by the model; the 5 private-notebook tools **are never sent off-device at all** (filtered by `ToolSchemas.forHub()`) |
| How credentials are used | — | The model can only ask the device to *type* them; **it never receives the password itself** (`fill_secret`) |

Two places in the code where you can verify this:

- `device/PermissionsController.kt` — the permission list, the plain-language explanation for each, and the guided jump to Settings
- `device/DeviceCommsController.kt` — every tool calls `ensurePermission()` **before** it acts; without a grant it returns a plain `denied("sms")`-style result. It does not crash, and it does not quietly find another way to do it.

---

## The full permission table

The "What you get" column is the copy the app actually shows (`dev_perm_feature_*` in `res/values-en/strings.xml`).

| Permission | What you get | Tools | What happens if you refuse |
|---|---|---|---|
| Overlay<br>`SYSTEM_ALERT_WINDOW` | Overlay (my face) | The ball, the subtitles, the settings panel itself | **Nothing is visible when the app starts.** The only permission that is "off means completely unusable" |
| Accessibility service<br>`BIND_ACCESSIBILITY_SERVICE` | See the screen, tap the UI, type | `get_screen_element` / `tap_screen_element` / `swipe_by_coordinates` / `type_text` / `submit_input` / `take_screenshot` — all of `screen.*` | **Every screen action errors.** This is the core capability; the other permissions exist around it |
| Microphone<br>`RECORD_AUDIO` | Voice, and the "Hey Andee" wake word | Streaming ASR + on-device wake-word matching | Tapping the ball does nothing; wake-word listening does not start (and, as a side effect, the Android 12+ green dot never appears) |
| Notification access<br>`BIND_NOTIFICATION_LISTENER_SERVICE` | Notification awareness (know when you get messages) | `get_notifications` | It will not know about incoming messages on its own; nothing else is affected |
| Location<br>`ACCESS_FINE_LOCATION` | Location (where you are / navigation) | `get_location` | "Where am I" cannot be answered |
| Step count<br>`ACTIVITY_RECOGNITION` | Step count | `get_step_count` | Step count cannot be answered |
| Contacts<br>`READ_CONTACTS` | Contacts (find someone to call) | `search_contacts` | "Call Old Wang" cannot find anyone |
| Phone<br>`CALL_PHONE` | Direct dialing | `dial` | It can find the person but cannot place the call |
| Call log<br>`READ_CALL_LOG` | Call history | `get_call_log` | Cannot look up who called recently |
| Read SMS<br>`READ_SMS` | Read SMS (verification codes) | `read_sms` | Cannot read verification codes |
| Send SMS<br>`SEND_SMS` | Send SMS | `send_sms` | Cannot send |
| Read calendar<br>`READ_CALENDAR` | Calendar (reminders) | `get_calendar` | Cannot see today's schedule |
| Write calendar<br>`WRITE_CALENDAR` | Write calendar (add events) | `add_calendar_event` | Cannot record events |
| Camera<br>`CAMERA` | Sight (identify objects / scan codes) | `camera_turn` / `scan_code` | Those two stop working |
| Installed apps<br>`QUERY_ALL_PACKAGES` | Know which apps are installed | `list_installed_apps` / `launch_app` | Cannot open a named app |
| Notifications<br>`POST_NOTIFICATIONS` | Remind you on time | Due-task reminders | No reminder at the due time, **but the task is not lost** |
| Boot<br>`RECEIVE_BOOT_COMPLETED` | Tasks survive a reboot | Rebuilding the `AlarmManager` alarms | After a reboot, due tasks stop reminding |
| Battery optimisation exemption<br>`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Reminders arrive on time | Task scheduling | With the screen off, reminders arrive late |
| Write secure settings<br>`WRITE_SECURE_SETTINGS` | Switch the input method, for the spare typing route | Prerequisite for the ADBKeyboard fallback | Nothing on Android 13 and above — typing goes through the accessibility input method. On Android 12 and below, **`type_text` fails outright**, and tells you exactly why. It will not pretend to succeed |

> On **Android 13 and above you do not need this.** The app types through its own accessibility service's input connection: nothing to install, no keyboard to switch to, and your own keyboard stays active throughout.
>
> `WRITE_SECURE_SETTINGS` is the odd one out: it **does not take effect on install — you must grant it manually over adb**:
>
> ```bash
> adb shell pm grant net.kuafuai.andee android.permission.WRITE_SECURE_SETTINGS
> ```
>
> That is a platform restriction, not a choice this app made. On Android 12 and below it is what typing needs; above that it only matters for the fallback route, which is used when an editor refuses the normal channel.

---

## Three switches you must turn on by hand after installing

These are outside the app. Android does not let an app flip them itself. Miss one and the matching feature is unavailable:

1. **Overlay** — Settings → Apps → Andee → Permissions → Display over other apps
2. **Accessibility service** — Settings → Accessibility → Installed services → Andee (you will get a big red warning box; that is the standard system flow)
3. **Microphone** — the system prompts for it the first time you tap the ball

> Some vendor ROMs (Xiaomi / Huawei / HONOR) **turn accessibility back off** after every system update or force-stop. You have to re-enable it. That is ROM behaviour.

---

## How credentials are kept (the question that most deserves asking)

Andee has a vault (`config/Vault.kt`) for the account passwords you enter yourself. Three hard rules in the design:

1. **Encrypted at rest.** AES/GCM, with the key generated and held by the Android KeyStore, **never leaving the device**. Even with the ciphertext in hand, another machine cannot decrypt it (there is a matching fallback in the code: "vault: decrypt failed — entries are unreadable on this install").
2. **The model never receives the password itself.** In what `get_vault` returns, any field marked secret is omitted; the model can only call `fill_secret`, and **the device itself** types the password into the field. So the password does not pass through the model and does not enter its context.
3. **Masked in the UI.** The vault screen shows the output of `Vault.mask()`.

**What it does not protect against (worth stating plainly):** the key is not bound to device unlock (it does not use `setUserAuthenticationRequired`). So **anyone who can unlock your phone can see the vault contents through the UI.** It protects against "data extracted off the device" and "the password read by the model" — not against "someone who picked up your phone".

---

## Where your data goes ("do you collect my data?")

**The conclusion: this project receives nothing about you; but "your data never leaves the device" is false.** Both halves need saying — only stating the first is misleading.

**There is no telemetry in this repository.** No analytics SDK, no crash reporting, no event tracking, no device fingerprinting — `firebase` / `analytics` / `sentry` / `crashlytics` are all zero across `app/build.gradle` and `app/src/main/`. If you install it and use it, the authors do not know.

**But data does leave the device, by design:**

| What | Where it goes | Default |
|---|---|---|
| Speech recognition (what you say), speech synthesis (what he says) | **Volcengine (ByteDance) cloud** | `openspeech.bytedance.com` |
| Conversation content, **and every tool result** | The "brain" **you** configured | `brain=local` (the out-of-box default) → `api.deepseek.com`; in hub mode, your own `hub_url` |
| Private notebook (`config/Notebook.kt`: what he remembers about you, what he promised) | **On-device only** | It is `localOnly` and never enters the hub payload |

The second row deserves spelling out: **a tool result is part of the conversation.** The SMS bodies, contacts, call log, notification content and location he reads, along with his "reading" of the screen, all travel with the conversation to the endpoint you configured.

**So "local mode" does not mean "local processing"** — it changes where the brain is, not the fact that data leaves the device. If you genuinely want data to stay in, you have to point `asr_endpoint` / `tts_endpoint` / `llm_base_url` / `hub_url` at a machine you control yourself.

The full allocation of responsibility and the disclaimer are in **[../DISCLAIMER.md](../DISCLAIMER.md)**; prohibited uses are in **[acceptable-use.md](acceptable-use.md)**.

---

## Known risk: the debug WebSocket port is open to the local network

**Please read this one to the end before using it.**

On startup the app opens a WebSocket service listening on `0.0.0.0:9008` (`net/BodyWsServer.kt`; the port is the `ScreenBodyService.WS_PORT` constant).

**The problem is that it authenticates nothing.** `onOpen()` accepts every incoming connection as a legitimate controller. Any host that can reach this device's IP can connect and issue commands — tap the screen, type, read the UI, read notifications, read SMS and the call log, and have the vault passwords typed into any app's input field.

**When you get hit:** whenever the accessibility service is on and the device is on a network you do not control — a café, hotel, airport, meeting room, a corporate LAN — the risk is live.

**What you can do about it today:**
- Only use it on networks you trust, or just block the port at the router / firewall
- Drive the device over `adb forward` instead of leaving it reachable on the network
- Treat a device with accessibility enabled as a device you would not leave lying around on a strange network

**The proper fix** (not done yet): verify a shared secret on connect, and make the bind address and port configurable, defaulting to loopback only. That is recorded as a to-do; until it lands, the limitation above stands.

> A related but lighter second item: the link from the app to the brain hub (`net/BodyWsClient.kt`) is also unauthenticated, and is plaintext `ws://`. So only ever put an address you control in `hub_url`. **Using `local` brain mode avoids that link entirely** — the model request goes out over HTTPS from the app itself, and no inbound command channel exists on the device.

---

## Why it does not ask for everything at once

Because that is what a trojan does.

The approach here is: **you ask for a feature, it asks for the permission; if you decline, it degrades — and it tells you it degraded.** You can verify that line by line in the code: every tool runs `ensurePermission()` before acting, and a refusal returns an explicit failure reason rather than finding a quieter route to the same result.

If you want to look deeper, [../SECURITY.md](../SECURITY.md) has the full security model and the vulnerability reporting channel.

---

**Other languages:** [简体中文](permissions.zh-CN.md)
