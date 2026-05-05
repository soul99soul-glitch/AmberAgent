# Codex Timeline UI Redesign Handoff - AmberAgent - 2026-05-05

This handoff is for a fresh Codex session dedicated to the AmberAgent timeline/chat UI redesign.

The task is allowed to implement UI changes, but only inside an experimental branch and only after reading the relevant code paths. The first implementation target should be the chat timeline experience, not a whole-app visual rewrite.

## Repository

```text
/Users/arquiel/Downloads/AI/rikkashit/rikkahub
```

Current experimental branch:

```text
experiment/ui-redesign-20260505
```

Stable code baseline before UI redesign:

```text
fd8b913b fix(ui): restore streamed message layout
```

Rollback tag:

```text
amberagent-ui-baseline-before-claude-20260505
```

Current handoff docs:

```text
docs/AMBERAGENT_HANDOFF_2026-05-04.md
docs/CLAUDE_UI_HANDOFF_2026-05-05.md
docs/CODEX_TIMELINE_UI_REDESIGN_HANDOFF_2026-05-05.md
```

There may also be an untracked file:

```text
docs/ui-mockup-2026-05-05.html
```

Treat that mockup as optional visual reference only. Do not trust it as implementation truth, and do not commit it unless the user explicitly asks.

## Rollback Strategy

If the redesign is not good:

```bash
cd /Users/arquiel/Downloads/AI/rikkashit/rikkahub
git switch amberagent/v0.5-wip
git reset --hard amberagent-ui-baseline-before-claude-20260505
```

If the experimental branch should be preserved before rollback:

```bash
git branch codex-ui-redesign-wip
git switch amberagent/v0.5-wip
git reset --hard amberagent-ui-baseline-before-claude-20260505
```

## Fixed Local Environment

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/arquiel/Library/Android/sdk
ADB=/Users/arquiel/Library/Android/sdk/platform-tools/adb
AAPT=/Users/arquiel/Library/Android/sdk/build-tools/37.0.0/aapt
```

ADB may show the same phone twice through wired and wireless debugging. Prefer wired:

```bash
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 987a53f3 ...
```

## Product Direction

AmberAgent is an Agent-first Android fork on top of RikkaHub.

Important boundaries:

- AmberAgent itself is the app-level Agent.
- Do not make old RikkaHub "Assistant = Agent" semantics more prominent.
- Keep compatibility models unless explicitly told otherwise.
- Do not fake capabilities.
- Do not copy OpenOmniBot/OpenMinis UI wholesale.
- Do not change provider marshalling, tool semantics, terminal runtime, SAF workspace, or Room schema for this UI pass.

## First Commands

Run these before editing:

```bash
git status --short
git branch --show-current
git log -5 --oneline
git diff --stat
git diff --check
```

Expected branch:

```text
experiment/ui-redesign-20260505
```

If not on that branch, switch to it:

```bash
git switch experiment/ui-redesign-20260505
```

## Read First

Read the handoff docs:

```text
docs/AMBERAGENT_HANDOFF_2026-05-04.md
docs/CLAUDE_UI_HANDOFF_2026-05-05.md
docs/CODEX_TIMELINE_UI_REDESIGN_HANDOFF_2026-05-05.md
```

Then inspect the timeline implementation:

```text
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt
app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownNew.kt
```

## Recent Regression To Preserve

A recent bug made assistant text render as a narrow vertical column.

Root cause:

- Streamed text chunks were saved as many separate `UIMessagePart.Text` parts.
- Empty explicit `reasoning_content` markers were inserted between chunks.
- UI rendered each text part separately.

Current fix:

```text
ai/src/main/java/me/rerere/ai/ui/MessageStreamAccumulator.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt
```

Regression tests:

```text
ai/src/test/java/me/rerere/ai/ui/MessageStreamAccumulatorTest.kt
app/src/test/java/me/rerere/rikkahub/ui/components/message/ChatMessageCotTest.kt
```

Do not "fix" text layout by adding random `fillMaxWidth()` constraints. If text layout breaks, first inspect the `UIMessagePart` data shape and `groupMessageParts()`.

## Timeline Redesign Scope

Primary target:

- Chat timeline reading experience.
- Message hierarchy.
- Tool cards inside the timeline.
- Reasoning blocks inside the timeline.
- Streaming/typing/processing state placement.
- Composer overlap and bottom safe area.

Allowed secondary target if needed:

- Small adjustments to `ChatInput.kt` only when they are required to make the timeline/composer boundary work.

Out of scope for this session:

- Settings redesign.
- Provider settings.
- Agent runtime.
- Tool execution behavior.
- Terminal job runtime.
- iCloud/experimental feature internals.
- Backup/sync.
- Database schema.
- Full app navigation rewrite.

## Design Goals

The timeline should feel like an Agent work log, not a generic marketing chatbot.

Priorities:

- Readability: normal assistant text should use comfortable line width and spacing.
- Stability: streaming text, tool cards, and reasoning blocks should not jump wildly.
- Density: keep it usable for long technical conversations.
- Clear state: tool running, waiting approval, failed, cancelled, completed should be visually distinguishable.
- Mobile first, but adaptive: phone portrait, phone landscape, and tablet should all work.
- No hardcoded mobile-only widths. Prefer constraints based on available width, plus reasonable max widths for large screens.
- Preserve existing Compose architecture unless a small local extraction clearly reduces risk.

## Suggested Phases

### Phase 0 - Audit Only

Deliver:

- A short map of the timeline UI tree.
- The exact files that should be touched.
- A risk list.
- A screenshot/smoke plan.

Do not edit in Phase 0 unless the user explicitly says to continue.

### Phase 1 - Timeline Structure Cleanup

Good targets:

- Extract small subcomponents from `ChatMessage.kt` only where it reduces local complexity.
- Improve spacing between text, reasoning, tools, and actions.
- Keep `UIMessagePart` ordering semantics intact.
- Keep `groupMessageParts()` behavior intact.

Avoid:

- New message data model.
- New persistence shape.
- A broad markdown renderer rewrite.

### Phase 2 - Tool Cards

Good targets:

- Improve visual hierarchy of tool cards.
- Make approval/running/failed/completed states easier to scan.
- Keep output truncation behavior.
- Ensure last tool card is not hidden behind composer or operation preview.

### Phase 3 - Composer Boundary

Good targets:

- Bottom padding and IME/composer safe area.
- Suggested actions chips not overlapping message content.
- Responsive behavior on wider screens.

Avoid:

- Full `ChatInput.kt` rewrite.

### Phase 4 - Real Device Smoke

Build and install:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:assembleDebug --no-daemon --stacktrace

cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk /Users/arquiel/Downloads/AmberAgent-v0.8.3-arm64-debug.apk
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 987a53f3 install -r /Users/arquiel/Downloads/AmberAgent-v0.8.3-arm64-debug.apk
```

Smoke on phone:

- Simple Chinese greeting renders as a normal paragraph.
- Mixed Chinese/English paragraph renders normally.
- Markdown list and code block render normally.
- Ask the Agent to call tools; cards remain readable.
- Start a long/running tool; timeline remains scrollable and last card is visible.
- Rotate device or test wide screen if available.

## Required Verification

At minimum after edits:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:compileDebugKotlin --no-daemon --stacktrace
```

For message/timeline rendering changes:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :ai:testDebugUnitTest --tests 'me.rerere.ai.ui.MessageStreamAccumulatorTest' \
  :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.components.message.ChatMessageCotTest' \
  --no-daemon --stacktrace
```

Before handing back:

```bash
git diff --check
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

## New Session Starting Prompt

Use the prompt below to start a fresh Codex UI redesign session.

```text
继续 AmberAgent 项目，但这次只做聊天时间线 UI 重构实验。

仓库：
/Users/arquiel/Downloads/AI/rikkashit/rikkahub

请先阅读：
docs/AMBERAGENT_HANDOFF_2026-05-04.md
docs/CLAUDE_UI_HANDOFF_2026-05-05.md
docs/CODEX_TIMELINE_UI_REDESIGN_HANDOFF_2026-05-05.md

当前应该在实验分支：
experiment/ui-redesign-20260505

稳定回滚点：
tag amberagent-ui-baseline-before-claude-20260505
commit fd8b913b fix(ui): restore streamed message layout

开始前先跑：
git status --short
git branch --show-current
git log -5 --oneline
git diff --stat
git diff --check

目标：
先理解并重构聊天时间线 UI，不做全 App 大改。

重点范围：
- ChatList.kt
- ChatMessage.kt
- ChatMessageCot.kt
- ChatMessageTools.kt
- ChatMessageActions.kt
- ChatMessageReasoning.kt
- 必要时少量触碰 ChatInput.kt 的底部安全区/时间线边界
- Markdown.kt / MarkdownNew.kt 只在确实需要时小心修改

硬约束：
- 不改 Provider/API marshalling
- 不改 tool 执行语义
- 不改 terminal runtime
- 不改 SAF workspace
- 不改 Room schema
- 不整块搬 OpenOmniBot/OpenMinis
- 不用随机 fillMaxWidth 之类的方式掩盖文本渲染问题
- 保留 streamed message layout 的修复：空 reasoning_content marker 不能把可见文本切成很多块

执行方式：
1. 先做 Phase 0 audit：画出时间线 UI 结构、风险点、建议触碰文件。
2. 然后给一个小而可回滚的实施计划。
3. 如果计划清晰，就直接实现第一阶段时间线 UI 改造。
4. 每次改完至少跑 compileDebugKotlin；涉及消息渲染时跑 MessageStreamAccumulatorTest 和 ChatMessageCotTest。
5. 需要真机验证时，用 wired adb device 987a53f3 安装测试。

固定环境：
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
ANDROID_HOME=/Users/arquiel/Library/Android/sdk
ADB=/Users/arquiel/Library/Android/sdk/platform-tools/adb
AAPT=/Users/arquiel/Library/Android/sdk/build-tools/37.0.0/aapt

先给我一个很短的状态确认，然后开始 audit，不要直接大改。
```

