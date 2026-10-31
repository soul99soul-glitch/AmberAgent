# Phase 6：Android 真机与发布准备验收报告（A6.1–A6.4 + 9 项跨端缺陷自查）

- **日期**：2026-10-31
- **工作项 ID**：`hub-work:ba70e7907fb83d16aca0e776eca8b4228e444189`
- **工作树**：`/Users/arquiel/.synara/worktrees/e5e767e345ee`
- **分支**：`synara/amber-android-baseline-validation`
- **前置提交（P1–P5 + P3/P4 审查修复）**：`7748e0522a2ba8f7468bb6bf6b274983dfc8295a`

---

## 1. Phase 6 各子项验收结果（A6.1–A6.4）

### 1.1 A6.1 构建产物、SDK 目标、16KB 页对齐与混淆签名
- **SDK 与版本配置**（`app/build.gradle.kts:143-152`）：
  - `namespace = "app.amber.agent"`
  - `compileSdk = 37`
  - `targetSdk = 37`
  - `minSdk = 26`
  - `versionCode = 396`
  - `versionName = "2.6.8"`
  - `ndk.abiFilters = ["arm64-v8a"]`（`app/build.gradle.kts:164-166`）
- **16KB Native 页对齐（Android 15/16 合规）**：
  - Rust 原生库通过 `-Wl,-z,max-page-size=16384` 与 `-Wl,-z,common-page-size=16384` 编译（`app/build.gradle.kts:507-508`）。
  - 构建期 `:app:verifyFullDebugNativePageSize` 校验任务在 `:app:assembleFullDebug` 中已通过，确认打包进 APK 的全部 `.so` 均满足 16KB ELF `PT_LOAD` 对齐要求。
  - **真机 16KB 页内核实测**：标注为 `CONDITIONAL`（需在 `getconf PAGE_SIZE = 16384` 的 Android 15/16 真机或 16KB 模拟器镜像上执行 `EmbeddedTerminalStartupDeviceTest`）。
- **R8 / ProGuard 规则与 Release AAB**：
  - 规则文件位于 `app/proguard-rules.pro`，结合 `app/src/main/baseline-prof.txt`（1,947 行预编译热点规则）。
  - **正式 Play 签名与 `bundleFullRelease`**：标注为 `CONDITIONAL`（本地环境无生产上传 `keystore` 与正式 `google-services.json`，上架前需由发布负责人注入正式凭据产出签名 AAB 并归档 R8 mapping）。

### 1.2 A6.2 隐私政策、Data Safety、无障碍披露与系统备份排除规则
- **系统云备份与设备迁移排除规则（已加固）**：
  - `app/src/main/res/xml/backup_rules.xml:8-44` 与 `app/src/main/res/xml/data_extraction_rules.xml:9-98`（同时覆盖 `<cloud-backup>` 与 `<device-transfer>`）已显式 `<exclude domain="sharedpref" ... />` 排除全部敏感凭据、小程序存储与 OAuth 令牌文件：
    - `mini_app_storage.xml`
    - `amberagent_secret_store.xml`
    - `amberagent_oauth_tokens.xml`
    - `amberagent_webmount_oauth_pending.xml`
    - `amberagent_webmount_oauth_tokens.xml`
    - `amberagent_webmount_oauth_creds.xml`
    - `amberagent_webmount_profiles.xml`
    - `antigravity_oauth.xml`
    - `google_gemini_oauth.xml`
    - `grok_web_oauth.xml`
    - `grok_web_oauth_backup.xml`
    - `openai_codex_oauth.xml`
- **无障碍服务（AccessibilityService）披露与边界**：
  - 配置文件：`app/src/main/res/xml/amberagent_accessibility_service.xml:1-10`（声明 `canRetrieveWindowContent="true"`、`canPerformGestures="true"`、`canTakeScreenshot="true"`）。
  - 运行时门禁：默认关闭（`settings.agentRuntime.screenAutomation.enabled == false`），开启前在设置页展示权限说明弹层，且敏感终态操作强制触发 `PhoneAutomationTakeoverController` 人工接管拦截。
  - **Play Console 政策表单与公开隐私政策 URL**：标注为 `CONDITIONAL`（需人工在 Play Console 提交 Accessibility Service 核心用途视频披露、SMS/Call Log/Query All Packages 权限声明或裁剪，以及更新线上隐私政策页面）。

### 1.3 A6.3 设备矩阵与屏幕自适应兼容性
- **代码级与自动化矩阵（已验证）**：
  - `minSdk = 26`（Android 8.0）至 `targetSdk = 37`（Android 16+）API 分支兼容已核实。
  - Compose 6 屏 × 4 轴（浅色/深色、`0.85x/1.0x/1.3x/2.0x` 字体缩放、`320dp` 窄屏 / `411dp` 标准屏 / `600dp–840dp` 折叠大屏、`48dp` 触控热区）已通过 `Phase5UiAccessibilityMatrixScreenshotTest` 等 27 项单测与 Robolectric 截图回归验证。
  - `emulator-5556`（Android 34 arm64）已完成 `AmberAgent-2.6.8-full-debug.apk` 安装与 `RouteActivity` 冷启动运行验证。
- **物理真机矩阵（Android 8 / 12 / 15 / 16 及折叠屏物理设备）**：标注为 `CONDITIONAL`（当前构建主机仅挂载 `emulator-5556`，物理真机 E3 矩阵待设备到位后补测）。

### 1.4 A6.4 分阶段发布计划与回滚触发器
- **分阶段灰度节奏（Staged Rollout）**：
  1. **Internal Testing**：内部测试轨（验证正式签名 AAB、Play App Signing、Crashlytics 符号表、无障碍与 OAuth 回调）。
  2. **Closed Alpha**：小范围种子用户验证手机代操作（Phase 1–4 闭环）、Mac Gateway 远程审批与上下文自动压缩。
  3. **Open Beta (5%)**：监控 48 小时 ANR、Crash-free rate、Room v20→v21 迁移成功率及 OAuth/SecretStore 解密异常率。
  4. **Production (10% → 50% → 100%)**：每阶段观察至少 24–48 小时，确认四项回滚触发器均未命中后再推进下一比例。
- **四条硬性回滚触发器（对齐四端计划 §10）**：
  1. **安全与审批边界失守**：发现任何高风险工具（`ToolRisk.High` / `allowsAutoApproval == false`）在未开启「自动批准高风险工具」时被静默放行，或从锁屏/通知栏/手机远端被直接批准，或凭据明文写入 Room/日志/导出包 —— **立即暂停灰发并回滚**。
  2. **数据库迁移或静息凭据损坏**：Room 迁移（含 `20 -> 21`）失败率 > `0.05%`，或 `SecretStore` / `OAuthTokenSecureStore` 升级后出现非预期凭据丢失 —— **立即暂停灰发并回滚**。
  3. **崩溃与 ANR 阈值突破**：主进程 Crash-free sessions < `99.2%` 或用户感知 ANR 率 > `0.35%`（持续 6 小时且样本 ≥ 500 sessions）—— **暂停扩量并回退至上一稳定轨道**。
  4. **手机代操作失控或接管失效**：无障碍屏幕代操作在用户点击「中断 / 我来操作」或手动触屏后未在单步内停止（`PhoneAutomationTakeoverController` 失效）—— **立即通过远程配置关闭屏幕自动化入口并回滚版本**。

---

## 2. 交付前 9 项跨端已知缺陷自查表（精确到 `file:line`）

| # | 缺陷检查项 | 状态 | 代码实现与证据位置（`file:line`） |
| :--- | :--- | :--- | :--- |
| **1** | **锁屏、通知、手机远端对高风险操作只能拒绝** | **PASS** | 1) 通知栏构建：`app/src/main/java/app/amber/feature/runtime/AgentLiveStatusNotifier.kt:80-112, 270-325`（`requiresInAppApprovalOnly(tool)` 对 `ToolRisk.High`、`!allowsAutoApproval` 或带 Jev 升级标记的工具隐藏通知栏 Approve 按钮，仅保留 Deny 与点击回 App）；<br>2) 锁屏通知脱敏：`app/src/main/java/app/amber/core/utils/NotificationUtil.kt:65-72`（`VISIBILITY_PRIVATE` + 挂载脱敏 `publicVersion`）；<br>3) 通知栏回调服务端兜底：`app/src/main/java/app/amber/core/service/ChatRunCoordinator.kt:989-996`（`handleNotificationApproval` 拒绝 `approved == true && requiresInAppApprovalOnly(toolPart)`）；<br>4) 手机端 Mac Gateway 远端审批：`app/src/main/java/app/amber/feature/macgateway/MacGatewayClient.kt:92-104`（客户端强制校验高风险请求仅允许 `decision == "denied"`）与 `app/src/main/java/app/amber/feature/ui/pages/setting/SettingExperimentalMacGatewayPage.kt:295-340`（UI 对高风险项仅渲染「拒绝」按钮）。 |
| **2** | **凭据在所有终态路径都要擦除，待审批时不能明文落盘** | **PASS** | 1) 凭据工具脱敏核心：`app/src/main/java/app/amber/core/utils/CredentialArgumentRedaction.kt:25-30`（覆盖 `ssh_profile_create`、`ssh_profile_update`、`provider_config_apply` 的 `api_key/token/secret/password/private_key/passphrase`）；<br>2) 待审批落盘拦截：`CredentialArgumentRedaction.kt:175-225`（`withUnapprovedCredentialsWithheld` 将未决凭据调用的落盘副本清空为 `{}` 并标为 `Denied(CREDENTIAL_WITHHELD_ON_RESUME)`）与 `app/src/main/java/app/amber/feature/chat/impl/ChatTurnAgent.kt:205-217`（流式合并写库边界调用 `withUnapprovedCredentialsWithheld()`）；<br>3) 全终态路径擦除：`app/src/main/java/app/amber/core/ai/DefaultRunKernel.kt:662-668`（执行完成后立刻 `withSettledCredentialsRedacted()`）、`app/src/main/java/app/amber/core/service/ChatApprovalActions.kt:143-144, 341-346, 466-481`（用户拒绝、中断放弃、新消息取消、继续跳过）、`app/src/main/java/app/amber/core/service/ChatRunCoordinator.kt:1081-1089`（用户取消生成）、`app/src/main/java/app/amber/feature/chat/impl/InterruptedRunProjection.kt:120-127`（崩溃恢复投影）、`app/src/main/java/app/amber/core/repository/ConversationRepository.kt:611-624`（Room `saveMessageNodes` 持久层兜底过滤）；<br>4) OAuth 临时态：`app/src/main/java/app/amber/feature/webmount/oauth/PendingOAuthStore.kt:37-131`（使用 `EncryptedSharedPreferences`，`consume()` 立即移除）。 |
| **3** | **凭据不能进入检索、摘要、导出、备份或外部复核** | **PASS** | 1) 压缩输入与输出摘要：`app/src/main/java/app/amber/core/context/ConversationContextPlanner.kt:306-318`（`summaryLine()` 对文本和工具输入/输出执行 `maskSensitiveJson` + `ContextFactRedaction.redact`）与 `app/src/main/java/app/amber/core/context/CompactSummaryPayload.kt:85-165`（`normalizeModelOutput` 全字段脱敏）；<br>2) 会话 Recap 摘要：`app/src/main/java/app/amber/core/recap/ConversationRecap.kt:232-246, 276-304`（输入 `recapText()` 与输出 `ConversationRecapParser.parse` 双重脱敏）；<br>3) 上下文检索与展开：`app/src/main/java/app/amber/core/context/ConversationContextRepository.kt:138-163`（`searchPage` 对 compact 和 message 预览执行 `ContextFactRedaction.redact`）与 `app/src/main/java/app/amber/feature/tools/ConversationContextTools.kt:134-140`（`contextExpandTool` 的 `textOf` 脱敏）；<br>4) 导出与同步备份：`app/src/main/java/app/amber/core/utils/Export.kt:292`（`maskSensitiveJson`）、`core/settings/src/main/kotlin/app/amber/core/settings/backup/BackupSettingsRedactor.kt:53-122`、`app/src/main/res/xml/backup_rules.xml:8-44` 与 `app/src/main/res/xml/data_extraction_rules.xml:9-98`（排除全部凭据与 OAuth `sharedpref`）；<br>5) 外部复核（Jev）：`app/src/main/java/app/amber/core/jev/JevAutoApprovalGate.kt:69-74, 115-137`（参数仅发白名单键名摘要，`recent_user_messages` 经 `ContextFactRedaction.redact`）与 `app/src/main/java/app/amber/core/jev/JevCompletionCheck.kt:44-48`（`final_reply` 经 `ContextFactRedaction.redact`）。 |
| **4** | **遇到占位符要拒绝写入** | **PASS** | 1) Provider 配置工具：`app/src/main/java/app/amber/core/ai/tools/ProviderConfigurationApply.kt:116-121, 328-347`（`isPlaceholderApiKey` 拦截 `••••`、`<redacted>`、`[REDACTED]`、`***`、`******`、`__MASKED_BY_AMBERAGENT_BACKUP__`、`********`、`sk-xxx`、`your_key` 等所有占位符，拒绝写入且不触发发布）；<br>2) SSH 凭据存储：`core/settings/src/main/kotlin/app/amber/core/settings/ssh/SshProfileStore.kt:295-324`（`validateSecret` 与 `validatePassphrase` 通过 `isMaskedOrRedactedPlaceholder` 拒绝掩码与 `<redacted>` 占位符覆盖真实凭据）。 |
| **5** | **子代理拿不到高风险写工具** | **PASS** | `app/src/main/java/app/amber/core/service/tools/RunToolCatalogFactory.kt:384-430`（`PROVIDER_CONFIG_TOOL_NAMES` 仅在主会话 `conversationId != null` 注入，不进入子代理工具池）、`feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentDefinitions.kt:77-320`（内置子代理白名单不含高风险配置写工具）、`feature/subagent/src/main/kotlin/app/amber/feature/subagent/SubAgentToolScope.kt:26-36`、`app/src/main/java/app/amber/core/service/subagent/SubAgentRunner.kt:118`（强制标记 `ToolInvocationContext.SubAgent`）。已由 `ProviderConfigToolsTest.kt:986-998` 验证。 |
| **6** | **自动压缩不能每轮触发** | **PASS** | `app/src/main/java/app/amber/core/context/ConversationContextPlanner.kt:23-84`（`planCompaction` 严格校验 `ratio < policy.precompactRatio`、`latestCoveredEnd >= sourceEnd` 以及最少新增轮数 `end - start + 1 < 2`）与 `app/src/main/java/app/amber/core/context/ConversationContextEngine.kt:95-145`。 |
| **7** | **预算裁剪不能丢掉 system 消息** | **PASS** | `app/src/main/java/app/amber/core/context/ConversationContextPlanner.kt:171-197`（`fitMessagesToTokenBudget` 始终先提取 `val systemMessages = messages.filter { it.role == MessageRole.SYSTEM }` 并在裁剪非 system 历史后完整保留所有 system 消息）。 |
| **8** | **默认关闭的能力不能被绕过** | **PASS** | 1) 屏幕代操作：`app/src/main/java/app/amber/feature/tools/ScreenAutomationTools.kt:65-95` 与 `app/src/main/java/app/amber/feature/tools/ScreenFlowTools.kt:75-95` 执行前强制检查 `settings.agentRuntime.screenAutomation.enabled` 与无障碍服务连接状态；<br>2) Mac Gateway：`app/src/main/java/app/amber/feature/macgateway/MacGatewayRepository.kt:55-90` 检查 `enabled` 开关；<br>3) 工具目录组装：`app/src/main/java/app/amber/core/service/tools/RunToolCatalogFactory.kt:356-392` 按设置开关裁剪暴露给模型的工具列表。 |
| **9** | **能力声明要和实际行为一致** | **PASS** | `feature/tools/api/src/main/kotlin/app/amber/feature/tools/ToolRegistry.kt:340-715`、`core/settings/src/main/kotlin/app/amber/core/settings/Capability.kt:200-220` 与 `app/src/test/java/app/amber/agent/data/agent/runtime/ExecutionPolicyCoveragePinTest.kt`、`app/src/test/java/app/amber/core/ai/tools/ProviderConfigToolsTest.kt:1011-1041` 锁定每把工具的 `effectClass`、`risk`、`needsApproval`、`autoApprovable` 与 `PermissionDecisionResolver` 实际执行策略完全一致。 |

---

## 3. `CONDITIONAL`（有条件放行 / 需人工与真机完成）汇总

上架 Google Play 前需由用户 / 发布团队人工完成的外部依赖项已单独整理为清单文档：
- 仓内路径：`docs/plans/2026-10-31-android-p6-conditional-checklist.md`
- 已同步归档至 Hub Library。
