# WebMount Capability Gate Matrix

Date: 2026-06-19

## Gate Model

| Capability | Android anchor | iOS state | Gate | Decision |
| --- | --- | --- | --- | --- |
| Formal feature entry | SettingExperimentalWebMountPage / WebMountManager global toggle | WebMountView under advanced features | Product navigation | Keep visible as a formal advanced feature. Do not hide behind an experimental/global kill switch. |
| Model tool access | LocalTools gates by WebMountManager.globalEnabled and evalEnabled | ChatViewModel declares 8 safe wm_* tools when local executor exists | IOSPermissionStore askEveryTime / disabled | Intentional iOS drift from Android global switch: formal entry stays available; model use requires foreground approval by default. |
| Arbitrary JS eval | wm_eval gated by separate eval toggle and mandatory approval | wm_eval unsupported, undeclared, blocked | Unsupported result | Keep unsupported unless a separate high-risk product decision is made. |
| Site registry | UserSiteRegistry | IOSWebMountRegistry | Registry membership and enabled flag | Implemented: seeds 9 sites, add/remove/restore, enable/disable. wm_open now requires a registered enabled station. |
| URL allowlist | collectSiteUrls / origin checks / http(s) only | IOSWebMountURLPolicy + registry site lookup | http/https, host match, station enabled | Implemented: http/https default, blocks file/javascript/data and unregistered hosts; removed hosts cannot be opened by direct URL. |
| WKWebView runtime | WebViewPool / SessionHandle | IOSWebMountWKRuntime | Visible WKWebView session | Implemented: status/title/redacted URL/progress/back/forward state and navigation policy. No headless pool parity claimed. |
| Session cookies | CookieManager / WebMountCookieProvider | IOSWebMountCookieStore | Foreground clear approval | Implemented summary/clear without values. Uses WKWebsiteDataStore.default; documented as persistent app WebKit jar. |
| Read-only page bridge | JsBridge / bridge.js | IOSWebMountBridgeScripts via evaluateJavaScript | Read-only helper scripts only | Implemented: state, readable/interactive/snapshot extraction, get text/value/attr. Raw HTML denied on iOS. |
| Visual snapshot | wm_visual_snapshot / wm_visual_read / screenshot | UI button uses wm_extract mode=snapshot; tool names blocked | Unsupported for real capture | Partial: DOM visual candidates with rects. Screenshot/visual_read tool names return unsupported. |
| Navigation | wm_open/state/extract/get/back/forward | IOSWebMountController | Permission + runtime | Implemented 8 safe tools. Model calls require approval; user-initiated UI calls can execute directly. |
| Site tools | wm_site_add/remove/profile_synthesize | UI add/remove/restore only | Foreground UI | Implemented in UI, not model tools. Profile synth unsupported. |
| OAuth / signed fetch / adapters | ProfileBridge / WebMountFetchTools / adapter tools | Unsupported | Unsupported result | Explicitly unsupported on iOS in this closure. |
| Chat handoff | Android chat/tool result surfaces | WebMount handoff to ChatView input draft | Foreground UI action | Implemented: extracted, redacted page text becomes next chat draft. |
| Deep Read handoff | Android DeepRead sources | Board signal ingest via webmount source type | Foreground UI action | Implemented fallback: extracted page text becomes local webmount board signal for manual generation. |
| Tool result display | ChatMessageTools Web category | ChatToolStepModel WebMount summaries | Redacted display | Implemented: wm_* timeline hides raw token/signed URL input and shows readable status. |

## Unsupported But Honest

- wm_eval
- wm_signed_fetch
- wm_oauth_connect
- wm_oauth_refresh
- wm_profile_synthesize
- wm_site_adapter
- wm_screenshot
- wm_visual_snapshot
- wm_visual_read

## Security Invariants

- No cookie values, tokens, Authorization headers, or complete sensitive URL queries in tool output or timeline summaries.
- Model-initiated WebMount tools default to needs_user_action under the WebMount askEveryTime policy.
- `wm_clear_session` always requires explicit foreground user action.
- `wm_open` requires a registered enabled station; stale allowed hosts from deleted sites cannot be opened directly.
- Raw DOM HTML reads are denied on iOS; sensitive value selectors are refused.
