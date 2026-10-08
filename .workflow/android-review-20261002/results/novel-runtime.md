# Novel runtime/UI review

Scope: current production workspace runtime, turn adapter/output capture, ghostwrite coordinator/controller/worker, author/approval owners, and Novel Compose/VM paths; HEAD `6a66f7a` plus current WIP/untracked. Read only production code; no Gradle run or production edits. Spec: `docs/plans/2026-09-30-android-novel-product-completion.md`. Storage module ownership is a separate review.

## Confirmed findings

### NR01 — P1 / Correctness: new free file written twice survives failed/cancelled turn

- Location: `app/src/main/java/app/amber/feature/novel/workspace/NovelWorkspaceTools.kt:398–400` (`rememberPrevious`). Real call site: interactive free write at `:287–290`; rollback in `NovelWorkspaceRuntime.kt:455–461`.
- Trigger: a model creates `setting/characters/new.md` or `drafts/new.md`, then revises that same path within the turn, then fails or is stopped. First remember records `null` (file did not exist); Kotlin/Java `putIfAbsent` considers a mapped null absent, so the second remember replaces null with the first draft. Rollback restores the first draft instead of deleting the newly created file.
- Impact: uncommitted setting/draft remains visible and injected after a failed/stopped turn; next successful unrelated commit can absorb it. This contradicts the explicit runtime rollback invariant and is not intentional partial author-output retention (that lives separately in hidden turn outputs).
- Minimal fix: record the first preimage based on `containsKey`, including null. No extra transaction/retry layer.
- Test: real Runtime + fake kernel invokes free write tool twice for the same missing path; suspend and cancel / throw; verify missing file after rollback, unchanged ledger, captured author partial text still recoverable. Also directly assert `rememberPrevious(path, null); rememberPrevious(path, "first")` retains null.

### NR02 — P2 / Spec + Correctness: interactive discussion does not send its previous dialogue

- Location: `app/src/main/java/app/amber/feature/novel/workspace/NovelWorkspaceRuntime.kt:215–227`. VM `send` saves user input (`:547–548`) and assistant reply (`:629–640`) but only passes `userText` into `TurnRequest` (`:579–603`). `TurnRequest` has no history field.
- Trigger: a nonblank existing project, discussion round 1 asks for two possible plot directions and assistant answers without writing files; round 2 says “选你刚才的第二个方案，继续细化”. Provider request consists only of current brief/system and this follow-up, with none of round 1.
- Impact: visible conversation implies ongoing co-creation but the model has no preceding user intent or its own choices, so follow-up references cannot be resolved. The five tools cannot recover `.amber/sessions.json`: `NovelWorkspaceStore.list` excludes dot paths and `NovelWorkspacePaths.validate` forbids hidden host paths.
- By-design recheck: `NovelWorkspaceSessions.kt` calls bubbles “Android-host-local; not part of cross-platform wire contract” and “never in the book”, specifying storage/exchange placement, not stateless model behavior. `NovelWorkspacePrompts.discussion` says discuss with author and new settings *may* be saved; it does not force every provisional answer into material files. Current context assembler consumes materials/plot/plan, not sessions. No local explicit stateless-dialogue requirement found. Raw scene/whole-book review/background inputs should stay specialized and not receive chat history.
- Minimal fix: add explicit optional history input and populate only interactive `send` from that branch's relevant user/assistant discussion history, before this accepted message. Avoid duplicate current user input; keep specialized reviews/background default empty. Respect the existing context-budget pathway rather than invent a summarizer.
- Required acceptance probe: production VM → NovelTurnLauncher/Agent → Runtime → kernel/fake gateway, capture two requests. Assert request 2 contains previous user + reply + one current user, other branch messages absent; specialized read-only/review requests remain isolated. This review did not execute the probe because Gradle is exclusively owned by root.

### NR03 — P2 / Spec + Correctness: chapter-edit save is the remaining stale-restore write path

- Location: `app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspaceViewModel.kt:1139–1180`.
- Trigger: an author chapter save is accepted in old state, and a restore completes before its scheduled coroutine/transaction enters runtime. This path captures directory/branch/body but does not capture restore epoch. Runtime `saveChapterEdit` calls `NovelWorkspaceRestoreBoundary.write` with no expected epoch, so a same-ID/same-path restored chapter is overwritten by pre-restore editor text. Restore only cancels `turnJob`, `reloadJob` and `ghostwriteRefreshJob`, not this anonymous author coroutine. Main.immediate commonly starts it inline, reducing the window, but the API is also callable from a scheduled callback and restore happens on another dispatcher; the equivalent runAuthorEdit explicitly protects this same boundary.
- Impact: imported manuscript data can be replaced by stale editor data and committed; unconditional `finally busy=false` can also erase current restore busy state.
- Minimal fix: route this save through existing `runAuthorEdit`, which already freezes epoch, synchronizes write, refreshes snapshot, and conditionally publishes callbacks/busy. Avoid new lifecycle owner.
- Test: paused main dispatcher, call save; begin + finish restore with same project/branch/path containing imported text; release scheduled callback; assert imported content/head untouched and no saved callback. Mirror existing queued-author restore tests. This finding requires that scheduled test before integration, to verify the race rather than infer from reduced Main.immediate production window.

### NR04 — P2 / Correctness: interactive session persistence failures escape viewModelScope

- Location: `app/src/main/java/app/amber/feature/ui/pages/novel/NovelMarkdownWorkspaceViewModel.kt:533–700`, especially append at `:547–548`, terminal append `:629–640`, and outer try/finally at `:690` with no non-cancellation catch.
- Trigger: session atomic persistence fails (for example ENOSPC while saving a newly accepted user message or completed assistant output), or IO projection fails after a successful provider response. This IO lives in the collector/viewmodel, outside Runtime/Agent's Failed-event catch.
- Impact: the exception leaves a root `viewModelScope.launch`; default Android coroutine handling sends it to uncaught exception handling instead of the existing UI error state. Busy reset runs but generation failure UI is not delivered and app can terminate. Output capture independently preserves provider text when that earlier persistence succeeded; it does not catch the VM collector's save.
- Minimal fix: catch CancellationException unchanged and catch ordinary Exception around send's outer body, display existing localized save/operation error, leave busy cleanup epoch-aware. No retry/fallback swallowing.
- Test: production VM fake kernel waits at response; deterministically force a filesystem/session destination error, then complete. Verify exception is caught, state has error, busy clears, no duplicate accepted message/output on reopen. Prefer an existing injectable IO seam if available; otherwise a deliberate invalid filesystem fixture is adequate to verify catch wiring, while ENOSPC is the production failure example.

## Rechecked exclusions / by design

- Failed/paused ghostwrite batch continuing to own a branch is deliberate; resume/cancel/dismiss flow maintains execution identity and CAS.
- Notification deep-link focus is explicitly view-only; it does not silently switch durable active branch or start a worker.
- History is single-level Undo plus retained blob versions; no general redo tree was specified.
- Whole-book checker intentionally refuses active writer owners and validates evidence against snapshots, retaining partial reports without changing canon.
- Reader chapter/history transitions refresh body on restore; history restore increments reader refresh and exits an old editor, so that initially suspected stale-editor history overwrite is covered.
- Path-local ephemeral UI editors reset on branch/restore scope. No confirmed late-read overwrite found in Reader produceState paths.
- Existing large VM/page files exceed documented usual line limits (2,480 / 2,831 lines), but no precise behavioral defect follows from file length; a wholesale split is not included as a bug fix.
- Manual plan save writes raw body by existing design; plan confirmation normalizes host binding. No metadata-only observation elevated without confirmed user-visible consequence.

## Validation boundary

NR01 and NR02 are fully traceable current production behavior; NR03 is a narrow concurrency correctness gap requiring the explicit queued-write regression before merge. NR04 is a concrete uncaught exception path requiring a persistence-error regression. No external provider, device, Gradle, or sibling-product verification was performed in this review.
