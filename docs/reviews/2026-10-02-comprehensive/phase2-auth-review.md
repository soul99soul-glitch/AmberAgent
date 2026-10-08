# Phase 2 Provider OAuth — independent review

Reviewer did not implement this phase. Read-only source review against `/tmp/amber-harmony-review-20261002-baseline` and `phase2-provider-auth.md`; no production/test source edits.

## Decision

**No confirmed new issue or blocker in the reviewed phase.** Changes address stale auxiliary OAuth bearer and missing Codex accountId while preserving existing task parameter boundaries and provider/model resolution.

## Reviewed evidence

- `provider_runtime.ts`: auth extraction preserves the original OAuth branches, null/blank token rejection, provider copy, Codex-only accountId, and API-key/token-plan behavior. Main runtime still snapshots model/assistant/defaults/custom params before its first asynchronous refresh.
- `AppContainer.ets`: `prepareProviderApi` copies the supplied task headers before awaiting auth; only auth snapshot is passed to the existing protocol factory. No dummy Assistant is introduced. Custom-header precedence remains existing behavior.
- All live `buildProviderApi` call sites were searched. Remaining calls are the authenticated subagent cache consumer, the new auth helper, and ChatPage manual title explicitly assigned to Phase 3. No unassigned bypass identified.
- Subagent provider/account cache remains local to each `buildSubAgentGenerationPort` invocation. `resolveModel` retains existing explicit override / Jev route / current-model fallback resolution and asynchronously refreshes the chosen provider; `makeProvider` preserves its supplied generation params and headers, adding cached accountId. No global auth cache/model route added.
- Automatic compression retains a synchronous provider factory; its stream method refreshes then passes exact messages, task params, callback, and opts. The real domain compaction passes the owning signal for foreground work, and creates `backgroundDeps` with no parent signal for independent precompaction.
- Manual compression, auxiliary title/suggestions/memory extraction, Daydream, topic grouping, and OCR retain their prior model/custom body/reasoning/header choices. OCR and visual-health probes remain model-only (no assistant or model custom headers/body). Topic's post-refresh aborted signal is propagated to RcpHttpClient, which checks already-aborted signals before starting transport; no post-cancel HTTP issue established.

## Independent verification

Reran phase-focused production-source tests:

```sh
TSX_TSCONFIG_PATH=harmony/chat/tsconfig.json node --import ./harmony/chat/node_modules/tsx/dist/loader.mjs --test harmony/chat/src/test/provider_auxiliary_auth_entry.test.cjs harmony/chat/src/test/provider_runtime.test.ts harmony/chat/src/test/deepread_runtime_entry.test.cjs
```

**39/39 passed**, `/tmp/phase2-auth-independent-review.log`.

Added disposable adversarial probes outside the repository using the same actual Entry factory evaluator:

- Null OAuth refresh for all 7 auxiliary task paths, both Codex and Grok: no HTTP request and no stale bearer fallback.
- Exact foreground compaction signal reaches the actual protocol HTTP port.
- Two separately created subagent ports with identical model IDs retain distinct refreshed bearer/accountId snapshots; later resolution in port 2 does not overwrite port 1.

Original 23 Entry tests plus 16 independent probes: **39/39 passed**, `/tmp/phase2-auth-independent-probes.cjs` and `/tmp/phase2-auth-independent-probes.log`.

These are controlled-port source/HTTP fixture results, not real OAuth endpoint or device acceptance. Root owns integrated lint/build/signing/device verification. Phase 3 manual-title wiring is intentionally pending and is not recorded as a Phase 2 omission.
