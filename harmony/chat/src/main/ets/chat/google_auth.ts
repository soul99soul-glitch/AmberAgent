// Google authentication DTOs and wire protocol; no SDK, HTTP or token store ownership.
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { ProviderSettingGoogle } from './provider_settings.ts';
import type { JsonObject, JsonValue } from './json.ts';

export type GoogleOAuthMode = 'gemini_code_assist_oauth' | 'antigravity_oauth';
export interface GoogleRequestAuth {
  kind: 'service_account' | 'code_assist' | 'antigravity';
  accessToken: string;
  projectId: string;
  location: string;
}
export type GoogleAuthResolver = (
  setting: ProviderSettingGoogle, signal?: AbortSignalLike,
) => Promise<GoogleRequestAuth>;
export interface GoogleOAuthBinding {
  providerId: string;
  mode: GoogleOAuthMode;
  clientId: string;
  redirectUri: string;
  clientSecretRef: string;
}
export interface GoogleOAuthTokens {
  accessToken: string;
  refreshToken: string | null;
  expiresAt: number;
  projectId: string | null;
  tierId: string | null;
}
export interface GoogleAuthStatus {
  phase: 'unconfigured' | 'configured' | 'signed_out' | 'authorizing' | 'onboarding_required' | 'ready' | 'error';
  mode: GoogleOAuthMode | 'service_account' | 'api_key';
  message: string;
  projectId: string;
  tierId: string;
  expiresAt: number;
  validationUrl: string | null;
}
export interface GoogleLoginAttempt {
  authorizationUrl: string;
  completion: Promise<GoogleAuthStatus>;
  cancel: () => void;
}
export interface GoogleCatalogModel {
  modelId: string;
  displayName: string;
}
export interface GoogleModelCatalog {
  supported: boolean;
  models: GoogleCatalogModel[];
  message: string;
}
export interface GoogleCatalogPage {
  models: GoogleCatalogModel[];
  nextPageToken: string | null;
}
export interface GoogleOnboardingPlan {
  projectId: string | null;
  tierId: string | null;
  onboardRequest: JsonObject | null;
}
export interface GoogleOnboardOperation {
  done: boolean;
  name: string | null;
  projectId: string | null;
}
export class GoogleAuthError extends Error {
  readonly code: string;
  readonly validationUrl: string | null;
  constructor(code: string, message: string, validationUrl: string | null = null) {
    super(message);
    this.name = 'GoogleAuthError';
    this.code = code;
    this.validationUrl = validationUrl;
  }
}

export const GOOGLE_OAUTH_AUTHORIZATION_ENDPOINT = 'https://accounts.google.com/o/oauth2/v2/auth';
export const GOOGLE_OAUTH_TOKEN_ENDPOINT = 'https://oauth2.googleapis.com/token';
export const GOOGLE_CLOUD_PLATFORM_SCOPE = 'https://www.googleapis.com/auth/cloud-platform';

const objectValue = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;
const nonemptyString = (value: JsonValue | undefined): string | null =>
  typeof value === 'string' && value.trim().length > 0 ? value : null;
const projectValue = (value: JsonValue | undefined): string | null => {
  const project = objectValue(value);
  return nonemptyString(value) ?? (project === null ? null : nonemptyString(project['id']));
};

export const googleOAuthBinding = (setting: ProviderSettingGoogle): GoogleOAuthBinding => {
  if (setting.authMode === 'api_key') {
    throw new GoogleAuthError('auth_mode', '当前 Google 配置未选择 OAuth。');
  }
  return {
    providerId: setting.id, mode: setting.authMode, clientId: setting.oauthClientId,
    redirectUri: setting.oauthRedirectUri, clientSecretRef: setting.oauthClientSecretRef,
  };
};

export const googleOAuthScopes = (mode: GoogleOAuthMode): string[] => {
  const scopes: string[] = [GOOGLE_CLOUD_PLATFORM_SCOPE,
    'https://www.googleapis.com/auth/userinfo.email', 'https://www.googleapis.com/auth/userinfo.profile'];
  if (mode === 'antigravity_oauth') {
    scopes.unshift('openid');
    scopes.push('https://www.googleapis.com/auth/cclog', 'https://www.googleapis.com/auth/experimentsandconfigs');
  }
  return scopes;
};

export const googleCloudCodeBaseUrl = (mode: GoogleOAuthMode): string =>
  mode === 'antigravity_oauth' ? 'https://daily-cloudcode-pa.googleapis.com' : 'https://cloudcode-pa.googleapis.com';
export const googleCloudCodeMethodUrl = (mode: GoogleOAuthMode, method: string): string =>
  `${googleCloudCodeBaseUrl(mode)}/v1internal:${encodeURIComponent(method)}`;
export const googleCloudCodeOperationUrl = (mode: GoogleOAuthMode, name: string): string => {
  const segments: string[] = name.split('/');
  if (segments.length < 2 || segments.some((part: string): boolean => part.length === 0 || part === '.' || part === '..')) {
    throw new GoogleAuthError('operation_invalid', 'Google onboarding 返回了无效 operation 名称。');
  }
  return `${googleCloudCodeBaseUrl(mode)}/v1internal/${segments.map(encodeURIComponent).join('/')}`;
};

const googleCloudCodeMetadata = (mode: GoogleOAuthMode): JsonObject => ({
  ideType: mode === 'antigravity_oauth' ? 'ANTIGRAVITY' : 'IDE_UNSPECIFIED',
  platform: 'PLATFORM_UNSPECIFIED', pluginType: 'GEMINI',
});
export const googleCloudCodeHeaders = (mode: GoogleOAuthMode, accessToken: string): Record<string, string> => {
  const headers: Record<string, string> = {
    'Authorization': `Bearer ${accessToken}`, 'Content-Type': 'application/json',
    'User-Agent': 'AmberAgent-Harmony',
    'Client-Metadata': mode === 'antigravity_oauth'
      ? JSON.stringify(googleCloudCodeMetadata(mode))
      : 'ideType=IDE_UNSPECIFIED,platform=PLATFORM_UNSPECIFIED,pluginType=GEMINI',
  };
  if (mode === 'antigravity_oauth') headers['Origin'] = 'https://antigravity.google';
  return headers;
};

export const buildGoogleLoadCodeAssistRequest = (mode: GoogleOAuthMode, projectId: string): JsonObject => {
  const metadata: JsonObject = googleCloudCodeMetadata(mode);
  const body: JsonObject = { metadata };
  if (projectId.trim().length > 0) {
    body['cloudaicompanionProject'] = projectId;
    metadata['duetProject'] = projectId;
  }
  return body;
};

// Tier IDs remain the server's values. Never assume a FREE/legacy entitlement.
export const parseGoogleLoadCodeAssistResponse = (
  mode: GoogleOAuthMode, input: JsonObject, configuredProjectId: string,
): GoogleOnboardingPlan => {
  if (input['error'] !== undefined) throw new GoogleAuthError('onboarding_error', 'Google loadCodeAssist 返回错误。');
  const currentTier: JsonObject | null = objectValue(input['currentTier']);
  const paidTier: JsonObject | null = objectValue(input['paidTier']);
  const projectId: string | null = projectValue(input['cloudaicompanionProject']) ?? nonemptyString(configuredProjectId);
  if (currentTier !== null) {
    if (projectId === null) throw new GoogleAuthError('project_required', '此 Google 账号需要配置 Cloud Project ID。');
    return {
      projectId, tierId: paidTier === null ? nonemptyString(currentTier['id'])
        : nonemptyString(paidTier['id']) ?? nonemptyString(currentTier['id']),
      onboardRequest: null,
    };
  }
  const ineligible: JsonValue | undefined = input['ineligibleTiers'];
  if (Array.isArray(ineligible)) {
    for (const item of ineligible) {
      const tier: JsonObject | null = objectValue(item);
      if (tier !== null && tier['reasonCode'] === 'VALIDATION_REQUIRED') {
        const url: string | null = nonemptyString(tier['validationUrl']);
        throw new GoogleAuthError('validation_required', 'Google 账号需要完成资格验证。',
          url !== null && url.startsWith('https://') ? url : null);
      }
    }
  }
  const allowed: JsonValue | undefined = input['allowedTiers'];
  const tiers: JsonObject[] = Array.isArray(allowed)
    ? allowed.map(objectValue).filter((tier: JsonObject | null): tier is JsonObject =>
      tier !== null && nonemptyString(tier['id']) !== null)
    : [];
  const tier: JsonObject | undefined = tiers.find((item: JsonObject): boolean => item['isDefault'] === true) ?? tiers[0];
  if (tier === undefined) throw new GoogleAuthError('ineligible_tier', 'Google 未返回可用 onboarding tier；请检查账号资格或项目配置。');
  const tierId: string = tier['id'] as string;
  const managed: boolean = tier['userDefinedCloudaicompanionProject'] === false || tierId === 'free-tier' || tierId === 'FREE';
  if (!managed && projectId === null) throw new GoogleAuthError('project_required', '此 Google onboarding tier 需要配置 Cloud Project ID。');
  const metadata: JsonObject = googleCloudCodeMetadata(mode);
  const onboardRequest: JsonObject = { tierId, metadata };
  if (!managed && projectId !== null) {
    onboardRequest['cloudaicompanionProject'] = projectId;
    metadata['duetProject'] = projectId;
  }
  return { projectId: null, tierId, onboardRequest };
};

export const parseGoogleOnboardOperation = (input: JsonObject): GoogleOnboardOperation => {
  if (input['error'] !== undefined) throw new GoogleAuthError('onboarding_error', 'Google onboarding operation 失败。');
  const done: boolean = input['done'] === true;
  const response: JsonObject | null = objectValue(input['response']);
  const projectId: string | null = response === null ? null : projectValue(response['cloudaicompanionProject']);
  const name: string | null = nonemptyString(input['name']);
  if (done && projectId === null) throw new GoogleAuthError('project_missing', 'Google onboarding 已结束，但未返回项目。');
  if (!done && name === null) throw new GoogleAuthError('operation_invalid', 'Google onboarding 未返回可轮询的 operation。');
  return { done, name, projectId };
};

export const parseGoogleOAuthTokenResponse = (
  input: JsonObject, nowMs: number, previous: GoogleOAuthTokens | null,
): GoogleOAuthTokens => {
  const error: string | null = nonemptyString(input['error']);
  if (error !== null) throw new GoogleAuthError(error, error === 'invalid_grant'
    ? 'Google 登录已失效，请重新登录。' : 'Google 令牌请求失败。');
  const accessToken: string | null = nonemptyString(input['access_token']);
  const expiresIn: JsonValue | undefined = input['expires_in'];
  if (accessToken === null || typeof input['token_type'] !== 'string'
    || (input['token_type'] as string).toLowerCase() !== 'bearer'
    || typeof expiresIn !== 'number' || !Number.isInteger(expiresIn) || expiresIn <= 0
    || !Number.isSafeInteger(nowMs + expiresIn * 1000)) {
    throw new GoogleAuthError('token_invalid', 'Google 令牌响应缺少有效 Bearer token 或有效期。');
  }
  return {
    accessToken, refreshToken: nonemptyString(input['refresh_token']) ?? previous?.refreshToken ?? null,
    expiresAt: nowMs + expiresIn * 1000, projectId: previous?.projectId ?? null, tierId: previous?.tierId ?? null,
  };
};

export const buildGoogleServiceAccountJwtClaims = (email: string, nowMs: number): JsonObject => {
  const iat: number = Math.floor(nowMs / 1000);
  return { iss: email, scope: GOOGLE_CLOUD_PLATFORM_SCOPE, aud: GOOGLE_OAUTH_TOKEN_ENDPOINT, iat, exp: iat + 3600 };
};

export const buildGoogleVertexCatalogUrl = (pageToken: string = ''): string =>
  'https://aiplatform.googleapis.com/v1beta1/publishers/google/models?pageSize=100'
  + (pageToken.length > 0 ? `&pageToken=${encodeURIComponent(pageToken)}` : '');
export const parseGoogleVertexModelCatalog = (input: JsonObject): GoogleCatalogPage => {
  const raw: JsonValue | undefined = input['publisherModels'];
  if (!Array.isArray(raw)) throw new GoogleAuthError('catalog_invalid', 'Vertex 未返回 publisherModels 目录。');
  const models: GoogleCatalogModel[] = [];
  for (const item of raw) {
    const model: JsonObject | null = objectValue(item);
    const name: string | null = model === null ? null : nonemptyString(model['name']);
    if (name === null || !name.startsWith('publishers/google/models/')) continue;
    const modelId: string = name.substring('publishers/google/models/'.length);
    if (modelId.length > 0 && modelId.indexOf('/') < 0) {
      models.push({ modelId, displayName: model === null ? modelId : nonemptyString(model['displayName']) ?? modelId });
    }
  }
  return { models, nextPageToken: nonemptyString(input['nextPageToken']) };
};
export const parseGoogleAntigravityModelCatalog = (input: JsonObject): GoogleCatalogModel[] => {
  const raw: JsonObject | null = objectValue(input['models']);
  if (raw === null) throw new GoogleAuthError('catalog_invalid', 'Antigravity 未返回 models 目录。');
  const models: GoogleCatalogModel[] = [];
  for (const modelId of Object.keys(raw)) {
    if (modelId.trim().length === 0) continue;
    const info: JsonObject | null = objectValue(raw[modelId]);
    models.push({ modelId, displayName: info === null ? modelId : nonemptyString(info['displayName']) ?? modelId });
  }
  return models;
};
