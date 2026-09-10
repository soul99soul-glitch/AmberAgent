# Phase 8 Bluetooth/Wear 只读准备交接

日期：2026-09-10  
范围：只核实“无新增 Gradle 外部依赖”条件下，平台 Bluetooth Classic/RFCOMM 是否能承担 Android 手机↔Wear OS 双向 app transport，并与同 LAN HTTP 手机端点比较。没有实施代码、没有改 Gradle、没有运行构建/测试、没有连接真实设备或账号；本文件供主 agent 在恢复实施前使用。

## 已确认的官方证据

### RFCOMM 的平台能力与配对边界

- Android SDK 37 的 `/Users/mi/Library/Android/sdk/platforms/android-37.0/android.jar` 公开了 `BluetoothAdapter.listenUsingRfcommWithServiceRecord(String, UUID)`、`BluetoothDevice.createRfcommSocketToServiceRecord(UUID)`、`BluetoothServerSocket.accept()` 和 `BluetoothSocket.connect/getInputStream/getOutputStream`；本地 API 表将这些类/方法标为 API 5（`data/api-versions.xml:12731-12770,13235-13250,13833-13840`）。这证明平台 API 可编译，不证明目标 Wear 机型接受第三方 Classic RFCOMM。
- 官方 [Bluetooth 连接指南](https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices) 说明，RFCOMM 连接会在未配对时阻塞并等待系统配对；拒绝/超时会失败。两端必须分别承担 server/client 角色，server 保持 `BluetoothServerSocket`，client 使用与 SDP service record 相同的 UUID 后调用阻塞的 `connect()`。
- 官方 [`BluetoothDevice`](https://developer.android.com/reference/android/bluetooth/BluetoothDevice) API 明确 `createRfcommSocketToServiceRecord` 是安全的 BR/EDR RFCOMM 出站 socket，远端会认证、链路会加密；若设备的认证能力不足，安全 socket 可能无法建立。Android peer 应使用双方约定的自有 UUID，不要假定串口 SPP UUID。安全链路不等于应用层任务协议、幂等和授权已经存在。
- 因此，“已配对”只满足建立链路的一个前置条件：还需要两端都安装/运行对应 app、一个可访问的 RFCOMM server、相同 UUID、可工作的 BR/EDR 能力及 `BLUETOOTH_CONNECT`。配对本身不会创建 app service、启动监听进程或提供离线队列。

### 权限、后台与前台服务

- SDK 37 的 `android.Manifest.permission` 公开 `BLUETOOTH_SCAN`、`BLUETOOTH_CONNECT`、`BLUETOOTH_ADVERTISE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE` 和 companion background 相关权限（本地 `javap` 已核对）。Android 12/API 31+ 连接已配对设备或创建 RFCOMM server 至少要处理运行时 `BLUETOOTH_CONNECT`；扫描才需要 `BLUETOOTH_SCAN`，发现/advertising 才涉及 `BLUETOOTH_ADVERTISE`。API 26–30 的兼容分支仍需核对旧 `BLUETOOTH`/`BLUETOOTH_ADMIN` 声明，不能只靠新权限名。
- 官方 [`BluetoothAdapter.listenUsingRfcommWithServiceRecord`](https://developer.android.com/reference/kotlin/android/bluetooth/BluetoothAdapter#listenUsingRfcommWithServiceRecord(kotlin.String,java.util.UUID)) API 要求 API 31+ 的 `BLUETOOTH_CONNECT`。它创建安全 RFCOMM server 并注册 SDP record；关闭 socket 或进程异常退出后 record 消失。
- 官方 [前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device) 对 API 34+ 的 `connectedDevice` 要求：Manifest 同时声明通用 `FOREGROUND_SERVICE` 和 `FOREGROUND_SERVICE_CONNECTED_DEVICE`，service 声明 `android:foregroundServiceType="connectedDevice"`，并满足至少一个连接类运行时权限（包括已授予的 `BLUETOOTH_CONNECT`/`ADVERTISE`/`SCAN`）。启动还受 Android 12+ 后台 FGS 限制；这不是“有权限即可随时常驻”。
- 官方 [Bluetooth 后台连接指南](https://developer.android.com/develop/connectivity/bluetooth/ble/background) 说明进程被杀会关闭连接；短通信可由 WorkManager 触发，持续同步/监听需要在允许的启动时机使用 `connectedDevice` FGS，或使用 `CompanionDeviceService` 及对应 companion 后台权限/存在性 API。WorkManager 只能提供重试/约束，不会替 RFCOMM 保持 socket。

### Wear OS 特有的传输选择

- 官方 [Wear 网络通信](https://developer.android.com/training/wearables/data/network-communication) 支持手表直接发 HTTP/TCP/UDP；蓝牙连接手机时网络流量通常经手机代理，手机不可用时可走手表 Wi-Fi/蜂窝网络，并建议异步请求使用 WorkManager。这条路径不要求同一 LAN，前提是存在可访问的共享后端；若目标是直连手机私有 HTTP listener，则仍需同一 LAN 或明确可路由的链路。
- 官方 [Data Layer 概览](https://developer.android.com/training/wearables/data/overview) 将严格的 watch↔phone 交互（遥控、手机 app 启动、认证桥接）归入 Data Layer，并要求 Google Play services wearable、Wear target 及两端包名/签名关系；该文档也提醒不要用低层 socket 代替 Wear Data Layer。Data Layer 的本地链路自带标准 Bluetooth 加密和 Google Play services 管理的连接，`DataClient` 适合持久 DataItem，`MessageClient` 仍是 best-effort，需要应用层重试。
- [CompanionDeviceManager 配对](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing) 负责发现/关联和部分后台运行便利，但不会自行创建持续数据连接；仍需 Bluetooth/Wi-Fi 连接 API 或已定义的网络端点。不能把 companion association 当成 RFCOMM transport。

## 当前仓库证据

- 手机 app Manifest 已有 `BLUETOOTH_SCAN/CONNECT/ADVERTISE`（`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/AndroidManifest.xml:35-37`）和通用 `FOREGROUND_SERVICE`（`:47`），但没有 `FOREGROUND_SERVICE_CONNECTED_DEVICE`，现有服务使用的是 `specialUse`/`mediaProjection`（`:224-252`）；`AgentPermissionBroker` 的 `nearby_devices` 只注册这些附近设备运行时权限（`/Users/mi/Downloads/AI/AmberAgent/android/feature/system/src/main/kotlin/app/amber/feature/system/AgentPermissionBroker.kt:376-386`），没有 companion association/service 或 Wear transport owner。
- `settings.gradle.kts:49-95` 只有现有 Android modules，没有 Wear/watch module；`app/build.gradle.kts:599-611` 没有 `com.google.android.gms:play-services-wearable`。当前仓库不能仅凭手机权限声明声称已有 Wear app。
- HTTP、WebSocket 和 MCP 接线目前均为出站 client：`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/tools/WorkspaceArtifactTools.kt:454-462`、`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/extensions/SkillsVM.kt:201-210` 使用 `HttpURLConnection`；`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/core/ai/mcp/transport/StreamableHttpClientTransport.kt:51-68,89-125,225-271` 是 MCP HTTP/SSE client。版本目录虽列 Ktor server artifacts（`/Users/mi/Downloads/AI/AmberAgent/android/gradle/libs.versions.toml:162-175`），未找到实际 server 使用。
- 唯一 Android `ServerSocket` 是 `/Users/mi/Downloads/AI/AmberAgent/android/common/src/main/java/app/amber/common/oauth/LoopbackOAuthCallbackServer.kt:28-35,43-80` 的一次性 `127.0.0.1` OAuth 回调，不能接收手表请求。
- Synara Android 入口的 owner 是 Mac 工作台：`/Users/mi/Downloads/AI/AmberAgent/android/app/src/main/java/app/amber/feature/ui/pages/synara/SynaraConnection.kt:5-14,25-44,60-83` 构造 Mac HTTP/WS/health 地址和 QR token，`SynaraVM.kt:78-153` 只做健康检查/WS 探测，`SynaraWorkspacePage.kt:39-47,209-245` 是远端 WebView；`/Users/mi/Downloads/AI/AmberAgent/android/scripts/synara-lan-bridge.py:251-321` 的 listener 运行在外部 Mac bridge。它不能作为手机 RFCOMM/HTTP 任务端点，也不应复用来推导 Wear 手机交接。
- 手机已有可复用的任务 durable owner：`/Users/mi/Downloads/AI/AmberAgent/android/feature/task/src/main/kotlin/app/amber/feature/task/AgentTaskStore.kt:19-35,38-109,196-233` 提供快照、原子更新、取消/重试和启动恢复，WorkManager 已在 app 使用；缺的是入站 adapter、连接生命周期和交接 envelope。

## 结论与最小选择

### RFCOMM 判定

**技术上可行，产品上有明确限制。** 在两端都是 Android app、目标手表支持 Bluetooth Classic BR/EDR、两端可运行对应代码、手机/手表已配对或能完成系统配对、双方共享同一 UUID 且一端能保持 server 的前提下，平台 secure RFCOMM 可以做双向字节流 transport；它不需要 LAN、HTTP 库或 Data Layer Gradle 依赖。

但这不是当前 Wear 仓库的现成功能，也不是官方 Wear Data Layer 推荐的默认实现。Wear OS 官方文档将严格 watch↔phone 交互导向 Data Layer，并对低层 socket 有明确警告；RFCOMM 的平台存在性不能等同于所有 Wear 机型、所有配对形态和后台场景都支持。必须用目标真实手表验证 BR/EDR、配对后的 SDP/UUID 发现、两向流、锁屏/切 app、进程被杀、蓝牙断开重连和 OEM 电量策略。

### 无新增依赖时的最小实现候选

1. **受控设备范围下的 RFCOMM**：为手机和独立 Wear target 各提供同一 UUID 的 server/client；手机端仅在实际需要传输时用 `connectedDevice` FGS 保持短连接，收到 envelope 后立即落 `AgentTaskStore`，手表先持久化同一 `requestId` 再发送。连接失败/超时用同一 ID 重试，手机重复 ID 返回已保存状态/结果引用；长任务交给现有 WorkManager。这个候选不新增通信库，但仍需新增 Wear target、服务声明和 app 代码，且需批准既定的任务协议/授权边界，不能现场自制安全体系。
2. **已有真实端点时的 Wear HTTP**：若后端端点已存在，手表用平台 `HttpURLConnection`/WorkManager，手机通过后端拉取或推送；无同 LAN 要求。若坚持直连手机 HTTP，则手机必须新增可路由长期 listener，通常同 LAN，且需要端点发现/授权。当前仓库两者都没有，因此只能条件可行。
3. **官方 Data Layer**：作为严格手机↔手表交互的风险最低路径，但需要 `play-services-wearable`、Wear target、两端签名/包关系，违反本轮“不新增外部依赖”约束，不能在本阶段宣称可交付。

RFCOMM 与 HTTP 的关键差异是：RFCOMM 不需要 LAN、但要求 Classic Bluetooth server 在正确生命周期运行；HTTP 更符合 Wear 网络官方路径、可走独立网络、但必须先有真实 phone/backend endpoint。现阶段不能把现有 Synara 或 `CompanionDeviceManager` 当作其中任一个 endpoint。

## 必须留给真实手表/手机的未知项

- 目标 Wear OS 型号是否支持 BR/EDR Classic RFCOMM 及第三方 SDP service；BLE-only 或仅暴露特定 profile 的设备不能按 RFCOMM 估算。
- 目标手表 OS/API、Google Play services、手机 OS/API、同一账号/配对流程，以及两端 app 是否能同时安装和保持匹配 UUID。
- 手机进程在锁屏、切 app、Doze、厂商电池限制、蓝牙关闭/重启后的 server 存活；后台启动 `connectedDevice` FGS 是否满足当时的 Android 版本豁免，是否改用 CompanionDeviceService/存在性 API。
- 配对后能否取得并稳定重用 `BluetoothDevice`/bond identity；当前仓库没有持久化 peer ID 或 companion association owner。
- 任务 envelope 的字段、结果状态、重复 request ID 行为和已批准的授权/认证方案。链路加密不替代产品层授权，不能通过临时明文协议补洞。

## 文档/工作状态

- 先前的 Phase 7/8 只读计划已落在 [`docs/plans/2026-09-09-phase7-8-platform-feasibility.md`](../../plans/2026-09-09-phase7-8-platform-feasibility.md)，其中已记录 Health Connect、提醒、运动计划、Wear 网络/Data Layer 和现有 owner 证据；本 handoff 补充了 RFCOMM 平台 API、配对前置条件和 connectedDevice FGS 边界。
- 本轮只写入本 handoff 文档；没有改 Android 生产源码、Manifest、Gradle 或测试，没有运行构建/测试，没有连接真实手表、手机或账号。
- 恢复实施前的首个门槛是确认 E01 的目标设备范围，并用至少一台真实 Wear OS/Android 手机验证安全 RFCOMM 或确认采用 HTTP/Data Layer；在该验证前只能把 RFCOMM 标为“平台 API 技术可用、产品 transport 未验证”。
