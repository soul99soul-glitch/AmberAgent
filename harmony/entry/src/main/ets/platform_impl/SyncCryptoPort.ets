// SyncCryptoPortImpl — SHA-256 派生密钥流 + XOR（可编译本地备份）
//
// 登记：CryptoArchitectureKit 的 AES-GCM / Kdf.deriveKey 在当前 SDK 头文件上
// API 名不稳定（doUpdate/deriveKey 缺失）。本实现用 SHA-256(key||iv||counter)
// 生成密钥流做对称加解密，**仅保证本端自产自销备份可逆**，不承诺与
// Android sync-crypto 字节互通。设备侧验证 AES-GCM 后再切换。

import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';
import type { SyncCryptoPort } from '@amber/chat-domain';

const concat = (a: Uint8Array, b: Uint8Array): Uint8Array => {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
};

const sha256Bytes = (bytes: Uint8Array): Uint8Array => {
  const md = cryptoFramework.createMd('SHA256');
  md.updateSync({ data: bytes });
  const out = md.digestSync();
  return new Uint8Array(out.data);
};

const u32be = (n: number): Uint8Array => {
  const out = new Uint8Array(4);
  out[0] = (n >>> 24) & 0xff;
  out[1] = (n >>> 16) & 0xff;
  out[2] = (n >>> 8) & 0xff;
  out[3] = n & 0xff;
  return out;
};

const xorWithKeystream = (
  data: Uint8Array, key: Uint8Array, iv: Uint8Array, tag: number,
): Uint8Array => {
  const out = new Uint8Array(data.length);
  let offset = 0;
  let counter = 0;
  while (offset < data.length) {
    const block = sha256Bytes(concat(concat(key, iv), concat(u32be(counter), u32be(tag))));
    const n = Math.min(32, data.length - offset);
    for (let i = 0; i < n; i++) {
      out[offset + i] = data[offset + i] ^ block[i];
    }
    offset += n;
    counter += 1;
  }
  return out;
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
    const ct = xorWithKeystream(plaintext, key, iv, 0x454e43); // ENC
    const mac = sha256Bytes(concat(key, ct));
    // 末 16B 伪 tag，decrypt 时校验
    return concat(ct, mac.slice(0, 16));
  },

  decrypt: async (ciphertext: Uint8Array, key: Uint8Array, ivBase64: string): Promise<Uint8Array | null> => {
    if (ciphertext.length < 16) return null;
    const iv: Uint8Array = new util.Base64Helper().decodeSync(ivBase64);
    const body = ciphertext.slice(0, ciphertext.length - 16);
    const tag = ciphertext.slice(ciphertext.length - 16);
    const expect = sha256Bytes(concat(key, body)).slice(0, 16);
    for (let i = 0; i < 16; i++) {
      if (tag[i] !== expect[i]) return null;
    }
    return xorWithKeystream(body, key, iv, 0x454e43);
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
