# Phase4 MiniApp 系统能力实施边界（Android / iOS）

审计日期：2026-09-09。范围限于 MiniApp 主运行链路与系统能力：bridge 方法/版本、权限声明与 grant、全局 settings、sandbox、runner、native owner、持久化和现有测试。没有运行测试、构建或改产品代码。

版本证据：Android 当前工作树 HEAD 为 `ab984f6`（有 39 项 WIP，保留）；iOS HEAD 为 `1023e8a`（clean）。以下“缺失”只表示在对应生产代码路径中没有找到消费者或实现，不把文档/命名当作实现。

## 结论

1. iOS 已经有一套可发现、可授权、可审计、带生命周期清理的系统能力契约；Android 当前 MiniApp V3 仍停在 storage/host/AI/event/sensor/location/clipboard 这一组能力，系统能力七组方法均没有 Android bridge 消费者。
2. iOS 的系统能力并非只写在 prompt：`IOSMiniAppBridgeRuntime` 做 method registry、系统总开关、声明/全局开关/grant 检查和审计，`IOSMiniAppDeviceCapabilities` 执行原生调用；Android 的 `MiniAppSystemBridge` 目前只有剪贴板读取和定位。
3. Android Manifest 中已有 `CAMERA`、位置、`RECORD_AUDIO` 等宿主权限（`app/src/main/AndroidManifest.xml:5-36`），但它们没有变成 MiniApp 权限，也不能作为系统能力已接线的证据。Android MiniApp 权限枚举、aliases、settings 映射和 grant 表中都没有 haptics/device/screen/speech/share/openURL。
4. 最小追齐路径是先建立 Android 与 iOS 同名的能力发现/权限契约，再接 Android native owner。现有 Android Room grant/audit、DataStore settings、WebView sandbox 可以复用，不需要另造一套授权持久化。

## 一页覆盖表

状态含义：**已实现**=生产代码有完整入口、消费者和 owner；**未接线**=有宿主基础能力或文字，但没有 MiniApp bridge 消费者；**疑似**=只能证明有依赖/通用宿主入口，不能证明 MiniApp 可调用。iOS 证据来自 iOS 生产路径，Android 证据来自 Android 生产路径。

| 能力 | iOS 实际契约与 owner | Android 实际契约与 owner | 权限 / 设置 / 持久化 | 判定 |
|---|---|---|---|---|
| 现有 V3：storage、toast、theme、network、search、clipboard、host、AI、sharedStore、eventBus、launch、sensor、location | `IOSMiniAppBridgeRuntime.swift:732-747` 组装 methods；`IOSMiniAppModels.swift:3-27` 定义权限；runtime dispatch/require 在 `IOSMiniAppBridgeRuntime.swift:310-337,766-806` | `MiniAppBridge.kt:135-383` 逐项处理；`MiniAppModels.kt:9-30,106-160` 定义 V2/V3 权限；`miniapp_bridge.js:1-163` 暴露 JS API | Android `MiniAppSandbox.kt:16-53` 做 enabled/声明/全局 setting/grant 检查；grant/audit 在 `MiniAppRepository.kt:196-252`、`MiniAppDAO.kt:97-185`；iOS 对应 repository/grant 在 `IOSMiniAppRepository.swift:80-130,439-470` | **双方已有对应生产接线**（本表不评价细节体验） |
| bridge 版本与能力发现 | `app.info` 在 `IOSMiniAppBridgeRuntime.swift:663-681`，含 `platform`、`bridgeVersion = "0.3-system-capabilities"`、app/version/grants；`app.capabilities` 在 `:732-763` 返回 methods、system 开关及每项 declared/enabled/decision | `MiniAppBridge.kt:135-385` 的 `when` 没有 `app.info`/`app.capabilities`；`MiniAppBridgeResponse` 只有 id/ok/data/error/errorCode（`MiniAppModels.kt:77-104`）；JS runtime 无 discovery wrapper（`miniapp_bridge.js:1-163`） | Android 没有 bridgeVersion 或系统能力 discovery persistence/owner | **Android 未接线** |
| `haptics.impact` / `.notification` / `.selection` | registry 与 `.haptics` 映射：`IOSMiniAppBridgeRuntime.swift:211-219`；原生参数校验、前台检查、硬件降级/限速：`IOSMiniAppDeviceCapabilities.swift:115-146,397-456`；JS wrapper：`MiniAppRunnerWebView.swift:276-283` | `MiniAppBridge.kt:135-385` 无方法；`MiniAppV3Runtime.kt:162-188` 的 system owner 也无振动；`MiniAppModels.kt:106-160` 无权限；`miniapp_bridge.js:1-163` 无 wrapper | Android Manifest 未声明 `VIBRATE`（`AndroidManifest.xml:5-36`）；现有 grant 表不能存不存在的枚举 | **Android 未接线** |
| `device.getInfo` / `device.getBattery` | registry `.device`：`IOSMiniAppBridgeRuntime.swift:211-216`；实现返回系统/可访问性/低电量模式及电池状态：`IOSMiniAppDeviceCapabilities.swift:148-152,458-523`；wrapper：`MiniAppRunnerWebView.swift:284-287` | 无 bridge case、无 JS wrapper、无 MiniApp permission。宿主是否有电池/设备读取代码不能替代 MiniApp consumer | iOS 仅受系统总开关 + `.device` grant；Android 无对应全局 setting/permission | **Android 未接线** |
| `qrcode.generate` | runtime method list 单独加入 QR（无 `.qrcode` permission）：`IOSMiniAppBridgeRuntime.swift:219,732-747`；参数/PNG dataURL/size 实现：`IOSMiniAppDeviceCapabilities.swift:217-225,672-720`；wrapper：`MiniAppRunnerWebView.swift:300-304`；prompt 明确 size 128–1024、文本 ≤1024 UTF-8：`IOSMiniAppModels.swift:365-367` | 无 bridge case/JS wrapper/权限/setting。`app/build.gradle.kts:678` 的 quickie 依赖注释只能证明宿主有过 QR 相关依赖线索，不能证明 MiniApp QR 生成已实现 | iOS QR 不需 per-app permission，但仍受 system capability 总开关（`dispatchSystem` `:684-690`）；Android 没有对应 gate | **Android 未接线** |
| `screen.getBrightness` / `setBrightness` / `setKeepAwake` | registry `.screen`：`IOSMiniAppBridgeRuntime.swift:214`；实现保存原值、lease、前台窗口和退出/后台恢复：`IOSMiniAppDeviceCapabilities.swift:154-172,232-250,335-395`；wrapper：`MiniAppRunnerWebView.swift:288-292` | 无 bridge/JS/permission/setting。Runner 只有 WebView load/destroy：`MiniAppRunnerPage.kt:293-411,413-419`，没有亮度 lease 或 keep-awake 清理 | Android Activity `RouteActivity` 及窗口不是 MiniApp grant；不能直接把宿主窗口控制暴露给 HTML | **Android 未接线** |
| `speech.getVoices` / `speak` / `stop` / `pause` / `resume` | registry `.speech`：`IOSMiniAppBridgeRuntime.swift:215-216`；AVSpeech owner、文本/参数限制、控制与关闭：`IOSMiniAppDeviceCapabilities.swift:174-200,232-250,525-560`；wrapper：`MiniAppRunnerWebView.swift:293-299` | `rg` 生产路径未见 SpeechRecognizer/TextToSpeech 的 MiniApp consumer；`MiniAppBridge.kt:135-385`、`MiniAppV3Runtime.kt:162-188`、`miniapp_bridge.js:1-163` 均无 speech。Manifest 的 `RECORD_AUDIO`（`AndroidManifest.xml:34`）是录音宿主权限，不是 TTS/语音 MiniApp API | iOS `.speech` 走系统总开关 + grant；Android 无权限/setting/owner。不要把 Live 屏幕或录音工具当作语音 bridge | **Android 未接线** |
| `share` | registry `.share`：`IOSMiniAppBridgeRuntime.swift:217`；系统 share sheet、busy/cancel/foreground、`{completed}`：`IOSMiniAppDeviceCapabilities.swift:202-205,562-670`；wrapper：`MiniAppRunnerWebView.swift:300-304` | 无 MiniApp `share` case/wrapper/permission。Android `RouteActivity` 的 `SEND`/`SEND_MULTIPLE` intent filter（`AndroidManifest.xml:125-138`）是外部分享进入宿主，不是 MiniApp 发起系统分享 | Android 不能以已有 inbound intent 充当 outbound share owner；需独立确认/系统 chooser | **Android 未接线** |
| `openURL` | registry `.openURL`：`IOSMiniAppBridgeRuntime.swift:217`；先验证公开 HTTPS/mailto/tel、拒绝凭证/私网/本地/控制字符，再每次确认并打开：`IOSMiniAppBridgeRuntime.swift:691-705`、`IOSMiniAppDeviceCapabilities.swift:207-215,254-329`；wrapper：`MiniAppRunnerWebView.swift:300-304` | `MiniAppRunnerPage.kt:311-335` 对 WebView 所有导航直接 `true` 阻止，`MiniAppBridge.kt:135-385` 无 `openURL`；Manifest 的 OAuth VIEW deep link（`AndroidManifest.xml:147-157`）是宿主回调，不是 MiniApp openURL | Android 需要显式 URL 校验、确认、审计和取消/失败语义；不可复用 WebView 导航绕过 | **Android 未接线** |

## 代码边界与数据接线

### Android 当前链路

```text
MiniAppRunnerPage (Compose/WebView)
  -> MiniAppShell.inject + assets/miniapp/miniapp_bridge.js
  -> @JavascriptInterface("AmberNative") MiniAppBridge.handle
  -> MiniAppSandbox.require
  -> MiniAppRepository (Room grants/audit/app/version/shared data)
  -> MiniAppV3Runtime.MiniAppSystemBridge (现在只有 clipboard/location)
```

- Runner 读取 `permissionsJson`、注入 bridge script 和 session token：`app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppRunnerPage.kt:263-277`；构造 sandbox、bridge 和系统 owner：`:321-400`。WebView 禁止 DOM storage/file/content、混合内容、多窗口，导航全拦截，HTTPS 图片另过 externalImages sandbox：`:293-335`。
- Shell 进一步禁用 XHR/WebSocket/EventSource/localStorage/sessionStorage/indexedDB/geolocation/mediaDevices/clipboard，并设置 CSP：`app/src/main/java/app/amber/feature/miniapp/MiniAppShell.kt:6-69`。
- `MiniAppBridge.handle` 的错误会返回结构化 `errorCode`（`permission_denied`/`bridge_error` 等）：`app/src/main/java/app/amber/feature/miniapp/bridge/MiniAppBridge.kt:122-131`；unknown method 当前只是 `IllegalArgumentException`：`:385`。bridge 关闭时确认流程返回 `runner_closed`：`:415-420`。
- Android durable owner 已足够承载新增 grant：Room 的 `MiniAppGrantEntity`/audit/version/shared data 与 DAO 在 `app/src/main/java/app/amber/agent/data/db/entity/MiniAppEntity.kt:44-106`、`app/src/main/java/app/amber/agent/data/db/dao/MiniAppDAO.kt:97-185`；repository 写入/读 decision/audit 在 `app/src/main/java/app/amber/feature/miniapp/MiniAppRepository.kt:196-252`。
- 全局 MiniApp settings 由 `AgentRuntimeSetting.miniApp` 持久化：`core/settings/src/main/kotlin/app/amber/core/settings/PreferencesStore.kt:175-228`；现有字段到 `clipboardReadEnabled`，没有 `systemCapabilitiesEnabled` 或七组系统能力字段。设置页入口和 Advanced 文案虽写“设备能力”，实际控件只有 sharedStore/eventBus/launch/sensor/location/clipboard read：`app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppSettingsPage.kt:81-110,193-278`。
- 当前 native owner 仅为：`app/src/main/java/app/amber/feature/miniapp/MiniAppV3Runtime.kt:162-188` 的 `MiniAppSystemBridge.readClipboard()` 与 `currentLocation()`。定位再检查 Android coarse/fine runtime permission，但系统能力没有类似层。
- Android Manifest 的权限是整个 app 的宿主边界：`app/src/main/AndroidManifest.xml:5-36`；MiniApp 本身没有 Android manifest/permission declaration 文件，声明来自 app record `permissionsJson`，受 `MiniAppV3Permissions` 交集约束：`app/src/main/java/app/amber/feature/miniapp/MiniAppSandbox.kt:5-30`。

### iOS 当前链路

```text
MiniAppRunnerView (SwiftUI)
  -> MiniAppRunnerWebView (WKWebView + injected Amber JS)
  -> MiniAppBridge (WKScriptMessageHandler/document guard)
  -> IOSMiniAppBridgeRuntime (registry/policy/require/grant/audit)
  -> IOSMiniAppDeviceCapabilities (@MainActor, per runner)
  -> IOSMiniAppRepository (miniapps/miniapps.json)
```

- iOS production runner 为 `MiniAppRunnerWebView`：`iosApp/iosApp/MiniAppRunnerWebView.swift:74-181`。它为每个 runner 创建 `IOSMiniAppDeviceCapabilities` 并把其 `dispatch` 注入 runtime：`:121-181`；系统 API 的 JS wrapper 在 `:276-304`。
- `IOSMiniAppBridge` 只接受可信主文档/主 frame、丢弃旧 document 请求并向 JS 回传 result/error：`iosApp/iosApp/MiniAppBridge.swift:104-230`。runtime 关闭会取消任务/订阅：`iosApp/iosApp/IOSMiniAppBridgeRuntime.swift:633-661`。
- `IOSMiniAppBridgeRuntime.systemMethodPermissions` 是单一 method registry：`iosApp/iosApp/IOSMiniAppBridgeRuntime.swift:211-219`；system dispatch 先检查 handler、系统总开关，再检查 declared/setting/grant；openURL 额外确认，调用前后检查 cancellation/closed，并审计动作标签：`:684-712`。
- iOS `app.capabilities` 同时暴露可调用 methods、`systemCapabilitiesEnabled` 及每项 permission 的 declared/enabled/decision：`iosApp/iosApp/IOSMiniAppBridgeRuntime.swift:732-763`。`require` 在 `:766-806` 对 grant 未决时弹确认、检查 app/version/hash 没被改动后再写入。
- Native owner 是 per-runner `@MainActor IOSMiniAppDeviceCapabilities`：`iosApp/iosApp/IOSMiniAppDeviceCapabilities.swift:41-67`；监听前后台、`close/suspend` 清 speech、恢复亮度和 keep-awake：`:69-107,232-250`。share/openURL/QR 也都在这个 owner 内完成：`:202-225`。
- iOS 系统总开关是 `@AppStorage("app.amber.ios.miniApp.systemCapabilitiesEnabled")`：`iosApp/iosApp/MiniAppSettingsView.swift:3-8,98-106`；运行页会在关闭时显示警告、仍展示每项授权菜单：`iosApp/iosApp/MiniAppRunnerView.swift:354-430`。它不走 KMP MiniAppSetting 更新，regular settings 更新在 `iosApp/iosApp/IOSSharedSettingsStore.swift:1335-1381`。
- iOS app/version/grant/audit/shared data 的 durable owner 是 `IOSMiniAppRepository`，状态编码到 `Documents/miniapps/miniapps.json`：`iosApp/iosApp/IOSMiniAppRepository.swift:80-130`；grant/audit 写入：`:439-470`。system grant 与审计不放在 UserDefaults；UserDefaults 只存系统总开关。

## 缺口的最小可复用路径

### W10：协议、发现与基础系统能力

1. **先固定同名契约。** Android `MiniAppV3Permissions`/`MiniAppPermission`/aliases（`MiniAppModels.kt:9-30,33-53,106-160`）增加 `haptics/device/screen/speech/share/openURL`；`qrcode.generate` 保持无 per-app permission，和 iOS 的 `systemMethods` 规则一致。Android `MiniAppOutputParser.kt:9-59` 与生成 prompt 必须明确这些方法、参数范围和 Promise rejection 语义。不要把 `RECORD_AUDIO` 推导为 speech 权限。
2. **补能力发现。** 在 Android bridge 增加与 iOS 同名的 `app.info`/`app.capabilities`（并在 JS 暴露 `Amber.getAppInfo/getCapabilities`），至少返回 `platform`、明确 bridgeVersion、methods、system toggle 和每项 declared/enabled/decision。只有实际方法全部接入后才把版本标为 `0.3-system-capabilities`；此前可返回显式较低版本/能力集，不能伪装成 iOS parity。
3. **复用现有授权持久化。** `MiniAppGrantEntity`/DAO/repository 已能存任意字符串 permission；新增 enum 后沿用 `MiniAppSandbox.require` 的顺序：MiniApp enabled → declaration → global system setting → per-app grant。新增 `MiniAppSetting.systemCapabilitiesEnabled`，在 Android 设置页 Advanced 放一个系统交互总开关，关闭时保留 grant 但所有 system method 失败并给可行动错误。
4. **基础 native owner。** W10 新增一个独立的 Android per-runner owner（建议新文件 `MiniAppAndroidDeviceCapabilities.kt`，由 bridge 通过小接口调用），实现：`Vibrator`/`VibrationEffect`（硬件不支持时明确失败或降级）、`Build`/`Locale`/`PowerManager`/`BatteryManager`、QR 纯本地编码。先确认当前工程已有 QR encoder 能否直接复用；`quickie` 依赖线索不能直接当作生成器契约，也不应为此阶段新增依赖。
5. **W10 验收。** `getCapabilities` 的 methods/permissions 与实际可调用集合相等；haptics 参数/前台/硬件错误可捕获；device info/battery 在低电量/无电池场景返回诚实值；QR 在 128/1024 边界和 1024 UTF-8 字节边界生成可扫描 dataURL，超限稳定 rejection；关闭总开关、未声明、拒绝 grant 三种错误可区分。

### W11：页面资源、语音、分享与外链

1. **屏幕。** 使用 runner 对应 Activity/Window 做亮度 lease，保存原值；`setKeepAwake` 只影响当前 runner 的窗口/生命周期，退出、重载、后台时恢复。不要把全局 `RouteActivity` 的窗口状态泄漏到下一个 MiniApp。
2. **语音。** 使用 per-runner `TextToSpeech` owner；`getVoices/speak/stop/pause/resume` 要求主线程、前台检查、文本/参数上限、初始化失败和关闭时 `shutdown`。Android `RECORD_AUDIO` 不参与 TTS；没有找到 SpeechRecognizer/TextToSpeech 的 MiniApp consumer，不能提前宣称已有语音输入或朗读。
3. **share/openURL。** share 使用 Android 系统 chooser，返回用户完成/取消，不自动发送；openURL 在跳出 WebView 前做 scheme/host/凭证/私网/长度校验，再复用现有 MiniApp confirmation/audit 机制调用 `ACTION_VIEW`。WebView 当前 `shouldOverrideUrlLoading = true`（`MiniAppRunnerPage.kt:311-335`）应继续作为默认阻断，只有显式 bridge 方法能外跳。
4. **生命周期与取消。** Android runner 的 `DisposableEffect` 当前只 close/destroy bridge/WebView（`MiniAppRunnerPage.kt:413-419`）；W11 需为新增 owner 接入 Activity/Compose lifecycle，取消未完成 TTS/share/外链确认，释放亮度和常亮 lease，并确保 late JS request 得到 `runner_closed`/取消错误。
5. **W11 验收。** 亮度修改退出后恢复；后台/旋转不会把 keep-awake 或 TTS 留给下一页；无语音引擎、TTS 初始化失败、share 用户取消、openURL 拒绝/无处理 Activity 都有可捕获错误；对 `https`、`mailto`、`tel` 与私网/本地/`javascript:` 做表格化测试。

## 两条不冲突的实施分工

**分工 A：协议 / 授权 / JS（W10 基础层）。** 只修改并负责评审：

- `core/settings/src/main/kotlin/app/amber/core/settings/PreferencesStore.kt`
- `app/src/main/java/app/amber/feature/miniapp/MiniAppModels.kt`
- `app/src/main/java/app/amber/feature/miniapp/MiniAppOutputParser.kt`
- `app/src/main/assets/miniapp/miniapp_bridge.js`
- `app/src/main/java/app/amber/feature/miniapp/bridge/MiniAppBridge.kt`
- `app/src/main/java/app/amber/feature/miniapp/MiniAppSandbox.kt`
- `app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppSettingsPage.kt`
- 对应 parser/sandbox/bridge/settings 单测。

该分工定义 `MiniAppSystemCapabilityHandler` 的最小接口、method registry、`app.info/capabilities` JSON、global toggle 和 permission/grant 错误；不写 Android haptic/TTS/window/Intent 代码，也不改 Runner wiring。现有 Room grant/audit 由它沿用，不新建第二套授权表。

**分工 B：Android native / runner lifecycle（W10/W11 执行层）。** 只修改并负责评审：

- 新增 `app/src/main/java/app/amber/feature/miniapp/MiniAppAndroidDeviceCapabilities.kt`（或同等独立 owner 文件），实现七组 system methods 与 QR；
- `app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppRunnerPage.kt` 仅做 owner 创建、Activity/window/lifecycle 绑定、close/destroy；
- `app/src/main/AndroidManifest.xml` 仅在静态检查证明必须时补最小宿主权限；不新增依赖、不把 app 级权限当 MiniApp grant；
- 对应 native 参数/生命周期/Intent/QR 单测与手工验收场景。

该分工不改 A 的 permission enum、settings UI、bridge JSON 或 JS wrapper；通过 A 提供的 handler 接口接入。两边之间唯一的合并点是 Runner 注入 handler，先合并 A 的契约后由 B 接线即可，避免共同编辑 `MiniAppV3Runtime.kt` 里现有 `MiniAppSystemBridge` 造成冲突。若选择直接扩展现有 `MiniAppSystemBridge`，应由 B 独占该文件并保持 A 不触碰。

## 已有测试与验证空白

- Android MiniApp 相关测试文件已存在：`app/src/test/java/app/amber/agent/data/agent/miniapp/MiniAppSandboxTest.kt`、`app/src/test/java/app/amber/feature/miniapp/MiniAppConversationWriterTest.kt`、`app/src/test/java/app/amber/feature/ui/pages/miniapp/MiniAppRunnerLoadStateTest.kt`。当前未运行。
- iOS 对应契约/实现测试已存在：`iosApp/iosAppTests/IOSMiniAppSystemSDKTests.swift`、`IOSMiniAppBridgeRuntimeTests.swift`、`IOSMiniAppDeviceCapabilitiesTests.swift`、`IOSMiniAppOutputParserTests.swift`、`IOSMiniAppVisualEvidenceTests.swift`、`IOSSettingsWiringTests.swift`。当前未运行。
- 因本阶段是只读准备，尚未验证真机硬件、无 TTS engine、后台恢复、系统 share chooser、QR 扫描兼容性或 Android Activity 生命周期；这些必须进入 W10/W11 验收矩阵。
