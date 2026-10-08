# Phase 3 runtime repair

## Implementation ready for root validation

Root confirmed existing notification regressions red (reply mutability/public command leakage) and the Koin baseline failure, then authorized production changes. Implemented RCI-01 through RCI-05:

- Only the explicit inline-reply PendingIntent is mutable; other actions remain immutable and token validation is intact.
- Running activity gets a separately redacted public title, which feeds both actual publicVersion and Xiaomi island; configured unlocked details are preserved.
- ChatService now uses the small production `NotificationApprovalDispatch.kt` helper. The LAZY approval/resume job is installed into the real ConversationSession before starting. Acceptance completes only at the shared decision's save/audit boundary, before inline continuation. It captures the same restore epoch as notification validation. Broadcast receivers require no additional lifetime logic.
- Legacy council progress supplies `expectedStatus=RUNNING`; AgentTaskStore checks it under the existing mutex and rejects mismatches without persistence/publication. No global terminal transition prohibition.
- KoinGraphVerifyTest's startup list now includes the actual `jevModule`; no production DI binding was added.

Additional deterministic tests:

- `NotificationApprovalDispatchTest` (6 tests) uses the actual production helper and real ConversationSession. It controls persistence/continuation suspension, asserts ownership before work starts, acceptance returned while generation runs, Stop cancellation, later-operation replacement, failure before persistence, failure after acceptance, and cancellation before a LAZY job can start. A completion handler settles the last case because an unstarted cancelled coroutine never executes its body/finally.
- `feature/task` AgentTaskStoreTest adds delayed progress after COMPLETED/CANCELLED, asserting unchanged store memory, tasksFlow and disk; an explicit followup test still allows terminal→RUNNING.

`git diff --check` passed. This worker did not run Gradle; root owns builds/tests. Known potential next verification issue: adding jevModule makes factory/default constructor parameters visible to the static verifier (e.g. inline-created JevClient transport and JevDecisionCoordinator usageStore). Classify any actual follow-on failure against the real factory before adding narrowly scoped injected-parameter exceptions; no speculative whitelist was added.

## Existing-API regression tests ready

Added `app/src/test/java/app/amber/feature/runtime/AgentLiveStatusNotifierTest.kt` (4 tests):

- Inline reply uses the actual Action's `RemoteInput` and `PendingIntent.isImmutable`; expected red on current production code.
- Approval actions remain immutable and share a nonempty decision token; expected green.
- `notifyRunning` publishes a real Notification; assertion checks unlocked `Notification.EXTRA_TITLE` retains command detail, real `publicVersion` redacts it, and actual `miui.focus.param` JSON redacts it. Expected red on current production code.
- `hideSensitive=true` removes command detail everywhere; expected green.

The test supplies only Xiaomi capability availability (cached OEM probe result), retaining the actual notifier / NotificationUtil / island JSON serialization and NotificationManager build/publication path. It does not inspect source strings or reconstruct the notification separately.

Existing `KoinGraphVerifyTest` baseline was already red on RCI-05. Its test module-list fix is now applied.

Suggested root command: `:app:testDebugUnitTest --tests app.amber.feature.runtime.AgentLiveStatusNotifierTest --tests app.amber.agent.di.KoinGraphVerifyTest`.

No Gradle command was run by this worker. Production code was only changed after root confirmed the red baseline and authorized Phase 3 implementation.

## Notification acceptance/owner design for implementation

`ConversationSession.setJob` atomically cancels its previous owner. Consequently, registering the receiver coroutine itself as owner and later switching to a new job would cancel acceptance/receiver work. The current code does register that caller, so that exact sequence must be changed.

Use one separate LAZY AppScope job for the approval plus inline continuation; install it through `session.setJob` before `start()`. Wait on a `CompletableDeferred<Boolean>` that the job completes after approval save/audit and before inline generation. `handleNotificationApproval` returns acceptance at that point and receiver finishes its PendingResult. The installed job keeps generation ownership, and same-conversation Stop or a later operation continues to cancel it through the current session mechanism. Return false if processing fails/cancels before the acceptance point; later generation errors stay regular ChatService errors.

This requires a small acceptance callback at the existing shared `applyToolApprovalDecision` persistence-to-resume boundary. It avoids introducing a new service/worker/approval subsystem or cancelling the generation to satisfy a broadcast deadline. The callback should not report true before the durable decision is stored.

A focused seam test should deliberately suspend the continuation, assert acceptance is returned with the installed session job still active, then cancel the session owner and assert the suspended continuation is cancelled. The current ChatService has no existing constructor fixture (many strong dependencies and no mocking library), so no unsafe allocation or fake full ChatService was added just to manufacture this red test.

## Council ordering design for implementation

Add optional `expectedStatus: AgentTaskStatus?` to `AgentTaskStore.update`, check it inside the existing mutex before persistence/publication, and pass `expectedStatus=RUNNING` only from legacy council progress. A final COMPLETED/CANCELLED store snapshot then rejects old RUNNING progress, while normal task followup/retry callers retain their existing transition freedom.

The current manager hardcodes `Dispatchers.IO` and uses two independent fire-and-forget closures. A deterministic existing-API test cannot reorder their critical point without bytecode-specific/private coroutine reflection. Do not add a probabilistic race test or global terminal guard. After the local CAS API is added, exercise true store behavior: register running → write terminal → apply a captured old progress with expected RUNNING → assert no mutation in memory, tasksFlow, and disk; separately assert ordinary explicit followup still permits terminal→RUNNING.

## Remaining validation

Root should run app tests `AgentLiveStatusNotifierTest`, `NotificationApprovalDispatchTest`, `KoinGraphVerifyTest` and `:feature:task:testDebugUnitTest` (AgentTaskStoreTest). Complete required app compile/build as appropriate, then independent subagent Phase 3 review. No Android device/real OEM notification interaction claim is made from JVM tests.

## Koin verifier follow-up

Root's integrated run passed 443 of 444 app tests; the remaining graph failure was ConversationRecapStore.rootDir. Inspected the real ChatModule and all JevModule factories/constructors. Added only per-definition `injectedParameters` for factory-created/default arguments:

- ConversationRecapStore: File built from Context, its own default Json.
- JevClient: inline-created JevTransport, its own default Json.
- JevDecisionCoordinator: inline-created AndroidJevUsageStore as JevUsageStore, default JevMetrics.
- JevRuntime: default background CoroutineScope.
- JevScreenGoalRunner: default main CoroutineDispatcher.

Coordinator's JevClient, runtime coordinator/policy/calibration, and feature wrappers' JevRuntime dependencies are still required from the graph. ConversationRecapGenerator's constructor dependencies all use get() and have real bindings, so no exemption was added. FileJevCalibrationStore is created inside the JevCalibrationStore interface definition; its implementation class is not a separately mapped Koin definition and does not need a pretend graph registration. No global File/transport/scope whitelist and no production binding was added. This worker still did not run Gradle.

Root's subsequent full app run reached NovelWorkspaceRestoreBridge.workspaceRoot. Its NovelModule factory computes the File from `NovelWorkspaceProjectRepository.defaultRoot(androidContext().filesDir)` and supplies a WorkManager cancellation closure; only the restore gate comes from `get()`. Added a per-definition File parameter declaration for this class. The cancellation callback remains covered by the verifier's existing Function0 fixture; the gate remains a graph requirement.

Completed a manual scan of all 14 actual startup modules for factory-computed File roots, callbacks and constructor defaults. The separately mapped File-consuming factories are NovelFileProjectRepository, NovelWorkspaceProjectRepository, NovelWorkspaceRestoreBridge, ReminderStore and ConversationRecapStore; each now has its own precise File declaration. FileJevCalibrationStore remains an interface factory implementation. Callback and caller primitive/collection arguments already use the existing fixture types. No actual missing `get()` binding was found in this follow-up, and no global File/default-parameter exception or production binding was added. `git diff --check` passed; root retains Gradle ownership.
