import type {
  SmartUiDiagnostic,
  SmartUiDocument,
  SmartUiAction,
  SmartUiNode,
  SmartUiScalar,
  SmartUiState,
  SmartUiVisibilityCondition,
} from './protocol.ts';
import { smartUiComponentValueValid, smartUiComponentDisplayText, componentKindRequiresState,
  componentKindIsPresentationState } from './components.ts';
import { smartUiContinuationSnapshot } from './continuation.ts';

/** The page owns this store; a later KV adapter can implement the same two methods. */
export interface SmartUiStateAccessor {
  get: (sourceKey: string, stateId: string) => SmartUiScalar | undefined;
  set: (sourceKey: string, stateId: string, value: SmartUiScalar) => void;
}

export interface SmartUiStateValue {
  id: string;
  value: SmartUiScalar;
}

export interface SmartUiStateReconciliation {
  values: SmartUiStateValue[];
  invalidStateIds: string[];
  diagnostics: SmartUiDiagnostic[];
}

export interface SmartUiStateChange extends SmartUiStateReconciliation {
  accepted: boolean;
  stateId: string;
}

export interface SmartUiAssetDiagnostic {
  ref: string;
  message: string;
  severity: 'warning' | 'error';
}

export interface SmartUiController {
  reconcile: (
    document: SmartUiDocument,
    sourceKey: string,
    currentValues: SmartUiStateValue[],
    initialValues: SmartUiStateValue[],
  ) => SmartUiStateReconciliation;
  setValue: (
    document: SmartUiDocument,
    sourceKey: string,
    currentValues: SmartUiStateValue[],
    stateId: string,
    value: SmartUiScalar,
  ) => SmartUiStateChange;
  isValueValid: (document: SmartUiDocument, stateId: string, value: SmartUiScalar) => boolean;
  visibleWhen: (condition: SmartUiVisibilityCondition | undefined, values: SmartUiStateValue[]) => boolean;
  draft: (document: SmartUiDocument, action: SmartUiAction | undefined, values: SmartUiStateValue[]) => string | null;
  reset: (document: SmartUiDocument, sourceKey: string) => SmartUiStateReconciliation;
}

/** State values are intentionally small because they are held by the chat page. */
export const SMART_UI_MAX_INPUT_VALUE_CHARS: number = 512;
export const SMART_UI_MAX_DRAFT_PROMPT_CHARS: number = 4096;

const TEMPLATE_PATTERN: RegExp = /\{\{([A-Za-z][A-Za-z0-9_-]{0,47})\}\}/g;

const isSameScalar = (left: SmartUiScalar, right: SmartUiScalar): boolean =>
  typeof left === typeof right && left === right;

const valueAt = (values: SmartUiStateValue[], stateId: string): SmartUiScalar | undefined => {
  for (const item of values) {
    if (item.id === stateId) return item.value;
  }
  return undefined;
};

const replaceValue = (values: SmartUiStateValue[], stateId: string, value: SmartUiScalar): SmartUiStateValue[] => {
  const next: SmartUiStateValue[] = [];
  let replaced: boolean = false;
  for (const item of values) {
    if (item.id === stateId) {
      next.push({ id: stateId, value });
      replaced = true;
    } else {
      next.push({ id: item.id, value: item.value });
    }
  }
  if (!replaced) next.push({ id: stateId, value });
  return next;
};

const stateFor = (document: SmartUiDocument, stateId: string): SmartUiState | undefined => {
  for (const state of document.states) {
    if (state.id === stateId) return state;
  }
  return undefined;
};

const hasInput = (document: SmartUiDocument, stateId: string): boolean => document.nodes.some((node: SmartUiNode): boolean =>
  node.type === 'input' && node.state === stateId);

const optionsFor = (document: SmartUiDocument, stateId: string): SmartUiNode[] => document.nodes.filter((node: SmartUiNode): boolean =>
  node.type === 'choice' && node.state === stateId);

const optionLabel = (document: SmartUiDocument, stateId: string, value: SmartUiScalar): string | null => {
  const choices: SmartUiNode[] = optionsFor(document, stateId);
  for (const choice of choices) {
    for (const option of choice.options ?? []) {
      if (isSameScalar(option.value, value)) return option.label;
    }
  }
  return null;
};

const scalarText = (document: SmartUiDocument, stateId: string, value: SmartUiScalar): string => {
  const control = document.nodes.find((node: SmartUiNode): boolean => node.state === stateId && node.component !== undefined);
  if (control !== undefined) return smartUiComponentDisplayText(control, value, document.nodes.filter((node: SmartUiNode): boolean => node.parent === control.id)) ?? '';
  const label: string | null = optionLabel(document, stateId, value);
  if (label !== null) return label;
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  return String(value);
};

const truncatePrompt = (text: string): string => {
  if (text.length <= SMART_UI_MAX_DRAFT_PROMPT_CHARS) return text;
  // Avoid returning half of a surrogate pair when a model emits emoji near the limit.
  let end: number = SMART_UI_MAX_DRAFT_PROMPT_CHARS;
  const code: number = text.charCodeAt(end - 1);
  if (code >= 0xd800 && code <= 0xdbff) end--;
  return text.slice(0, end);
};

export const limitSmartUiDraftPrompt = (text: string): string => truncatePrompt(text);

const inputValue = (value: SmartUiScalar): boolean => typeof value === 'string' && value.length <= SMART_UI_MAX_INPUT_VALUE_CHARS;

const valueIsValid = (document: SmartUiDocument, stateId: string, value: SmartUiScalar): boolean => {
  const state: SmartUiState | undefined = stateFor(document, stateId);
  if (state === undefined || typeof value !== state.valueType) return false;
  if (typeof value === 'number' && !Number.isFinite(value)) return false;
  if (typeof value === 'string' && value.length > SMART_UI_MAX_INPUT_VALUE_CHARS) return false;
  if (hasInput(document, stateId) && !inputValue(value)) return false;
  const control = document.nodes.find((node: SmartUiNode): boolean => node.state === stateId &&
    node.type === 'component' && node.component !== undefined && componentKindRequiresState(node.component.kind));
  // Empty required fields are valid editable drafts; only task submission requires an answer.
  if (control !== undefined) return smartUiComponentValueValid(control, value, document.nodes.filter((node: SmartUiNode): boolean => node.parent === control.id), false);
  const choices: SmartUiNode[] = optionsFor(document, stateId);
  if (choices.length === 0) return true;
  for (const choice of choices) {
    for (const option of choice.options ?? []) {
      if (isSameScalar(option.value, value)) return true;
    }
  }
  return false;
};

const invalidDiagnostic = (stateId: string, line: number = 0): SmartUiDiagnostic => ({
  code: 'invalid-state-value',
  message: `状态 ${stateId} 的值已不再匹配当前组件选项，请重新选择`,
  line,
  severity: 'warning',
});

const reconcile = (
  document: SmartUiDocument,
  sourceKey: string,
  currentValues: SmartUiStateValue[],
  initialValues: SmartUiStateValue[],
  accessor: SmartUiStateAccessor,
): SmartUiStateReconciliation => {
  const values: SmartUiStateValue[] = [];
  const invalidStateIds: string[] = [];
  const diagnostics: SmartUiDiagnostic[] = [];
  for (const state of document.states) {
    const current: SmartUiScalar | undefined = valueAt(currentValues, state.id);
    const stored: SmartUiScalar | undefined = accessor.get(sourceKey, state.id);
    const initial: SmartUiScalar | undefined = valueAt(initialValues, state.id);
    const value: SmartUiScalar = current ?? stored ?? initial ?? state.initial;
    values.push({ id: state.id, value });
    if (!valueIsValid(document, state.id, value)) {
      invalidStateIds.push(state.id);
      diagnostics.push(invalidDiagnostic(state.id));
    }
  }
  return { values, invalidStateIds, diagnostics };
};

const setValue = (
  document: SmartUiDocument,
  sourceKey: string,
  currentValues: SmartUiStateValue[],
  stateId: string,
  value: SmartUiScalar,
  accessor: SmartUiStateAccessor,
): SmartUiStateChange => {
  const values: SmartUiStateValue[] = replaceValue(currentValues, stateId, value);
  const accepted: boolean = valueIsValid(document, stateId, value);
  if (accepted) accessor.set(sourceKey, stateId, value);
  const reconciliation: SmartUiStateReconciliation = reconcile(document, sourceKey, values, [], accessor);
  return { accepted, stateId, values: reconciliation.values, invalidStateIds: reconciliation.invalidStateIds,
    diagnostics: accepted ? reconciliation.diagnostics : reconciliation.diagnostics.concat([invalidDiagnostic(stateId)]) };
};

const visibleWhen = (condition: SmartUiVisibilityCondition | undefined, values: SmartUiStateValue[]): boolean => {
  if (condition === undefined) return true;
  const current: SmartUiScalar | undefined = valueAt(values, condition.state);
  return current !== undefined && isSameScalar(current, condition.equals);
};

const draft = (
  document: SmartUiDocument,
  action: SmartUiAction | undefined,
  values: SmartUiStateValue[],
): string | null => {
  if (action === undefined || action.type !== 'draft') return null;
  if (smartUiContinuationSnapshot(document, values, (stateId: string, value: SmartUiScalar): boolean => valueIsValid(document, stateId, value)) === null) return null;
  let failed: boolean = false;
  TEMPLATE_PATTERN.lastIndex = 0;
  const result: string = action.text.replace(TEMPLATE_PATTERN, (_match: string, stateId: string): string => {
    const value: SmartUiScalar | undefined = valueAt(values, stateId);
    if (value === undefined || !valueIsValid(document, stateId, value) || document.nodes.some((node: SmartUiNode): boolean => node.state === stateId && node.component !== undefined && componentKindIsPresentationState(node.component.kind))) {
      failed = true;
      return '';
    }
    return scalarText(document, stateId, value);
  });
  return failed || result.length > SMART_UI_MAX_DRAFT_PROMPT_CHARS ? null : result;
};

export const createSmartUiController = (accessor: SmartUiStateAccessor): SmartUiController => ({
  reset: (document: SmartUiDocument, sourceKey: string): SmartUiStateReconciliation => {
    const values: SmartUiStateValue[] = document.states.map((state: SmartUiState): SmartUiStateValue => ({ id: state.id, value: state.initial }));
    for (const item of values) accessor.set(sourceKey, item.id, item.value);
    return reconcile(document, sourceKey, values, [], accessor);
  },
  reconcile: (
    document: SmartUiDocument,
    sourceKey: string,
    currentValues: SmartUiStateValue[],
    initialValues: SmartUiStateValue[],
  ): SmartUiStateReconciliation => reconcile(document, sourceKey, currentValues, initialValues, accessor),
  setValue: (
    document: SmartUiDocument,
    sourceKey: string,
    currentValues: SmartUiStateValue[],
    stateId: string,
    value: SmartUiScalar,
  ): SmartUiStateChange => setValue(document, sourceKey, currentValues, stateId, value, accessor),
  isValueValid: (document: SmartUiDocument, stateId: string, value: SmartUiScalar): boolean => valueIsValid(document, stateId, value),
  visibleWhen,
  draft,
});

/** Keep the user's text and append a generated prompt only once at the end. */
export const appendSmartUiDraft = (input: string, draftText: string): string => {
  const next: string = truncatePrompt(draftText);
  if (next.length === 0) return input;
  const trimmedInput: string = input.trimEnd();
  if (trimmedInput === next || trimmedInput.endsWith(`\n\n${next}`)) return input;
  if (input.length === 0) return next;
  if (input.endsWith('\n\n')) return `${input}${next}`;
  if (input.endsWith('\n')) return `${input}\n${next}`;
  return `${input}\n\n${next}`;
};
