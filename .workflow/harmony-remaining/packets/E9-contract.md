# E9 frozen contract: separate Google authentication identities

E9 starts after E8 source checkpoint. Source authority: results/E9-google-auth-design.md, local SDK/API12 docs cited there and the actual Android/iOS implementations. Only necessary checks, no external account requests or repeated device runs. Keep API-key Gemini/Vertex behavior and the existing message/SSE engine. No shared OAuth framework.

## Identity, storage and fields

Keep `api_key` and the old `gemini_code_assist_oauth` identity; add independent `antigravity_oauth`. Old Code Assist is the corresponding Standard/Enterprise compatibility transport, with current individual-tier deprecation stated in UI. Antigravity uses its own daily-cloudcode route, headers, onboarding identity and agent wrapper. Do not silently transform accounts, mix token stores or substitute clients/hosts after errors.

OAuth client configuration is explicit user-supplied `oauthClientId` and exact registered `oauthRedirectUri`; optional client secret is stored only by secure reference `oauthClientSecretRef`. No copied platform client credentials. Missing configuration is honestly unavailable. Loopback uses a minimal POSIX native helper in the existing libamber_native pipeline: SDK TCP server close is API20 and cannot satisfy API12 cancel cleanup. The helper binds loopback, returns actual port, bounds HTTP input and closes listener/connections/worker/TSFN on cancel. No additional native dependency. It is supported technically only for exact `http://127.0.0.1:PORT/PATH` / `http://localhost:PORT/PATH`, with registered desktop client responsibility and actual bound-port check. Do not invent a callback scheme or embedded login.

New service-account private keys live in the dedicated encrypted Google store by `privateKeyRef`. Existing `privateKey` remains decode/read compatible until explicit successful save moves it into secure storage. A save must stage the new reference, persist provider metadata, then reclaim only superseded owned secret; source persistence failure keeps the prior provider/secret valid. Never claim the old Preferences blob was encrypted. New provider export excludes OAuth tokens/client secret/private key data; referenced secrets are local and require reconfiguration after import, made visible.

Provider public fields are `oauthClientId`, `oauthRedirectUri`, `oauthClientSecretRef`, `privateKeyRef` (default empty), plus existing email/project/location/auth mode. `hasUsableAuth` describes configured eligibility, not successful network authorization: OAuth requires client+redirect; SA requires vertexAI/email/project/location and legacy key or local reference; requests verify actual store/token/project. copy/serialize/decode preserve fields and all mode identities.

## Frozen ports

All signals use existing `AbortSignalLike` from `@amber/deepread-domain`; no DOM signal type.

Domain owns `google_auth.ts` named DTOs and shared wire constants/protocol helpers. `GoogleRequestAuth` named interface carries `kind:'service_account'|'code_assist'|'antigravity'`, `accessToken:string`, `projectId:string`, `location:string`. `GoogleAuthResolver` named function type `(setting:ProviderSettingGoogle, signal?:AbortSignalLike)=>Promise<GoogleRequestAuth>`. `GoogleChatApiDeps.resolveAuth?:GoogleAuthResolver` is awaited per request with the original signal; factory remains synchronous. API key requests never call this resolver.

Entry `GoogleAuthSupport.ets` exports `resolveEntryGoogleAuth(setting,signal?)`, `loadGoogleAuthStatus(setting)`, `beginGoogleLogin(setting):Promise<GoogleLoginAttempt>` with named attempt DTO `{authorizationUrl:string,completion:Promise<GoogleAuthStatus>,cancel:()=>void}`, `retryGoogleOnboarding(setting,signal?)`, `logoutGoogle(setting)`, and `loadGoogleModelCatalog(setting,signal?)`. UI owns external browser start/cancel/lifecycle and saved draft setting; external browser backgrounding must not cancel its own login. Explicit page destruction/back/cancel/logout/config change terminates the attempt; OAuth attempts bind saved provider+mode+client config. Secure store + PKCS8 signer separate small modules, not DI imports. Entry exports stage/load/delete Google secret helpers for explicit save; status never returns tokens/private keys.

## Actual requests and gates

- SA: PKCS8 PEM → API12 RSA PKCS1 SHA256 → RS256 JWT exact Google claims, max3600s → token endpoint form; async cache single-flight keyed actual credential revision, expiry margin; no refresh token. Vertex project/location/model path segments encoded and Bearer used without API-key roulette.
- OAuth: PKCE/state in memory, loopback listen + native getsockname actual port check, exact bounded callback/path/state single use, cancel/timeout closes sockets. Per-provider+mode refresh single-flight, preserve refresh token, invalid_grant logout, generation prevents stale refresh/onboard after logout/relogin. Secure token persistence must succeed before READY. No access token in provider.apiKey/export/logs.
- Code Assist `{model,project,user_prompt_id,request}` wrapper and cloudcode-pa route; Antigravity daily-cloudcode `{model,project,userAgent,requestType,requestId,request}` agent wrapper using truthful platform metadata. Exact loadCodeAssist/onboardUser/LRO protocol, server allowed tier/project/validation errors remain visible; no assumed FREE eligibility. Valid token with failed onboarding is onboarding_required, explicit retry.
- Nonstream and SSE unwrap exactly one true `response`, retain nested errors and abort. No resend after consumed content, automatic host/client/tier changes or second chat engine.
- UI modes keep independent fields; SA JSON importer only parses known Google PKCS8 fields, masks secrets, saves references before provider commit, preserving original model list and unrelated fields. Model catalog uses the actual mode, not API-key endpoint for OAuth/SA. No fake READY from client config or token exchange alone.

## Ownership and necessary verification

A domain: provider_settings.ts/serialize, new google_auth.ts and google_chat_api.ts plus minimal google_parse/request mode mapping. Root owns index exports and DI. One affected domain HTTP group only (API-key untouched, SA/OAuth wrappers/routes/abort/errors/codec).
B Entry auth: new GoogleAuthSupport/GoogleOAuthClient/GoogleServiceAccountToken and small callback/wire helpers. C secure owner: new GoogleSecureStore and GoogleRS256Signer; actual existing Asset/AES helpers, no SSH codec changes. B and C share frozen narrow interfaces before production. Only one auth lifecycle/callback group and one actual temporary-key independent signature group; mock crypto is explicitly not device Crypto acceptance.
D UI: ChatProviderDetailPage and a small dedicated Google configuration component/page if needed to keep existing 1200+ file growth narrow; actual model fetch/login/save/import/export paths. E native owner: new google_loopback_core/napi .h/.cpp and one necessary host lifecycle group (real GET/reply/close/rebind/incomplete-connection cancel). Root owns native shared CMake/napi_init/d.ts registration.

Root owns shared routes/main_pages if new page necessary, index and AppContainer resolver injection. No unrelated OAuth refactor.

Explicit provider deletion persists removal before cancelling both provider OAuth identities and removing their tokens/current bound secret references. Ordinary logout stays mode/binding-specific. Cleanup failure reports source deletion truthfully.

AuthSupport exports `initializeEntryGoogleAuth(http:HttpClient):void`; root initializes it with the existing HttpClient once, no reverse DI dependency or second client. Unsupported mode-specific model catalog is reported with the existing manual-add path; no API-key catalog substituted for OAuth or SA.

`updateGoogleAuthConfiguration(setting):Promise<void>` is called only after real provider metadata commit, outside the stage/commit rollback catch. It and explicit begin may replace an active client binding; ordinary status/resolve/retry/catalog reject an obsolete binding instead of cancelling a newer login. Cleanup of prior settings is binding-specific and cannot invalidate the new binding. Post-commit update/cleanup failures report that configuration was saved; they never reclaim its new secret reference. Normal API-key catalog keeps the existing details-page HTTP path. Save busy consumes both hardware/title back until metadata and cleanup settle; an OAuth attempt can still be explicitly cancelled and left.

Root integrates once, necessary affected lint/compiler/sign and independent A logic/B UI review. Device/real account/API12 signing, callback and one streaming/nonstream provider acceptance remain external C gates while offline, never marked PASS by fixtures. Continue E10 after E9 source/checkpoint while retaining those gates.
