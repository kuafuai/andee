<p align="center">
  <a href="./permissions.md"><img alt="English" src="https://img.shields.io/badge/English-d9d9d9"></a>
  <a href="./permissions.zh-CN.md"><img alt="简体中文" src="https://img.shields.io/badge/简体中文-d9d9d9"></a>
</p>

# 权限说明

这一页解释 Andee 为什么要那些权限、每个权限对应你能得到的什么功能、以及你拒绝之后会发生什么。

写这么细的原因很直接：**Andee 申请的权限组合，和安卓银行木马的检测特征高度重合** —— 无障碍 + 悬浮窗 + 通知监听 + 短信 + 通话记录 + 通讯录 + 应用列表，任何一个安全研究者扫一眼 Manifest 都会把它标红。这是合理的警觉，不是误判。

所以这一页要做的事只有一件：**把"看起来一样"和"实际不一样"分别讲清楚，并且每一条都能在代码里核对。**

---

## 先看区别在哪

| | 典型国产 App / 恶意软件 | Andee |
|---|---|---|
| 要权限的时机 | 启动时一次性全要 | **按次申请**：用到哪个功能才要哪个 |
| 你拒绝之后 | 不让用 / 反复弹窗 | **功能降级**，其余照常用，并且告诉你为什么没做成 |
| 权限和功能的对应 | 不解释 | 见下表，每项都能对应到一个具体工具 |
| 敏感数据去了哪 | 不说明 | 凭据加密存在本机（`config/Vault.kt`），模型读不到；5 个私人笔记工具**根本不外发**（`ToolSchemas.forHub()` 过滤） |
| 凭据怎么用 | — | 模型只能让设备"去填"，**它自己永远拿不到密码原文**（`fill_secret`） |

代码里能核对这件事的两处：

- `device/PermissionsController.kt` —— 权限清单 + 每项的人话说明 + 引导跳转
- `device/DeviceCommsController.kt` —— 每个工具在动手**之前**先 `ensurePermission()`，没授权就直接返回 `denied("sms")` 这样的结果，不会崩、也不会偷偷换个方式做

---

## 权限总表

「用户得到什么」一列是应用里实际显示的原文（`res/values/strings.xml` 的 `dev_perm_feature_*`）。

| 权限 | 用户得到什么 | 对应工具 | 你拒绝了会怎样 |
|---|---|---|---|
| 悬浮窗<br>`SYSTEM_ALERT_WINDOW` | 悬浮窗(我的脸) | 球、字幕、设置面板本身 | **应用起来什么都看不到。** 这是唯一一个"不开就完全不能用"的 |
| 无障碍服务<br>`BIND_ACCESSIBILITY_SERVICE` | 看屏幕、点界面、打字 | `get_screen_element` / `tap_screen_element` / `swipe_by_coordinates` / `type_text` / `submit_input` / `take_screenshot` 等全部 `screen.*` | **所有屏幕操作报错。** 这是核心能力，其它权限都是围着它转的 |
| 麦克风<br>`RECORD_AUDIO` | 语音说话、唤醒词「嘿 Andee」 | 流式 ASR + 本机唤醒词匹配 | 点球没反应；唤醒词监听不启动（顺带：Android 12+ 的绿点也不会出现） |
| 通知监听<br>`BIND_NOTIFICATION_LISTENER_SERVICE` | 通知感知(微信来消息我知道) | `get_notifications` | 来消息不会主动知道；其余功能不受影响 |
| 位置<br>`ACCESS_FINE_LOCATION` | 位置(在哪/导航) | `get_location` | 问"我在哪"答不出来 |
| 运动步数<br>`ACTIVITY_RECOGNITION` | 运动步数 | `get_step_count` | 问步数答不出来 |
| 通讯录<br>`READ_CONTACTS` | 通讯录(打电话找人) | `search_contacts` | 说"打电话给老王"找不到人 |
| 拨号<br>`CALL_PHONE` | 直接拨号 | `dial` | 能找到人但拨不出去 |
| 通话记录<br>`READ_CALL_LOG` | 通话记录 | `get_call_log` | 查不了最近谁打的电话 |
| 读短信<br>`READ_SMS` | 读短信(验证码) | `read_sms` | 读不了验证码 |
| 发短信<br>`SEND_SMS` | 发短信 | `send_sms` | 发不了 |
| 读日历<br>`READ_CALENDAR` | 日历(日程提醒) | `get_calendar` | 看不了今天的日程 |
| 写日历<br>`WRITE_CALENDAR` | 写日历(记日程) | `add_calendar_event` | 记不了日程 |
| 摄像头<br>`CAMERA` | 看（拍照识物 / 扫码） | `camera_turn` / `scan_code` | 这两条用不了 |
| 应用列表<br>`QUERY_ALL_PACKAGES` | 知道装了哪些应用 | `list_installed_apps` / `launch_app` | 打不开指定应用 |
| 通知栏<br>`POST_NOTIFICATIONS` | 到点提醒你 | 待办到期的提醒 | 到点不提醒，**但待办不会丢** |
| 开机自启<br>`RECEIVE_BOOT_COMPLETED` | 重启后待办还在 | 重建 `AlarmManager` 闹钟 | 重启后待办不再提醒 |
| 电池优化豁免<br>`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 到点提醒准时 | 待办定时 | 息屏状态下提醒会迟到 |
| 改系统设置<br>`WRITE_SECURE_SETTINGS` | 换输入法（打字用） | `type_text` 前置步骤 | **`type_text` 直接失败**，并明确告诉你失败原因，不会假装成功 |

> `WRITE_SECURE_SETTINGS` 是特殊的一个：它**装完不生效，必须用 adb 手动授予**：
>
> ```bash
> adb shell pm grant net.kuafuai.andee android.permission.WRITE_SECURE_SETTINGS
> ```
>
> 这是系统限制，不是应用的选择。不授予也能用，只是打不了字。

---

## 装机必须手动开的三个开关

这三个不在应用内，Android 不允许应用自己翻。少一个就有对应功能不能用：

1. **悬浮窗** —— 设置 → 应用 → Andee → 权限 → 显示在其他应用上层
2. **无障碍服务** —— 设置 → 无障碍 → 已安装的服务 → Andee（会弹一个大红框警告，这是系统标准流程）
3. **麦克风** —— 首次点球时系统会自己弹

> 部分厂商 ROM（小米 / 华为 / 荣耀）每次系统更新或强制停止应用后会**自动关掉无障碍**，需要重开。这是 ROM 行为。

---

## 凭据是怎么保管的（这是最该被问的一条）

Andee 有一个保险箱（`config/Vault.kt`），用来存你自己填进去的账号密码。设计上有三条硬规则：

1. **加密落盘。** AES/GCM，密钥由 Android KeyStore 生成并保管，**不出设备**。换台机器即使拿到密文也解不开（代码里有对应的兜底："vault: decrypt failed — entries are unreadable on this install"）。
2. **模型永远拿不到密码原文。** `get_vault` 返回的条目里，标记为 secret 的字段不下发；模型只能用 `fill_secret`，由**设备自己**把密码打进输入框。也就是说密码不经过模型、不进模型的上下文。
3. **界面上打码。** 保险箱页面显示的是 `Vault.mask()` 之后的结果。

**它不防什么（要说清楚）：** 密钥没有绑定设备解锁（没用 `setUserAuthenticationRequired`）。所以**能解锁你手机的人，就能通过界面看到保险箱内容**。它防的是"数据被提取走"和"密码被模型读到"，不防"捡到你手机的人"。

---

## 数据会去哪（"你们会不会收集我的数据"）

**结论：本项目收不到你的任何数据；但"数据不出设备"是错的。** 这两句都要说清楚，只讲前一句等于误导。

**这个仓库里没有任何遥测。** 没有统计 SDK、没有崩溃上报、没有埋点、没有设备指纹 —— `app/build.gradle` 与 `app/src/main/` 里 `firebase` / `analytics` / `sentry` / `crashlytics` 全部为零。你装了它、用了它，作者不知道。

**但数据按设计会离开设备：**

| 什么数据 | 去哪 | 默认值 |
|---|---|---|
| 语音识别（你说的话）、语音合成（他说的） | **火山引擎（字节跳动）云端** | `openspeech.bytedance.com` |
| 对话内容，**以及全部工具返回结果** | 你配置的"大脑" | `brain=hub`；本机模式默认 `api.deepseek.com` |
| 私人笔记本（`config/Notebook.kt`：记住的你、答应过的事） | **只在本机** | 是 `localOnly`，不进 hub 载荷 |

第二行要说透：**工具返回结果就是对话的一部分。** 他读到的短信正文、通讯录、通话记录、通知内容、定位，以及他对屏幕的"理解"，都会随对话发到你配的那个端点。

**所以"本机模式"不等于"本地处理"** —— 它换的是大脑的目的地，不是"数据留在设备上"这件事。真要数据不出门，你得自己把 `asr_endpoint` / `tts_endpoint` / `llm_base_url` / `hub_url` 全指向你自己控制的机器。

完整的责任划分与免责条款见 **[../DISCLAIMER.zh-CN.md](../DISCLAIMER.zh-CN.md)**；禁止用途见 **[acceptable-use.zh-CN.md](acceptable-use.zh-CN.md)**。

---

## 已知风险：调试用的 WebSocket 端口对局域网开放

**这一条请务必读完再用。**

应用启动时会开一个 WebSocket 服务，监听 `0.0.0.0:9008`（`net/BodyWsServer.kt`，端口是 `ScreenBodyService.WS_PORT` 常量）。

**问题在于：它不验证任何身份。** `onOpen()` 把每一条进来的连接都当作合法的控制端收下。任何能访问到这台设备 IP 的主机，都可以连上来发命令 —— 点屏幕、打字、读界面、读通知、读短信和通话记录，还能让它把保险箱里的密码填进任意应用的输入框。

**什么时候会被打：** 只要无障碍服务开着，而设备又在一个你控制不了的网络上 —— 咖啡厅、酒店、机场、会议室、公司大内网 —— 就存在这个风险。

**现在能做的缓解：**
- 只在你信得过的网络上用，或者干脆让路由器/防火墙挡住这个端口
- 用 `adb forward` 驱动设备，别让它从网络上可达
- 把开了无障碍服务的设备，当成不敢随手放在陌生网络里的设备

**彻底修法**（还没做）：连接时校验一个共享密钥，并把监听地址和端口做成可配置、默认只听回环。这件事记为待办；在它落地之前，上面这条限制一直有效。

> 相关但轻一些的第二条：应用连大脑 hub 那条链路（`net/BodyWsClient.kt`）同样没有鉴权，且是明文 `ws://`。所以 `hub_url` 只填你自己控制的地址。**用 `local` 大脑模式可以完全避开这条链路** —— 模型请求由应用自己走 HTTPS 发出，设备上不存在对外的命令通道。

---

## 为什么不做"一次全要"

因为那才是木马的做法。

Andee 的做法是：**你要什么功能，才要什么权限；你不给的，它就降级，并且告诉你降级了。** 这一条能在代码里逐条核对 —— 每个工具在动手前都过一遍 `ensurePermission()`，拒绝就返回明确的失败原因，而不是换个更隐蔽的路子把事办了。

如果你想看得更深，[../SECURITY.md](../SECURITY.md)（英文）里有完整的安全模型和漏洞上报渠道。

---

**其他语言：** [English](permissions.md)
