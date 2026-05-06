# Codex Phase 0 Audit: M3 Restrained UI Refactor

Date: 2026-05-05
Branch: `experiment/ui-redesign-20260505`
Baseline: `fd8b913b fix(ui): restore streamed message layout`
Rollback tag: `amberagent-ui-baseline-before-claude-20260505`

## Starting State

- `docs/ui-mockup-2026-05-05.html` exists as an untracked file and is treated as Claude-owned. It was not opened, read, or referenced for this audit.
- `docs/CLAUDE_AUDIT_PHASE0_2026-05-05.md` exists as an untracked file and is treated as external to this Codex line.
- `docs/CODEX_M3_RESTRAINED_UI_PROTOTYPE_2026-05-05.html` is the visual reference for this route.
- `ChatMessageTools.kt` already contained experimental expressive edits (`LinearWavyProgressIndicator`, `MaterialShapes.Cookie6Sided`, animated capsule corners). Those conflict with this route and should be replaced with stable M3 shapes/progress.

## Current UI Structure

- Chat screen is `Scaffold` driven: `ChatPage.kt` owns top bar, `ChatList.kt` owns the timeline, and `ChatInput.kt` is the bottom bar.
- Timeline rendering flows through `ChatList.kt -> ChatMessage.kt -> MessagePartsBlock`.
- Message part grouping lives in `ChatMessageCot.kt`; it currently preserves the streamed layout fix by merging adjacent text and ignoring blank reasoning markers for visible layout boundaries.
- Reasoning/tool work log UI is split across `ChatMessageReasoning.kt` and `ChatMessageTools.kt`.
- Message actions are currently icon-like controls in `ChatMessageActions.kt`, with a larger bottom sheet for secondary actions.
- Live Status notification copy is centralized in `AgentLiveStatusNotifier.kt`; notification icon and promoted ongoing choices are already deliberate and should not be changed.
- Settings homepage is `SettingPage.kt`, using `CardGroup` and `LargeFlexibleTopAppBar`.

## Risk Points

- Do not modify `MarkdownBlock`, `Markdown.kt`, or `MarkdownNew.kt`; visual changes should wrap Markdown output only.
- Do not change `groupMessageParts()` semantics unless absolutely necessary. If touched, both stream regression tests are mandatory.
- Do not alter tool execution, approval state, terminal behavior, workspace/SAF behavior, provider/API marshalling, or Room schema.
- `ChatInput.kt` is high-risk because it owns launchers, crop flow, picker flow, paste handling, WebView preview, and Sandbox controls. Visual changes must stay around shape, spacing, color, and fixed-size controls.
- `SettingPage.kt` may reorganize homepage group order, but must not rename routes or touch `SettingVM`.

## Recommended Touch Files

- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`
- `app/src/main/java/me/rerere/rikkahub/data/agent/AgentLiveStatusNotifier.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh/strings.xml`

## Implementation Direction

- Use stable Material 3 containers, standard rounded shapes, ListItem/Chip-like hierarchy, and LinearProgressIndicator.
- Keep the tone utilitarian and scannable: "Agent work log" over expressive demo.
- Prefer local constants and small composable helpers where they reduce repeated shape/spacing/color choices.
- Verify after message rendering changes with `MessageStreamAccumulatorTest`, `ChatMessageCotTest`, `:app:compileDebugKotlin`, and `git diff --check`.
