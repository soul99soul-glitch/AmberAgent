// Public smart_ui capability exports; implementations own their state.

export { parseSmartUi, validateSmartUiNode, validateSmartUiState } from '../chat/smart_ui/protocol.ts';
export {
  SMART_UI_COMPONENT_KINDS, SMART_UI_COMPONENT_CATALOG, componentKindAllowsChildren, componentKindRequiresState,
  componentKindIsPresentationState, componentKindHasOptions, componentKindIsNumeric,
  componentKindIsMultiSelection, componentNumberMin, componentNumberMax, componentNumberStep,
  decodeSmartUiSelection, encodeSmartUiSelection, smartUiComponentValueValid, smartUiComponentDisplayText,
} from '../chat/smart_ui/components.ts';
export type {
  SmartUiComponent, SmartUiComponentKind, SmartUiComponentRow, SmartUiComponentStyle,
  SmartUiStudyItem, SmartUiStudyOption,
  SmartUiComponentTone, SmartUiComponentEmphasis, SmartUiComponentDensity,
  SmartUiComponentAlignment, SmartUiComponentSpace, SmartUiComponentCatalogEntry, SmartUiComponentFamily,
} from '../chat/smart_ui/components.ts';
export type { StudyProgress } from '../chat/smart_ui/study.ts';
export {
  emptyStudyProgress, gradedItemCount, hasGradedItems, chooseStudyOption, revealStudyAnswer,
  nextStudy, previousStudy, restartStudy, parseStudyProgress, serializeStudyProgress,
  shouldAdoptStudyProgress, studyProgressFrom,
} from '../chat/smart_ui/study.ts';
export {
  smartUiContinuationSnapshot, appendSmartUiContinuation, isExplicitSmartUiContinuation,
  latestSmartUiContinuationSource, isSmartUiTaskState, smartUiContinuationDocumentForSource,
  SMART_UI_MAX_CONTINUATION_SNAPSHOT_CHARS,
} from '../chat/smart_ui/continuation.ts';
export type { SmartUiContinuationCandidate } from '../chat/smart_ui/continuation.ts';
export type {
  SmartUiDocument, SmartUiNode, SmartUiState, SmartUiScalar, SmartUiOption, SmartUiDiagnostic,
  SmartUiDraftAction, SmartUiResetAction, SmartUiAction, SmartUiVisibilityCondition, SmartUiNodeType, SmartUiValueType,
  SmartUiVariant, SmartUiLayout, SmartUiTone,
  SmartUiDocumentStatus, SmartUiDiagnosticSeverity,
} from '../chat/smart_ui/protocol.ts';
export {
  appendSmartUiDraft, createSmartUiController, limitSmartUiDraftPrompt,
  SMART_UI_MAX_INPUT_VALUE_CHARS, SMART_UI_MAX_DRAFT_PROMPT_CHARS,
} from '../chat/smart_ui/interaction.ts';
export type {
  SmartUiStateAccessor, SmartUiStateValue, SmartUiStateReconciliation, SmartUiStateChange, SmartUiController,
  SmartUiAssetDiagnostic,
} from '../chat/smart_ui/interaction.ts';

export type { SmartUiTextBinding, SmartUiTextBindingPath } from '../chat/smart_ui/protocol.ts';
export { SMART_UI_MAX_TEXT_BINDINGS } from '../chat/smart_ui/protocol.ts';
export { smartUiDefinitionFingerprint, smartUiDraftKey, smartUiConversationKeyPrefix,
  restoreSmartUiDraft, serializeSmartUiDraft } from '../chat/smart_ui/persistence.ts';
export type { SmartUiDraftSource, SmartUiDraftRestore } from '../chat/smart_ui/persistence.ts';
export { createSmartUiDraftStore } from '../chat/smart_ui/store.ts';
export type { SmartUiDraftStore, SmartUiDraftSaveResult } from '../chat/smart_ui/store.ts';
export { projectWeatherReadTool, resolveWeatherBinding, annotateSmartUiWeatherBindings,
  weatherBindingRef } from '../chat/smart_ui/binding.ts';
export type { SmartUiWeatherProjection, SmartUiToolSource } from '../chat/smart_ui/binding.ts';
