# Runtime / notification / task / council / CI review

Read-only review of current Android worktree. No Gradle execution or production edits in this task. Checked current callers and persistence paths; no reads from other product repositories.

## Confirmed findings

### RCI-01 — P1: Notification inline replies use an immutable PendingIntent and lose the answer

- Location: `app/src/main/java/app/amber/feature/runtime/AgentLiveStatusNotifier.kt`, `replyAction` (currently 679–684) and `broadcast` (currently 719–726).
- Trigger: pending `ask_user` → user types a notification reply or selects a quick answer.
- Production chain: `replyAction` installs `RemoteInput` → common `broadcast()` creates `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT` PendingIntent → system cannot inject reply results → `AgentNotificationActionReceiver.replyAskUser` reads no result and returns at line 109 before an error notification is shown.
- Second check: NotificationUtil builds the real Action with `.addRemoteInput(action.remoteInput)`; there is no alternate mutable PendingIntent path. Manifest receiver is explicit/non-exported. [Official PendingIntent API](https://developer.android.com/reference/android/app/PendingIntent#FLAG_MUTABLE) explicitly requires mutability for inline replies and says immutable intents ignore fill-in properties. This is a functional defect, not a security choice that preserves the requested feature.
- Minimal fix: make only `ACTION_REPLY_ASK_USER`'s explicit PendingIntent mutable; keep the approval token binding and keep other actions immutable. Focused Robolectric/Android test should send injected RemoteInput through the generated PendingIntent and assert the receiver sees the answer (also assert other actions remain immutable).

### RCI-02 — P1: Notification approve/deny/reply holds the broadcast open through an entire resumed model turn

- Location: `app/src/main/java/app/amber/agent/feature/runtime/AgentNotificationActionReceiver.kt:78–95,110–128`; `app/src/main/java/app/amber/core/service/ChatService.kt:2288–2299,2364–2370`.
- Trigger: user resolves the last pending tool using a notification, then resumed generation takes longer than Android's broadcast budget.
- Production chain: receiver `goAsync` → awaits `handleNotificationApproval` → awaits `applyToolApprovalDecision` → awaits `continueGenerationInline` → `runKernelDispatchLoop` waits for the launched agent to reach pause/terminal (ChatService around 1350). `PendingResult.finish()` is only called after that whole turn ends. A normal slow API call/tool can therefore keep the broadcast active for minutes.
- Second check: `continueGenerationInline` is genuinely suspending to completion; it does not just enqueue. The in-app `handleToolApproval` launches its own session job and has no broadcast deadline. `retryFromNotification` calls `regenerateAtMessage`, which enqueues a job and returns, so retry is not included in this finding. [Official BroadcastReceiver.goAsync API](https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync()) says the execution limit continues until `PendingResult.finish()`, generally 10 seconds and longer for non-foreground delivery. Off-main execution does not remove the budget.
- Minimal fix: separate approval acceptance/persistence from generation execution. After the decision is persisted, launch the resumed turn in the normal AppScope/session job and return acceptance to the receiver. Do not simply call finish before accepting/persisting or add a timeout that cancels the model turn. Focused test with a deliberately suspended resumed generation must show approval acceptance and broadcast completion while the independent session job stays active and Stop cancels it.

### RCI-03 — P1: Raw running terminal command leaks into public lock-screen notification / Xiaomi island

- Location: `app/src/main/java/app/amber/feature/runtime/AgentLiveStatusNotifier.kt`, `runningStatus` (`publicTitle = title`), `toolTitle`, `terminalTitle` (currently 489–504), `publicCard` and `islandConfig`.
- Trigger: normal configuration `hideSensitive=false`; active terminal tool whose command is not an installation shortcut, e.g. `curl -H 'Authorization: ...' ...`.
- Production chain: `toolTitle(activity, false)` → `terminalTitle` embeds the command into title → `runningStatus` copies it to `publicTitle` → `publicCard` sets `VISIBILITY_PUBLIC` and `title=status.publicTitle`; Xiaomi island uses the same publicTitle. The command can include tokens, paths, text, or private search content.
- Second check: approvalStatus and askUserStatus already produce deliberately redacted public versions; class KDoc explicitly says original commands/questions appear only after unlock. NotificationConfig defaults VISIBILITY_PRIVATE, but its publicVersion is explicitly PUBLIC and contains the leak, so that default does not protect this path.
- Minimal fix: provide a separately redacted public running-tool title (e.g. `toolTitle(activity, hideSensitive=true)`), while preserving the configured unlocked title. Focused notification/status test must assert raw command remains in private title when allowed but appears in neither publicVersion nor island payload.

### RCI-04 — P2: Legacy model council's delayed progress write can resurrect a terminal task snapshot

- Location: `feature/modelcouncil/src/main/kotlin/app/amber/feature/modelcouncil/ModelCouncilManager.kt:598–614,618–640`; `feature/task/src/main/kotlin/app/amber/feature/task/AgentTaskStore.kt:update`.
- Trigger: final seat finishes close to synthesis completion or user cancellation; IO-dispatched progress and terminal coroutines are scheduled in reverse order.
- Production chain: `appendTurn` checks the in-memory running state under synchronized, then launches an independent IO coroutine that later writes RUNNING/cancelCapability=true; `finish` changes memory to terminal and launches a separate terminal store write. If finish's coroutine writes first, the older appendTurn coroutine writes RUNNING afterward. Store mutex makes writes atomic but does not compare expected status or prevent terminal→RUNNING. Bubble/task consumers read tasksFlow, so they can show a finished council as running until process restart.
- Second check: ModelCouncilTools uses this manager's start/read/wait/cancel (the CouncilRoomManager is a distinct production feature). Manager in-memory finish guard prevents its own status resurrection but not the separate task projection. Store has no terminal monotonicity guard; generic task store must permit real followups/retries, so a global ban on terminal→RUNNING would be the wrong fix.
- Minimal fix: serialize this manager's progress/terminal task publications with its lifecycle transitions, or use an expected-status compare-and-set for progress that rejects stale writes after terminal. Do not add polling/retries. Reproduce with deterministic scheduling: capture queued RUNNING update, write COMPLETED/CANCELLED snapshot first, release delayed progress, assert task remains terminal and cancelCapability=false.

### RCI-05 — P2: Koin graph verifier omits a startup module and fails baseline despite production binding

- Location: `app/src/test/java/app/amber/agent/di/KoinGraphVerifyTest.kt:40–54` (`loadedAtStartup`).
- Evidence: baseline XML `app/build/test-results/testDebugUnitTest/TEST-app.amber.agent.di.KoinGraphVerifyTest.xml`: MissingKoinDefinitionException for LocalTools constructor's JevDecisionCoordinator.
- Second check: actual production `AmberAgentApp.kt:83` includes `jevModule`; `app/src/main/java/app/amber/core/di/JevModule.kt:48` registers JevDecisionCoordinator. The graph test's hardcoded module list excludes jevModule. This is a real verification/baseline defect and a false alarm for production startup DI; no new production binding is needed.
- Minimal fix: import/add jevModule to test's loadedAtStartup list and rerun the focused graph test. Any subsequent missing dependency must be classified against actual factory construction, not blindly whitelisted.

## Examined candidates excluded from findings

- In-memory notification approval tokens expire/process-reset by design; comment and in-app persisted WAITING_USER recovery are explicit. A stale notification rejection is not missing persistence.
- PersistingEventWriter drops nonregistered/broken audit events intentionally by contract; cancellation is rethrown, so no blanket failure-swallowing issue is raised.
- Android permission registry's high-risk unattended switch bypasses normal per-tool approval intentionally; explicit special cases (`ask_user`, website catalog, theme) precede it. No permission-policy redesign proposed.
- `AgentTaskStore.retry` can overwrite adapter-projected state in theory, but no real retry callback is registered by current production callers in inspected scope; excluded as unproven production issue.
- Cron recovery sets generic cron snapshot QUEUED on startup; cron owner reprojects enabled/disabled and last status on startup, so no steady-state bug raised from that branch alone.
- CI builds release, verifies all eight required Rust libraries through assemble dependencies, verifies APK signatures, deletes secret files under always(), then uploads artifacts. No definite defect found in static checks. Action/tool versions or SDK availability were not guessed to be wrong; remote workflow/service execution was not tested.
- Three.js bundle script pins three version, uses a temporary working directory and cleans it, classic script exposes THREE+OrbitControls. Non-pinned esbuild is a reproducibility tradeoff, not an established correctness defect. No remote package availability claim was made.
- Core event store CAS uses a persisted status compare-and-set; runner launch/terminal ownership gates and cold-start gate were inspected. No speculative multi-process fencing demand (explicitly outside current single-process design).

## Suggested phases for this slice

1. Repair Koin verifier baseline, rerun focused test, classify any follow-on failures accurately.
2. Notification delivery/ownership phase: RCI-01 + RCI-02. Verify actual RemoteInput and session Stop ownership; subagent review after phase.
3. Notification privacy phase: RCI-03 with private/public/island regression coverage; subagent review after phase.
4. Council projection ordering phase: RCI-04 with controlled late-progress regression; subagent review after phase.

No broad abstraction, defensive retry layer, global terminal ban, or other repository changes are justified by these findings.
