import { GoogleAuthError, GOOGLE_OAUTH_TOKEN_ENDPOINT, buildGoogleServiceAccountJwtClaims,
  parseGoogleOAuthTokenResponse, buildGoogleVertexCatalogUrl, parseGoogleVertexModelCatalog } from '@amber/chat-domain';
import type { ProviderSettingGoogle, GoogleOAuthTokens, GoogleRequestAuth, GoogleModelCatalog,
  GoogleCatalogModel, GoogleCatalogPage, JsonObject } from '@amber/chat-domain';
import type { AbortSignalLike, HttpClient } from '@amber/deepread-domain';
import { loadGoogleSecret } from './GoogleSecureStore.ets';
import { signGoogleRS256 } from './GoogleRS256Signer.ets';
import { GoogleAuthFlight, googleFormEncode, googleBase64Url, googleUtf8, googleSha256,
  checkGoogleAbort, fetchGoogleJson } from './GoogleAuthHttp.ets';

interface ServiceAccountState {
  revision: string;
  tokens: GoogleOAuthTokens | null;
  flight: GoogleAuthFlight<GoogleOAuthTokens> | null;
}

const configuredKey = async (setting: ProviderSettingGoogle): Promise<string> => {
  if (!setting.vertexAI || !setting.useServiceAccount || setting.serviceAccountEmail.trim().length === 0
    || setting.projectId.trim().length === 0 || setting.location.trim().length === 0) {
    throw new GoogleAuthError('service_account_unconfigured', '请配置服务账号邮箱、私钥、Cloud Project ID 与区域。');
  }
  if (setting.privateKeyRef.length > 0) {
    const stored: string | null = await loadGoogleSecret(setting.id, 'service_account_private_key', setting.privateKeyRef);
    if (stored === null) throw new GoogleAuthError('private_key_missing', '服务账号私钥不存在，请重新配置。');
    return stored;
  }
  if (setting.privateKey.trim().length > 0) return setting.privateKey;
  throw new GoogleAuthError('private_key_missing', '请配置服务账号私钥。');
};

const credentialRevision = (setting: ProviderSettingGoogle, key: string): string => googleSha256(JSON.stringify([
  setting.serviceAccountEmail, setting.projectId, setting.location, setting.privateKeyRef, googleSha256(key),
]));

export class GoogleServiceAccountToken {
  private readonly http: HttpClient;
  private readonly states: Map<string, ServiceAccountState> = new Map<string, ServiceAccountState>();

  constructor(http: HttpClient) { this.http = http; }

  forget(providerId: string): void {
    this.states.get(providerId)?.flight?.cancel();
    this.states.delete(providerId);
  }

  async cached(setting: ProviderSettingGoogle): Promise<GoogleOAuthTokens | null> {
    const key: string = await configuredKey(setting);
    const state: ServiceAccountState | undefined = this.states.get(setting.id);
    return state?.revision === credentialRevision(setting, key) && state.tokens !== null
      && state.tokens.expiresAt > Date.now() + 60000 ? state.tokens : null;
  }

  async resolve(setting: ProviderSettingGoogle, signal?: AbortSignalLike): Promise<GoogleRequestAuth> {
    checkGoogleAbort(signal);
    const key: string = await configuredKey(setting);
    checkGoogleAbort(signal);
    const revision: string = credentialRevision(setting, key);
    let state: ServiceAccountState | undefined = this.states.get(setting.id);
    if (state === undefined || state.revision !== revision) {
      state?.flight?.cancel();
      state = { revision, tokens: null, flight: null };
      this.states.set(setting.id, state);
    }
    const current: ServiceAccountState = state;
    let tokens: GoogleOAuthTokens | null = current.tokens;
    if (tokens === null || tokens.expiresAt <= Date.now() + 60000) {
      if (current.flight === null) {
        const flight: GoogleAuthFlight<GoogleOAuthTokens> = new GoogleAuthFlight<GoogleOAuthTokens>(
          async (sharedSignal: AbortSignalLike): Promise<GoogleOAuthTokens> => {
            const header: string = googleBase64Url(googleUtf8(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
            const claims: JsonObject = buildGoogleServiceAccountJwtClaims(setting.serviceAccountEmail, Date.now());
            const input: string = `${header}.${googleBase64Url(googleUtf8(JSON.stringify(claims)))}`;
            const signature: Uint8Array = await signGoogleRS256(key, googleUtf8(input));
            checkGoogleAbort(sharedSignal);
            const fields: Record<string, string> = {
              'grant_type': 'urn:ietf:params:oauth:grant-type:jwt-bearer',
              'assertion': `${input}.${googleBase64Url(signature)}`,
            };
            const response: JsonObject = await fetchGoogleJson(this.http, {
              url: GOOGLE_OAUTH_TOKEN_ENDPOINT, method: 'POST',
              headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
              body: googleFormEncode(fields),
            }, sharedSignal);
            const result: GoogleOAuthTokens = parseGoogleOAuthTokenResponse(response, Date.now(), null);
            checkGoogleAbort(sharedSignal);
            if (this.states.get(setting.id) !== current) throw new GoogleAuthError('credential_changed', '服务账号配置已改变，请重试。');
            current.tokens = result;
            return result;
          });
        current.flight = flight;
        flight.onSettled((): void => { if (current.flight === flight) current.flight = null; });
      }
      const pending: GoogleAuthFlight<GoogleOAuthTokens> = current.flight;
      tokens = await pending.wait(signal);
    }
    checkGoogleAbort(signal);
    return { kind: 'service_account', accessToken: tokens.accessToken,
      projectId: setting.projectId, location: setting.location };
  }

  async catalog(setting: ProviderSettingGoogle, signal?: AbortSignalLike): Promise<GoogleModelCatalog> {
    const auth: GoogleRequestAuth = await this.resolve(setting, signal);
    const models: GoogleCatalogModel[] = [];
    const seen: Set<string> = new Set<string>();
    let pageToken: string = '';
    for (;;) {
      const input: JsonObject = await fetchGoogleJson(this.http, {
        url: buildGoogleVertexCatalogUrl(pageToken), method: 'GET',
        headers: { Authorization: `Bearer ${auth.accessToken}` },
      }, signal);
      const page: GoogleCatalogPage = parseGoogleVertexModelCatalog(input);
      models.push(...page.models);
      if (page.nextPageToken === null) break;
      if (seen.has(page.nextPageToken)) throw new GoogleAuthError('catalog_invalid', 'Vertex 模型目录返回重复分页标记。');
      seen.add(page.nextPageToken);
      pageToken = page.nextPageToken;
    }
    return { supported: true, models, message: '模型目录不代表当前账号的项目与区域已获推理权限。' };
  }
}
