import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {
  verifyCloudImportKey,
  buildCompactJwe,
  base64UrlEncodeBytes,
  base64UrlDecodeBytes,
  normalizedCloudModelBaseUrl,
  isValidCloudModelBaseUrl,
  buildMacDecisionTrustHeaders,
  normalizePendingApproval,
  type CloudConfigCryptoPort,
  type CloudImportKeyDto,
} from '../main/ets/chat/cloud_config.ts';

const nodeCryptoPort: CloudConfigCryptoPort = {
  sha256: (data) => new Uint8Array(crypto.createHash('sha256').update(data).digest()),
  randomBytes: (len) => new Uint8Array(crypto.randomBytes(len)),
  rsaOaepSha256Encrypt: async (spkiDer, _modulusBits, cek) => {
    const pubKey = crypto.createPublicKey({ key: Buffer.from(spkiDer), format: 'der', type: 'spki' });
    return new Uint8Array(crypto.publicEncrypt({
      key: pubKey,
      padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: 'sha256',
    }, Buffer.from(cek)));
  },
  aes256GcmEncrypt: (plaintext, key, iv, aad) => {
    const cipher = crypto.createCipheriv('aes-256-gcm', Buffer.from(key), Buffer.from(iv));
    cipher.setAAD(Buffer.from(aad));
    const ct = Buffer.concat([cipher.update(Buffer.from(plaintext)), cipher.final()]);
    const tag = cipher.getAuthTag();
    return { ciphertext: new Uint8Array(ct), tag: new Uint8Array(tag) };
  },
};

test('P1-2: verifies RSA-3072 SPKI vs RFC7638 kid and round-trips compact JWE decryption', async () => {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 3072 });
  const jwk = publicKey.export({ format: 'jwk' });
  const spkiDer = publicKey.export({ format: 'der', type: 'spki' });
  const canonicalThumb = `{"e":${JSON.stringify(jwk.e)},"kty":"RSA","n":${JSON.stringify(jwk.n)}}`;
  const expectedKid = base64UrlEncodeBytes(new Uint8Array(crypto.createHash('sha256').update(canonicalThumb).digest()));

  const dto: CloudImportKeyDto = {
    version: 1,
    gatewayId: 'gw-1',
    deviceId: 'dev-1',
    kid: expectedKid,
    alg: 'RSA-OAEP-256',
    enc: 'A256GCM',
    publicJwk: { kty: 'RSA', n: String(jwk.n), e: String(jwk.e) },
    publicKeySpkiDerBase64: Buffer.from(spkiDer).toString('base64'),
    serverTime: 1700000000,
    maxEnvelopeLifetimeSeconds: 300,
  };

  const verified = verifyCloudImportKey(dto, 'gw-1', nodeCryptoPort.sha256);
  assert.equal(verified.kid, expectedKid);

  // Tampered kid is rejected
  assert.throws(() => verifyCloudImportKey({ ...dto, kid: 'tampered-kid' }, 'gw-1', nodeCryptoPort.sha256), /malformed_response/);
  // Mismatched gatewayId is rejected
  assert.throws(() => verifyCloudImportKey(dto, 'gw-other', nodeCryptoPort.sha256), /malformed_response/);

  const payload = JSON.stringify({
    version: 1,
    gatewayId: 'gw-1',
    deviceId: 'dev-1',
    requestId: 'req-123',
    expectedRevision: 4,
    issuedAt: 1700000000,
    expiresAt: 1700000120,
    configuration: { model: { provider: 'openai', modelId: 'gpt-5', baseUrl: 'https://api.openai.com/v1' } },
    credentials: { modelApiKey: { action: 'replace', value: 'sk-secret-value' } },
  });

  const jwe = await buildCompactJwe(payload, verified, nodeCryptoPort);
  const parts = jwe.split('.');
  assert.equal(parts.length, 5);

  const header = JSON.parse(Buffer.from(base64UrlDecodeBytes(parts[0])).toString('utf8'));
  assert.deepEqual(header, {
    alg: 'RSA-OAEP-256',
    enc: 'A256GCM',
    kid: expectedKid,
    typ: 'amber-config+jwe',
  });

  // Decrypt using private key + verify protected header ASCII AAD matches
  const unwrappedCek = crypto.privateDecrypt({
    key: privateKey,
    padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
    oaepHash: 'sha256',
  }, Buffer.from(base64UrlDecodeBytes(parts[1])));

  const decipher = crypto.createDecipheriv('aes-256-gcm', unwrappedCek, Buffer.from(base64UrlDecodeBytes(parts[2])));
  decipher.setAAD(Buffer.from(parts[0], 'ascii'));
  decipher.setAuthTag(Buffer.from(base64UrlDecodeBytes(parts[4])));
  const decrypted = Buffer.concat([decipher.update(Buffer.from(base64UrlDecodeBytes(parts[3]))), decipher.final()]).toString('utf8');
  assert.equal(decrypted, payload);
});

test('P1-2: buildMacDecisionTrustHeaders emits trusted headers ONLY on verified foreground app with matching gateway', () => {
  assert.deepEqual(
    buildMacDecisionTrustHeaders({
      surface: 'app',
      trustedForeground: true,
      biometricVerified: true,
      gatewayIdMatches: true,
    }),
    {
      'x-amber-surface': 'app',
      'x-amber-trusted-foreground': 'true',
      'x-amber-biometric-verified': 'true',
    },
  );

  // Notification surface can never forge foreground/biometric headers
  assert.deepEqual(
    buildMacDecisionTrustHeaders({
      surface: 'notification',
      trustedForeground: true,
      biometricVerified: true,
      gatewayIdMatches: true,
    }),
    { 'x-amber-surface': 'notification' },
  );

  // Unverified / background / mismatched gateway fail closed
  assert.deepEqual(
    buildMacDecisionTrustHeaders({
      surface: 'app',
      trustedForeground: true,
      biometricVerified: false,
      gatewayIdMatches: true,
    }),
    { 'x-amber-surface': 'app' },
  );
  assert.deepEqual(
    buildMacDecisionTrustHeaders({
      surface: 'app',
      trustedForeground: true,
      biometricVerified: true,
      gatewayIdMatches: false,
    }),
    { 'x-amber-surface': 'app' },
  );
});

test('P1-2: normalizedCloudModelBaseUrl enforces https contract & normalizePendingApproval fails closed on missing highRisk', () => {
  assert.equal(normalizedCloudModelBaseUrl(''), '');
  assert.equal(normalizedCloudModelBaseUrl('HTTPS://API.OpenAI.COM/v1/'), 'https://api.openai.com/v1');
  assert.equal(isValidCloudModelBaseUrl('http://192.168.1.10:8080/v1'), false);
  assert.equal(isValidCloudModelBaseUrl('https://user:pass@api.example.com/v1'), false);

  const omittedRisk = normalizePendingApproval({ toolCallId: 'tc-1', tool: 'shell_exec' });
  assert.equal(omittedRisk?.highRisk, true);
  const lowRisk = normalizePendingApproval({ toolCallId: 'tc-2', tool: 'file_read', highRisk: false });
  assert.equal(lowRisk?.highRisk, false);
});
