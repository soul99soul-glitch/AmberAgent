# AmberAgent Codex Handoff - 2026-05-07 - v1.1.7

## Purpose

This document is the current handoff for continuing AmberAgent development after the long Codex session on 2026-05-07.

`docs/AMBERAGENT_HANDOFF_2026-05-04.md` is now historical background only. Its branch names, APK paths, version numbers, and immediate task list are stale. Use this document plus live `git` state as the current source of truth.

## Current Repo State

- Repo: `/Users/arquiel/Downloads/AI/rikkashit/rikkahub`
- Branch: `experiment/ui-redesign-20260505`
- Latest committed anchor: `7d34904d perf(chat): improve timeline loading and run interactions`
- Current APK: `/Users/arquiel/Downloads/AmberAgent-v1.1.7-notion.apk`
- APK package: `me.rerere.amberagent.notion`
- APK version: `versionName=1.1.7`, `versionCode=194`

Before continuing, run:

```bash
git status --short
git branch --show-current
git log -8 --oneline
git diff --stat
git diff --check
```

At the time this handoff was written, the worktree had uncommitted UI edits in:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/ui/UIAvatar.kt
app/src/main/java/me/rerere/rikkahub/ui/hooks/AvatarShape.kt
```

Treat those as user/session work. Do not overwrite or revert them unless explicitly asked.

## Recent Commit Trail

```text
7d34904d perf(chat): improve timeline loading and run interactions
c1affbfa fix(ui): tune queued message layout and scrolling
924e282d feat(agent): harden search and queued input UX
9f69a23a feat(settings): refine search service configuration
aca84383 fix(agent): harden queued generation retry cancellation
a78f0293 feat(agent): add resilient generation and queued input
70b024ef feat(agent): expose MCP calls and clean TTS defaults
e9e31fe1 feat(experimental): improve icloud and model council
```

## What Was Just Finished

### Timeline Progressive Loading

The latest commit changed long session loading so a conversation opens with the newest messages first instead of waiting for the full transcript to decode.

Key behavior:

- Initial session load reads the latest `80` message nodes.
- Older history is prefetched in the background in `40`-node batches.
- `ConversationTimelineLoadState` tracks loaded count, total count, oldest loaded index, and prefetch state.
- Mutating operations now guard against partial-history writes by forcing full conversation loading before saving/editing/deleting/forking/regenerating/compressing.
- Compact markers account for `oldestLoadedIndex` so they do not shift when only the tail window is visible.
- Auto-follow scrolling now pauses during user touch/scroll. After 2 seconds:
  - near bottom: animates back to bottom;
  - far from bottom: shows a bottom-follow button instead of forcibly pulling the user down.

Relevant files:

```text
app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt
app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt
app/src/main/java/me/rerere/rikkahub/service/ChatService.kt
app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
```

### Queued Input Interaction

Queued messages were adjusted so the UI does not need a separate send and stop button row during agent runs.

Current intended behavior:

- Agent running + input empty: main button stops current run.
- Agent running + input has text:
  - short press send: queue as next-round follow-up;
  - long press send: queue as `STEER`, injected at the next safe tool boundary.
- Follow-up queued bubbles use gray dashed border.
- Steer/interruption bubbles use light blue dashed border.
- Queue count appears under the last queued bubble, not in the model/options row.

Relevant files:

```text
app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt
```

### Search And WebView Work

Recent work added stronger search orchestration and WebView read/wait behavior. The current line includes:

- `search_web` hardening and multi-source search behavior.
- Notion-like search service settings.
- WebView operation state tracking and improved load/read wait behavior.
- WebView preview/card fixes are still an active area; verify on device before assuming final stability.

Relevant files include:

```text
app/src/main/java/me/rerere/rikkahub/data/agent/webview/WebViewOperationStore.kt
app/src/main/java/me/rerere/rikkahub/ui/pages/settings/...
```

## Verification Already Done

Most recent local checks:

```bash
git diff --check
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:compileNotionKotlin --no-daemon --stacktrace
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleNotion --no-daemon --stacktrace
```

`assembleNotion` succeeded and produced `/Users/arquiel/Downloads/AmberAgent-v1.1.7-notion.apk`.

Known build warnings at that time:

- `AgentPermissionBroker.kt`: `unsafeCheckOpNoThrow` deprecated.
- `ChatInput.kt`: Haze experimental API warnings.
- `ChatPage.kt`: `currentWindowDpSize()` deprecated in compile-only runs.

These warnings did not block the build.

## Open Risks / Next Checks

### Timeline Performance Still Needs Device Profiling

The latest progressive-loading work compiled, but it has not been proven by `gfxinfo` or Perfetto in this handoff. The next session should validate on real device:

- Long session open latency.
- Upward scroll jank around assistant-run starts.
- Whether background prefetch causes small periodic frame drops.
- Whether automatic follow resume feels natural.

Useful checks:

```bash
ADB=/Users/arquiel/Library/Android/sdk/platform-tools/adb
$ADB shell dumpsys gfxinfo me.rerere.amberagent.notion framestats
```

Consider the `test-android-apps:android-performance` skill if doing a full perf pass.

### Partial Timeline Safety

The code now tries to force full conversation loading before mutations. If touching this area, pay special attention to:

- `saveConversation`
- `editMessage`
- `deleteMessage`
- `forkConversationAtMessage`
- `regenerateAtMessage`
- `handleMessageComplete`
- `compressConversation`

Do not allow a partial tail-window conversation to overwrite the full persisted transcript.

### Dirty UI Avatar Files

The current worktree may have uncommitted edits in avatar-related UI files. Inspect before editing:

```bash
git diff -- app/src/main/java/me/rerere/rikkahub/ui/components/ui/UIAvatar.kt
git diff -- app/src/main/java/me/rerere/rikkahub/ui/hooks/AvatarShape.kt
```

## Suggested Bootstrap Prompt For New Session

```text
继续 AmberAgent 项目。

仓库：
/Users/arquiel/Downloads/AI/rikkashit/rikkahub

当前分支：
experiment/ui-redesign-20260505

请先执行：
git status --short
git branch --show-current
git log -8 --oneline
git diff --stat
git diff --check

请先阅读：
docs/CODEX_HANDOFF_2026-05-07_V1_1_7.md

注意：
docs/AMBERAGENT_HANDOFF_2026-05-04.md 只能作为历史背景，不代表当前状态。

当前锚点：
7d34904d perf(chat): improve timeline loading and run interactions

当前 APK：
/Users/arquiel/Downloads/AmberAgent-v1.1.7-notion.apk

当前目标：
【填写本轮唯一目标】

执行规则：
- 不重开大坑。
- 不回滚 UI 实验线。
- 不覆盖未提交的用户改动。
- 修改前先读相关代码。
- 每次修改后至少跑 git diff --check 和 compileNotionKotlin。
- 涉及 APK 时版本号继续递增。
```

