# Phase4 W10/W11 MiniApp 协议层交接

日期：2026-09-10  
任务：Android MiniApp 系统能力协议、授权、发现、JS 与设置层  
状态：**已暂停，未开始产品实现**

## 当前树核对

本子任务没有写入任何产品代码、测试或构建产物。以下文件仍未落地 Phase4 改动：

- `app/src/main/java/app/amber/feature/miniapp/MiniAppModels.kt`
- `app/src/main/java/app/amber/feature/miniapp/MiniAppOutputParser.kt`
- `app/src/main/java/app/amber/feature/miniapp/MiniAppSandbox.kt`
- `app/src/main/java/app/amber/feature/miniapp/bridge/MiniAppBridge.kt`
- `app/src/main/assets/miniapp/miniapp_bridge.js`
- `app/src/main/java/app/amber/feature/ui/pages/miniapp/MiniAppSettingsPage.kt`
- `app/src/main/java/app/amber/core/ai/transformers/MiniAppPromptTransformer.kt`

尚未创建 `app/src/main/java/app/amber/feature/miniapp/MiniAppSystemCapabilityHandler.kt`。当前工作树上述范围内可见的 dirty 文件来自前序/其他任务：`MiniAppRunnerPage.kt`、`MiniAppRepository.kt`、`ConversationDraftStore.kt`、`MiniAppSourceEditor.kt` 和 `PreferencesStore.kt`；不要用 reset 或整文件覆盖清理它们。`scripts/miniapp-parity/index.html` 已由主线创建，是验收 fixture，不是本子任务产物。

没有运行 Gradle、单测、assemble 或其他构建验证。

## 已确认的 iOS 契约

六组需要 per-app 声明/grant 的系统权限：`haptics`、`device`、`screen`、`speech`、`share`、`openURL`。`qrcode.generate` 是系统能力方法，但没有 per-app permission；仍受全局 `systemCapabilitiesEnabled` 开关控制。新 system grant 未决时不能按 Android 旧能力的 null grant 规则默许。

方法和参数以 iOS 生产代码为准：

- `haptics.impact({style?, intensity?})`：style 为 `light|medium|heavy|soft|rigid`，intensity 为 `0...1`；`notification({type?})`：`success|warning|error`；`selection()`。
- `device.getInfo()`、`device.getBattery()`。
- `screen.getBrightness()`；`setBrightness(number 或 {brightness})`，范围 `0...1`；`setKeepAwake(bool 或 {enabled})`。
- `speech.getVoices()`；`speak(string 或 {text, language?, rate?, pitch?, volume?})`，text `1...4000` 字符，rate `0...1`、pitch `0.5...2`、volume `0...1`；`stop/pause/resume()`。
- `share({text?, url?})`，至少有一项，返回 `{completed}`，由用户操作系统分享面板；不自动发送。
- `openURL(string 或 {url})`，允许公开 HTTPS、`mailto`、`tel`，校验凭证/私网/本地/控制字符；每次都确认，返回 `{opened,url}`。
- `qrcode.generate(string 或 {text,size?})`，text 最多 1024 UTF-8 字节，size 默认 256、范围 `128...1024`，返回 `{dataURL,width,height}`。

`app.info` 应返回 `platform`、`bridgeVersion`、`appId`、`title`、`version`、`runCount`、`permissions`、`grants`。`app.capabilities` 应返回 `platform`、`bridgeVersion`、`systemCapabilitiesEnabled`、实际可调用 `methods`，以及每项 permission 的 `permission/declared/enabled/decision`；发现阶段不弹授权。只有 native handler 实际支持全部声明方法后才可报告 `0.3-system-capabilities`，不能预先伪装 parity。

## 已确定的接口与实现边界

建议/已转主线的最小接口：

```kotlin
interface MiniAppSystemCapabilityHandler {
    val supportedMethods: Set<String>
    suspend fun dispatch(method: String, params: JsonObject): JsonElement
}
```

文件应新建在 `app/src/main/java/app/amber/feature/miniapp/MiniAppSystemCapabilityHandler.kt`。`close`、前台判断、屏幕/语音/分享生命周期由 native owner 自己管理，不放进协议接口。Bridge 构造函数应追加可选的 `systemCapabilityHandler` 参数以保持旧调用兼容；Runner 注入由 `browser_miniapp_parity` owner 负责。

建议在该新文件同处定义固定 registry：六组方法到 permission 的映射，以及无 permission 的 `qrcode.generate`。`app.capabilities.methods` 必须是固定 registry 与 handler `supportedMethods` 的交集，再加真实已有 bridge 方法。

授权链必须保持：MiniApp enabled → 声明 → 全局系统开关 → durable grant。新六组 grant 为 null 时经现有 confirmation 弹窗，用户拒绝要持久化 DENY；确认返回后重新读取 durable app，核对 appId、version、htmlHash、声明和当前 setting，再写 ALLOW；旧 runner 的 `appProvider` 闭包不能作为版本证明。`qrcode.generate` 跳过声明/grant，但不能跳过总开关。`openURL` 即使已有 ALLOW 也每次确认。动作完成后沿现有 repository audit 写入审计。

W06 兼容边界：旧 `MiniAppV3Permissions` 与现有 `MiniAppSystemBridge` 的 clipboard/location 行为保留；旧权限 null grant 仍默认允许，只对新增六组系统权限要求实际 grant。不要把 Android Manifest 的录音/位置等宿主权限推导成 MiniApp 权限。

## 待实施文件与定点验证

1. `MiniAppModels.kt`：追加六个 permission、别名（至少 `vibrate/vibration/haptic/振动/震动/openurl`），把它们加入 V3 permission 集合；二维码不加入 permission 集合。
2. `MiniAppSandbox.kt`：加入 `systemCapabilitiesEnabled` gate、system grant pending/deny 的结构化错误，同时保持旧能力语义；暴露只读的 declared/global-enabled 查询供 capabilities 组装。
3. `MiniAppBridge.kt`：追加 system handler 参数；实现 `app.info`、`app.capabilities`、真实 registry dispatch、system confirmation/版本复核、openURL 每次确认和审计；handler 缺失/方法未支持不能弹授权。
4. `miniapp_bridge.js`：加入 `valueParams`、`Amber.getAppInfo/getCapabilities` 以及全部 iOS 同名 wrapper，继续使用现有 Android `AmberNative.postMessage`/Promise/errorCode 协议。
5. `MiniAppOutputParser.kt` 与 `MiniAppPromptTransformer.kt`：允许/声明全部六组能力，补齐 iOS 参数、返回和 try/catch 约束；不得把 qrcode 写入 permissions。
6. `PreferencesStore.kt`：`MiniAppSetting` 增加默认开启的 `systemCapabilitiesEnabled`；AgentPrefs 已整体序列化 `AgentRuntimeSetting`，不需新增 DataStore key。`MiniAppSettingsPage.kt` Advanced 增加“系统交互”总开关；补英文/中文专属 strings。
7. 测试至少覆盖：新权限 parser/alias；六组全局开关和 pending/deny/allow；confirmation 后 app/version/hash 变化拒绝写 grant；qrcode 无 per-app grant 但受总开关；capabilities 方法与 handler 支持集合相等且不触发 confirmation；JS wrapper 参数形状；settings round-trip。Bridge 测试优先真实 repository/confirmation/handler 受控 fixture，避免 source 字符串断言。

## 协调记录与风险

已向主线转发 handler 签名，主线表示会转给 `browser_miniapp_parity`。当前环境对直接向该 subagent 发送 app-server 消息返回 `direct app-server input is not allowed for multi-agent v2 sub-agents`，因此不要把该失败误判成接口未协调；以本记录和主线转发为准。

关键风险是多个 agent 共享 dirty worktree：`PreferencesStore.kt` 与 `MiniAppRunnerPage.kt` 有前序改动，后续只能局部 patch；native owner 注入应等待本接口文件落地后由 B 改 Runner。当前没有编译证据，也没有证明 Android native 七组方法已接线；W10/W11 native 实现仍是后续任务。

