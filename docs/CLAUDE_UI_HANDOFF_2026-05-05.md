# Claude UI Handoff - AmberAgent - 2026-05-05

This document is for bringing Claude into the AmberAgent project as an external reviewer and UI refactor partner.

The goal of the first Claude pass is **understanding and review only**. Do not start broad UI edits before reading the code paths, the recent fixes, and the rollback instructions below.

## Repository

```text
/Users/arquiel/Downloads/AI/rikkashit/rikkahub
```

Current branch:

```text
amberagent/v0.5-wip
```

Current code baseline commit:

```text
fd8b913b fix(ui): restore streamed message layout
```

Rollback tag before Claude UI work:

```text
amberagent-ui-baseline-before-claude-20260505
```

Current APK on device:

```text
/Users/arquiel/Downloads/AmberAgent-v0.8.3-arm64-debug.apk
package: me.rerere.amberagent.debug
versionName: 0.8.3
versionCode: 173
```

## Rollback

If UI refactor work goes sideways, return to the current working baseline:

```bash
cd /Users/arquiel/Downloads/AI/rikkashit/rikkahub
git reset --hard amberagent-ui-baseline-before-claude-20260505
```

If you want to keep Claude's failed attempt for later inspection first:

```bash
git branch claude-ui-wip
git reset --hard amberagent-ui-baseline-before-claude-20260505
```

## Fixed Local Environment

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/Users/arquiel/Library/Android/sdk
ADB=/Users/arquiel/Library/Android/sdk/platform-tools/adb
AAPT=/Users/arquiel/Library/Android/sdk/build-tools/37.0.0/aapt
```

ADB currently may show the same phone twice through wired and wireless debugging. Prefer the wired device:

```bash
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 987a53f3 ...
```

## Product Direction

AmberAgent is an Agent-oriented Android fork built on RikkaHub's foundation.

Important product semantics:

- AmberAgent itself is the application-level Agent.
- Old RikkaHub "Assistant = Agent" concepts should not be made more prominent.
- Compatibility data structures can remain.
- Do not fake capabilities. Stage0/stage1 features must be labeled honestly.
- Do not whole-copy OpenOmniBot or OpenMinis UI architecture.
- Do not rework the entire Compose app shell just for polish.

## Before Any Edit

Run:

```bash
git status --short
git diff --stat
git diff --check
```

Read before changing:

```text
docs/AMBERAGENT_HANDOFF_2026-05-04.md
docs/CLAUDE_UI_HANDOFF_2026-05-05.md
```

For UI work, inspect the actual implementation first:

```text
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt
app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt
app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownNew.kt
app/src/main/java/me/rerere/rikkahub/data/agent/AgentLiveStatusNotifier.kt
app/src/main/res/drawable/amberagent_live_status_icon.xml
```

## Recent UI-Related Fixes

### 1. Streamed Message Layout Regression

The phone showed assistant output as a narrow vertical column.

Root cause discovered from the on-device Room database:

- The model stream produced many tiny `UIMessagePart.Text` chunks.
- Empty explicit `reasoning_content` markers were inserted between chunks.
- The UI rendered each visible text part as a separate content block.
- The result looked like one word or a few characters per line.

Current fix:

- `ai/src/main/java/me/rerere/ai/ui/MessageStreamAccumulator.kt`
  - coalesces stream parts so explicit empty reasoning markers do not split visible text.
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt`
  - coalesces adjacent visible `Text` blocks at render time, so already-saved broken history displays correctly.
- Regression tests:
  - `ai/src/test/java/me/rerere/ai/ui/MessageStreamAccumulatorTest.kt`
  - `app/src/test/java/me/rerere/rikkahub/ui/components/message/ChatMessageCotTest.kt`

Do not "fix" this by adding random `fillMaxWidth()` constraints. That was tried and did not solve the real issue.

### 2. Live Status / Fluid Cloud Icon

OPPO/Android notification small icons are monochrome template icons. Original black-on-white vector colors are not preserved in the status island. A white circle plus black pig strokes can collapse into a plain white ball.

Current approach:

- `AgentLiveStatusNotifier` no longer sets a large icon for live status cards, so the large pig no longer blocks card text.
- `amberagent_live_status_icon.xml` uses an even-odd filled shape:
  - white outer circle
  - pig line art cut out as transparent negative space
  - system dark background shows through as the pig lines

This is intentional. Do not replace it with ordinary black stroke paths unless the target system is confirmed to preserve colors for notification small icons.

## Recent Non-UI Baseline Commits

Key recent commits:

```text
fd8b913b fix(ui): restore streamed message layout
101112eb fix(ui): polish live status and message layout
868e69e4 fix(agent): harden tool safety boundaries
71ad3584 fix(sync): harden backup restore handling
718483f6 fix(agent): harden terminal job runtime
```

Terminal and backup hardening are already done. Do not reopen those systems for a UI refactor unless a new UI bug directly requires it.

## UI Refactor Boundaries

Good first targets:

- Chat message visual hierarchy and spacing.
- Chat input ergonomics and cramped mobile layout.
- Tool cards readability and stable height/scroll behavior.
- Settings information architecture polish.
- Live status card copy and content priority.
- Icon sizing and visual consistency.

Avoid in the first UI pass:

- Provider/request marshalling.
- Tool execution semantics.
- Terminal runtime process management.
- SAF workspace and mirror logic.
- Room schema changes.
- ChatService architecture rewrite.
- Big visual rewrite of every screen.
- Whole-app design-system replacement.

## Current UI Risk Areas

### Chat Message Rendering

Relevant files:

```text
ChatMessage.kt
ChatMessageCot.kt
ChatMessageTools.kt
Markdown.kt
MarkdownNew.kt
```

Risk:

- Text layout issues may be data-shape issues, not width issues.
- Reasoning/tool/text parts have ordering semantics.
- Empty reasoning markers are metadata, not visible UI.

Before touching this area:

- Create or update a small unit test where possible.
- Test normal Chinese text, English words, mixed Chinese/English, markdown list, code block, tool result.

### Chat Input

Relevant file:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt
```

Risk:

- This file is large and old RikkaHub-heavy.
- Avoid a full rewrite.
- Keep attachment, tool, search, voice/OCR, and send controls functional.

### Tool Cards

Relevant file:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt
```

Risk:

- Tool calls can be pending, approval-needed, running, successful, failed, cancelled.
- Output may be truncated intentionally.
- Do not expose giant raw outputs in UI by default.

### Live Status / Notifications

Relevant files:

```text
app/src/main/java/me/rerere/rikkahub/data/agent/AgentLiveStatusNotifier.kt
app/src/main/res/drawable/amberagent_live_status_icon.xml
```

Risk:

- Device-specific rendering matters.
- Small icon is system-tinted/template-rendered.
- Verify on real phone, not only Android Studio preview.

## Verification Commands

Run after code changes:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:compileDebugKotlin --no-daemon --stacktrace
```

For the recent stream/message regression:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :ai:testDebugUnitTest --tests 'me.rerere.ai.ui.MessageStreamAccumulatorTest' \
  :app:testDebugUnitTest --tests 'me.rerere.rikkahub.ui.components.message.ChatMessageCotTest' \
  --no-daemon --stacktrace
```

For workspace/path safety when touching agent runtime or workspace:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.data.agent.workspace.WorkspacePathsTest' \
  --no-daemon --stacktrace
```

Build APK:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
ANDROID_HOME=/Users/arquiel/Library/Android/sdk \
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

Copy and install:

```bash
cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk /Users/arquiel/Downloads/AmberAgent-v0.8.3-arm64-debug.apk
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 987a53f3 install -r /Users/arquiel/Downloads/AmberAgent-v0.8.3-arm64-debug.apk
/Users/arquiel/Library/Android/sdk/platform-tools/adb -s 987a53f3 shell dumpsys package me.rerere.amberagent.debug | rg "versionCode|versionName"
```

## Manual Smoke Checklist

On the phone:

- Send a simple Chinese greeting.
  - Assistant response should render as normal paragraphs, not a vertical word column.
- Ask for a tool call.
  - Tool card should remain readable and not be hidden by the composer.
- Trigger live status / fluid cloud.
  - Small pig icon should be visible inside white circle.
  - Large pig should not appear inside the expanded live status card.
- Rotate if possible or test a wide screen/tablet later.
  - Do not make mobile look correct by hardcoding narrow-screen dimensions.

## Suggested Claude First-Pass Prompt

Use this prompt before asking Claude to edit UI:

```text
You are joining the AmberAgent Android project as an external UI architecture reviewer first, not an implementer yet.

Repository:
/Users/arquiel/Downloads/AI/rikkashit/rikkahub

Read these first:
1. docs/AMBERAGENT_HANDOFF_2026-05-04.md
2. docs/CLAUDE_UI_HANDOFF_2026-05-05.md

Current safe rollback point:
commit fd8b913b fix(ui): restore streamed message layout
tag amberagent-ui-baseline-before-claude-20260505

Before doing anything, run:
git status --short
git diff --stat
git diff --check
git log -5 --oneline

Task for this first pass:
- Do not edit files yet.
- Build a detailed mental model of the UI architecture.
- Identify the main Compose UI surfaces, especially chat list, message rendering, tool cards, chat input, settings, and live status notification.
- Explain which files are safe to refactor first and which files are risky.
- Pay special attention to the recent streamed-message layout regression:
  - empty explicit reasoning_content markers must not split visible text into many blocks
  - do not “fix” text layout by random fillMaxWidth constraints
- Pay special attention to Android notification small-icon constraints:
  - small icon is monochrome/template-rendered
  - current pig icon uses white fill plus transparent cutout lines
- Produce a UI refactor plan with small, reversible phases.
- For each phase, list exact files, intended behavior, risks, and verification steps.
- Do not propose whole-app rewrites, OpenOmniBot/OpenMinis UI copies, Room schema changes, provider marshalling changes, terminal runtime changes, or workspace logic changes.

Expected output:
1. A concise architecture map.
2. Findings ranked by risk.
3. A phased UI refactor plan.
4. Tests/smoke checks for each phase.
5. A list of “do not touch yet” areas.

Only after this review is accepted should you start implementation.
```
