# Search child audit

Scope: `harmony/chat/src/main/ets/search/*`, production `SearchRegistry.ets` and `SearchSdkInit.ets`. Read Harmony ArkTS skill. No production or test edits. Existing R12 recency/query fix is present and excluded from new findings.

## Confirmed

### Search URL 1 · P2 · Direct result URLs with ordinary `u`/`url` parameters are treated as Bing redirects

- Owner: `harmony/chat/src/main/ets/search/bing_service.ts:28–42`, production call at `parseBingResult` and fallback parsing. `search_web` enables Bing through `search_orchestrator`, DeepRead free provider reaches the same service.
- Trigger: a normal direct result anchor is `https://forum.example.com/member.php?u=123`, or `https://developer.example.com/tool?url=https%3A%2F%2Fexample.org`.
- Actual service fixture reproduction: first result disappears and the service rejects with `Search failed: no results found`; second result retains “Tool page” and its snippet but rewrites citation to unrelated `https://example.org`.
- Cause: decoder searches `u`/`url` on every host/path; only Bing redirect URLs should have their target unwrapped. Direct URLs are already explicitly supported by existing provider fixtures (`search_html_providers.test.ts:31–34`).
- Minimal fix: recognize the supported Bing redirect host/path before decoding; return all other direct URLs unchanged. Preserve ordinary encoded and `a1` base64 redirects through the same decoder, with direct-URL query cases as regression fixtures. Do not add a generic redirect follower.
- Closely related DDG defect: `duckduckgo_service.ts:25–34` interprets `uddg` on every direct host, so direct `https://example.com/docs?uddg=section1` also disappears. The same host/path ownership restriction applies; parameter name is much rarer than Bing `u`, so list this as the sibling location rather than a separate high-impact finding.
- Controls: existing-format `https://www.bing.com/ck/a?u=...` and `//duckduckgo.com/l/?uddg=...` each still resolve to the expected original article in this probe.
- Evidence: `memory-context-search-url-probe.ts` and `.out`, real service funcs with local HTML HTTP fixtures. This is not a live Bing/website or device result.

### Search URL 2 · P2 (narrow trigger) · IPv6 non-default ports disappear from the dedup domain key

- Owner: `harmony/chat/src/main/ets/search/search_orchestrator.ts:368–371`.
- Trigger: same-titled results on two different services, e.g. `http://[2001:db8::1]:8080/status` and `http://[2001:db8::1]:9090/status`.
- Actual orchestrator reproduction: HTTP executor returns both valid items but tool output contains only the first, with `duplicate_count=2` and domain `[2001:db8::1]`; service B URL and content are suppressed.
- Cause: IPv6 branch returns bracketed host only, discarding `:port`. Canonical URL keys correctly retain ports, but same-domain title fallback still collapses the two services. The adjacent comment at line 360 explicitly states port retention is the contract; `search_aggregator` uses a port-preserving domain parser.
- Minimal fix: include the validated numeric port suffix after IPv6 bracket in `domainOf`; add one distinct-port regression. No change to same-service title dedup policy.
- Evidence: same probe files. IPv6 port URLs are valid and retained in the API provider result model; trigger is uncommon, but behavior and owner-level repair are deterministic.

## Exclusions and boundaries

- No finding for dropping `SearchResult.answer`: orchestrator merges source result items by design; no requirement to include vendor-generated synthesis was found.
- No finding for generic same-domain/same-title dedup, source priority/call cap, enabled-service selection, Wiki/HN topic applicability, or query-text time constraints: explicit current policy.
- No finding for `enableWebSearch` in `createSearchTools`: production callers gate creation (`ConversationRunTools.ets:356`; AppContainer has equivalent gates).
- Cancellation is threaded through configured/free search and scrape HTTP paths. Existing `search_cancellation.test.ts` covers ownership and breaker behavior; no new reachable cancellation fault confirmed.
- DeepRead free-source and Google share the current nine-second budget, and prefetch finalization owns transport cancellation; current tests cover aggregate success preservation. A local timeout alone was not escalated into a transport leak claim.
- Legacy independent-key adapters in `SearchRegistry.ets` are not active (`loadKeyedProviders` is never called); historical API/schema doubts there are excluded.
- Checked Firecrawl v2 response grouping against its official docs; current `data.web`/`data.news` grouping matches. Linkup boolean-string representation was not confirmed incompatible; excluded rather than inferred from an SDK type.
- Malformed cache state / hypothetical platform short writes are not listed without a reachable reproduction in this cache owner.

Run probe from repo root:

```sh
harmony/chat/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json .workflow/comprehensive-review-round2-20261018/results/memory-context-search-url-probe.ts
```
