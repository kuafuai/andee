# Getting started

[简体中文](getting-started.zh-CN.md)

This is the shortest path from a first look at Andee to seeing the ball and completing a basic interaction. Andee is experimental software that can read and operate your device; read the [disclaimer](../DISCLAIMER.md) before installing it.

## 1. Choose how to install

Pick the route that matches what you want to do:

- **Try it without building:** use the [CodeFlying build page](https://www.codeflying.app/andee) for a pre-configured APK. This is a separate service, and its own terms and token limits apply.
- **Try a repository build:** download the bare debug APK from the [latest release](https://github.com/kuafuai/andee/releases/latest). It has no credentials, so you must configure the on-device model and voice keys later.
- **Build it yourself:** follow the Android requirements and commands in [Build from source](../README.md#build-from-source), then use the detailed [Provisioning](../README.md#provisioning) reference if the toolchain is not ready.

For a first run, use a real Android 11 (API 30) or newer device. An emulator can install the app, but most device-control features are not meaningful there.

## 2. Install and make the ball appear

After the APK is on the device, do these four things in order:

1. **Install the APK.** If installation fails, check that the APK matches the device ABI and that an older copy was not signed with a different key. A debug build from another machine may need the old copy uninstalled.
2. **Grant “Display over other apps” (Overlay).** The ball should appear when you return to Andee. If it does not, reopen the app and use Self-check; do not continue by guessing at later permissions.
3. **Enable Andee under Accessibility.** This lets Andee see and touch the screen. If the service is off, the ball may be visible but it cannot operate another app. Android or a device manufacturer may turn this service off again after a force-stop or system update.
4. **Tap the ball and allow the microphone.** If you refused it earlier, enable Microphone under Android Settings → Apps → Andee → Permissions. Without it, typing remains possible but voice input and wake-word use do not.

The same order is summarized in the README’s [Provisioning order](../README.md#provisioning-order). The [Permissions reference](permissions.md) explains what each permission enables and what Andee does when you refuse it.

## 3. Configure a brain and try one interaction

Open ⚙ on the ball and choose **Backend → On-device**. Enter an OpenAI-compatible endpoint and `llm_api_key`; the local brain cannot run without the brain key. If you installed a CodeFlying package, its **CodeFlying** backend is already configured instead. A voice key is optional for a first typed interaction, but voice input and spoken replies need the selected voice provider configured too.

Now tap the ball, type a simple request such as “What can you see on this screen?”, and send it. The ball should expand into the conversation card, answer, and return to the floating ball when it is done. This confirms the basic loop: the app is running, the brain is reachable, and the overlay is visible. Do not use a payment, messaging, or other consequential task as the first test.

## 4. If something fails, use Self-check

Self-check is the in-app checklist, not a second setup wizard. Open **✓** in the ball’s control bar, or launch Andee again when the accessibility service is off. Each failed row names the missing readiness item and, where possible, opens the relevant settings page. Fix one row, return to Self-check, and check again.

The README describes where the [Self-check surface appears](../README.md#what-it-looks-like), and the full [Permissions reference](permissions.md) explains the rationale behind its permission rows. For build and device setup failures, return to [Provisioning](../README.md#provisioning); for a successful install that still cannot reach the configured brain, check the endpoint and key under **Backend → On-device** before changing permissions.

When the ball is visible and the typed request has received an answer, the first-run path is complete. The README remains the reference for advanced provisioning, cloud/hub mode, wake word setup, and known limitations.
