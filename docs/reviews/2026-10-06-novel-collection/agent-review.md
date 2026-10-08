# Read-only collect / structured helper review

Scope: current Harmony collection -> material suggestion analysis -> NovelInteractiveAdapter provider input; current AmberNovel iOS collect and archive UI. No repository edits.

## Verified reachable failures

1. `discussion_archive.ts:70–75` calls `JSON.parse(text)` directly. A model returning a complete valid JSON object wrapped in a `json` Markdown fence fails `prepareNovelDiscussionArchive` with `归档摘要不是有效 JSON，请重试或手动整理讨论`. Temporary `structured-probe.ts` reproduces through production helper, not a copy of its parser.
2. `suggestion_engine.ts:26` JSON example literally sets `kind` to `world|character|relationship|outline|requirement|other`. This is not any allowed kind. If copied as presented, `parseSuggestions:92–95` rejects it. The probe invokes real `creation.collectMessage`, proves manuscript saved first, then analysis rejects and chapter `suggestionWarning` records the format error. This confirms a malformed example can cause the reported failure, not that this exact model output was observed on user's phone.
3. Suggestion fenced JSON is ALREADY supported by first `{` to last `}` extraction (`suggestion_engine.ts:75–81`). Probe explicitly verifies acceptance. Do not add a second generic fallback solely to solve this.

## Actual provider contract

`creation.ts:1092` starts `refreshMaterialSuggestions` after collect; `1108–1109` resolves stateSync target then calls helper. Suggestion helper has 4096 hard output tokens, `toolProfile:'none'`, no history (`suggestion_engine.ts:179–188`). Archive helper has 3072 hard tokens (`discussion_archive.ts:104,123–127`). Both omit taskOptions.

`novel_interactive_adapter.ts:263–281` overrides assistant system/presets, disables memory/recent chats/skills/local and MCP tools; `92–93` disables selected tools for `none`; `357–359` runs ChatTurn directly for empty tools. A second probe injects marked global persona/system, presets, template, memory flags, enabled skill. Real helper -> adapter -> ChatTurn provider input contains only helper system and chapter user prompt, no marked global content. No prompt pollution reproduced.

`novel_request_config.ts:58–63` retains configured sampling and reasoning/customBody when taskOptions omitted. No novel response_format/json_schema contract exists to reuse. Do not invent provider schema support.

## iOS comparison, keeping tasks distinct

`NovelProjectWorkspaceView.swift:607–615`: new whole-chapter collection offers discussion archive; replaceChapter exits without offer. User's explicit new preference authorizes removing Harmony automatic offer regardless of iOS current offer.

`NovelSessionViewModel.swift:2256–2359`: collectCandidate performs collection transaction then conditionally schedules state sync only if branch needs it. `NovelFactTransactionLifecycle.swift:22–35,233–321`: collection writes deterministic plot or commits without state sync. No automatic material-suggestion analysis is in this path, and current iOS app has no `materialSuggestions` or `suggestionWarning` model. This is not sufficient justification by itself to remove Harmony live-material feature; they are separate responsibilities.

`NovelStructuredModelExecutor.swift:875–942`: iOS separates local output reservation from provider maxOutputTokens. Its notes cite a prior reasoning-budget JSON truncation bug. Current helper provider hard cap is nil; archive reasoning is automatic; state sync respects setting. Harmony still has 4096/3072 caps. This is a concrete contract difference, but no user raw output establishes truncation as current root cause.

iOS repair machinery is specific manual state sync: `NovelManualSyncProgress.swift:88` maximum 2 repairs, `NovelFactTransactionLifecycle.swift:660–709` permits repair on decode/schema, uses failed raw text, excludes provider/no-output failures. `NovelStructuredModelExecutor.swift:109–119` explicitly restricts repairable codes. Do not present it as automatic archive repair.

## Recommended scoped repairs

- Remove automatic collect archive offer while preserving explicit archive entry and its author confirmation.
- Change suggestion JSON example to one actual valid kind; state supported kinds separately and prohibit copying the pipe-separated list as a value.
- Normalize ONLY complete fenced JSON for archive before existing schema validation. Keep malformed/concatenated/partial output rejection and no mutation before author confirmation.
- If needed, one format repair for invalid-output only, with previous response and original task facts; still run original parser, maintain suggestion source digest checks. Do not retry network/provider/timeout or silently return zero suggestions.
- Keep technical parse diagnostics out of disruptive notifications; distinguish saved manuscript from optional failed analysis with explicit retry.
- Treat output-cap changes independently if real provider failure proves truncation; nil is not currently a transparent request through Harmony output-budget adapter.

## Evidence

- `probe.log`: current fence acceptance/rejection, invalid kind copied, collect persistence and warning.
- `adapter-probe.log`: actual provider input, no global system/template/memory pollution.
- `existing-tests.log`: current suggestion/archive/adoption regression files 19/19 passed before main-agent changes.

Tests:
`harmony/deepread/node_modules/.bin/tsx --tsconfig harmony/chat/tsconfig.json --test harmony/deepread/src/test/novel_suggestion_engine.test.ts harmony/deepread/src/test/novel_discussion_archive.test.ts harmony/deepread/src/test/novel_adoption_branch_notification.test.ts`
