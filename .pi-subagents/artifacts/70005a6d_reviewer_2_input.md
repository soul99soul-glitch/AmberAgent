# Task for reviewer

[Read from: /Users/arquiel/Downloads/AI/amberagent-ios/plan.md, /Users/arquiel/Downloads/AI/amberagent-ios/progress.md]

你在复核一份针对 iOS 仓库 /Users/arquiel/Downloads/AI/amberagent-ios 的独立审计报告。只做只读核对,禁止修改任何文件。注意:docs/PROJECT_STATE.md 记录了多轮 Chat 修复,部分条目可能已过时(例如凭据删除 generic 条目残留已在 2026-07-25 修复、ChatStreamPresentationPacer 已上线)。你必须以当前工作区代码为准逐条取证。

对下面每一条,给出判定:确认成立 / 已修复(过时) / 部分成立 / 不成立,并附当前代码证据(文件:行号 + 关键代码摘录,一两行即可)。

条目10: 发送资格在 UI 和 VM 之间漂移:VM 支持纯图片消息,但 UI 要求文本非空,纯图片永远无法发送;OCR 进行中按钮显示可发送,点击后 VM 静默 return。证据: iosApp/iosApp/ChatView.swift:1223, iosApp/iosApp/ChatViewModel.swift:589

条目11: PhotosPicker 异步图片处理没有 conversationId/request token/取消检查,加载期间切换或新建会话,图片会追加到当前 VM(串会话)。证据: iosApp/iosApp/ChatView.swift:465, iosApp/iosApp/ChatViewModel.swift:1232

条目12: OCR 成功结果在切会话后静默丢失:成功路径要求当前仍是原会话否则直接 return,原会话只留下用户图片,没有回复/错误/重试入口。证据: iosApp/iosApp/ChatViewModel.swift:709

条目18: 默认 Chat 长文流式存在负载敏感跳变:真实 ChatSwiftUIMessageList 回放 16 条中 2 条失败,连续长段落单次高度跳 61pt>40pt;24KB bottom debt 79.96pt>72pt。证据: iosApp/iosAppTests/ChatSwiftUIStreamReplayTests.swift:910。同时检查 PROJECT_STATE 里 07-24 记录的 "修复前 893pt → 修复后 23.3→21.3→6.7pt 通过 ≤72pt 门禁" 是否与该条目矛盾(即 79.96pt 是否为新失败或不同测试)。

条目19: 超过 50 行的用户消息永久截断,注释称折叠但无展开按钮/状态。证据: iosApp/iosApp/ChatMessageListSupport.swift:76

条目20: 自动标题可能覆盖人工重命名:首轮标题 Task 返回后无条件 rename,无 baseline/CAS。证据: iosApp/iosApp/ChatViewModel.swift:947

条目21: 聊天建议存在同会话旧任务逆序覆盖:只有 conversationId 和"当前未生成"检查,无 generation token;输出未去重,UI 以字符串自身为 identity。证据: iosApp/iosApp/ChatViewModel.swift:965

条目22: pending approval/Grok/不可 handoff 时切换会话静默无响应:prepareForConversationChange 返回 false,所有 UI 调用方直接 return,无原因提示。证据: iosApp/iosApp/PlaceholderViews.swift:1002

条目23: 照片编码和历史 data URL 解码在 MainActor:高像素图片同步位图绘制/JPEG/Base64/UIImage 解码。证据: iosApp/iosApp/ChatView.swift:465, iosApp/iosApp/ChatMiscViews.swift:519

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