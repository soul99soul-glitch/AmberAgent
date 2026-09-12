import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createSyncBackup, restoreSyncBackup, inspectSyncArchive,
  buildSyncPayload, MANIFEST_ENTRY, PAYLOAD_ENTRY, type SyncCryptoPort,
} from '../main/ets/chat/sync_snapshot.ts';
import { makeConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
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
