# E10 frozen contract: Google builtin tools and complete source display

Start after E9 source checkpoint. Use results/E10-google-tools-design.md and its official GenerateContent references; no auth/endpoint rewrite. Existing model.tools/codec/toChatModel remain the single data path.

## Small explicit operating mode

Google search/url_context use a builtin-tools request mode: one or both selected → `tools` includes actual `googleSearch:{}` / `urlContext:{}`; this mode does not register local/MCP functionDeclarations. Explain the choice in model settings before saving. With neither Google builtin selected, retain original function declaration behavior. image_generation alone never writes an empty Google tools array or suppresses local functions. Do not describe all Gemini versions as incapable of combinations: Gemini 3 mixed mode needs server parts/id/signature round-trip and is not implicitly enabled by these switches. No retry/downgrade/scrape after provider errors. Custom body remains existing last-write override.

URL context is Google-only. OpenAI Responses retains current search/image mapping; unsupported selections are retained in configuration with truthful protocol explanation, not silently removed. Actual capability is based on model.providerOverwrite ?? provider container, not merely the container brand/name. Provider errors/unsupported models remain visible, no guessed model-name allowlist.

## Complete response and persistence chain

Root extends the one DeepRead UIMessageAnnotation definition with named DTOs:

- `UIMessageUrlContextAnnotation { type:'url_context';url:string;status:string }`
- `UIMessageGoogleSearchSuggestionsAnnotation { type:'google_search_suggestions';html:string }`

Chat facade keeps the same re-export, serializer/parse add exact branches. Keep citation/interruption compatibility and original annotations append/distinct accumulator. Never serialize a new annotation as interruption.

Google parser shares candidate source extraction for nonstream and SSE: grounding web citations unchanged; urlContextMetadata.urlMetadata retrievedUrl + real status; groundingMetadata.searchEntryPoint.renderedContent stored exactly. Metadata-only final SSE emits empty assistant parts with annotations; no fake text, no dropping sources due to missing content. Distinct duplicates remain handled by existing accumulator. SUCCESS/ERROR/PAYWALL/UNSAFE/unknown are displayed honestly, never turn failed retrieval into successful citation. HTTP/promptFeedback/SSE errors remain original real failures.

Builtin-only mode does not request includeServerSideToolInvocations or claim raw server toolCall/toolResponse mixing. Ordinary response parts remain parsed by the existing engine; do not add incomplete tool loops solely to map these fields.

## UI

ChatModelParams adds URL-context draft/load/save alongside current search/image; selects current effective protocol, preserves unsupported stored choices. Show useful mode explanation, not internal class names.

ChatMessageAnnotations consumes exact new annotations adjacent to existing citations. URL state/source link meaningful, long URLs/title wrap and 44vp click targets where actionable. Google Search Suggestions display the actual returned HTML/CSS in a small dedicated component using existing Web loadData/controller-attach pattern; no fake chips, JS bridge, or generic browser framework. Verify local official API12 docs for size/navigation APIs first; avoid fixed clipping. Links go once to the actual target via existing external browser path. Theme/safearea/message-width/layout source review; do not claim device screenshots while offline.

## Ownership and necessary gates

A domain: google_request.ts/google_parse.ts and one necessary round-trip HTTP fixture. No E9 auth rewrite or second request engine. Root owns DeepRead annotation DTO/re-export + Chat serialize, any other exact annotation-copy consumers found by search. Root freezes exports before A consumes them.
B model UI: ChatModelParamsPage only, actual effective capability/draft/read/save/preserve. C source UI: ChatMessageCommon.ets + new GoogleSearchSuggestions.ets, actual source-status and HTML display. Shared ChatPage/DI/build/device remain root-owned.

Only one end-to-end fixture: serialized real Google ProviderModel.tools → toChatModel → actual GoogleChatApi fake HTTP → body and two SSE events (text, metadata-only tail containing citations, successful and failed URLs, exact suggestion HTML, duplicate sources) → real accumulator → serialize/reload. Preserve text once, statuses/HTML round-trip and citation distinctness. Include the concrete only-image/no-empty-tools branch in the same group, not a separate suite. Do not repeat E9/auth/whole Chat checks. Necessary affected types/lint/compiler/sign, independent logic and UI-call review, C device pending recorded. Continue E11 after source checkpoint.
