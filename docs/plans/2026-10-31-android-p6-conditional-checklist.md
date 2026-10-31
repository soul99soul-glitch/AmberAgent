# Android 上架发布人工待办与真机验证清单（CONDITIONAL Checklist）

- **日期**：2026-10-31
- **适用分支**：`synara/amber-android-baseline-validation`
- **当前应用版本**：`versionName = "2.6.8"`, `versionCode = 396`, `applicationId = "app.amber.agent"`, `targetSdk = 37`, `minSdk = 26`

> 以下事项因依赖**生产签名密钥、Google Play Console 后台资质表单、法务政策页面或物理真机硬件**，无法在自动化构建工作区中由代码自证，特标注为 `CONDITIONAL` 供发布前逐项勾检。

---

## 1. 生产签名、Release AAB 与符号表归档（A6.1）

- [ ] **1.1 注入正式上传密钥（Upload Keystore）**
  - 在发布构建机 `local.properties` 或 CI Secret 中配置正式生产密钥（替换本地调试用的 `androiddebugkey`）：
    - `storeFile=<path-to-production-upload-keystore.jks>`
    - `storePassword=<secret>`
    - `keyAlias=<alias>`
    - `keyPassword=<secret>`
- [ ] **1.2 确认生产 `google-services.json`**
  - 将正式环境的 `google-services.json` 放入 `app/` 目录（确认包名 `app.amber.agent` 与生产 SHA-1 / SHA-256 证书指纹已在 Firebase 控制台注册）。
- [ ] **1.3 构建正式 Release AAB 并校验产物**
  - 执行命令：
    ```bash
    ./gradlew :app:bundleFullRelease
    ```
  - 核对产物：`app/build/outputs/bundle/fullRelease/app-full-release.aab`
  - 使用 `bundletool` 或 `apksigner` 确认签名证书为正式 Upload Key，且包名为 `app.amber.agent`（无 `.debug` 或 `.graphite` 后缀）。
- [ ] **1.4 归档 R8 Mapping 与 Native Debug Symbols**
  - 保存 `app/build/outputs/mapping/fullRelease/mapping.txt` 并上传至 Google Play Console / Firebase Crashlytics，确保线上混淆堆栈可反解。

---

## 2. Google Play Console 政策声明与隐私合规（A6.2）

- [ ] **2.1 无障碍服务（AccessibilityService）核心用途声明与演示视频**
  - Play Console 路径：**App content → Accessibility**
  - 说明 `AmberAccessibilityService`（`app/src/main/res/xml/amberagent_accessibility_service.xml`）仅在用户主动开启「屏幕代操作」并授权后用于辅助执行屏幕任务，不后台静默采集数据，且在支付/密码/删除等敏感节点强制移交人工接管。
  - 录制并上传包含**应用内显著披露弹窗（Prominent Disclosure）→ 用户点击同意跳转系统无障碍设置 → 执行任务与浮窗随时中断**的完整实机视频。
- [ ] **2.2 高风险/受限权限审核或裁剪（`AndroidManifest.xml`）**
  - Play Console 路径：**App content → Sensitive app permissions**
  - 决策并填写以下权限的例外声明表单（若首发商店版本不开放对应 Agent 工具能力，建议在商店上架分支从 `AndroidManifest.xml` 移除）：
    1. **SMS / Call Log 权限**（`READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `READ_CALL_LOG`, `CALL_PHONE`）：Google Play 对非默认短信/电话应用审核极严，若无豁免资质须在 Play 渠道包移除。
    2. **应用列表权限**（`QUERY_ALL_PACKAGES`）：说明用于手机代操作目标应用选择与启动（`screen_apps`），并附演示说明。
    3. **悬浮窗与后台能力**（`SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`, `SCHEDULE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`）：逐一填写 `specialUse` 与前台服务用例说明。
    4. **健康数据权限**（`android.permission.health.READ_STEPS / HEART_RATE / SLEEP / WEIGHT`）：填写 Health Connect 权限申报表。
- [ ] **2.3 更新线上隐私政策与 Play Data Safety 表单**
  - Play Console 路径：**App content → Data safety** & **Privacy policy**
  - 披露端侧数据存储（Room 会话数据库、Keystore 加密凭据、系统云备份已排除全部凭据与 OAuth `sharedpref`）。
  - 披露外部模型与可选 Jev 辅助判断服务的数据传输边界（启用时会将脱敏后的上下文或屏幕摘录发送至用户所选模型提供商）。

---

## 3. 物理真机矩阵与 16KB 页大小实机验证（A6.3 / E3）

- [ ] **3.1 16KB Page Size 真机/镜像原生终端启动验证**
  - 在 Android 15/16 16KB 页大小设备（如 Pixel 8/9 启用 16KB 开发者选项，确认 `adb shell getconf PAGE_SIZE` 输出 `16384`）上安装 Release/Debug 包。
  - 运行原生终端与 `proot/loader` 启动测试：
    ```bash
    ./gradlew :app:connectedFullDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.amber.agent.EmbeddedTerminalStartupDeviceTest
    ```
- [ ] **3.2 多代数 Android 系统与折叠屏物理真机回归**
  - **Android 8.0 / 8.1（API 26–27）**：验证 `minSdk` 边界冷启动、Keystore AES/GCM 凭据读写、通知渠道降级兼容。
  - **Android 12 / 13（API 31–33）**：验证近似/精确位置授权、蓝牙与通知运行时权限、前台服务启动限制。
  - **Android 15 / 16（API 35–37）**：验证 `targetSdk = 37` edge-to-edge 沉浸式栏位、预测式返回手势（Predictive Back）、MediaProjection 单应用/全屏投屏重授权、无障碍截屏与ML Kit 端侧 OCR 兜底。
  - **折叠屏 / 平板（展开态 ≥ 600dp / 840dp）**：验证折叠/展开动态切换时聊天流式输出不中断、接管悬浮球位置自愈、底部弹层与双栏布局无裁切。

---

## 4. 分阶段灰度放量与监控值守（A6.4）

- [ ] **4.1 按 Internal → Closed Alpha → Open Beta (5%) → Production (10% → 50% → 100%) 阶梯放量**
- [ ] **4.2 盯盘四项硬性回滚指标（见 `docs/plans/2026-10-31-android-p6-release-readiness.md` §1.4）**：
  - 高风险审批与凭据零明文落盘边界；
  - Room `20 -> 21` 迁移失败率 < `0.05%`；
  - 主进程 Crash-free sessions ≥ `99.2%`、ANR ≤ `0.35%`；
  - 屏幕代操作人工接管（`PhoneAutomationTakeoverController`）单步响应率 100%。
