# Task for reviewer

[Read from: /Users/arquiel/Downloads/AI/amberagent-ios/plan.md, /Users/arquiel/Downloads/AI/amberagent-ios/progress.md]

你在复核一份针对 iOS 仓库 /Users/arquiel/Downloads/AI/amberagent-ios 的独立审计报告。只做只读核对,禁止修改任何文件。注意:该仓库 docs/PROJECT_STATE.md 记录了多轮修复(2026-07-18 至 2026-07-24),可能已使部分条目过时:例如 "OpenAI/Responses/Claude 流式只有收到协议终态才完成"、"后台/Watch 真实终态缺口已闭环"、ask_user 已接成一等 pending 节点、Watch 回包改 Bool 成功语义、后台 backgroundToolExecutors 登记等。你必须以当前工作区代码为准逐条取证,不能只看 PROJECT_STATE 的描述。

对下面每一条,给出判定:确认成立 / 已修复(过时) / 部分成立 / 不成立,并附当前代码证据(文件:行号 + 关键代码摘录,一两行即可)。

条目9: Grok Web 裸 EOF 被当成成功:JS reader 结束总发送 complete,transport 无条件成功,只有 parser 知道是否收到 [DONE]/final metadata,网络中断后半截回复被记为 completed。证据: iosApp/iosApp/IOSGrokWebProvider.swift:272 和 :495

条目13: 审批卡和 ask_user 无法跨冷启动恢复:pauseForApproval 只写内存和当前 UI,没有持久化 pending descriptor,强杀后卡片消失。证据: iosApp/iosApp/ChatGenerationCoordinator.swift:1608, iosApp/iosApp/IOSRunRecovery.swift:19

条目14: 批准后的五类异步工具(Search/WebMount/Workspace/iSH/MCP)直接 await,未登记 foregroundToolExecutionTask,Stop/删除会话后外部副作用仍可能发生但结果被丢弃;Council 已正确登记。证据: iosApp/iosApp/ChatGenerationCoordinator.swift:1683

条目15: 后台 BG expiration 先交还系统任务再保存终态:先删 payload、调 setTaskCompleted(false),之后才 await 保存失败消息,系统可在 durable terminal 前挂起进程。证据: iosApp/iosApp/IOSChatBackgroundGenerationCoordinator.swift:462

条目16: 后台多工具轮过期丢失已完成的前一轮:每轮开始清空 assistant snapshot,expiration 只保留当前 partial,已执行工具的 assistant/tool suffix 消失,重试可能重复副作用。证据: iosApp/iosApp/IOSChatBackgroundGenerationCoordinator.swift:540

条目17: Watch 显示失败后操作仍可能迟到执行:不可达或实时发送失败时仍 transferUserInfo 排队,UI 同时报失败;手机端不检查 createdAt TTL。证据: iosApp/SharedWatch/WatchConnectivityBridge.swift:174, iosApp/iosApp/WatchTaskCoordinator.swift:152

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