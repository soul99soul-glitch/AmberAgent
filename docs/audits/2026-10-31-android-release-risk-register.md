# Android 发布风险登记（A1.4）

> 2026-10-31，只读核对 `app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`native/README.md` 与本次构建输出。
> 状态分为：**已验证**（本次有命令/构建证据）、**待人工**（Play Console / 资质 / 法务）、**未验证**（缺设备或流程）。

## 构建与产物

| ID | 风险 | 现状与证据 | 状态 | 需要的动作 |
| --- | --- | --- | --- | --- |
| R1 | `targetSdk = 37` 行为未在真机矩阵验证（edge-to-edge、预测式返回、后台限制、通知权限细化） | `app/build.gradle.kts` `targetSdk = 37`、`minSdk = 26`；本次仅在 android-34 arm64 模拟器启动 | 未验证 | Android 15/16 真机回归；确认 Play 当年 targetSdk 要求 |
| R2 | 16 KB page size 合规 | `:app:verifyFullDebugNativePageSize` 在本次 `assembleFullDebug` 中通过；`native/README.md:28` 要求每个 `.so` 的 `PT_LOAD` 对齐与文件/虚拟地址同余，`useLegacyPackaging = true` | 构建已验证；设备未验证 | 在 16 KB 页镜像上装包并跑 `EmbeddedTerminalStartupDeviceTest`（`native/README.md:39-58`） |
| R3 | Play 签名 / AAB / 版本号 | 本地 `signingConfigs.release` 读取 `local.properties` 的 `storeFile/storePassword/keyAlias`；当前工作区配置是 `androiddebugkey` 风格凭据；`versionCode 396`、`versionName 2.6.8`；发布构建 `isMinifyEnabled = true` | 待人工 | 换正式上传密钥并启用 Play App Signing；产出 AAB 而非 APK；确认 mapping 上传（`uploadCrashlyticsMapping`） |
| R4 | 产物包名/flavor 混淆 | `applicationId = app.amber.agent`；`graphite` debug 后缀 `.graphite`；`novel` 为 `app.amber.novel`；本次安装的是 `app.amber.agent.graphite` | 已验证（debug） | 发布前确认上架包为 `full`、非 debug 后缀 |

## 权限与政策

| ID | 风险 | 现状与证据 | 状态 | 需要的动作 |
| --- | --- | --- | --- | --- |
| R5 | 受限权限：`READ_SMS`/`RECEIVE_SMS`/`SEND_SMS`/`READ_CALL_LOG`/`CALL_PHONE`/`READ_CONTACTS`/`WRITE_CONTACTS`/`READ_PHONE_STATE`/`READ_PHONE_NUMBERS` | `AndroidManifest.xml:10-23`；这些是可选 Agent 工具能力，`hardware.telephony required=false` 只解决设备过滤，不解决 Play 权限审核 | 待人工 | 逐项决定「保留并申请例外 / 改为运行时按需 / 移除」；SMS 与 Call Log 需 Play 例外表单 |
| R6 | `QUERY_ALL_PACKAGES`、`SYSTEM_ALERT_WINDOW`、`PACKAGE_USAGE_STATS`、`SCHEDULE_EXACT_ALARM`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | `AndroidManifest.xml:48-58`；`screen_apps`/悬浮球/任务调度依赖其中多项 | 待人工 | 说明核心用途或改为更窄 API（如 `queryIntentActivities` 已部分使用）；Play 政策表单 |
| R7 | 健康权限 `health.READ_STEPS/HEART_RATE/SLEEP/WEIGHT` 与媒体/位置/麦克风/蓝牙/附近 Wi-Fi 权限 | `AndroidManifest.xml:22-45` | 待人工 | Data safety 与敏感权限声明；未使用的能力应移除 |
| R8 | 无障碍服务披露 | `amberagent_accessibility_service.xml`：`canRetrieveWindowContent`、`canPerformGestures`、`canTakeScreenshot`、`flagRetrieveInteractiveWindows`；描述串仅为一句 `amberagent_accessibility_service_desc` | 待人工 | Play 要求显著披露 + 用户同意 + 核心功能说明；核对首次启用前的应用内说明与隐私政策 |
| R9 | 屏幕内容/无障碍树/OCR 数据 | OCR 走 bundled 端侧模型（`ScreenOcrService`），但开启 Jev 时屏幕文本会发往远端决策服务（`JevScreenUsage`/`SCREEN_CONTENT` scope） | 待人工 | 隐私政策与 Data safety 必须覆盖「屏幕内容可能上传」；应用内授权文案与撤回路径 |
| R10 | 前台服务类型 | `FOREGROUND_SERVICE_SPECIAL_USE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`、`RECEIVE_BOOT_COMPLETED` | 待人工 | `specialUse` 需 Play 声明；核对 MediaProjection 前台服务通知与停止路径 |

## 终端与原生

| ID | 风险 | 现状与证据 | 状态 | 需要的动作 |
| --- | --- | --- | --- | --- |
| R11 | proot/loader 可执行路径依赖安装器解压 | `useLegacyPackaging = true`（`app/build.gradle.kts` packaging），8 个 required `.so` 缺失时 release/graphite/baseline 构建失败（`native/README.md:35`） | 构建已验证 | 真机安装后确认 `nativeLibraryDir` 内 loader 可执行、终端可启动 |
| R12 | Termux 互操作权限 | `com.termux.permission.RUN_COMMAND` 与 `<queries>` 中 `com.termux` | 待人工 | 说明用途；未安装 Termux 时确认不影响主流程 |

## 设备矩阵

| ID | 风险 | 现状与证据 | 状态 | 需要的动作 |
| --- | --- | --- | --- | --- |
| R13 | Android 8/12/15/16、折叠/大屏未覆盖 | 本次仅 android-34 arm64 模拟器（`emulator-5556`）启动、设置页与 OCR 设备用例 | 未验证 | 按 P6/A6.3 设备矩阵补测；记录 `getconf PAGE_SIZE` |

## 结论

- 构建侧有两项实测证据：Debug APK 组装（含 native 页大小校验）与模拟器安装启动；其余为**政策/资质/真机**工作，不能由代码自证。
- 上架前必须解决 R3（正式签名/AAB）、R5–R10（权限与披露）、R13（设备矩阵）；R2/R11 需要 16 KB 真机证据。
