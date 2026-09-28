# E12 narrow execution contract draft — pending root freeze

Read-only preparation, 2026-09-28. Based on `results/E12-webmount-design.md` and the current source paths below. This draft authorizes no product/test/build/device/network work. Root freezes the final API/boundaries before implementation. E11 decision and approval-subject exports are dependencies, not already completed E12 consumers. Scope③ Moli/Playwright/Steel/remote desktop remains excluded.

## Verified seams and scope corrections

- `platform_impl/WebMountTools.ets:100–134` already checks global OR assistant enablement, current Stations permission and bot/hand lease. The nine `WebMountSites.ets:46–56` seeds are URL authorization entries, not nine adapters. DOM scripts currently assign positional `data-wm-ref` afresh (`WebMountTools:138–164`); `wm_observe:777–790` compares a signature string. Neither is a stable document/revision binding.
- `WebMountSession.ets` has one real ArkWeb controller and logical tabs, pageReady/onPageEnd, host detach and cancellable open/reload. `evalJson:472–480` stringifies immediately; a Promise is not an awaited browser request. `WebMountSurface:526–547` has no document-start capture/revision injection. `getNavLog:125–127` and `wm_network_inspect:757–775` are navigation-only; there is no Harmony HAR parser/archive/template registry.
- Android's actual `WebMountFetchTools.kt:151–195` only replays observed opaque GET/HEAD templates, same origin, excluding mutation-like paths. `app/src/main/assets/webmount/bridge.js:1193–1269` observes page XHR/fetch, and `:1276–1317` performs actual page fetch. These are behavioral references, not proof of full browser HAR capture or Harmony execution.
- `ConversationBrowserGuard.ets:37–51` exposes an owner assertion closure. Its `:53–93` wraps all `wm_*` in one actual `inFlight`; wrapping a goal and then dispatching a guarded child rejects itself. Goal must retain ownership checks without taking this primitive-operation slot.
- Actual catalog is `ConversationRunTools.ets:175–205,246–286`, not only ChatPage's old factory. It applies current profile, browser guard, Recipe/Plugin and discovery scope. `recipe_loop.ts:21–82` has existing ordered save/permission/dispatch ports and no goal slot. Do not create a second tool loop, scheduler, provider registry, remote browser host or global adapter registry.
- Existing generic OAuth design's pasted callback is superseded here by root's explicit request to reuse E9 native loopback: `GoogleOAuthCallback.ets:23–114`, native `startGoogleLoopback/replyGoogleLoopback/closeGoogleLoopback` (`cpp/types/libamber_native/index.d.ts:135–147`). That transport is loopback HTTP, not a Google-specific exchange protocol; keep the existing native names/API and implement a local WebMount consumer. No new SDK server or external account is needed for source/controlled validation.
- Existing `WebMountOAuthSupport.ets` provides PKCE crypto and Feishu token exchange; its `webmount_feishu_token` Preferences key is not a secure token store. Existing Feishu consumers stay compatible. New generic OAuth uses secure references and is not declared connected merely because the browser opens.

## Four worker owners plus root integration

| Owner | Exclusive production boundary after root starts E12 |
|---|---|
| A — pure OAuth/HAR/profile domain | New small `chat/webmount/` models, validation/protocol, HAR/template projection, nine fixed readonly profile descriptors and JSON-encoded DOM builders. Reuse existing `WebMountCryptoPort`; no SDK/DI, index, dispatcher, Session or Page edits. |
| B — platform browser/OAuth | Own `WebMountSession.ets`, narrow `WebMountTools.ets` wiring and new `WebMountAdvancedTools.ets`; document-start rawfile, local OAuth runtime/callback/secure-store/restricted-HTTP consumers. All shared controller/document operations have one owner. Do not modify native loopback, generic Google auth, DI, tool_loop or UI. Keep Session/Tools changes small; extract the new bridge/runtime responsibilities rather than growing a God Class. |
| C — goal orchestration | New pure `chat/webmount/goal.ts` plus narrow tools/projection/recovery helpers, existing ordered execution-port consumer and one necessary loop fixture. No source ownership of tool_loop/recipe_loop, shared guard/policy, DI, Page or browser Session. |
| D — settings/UI | Existing `SettingExperimentalWebMountPage.ets` sections or small named cards: site OAuth configuration/status/cancel/disconnect and HAR file import/coverage. Approval projection uses root's existing message UI. No browser core, secret storage, dispatcher or DI changes. |
| Root | Exports/DI/current Service catalog and real main-model/E11 decision injection; optional goal slot in existing loop; exact guard exclusion, saved-child approval subject/card and cold recovery/lifecycle hooks; shared declaration/profile additions only when needed. Own final reviews/types/lint/build/sign. Device C remains pending until an actual available device is verified. |

Workers are not alone in the repository and must preserve concurrent edits. API changes are coordinated before source edits. No broad old-suite rerun or device matrix by workers.

## Shared named DTO and exports

Exact shape is named interfaces (no inline ArkTS object unions); pure JSON fields use existing `JsonObject/JsonValue`. Existing `WebMountOAuthToken`, `WebMountCryptoPort`, `RecipeExecutionPort` and `RecipeLoopAdapter` are reused rather than cloned.

```ts
interface WebMountDocumentToken { sessionId:string; documentId:string; revision:number; }
interface WebMountElement {
  ref:string; tag:string; text:string; href:string|null; inputType:string|null;
  options:string[]; // visible option values only; empty unless a select candidate
}
interface WebMountObservation {
  token:WebMountDocumentToken; url:string; title:string; text:string;
  elements:WebMountElement[];
}
interface WebMountOAuthApplication {
  siteId:string; bindingHash:string;
  authorizationEndpoint:string; tokenEndpoint:string; clientId:string;
  redirectUri:string; scope:string;
  tokenEncoding:'form'|'json'; clientAuthentication:'none'|'body'|'basic';
  apiOrigins:string[]; clientSecretRef:string|null;
}
interface WebMountOAuthApplicationDraft {
  application:WebMountOAuthApplication;
  secretAction:'keep'|'replace'|'clear'; clientSecret:string|null;
}
interface WebMountOAuthState {
  siteId:string; bindingHash:string|null;
  status:'unconfigured'|'disconnected'|'awaiting_callback'|'connected'|'expired'|'failed';
  expiresAtMillis:number|null; errorCode:string|null; message:string|null;
}
interface WebMountHarArchive { id:string; importedAtMillis:number; har:JsonObject; }
interface WebMountReplayTemplate {
  id:string; sessionId:string; sourceId:string; source:'page_fetch_xhr'|'imported_har';
  documentId:string|null; origin:string; method:string; url:string;
}
interface WebMountNetworkSummary {
  requestTemplateId:string; sourceId:string; captureCoverage:'page_fetch_xhr'|'imported_har';
  method:string; displayUrl:string; status:number|null; replayable:boolean; reason:string|null;
}
interface WebMountSiteAdapterResult {
  siteId:string; observation:WebMountDocumentToken; url:string;
  fields:JsonObject; matched:number; truncated:boolean;
}
interface WebMountGoalRequest {
  sessionId:string; goal:string; completionText:string; allowedActions:string[];
  draftValue:string|null; maxActionDecisions:number; maxSeconds:number; maxNoProgress:number;
  decisionSource:'main_model'|'jev';
}
interface WebMountGoalDecision {
  kind:'action'|'handback'; candidateId:string|null; reason:string;
}
interface WebMountGoalCheckpoint {
  version:1; request:WebMountGoalRequest;
  phase:'ready'|'awaiting_approval'|'started'|'finished';
  observation:WebMountObservation|null; pendingStep:UIMessagePartTool|null;
  decisions:number; noProgress:number; startedAtMillis:number; deadlineMillis:number;
}
```

Host-only archive/template URLs remain raw inside the bounded private runtime. Model/UI summary uses redacted query values and excludes headers, cookies, authorization, secret/token, verifier/state and raw HAR. `bindingHash` is computed from validated fixed application configuration plus secret reference; UI/Model input cannot choose it. Goal metadata is exactly `goal_v1`; preserve other parent metadata.

A public pure exports: `validateWebMountOAuthApplication`, `buildWebMountAuthorizationUrl`, `buildWebMountTokenRequest`, `parseWebMountTokenResponse`; `parseWebMountHar(text,archiveId,now):WebMountHarArchive`, `webMountHarTemplates(archive,sessionId):WebMountReplayTemplate[]`, `webMountReplayAllowed(template,currentUrl):boolean`; `WEBMOUNT_READONLY_SITE_PROFILES`, `buildWebMountSiteAdapterScript(siteId,limit):string`. Use existing crypto/PKCE port; pure domain does not assume global `URL` (Harmony lacks it). Platform standard parsing supplies canonical origins/redirects; any pure parser remains small and explicitly tested, not a URL framework.

B Entry exports: `getWebMountOAuthRuntime()` returning the named interface below; `importWebMountHar(text):Promise<WebMountHarImportResult>` returns only archive ID/count (the raw archive never passes tool output); `createWebMountAdvancedTools(deps)` receives the actual Session, current assistant enabled state and OAuth runtime. Do not reverse-lookup another conversation's tool scope. Lifecycle cancel-all is root-wired and never background OAuth work.

```ts
interface WebMountHarImportResult { archiveId:string; entryCount:number; }
interface WebMountOAuthRuntime {
  loadApplications:()=>Promise<WebMountOAuthApplication[]>;
  saveApplication:(draft:WebMountOAuthApplicationDraft)=>Promise<WebMountOAuthApplication>;
  readState:(siteId:string)=>Promise<WebMountOAuthState>;
  subscribe:(siteId:string,listener:(state:WebMountOAuthState)=>void)=>(()=>void);
  connect:(siteId:string,signal?:AbortSignalLike)=>Promise<WebMountOAuthState>;
  refresh:(siteId:string,signal?:AbortSignalLike)=>Promise<WebMountOAuthState>;
  cancel:(siteId:string)=>void;
  cancelAll:()=>void;
  disconnect:(siteId:string)=>Promise<void>;
}
```

`connect` opens the browser only after actual native listener binding; returns pending once opened and handles completion internally with observable real state. It remains bound to the supplied signal after that pending return until completion/cancel. UI and tool share the same attempt. `cancel/cancelAll` are synchronous/light, close native handles through handled promises and invalidate late exchange/commit. No unhandled background continuation or waiting call falsely labeled connected.

B Session additions are local `observeDocument(signal?):Promise<WebMountObservation>`, `assertDocument(token):Promise<void>` and `evalAsyncJson(expr,token,assertOwner:()=>void,signal?,timeoutMs?):Promise<string>`. The owner closure is the existing conversation-browser assertion, passed by the current consumer; every poll/cleanup checks it, with no reverse DI lookup inside Session. The async method is for the fixed shipped bridge builders only, not a new model-facing eval capability or generic browser RPC. Cancellation uses the original document's AbortController/result slot; lost owner/document must not access/reset the next page. Detach/navigation invalidates tokens, refs and page templates. Logical tab changes use a new document token for the single actual WebView.

C exports: `createWebMountGoalAdapter(deps):RecipeLoopAdapter`, `createWebMountGoalTool():AgentTool`, `webMountGoalPendingPart(parent):UIMessagePartTool|null`, `recoverInterruptedWebMountGoals(conversation):Conversation`. Named deps inject actual observe/document/lease checks, finite candidates/decision port and time; execution uses existing `RecipeExecutionPort` supplied by root, not copied tool execution. No global event bus/ledger/scheduler.

## OAuth configuration → PKCE → origin-bound consumer

1. UI saves an application for an actual registered site. HTTPS authorization/token endpoints, client ID, scope, redirect, encoding/auth style and exact allowed API origins are explicit. The redirect is fixed `http://127.0.0.1:<port>/<path>` supported by E9 loopback; no remote callback relay/App Link/DNS listener is invented. Endpoint/origin configuration does not grant Stations access. APIs may be separate configured origins, but still require explicit existing Stations authorization; do not silently authorize `api.*`.
2. New `wm_oauth_connect(site_id)` only takes the saved site binding, receives explicit current permission before opening the system browser, starts native callback first, and returns truthful pending/connected/failed state without secrets. The approved binding is pinned before effect. Changed config invalidates pending and token binding; approval cannot be reused to open changed endpoints.
3. Callback accepts exactly one correct path/state and code-or-error, rejects duplicate/empty/fragment values and consumes one valid response. S256 verifier/state stay only in the live attempt. Cancel/timeout/background/port mismatch closes the actual loopback and rejects waiting work. Wrong state produces zero exchange requests. Restart cannot resurrect a PKCE attempt.
4. Exchange/refresh validates the fixed binding again before token commit. Store client secret/token through a local Asset-backed random key + private encrypted reference, following existing E2/E9 adapters; public KV stores only config/references. Write/verify a new secret reference before metadata commit; save/rotation failure preserves the previous working reference. No refactor of all provider/SSH storage and no claim that legacy Feishu Preferences tokens were securely migrated.
5. `wm_oauth_refresh(site_id)` uses only the configured token endpoint and saved bound refresh token. `wm_signed_fetch` gains optional `oauth_site_id`; host injects Bearer only at an exact allowed API origin with matching current binding and Stations permission. This OAuth route uses the existing E5 RCP no-auto-redirect pattern, checking each Location before sending; never sends old Bearer/Cookie across origin. Token exchange redirects fail visibly rather than sending client credentials to an unconfigured target. Existing `fetchWebMountRequest` enablement/cancellation gate is retained around the restricted transport, without allowing its ordinary auto-redirect client to bypass per-hop checks.
6. Tokens/secrets never enter tool results, page scripts, Jev/main-model input or logs. `disconnect` deletes this site's bound references only. Account login/API acceptance remains separately untested until actual application credentials and browser/account evidence exist.

## Nine actual readonly adapters

`wm_site_adapter(site_id?,limit?)` matches current authorized/ready URL to one of the nine frozen profiles: hackernews, reddit, github, bilibili, x_com, weibo, juejin, zhihu, feishu_docs. Read selectors from existing corresponding Android `profiles/*.json` hints and ship a small finite read projection: visible text/public links only, no cookie/storage/input value/HTML/functions/signing/private API calls.

Current Stations gate AND profile origins both apply. Explicit site ID differing from current URL fails; seed login host does not authorize another main host (notably Bilibili). Custom/unmatched sites report `site_adapter_unavailable`/`site_page_unmatched`, never synthetic records. `limit` default 20/cap 100. Profile JS JSON-encodes all parameters and produces real fields/count/truncation; nine descriptors are nine executed DOM adapters, not nine stub registrations. Actual third-party DOM match/login is a separate device/network gate.

## Full HAR consumption and honest page ledger

- Import only through the settings file picker/user-selected file; validate HAR 1.2 `log.version`, creator, pages when present, entries' startedDateTime/time, request method/url/httpVersion/cookies/headers/queryString/headersSize/bodySize, response status/statusText/httpVersion/cookies/headers/content/redirectURL/headersSize/bodySize and timings including valid -1 unavailable fields. Retain the complete validated JSON including extension fields. No partial archive labeled complete; reject a file beyond 2MiB/200 entries. Filesystem choice does not expand model/private file access.
- Archive source IDs and opaque request-template IDs are host-created; a tool cannot register its own URL/body as an observed template. Model receives only summaries. Import alone does not issue network requests. HAR may contain POST/binary/credentials while its permitted replay projection remains narrower.
- Document-start fetch/XHR wrappers keep original call/response semantics, do not consume original streams or add extra network requests. Keep ≤200 observed records with document/seq, actual status and explicit unavailable/truncated body/headers. Coverage is `page_fetch_xhr`, not full HAR/browser capture. Do not copy Android raw body/header forwarding into model output. ServiceWorker/frames/resource downloads/browser-wide response headers remain unobserved.
- `wm_network_inspect` retains existing navigation information and adds typed summaries/source/coverage. Imported HAR is labeled `imported_har`, never “captured by ArkWeb”. Full browser HAR collection remains unproven and is not silently replaced by a fetch ledger or a new remote service.
- `wm_fetch_replay(session_id,request_template_id,max_chars?)` is mandatory human approval (`allowsAutoApproval=false`); only a real current same-origin GET/HEAD non-mutation template, max_chars default 60000/cap 200000. User approval binds the actual template ID/source/method/URL and current document; changing document/origin requires revalidation, not silently substituted endpoint data. Imported Cookie/Authorization/body is not injected.
- Execute actual in-page `fetch` with current-page credentials and `redirect:'error'`, await actual result through the bounded local async slot, then return real status/content type/text/truncation/source. Cancel/30s deadline/document/owner checks run before, during and after; remove only the original slot. Missing template/unsupported method/cross-origin/mutation hints causes zero fetch. Timeout or host loss after request start reports unknown outcome where applicable, not a successful empty object. No replay retry.

## Synthetic DOM touch, truthful result

`wm_tap(session_id,document_id,revision,ref? or x/y?)` requires one current ref or one viewport coordinate pair, visible target/hit-test and the existing site/bot-hand/owner gates. It dispatches real synthetic Touch/TouchEvent touchstart then touchend with correct touches/changedTouches; only if not consumed does it invoke one default DOM click. Unsupported constructors fail `touch_event_unavailable`; no silent mouse/native fallback.

Output explicitly contains `event_mode:'dom_touch'`, `synthetic:true`, `dispatched`, `default_click`, `page_changed`, `goal_verified:false`. This never promises `isTrusted` or native touch, canvas/iframe/system injection. Tap is mandatory human approval and cannot bypass disabled arbitrary eval. Acknowledgment is not navigation/goal completion; next observation verifies result.

**Existing approval flags are insufficient for that promise:** `tool_permission.ts:188–208` allows every auto part when both auto toggles are set, before mandatory/alwaysAsk checks. Root must either freeze the original settings-based behavior explicitly or implement the proposed narrow E12 admission for exactly `wm_tap`/`wm_fetch_replay`: auto ⇒ saved pending, approved/denied remain authoritative, with a truthful `webmount_explicit_approval` trace. Apply the same admission to standalone and goal-child ordered paths; no generic resolver semantic change or new permission framework. Until root settles this source seam, declaring “mandatory human” merely by tool flags is incorrect. The final contract must record the chosen behavior before implementation.

## Goal uses the actual ordered host loop

- `wm_run_goal` request uses the named DTO; nonempty goal/completionText, defaults/caps 12 action decisions, 60 seconds, 3 no-progress. `allowed_actions` only narrows the fixed v1 actions. No submits/publish/delete/pay/login/captcha, arbitrary eval, OAuth/replay, package/subagent tools or recursive goal.
- Observe actual current document first. Generate a finite locally validated candidate list from that observation/request/current scoped primitive definitions (bounded 32, fixed IDs). Only current authorized link navigation, scroll, readonly observation/search, visible finite select options and the frozen draftValue for nonsecret text/search/textarea fields. No Enter/submit candidate, arbitrary URL/text/function generation or unobserved element ref.
- A real main-model selector may choose only these candidate IDs using the current parent runtime API/identity/parameters, or explicit Jev source uses E11 `chooseJevWebCandidate`/`JevWebCandidate`/`JevWebObservation`. Invalid ID/decision unavailable ⇒ handback. No implicit main-model fallback after Jev failure. Jev OFF/SHADOW emits no child actions; source selection is explicit. Neither model has a DONE shortcut; actual URL/title/visible-text completionText match in a new observation determines completed.
- Adapter `prepare` pins normalized request in parent `metadata.goal_v1`, no effect. Wrapper `.execute` explicitly throws `adapterRequired`; direct tool invocation cannot bypass orchestration. Root adds exactly one optional `goalAdapter?:RecipeLoopAdapter` slot to ToolLoopOptions/RecipeBatchOptions and ordered routing/checkpoint detection, preserving Recipe/Plugin/sibling semantics.
- Children come from this round's actual profile-filtered/current assistant oracle. Each child obtains immutable `wm_document_v1` metadata and current capture/permission trace. Original `port.decide` runs on each step. ASK saves the exact pending child and parent pending state before publication; parent output stays empty and siblings pause. Parent approve/auto/allowlist never grants later children approval. Existing saved parent locator locates this exact child; stale subject buttons must not approve a new child.
- On approved continuation, recheck original page owner, bot lease, enablement/Stations, document/revision, primitive availability and signal before saving STARTED and again at actual primitive dispatch. Document changed while waiting ⇒ discard stale child, clear its approval, observe/replan and seek fresh approval; never retarget approved ref. Failure to save STARTED means zero child effect. Primitive tool retry stays disabled for this adapter.
- Goal primitive inputs carry their immutable expected document_id/revision with the observed ref. B adds only optional token fields to the affected existing click/type/select/scroll/find scripts so the fixed script checks that token immediately before touching DOM, in the same JS turn as the effect. A dispatcher.before check alone has an await-to-script race and is insufficient. Ordinary old callers without goal tokens retain their current behavior; no rewrite of all WebMount tools or permission bypass.
- Outer goal is excluded from `ConversationBrowserGuard`'s primitive inFlight acquisition only; it still calls the original owner assertion before every await/effect. Actual guarded children alone acquire/release that slot. E6 visible conversation owns this controller: no reacquisition from B after leaving A, including nested asynchronous result cleanup.
- After each child, observe again and verify completion/no-progress. Preserve exact returned unknown before generic cancel/timeout classification; STARTED plus interrupted/failed result-save/cold page loss never replays the action. Cancel promptly stops active page/decision request. Budget, hand mode, lost capability/host, error and no progress produce explicit handback/cancelled/failed/outcome_unknown. Cold recovery has unchanged-reference/idempotent pure behavior and root saves before UI publication; it does not reconstruct a WebView.

## Root's minimum shared edits

1. `tool_loop.ts` + `recipe_loop.ts`: optional goal adapter slot, correct `goal_v1` routing/ordered sibling pause/continuation/cold recovery; reuse same existing dispatcher/save/retry-disabled port.
2. `ConversationBrowserGuard.ets`: narrow `wm_run_goal` orchestration exception to inFlight only, with original visible-page lease assertion still enforced. Do not mark all new WebMount tools unguarded.
3. `ConversationRunTools.ets` and current plugin/catalog oracle where applicable: inject current parent decision API and E11 selector, actual scoped primitives/browser/OAuth; compose browser capture/dispatch validation with terminal guard. Subagents/council without the visible browser capability do not receive a synthetic host.
4. Existing approval projection + Jev subject builder: saved goal.pendingStep drives exact child parameters/subject hash and parent locator, no copied parent approval. Settle the exact-name tap/replay human-admission seam above; both-auto must not silently contradict the chosen product behavior. Shared index exports and lifecycle cancel for pending OAuth/goal only.
5. Settings file-picker/runtime DI and document-start rawfile wiring by owning boundaries. OAuth/replay/goal are not default Plugin hostcalls; readonly site_adapter/tap require explicit capabilities/whitelist review before any separate addition.

## Necessary counterexamples and separate acceptance gates

No checks were run in this preparation. After root start, keep only source-needed focused groups, no site×method×mode matrix:

1. One domain/platform controlled group: exact callback state/path yields one exchange; wrong state/config replacement/cancel yields zero late exchange/commit and actual listener close; origin redirect never receives Bearer. One complete HAR with GET and POST retains structure, creates opaque IDs, and rejects POST/cross-origin/forged ID with zero replay. One representative profile fixture proves actual selector/readonly projection; it does not prove all nine live sites. If root freezes strict human admission, both-auto tap/replay must still save pending with zero primitive effect.
2. One actual tool_loop/dispatcher group: goal observe→finite choose→ASK holds sibling; one child approval saves STARTED before effect and executes once; next observation determines completion. Waiting revision change produces fresh pending rather than approved ref substitution; save failure/host loss/cancel prevents new effect and STARTED cold recovery never replays. Specifically prove the outer goal does not self-trigger browser `inFlight` and A's late callback cannot touch B.
3. Root's eventual local ArkWeb validation: page's actual fetch/XHR ledger, imported full HAR source, approved same-origin replay counted by the local endpoint, touchstart/end/default-action counts and goal final observation. Controlled JS/provider results, source checks, compiler and signing are distinct from ArkWeb/native/API12 behavior. No external account/network claim without evidence.
4. Independent A logic and B consumer/UI reviews, affected lint/types/final build/sign only after source freeze. C actual device bright/dark config/child approval/callback/HAR/touch/goal acceptance remains **pending** while unavailable; do not replace it with source or screenshots from another phase. No additional remote service, native trusted touch or full browser capture assertion.
