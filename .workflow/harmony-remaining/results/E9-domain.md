# E9 domain implementation

2026-09-28; started after E8 source checkpoint `189eec7bd`. Read `harmony-arkts`, E9 frozen contract and Google auth design. Only domain-owned files changed; root retains index, DI and sanitized provider import. No build, device, external OAuth/token/model requests, credentials or native/socket changes.

## Result

- Google settings preserve `api_key`, `gemini_code_assist_oauth`, independent `antigravity_oauth`, and the four local configuration fields `privateKeyRef`, `oauthClientId`, `oauthRedirectUri`, `oauthClientSecretRef`. Maker defaults are empty. Copy and full persistence codec retain these fields and legacy `privateKey`; explicit staged secure migration belongs to Entry/UI. The full persistence serializer is not a portable or sanitized export.
- `hasUsableAuth` checks configuration eligibility only. OAuth requires client/redirect; SA requires Vertex/email/project/location plus legacy key or local ref. It does not return READY or authenticate the account.
- Synchronous Google API factory now awaits its optional injected `GoogleAuthResolver` on each authenticated request with the original `AbortSignalLike`. It checks cancellation before/after resolution, requires the matching authentication identity, and requires real token/project/SA location. Gemini/Vertex API-key requests retain their existing roulette/auth routes and never invoke the resolver.
- SA generates the encoded Vertex project/location/model route and Bearer header. Code Assist uses cloudcode-pa with `{model,project,user_prompt_id,request}`; Antigravity uses its independent daily-cloudcode route and `{model,project,userAgent,requestType,requestId,request}`. Custom legacy API-key/Authorization headers cannot override authenticated requests. No host/client/tier retry or second chat engine was introduced.
- Nonstream and SSE each unwrap one `response`; both root and nested errors remain visible. SSE error stops consumption without resending already delivered text. The existing message/body/tool-ID/parser pipeline remains in use.
- Shared pure wire helpers implement load/onboard/LRO, token response validation, exact SA JWT claims, and real model catalogs. They do not own HTTP/timers/refresh/generation/store. Onboarding retains actual server tier IDs, chooses its advertised default or first eligible tier, exposes project/validation/ineligibility failures, and never invents FREE/legacy entitlement. Validation URLs are a separate safe status field.

`google_auth.ts` is 259 lines; the other changed production files remain under 1000. No new SDK APIs were added. `google_request.ts` needed no additional changes: its existing CodeAssist thinking gate is now enabled for both OAuth transports by the caller.

## Frozen exports

Types/interfaces: `GoogleOAuthMode`, `GoogleRequestAuth`, `GoogleAuthResolver`, `GoogleOAuthBinding`, `GoogleOAuthTokens`, `GoogleAuthStatus`, `GoogleLoginAttempt`, `GoogleCatalogModel`, `GoogleModelCatalog`, `GoogleCatalogPage`, `GoogleOnboardingPlan`, `GoogleOnboardOperation`. `GoogleAuthError(code,message,validationUrl=null)` exposes readonly `code` and `validationUrl`.

`GoogleAuthStatus.phase` includes `configured` for local configuration without remote validation; `ready` belongs to Entry's real persisted token/project state. Status has no secrets. `validationUrl:string|null` allows the actual qualification action instead of burying server JSON in a message. `GoogleLoginAttempt` is shared domain data; Entry's `beginGoogleLogin` asynchronously returns it.

Constants: `GOOGLE_OAUTH_AUTHORIZATION_ENDPOINT`, `GOOGLE_OAUTH_TOKEN_ENDPOINT`, `GOOGLE_CLOUD_PLATFORM_SCOPE`.

Public pure helpers:

```ts
googleOAuthBinding(setting:ProviderSettingGoogle):GoogleOAuthBinding
googleOAuthScopes(mode:GoogleOAuthMode):string[]
googleCloudCodeBaseUrl(mode:GoogleOAuthMode):string
googleCloudCodeHeaders(mode:GoogleOAuthMode,accessToken:string):Record<string,string>
googleCloudCodeMethodUrl(mode:GoogleOAuthMode,method:string):string
googleCloudCodeOperationUrl(mode:GoogleOAuthMode,name:string):string
buildGoogleLoadCodeAssistRequest(mode:GoogleOAuthMode,projectId:string):JsonObject
parseGoogleLoadCodeAssistResponse(mode:GoogleOAuthMode,input:JsonObject,configuredProjectId:string):GoogleOnboardingPlan
parseGoogleOnboardOperation(input:JsonObject):GoogleOnboardOperation
parseGoogleOAuthTokenResponse(input:JsonObject,nowMs:number,previous:GoogleOAuthTokens|null):GoogleOAuthTokens
buildGoogleServiceAccountJwtClaims(email:string,nowMs:number):JsonObject
buildGoogleVertexCatalogUrl(pageToken:string=''):string
parseGoogleVertexModelCatalog(input:JsonObject):GoogleCatalogPage
parseGoogleAntigravityModelCatalog(input:JsonObject):GoogleCatalogModel[]
```

`GoogleChatApiDeps.resolveAuth?:GoogleAuthResolver` is the only added host injection. `unwrapGoogleResponse` is consumed directly by the existing chat API/parser; it does not need a new Entry interface. B/C/D were notified of the actual DTO/helper source and root owns public index exports.

## Verified evidence and limits

One necessary group, `harmony/chat/src/test/google_auth_http.test.ts`: initial actual API calls reproduced four failures (SA/Code Assist throws, Antigravity ignoring cancellation, nested error reported as no candidates); after implementation it passed 8/8. Added the requested sanitized-import assertion and authenticated SSE identity/signal case to the same group: **10/10 PASS**. This checks HTTP ports and codecs, not account availability or platform signing. No other suite was run.

- `cd harmony/chat && ./node_modules/.bin/tsx --test src/test/google_auth_http.test.ts`: exit 0, 10 tests PASS.
- `cd harmony/chat && ./node_modules/.bin/tsc --noEmit`: exit 0, including shared current index/import source. Rechecked after the final two necessary assertions.
- `harmony/scripts/lint-arkts.sh` for the five changed production files: exit 0, **0 errors / 472 warnings**. Most diagnostics are codelinter's unresolved cross-file type warnings (`no-unsafe-*`); they were not suppressed or described as zero defects. The first sandbox attempt could not write the official tool log; the authorized identical retry completed.
- `git diff --check`: exit 0. No commit made by this worker.

## Primary-source protocol corrections

Current official [Code Assist types](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/types.ts) use `free-tier`/`standard-tier`, an advertised default and separate ineligible-tier validation data. [Setup](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/setup.ts) supplies unspecified IDE/platform metadata for its protocol. This implementation uses returned tier IDs and truthful unspecified Harmony metadata; it does not copy platform clients or Android/darwin metadata. Antigravity uses the existing cross-platform compatibility wire, with an AmberAgent-Harmony User-Agent and independent store identity; this is not a new official third-party support claim.

SA catalog is the official [publishers.models.list](https://docs.cloud.google.com/gemini-enterprise-agent-platform/reference/rest/v1beta1/publishers.models/list) endpoint, corroborated by [Google ModelGarden proto](https://github.com/googleapis/googleapis/blob/master/google/cloud/aiplatform/v1beta1/model_garden_service.proto): `aiplatform.googleapis.com/v1beta1/publishers/google/models`, Bearer cloud-platform, `publisherModels` and `nextPageToken`. A catalog is not project/region inference authorization. Code Assist has no verified model-list endpoint in the actual [server](https://github.com/google-gemini/gemini-cli/blob/main/packages/core/src/code_assist/server.ts) and the local Android implementation explicitly documents that boundary; B returns the explicit manual catalog path, never an API-key endpoint or fabricated fetchAvailableModels.

Actual API12 signing, loopback callback, OAuth client/redirect acceptance, daily-cloudcode account eligibility and live streaming/nonstream acceptance remain external gates. Nothing in this report marks them PASS. Existing unused legacy `google_oauth_contracts.ts` exports were left intact under the repository's no-unrequested-deletion rule; real request execution no longer calls those old unimplemented contracts.
