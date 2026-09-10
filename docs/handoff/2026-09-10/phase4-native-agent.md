# Phase 4 W10/W11 MiniApp Native 与 Runner 交接

日期：2026-09-10  
范围：Android MiniApp 系统能力的 native owner、Runner 生命周期与系统 Intent 接线。  
状态：**用户已暂停实施；本交接只记录事实和可执行边界，没有继续修改产品、测试或构建。**

## 当前结论

Phase 4 的 Android native/Runner 实现还没有落盘。当前生产代码没有以下文件或类型：

- `app/src/main/java/app/amber/feature/miniapp/MiniAppAndroidDeviceCapabilities.kt`
- `app/src/main/java/app/amber/feature/miniapp/MiniAppSpeechEngine.kt`
- `app/src/main/java/app/amber/feature/miniapp/MiniAppSystemCapabilityHandler.kt`
- Android bridge 的 `systemCapabilityHandler` 注入和七组新 system methods

因此不能把 Android 标成已经追齐 iOS，也不能把当前工作树中的宿主权限、ZXing 依赖、验收 HTML 或已有 WebView 代码当成系统能力已实现的证据。

当前 Android 仍是以下链路：

```text
MiniAppRunnerPage
  -> MiniAppShell.inject + assets/miniapp/miniapp_bridge.js
  -> @JavascriptInterface("AmberNative") MiniAppBridge
  -> MiniAppSandbox / MiniAppRepository
  -> MiniAppV3Runtime.MiniAppSystemBridge
```

`MiniAppSystemBridge`（`app/src/main/java/app/amber/feature/miniapp/MiniAppV3Runtime.kt:162-188`）只提供剪贴板读取和定位。`MiniAppBridge`（`app/src/main/java/app/amber/feature/miniapp/bridge/MiniAppBridge.kt:55-78,350-385`）对新增系统能力没有分支，现有 clipboard/location/sensor 等方法保持当前实现；`else` 仍将未知方法作为错误。`miniapp_bridge.js` 也没有新系统能力 wrapper。

## 已核对的 Android 文件状态

### Runner

`app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppRunnerPage.kt:243-421` 是当前真实 Runner：

- WebView 开启 JavaScript，关闭 DOM storage、file/content access、数据库、混合内容和多窗口。
- `shouldOverrideUrlLoading` 对导航统一返回 `true`；外跳不能通过 WebView 导航绕过 bridge 的确认和审计。
- `shouldInterceptRequest` 只对获得 `externalImages` 权限的 HTTPS 图片走代理，其他网络协议被拦截。
- `DisposableEffect` 目前只调用 `bridgeRef?.close()`、清空引用并 `webViewRef?.destroy()`（`:413-419`）。没有 Activity/Window owner、亮度 lease、keep-awake lease、TTS、share callback 或后台处理。
- 该文件当前有其他并行 WIP：Runner 加载错误状态和 `CancellationException` 处理位于 `:233-241,423-431`。后续接线只能局部修改，不能用基线文件覆盖整份文件。

### 协议、授权与持久化边界

- `app/src/main/java/app/amber/feature/miniapp/MiniAppModels.kt:9-30,106-160` 的 V3 权限目前止于 `sensor`、`location`、`clipboard.read`，没有 `haptics/device/screen/speech/share/openURL`。
- `app/src/main/java/app/amber/feature/miniapp/MiniAppSandbox.kt:5-53` 已有 MiniApp enabled → 声明 → 全局 setting → durable grant 的顺序，但没有 `systemCapabilitiesEnabled` 或新 permission 映射。
- `core/settings/src/main/kotlin/app/amber/core/settings/PreferencesStore.kt:209-228` 的 `MiniAppSetting` 没有系统能力总开关。当前 dirty 不能视为 Phase 4 setting 已落地。
- Room grant/audit 已可复用：`app/src/main/java/app/amber/agent/data/db/entity/MiniAppEntity.kt:44-106`、`app/src/main/java/app/amber/agent/data/db/dao/MiniAppDAO.kt:97-185`、`app/src/main/java/app/amber/feature/miniapp/MiniAppRepository.kt:196-252`。native owner 不应新建第二套授权表。
- `app/src/main/AndroidManifest.xml:5-36` 有宿主 `CAMERA`、位置、`RECORD_AUDIO`、蓝牙等权限，但没有 `android.permission.VIBRATE`。`RECORD_AUDIO` 是录音宿主权限，不是 MiniApp TTS 权限；现有 `SEND`/`SEND_MULTIPLE` filter（`:125-138`）是外部内容分享进入 Amber，也不是 MiniApp 发起系统分享。

### 已落盘但仅用于验收的文件

主线已经创建：

- `scripts/miniapp-parity/README.md`
- `scripts/miniapp-parity/index.html`
- `scripts/miniapp-parity/seed-emulator.py`

fixture 使用 `Amber.getAppInfo/getCapabilities`、haptics/device/screen/speech/QR/share/openURL 的预期 API，并要求真实 WebView、真实二维码解码、分享取消和离页清理。当前 Android 没有这些 API，所以 fixture 只能作为恢复实施后的设备验收入口，不能证明生产功能存在。seed 脚本把声明写入一次性验收小应用，不会替代 bridge grant 和 native owner。

`app/build.gradle.kts:675-680` 已有 `libs.zxing.core` 和 quickie scanner 依赖。ZXing core 可直接用于 QR 编码；本阶段没有新增依赖，也没有把 scanner 依赖误当成 MiniApp QR 生成器。

## 与 iOS 对齐的固定契约

iOS 生产基准为：

- `/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSMiniAppDeviceCapabilities.swift`
- `/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/IOSMiniAppBridgeRuntime.swift`
- `/Users/mi/Downloads/AI/AmberAgent/ios/iosApp/iosApp/MiniAppRunnerWebView.swift`

需要对齐的 method 集合为：

```text
haptics.impact
haptics.notification
haptics.selection
device.getInfo
device.getBattery
qrcode.generate
screen.getBrightness
screen.setBrightness
screen.setKeepAwake
speech.getVoices
speech.speak
speech.stop
speech.pause
speech.resume
share
openURL
```

其中只有六组需要 MiniApp 声明/grant：`haptics`、`device`、`screen`、`speech`、`share`、`openURL`；`qrcode.generate` 不加入 per-app permission，但仍受系统能力总开关控制。方法发现必须只返回真实可调用集合；在 native owner 和 bridge 都未接线前，不能提前返回 iOS 的 `0.3-system-capabilities`。

参数和结果应保持 iOS 语义：

- haptics：impact style `light|medium|heavy|soft|rigid`，intensity `0...1`；notification type `success|warning|error`；selection 无参数。
- device：`getInfo()`、`getBattery()`；无电池、无可读字段或系统不支持时返回明确的 `null/unknown`，不伪造值，不暴露设备标识。
- QR：文本 UTF-8 不超过 1024 bytes，size 默认 256，范围 128–1024；结果含 `dataURL`、`width`、`height`。
- screen：brightness 为 `0...1`；keep-awake 接受 boolean 或 `{enabled}`，只影响当前 Runner 的 Window。
- speech：text 1–4000 字符；rate `0...1`、pitch `0.5...2`、volume `0...1`；stop/pause/resume 需要对引擎缺失和生命周期取消返回真实错误。
- share：`{text?,url?}` 至少一项，返回 `{completed}`；打开 chooser 不代表分享完成。
- openURL：允许公开 HTTPS、`mailto`、`tel`；拒绝 credentials、控制字符、本地/私网和 `javascript:` 等危险输入，返回 `{opened,url}`；确认、grant、audit 由协议层负责。

主线已确认的窄协议接口是：

```kotlin
package app.amber.feature.miniapp

interface MiniAppSystemCapabilityHandler {
    val supportedMethods: Set<String>
    suspend fun dispatch(method: String, params: JsonObject): JsonElement
}
```

该接口尚未创建。协议 agent 负责 registry、`app.info/app.capabilities`、permission/settings/grant/confirmation/audit 和 JS；native agent 只实现实际系统调用，并通过 Runner 注入 handler。不要把 owner 生命周期方法塞进协议接口，也不要再引入全局 ThreadLocal 或第二套上下文。

语音原定由 phase1_models 单独负责，当前只约定了以下类级接口；用户暂停前没有真正派发 follow-up，也没有文件或实现：

```kotlin
class MiniAppSpeechEngine(context: Context) {
    suspend fun dispatch(method: String, params: JsonObject): JsonElement
    fun suspendForBackground()
    fun close()
}
```

恢复时 native owner 依赖该类或由同一 owner 明确接管，必须先解决实际文件所有权，不能在 handoff 之外假设 TTS 已存在。

## Native owner 的最小设计

恢复后新增一个 per-runner `MiniAppAndroidDeviceCapabilities`，建议放在：

`app/src/main/java/app/amber/feature/miniapp/MiniAppAndroidDeviceCapabilities.kt`

建议构造依赖只包含当前 Runner 所需对象：`Context`、可选 `Activity`/`Window`、生命周期状态、share 的 Activity Result 启动回调、openURL 的 `ACTION_VIEW` 启动回调，以及 speech engine。owner 需提供上面的 `MiniAppSystemCapabilityHandler` 方法，并保留窄的生命周期操作：

```kotlin
override val supportedMethods: Set<String>
suspend fun dispatch(method: String, params: JsonObject): JsonElement
fun completeShare(completed: Boolean, error: Throwable? = null)
fun suspendRunner()
fun close()
```

实现约束：

1. **haptics** 使用 `Vibrator`/`VibratorManager` 与 `VibrationEffect`，前台和硬件不可用要返回可捕获错误；只有实际接线时才在 Manifest 增加 `VIBRATE`。
2. **device/battery** 使用 Android 系统公开 API，字段有界且诚实；不把 app 宿主的录音、位置或设备标识权限映射为 MiniApp grant。
3. **QR** 使用现有 ZXing core 的 `QRCodeWriter` 生成 PNG data URL，严格执行 UTF-8 和尺寸边界，不引入 scanner 以外的新依赖。
4. **screen** 保存当前 Runner 的原始 `Window` brightness 和 keep-awake flag；退出、重载、后台时只在 owner 仍是当前值时恢复，旧 Runner 不能覆盖后来 owner 的状态。
5. **share** 使用 `Intent.ACTION_SEND` + `Intent.createChooser`，`ActivityResult` 可用于面板返回/取消的生命周期，但不能普遍证明接收应用已完成分享。Android 的 completed/unknown 返回契约仍须实施时明确，不能从 RESULT_OK 或选中组件推导实际发送成功；取消、关闭和 Runner 销毁应结束 pending 请求。
6. **openURL** 在离开 WebView 前校验 scheme、host、credentials、私网/本地地址和控制字符，再调用 `Intent.ACTION_VIEW`；无 resolver 或 start 失败不能报告 opened。
7. **生命周期** 每个 Runner 只创建一个 owner；前后台/旋转/关闭要清理 TTS、share pending 和 screen leases。`DisposableEffect` 应先关闭 owner，再关闭 bridge、销毁 WebView，迟到 JS 请求返回现有 `runner_closed`/取消语义。

## 官方 Android API 结论

- [Vibrator API](https://developer.android.com/reference/android/os/Vibrator) 和 [VibrationEffect API](https://developer.android.com/reference/android/os/VibrationEffect) 是 Android 的振动入口；振动需要 Manifest 的 `android.permission.VIBRATE`。硬件没有对应能力时不能假装成功。
- [TextToSpeech API](https://developer.android.com/reference/android/speech/tts/TextToSpeech) 提供初始化、`getVoices`、`speak`、`stop` 和参数/语言设置；[UtteranceProgressListener](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener) 可提供播放边界回调。Android API 没有与 iOS synthesizer 等价的原生 pause，因此 pause/resume 只能在已知分段边界保存剩余文本实现实用等效，并必须在接口结果中诚实表达限制。
- [WindowManager.LayoutParams.screenBrightness](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#screenBrightness) 与 [`FLAG_KEEP_SCREEN_ON`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON) 是 Window 级状态，不是全局 MiniApp 权限；必须按 Runner owner 保存和恢复。
- [BatteryManager API](https://developer.android.com/reference/android/os/BatteryManager) 可读取系统电池属性；无电池或属性不可用时返回 unknown/null，不推断充电状态。
- Android [发送简单数据](https://developer.android.com/training/sharing/send) 的系统分享路径是 `ACTION_SEND` 和 chooser。chooser 仅表示系统面板已打开，Activity Result 回调也不能普遍证明目标应用实际完成发送；它只能提供当前 Activity 流程支持的结果。Android 的完成/未知语义仍待明确定义，不可把 `startActivity` 成功或选中目标当成用户分享成功。
- `openURL` 使用 `Intent.ACTION_VIEW` 前仍需应用自己的公开 URL 校验、确认和审计；Android resolver 结果只能说明是否有处理 Activity，不能替代用户确认。

## 恢复实施顺序与文件所有权

1. 协议 agent 先落地 `MiniAppSystemCapabilityHandler`、六个新 permission、系统总开关、registry、`app.info/app.capabilities`、bridge/JS wrapper 和确认/审计；沿用已有 Room grant/audit。
2. native agent 新增 `MiniAppAndroidDeviceCapabilities.kt`，接入 haptics、device、battery、QR、screen、share、openURL；语音先等 `MiniAppSpeechEngine.kt` 的明确 owner，再实现代理和生命周期调用。
3. Runner owner 只修改 `MiniAppRunnerPage.kt` 的构造和生命周期区域：拿到当前 Activity/Window，注册 Activity Result 和 lifecycle observer，创建 handler，dispose 时按 owner → bridge → WebView 顺序清理。保留现有导航阻断和外部图片 sandbox。
4. Manifest 只在 haptics 代码实际使用后追加 `VIBRATE`；不新增二维码或网络依赖，不把现有 inbound share filter 改成 outbound 能力。
5. 协议层定点 JVM 测试先覆盖权限/registry/参数/URL/返回 JSON；native JVM 测试覆盖错误和资源状态；设备测试使用 `scripts/miniapp-parity` 做真实 WebView 运行。

## 未验证项

本阶段没有运行 Gradle、单测、assemble、安装 APK 或设备测试。以下均保持未验证：

- Android `MiniAppSystemCapabilityHandler` 尚未创建，bridge/JS/permission/settings 没有新能力接线。
- 真实设备 haptic 硬件、无电池场景、设备 info 字段边界和 QR PNG 的 ZXing 解码。
- TTS 初始化失败、无 voice、pause/resume 的分段效果、后台/关闭时 `shutdown` 和迟到回调。
- Activity chooser 用户取消/完成、旋转或 Runner 关闭时 share pending 的结果。
- Window brightness/keep-awake 在离页、后台、重载和两个 Runner 交替时的 owner 恢复。
- `https`、`mailto`、`tel`、credentials、localhost、私网 IP、IPv6 本地地址、`javascript:` 等 openURL 表格，以及无处理 Activity 的失败语义。
- 协议层 global toggle、未声明、grant pending/deny/allow、app/version/htmlHash 变化后的确认复核和 audit。

恢复实施时，先由主线明确 speech 文件归属并合并协议接口，再做 native/Runner 小范围接线；在上述设备门通过前，报告应继续把 Phase 4 Android 系统能力标为“协议已规划、native 未验证”。
