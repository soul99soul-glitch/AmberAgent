import type { JsonObject, JsonValue } from '../json.ts';
import { utf8ByteLength } from '../miniapp/miniapp_models.ts';
import type { SmartUiComponent } from './components.ts';
import {
  componentKindAllowsChildren, componentKindRequiresState, componentKindHasOptions,
  componentKindIsMultiSelection, componentKindIsNumeric, componentKindIsPresentationState,
  smartUiComponentValueValid, validateSmartUiComponent,
} from './components.ts';

/** The first Smart UI protocol is deliberately smaller than SVG, HTML, or Three.js. */
export const SMART_UI_MAX_NODES: number = 64;
export const SMART_UI_MAX_DEPTH: number = 6;
export const SMART_UI_MAX_RECORDS: number = 256;
export const SMART_UI_MAX_LINE_BYTES: number = 16 * 1024;
export const SMART_UI_MAX_TOTAL_BYTES: number = 128 * 1024;
export const SMART_UI_MAX_STATES: number = 32;
export const SMART_UI_MAX_OPTIONS: number = 32;
export const SMART_UI_MAX_TEXT_CHARS: number = 4096;
export const SMART_UI_MAX_LABEL_CHARS: number = 120;
export const SMART_UI_MAX_ACTION_CHARS: number = 512;
export const SMART_UI_MAX_ID_CHARS: number = 48;
export const SMART_UI_MAX_STATE_VALUE_CHARS: number = 512;
export const SMART_UI_MAX_TEXT_BINDINGS: number = 16;

export type SmartUiScalar = string | number | boolean;
export type SmartUiNodeType = 'column' | 'text' | 'choice' | 'input' | 'button' | 'asset' | 'component';
export type SmartUiVariant = 'plain' | 'group' | 'outlined' | 'elevated' | 'callout' | 'section' |
  'body' | 'heading' | 'subheading' | 'caption' | 'metric' | 'code' | 'badge' | 'hero' | 'lead' | 'eyebrow' | 'quote' |
  'list' | 'chips' | 'tiles' | 'segmented' | 'primary' | 'secondary' | 'ghost' | 'pill' | 'underline';
export type SmartUiLayout = 'stack' | 'row' | 'grid';
export type SmartUiTone = 'neutral' | 'accent' | 'success' | 'warning' | 'info';
export type SmartUiValueType = 'string' | 'number' | 'boolean';
export type SmartUiDocumentStatus = 'partial' | 'ready' | 'error';
export type SmartUiDiagnosticSeverity = 'warning' | 'error';
export type SmartUiDiagnosticCode = string;

export interface SmartUiDiagnostic {
  code: SmartUiDiagnosticCode;
  message: string;
  line: number;
  severity: SmartUiDiagnosticSeverity;
}

export interface SmartUiState {
  id: string;
  initial: SmartUiScalar;
  valueType: SmartUiValueType;
}

export interface SmartUiOption {
  value: SmartUiScalar;
  label: string;
  detail?: string;
  color?: string;
}

export interface SmartUiVisibilityCondition {
  state: string;
  equals: SmartUiScalar;
}

export type SmartUiTextBindingPath = 'location' | 'temperature_celsius' | 'condition' | 'observed_at';

export interface SmartUiTextBinding {
  ref: string;
  path: SmartUiTextBindingPath;
}

export interface SmartUiDraftAction { type: 'draft'; text: string; }
export interface SmartUiResetAction { type: 'reset'; }
export type SmartUiAction = SmartUiDraftAction | SmartUiResetAction;

/** Flat node shape keeps the native renderer free from recursive JSON unions. */
export interface SmartUiNode {
  id: string;
  parent: string | null;
  type: SmartUiNodeType;
  variant?: SmartUiVariant;
  layout?: SmartUiLayout;
  tone?: SmartUiTone;
  text?: string;
  bind?: SmartUiTextBinding;
  state?: string;
  options?: SmartUiOption[];
  placeholder?: string;
  label?: string;
  action?: SmartUiAction;
  component?: SmartUiComponent;
  required?: boolean;
  ref?: string;
  visibleWhen?: SmartUiVisibilityCondition;
}

export interface SmartUiDocument {
  id: string;
  nodes: SmartUiNode[];
  states: SmartUiState[];
  status: SmartUiDocumentStatus;
  diagnostics: SmartUiDiagnostic[];
  ended: boolean;
  rootId: string | null;
  records: number;
}

export interface SmartUiNodeValidationResult {
  node: SmartUiNode | null;
  diagnostics: SmartUiDiagnostic[];
}

export interface SmartUiStateValidationResult {
  state: SmartUiState | null;
  diagnostics: SmartUiDiagnostic[];
}

const NODE_TYPES: SmartUiNodeType[] = ['column', 'text', 'choice', 'input', 'button', 'asset', 'component'];
const TEXT_BINDING_PATHS: SmartUiTextBindingPath[] = ['location', 'temperature_celsius', 'condition', 'observed_at'];
const ID_PATTERN: RegExp = /^[A-Za-z][A-Za-z0-9_-]{0,47}$/;
const TEMPLATE_PATTERN: RegExp = /\{\{([A-Za-z][A-Za-z0-9_-]{0,47})\}\}/g;

const isObject = (value: JsonValue | undefined): value is JsonObject =>
  value !== undefined && value !== null && typeof value === 'object' && !Array.isArray(value);

const isScalar = (value: JsonValue | undefined): value is SmartUiScalar =>
  (typeof value === 'string' || typeof value === 'boolean' ||
    (typeof value === 'number' && Number.isFinite(value)));

const scalarType = (value: SmartUiScalar): SmartUiValueType => {
  if (typeof value === 'string') return 'string';
  if (typeof value === 'boolean') return 'boolean';
  return 'number';
};

const scalarEqual = (left: SmartUiScalar, right: SmartUiScalar): boolean =>
  scalarType(left) === scalarType(right) && left === right;

const validId = (value: string): boolean => value.length > 0 && value.length <= SMART_UI_MAX_ID_CHARS && ID_PATTERN.test(value);

const truncateUtf8 = (text: string, maxBytes: number): string => {
  let bytes: number = 0;
  let end: number = 0;
  while (end < text.length) {
    const code: number = text.charCodeAt(end);
    let width: number = 1;
    let next: number = end + 1;
    if (code < 0x80) width = 1;
    else if (code < 0x800) width = 2;
    else if (code >= 0xd800 && code <= 0xdbff && end + 1 < text.length &&
      text.charCodeAt(end + 1) >= 0xdc00 && text.charCodeAt(end + 1) <= 0xdfff) {
      width = 4;
      next = end + 2;
    } else width = 3;
    if (bytes + width > maxBytes) break;
    bytes += width;
    end = next;
  }
  return text.slice(0, end);
};

const diagnostic = (
  code: SmartUiDiagnosticCode,
  message: string,
  line: number,
  severity: SmartUiDiagnosticSeverity = 'error',
): SmartUiDiagnostic => ({ code, message, line, severity });

const objectKeys = (value: JsonObject): string[] => Object.keys(value);

const checkAllowedKeys = (value: JsonObject, allowed: string[], line: number): SmartUiDiagnostic[] => {
  const diagnostics: SmartUiDiagnostic[] = [];
  for (const key of objectKeys(value)) {
    if (!allowed.includes(key)) {
      diagnostics.push(diagnostic('unsupported-field', `不支持字段 ${key}`, line));
    }
  }
  return diagnostics;
};

const requiredString = (
  value: JsonValue | undefined,
  field: string,
  line: number,
  min: number,
  max: number,
): { value: string | null; diagnostics: SmartUiDiagnostic[] } => {
  if (typeof value !== 'string' || value.length < min || value.length > max) {
    return { value: null, diagnostics: [diagnostic('invalid-field', `${field} 必须是 ${min}-${max} 个字符的字符串`, line)] };
  }
  return { value, diagnostics: [] };
};

const idField = (value: JsonValue | undefined, field: string, line: number): { value: string | null; diagnostics: SmartUiDiagnostic[] } => {
  if (typeof value !== 'string' || !validId(value)) {
    return { value: null, diagnostics: [diagnostic('invalid-id', `${field} 不是合法 ID`, line)] };
  }
  return { value, diagnostics: [] };
};

const append = (target: SmartUiDiagnostic[], source: SmartUiDiagnostic[]): void => {
  for (const item of source) target.push(item);
};

const parseVisibility = (value: JsonValue | undefined, line: number): { value: SmartUiVisibilityCondition | null; diagnostics: SmartUiDiagnostic[] } => {
  if (!isObject(value)) {
    return { value: null, diagnostics: [diagnostic('invalid-visible-when', 'visibleWhen 必须是对象', line)] };
  }
  const diagnostics: SmartUiDiagnostic[] = checkAllowedKeys(value, ['state', 'equals'], line);
  const stateResult = idField(value['state'], 'visibleWhen.state', line);
  append(diagnostics, stateResult.diagnostics);
  const equals: JsonValue | undefined = value['equals'];
  if (!isScalar(equals)) diagnostics.push(diagnostic('invalid-visible-when', 'visibleWhen.equals 必须是字符串、数字或布尔值', line));
  if (diagnostics.length > 0 || stateResult.value === null || !isScalar(equals)) {
    return { value: null, diagnostics };
  }
  return { value: { state: stateResult.value, equals }, diagnostics };
};

const parseTextBinding = (value: JsonValue | undefined, line: number): { value: SmartUiTextBinding | null; diagnostics: SmartUiDiagnostic[] } => {
  if (!isObject(value)) return { value: null, diagnostics: [diagnostic('invalid-bind', 'text.bind 必须是对象', line)] };
  const diagnostics: SmartUiDiagnostic[] = checkAllowedKeys(value, ['ref', 'path'], line);
  const refResult = idField(value['ref'], 'text.bind.ref', line);
  for (const item of refResult.diagnostics) diagnostics.push(diagnostic('invalid-bind-ref', item.message, item.line, item.severity));
  const path: JsonValue | undefined = value['path'];
  if (typeof path !== 'string' || !TEXT_BINDING_PATHS.includes(path as SmartUiTextBindingPath)) {
    diagnostics.push(diagnostic('invalid-bind-path', 'text.bind.path 不在受支持字段目录中', line));
  }
  if (diagnostics.length > 0 || refResult.value === null || typeof path !== 'string' ||
    !TEXT_BINDING_PATHS.includes(path as SmartUiTextBindingPath)) {
    return { value: null, diagnostics };
  }
  return { value: { ref: refResult.value, path: path as SmartUiTextBindingPath }, diagnostics };
};

const parseOptions = (value: JsonValue | undefined, line: number): { value: SmartUiOption[] | null; diagnostics: SmartUiDiagnostic[] } => {
  if (!Array.isArray(value) || value.length === 0 || value.length > SMART_UI_MAX_OPTIONS) {
    return { value: null, diagnostics: [diagnostic('invalid-options', `options 必须包含 1-${SMART_UI_MAX_OPTIONS} 项`, line)] };
  }
  const options: SmartUiOption[] = [];
  const diagnostics: SmartUiDiagnostic[] = [];
  for (let index: number = 0; index < value.length; index++) {
    const item: JsonValue = value[index];
    if (!isObject(item)) {
      diagnostics.push(diagnostic('invalid-option', `options[${index}] 必须是对象`, line));
      continue;
    }
    append(diagnostics, checkAllowedKeys(item, ['value', 'label', 'detail', 'color'], line));
    const label = requiredString(item['label'], `options[${index}].label`, line, 1, SMART_UI_MAX_LABEL_CHARS);
    append(diagnostics, label.diagnostics);
    if (label.value !== null && label.value.trim().length === 0) diagnostics.push(diagnostic('invalid-option-label', '选项标签不能仅包含空白', line));
    const optionValue: JsonValue | undefined = item['value'];
    if (!isScalar(optionValue)) diagnostics.push(diagnostic('invalid-option', `options[${index}].value 必须是标量`, line));
    if (label.value !== null && isScalar(optionValue)) {
      if (options.some((existing: SmartUiOption): boolean => scalarEqual(existing.value, optionValue))) {
        diagnostics.push(diagnostic('duplicate-option', `options[${index}] 的 value 重复`, line));
      } else {
        const option: SmartUiOption = { value: optionValue, label: label.value };
        if (item['detail'] !== undefined) {
          const detail = requiredString(item['detail'], 'option.detail', line, 0, 1000);
          append(diagnostics, detail.diagnostics);
          if (detail.value !== null) option.detail = detail.value;
        }
        if (item['color'] !== undefined) {
          const color = item['color'];
          if (typeof color !== 'string' || !['accent', 'red', 'orange', 'yellow', 'green', 'cyan', 'blue', 'indigo', 'purple', 'pink', 'gray'].includes(color)) diagnostics.push(diagnostic('invalid-option-color', '选项颜色不在原生颜色目录中', line));
          else option.color = color;
        }
        options.push(option);
      }
    }
  }
  return { value: diagnostics.length === 0 ? options : null, diagnostics };
};

const draftReferences = (text: string): string[] | null => {
  const references: string[] = [];
  let cursor: number = 0;
  TEMPLATE_PATTERN.lastIndex = 0;
  let match: RegExpExecArray | null = TEMPLATE_PATTERN.exec(text);
  while (match !== null) {
    if (text.slice(cursor, match.index).includes('{') || text.slice(cursor, match.index).includes('}')) return null;
    references.push(match[1]);
    cursor = match.index + match[0].length;
    match = TEMPLATE_PATTERN.exec(text);
  }
  if (text.slice(cursor).includes('{') || text.slice(cursor).includes('}')) return null;
  return references;
};

const parseAction = (value: JsonValue | undefined, line: number): { value: SmartUiAction | null; diagnostics: SmartUiDiagnostic[] } => {
  if (!isObject(value)) return { value: null, diagnostics: [diagnostic('invalid-action', 'button.action 必须是对象', line)] };
  if (value['type'] === 'reset') {
    const resetDiagnostics = checkAllowedKeys(value, ['type'], line);
    return { value: resetDiagnostics.length === 0 ? { type: 'reset' } : null, diagnostics: resetDiagnostics };
  }
  const diagnostics: SmartUiDiagnostic[] = checkAllowedKeys(value, ['type', 'text'], line);
  if (value['type'] !== 'draft') diagnostics.push(diagnostic('invalid-action', 'button 只支持 draft 动作', line));
  const text = requiredString(value['text'], 'button.action.text', line, 1, SMART_UI_MAX_ACTION_CHARS);
  append(diagnostics, text.diagnostics);
  if (text.value !== null && draftReferences(text.value) === null) {
    diagnostics.push(diagnostic('invalid-action', 'button.action.text 只允许 {{state_id}} 占位符', line));
  }
  return diagnostics.length === 0 && text.value !== null
    ? { value: { type: 'draft', text: text.value }, diagnostics }
    : { value: null, diagnostics };
};

const nodeAllowedKeys = (type: SmartUiNodeType): string[] => {
  const common: string[] = ['op', 'id', 'parent', 'type', 'visibleWhen'];
  if (type === 'component') return common.concat(['component', 'state', 'options', 'action', 'label', 'required']);
  if (type === 'column') return common.concat(['variant', 'layout', 'tone']);
  if (type === 'text') return common.concat(['text', 'bind', 'variant', 'tone']);
  if (type === 'choice') return common.concat(['state', 'options', 'variant', 'label', 'required']);
  if (type === 'input') return common.concat(['state', 'placeholder', 'variant', 'label', 'required']);
  if (type === 'button') return common.concat(['label', 'action', 'variant']);
  return common.concat(['ref', 'label']);
};

const nodeVariants = (type: SmartUiNodeType): SmartUiVariant[] => {
  if (type === 'column') return ['plain', 'group', 'outlined', 'elevated', 'callout', 'section'];
  if (type === 'text') return ['body', 'heading', 'subheading', 'caption', 'metric', 'code', 'badge',
    'hero', 'lead', 'eyebrow', 'quote'];
  if (type === 'choice') return ['list', 'chips', 'tiles', 'segmented'];
  if (type === 'button') return ['primary', 'secondary', 'ghost', 'pill'];
  if (type === 'input') return ['underline'];
  return [];
};

/** Validate one complete node record without relying on document order or state maps. */
export const validateSmartUiNode = (record: JsonObject, line: number = 0): SmartUiNodeValidationResult => {
  const diagnostics: SmartUiDiagnostic[] = [];
  if (record['op'] !== 'node') {
    diagnostics.push(diagnostic('invalid-record', '记录 op 必须是 node', line));
    return { node: null, diagnostics };
  }
  const typeValue: JsonValue | undefined = record['type'];
  if (typeof typeValue !== 'string' || !NODE_TYPES.includes(typeValue as SmartUiNodeType)) {
    diagnostics.push(diagnostic('invalid-node-type', 'node.type 不在受支持目录中', line));
    return { node: null, diagnostics };
  }
  const type: SmartUiNodeType = typeValue as SmartUiNodeType;
  append(diagnostics, checkAllowedKeys(record, nodeAllowedKeys(type), line));
  const idResult = idField(record['id'], 'node.id', line);
  append(diagnostics, idResult.diagnostics);
  let parent: string | null = null;
  const parentValue: JsonValue | undefined = record['parent'];
  if (parentValue !== undefined && parentValue !== null) {
    const parentResult = idField(parentValue, 'node.parent', line);
    append(diagnostics, parentResult.diagnostics);
    parent = parentResult.value;
  }
  let visibleWhen: SmartUiVisibilityCondition | undefined;
  if (record['visibleWhen'] !== undefined) {
    const visibleResult = parseVisibility(record['visibleWhen'], line);
    append(diagnostics, visibleResult.diagnostics);
    if (visibleResult.value !== null) visibleWhen = visibleResult.value;
  }
  if (idResult.value === null) return { node: null, diagnostics };
  const node: SmartUiNode = { id: idResult.value, parent, type };
  if (record['required'] !== undefined) {
    if (typeof record['required'] !== 'boolean') diagnostics.push(diagnostic('invalid-required', 'node.required 必须是布尔值', line));
    else node.required = record['required'];
  }
  if (record['label'] !== undefined && (type === 'component' || type === 'choice' || type === 'input')) {
    const label = requiredString(record['label'], 'node.label', line, 1, SMART_UI_MAX_LABEL_CHARS);
    append(diagnostics, label.diagnostics);
    if (label.value !== null && label.value.trim().length > 0) node.label = label.value;
    else diagnostics.push(diagnostic('invalid-label', '控件标签不能仅包含空白', line));
  }
  if (visibleWhen !== undefined) node.visibleWhen = visibleWhen;
  const variant: JsonValue | undefined = record['variant'];
  if (variant !== undefined) {
    if (typeof variant !== 'string' || !nodeVariants(type).includes(variant as SmartUiVariant)) {
      diagnostics.push(diagnostic('invalid-variant', `variant 不适用于 ${type}`, line));
    } else {
      node.variant = variant as SmartUiVariant;
    }
  }
  const layout: JsonValue | undefined = record['layout'];
  if (layout !== undefined) {
    if (type !== 'column' || (layout !== 'stack' && layout !== 'row' && layout !== 'grid')) {
      diagnostics.push(diagnostic('invalid-layout', 'layout 只支持 column 的 stack、row、grid', line));
    } else {
      node.layout = layout;
    }
  }
  const tone: JsonValue | undefined = record['tone'];
  if (tone !== undefined) {
    if ((type !== 'column' && type !== 'text') ||
      (tone !== 'neutral' && tone !== 'accent' && tone !== 'success' && tone !== 'warning' && tone !== 'info')) {
      diagnostics.push(diagnostic('invalid-tone', 'tone 只支持 column/text 的语义色', line));
    } else {
      node.tone = tone;
    }
  }

  if (type === 'component') {
    const result = validateSmartUiComponent(record['component']);
    for (const message of result.errors) diagnostics.push(diagnostic('invalid-component', message, line));
    if (result.component !== null) {
      node.component = result.component;
      const kind = result.component.kind;
      if (componentKindRequiresState(kind)) {
        const stateResult = idField(record['state'], 'component.state', line);
        append(diagnostics, stateResult.diagnostics);
        if (stateResult.value !== null) node.state = stateResult.value;
      } else if (record['state'] !== undefined) diagnostics.push(diagnostic('unexpected-component-state', `${kind} 不接受交互状态`, line));
      if (record['options'] !== undefined) {
        const options = parseOptions(record['options'], line);
        append(diagnostics, options.diagnostics);
        if (options.value !== null) node.options = options.value;
        if (!componentKindHasOptions(kind)) diagnostics.push(diagnostic('unexpected-options', `${kind} 不接受选项`, line));
      }
      if (componentKindHasOptions(kind) && (node.options?.length ?? 0) === 0) {
        if (kind !== 'checklist' || (result.component.items?.length ?? 0) === 0) diagnostics.push(diagnostic('component-options-required', `${kind} 需要 options`, line));
      }
      if (componentKindIsMultiSelection(kind) && (node.options ?? []).some((option: SmartUiOption): boolean => typeof option.value !== 'string' || option.value.length === 0 || option.value.length > 120)) diagnostics.push(diagnostic('multi-option-not-string', '多选选项必须使用 1-120 字符的字符串 ID', line));
      if (kind === 'checklist' && (node.options?.length ?? 0) === 0 && result.component.items !== undefined) {
        const items = result.component.items;
        if (new Set(items).size !== items.length || items.some((item: string): boolean => item.length === 0 || item.length > 120)) diagnostics.push(diagnostic('invalid-checklist-items', '清单 items 必须唯一且有 1-120 字符的标签', line));
      }
      if (node.required === true && (!componentKindRequiresState(kind) || componentKindIsPresentationState(kind))) diagnostics.push(diagnostic('unexpected-required', '只有任务输入字段可以标为必填', line));
      if (record['action'] !== undefined) {
        const action = parseAction(record['action'], line);
        append(diagnostics, action.diagnostics);
        if (action.value !== null) node.action = action.value;
        if (!['button', 'submitButton', 'resetButton'].includes(kind) || (kind === 'submitButton' && node.action?.type !== 'draft') || (kind === 'resetButton' && node.action?.type !== 'reset')) diagnostics.push(diagnostic('invalid-component-action', '组件按钮只能生成继续草稿或重置本卡状态', line));
      }
      if ((kind === 'button' || kind === 'submitButton') && node.action === undefined) diagnostics.push(diagnostic('component-action-required', `${kind} 必须声明 draft 或 reset 动作`, line));
      if (componentKindRequiresState(kind) && (node.label?.trim().length ?? 0) === 0 && (result.component.title?.trim().length ?? 0) === 0) diagnostics.push(diagnostic('component-label-required', '交互组件需要 label 或 title 标签', line));
    }
  } else if (type === 'text') {
    const text = requiredString(record['text'], 'node.text', line, 0, SMART_UI_MAX_TEXT_CHARS);
    append(diagnostics, text.diagnostics);
    if (text.value !== null) node.text = text.value;
    if (record['bind'] !== undefined) {
      const binding = parseTextBinding(record['bind'], line);
      append(diagnostics, binding.diagnostics);
      if (binding.value !== null) node.bind = binding.value;
    }
  } else if (type === 'choice') {
    const stateResult = idField(record['state'], 'choice.state', line);
    append(diagnostics, stateResult.diagnostics);
    const options = parseOptions(record['options'], line);
    append(diagnostics, options.diagnostics);
    if (stateResult.value !== null) node.state = stateResult.value;
    if (options.value !== null) node.options = options.value;
  } else if (type === 'input') {
    const stateResult = idField(record['state'], 'input.state', line);
    append(diagnostics, stateResult.diagnostics);
    if (stateResult.value !== null) node.state = stateResult.value;
    if (record['placeholder'] !== undefined) {
      const placeholder = requiredString(record['placeholder'], 'input.placeholder', line, 0, SMART_UI_MAX_LABEL_CHARS);
      append(diagnostics, placeholder.diagnostics);
      if (placeholder.value !== null) node.placeholder = placeholder.value;
    }
  } else if (type === 'button') {
    const label = requiredString(record['label'], 'button.label', line, 1, SMART_UI_MAX_LABEL_CHARS);
    append(diagnostics, label.diagnostics);
    const action = parseAction(record['action'], line);
    append(diagnostics, action.diagnostics);
    if (label.value !== null) node.label = label.value;
    if (action.value !== null) node.action = action.value;
  } else if (type === 'asset') {
    const ref = idField(record['ref'], 'asset.ref', line);
    for (const item of ref.diagnostics) diagnostics.push(diagnostic('invalid-ref', item.message, item.line, item.severity));
    if (ref.value !== null) node.ref = ref.value;
    if (record['label'] !== undefined) {
      const label = requiredString(record['label'], 'asset.label', line, 1, SMART_UI_MAX_LABEL_CHARS);
      append(diagnostics, label.diagnostics);
      if (label.value !== null) node.label = label.value;
    }
  }
  return { node: diagnostics.length === 0 ? node : null, diagnostics };
};

/** Validate one state declaration. State values intentionally exclude null, arrays, and objects. */
export const validateSmartUiState = (record: JsonObject, line: number = 0): SmartUiStateValidationResult => {
  const diagnostics: SmartUiDiagnostic[] = [];
  if (record['op'] !== 'state') {
    return { state: null, diagnostics: [diagnostic('invalid-record', '记录 op 必须是 state', line)] };
  }
  append(diagnostics, checkAllowedKeys(record, ['op', 'id', 'initial'], line));
  const idResult = idField(record['id'], 'state.id', line);
  append(diagnostics, idResult.diagnostics);
  const initial: JsonValue | undefined = record['initial'];
  if (!isScalar(initial)) diagnostics.push(diagnostic('invalid-state-value', 'state.initial 必须是字符串、数字或布尔值', line));
  if (typeof initial === 'string' && initial.length > SMART_UI_MAX_STATE_VALUE_CHARS) {
    diagnostics.push(diagnostic('state-value-limit', `state.initial 字符串超过 ${SMART_UI_MAX_STATE_VALUE_CHARS} 字符限制`, line));
  }
  if (diagnostics.length > 0 || idResult.value === null || !isScalar(initial)) return { state: null, diagnostics };
  return { state: { id: idResult.value, initial, valueType: scalarType(initial) }, diagnostics };
};

const depthOf = (node: SmartUiNode, nodes: Map<string, SmartUiNode>): number => {
  let depth: number = 1;
  let parent: string | null = node.parent;
  const seen = new Set<string>();
  while (parent !== null) {
    if (seen.has(parent)) return SMART_UI_MAX_DEPTH + 1;
    seen.add(parent);
    const parentNode: SmartUiNode | undefined = nodes.get(parent);
    if (parentNode === undefined) return SMART_UI_MAX_DEPTH + 1;
    depth++;
    parent = parentNode.parent;
  }
  return depth;
};

const checkNodeReferences = (
  node: SmartUiNode,
  line: number,
  states: Map<string, SmartUiState>,
): SmartUiDiagnostic[] => {
  const diagnostics: SmartUiDiagnostic[] = [];
  const stateId: string | undefined = node.state;
  if (stateId !== undefined) {
    const state: SmartUiState | undefined = states.get(stateId);
    if (state === undefined) {
      diagnostics.push(diagnostic('unknown-state', `找不到状态 ${stateId}`, line));
    } else if (node.type === 'component' && node.component !== undefined) {
      const component = node.component;
      if (component.kind === 'toggle' || component.kind === 'disclosure') {
        if (state.valueType !== 'boolean') diagnostics.push(diagnostic('component-state-type', `${component.kind} 必须绑定布尔状态`, line));
      } else if (componentKindIsNumeric(component.kind)) {
        if (state.valueType !== 'number') diagnostics.push(diagnostic('component-state-type', `${component.kind} 必须绑定数字状态`, line));
      } else if (!componentKindHasOptions(component.kind) || componentKindIsMultiSelection(component.kind)) {
        if (state.valueType !== 'string') diagnostics.push(diagnostic('component-state-type', `${component.kind} 必须绑定字符串状态`, line));
      } else if ((node.options ?? []).some((option: SmartUiOption): boolean => scalarType(option.value) !== state.valueType)) {
        diagnostics.push(diagnostic('state-type-mismatch', '组件选项值类型与状态声明不一致', line));
      }
      // Container selections reference children declared later in the flat stream.
      if (component.kind !== 'tabs' && component.kind !== 'accordion' && !smartUiComponentValueValid(node, state.initial, [], false)) diagnostics.push(diagnostic('component-initial-invalid', `${component.kind} 初始值不匹配组件值域`, line));
    } else if (node.type === 'input') {
      if (state.valueType !== 'string') diagnostics.push(diagnostic('input-state-not-string', `input 只能绑定字符串状态 ${stateId}`, line));
    } else if (node.type === 'choice' && node.options !== undefined) {
      let hasInitial: boolean = false;
      for (const option of node.options) {
        if (scalarType(option.value) !== state.valueType) {
          diagnostics.push(diagnostic('state-type-mismatch', `选项 ${option.label} 的值类型与状态 ${stateId} 不一致`, line));
          break;
        }
        if (scalarEqual(option.value, state.initial)) hasInitial = true;
      }
      if (!hasInitial) diagnostics.push(diagnostic('choice-initial-not-option', `状态 ${stateId} 的初始值不在 choice 选项中`, line));
    }
  }
  if (node.visibleWhen !== undefined) {
    const state: SmartUiState | undefined = states.get(node.visibleWhen.state);
    if (state === undefined) diagnostics.push(diagnostic('unknown-state', `找不到状态 ${node.visibleWhen.state}`, line));
    else if (scalarType(node.visibleWhen.equals) !== state.valueType) {
      diagnostics.push(diagnostic('state-type-mismatch', `visibleWhen.equals 与状态 ${state.id} 类型不一致`, line));
    }
  }
  if (node.action?.type === 'draft') {
    const references: string[] | null = draftReferences(node.action.text);
    if (references === null) diagnostics.push(diagnostic('invalid-action', 'button.action.text 占位符无效', line));
    else {
      for (const reference of references) {
        if (!states.has(reference)) diagnostics.push(diagnostic('unknown-state', `找不到状态 ${reference}`, line));
      }
    }
  }
  return diagnostics;
};

const appendNodeSemanticDiagnostics = (
  target: SmartUiDiagnostic[],
  source: SmartUiDiagnostic[],
  forwardStateCode: boolean,
): void => {
  for (const item of source) {
    if (forwardStateCode && item.code === 'unknown-state') {
      target.push(diagnostic('state-after-dependent-node', item.message, item.line, item.severity));
    } else {
      target.push(item);
    }
  }
};

const controlStateConflict = (candidate: SmartUiNode, nodes: SmartUiNode[]): SmartUiNode | null => {
  if (!nodeControlsState(candidate) || candidate.state === undefined) return null;
  for (const node of nodes) {
    if (node.id !== candidate.id && nodeControlsState(node) && node.state === candidate.state) {
      return node;
    }
  }
  return null;
};

const nodeControlsState = (node: SmartUiNode): boolean => node.type === 'choice' || node.type === 'input' ||
  (node.type === 'component' && node.component !== undefined && componentKindRequiresState(node.component.kind));

const nodeAllowsChildren = (node: SmartUiNode | undefined): boolean => node !== undefined &&
  (node.type === 'column' || (node.type === 'component' && node.component !== undefined && componentKindAllowsChildren(node.component.kind)));

const emptyDocument = (id: string, diagnostics: SmartUiDiagnostic[], records: number = 0): SmartUiDocument => ({
  id,
  nodes: [],
  states: [],
  status: 'error',
  diagnostics,
  ended: false,
  rootId: null,
  records,
});

/**
 * Parse the body of one `amber-ui v=1 id=...` fence.
 * Only complete lines are consumed during streaming. The final unterminated line is consumed
 * only after the fence closes or the owning message reaches a terminal state.
 */
export const parseSmartUi = (body: string, id: string, closedOrTerminal: boolean): SmartUiDocument => {
  if (!validId(id)) return emptyDocument(id, [diagnostic('invalid-block-id', '智能 UI 块 ID 不合法', 0)]);
  const diagnostics: SmartUiDiagnostic[] = [];
  let source: string = body;
  if (utf8ByteLength(source) > SMART_UI_MAX_TOTAL_BYTES) {
    diagnostics.push(diagnostic('total-limit', `块内容超过 ${SMART_UI_MAX_TOTAL_BYTES} 字节限制`, 0));
    source = truncateUtf8(source, SMART_UI_MAX_TOTAL_BYTES);
  }
  const nodes: SmartUiNode[] = [];
  const states: SmartUiState[] = [];
  const nodeMap = new Map<string, SmartUiNode>();
  const stateMap = new Map<string, SmartUiState>();
  let rootId: string | null = null;
  let ended: boolean = false;
  let records: number = 0;
  let hadNonEmptyLine: boolean = false;
  const rawLines: string[] = source.split('\n');
  const hasTrailingNewline: boolean = source.endsWith('\n');
  const trailing: string = hasTrailingNewline ? '' : (rawLines.pop() ?? '');
  if (!hasTrailingNewline && trailing.trim().length > 0 && !closedOrTerminal) {
    const lineNumber: number = rawLines.length + 1;
    diagnostics.push(diagnostic('incomplete-line', '等待完整的 NDJSON 行', lineNumber, 'warning'));
  } else if (!hasTrailingNewline && trailing.trim().length > 0) {
    rawLines.push(trailing);
  }

  const textBindingCount = (): number => nodes.filter((node: SmartUiNode): boolean => node.bind !== undefined).length;

  const addNode = (candidate: SmartUiNode, line: number): void => {
    const existing: SmartUiNode | undefined = nodeMap.get(candidate.id);
    if (existing === undefined) {
      if (candidate.parent === null) {
        if (candidate.type !== 'column') {
          diagnostics.push(diagnostic('root-not-column', 'root 节点必须是 column', line));
          return;
        }
        if (rootId !== null) {
          diagnostics.push(diagnostic('duplicate-root', '智能 UI 只能有一个 root 节点', line));
          return;
        }
      } else if (!nodeMap.has(candidate.parent)) {
        diagnostics.push(diagnostic('parent-before-child', `父节点 ${candidate.parent} 必须先声明`, line));
        return;
      } else if (!nodeAllowsChildren(nodeMap.get(candidate.parent))) {
        diagnostics.push(diagnostic('parent-not-column', `父节点 ${candidate.parent} 必须是 column`, line));
        return;
      }
      if (nodes.length >= SMART_UI_MAX_NODES) {
        diagnostics.push(diagnostic('node-limit', `节点数超过 ${SMART_UI_MAX_NODES} 限制`, line));
        return;
      }
      if (depthOf(candidate, nodeMap) > SMART_UI_MAX_DEPTH) {
        diagnostics.push(diagnostic('depth-limit', `嵌套深度超过 ${SMART_UI_MAX_DEPTH} 限制`, line));
        return;
      }
      const semanticDiagnostics: SmartUiDiagnostic[] = checkNodeReferences(candidate, line, stateMap);
      if (semanticDiagnostics.length > 0) {
        appendNodeSemanticDiagnostics(diagnostics, semanticDiagnostics, true);
        return;
      }
      const newControlConflict: SmartUiNode | null = controlStateConflict(candidate, nodes);
      if (newControlConflict !== null) {
        diagnostics.push(diagnostic('state-control-duplicate', `状态 ${candidate.state} 已绑定控件 ${newControlConflict.id}`, line));
        return;
      }
      if (candidate.bind !== undefined && textBindingCount() >= SMART_UI_MAX_TEXT_BINDINGS) {
        diagnostics.push(diagnostic('binding-limit', `text.bind 数超过 ${SMART_UI_MAX_TEXT_BINDINGS} 限制`, line));
        return;
      }
      if (candidate.parent === null) rootId = candidate.id;
      nodes.push(candidate);
      nodeMap.set(candidate.id, candidate);
      return;
    }
    if (candidate.type !== existing.type) {
      diagnostics.push(diagnostic('node-type-change', `节点 ${candidate.id} 不能改变 type`, line));
      return;
    }
    if (candidate.component?.kind !== existing.component?.kind) {
      diagnostics.push(diagnostic('component-kind-change', `节点 ${candidate.id} 不能改变原生组件 kind`, line));
      return;
    }
    if (candidate.parent !== existing.parent) {
      diagnostics.push(diagnostic('node-parent-change', `节点 ${candidate.id} 不能改变 parent`, line));
      return;
    }
    if (candidate.parent !== null && !nodeMap.has(candidate.parent)) {
      diagnostics.push(diagnostic('parent-before-child', `父节点 ${candidate.parent} 必须先声明`, line));
      return;
    }
    if (candidate.parent !== null && !nodeAllowsChildren(nodeMap.get(candidate.parent))) {
      diagnostics.push(diagnostic('parent-not-column', `父节点 ${candidate.parent} 必须是 column`, line));
      return;
    }
    const semanticDiagnostics: SmartUiDiagnostic[] = checkNodeReferences(candidate, line, stateMap);
    if (semanticDiagnostics.length > 0) {
      appendNodeSemanticDiagnostics(diagnostics, semanticDiagnostics, false);
      return;
    }
    const updateControlConflict: SmartUiNode | null = controlStateConflict(candidate, nodes);
    if (updateControlConflict !== null) {
      diagnostics.push(diagnostic('state-control-duplicate', `状态 ${candidate.state} 已绑定控件 ${updateControlConflict.id}`, line));
      return;
    }
    if (candidate.bind !== undefined && existing.bind === undefined && textBindingCount() >= SMART_UI_MAX_TEXT_BINDINGS) {
      diagnostics.push(diagnostic('binding-limit', `text.bind 数超过 ${SMART_UI_MAX_TEXT_BINDINGS} 限制`, line));
      return;
    }
    if (existing.state !== undefined && candidate.state !== undefined) {
      const oldState: SmartUiState | undefined = stateMap.get(existing.state);
      const nextState: SmartUiState | undefined = stateMap.get(candidate.state);
      if (oldState !== undefined && nextState !== undefined && oldState.valueType !== nextState.valueType) {
        diagnostics.push(diagnostic('state-type-change', `节点 ${candidate.id} 不能改变状态值类型`, line));
        return;
      }
    }
    if (depthOf(candidate, nodeMap) > SMART_UI_MAX_DEPTH) {
      diagnostics.push(diagnostic('depth-limit', `嵌套深度超过 ${SMART_UI_MAX_DEPTH} 限制`, line));
      return;
    }
    const position: number = nodes.findIndex((item: SmartUiNode): boolean => item.id === candidate.id);
    if (position >= 0) nodes[position] = candidate;
    nodeMap.set(candidate.id, candidate);
  };

  const processRecord = (record: JsonObject, line: number): void => {
    if (ended) {
      diagnostics.push(diagnostic('after-end', 'end 之后不允许继续声明记录', line));
      return;
    }
    const op: JsonValue | undefined = record['op'];
    if (op === 'state') {
      const result = validateSmartUiState(record, line);
      append(diagnostics, result.diagnostics);
      if (result.state === null) return;
      const existing: SmartUiState | undefined = stateMap.get(result.state.id);
      if (existing !== undefined) {
        if (existing.valueType !== result.state.valueType || !scalarEqual(existing.initial, result.state.initial)) {
          diagnostics.push(diagnostic('duplicate-state', `状态 ${result.state.id} 不能覆盖既有声明`, line));
        }
        return;
      }
      if (states.length >= SMART_UI_MAX_STATES) {
        diagnostics.push(diagnostic('state-limit', `状态数超过 ${SMART_UI_MAX_STATES} 限制`, line));
        return;
      }
      states.push(result.state);
      stateMap.set(result.state.id, result.state);
      return;
    }
    if (op === 'node') {
      const result = validateSmartUiNode(record, line);
      append(diagnostics, result.diagnostics);
      if (result.node !== null) addNode(result.node, line);
      return;
    }
    if (op === 'end') {
      const endDiagnostics: SmartUiDiagnostic[] = checkAllowedKeys(record, ['op'], line);
      append(diagnostics, endDiagnostics);
      ended = true;
      if (rootId === null) diagnostics.push(diagnostic('end-before-root', 'end 之前必须声明 root 节点', line));
      return;
    }
    diagnostics.push(diagnostic('unsupported-op', '只支持 state、node、end 记录', line));
  };

  for (let index: number = 0; index < rawLines.length; index++) {
    let lineText: string = rawLines[index];
    if (lineText.endsWith('\r')) lineText = lineText.slice(0, -1);
    if (lineText.trim().length === 0) continue;
    hadNonEmptyLine = true;
    const line: number = index + 1;
    if (utf8ByteLength(lineText) > SMART_UI_MAX_LINE_BYTES) {
      diagnostics.push(diagnostic('line-limit', `单行超过 ${SMART_UI_MAX_LINE_BYTES} 字节限制`, line));
      continue;
    }
    if (records >= SMART_UI_MAX_RECORDS) {
      diagnostics.push(diagnostic('record-limit', `记录数超过 ${SMART_UI_MAX_RECORDS} 限制`, line));
      break;
    }
    records++;
    let parsed: JsonValue;
    try {
      parsed = JSON.parse(lineText) as JsonValue;
    } catch (_error) {
      diagnostics.push(diagnostic('invalid-json', '记录不是完整有效的 JSON', line));
      continue;
    }
    if (!isObject(parsed)) {
      diagnostics.push(diagnostic('invalid-record', 'NDJSON 记录必须是 JSON 对象', line));
      continue;
    }
    processRecord(parsed, line);
  }

  if (!hasTrailingNewline && trailing.trim().length > 0 && !closedOrTerminal && ended) {
    diagnostics.push(diagnostic('after-end', 'end 之后存在未完成记录', rawLines.length + 1));
  }

  if (closedOrTerminal && !ended) diagnostics.push(diagnostic('missing-end', '终态智能 UI 缺少 end 记录', rawLines.length));
  if (closedOrTerminal && !ended && rootId === null) diagnostics.push(diagnostic('missing-root', '终态智能 UI 缺少 root 节点', rawLines.length));
  if (ended) {
    for (const node of nodes) {
      if (node.action?.type === 'draft') {
        const refs = draftReferences(node.action.text) ?? [];
        if (refs.some((stateId: string): boolean => nodes.some((control: SmartUiNode): boolean => control.state === stateId && control.component !== undefined && componentKindIsPresentationState(control.component.kind)))) diagnostics.push(diagnostic('presentation-state-in-action', '继续草稿不能引用仅用于折叠或标签页的展示状态', 0));
      }
      const component = node.component;
      if (component === undefined) continue;
      const children = nodes.filter((child: SmartUiNode): boolean => child.parent === node.id);
      if (component.kind === 'tabs' || component.kind === 'accordion') {
        if (children.length === 0 || children.some((child: SmartUiNode): boolean => (child.component?.title?.trim().length ?? child.label?.trim().length ?? 0) === 0)) diagnostics.push(diagnostic('component-child-label', 'tabs/accordion 需要有标题的子组件', 0));
        const state = states.find((item: SmartUiState): boolean => item.id === node.state);
        if (state !== undefined && !smartUiComponentValueValid(node, state.initial, children, false)) diagnostics.push(diagnostic('component-initial-invalid', '展示状态初始值不匹配子组件', 0));
      }
      if (component.kind === 'decisionSummary' && (component.items ?? []).some((stateId: string): boolean => !stateMap.has(stateId))) diagnostics.push(diagnostic('component-summary-field', '摘要只能引用已声明字段', 0));
    }
  }
  const hasError: boolean = diagnostics.some((item: SmartUiDiagnostic): boolean => item.severity === 'error');
  const status: SmartUiDocumentStatus = ended && !hasError ? 'ready' : hasError && (closedOrTerminal || ended) ? 'error' : 'partial';
  return { id, nodes, states, status, diagnostics, ended, rootId, records };
};
