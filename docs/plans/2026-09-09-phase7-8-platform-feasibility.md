# Phase 7/8 Android 平台可行性预检

范围：只读核查 Android 当前仓库，覆盖 E02 Health Connect、提醒/闹钟、运动计划，以及 E01 Wear companion。约束是**不新增依赖**，本轮不改源码、不改 Gradle、不跑构建/测试、不连接个人设备或账号。下文的“可做”指有真实系统 API、持久化和恢复路径可以落地；打开设置页、显示占位状态或自制一套未定义的设备协议不算完成。

## 结论

在现有依赖下，E02 可以先做一个受平台版本约束的 Health Connect 适配（普通记录为 Android 14/API 34+）和真正的 App 闹钟/提醒；Android 9–13 的 Health Connect 不能获得完整覆盖，因为仓库没有 Jetpack Health Connect client。计划运动的公共平台记录、Builder 和权限在 SDK 37 中是 API 35+，不能按 API 34+ 或一个未在平台 SDK 中出现的 `FEATURE_PLANNED_EXERCISE` 来承诺；只把计划存进本地数据库不能称为 Health Connect 运动计划。

E01 需要拆成通信方案来判断。当前仓库没有 Wear target、Wear 服务或 Android 手机入站任务端点，因此“按现状可交付”是不成立的；但没有 Data Layer **不等于**网络方案不可能。官方 Wear 文档支持 Wear 应用直接发 HTTP/TCP/UDP 请求，蓝牙连接时网络流量通常可经手机代理，Wi-Fi/蜂窝网络也可用。若已有可路由的手机端或共享后端端点，平台 `HttpURLConnection` 加现有持久化/WorkManager 可以承载带稳定 request ID 的幂等交接；本仓库目前没有这个端点、协议或端点授权输入，所以只能判为条件可行。Data Layer 是本地手机↔手表的官方推荐路径之一，但它确实需要 wearable 依赖和第二端 target。

| 范围 | Android 当前事实 | 不加依赖的最小可行面 | 真正阻断/验收 |
|---|---|---|---|
| Health Connect | `compileSdk=37`、`minSdk=26`、`targetSdk=37`（`/Users/mi/Downloads/AI/AmberAgent/android/app/build.gradle.kts:102-111`）；依赖表没有 `androidx.health.connect:connect-client`（`/Users/mi/Downloads/AI/AmberAgent/android/app/build.gradle.kts:599-611`、`/Users/mi/Downloads/AI/AmberAgent/android/gradle/libs.versions.toml:1-66`）；源码/Manifest 没有 Health Connect API 或 `android.permission.health.*`。现有 `ACTIVITY_RECOGNITION` 只是普通活动识别能力（`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/AndroidManifest.xml:39`、`/Users/mi/Downloads/AI/AmberAgent/android/feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt:388-396`）。 | API 34+ 用平台 `android.health.connect.HealthConnectManager`，严格 `SDK_INT >= 34` 分支；声明实际需要的 health 权限，走 Health Connect 的系统授权/引导，再读写已确认的记录类型。API 26–33 明确返回“当前版本不支持”，不伪装成已接通。 | 官方说明 Android 14 将 Health Connect 纳入系统，Android 13 及以下通常通过 Health Connect app；常规跨版本接入使用 `androidx.health.connect:connect-client`。因此在“不加依赖”约束下无法承诺全 `minSdk` 覆盖。需要真机/模拟器验证服务存在、授权撤销、分页/变更 token、后台同步和数据时区边界。 |
| 提醒/闹钟 | Manifest 已声明 `SCHEDULE_EXACT_ALARM`（`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/AndroidManifest.xml:44`）；`AgentPermissionBroker` 只负责检查 `canScheduleExactAlarms()` 和打开 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`（`/Users/mi/Downloads/AI/AmberAgent/android/feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt:147-192`，注册项 `:427-433`）。仓库搜索不到 `AlarmManager` 的实际 schedule、闹钟 receiver 或 `RECEIVE_BOOT_COMPLETED`。Cron、Board、DeepRead 都是 WorkManager 延迟任务（`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/cron/AgentCronManager.kt:276-288`、`/Users/mi/Downloads/AI/AmberAgent/android/feature/board/src/main/kotlin/app/amber/feature/board/worker/BoardScheduler.kt:30-61`、`/Users/mi/Downloads/AI/AmberAgent/android/feature/board/src/main/kotlin/app/amber/feature/board/hotlist/deepread/DeepReadScheduler.kt:14-85`）。 | 新增 app-owned reminder store（可复用现有 DataStore/原子文件持久化模式）、稳定 reminder id/title/time/recurrence/nextFire；用户要求精确时刻且权限可用时使用 `AlarmManager.setExactAndAllowWhileIdle`，否则显式标注近似并用 `setWindow`/WorkManager。receiver 触发后通过已有 `NotificationUtil`（`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/utils/NotificationUtil.kt:98-224`）发通知并深链到 owner；补 `BOOT_COMPLETED`、时区/系统时间变化和 exact-alarm 权限变化后的重排。可复用 Cron 的列表、状态、启动重排样式，但不把 Cron 当作提醒 owner。 | Exact alarm 是受用户授权、Doze、厂商电量策略影响的系统能力；Android 官方不保证权限被拒时的精确投递。已有 `POST_NOTIFICATIONS`（`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/AndroidManifest.xml:8`）仍需运行时授权。必须验收锁屏/Doze、重启、改时区、撤销精确闹钟权限、通知权限拒绝和重复触发去重。 |
| 运动计划 | Android 没有 Health Connect/Workout 代码或运动计划 owner。SDK 37 的 `android.jar` 确实公开了 `PlannedExerciseSessionRecord`、`PlannedExerciseBlock`、`PlannedExerciseStep` 及其 Builder，但它们和 `READ/WRITE_PLANNED_EXERCISE` 权限均标注 API 35+；普通 Health Connect 管理器和普通记录路径是 API 34+。 | API 35+ 才可在平台类上做 planned-exercise 的版本分支和授权后读写；API 34 只能走已确认的普通记录或把计划明确为 App-owned 本地模型。若仅做 App 内计划，由提醒调度器触发通知。 | SDK 37 的平台 API 版本表没有 `FEATURE_PLANNED_EXERCISE` 这个 PackageManager feature 常量；它是文档/Jetpack 语境中的能力名，不能直接当作平台字段。不能把本地 JSON、普通提醒或第三方设置页冒充跨应用运动计划。仍需在目标 API/Health Connect 服务上验证记录类型、授权、冲突和写入可见性。 |
| Wear companion | 没有 Wear/watch module、Wear manifest service、`DataClient`/`MessageClient`/`CapabilityClient`/`Wearable`，也没有 `com.google.android.gms:play-services-wearable`（`/Users/mi/Downloads/AI/AmberAgent/android/settings.gradle.kts:48-95`、`/Users/mi/Downloads/AI/AmberAgent/android/app/build.gradle.kts:599-611`）。已有蓝牙权限/`nearby_devices` broker（`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/AndroidManifest.xml:35-38`、`/Users/mi/Downloads/AI/AmberAgent/android/feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt:376-386`）只表示系统权限，不表示 companion transport。`HttpURLConnection`、OkHttp、Ktor 在本仓库都是出站 client；唯一的 `ServerSocket` 是绑定 `127.0.0.1` 的一次性 OAuth 回调（`/Users/mi/Downloads/AI/AmberAgent/android/common/src/main/java/app/amber/common/oauth/LoopbackOAuthCallbackServer.kt:28-35,43-80`）。 | Wear 网络交接在**已有真实端点**的前提下可不新增 HTTP 库：手表本地先持久化 envelope/request ID，平台 HTTP POST；手机原子记录 request ID、返回已保存结果并用现有 WorkManager/任务 store 执行长任务；手表仅对超时/断线用同一 ID 重试。直连手机需要手机运行可路由的长期 listener，通常要求同一 LAN 或明确可用的 Bluetooth/Wi-Fi 路由；共享后端则不要求同 LAN，但需要现成后端、手机拉取/推送和端点授权。若选官方 Data Layer，则需要新增 wearable target 与 Google Play services 依赖，DataClient 适合离线同步，MessageClient 仍需应用层重试。 | 当前 Android 没有手机 listener、Wear APK、共享 relay 或 task handoff API；Synara 现有协议连接的是 Mac 工作台（见下文），不能把它算作手机端点。`CompanionDeviceManager` 只做配对/发现/存在性协助，不自动建立数据连接；它可与已有 Bluetooth/Wi-Fi 传输结合，但不能替代端点、协议和结果幂等。真正验收需实体 watch/phone、断网重试、重复 request ID、进程/重启恢复和端点授权证据。 |

## 可复用 owner 与建议落点

- **权限/系统入口**：`AgentPermissionBroker` 已统一普通运行时权限和 exact-alarm 特殊访问状态；Health Connect 权限不能直接套 `ContextCompat.checkSelfPermission`，应由独立 Health Connect adapter 通过系统 manager 的授权结果管理。现有 broker 可复用 capability 展示，但不能把“打开设置”当作授权成功。
- **持久化/恢复**：`/Users/mi/Downloads/AI/AmberAgent/android/feature/task/src/main/kotlin/app/amber/feature/task/AgentTaskStore.kt:19-35,38-109,196-233` 有快照目录、原子更新、取消/重试和启动恢复；可借鉴其 durable-first 规则。提醒需要自己定义 reminder 状态机与 nextFire，启动时重排；不能把 AI Cron 的 prompt 任务数据直接当成提醒数据。
- **已有调度/通知**：`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/cron/AgentCronManager.kt:35-90,193-288` 和 `/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/cron/AgentCronWorker.kt:30-118` 负责 AI cron 执行；`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/utils/NotificationUtil.kt:98-224` 已能创建通知、action、进度和 deep link。E02 应新增明确的 Reminder owner/receiver，复用通知基础设施和设置页的列表/状态交互。
- **启动重排模式**：`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/agent/AmberAgentApp.kt:119-120,261-265` 已在启动时重排 Cron。提醒可以沿同一生命周期接线；系统广播 receiver 负责重启、时间变化、精确闹钟权限变化，不要依赖进程常驻。

## Wear 网络路径复核

官方 Wear 文档明确区分了两条路径：Data Layer 用于 Android 手机与 Wear OS 的本地同步；普通网络通信则可以让手表直接发 HTTP/TCP/UDP，请求在蓝牙连接时通常经手机代理，手表也可以使用自己的 Wi-Fi/蜂窝网络。长请求应交给 WorkManager。由此可得：

| 路径 | 可行性判定 | 网络与持久化边界 |
|---|---|---|
| Wear → 现有 Synara | **不能满足 Android 手机交接** | Synara 的 Android 入口只保存 Mac 主机/端口/token，做健康检查和 WebSocket/WebView 远端工作台连接；连接约束是手机与 Mac 同一 LAN/Tailscale。它没有 Android 手机任务端点、request ID 或结果队列。复用它最多把请求送到 Mac，不能把手机作为任务 owner。 |
| Wear → Android 手机直连 HTTP | **条件可行，当前未具备** | `HttpURLConnection` 足以做客户端 POST；但手机必须另有可路由的长期 listener 和已批准的协议/端点授权。直连通常要求同一 LAN，或明确可用的 Bluetooth/Wi-Fi 路由；官方“蓝牙流量经手机代理”只说明出站网络路径，不能推导手机应用自动拥有入站端口。当前仓库没有该 listener。 |
| Wear → 共享后端 HTTP | **条件可行，当前未具备** | 手表可走蓝牙代理、Wi-Fi 或蜂窝访问后端，因此不要求和手机同 LAN；手机需要拉取/接收并把结果落到本地。仓库没有现成 relay、FCM 交接或 phone polling owner，需外部后端/端点协议和授权输入。 |
| Wear Data Layer | **架构可行，但违反“不新增依赖”约束** | 官方 `DataClient` 的 DataItem 可离线持久化并同步；`MessageClient` 是 best-effort，需要应用层 ACK/重试。要有第二个 Wear target、Google Play services wearable、两端签名/包关系和实体配对验收。 |
| CompanionDeviceManager | **仅配对/发现协助** | 官方 API 不自行建立持续数据连接；可在已有 Bluetooth/Wi-Fi 连接方案旁提供关联、presence 和后台权限帮助，不能替代数据传输、端点或幂等结果。 |

本仓库的接线证据与上述判定一致：

- 出站 HTTP：`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/tools/WorkspaceArtifactTools.kt:454-462` 和 `/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/extensions/SkillsVM.kt:201-210` 创建 `HttpURLConnection`；`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/ai/mcp/transport/StreamableHttpClientTransport.kt:51-68,89-125,225-271` 是 MCP HTTP/SSE client。它们都不监听手机端口。版本目录虽声明 Ktor server artifact（`/Users/mi/Downloads/AI/AmberAgent/android/gradle/libs.versions.toml:162-175`），代码和 Gradle 没有实际 server 使用。
- 唯一的 Android `ServerSocket` 是 `/Users/mi/Downloads/AI/AmberAgent/android/common/src/main/java/app/amber/common/oauth/LoopbackOAuthCallbackServer.kt:28-35,43-80`，绑定 `127.0.0.1`、接收一次 OAuth 回调后关闭，不能作为 Wear 入站服务。`jmdns` 虽在版本/依赖表中（`/Users/mi/Downloads/AI/AmberAgent/android/gradle/libs.versions.toml:64,177`、`/Users/mi/Downloads/AI/AmberAgent/android/app/build.gradle.kts:717-718`），仓库没有实际 `JmDNS`/`ServiceInfo` 或 listener。
- Synara 的连接 owner 是 Mac 工作台：`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/synara/SynaraConnection.kt:5-14,25-44,60-83` 构造 HTTP/WS/health URL 和 QR token；`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/synara/SynaraVM.kt:78-153` 只做 health GET 与 WS 探测；`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/synara/SynaraWorkspacePage.kt:39-47,209-245` 是带 token 的远程 WebView；`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/synara/SynaraConnectPage.kt:49-57,78-99,122-150,227-256` 指向 Mac bridge。`/Users/mi/Downloads/AI/AmberAgent/android/scripts/synara-lan-bridge.py:251-321` 的 `0.0.0.0` listener 运行在外部 Mac Python bridge，不在 Android 手机进程。
- 收到任务后可复用的手机 owner 已存在：`/Users/mi/Downloads/AI/AmberAgent/android/feature/task/src/main/kotlin/app/amber/feature/task/AgentTaskStore.kt:19-35,38-109,196-233` 提供 durable snapshot、原子更新、取消/重试和启动恢复；WorkManager 也已在 app 使用。缺口是入站 adapter/endpoint 和任务 envelope，并非存储/恢复基础设施。

因此，若后续明确提供一个真实 phone/backend endpoint，最小交接契约只需定义稳定 `requestId`、设备/操作/payload、状态（accepted/pending/complete/failed）和结果引用：手表先落盘再发送；手机先原子登记 ID 后执行，重复 ID 直接返回已保存结果；手表仅在连接失败/超时用同一 ID 重试。长任务交给手机已有 WorkManager/`AgentTaskStore`。这依赖外部端点、协议和现有授权，不应临时发明未审定的安全体系；在这些输入缺失时，不能声称 E01 已实现。

## SDK 37 的本地 API 证据

本机 SDK 37 的实际文件是 `/Users/mi/Library/Android/sdk/platforms/android-37.0/android.jar`，并以同目录 `data/api-versions.xml` 核对版本标注：

- `android.health.connect.HealthConnectManager` 及 aggregate/read/insert/update/delete/change-log 方法是公共 API 34（`api-versions.xml:26112-26133`）。
- `HealthPermissions.READ_PLANNED_EXERCISE`/`WRITE_PLANNED_EXERCISE` 是 API 35（`api-versions.xml:26191,26294`）；`PlannedExerciseSessionRecord`、`PlannedExerciseBlock`、`PlannedExerciseStep` 及 Builder 是 API 35（`api-versions.xml:27771-27839`）。
- 用该 `android.jar` 对 `/tmp/HealthApiCheck.java` 做了直接 `javac -cp .../android-37.0/android.jar` 编译，导入 `HealthConnectManager`、`HealthPermissions`、读取请求和三类 planned-exercise Builder 并调用 `readRecords`，编译成功。这个检查只证明 SDK 公共签名可编译，不证明目标设备的 Health Connect 服务、授权或记录互操作已经可用。
- 在 platform SDK 的 API 版本表中没有名为 `FEATURE_PLANNED_EXERCISE` 的平台字段；后续若使用同名 Jetpack feature check，应把它视为另一个依赖/契约选择，不能据此倒推出 API 34 的平台能力。

所以“不新增依赖”的精确结论是：Health Connect 普通记录可做 API 34+ 的 platform adapter；planned exercise 只能从 API 35+ 做平台分支；提醒可用系统 AlarmManager 但需补完整 owner/receiver；Wear 的网络 client 可复用平台 API，但在本仓库缺少真实入站端点，Data Layer 方案则明确需要新增 wearable 依赖和 target。

## 最小落地顺序

1. **先定能力边界**：建版本/feature gate；API 34+ 才创建 `HealthConnectManager`，API 26–33 返回可解释的 unsupported 状态。列出所需记录类型、读写权限、数据时间范围和撤销后的状态；不要先添加依赖或假定所有设备都有 Health Connect。
2. **Health Connect adapter**：接入 API 34+ 的系统授权/引导、读写、分页和变更同步；把授权、服务不可用、权限撤销、记录冲突和时区转换落为可持久化状态。若需要 Android 13 及以下或跨设备统一行为，必须重新评估允许加入官方 Jetpack client，这是当前约束下的外部决策。
3. **提醒 owner**：定义 durable schema 和幂等 fire key；精确权限可用时才创建 exact alarm，否则使用不精确调度并在 UI 告知时间语义。receiver 只做短任务/通知，较长工作交给 WorkManager；启动、重启、时区和权限变化均调用同一个 `rescheduleAll`。
4. **运动计划**：先以真实 feature/record/permission 探针决定是否能写入计划；验证通过才实现 Health Connect 互操作。若产品允许本地计划，单独命名为 App-owned training plan，并复用提醒调度，验收不能写成 Health Connect plan。
5. **Wear**：先确认 E01 目标是 Wear OS 还是厂商 BLE。Wear OS 路径需单独 target、官方 wearable 依赖、phone/watch 两端数据契约和实体配对验收；BLE 路径需设备方提供协议和权限模型。拿不到这些输入时，保留明确不可用态比发布假同步更安全。

## 后续验收

本轮未运行任何 Gradle、测试或设备操作。代码阶段至少应补 JVM 的 schema/校验/恢复/幂等测试，API 34+ instrumentation 覆盖授权与记录读写，AlarmManager instrumentation 覆盖 exact/inexact、重启和时区；Health Connect 和 Wear 还需要签名应用、系统服务/Health Connect 版本、通知权限及实体设备证据。API 26–33 Health Connect 全链路、exact alarm 被拒后的近似策略、厂商后台限制和 Wear 真机配对目前都是外部验收项。

## 官方依据

- [Health Connect 入门与版本接入](https://developer.android.com/health-and-fitness/health-connect/get-started)
- [`HealthConnectManager` API（API 34）](https://developer.android.com/reference/android/health/connect/HealthConnectManager)
- [`HealthPermissions` API（API 34）](https://developer.android.com/reference/android/health/connect/HealthPermissions)
- [Health Connect 可用性](https://developer.android.com/health-and-fitness/health-connect/availability)
- [Health Connect Training Plans](https://developer.android.com/health-and-fitness/health-connect/features/training-plans)
- [Android exact alarms](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms) 与 [`AlarmManager`](https://developer.android.com/reference/android/app/AlarmManager)
- [Wear OS Data Layer 概览](https://developer.android.com/training/wearables/data/overview)
- [Wear OS Data Items](https://developer.android.com/training/wearables/data/data-items)
- [Wear OS 网络访问与同步](https://developer.android.com/training/wearables/data/network-communication)
- [Wear OS client 类型选择](https://developer.android.com/training/wearables/data/client-types) 与 [数据同步](https://developer.android.com/training/wearables/data/sync)
- [Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing) 与 [`CompanionDeviceManager`](https://developer.android.com/reference/android/companion/CompanionDeviceManager)
