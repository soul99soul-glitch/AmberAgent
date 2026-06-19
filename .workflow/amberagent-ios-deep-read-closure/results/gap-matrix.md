# iOS Deep Read Gap Matrix

## Android Reference
- Deep Read is opened by `deep_read_open`, keyed by `topicId`, optional `sourceUrl`, and `force_regenerate`.
- `DeepReadScreen` owns real loading, running, partial failure, retry, history, template fallback, and expired-cache states.
- `HotListRepository` persists `DeepReadHistoryItem` with `topicId`, `title`, `DeepReadOutput`, `createdAt`, `expiresAt`, `updatedAt`, and 24h freshness.
- `DeepReadWorker` and `DeepReadAgentRunManager` provide background execution, segmented stage retry, source prefetch, model/tool checks, and partial failure persistence.
- Template validation blocks scripts, external resources, interactive elements, unsafe placeholders, and oversized HTML.
- Android uses hidden assistant/subagent settings for Deep Read. iOS must not restore that multi-assistant pattern.

## iOS Current State
- `BoardView` is a one-page manual BoardSignal summary labeled "深度阅读"; it persists only one date-bucket Markdown result.
- `BoardSettingsView` is an empty explanation page.
- `IOSBoardPersistence` has board Markdown persistence plus signal repository/aggregator, but no Deep Read task/history model.
- `IOSSearchExecutor` has locally testable structured search results and built-in DuckDuckGo/Bing fallbacks.
- `IOSConversationStore` has read-only current messages, search, and board signal candidates.
- `DocumentAccessStore` can read selected txt/md/json/csv/pdf/docx previews and honestly rejects images/OCR.
- `IOSWebMountController` can extract readable text from the current foreground WKWebView; without a ready session it must degrade honestly.

## P0 Gaps
- Add a real iOS Deep Read task model with statuses: queued/running/succeeded/failed/unsupported.
- Add local Documents persistence for task history and result details, not one date-overwritten Markdown file.
- Add source normalization for manual text, search result, conversation, file, and WebMount source inputs.
- Add a visible Deep Read entry, history list, create flow, detail/result view, retry, empty/error states.
- Add minimal template/layout choices and validation.
- Add local generation that works without paid services by producing a deterministic draft from provided sources, with model/API-key failures represented honestly.
- Add result actions: copy/save and return to chat context.

## P1 Gaps
- Add XCTest coverage for persistence, history, normalization, template validation, failed/retry state, search/conversation/file/web sources.
- Add docs roadmap note with actual iOS scope and known unsupported paths.
- Clean misleading settings copy so unimplemented capability is not presented as a setting.

## Implemented iOS State
- P0 task model/history/source normalization/create/detail/retry/result actions are implemented locally.
- Manual text, structured search results, current conversation, selected file previews, and current WebMount readable text are wired as Deep Read sources.
- File and WebMount paths return honest errors when the source cannot be read instead of fabricating content.
- Built-in template selection and safe HTML validation are implemented; custom template UI/workbench remains out of scope.
- XCTest coverage was added for the requested local/mocked lanes, but execution is blocked by the existing app-target `SubAgentRunner.swift` compile error.

## P2 Gaps
- Android-style staged supervisor loops, source prefetch budget, image scoring, custom HTML rendering, notifications, and WorkManager background fill are out of scope for this iOS closure.
- File OCR, private WebMount login automation, paid/API-key search, and background auto-generation remain unsupported unless a real local path exists.
