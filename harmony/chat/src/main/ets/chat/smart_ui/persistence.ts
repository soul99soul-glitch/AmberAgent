// smart_ui/persistence — 受限智能 UI 字段草稿的纯域序列化。
//
// 原始 UI 定义仍在消息正文中；这里只保存用户字段值，并用来源和最终定义
// 指纹把它们绑在同一个会话分支中的同一个 UI 块上。

import { utf8ByteLength } from '../miniapp/miniapp_models.ts';
import type { SmartUiDocument, SmartUiScalar } from './protocol.ts';
import { createSmartUiController } from './interaction.ts';
import type { SmartUiStateValue } from './interaction.ts';

export const SMART_UI_PERSISTENCE_SCHEMA_VERSION: number = 1;
export const SMART_UI_PERSISTENCE_PROTOCOL_VERSION: number = 1;
export const SMART_UI_STATE_KEY_PREFIX: string = 'smart_ui_state/v1';
export const SMART_UI_DRAFT_BLOCK_MAX_BYTES: number = 8 * 1024;
export const SMART_UI_DRAFT_SESSION_MAX_BYTES: number = 128 * 1024;
export const SMART_UI_SOURCE_FIELD_MAX_CHARS: number = 256;

export interface SmartUiDraftSource {
  conversationId: string;
  nodeId: string;
  messageId: string;
  blockId: string;
  blockIndex: number;
  sourceStart: number;
}

export interface SmartUiDraftEnvelope {
  schemaVersion: 1;
  protocolVersion: 1;
  source: SmartUiDraftSource;
  definitionFingerprint: string;
  values: SmartUiStateValue[];
  updatedAt: number;
}

export type SmartUiDraftSerializationReason =
  | 'ok'
  | 'document-not-ready'
  | 'invalid-source'
  | 'invalid-values'
  | 'block-too-large';

export interface SmartUiDraftSerialization {
  raw: string | null;
  envelope: SmartUiDraftEnvelope | null;
  bytes: number;
  reason: SmartUiDraftSerializationReason;
  diagnostics: string[];
}

export type SmartUiDraftRestoreStatus = 'restored' | 'missing' | 'rejected';

export interface SmartUiDraftRestore {
  status: SmartUiDraftRestoreStatus;
  values: SmartUiStateValue[];
  envelope: SmartUiDraftEnvelope | null;
  diagnostics: string[];
}

export interface SmartUiDraftSizeCheck {
  ok: boolean;
  bytes: number;
  limit: number;
  reason: 'ok' | 'block-too-large' | 'session-too-large';
}

const hashText = (input: string): string => {
  let hash: number = 0x811c9dc5;
  for (let index: number = 0; index < input.length; index++) {
    hash = Math.imul(hash ^ input.charCodeAt(index), 0x01000193);
  }
  return (hash >>> 0).toString(16).padStart(8, '0');
};

const definitionJson = (document: SmartUiDocument): string => {
  const states: Array<{ id: string; initial: SmartUiScalar; valueType: string }> = document.states.map(
    (state): { id: string; initial: SmartUiScalar; valueType: string } => ({
      id: state.id, initial: state.initial, valueType: state.valueType,
    }),
  );
  const nodes: Array<Record<string, string | number | boolean | null | object>> = document.nodes.map(
    (node): Record<string, string | number | boolean | null | object> => {
      const value: Record<string, string | number | boolean | null | object> = {
        id: node.id,
        parent: node.parent,
        type: node.type,
      };
      if (node.text !== undefined) value['text'] = node.text;
      if (node.bind !== undefined) value['bind'] = node.bind;
      if (node.state !== undefined) value['state'] = node.state;
      if (node.options !== undefined) value['options'] = node.options;
      if (node.placeholder !== undefined) value['placeholder'] = node.placeholder;
      if (node.label !== undefined) value['label'] = node.label;
      if (node.action !== undefined) value['action'] = node.action;
      if (node.ref !== undefined) value['ref'] = node.ref;
      if (node.visibleWhen !== undefined) value['visibleWhen'] = node.visibleWhen;
      return value;
    },
  );
  return JSON.stringify({ protocolVersion: SMART_UI_PERSISTENCE_PROTOCOL_VERSION,
    id: document.id, rootId: document.rootId, states, nodes });
};

/** A deterministic SDK-free identity fingerprint; it is not a security hash. */
export const smartUiDefinitionFingerprint = (document: SmartUiDocument): string =>
  hashText(definitionJson(document));

const sourceFieldValid = (value: string): boolean =>
  value.trim().length > 0 && value.length <= SMART_UI_SOURCE_FIELD_MAX_CHARS;

const sourceValid = (source: SmartUiDraftSource): boolean =>
  sourceFieldValid(source.conversationId) && sourceFieldValid(source.nodeId)
  && sourceFieldValid(source.messageId) && sourceFieldValid(source.blockId)
  && Number.isInteger(source.blockIndex) && source.blockIndex >= 0
  && Number.isInteger(source.sourceStart) && source.sourceStart >= 0;

const sameSource = (left: SmartUiDraftSource, right: SmartUiDraftSource): boolean =>
  left.conversationId === right.conversationId && left.nodeId === right.nodeId
  && left.messageId === right.messageId && left.blockId === right.blockId
  && left.blockIndex === right.blockIndex && left.sourceStart === right.sourceStart;

const hexSourcePart = (value: string): string => {
  let result: string = '';
  for (let index: number = 0; index < value.length; index++) {
    result += value.charCodeAt(index).toString(16).padStart(4, '0');
  }
  return result;
};

export const smartUiConversationKeyPrefix = (conversationId: string): string =>
  `${SMART_UI_STATE_KEY_PREFIX}/${hexSourcePart(conversationId)}/`;

/** The block key is reversible only by the source kept in the value; no raw user text enters the key. */
export const smartUiDraftKey = (source: SmartUiDraftSource): string =>
  `${smartUiConversationKeyPrefix(source.conversationId)}${hexSourcePart(source.nodeId)}/`
  + `${hexSourcePart(source.messageId)}/${source.blockIndex}/${source.sourceStart}/`
  + `${hexSourcePart(source.blockId)}`;

const validValuesFor = (
  document: SmartUiDocument,
  values: SmartUiStateValue[],
): { values: SmartUiStateValue[]; diagnostics: string[] } => {
  const controller = createSmartUiController({
    get: (_sourceKey: string, _stateId: string): SmartUiScalar | undefined => undefined,
    set: (_sourceKey: string, _stateId: string, _value: SmartUiScalar): void => {},
  });
  const accepted: SmartUiStateValue[] = [];
  const diagnostics: string[] = [];
  const seen: Set<string> = new Set<string>();
  for (const item of values) {
    if (seen.has(item.id)) {
      diagnostics.push(`重复状态值 ${item.id}`);
      continue;
    }
    seen.add(item.id);
    if (document.states.every((state): boolean => state.id !== item.id)) {
      diagnostics.push(`忽略未知状态 ${item.id}`);
      continue;
    }
    if (!controller.isValueValid(document, item.id, item.value)) {
      diagnostics.push(`忽略非法状态值 ${item.id}`);
      continue;
    }
    accepted.push({ id: item.id, value: item.value });
  }
  // State order makes the serialized form stable when a caller's object/list order changes.
  accepted.sort((left: SmartUiStateValue, right: SmartUiStateValue): number => {
    const leftIndex: number = document.states.findIndex((state): boolean => state.id === left.id);
    const rightIndex: number = document.states.findIndex((state): boolean => state.id === right.id);
    return leftIndex - rightIndex;
  });
  return { values: accepted, diagnostics };
};

const serializeEnvelope = (envelope: SmartUiDraftEnvelope): string => JSON.stringify(envelope);

export const serializeSmartUiDraft = (
  document: SmartUiDocument,
  source: SmartUiDraftSource,
  values: SmartUiStateValue[],
  updatedAt: number = Date.now(),
): SmartUiDraftSerialization => {
  if (document.status !== 'ready' || !document.ended) {
    return { raw: null, envelope: null, bytes: 0, reason: 'document-not-ready',
      diagnostics: ['只允许保存已闭合且通过校验的智能 UI 定义'] };
  }
  if (!sourceValid(source)) {
    return { raw: null, envelope: null, bytes: 0, reason: 'invalid-source',
      diagnostics: ['智能 UI 来源身份不完整或块位置无效'] };
  }
  if (!Number.isFinite(updatedAt) || updatedAt < 0) {
    return { raw: null, envelope: null, bytes: 0, reason: 'invalid-source', diagnostics: ['updatedAt 无效'] };
  }
  const checked: { values: SmartUiStateValue[]; diagnostics: string[] } = validValuesFor(document, values);
  const envelope: SmartUiDraftEnvelope = {
    schemaVersion: 1,
    protocolVersion: 1,
    source,
    definitionFingerprint: smartUiDefinitionFingerprint(document),
    values: checked.values,
    updatedAt,
  };
  const raw: string = serializeEnvelope(envelope);
  const bytes: number = utf8ByteLength(raw);
  if (bytes > SMART_UI_DRAFT_BLOCK_MAX_BYTES) {
    return { raw: null, envelope: null, bytes, reason: 'block-too-large',
      diagnostics: checked.diagnostics.concat([`智能 UI 草稿超过 ${SMART_UI_DRAFT_BLOCK_MAX_BYTES} 字节限制`]) };
  }
  return { raw, envelope, bytes, reason: checked.diagnostics.length > 0 ? 'invalid-values' : 'ok',
    diagnostics: checked.diagnostics };
};

const isScalar = (value: unknown): value is SmartUiScalar =>
  typeof value === 'string' || typeof value === 'boolean'
  || (typeof value === 'number' && Number.isFinite(value));

const parseSource = (value: unknown): SmartUiDraftSource | null => {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
  const raw: Record<string, unknown> = value as Record<string, unknown>;
  if (typeof raw['conversationId'] !== 'string' || typeof raw['nodeId'] !== 'string'
    || typeof raw['messageId'] !== 'string' || typeof raw['blockId'] !== 'string'
    || typeof raw['blockIndex'] !== 'number' || typeof raw['sourceStart'] !== 'number') return null;
  const source: SmartUiDraftSource = {
    conversationId: raw['conversationId'], nodeId: raw['nodeId'], messageId: raw['messageId'],
    blockId: raw['blockId'], blockIndex: raw['blockIndex'], sourceStart: raw['sourceStart'],
  };
  return sourceValid(source) ? source : null;
};

const parseValues = (value: unknown): SmartUiStateValue[] | null => {
  if (!Array.isArray(value)) return null;
  const values: SmartUiStateValue[] = [];
  for (const item of value) {
    if (item === null || typeof item !== 'object' || Array.isArray(item)) return null;
    const raw: Record<string, unknown> = item as Record<string, unknown>;
    if (typeof raw['id'] !== 'string' || !isScalar(raw['value'])) return null;
    if (typeof raw['value'] === 'string' && raw['value'].length > 512) return null;
    values.push({ id: raw['id'], value: raw['value'] });
  }
  return values;
};

export const parseSmartUiDraft = (raw: string): SmartUiDraftEnvelope | null => {
  if (utf8ByteLength(raw) > SMART_UI_DRAFT_BLOCK_MAX_BYTES) return null;
  try {
    const parsed: unknown = JSON.parse(raw);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return null;
    const value: Record<string, unknown> = parsed as Record<string, unknown>;
    if (value['schemaVersion'] !== 1 || value['protocolVersion'] !== 1
      || typeof value['definitionFingerprint'] !== 'string'
      || value['definitionFingerprint'].length === 0
      || value['definitionFingerprint'].length > 64
      || typeof value['updatedAt'] !== 'number' || !Number.isFinite(value['updatedAt'])
      || value['updatedAt'] < 0) return null;
    const source: SmartUiDraftSource | null = parseSource(value['source']);
    const values: SmartUiStateValue[] | null = parseValues(value['values']);
    if (source === null || values === null) return null;
    return { schemaVersion: 1, protocolVersion: 1, source,
      definitionFingerprint: value['definitionFingerprint'], values, updatedAt: value['updatedAt'] };
  } catch (_error) {
    return null;
  }
};

export const restoreSmartUiDraft = (
  raw: string | null,
  document: SmartUiDocument,
  source: SmartUiDraftSource,
): SmartUiDraftRestore => {
  if (raw === null) return { status: 'missing', values: [], envelope: null, diagnostics: [] };
  const envelope: SmartUiDraftEnvelope | null = parseSmartUiDraft(raw);
  if (envelope === null) return { status: 'rejected', values: [], envelope: null, diagnostics: ['草稿格式无效或超过单块大小限制'] };
  if (!sourceValid(source) || !sameSource(envelope.source, source)) {
    return { status: 'rejected', values: [], envelope, diagnostics: ['草稿来源与当前消息分支不匹配'] };
  }
  const fingerprint: string = smartUiDefinitionFingerprint(document);
  if (document.status !== 'ready' || !document.ended || envelope.definitionFingerprint !== fingerprint) {
    return { status: 'rejected', values: [], envelope, diagnostics: ['草稿定义已变化或当前智能 UI 尚未完成'] };
  }
  const checked: { values: SmartUiStateValue[]; diagnostics: string[] } = validValuesFor(document, envelope.values);
  if (checked.values.length !== envelope.values.length || checked.diagnostics.length > 0) {
    return { status: 'rejected', values: [], envelope, diagnostics: ['草稿包含当前定义不接受的状态值'] };
  }
  return { status: 'restored', values: checked.values, envelope, diagnostics: [] };
};

export const checkSmartUiDraftSessionBudget = (rawBlocks: string[]): SmartUiDraftSizeCheck => {
  let bytes: number = 0;
  for (const raw of rawBlocks) {
    const blockBytes: number = utf8ByteLength(raw);
    if (blockBytes > SMART_UI_DRAFT_BLOCK_MAX_BYTES) {
      return { ok: false, bytes: bytes + blockBytes, limit: SMART_UI_DRAFT_BLOCK_MAX_BYTES, reason: 'block-too-large' };
    }
    bytes += blockBytes;
  }
  return { ok: bytes <= SMART_UI_DRAFT_SESSION_MAX_BYTES, bytes, limit: SMART_UI_DRAFT_SESSION_MAX_BYTES,
    reason: bytes <= SMART_UI_DRAFT_SESSION_MAX_BYTES ? 'ok' : 'session-too-large' };
};

