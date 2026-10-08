# MiniApp / chat / provider review

Read-only source review against main 6a66f7a plus current WIP. No Gradle or device runs. Only this Android repository read. Findings below are source-confirmed; suggested tests still need execution. Jev / DefaultRunKernel excluded.

## Confirmed findings

### MC-01 P2 — source checker skips the character following quoted strings and block comments

- `app/src/main/java/app/amber/feature/miniapp/MiniAppSourceChecks.kt:111-123,143`: comment branch advances to `end + 2`, quote branch advances to `j + 1`, then the shared `i++` advances once again.
- Production: source editor `MiniAppSourceEditor.kt:269-278` uses issues as blocking save gate.
- Repro: `<html><body><script>const x={a:'x'};</script></body></html>` is valid JS, but checker skips `}` after `'x'`, leaving `{` unclosed. `f('x')` similarly leaves `(`. CSS `a{content:'x'}` affected too. `function f(){/*note*/}` also skips its closing brace. Existing tests only use strings followed by semicolons, so miss this.
- Second check: not by design; promised checker is for obvious syntax errors, but this rejects ordinary correct code.
- Minimal fix: make cursor advancement single-owner (continue after branches that fully advance). Add quoted-object/function-call, immediately-following-comment-close and CSS quoted-value regressions; also keep malformed bracket cases failing.

### MC-02 P1 — saving source version overwrites newer metadata and can resurrect a deleted app

- `MiniAppRepository.kt:161-188`: transaction never reads current app; copies entire captured `app`, then `dao.upsert(updated)`. Source editor captures that entity and calls `saveNewVersion` at `MiniAppSourceEditor.kt:278`. `restoreVersion` also reads app outside the version-writing transaction at repository lines 193-196.
- Production: open editor from app list/card, run app / rename / pin / update summary or generated revision through other surface while editor is open, then save. Stale `runCount`, `lastRunAt`, `pinned`, title, permissions, summary etc. replace durable values. Deleting the app between capture and write recreates its row with cleared grants/history. Room transaction serializes write but does not fix stale copied state.
- Second check: `saveRevision` already uses current row plus expected version inside transaction, establishing intended behavior; source edit is explicitly HTML-only and says permissions stay unchanged, so reverting latest permissions is not intentional.
- Minimal fix: inside write transaction load current row, reject missing row; apply only HTML/version/hash/time to current row. Enforce captured version for user source edits to avoid silently replacing a concurrently revised HTML version. Make restore's read/write share that transaction. Tests: stale metadata preserved; deleted app not recreated; concurrent source version produces explicit conflict.

### MC-03 P2 — attachments are silently truncated into corrupt images or changed URLs

- `MiniAppConversationWriter.kt:87-90` trims and `take(2000)` before accepting `data:image/` or `https://`.
- Production: `miniapp_bridge.js:191` → `MiniAppBridge.kt:280` parse attachments → durable composer draft or real send. Normal base64 screenshot exceeds 2,000 chars, gets persisted/uploaded as corrupt partial image. Long signed HTTPS URLs lose signature/query and stop working or change resource.
- Second check: size cap can be intentional, but mutating accepted opaque URL/data is a correctness bug. Return does not indicate truncation; bytes are not optional text.
- Minimal fix: validate full input against a bounded size and explicitly reject oversized attachments, or adopt separate data-image size limit consistent with bounded screenshot use. Never truncate URL/base64. Tests exact limit, oversized data URI, long signed URL; accepted input must remain byte-for-byte intact.

### MC-04 P1 — MiniApp auto-send gate keeps high-risk approval setting captured at runner creation

- `MiniAppRunnerPage.kt:330-335` constructs gate with closure over `appSettings`; gate injected only in AndroidView factory at lines 468-507. Later recomposition creates a new gate but existing bridge keeps old gate. Factory-only theme capture has the same stale-setting shape but is cosmetic.
- Production: launch runner when `autoApproveHighRiskToolCalls=true` and MINIAPP_SEND=AUTO; disable global high-risk auto-approval while runner is still mounted (settings restore / another surface). Subsequent host send still sees old true and skips confirmation. Reverse direction always confirms despite newly enabled approval.
- Second check: sandbox global settings intentionally read `settingsStore.settingsFlow.value` per request; permission store/flags also live. Only high-risk global flag uses captured snapshot, so live revocation should work.
- Minimal fix: gate callback read current SettingsAggregator value (or rememberUpdatedState); avoid bridge rebuild. Regression: same gate/runner object sees true→false and false→true updates.

### MC-05 P2 — createArtifact effectId dedup is a non-atomic check then insert

- `MiniAppWorkspaceWriter.kt:53-64`; repository `ArtifactRepository.kt:233-281` performs lookup separately from randomly identified insert. No unique key on app/effect or mutex in either owner. `withArtifactWrite` at lines 436-437 is restore gate, not serialized effect lock.
- Production: JS issues two overlapping `host.createArtifact` calls with same effectId. Bridge limitedParallelism(1) serializes only non-suspending sections; both suspend at dialogs/Room lookup, both can see missing artifact and both create independent rows/files. Existing replay tests are sequential.
- Second check: writer documentation explicitly promises idempotency; this is a reachable violation, not design. Bridge dispatcher does not route through effect ledger.
- Minimal fix: serialize lookup + create in singleton writer with a simple Mutex (no map of per-key locks needed). Retain durable lookup for sequential/process replay. Deterministic concurrent regression launches duplicate calls with controlled overlap, expects one artifact/reference and same receipt ID.

### MC-06 P1 — consuming an earlier draft can delete a newer MiniApp draft

- `MiniAppConversationWriter.kt:57-67` saves draft A, calls suspend send, then unconditionally deletes conversation row. `ConversationDraftStore.kt:95-101` / `ConversationDraftDAO.kt:17` delete by conversation only. `ChatVM.kt:355` also launches asynchronous unconditional clear after send.
- Production: draft A send/clear is delayed at suspend boundary while another authorized MiniApp writes draft B to same conversation; A resumes and deletes B. Separate runner bridge scopes are not mutually serialized. ChatVM's asynchronous clear similarly races a newly arriving bridge draft.
- Second check: draftId is already persisted and receipt identifies exact item; comment says draft that was written then sent should be consumed. Deleting a replacement draft loses user content.
- Minimal fix: DAO delete WHERE conversation_id and draft_id; store exposes clear-if-current. Writer passes saved draft ID. ChatVM only consumes actual restored draft identity and protects against newer replacement. Regressions pause between saved/sent/clear, overwrite B, assert B remains.

### MC-07 P2 — remote images are encoded as base64 data in Claude and Gemini requests

- `ai/.../util/FileEncoder.kt:100-102` deliberately returns HTTP URL unchanged from `encodeBase64(false)` for OpenAI image_url compatibility. `AnthropicMessagesAdapter.kt:271-278` always writes it as `source.type=base64,data=<URL>`; `GeminiGenerateContentAdapter.kt:339-344` always writes `inlineData.data=<URL>`.
- Production: MiniApp explicitly accepts HTTPS attachments; draft/send → UserInputPreprocessor passes non-text unchanged → model adapter. Existing ImageAttachmentValidator.kt:107 accepts the same helper's successful URL result and marks image READY for vision-enabled models. No transformer in core/ai downloads these remote image URLs.
- Second check: OpenAI supports remote URL, but Claude/Gemini base64 fields require encoded bytes; HTTP string is not base64. Do not alter shared FileEncoder's return to globally fetch because OpenAI intentionally consumes URL.
- Minimal fix: map Claude remote URL to provider-supported URL image source; Gemini needs bounded download/encoding at suitable async provider boundary or explicitly reject unsupported remote images before generation. Add adapter/request regressions asserting no URL string in base64/inlineData and exercise MiniApp→provider route.

### MC-08 P2 — MiniApp-written draft is only restored during ChatVM construction, not when returning to existing chat

- `ChatVM.kt:207-220`: only initialization coroutine loads ConversationDraftStore. `onChatVisible` at 227-236 only handles approvals. `ChatPage.kt:217-223` uses navigation-retained Koin ViewModel and calls onChatVisible when page re-enters composition.
- Production: existing chat page → MiniApp runner → host.sendToConversation mode=draft targeting this chat → Back. Existing ChatVM survives navigation; its initializer does not rerun, so new durable draft never appears in the composer until VM is recreated. Return-on-existing-conversation is the common host draft usage.
- Second check: method explicitly promises composer draft; persisted draft still exists, so this is missing projection, not persistence loss. Keep user-entered text protected when implementing, as initializer already checks empty input.
- Minimal fix: refresh host draft when chat becomes visible / observe store for current conversation, only replace empty composer or the unchanged previously restored draft. Test retained VM visible→hidden→draft written→visible sequence.

## Excluded / not promoted

- MiniApp validator regex bypasses: shell has CSP connect-src none, script-src only inline + bundled libs, URL interception and private-address DNS guard; regex-only bypass is not sufficient to demonstrate dangerous execution beyond deliberately trusted JS. No speculative security finding promoted.
- Legacy null-grant-means-allowed for old capabilities is explicitly documented; new system permissions use durable decisions. Not a bug by itself.
- Source checker regex literals / optional HTML closing tags may be unsupported-by-design lightweight checking; did not broaden MC-01 into a parser replacement.
- Generic WebView URL synchronization compares original URL to current URL and clients capture original state. Potential navigation reload/stale-client behavior requires an actual Compose runtime trigger check because AndroidView update scope may not re-run solely from client fields; excluded pending reproduction.
- Rich sandbox loading occurs only in factory but expanded dialog receives completed widget spec; streaming-update concern not promoted without a supported edit-while-open production chain.

## Suggested phase grouping

1. MiniApp data correctness: MC-01, MC-02, MC-03, MC-05, MC-06, focused JVM/Robolectric tests.
2. Host integration and approval: MC-04, MC-08 with retained runner/VM tests and production settings reads.
3. Provider attachment correctness: MC-07 with ai request mapping tests and explicit remote-image policy at provider boundary.

All fixes should be local; no new fallback provider, global retry scheme or generic parser required.
