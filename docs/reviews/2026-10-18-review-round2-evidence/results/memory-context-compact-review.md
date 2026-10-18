# Read-only context-compaction audit

Scope: `context_engine.ts`, `context_compact.ts`, `context_assembly.ts`, `ContextCompactionSupport.ets`; read related callers to prove production reachability. No production/test edits. The pre-existing R07/R08 repairs were excluded.

Run evidence: `harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json .workflow/comprehensive-review-round2-20261018/results/memory-context-compact-probe.ts` (exit 0, all assertions passed).

## Confirmed C01: P2 — A background incremental compact can resurrect facts from a branch the user has already invalidated

- Primary: `harmony/chat/src/main/ets/chat/context_engine.ts:559`, unconditional store insertion after the provider await at line 526. `previousCompacts` is captured at lines 503–509; only the current incremental range is written to `sourceMessageIds` at line 552.
- Trigger: C0 summarizes m0..m5. C1 starts summarizing m6..m9 and carries C0's handoff. The first generation finishes while C1 is still running. User switches an old source node (m0) to another branch, which clears the compact store. C1 then completes and inserts its old handoff.
- Production reachability: `ConversationRunService.ets:324–326` considers runs/append/drain busy, but background compaction is separate; `ChatPage.ets:2098–2105` permits branch switch after generation and explicitly invalidates compacts. `EntryCompactStore.ets:75–93` serializes individual insert/delete operations but has no invalidation generation; a delete preceding the eventual provider completion does not prevent the late insertion.
- Impact: `validCompletedCompacts` (`context_compact.ts:622–628`) sees the unchanged m6..m9 and accepts C1, although its carried C0 was invalidated. The next model gets both the new selected m0 fact and the previous branch's contradictory fact in C1.
- Actual probe: first output has `sources=[m6,m7,m8,m9]`, `ownSourceNeutralOnly=true`, `oldFactOnlyFromCarriedCompact=true`, `staleFactInjected=true`, `editedFactAlsoPresent=true`. Only m0 originally contains old-value; m1..m11 are neutral, and the probe asserts the C1 compression source contains neither old-value nor new-value. The stale fact is carried exclusively from C0. This uses real `compactConversation`, `selectMessageBranch`, `invalidateCompacts`, and `prepareMessagesWithCompacts`.
- Small repair direction: invalidate in-flight compaction commits for that conversation whenever history invalidation occurs. Put the version/token check at the store-owned atomic insertion boundary, or serialize history mutation against a retained compaction promise. Do not block normal sends merely to solve invalidation. Include a regression with a delayed provider and a switch of a carried compact's source node.
- Related gap: when the switched message is in the new compact's own source range, injection correctly rejects it, but `planCompaction` (`context_compact.ts:896–905`) still counts all completed sourceEndIndex values and returns `already_compacted`. Probe output: `injectionCount=0`, `planReason=already_compacted`. Planner should use the same valid completed set as injection. This is a consequence to address with C01, not a separate concurrency finding.

## Confirmed C02: P2 — Precompact and a later force compaction overlap and both summaries are injected

- Primary: `context_engine.ts:697–721`: `precompactInFlight` is consulted only for background precompact; the force branch bypasses it. Both `compactConversation` calls capture the same empty/old store before their provider awaits.
- Trigger: fixed model context window 11,500; 28 messages of 300 Han characters trigger a background precompact of m0..m3. Keep the provider pending, then append six messages. The next request crosses the force threshold and summarizes m0..m9 before the first compact has committed.
- Actual probe: `providerCalls=2`, saved ranges `[m0..m9]` and `[m0..m3]`, `injectedSummaries=2`. Both source sets are valid. Neither payload lists the other as covered because neither existed when its prompt was built.
- Impact: `selectCompactsForInjection` (`context_compact.ts:665–672`) preserves both unrelated records and sends duplicate historical summaries. This consumes budget and can convey two incompatible summaries of the same history; all compact summaries are system messages, which `fitMessagesToTokenBudget` retains unconditionally.
- Small repair direction: serialize compaction planning/generation/commit per conversation, including force and background paths. A later force request should await/observe the in-flight result and re-plan from the resulting store. If using source overlap rejection instead, preserve newly covered suffixes; do not blindly drop the larger compact or keep duplicates. Test delayed precompact + later force at one unchanged model window.

## Confirmed C03: P2, narrow trigger — Compression template substitution rewrites literal placeholders in conversation content

- Primary: `context_engine.ts:69–73`, used by `buildCompressionPrompt` with content inserted before the other placeholders at lines 119–125.
- Trigger: a compressed message literally contains `{locale}`, `{target_tokens}`, or `{additional_context}` (e.g. the user pasted a prompt template or asks to edit one).
- Actual probe: source `literal: {locale}; {target_tokens}; {additional_context}` becomes `literal: Chinese; 2000; Return valid JSON only. Required schema: ...` inside the `<conversation>` body. The entire structural instructions block is duplicated in place of the last placeholder.
- Impact: the model summarizes altered source and may preserve wrong literal commands/templates; `{additional_context}` also unexpectedly expands a short source fragment by the complete schema and previous compact handoffs.
- Small repair direction: substitute only tokens present in the original template in one pass. Return replacements literally without scanning replacement text. Add a targeted source-preservation regression; no template framework is needed.

## Rejected / by-design candidates

- Tool result call/result protocol splitting: domain tool output is retained in its tool part and request adapters emit its matching call/result together. A suffix cut at message boundaries alone is not proof of an orphan protocol error.
- `keepRecentTurns * 2` is an existing documented count policy. An arbitrary expectation that it must count user turns is insufficient to label it a defect.
- Forced fallback preserves only one oversized final message and retains system messages: known budget policy, not independently called a new bug here. C02 creates extra duplicate system content through a confirmed race.
- Manual compaction is serialized by `ConversationRunService.compact` admission and its write queue; no simultaneous manual/manual bug was found.
- Source editing does not preserve the message ID: `editMessage` appends a new-ID branch. The old single-compact case therefore fails injection validity as intended. C01 specifically requires a carried compact's source branch changing while the new incremental range stays valid.
- Existing R07 image budgeting order is already repaired in `prepareContext`; do not duplicate it as a new finding.
