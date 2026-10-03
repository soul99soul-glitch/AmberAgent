# P6 discovery implementation

Status: source frozen for independent review. Discovery source/UI and the coordinated URL-context collection and cached forced-generation input fixes are complete. No build, signing, installation or device verification by this worker.

## Actual production reference and scope

The current iOS independent app calls `DeepReadDiscoveryView → IOSHotListDashboardStore → IOSHotlistProviders.all`, with discovery preferences and grouped settings in `DeepReadDiscoveryPreferences` / `DeepReadDiscoverySettingsView`. The production registry has five English sources plus fifteen NewsNow sources. Harmony already had eleven equivalent NewsNow/RSS sources and aggregation/cache/refresh behavior. This phase adds the nine actual gaps, rather than treating existing aggregated content as absent.

Added sources: Hacker News (`hacker_news`), HuggingFace Papers (`huggingface_papers`), GitHub AI (`github_trending_ai`), IT Home (`ithome`), SSPAI (`sspai`), Juejin (`juejin`), Coolapk (`coolapk`), Xueqiu (`xueqiu-hotstock`), and Hupu (`hupu-zhugandaoretie`). Existing eleven IDs and their saved switches remain intact. Five newly optional Chinese sources default off, matching their production iOS default status; newly default HN/HF/GitHub/IT Home sources default on. Existing user switches always take precedence.

## Resulting path

- New small pure `domain/discovery.ts` holds the twenty-source catalog, five ordered categories, and actual HN/HF/GitHub response parsers. `NewsNowProvider` reuses the current HTTP port, cancellation, source section, RSS and error/cache paths. HN asks Firebase top stories and individual items, HF reads daily-papers JSON, GitHub reads the production trending HTML, and six Chinese additions use the existing NewsNow endpoint.
- Summaries flow through provider items, raw cache copying/merging, title translation projection and aggregate source projection. A failed refresh retains prior content, summary and success timestamp with the existing visible stale/error state.
- `domain/discovery_input.ts` uses existing typed `DeepReadInputSource`. Hotspot inputs contain readable title/provider/rank/summary, with genuine URLs only. A no-link item remains ready text with null URL; its stable `discovery-<digest>` topic identity is not a fabricated URL. Actual existing generation evidence/planning accepts these saved inputs even with empty search results.
- Board single-source and aggregated actions preserve all source contexts in `inputSourcesJson` and all valid URLs in `seedUrlsJson`. The Article UI owner saves inputs before generation. The actual linked-page collection path fetches a URL despite ready title/rank context, then combines the background and fetched body into one source; the input merge does that without letting ten metadata rows displace page bodies.
- New `DeepReadDiscoverySettingsPage` groups all twenty sources and reuses existing source/focus/interval/Wi-Fi/translation keys. The existing refresh/cache/return-to-page projection is reused. Both the new standalone settings and retained shared setting entry use the same source-default fallback.
- Settings and Board use existing V1/theme/page components. The discovery settings action is 44px; row title width is flexible. No new SDK API or persistent discovery schema was added. Root registered the route and domain exports.

## APIs and ownership

- `DEEPREAD_DISCOVERY_SOURCES`, `DEEPREAD_DISCOVERY_CATEGORIES`, `DeepReadDiscoverySource`, `DeepReadDiscoveryCategory`, `DeepReadDiscoveryItem`.
- `DeepReadHotspot`, `discoveryHotspotInputs(items)`, `discoveryArticleParams(title, inputs, urls, force?)`.
- Owned sources: new `discovery.ts` / `discovery_input.ts`; provider/parser integration in `NewsNowProvider.ets`; summary-only changes in `HotListState.ets` / `HotListAggregator.ets`; narrow `input_sources.ts` merge; `BoardPage.ets`; new `DeepReadDiscoverySettingsPage.ets`; one default-fallback line in the discovery load region of `SettingDeepReadPage.ets`.
- Article entry/input refresh is owned by the main P6 UI worker; `run_manager` is owned by the pipeline worker. No unrelated search settings, article/template fields, AppContainer or generation lifecycle areas were edited by this worker.

## Validation and independent review

- Provider test first reproduced **0/9** additional sources, then all nine actual endpoint paths passed. Deleted HN records and absent URLs are handled without invented links. Cancellation between top IDs and item fetches prevents the latter requests; malformed HF JSON yields a visible source error.
- Pure discovery tests cover catalog/category completeness, actual parser shapes, no-link persistence/evidence, real mixed links and stable rank-independent identity.
- Actual `createRunContext` test verifies a no-link saved input with empty search reaches planning with provider/rank and remains stored. Linked-input regression reproduced the old missing fetch seed, then passed after the pipeline owner restricted verified body-cache reuse to web/search inputs. Six discovery tests now pass, including the actual linked-page and no-link planning paths; `/tmp/p6-discovery-domain-tests.log`.
- Final provider/cache/RSS/aggregate/Board/grouped-settings/shared-switch regressions: **44/44 passed**, `/tmp/p6-discovery-focused-final.log`. Input collection and run-manager regressions: **28/28 passed**, `/tmp/p6-discovery-inputs-run.log`.
- New settings and actual Board method tests: 10/10 passed; `/tmp/amber-p6-discovery-ui-tests.log`. Independent child review checked catalog, summary/cache, cancellation, source/input calls, and 44px source layout.
- UI lint: 0 defects, `/tmp/amber-p6-discovery-ui-lint.log`; domain/provider/cache lint: 0 errors, `/tmp/p6-discovery-domain-lint.log`; input merge lint: 0 errors, `/tmp/p6-discovery-merge-lint.log`; shared settings lint: 0 defects, `/tmp/p6-discovery-shared-settings-lint.log`.
- Whole-domain `tsc --noEmit -p harmony/deepread/tsconfig.json`: passed; `git diff --check`: passed. Independent discovery source/UI review rerun: **24/24 passed**, `/tmp/amber-p6-independent-review-tests.log`. Article-owner actual cached-force/non-force/live-task/input persistence regressions: **11/11 passed**, `/tmp/p6-discovery-article-inputs.log`.

Independent review found two concrete integration gaps: URL rank metadata blocked page-body collection; cached forced generation ignored newly selected hotspot context. Both were fixed by their exact owners and verified by actual caller regression tests. Cached force refreshes the incoming hotspot context before generation, preserves existing article/template metadata until the actual run replaces it, and rejects overwriting the inputs of a live task. Ordinary reopening keeps the existing article without an unintended new generation. It also found the shared setting default mismatch, fixed by one fallback expression. No broader redesign or new recovery framework was introduced. Root owns the final build/screenshots and phase gate; this report does not close those gates.

## First-build ArkTS correction

Root's first build rejected the HN array filter's TypeScript type predicate (`item is HotItem`, `arkts-no-is`). Replaced only that callback annotation with `boolean` and an explicit `HotItem[]` cast after null filtering; parser behavior is unchanged. Actual provider HTTP regressions **8/8 passed**, whole-domain tsc and diff check passed, single-provider lint **0 defects** (`/tmp/p6-discovery-provider-arkts-fix-tests.log`, `/tmp/p6-discovery-provider-arkts-fix-lint.log`). Source re-frozen; root owns the build retry.
