import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createSyncBackup, restoreSyncBackup, inspectSyncArchive,
  buildSyncPayload, validateSyncPayload, conversationFromSyncDto,
  MANIFEST_ENTRY, PAYLOAD_ENTRY, type SyncCryptoPort,
} from '../main/ets/chat/sync_snapshot.ts';
import { makeConversation, makeMessageNode, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUserMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';

// 测试用 mock：XOR+长度校验，足够锁定组装/校验逻辑
const mockCrypto = (): SyncCryptoPort => ({
  deriveKey: async (pass, salt) => {
    const key = new Uint8Array(32);
    for (let i = 0; i < 32; i++) {
      const sc = salt.charCodeAt(i % Math.max(1, salt.length));
      const pc = pass.charCodeAt(i % Math.max(1, pass.length)) | 1;
      key[i] = (sc ^ i ^ pc) & 0xff;
    }
    return key;
  },
  encrypt: async (plaintext, key) => {
    const out = new Uint8Array(plaintext.length + 16);
    for (let i = 0; i < plaintext.length; i++) out[i] = plaintext[i] ^ key[i % 32];
    // tag 占位
    for (let i = 0; i < 16; i++) out[plaintext.length + i] = key[i];
    return out;
  },
  decrypt: async (ciphertext, key) => {
    if (ciphertext.length < 16) return null;
    for (let i = 0; i < 16; i++) {
      if (ciphertext[ciphertext.length - 16 + i] !== key[i]) return null;
    }
    const plain = ciphertext.slice(0, ciphertext.length - 16);
    const out = new Uint8Array(plain.length);
    for (let i = 0; i < plain.length; i++) out[i] = plain[i] ^ key[i % 32];
    return out;
  },
  sha256Hex: async (bytes) => {
    let h = 0x811c9dc5;
    for (let i = 0; i < bytes.length; i++) {
      h ^= bytes[i];
      h = Math.imul(h, 0x01000193) >>> 0;
    }
    return h.toString(16).padStart(8, '0');
  },
  randomBytes: async (n) => {
    const out = new Uint8Array(n);
    for (let i = 0; i < n; i++) out[i] = (i * 17 + 3) & 0xff;
    return out;
  },
});

const sampleConv = () => makeConversation('c1', [
  toMessageNode(makeUserMessage('备份我')),
  toMessageNode(makeAssistantMessage('好的')),
], { title: '备份会话' });

test('buildSyncPayload exports conversations', () => {
  const payload = buildSyncPayload({
    conversations: [sampleConv()],
    appVersionName: '1.0',
    appVersionCode: 1,
    deviceId: 'dev',
    passphrase: 'pw',
  }, 1000);
  assert.equal(payload.conversations.length, 1);
  assert.equal(payload.conversations[0].title, '备份会话');
});

test('create + inspect + restore roundtrip (passphrase)', async () => {
  const crypto = mockCrypto();
  const bytes = await createSyncBackup(crypto, {
    conversations: [sampleConv()],
    settings: { theme: 'dark' },
    appVersionName: '2.6.8',
    appVersionCode: 396,
    deviceId: 'harmony-1',
    deviceLabel: 'Mate',
    passphrase: 'secret',
  }, 2000);
  const preview = await inspectSyncArchive(bytes, 'backup.bin');
  assert.equal(preview.manifest.encrypted, true);
  assert.equal(preview.manifest.passphraseProtected, true);
  assert.equal(preview.manifest.appVersionName, '2.6.8');
  assert.ok(preview.manifest.payloadSha256.length > 0);
  assert.equal(preview.needsPassphrase, true);

  const restored = await restoreSyncBackup(crypto, bytes, 'secret');
  assert.equal(restored.payload.conversations[0].title, '备份会话');
  assert.deepEqual(restored.payload.settings, { theme: 'dark' });
});

test('restore wrong passphrase / missing passphrase', async () => {
  const crypto = mockCrypto();
  const bytes = await createSyncBackup(crypto, {
    conversations: [sampleConv()],
    appVersionName: '1',
    appVersionCode: 1,
    deviceId: 'd',
    passphrase: 'right',
  }, 1);
  await assert.rejects(() => restoreSyncBackup(crypto, bytes, null), /需要密码/);
  // mock decrypt always "succeeds" with wrong key producing garbage JSON → parse fail
  await assert.rejects(() => restoreSyncBackup(crypto, bytes, 'wrong'));
});

test('create without passphrase → needsPassphrase false', async () => {
  const crypto = mockCrypto();
  const bytes = await createSyncBackup(crypto, {
    conversations: [sampleConv()],
    appVersionName: '1',
    appVersionCode: 1,
    deviceId: 'd',
    passphrase: null,
  }, 1);
  const preview = await inspectSyncArchive(bytes, 'b');
  assert.equal(preview.manifest.passphraseProtected, false);
  const restored = await restoreSyncBackup(crypto, bytes, null);
  assert.equal(restored.payload.conversations.length, 1);
});

test('tampered payload fails checksum', async () => {
  const crypto = mockCrypto();
  const bytes = await createSyncBackup(crypto, {
    conversations: [sampleConv()],
    appVersionName: '1',
    appVersionCode: 1,
    deviceId: 'd',
    passphrase: 'x',
  }, 1);
  // flip one payload byte
  const archive = JSON.parse(new TextDecoder().decode(bytes)) as Array<{ name: string; b64: string }>;
  const pay = archive.find((e) => e.name === PAYLOAD_ENTRY);
  assert.ok(pay !== undefined);
  pay.b64 = (pay.b64[0] === 'A' ? 'B' : 'A') + pay.b64.slice(1);
  const tampered = new TextEncoder().encode(JSON.stringify(archive));
  await assert.rejects(() => restoreSyncBackup(crypto, tampered, 'x'), /校验失败/);
});

test('emoji / 4-byte UTF-8 settings roundtrip', async () => {
  const crypto = mockCrypto();
  const bytes = await createSyncBackup(crypto, {
    conversations: [sampleConv()],
    settings: { note: '🚀😀🎉' },
    appVersionName: '1',
    appVersionCode: 1,
    deviceId: 'd',
    passphrase: 'pw',
  }, 1);
  const restored = await restoreSyncBackup(crypto, bytes, 'pw');
  assert.equal(restored.payload.settings?.note, '🚀😀🎉');
});

// ===== R02：完整 nodes DTO 可逆（分支 / selectIndex / 元数据） =====

const branchConv = () => {
  const v1 = makeAssistantMessage('版本1');
  v1.id = 'v1';
  v1.createdAt = '2026-07-28T10:00:00.000Z';
  const v2 = makeAssistantMessage('版本2');
  v2.id = 'v2';
  v2.createdAt = '2026-07-28T10:01:00.000Z';
  const user = makeUserMessage('问题');
  user.id = 'u1';
  user.createdAt = '2026-07-28T09:59:00.000Z';
  return makeConversation('cb', [
    makeMessageNode([user], 0, 'node-user'),
    makeMessageNode([v1, v2], 1, 'node-branch'),
  ], {
    title: '分支会话',
    createAt: '2026-07-28T09:00:00.000Z',
    updateAt: '2026-07-28T10:01:00.000Z',
    chatSuggestions: ['再试一次', '展开'],
    isPinned: true,
    autoApproveToolCalls: true,
  });
};

test('R02: 完整 nodes DTO 经真实序列化器可逆(分支/selectIndex/元数据不缩水)', async () => {
  const crypto = mockCrypto();
  const original = branchConv();
  const bytes = await createSyncBackup(crypto, {
    conversations: [original],
    appVersionName: '1',
    appVersionCode: 1,
    deviceId: 'd',
    passphrase: 'pw',
  }, 1);
  const restored = await restoreSyncBackup(crypto, bytes, 'pw');
  const dto = restored.payload.conversations[0];
  assert.equal(dto.nodes.length, 2, 'node 数保留');
  assert.equal(dto.nodes[1].messages.length, 2, '替代分支完整保留(非打平)');
  assert.equal(dto.nodes[1].selectIndex, 1, 'selectIndex 保留');
  assert.equal(dto.isPinned, true);
  assert.equal(dto.autoApproveToolCalls, true);
  assert.deepEqual(dto.chatSuggestions, ['再试一次', '展开']);

  const back = conversationFromSyncDto(dto, () => '2099-01-01T00:00:00.000Z');
  assert.deepEqual(back, original, '完整会话深等价');
});

test('R02: 导出即校验——非法 selectIndex 在打包阶段抛出，不产出不可恢复归档', () => {
  const bad = makeConversation('bad', [
    makeMessageNode([makeUserMessage('x')], 5, 'node-oob'),
  ], { createAt: '2026-07-28T09:00:00.000Z', updateAt: '2026-07-28T09:01:00.000Z' });
  assert.throws(() => buildSyncPayload({
    conversations: [bad], appVersionName: '1', appVersionCode: 1, deviceId: 'd', passphrase: null,
  }, 1), /selectIndex/);
});

// ===== R03：恢复前整体校验（结构 / ID / 时间 / messages / settings） =====

const validMsg = () => ({
  id: 'm1', role: 'user', parts: [{ type: 'text', text: 'hi' }],
  annotations: [], createdAt: '2026-07-28T09:00:00.000Z',
});

const validConv = () => ({
  id: 'c1',
  assistantId: 'a1',
  title: 't',
  createAt: '2026-07-28T09:00:00.000Z',
  updateAt: '2026-07-28T09:01:00.000Z',
  chatSuggestions: [],
  isPinned: false,
  autoApproveToolCalls: false,
  nodes: [{ id: 'n1', selectIndex: 0, messages: [validMsg()] }],
});

const validPayload = () => ({
  version: 2,
  createdAt: 1,
  conversations: [validConv()],
});

test('R03: 校验拒绝缺 conversations / 非数组会话 / 缺 messages / selectIndex 越界', () => {
  assert.throws(() => validateSyncPayload({ version: 2, createdAt: 1 }), /缺少会话列表/);
  assert.throws(() => validateSyncPayload({ version: 2, createdAt: 1, conversations: {} }), /缺少会话列表/);

  // v1 legacy：缺 messages 数组
  const legacyMissing = {
    version: 1, createdAt: 1,
    conversations: [{
      id: 'c1', assistantId: 'a1', title: 't',
      createAt: '2026-07-28T09:00:00.000Z', updateAt: '2026-07-28T09:01:00.000Z',
    }],
  };
  assert.throws(() => validateSyncPayload(legacyMissing), /缺少 messages/);

  const oob = {
    version: 2, createdAt: 1,
    conversations: [{
      ...validConv(),
      nodes: [{ id: 'n1', selectIndex: 3, messages: [validMsg()] }],
    }],
  };
  assert.throws(() => validateSyncPayload(oob), /selectIndex/);
});

test('R03: 校验拒绝重复会话 ID 与跨会话重复 node ID(含跨 conv PK 唯一)', () => {
  const dupConv = { version: 2, createdAt: 1, conversations: [validConv(), validConv()] };
  assert.throws(() => validateSyncPayload(dupConv), /重复会话 ID/);

  // 两份不同会话共用同一 node id → message_node PK 冲突，须拒绝
  const dupNode = {
    version: 2, createdAt: 1,
    conversations: [
      validConv(),
      { ...validConv(), id: 'c2' },
    ],
  };
  assert.throws(() => validateSyncPayload(dupNode), /重复节点 ID/);
});

test('R03: 非法时间 / 畸形消息 / settings 枚举在校验阶段以非持久化错误拒绝', () => {
  const badTime = {
    version: 2, createdAt: 1,
    conversations: [{ ...validConv(), createAt: 'not-a-date' }],
  };
  assert.throws(() => validateSyncPayload(badTime), /时间非法/);

  const badParts = {
    version: 2, createdAt: 1,
    conversations: [{
      ...validConv(),
      nodes: [{ id: 'n1', selectIndex: 0, messages: [{ ...validMsg(), parts: [{ type: 'text' }] }] }],
    }],
  };
  assert.throws(() => validateSyncPayload(badParts));

  const badSettings = {
    version: 2, createdAt: 1, conversations: [validConv()],
    settings: { assistants: [{ id: 'x', reasoningLevel: 'NOPE' }] },
  };
  assert.throws(() => validateSyncPayload(badSettings), /ReasoningLevel/);
});

test('R03: v1 旧归档只读兼容——还原为单分支 node，元数据回默认', () => {
  const v1 = {
    version: 1,
    createdAt: 1,
    conversations: [{
      id: 'c1',
      title: '旧',
      assistantId: 'a1',
      createAt: '2026-07-28T09:00:00.000Z',
      updateAt: '2026-07-28T09:01:00.000Z',
      messages: [validMsg()],
    }],
  };
  const payload = validateSyncPayload(v1);
  assert.equal(payload.conversations[0].nodes.length, 1);
  assert.equal(payload.conversations[0].nodes[0].selectIndex, 0);
  assert.deepEqual(payload.conversations[0].chatSuggestions, []);
  assert.equal(payload.conversations[0].isPinned, false);
});

// P2-1:parseUIMessage 对缺 id/createdAt 会补新值/now()，必须在 parse 前强验原始字段，
// 否则损坏归档被静默修补、恢复不忠实；节点内消息 id 亦须唯一。
test('P2-1: 校验在 parse 前拒绝缺/空消息 id、非 ISO createdAt 与节点内重复消息 id', () => {
  const noId = () => {
    const m = validMsg() as Record<string, unknown>;
    delete m.id;
    return { version: 2, createdAt: 1, conversations: [{ ...validConv(), nodes: [{ id: 'n1', selectIndex: 0, messages: [m] }] }] };
  };
  assert.throws(() => validateSyncPayload(noId()), /缺少有效 .*message\[0\]\.id/);

  const badCreated = {
    version: 2, createdAt: 1,
    conversations: [{
      ...validConv(),
      nodes: [{ id: 'n1', selectIndex: 0, messages: [{ ...validMsg(), createdAt: 'yesterday' }] }],
    }],
  };
  assert.throws(() => validateSyncPayload(badCreated), /时间非法/);

  const dupMsg = {
    version: 2, createdAt: 1,
    conversations: [{
      ...validConv(),
      nodes: [{ id: 'n1', selectIndex: 0, messages: [validMsg(), validMsg()] }],
    }],
  };
  assert.throws(() => validateSyncPayload(dupMsg), /消息 ID 重复/);

  // v1 legacy 同样强验（此前只 push 序列化结果、不校验原始 createdAt）
  const legacyBadCreated = {
    version: 1, createdAt: 1,
    conversations: [{
      id: 'c1', assistantId: 'a1', title: 't',
      createAt: '2026-07-28T09:00:00.000Z', updateAt: '2026-07-28T09:01:00.000Z',
      messages: [{ ...validMsg(), createdAt: '' }],
    }],
  };
  assert.throws(() => validateSyncPayload(legacyBadCreated), /时间非法/);
});
