// Topic E isolated probe — exercises REAL sync_snapshot.ts domain logic with a
// synthetic SyncCryptoPort (no network, no device, no real credentials).
// Purpose: characterize payload validation / duplicate-id / settings-secret behaviour.
import {
  createSyncBackup, restoreSyncBackup, inspectSyncArchive,
  MANIFEST_ENTRY, PAYLOAD_ENTRY, type SyncCryptoPort,
} from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/sync_snapshot.ts';
import { serializeProviderSettingList } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/provider_settings_serialize.ts';
import { makeProviderSettingOpenAIVariant, makeProviderModel } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/provider_settings.ts';

const enc = (s: string): Uint8Array => new TextEncoder().encode(s);
const dec = (b: Uint8Array): string => new TextDecoder().decode(b);
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
  randomBytes: async (n: number) => { const o = new Uint8Array(n); for (let i = 0; i < n; i++) o[i] = (i * 7) & 0xff; return o; },
});

// Build a raw archive directly (bypassing typed buildSyncPayload) so we can inject
// malformed / duplicate JSON payloads exactly as a hand-crafted .abin would contain.
const craftArchive = (payloadJson: string, passphraseProtected: boolean, crypto: SyncCryptoPort) =>
  (async () => {
    const salt = await crypto.randomBytes(16);
    const iv = await crypto.randomBytes(12);
    const saltB64 = b64(salt);
    const ivB64 = b64(iv);
    const key = await crypto.deriveKey(passphraseProtected ? 'pw' : '', saltB64, 100000);
    const ct = await crypto.encrypt(enc(payloadJson), key, ivB64);
    const sha = await crypto.sha256Hex(ct);
    const manifest = {
      archiveVersion: 1, appVersionName: 'probe', appVersionCode: 1, createdAt: 1,
      deviceId: 'probe', deviceLabel: '', mode: 'STANDARD', encrypted: true,
      passphraseProtected, kdf: { name: 'SHA256-ITERATED', saltBase64: saltB64, iterations: 100000 },
      cipher: { name: 'AES-GCM-256', ivBase64: ivB64 }, payloadSha256: sha,
    };
    const entries = [
      { name: MANIFEST_ENTRY, b64: b64(enc(JSON.stringify(manifest))) },
      { name: PAYLOAD_ENTRY, b64: b64(ct) },
    ];
    return enc(JSON.stringify(entries));
  })();

const main = async () => {
  const crypto = mockCrypto();
  const out: string[] = [];
  const log = (s: string) => out.push(s);

  // 1) missing `conversations` key
  {
    const bytes = await craftArchive(JSON.stringify({ version: 1, createdAt: 1 }), false, crypto);
    try {
      const r = await restoreSyncBackup(crypto, bytes, null);
      log(`[1] missing conversations: restore RESOLVED (no validation). typeof payload.conversations=${typeof (r.payload as any).conversations}; Array.isArray=${Array.isArray((r.payload as any).conversations)}`);
    } catch (e) { log(`[1] missing conversations: threw ${(e as Error).message}`); }
  }

  // 2) conversations not an array (object)
  {
    const bytes = await craftArchive(JSON.stringify({ version: 1, conversations: { a: 1 } }), false, crypto);
    try {
      const r = await restoreSyncBackup(crypto, bytes, null);
      log(`[2] conversations=object: restore RESOLVED, Array.isArray=${Array.isArray((r.payload as any).conversations)}`);
    } catch (e) { log(`[2] conversations=object: threw ${(e as Error).message}`); }
  }

  // 3) duplicate conversation ids + missing messages
  {
    const payload = {
      version: 1,
      conversations: [
        { id: 'dup', title: 'first', assistantId: 'a', createAt: 't', updateAt: 't', messages: [] },
        { id: 'dup', title: 'second', assistantId: 'a', createAt: 't', updateAt: 't', messages: [] },
        { id: 'nomessages', title: 'bad', assistantId: 'a', createAt: 't', updateAt: 't' },
      ],
    };
    const bytes = await craftArchive(JSON.stringify(payload), false, crypto);
    const r = await restoreSyncBackup(crypto, bytes, null);
    const convs = (r.payload as any).conversations as any[];
    const ids = convs.map((c) => c.id);
    const withMissing = convs.filter((c) => c.messages === undefined).length;
    log(`[3] duplicate+missing: restore RESOLVED. payload ids=${JSON.stringify(ids)}; entries with messages===undefined=${withMissing}. A per-id REPLACE save would keep only 1 'dup'.`);
  }

  // 4) inspectSyncArchive on a valid archive keeps mode STANDARD even when caller
  //    omitted mode, and settings secrets are the caller's blob (not redacted here).
  {
    const bytes = await createSyncBackup(crypto, {
      conversations: [], settings: { providers: [{ apiKey: 'sk-probe-not-real' }] } as any,
      appVersionName: 'probe', appVersionCode: 1, deviceId: 'd', passphrase: null,
    }, 1);
    const prev = await inspectSyncArchive(bytes, 'x.abin');
    log(`[4] createSyncBackup default mode=${prev.manifest.mode}; settings blob is stored verbatim inside payload (see [5]).`);
  }

  // 5) provider serializer includes apiKey/privateKey verbatim
  {
    const p = makeProviderSettingOpenAIVariant({
      id: 'p1', enabled: true, name: 'n', apiKey: 'sk-probe-not-real', baseUrl: 'https://x',
      chatCompletionsPath: '/c', useResponseApi: false, models: [
        makeProviderModel({ modelId: 'm', displayName: 'm', id: 'm1', type: 'chat' }),
      ],
    } as any);
    const json = serializeProviderSettingList([p]);
    log(`[5] serializeProviderSettingList contains apiKey field: ${json.includes('"apiKey"')}; contains literal secret value: ${json.includes('sk-probe-not-real')}`);
  }

  console.log(out.join('\n'));
};

main().catch((e) => { console.error('PROBE ERROR', e); process.exit(1); });
