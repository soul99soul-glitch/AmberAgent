# Phase 5 C independent review

Scope: Jev settings/purpose evaluator, completion facts, actual ConversationRunService lifecycle, actual ChatPage verification callback, and notice component. Review is read-only for product source; host probes are in `/tmp`. Device operations and visual acceptance belong to the root/UI operator.

## Findings

1. **P2 — conditional shell commands incorrectly counted as successful verification (resolved).** A completed exit-zero `true || npm test` never executes the test; `npm test || true` can hide failure. The original segment matcher suppressed the notice for both. `/tmp/amber-phase5-review-controlflow.mts` reproduced this with the real domain code. The implementation now requires a provable final simple command or successful `&&` chain, rejecting pipes/background/`||` ambiguity. Added domain regressions also cover `npm test; true` and a real final checker after an echo. Focused tests pass.

2. **P2 — deadline did not bound asynchronous preflight/budget/settings reads (resolved).** The initial timer only raced HTTP. The evaluator now races the entire operation against one 1200ms deadline, relays cancellation, removes timer/parent listener, and prevents resumed timed-out reads from starting HTTP. Actual Entry runtime tests pass, including a stalled first settings read, an HTTP adapter ignoring abort, parent cancellation, late consent revocation, and concurrent reservation of attempt 200.

3. **P2 — earlier branch change leaves the old completion notice eligible (resolved).** `selectMessageBranch` retains the tail. Switching a user-node branch can change task text while keeping final message ID/parts and changed file paths identical. The original notice and pending-evaluation binding omitted that branch/task identity. `/tmp/amber-phase5-review-branch.cjs` uses the real Entry service, normal send completion, `mutateStoredConversation`, and `selectMessageBranch`; it produced `noticeAfterBranch:true` after the current task changed. The final implementation binds selected-message IDs/roles/part fingerprints in `sourceFingerprint`, stores it in the session and volatile notice, aborts pending evaluation on a source change, and verifies it again at publication and button activation. The same host reproduction now returns `noticeAfterBranch:false`. Actual service regressions cover both pending and already-visible notices with the same final tail, and a genuinely pending previous-run result crossing a new run.

## Verified behavior

- All four new purposes default off; all six purpose settings survive load/save and all global settings setters. `/tmp/amber-phase5-review-setting-setters.cjs` executes the actual extracted methods and verifies mode, API mode, text edits, and one-purpose edits preserve the other purpose objects.
- Global off yields zero HTTP; global shadow caps active purpose behavior. Required core consent is independent: output for context purposes, metadata for auto approval, task text for completion. Optional fields are omitted independently.
- Daily attempts are reserved serially before HTTP. HTTP runs outside the reservation queue; no budget overshoot at attempt 200 was observed by the actual Entry runtime test.
- Completion consumes successful real `file_write` path/size output, positive `file_edit.replace_count` with changed old/new text, and non-directory `file_move` output. Document extensions are excluded. Only a completed/non-running/exit-zero recognized terminal check after the last change clears facts.
- Completion task permission covers limited user task text and final assistant reply. File paths require separate metadata permission; tool output bodies are not sent by completion. Final evaluator also compares the payload-builder settings snapshot before dispatch: optional metadata consent revoked between batch construction and evaluation causes zero HTTP. This final A-review fix was independently rerun in the actual Entry evaluator suite.
- Notice creation writes no conversation history. New run, deletion/release, and cancellation paths clear/abort the volatile notice operation. Candidate publication checks live session/controller/run/phase/tail/selected-source identity.
- Actual ChatPage callback invokes the original `send()` only for an empty attachment-free composer outside edit mode. Existing drafts, attachments, and edit identity are preserved and require manual send. The prompt matches the domain constant exactly.
- Notice controls have at least 44x44 bounds in source, text has 12/4 horizontal and 4 vertical padding, and the component uses existing theme tokens/V1 state. No product fixture/debug route was added.

## Verification evidence

Command from `harmony/chat`:

```sh
./node_modules/.bin/tsx --test src/test/jev_purposes.test.ts src/test/jev_completion.test.ts src/test/jev_runtime_purposes.test.cjs src/test/jev_completion_composer.test.cjs src/test/conversation_run_service.test.cjs
```

Final independent focused run: **31/31 PASS**. This includes all three fixes, actual Entry evaluator/service/composer callbacks, pending/visible branch invalidation, and a truly pending result crossing a new run.

Additional `/tmp/amber-phase5-review-lifecycle.cjs` executes the real Entry service for **8/8 passing scenarios**: tail mutation, cancellation, deletion, and idle release, each with a pending and already-visible notice. Cancellation/source changes abort pending assessment, late candidates do not create notices, and assessment completion does not add history saves. The normal tail mutation alone adds its expected save.

All three reported findings are resolved; no remaining source blocker was found in this review.

Build, lint, signing, install, and visual acceptance are not claimed by this read-only review. The root/UI operator must attach their actual evidence separately. Offline host fixture helpers remain in `/tmp`; this reviewer did not start the server or operate the device.
