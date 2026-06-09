# Codex Handoff 2026-05-30: Streaming Reveal Recovery

This document recovers the unfinished Codex session that hit the AndroMeld MCP
schema error before it could write a handoff.

## Session Recovery Note

- Broken old Codex thread: `019e711a-1f91-7931-a2c6-384016ce1f5c`
- Symptom after phone reconnect: the old thread kept failing with:
  `Invalid schema for function 'andromeld_ui_set_text'`
- Cause: the old session had already cached the bad AndroMeld tool schema. Turning
  the MCP off affects newly loaded sessions, but it does not clean tools already
  attached to the old conversation.
- Do not continue that old thread. Start a clean Codex thread from this handoff.

## Current Repo Anchor

- Repo: `/Users/arquiel/Downloads/AI/amberagent/main`
- Branch: `main`
- Tracking: `github-private/main`
- Current HEAD at handoff creation: `a5101798 refactor: prune legacy chat and web surfaces`
- Branch state: `main` is `ahead 1` of `github-private/main`
- Product remote: `github-private`
- Upstream reference remote only: `origin`
- Default rule: do not push unless the user explicitly asks.

Older baseline still matters as a known good anchor, but it is no longer HEAD:

- Baseline tag: `baseline/amber-main-2026-05-29`
- Baseline commit: `e6b81600 test: repair rebrand baseline checks`
- Baseline command previously passed: `./gradlew assembleNotion test`

## User Problem Being Solved

The user is tuning the streaming chat rendering experience:

1. Text should appear smoothly with visible per-character reveal, not in large
   clumps.
2. Very fast models such as DeepSeek V4 must not outrun the display buffer and
   then dump a large final wall of text at completion.
3. Bottom follow should feel attached to the generated content bottom. When the
   user scrolls up during generation, the loading indicator should move as part
   of the timeline, not appear/disappear as a detached overlay.
4. Markdown repair/reveal must not cause whole-message re-fade, HTML first-frame
   blanking, or invalid synthetic suffix visibility.

## Current Dirty Worktree

Tracked dirty files at handoff creation:

```text
app/src/main/java/app/amber/core/nativepath/NativePathBootstrap.kt
app/src/main/java/app/amber/core/sync/core/SyncCryptoNative.kt
app/src/main/java/app/amber/feature/board/hotlist/HotListTitleLocalizer.kt
app/src/main/java/app/amber/feature/board/hotlist/deepread/DeepReadSourcePrefetcher.kt
app/src/main/java/app/amber/feature/deepread/nativebridge/ReaderExtractorNative.kt
app/src/main/java/app/amber/feature/ui/components/message/ChatMessage.kt
app/src/main/java/app/amber/feature/ui/components/message/ChatMessageMessagePartsBlock.kt
app/src/main/java/app/amber/feature/ui/components/message/ChatMessageRenderers.kt
app/src/main/java/app/amber/feature/ui/components/richtext/CharReveal.kt
app/src/main/java/app/amber/feature/ui/components/richtext/HighlightCodeBlock.kt
app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt
app/src/main/java/app/amber/feature/ui/components/richtext/MarkdownNew.kt
app/src/main/java/app/amber/feature/ui/components/richtext/MarkdownSplit.kt
app/src/main/java/app/amber/feature/ui/pages/chat/ChatJankProbe.kt
app/src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt
app/src/test/java/app/amber/agent/ui/components/richtext/RevealOverlayParityTest.kt
common/src/main/java/app/amber/core/json/expr/JsonExprNative.kt
```

Untracked files at handoff creation:

```text
app/src/androidTest/java/app/amber/agent/AmberAgentAndroidTestRunner.kt
app/src/androidTest/java/app/amber/agent/NativePathDeviceBenchmarkTest.kt
app/src/main/java/app/amber/feature/ui/components/richtext/StreamingRenderProbe.kt
app/src/test/java/app/amber/agent/ui/components/richtext/StreamingDisplayBufferTest.kt
app/src/test/java/app/amber/agent/ui/components/richtext/StreamingMarkdownRepairTest.kt
```

Important: not every dirty file is part of the final streaming UI fix. Native path
and sync/deepread files pre-existed in this dirty tree. Do not revert them without
inspecting ownership.

## Streaming Changes Already Applied

### 1. Display Buffer Catch-Up

File: `app/src/main/java/app/amber/feature/ui/components/richtext/CharReveal.kt`

Current intended behavior:

- `rememberStreamingDisplayText` still uses a frame-clock display buffer.
- It now targets backlog drain time instead of a fixed `base + backlog * gain`.
- Current constants:
  - `STREAM_DISPLAY_TARGET_DRAIN_SECONDS = 0.28f`
  - `STREAM_DISPLAY_FINAL_TARGET_DRAIN_SECONDS = 0.10f`
  - `STREAM_DISPLAY_MAX_CHARS_PER_SEC = 1_200f`
  - `STREAM_DISPLAY_FINAL_MAX_CHARS_PER_SEC = 2_400f`
  - `STREAM_DISPLAY_MIN_EMIT_INTERVAL_NANOS = 8_000_000L`
  - `STREAM_DISPLAY_MAX_CHARS_PER_EMIT = 18`
  - `STREAM_DISPLAY_FINAL_MAX_CHARS_PER_EMIT = 32`
  - `STREAM_DISPLAY_MAX_BACKLOG_CHARS = 420`

Reason: the earlier one-character-per-frame version looked smoother but could not
catch up with fast models. DeepSeek V4 could finish while the UI still owed a
large backlog, then completion would appear as a large dump.

The 2026-05-30 continuation added a hard lag bound: if the display buffer falls
more than 420 chars behind, it fast-forwards visible text just enough to leave at
most 420 chars of backlog, then returns to the normal smooth drain path. This
keeps fixed speed caps as comfort targets rather than correctness limits.

Do not blindly restore `MAX_CHARS_PER_EMIT = 1`. If changing this again, validate
both:

- fast-model backlog catch-up before final completion;
- no visible 15 to 20 character batch jumps during normal streaming.

Tests added:

- `StreamingDisplayBufferTest`

### 2. Reveal Degrade No Longer Hard-Jumps To Black

File: `CharReveal.kt`

The old degrade path cleared the reveal queue and promoted all queued text to
alpha 1. That could look like a black flash when the UI was already under load.

Current path:

- `compressRevealQueue(frameNanos)`
- `drainCompletedEntries(frameNanos)`
- compressed duration cap: `HARD_DEGRADE_REVEAL_DURATION_NANOS = 72_000_000L`
- `ageForEaseOutAlpha` keeps alpha continuous through compression.

Test coverage:

- `RevealOverlayParityTest.hard_degrade_keeps_reveal_entries_in_flight`

### 3. Markdown Parse Throttle No Longer Blocks Latest Text

File: `app/src/main/java/app/amber/feature/ui/components/richtext/Markdown.kt`

Problem found during the session: `renderContent` was growing every frame, but
the visible AST was still gated by the streaming markdown parse throttle. This
made text appear in large chunks even when the display buffer itself was updating.

Current fix:

- `streamingLiveSuffixFor(...)` exposes raw text newer than the throttled parse.
- The live suffix is appended into the active paragraph `Text`.
- Synthetic repair suffix is excluded from live text.
- Parse can still be throttled for expensive Markdown structure work.

Test coverage:

- `StreamingMarkdownRepairTest.live suffix exposes text newer than throttled parse`
- `StreamingMarkdownRepairTest.live suffix ignores synthetic repair suffix`
- `StreamingMarkdownRepairTest.live suffix stays empty when parsed text no longer matches source`

### 4. Streaming Markdown Tail Repair

File: `Markdown.kt`

Current repair helper:

- `repairStreamingMarkdownTail(tail)`
- repairs unfinished fenced code, inline code, strong markers, and math markers;
- tracks `syntheticSuffixStart`;
- uses non-overlapping counts for `**` and `$$`.

Reason: streaming partial Markdown should not destabilize parsing, but synthetic
repair characters must never become visible or count as source content.

Test coverage:

- unfinished strong marker;
- unfinished inline code;
- unfinished fenced code;
- unfinished inline math;
- valid `**amber**` no-op;
- adjacent `****` no-op;
- valid double-backtick code span no-op;
- valid `$$mc$$` no-op.

### 5. Reveal Controller Uses Stable Render Coordinates

File: `Markdown.kt`

Claude review found a real P1 risk: the reveal controller could use
`data.preprocessed` before commit and `renderContent` after commit. If repair
synthetic text made `data.preprocessed` longer, switching back to `renderContent`
could look like truncation and trigger whole-message re-fade.

Current fix:

- `rememberCharRevealController(content = renderContent)`
- `commit = min(parserStableEnd, revealStableEnd)` still preserves parser/reveal
  coordination.
- source offsets are carried with `LocalMarkdownSourceOffsetBase`.
- synthetic suffix cutoff is carried with `LocalMarkdownSyntheticSuffixStart`.

### 6. HTML First Frame No Longer Blank

File: `app/src/main/java/app/amber/feature/ui/components/richtext/MarkdownNew.kt`

Claude review found a P2 regression: initializing with `Jsoup.parse("")` caused
HTML blocks to show a blank first frame until the background parse completed.

Current fix:

- initial state uses `parseMarkdownHtmlDocument(content)` synchronously;
- later updates still use `Dispatchers.Default + mapLatest`.

### 7. Bottom Follow Changed To Timeline-Anchored Behavior

File: `app/src/main/java/app/amber/feature/ui/pages/chat/ChatListNormalSection.kt`

Current intent:

- Disable the pinned `AgentWorkingIndicator` overlay by setting
  `showPinnedAgentWorkingIndicator = false`.
- Keep the working indicator inside the `LazyColumn` timeline.
- While following the bottom, call `requestScrollToItem(totalItems - 1)` through
  `requestTimelineBottom(...)` instead of small `scrollBy` catch-up steps.
- Add `deferStreamingParse` while the user has paused follow mode by scrolling up,
  to avoid spending parse work on content the user is not watching.

This was motivated by the user's comparison with RikkaHub: the indicator should
move with the generated content bottom, not hide/reappear as a detached overlay.

## Verification From The Broken Session

Before the final fast-model catch-up change, the old session reported:

- `git diff --check`: passed.
- `./gradlew :app:testDebugUnitTest --tests ...RevealOverlayParityTest --tests ...StreamingMarkdownRepairTest`: passed.
- `./gradlew assembleNotion`: passed.
- installed to device `3B164901CEF00000`: success.
- launched package `app.amber.agent.notion`: process existed.
- recent `AndroidRuntime`: no crash.
- screenshot path at that time: `/private/tmp/amber_after_install.png`.

After the user reported DeepSeek V4 was too fast and final completion dumped text,
the old session applied the latest display-buffer catch-up changes and reported:

- Kotlin compile and related JVM tests passed.
- `assembleNotion` passed.
- APK was built.
- install did not happen because `adb devices` returned empty after the device
  disconnected.

Then the user reconnected the phone, but the old thread hit the AndroMeld cached
schema error before it could install, re-test, or write this handoff.

2026-05-30 continuation in a clean thread:

- reviewed Claude's follow-up and confirmed the current code had already moved
  past `MAX_CHARS_PER_EMIT = 1`, but still had a fixed 1200 cps comfort ceiling;
- added the 420-char hard backlog cap in `CharReveal.kt`;
- added `StreamingDisplayBufferTest` coverage for the hard lag bound;
- `git diff --check`: passed;
- `./gradlew :app:testDebugUnitTest --tests 'app.amber.feature.ui.components.richtext.StreamingDisplayBufferTest' --tests 'app.amber.feature.ui.components.richtext.StreamingMarkdownRepairTest' --tests 'app.amber.feature.ui.components.richtext.RevealOverlayParityTest'`: passed;
- `./gradlew assembleNotion`: passed;
- installed `app-arm64-v8a-notion.apk` to device `3B164901CEF00000`: success;
- launched package `app.amber.agent.notion`, process `17859` existed, recent
  `AndroidRuntime:E` had no output;
- visual screenshot showed the phone on the lock screen, with
  `mFocusedApp=app.amber.agent.notion/app.amber.agent.RouteActivity`, so a real
  in-app streaming visual check still needs the user to unlock the device.

## Recommended Next Steps

Start with read-only reconciliation:

```bash
cd /Users/arquiel/Downloads/AI/amberagent/main
git status --short
git branch -vv
git log --oneline -8
git diff --stat
```

Then run the narrow gates:

```bash
./gradlew :app:testDebugUnitTest \
  --tests 'app.amber.agent.ui.components.richtext.RevealOverlayParityTest' \
  --tests 'app.amber.agent.ui.components.richtext.StreamingMarkdownRepairTest' \
  --tests 'app.amber.agent.ui.components.richtext.StreamingDisplayBufferTest'

./gradlew assembleNotion
```

Install and smoke test:

```bash
ADB=/Users/arquiel/Library/Android/sdk/platform-tools/adb
$ADB devices -l
$ADB install -r app/build/outputs/apk/notion/app-arm64-v8a-notion.apk
$ADB shell monkey -p app.amber.agent.notion 1
$ADB shell pidof app.amber.agent.notion
$ADB logcat -d -t 300 AndroidRuntime:E '*:S'
```

Manual validation target:

1. Use a fast streaming model, preferably the same DeepSeek V4 path the user
   complained about.
2. Ask for a long answer with regular prose and some Markdown markers.
3. Watch for:
   - no 15 to 20 character visual jumps during normal streaming;
   - no large final dump when the model completes;
   - no whole-message re-fade at Markdown commit boundaries;
   - no blank first frame for HTML blocks;
   - bottom follow stays attached to the timeline bottom;
   - when user scrolls up during generation, the working indicator leaves with
     the timeline instead of popping out as overlay.

If the user still sees jumps, inspect logs from `StreamingRenderProbe` before
changing constants. Important log labels include:

- `display_emit`
- `reveal_content`
- `parse_deferred`
- `reveal_degrade`
- `streaming_display_completed`

## Known Risks And Guardrails

- The current `18/32` emit caps are intentionally not single-character caps.
  They are a compromise for fast-model catch-up. Tune from device evidence.
- AndroMeld mirroring may be 60Hz and cannot prove true 120Hz smoothness. Use
  real-device observation or Perfetto FrameTimeline if the claim is about 120Hz.
- `StreamingRenderProbe.kt` is untracked and diagnostic. Decide whether it should
  be committed, feature-gated, or removed before final commit.
- `StreamingMarkdownRepairTest.kt` and `StreamingDisplayBufferTest.kt` are
  untracked. Do not forget them if committing the streaming fixes.
- Current worktree has unrelated dirty native/sync/deepread files. Do not revert
  or commit them blindly.

## Paste-Ready Prompt For Next Clean Codex Session

```text
You are continuing AmberAgent Android/Kotlin work from a broken Codex session.
Read this handoff first:

/Users/arquiel/Downloads/AI/amberagent/main/docs/CODEX_HANDOFF_2026-05-30_STREAMING_REVEAL_RECOVERY.md

Repo:
/Users/arquiel/Downloads/AI/amberagent/main

Important:
- Do not use old paths under /Users/arquiel/Downloads/AI/rikkashit for current mainline work.
- Product remote is github-private. origin is upstream reference only.
- Do not push unless explicitly asked.
- Current branch is main, HEAD was a5101798 when the handoff was written, and main was ahead 1 of github-private/main.
- The old Codex thread cached a bad AndroMeld MCP schema, so do not try to continue that thread.

Task:
Verify and finish the streaming Markdown/reveal/bottom-follow recovery.
First reconcile live git status, then run the narrow richtext tests and assembleNotion.
Install the latest Notion APK on the connected device and validate fast-model streaming:
no large final text dump, no 15-20 char visual jumps, no whole-message re-fade, no HTML first-frame blank, and bottom follow stays attached to the timeline.

Keep the scope narrow. Do not revert unrelated dirty native/sync/deepread changes without inspecting ownership.
```
