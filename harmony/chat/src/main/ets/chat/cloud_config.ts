// cloud_config — /v2/configuration 加密封装 (RSA-OAEP-256 + A256GCM JWE)、RFC7638 kid 固定、
//   URL 规范化、本地 Provider 可导出校验、与 /v1/amber/decision 生物认证信任头构造

export type CloudModelProviderKind = 'openai' | 'claude';
export type CloudSearchMode = 'fallback' | 'aggregate';
export type MacDecisionSurface = 'app' | 'notification';

export interface CloudConfigViewModelDto {
  provider: string;
  modelId: string;
  baseUrl: string;
}

export interface CloudConfigViewSearchDto {
  sources: string[];
  mode: string;
}

export interface CloudConfigConfiguredFlagDto {
  configured: boolean;
}

export interface CloudConfigViewCredentialsDto {
  modelApiKey: CloudConfigConfiguredFlagDto;
  exaApiKey: CloudConfigConfiguredFlagDto;
  braveApiKey: CloudConfigConfiguredFlagDto;
}

export interface CloudConfigViewDto {
  gatewayId: string;
  revision: number;
  model: CloudConfigViewModelDto;
  search: CloudConfigViewSearchDto;
  credentials: CloudConfigViewCredentialsDto;
  supportedModelProviders?: string[];
  supportedSearchSources?: string[];
}

export interface CloudImportKeyJwkDto {
  kty: string;
  kid?: string;
  alg?: string;
  use?: string;
  n: string;
  e: string;
}

export interface CloudImportKeyDto {
  version?: number;
  gatewayId: string;
  deviceId: string;
  kid: string;
  alg?: string;
  enc?: string;
  publicJwk: CloudImportKeyJwkDto;
  publicKeySpkiDerBase64: string;
  serverTime: number;
  maxEnvelopeLifetimeSeconds?: number;
}

export interface CloudConfigApplyReceipt {
  requestId: string;
  revision: number;
  currentRevision: number;
  replayed: boolean;
  effective: string;
}

export interface CloudConfigCredentialMutation {
  action: 'replace' | 'delete';
  value?: string;
}

export interface CloudConfigCredentialsPatch {
  modelApiKey?: CloudConfigCredentialMutation;
  exaApiKey?: CloudConfigCredentialMutation;
  braveApiKey?: CloudConfigCredentialMutation;
}

export interface CloudConfigPatch {
  model?: CloudConfigViewModelDto;
  search?: CloudConfigViewSearchDto;
}

export interface CloudConfigPlaintextPayload {
  version: number;
  gatewayId: string;
  deviceId: string;
  requestId: string;
  expectedRevision: number;
  issuedAt: number;
  expiresAt: number;
  configuration?: CloudConfigPatch;
  credentials?: CloudConfigCredentialsPatch;
}

export interface VerifiedCloudImportKey {
  kid: string;
  gatewayId: string;
  deviceId: string;
  serverTime: number;
  maxEnvelopeLifetimeSeconds: number;
  spkiDer: Uint8Array;
}

export interface RsaSpkiNumbers {
  pkcs1: Uint8Array;
  modulus: Uint8Array;
  exponent: Uint8Array;
}

export interface GcmEncryptedResult {
  ciphertext: Uint8Array;
  tag: Uint8Array;
}

export interface CloudConfigCryptoPort {
  sha256: (data: Uint8Array) => Uint8Array;
  randomBytes: (length: number) => Uint8Array;
  rsaOaepSha256Encrypt: (spkiDer: Uint8Array, modulusBits: number, cek: Uint8Array) => Promise<Uint8Array>;
  aes256GcmEncrypt: (plaintext: Uint8Array, key: Uint8Array, iv: Uint8Array, aad: Uint8Array) => GcmEncryptedResult;
}

export interface MacDecisionTrustContext {
  surface: MacDecisionSurface;
  trustedForeground: boolean;
  biometricVerified: boolean;
  gatewayIdMatches: boolean;
}

export interface MacGatewayPendingApproval {
  toolCallId: string;
  tool: string;
  detail?: string;
  highRisk: boolean;
}

export const normalizePendingApproval = (raw: Partial<MacGatewayPendingApproval> | null | undefined): MacGatewayPendingApproval | null => {
  if (raw === null || raw === undefined) return null;
  const toolCallId: string = typeof raw.toolCallId === 'string' ? raw.toolCallId.trim() : '';
  const tool: string = typeof raw.tool === 'string' ? raw.tool.trim() : '';
  if (toolCallId.length === 0 || tool.length === 0) return null;
  return {
    toolCallId,
    tool,
    detail: typeof raw.detail === 'string' && raw.detail.trim().length > 0 ? raw.detail.trim() : undefined,
    // Fail closed when older gateways omit highRisk
    highRisk: typeof raw.highRisk === 'boolean' ? raw.highRisk : true,
  };
};

/**
 * 严格按 iOS / Daemon 合同构造 `/v1/amber/decision` 信任来源头：
 * 非 `.app` 前台生物认证 + Gateway 匹配场景，绝不发送 `X-Amber-Trusted-Foreground` 或 `X-Amber-Biometric-Verified`。
 */
export const buildMacDecisionTrustHeaders = (ctx: MacDecisionTrustContext): Record<string, string> => {
  const headers: Record<string, string> = {
    'x-amber-surface': ctx.surface,
  };
  if (ctx.surface === 'app' && ctx.trustedForeground && ctx.biometricVerified && ctx.gatewayIdMatches) {
    headers['x-amber-trusted-foreground'] = 'true';
    headers['x-amber-biometric-verified'] = 'true';
  }
  return headers;
};

const B64_ALPHA: string = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

export const base64EncodeBytes = (bytes: Uint8Array): string => {
  let out: string = '';
  for (let i: number = 0; i < bytes.length; i += 3) {
    const b0: number = bytes[i];
    const has1: boolean = i + 1 < bytes.length;
    const has2: boolean = i + 2 < bytes.length;
    const b1: number = has1 ? bytes[i + 1] : 0;
    const b2: number = has2 ? bytes[i + 2] : 0;
    const triple: number = (b0 << 16) | (b1 << 8) | b2;
    out += B64_ALPHA.charAt((triple >> 18) & 0x3f);
    out += B64_ALPHA.charAt((triple >> 12) & 0x3f);
    out += has1 ? B64_ALPHA.charAt((triple >> 6) & 0x3f) : '=';
    out += has2 ? B64_ALPHA.charAt(triple & 0x3f) : '=';
  }
  return out;
};

export const base64DecodeBytes = (b64: string): Uint8Array => {
  const clean: string = b64.replace(/[\r\n\s]/g, '');
  if (clean.length === 0) return new Uint8Array(0);
  if (clean.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(clean)) {
    throw new Error('invalid_base64');
  }
  const pad: number = clean.endsWith('==') ? 2 : clean.endsWith('=') ? 1 : 0;
  const outLen: number = (clean.length / 4) * 3 - pad;
  const out: Uint8Array = new Uint8Array(outLen);
  let outIdx: number = 0;
  for (let i: number = 0; i < clean.length; i += 4) {
    const c0: number = B64_ALPHA.indexOf(clean.charAt(i));
    const c1: number = B64_ALPHA.indexOf(clean.charAt(i + 1));
    const c2ch: string = clean.charAt(i + 2);
    const c3ch: string = clean.charAt(i + 3);
    const c2: number = c2ch === '=' ? 0 : B64_ALPHA.indexOf(c2ch);
    const c3: number = c3ch === '=' ? 0 : B64_ALPHA.indexOf(c3ch);
    const triple: number = (c0 << 18) | (c1 << 12) | (c2 << 6) | c3;
    if (outIdx < outLen) out[outIdx++] = (triple >> 16) & 0xff;
    if (outIdx < outLen) out[outIdx++] = (triple >> 8) & 0xff;
    if (outIdx < outLen) out[outIdx++] = triple & 0xff;
  }
  return out;
};

export const base64UrlEncodeBytes = (bytes: Uint8Array): string =>
  base64EncodeBytes(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

export const base64UrlDecodeBytes = (input: string): Uint8Array => {
  let std: string = input.replace(/-/g, '+').replace(/_/g, '/');
  while (std.length % 4 !== 0) std += '=';
  return base64DecodeBytes(std);
};

export const utf8EncodeAsciiOrText = (text: string): Uint8Array => {
  const bytes: number[] = [];
  for (let i: number = 0; i < text.length; i++) {
    let code: number = text.charCodeAt(i);
    if (code < 0x80) {
      bytes.push(code);
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
      const next: number = text.charCodeAt(++i);
      const cp: number = 0x10000 + (((code - 0xd800) << 10) | (next - 0xdc00));
      bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    } else {
      bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    }
  }
  return new Uint8Array(bytes);
};

interface DerReadResult {
  value: Uint8Array;
  nextOffset: number;
}

const readDerTlv = (bytes: Uint8Array, offset: number, expectedTag: number): DerReadResult | null => {
  if (offset < 0 || offset >= bytes.length || bytes[offset] !== expectedTag) return null;
  let cursor: number = offset + 1;
  if (cursor >= bytes.length) return null;
  const firstLen: number = bytes[cursor++];
  let length: number = 0;
  if ((firstLen & 0x80) === 0) {
    length = firstLen;
  } else {
    const count: number = firstLen & 0x7f;
    if (count === 0 || count > 4 || cursor + count > bytes.length) return null;
    for (let i: number = 0; i < count; i++) {
      length = (length << 8) | bytes[cursor++];
    }
  }
  if (length < 0 || cursor + length > bytes.length) return null;
  return { value: bytes.slice(cursor, cursor + length), nextOffset: cursor + length };
};

const stripLeadingZeroBytes = (bytes: Uint8Array): Uint8Array => {
  let idx: number = 0;
  while (idx < bytes.length && bytes[idx] === 0) idx++;
  return bytes.slice(idx);
};

/**
 * 从 X.509 SubjectPublicKeyInfo DER 解析 PKCS#1 RSAPublicKey 与无符号 modulus / exponent。
 */
export const extractRsaPublicNumbersFromSpki = (spki: Uint8Array): RsaSpkiNumbers | null => {
  const topSeq: DerReadResult | null = readDerTlv(spki, 0, 0x30);
  if (topSeq === null || topSeq.nextOffset !== spki.length) return null;
  const algId: DerReadResult | null = readDerTlv(topSeq.value, 0, 0x30);
  if (algId === null) return null;
  const bits: DerReadResult | null = readDerTlv(topSeq.value, algId.nextOffset, 0x03);
  if (bits === null || bits.value.length < 2 || bits.value[0] !== 0) return null;
  const pkcs1: Uint8Array = bits.value.slice(1);
  const rsaSeq: DerReadResult | null = readDerTlv(pkcs1, 0, 0x30);
  if (rsaSeq === null) return null;
  const modTlv: DerReadResult | null = readDerTlv(rsaSeq.value, 0, 0x02);
  if (modTlv === null) return null;
  const expTlv: DerReadResult | null = readDerTlv(rsaSeq.value, modTlv.nextOffset, 0x02);
  if (expTlv === null) return null;
  return {
    pkcs1,
    modulus: stripLeadingZeroBytes(modTlv.value),
    exponent: stripLeadingZeroBytes(expTlv.value),
  };
};

/**
 * RFC7638 RSA JWK SHA-256 thumbprint（严格按键名字典序 {"e":...,"kty":"RSA","n":...}）。
 */
export const computeRsaJwkThumbprint = (
  e: string,
  n: string,
  sha256: (data: Uint8Array) => Uint8Array,
): string => {
  const canonical: string = `{"e":${JSON.stringify(e)},"kty":"RSA","n":${JSON.stringify(n)}}`;
  return base64UrlEncodeBytes(sha256(utf8EncodeAsciiOrText(canonical)));
};

export const verifyCloudImportKey = (
  dto: CloudImportKeyDto,
  gatewayId: string,
  sha256: (data: Uint8Array) => Uint8Array,
): VerifiedCloudImportKey => {
  const version: number = dto.version ?? 1;
  const alg: string = dto.alg ?? 'RSA-OAEP-256';
  const enc: string = dto.enc ?? 'A256GCM';
  const maxLifetime: number = dto.maxEnvelopeLifetimeSeconds ?? 300;
  if (
    version !== 1
    || alg !== 'RSA-OAEP-256'
    || enc !== 'A256GCM'
    || dto.gatewayId !== gatewayId
    || dto.publicJwk.kty !== 'RSA'
    || maxLifetime <= 0
    || maxLifetime > 300
  ) {
    throw new Error('malformed_response');
  }
  const computedKid: string = computeRsaJwkThumbprint(dto.publicJwk.e, dto.publicJwk.n, sha256);
  if (computedKid !== dto.kid) {
    throw new Error('malformed_response');
  }
  const spkiDer: Uint8Array = base64DecodeBytes(dto.publicKeySpkiDerBase64);
  const rsa: RsaSpkiNumbers | null = extractRsaPublicNumbersFromSpki(spkiDer);
  if (
    rsa === null
    || base64UrlEncodeBytes(rsa.modulus) !== dto.publicJwk.n
    || base64UrlEncodeBytes(rsa.exponent) !== dto.publicJwk.e
  ) {
    throw new Error('malformed_response');
  }
  return {
    kid: dto.kid,
    gatewayId: dto.gatewayId,
    deviceId: dto.deviceId,
    serverTime: dto.serverTime,
    maxEnvelopeLifetimeSeconds: maxLifetime,
    spkiDer,
  };
};

export const buildCompactJwe = async (
  payloadJson: string,
  key: VerifiedCloudImportKey,
  cryptoPort: CloudConfigCryptoPort,
): Promise<string> => {
  const rsa: RsaSpkiNumbers | null = extractRsaPublicNumbersFromSpki(key.spkiDer);
  if (rsa === null || rsa.modulus.length === 0) {
    throw new Error('malformed_response');
  }
  const modulusBits: number = rsa.modulus.length * 8;
  // 按 RFC7516 与服务端契约，Protected Header 仅包含固定顺序的 4 个字段
  const headerJson: string =
    `{"alg":"RSA-OAEP-256","enc":"A256GCM","kid":${JSON.stringify(key.kid)},"typ":"amber-config+jwe"}`;
  const protectedB64u: string = base64UrlEncodeBytes(utf8EncodeAsciiOrText(headerJson));
  const aadBytes: Uint8Array = utf8EncodeAsciiOrText(protectedB64u);
  const cek: Uint8Array = cryptoPort.randomBytes(32);
  const iv: Uint8Array = cryptoPort.randomBytes(12);
  const wrappedCek: Uint8Array = await cryptoPort.rsaOaepSha256Encrypt(key.spkiDer, modulusBits, cek);
  const gcm: GcmEncryptedResult = cryptoPort.aes256GcmEncrypt(utf8EncodeAsciiOrText(payloadJson), cek, iv, aadBytes);
  return [
    protectedB64u,
    base64UrlEncodeBytes(wrappedCek),
    base64UrlEncodeBytes(iv),
    base64UrlEncodeBytes(gcm.ciphertext),
    base64UrlEncodeBytes(gcm.tag),
  ].join('.');
};

/**
 * 规范化云端模型 Base URL：允许空字符串（代表协议默认地址），否则必须为无 userinfo/query/fragment 的 https URL。
 */
export const normalizedCloudModelBaseUrl = (rawBaseUrl: string): string | null => {
  const trimmed: string = rawBaseUrl.trim();
  if (trimmed.length === 0) return '';
  const match: RegExpMatchArray | null = trimmed.match(/^(https?):\/\/([^/?#]+)([^?#]*)(?:\?([^#]*))?(?:#(.*))?$/i);
  if (match === null) return null;
  const scheme: string = match[1].toLowerCase();
  const authority: string = match[2];
  let path: string = match[3] ?? '';
  const query: string | undefined = match[4];
  const fragment: string | undefined = match[5];
  if (scheme !== 'https' || authority.length === 0 || authority.indexOf('@') >= 0) return null;
  if (query !== undefined || fragment !== undefined) return null;
  const lowerHost: string = authority.toLowerCase();
  if (path === '/') path = '';
  while (path.length > 1 && path.endsWith('/')) {
    path = path.slice(0, path.length - 1);
  }
  return `https://${lowerHost}${path}`;
};

export const isValidCloudModelBaseUrl = (rawBaseUrl: string): boolean =>
  normalizedCloudModelBaseUrl(rawBaseUrl) !== null;

export const mapLocalProviderTypeToCloudKind = (providerType: string): CloudModelProviderKind | null => {
  const lower: string = providerType.trim().toLowerCase();
  if (lower === 'openai') return 'openai';
  if (lower === 'claude' || lower === 'anthropic') return 'claude';
  return null;
};

export const describeCloudConfigErrorCode = (status: number, code: string): string => {
  if (status === 401) return '此设备已在 Mac 上被吊销，请重新配对';
  if (status === 403 || code === 'untrusted_device') return '此设备不是云端认可的受信管理端，请检查 Mac 上的设备授权';
  if (status === 404) return '云端接口待接入，等待服务端更新';
  if (status === 409 && code === 'request_id_conflict') return '请求标识冲突，请重试';
  if (status === 409) return '云端配置刚被其他设备修改，已为你刷新，请核对后重试';
  if (status === 410 || code === 'envelope_expired') return '封装已过期，请重试';
  if (status === 413 || code === 'request_too_large') return '导入内容超出云端允许的大小';
  if (status === 422 && code === 'credential_action_required') {
    return '云端已保存旧模型 Key：切换服务商或地址时需携带本机 Key，或先删除云端旧 Key';
  }
  if (status === 422) return '云端拒绝了该配置（参数不合法），原配置保持不变';
  if (status === 400 && code === 'invalid_envelope') return '加密封装被云端拒绝，请重试或更新 App';
  if (status === 400) return '请求参数无效，请核对后重试';
  if (status === 503) return '云端配置存储暂不可用，请稍后重试';
  if (code === 'import_key_changed') {
    return '云端导入公钥与已固定的不一致：可能是服务端重装或网络被劫持。确认 Mac 状态后重新信任再继续';
  }
  return code || `HTTP ${status}`;
};
