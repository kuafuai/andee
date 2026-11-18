# Build and install from source

> Moved here from the README. The content is unchanged; only the headings are one level deeper.

### Build from source

If you only want to use it, see [Install](../README.md#install) above. This is the route for building it yourself; the full
Android steps are further down, under [Provisioning](#provisioning).

**On a Mac**, build the desktop app from the same checkout (Apple silicon, macOS 14+, the Command Line Tools
15 or newer for `swiftc` and the macOS 14 SDK — an older SDK stops the build — and the same JDK 17–20):

```bash
./gradlew :desktop:createDistributable   # → desktop/build/compose/binaries/main/app/Andee.app
cp -R desktop/build/compose/binaries/main/app/Andee.app /Applications/
# then: grant Accessibility and Screen Recording when it asks (System Settings → Privacy & Security),
# and put your model key in Settings → Backend, as on the phone.
```

macOS remembers those two grants against the app's signature, so an unsigned rebuild loses them every
time — [desktop/README-signing.md](../desktop/README-signing.md) sets up one fixed local identity.

#### Packaging it for someone else — both chips

`createDistributable` packs for **the machine that runs it**: the launcher, the embedded runtime and
the native pieces (`libandeemac.dylib`, `andee-trash`, the LWJGL natives) all follow the `os.arch` of
the Gradle JVM. There is no cross-compile switch — `os.arch` is read straight off the JVM — so an
Apple silicon build will not start on an Intel Mac, and the other way round. Ship two packages, as
every other Mac app does:

| Chip | Package to hand over | Built with |
| --- | --- | --- |
| Apple silicon (M1–M4) | `Andee-1.0.0-arm64.dmg` | any JDK 17–20 |
| Intel | `Andee-1.0.0-x64.dmg` | an x86_64 JDK 17, under Rosetta |

An Apple silicon Mac can build **both**: the architecture is decided by whichever JVM runs Gradle, and
Rosetta runs an x86_64 one. Take Temurin's macOS x64 tarball rather than Homebrew — the `openjdk@17`
formula compiles from source and demands a full Xcode, which this project otherwise does not need:

`packageDmg` uses dmgbuild 1.6.7 to add the Applications drag target and Finder layout.
Set up its pinned Python dependency once before either build. The Gradle task checks for
this dependency before jpackage writes a dmg:

```bash
python3 -m venv desktop/build/dmg-venv
desktop/build/dmg-venv/bin/python -m pip install -r desktop/dmg/requirements.txt
export ANDEE_DMG_PYTHON="$PWD/desktop/build/dmg-venv/bin/python"
```

The committed `desktop/dmg/background.png` is used as-is during packaging. To
regenerate it after editing the artwork or caption, install the optional Pillow
dependency with
`desktop/build/dmg-venv/bin/python -m pip install -r desktop/dmg/requirements-background.txt`
and run
`ANDEE_REGENERATE_BACKGROUND=1 bash desktop/dmg/make-dmg.sh ...`.
`packageReleaseDmg` and `notarizeDmg` are not part of this drag-to-install
packaging path; verify their image layout separately before distributing one.

```bash
curl -L -o /tmp/jdk17-x64.tar.gz \
  'https://api.adoptium.net/v3/binary/latest/17/ga/mac/x64/jdk/hotspot/normal/eclipse'
mkdir -p ~/jdk-x64 && tar -xzf /tmp/jdk17-x64.tar.gz -C ~/jdk-x64

./gradlew --stop                                    # the daemon already running is arm64
JAVA_HOME=~/jdk-x64/jdk-17.0.20.1+1/Contents/Home \
  ./gradlew :desktop:packageDmg --no-daemon         # → Andee-1.0.0.dmg (x86_64)
./gradlew --stop && ./gradlew :desktop:packageDmg   # → Andee-1.0.0.dmg (arm64)
```

Both architectures write the **same** file, `desktop/build/compose/binaries/main/dmg/Andee-1.0.0.dmg`, so the
second build overwrites the first — rename it to the name in the table above before building the other one.

**Two failures here are silent.**

`createRuntimeImage` is declared up to date whenever its *inputs* have not changed, and the JVM it
runs on is not one of those inputs. Switch architecture and Gradle reuses the previous JRE, producing
an x86_64 launcher wrapped around an arm64 `libjvm.dylib`: BUILD SUCCESSFUL, and the app dies on
launch. Move the runtime away before each switch:

```bash
mv desktop/build/compose/tmp/main/runtime desktop/build/compose/tmp/main/runtime-old
```

And both packages are written to the same file name, so the second build replaces the first without a
word. Rename as you go. (The `1.0.0` in that name is the macOS bundle version — `packageVersion` in
`desktop/build.gradle`, because macOS rejects a `0.x` bundle. It is not the product version.)

Check all four binaries before shipping; they have to agree:

```bash
cd desktop/build/compose/binaries/main/app/Andee.app
lipo -archs Contents/MacOS/Andee
lipo -archs Contents/runtime/Contents/Home/lib/server/libjvm.dylib
lipo -archs Contents/runtime/Contents/Home/lib/libjli.dylib
lipo -archs Contents/app/libskiko-macos-*.dylib
```

The skiko file is named for the architecture it was built for (`libskiko-macos-arm64.dylib` versus
`libskiko-macos-x64.dylib`), so its name alone says which package you are holding.

One package for both is not an option: the jpackage launcher names a single runtime directory, so a
single launcher cannot choose between two JREs, and a universal JRE would mean lipo'ing every dylib
by hand. Two packages is the answer this build supports.

To build the Android app yourself:

> **Requirements**
>
> - Android 8.1 (API 27) or newer — it installs and runs. **Screen capability needs Android 11
>   (API 30)**: `ACTION_IME_ENTER` and `takeScreenshot` are both API 30+, and below that it can
>   read the accessibility tree but cannot see the screen
> - **JDK 17–20.** AGP 8.3.1 needs 17+; the Gradle 8.4 wrapper cannot run on 21+ (Android Studio's  
>   bundled JBR 25 is rejected with `Unsupported class file major version 69`)
> - Android SDK with platform 34 and build-tools 34.0.0
> - A real device. The body is a foreground service and only the screen half is an accessibility  
>   service; an emulator can run it, but almost nothing it does is meaningful there

```bash
./gradlew clean :app:assembleDebug     # → app/build/outputs/apk/debug/app-<abi>-debug.apk
./gradlew installDebug                 # build + install to an attached device

# then, on the device, in this order:
#   1. grant Overlay       → the ball appears
#   2. enable Accessibility → it can now see and touch the screen
#   3. tap the ball        → allow the microphone prompt
#   4. open ⚙ and put in two keys of your own (see below)
```

**No credentials ship in this repository, and the two that are missing fail in ways that do not look  
like missing credentials.** Both go in ⚙ on the device — **one is required, the other only costs you  
the voice:**

**1. The brain key — required.** With `brain = local` (the out-of-box default) the agent loop runs on  
the device itself, against any OpenAI-compatible endpoint (`llm_api_key`, default  
`https://api.openai.com/v1` — a Chinese-language card defaults to `https://api.deepseek.com`  
instead). **Leave it empty and the local brain does not run at all** — nothing  
behind it falls back to something else. The ball does at least say so out loud, rather than silently  
doing nothing.


**2. The voice key — optional.** You pick the provider first, at the top of the same **Backend →
On-device** tab (**Voice provider** → Volcengine or Google), and then fill in that provider's key.
This is a separate service from Volcengine's, with its own terms and data handling.
For **Volcengine**, a [Volcengine Speech](https://www.volcengine.com/product/voice-tech) API key goes
in the **Volcengine API key** field; for **Google**, a Vertex Express key plus a GCP project *number*
and a location. Without a key for whichever one is selected, ASR and TTS are **refused at the
handshake** — the server answers at once, and answers no (a blank or a revoked key is a 403), so the
socket dies before a byte of audio is sent. That is a different failure from a **connect timeout**,
which is what a host that never answers looks like; reading the first as the second sends you off to
check the network when the field is simply empty.
Everything else still works; it is just mute and deaf.

**With Volcengine, a correct key is still not enough.** In the console, "the service is activated"
and "calls against this resource are entitled" are two different statements, and the API only checks
the second. A service that was just switched on is **post-paid (后付费)** — it works from
Volcengine's own demo page on the website and is rejected from code — so the key is right, the field
is filled in, and the turn still dies at the start. The switch is on the console's **开通管理** page
(activation management): set that service to **prepaid / subscription (预付 / 预购)** and buy a
streaming-ASR resource pack there. Their billing terms are theirs and change without telling us, so
this file does not restate them — only where the switch is.

**That failure has two shapes, and when it happens is the clue.** Either the handshake is refused
outright — the connection dies at the very start of the turn, before a byte of audio has gone out,
which is the shape a missing entitlement takes — or the socket does come up and the server returns
an error frame carrying the entitlement code after the first request. The first is **not** a
timeout, the second is not a key problem, and both surface as `ASR ws refused: …` or as a server
error code. Neither message points at 开通管理, which is why it is written down here.

**If the pack you bought is a different SKU, the 识别资源 ID row is the third cause.** It
sits under the Volcengine key (`asr_resource_id`), defaults to `volc.bigasr.sauc.duration`
(the **1.0** model's by-duration pack) and is sent as the `X-Api-Resource-Id` header. A
by-concurrency pack (并发版) of the same model takes `volc.bigasr.sauc.concurrent` there
instead. Get it wrong and the symptom is indistinguishable from the two above: the key is
fine, the call just fails.

The two ids that pair with this build are the 1.0 ones, because the request it sends also names
the model: `model_name` is `bigmodel`, which is 豆包流式语音识别模型 1.0. The 2.0 service
(豆包流式语音识别 2.0, resource ids `volc.seedasr.sauc.duration` / `volc.seedasr.sauc.concurrent`)
is a different model, so if the console only offered you 2.0, this row cannot rescue it — open
the 1.0 service as well. Leave the row alone once it holds one of the two 1.0 values.

> **If you took the CodeFlying route in [Install](../README.md#install), both keys are already baked in.**  
> ⚙ → **Backend** will show **CodeFlying** as the active tab; LLM, ASR, TTS and the long tail of  
> third-party APIs (image / video / music generation, geolocation, weather, PDF, search, news,  
> stocks, …) all go through one key, wrapped and billed by CodeFlying so you don't sign up for each  
> provider yourself. The two keys above only apply to the **On-device** tab you configure yourself.

You do not have to remember this. Every time the assistant comes up it checks itself, and if either  
key is missing the card it puts up names what is missing — its button opens our settings, where both  
keys live under **Backend → On-device**. The **✓** button in the ball's control bar opens that same  
list whenever you want it.

**To hand someone an APK, sign a release build — not the debug one.** The release build type is the  
minified one (`minifyEnabled` + `shrinkResources`), and that is most of what makes the APK small: the  
debug variant ships un-shrunk dex. It has no `signingConfig` and there is no keystore in the repo, so  
`assembleRelease` emits `app-<abi>-release-unsigned.apk`, which will not install until you sign it.  
The debug build is signed with the standard Android debug certificate and installs as-is. Either way  
the APK is **per-ABI** (`splits` in `app/build.gradle`), so pick the one that matches the device —  
`arm64-v8a` for anything bought in the last several years.

**Keep your signing key out of git — including this repository's forks.** Android lets an APK replace  
an installed one only when the signatures match, and that is the only thing standing between your users  
and a fake "update". Whoever holds the key can ship an APK that installs over theirs and inherits  
everything: the accessibility service stays enabled, the `WRITE_SECURE_SETTINGS` grant stays, and the  
vault, notebook and conversation are its to read. A leaked key cannot be taken back without every user  
uninstalling. Keep it in your build machine's secret storage and point `signingConfig` at it through  
environment variables. A side effect worth knowing: the debug keystore is generated per machine, so an  
APK built on one machine cannot be installed over one built on another (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`)  
— uninstall first, or give local builds a different `applicationId`; do not share the key to get around it.

**If the build does not work, the answer is almost always the JDK.** Full from-scratch setup — the  
JDK traps, the Android SDK two different ways (including a command-line-only path for CI), and a table  
of every error you are likely to hit — is in **[Provisioning](#provisioning)** below. Permissions are  
answered in **[Permissions](reference.md#permissions-why-each-one-and-what-happens-if-you-refuse)**, and the  
known limitations in **[SECURITY.md](../SECURITY.md)** — read that last one before running this on a  
network you do not control.

### Provisioning

Pure Kotlin, no Compose; the whole thing hangs off two pillars, `WindowManager` and  
`AccessibilityService`.

#### Build configuration

| What                      | Value               | Why                                                                                       |
| ------------------------- | ------------------- | ----------------------------------------------------------------------------------------- |
| Android Gradle Plugin     | 8.3.1               | `agp` in `gradle/libs.versions.toml`                                                      |
| Kotlin                    | 2.1.21              | pinned in the root `build.gradle`; all three modules move together                        |
| compileSdk / targetSdk    | 34                  | Android 14                                                                                |
| minSdk                    | 27                  | Android 8.1 — it installs and chats. **Screen capability needs API 30+**: `ACTION_IME_ENTER` and `takeScreenshot` are both API 30+, and below that the self-check shows an amber row |
| Java / Kotlin JVM target  | 1.8                 | `sourceCompatibility` 1.8, `jvmTarget = '1.8'`                                            |
| **Build JDK**             | **17–20**           | see [Which JDK](#which-jdk) — this is the step people get stuck on                        |
| versionCode / versionName | 31 / 0.16.0          | see [CHANGELOG.md](../CHANGELOG.md) for the policy                                           |
| applicationId / namespace | `net.kuafuai.andee` | never changed from the template. It affects nothing                                       |

**Runtime dependencies** (`app/build.gradle`):

- `org.java-websocket:Java-WebSocket:1.5.7` — the device's own ws server (port 9008, for debugging  
  from the same LAN) plus the ws client that dials the brain hub
- `com.squareup.okhttp3:okhttp:4.12.0` — HTTP + WS client for ASR / TTS / local models
- `com.github.mik3y:usb-serial-for-android:3.8.1` — the robot-dog USB serial dongle. **Deliberately  
  pinned at 3.8.1**: 3.11.0 pulls in `kotlin-stdlib` 2.2.x, whose metadata this project's Kotlin  
  1.9.22 compiler cannot read
- `androidx.camera:camera-*:1.3.4` + `com.google.mlkit:barcode-scanning:17.2.0` — scanning (the  
  ball's eyes)
- `androidx.appcompat:1.6.1` + `com.google.android.material:1.10.0` — for one base theme only. All UI  
  is hand-written `View` code

**What is deliberately absent:**

- No Jetpack Compose — Compose cannot live inside an overlay, so `WindowManager` gets plain `View`s
- No Hilt / Dagger — there is one service, so its dependencies are constructor-injected by hand
- No coroutines — WS callbacks run on `Handler` plus dedicated `Executor`s

Match this style when you change the code, rather than bringing in the modern full stack.

#### Which JDK

**This is the step that stops people.** `gradle.properties` **does not** hardcode  
`org.gradle.java.home`, and that is on purpose. It used to, with a machine-specific path  
(`/Users/<someone>/...`), and the cost was that the very first build on anyone else's machine failed  
in a way that looked like an environment problem.

The build needs **JDK 17–20**:

- AGP 8.3.1 requires 17 or newer;
- **the Gradle 8.4 wrapper cannot run on 21 or newer.** Android Studio's bundled JBR 25 is rejected  
  outright with `Unsupported class file major version 69`.

Two ways to point one machine at a JDK (neither belongs in the repo's `gradle.properties`):

```bash
export JAVA_HOME=/path/to/jdk-17        # 1. environment variable
printf 'org.gradle.java.home=/path/to/jdk-17\n' >> ~/.gradle/gradle.properties   # 2. user-level config
```

Android Studio users: **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle  
JDK**, and pick a 17–20 JDK. (The IDE setting overrides the file.)

#### Getting the Android SDK

Gradle finds the SDK through `sdk.dir=` in `local.properties`. Android Studio writes that file for  
you; it is **not in the repository** (it is in `.gitignore`), so every machine configures it once.

##### Path A — Android Studio (recommended, one step)

1. Install [Android Studio](https://developer.android.com/studio). The first launch shows the  
   **Setup Wizard** — take the defaults and it downloads the Android SDK, Platform Tools (`adb`) and  
   a bundled JDK for you.
2. **Open this repository's root directory** in Android Studio. It writes `local.properties`, notices  
   which SDK pieces are missing, and offers "Install missing SDK" in a blue bar. Accept it.
3. In SDK Manager (top bar → Tools → SDK Manager), confirm:
   - **SDK Platforms**: Android 14 (API 34 — compileSdk / targetSdk); Android 11 (API 30 — the floor for screen capability,  
     handy if you want an emulator)
   - **SDK Tools**: Android SDK Build-Tools (34.x), Android SDK Platform-Tools (latest), Android SDK  
     Command-line Tools (latest)
4. The default SDK path is `~/Library/Android/sdk` on macOS and `%LOCALAPPDATA%\Android\Sdk` on  
   Windows.

##### Path B — command line only

For a server or a CI box, skip Studio and use `sdkmanager`:

```bash
# 1. JDK 17 (macOS)
brew install --cask temurin@17

# 2. download the command-line tools zip
#    https://developer.android.com/studio → scroll to "Command line tools only"
#    grab commandlinetools-mac-*.zip

mkdir -p ~/Library/Android/sdk/cmdline-tools
unzip commandlinetools-mac-*.zip -d ~/Library/Android/sdk/cmdline-tools
mv ~/Library/Android/sdk/cmdline-tools/cmdline-tools ~/Library/Android/sdk/cmdline-tools/latest

# 3. put it on your PATH (~/.zshrc)
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 4. install the pieces
sdkmanager --licenses           # answer y to all
sdkmanager "platform-tools" \
           "platforms;android-34" \
           "build-tools;34.0.0"
```

##### `local.properties`

Android Studio writes this for you. By hand, put one line in `local.properties` at the **repository  
root**:

```
sdk.dir=/Users/<your-name>/Library/Android/sdk
```

On Linux it is usually `/home/<user>/Android/Sdk`; on Windows,  
`C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk` (backslashes escaped).

You can also skip `local.properties` entirely and export `ANDROID_HOME` / `ANDROID_SDK_ROOT` only —  
Gradle reads either.

##### Verify the toolchain

```bash
./gradlew --version           # expect JVM 17.x and Gradle 8.4
./gradlew tasks               # seeing :app:assembleDebug means you are through
./gradlew assembleDebug       # first run downloads dependencies, so it is slow
```

##### Traps and their fixes

| Message                                                  | Cause                                                      | Fix                                                                                                                                  |
| -------------------------------------------------------- | ---------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| `SDK location not found`                                 | no `sdk.dir=` in `local.properties`, and no `ANDROID_HOME` | configure one of the two                                                                                                             |
| `Failed to find Build Tools revision 34.0.0`             | that build-tools version is not installed                  | `sdkmanager "build-tools;34.0.0"`, or add it in Studio's SDK Manager                                                                 |
| `You have not accepted the license agreements`           | licences not accepted                                      | `sdkmanager --licenses`, answer y                                                                                                    |
| `Unsupported class file major version 69`                | JDK too new (25); Gradle 8.4 cannot take it                | switch to JDK 17–20, see [Which JDK](#which-jdk)                                                                                     |
| `Could not resolve com.android.tools.build:gradle:8.3.1` | network, or no mirror configured                           | try a phone hotspot, or set an Aliyun mirror in `~/.gradle/init.gradle` (this project's `settings.gradle` already puts Aliyun first) |

#### Hardware and system requirements

- Android 8.1 (API 27) or newer — chat, voice, the notebook and settings all work;
  **screen capture, visual tap targeting and window blur need Android 11 (API 30) or newer**
- Any screen orientation — the ball always floats on top
- A microphone, or ASR is unusable
- Network (WiFi / 4G / 5G all fine). ASR and TTS go over the public internet, the brain hub goes over  
  the LAN; in `local` mode only the public internet is used

#### Installing a build you made

```bash
./gradlew installDebug         # device attached over USB, or already on wireless adb
# or build the APK and install it yourself
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

#### Provisioning order

For a first install, this order is the shortest path:

1. Install the APK
2. Go straight to "display over other apps" and grant the overlay → come back and the ball is there
3. Go to Accessibility → enable Andee
4. Tap the ball, and allow the microphone prompt
5. Open the ⚙ on the ball. In **Advanced**, turn on **Show cloud option** (the **Cloud** tab is  
   hidden by default). Go back to **Backend**, flip the picker to **Cloud**, set `hub_url` =  
   `ws://<your Mac's IP>:9100` and `device_name` to anything, and save
6. logcat should show `hub: registered as body-xxx with N tools`, and the brain side should log  
   `[body_hub] 'body-xxx' registered (…) with N tools`

If any step does not line up, go back to that section's **Verify**.

> If you only want the brain on this device, replace step 5 with **Backend → On-device** and fill in  
> an OpenAI-compatible endpoint plus `llm_api_key` — running without a hub is a supported mode, not a  
> degraded one. If your APK was packaged via CodeFlying, the **CodeFlying** tab is the default and  
> there is nothing to fill in.
