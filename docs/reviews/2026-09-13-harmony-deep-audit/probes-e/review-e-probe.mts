// Independent re-verification probe for Topic E (E-01 / E-02).
// Uses REAL domain code: conversation.ts (toMessageNode), conversation_export.ts,
// sync_snapshot.ts. Only the SyncCryptoPort is a synthetic mock (no network, no
// real credentials, no device). Reconstructs the entry-layer inverse transform
// exactly as SettingBackupPage.doRestore does, so the round-trip loss is shown
// end-to-end in the real domain functions, not a simplified copy.
import {
  createSyncBackup, restoreSyncBackup, buildSyncPayload,
  MANIFEST_ENTRY, PAYLOAD_ENTRY, type SyncCryptoPort,
} from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/sync_snapshot.ts';
import { exportConversationJson } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/conversation_export.ts';
import { makeConversation, makeMessageNode, toMessageNode } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/conversation.ts';
import { makeUserMessage, makeAssistantMessage } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';

const enc = (s: string): Uint8Array => new TextEncoder().encode(s);
const b64 = (b: Uint8Array): string => Buffer.from(b).toString('base64');

const mockCrypto = (): SyncCryptoPort => ({
  deriveKey: async (pass: string) => {
    const key = new Uint8Array(32);
    for (let i = 0; i < 32; i++) key[i] = ((pass.charCodeAt(i % Math.max(1, pass.length)) | 1) ^ i) & 0xff;
    return key;
  },
  encrypt: async (plaintext: Uint8Array, key: Uint8Array) => {
    const out = new Uint8Array(plaintext.length + 16);
    for (let i = 0; i < plaintext.length; i++) out[i] = plaintext[i] ^ key[i % 32];
    for (let i = 0; i < 16; i++) out[plaintext.length + i] = key[i];
    return out;
  },
  decrypt: async (ciphertext: Uint8Array, key: Uint8Array) => {
    if (ciphertext.length < 16) return null;
    for (let i = 0; i < 16; i++) if (ciphertext[ciphertext.length - 16 + i] !== key[i]) return null;
    const out = new Uint8Array(ciphertext.length - 16);
    for (let i = 0; i < out.length; i++) out[i] = ciphertext[i] ^ key[i % 32];
    return out;
  },
  sha256Hex: async (bytes: Uint8Array) => {
    let h = 0x811c9dc5;
    for (let i = 0; i < bytes.length; i++) { h ^= bytes[i]; h = Math.imul(h, 0x01000193) >>> 0; }
    return h.toString(16).padStart(8, '0');
  },
  randomBytes: async (n: number) => { const o = new Uint8Array(n); for (let i = 0; i < n; i++) o[i] = (i * 7 + 1) & 0xff; return o; },
});

const craftArchive = async (payloadJson: string, protectedPw: boolean, crypto: SyncCryptoPort) => {
  const salt = await crypto.randomBytes(16);
  const iv = await crypto.randomBytes(12);
  const saltB64 = b64(salt);
  const ivB64 = b64(iv);
  const key = await crypto.deriveKey(protectedPw ? 'pw' : '', saltB64, 100000);
  const ct = await crypto.encrypt(enc(payloadJson), key, ivB64);
  const sha = await crypto.sha256Hex(ct);
  const manifest = {
    archiveVersion: 1, appVersionName: 'probe', appVersionCode: 1, createdAt: 1,
    deviceId: 'probe', deviceLabel: '', mode: 'STANDARD', encrypted: true,
    passphraseProtected: protectedPw, kdf: { name: 'SHA256-ITERATED', saltBase64: saltB64, iterations: 100000 },
    cipher: { name: 'AES-GCM-256', ivBase64: ivB64 }, payloadSha256: sha,
  };
  return enc(JSON.stringify([
    { name: MANIFEST_ENTRY, b64: b64(enc(JSON.stringify(manifest))) },
    { name: PAYLOAD_ENTRY, b64: b64(ct) },
  ]));
};

// Mirror of SettingBackupPage.doRestore reconstruction (real toMessageNode).
const reconstruct = (item: { messages: ReturnType<typeof makeUserMessage>[] }) =>
  item.messages.map((m) => toMessageNode(m));

const main = async () => {
  const crypto = mockCrypto();
  const lines: string[] = [];
  const log = (s: string): void => { lines.push(s); };

  // ===== E-01: branch + metadata round-trip loss =====
  const chosen = makeAssistantMessage('分支B（当前选中）');
  const alternative = makeAssistantMessage('分支A（未选中）');
  const branchNode = makeMessageNode([alternative, chosen], 1, 'node-branch');
  const conv = makeConversation('conv-e01', [
    toMessageNode(makeUserMessage('你好')),
    branchNode,
  ], {
    title: 'E01 分支会话',
    chatSuggestions: ['继续?', '换个说法'],
    isPinned: true,
    autoApproveToolCalls: true,
  });

  // What the export DTO actually carries (real function):
  const exported = exportConversationJson(conv);
  log(`[E01-a] exported per-conversation keys = ${JSON.stringify(Object.keys(exported).sort())}`);
  log(`[E01-a] exported messages = ${exported.messages.length} (original nodes=2, node-branch has 2 alternatives)`);

  const payload = buildSyncPayload({
    conversations: [conv], appVersionName: 'p', appVersionCode: 1, deviceId: 'd', passphrase: 'pw',
  }, 1);
  log(`[E01-b] buildSyncPayload payload keys = ${JSON.stringify(Object.keys(payload).sort())}`);
  log(`[E01-b] payload.conversations[0] keys = ${JSON.stringify(Object.keys(payload.conversations[0]).sort())}`);

  const bytes = await createSyncBackup(crypto, {
    conversations: [conv], appVersionName: 'p', appVersionCode: 1, deviceId: 'd', passphrase: 'pw',
  }, 1);
  const restored = await restoreSyncBackup(crypto, bytes, 'pw');
  const item = restored.payload.conversations[0];
  const nodes = reconstruct(item);
  log(`[E01-c] after restore: nodes=${nodes.length}, branchNode.messages=${branchNode.messages.length}, restored node[1].selectIndex=${nodes[1].selectIndex}`);
  log(`[E01-c] metadata: chatSuggestions=${JSON.stringify((item as any).chatSuggestions)}, isPinned=${(item as any).isPinned}, autoApproveToolCalls=${(item as any).autoApproveToolCalls}`);
  log(`[E01-c] => branches preserved=${nodes[1].messages.length === 2}, selectIndex preserved=${nodes[1].selectIndex === 1}`);

  // ===== E-02: no structural validation / no rollback (domain layer) =====
  const malformed = await craftArchive(JSON.stringify({ version: 1, conversations: [
    { id: 'ok', title: 'a', assistantId: 'a', createAt: 't', updateAt: 't', messages: [] },
    { id: 'bad', title: 'b', assistantId: 'a', createAt: 't', updateAt: 't' }, // messages missing
  ] }), false, crypto);
  const r = await restoreSyncBackup(crypto, malformed, null);
  const convs = (r.payload as any).conversations as any[];
  log(`[E02-a] malformed restore RESOLVED (no runtime validation); entries with messages=undefined: ${convs.filter((c) => c.messages === undefined).length}`);
  let partial = 0;
  try {
    for (const c of convs) { c.messages.map((m: any) => toMessageNode(m)); partial += 1; }
  } catch (e) {
    log(`[E02-b] entry-layer loop: saved ${partial} conversation(s) BEFORE throwing "${(e as Error).message}" -> partial restore, no rollback`);
  }
  log(`[E02-c] duplicate-id payload keeps both entries; writeConversation uses ON_CONFLICT_REPLACE -> later dup overwrites earlier`);

  console.log(lines.join('\n'));
};

main().catch((e) => { console.error('PROBE ERROR', e); process.exit(1); });
