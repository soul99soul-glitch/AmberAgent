# Phase 4 MiniApp / chat / images — preparation

Status: authorized MC01–08 production fixes implemented. Parent already reproduced previous red assertions (app log `/tmp/amber-review-phase1-green-next-red.log`, ai log `/tmp/amber-review-phases2-4-red-phase1-green.log`); this worker did not run Gradle. Root must now run green verification and independent phase review. ArtifactRepository remains untouched; artifact dedup is fixed only in singleton MiniAppWorkspaceWriter.

## Added regression coverage

- MC01: MiniAppSourceChecksTest — quoted object value/call/array/CSS, comment immediately before closing brace, and hidden unexpected closing braces. Existing source checker fails these inputs deterministically by its cursor advancement.
- MC02: new MiniAppSourceVersionConcurrencyTest (Room/Robolectric) — source save preserves rename/pin/run/summary metadata changed after editor capture; deleted entity cannot resurrect; stale captured HTML version must reject rather than overwrite newer HTML.
- MC03: MiniAppConversationWriterTest — oversized data-image/signed HTTPS explicitly fail; accepted URL preserved exactly. No testing new constants invented before implementation.
- MC05: MiniAppWorkspaceWriterTest — wrapped production ArtifactDAO coordinates first lookup and insert; two concurrent calls using same effect return same artifact ID, created/existing statuses, one row, one reference. It does not wait for two lookup entries, so remains valid once singleton mutex serializes calls.
- MC06: MiniAppConversationWriterTest — hold actual suspend send lambda, write replacement B through real ConversationDraftStore, resume send A, assert B survives.
- MC07: ClaudeProviderMessageTest — remote image encoded as URL source; new GoogleRemoteImageRequestTest exercises public GoogleProvider.complete and stream with injected OkHttp interceptor. Full signed image URL must be fetched once; exact fixture bytes and mime must reach API inlineData; original caller message stays unchanged. Both provider routes still use real request construction/parsing. Interceptor replaces network only.

## Execution design remaining

1. MC01: fix loop cursor advancement in skipped-token branches; preserve basic checker scope (no JS parser).
2. MC02: within one Room transaction re-read current row, reject absent/stale version, copy only HTML/version/hash/updatedAt. Restore reads version/current state within same transaction; captured metadata never overwrites new metadata.
3. MC03: reject oversized opaque attachment URLs at current bridge boundary; never slice URL/base64. Keep scheme policy and attachment count unchanged unless explicit evidence requires otherwise.
4. MC04: runner's gate callback reads current SettingsAggregator.settingsFlow.value rather than factory-captured appSettings. Existing gate accepts live function, so one local callback change is sufficient. A gate-only dynamic callback test would pass before fix and would not reproduce factory wiring; do not substitute it for runner verification.
5. MC05: singleton writer Mutex around effect lookup/create; no ArtifactRepository edits, no per-key lock registry.
6. MC06/08: conditional DAO delete by conversation ID + exact draftId; consume the actual restored/sent identity. Reload durable host draft on chat visibility, then re-check composer state after suspend load. Preserve hand-entered content and avoid late stale load replacing newer content. New draft should replace only empty composer or unmodified prior restored host draft. Track identity locally rather than add generic synchronization abstractions. Existing ChatVM is large and has no existing lightweight construction fixture; add narrowly extracted actual owner behavior tests during execution rather than mock dozens of unrelated services or test source strings.
7. MC07: Claude preserves URL image semantics using URL source. Gemini resolves remote image bytes asynchronously using its already-injected OkHttpClient, before synchronous wire adapter mapping, through existing cancellable await seam. Apply bounded byte read and explicit HTTP/content errors; preserve image metadata and caller-owned messages; include tool-output image path if current encoding actually consumes it. No retries, no provider fallback, no dropping unsupported image parts. Keep adapter protocol-only.

## Boundaries and verification

- Test compilation and RED status remain unverified until parent runs targeted app/ai tasks.
- New tests use existing constructor/API names; no production seam exists solely for tests.
- Root should run focused MiniApp and ai tests, inspect expected failing assertions, then authorize implementation in this phase.
- MC04 and MC08 still need production-wiring/owner integration verification after implementation; preparation does not claim those cases are already covered by green tests.

## Implemented delivery

- MC01 skipped lexical regions now own cursor advancement via `continue`; no parser expansion.
- MC02 transactional re-read, explicit absent/stale-version rejection, current metadata preserved; restore read + write share Room transaction.
- MC03 allowed attachment's full URL is accepted intact or explicitly rejected when above existing 2,000-character limit; no truncation. This deliberately keeps existing bridge size policy.
- MC04 runner gate now built by actual `createRunnerSendGate(SettingsAggregator, ...)` factory reading current high-risk setting per call. Gate is remembered by stable owners, not snapshots. New test in MiniAppSystemCapabilityBridgeTest exercises same production factory and same gate instance across real SettingsAggregator true→false→true updates.
- MC05 singleton writer Mutex encloses effect lookup/create. No keyed lock map, retries or ArtifactRepository modification.
- MC06 DAO clear-if-current checks exact draft ID; writer uses its saved ID. ChatVM consumes only identity of draft actually projected into this composer, captured synchronously at send acceptance before asynchronous delete.
- MC08 new small HostDraftComposer owns refresh-on-visibility, manual-input preservation and conditional consumption; ChatVM actually delegates initializer, visible hook and accepted-send handling. Owner rejects older overlapping refresh result / manual edits during suspended load / reloading already consumed item. New HostDraftComposerTest uses real ChatInputState + Room store with retained-owner return, unchanged prior draft replacement, manual edits, controlled suspended read and delayed CAS delete cases.
- MC07 Claude remote image uses URL source. Gemini public complete and stream async-prepare remote images through injected client/cancellable await before synchronous adapter; downloaded images copy URL to data URI while preserving metadata and original caller messages. 5 MiB cap, explicit HTTP/MIME/empty/size failure, no dropped image, retry or fallback. Google adapter now rejects unresolved remote URL rather than writing invalid inlineData. Tool function responses currently encode only text, so preparing images hidden in tool.output would be wasted and was not added.
- GoogleRemoteImageRequestTest also checks HTTP failure and oversized image stop generation before any API request.

`git diff --check` passed for owned paths. JVM compilation and all green assertions remain root-owned pending checks. Existing WIP in MiniAppHtmlValidator/MiniAppShell was preserved.

Independent phase review identified the changed Gemini network execution path and header/body cancellation gap. Both have now been addressed as described below.

## Independent review follow-up

Root authorized two focused MC07 corrections identified by phase reviewer:

- Header-only common HTTP await did not keep Call cancellation attached to blocking image-body reading. Local `Call.awaitRemoteImage` now owns a cancellable continuation through response/body close, installing cancel before enqueue and reading the bounded body inside onResponse. No common HTTP or Jev utility changed. New RemoteImageCancellationTest delivers headers through a fake actual Call, signals body-read entry, uses a finite blocked source and then cancels the coroutine; same Call must receive cancel after headers.
- MiniApp-created remote images could otherwise reach generic Gemini downloader after draft/send, bypassing its existing sandbox network boundary. The bridge now snapshots only MiniApp HTTPS Image attachments through its already-owned MiniAppHttpClient.fetchImage after send approval/draft confirmation and before writing. Existing guarded DNS, per-hop redirect guard and 2 MiB image bound are reused. Stored draft/sent payload contains image data bytes, never the external URL. Document handling and ordinary provider URL policy stay as before; no new fetch permission or global private-host policy.
- Actual bridge postMessage test checks public uppercase HTTPS success → exact data URI bytes in durable draft and real send lambda; private destination blocked before request, redirect to private destination never fetched. Separate denied-send case checks zero image fetch before user approval.
- Claude / Gemini remote scheme detection now agrees with bridge's case-insensitive HTTPS acceptance. Provider positive tests use uppercase scheme and keep caller URL byte-for-byte; HTTP canonicalization of network request scheme is expected.
- Runner approval test cold-start failure was a fixture readiness issue: real SettingsAggregator dummy gate prevented initial update. Fixture uses test Main dispatcher so AppScope initialization can run under paused Robolectric looper, then explicitly awaits initialized settings and checks durable flag/policy values before true→false→true assertions. No production settings behavior changed.

These follow-up changes passed owned-path diff-check; root will compile/run and re-review. Worker still did not run Gradle.

Full-suite fixture correction: root's 2,696 app tests left one MiniApp snapshot assertion failure (plus separate Koin-owned failure). Synthetic public image HTTP response supplied only ResponseBody's MIME and omitted actual `Content-Type` response header, while existing fetchImageBlocking checks the header at MiniAppNetwork.kt:143–145. Fixture now supplies the real HTTP header; draft/send assertions retain response errorCode/error for evidence if another failure occurs. No production URL guard changed for this correction.
