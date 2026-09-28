import { cryptoFramework } from '@kit.CryptoArchitectureKit';
import { util } from '@kit.ArkTS';

// Google service-account JSON contains an unencrypted PKCS#8 PRIVATE KEY.
// PKCS#1 RSA PRIVATE KEY and encrypted PEM are deliberately rejected.
const decodePKCS8 = (pem: string): Uint8Array => {
  const match: RegExpMatchArray | null = pem.match(
    /^\s*-----BEGIN PRIVATE KEY-----\s*([A-Za-z0-9+/=\s]+)\s*-----END PRIVATE KEY-----\s*$/,
  );
  if (match === null || match[0] !== pem) throw new Error('google_service_account_invalid_pkcs8');
  const base64: string = match[1].replace(/\s/g, '');
  if (base64.length === 0 || base64.length > Math.ceil(64 * 1024 / 3) * 4 ||
    !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(base64)) {
    throw new Error('google_service_account_invalid_pkcs8');
  }
  const bytes: Uint8Array = new util.Base64Helper().decodeSync(base64);
  if (new util.Base64Helper().encodeToStringSync(bytes) !== base64) {
    bytes.fill(0); throw new Error('google_service_account_invalid_pkcs8');
  }
  return bytes;
};

export const signGoogleRS256 = async (privateKeyPem: string, signingInput: Uint8Array): Promise<Uint8Array> => {
  const der: Uint8Array = decodePKCS8(privateKeyPem);
  let privateKey: cryptoFramework.PriKey | null = null;
  try {
    const keyData: cryptoFramework.DataBlob = { data: der };
    const pair: cryptoFramework.KeyPair = await cryptoFramework.createAsyKeyGenerator('RSA2048')
      .convertKey(null, keyData);
    privateKey = pair.priKey;
    // A fresh signer per invocation. PKCS1+SHA256 is JWT RS256, not RSA-PSS.
    const signer: cryptoFramework.Sign = cryptoFramework.createSign('RSA2048|PKCS1|SHA256');
    await signer.init(privateKey);
    const input: cryptoFramework.DataBlob = { data: signingInput };
    const signature: cryptoFramework.DataBlob = await signer.sign(input);
    return new Uint8Array(signature.data);
  } finally { privateKey?.clearMem(); der.fill(0); }
};
