# AmberAgent iOS 权限替换调研

日期：2026-06-14
范围：基于当前 Android 版本已声明权限、`AgentPermissionBroker` 能力表、系统访问工具，调研 iOS 版本应如何替换、删除或新增平台权限。
状态：落地前调研，不改代码。

## 目标

本调研回答三个问题：

1. Android 当前已经申请/使用的权限，在 iOS 上应如何替换。
2. iOS 无法申请或不允许 agent 化的权限，应从 iOS 工具集移除或降级。
3. iOS 独有或更强的平台能力，如 HealthKit、Core Motion、Photos limited library、security-scoped files，应如何新增。

## 本地依据

本次调研参考了当前仓库里的实际代码：

- `app/src/main/AndroidManifest.xml`
- `feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt`
- `feature/tools/access/src/main/kotlin/app/amber/feature/tools/*AccessTools.kt`
- `app/src/main/java/app/amber/feature/runtime/PermissionDecisionResolver.kt`
- `feature/tools/api/src/commonMain/kotlin/app/amber/feature/tools/ToolRegistry.kt`
- `iosApp/iosApp/Info.plist`
- `iosApp/project.yml`

当前 iOS `Info.plist` 还没有声明任何隐私 usage description。

## 外部依据

Apple 官方相关文档：

- HealthKit authorization
  https://developer.apple.com/documentation/healthkit/authorizing-access-to-health-data
- HealthKit setup
  https://developer.apple.com/documentation/healthkit/setting-up-healthkit
- `NSHealthShareUsageDescription`
  https://developer.apple.com/documentation/bundleresources/information-property-list/nshealthshareusagedescription
- `NSHealthUpdateUsageDescription`
  https://developer.apple.com/documentation/bundleresources/information-property-list/nshealthupdateusagedescription
- Core Motion authorization / `NSMotionUsageDescription`
  https://developer.apple.com/documentation/coremotion/cmmotionactivitymanager/1616149-authorizationstatus
  https://developer.apple.com/documentation/bundleresources/information-property-list/nsmotionusagedescription
- EventKit calendar access
  https://developer.apple.com/documentation/eventkit/accessing-calendar-using-eventkit-and-eventkitui
- Calendar usage descriptions
  https://developer.apple.com/documentation/bundleresources/information-property-list/nscalendarsfullaccessusagedescription
  https://developer.apple.com/documentation/bundleresources/information-property-list/nscalendarswriteonlyaccessusagedescription
- Contacts usage description
  https://developer.apple.com/documentation/bundleresources/information-property-list/nscontactsusagedescription
- Location usage description
  https://developer.apple.com/documentation/bundleresources/information-property-list/nslocationwheninuseusagedescription
- Photos picker / PhotoKit
  https://developer.apple.com/documentation/photokit/bringing-photos-picker-to-your-swiftui-app
  https://developer.apple.com/documentation/bundleresources/information-property-list/nsphotolibraryusagedescription
- Camera / microphone usage descriptions
  https://developer.apple.com/documentation/bundleresources/information-property-list/nscamerausagedescription
  https://developer.apple.com/documentation/bundleresources/information-property-list/nsmicrophoneusagedescription
- Notifications authorization
  https://developer.apple.com/documentation/usernotifications/asking-permission-to-use-notifications
- Message compose / mail compose
  https://developer.apple.com/documentation/messageui/mfmessagecomposeviewcontroller
  https://developer.apple.com/documentation/messageui/mfmailcomposeviewcontroller
- Bluetooth usage description
  https://developer.apple.com/documentation/bundleresources/information-property-list/nsbluetoothalwaysusagedescription

说明：Apple Developer 文档页面在非浏览器环境中多数需要 JavaScript，但 URL 与 API 名称已核对。

## 当前 Android 权限全量清单

来自 `app/src/main/AndroidManifest.xml`：

| Android permission / component | 当前用途推断 | Android 风险 | iOS 处理 |
|---|---|---:|---|
| `INTERNET` | 网络请求、LLM、WebMount | Normal/Sensitive | 保留为网络能力，无 Info.plist 权限弹窗 |
| `CAMERA` | 拍照、附件、快捷入口 | Sensitive | 替换为 `NSCameraUsageDescription` |
| `POST_NOTIFICATIONS` | App 通知、任务状态 | Sensitive | 替换为 UserNotifications 授权 |
| `POST_PROMOTED_NOTIFICATIONS` | Android 新通知能力 | Sensitive | 删除，无 iOS 等价 |
| `READ_CONTACTS` | 联系人搜索 | Sensitive | 替换为 Contacts framework + `NSContactsUsageDescription` |
| `WRITE_CONTACTS` | 创建联系人 | High | 替换为 Contacts framework，但必须 draft-first / always ask |
| `GET_ACCOUNTS` | Android 账户列表 | High | 删除，无 iOS 等价 |
| `READ_SMS` | 读取短信 | High | 删除，iOS 不允许读取短信数据库 |
| `RECEIVE_SMS` | 接收短信 | High | 删除，iOS 不允许普通 App 接收短信内容 |
| `SEND_SMS` | 发送短信 | High | 降级为系统短信 compose，不支持静默发送 |
| `RECEIVE_MMS` | 接收彩信 | High | 删除 |
| `READ_PHONE_STATE` | 电话/SIM 状态 | Sensitive | 删除，无 iOS 等价 |
| `READ_PHONE_NUMBERS` | 读取本机号码 | High | 删除，无 iOS 等价 |
| `CALL_PHONE` | 直接拨号 | High | 降级为 `tel:` URL，系统确认 |
| `ANSWER_PHONE_CALLS` | 接听电话 | High | 删除，普通 iOS App 不支持 |
| `READ_CALL_LOG` | 通话记录 | High | 删除，iOS 不允许读取 |
| `READ_CALENDAR` | 读取日历 | Sensitive | 替换为 EventKit full access |
| `WRITE_CALENDAR` | 写日历 | High | 替换为 EventKit write-only/full access，draft-first |
| `READ_EXTERNAL_STORAGE` | 旧版媒体/文件读取 | Sensitive | 替换为 Photos picker / Document Picker |
| `READ_MEDIA_IMAGES` | 图片库读取 | Sensitive | 优先 PhotosPicker；全库才用 PhotoKit 授权 |
| `READ_MEDIA_VIDEO` | 视频库读取 | Sensitive | 优先 PhotosPicker；全库才用 PhotoKit 授权 |
| `READ_MEDIA_AUDIO` | 音频媒体读取 | Sensitive | 不直接等价；用 Document Picker / MusicKit 另行设计 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 选中媒体 | Sensitive | 替换为 PhotosPicker / limited library |
| `ACCESS_MEDIA_LOCATION` | 照片地理位置 | High | 默认不做；如做，视为位置数据 High |
| `MANAGE_EXTERNAL_STORAGE` | 全文件访问 | High | 删除；iOS 用 user-selected files + security-scoped bookmarks |
| `ACCESS_COARSE_LOCATION` | 粗略定位 | Sensitive | 替换为 CoreLocation when-in-use approximate |
| `ACCESS_FINE_LOCATION` | 精确定位 | High | 替换为 CoreLocation precise，必须单独审批 |
| `RECORD_AUDIO` | 录音、语音输入 | High | 替换为 `NSMicrophoneUsageDescription` |
| `BLUETOOTH_SCAN` | 蓝牙扫描 | Sensitive/High | 暂不首批；如做用 CoreBluetooth + usage description |
| `BLUETOOTH_CONNECT` | 蓝牙连接 | Sensitive/High | 暂不首批 |
| `BLUETOOTH_ADVERTISE` | 蓝牙广播 | High | 暂不首批 |
| `NEARBY_WIFI_DEVICES` | 附近 Wi-Fi | Sensitive | 删除或替换为 Local Network，但语义不同 |
| `ACTIVITY_RECOGNITION` | 活动识别 | Sensitive | 替换为 Core Motion / `NSMotionUsageDescription` |
| `PACKAGE_USAGE_STATS` | 使用情况访问 | High | 删除，iOS 无等价 API |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 后台任务保活 | Sensitive | 删除；iOS 用 background task/notification 策略 |
| `SCHEDULE_EXACT_ALARM` | 精确唤醒 | Sensitive | 降级为通知/后台任务，不等价 |
| `ACCESS_WIFI_STATE` | Wi-Fi 状态 | Sensitive | 大多删除；若需要局域网能力另走 Local Network |
| `CHANGE_WIFI_MULTICAST_STATE` | 多播网络 | High | 暂不首批；可能涉及 Local Network/Bonjour |
| `FOREGROUND_SERVICE` | 长任务前台服务 | Normal | 删除；iOS 用 background modes/Live Activity/notifications，能力受限 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | 屏幕捕获服务 | High | 删除；iOS 普通 App 不能全局屏幕捕获 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | 特殊前台服务 | High | 删除；无 iOS 等价 |
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 | High | 删除；iOS 不允许跨 App 悬浮窗 |
| `com.termux.permission.RUN_COMMAND` | Termux 命令执行 | High | 删除；iOS 不支持 Termux 式外部进程 |
| `QUERY_ALL_PACKAGES` | 全量应用列表 | High | 删除；iOS 不能枚举安装 App |
| `BIND_ACCESSIBILITY_SERVICE` | 无障碍自动化 | High | 删除；iOS App Store 普通 App 不可提供系统级自动化 |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | 通知监听 | High | 删除；iOS 不能读取其他 App 通知 |

## 当前 Android AgentPermissionCapability 替换矩阵

### contacts_read

Android：

- `READ_CONTACTS`
- 工具：`contacts_search`

iOS 替换：

- Contacts framework
- `NSContactsUsageDescription`
- capability：`ios.contacts.read`

落地策略：

- 保留。
- 只允许 query-based search。
- 禁止无条件导出全部联系人。
- 工具结果默认脱敏 phone/email。
- 首次使用需要系统授权 + 工具审批。

审批：

- 风险：Sensitive。
- 默认不可全局 auto-approve。
- 可允许同一 run、同一 query 的短期复用。

### contacts_write

Android：

- `WRITE_CONTACTS`
- 工具：`contacts_write`

iOS 替换：

- Contacts framework write。
- `NSContactsUsageDescription`。

落地策略：

- 保留但改成 draft-first。
- agent 先生成联系人草稿，用户确认后写入。
- 更新/删除联系人比创建更危险，首版只做 create。

审批：

- 风险：High。
- `alwaysAsk`。
- 不允许 global auto approval。

### sms_read

Android：

- `READ_SMS` / `RECEIVE_SMS` / `RECEIVE_MMS`
- 工具：`sms_list`, `sms_read`

iOS 替换：

- 无。

处理：

- 删除。
- iOS 工具 registry 不暴露 `sms_list` / `sms_read`。
- 设置页显示 unsupported：iOS does not allow third-party apps to read SMS database。

### sms_send

Android：

- `SEND_SMS`
- 工具：`sms_send`

iOS 替换：

- MessageUI `MFMessageComposeViewController`。

处理：

- 降级为 `sms_compose_draft`。
- 不支持静默发送。
- 用户必须在系统 compose UI 中点发送。

审批：

- 风险：High。
- `alwaysAsk`。
- approval 卡片显示收件人、正文摘要、是否包含附件。

### phone_state

Android：

- `READ_PHONE_STATE`
- `READ_PHONE_NUMBERS`
- 工具：`device_phone_state`

iOS 替换：

- 无可靠等价。

处理：

- 删除。
- 不提供 SIM、本机号码、通话状态读取。

### call_log_read

Android：

- `READ_CALL_LOG`
- 工具：`call_log_list`

iOS 替换：

- 无。

处理：

- 删除。

### call_phone

Android：

- `CALL_PHONE`
- 工具：`call_phone`

iOS 替换：

- `tel:` URL / `UIApplication.open`。

处理：

- 降级为打开拨号系统界面。
- 不支持静默拨号。

审批：

- 风险：High。
- `alwaysAsk`。
- 工具名建议改为 `phone_call_prompt` 或 `phone_open_dialer`。

### calendar_read

Android：

- `READ_CALENDAR`
- 工具：`calendar_list`

iOS 替换：

- EventKit。
- iOS 17+ 应区分 full access / write-only；读取需要 full access。
- Info.plist 使用 calendar full access usage description。

处理：

- 保留。
- 必须要求时间范围。
- 默认最大范围，例如未来 7 天或用户指定。

审批：

- 风险：Sensitive。
- 首次工具调用必须 ask。
- 同一 run、同一时间范围可复用。

### calendar_write

Android：

- `WRITE_CALENDAR`
- 工具：`calendar_create`

iOS 替换：

- EventKit write。
- 可用 write-only access，但如果要检测冲突或读取现有事件需要 full access。

处理：

- 保留但 draft-first。
- 创建前展示标题、时间、地点、参与人、提醒。
- 修改/删除已有事件首版不做或 always ask。

审批：

- 风险：High。
- `alwaysAsk`。

### media_images / media_video

Android：

- `READ_MEDIA_IMAGES`
- `READ_MEDIA_VIDEO`
- `READ_MEDIA_VISUAL_USER_SELECTED`
- 工具：`media_search`

iOS 替换：

- 首选 `PhotosPicker` / `PHPickerViewController`。
- 如确实要搜索全库，再引入 PhotoKit authorization。

处理：

- 不直接迁移 Android 的 `media_search` 全库搜索。
- 首版实现 `photo_pick` / `video_pick`，由用户选择具体媒体。
- iOS limited library 可以作为二阶段能力。

审批：

- 用户选择媒体只是平台资源授权。
- 如果要把图片/视频发给模型，仍需要工具审批。

### media_audio

Android：

- `READ_MEDIA_AUDIO`
- 工具：可能归入 `media_search`

iOS 替换：

- 无直接等价。
- 用户本地音频文件走 Document Picker。
- Apple Music/媒体库走 MusicKit，但语义、授权、服务条款都不同。

处理：

- 首版删除全库音频搜索。
- 新增 `audio_file_pick`，只读取用户选择的音频文件。

### location_current

Android：

- `ACCESS_COARSE_LOCATION`
- `ACCESS_FINE_LOCATION`
- 工具：`location_current`

iOS 替换：

- CoreLocation。
- `NSLocationWhenInUseUsageDescription`。

处理：

- 保留。
- 首版只做 when-in-use 单次定位。
- 默认优先 approximate / coarse 输出。
- precise 坐标需要用户明确批准。

审批：

- approximate：Sensitive。
- precise：High。
- 后台定位：首版不做。

### audio_record

Android：

- `RECORD_AUDIO`
- 工具：`audio_record_once`

iOS 替换：

- AVFoundation microphone authorization。
- `NSMicrophoneUsageDescription`。

处理：

- 保留。
- 必须前台、可见、限时。
- 默认短录音，例如 30 秒以内。

审批：

- 风险：High。
- `alwaysAsk`。
- 显示录音时长、用途、是否发送给模型。

### nearby_devices

Android：

- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`
- `BLUETOOTH_ADVERTISE`
- `NEARBY_WIFI_DEVICES`

iOS 替换：

- CoreBluetooth。
- Local Network / Bonjour。

处理：

- 首版删除。
- 后续如有明确场景再加，例如连接 BLE 外设。

审批：

- 风险：High。
- 不允许自动批准扫描。

### activity_recognition

Android：

- `ACTIVITY_RECOGNITION`

iOS 替换：

- Core Motion。
- `NSMotionUsageDescription`。
- `CMMotionActivityManager` / `CMPedometer`。

处理：

- 保留并扩展。
- 可以提供当天步数、运动状态摘要。
- 不做后台持续采样。

审批：

- 当天步数：Sensitive。
- 历史轨迹/长范围：High。

### notification_access

Android：

- NotificationListener special access。
- 工具：`notification_list`

iOS 替换：

- 无。

处理：

- 删除读取其他 App 通知的能力。
- 仅保留本 App 发送通知：`notification_post` / `notification_schedule`。

### usage_access

Android：

- `PACKAGE_USAGE_STATS`
- 工具：`usage_stats_list`

iOS 替换：

- 无通用等价。

处理：

- 删除。
- 不提供“最近使用 App/时长”工具。

### overlay

Android：

- `SYSTEM_ALERT_WINDOW`

iOS 替换：

- 无。

处理：

- 删除。
- iOS 只能在本 App 内显示 overlay/sheet/Live Activity，不能覆盖其他 App。

### battery_optimization

Android：

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`

iOS 替换：

- 无。

处理：

- 删除。
- 后台任务用 iOS BackgroundTasks、通知、Live Activity 等重新设计。

### exact_alarm

Android：

- `SCHEDULE_EXACT_ALARM`

iOS 替换：

- UserNotifications scheduled notification。
- BackgroundTasks 不能保证精确时间执行。

处理：

- 降级。
- 对用户提醒使用通知。
- 对 agent 后台继续执行不能承诺精确唤醒。

### manage_all_files

Android：

- `MANAGE_EXTERNAL_STORAGE`
- 工具：`external_file_list/read/write/delete`

iOS 替换：

- Document Picker / File Importer。
- security-scoped bookmarks。

处理：

- 删除“全文件访问”。
- 新增 user-selected file/folder scope。
- 文件写入使用 export/draft 或 security-scoped write。

审批：

- 读 selected file：Sensitive。
- 写/覆盖/删除：High。
- 删除 always ask。

### apps / installed_apps_full_access

Android：

- launcher query
- `QUERY_ALL_PACKAGES`
- 工具：`apps_list`, `app_open`, `app_info`, `apps_installed_list`

iOS 替换：

- URL scheme / Universal Link 打开已知目标。
- `canOpenURL` 需要 `LSApplicationQueriesSchemes` 白名单。
- 无全量枚举安装 App。

处理：

- 删除 `apps_installed_list`。
- `apps_list` 改为“AmberAgent 已配置可打开目标列表”，不是系统安装列表。
- `app_open` 只允许白名单 scheme。

审批：

- 打开 App：Normal/Sensitive，视是否携带 URL payload。
- 携带用户数据打开第三方 App：High。

## Android Manifest 中额外能力处理

### Camera

Android 当前 Manifest 有 `CAMERA`，但 `AgentPermissionBroker` 没把它纳入 system access registry。iOS 应把它作为平台能力：

- capability：`ios.camera.capture`
- Info.plist：`NSCameraUsageDescription`
- 工具：`camera_capture` / attachment capture
- 风险：High
- 策略：用户前台触发，不能后台拍摄。

### Notifications post

Android 有 `POST_NOTIFICATIONS`。iOS 对应：

- UserNotifications。
- 工具：`notification_post`, `notification_schedule`。
- 风险：Sensitive。

注意：这只是发送 AmberAgent 自己的通知，不是读取其他 App 通知。

### Screen capture / MediaProjection

Android 有 `FOREGROUND_SERVICE_MEDIA_PROJECTION` 和 `ScreenCaptureService`。

iOS 普通 App 不能随意全局截屏其他 App 内容。可选替代：

- 用户手动分享截图到 AmberAgent。
- 用户在 App 内选择图片。
- ReplayKit 只适用于特定录屏/直播场景，不适合作为通用 agent screen access。

处理：

- `screen_*` 全局控制/读取类工具在 iOS 首版删除。
- 保留“用户导入截图后分析”的能力。

### Accessibility automation

Android 有 `AmberAccessibilityService`。

iOS 对第三方 App 不提供同等系统级无障碍自动化能力。处理：

- 删除 screen automation / click / type 这类跨 App 工具。
- iOS App 内 UI 自动化仅限测试，不作为生产 agent 工具。

### Termux external process

Android 用 `com.termux.permission.RUN_COMMAND`。

iOS 不支持任意外部进程执行。处理：

- 删除 terminal / external process 工具。
- 如果未来做代码执行，需要远端 sandbox 或受限本地解释器，不属于系统权限替换。

### Share sheet / Intent

Android 有 intent/share/open app。

iOS 替代：

- `UIActivityViewController` 分享文本/文件。
- `UIApplication.open` 打开 URL。
- MessageUI/Mail compose。

策略：

- 分享到外部 App 视为数据外发，默认 High。
- 必须显示将分享的内容摘要和目标类型。

## iOS 新增能力清单

这些能力不是 Android 当前等价权限的简单替换，但 iOS 版本应纳入设计。

### HealthKit

新增 capability：

- `ios.health.read`
- `ios.health.write`

需要：

- HealthKit entitlement。
- `NSHealthShareUsageDescription`。
- 写入时还需要 `NSHealthUpdateUsageDescription`。

首版建议：

- 只做 `health_step_count_summary`。
- 只读当天或用户指定短时间范围。
- 不做心率、睡眠、医疗记录、生殖健康、写入。

审批：

- HealthKit 读取默认 Sensitive。
- 心率/睡眠/医疗类 High。
- 写入 always ask。

### Core Motion / Fitness

新增 capability：

- `ios.motion.activity`
- `ios.motion.pedometer`

需要：

- `NSMotionUsageDescription`。

首版建议：

- `motion_authorization_status`
- `motion_pedometer_summary`
- 不做后台持续采样。

说明：

- “从运动应用获取运动数据”不应理解为读取 Fitness app 私有数据库。
- Workout / Activity Summary 更适合经 HealthKit 读取。

### Photos limited library / Picker

新增 capability：

- `ios.photos.picker`
- `ios.photos.limited_library`

首版建议：

- 优先 PhotosPicker，不请求全库。
- 用户选中的照片/视频进入附件流。
- 如果后续要相册搜索，再做 PhotoKit limited/full authorization。

### Security-scoped files

新增 capability：

- `ios.files.user_selected_read`
- `ios.files.user_selected_write`

需要：

- Document Picker / SwiftUI fileImporter。
- security-scoped bookmark。

首版建议：

- 用户选择单文件。
- read grant 绑定 file id。
- 写入只做 export 或保存副本。

### App Intents / Shortcuts

新增 capability：

- `ios.app_intents`

用途：

- 暴露 AmberAgent 的安全入口给 Siri / Shortcuts / Spotlight。

策略：

- App Intent 触发的 agent 动作必须进入同一套工具审批。
- App Intent 不能成为绕过审批的后门。

### Live Activities / Dynamic Island

新增 capability：

- `ios.live_activity`

用途：

- 展示长任务状态，而不是保证后台执行。

策略：

- 不替代 Android foreground service。
- 只展示状态，不授予工具访问权限。

## 建议的 iOS 首版权限 Manifest

不要一次性添加所有 usage description。Apple 权限弹窗文案应与真实功能对应。首版建议分层：

### P0/P1 必备

当前 iOS vertical slice 只需要网络和模型配置，不需要系统隐私权限。

应保持：

- 不新增 HealthKit / Contacts / Calendar / Photos 等权限。
- 不在 `Info.plist` 提前声明未使用的敏感权限。

### P6 文件与附件

新增：

- PhotosPicker：优先不需要全库 usage description。
- Camera 如支持拍照：`NSCameraUsageDescription`。
- Microphone 如支持录音：`NSMicrophoneUsageDescription`。

### P6 平台能力

按功能逐步新增：

| 功能 | Info.plist / entitlement |
|---|---|
| Contacts search/write | `NSContactsUsageDescription` |
| Calendar read/write | calendar full/write-only usage description |
| Location current | `NSLocationWhenInUseUsageDescription` |
| Motion / pedometer | `NSMotionUsageDescription` |
| Health read | HealthKit entitlement + `NSHealthShareUsageDescription` |
| Health write | HealthKit entitlement + `NSHealthUpdateUsageDescription` |
| Notifications | UserNotifications runtime authorization |
| Bluetooth | Bluetooth usage description |
| Local Network | Local network usage description |

## 推荐工具替换清单

### 保留但 iOS 重写

| Android tool | iOS tool | 说明 |
|---|---|---|
| `contacts_search` | `contacts_search` | query only, masked result |
| `contacts_write` | `contacts_create_draft` | draft-first |
| `calendar_list` | `calendar_list` | time-range required |
| `calendar_create` | `calendar_create_draft` | draft-first |
| `location_current` | `location_current` | approximate default |
| `audio_record_once` | `audio_record_once` | foreground only |
| `media_search` | `photo_pick` / `video_pick` | picker-first |
| `notification_post` | `notification_post` | only AmberAgent notifications |
| `share_text` | `share_text` | UIActivityViewController |
| `share_file` | `share_file` | UIActivityViewController |
| `app_open` | `app_open_url` | whitelist schemes only |

### 删除

| Android tool | 原因 |
|---|---|
| `sms_list` | iOS 不允许读短信 |
| `sms_read` | iOS 不允许读短信 |
| `call_log_list` | iOS 不允许读通话记录 |
| `device_phone_state` | iOS 无等价电话/SIM 状态 API |
| `notification_list` | iOS 不允许读其他 App 通知 |
| `usage_stats_list` | iOS 无全局 App 使用统计 API |
| `external_file_*` 全文件模式 | iOS 无全文件访问，改 user-selected scope |
| `apps_installed_list` | iOS 不允许枚举安装 App |
| `terminal_*` | iOS 不支持外部进程/Termux |
| `screen_*` 跨 App 控制 | iOS 不支持生产级跨 App 自动化 |

### 降级

| Android tool | iOS 降级 |
|---|---|
| `sms_send` | `sms_compose_draft`，用户在系统 UI 中发送 |
| `call_phone` | `phone_open_dialer`，系统确认 |
| `manage_all_files` | Document Picker selected file/folder |
| `exact_alarm` | scheduled notification，不能保证后台执行 |
| `foreground_service` 长任务 | Live Activity / notification 状态展示 |

### 新增

| iOS tool | Capability | 首版建议 |
|---|---|---|
| `health_authorization_status` | HealthKit | 可以先做状态 |
| `health_step_count_summary` | HealthKit read | 首个健康数据工具 |
| `motion_authorization_status` | Core Motion | 可以先做状态 |
| `motion_pedometer_summary` | Core Motion | 当天步数/距离 |
| `file_pick` | Document Picker | 文件入口 |
| `file_read_selected` | security-scoped file | 只读用户选择文件 |
| `file_export` | document export | 写副本，不覆盖 |
| `photo_pick` | PhotosPicker | 图片附件 |
| `video_pick` | PhotosPicker | 视频附件 |
| `app_intent_start_chat` | App Intents | 系统入口 |
| `live_activity_status` | Live Activities | 长任务展示 |

## iOS 审批规则调整

Android 当前 `PermissionDecisionResolver` 支持：

- global auto approval
- high-risk auto approval
- run trust
- mandatory approval
- subagent 限制

iOS 需要新增更硬的约束：

### 1. Platform-sensitive tools 不允许 global auto approval

以下 capability 即使用户开启自动批准，也不能静默执行：

- HealthKit read/write
- precise location
- contacts write
- calendar write/delete
- file write/delete
- microphone recording
- camera capture
- share/send to external app

### 2. 系统授权和工具审批分开

流程：

1. App 内 rationale。
2. 系统授权弹窗。
3. 工具审批卡片。
4. 执行工具。
5. 审计。

不要把“允许系统权限”按钮等价为“允许 agent 本次读取/写入”。

### 3. Run-scoped approval 绑定 scope

同一 run 内可复用的批准必须绑定：

- capability id
- operation
- scope digest
- payload digest
- tool name
- expiry time
- max uses

例如：

- 允许读取“今天步数汇总”不等于允许读取“过去一年心率”。
- 允许读取“foo.pdf”不等于允许读取同目录所有文件。
- 允许搜索联系人 “Alice” 不等于允许导出通讯录。

### 4. subagent 默认禁止平台敏感工具

subagent 不应直接调用：

- HealthKit
- Location
- Contacts
- Calendar write
- Files write/delete
- Camera/Microphone
- Share/send

如果确实需要，必须由 main agent 发起审批并签发窄 scope grant。

## iOS 权限 UI 建议

### Settings > Tool Permissions

按平台域分组：

- Health & Fitness
- Location
- Contacts
- Calendar & Reminders
- Photos & Files
- Camera & Microphone
- Notifications
- External Apps & Sharing
- Unsupported Android-only capabilities

每个 capability 显示：

- 系统授权状态：Not Determined / Granted / Denied / Restricted / Limited / Unsupported。
- Agent 策略：Disabled / Ask every time / Allow once per run。
- 支持的工具列表。
- 最近使用记录。

### 审批卡片

审批卡片显示：

- 动作：读取今天步数 / 搜索联系人 / 创建日历事件。
- 数据域：Health / Contacts / Calendar / Files。
- 范围：时间范围、query、文件名、位置精度。
- 是否外发给模型。
- 批准有效期。

按钮：

- 允许一次
- 本轮同范围允许
- 拒绝

对 High 工具不显示“总是允许”。

## 落地前决策点

### 决策 1：首个 iOS 系统能力做哪个？

建议顺序：

1. Document Picker selected file。
2. PhotosPicker attachment。
3. Motion pedometer summary。
4. HealthKit step count summary。
5. Contacts search。
6. Calendar read/write draft。

原因：

- 文件/照片最贴近聊天附件和当前产品价值。
- Motion/HealthKit 可验证 iOS 新增能力，但隐私风险更高，适合在审批模型成熟后做。

### 决策 2：HealthKit 是否进入默认工具列表？

建议：不进入。

HealthKit 工具应默认 disabled，只有用户在 Settings 明确开启后才加入 prompt/tool schema。

### 决策 3：是否保留 Android-only 工具名？

建议：

- 对语义一致的工具保留名称，如 `contacts_search`, `calendar_list`。
- 对语义降级的工具改名，如 `sms_send` -> `sms_compose_draft`。
- 对 iOS 不支持的工具保留 unsupported metadata，但不暴露给模型。

### 决策 4：是否提前写入所有 Info.plist usage descriptions？

建议：不要。

只在实现对应功能时添加 usage description 和 entitlement，避免 App Store 隐私声明和实际功能不一致。

## 推荐落地任务拆分

### P6-A：权限替换表进入代码

- 新建 `PlatformCapabilityRegistry` common model。
- 新建 `IosCapabilityRegistry`。
- 把 Android capability 映射到 iOS status：supported / degraded / unsupported / ios-only。
- 写单元测试覆盖所有 Android capability。

### P6-B：iOS Tool Filter

- 在 iOS tool registry 阶段过滤 unsupported tools。
- 设置页可查看 unsupported reason。
- 模型不可见 unsupported tools。

### P6-C：审批 resolver 增强

- 新增 `PlatformToolGate`。
- high-risk iOS platform tools 不被 global auto approval 绕过。
- run-scoped grant 绑定 scope digest。

### P6-D：第一个实际能力：Document Picker

- 实现 `file_pick`。
- 实现 `file_read_selected`。
- security-scoped bookmark 审计。
- 不实现全目录写入。

### P6-E：Motion/HealthKit 实验切片

- Motion：当天步数摘要。
- HealthKit：step count daily summary。
- HealthKit 默认 disabled。

## 验收标准

调研落地后至少满足：

- iOS 工具列表中没有 `sms_read`, `call_log_list`, `notification_list`, `usage_stats_list`, `terminal_*`, `apps_installed_list`。
- iOS 设置页能解释 Android-only 能力为什么 unsupported。
- HealthKit/Motion/Location/Contacts/Calendar/File write 不能被普通 auto approval 静默执行。
- 用户选择一个文件后，agent 不能读取同目录其他文件。
- 用户允许读取今天步数后，agent 不能借此读取心率、睡眠或长期历史。
- 所有 iOS privacy usage description 都对应真实已实现功能。
