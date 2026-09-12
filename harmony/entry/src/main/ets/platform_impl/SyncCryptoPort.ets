// SyncCryptoPortImpl — SyncCryptoPort 的 CryptoArchitectureKit 实现
//
// 策略（诚实、可测）：
// - sha256 / randomBytes：标准 SHA-256 + LCG（IV/salt）
// - deriveKey：迭代 SHA-256(salt||pass)（与 Android native PBKDF2 字节不互通，登记；
//   本端自产自销备份闭环）
// - encrypt/decrypt：AES-256-GCM，密文末 16B 为 tag

import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { SyncCryptoPort } from '@amber/chat-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'SyncCrypto';

const toBlob = (bytes: Uint8Array): cryptoFramework.DataBlob => {
  const buf = new ArrayBuffer(bytes.length);
  new Uint8Array(buf).set(bytes);
  return { data: buf };
};

const fromBlob = (blob: cryptoFramework.DataBlob | undefined | null): Uint8Array => {
  if (blob === undefined || blob === null) return new Uint8Array(0);
  return new Uint8Array(blob.data);
};

const concat = (a: Uint8Array, b: Uint8Array): Uint8Array => {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
};

const sha256Bytes = (bytes: Uint8Array): Uint8Array => {
  const md = cryptoFramework.createMd('SHA256');
  md.updateSync(toBlob(bytes));
  return fromBlob(md.digestSync());
};

const iteratedDeriveKey = (
  passphrase: string, salt: Uint8Array, iterations: number,
): Uint8Array => {
  const pass: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(passphrase);
  let block = sha256Bytes(concat(salt, pass));
  const n = Math.max(1, Math.min(iterations, 100_000));
  for (let i = 1; i < n; i++) {
    block = sha256Bytes(concat(block, pass));
  }
  return block.slice(0, 32);
};

export const createEntrySyncCryptoPort = (): SyncCryptoPort => ({
  deriveKey: async (passphrase: string, saltBase64: string, iterations: number): Promise<Uint8Array> => {
    const salt: Uint8Array = new util.Base64Helper().decodeSync(saltBase64);
    return iteratedDeriveKey(passphrase, salt, iterations);
  },

  encrypt: async (plaintext: Uint8Array, key: Uint8Array, ivBase64: string): Promise<Uint8Array> => {
    const iv: Uint8Array = new util.Base64Helper().decodeSync(ivBase64);
    const kdf = cryptoFramework.createKdf('PBKDF2|SHA256');
    const params: cryptoFramework.KdfParamsSpec = {
      algName: 'PBKDF2',
      salt: { data: new ArrayBuffer(0) },
      iterations: 1,
      keyLength: 256,
    };
    // 用 kdf 从 raw key 字节导出 SymKey 对象（同输入 → 同 key）
    const keyObj = await kdf.deriveKey(toBlob(key), params as cryptoFramework.KdfParamsSpec);
    const cipher = cryptoFramework.createCipher('AES256|GCM');
    const gcmParams: cryptoFramework.IvParamsSpec = {
      algName: 'GCM',
      iv: toBlob(iv),
    };
    await cipher.init(cryptoFramework.CryptoMode.ENCRYPT_MODE, keyObj, gcmParams as cryptoFramework.IvParamsSpec);
    const u = await cipher.doUpdate(toBlob(plaintext));
    const f = await cipher.doFinal(toBlob(new Uint8Array(0)));
    return concat(fromBlob(u), fromBlob(f));
  },

  decrypt: async (ciphertext: Uint8Array, key: Uint8Array, ivBase64: string): Promise<Uint8Array | null> => {
    try {
      const iv: Uint8Array = new util.Base64Helper().decodeSync(ivBase64);
      const kdf = cryptoFramework.createKdf('PBKDF2|SHA256');
      const params: cryptoFramework.KdfParamsSpec = {
        algName: 'PBKDF2',
        salt: { data: new ArrayBuffer(0) },
        iterations: 1,
        keyLength: 256,
      };
      const keyObj = await kdf.deriveKey(toBlob(key), params as cryptoFramework.KdfParamsSpec);
      const cipher = cryptoFramework.createCipher('AES256|GCM');
      const gcmParams: cryptoFramework.IvParamsSpec = {
        algName: 'GCM',
        iv: toBlob(iv),
      };
      await cipher.init(cryptoFramework.CryptoMode.DECRYPT_MODE, keyObj, gcmParams as cryptoFramework.IvParamsSpec);
      const u = await cipher.doUpdate(toBlob(ciphertext));
      const f = await cipher.doFinal(toBlob(new Uint8Array(0)));
      return concat(fromBlob(u), fromBlob(f));
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'decrypt failed: %{public}s',
        e instanceof Error ? e.message : String(e));
      return null;
    }
  },

  sha256Hex: async (bytes: Uint8Array): Promise<string> => {
    const digest = sha256Bytes(bytes);
    let hex = '';
    for (const b of digest) {
      hex += b.toString(16).padStart(2, '0');
    }
    return hex;
  },

  randomBytes: async (n: number): Promise<Uint8Array> => {
    const out = new Uint8Array(n);
    let seed = (Date.now() ^ Math.imul(n, 0x9e3779b9)) >>> 0;
    for (let i = 0; i < n; i++) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      out[i] = (seed >>> 16) & 0xff;
    }
    return out;
  },
});
