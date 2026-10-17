import type { SmartUiNode, SmartUiOption, SmartUiScalar, SmartUiStateValue } from '@amber/chat-domain';
import { decodeSmartUiSelection } from '@amber/chat-domain';

/** Presentation reads the page-owned snapshot; all mutations still go through the card controller. */
export function smartUiValue(values: SmartUiStateValue[], stateId: string | undefined): SmartUiScalar | undefined {
  for (const item of values) if (item.id === stateId) return item.value;
  return undefined;
}

export function smartUiString(values: SmartUiStateValue[], stateId: string | undefined): string {
  const value: SmartUiScalar | undefined = smartUiValue(values, stateId);
  return value === undefined ? '' : String(value);
}

export function smartUiSelection(values: SmartUiStateValue[], stateId: string | undefined): string[] {
  const raw: SmartUiScalar | undefined = smartUiValue(values, stateId);
  if (typeof raw !== 'string' || raw.length === 0) return [];
  return decodeSmartUiSelection(raw) ?? [];
}

export function smartUiOptions(node: SmartUiNode): SmartUiOption[] {
  if ((node.options ?? []).length > 0) return node.options ?? [];
  const result: SmartUiOption[] = [];
  for (const item of node.component?.items ?? []) result.push({ value: item, label: item });
  return result;
}

export function smartUiSpace(value: string | undefined, fallback: number): number {
  if (value === 'none') return 0;
  if (value === 'small') return 6;
  if (value === 'medium') return 12;
  if (value === 'large') return 20;
  return fallback;
}

export function smartUiNumericValue(node: SmartUiNode, values: SmartUiStateValue[]): number {
  const raw: SmartUiScalar | undefined = smartUiValue(values, node.state);
  const minimum: number = node.component?.min ?? 0;
  const maximum: number = node.component?.max ?? (node.component?.kind === 'rating' ? 5 : 100);
  const display: number = typeof raw === 'number' ? raw : node.component?.value ?? minimum;
  return Math.max(minimum, Math.min(maximum, display));
}

export function smartUiDateString(date: Date, timeOnly: boolean): string {
  if (timeOnly) return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export function smartUiSelectedDate(raw: string, timeOnly: boolean): Date {
  const date: Date = new Date();
  if (timeOnly && /^\d{2}:\d{2}$/.test(raw)) {
    date.setHours(Number(raw.slice(0, 2)), Number(raw.slice(3, 5)), 0, 0);
  } else if (!timeOnly && /^\d{4}-\d{2}-\d{2}$/.test(raw)) {
    date.setFullYear(Number(raw.slice(0, 4)), Number(raw.slice(5, 7)) - 1, Number(raw.slice(8, 10)));
  }
  return date;
}
