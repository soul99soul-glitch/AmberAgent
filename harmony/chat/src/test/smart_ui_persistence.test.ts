import { test } from 'node:test';
import assert from 'node:assert/strict';

import { parseSmartUi } from '../main/ets/chat/smart_ui/protocol.ts';
import type { SmartUiDocument } from '../main/ets/chat/smart_ui/protocol.ts';
import {
  checkSmartUiDraftSessionBudget, parseSmartUiDraft, restoreSmartUiDraft,
  serializeSmartUiDraft, smartUiDefinitionFingerprint, smartUiDraftKey,
  SMART_UI_DRAFT_BLOCK_MAX_BYTES, SMART_UI_DRAFT_SESSION_MAX_BYTES,
} from '../main/ets/chat/smart_ui/persistence.ts';
import type { SmartUiDraftSource } from '../main/ets/chat/smart_ui/persistence.ts';
import { createSmartUiDraftStore } from '../main/ets/chat/smart_ui/store.ts';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import type { KeyValueStore } from '../main/ets/chat/kv_store.ts';
import { createSmartUiController } from '../main/ets/chat/smart_ui/interaction.ts';

const document = (text: string = '证书链'): SmartUiDocument => parseSmartUi(
  '{"op":"state","id":"selected","initial":"root"}\n' +
  '{"op":"node","id":"panel","type":"column"}\n' +
  `{"op":"node","id":"heading","parent":"panel","type":"text","text":${JSON.stringify(text)}}\n` +
  '{"op":"node","id":"choice","parent":"panel","type":"choice","state":"selected","options":[' +
  '{"value":"root","label":"根证书"},{"value":"server","label":"服务器证书"}]}\n' +
  '{"op":"end"}', 'ca-controls', true,
);

const source: SmartUiDraftSource = {
  conversationId: 'conv/一', nodeId: 'branch/1', messageId: 'message😀', blockId: 'ca-controls', blockIndex: 2,
  sourceStart: 128,
};

test('definition fingerprint covers final states and nodes but excludes diagnostics', () => {
  const first: SmartUiDocument = document();
  const second: SmartUiDocument = document();
  assert.equal(smartUiDefinitionFingerprint(first), smartUiDefinitionFingerprint(second));
  assert.notEqual(smartUiDefinitionFingerprint(first), smartUiDefinitionFingerprint(document('另一段')));
});

test('serialize → restore keeps source, fingerprint, and only legal values', () => {
  const doc: SmartUiDocument = document();
  const saved = serializeSmartUiDraft(doc, source, [
    { id: 'selected', value: 'server' },
  ], 123);
  assert.equal(saved.reason, 'ok');
  assert.ok(saved.raw !== null);
  assert.equal(saved.bytes <= SMART_UI_DRAFT_BLOCK_MAX_BYTES, true);
  assert.deepEqual(parseSmartUiDraft(saved.raw as string)?.source, source);
  const restored = restoreSmartUiDraft(saved.raw, doc, source);
  assert.equal(restored.status, 'restored');
  assert.deepEqual(restored.values, [{ id: 'selected', value: 'server' }]);
});

test('invalid or unknown values are skipped on save and never restored as valid state', () => {
  const doc: SmartUiDocument = document();
  const saved = serializeSmartUiDraft(doc, source, [
    { id: 'selected', value: 'server' },
    { id: 'selected', value: 'root' },
    { id: 'unknown', value: 'secret' },
    { id: 'selected', value: 'missing' },
  ], 123);
  assert.equal(saved.reason, 'invalid-values');
  assert.ok(saved.raw !== null);
  assert.deepEqual(saved.envelope?.values, [{ id: 'selected', value: 'server' }]);
  assert.deepEqual(restoreSmartUiDraft(saved.raw, doc, source).values, [{ id: 'selected', value: 'server' }]);
});

test('restore rejects source, definition, version, and malformed/truncated changes', () => {
  const doc: SmartUiDocument = document();
  const saved = serializeSmartUiDraft(doc, source, [{ id: 'selected', value: 'root' }], 123);
  assert.ok(saved.raw !== null);
  const raw: string = saved.raw as string;
  assert.equal(restoreSmartUiDraft(raw, doc, { ...source, nodeId: 'other' }).status, 'rejected');
  assert.equal(restoreSmartUiDraft(raw, doc, { ...source, sourceStart: source.sourceStart + 1 }).status, 'rejected');
  assert.equal(restoreSmartUiDraft(raw, document('edited'), source).status, 'rejected');
  assert.equal(restoreSmartUiDraft(raw.slice(0, -1), doc, source).status, 'rejected');
  const parsed = JSON.parse(raw) as Record<string, unknown>;
  parsed['schemaVersion'] = 2;
  assert.equal(restoreSmartUiDraft(JSON.stringify(parsed), doc, source).status, 'rejected');
});

test('incomplete UI definitions cannot be persisted; oversized blocks are rejected', () => {
  const partial: SmartUiDocument = parseSmartUi(
    '{"op":"node","id":"panel","type":"column"}\n', 'partial', false,
  );
  assert.equal(serializeSmartUiDraft(partial, source, [], 1).reason, 'document-not-ready');

  let body: string = '';
  for (let index: number = 0; index < 32; index++) {
    body += JSON.stringify({ op: 'state', id: `state${index}`, initial: 'x'.repeat(512) }) + '\n';
  }
  body += '{"op":"node","id":"panel","type":"column"}\n{"op":"end"}';
  const large: SmartUiDocument = parseSmartUi(body, 'large', true);
  assert.equal(large.status, 'ready');
  const saved = serializeSmartUiDraft(large, { ...source, blockId: 'large' },
    large.states.map((state): { id: string; value: string } => ({ id: state.id, value: state.initial as string })), 1);
  assert.equal(saved.reason, 'block-too-large');
  assert.ok(saved.bytes > SMART_UI_DRAFT_BLOCK_MAX_BYTES);
});

test('session budget counts UTF-8 bytes and exact limits', () => {
  assert.deepEqual(checkSmartUiDraftSessionBudget(['x'.repeat(SMART_UI_DRAFT_BLOCK_MAX_BYTES)]), {
    ok: true, bytes: SMART_UI_DRAFT_BLOCK_MAX_BYTES, limit: SMART_UI_DRAFT_SESSION_MAX_BYTES, reason: 'ok',
  });
  const tooMany: string[] = [];
  for (let index: number = 0; index < 16; index++) tooMany.push('x'.repeat(SMART_UI_DRAFT_BLOCK_MAX_BYTES));
  assert.equal(checkSmartUiDraftSessionBudget(tooMany).ok, true);
  tooMany.push('x');
  assert.equal(checkSmartUiDraftSessionBudget(tooMany).reason, 'session-too-large');
  assert.equal(checkSmartUiDraftSessionBudget(['汉'.repeat(4096)]).reason, 'block-too-large');
});

test('source key isolates slash/unicode identities', () => {
  const other: SmartUiDraftSource = { ...source, conversationId: 'conv_一' };
  assert.notEqual(smartUiDraftKey(source), smartUiDraftKey(other));
  assert.match(smartUiDraftKey(source), /^smart_ui_state\/v1\/[0-9a-f]+\//);
});

test('store saves, restores, serializes writes, and cleans one conversation only', async () => {
  const kv = createMemoryKeyValueStore();
  const store = createSmartUiDraftStore(kv);
  const doc: SmartUiDocument = document();
  const saved = await store.save(doc, source, [{ id: 'selected', value: 'server' }], 10);
  assert.equal(saved.saved, true);
  assert.equal((await store.load(doc, source)).status, 'restored');
  const otherSource: SmartUiDraftSource = { ...source, conversationId: 'other' };
  await store.save(doc, otherSource, [{ id: 'selected', value: 'root' }], 11);
  const cleanup = await store.cleanupConversation(source.conversationId);
  assert.deepEqual(cleanup, { available: true, deleted: 1, error: null });
  assert.equal(await kv.get(smartUiDraftKey(source)), null);
  assert.ok(await kv.get(smartUiDraftKey(otherSource)) !== null);
});

test('store refuses to claim session quota when KV cannot enumerate entries', async () => {
  const values = new Map<string, string>();
  const kv: KeyValueStore = {
    get: async (key: string): Promise<string | null> => values.get(key) ?? null,
    put: async (key: string, value: string): Promise<void> => { values.set(key, value); },
    delete: async (key: string): Promise<void> => { values.delete(key); },
  };
  const result = await createSmartUiDraftStore(kv).save(document(), source, [{ id: 'selected', value: 'root' }], 1);
  assert.equal(result.saved, false);
  assert.equal(result.reason, 'quota-unknown');
});

test('debounced saves coalesce same-block writes and cleanup cancels a pending write', async () => {
  const kv = createMemoryKeyValueStore();
  const store = createSmartUiDraftStore(kv);
  const doc: SmartUiDocument = document();
  const first = store.saveDebounced(doc, source, [{ id: 'selected', value: 'root' }], 1, 5);
  const second = store.saveDebounced(doc, source, [{ id: 'selected', value: 'server' }], 2, 5);
  assert.equal((await first).saved, true);
  assert.equal((await second).saved, true);
  const restored = await store.load(doc, source);
  assert.deepEqual(restored.values, [{ id: 'selected', value: 'server' }]);

  const pending = store.saveDebounced(doc, { ...source, blockId: 'pending' }, [{ id: 'selected', value: 'root' }], 3, 20);
  const cleanup = await store.cleanupConversation(source.conversationId);
  assert.equal(cleanup.deleted, 1);
  assert.equal((await pending).reason, 'cancelled');
});

test('load flushes a pending same-block debounce before reading KV', async () => {
  const kv = createMemoryKeyValueStore();
  const store = createSmartUiDraftStore(kv);
  const doc: SmartUiDocument = document();
  const pending = store.saveDebounced(doc, source, [{ id: 'selected', value: 'server' }], 3, 500);
  const restored = await store.load(doc, source);
  assert.equal(restored.status, 'restored');
  assert.deepEqual(restored.values, [{ id: 'selected', value: 'server' }]);
  assert.equal((await pending).saved, true);
});

test('flush persists the latest pending value before the store owner leaves', async () => {
  const kv = createMemoryKeyValueStore();
  const store = createSmartUiDraftStore(kv);
  const doc: SmartUiDocument = document();
  const pending = store.saveDebounced(doc, source, [{ id: 'selected', value: 'server' }], 4, 500);
  await store.flush();
  assert.equal((await pending).saved, true);
  assert.deepEqual((await store.load(doc, source)).values, [{ id: 'selected', value: 'server' }]);
});

const nativeDocument = (step: number = 1, tone: string = 'neutral'): SmartUiDocument => parseSmartUi([
  { op: 'state', id: 'budget', initial: 10 },
  { op: 'state', id: 'note', initial: '' },
  { op: 'state', id: 'preferences', initial: '[]' },
  { op: 'node', id: 'root', type: 'column' },
  { op: 'node', id: 'budgetNode', parent: 'root', type: 'component', state: 'budget', component: { kind: 'slider', title: '预算', min: 0, max: 20, step, style: { tone } } },
  { op: 'node', id: 'noteNode', parent: 'root', type: 'component', state: 'note', required: true, component: { kind: 'textArea', title: '需求' } },
  { op: 'node', id: 'preferencesNode', parent: 'root', type: 'component', state: 'preferences', required: true, options: [{ value: 'a', label: '甲' }], component: { kind: 'multiChoice', title: '偏好' } },
  { op: 'end' },
].map((record) => JSON.stringify(record)).join('\n'), 'native-task', true);

test('native fingerprints include semantic component changes while allowing theme-only changes', () => {
  const doc = nativeDocument();
  assert.equal(doc.status, 'ready');
  assert.notEqual(smartUiDefinitionFingerprint(doc), smartUiDefinitionFingerprint(nativeDocument(2)));
  assert.equal(smartUiDefinitionFingerprint(doc), smartUiDefinitionFingerprint(nativeDocument(1, 'accent')));
  const saved = serializeSmartUiDraft(doc, source, [{ id: 'budget', value: 13 }], 1);
  assert.equal(restoreSmartUiDraft(saved.raw, nativeDocument(2), source).status, 'rejected');
  assert.equal(restoreSmartUiDraft(saved.raw, nativeDocument(1, 'accent'), source).status, 'restored');
});

test('cleared required text and collections round-trip through the actual draft store', async () => {
  const kv = createMemoryKeyValueStore();
  const store = createSmartUiDraftStore(kv);
  const doc = nativeDocument();
  await store.save(doc, source, [{ id: 'budget', value: 10 }, { id: 'note', value: '原回答' }, { id: 'preferences', value: '["a"]' }], 1);
  const changed = await store.save(doc, source, [{ id: 'budget', value: 10 }, { id: 'note', value: '' }, { id: 'preferences', value: '[]' }], 2);
  assert.equal(changed.saved, true);
  const restored = await store.load(doc, source);
  assert.deepEqual(restored.values, [{ id: 'budget', value: 10 }, { id: 'note', value: '' }, { id: 'preferences', value: '[]' }]);
  const controller = createSmartUiController({ get: () => undefined, set: () => {} });
  const owner = controller.reconcile(doc, 'same-source', [], restored.values);
  assert.deepEqual(owner.values, restored.values);
  assert.deepEqual(owner.invalidStateIds, []);
  assert.equal(controller.draft(doc, { type: 'draft', text: '按这些继续' }, owner.values), null);
});

test('restore rejects illegal native values even when the saved definition still matches', () => {
  const doc = nativeDocument(2);
  const serialized = serializeSmartUiDraft(doc, source, [{ id: 'budget', value: 12 }], 1);
  assert.ok(serialized.raw);
  const raw = JSON.parse(serialized.raw);
  raw.values[0].value = 13;
  const restored = restoreSmartUiDraft(JSON.stringify(raw), doc, source);
  assert.equal(restored.status, 'rejected');
  assert.deepEqual(restored.values, []);
});

test('definition fingerprint covers study content and numeric linkage fields', () => {
  const study = (answer: string): SmartUiDocument => parseSmartUi(
    '{"op":"state","id":"progress","initial":""}\n' +
    '{"op":"node","id":"root","type":"column"}\n' +
    '{"op":"node","id":"study","parent":"root","type":"component","state":"progress","component":{"kind":"study","study":[{"id":"q1","prompt":"问题","answer":' + JSON.stringify(answer) + '}]}}\n' +
    '{"op":"end"}', 'study-fp', true);
  assert.notEqual(smartUiDefinitionFingerprint(study('答案一')), smartUiDefinitionFingerprint(study('答案二')));
  const metric = (fieldId: string): SmartUiDocument => parseSmartUi(
    '{"op":"state","id":"budget","initial":50}\n' +
    '{"op":"state","id":"other","initial":10}\n' +
    '{"op":"node","id":"root","type":"column"}\n' +
    '{"op":"node","id":"metric","parent":"root","type":"component","component":{"kind":"metric","valueFieldId":' + JSON.stringify(fieldId) + '}}\n' +
    '{"op":"end"}', 'metric-fp', true);
  assert.notEqual(smartUiDefinitionFingerprint(metric('budget')), smartUiDefinitionFingerprint(metric('other')));
});
