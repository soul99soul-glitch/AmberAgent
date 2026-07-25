## Review

### 条目2 — 确认成立
- `iosApp/iosApp/IOSCredentialRedactor.swift:85`:`custombodies` 仍与 `headers`/`customheaders` 一起走 `redactHeaderCollection`。
- `IOSCredentialRedactor.swift:336-342`(`sensitiveHeaderMarkers`)含 `"token"` 子串标记，`max_tokens`→`maxtokens` 命中；`redactSensitiveValue`(`:161`)`guard let string = value as? String else { return mask }` —— 非字符串值直接打 mask 且**不写 Keychain**。
- `CustomBody.value` 是 `JsonElement`(`ai-core/src/commonMain/kotlin/app/amber/ai/provider/Provider.kt:105-108`)，数字/布尔合法存在；rehydrate 时 mask 找不到 side-table 条目 → `?? ""`(`:178`)。重启后数值变空字符串，属实。
- 修复建议方向合理：按 value 类型分流（非 String 不打 mask 直接保留），或对 body 集合改用精确凭据名匹配而非子串。

### 条目3 — 确认成立（低风险窗口）
- `iosApp/iosApp/IOSCredentialSideTable.swift:33-38`:`store` 先 `SecItemDelete` 再 `SecItemAdd`，无 update-or-add 原子语义。
- 上层全部丢弃返回值：`IOSSharedSettingsStore.swift:278/287/299` 及 redact 回调内均不检查 `Bool`；失败后 redacted mask 照样持久化，重启 rehydrate 成 `""`。
- 修复方向合理：改用 `SecItemUpdate` 失败再 `SecItemAdd`，或至少在 add 失败时中止本次 persist 并上抛错误。

### 条目4 — 部分成立（主路径已修复，写盘阶段仍非全原子）
- 当前恢复走 `JsonConversationStorage.importConversations`(`core/conversation-storage/.../JsonConversationStorage.kt:104`)：**先全量解码校验**(`:106-113`)，任一文档损坏在写盘前抛出；同一 `operationMutex` 内写文件并 `rebuildIndex()`(`:115-118`)。`SyncBackupView.swift:610` 经 `conversationStore.importConversationDocuments`(`IOSConversationStore.swift:204`)进入该批次入口，不再逐文件直接覆盖。
- 残余：批次中途 `writeText` 失败（如磁盘满）时前 N 个同 ID 会话已被覆盖、无回滚，`rebuildIndex` 被跳过导致 index 暂时陈旧 —— 但 `listSummaries`(`:48-63`) 会对陈旧/孤儿条目做机会性修复，且 UI 错误现带 `localizedDescription`(`SyncBackupView.swift:627`)。原条目"只报恢复失败"已过时。
- 修复方向（temp 目录+rename 原子批）合理但属增强；当前状态已非 blocker。

### 条目5 — 确认成立
- 选择列表过滤 enabled:`ChatProviderConfiguration.swift:86` `guard provider.enabled`。
- 最终解析/dispatch 不查 enabled:`issue(for:provider:)`(`:95-119`）无 enabled 检查；`ChatViewModel.swift:1955` `makeProviderSetting()` → `findProvider`(`core/types/.../Settings.kt:486-493`）也不查。禁用 provider 后当前会话确实可继续请求。
- 是否算 bug 取决于产品语义（可能为 Android parity)；若确认要拦截，建议在 `issue(for:)` 增加 `.providerDisabled` 而非散点过滤——方向合理。

### 条目6 — 确认成立
- `shared/src/commonMain/kotlin/shared/IosSettingsMutations.kt:430-453` `upsertProviderImageModel`：滤掉同 modelId 旧模型后以 `Uuid.random()`(`:446`）新建，**不迁移** `imageGenerationModelId`（全局 `:657-659` 及 assistant 级均无联动）。
- 调用点 `iosApp/iosApp/ProviderDetailView.swift:73-79`(Codex 登录 persistModels)。悬空后 `ChatViewModel.swift:1972` `findModelById` 返回 nil → `imageGenerationConfigured=false` → 生图工具静默不挂载。
- 注：PROJECT_STATE 所称"登录/刷新不改写模型 UUID"只覆盖 chat 模型（`mergeProviderChatModels` 保留既有 UUID,`IosSettingsMutations.kt:400-419`);image 模型 upsert 未修。
- 修复方向合理：同 modelId 时复用旧 `model.id`（一行改动），或在 upsert 内同步重指引用。

### 条目7 — 确认成立
- `iosApp/iosApp/IOSSharedSettingsStore.swift:823`:`resolveBoardDeepReadModel` 强制 `!Self.apiKey(of: provider)….isEmpty`;Codex/Grok 无 apiKey → 返回 nil。
- `DeepReadCreateView.swift:173-175` 落入离线草稿分支，`:184` 仍报"已生成并保存深度阅读。"，无离线提示。
- 下游其实已支持 keyless:`OpenAIKmpProviderAdapter.generateText` 走 `IOSCodexProviderResolver.resolved`(`IOSAgentToolEngine.swift:158-162`),Grok 走 `grokGenerator` —— 即门禁是误拒。
- 修复方向合理：仿 `ChatProviderConfiguration.issue(for:)` 按 Codex/Grok 登录态放行；离线草稿成功文案应如实标注。

### 条目8 — 确认成立
- 辅助请求传空：`ChatViewModel.swift:838-839`(OCR)、`:932-933`(runAuxModel 标题/建议）、`MiniAppRunnerView.swift:456-457`、`IOSBoardPersistence.swift:3454-3455`(Deep Read；另有 :1832、:3064)。
- KMP provider 只读 `params.customHeaders`(`ai-provider-openai/.../OpenAIKmpProvider.kt:613,650,672`),adapter 不做 model 级合并，故 model 级 headers/body 真被丢弃；普通 Chat 正确合并 assistant+model(`ChatViewModel.swift:2016, 2084-2085`)。
- 修复方向合理：抽一个共享的"辅助请求 params 构建"helper 复用 :2016 的合并逻辑，避免逐点修补。

### 总体评价
这批修复建议方向均合理且与现有架构一致（redactor 类型分流、Keychain update-or-add、enabled 集中门禁、image UUID 复用、keyless 放行、params 合并 helper)。条目4 主体已修，仅需决定是否追加写盘原子性增强；条目3 窗口窄、优先级最低；条目2/6/7 有真实用户可见后果（数据丢失/工具消失/虚假成功提示），建议优先。