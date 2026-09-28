# E6 gate — per-conversation generation host

2026-09-28. **Source A/B and necessary local checks PASS; C device/install/visual pending.** This is a source checkpoint, not proof of finished device acceptance. E7 independent work continues.

## Result

ConversationRunService owns per-ID admission, abort/runId, real full conversation hydration, provider seed, kernel/engine, local activity/catalog, pending queue and one actual write tail. Page sends commands and subscribes; hiding/disappearing leaves the run intact. The shared generating flag and keepalive release use all active conversations. Frozen parent runtime/assistant/MCP identities reach child runs. Existing engines and tool loops were reused.

Actual canonical checkpoints and stable visualConversation are separate: user regeneration truncates its display base; assistant regeneration/resume replace the original display row. Returning to a released idle host reads current persisted content; stale reads cannot replace a newly active host. New blank seeds survive hidden pages and are discarded only on actual destruction. Title/pin/message mutations merge latest rows in the same per-ID write tail. Successful guarded deletion clears queue/canonical and cancels only paused native JS sessions with that actual conversation owner.

Browser primitives retain the actual page-host limitation. A missing/lost page rejects its tool chain; an operation interrupted by page loss cannot report known success. Plugin scope checks ownership before any shared URL-gate/read/reset and again after the await. No second WebView scheduler or stable background-browser claim was introduced.

## Independent reviews

- `E6-logic-review.md`: actual logic/call-chain A PASS, concrete pin/title/cold-return/delete-retention findings fixed and source rechecked.
- `E6-ui-source-review.md`: four concrete visual/callback/scope/preparing-stop findings fixed and source rechecked. No confirmed remaining P1/P2 from the affected review. Device C explicitly pending.
- `E6-js-delete-review.md`: narrow native-session owner cleanup and successful-delete consumers source closed. Existing native cancel entry unchanged; no native/device claim.
- `E6-list-integration.md`: exact List/assistant cascade and running-ID UI consumer evidence.

## Necessary checks only

- `E6-focused-checks.md`: actual Service/Entry controller/restore guard/kernel/chat engine/queue with controlled provider/repository ports. One A/B group initially PASS; only pin field-merge change justified the affected group rerun, PASS. A newly reproduced user regeneration projection counterexample was red then green; only its name-matched case ran, not the original group again. Fixture has two tests (225 lines), not a broad suite.
- `E6-browser-owner-check.md`: one /tmp controlled actual BrowserGuard/PluginWebMountGate group PASS, including both settle paths after A loses page ownership. No network/JSVM/device repetition.
- Full chat strict TypeScript `node node_modules/typescript/bin/tsc --noEmit`: exit0, `/tmp/e6-chat-types.log`. No chat full test suite.
- Final root changed-file SDK CodeLinter: exit0, **0 errors / 489 warnings**, `/tmp/e6-root-lint.log`; owner dependency/tools/auxiliary lint completed separately, final plugin scope pair 0 error/0 warn. New later DTO/owner changes were included in the actual app compiler.
- Actual first compile surfaced inline command object-union and one wrong type import; next compile surfaced two actual page object spreads. Only those compiler errors were repaired. Integration build then PASS (14.011s), followed by frozen final-source Release build **PASS (8.445s)**: `/tmp/e6-release-source-final-build.log`.
- Final local signing and digest verify **PASS**, exit0, Digest verify true / verify-app success: `/tmp/e6-release-source-final-sign.log`. HAP is a local Test-CA artifact, not AGC/device installation proof. Existing symbol-archive symlink warnings do not make a device acceptance claim.

The NativePluginJsTransport cancellation import was added after the focused projection execution. Fixture's controlled import table was updated for that port; no unnecessary host group rerun was made. Per-owner cancellation is source-reviewed and actual-app-compiled, not separately fixture/device-executed.

## Remaining C

Latest recorded HDC inventory was Empty; no repeated recovery or emulator matrix. Necessary A→B→A navigation/parallel cancel, bright/dark new running indicators and actual WebView/JS host behavior, final AGC-signed install/launch remain pending. Offline status does not convert those gates into PASS. Existing E3 final Mosh, E4 Recipes and E5 real JSVM/UI device gates are retained for E14 integration.
