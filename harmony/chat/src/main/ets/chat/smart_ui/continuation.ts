import type { SmartUiDocument, SmartUiNode, SmartUiScalar } from './protocol.ts';
import type { SmartUiStateValue } from './interaction.ts';
import type { SmartUiDraftSource } from './persistence.ts';
import type { UIMessage } from '../message.ts';
import { groupMessageParts } from '../message_grouping.ts';
import { parseResponseContent, declaredResponseContentIds } from '../response_content.ts';
import {
  componentKindRequiresState, componentKindIsPresentationState,
  smartUiComponentValueValid, smartUiComponentDisplayText,
} from './components.ts';

/** Reserve the action template budget; the composer also checks the expanded prompt plus snapshot. */
export const SMART_UI_MAX_CONTINUATION_SNAPSHOT_CHARS: number = 3582;
const SNAPSHOT_START: string = '【智能 UI 当前选择】';

export interface SmartUiContinuationCandidate {
  sourceKey: string;
  conversationId: string;
  messageId: string;
  ready: boolean;
  streaming: boolean;
  lastInteraction: number;
}

/** Only explicit references to a selected card attach state to an ordinary message. */
export const isExplicitSmartUiContinuation = (text: string): boolean =>
  /^(?:按这个继续|按这些继续|按当前选择继续|按所选继续|根据当前选择继续|就按这个继续|continue with (?:this|these|my selections))[。.!！\s]*$/i.test(text.trim());

/** Restored/default fields and unselected message branches are never inferred as user intent. */
export const latestSmartUiContinuationSource = (
  candidates: SmartUiContinuationCandidate[], conversationId: string, selectedMessageIds: string[],
): string | null => {
  let latest: SmartUiContinuationCandidate | null = null;
  let ambiguous: boolean = false;
  for (const candidate of candidates) {
    if (candidate.conversationId !== conversationId || !selectedMessageIds.includes(candidate.messageId) ||
      !candidate.ready || candidate.lastInteraction <= 0) continue;
    if (latest === null || candidate.lastInteraction > latest.lastInteraction) {
      latest = candidate;
      ambiguous = false;
    } else if (candidate.lastInteraction === latest.lastInteraction && candidate.sourceKey !== latest.sourceKey) {
      ambiguous = true;
    }
  }
  return latest === null || ambiguous ? null : latest.sourceKey;
};

/** Re-read the owning text group so a delayed renderer callback cannot authorize a stale definition. */
export const smartUiContinuationDocumentForSource = (
  message: UIMessage, source: SmartUiDraftSource,
): SmartUiDocument | null => {
  if (message.id !== source.messageId) return null;
  const group = groupMessageParts(message.parts).find((candidate): boolean =>
    candidate.kind === 'content' && candidate.index === source.blockIndex);
  if (group === undefined || group.kind !== 'content' || group.part.type !== 'text') return null;
  const segment = parseResponseContent(group.part.text, false, undefined, true,
    declaredResponseContentIds(message.parts)).find((candidate): boolean =>
    candidate.type === 'ui' && candidate.sourceStart === source.sourceStart && candidate.id === source.blockId);
  return segment !== undefined && segment.complete && segment.ui !== null ? segment.ui : null;
};

export const isSmartUiTaskState = (document: SmartUiDocument, stateId: string): boolean =>
  document.nodes.some((node: SmartUiNode): boolean => node.state === stateId &&
    (node.type === 'choice' || node.type === 'input' || (node.type === 'component' && node.component !== undefined &&
      componentKindRequiresState(node.component.kind) && !componentKindIsPresentationState(node.component.kind))));

const resolvedValues = (document: SmartUiDocument, values: SmartUiStateValue[]): SmartUiStateValue[] =>
  document.states.map((state): SmartUiStateValue => ({ id: state.id,
    value: values.find((item: SmartUiStateValue): boolean => item.id === state.id)?.value ?? state.initial }));

const valueAt = (values: SmartUiStateValue[], stateId: string): SmartUiScalar | undefined =>
  values.find((item: SmartUiStateValue): boolean => item.id === stateId)?.value;

const visibleNode = (document: SmartUiDocument, node: SmartUiNode, values: SmartUiStateValue[]): boolean => {
  let current: SmartUiNode | undefined = node;
  let depth: number = 0;
  while (current !== undefined && depth++ <= document.nodes.length) {
    const condition = current.visibleWhen;
    if (condition !== undefined && valueAt(values, condition.state) !== condition.equals) return false;
    current = current.parent === null ? undefined : document.nodes.find(
      (parent: SmartUiNode): boolean => parent.id === current?.parent);
  }
  return depth <= document.nodes.length + 1;
};

const truncateTitle = (title: string): string => {
  if (title.length <= 160) return title;
  let end: number = 160;
  const code: number = title.charCodeAt(end - 1);
  if (code >= 0xd800 && code <= 0xdbff) end--;
  return `${title.slice(0, end)}…`;
};

const taskTitle = (document: SmartUiDocument, values: SmartUiStateValue[]): string => {
  for (const node of document.nodes) {
    if (!visibleNode(document, node, values)) continue;
    if (node.type === 'text' && ['heading', 'hero', 'lead'].includes(node.variant ?? '') && node.text) {
      return truncateTitle(node.text);
    }
    if (node.type === 'component' && node.component !== undefined &&
      ['title', 'goal', 'decisionSummary'].includes(node.component.kind)) {
      const title: string = node.component.title ?? node.component.text ?? node.label ?? '';
      if (title.length > 0) return truncateTitle(title);
    }
  }
  return document.id;
};

const controlLabel = (document: SmartUiDocument, node: SmartUiNode): string => {
  const own: string | undefined = node.label ?? node.component?.title ?? node.placeholder;
  if (own !== undefined && own.trim().length > 0) return own;
  // Older choice nodes have no label field; a preceding sibling caption names their control.
  let previousText: string = '';
  for (const sibling of document.nodes) {
    if (sibling.id === node.id) break;
    if (sibling.parent !== node.parent) continue;
    previousText = sibling.type === 'text' && ['caption', 'subheading'].includes(sibling.variant ?? '')
      ? (sibling.text ?? '') : '';
  }
  return previousText || node.state || node.id;
};

/** Human-readable task state. UI-only expansion/tab fields are deliberately omitted. */
export const smartUiContinuationSnapshot = (
  document: SmartUiDocument, currentValues: SmartUiStateValue[],
  isValueValid?: (stateId: string, value: SmartUiScalar) => boolean,
): string | null => {
  if (document.status !== 'ready' || !document.ended) return null;
  const values: SmartUiStateValue[] = resolvedValues(document, currentValues);
  const fields: string[] = [];
  const included: string[] = [];
  for (const node of document.nodes) {
    const stateId: string | undefined = node.state;
    if (stateId === undefined || included.includes(stateId) || !isSmartUiTaskState(document, stateId) ||
      !visibleNode(document, node, values)) continue;
    const value: SmartUiScalar | undefined = valueAt(values, stateId);
    const state = document.states.find((item): boolean => item.id === stateId);
    if (state === undefined || value === undefined || typeof value !== state.valueType ||
      (typeof value === 'string' && value.length > 512) ||
      (typeof value === 'number' && !Number.isFinite(value)) ||
      (isValueValid !== undefined && !isValueValid(stateId, value))) return null;
    let display: string = String(value);
    if (node.type === 'component') {
      const children: SmartUiNode[] = document.nodes.filter((child: SmartUiNode): boolean => child.parent === node.id);
      if (!smartUiComponentValueValid(node, value, children)) return null;
      const text: string | null = smartUiComponentDisplayText(node, value, children);
      if (text === null) return null;
      display = text;
    } else if (node.options !== undefined) {
      const option = node.options.find((item): boolean => item.value === value);
      if (option === undefined) return null;
      display = option.label;
    } else if (typeof value === 'boolean') display = value ? '是' : '否';
    if (node.required === true && typeof value === 'string' && value.trim().length === 0) return null;
    fields.push(`- ${JSON.stringify(controlLabel(document, node))}：${JSON.stringify(display)}`);
    included.push(stateId);
  }
  const lines: string[] = [SNAPSHOT_START, `来源：${document.id}`, `目标：${JSON.stringify(taskTitle(document, values))}`];
  const snapshot: string = lines.concat(fields).concat(['【选择结束】']).join('\n');
  // Refuse an incomplete selection summary instead of silently dropping the final fields.
  return snapshot.length <= SMART_UI_MAX_CONTINUATION_SNAPSHOT_CHARS ? snapshot : null;
};

export const appendSmartUiContinuation = (prompt: string, snapshot: string | null): string => {
  if (snapshot === null || prompt.trimEnd().endsWith(snapshot)) return prompt;
  return `${prompt}\n\n${snapshot}`;
};
