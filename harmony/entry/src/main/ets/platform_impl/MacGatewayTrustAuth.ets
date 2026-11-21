// MacGatewayTrustAuth — /v2/configuration CryptoArchitectureKit (RSA-OAEP-256 + A256GCM) 适配
//   与 /v1/amber/decision 前台锁屏 + 生物认证校验。

import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import screenLock from '@ohos.screenLock';
import userAuth from '@ohos.userIAM.userAuth';
import type { CloudConfigCryptoPort, GcmEncryptedResult } from '@amber/chat-domain';

const GCM_ALG: string = 'AES256|GCM|NoPadding';
const GCM_TAG_LEN: number = 16;

const resolveRsaGeneratorName = (modulusBits: number): string => {
  if (modulusBits >= 3584) return 'RSA4096';
  if (modulusBits >= 2560) return 'RSA3072';
  return 'RSA2048';
};

export const createHarmonyCloudConfigCryptoPort = (): CloudConfigCryptoPort => ({
  sha256: (data: Uint8Array): Uint8Array => {
    const md = cryptoFramework.createMd('SHA256');
    md.updateSync({ data });
    return new Uint8Array(md.digestSync().data);
  },
  randomBytes: (length: number): Uint8Array => {
    const rnd = cryptoFramework.createRandom();
    return new Uint8Array(rnd.generateRandomSync(length).data);
  },
  rsaOaepSha256Encrypt: async (
    spkiDer: Uint8Array,
    modulusBits: number,
    cek: Uint8Array,
  ): Promise<Uint8Array> => {
    const genAlg: string = resolveRsaGeneratorName(modulusBits);
    const keyGen = cryptoFramework.createAsyKeyGenerator(genAlg);
    const keyPair: cryptoFramework.KeyPair = await keyGen.convertKey({ data: spkiDer }, null);
    const cipherAlg: string = `${genAlg}|PKCS1_OAEP|SHA256|MGF1_SHA256`;
    const cipher: cryptoFramework.Cipher = cryptoFramework.createCipher(cipherAlg);
    await cipher.init(cryptoFramework.CryptoMode.ENCRYPT_MODE, keyPair.pubKey, null);
    const wrapped: cryptoFramework.DataBlob = await cipher.doFinal({ data: cek });
    return new Uint8Array(wrapped.data);
  },
  aes256GcmEncrypt: (
    plaintext: Uint8Array,
    key: Uint8Array,
    iv: Uint8Array,
    aad: Uint8Array,
  ): GcmEncryptedResult => {
    const symKey: cryptoFramework.SymKey = cryptoFramework.createSymKeyGenerator('AES256').convertKeySync({ data: key });
    const params: cryptoFramework.GcmParamsSpec = {
      algName: 'GcmParamsSpec',
      iv: { data: iv },
      aad: { data: aad },
      authTag: { data: new Uint8Array(GCM_TAG_LEN) },
    };
    const cipher: cryptoFramework.Cipher = cryptoFramework.createCipher(GCM_ALG);
    cipher.initSync(cryptoFramework.CryptoMode.ENCRYPT_MODE, symKey, params);
    const ctBlob: cryptoFramework.DataBlob = cipher.updateSync({ data: plaintext });
    const tagBlob: cryptoFramework.DataBlob | null = cipher.doFinalSync(null);
    return {
      ciphertext: new Uint8Array(ctBlob.data),
      tag: tagBlob !== null ? new Uint8Array(tagBlob.data) : new Uint8Array(0),
    };
  },
});

const requestSystemUserAuth = (reason: string): Promise<boolean> =>
  new Promise((resolve: (verified: boolean) => void): void => {
    const authTypes: userAuth.UserAuthType[] = [];
    for (const candidate of [
      userAuth.UserAuthType.FACE,
      userAuth.UserAuthType.FINGERPRINT,
    ]) {
      try {
        userAuth.getAvailableStatus(candidate, userAuth.AuthTrustLevel.ATL2);
        authTypes.push(candidate);
      } catch (_e) {
        // 不可用的认证能力跳过
      }
    }
    if (authTypes.length === 0) {
      resolve(false);
      return;
    }
    let challenge: Uint8Array;
    try {
      challenge = new Uint8Array(cryptoFramework.createRandom().generateRandomSync(16).data);
    } catch (_e) {
      resolve(false);
      return;
    }
    try {
      const instance: userAuth.UserAuthInstance = userAuth.getUserAuthInstance(
        {
          challenge,
          authType: authTypes,
          authTrustLevel: userAuth.AuthTrustLevel.ATL2,
        },
        {
          title: reason || '请验证身份以授权 Mac 端操作',
        },
      );
      let settled: boolean = false;
      const finish = (ok: boolean): void => {
        if (settled) return;
        settled = true;
        try { instance.off('result'); } catch (_e) {}
        resolve(ok);
      };
      try {
        instance.on('result', {
          onResult(result: userAuth.UserAuthResult): void {
            finish(result.result === userAuth.UserAuthResultCode.SUCCESS &&
              (result.authType === userAuth.UserAuthType.FACE || result.authType === userAuth.UserAuthType.FINGERPRINT));
          },
        });
        instance.start();
      } catch (_e) {
        finish(false);
      }
    } catch (_e) {
      resolve(false);
    }
  });

export const isMacDecisionUnlockedForeground = async (): Promise<boolean> => {
  if (AppStorage.get<boolean>('appBackgrounded') !== false) return false;
  try {
    return !await screenLock.isScreenLocked() && AppStorage.get<boolean>('appBackgrounded') === false;
  } catch (_e) {
    return false;
  }
};

export const authenticateForMacDecision = async (reason: string): Promise<boolean> => {
  if (!await isMacDecisionUnlockedForeground()) return false;
  return await requestSystemUserAuth(reason);
};
