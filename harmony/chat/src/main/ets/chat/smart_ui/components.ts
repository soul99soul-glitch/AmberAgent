import type { JsonObject, JsonValue } from '../json.ts';
import type { SmartUiNode, SmartUiScalar } from './protocol.ts';

/** Finite native views: component data never carries executable markup or expressions. */
export const SMART_UI_COMPONENT_KINDS = [
  'stack', 'column', 'row', 'grid', 'card', 'panel', 'group', 'inset', 'divider', 'spacer',
  'title', 'subtitle', 'body', 'caption', 'callout', 'quote', 'inlineCode', 'bulletList', 'numberedList', 'keyValue', 'footnote', 'highlight',
  'badge', 'chip', 'tag', 'statusDot', 'progress', 'stepProgress', 'skeleton', 'emptyState', 'notice', 'alert',
  'singleChoice', 'multiChoice', 'segmented', 'picker', 'toggle', 'textField', 'textArea', 'numberField', 'slider', 'stepper', 'datePicker', 'timePicker', 'rating', 'colorChoice',
  'metric', 'metricGrid', 'table', 'list', 'comparison', 'timeline', 'steps', 'checklist', 'barChart', 'lineChart',
  'image', 'imageGrid', 'avatar', 'icon', 'linkCard', 'fileCard', 'codeBlock', 'diagram',
  'goal', 'context', 'source', 'reason', 'tradeoff', 'result', 'assumptions', 'decisionSummary', 'study',
  'button', 'buttonGroup', 'submitButton', 'resetButton', 'disclosure', 'tabs', 'accordion', 'menu',
] as const;
export type SmartUiComponentKind = typeof SMART_UI_COMPONENT_KINDS[number];
export type SmartUiComponentTone = 'neutral' | 'accent' | 'success' | 'warning' | 'danger';
export type SmartUiComponentEmphasis = 'plain' | 'soft' | 'outlined' | 'filled';
export type SmartUiComponentDensity = 'compact' | 'comfortable';
export type SmartUiComponentAlignment = 'leading' | 'center';
export type SmartUiComponentSpace = 'none' | 'small' | 'medium' | 'large';

export interface SmartUiComponentStyle {
  tone?: SmartUiComponentTone;
  emphasis?: SmartUiComponentEmphasis;
  density?: SmartUiComponentDensity;
  alignment?: SmartUiComponentAlignment;
  spacing?: SmartUiComponentSpace;
  padding?: SmartUiComponentSpace;
}

export interface SmartUiComponentRow {
  label: string;
  value?: number;
  // 数字输入状态 id；有值时优先读当前状态，实现输入→指标/图表联动。
  valueFieldId?: string;
  detail?: string;
  cells?: string[];
}

export interface SmartUiStudyOption {
  id: string;
  label: string;
}

export interface SmartUiStudyItem {
  id: string;
  prompt: string;
  answer: string;
  detail?: string;
  options?: SmartUiStudyOption[];
  correctOptionId?: string;
}

export interface SmartUiComponent {
  kind: SmartUiComponentKind;
  title?: string;
  text?: string;
  items?: string[];
  rows?: SmartUiComponentRow[];
  study?: SmartUiStudyItem[];
  value?: number;
  // metric/progress/stepProgress 的数字状态 id；与 rows[].valueFieldId 同义。
  valueFieldId?: string;
  min?: number;
  max?: number;
  step?: number;
  columns?: number;
  symbol?: string;
  url?: string;
  style?: SmartUiComponentStyle;
}

/** Artifact snapshots must not share mutable row/item/style arrays with the live definition. */
export const cloneSmartUiComponent = (component: SmartUiComponent): SmartUiComponent => ({
  kind: component.kind, title: component.title, text: component.text,
  items: component.items?.slice(),
  rows: component.rows?.map((row: SmartUiComponentRow): SmartUiComponentRow => ({
    label: row.label, value: row.value, valueFieldId: row.valueFieldId,
    detail: row.detail, cells: row.cells?.slice(),
  })),
  study: component.study?.map((item: SmartUiStudyItem): SmartUiStudyItem => ({
    id: item.id, prompt: item.prompt, answer: item.answer, detail: item.detail,
    options: item.options?.map((option: SmartUiStudyOption): SmartUiStudyOption => ({
      id: option.id, label: option.label,
    })),
    correctOptionId: item.correctOptionId,
  })),
  valueFieldId: component.valueFieldId,
  value: component.value, min: component.min, max: component.max, step: component.step,
  columns: component.columns, symbol: component.symbol, url: component.url,
  style: component.style === undefined ? undefined : {
    tone: component.style.tone, emphasis: component.style.emphasis, density: component.style.density,
    alignment: component.style.alignment, spacing: component.style.spacing, padding: component.style.padding,
  },
});

export const componentKindAllowsChildren = (kind: SmartUiComponentKind): boolean =>
  ['stack', 'column', 'row', 'grid', 'card', 'panel', 'group', 'inset', 'metricGrid', 'imageGrid',
    'buttonGroup', 'disclosure', 'tabs', 'accordion'].includes(kind);

export const componentKindRequiresState = (kind: SmartUiComponentKind): boolean =>
  ['singleChoice', 'multiChoice', 'segmented', 'picker', 'toggle', 'textField', 'textArea', 'numberField',
    'slider', 'stepper', 'datePicker', 'timePicker', 'rating', 'colorChoice', 'checklist', 'disclosure',
    'tabs', 'accordion', 'menu'].includes(kind);

export const componentKindIsPresentationState = (kind: SmartUiComponentKind): boolean =>
  ['disclosure', 'tabs', 'accordion'].includes(kind);

export const componentKindHasOptions = (kind: SmartUiComponentKind): boolean =>
  ['singleChoice', 'multiChoice', 'segmented', 'picker', 'colorChoice', 'checklist', 'menu'].includes(kind);

export const componentKindIsNumeric = (kind: SmartUiComponentKind): boolean =>
  ['numberField', 'slider', 'stepper', 'rating'].includes(kind);

export const componentKindIsMultiSelection = (kind: SmartUiComponentKind): boolean =>
  ['multiChoice', 'checklist', 'accordion'].includes(kind);

export type SmartUiComponentFamily = 'layout' | 'text' | 'status' | 'input' | 'data' | 'media' | 'task' | 'action';
export interface SmartUiComponentCatalogEntry {
  kind: SmartUiComponentKind;
  family: SmartUiComponentFamily;
  allowsChildren: boolean;
  requiresState: boolean;
  presentationState: boolean;
  stateType?: 'string' | 'number' | 'boolean';
  valueSemantics?: string;
}

const familyFor = (index: number): SmartUiComponentFamily => {
  if (SMART_UI_COMPONENT_KINDS[index] === 'study') return 'task';
  if (index < 10) return 'layout';
  if (index < 22) return 'text';
  if (index < 32) return 'status';
  if (index < 46) return 'input';
  if (index < 56) return 'data';
  if (index < 64) return 'media';
  if (index < 72) return 'task';
  return 'action';
};

export const SMART_UI_COMPONENT_CATALOG: SmartUiComponentCatalogEntry[] = SMART_UI_COMPONENT_KINDS.map(
  (kind: SmartUiComponentKind, index: number): SmartUiComponentCatalogEntry => {
    const entry: SmartUiComponentCatalogEntry = {
      kind, family: familyFor(index), allowsChildren: componentKindAllowsChildren(kind),
      requiresState: componentKindRequiresState(kind), presentationState: componentKindIsPresentationState(kind),
    };
    if (!entry.requiresState) return entry;
    entry.stateType = componentKindIsNumeric(kind) ? 'number' : ['toggle', 'disclosure'].includes(kind) ? 'boolean' : 'string';
    entry.valueSemantics = componentKindIsMultiSelection(kind) ? 'JSON array string of unique option or child IDs' :
      componentKindIsNumeric(kind) ? 'finite number within min/max on step' :
      ['toggle', 'disclosure'].includes(kind) ? 'boolean' : kind === 'tabs' ? 'selected child ID' :
      kind === 'datePicker' ? 'yyyy-MM-dd' : kind === 'timePicker' ? 'HH:mm' :
      componentKindHasOptions(kind) ? 'selected option value' : 'plain text';
    return entry;
  },
);

export const componentNumberMin = (component: SmartUiComponent): number => component.min ?? 0;
export const componentNumberMax = (component: SmartUiComponent): number => component.max ?? (component.kind === 'rating' ? 5 : 100);
export const componentNumberStep = (component: SmartUiComponent): number => component.step ?? 1;

/** Collection fields retain the existing scalar persistence contract as bounded JSON string arrays. */
export const decodeSmartUiSelection = (value: string): string[] | null => {
  let parsed: JsonValue;
  try { parsed = JSON.parse(value) as JsonValue; } catch (_error) { return null; }
  if (!Array.isArray(parsed) || parsed.length > 32) return null;
  const result: string[] = [];
  for (const item of parsed) {
    if (typeof item !== 'string' || item.length === 0 || item.length > 120 || result.includes(item)) return null;
    result.push(item);
  }
  return result;
};

export const encodeSmartUiSelection = (values: string[]): string => JSON.stringify(values);

const validDate = (value: string): boolean => {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const year = Number(value.slice(0, 4));
  const month = Number(value.slice(5, 7));
  const day = Number(value.slice(8, 10));
  if (year < 1 || month < 1 || month > 12 || day < 1) return false;
  const days = [31, year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day <= days[month - 1];
};

export const smartUiComponentValueValid = (
  node: SmartUiNode, value: SmartUiScalar, children: SmartUiNode[], checkRequired: boolean = true,
): boolean => {
  const component = node.component;
  if (component === undefined || !componentKindRequiresState(component.kind)) return false;
  const kind = component.kind;
  if (typeof value === 'string' && value.length > 512) return false;
  if (componentKindIsNumeric(kind)) {
    if (typeof value !== 'number' || !Number.isFinite(value)) return false;
    const min = componentNumberMin(component);
    const max = componentNumberMax(component);
    const step = componentNumberStep(component);
    const position = (value - min) / step;
    return value >= min && value <= max && Math.abs(position - Math.round(position)) <= 1e-7;
  }
  if (kind === 'toggle' || kind === 'disclosure') return typeof value === 'boolean';
  if (componentKindIsMultiSelection(kind)) {
    if (typeof value !== 'string') return false;
    const selected = decodeSmartUiSelection(value);
    if (selected === null || (checkRequired && node.required === true && selected.length === 0)) return false;
    const ids = kind === 'accordion' ? children.map((child: SmartUiNode): string => child.id) :
      (node.options?.length ?? 0) > 0 ? (node.options ?? []).map((option): string => String(option.value)) : component.items ?? [];
    return selected.every((id: string): boolean => ids.includes(id));
  }
  if (kind === 'tabs') return typeof value === 'string' && children.some((child: SmartUiNode): boolean => child.id === value);
  if (componentKindHasOptions(kind)) return (node.options ?? []).some((option): boolean => typeof option.value === typeof value && option.value === value);
  if (typeof value !== 'string') return false;
  if (checkRequired && node.required === true && value.trim().length === 0) return false;
  if (kind === 'datePicker') return value.length === 0 ? node.required !== true || !checkRequired : validDate(value);
  if (kind === 'timePicker') return value.length === 0 ? node.required !== true || !checkRequired : /^([01]\d|2[0-3]):[0-5]\d$/.test(value);
  return true;
};

export const smartUiComponentDisplayText = (
  node: SmartUiNode, value: SmartUiScalar, children: SmartUiNode[],
): string | null => {
  const component = node.component;
  if (component === undefined || componentKindIsPresentationState(component.kind) || !smartUiComponentValueValid(node, value, children)) return null;
  if (component.kind === 'toggle') return value === true ? '开启' : '关闭';
  if (componentKindIsMultiSelection(component.kind)) {
    const selected = decodeSmartUiSelection(String(value));
    if (selected === null) return null;
    return selected.map((id: string): string => (node.options ?? []).find((option): boolean => option.value === id)?.label ?? id).join('、');
  }
  const option = (node.options ?? []).find((item): boolean => typeof item.value === typeof value && item.value === value);
  return option?.label ?? String(value);
};

const isObject = (value: JsonValue | undefined): value is JsonObject =>
  value !== null && value !== undefined && typeof value === 'object' && !Array.isArray(value);

const allowedKeys = (object: JsonObject, keys: string[], errors: string[], path: string): void => {
  for (const key of Object.keys(object)) if (!keys.includes(key)) errors.push(`${path} 不支持字段 ${key}`);
};

const boundedText = (value: JsonValue | undefined, max: number, path: string, errors: string[]): string | undefined => {
  if (value === undefined) return undefined;
  if (typeof value !== 'string' || value.length > max) { errors.push(`${path} 必须是最多 ${max} 字符的字符串`); return undefined; }
  return value;
};

const textArray = (value: JsonValue | undefined, path: string, maxItems: number, errors: string[]): string[] | undefined => {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length > maxItems) { errors.push(`${path} 最多包含 ${maxItems} 项`); return undefined; }
  const result: string[] = [];
  for (const item of value) {
    const text = boundedText(item, 2000, path, errors);
    if (text !== undefined) result.push(text);
    else errors.push(`${path} 每项必须是字符串`);
  }
  return result;
};

const parseStyle = (value: JsonValue | undefined, errors: string[]): SmartUiComponentStyle | undefined => {
  if (value === undefined) return undefined;
  if (!isObject(value)) { errors.push('component.style 必须是对象'); return undefined; }
  const names: string[] = ['tone', 'emphasis', 'density', 'alignment', 'spacing', 'padding'];
  const choices: string[][] = [
    ['neutral', 'accent', 'success', 'warning', 'danger'], ['plain', 'soft', 'outlined', 'filled'],
    ['compact', 'comfortable'], ['leading', 'center'], ['none', 'small', 'medium', 'large'], ['none', 'small', 'medium', 'large'],
  ];
  allowedKeys(value, names, errors, 'component.style');
  for (let index = 0; index < names.length; index++) {
    const token = value[names[index]];
    if (token !== undefined && (typeof token !== 'string' || !choices[index].includes(token))) errors.push(`component.style.${names[index]} 不在原生样式目录中`);
  }
  const style: SmartUiComponentStyle = {};
  if (typeof value['tone'] === 'string') style.tone = value['tone'] as SmartUiComponentTone;
  if (typeof value['emphasis'] === 'string') style.emphasis = value['emphasis'] as SmartUiComponentEmphasis;
  if (typeof value['density'] === 'string') style.density = value['density'] as SmartUiComponentDensity;
  if (typeof value['alignment'] === 'string') style.alignment = value['alignment'] as SmartUiComponentAlignment;
  if (typeof value['spacing'] === 'string') style.spacing = value['spacing'] as SmartUiComponentSpace;
  if (typeof value['padding'] === 'string') style.padding = value['padding'] as SmartUiComponentSpace;
  return style;
};

const SMART_UI_FIELD_ID: RegExp = /^[A-Za-z][A-Za-z0-9_-]{0,47}$/;

const parseStudyItems = (value: JsonValue | undefined, errors: string[]): SmartUiStudyItem[] | undefined => {
  if (value === undefined) return undefined;
  if (!Array.isArray(value) || value.length < 1 || value.length > 40) {
    errors.push('component.study 需要 1-40 个学习项目');
    return undefined;
  }
  const items: SmartUiStudyItem[] = [];
  for (const raw of value) {
    if (!isObject(raw)) { errors.push('study 项目必须是对象'); continue; }
    allowedKeys(raw, ['id', 'prompt', 'answer', 'detail', 'options', 'correctOptionId'], errors, 'study');
    const id: string | undefined = boundedText(raw['id'], 48, 'study.id', errors);
    const prompt: string | undefined = boundedText(raw['prompt'], 2000, 'study.prompt', errors);
    const answer: string | undefined = boundedText(raw['answer'], 2000, 'study.answer', errors);
    if (id === undefined || !SMART_UI_FIELD_ID.test(id)) { errors.push('study.id 必须是稳定 ASCII 标识'); continue; }
    if (prompt === undefined || prompt.trim().length === 0) { errors.push('study.prompt 不能为空'); continue; }
    if (answer === undefined || answer.trim().length === 0) { errors.push('study.answer 不能为空'); continue; }
    const item: SmartUiStudyItem = { id, prompt, answer };
    item.detail = boundedText(raw['detail'], 2000, 'study.detail', errors);
    const rawOptions = raw['options'];
    if (rawOptions !== undefined) {
      if (!Array.isArray(rawOptions) || rawOptions.length < 2 || rawOptions.length > 8) {
        errors.push('study.options 需要 2-8 个选项');
      } else {
        item.options = [];
        for (const optionRaw of rawOptions) {
          if (!isObject(optionRaw)) { errors.push('study.options 每项必须是对象'); continue; }
          allowedKeys(optionRaw, ['id', 'label'], errors, 'study.options');
          const optionId: string | undefined = boundedText(optionRaw['id'], 48, 'study.options.id', errors);
          const label: string | undefined = boundedText(optionRaw['label'], 200, 'study.options.label', errors);
          if (optionId === undefined || !SMART_UI_FIELD_ID.test(optionId)) { errors.push('study.options.id 必须是稳定 ASCII 标识'); continue; }
          if (label === undefined || label.trim().length === 0) { errors.push('study.options.label 不能为空'); continue; }
          if (item.options.some((option: SmartUiStudyOption): boolean => option.id === optionId)) {
            errors.push('同一学习项目内 study.options.id 不能重复');
            continue;
          }
          item.options.push({ id: optionId, label });
        }
      }
    }
    if (item.options !== undefined && item.options.length > 0) {
      const correct: string | undefined = boundedText(raw['correctOptionId'], 48, 'study.correctOptionId', errors);
      if (correct === undefined || !item.options.some((option: SmartUiStudyOption): boolean => option.id === correct)) {
        errors.push('study.correctOptionId 必须指向一个选项');
      } else {
        item.correctOptionId = correct;
      }
    } else if (raw['correctOptionId'] !== undefined) {
      errors.push('无选项的学习项目不能声明 correctOptionId');
    }
    items.push(item);
  }
  return items;
};

export interface SmartUiComponentValidation {
  component: SmartUiComponent | null;
  errors: string[];
}

export const validateSmartUiComponent = (value: JsonValue | undefined): SmartUiComponentValidation => {
  const errors: string[] = [];
  if (!isObject(value)) return { component: null, errors: ['component 必须是对象'] };
  allowedKeys(value, ['kind', 'title', 'text', 'items', 'rows', 'study', 'value', 'valueFieldId', 'min', 'max', 'step', 'columns', 'symbol', 'url', 'style'], errors, 'component');
  const kind = value['kind'];
  if (typeof kind !== 'string' || !SMART_UI_COMPONENT_KINDS.includes(kind as SmartUiComponentKind)) return { component: null, errors: errors.concat(['component.kind 不在原生组件目录中']) };
  const component: SmartUiComponent = { kind: kind as SmartUiComponentKind };
  component.title = boundedText(value['title'], 120, 'component.title', errors);
  component.text = boundedText(value['text'], 4096, 'component.text', errors);
  component.symbol = boundedText(value['symbol'], 80, 'component.symbol', errors);
  component.url = boundedText(value['url'], 2048, 'component.url', errors);
  component.items = textArray(value['items'], 'component.items', 32, errors);
  component.style = parseStyle(value['style'], errors);
  component.valueFieldId = boundedText(value['valueFieldId'], 48, 'component.valueFieldId', errors);
  if (component.valueFieldId !== undefined && !SMART_UI_FIELD_ID.test(component.valueFieldId)) {
    errors.push('component.valueFieldId 必须是稳定 ASCII 状态标识');
  }
  if (component.valueFieldId !== undefined &&
    !['metric', 'progress', 'stepProgress'].includes(component.kind)) {
    errors.push('只有 metric/progress/stepProgress 可以声明 component.valueFieldId');
  }
  component.study = parseStudyItems(value['study'], errors);
  if (component.kind === 'study' && (component.study?.length ?? 0) === 0) errors.push('study 组件需要非空 component.study');
  if (component.kind !== 'study' && value['study'] !== undefined) errors.push('只有 study 组件可以包含 component.study');
  const numeric: string[] = ['value', 'min', 'max', 'step', 'columns'];
  for (const field of numeric) {
    const number = value[field];
    if (number !== undefined && (typeof number !== 'number' || !Number.isFinite(number))) errors.push(`component.${field} 必须是有限数值`);
  }
  if (typeof value['value'] === 'number') component.value = value['value'];
  if (typeof value['min'] === 'number') component.min = value['min'];
  if (typeof value['max'] === 'number') component.max = value['max'];
  if (typeof value['step'] === 'number') component.step = value['step'];
  if (typeof value['columns'] === 'number') component.columns = value['columns'];
  if (component.columns !== undefined && (!Number.isInteger(component.columns) || component.columns < 1 || component.columns > 4)) errors.push('component.columns 必须是 1-4 的整数');
  if (componentNumberMin(component) >= componentNumberMax(component) || componentNumberStep(component) <= 0) errors.push('component 数值范围或步长不合法');
  if (component.kind === 'rating' && (!Number.isInteger(componentNumberMax(component)) || componentNumberMax(component) < 1 || componentNumberMax(component) > 10 || componentNumberMin(component) !== 0 || componentNumberStep(component) !== 1)) errors.push('rating 需要 0 至 1-10 的整数刻度，步长为 1');
  if (component.kind === 'progress' && (componentNumberMax(component) <= 0 || (component.value ?? 0) < 0 || (component.value ?? 0) > componentNumberMax(component))) errors.push('progress.value 必须在 0 与总值之间');
  if (component.url !== undefined && (!['image', 'avatar', 'linkCard'].includes(component.kind) || !/^https:\/\/[A-Za-z0-9][^\s/@:]*(:[0-9]+)?([/?#][^\s]*)?$/.test(component.url))) errors.push('媒体地址必须是无认证信息的 HTTPS 地址');
  const rows = value['rows'];
  if (rows !== undefined) {
    if (!Array.isArray(rows) || rows.length > 32) errors.push('component.rows 最多包含 32 行');
    else {
      component.rows = [];
      for (const raw of rows) {
        if (!isObject(raw)) { errors.push('component.rows 每行必须是对象'); continue; }
        allowedKeys(raw, ['label', 'value', 'valueFieldId', 'detail', 'cells'], errors, 'component.rows');
        const label = boundedText(raw['label'], 120, 'row.label', errors);
        if (label === undefined || label.trim().length === 0) { errors.push('row.label 必须有可读标签'); continue; }
        const row: SmartUiComponentRow = { label };
        row.detail = boundedText(raw['detail'], 2000, 'row.detail', errors);
        row.cells = textArray(raw['cells'], 'row.cells', 8, errors);
        row.valueFieldId = boundedText(raw['valueFieldId'], 48, 'row.valueFieldId', errors);
        if (row.valueFieldId !== undefined && !SMART_UI_FIELD_ID.test(row.valueFieldId)) {
          errors.push('row.valueFieldId 必须是稳定 ASCII 状态标识');
        }
        if (raw['value'] !== undefined) {
          if (typeof raw['value'] !== 'number' || !Number.isFinite(raw['value'])) errors.push('row.value 必须是有限数值');
          else row.value = raw['value'];
        }
        component.rows.push(row);
      }
    }
  }
  if (['barChart', 'lineChart'].includes(component.kind) && ((component.rows?.length ?? 0) === 0 || component.rows?.some((row: SmartUiComponentRow): boolean => row.value === undefined && row.valueFieldId === undefined))) errors.push('图表需要带有限数值或 valueFieldId 的 rows');
  return { component: errors.length === 0 ? component : null, errors };
};
