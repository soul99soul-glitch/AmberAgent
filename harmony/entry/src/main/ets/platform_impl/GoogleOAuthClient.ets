import {
  GOOGLE_OAUTH_AUTHORIZATION_ENDPOINT, GOOGLE_OAUTH_TOKEN_ENDPOINT, GoogleAuthError,
  googleOAuthScopes, googleCloudCodeMethodUrl, googleCloudCodeOperationUrl, googleCloudCodeHeaders,
  buildGoogleLoadCodeAssistRequest, parseGoogleLoadCodeAssistResponse, parseGoogleOnboardOperation,
  parseGoogleOAuthTokenResponse, parseGoogleAntigravityModelCatalog,
} from '@amber/chat-domain';
import type { GoogleOAuthBinding, GoogleOAuthTokens, GoogleOnboardingPlan, GoogleOnboardOperation,
  GoogleModelCatalog, JsonObject } from '@amber/chat-domain';
import type { AbortSignalLike, HttpClient } from '@amber/deepread-domain';
import { loadGoogleSecret } from './GoogleSecureStore.ets';
import { googleFormEncode, fetchGoogleJson, checkGoogleAbort, googleDelay } from './GoogleAuthHttp.ets';

export const buildGoogleAuthorizationUrl = (binding: GoogleOAuthBinding, state: string, challenge: string): string => {
  const fields: Record<string, string> = {
    'client_id': binding.clientId, 'redirect_uri': binding.redirectUri, 'response_type': 'code',
    'scope': googleOAuthScopes(binding.mode).join(' '), 'state': state, 'code_challenge': challenge,
    'code_challenge_method': 'S256', 'access_type': 'offline', 'prompt': 'consent',
  };
  return `${GOOGLE_OAUTH_AUTHORIZATION_ENDPOINT}?${googleFormEncode(fields)}`;
};

const withClientSecret = async (binding: GoogleOAuthBinding, fields: Record<string, string>): Promise<void> => {
  if (binding.clientSecretRef.length === 0) return;
  const secret: string | null = await loadGoogleSecret(binding.providerId, 'oauth_client_secret', binding.clientSecretRef);
  if (secret === null) throw new GoogleAuthError('client_secret_missing', 'OAuth 客户端密钥不存在，请重新配置。');
  fields['client_secret'] = secret;
};

const tokenRequest = async (
  http: HttpClient, binding: GoogleOAuthBinding, fields: Record<string, string>,
  previous: GoogleOAuthTokens | null, signal?: AbortSignalLike,
): Promise<GoogleOAuthTokens> => {
  await withClientSecret(binding, fields);
  checkGoogleAbort(signal);
  const response: JsonObject = await fetchGoogleJson(http, {
    url: GOOGLE_OAUTH_TOKEN_ENDPOINT, method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: googleFormEncode(fields),
  }, signal);
  return parseGoogleOAuthTokenResponse(response, Date.now(), previous);
};

export const exchangeGoogleAuthorizationCode = (
  http: HttpClient, binding: GoogleOAuthBinding, code: string, verifier: string, signal?: AbortSignalLike,
): Promise<GoogleOAuthTokens> => {
  const fields: Record<string, string> = {
    'grant_type': 'authorization_code', 'client_id': binding.clientId, 'redirect_uri': binding.redirectUri,
    'code': code, 'code_verifier': verifier,
  };
  return tokenRequest(http, binding, fields, null, signal);
};

export const refreshGoogleOAuthTokens = (
  http: HttpClient, binding: GoogleOAuthBinding, previous: GoogleOAuthTokens, signal?: AbortSignalLike,
): Promise<GoogleOAuthTokens> => {
  if (previous.refreshToken === null) return Promise.reject(new GoogleAuthError('login_required', 'Google 令牌已过期，请重新登录。'));
  const fields: Record<string, string> = {
    'grant_type': 'refresh_token', 'client_id': binding.clientId, 'refresh_token': previous.refreshToken,
  };
  return tokenRequest(http, binding, fields, previous, signal);
};

export const onboardGoogleOAuthTokens = async (
  http: HttpClient, binding: GoogleOAuthBinding, tokens: GoogleOAuthTokens, configuredProjectId: string,
  signal?: AbortSignalLike,
): Promise<GoogleOAuthTokens> => {
  const headers: Record<string, string> = googleCloudCodeHeaders(binding.mode, tokens.accessToken);
  const loaded: JsonObject = await fetchGoogleJson(http, {
    url: googleCloudCodeMethodUrl(binding.mode, 'loadCodeAssist'), method: 'POST', headers,
    body: JSON.stringify(buildGoogleLoadCodeAssistRequest(binding.mode, configuredProjectId)),
  }, signal);
  const plan: GoogleOnboardingPlan = parseGoogleLoadCodeAssistResponse(binding.mode, loaded, configuredProjectId);
  let projectId: string | null = plan.projectId;
  if (plan.onboardRequest !== null) {
    let response: JsonObject = await fetchGoogleJson(http, {
      url: googleCloudCodeMethodUrl(binding.mode, 'onboardUser'), method: 'POST', headers,
      body: JSON.stringify(plan.onboardRequest),
    }, signal);
    for (let poll: number = 0; ; poll++) {
      const operation: GoogleOnboardOperation = parseGoogleOnboardOperation(response);
      if (operation.done) { projectId = operation.projectId; break; }
      if (poll >= 12 || operation.name === null) throw new GoogleAuthError('onboarding_timeout', 'Google 项目初始化超时，请稍后重试。');
      await googleDelay(5000, signal);
      response = await fetchGoogleJson(http, {
        url: googleCloudCodeOperationUrl(binding.mode, operation.name), method: 'GET', headers,
      }, signal);
    }
  }
  checkGoogleAbort(signal);
  if (projectId === null || projectId.length === 0) throw new GoogleAuthError('project_missing', 'Google 未返回可用于推理的项目。');
  return { accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, expiresAt: tokens.expiresAt,
    projectId, tierId: plan.tierId };
};

export const loadGoogleOAuthCatalog = async (
  http: HttpClient, binding: GoogleOAuthBinding, tokens: GoogleOAuthTokens, signal?: AbortSignalLike,
): Promise<GoogleModelCatalog> => {
  if (binding.mode === 'gemini_code_assist_oauth') {
    return { supported: false, models: [], message: 'Code Assist 未提供模型目录接口，请手动填写有权使用的模型 ID。' };
  }
  const input: JsonObject = await fetchGoogleJson(http, {
    url: googleCloudCodeMethodUrl(binding.mode, 'fetchAvailableModels'), method: 'POST',
    headers: googleCloudCodeHeaders(binding.mode, tokens.accessToken), body: JSON.stringify({ project: tokens.projectId }),
  }, signal);
  return { supported: true, models: parseGoogleAntigravityModelCatalog(input), message: '' };
};
