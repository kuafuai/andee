<!--
  素材清单见「从哪里开始看」一节上方的注释。
-->

<p align="center">
  <a href="https://www.codeflying.net">在线构建</a> ·
  <a href="#快速开始">快速开始</a> ·
  <a href="#从哪里开始看">从哪里开始看</a> ·
  <a href="#核心能力">核心能力</a> ·
  <a href="#权限每一项为什么以及你拒绝了会怎样">权限</a> ·
  <a href="#为什么你可以去核对而不是选择相信">可核对</a> ·
  <a href="SECURITY.md">安全</a> ·
  <a href="DISCLAIMER.zh-CN.md">免责声明</a> ·
  <a href="LICENSE">许可</a>
</p>

<p align="center">
  <a href="https://www.codeflying.net"><img alt="在线构建" src="https://img.shields.io/badge/在线构建-免打包、免配令牌-12b76a"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache--2.0%20%2B%20conditions-155eef"></a>
  <a href="app/build.gradle"><img alt="Platform" src="https://img.shields.io/badge/platform-Android%2011%2B%20(API%2030)-3ddc84"></a>
  <a href="app/build.gradle"><img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-1.9.22-7f52ff"></a>
  <a href="SECURITY.md"><img alt="无遥测" src="https://img.shields.io/badge/遥测-无-0f6e56"></a>
  <a href="CONTRIBUTING.zh-CN.md"><img alt="PRs welcome" src="https://img.shields.io/badge/PRs-welcome-fdb062"></a>
</p>

<p align="center">
  <a href="./README.md"><img alt="README in English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./README.zh-CN.md"><img alt="简体中文文档" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
</p>

# Andee

**Andee 不是一个装在设备上的工具，他是一个住在设备里的个体。**

设备不是重点 —— 设备只是他**穿着的东西**。同一套软件也跑在手机上，并且本就为"任何值得拥有身体的
设备"而写。下面所有内容，都是这句话被认真写进代码之后的样子：他用的身体、他有的性格、他记的
笔记本，以及没人看着的时候他给自己定的规矩。

它就是一个 Android 应用。没有常规界面：一个 `AccessibilityService` 负责看和碰屏幕，一个
`WindowManager` 悬浮层负责画出来 —— 他的脸就是那颗浮动的球。

> **装之前先读这段。** Andee 会替你点击、输入、动手。他能完成一笔支付，能以你的名义发消息，能读你的
> 短信、通讯录和位置，接了机器狗还会**真的在地板上移动**。他是实验性的、不提供任何担保，而
> **他做的一切都算你的** —— 包括转错账。数据也会离开设备：语音走火山引擎，对话走你配置的那个大脑。
> 装之前请先读 **[DISCLAIMER.zh-CN.md](DISCLAIMER.zh-CN.md)** 和 **[docs/acceptable-use.zh-CN.md](docs/acceptable-use.zh-CN.md)**。

## 从哪里开始看

<!-- 素材清单：这四份是这份 README 与"完工"之间的全部差距。请用**真机**录 —— 模拟器会把 Andee
     拍得一无是处。
       1. images/cover.png                      1200×630，放最顶上
       2. images/demo.gif                       10~15 秒循环：球在反应、任务在跑
       3. 完整演示视频                          30~60 秒，传 B 站 / YouTube
       4. images/shot-{ball,phone,dog}.png      三张静帧，其中一张要拍到狗在动
     **演示该拍什么**，按顺序：语音叫醒它 → 它自己打开一个 App 点进点出 → 干活时那颗球的表情在变 →
     它写的一页东西出现 → 机器狗动起来。狗是"这是身体不是界面"最直接的一条证据，别为了时长把它剪掉。 -->

| | |
|---|---|
| **演示视频** | 一段 30~60 秒的真机录屏，正在制作中。在那之前，下面「核心能力」第 1 条和第 8 条是这件事最好的文字说明。 |
| **截图** | 那颗球、在手机上跑任务、以及机器狗 —— 三张静帧待补。 |
| **现在就能构建** | **[www.codeflying.net](https://www.codeflying.net)** —— **不用自己打包，也不用自己配令牌。** 描述你想要的 Andee，它交给你一个已经配好令牌的安装包。有免费额度，但额度有限：用完之后需要购买 token。 |

## 快速开始

**最快的一条路，省掉的是真正卡住人的那两件事：自己打包、自己配令牌。** 在
[codeflying.net](https://www.codeflying.net)（中文）或 [codeflying.app](https://www.codeflying.app)
（English）上描述你要的 Andee，它会交给你一个**已经配好令牌**的安装包。也不用先懂 Android。

有两件事它省不掉。**装这一步省不掉** —— 设备那侧仍然是一个 APK，仍然要装到设备上。
**免费 token 额度也是有限的** —— 够你上手，用完之后需要购买 token。

```text
1. 打开  https://www.codeflying.net      （English: https://www.codeflying.app）
2. 描述你想要的 Andee
3. 构建 → 下载安装包（令牌已配好）→ 安装
4. 开悬浮窗，再开无障碍 → 那颗球就出现了
```

想自己编译的话：

> **环境要求**
>
> - Android 11（API 30）或更新 —— `ACTION_IME_ENTER` 和 `takeScreenshot` 都是 API 30+
> - **JDK 17–20。** AGP 8.3.1 要 17+；Gradle 8.4 的 wrapper 跑不了 21+（Android Studio 自带的
>   JBR 25 会被直接拒绝：`Unsupported class file major version 69`）
> - Android SDK：platform 34 + build-tools 34.0.0
> - **真机。** 这个应用本身就是那个无障碍服务；模拟器能跑，但它几乎什么都干不了

```bash
./gradlew clean :app:assembleDebug     # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                 # 构建并安装到已连接的设备

# 然后在设备上，按这个顺序：
#   1. 开悬浮窗        → 球出现
#   2. 开无障碍        → 它能看、能碰屏幕了
#   3. 点一下那颗球     → 允许麦克风
#   4. 打开 ⚙，填两个你自己的 Key（见下）
```

**仓库里不带任何密钥，而这两个缺失的密钥，报出来的错都不像「缺密钥」。** 两个都填在设备的 ⚙ 里：

- **语音** —— 一个[火山引擎语音服务](https://www.volcengine.com/product/voice-tech)的 API Key，填在
  「语音密钥」。不填，识别和合成会在 WebSocket 握手阶段失败，**表现为连接超时** —— 看着像网络坏了，
  其实是那一栏空着。其余功能照常，只是既听不见也说不出。
- **大脑** —— `brain = local`（默认）下，任何 OpenAI 兼容接口的 API Key。空着，本机大脑根本跑不起来。

这些不用记：球的控制条上的 **✓** 会打开自检清单，直接告诉你缺哪一个，并给出去处理的按钮。第一次
启动也会自己把这份清单弹出来。

**打包装 `assembleDebug`，别用 `assembleRelease`。** release 没有 `signingConfig`，仓库里也没有
keystore，`assembleRelease` 产出的是 `app-release-unsigned.apk`，**装不上**。debug 包用 Android
默认调试证书签名。

#### 遇到问题

装机流程、以及从零配 SDK 的完整说明（含纯命令行 / CI 路径）在本文下面的
[Android SDK 下载与配置](#android-sdk-下载与配置)。权限为什么这么要、拒绝了会怎样 —— 在
**[docs/permissions.zh-CN.md](docs/permissions.zh-CN.md)**。已知限制在 **[SECURITY.md](SECURITY.md)**（英文）；
如果你打算在**你控制不了的网络**上用，先读那一条。

## 核心能力

**1. 身体，不是屏幕。**
设备是他穿着的东西。相机是眼睛，ASR + 离线唤醒词是耳朵，无障碍服务是手；而当有狗接上时，
那是一条**腿**。提示词说得很直接：*"你得到的不是一个下属，是一个身体部位"*，不是遥控的宠物。
身体各不相同，所以他会**去查**，而不是假设。

**2. 大脑可以和身体分离。**
大脑可以跑在设备本机（`brain = local`，接任意 OpenAI 兼容端点），也可以待在别处、通过
WebSocket hub 连到设备。两者之间只是一份普通的工具 schema 契约 —— 任何一半都能被换掉而不用
重写另一半。**这才是"以任意智能硬件为身体"在这里是事实而非愿景的原因。**

**3. 性格，不是主题皮肤。**
十五种情绪（`ui/ball/Mood.kt`）在"他的感受 / 语音管线在干什么 / 手头有什么活儿"之间仲裁；
另有一组待机小动作，**从不连续重复同一个** —— *"重复看起来像动画卡住了，不像性格"*。
滑动那颗球，你换的是"在跟谁说话"：一个形象同时带着 `persona`（怎么说话）和 `voice`（听起来
什么样），而 persona 会被放在系统提示词的**最前面** —— 语气只有放在那儿才能改变模型的写法。

**4. 性格是"表达方式"，永远不是许可证。**
提示词 §11 把人格置于前十条之下：它可以改变措辞、节奏、听起来对这件事什么感觉 —— 但永远不改变
他做什么、愿意做什么、以及告诉用户什么是真的。而**真出了事，面具是要摘下来的**。

**5. 有自己的记忆，也有自己的日程。**
`config/Notebook.kt` 是他记下的"关于这个人"以及"答应过要做的"。写进去的门槛只有一条：
*"下次跟这个人打交道，不知道这件事会不会让我搞错？"* 这本笔记本是**私人的** —— 对应工具是
`localOnly`，在发往 hub 的载荷里被过滤掉，习惯和承诺从不离开设备。上面还有一个真的调度器，
规矩很硬：说出"我会提醒你"的那一刻，任务必须**已经设好并确认过**。

**6. 没人看着的时候，他一样。**
对话安静下来之后，设备会发一个 `(to yourself …)` 的回合 —— Andee 独自复盘刚才的对话：
*"没人在看你，也没人在等你回答。"* 两条规矩：**不许出声**，**不许碰设备**。他去收尾自己许下的
承诺，并且**宁可空手结束，也不为了记而编一条**。

**7. 六十来个工具，且被要求诚实地失败。**
屏幕、输入、通知、短信、通话、通讯录、日历、文件、相机、TTS/ASR，以及那只狗。每一个在动手
**之前**先检查自己的运行时权限，拒绝就返回 `denied("sms")`，而不是绕过去偷偷做。工具返回它
**看到**的，不是它**希望**的。

**8. 没有 Compose，没有 DI 框架，没有协程。**
这是有意的。悬浮窗挂不上 Compose，所以 UI 全是手写 `View`；service 只有一个，所以依赖是手工
构造函数注入；并发是 `Handler` + 独立 `Executor`。三万四千多行，全部自研 —— 没有一行是从别处
拷来的。

## 权限：每一项为什么，以及你拒绝了会怎样

这一节必须有，因为诚实的版本读起来并不舒服：**这个应用申请的权限组合 —— 无障碍、悬浮窗、
通知监听、短信、通话记录、通讯录、已安装应用列表 —— 正是安卓银行木马被检测出来的那套特征。**
任何安全研究者扫一眼 Manifest 都会标记它。这是合理的第一个反应，值得正面回答而不是绕开。

**设计规矩是"按次"，不是"装机时一次给全"。** `device/DeviceCommsController.kt` 在**每一次**
敏感操作之前调 `ensurePermission()`，**被拒绝就优雅降级** —— 返回 `denied("sms")`，这件事到此
为止。它不会在安装时索要全部权限，也不会因为你说了不就让你用不了。这与同类里多数安卓应用的
做法正好相反，也是判断这份代码之前最该先知道的一件事。

有四项确实是非此即彼的，每一项都值得说明白：

| 能力 | 换到什么 | 拒绝后会怎样 |
|---|---|---|
| **悬浮窗**（`SYSTEM_ALERT_WINDOW`） | 球、字幕、设置 —— 全部可见界面 | **什么都看不到。** 唯一真正"不开就完全不能用"的一项 |
| **无障碍服务** | 全部 `screen.*` 工具：点按、输入、滑动、截屏、读 UI 树 | 手没了。球还会出现、狗还能用，但它无法再操作屏幕 |
| **麦克风**（`RECORD_AUDIO`） | 流式 ASR + 唤醒词 | 点球没反应。由 hub 驱动的对话仍然可用 |
| **USB 设备**（按设备授权，不是开关） | 机器狗 | 视作"没接狗"。它不会主动提这件事 |

其余全部是按需申请、可随时收回的：短信、通话记录、通讯录、日历、位置、步数、摄像头、已安装
应用列表、电池优化豁免。拒绝其中任何一个，只是那一项能力消失，其余照常。逐项对应到具体工具的
完整表格在 **[docs/permissions.zh-CN.md](docs/permissions.zh-CN.md)**
（[English](docs/permissions.md)），而每个权限申请弹窗的文案已经写在 `device/PermissionsController.kt` 里。

## 为什么你可以去核对，而不是选择相信

这一类产品里的多数说法 —— 包括那些融资充裕的 —— 都是发布时的主张，没有第三方审计过。而
这个项目的说法更无聊，但**可以核对**，就在你现在能读的代码里：

- **没有任何遥测。** 不是"我们做了匿名化"，也不是"可以在设置里关掉"。这个仓库里根本没有
  数据分析 SDK。
- **笔记本出不了设备。** 这不是一句政策，是一道过滤：`net/ToolSchemas.kt` 在注册之前就把所有
  `localOnly` 工具从发往 hub 的载荷里剔掉了。习惯和承诺在物理上无法被发出去。
- **唤醒词全离线。** 本机 MFCC + DTW 模板匹配（`app/.../wake/`）。存的是特征向量，从不存录音，
  从不联网。
- **本机大脑不需要任何厂商后端。** `brain = local` 接任意 OpenAI 兼容端点是受支持的正式模式，
  不是降级模式。
- **没修好的，是写下来的。** [SECURITY.md](SECURITY.md) 列了五条已知限制，包括调试用 WebSocket
  服务绑在 `0.0.0.0:9008` 且不验证客户端身份、以及保险箱尚未绑定设备解锁。在你控制不了的网络上
  跑之前先读它。

这是一个有意的取舍：**宁可要一个小一点但站得住的说法，也不要一个大到只能靠相信的说法。**

## 三种使用方式

- **在线 —— 不用自己打包、不用自己配令牌。** **[codeflying.net](https://www.codeflying.net)**（中文）·
  **[codeflying.app](https://www.codeflying.app)**（English）。描述你想要的 Andee，它会给你一个
  已经配好令牌的安装包；本地不用编译，也不用先懂 Android。免费额度够上手，用完之后需要购买 token。

- **自己部署。** 克隆、构建、安装 —— 见[快速开始](#快速开始)，以及本文下面完整的
  [装机顺序建议](#装机顺序建议)。接任意 OpenAI 兼容端点即可运行，**不依赖任何私有后端**。

- **换一具身体。** 工具契约就是那道缝。任何说得出这套契约的东西 —— 手机、平板、另一块硬件
  —— 都能承载同一个个体。

## 它长什么样

- **没有常规主界面。** UI 全部是 `WindowManager` 加的 `TYPE_APPLICATION_OVERLAY` 悬浮层，核心是
  那颗球（`ui/FloatingWindowUi`）。
- **唯一的桌面入口是 `ui/SelfCheckActivity`（自检页）。** 它的存在是个教训：以前刻意不放
  LAUNCHER 图标，代价是无障碍没开的时候，用户手里是一台什么都不做的设备，而且没有任何地方能
  告诉他为什么。
- **活儿全在 `ScreenBodyService`（`AccessibilityService`）里。** 它构建整个对象图，并同时启动
  两条网络通路。

另有几个瞬时 Activity（`PermissionRequestActivity`、`ScanActivity`、`LookActivity`、
`HtmlActivity`、`TextInputActivity`），都是为了借系统能力临时上台，不是页面。

## 构建配置

纯 Kotlin，无 Compose，靠 `WindowManager` + `AccessibilityService` 两根柱子撑起来。

| 项 | 值 | 说明 |
|---|---|---|
| Android Gradle Plugin | 8.3.1 | `gradle/libs.versions.toml` 里的 `agp` |
| Kotlin | 1.9.22 | `app/build.gradle` 顶部固定 |
| compileSdk / targetSdk | 34 | Android 14 |
| minSdk | 30 | Android 11 —— 因为要用 `AccessibilityAction.ACTION_IME_ENTER`（API 30+）和 `takeScreenshot`（API 30+） |
| Java / Kotlin JVM target | 1.8 | `sourceCompatibility` 1.8、`jvmTarget = '1.8'` |
| **构建用 JDK** | **17–20** | 见[配 JDK](#配-jdk)，这是最容易卡住的一步 |
| versionCode / versionName | 1 / 1.0 | 还没做发版管理 |
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

## 配 JDK

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

## Android SDK 下载与配置

Gradle 编译时读 `local.properties` 里的 `sdk.dir=` 找 SDK。这个文件是 Android Studio 自动生成
的，**不入版本库**（在 `.gitignore` 里），所以每台机器第一次拉代码都要自己配。

### 路径一：走 Android Studio（推荐，一步到位）

1. 装 [Android Studio](https://developer.android.com/studio) → 首次启动会弹 **Setup Wizard**，
   一路默认下一步，它会自动下 Android SDK、Platform Tools（`adb`）、内置 JDK。
2. 用 Android Studio **打开本仓库根目录** → 它会自己写 `local.properties`，并检测缺什么 SDK
   组件、蓝条提示 "Install missing SDK"，点 Accept 装完就行。
3. SDK Manager（顶栏 → Tools → SDK Manager）确认下列都装了：
   - **SDK Platforms**：Android 14（API 34，compileSdk / targetSdk）；Android 11（API 30，minSdk，装一份方便起模拟器）
   - **SDK Tools**：Android SDK Build-Tools（34.x）、Android SDK Platform-Tools（最新）、Android SDK Command-line Tools（latest）
4. macOS 默认 SDK 路径 `~/Library/Android/sdk`，Windows 默认 `%LOCALAPPDATA%\Android\Sdk`。

### 路径二：纯命令行（不装 Studio）

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

### 配 `local.properties`

Android Studio 会替你写。手动的话，在**仓库根目录**的 `local.properties` 里放这一行：

```
sdk.dir=/Users/<你的用户名>/Library/Android/sdk
```

Linux 通常是 `/home/<user>/Android/Sdk`，Windows 是
`C\:\\Users\\<user>\\AppData\\Local\\Android\\Sdk`（反斜杠要转义）。

也可以不写 `local.properties`，只导 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 环境变量，Gradle 会读
这两个之一。

### 验证

```bash
./gradlew --version           # 看到 JVM 17.x + Gradle 8.4
./gradlew tasks               # 能列出 :app:assembleDebug 就是通的
./gradlew assembleDebug       # 第一次下依赖会久
```

### 常见坑

| 报错 | 原因 | 修 |
|---|---|---|
| `SDK location not found` | `local.properties` 没有 `sdk.dir=`，`ANDROID_HOME` 也没导 | 二选一配上 |
| `Failed to find Build Tools revision 34.0.0` | SDK 里没装对应 build-tools | `sdkmanager "build-tools;34.0.0"` 或在 Studio 的 SDK Manager 里补装 |
| `You have not accepted the license agreements` | 没接受协议 | `sdkmanager --licenses` 全输 y |
| `Unsupported class file major version 69` | JDK 太新（25），Gradle 8.4 不支持 | 换成 JDK 17–20，见[配 JDK](#配-jdk) |
| `Could not resolve com.android.tools.build:gradle:8.3.1` | 网络问题 / 镜像没配 | 用手机热点，或在 `~/.gradle/init.gradle` 配阿里云镜像（本项目的 `settings.gradle` 已把阿里云镜像排在前） |

## 硬件 / 系统要求

- Android 11（API 30）或更高
- 屏幕方向随意，悬浮球会浮在最上层
- 需要麦克风，否则 ASR 用不了
- 需要联网（WiFi / 4G / 5G 都行）—— ASR / TTS 走公网，大脑 hub 走 LAN；用 `local` 模式则只走公网

## 安装

```bash
./gradlew installDebug         # 设备已插 USB 或已连 wifi adb
# 或者产出 APK 后自己 adb install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 权限总览

装完 APK 后需要手动开三个开关，少一个就有对应功能不能用。逐项解释、以及「你拒绝了会怎样」的
完整表格在 **[`docs/permissions.zh-CN.md`](docs/permissions.zh-CN.md)**。

### 一览

| 权限 | 干什么 | 不开会怎样 |
|---|---|---|
| 悬浮窗（`SYSTEM_ALERT_WINDOW`） | 画那颗球 + 字幕 + 设置窗 | **应用起来什么都看不到**，唯一一个「不开就完全不能用」的 |
| 无障碍服务（`Accessibility`） | 点按 / 滑动 / 输入 / 截屏 / dump UI 树 / back·home | 大脑侧所有 `screen.*` 工具全部报错 |
| 麦克风（`RECORD_AUDIO`） | 流式 ASR + 唤醒词「嘿 Andee」 | 点球没反应，ASR 起不来 |
| USB 设备（按设备授权，不是开关） | 机器狗的加密狗 | 第一次调 `dog_*` 时会弹「允许访问该 USB 设备」，不点允许就遥控不了狗 |

`INTERNET` 是普通权限，装完就有，不用管。

还有一批「按需申请」的权限（短信、通话记录、通讯录、日历、位置、步数、摄像头、应用列表、
通知、电池优化豁免、改系统设置）—— 用到哪个功能才要哪个，拒绝了对应功能就降级，其余照常。
完整清单见 `docs/permissions.zh-CN.md`。

### 一、悬浮窗

**为什么要**：整个 UI（球、字幕、设置面板）都是 `WindowManager` 加的
`TYPE_APPLICATION_OVERLAY` 悬浮层，没有 Activity 主界面。

**怎么开**：

1. 设置 → 应用 → Andee → 权限 → 悬浮窗 / 显示在其他应用上层
2. 或系统设置 → 应用与通知 → 特殊权限 → 显示在其他应用上层 → Andee → 开启
3. 首次启动一般会弹提示，点「去设置」最快

**验证**：开完权限重新启动 APK，应该立刻看到那颗球。

### 二、无障碍服务

**为什么要**：所有屏幕操作（`get_screen_element` / `tap_screen_element` / `swipe_by_coordinates` / `type_text` / `submit_input` /
`system_action` / 截屏）都走 `AccessibilityService`。这是非 root 设备上唯一能
做出真实触摸事件、读取任意应用 UI 树、调系统截屏的路径。

Manifest 里声明的能力（`res/xml/accessibility_service_config.xml`）：

- `canPerformGestures` —— 派发触摸手势
- `canRetrieveWindowContent` —— 读整棵 UI 树
- `canTakeScreenshot` —— 系统截屏（API 30+）

**怎么开**：

1. 设置 → 无障碍（辅助功能）→ 已下载的服务 / 已安装的服务 → **Andee**
2. 打开开关 → 系统弹一个大红框警告「此服务可查看你所有操作」，点确认
3. 部分厂商（小米 / 华为 / 荣耀）还会要求再开一个「允许后台弹出界面」或「锁屏显示」，一起打开

**验证**：

- 看 logcat 出现 `Body: ws server started on 0.0.0.0:9008`，说明 `onServiceConnected` 已触发
- 在悬浮球顶部条点一下 `☰`（UI Tree）或 `◉`（Screenshot），字幕栏应反馈成功和文件名

**注意**：大部分厂商 ROM 在每次系统更新、每次强制停止应用之后会**自动关掉**这个权限，需要重开。
这是 ROM 行为，不是应用能阻止的。

### 三、麦克风

**为什么要**：ASR 是流式识别，需要拿 PCM 数据流上行。

**怎么开**：

1. 首次点球触发 ASR 时，系统会弹权限申请，选「仅在使用该应用时允许」或「始终允许」都行
2. 之前拒绝过的话，手动开：设置 → 应用 → Andee → 权限 → 麦克风 → 允许

**验证**：点悬浮球一次，字幕栏出现 "listening…" 或实时 ASR 文本就是通了。

## 唤醒词「嘿 Andee」

不想每次都去点球，可以教它认自己的声音：**⚙ → 唤醒词 → 录入唤醒词**，照提示说 3 遍「嘿
Andee」。之后**亮屏状态下**说这句话，效果和点一下球完全一样。

几件值得先知道的事：

- **全离线。** 识别是本机的 MFCC + DTW 模板匹配（`app/.../wake/`），不联网、不花钱，存的只是
  特征向量，录音本身从不落盘。代价是它认的是**你**说这句话的样子 —— 换个人叫、或语气差太多，
  可能叫不醒；叫不醒就点球，不影响别的。
- **录的时候别刻意念清楚。** 判定阈值是从你那 3 遍之间的差距算出来的：念得过分标准，阈值会收得
  很紧，平时那样叫反而认不出来。
- **只在亮屏时监听。** 息屏、Andee 自己正在说话、以及正在录你说话的时候都会把麦克风让出来 ——
  对话本身的麦克风优先级永远高于唤醒词。
- Android 12 以后，只要麦克风开着，状态栏就有个绿点，这是系统行为，去不掉。没录唤醒词的话监听
  根本不启动，也就不会有绿点。

**验证**：`adb logcat -s Wake:*`，叫一声应该看到 `wake: d=1.52 <= 1.96`；没叫醒但确实说了话会
看到 `no wake: d=… > …` —— 这两个数就是调阈值的依据，差得不多就重录一次。

## 机器狗（USB 加密狗）

机器狗是一个**外设**，不是这台设备的屏幕：它通过一根 USB OTG 线接在本机上的 Arduino Nano
加密狗上。狗端固件一行都不用改，原装遥控器照样能用。

```
平板 App ──USB OTG──> Arduino Nano + NRF24L01 ──2.4G──> 机器狗
```

大脑侧的工具：

| 工具 | 干什么 |
|---|---|
| `dog_move` | 往一个方向动 N 秒，**到时自己停**；默认轮式，`gait=true` 用腿走 |
| `dog_sequence` | 多步连做（转半圈 → 停一下 → 前进 → 退回），后台跑，结束自动停 |
| `dog_stop` | 急刹。用户说「停」时第一个调它 |
| `dog_status` | 链路状态 + 狗在动什么。查不了电量，这条链路是单向的 |

### 接线与第一次使用

1. 把加密狗（Nano + NRF24L01）接好并烧好固件。**加密狗固件与接线图不在本仓库** —— 这里只有
   App 侧的驱动（`com.github.mik3y:usb-serial-for-android`）。请按你手上的固件说明接线。
2. 用 OTG 线插到本机。**加密狗的 3.3V 供电一定要稳** —— NRF24L01 是瞬时电流大户，模块旁边建议
   并一个 10~100µF 电容，否则会出现「命令发了但狗不动」。
3. 第一次调 `dog_*` 时系统弹「允许 Andee 访问该 USB 设备吗？」，点**允许**。不点就等于没插。
4. `dog_status` 里的 `banner` 会显示加密狗开机时说的话：`READY` 表示加密狗和它的射频模块都好；
   如果是 `ERR: NRF24L01 not responding`，那是**加密狗自己的接线 / 供电问题**，不是狗坏了。

### 一个 USB 口，两个用途 —— 调试时要换无线 adb

单 USB-C 口的机器，OTG 和 adb 抢同一个口。要用加密狗就得把调试切到网络：

```bash
adb tcpip 5555                      # 先插着 USB 线执行
adb connect <设备IP>:5555
# 现在可以拔掉 USB 线，把口让给加密狗
adb usb                            # 想切回来时执行
```

### 安全须知（这几条不是客套）

- **原装遥控器必须关机。** 它和加密狗用同一个信道、同一个地址，两边同时发会互相打架。
- **狗有避障，并且会拒绝你**：前方 <20cm 不让前进、<10cm 自动后退。所以「让它往前走它不动」
  通常是前面有东西，不是命令失败 —— 用 `camera_turn` 看一眼再决定。
- **每条动作都有终点**：`dog_move` 到时自停，`dog_sequence` 跑完自停，服务退出时会尽力发停止
  命令。所以狗不会因为某次调用丢了就一直在跑。
- **唯一的安全缺口在链路上**：如果加密狗在狗正在动的时候被拔掉或断电，本机这边发不出停止命令，
  狗会把最后一个动作做完。这是狗端固件的固有设计（它没有失联自动停车），App 补不了。要彻底解决
  得改固件加一个「多久没收到包就停」的看门狗。
- 别在人、宠物、易碎物旁边试长动作。第一次调建议先用 1~2 秒的小动作确认方向对不对。

### 方向对照（以实测为准）

固件注释里足式 `E`/`D` 标的是左转 / 右转，**实测是反的**（E 右转、D 左转）。App 里的映射按实测写：

| 工具参数 | 轮式（默认） | 足式（`gait=true`） |
|---|---|---|
| `forward` | K 前进 | B 前进 |
| `back` | L 后退 | C 后退 |
| `left` | M 左转 | D 左转 |
| `right` | N 右转 | E 右转 |

标定过的时长：轮式转一圈约 **8.7 秒**，足式约 **12 秒**（后者未精确标定）。直线速度没标过，按
秒数估。改标定值要同时改 `ToolSchemas.kt` 里 `dog_move` 的描述 —— 那段文字是大脑唯一读到的
说明书。

## 两种语言，三种中文

仓库里的中文分三类，只有一类需要翻译，判据是「谁读它」。改任何字符串前先看
[`CONTRIBUTING.zh-CN.md`](CONTRIBUTING.zh-CN.md) 里那张表 —— 里面还写着为什么「要和系统文字做匹配」的那
一类**永远不能翻**。

## 装机顺序建议

第一次装，按这个顺序最省事：

1. 装 APK
2. 立刻去「显示在其他应用上层」开悬浮窗 → 回来能看到球了
3. 去「无障碍」→ 开 Andee
4. 点一下球，弹麦克风申请 → 允许
5. 打开球上的 ⚙，填 `hub_url` = `ws://<你 Mac 的 IP>:9100`、`device_name` = 随便起，保存
6. logcat 里应该出现 `hub: registered as body-xxx with N tools`，同时大脑侧日志出现
   `[body_hub] 'body-xxx' registered (…) with N tools`

任一步没对上，回来看本文档对应章节的**验证**。

> 只想要本机大脑的话，第 5 步改成把 `brain` 选成 `local` 并填一个 OpenAI 兼容端点，`hub_url`
> 留空即可 —— 服务端模式是合法模式，不需要 hub。

## 目录结构

```
app/                    Android 应用（Kotlin，无 Compose）
  src/main/java/net/kuafuai/andee/
    ScreenBodyService.kt   无障碍服务 —— 第一个该读的文件
    brain/                 智能体循环 —— LocalBrain，以及 LocalPrompt，
                           后者是角色的行为准则（§1–§10 是活儿，§11 是人格）
    ui/ball/               那张脸 —— Mood（15 种情绪 + 待机小动作）、BallLook（形象即角色）
    net/                   BodyWsServer、BodyWsClient、ToolSchemas（工具定义）
    device/                屏幕控制、权限、保险箱、机器狗
    ui/                    悬浮层：球、字幕、设置、自检页
    i18n/AppLocale.kt      悬浮层怎么拿到自己的字符串
  src/main/res/values{,-en}/strings.xml   两张面向用户的字符串表
docs/permissions.zh-CN.md     为什么它要这些权限
reports/                设计实验室与调研笔记（是仓库的一部分，不是草稿）
tools/                  设备侧小工具、球脸验收 runner、许可证审计
CLAUDE.md               给 AI 编码助手看的架构说明（英文）
```

## 参与贡献

- **代码** —— 先读 [CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md)。里面有代码风格，以及这个仓库里**三种
  中文只有一种要翻译**的规则。改人格或情绪有单独一节，那不是普通文案。
- **报 bug** —— 请务必带上**设备型号、Android 版本、ROM 和 logcat**。这个应用和 Android 打交道的
  层次上各家 ROM 是有分歧的：AOSP 上能用的调用在 MagicOS 上可能静默失败。缺这些，多数报告没法
  复现。
- **形象与人设** —— 新形象只写语气，不写能力。能力属于行为准则，不属于某个角色。
- **文档与翻译** —— 欢迎。中文 README 是装机那份，必须保持准确。

### 贡献者

<!-- 项目有公开主页后换成真实的贡献者图：
     <img alt="Contributors" src="https://contrib.rocks/image?repo=kuafuai/andee" /> -->

## 社区与联系

<!-- 有公开主页后把 Issue 跟踪 / 讨论区两行补上，不适用的删掉。
     不要发布会 404 的链接 —— 死掉的渠道比没有渠道更糟。 -->

| 渠道 | 用来做什么 |
|---|---|
| Issue 跟踪 | 可复现的 bug、工程事项（**请带上设备型号 / Android 版本 / ROM / logcat**） |
| 讨论区 / 问答 | 装机求助、想法、"这个能不能做" |
| 安全 | 漏洞 —— 走私密渠道，永远不要开公开 issue。见 [SECURITY.md](SECURITY.md) |
| 产品 / 在线 | 不想自己打包就想试 Andee —— [codeflying.net](https://www.codeflying.net) · [codeflying.app](https://www.codeflying.app) |

## 安全披露

安全问题**请不要开公开 issue**。私密上报渠道和处理流程在 **[SECURITY.md](SECURITY.md)**。

那份文件同时列出了**部署前必须知道的已知限制**，其中一条在任何你控制不了的网络上都成立：调试
用的 WebSocket 服务绑在 `0.0.0.0:9008` 且不验证客户端身份。本应用申请的权限组合与安卓银行木马
的检测特征高度重合 —— [docs/permissions.zh-CN.md](docs/permissions.zh-CN.md) 把每一条对应到一个具体工具，
而 [SECURITY.md](SECURITY.md) 对"还没修的"是诚实的。

## 免责声明与可接受使用

两份文件，都是**使用条件**，不是客套。中文版在这里，英文版见各自的对照文件：

- **[DISCLAIMER.zh-CN.md](DISCLAIMER.zh-CN.md)**（[English](DISCLAIMER.md)）—— 这是什么性质的
  软件（实验性、无 SLA、无担保）、**你的数据到底去了哪里**、它实际能做到什么、以及出事后由谁承担。
- **[docs/acceptable-use.zh-CN.md](docs/acceptable-use.zh-CN.md)**（[English](docs/acceptable-use.md)）
  —— 明确禁止的用途。违反即超出许可范围，许可**自动终止**。

就算其余都不看，这四条也请看完：

- **他能花钱。** 项目里没有支付工具，也不需要 —— 他直接点。唯一挡住一笔转账的是 Android 自己的
  `FLAG_SECURE`，**本项目对这条路径没有任何技术围栏**。任何涉及钱的操作都要人在场；并且请记住
  **"是 AI 自己做的"不是抗辩理由**，因为它确实不是。
- **他发出去的东西是你的。** 消息、帖子、通话、订单，署名都是你。
- **你的数据会离开设备。** 本仓库没有任何遥测，但语音会走火山引擎，对话连同**全部工具返回结果**
  会走到你配置的那个大脑端点。"本机模式"换的是目的地，不是这个事实。
- **他能被屏幕上的字操纵。** 任何能让你打开一个页面的人，都可能影响到他。

## Star History

<!-- 项目公开托管到 GitHub 后启用：
     <a href="https://star-history.com/#kuafuai/andee&Date">
       <img alt="Star History Chart" src="https://api.star-history.com/svg?repos=kuafuai/andee&type=Date" />
     </a> -->

## 许可

[Apache-2.0 **加附加条件**](LICENSE) —— 禁止多租户 SaaS、禁止去 LOGO 白标。GitHub 会把它显示成
`NOASSERTION`，这是预期的，不是配置错误。

随包分发的第三方组件清单在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。再分发之前请先看：
**ML Kit 扫码 SDK 和 `play-services-*` 不是开源许可**，而且扫码模型是运行时从 Google 拉的。

改了什么、以及版本号策略见 [CHANGELOG.md](CHANGELOG.md) —— 目前还没有打过 tag，版本号也不能
用来辨认是哪次构建。

## 相关文档

- [docs/permissions.zh-CN.md](docs/permissions.zh-CN.md)（[English](docs/permissions.md)）—— 权限为什么这么要
- [DISCLAIMER.zh-CN.md](DISCLAIMER.zh-CN.md) —— 免责声明：数据去哪、能做什么、出事谁担
- [docs/acceptable-use.zh-CN.md](docs/acceptable-use.zh-CN.md) —— 可接受使用政策（禁止用途）
- [SECURITY.md](SECURITY.md)（英文）—— 威胁模型、已知限制、漏洞上报
- [CONTRIBUTING.zh-CN.md](CONTRIBUTING.zh-CN.md)（[English](CONTRIBUTING.md)）—— 贡献指南（含改人格的规范）
- [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) —— 依赖许可证清单
- [CHANGELOG.md](CHANGELOG.md) —— 变更记录
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md)（英文）—— 社区行为准则
- [CLAUDE.md](CLAUDE.md) —— 架构导览（英文）
