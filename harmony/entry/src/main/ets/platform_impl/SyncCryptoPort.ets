// SyncCryptoPortImpl — AES-GCM-256 + SHA256-ITERATED KDF（CryptoArchitectureKit）
//
// encrypt/decrypt：cryptoFramework AES256|GCM|NoPadding。GCM 下
// updateSync(data) 出 ciphertext、doFinalSync(null) 出 authTag(16B)；
// encrypt 返回 ciphertext||tag，decrypt 截末尾 16B 作 tag 传入 GcmParamsSpec，
// doFinal 校验失败（错密码/篡改）→ null；init/参数错误向上抛。
// randomBytes：cryptoFramework CSPRNG（createRandom），禁止可预测 PRNG。
// deriveKey：本端 iterated SHA-256（与 Android PBKDF2 不互通；manifest 标
// SHA256-ITERATED）。设备侧确认 createKdf 后再切换 PBKDF2。

import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import type { SyncCryptoPort } from '@amber/chat-domain';

const sha256Bytes = (bytes: Uint8Array): Uint8Array => {
  const md = cryptoFramework.createMd('SHA256');
  md.updateSync({ data: bytes });
  const out = md.digestSync();
  return new Uint8Array(out.data);
};

const GCM_ALG: string = 'AES256|GCM|NoPadding';
// GCM authTag 在 encrypt 时由 doFinalSync(null) 产出；算法库目前仅支持 16B。
const GCM_TAG_LEN: number = 16;

const blobOf = (bytes: Uint8Array): cryptoFramework.DataBlob => {
  const blob: cryptoFramework.DataBlob = { data: bytes };
  return blob;
};

const emptyBlob = (): cryptoFramework.DataBlob => blobOf(new Uint8Array(0));

const importAes256Key = (key: Uint8Array): cryptoFramework.SymKey =>
  cryptoFramework.createSymKeyGenerator('AES256').convertKeySync(blobOf(key));

// encrypt：updateSync(plaintext)→ciphertext，doFinalSync(null)→authTag；
// 返回 ciphertext||tag(16B)，与 SyncCryptoPort 合同/manifest 一致。
const encryptGcm = (plaintext: Uint8Array, key: Uint8Array, iv: Uint8Array): Uint8Array => {
  const cipher = cryptoFramework.createCipher(GCM_ALG);
  const params: cryptoFramework.GcmParamsSpec = {
    algName: 'GcmParamsSpec',
    iv: blobOf(iv),
    aad: emptyBlob(),
    authTag: blobOf(new Uint8Array(GCM_TAG_LEN)),
  };
  cipher.initSync(cryptoFramework.CryptoMode.ENCRYPT_MODE, importAes256Key(key), params);
  const body: cryptoFramework.DataBlob = cipher.updateSync(blobOf(plaintext));
  params.authTag = cipher.doFinalSync(null);
  return concatBytes(new Uint8Array(body.data), new Uint8Array(params.authTag.data));
};

// decrypt：截末尾 16B 作 authTag 传 GCM 参数，updateSync(ct)→plaintext，
// doFinalSync(null) 校验 tag，认证失败抛异常 → null。init 失败向上抛（配置/密钥错误）。
const decryptGcm = (ciphertext: Uint8Array, key: Uint8Array, iv: Uint8Array): Uint8Array | null => {
  if (ciphertext.length < GCM_TAG_LEN) {
    return null;
  }
  const tagStart: number = ciphertext.length - GCM_TAG_LEN;
  const params: cryptoFramework.GcmParamsSpec = {
    algName: 'GcmParamsSpec',
    iv: blobOf(iv),
    aad: emptyBlob(),
    authTag: blobOf(ciphertext.slice(tagStart)),
  };
  const cipher = cryptoFramework.createCipher(GCM_ALG);
  cipher.initSync(cryptoFramework.CryptoMode.DECRYPT_MODE, importAes256Key(key), params);
  try {
    const body: cryptoFramework.DataBlob = cipher.updateSync(blobOf(ciphertext.slice(0, tagStart)));
    cipher.doFinalSync(null);
    return new Uint8Array(body.data);
  } catch (_err) {
    return null;
  }
};

const iteratedDeriveKey = (
  passphrase: string, salt: Uint8Array, iterations: number,
): Uint8Array => {
  // encodeInto('') returns undefined by the API contract; an empty password is zero bytes.
  const pass: Uint8Array = passphrase.length === 0 ? new Uint8Array(0)
    : util.TextEncoder.create('utf-8').encodeInto(passphrase);
  let block = sha256Bytes(concatBytes(salt, pass));
  const n = Math.max(1, Math.min(iterations, 100_000));
  for (let i = 1; i < n; i++) {
    block = sha256Bytes(concatBytes(block, pass));
  }
  return block.slice(0, 32);
};

const concatBytes = (a: Uint8Array, b: Uint8Array): Uint8Array => {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
};

export const createEntrySyncCryptoPort = (): SyncCryptoPort => ({
  deriveKey: async (passphrase: string, saltBase64: string, iterations: number): Promise<Uint8Array> => {
    const salt: Uint8Array = new util.Base64Helper().decodeSync(saltBase64);
    return iteratedDeriveKey(passphrase, salt, iterations);
  },

  encrypt: async (plaintext: Uint8Array, key: Uint8Array, ivBase64: string): Promise<Uint8Array> => {
    const iv: Uint8Array = new util.Base64Helper().decodeSync(ivBase64);
    try {
      return encryptGcm(plaintext, key, iv);
    } catch (e) {
      const detail: string = e instanceof Error ? e.message : 'unknown';
      throw new Error(`AES-GCM encrypt failed: ${detail}`);
    }
  },

  decrypt: async (ciphertext: Uint8Array, key: Uint8Array, ivBase64: string): Promise<Uint8Array | null> => {
    const iv: Uint8Array = new util.Base64Helper().decodeSync(ivBase64);
    return decryptGcm(ciphertext, key, iv);
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
    const rnd = cryptoFramework.createRandom();
    const buf: cryptoFramework.DataBlob = rnd.generateRandomSync(n);
    return new Uint8Array(buf.data);
  },
});
