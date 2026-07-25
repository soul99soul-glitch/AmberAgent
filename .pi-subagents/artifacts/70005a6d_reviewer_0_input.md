# Task for reviewer

[Read from: /Users/arquiel/Downloads/AI/amberagent-ios/plan.md, /Users/arquiel/Downloads/AI/amberagent-ios/progress.md]

你在复核一份针对 iOS 仓库 /Users/arquiel/Downloads/AI/amberagent-ios 的独立审计报告。只做只读核对，禁止修改任何文件。注意:该仓库 docs/PROJECT_STATE.md 记录了 2026-07-24 的一轮 "evidence-backed bug review remediation",其中多项修复可能已使下列审计条目过时(例如:SubAgent 已走 OAuth/headers resolver、会话恢复改走 JsonConversationStorage 单 mutex 校验、Deep Read/MiniApp 复用当前 provider/model、凭据 side-table 扩展、Codex 登录刷新不再改写模型 UUID)。你必须以当前工作区代码为准逐条取证。

对下面每一条,给出判定:确认成立 / 已修复(过时) / 部分成立 / 不成立,并附当前代码证据(文件:行号 + 关键代码摘录,一两行即可)。

条目2: Credential redactor 把 customBodies 当 header 集合处理,max_tokens/token_budget 因含 "token" 被误判为凭据;数字/布尔值无法写入 Keychain,重启后变空字符串。证据: iosApp/iosApp/IOSCredentialRedactor.swift:85

条目3: Keychain 更新是先删后增,新增失败时旧凭据已丢失,上层仍保存 mask 并提示成功。证据: iosApp/iosApp/IOSCredentialSideTable.swift:26, iosApp/iosApp/IOSSharedSettingsStore.swift:266

条目4: 会话恢复不是批次原子:JSON 先解码但逐文件覆盖,第 N 次写盘失败时磁盘处于新旧混合状态,UI 只报"恢复失败"。证据: core/conversation-storage/src/commonMain/kotlin/app/amber/core/storage/conversation/JsonConversationStorage.kt:104, iosApp/iosApp/SyncBackupView.swift:603

条目5: 禁用 Provider 后当前会话仍可继续请求:模型列表过滤了 disabled provider,但最终模型解析和 dispatch 不检查 provider.enabled。证据: iosApp/iosApp/ChatProviderConfiguration.swift:95, iosApp/iosApp/ChatViewModel.swift:1955

条目6: 刷新 Codex 模型会删除旧 image model 并用随机 UUID 新建,不迁移全局/assistant 引用,导致已选择的生图 UUID 悬空、生图工具静默消失。证据: shared/src/commonMain/kotlin/shared/IosSettingsMutations.kt:430, iosApp/iosApp/ProviderDetailView.swift:73

条目7: Deep Read resolver 强制要求非空 API key,Codex/Grok 等 keyless OAuth 登录不被承认,落到离线草稿却仍提示"已生成并保存深度阅读"。证据: iosApp/iosApp/IOSSharedSettingsStore.swift:811, iosApp/iosApp/DeepReadCreateView.swift:143

条目8: OCR、标题/建议、MiniApp AI、Deep Read 等辅助请求丢弃 model 级 custom headers/body(传空数组),普通 Chat 正确合并 assistant+model 参数。证据: iosApp/iosApp/ChatViewModel.swift:815, iosApp/iosApp/MiniAppRunnerView.swift:435, iosApp/iosApp/IOSBoardPersistence.swift:3441

最后简要评价这批条目的修复建议方向是否合理(一两句/条)。

## Acceptance Contract
Acceptance level: attested
Completion is not accepted from prose alone. End with a structured acceptance report.

Criteria:
- criterion-1: Return concrete findings with file paths and severity when applicable

Required evidence: review-findings, residual-risks

Finish with a fenced JSON block tagged `acceptance-report` in this shape:
Use empty arrays when no items apply; array fields contain strings unless object entries are shown.
`criteriaSatisfied[].status` must be exactly one of: satisfied, not-satisfied, not-applicable.
`commandsRun[].result` must be exactly one of: passed, failed, not-run.
`manualNotes` and `notes` are optional strings; an empty string means no note and does not satisfy `manual-notes` evidence.
```acceptance-report
{
  "criteriaSatisfied": [
    {
      "id": "criterion-1",
      "status": "satisfied",
      "evidence": "specific proof"
    }
  ],
  "changedFiles": [
    "src/file.ts"
  ],
  "testsAddedOrUpdated": [
    "test/file.test.ts"
  ],
  "commandsRun": [
    {
      "command": "command",
      "result": "passed",
      "summary": "short result"
    }
  ],
  "validationOutput": [
    "validation output or concise summary"
  ],
  "residualRisks": [
    "none"
  ],
  "noStagedFiles": true,
  "diffSummary": "short description of the diff",
  "reviewFindings": [
    "blocker: file.ts:12 - issue found, or no blockers"
  ],
  "manualNotes": "anything else the parent should know"
}
```