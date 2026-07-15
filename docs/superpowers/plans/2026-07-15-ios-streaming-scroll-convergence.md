# iOS Streaming Scroll Convergence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make standard Chat, Model Council, and Novel Creation share the same lightweight streaming presentation behavior: native scrolling, smooth upward growth, user-owned history browsing with near-bottom resume, live Markdown, bounded UI publication, and graceful new-text fade.

**Architecture:** Keep each feature's existing generation, persistence, and business state owner. Standard Chat remains the reference implementation. Converge only three presentation contracts: publish the latest accumulated text on a bounded display cadence, flush the exact terminal snapshot before completion, and animate only the bottom scroll position during live content growth. Reuse `ChatAssistantMarkdownView` and the vendored `SwiftStreamingMarkdown`; do not introduce a new chat framework or a cross-feature ViewModel.

**Tech Stack:** Swift 6, SwiftUI native `ScrollView` / `ScrollPosition`, Observation, `AsyncStream`, existing `SwiftStreamingMarkdown`, XCTest, Xcode 26 iOS Simulator.

---

## Scope guardrails

- Do not replace `ChatSwiftUIMessageList`, `NovelSessionView`, or Council's bounded native `ScrollView`.
- Do not change provider request protocols, domain/persistence ownership, persistence formats, Markdown vendor code, or the existing 40pt true-bottom threshold. The separate near-bottom resume intent uses the existing 96pt follow gap.
- Do not add a dependency, semantic word tokenizer, second animation overlay, raw `contentOffset` write, or geometry compensation.
- Preserve standard Chat's stable lazy history + non-lazy live tail and Novel's equivalent structure.
- “逐词淡入” continues to mean the renderer's fade of the newly published glyph range. The presentation pacer controls arrival batches; it does not parse language.

## Corner-case contract

- A short transcript that becomes scrollable mid-stream starts following without a one-line jump.
- User tracking or deceleration wins immediately over auto-follow. Returning within the 96pt resume threshold resumes follow and then performs a semantic bottom write; passive layout changes cannot clear a history-reading pause.
- Nested horizontal scrolling in code blocks/tables does not become a second vertical follow state.
- Keyboard/safe-area changes and the running-to-terminal spacer shrink keep the bottom anchored only when the user was already following.
- Provider bursts are accumulated without loss; completion/interruption/failure publishes the exact latest snapshot before changing phase.
- Council cancel/error closes every speaking row after the exact tail is published. An empty tail shows the failure reason rather than persisting a loading placeholder.
- Council cancellation while waiting for user input resumes the pending continuation; immediate restart gets a new run identity, and the old run cannot emit into or cancel the replacement run.
- Non-prefix replacement snapshots bypass incremental append assumptions and become authoritative immediately.
- Incomplete emphasis, links, code fences, lists, and table rows remain on the existing streaming Markdown repair path; stable blocks and cache identities must not remount.
- CJK, emoji, and composed `Character` values must not be split by byte or UTF-16 offsets.
- Reduce Motion disables the 80ms scroll animation and does not disable content delivery or Markdown formatting.
- The Council measured-growth task owns the entire 80ms animation window, so later Markdown measurements cannot restart the same animation mid-flight.
- Conversation/branch/run switches cancel pending presentation work so stale text cannot enter the new screen.
- History prepend keeps its semantic anchor; stream growth never rewrites historical row identity.

## Task 1: Lock the presentation contracts with focused tests

**Files:**
- Modify: `iosApp/iosAppTests/ChatViewportPolicyTests.swift`
- Modify: `iosApp/iosAppTests/IOSCouncilRunnerMechanicsTests.swift`
- Modify: `iosApp/iosAppTests/NovelSessionViewModelTests.swift`
- Modify: `iosApp/iosAppTests/NovelSessionReplayTests.swift`

- [x] Add standard Chat policy tests proving 95/96pt resumes while 97pt remains paused, without weakening the existing 40pt true-bottom meaning.
- [x] Preserve the standard Chat measured-growth scheduler; a local A/B showed that adding a pending-task gate regressed the long-table display-link gate.
- [x] Add a Council presentation-session test proving multiple queued chunks publish once on flush, in FIFO order, and a second flush does not duplicate text.
- [x] Add Council message tests proving speaking host/guest rows request the streaming Markdown renderer and completed streamed rows retain the same renderer without re-fading.
- [x] Add a Novel burst test proving many immediate deltas produce the exact accumulated tail with materially fewer render revisions than delta count.
- [x] Extend the Novel view wiring assertion so live growth uses the same 80ms linear scroll-position animation as standard Chat and respects Reduce Motion.
- [x] Run the focused tests before implementation and confirm the new assertions fail for the intended missing behavior.

Run:

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/ChatViewportPolicyTests \
  -only-testing:iosAppTests/IOSCouncilRunnerMechanicsTests \
  -only-testing:iosAppTests/NovelSessionViewModelTests \
  -only-testing:iosAppTests/NovelSessionReplayTests test
```

## Task 2: Correct standard Chat's near-bottom resume and preserve measured-growth behavior

**Files:**
- Modify: `iosApp/iosApp/ChatCollectionMessageList.swift`
- Modify: `iosApp/iosApp/ChatMessageListSupport.swift`
- Test: `iosApp/iosAppTests/ChatViewportPolicyTests.swift`

- [x] Keep the existing 48ms display clock, 12-`Character` visible step, exact terminal drain, FIFO sink, and non-prefix fallback unchanged.
- [x] Name the 96pt near-bottom resume threshold separately from the 40pt true-bottom threshold and use it only after genuine user scroll activity.
- [x] Resume follow within that threshold without pre-claiming a false exact-bottom geometry state.
- [x] Keep the existing measured-growth scheduler unchanged after the A/B performance gate rejected extra task coalescing.
- [x] Leave measured-growth ownership unchanged; no second pending task or cancellation state remains in standard Chat.
- [x] Run `ChatViewportPolicyTests`, `ChatSwiftUIStreamReplayTests`, `IOSParityRedLightTests`, and `ChatStreamReplayTests`.

## Task 3: Put Model Council on the real streaming presentation path

**Files:**
- Modify: `iosApp/iosApp/CouncilRunner.swift`
- Modify: `iosApp/iosApp/CouncilChatRuntimeView.swift`
- Test: `iosApp/iosAppTests/IOSCouncilRunnerMechanicsTests.swift`

- [x] Replace per-chunk `Task { @MainActor ... }` publication with one local FIFO `AsyncStream` consumer and one 48ms latest-snapshot presentation task.
- [x] Keep accumulation lossless; completion/error/cancel closes the local stream and flushes the exact latest text.
- [x] Pass stable message identity, `isStreaming`, and `hasEverStreamed` into `ChatAssistantMarkdownView` so Markdown formatting and glyph fade match standard Chat.
- [x] Keep the bounded non-lazy Council transcript, but animate live bottom growth with `.linear(duration: 0.08)`; new-message and explicit navigation behavior remain unchanged.
- [x] Move the running/rest spacer to one stable bottom sentinel and re-follow on measured positive content-height growth, including asynchronous Markdown layout growth.
- [x] Respect Reduce Motion and keep user drag/deceleration as the only owner of pause/resume.
- [x] Give every Council run a distinct generation, reject stale ViewModel events, resume a pending user-question continuation on cancellation, and prevent an old timeout/cancel path from cancelling an immediate replacement run.
- [x] Close all speaking rows on error/cancel without replacing a non-empty accepted tail; retain the failure reason when no text was generated.
- [x] Hold measured-growth single-flight ownership for the complete 80ms animation instead of only one `Task.yield()`.
- [x] Run `IOSCouncilRunnerMechanicsTests` and the shared chat replay gate.

## Task 4: Decouple Novel provider deltas from observable list projection

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionViewModel.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionView.swift`
- Test: `iosApp/iosAppTests/NovelSessionViewModelTests.swift`
- Test: `iosApp/iosAppTests/NovelSessionReplayTests.swift`

- [x] Accumulate delta/replacement content in ignored presentation state keyed by `NovelRunID`.
- [x] Publish at most once per 48ms window; do not recompute the full session projection for each raw provider delta.
- [x] Flush/cancel pending presentation state on terminal, detach, binding switch, stop, and clear-tail paths; terminal snapshots remain authoritative.
- [x] Do not alter `NovelGenerationLifecycle`, durable partial persistence, reattach, or projection semantics.
- [x] Animate only live stream bottom growth with `.linear(duration: 0.08)` and retain immediate terminal settling plus the existing explicit-bottom animation.
- [x] Run `NovelSessionViewModelTests`, `NovelSessionReplayTests`, and `IOSNovelCreationWiringTests`.

## Task 5: Regression and performance-safety gate

**Files:**
- Modify if current facts changed: `docs/PROJECT_STATE.md`

- [x] Run the mandatory default-path stream replay gate:

```bash
xcodebuild -quiet -project iosApp/AmberAgent.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  -parallel-testing-enabled NO \
  -only-testing:iosAppTests/ChatStreamReplayTests test
```

- [x] Run `ChatViewportPolicyTests`, `ChatSwiftUIStreamReplayTests`, `ChatMessageProjectionTests`, and the three feature-focused suites above.
- [x] Run a Stable Debug simulator build and `git diff --check`.
- [x] Audit the final diff: no vendor change, no new dependency, no alternate scroll container, no unrelated cleanup.
- [x] Record simulator evidence honestly; leave 120Hz feel, keyboard interaction, nested horizontal scroll, and long real-provider output as explicit device checks unless actually verified on device.
- [x] Update `docs/PROJECT_STATE.md` with the completed slice, exact tests, and remaining device evidence.

## Verification result (2026-07-15)

- The final combined gate on iPhone 17 Pro Simulator ran `ChatViewportPolicyTests`, `IOSCouncilRunnerMechanicsTests`, `NovelSessionViewModelTests`, `NovelSessionReplayTests`, and the mandatory `ChatStreamReplayTests`: 137 passed, 1 expected skip, 0 failed. Result: `/tmp/amber-streaming-scroll-dd/Logs/Test/Test-iosApp-2026.07.15_17-15-30-+0800.xcresult`.
- Council's final corner-case suite is 19/19, including cancel→terminal tail→immediate restart, stale-event rejection, empty/partial error tails, FIFO terminal drain, live Markdown flags, near-bottom resume, and full-animation single-flight ownership.
- `testContinuousProseGrowthStaysLineSizedWhileFollowingBottom` and `testPerfGrowingTableStreamingKeepsDisplayLinkResponsive` passed together 2/2 in a separate performance run. Result: `/tmp/amber-streaming-scroll-dd/Logs/Test/Test-iosApp-2026.07.15_17-09-38-+0800.xcresult`.
- The broader regression run passed 166 tests with one expected skip; two display-link sampling tests became unstable only after many suites shared one simulator process. Their thresholds were not relaxed. After shutting down the simulator, `testContinuousProseGrowthStaysLineSizedWhileFollowingBottom` and `testPerfGrowingTableStreamingKeepsDisplayLinkResponsive` each passed in isolation.
- A narrow A/B rejected standard Chat measured-growth task coalescing: the added gate produced repeatable 82–95ms long-table spikes, while the original scheduler passed twice in isolation. The experimental coalescing code was removed.
- Final Stable Debug generic iOS Simulator build passed with the repository-required `ARCHS=arm64 ONLY_ACTIVE_ARCH=NO CODE_SIGNING_ALLOWED=NO`; the unrestricted generic build attempted x86_64 and failed against the arm64-only local native slice, as documented by the repository.
- Three independent read-only reviews of Standard Chat, Council, and Novel finished with no remaining P0-P2.
- `git diff --check` passed. No vendor file, dependency, provider contract, persistence schema, or alternate scroll container was changed by this slice. True 120Hz feel, keyboard/safe-area interaction, nested horizontal Markdown scrolling, and long real-provider output remain device checks.

## Closure remediation pass (2026-07-15)

Fresh production-call-chain review invalidated the earlier “no remaining P0-P2” conclusion. Keep the architecture and default Chat path unchanged; close only the demonstrated gaps below.

### Task 6: Close Council's concrete stream and lifecycle gaps

**Files:**
- Modify: `iosApp/iosApp/CouncilRunner.swift`
- Modify: `iosApp/iosApp/CouncilChatRuntimeView.swift`
- Test: `iosApp/iosAppTests/IOSCouncilRunnerMechanicsTests.swift`

- [x] Prove the concrete accumulator publishes no generated text before an assistant message exists; usage-only, empty, first-token error, and cancellation must never expose the internal user prompt.
- [x] When the runtime view is detached, skip a future mandatory user question and resume any already-pending continuation with `nil`, while allowing the background run to finish and archive normally.
- [x] Preserve one pending measured-growth replay during Council's existing 80ms animation window; do not create a second scroll coordinator.
- [x] Re-anchor with the same semantic bottom write when the visible viewport shrinks while follow ownership is active, covering keyboard/safe-area changes without raw offsets.
- [x] Commit a semantic bottom write when a user drag ends near bottom, including the canceled-owner handoff window.
- [x] Before running `openArchive` or room reset replaces the current room, synchronously drain, terminalize, persist, and archive the active room without adding a synthetic chat message.

### Task 7: Close Novel's measured-layout and branch-ownership gaps

**Files:**
- Modify: `iosApp/iosApp/NovelCreation/NovelSessionView.swift`
- Modify: `iosApp/iosApp/NovelCreation/NovelCreationViewModel.swift`
- Test: `iosApp/iosAppTests/NovelSessionReplayTests.swift`
- Test: the narrow existing Novel creation/session suite that exercises branch selection

- [x] Dispatch the existing live `.streamDelta` follow event for every positive asynchronous Markdown height growth while not dragging; keep terminal growth on `.terminalLayoutChanged`.
- [x] Commit the existing semantic bottom command when a user drag ends inside the near-bottom resume zone.
- [x] Make production branch selection validate the target before interrupting a running durable run owned by the previously selected branch; ordinary route detach remains non-destructive.
- [x] Read the validated target once, refresh only project metadata after interruption, and restore the authoritative source snapshot once if that refresh fails.
- [x] Preserve last-intent-wins between explicit project/branch selection while allowing internal terminal refreshes to keep their existing token.

### Task 8: Re-run closure gates

- [x] Run the Council and Novel focused regression tests first and verify the production canaries fail before their fixes.
- [x] Run `ChatStreamReplayTests` unchanged as the mandatory default-path gate.
- [x] Run the affected-suite regression, Stable Debug arm64 simulator build, and `git diff --check`.
- [x] Replace the stale review claim in `docs/PROJECT_STATE.md` with the new findings, fixes, exact test evidence, and remaining device-only checks.
- [x] Have fresh read-only subagents re-audit state ownership, terminal paths, and production call-chain reachability after the fixes.

### Closure verification result

- Focused canaries reproduced and then closed prompt leakage, detached-question hangs, dropped Council growth/near-bottom requests, active-room replacement loss, Novel async Markdown growth, unsafe branch interruption, post-interrupt stale snapshots, duplicate target reads, and last-intent selection races.
- iPhone 17 Pro Simulator green shards: 161/161 for the six affected suites (`Test-iosApp-2026.07.15_19-18-09-+0800.xcresult`) and 16 passed + 1 expected skip for the unchanged mandatory `ChatStreamReplayTests` (`Test-iosApp-2026.07.15_19-16-06-+0800.xcresult`).
- One all-in-one run reached 176 passed + 1 skip before the test process was killed by signal during one Chat replay. The complete Chat replay class passed when rerun alone; no Chat production code or threshold was changed in the closure pass.
- Stable Debug generic iOS Simulator arm64 build and `git diff --check` passed. Three fresh read-only subagents returned PASS with no remaining P0-P2.
- Device-only evidence remains: 120Hz long-stream feel, keyboard/safe-area animation, complex Markdown late layout, nested horizontal code/table gestures, and long real-provider output.
