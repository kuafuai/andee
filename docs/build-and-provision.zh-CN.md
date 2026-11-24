# 从源码构建与配置

> 从 README 搬到这里，内容没有改，只是标题深了一级。

### 从源码构建

只想用、不想自己构建的话，看上面的[安装](../README.md#安装)。下面是自己构建的路：Android 的完整步骤在后面的
[构建配置](#构建配置)、[配 JDK](#配-jdk)、[把自己构建的包装到设备](#把自己构建的包装到设备)几节。

**在 Mac 上**，同一份代码可以构建桌面版（Apple 芯片、macOS 14+，要装 Command Line Tools 15 或更新——它带的 macOS 14
SDK 提供 `swiftc` 和截图用的 `SCScreenshotManager`，更旧的会让构建直接报错——JDK 同样是 17–20）：

```bash
./gradlew :desktop:createDistributable   # → desktop/build/compose/binaries/main/app/Andee.app
cp -R desktop/build/compose/binaries/main/app/Andee.app /Applications/
# 然后：它要「辅助功能」和「屏幕录制」时允许（系统设置 → 隐私与安全性），
# 再像手机上一样，在 设置 → 后端 里填模型 Key。
```

macOS 按应用签名记住这两项授权，所以没签名的重新构建每次都会丢授权 ——
[desktop/README-signing.md](../desktop/README-signing.md) 讲了怎么配一个固定的本地签名。

#### 打包给别人装：两种芯片

`createDistributable` 只为**跑它的那台机器**打包：启动器、内嵌运行时、以及那几个原生件
（`libandeemac.dylib`、`andee-trash`、LWJGL 的 natives）全都跟着 Gradle 用的那个 JVM 的 `os.arch` 走。
没有交叉编译开关 —— `os.arch` 直接从 JVM 读 —— 所以 Apple 芯片的包在 Intel Mac 上起不来，反过来也一样。
所以要给两个包，Mac 软件都是这么发的：

| 芯片 | 给人的包 | 用什么打 |
| --- | --- | --- |
| Apple 芯片（M1–M4） | `Andee-1.0.0-arm64.dmg` | 任意 JDK 17–20 |
| Intel | `Andee-1.0.0-x64.dmg` | x86_64 的 JDK 17，跑在 Rosetta 下 |

**Apple 芯片的机器两个都能打**：架构只由「跑 Gradle 的那个 JVM」决定，而 Rosetta 能跑 x86_64 的 JDK。
装 x86_64 JDK 请用 Temurin 的 macOS x64 压缩包，别用 Homebrew —— `openjdk@17` 那个 formula 要从源码编译、
要求完整 Xcode，而本项目其它地方并不需要 Xcode：

```bash
curl -L -o /tmp/jdk17-x64.tar.gz \
  'https://api.adoptium.net/v3/binary/latest/17/ga/mac/x64/jdk/hotspot/normal/eclipse'
mkdir -p ~/jdk-x64 && tar -xzf /tmp/jdk17-x64.tar.gz -C ~/jdk-x64

./gradlew --stop                                    # 已经在跑的 daemon 是 arm64
JAVA_HOME=~/jdk-x64/jdk-17.0.20.1+1/Contents/Home \
  ./gradlew :desktop:packageDmg --no-daemon         # → Andee-1.0.0.dmg（x86_64）
./gradlew --stop && ./gradlew :desktop:packageDmg   # → Andee-1.0.0.dmg（arm64）
```

两种架构打出来的文件名**都是** `desktop/build/compose/binaries/main/dmg/Andee-1.0.0.dmg`，后打的会覆盖先打的 ——
打完一个先按上表改名，再打另一个。打包要先装 `dmgbuild`，步骤见英文 README 的 Mac packaging 一节。

**这里有两处失败是静默的。**

只要**输入**没变，`createRuntimeImage` 就被判成 up-to-date —— 而它跑在哪个 JVM 上并不算输入。换架构时
Gradle 会直接复用上一次的 JRE，产出「x86_64 启动器套着 arm64 的 `libjvm.dylib`」这种混合体：
BUILD SUCCESSFUL，装上去打不开。所以每次换架构之前，先把运行时挪开：

```bash
mv desktop/build/compose/tmp/main/runtime desktop/build/compose/tmp/main/runtime-old
```

另一个是两个包写进同一个文件名，后打的会不声不响地把先打的盖掉，打完随手改名。（文件名里的 `1.0.0`
是 macOS 的 bundle 版本，即 `desktop/build.gradle` 里的 `packageVersion` —— macOS 不接受 `0.x` 的 bundle
版本；它不是产品版本。）

发之前把四个二进制都验一遍，架构必须一致：

```bash
cd desktop/build/compose/binaries/main/app/Andee.app
lipo -archs Contents/MacOS/Andee
lipo -archs Contents/runtime/Contents/Home/lib/server/libjvm.dylib
lipo -archs Contents/runtime/Contents/Home/lib/libjli.dylib
lipo -archs Contents/app/libskiko-macos-*.dylib
```

skiko 那个文件名本身就带着架构（`libskiko-macos-arm64.dylib` / `libskiko-macos-x64.dylib`），
看一眼名字就知道手里是哪个包。

做不成「一个包通吃」：jpackage 的启动器只认一个运行时目录，一个启动器没法在两个 JRE 之间选，
而要做 universal 的 JRE 就得分文件把整套 dylib 都 lipo 一遍。两个包才是这套构建支持的答案。

想自己编译 Android 版的话：

> **环境要求**
>
> - Android 8.1（API 27）或更新 —— 能装能跑。**但屏幕相关的能力要 Android 11（API 30）**：
>   `ACTION_IME_ENTER` 和 `takeScreenshot` 都是 API 30+，低于它只能读无障碍树、看不了屏幕
> - **JDK 17–20。** AGP 8.3.1 要 17+；Gradle 8.4 的 wrapper 跑不了 21+（Android Studio 自带的
>   JBR 25 会被直接拒绝：`Unsupported class file major version 69`）
> - Android SDK：platform 34 + build-tools 34.0.0
> - **真机。** 身体是前台服务，屏幕那一半才是无障碍服务；模拟器能跑，但它几乎什么都干不了

```bash
./gradlew clean :app:assembleDebug     # 产物 app/build/outputs/apk/debug/app-<abi>-debug.apk
./gradlew installDebug                 # 构建并安装到已连接的设备

# 然后在设备上，按这个顺序：
#   1. 开悬浮窗        → 球出现
#   2. 开无障碍        → 它能看、能碰屏幕了
#   3. 点一下那颗球     → 允许麦克风
#   4. 打开 ⚙，填两个你自己的 Key（见下）
```

**仓库里不带任何密钥，而这两个缺失的密钥，报出来的错都不像「缺密钥」。** 两个都填在设备的 ⚙ 里 ——
**一个是必须的，一个只是让你用不了语音：**

**1. 大脑密钥 —— 必须。** `brain = local`（开箱默认）下，智能体循环就跑在设备本机，接任意
OpenAI 兼容端点（`llm_api_key`，中文界面默认 `https://api.deepseek.com`）。**空着，本机大脑根本跑不起来**
—— 它后面没有别的兜底。球至少会把这句话说出来，而不是默默什么都不做。

**2. 语音密钥 —— 非必须。** 先在同一个**后端 → 本机** tab 顶部选服务商（**语音服务商** → 火山 /
Google），再填该服务商的密钥。这是两个独立的服务，条款和数据处理各归各家。
选**火山**就填[火山引擎语音服务](https://www.volcengine.com/product/voice-tech)的 API Key；
选 **Google** 则要一把 Vertex Express Key，外加 GCP 的**项目编号**（不是项目名）和一个区域。
选中的那家没填密钥，识别和合成会**在握手阶段被直接拒绝** —— 服务器是应答了的，答的是「不行」
（key 空着或被吊销都是 403），连接在一个字节的音频发出去之前就断了。这和**连接超时**不是一回事，
超时是一台根本不回话的主机才有的样子；把前一种读成后一种，就会跑去查网络，而那一栏其实只是空着。
其余功能照常，只是既听不见也说不出。
不管你选哪家，音频都会发到那家 —— 见 [DISCLAIMER.zh-CN.md](../DISCLAIMER.zh-CN.md)。

**火山这边，key 填对了也还是不够。** 控制台里「服务已开通」和「这个资源已被授权调用」是两句话，
API 只认后一句。刚开通的服务默认是**后付费**，它只能在火山官网的体验页里用，程序走 API 会被拒
—— 于是 key 是对的、那一栏也填满了，这一轮照样死在开头。开关在控制台的**开通管理**页：把该服务
改成**预付 / 预购**，并在那里买一个流式语音识别的资源包。火山的计费条款是他们的，而且会不打招呼
就改，所以这里不复述条款内容，只写到开关在哪一页。

**这种失败有两种形态，而它失败的时机本身就是线索。** 要么握手被直接拒绝 —— 连接在对话的一开始
就断，一个字节的音频都还没发出去，资源没有授权通常是这一种；要么连接确实建起来了，第一次请求
之后服务端回一个带授权错误码的错误帧。前一种**不是**超时，后一种也不是 key 错了，而两者都表现为
`ASR ws refused: …` 或者一个服务器错误码。这两句提示都不会叫你去开通管理，所以这件事写在这里。

**如果买的包是另一种 SKU，第三种原因在「识别资源 ID」那一行。** 它就在火山 key 下面
（`asr_resource_id`），默认 `volc.bigasr.sauc.duration`（**1.0** 模型的时长版），作为
`X-Api-Resource-Id` 头发出去；同一个模型的并发版则要换成 `volc.bigasr.sauc.concurrent`。填错了，
症状和上面两种分不出来：key 没问题，就是调不通。

能跟这一版配上的是 1.0 那两个值，因为发出去的请求里也写死了模型：`model_name` 是 `bigmodel`，
也就是**豆包流式语音识别模型 1.0**。**2.0**（豆包流式语音识别 2.0，资源 id 是
`volc.seedasr.sauc.duration` / `volc.seedasr.sauc.concurrent`）是另一个模型，所以控制台只给你
2.0 的话，改这一行救不回来 —— 要另外把 1.0 的服务也开通。这一行已经填了上面两个 1.0 值之一，就别动它。

> **如果你走的是[安装](../README.md#安装)里 CodeFlying 那条路，这两把 Key 都已经在安装包里了。**
> ⚙ → **后端**会直接显示 **CodeFlying** 这一 tab 为当前后端；LLM、ASR、TTS 以及海量第三方 API
> （AI 图片/视频/音乐生成、地理位置、天气、PDF、搜索、新闻、股票……）都走同一把 Key，由 CodeFlying
> 统一封装并计费，免去你逐一申请密钥和维护账单的麻烦。上面两把 Key 只适用于你自己配置的**本机** tab。

这些不用记。每次助手起来都会自检一遍，两把 Key 缺哪个，弹出来的卡上就会点名说清楚，卡上的按钮
直接打开设置，Key 填在**后端 → 本机**。球的控制条上的 **✓** 则随时可以再打开这份清单。

**要拿 APK 给别人装，签一个 release 包，别发 debug 包。** release 才是压过的那版
（`minifyEnabled` + `shrinkResources`），体积小主要就小在这里——debug 包带的是没压过的 dex。
它没有 `signingConfig`，仓库里也没有 keystore，所以 `assembleRelease` 产出的是
`app-<abi>-release-unsigned.apk`，**不签名装不上**。debug 包用 Android 默认调试证书签名，
可以直接装。两种包都是**按 ABI 拆开的**（见 `app/build.gradle` 里的 `splits`），拿和设备对得上
的那个——近几年买的机器基本都是 `arm64-v8a`。

**签名文件不要进 git，fork 也一样。** Android 只允许签名相同的 APK 覆盖安装，这是用户和一个
冒牌「更新」之间唯一的一道门。谁拿到签名，谁就能做出一个能直接装在用户机器上的 APK，并且继承
一切：无障碍服务仍然开着，`WRITE_SECURE_SETTINGS` 授权还在，密码本、笔记本和对话记录都归它读。
签名一旦泄露就收不回来，除非所有用户卸载重装。签名放在打包机的私密存储里，`signingConfig` 通过
环境变量指向它。顺带一提：debug 签名是每台机器各自生成的，所以一台机器打的包不能覆盖另一台机器
装的包（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）——先卸载，或者给本地构建换一个 `applicationId`，
别为了绕过它去共享签名。

##### 遇到问题

装机流程、以及从零配 SDK 的完整说明（含纯命令行 / CI 路径）在本文下面的
[Android SDK 下载与配置](#android-sdk-下载与配置)。权限为什么这么要、拒绝了会怎样 —— 在
**[docs/permissions.zh-CN.md](../docs/permissions.zh-CN.md)**。已知限制在 **[SECURITY.md](../SECURITY.md)**（英文）；
如果你打算在**你控制不了的网络**上用，先读那一条。

### 构建配置

纯 Kotlin，无 Compose，靠 `WindowManager` + `AccessibilityService` 两根柱子撑起来。

| 项 | 值 | 说明 |
|---|---|---|
| Android Gradle Plugin | 8.3.1 | `gradle/libs.versions.toml` 里的 `agp` |
| Kotlin | 2.1.21 | 根目录 `build.gradle` 固定，三个模块一起动 |
| compileSdk / targetSdk | 34 | Android 14 |
| minSdk | 27 | Android 8.1 —— 装得上就能聊。**屏幕能力要 API 30+**：`ACTION_IME_ENTER` 和 `takeScreenshot` 都是 API 30+，低于它装完会在自检里看到一条琥珀色提醒 |
| Java / Kotlin JVM target | 1.8 | `sourceCompatibility` 1.8、`jvmTarget = '1.8'` |
| **构建用 JDK** | **17–20** | 见[配 JDK](#配-jdk)，这是最容易卡住的一步 |
| versionCode / versionName | 32 / 0.16.1 | 策略见 [CHANGELOG.md](../CHANGELOG.md) |
| applicationId / namespace | `net.kuafuai.andee` | 从模板里没改，不影响功能 |

**运行库依赖**（`app/build.gradle`）：

- `org.java-websocket:Java-WebSocket:1.5.7` —— 设备自己起的 ws server（端口 9008，给同网段调试用）+ 拨向大脑 hub 的 ws client
- `com.squareup.okhttp3:okhttp:4.12.0` —— ASR / TTS / 本地模型的 HTTP + WS 客户端
- `com.github.mik3y:usb-serial-for-android:3.8.1` —— 机器狗加密狗（USB 串口）。**故意停在 3.8.1**：3.11.0 会拉进 `kotlin-stdlib` 2.2.x，而本项目的 Kotlin 1.9.22 编译器读不了它的 metadata
- `androidx.camera:camera-*:1.3.4` + `com.google.mlkit:barcode-scanning:17.2.0` —— 扫码（球球的眼睛）
- `androidx.appcompat:1.6.1` + `com.google.android.material:1.10.0` —— 只吃一个基础主题，UI 全是手写 `View`

**有意没引入什么**：

- 没有 Jetpack Compose —— 悬浮窗挂不上 Compose，`WindowManager` 直接加 `View`
- 没有 Hilt / Dagger —— service 就一个，构造函数注入手工写
- 没有 Coroutines —— WS 回调用 `Handler` + 独立 `Executor`

改代码时请沿用这套风格，而不是引入现代全家桶。

### 配 JDK

**这是最容易卡住的一步。** `gradle.properties` **不写死** `org.gradle.java.home`，这是有意的。
以前写死过一条机器专属路径（`/Users/<某人>/...`），代价是别人 clone 下来第一步就编不过，而报错
看起来像环境问题。

这个构建需要 **JDK 17–20**：

- AGP 8.3.1 要求 17 或更高；
- **Gradle 8.4 的 wrapper 跑不了 21 或更高**。新版 Android Studio 自带的 JBR 25 会被直接拒绝：
  `Unsupported class file major version 69`。

给本机指定 JDK 的两种做法（都**不要**写进仓库里的 `gradle.properties`）：

```bash
export JAVA_HOME=/path/to/jdk-17        # 方式一：环境变量
printf 'org.gradle.java.home=/path/to/jdk-17\n' >> ~/.gradle/gradle.properties   # 方式二：用户级配置
```

Android Studio 用户：**Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle
JDK**，选一个 17–20 的 JDK。（IDE 里的设置会覆盖本文件。）

### Android SDK 下载与配置

Gradle 编译时读 `local.properties` 里的 `sdk.dir=` 找 SDK。这个文件是 Android Studio 自动生成
的，**不入版本库**（在 `.gitignore` 里），所以每台机器第一次拉代码都要自己配。

#### 路径一：走 Android Studio（推荐，一步到位）

1. 装 [Android Studio](https://developer.android.com/studio) → 首次启动会弹 **Setup Wizard**，
   一路默认下一步，它会自动下 Android SDK、Platform Tools（`adb`）、内置 JDK。
2. 用 Android Studio **打开本仓库根目录** → 它会自己写 `local.properties`，并检测缺什么 SDK
   组件、蓝条提示 "Install missing SDK"，点 Accept 装完就行。
3. SDK Manager（顶栏 → Tools → SDK Manager）确认下列都装了：
   - **SDK Platforms**：Android 14（API 34，compileSdk / targetSdk）；Android 11（API 30，跑屏幕能力的最低版本，装一份方便起模拟器）
   - **SDK Tools**：Android SDK Build-Tools（34.x）、Android SDK Platform-Tools（最新）、Android SDK Command-line Tools（latest）
4. macOS 默认 SDK 路径 `~/Library/Android/sdk`，Windows 默认 `%LOCALAPPDATA%\Android\Sdk`。

#### 路径二：纯命令行（不装 Studio）

只想在服务器 / CI 上编的话，靠 `sdkmanager`：

```bash
# 1. 装 JDK 17（macOS）
brew install --cask temurin@17

# 2. 下 command-line tools zip
#    https://developer.android.com/studio → 页面拉到底 "Command line tools only"
#    下 commandlinetools-mac-*.zip

mkdir -p ~/Library/Android/sdk/cmdline-tools
unzip commandlinetools-mac-*.zip -d ~/Library/Android/sdk/cmdline-tools
mv ~/Library/Android/sdk/cmdline-tools/cmdline-tools ~/Library/Android/sdk/cmdline-tools/latest

# 3. 写环境变量到 ~/.zshrc
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# 4. 装组件
sdkmanager --licenses           # 全部输 y
sdkmanager "platform-tools" \
           "platforms;android-34" \
           "build-tools;34.0.0"
```

#### 配 `local.properties`

Android Studio 会替你写。手动的话，在**仓库根目录**的 `local.properties` 里放这一行：

```
sdk.dir=/Users/<你的用户名>/Library/Android/sdk
```

Linux 通常是 `/home/<user>/Android/Sdk`，Windows 是
`C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk`（反斜杠要转义）。

也可以不写 `local.properties`，只导 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 环境变量，Gradle 会读
这两个之一。

#### 验证

```bash
./gradlew --version           # 看到 JVM 17.x + Gradle 8.4
./gradlew tasks               # 能列出 :app:assembleDebug 就是通的
./gradlew assembleDebug       # 第一次下依赖会久
```

#### 常见坑

| 报错 | 原因 | 修 |
|---|---|---|
| `SDK location not found` | `local.properties` 没有 `sdk.dir=`，`ANDROID_HOME` 也没导 | 二选一配上 |
| `Failed to find Build Tools revision 34.0.0` | SDK 里没装对应 build-tools | `sdkmanager "build-tools;34.0.0"` 或在 Studio 的 SDK Manager 里补装 |
| `You have not accepted the license agreements` | 没接受协议 | `sdkmanager --licenses` 全输 y |
| `Unsupported class file major version 69` | JDK 太新（25），Gradle 8.4 不支持 | 换成 JDK 17–20，见[配 JDK](#配-jdk) |
| `Could not resolve com.android.tools.build:gradle:8.3.1` | 网络问题 / 镜像没配 | 用手机热点，或在 `~/.gradle/init.gradle` 配阿里云镜像（本项目的 `settings.gradle` 已把阿里云镜像排在前） |

### 硬件 / 系统要求

- Android 8.1（API 27）或更高 —— 装得上就能用聊天、语音、笔记和设置；
  **屏幕截图、看图点击、窗口毛玻璃要 Android 11（API 30）或更高**，低于它这些功能不可用
- 屏幕方向随意，悬浮球会浮在最上层
- 需要麦克风，否则 ASR 用不了
- 需要联网（WiFi / 4G / 5G 都行）—— ASR / TTS 走公网，大脑 hub 走 LAN；用 `local` 模式则只走公网

### 把自己构建的包装到设备

```bash
./gradlew installDebug         # 设备已插 USB 或已连 wifi adb
# 或者产出 APK 后自己 adb install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

### 装机顺序建议

第一次装，按这个顺序最省事：

1. 装 APK
2. 立刻去「显示在其他应用上层」开悬浮窗 → 回来能看到球了
3. 去「无障碍」→ 开 Andee
4. 点一下球，弹麦克风申请 → 允许
5. 打开球上的 ⚙，先翻到**高级**把「显示云端选项」打开（**云端** tab 默认是藏起来的）；回到
   **后端**，picker 切到 **云端**，填 `hub_url` = `ws://<你 Mac 的 IP>:9100`、`device_name` =
   随便起，保存
6. logcat 里应该出现 `hub: registered as body-xxx with N tools`，同时大脑侧日志出现
   `[body_hub] 'body-xxx' registered (…) with N tools`

任一步没对上，回来看本文档对应章节的**验证**。

> 只想要本机大脑的话，第 5 步改成**后端 → 本机**，填一个 OpenAI 兼容端点和 `llm_api_key` 即可 ——
> 不走 hub 是合法模式，不是降级。如果你的 APK 是通过 CodeFlying 打包的，那条 tab 也是默认选中的
> 一个，什么都不用填。
