# E7 pure-domain result

Implemented the frozen E7 domain contract; no platform/UI/index/build/device files changed.

## Public interfaces and behavior

- `chat/memory_models.ts:20/73/93`: `MemoryKind` adds `topic`; required `topicTitle:string|null`, `memberIds:number[]`; maker defaults old records to null/[].
- `chat/memory_topics.ts:4/10/18`: exact `MemoryTopicSuggestion`, `MemoryTopicApplyResult`, `MemoryTopicPruneResult` DTOs.
- `memory_topics.ts:41/61`: `buildMemoryTopicPrompt(records,now)` uses only saved active non-core non-topic non-expired sources, 120 Unicode-character previews, existing active topic titles/member IDs; `decodeMemoryTopicSuggestions(raw)` checks the real `{topics:[...]}` JSON format, trimmed 1–12 Unicode-character titles, nonempty summaries, valid positive Room integer IDs, ≥2 distinct members, normalized duplicate titles and cross-topic assignment.
- `memory_topics.ts:130`: `applyMemoryTopicSuggestions(live,baseline,suggestions,now)` verifies current source identity/content/updatedAt/classification/archive/expiry against baseline. `staleCount` counts rejected suggestions, not member IDs. Case/whitespace-equivalent titles keep the same topic ID. Eligible historical members union/distinct with the suggestion; another active topic's members cannot be reassigned. Same summary/members is idempotent. Source records are not edited, merged or archived by grouping.
- `memory_topics.ts:23/101/107`: additional narrow helpers `isMemoryTopicSource(record,now)`, `invalidateMemoryTopicsForSource(records,sourceId,now)`; `pruneMemoryTopics(records,now)` returns original array on no change.
- `chat/memory_documents.ts:4/12/37`: exact projection/derived-document DTOs and `renderMemoryDocuments(records,now)`. Only `index.md`, `topics/<numeric-id>-<controlled-slug>.md`; full real source bodies, scope/kind/dates/actual conversation+message IDs, real coverage and ungrouped sources. Empty eligible source library returns []. No filesystem or second source of truth.

## Exact invalidation policy

Dependent active topics are archived when source content, kind, scope, archived status or expiry changes through ordinary writes, or the source is removed. The title and ID are retained; member IDs are retained on content edits and pruned on source removal/unavailability. Any membership loss invalidates the summary even when ≥2 members remain. A new model result with the same normalized title can revive that stable topic ID. Old generated summary documents disappear and remaining sources return to the ungrouped index until refresh.

Pin and recall `lastUsedAt` metadata changes do not invalidate summaries. `pruneMemoryTopics` has no timestamp-only invalidation; model apply still strictly rejects a baseline/live `updatedAt` mismatch as the contract requires. This separates already-generated factual summary validity from a late model write's snapshot check without new stale flags or ledger.

## Source-only existing boundaries

- `memory_write.ts:75/104/118/151`: ordinary append/content edit/upsert/delete refuse topics; upsert also refuses a source-shaped overwrite of an existing topic ID. New fields are preserved/defaulted in explicit mapping. Source writes invalidate dependents; removal/upsert also prune. `deleteMemoryRecord(records,id,now?)` is backwards compatible; B's explicit guarded `deleteTopic` must not use this ordinary path.
- `memory_write.ts:141`, `memory_summary.ts:20`: ordinary bucket tools and summary groups exclude derived topics.
- `memory_frontmatter.ts:45/126`: export/import refuses topic kind; old source decode gets null/[] fields. Require actual leading frontmatter so a derived Markdown document with embedded source frontmatter cannot be decoded as a source note.
- `memory_import_export.ts:118/160`: export/filter counts only source records; import's existing-record lookup excludes topics. The existing source frontmatter format/ID mapping remains intact.
- `memory_extractor.ts:134`: requesting `kind:'topic'` fails extraction instead of silently converting it to note.
- `memory_dream.ts:122/226/417/433/533`, `memory_dream_prompt.ts:8`: exclude topics from local/model plans, merge/archive/promote/supersede application and candidate ignore operations. Existing Dream approval semantics stay intact.

## Necessary focused evidence

- ONE group: `harmony/chat/src/test/memory_topics.test.ts` — saved sources → JSON grouping → persisted topic → idempotent repeat → real provenance documents → source edit/late-result rejection/stable-ID refresh → conflict/expiry/removal invalidation → source-only export/import roundtrip. Final **1/1 PASS**, ~121 ms; `/private/tmp/E7-domain-focused.log`.
- The same group reproduced a concrete derived-document import bug: `Missing expected exception` before the leading-frontmatter check; `/private/tmp/E7-domain-derived-red.log`. Only that group was rerun after the local fix. No existing memory suites were run.
- `harmony/chat/node_modules/.bin/tsc --project harmony/chat/tsconfig.json --noEmit --pretty false`: final **0 diagnostics**, `/private/tmp/E7-domain-types.log`. One old explicit source fixture (`src/test/memory_frontmatter.test.ts`) needed the two required null/[] fields; its suite was not executed.
- Initial targeted lint of the 10 changed product TS files: **0 errors / 601 warnings**, `/private/tmp/E7-domain-lint.log`. After the one-line decoder fix, only that affected file was checked: **0 errors / 35 warnings**, `/private/tmp/E7-domain-frontmatter-lint.log`. Warnings include the existing SDK linter's unresolved pure-domain import types; TypeScript checks are separate evidence.
- Owned diff whitespace check passed. New topic module 202 lines; document module 66 lines. No full test suite, provider/store/device check, SDK build or signing claim.

B/C received actual interfaces before finishing their consumers. Root owns exports/DI/routes/lifecycle and independent phase review. Actual source JSON hydration, queued commit gates, file IO/mtime, model calls and page/device behavior are B/C/root acceptance layers.
