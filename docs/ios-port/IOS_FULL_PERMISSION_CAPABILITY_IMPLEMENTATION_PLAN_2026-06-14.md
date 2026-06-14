# AmberAgent iOS 全量系统权限能力打通计划

日期：2026-06-14
范围：iOS 三方 App 可向系统申请、声明、授权或经系统 UI 获取的权限/能力。
目标：尽可能多地把 iOS 可获取权限纳入 AmberAgent iOS 权限系统，让用户自行判断是否申请；不再按产品保守策略预先隐藏或禁用可申请能力。

## 目标定义

本计划的目标不是只做 V3 的最小权限闭环，而是把权限获取能力扩展为全量 iOS capability catalog：

1. 所有 iOS 三方 App 可申请的系统权限都进入 registry、状态快照和 UI 数据源。
2. 对有公开 runtime authorization API 的权限，提供真实 request/status 入口。
3. 对需要系统 picker、extension target、entitlement、capability 或 Apple 特批的权限，仍进入清单，并显示真实前置条件。
4. 不做产品层“用户可能不会申请”“App Review 风险”“默认不推荐”的过滤。
5. 不把 iOS 系统根本不给三方 App 的能力伪装成可申请。

## 非目标

以下不作为产品策略限制，但必须作为系统真实边界呈现：

- 不绕过 iOS 系统弹窗、系统 picker、系统设置页、entitlement、provisioning profile 或 extension 限制。
- 不伪造授权状态；无法判断 read-granted 的能力必须显示 Apple API 能表达的真实状态。
- 不把“App 获得系统权限”自动等价为“agent 可以静默读取/写入数据”。系统权限申请和 agent 工具执行 gate 仍是两层。
- 不实现 iOS 不允许三方 App 获取的能力，例如短信数据库、通话记录、其他 App 通知内容、全盘文件枚举、跨 App 全局点击输入、悬浮窗覆盖其他 App。

## 全量能力目录

状态/请求方式必须使用真实系统路径，不得用产品策略替代系统边界：

- `directSystemPrompt`：App target 内可直接触发系统授权弹窗。
- `foregroundSystemUI`：必须由前台 UI 触发的系统界面、sheet、picker 或 composer。
- `foregroundSession`：必须启动前台 session，不能后台静默触发。
- `authenticationOperation`：一次性本地认证操作，不是持久 grant。
- `picker`：用户选择型授权，只能访问用户选择的资源。
- `settingsOnly`：只能从系统设置、功能设置或系统授权面板启用。
- `entitlementRequired`：需要 entitlement/provisioning profile，未配置时显示前置条件。
- `extensionTargetRequired`：需要 extension target，App target 不能直接申请。
- `entitlementAndExtensionRequired`：同时需要 entitlement 和 extension。
- `diagnosticOnly`：只能展示可用性或配置诊断。
- `unsupported`：iOS 不给三方 App 获取，不能伪装成可申请。

| Domain | Capability ID | 用户可申请/授权内容 | 状态/请求方式 | 主要声明或前置条件 |
|---|---|---|---|---|
| Files | `ios.files.selected_read` | 用户选择的单文件读取 | `picker` | SwiftUI `fileImporter` / document picker |
| Files | `ios.files.security_scoped` | security-scoped URL/bookmark | `picker` | user-selected URL，bookmark 另行设计 |
| Files | `ios.files.export` | 导出/分享用户确认的文件 | `foregroundSystemUI` | document exporter / share sheet |
| Photos | `ios.photos.library_read` | 照片/视频库读取，全量或 limited library | `directSystemPrompt` | `NSPhotoLibraryUsageDescription` |
| Photos | `ios.photos.library_add` | 保存照片/视频到相册 | `directSystemPrompt` | `NSPhotoLibraryAddUsageDescription` |
| Photos | `ios.photos.limited_library_management` | 管理 limited library 选择范围 | `foregroundSystemUI` | `PHPhotoLibrary.presentLimitedLibraryPicker` |
| Photos | `ios.photos.picker` | 用户选择照片/视频资源 | `picker` | `PhotosPicker` / `PHPickerViewController` |
| Files | `ios.files.external_storage_capture` | 外接存储设备采集授权 | `directSystemPrompt` | `AVExternalStorageDevice.requestAccess()` |
| Files | `ios.image_capture.contents` | 外接 media device 内容访问授权 | `directSystemPrompt` | `ICDeviceBrowser.requestContentsAuthorization` |
| Files | `ios.image_capture.control` | 外接 camera device 控制授权 | `directSystemPrompt` | `ICDeviceBrowser.requestControlAuthorization` |
| Journaling | `ios.journaling_suggestions.picker` | Journaling Suggestions 选择器 | `foregroundSystemUI` | Journaling Suggestions framework / availability |
| Location | `ios.location.when_in_use` | 使用期间定位、当前位置、地理围栏、Beacon | `directSystemPrompt` | `NSLocationWhenInUseUsageDescription` |
| Location | `ios.location.always` | 始终/后台定位 | `directSystemPrompt` | `NSLocationAlwaysAndWhenInUseUsageDescription`, `UIBackgroundModes=location` |
| Location | `ios.location.temporary_precise` | 临时精确定位 | `directSystemPrompt` | `NSLocationTemporaryUsageDescriptionDictionary` |
| Location | `ios.location.wilderness_safety` | Wilderness safety location | `entitlementRequired` | `NSLocationWildernessSafetyUsageDescription`, entitlement |
| Camera | `ios.camera.capture` | 相机拍照、录像、扫描、AR camera feed | `directSystemPrompt` | `NSCameraUsageDescription` |
| Camera | `ios.camera.multitasking` | iPad multitasking camera access | `entitlementRequired` | camera multitasking entitlement |
| Camera | `ios.camera.external_uvc` | 外接 UVC 相机/外部采集设备 | `entitlementRequired` | external camera / capture entitlement |
| Microphone | `ios.microphone.record` | 麦克风录音、语音输入、音视频采集 | `directSystemPrompt` | `NSMicrophoneUsageDescription` |
| Speech | `ios.speech.recognition` | Apple Speech framework 语音识别 | `directSystemPrompt` | `NSSpeechRecognitionUsageDescription` |
| Speech | `ios.speech.personal_voice` | Personal Voice 可用性/授权 | `directSystemPrompt` | Personal Voice availability, `AVSpeechSynthesizer` |
| Media | `ios.media.apple_music` | Apple Music / Media Library 访问 | `directSystemPrompt` | `NSAppleMusicUsageDescription` |
| Video Subscriber | `ios.video_subscriber.account` | TV provider 订阅账号 | `directSystemPrompt` | `NSVideoSubscriberAccountUsageDescription` |
| Contacts | `ios.contacts.full` | 读取、新增、修改联系人 | `directSystemPrompt` | `NSContactsUsageDescription` |
| Contacts | `ios.contacts.limited` | iOS 18 limited contacts access | `directSystemPrompt` | `NSContactsUsageDescription`, OS availability |
| Contacts | `ios.contacts.picker` | 用户选择联系人 | `foregroundSystemUI` | `CNContactPickerViewController` |
| Calendar | `ios.calendar.full` | 读取/写入日历事件 | `directSystemPrompt` | `NSCalendarsFullAccessUsageDescription` |
| Calendar | `ios.calendar.write_only` | 创建事件，不读取完整日历 | `directSystemPrompt` | `NSCalendarsWriteOnlyAccessUsageDescription` |
| Reminders | `ios.reminders.full` | 读取/写入提醒事项 | `directSystemPrompt` | `NSRemindersFullAccessUsageDescription` |
| Health | `ios.health.read` | 读取用户授权的 HealthKit data types | `entitlementRequired` | HealthKit entitlement, `NSHealthShareUsageDescription` |
| Health | `ios.health.write` | 写入支持的 HealthKit data types | `entitlementRequired` | HealthKit entitlement, `NSHealthUpdateUsageDescription` |
| Motion | `ios.motion.fitness` | 步数、距离、活动状态、运动数据 | `directSystemPrompt` | `NSMotionUsageDescription` |
| Motion | `ios.motion.fall_detection` | Apple Watch Fall Detection 事件授权 | `entitlementAndExtensionRequired` | watchOS App target, Apple Fall Detection entitlement |
| Workout | `ios.workoutkit.scheduler` | WorkoutKit 训练计划调度授权 | `directSystemPrompt` | `WorkoutScheduler.requestAuthorization()` |
| Finance | `ios.finance.financekit` | FinanceKit 金融数据授权 | `directSystemPrompt` | FinanceKit entitlement, `FinanceStore.requestAuthorization()` |
| Alarms | `ios.alarmkit.alarms` | AlarmKit alarm/timer 授权 | `directSystemPrompt` | `AlarmManager.requestAuthorization()` |
| Notifications | `ios.notifications.alerts` | alert、sound、badge、本地/远程通知 | `directSystemPrompt` | UserNotifications |
| Notifications | `ios.notifications.provisional` | 临时通知授权 | `directSystemPrompt` | UserNotifications `.provisional` |
| Notifications | `ios.notifications.critical` | Critical Alerts | `entitlementRequired` | Critical Alerts entitlement |
| Notifications | `ios.notifications.time_sensitive` | Time Sensitive notifications | `settingsOnly` | notification settings / interruption level entitlement when needed |
| Notifications | `ios.notifications.live_activities` | Live Activities 状态和更新能力 | `diagnosticOnly` | ActivityKit, app capability |
| Notifications | `ios.notifications.communication` | Communication notifications | `entitlementRequired` | communication notification entitlement |
| Messages | `ios.messages.critical_sms` | Critical SMS per-recipient 授权 | `foregroundSystemUI` | `MSCriticalSMSMessenger.requestAuthorization(for:)`，需要 recipient |
| Bluetooth | `ios.bluetooth.ble` | BLE 扫描、连接、外设交互 | `directSystemPrompt` | `NSBluetoothAlwaysUsageDescription` |
| Local Network | `ios.network.local` | 访问局域网设备、Bonjour 服务 | `directSystemPrompt` | `NSLocalNetworkUsageDescription`, `NSBonjourServices` |
| Wi-Fi | `ios.network.wifi_sharing` | AccessorySetupKit accessory 的 Wi-Fi sharing 授权 | `foregroundSystemUI` | `WINetworkSharingController.requestAuthorization()`，需要 selected accessory / entitlement |
| Wi-Fi | `ios.network.wifi_info` | 当前 Wi-Fi SSID/BSSID | `entitlementRequired` | Access Wi-Fi Information entitlement |
| Wi-Fi | `ios.network.hotspot_configuration` | 配置 Wi-Fi / Hotspot | `foregroundSystemUI` | `NEHotspotConfigurationManager` |
| Wi-Fi | `ios.network.hotspot_helper` | Hotspot helper | `entitlementRequired` | Hotspot Helper entitlement |
| Network Extension | `ios.network_extension.vpn_dns_filter` | VPN、DNS proxy、content filter | `entitlementAndExtensionRequired` | NetworkExtension entitlement + extension |
| Network | `ios.network.multipath` | Multipath networking | `entitlementRequired` | Multipath entitlement |
| NFC | `ios.nfc.reader` | NFC tag/NDEF/ISO7816/FeliCa 读取 | `foregroundSession` | NFC entitlement, `NFCReaderUsageDescription` |
| Tracking | `ios.tracking.app_tracking` | IDFA / 跨 App 和网站追踪授权 | `directSystemPrompt` | `NSUserTrackingUsageDescription` |
| Authentication | `ios.authentication.face_id` | Face ID 本地认证 | `authenticationOperation` | `NSFaceIDUsageDescription` |
| Focus | `ios.focus.status` | Focus status 读取/共享 | `directSystemPrompt` | `INFocusStatusCenter.requestAuthorization` |
| Authentication | `ios.authentication.passkeys_platform_credentials` | Browser-class platform public key credentials 授权 | `directSystemPrompt` | `ASAuthorizationWebBrowserPublicKeyCredentialManager.requestAuthorizationForPublicKeyCredentials` |
| Identity | `ios.identity` | 身份相关受控能力 | `entitlementRequired` | `NSIdentityUsageDescription`, entitlement as required |
| Game Center | `ios.game_center.friends` | Game Center 好友列表 | `directSystemPrompt` | `NSGKFriendListUsageDescription` |
| Screen Time | `ios.screen_time.family_controls` | App/category selection、屏幕时间控制 | `entitlementRequired` | FamilyControls entitlement |
| Safety | `ios.sensitive_content_analysis` | Sensitive Content Analysis | `entitlementRequired` | SensitiveContentAnalysis entitlement |
| Home | `ios.home.homekit` | HomeKit 家庭、房间、配件 | `entitlementRequired` | HomeKit entitlement, `NSHomeKitUsageDescription` |
| Nearby | `ios.nearby.interaction` | UWB / Nearby Interaction ranging | `entitlementRequired` | Nearby Interaction entitlement, `NSNearbyInteractionUsageDescription` |
| Matter | `ios.matter.setup` | Matter device setup | `foregroundSystemUI` | Matter Support / system setup UI |
| Accessory | `ios.accessory.setup` | AccessorySetupKit 配件授权 | `foregroundSystemUI` | AccessorySetupKit availability |
| Accessory | `ios.accessory.media_device_discovery` | Media device discovery | `entitlementRequired` | media device discovery entitlement |
| Siri | `ios.siri.shortcuts` | SiriKit / Shortcuts / App Intents 入口 | `entitlementRequired` | `NSSiriUsageDescription`, Siri/App Intents capability |
| CallKit | `ios.call_directory` | 来电识别/拦截列表 | `extensionTargetRequired` | Call Directory extension |
| Messages | `ios.sms_filter` | 未知短信过滤扩展 | `extensionTargetRequired` | Message Filter extension |
| Keyboard | `ios.keyboard.full_access` | 第三方键盘完整访问 | `extensionTargetRequired` | Keyboard extension, RequestsOpenAccess |
| ReplayKit | `ios.replaykit.record` | 屏幕录制/直播 broadcast | `foregroundSystemUI` | ReplayKit system picker / broadcast extension when needed |
| Wallet | `ios.wallet.pass_library` | Wallet pass library / background add passes 授权 | `foregroundSystemUI` | `PKPassLibrary.authorizationStatus(for:)` / `requestAuthorization(for:)` |
| Wallet | `ios.wallet.apple_pay` | Apple Pay 支付授权 sheet | `entitlementRequired` | Apple Pay merchant entitlement |
| System Extension | `ios.system_extension` | System extension 安装/授权 | `entitlementAndExtensionRequired` | SystemExtension entitlement + extension |
| AutoFill | `ios.autofill_credential_provider` | 密码自动填充提供方 | `extensionTargetRequired` | Credential Provider extension |
| Push to Talk | `ios.push_to_talk` | Push To Talk capability | `entitlementRequired` | PushToTalk entitlement |
| Location Push | `ios.location_push` | Location Push service extension | `entitlementAndExtensionRequired` | location push entitlement + extension |
| External Apps | `ios.external.sms_compose` | 打开短信草稿 composer | `foregroundSystemUI` | MessageUI foreground composer |
| External Apps | `ios.external.phone_dialer` | 打开系统拨号 | `foregroundSystemUI` | `tel:` URL / foreground confirmation |
| External Apps | `ios.external.share` | 系统分享 sheet | `foregroundSystemUI` | `UIActivityViewController` |
| Unavailable | `android.sms.read` | 短信数据库读取 | `unsupported` | iOS 不开放 |
| Unavailable | `android.call_log.read` | 通话记录读取 | `unsupported` | iOS 不开放 |
| Unavailable | `android.phone_state` | 电话状态/IMEI 等 | `unsupported` | iOS 不开放 |
| Unavailable | `android.notification.listener` | 其他 App 通知内容读取 | `unsupported` | iOS 不开放 |
| Unavailable | `android.usage_stats` | 使用统计列表 | `unsupported` | iOS 不开放 |
| Unavailable | `android.manage_all_files` | 全盘文件枚举 | `unsupported` | iOS 不开放 |
| Unavailable | `android.terminal` | Termux/系统 shell | `unsupported` | iOS 不开放 |
| Unavailable | `android.screen_automation` | 跨 App 全局点击/输入 | `unsupported` | iOS 不开放 |
| Unavailable | `android.installed_apps` | 全量安装 App 列表 | `unsupported` | iOS 不开放 |
| Unavailable | `android.overlay` | 跨 App 悬浮窗覆盖 | `unsupported` | iOS 不开放 |

## 实现原则

### 1. 不做产品层过滤

所有可申请能力都进入 UI 和 `permissions_status`。默认状态可以是 `notDetermined`、`requiresEntitlement`、`requiresExtensionTarget`、`unavailableOnDevice` 或 `denied`，但不能因为“风险高”从清单中隐藏。

### 2. 请求系统权限和 agent 工具执行分离

新增 `IOSSystemPermissionCoordinator` 只负责：

- 查询系统授权状态。
- 触发系统授权弹窗或系统 picker。
- 返回 entitlement/extension/device availability 结果。

它不负责：

- 替 agent 自动读取数据。
- 替工具审批放行。
- 复用 grant。

### 3. Capability 必须表达前置条件

每个 capability 至少包含：

- `id`
- `domain`
- `title`
- `summary`
- `requestKind`
- `systemStatus`
- `requiredInfoPlistKeys`
- `requiredEntitlements`
- `requiredBackgroundModes`
- `requiredExtensionTargets`
- `requestEntryPoint`
- `canRequestInApp`
- `canOpenSettings`
- `modelToolNames`
- `uiActionNames`
- `blockedToolNames`

Entitlement 诊断规则：

- iOS App 运行时不能依赖私有 API 读取所有签名 entitlement。
- 当前实现用 `Info.plist` 中的 `AmberAgentConfiguredEntitlements` 作为构建配置诊断清单，默认空数组。
- 当真实接入 HealthKit、FinanceKit、NFC、FamilyControls、NetworkExtension、Apple Pay 等 entitlement 时，必须同时更新 `.entitlements` 和 `AmberAgentConfiguredEntitlements`，否则 UI 会继续显示 `requiresEntitlement`。
- `AmberAgentConfiguredEntitlements` 只决定前置条件诊断，不代表系统授权；真实 request/status 仍必须走对应系统 API。
- `iosApp/scripts/validate-entitlement-diagnostics.sh` 已接入 app targets 的 pre-build scripts，并有 Swift test 校验 `.entitlements` 与 `AmberAgentConfiguredEntitlements` 一致，防止构建配置漂移。

### 4. 系统不可申请能力仍单独列出

SMS read、call log、notification listener、installed apps full list、global screen automation、overlay、all files access 等继续保留在 unsupported section，用于向用户解释 iOS 系统边界。

## Phase Plan

### Phase 0：基线与边界确认

目标：锁定当前实现和新目标之间的差异。

任务：

- 阅读当前 `IOSPermissionModels.swift`、`IOSLocalToolExecutor.swift`、`DocumentAccessStore.swift`、`ToolPermissionsView.swift`、`Info.plist`、`project.yml`。
- 对比旧 V3 最小 registry 和本计划全量目录。
- 明确哪些能力只需 App target，哪些必须 extension target，哪些必须 entitlement/provisioning。

完成标准：

- 形成 implementation checklist。
- 没有把系统不可申请能力误列为可申请。

Subagent review：

- 检查全量目录是否漏掉明显 iOS privacy capability。
- 检查是否把 Apple 硬限制误写成产品限制。

### Phase 1：全量 Registry 与 Status Snapshot

目标：先让 UI/debug 能看到完整权限目录。

任务：

- 扩展 `IOSCapabilityDomain` 和 `IOSPlatformCapability` metadata。
- 将全量能力目录写入 registry。
- `permissions_status` 返回所有能力、状态、前置条件、可请求入口。
- 对 Android-only unsupported 能力继续显示为 unavailable on iOS。

完成标准：

- UI/status 能展示全量 iOS 可申请能力。
- `file_pick` 仍只在 `uiActionNames`，不进入 model tool list。
- `permissions_status` 不返回 Android permission 字符串或 Android settings 文案。

Subagent review：

- 检查 registry 映射是否有同名 tool 冲突。
- 检查 snapshot 是否泄露错误平台语义。
- 检查 supported/requestable/unsupported 状态是否闭环。

### Phase 2：Info.plist、Entitlement、Extension 前置条件

目标：让可直接申请的权限具备必要声明；让需要 entitlement/extension 的能力明确可诊断。

任务：

- 在 `Info.plist` 中加入所有 App target 可声明的 usage description key。
- 在 `project.yml` 中声明可配置 capabilities/entitlements 的目标结构。
- 对需要 extension 的能力列出目标：Call Directory、SMS Filter、Keyboard、ReplayKit Broadcast Upload、Network Extension。
- 对需要 Apple/Developer entitlement 的能力显示 `requiresEntitlement`。

完成标准：

- App 不因缺少 usage description 而在请求权限时崩溃。
- 未配置 entitlement 的能力不会伪装成系统授权失败，而是显示 entitlement missing。
- Extension-only 能力不会被 App target 直接请求。

Subagent review：

- 检查 Info.plist key 是否覆盖可直接请求权限。
- 检查 entitlement/extension 能力是否被错误地作为普通 in-app request。

### Phase 3：IOSSystemPermissionCoordinator

目标：建立唯一系统权限请求入口。

任务：

- 新增 `IOSSystemPermissionCoordinator`。
- 定义统一 request/status 返回模型：
  - `notDetermined`
  - `authorized`
  - `limited`
  - `denied`
  - `restricted`
  - `unavailableOnDevice`
  - `requiresEntitlement`
  - `requiresExtensionTarget`
  - `requiresSystemSettings`
  - `unknown`
- 实现可直接请求权限：
  - Location when-in-use / always / temporary precise。
  - Camera。
  - Microphone。
  - Speech Recognition。
  - Photos read / add-only / limited。
  - Contacts。
  - Calendar full / write-only。
  - Reminders。
  - Notifications alert/provisional/critical/time-sensitive status。
  - Tracking。
  - Face ID availability/authentication prompt。
  - Apple Music / Media Library。
  - Motion / Fitness status trigger。
  - Bluetooth authorization/state。
  - Local Network probe request。
  - NFC availability/status where possible。
  - HealthKit availability and authorization request by selected type groups.

完成标准：

- 所有直接可请求权限都能从同一个 coordinator 查询状态和触发请求。
- 需要 UI/picker 的请求只从 foreground UI action 触发。
- 需要 entitlement/extension 的能力返回清晰诊断。

Subagent review：

- 检查是否存在绕过 coordinator 的系统权限请求。
- 检查 callback/async 状态是否能回写 UI。
- 检查 HealthKit read 状态是否未被误报为普通 granted。

### Phase 4：UI 接入

目标：让用户能看到并主动申请所有可申请能力。

任务：

- `ToolPermissionsView` 或新的 Permissions 页面接入 full catalog。
- 每个 capability 显示：
  - 当前系统状态。
  - 申请按钮。
  - 打开 Settings 按钮。
  - Info.plist keys。
  - entitlement/extension 前置条件。
  - 相关工具名。
- 允许用户对任意可申请能力发起系统授权请求。
- 对 picker/extension/settings-only 能力展示正确 action。

完成标准：

- UI 不隐藏高风险但可申请能力。
- 不可直接 in-app 请求的能力不会出现假按钮。
- 系统权限状态刷新后能反映在 `permissions_status`。

Subagent review：

- 检查 UI action 是否调用 coordinator，不复制权限逻辑。
- 检查前台-only 能力是否可能后台触发。
- 检查状态刷新和错误提示是否闭环。

### Phase 5：能力最小验证工具

目标：让权限申请不是 display-only，至少有最小可验证动作。

任务：

- 为每个已直接请求权限提供最小验证：
  - Location：获取一次当前位置/authorization status。
  - Camera：打开系统相机/AV capture authorization check，不自动拍摄。
  - Microphone：authorization check，不自动录音。
  - Photos：读取 limited/full selection count 或打开 picker。
  - Contacts：读取授权状态，最小 query 由用户输入。
  - Calendar/Reminders：读取 calendar/reminder store status。
  - Notifications：读取 notification settings。
  - Motion：查询可用性/授权状态，可选当天 pedometer summary。
  - HealthKit：按用户选择 type group 请求授权，状态只显示 API 可确认结果。
  - Bluetooth/Local Network/NFC：显示 manager/session availability。
- 不把这些验证动作自动暴露给 OpenAI tools，除非后续单独接 agent runtime。

完成标准：

- 权限申请后有可观察的状态变化或诊断结果。
- 不发生静默敏感数据读取。
- 仍不改变 Android resolver。

Subagent review：

- 检查最小验证是否越界变成数据采集工具。
- 检查失败/denied/restricted/unavailable 状态是否明确。

### Phase 6：Tests 与 Verification

目标：验证全量目录、请求入口、状态映射和 UI 数据源不破。

测试：

- Registry tests：
  - 全量 capability id 唯一。
  - 每个 capability 都有 request kind 和前置条件。
  - Android-only unsupported 不进入 requestable list。
  - extension-only 不进入 direct in-app request list。

- Coordinator tests：
  - 无 Info.plist/entitlement mock 时返回正确 diagnostic。
  - status mapping 覆盖 authorized/denied/restricted/limited/unavailable。
  - HealthKit read 不显示普通 granted。

- UI/data source tests：
  - UI snapshot 数据包含所有 domains。
  - request button 只对可 direct request 的能力出现。
  - settings/extension/picker action 类型正确。

- Regression：
  - `xcrun swiftc -typecheck ... iosApp/iosApp/*.swift`
  - `xcodegen generate`
  - 有 simulator runtime 时运行 `xcodebuild test -scheme iosApp`
  - `./gradlew test -Djava.awt.headless=true`

Subagent review：

- 检查测试是否只是测试 mock，而没有证明真实调用链。
- 检查是否存在 P3+ 的权限绕过、误授权、状态误报、调用链断裂。

## P3+ 风险循环标准

每个阶段完成后都必须启动 subagent review。发现 P3 及以上风险时：

1. 主 agent 判断风险是否真实。
2. 精准修复，不扩大范围。
3. 运行相关验证。
4. 再次启动 subagent review。
5. 循环直到该阶段没有 P3+。

所有阶段完成后，再做一次整体 review，覆盖：

- 全量 registry 完整性。
- coordinator 唯一路径。
- UI -> coordinator -> system API -> status snapshot 调用链。
- entitlement/extension/settings-only 能力诊断。
- Document Picker/file grant 既有闭环是否被破坏。
- Chat 不会静默请求或消费系统权限。
- OpenAI tools 仍不会自动暴露新权限工具，除非后续明确接入 runtime。

## /goal 命令 Prompt

```text
/goal 按照 /Users/arquiel/Downloads/AI/amberagent-ios/docs/ios-port/IOS_FULL_PERMISSION_CAPABILITY_IMPLEMENTATION_PLAN_2026-06-14.md，以终为始，一口气完成 iOS 全量系统权限能力打通。

目标：
把 iOS 三方 App 可向系统申请、声明、授权或经系统 UI 获取的权限/能力，尽可能全量纳入 AmberAgent iOS 权限系统。不要按“用户可能不会申请”“App Review 风险”“默认不推荐”“高风险”等产品策略隐藏或禁用可申请能力；是否申请由用户自行判断。只保留 Apple/iOS 系统真实硬边界：系统根本不给三方 App 的能力不能伪装成可申请；需要 entitlement、extension、provisioning、系统设置或系统 picker 的能力必须真实呈现前置条件。

必须遵循：
1. 先阅读并严格遵循：
   - /Users/arquiel/Downloads/AI/amberagent-ios/AGENTS.md
   - /Users/arquiel/Downloads/AI/amberagent-ios/docs/ios-port/IOS_FULL_PERMISSION_CAPABILITY_IMPLEMENTATION_PLAN_2026-06-14.md
   - 相关实现：iosApp/iosApp/IOSPermissionModels.swift、IOSLocalToolExecutor.swift、IOSSystemPermissionCoordinator.swift、DocumentAccessStore.swift、ToolPermissionsView.swift、ChatView.swift、ChatViewModel.swift、AppShell.swift、Info.plist、iosApp/project.yml、iosApp/iosAppTests/*

2. 按文档 Phase 0 到 Phase 6 完成：
   - 全量 iOS 可申请 capability registry。
   - permissions_status 全量状态快照。
   - Info.plist usage descriptions / entitlement / extension 前置条件诊断。
   - IOSSystemPermissionCoordinator 统一系统权限 status/request 入口。
   - UI 数据源接入，让用户能看到并主动申请所有可直接申请能力。
   - 为可直接请求权限提供最小可验证动作。
   - 补齐 registry、coordinator、UI/data source、状态映射和回归测试。

3. 不做产品层过滤：
   - 高风险但 iOS 可申请的能力也必须进入 registry/UI。
   - 不因为“用户不一定会开”而隐藏。
   - 不因为“暂不推荐”而禁用。
   - 不因为“App Review 可能敏感”而从清单移除。
   - entitlement/extension/settings-only 能力可以显示 requiresEntitlement / requiresExtensionTarget / requiresSystemSettings，但不能从清单隐藏。

4. 系统硬边界必须真实：
   - 不伪造授权状态。
   - 不绕过系统弹窗、系统 picker、系统设置、entitlement、provisioning profile、extension 限制。
   - 不把 iOS 不允许三方 App 获取的短信数据库、通话记录、其他 App 通知内容、全盘文件枚举、跨 App 全局点击输入、悬浮窗等能力标成可申请。
   - Face ID 这类 authentication operation 只能作为一次性本地认证，不得标成持久权限 grant。
   - HealthKit read 授权状态不得伪装成普通 granted，只显示 API 能确认的真实查询结果或诊断结果。

5. 每完成一个阶段，必须用 subagent 做 review。每轮 subagent review 至少检查：
   - 是否漏掉明显 iOS 可申请权限。
   - 是否把 Apple 硬限制误写成产品限制。
   - 是否存在权限绕过、误授权、状态误报。
   - UI -> coordinator -> system API -> status snapshot 调用链是否闭环。
   - entitlement / extension / settings-only / picker-only 能力是否被错误地当作普通 in-app request。
   - 复合 action 是否能映射到所有所需 capability，而不是只挑一个最严格项。
   - Document Picker/file grant 既有闭环是否被破坏。
   - Chat 是否会静默请求或消费系统权限。
   - OpenAI tools 是否被意外暴露新权限工具。

6. 每轮 review 后：
   - 整理所有 P3 及以上风险。
   - 精准修复，不过度防御、不过度兜底、不过度设计。
   - 运行相关验证。
   - 再次用 subagent review 修复结果。
   - 循环，直到该阶段没有 P3+ 风险。

7. 全部阶段完成后，必须再做一次整体 subagent review，覆盖全量 registry、coordinator 唯一路径、UI 调用链、状态快照、测试有效性和既有 V3 文件读取权限闭环。若仍有 P3+ 风险，继续修复，不要结束目标。

8. 必须运行或尝试运行验证：
   - xcrun swiftc -typecheck ... iosApp/iosApp/*.swift
   - xcodegen generate
   - 如果本机有可用 simulator runtime，运行 xcodebuild test -scheme iosApp
   - ./gradlew test -Djava.awt.headless=true
   - 无法运行的验证要说明具体环境原因。

最终交付：
- 说明修改的核心文件。
- 列出新增/覆盖的 iOS 权限能力域。
- 列出每轮 subagent review 的 P3+ 发现和收敛结果。
- 给出最终验证结果。
- 明确声明：iOS 全量系统权限能力打通范围内是否仍存在已知 P3+ 风险；如果有，继续修复，不要完成目标。
```

## 最终完成定义

1. iOS 可申请系统权限全量进入 registry 和 UI 数据源。
2. 可直接请求权限具备真实 status/request 入口。
3. entitlement/extension/settings-only 能力有清晰状态和操作入口，不伪装成普通权限。
4. 系统不可申请能力清楚标为 unavailable on iOS。
5. 没有已知 P3+ 的权限绕过、误授权、状态误报、调用链断裂。
6. Swift typecheck 通过。
7. XcodeGen 生成通过。
8. 若本机 simulator runtime 不可用，明确记录原因；如果可用，iOS tests 通过或仅有无关既有失败。
9. Android 回归失败时，必须确认失败点与 iOS 权限全量接入无关。
