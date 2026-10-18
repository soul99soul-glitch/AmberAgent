# Phase 3 independent functional review

Final verdict: PASS after the reproduced C01 timing gap was repaired and independently rechecked. C01–C03/M01/M02/S01/S02 have no remaining reproduced blocker in the reviewed seams. Initial blocked evidence is retained below. This review changed no production or repository test files.

## Reviewed scope

Read `.agents/skills/harmony-arkts/SKILL.md`. Reviewed C01–C03 against the task-owned patch (which preserves pre-existing R07 work), actual full `context_engine.ts`, `context_compact.ts`, `EntryCompactStore.ets` and actual singleton/caller wiring. A separate independent reviewer checked memory/search against captured baseline for initially dirty files and HEAD for clean files; its detailed evidence is `phase3-memory-search-independent-review.md`.

## Reproduced blocker: revision read can observe a new revision with old source compacts

Owner: `harmony/entry/src/main/ets/platform_impl/EntryCompactStore.ets`, initial lines 76–78 (`getRevision`). Priority P2, same stale carried-fact impact as C01.

`getRevision` awaits the `writeTails` Promise present at call time once, but returns the current revision after waiting. If another operation invalidates history while this await is pending, the new delete is queued after the old tail and the revision is bumped immediately. The original read can resume before that delete finishes, return the new revision, and read the old compacts. An incremental generated summary then carries the old compact's fact while its own source IDs contain only later unchanged messages. Insertion uses the new revision and is accepted after deletion; injection considers the summary's own source IDs valid on the edited branch and emits the old fact.

Actual complete Entry source plus actual domain probe: `phase3-context-revision-overlap-probe.mts`. It creates a valid prior compact, blocks a prior audit write A, starts real `compactConversation` so `getRevision` waits A, admits invalidation B while A is pending, pauses B's KV delete, then releases A. Provider starts while B is blocked, carrying the prior summary; after B clears, the provider completes and the late summary is inserted and selected for the edited branch. No production/test edits or source replacements are used.

Saved initial output: `phase3-context-revision-overlap-red.log`:

- provider started while invalidation still blocked: true;
- provider carried old summary: true;
- source excludes the edited old-branch fact: true;
- result status `completed`, savedIds/injectedIds `["late"]`, `leakedOldFact: true`;
- assertion `invalidated carried old branch fact must never be injected` fails.

Run from repository root with:

```sh
harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json .workflow/comprehensive-review-round2-20261018/results/phase3-context-revision-overlap-probe.mts
```

A narrow owner fix must make the observed revision and source-clear ordering consistent. Examples are waiting until the same-conversation tail remains stable, or capturing the revision before the initial await so a later invalidation makes publication reject. No global/provider lock, retry or new persistence schema is required. Add this exact await-before-invalidation ordering to the adapter regression; the current test starts revision reading only after invalidation was already admitted and therefore does not cover it.

## Passed review and reused evidence

- C01 provider-in-progress invalidation, queued insertion invalidation and publication-in-progress invalidation reject under the existing new tests. The additional read/invalidation overlap above remains a blocker.
- C02 same-store same-conversation queue includes planning through publication. Forced preparation waits for a pending precompact, replans from committed ranges and injects one current handoff. An ordinary send below force threshold stays nonblocking, and different conversations/store instances run independently. Planning now excludes source-invalid compacts with the same predicate used by injection.
- C03 template replacement scans only the original template. Literal `{locale}`, `{content}`, `{target_tokens}`, `{additional_context}` and `$&` inside inserted content remain intact. Actual call sites supply unique substitution names.
- M01/M02/S01/S02 independently pass; details, by-design checks and baseline mapping are in `phase3-memory-search-independent-review.md`. Atomic Entry reservation persists quota while provider calls remain parallel; only final injected memory IDs are touched; direct result parameters and owned redirects both work; IPv6 ports distinguish services.

Independent focused validation: `phase3-context-independent-tests.log` has 12 pass / 0 fail (domain race and actual Entry adapter suites). Independent memory/search validation has 63 pass / 0 fail. These green tests do not override the separate new failing composed probe.

## Limits

This reviewer did not run SDK lint/build/signing/install/emulator/physical-device gates. Actual Entry adapters run via transpilation with controlled KV/filesystem methods; domain functions execute directly. Fixtures do not establish live Bing/DDG HTML shape, paid-provider billing or physical-device acceptance. No generic fallback, retry or unrelated redesign is requested.

## Follow-up

The context owner replaced the one-time tail await with a stable-tail loop. After awaiting a tail, `getRevision` checks whether another tail was admitted while waiting and continues until the queue is drained or the same completed tail is still current. Only then does it return the revision. If invalidation starts after this synchronous return, the returned older revision makes the existing insertion guard reject it. State remains per store and conversation; no provider/global serialization was added.

I independently reran the original composed actual-Entry/domain probe against the repair. `phase3-context-revision-overlap-green.log` exits 0 and shows:

- provider started while invalidation delete B was blocked: false;
- provider carried the old summary: false;
- regenerated source covers the original old message itself, so branch validation excludes it after the edit;
- injectedIds `[]`, `leakedOldFact: false`.

I also independently reran only the new overlap regression and its two directly related controls (fresh-revision waiting and invalidation during publication): **3 pass / 0 fail** in `phase3-context-overlap-independent-tests.log`. The regression uses the actual full transpiled adapter plus real domain planning/generation/selection, preserving the failed ordering rather than replacing it with a simpler post-invalidation read. Scoped `git diff --check` passes. The other 12 context and 63 memory/search results above are reused; no full suite was repeated without a new reason.

The original blocker is closed. No remaining reproduced issue or speculative redesign is requested for phase 3.
