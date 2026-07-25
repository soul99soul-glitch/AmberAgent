# Task for reviewer

[Read from: /Users/arquiel/Downloads/AI/amberagent-ios/plan.md, /Users/arquiel/Downloads/AI/amberagent-ios/progress.md]

你在复核一份针对 iOS 仓库 /Users/arquiel/Downloads/AI/amberagent-ios 的独立审计报告。只做只读核对,禁止修改任何文件。以当前工作区代码为准逐条取证。

对下面每一条,给出判定:确认成立 / 已修复(过时) / 部分成立 / 不成立,并附当前代码证据(文件:行号 + 关键代码摘录,一两行即可)。

条目24: Chat 正文绕过系统 Dynamic Type:用户气泡和 Markdown 用固定 point size,Accessibility XXXL 下输入框放大但正文基本不变。证据: iosApp/iosApp/ChatMessageListSupport.swift:76, iosApp/iosApp/MessageBubbleView.swift:676

条目25: Reduce Motion 覆盖不完整:OCR 呼吸动画、上下文环、TypingDots、流式 glyph fade 不受系统设置控制。证据: iosApp/iosApp/ChatMiscViews.swift:550, iosApp/iosApp/ChatComposerViews.swift:328, iosApp/iosApp/ChatMessageListSupport.swift:267。注意 PROJECT_STATE 记录 2026-07-23 已给回底动画和消息 part 插入动画接入 accessibilityReduceMotion——检查条目所列四处是否仍缺。

条目26: 多个高频控件真实点击区域小于 44pt:顶栏/回到底部 38pt、附件 32pt、思考/上下文 34pt;.contentShape 不扩大 bounds。证据: iosApp/iosApp/ChatView.swift:7, iosApp/iosApp/ChatComposerViews.swift:25

条目27: "流式 Markdown 渲染器"设置中 fade 和 experimental 使用完全相同谓词,fade 分支永远先截获,设置开关实际无效。证据: iosApp/iosApp/MessageBubbleView.swift:616, iosApp/iosApp/DisplayFontSettingsView.swift:162

条目28: "长文本粘贴为文件"是无副作用状态写入:超阈值只把 pasteHintShown=true,无提示、无附件、无发送分支。证据: iosApp/iosApp/ChatView.swift:1136

条目29: 多图生成进行态只画一张占位,完成时突然扩成 N 张:count:4 已解析但 placeholder 固定为 1,content size 瞬间跳变。证据: iosApp/iosApp/MessageBubbleView.swift:2584

条目30: 常见 $x^2$ 行内公式被当字面文本:只支持 \(...\)、$$...$$、\[...\],已有测试标为 Known Gap。证据: iosApp/vendor/SwiftStreamingMarkdown/Sources/MarkdownText/Parser/MarkdownParseOption.swift:24

最后简要评价这批条目的修复建议方向是否合理(一两句/条)。特别评价条目30的修复建议(单美元解析规则)在流式增量解析下的误伤风险。

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