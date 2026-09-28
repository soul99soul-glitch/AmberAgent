import test from 'node:test';
import assert from 'node:assert/strict';
import type { AbortSignalLike, HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';
import { createGoogleChatApi } from '../main/ets/chat/google_chat_api.ts';
import {
  makeProviderSettingGoogle, hasUsableAuth, copyProviderSettingWithModels,
} from '../main/ets/chat/provider_settings.ts';
import { parseProviderSetting, serializeProviderSetting } from '../main/ets/chat/provider_settings_serialize.ts';
import { providerFromImportJson } from '../main/ets/chat/provider_import.ts';
import { makeChatModel, makeTextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { GoogleOAuthMode, GoogleRequestAuth } from '../main/ets/chat/google_auth.ts';
import {
  buildGoogleLoadCodeAssistRequest, parseGoogleLoadCodeAssistResponse, parseGoogleOnboardOperation,
  parseGoogleOAuthTokenResponse, buildGoogleServiceAccountJwtClaims, buildGoogleVertexCatalogUrl,
  parseGoogleVertexModelCatalog, parseGoogleAntigravityModelCatalog,
} from '../main/ets/chat/google_auth.ts';

const params = makeTextGenerationParams({ model: makeChatModel({ modelId: 'gemini-3-pro' }) });
const messages = [makeUserMessage('hi')];
const response: JsonObject = { candidates: [{ content: { role: 'model', parts: [{ text: 'ok' }] } }] };
const responseText = JSON.stringify(response);
const fixture = (body: string, streamEvents: JsonObject[] = []): {
  http: HttpClient; requests: HttpRequest[]; signals: Array<AbortSignalLike | undefined>;
} => {
  const requests: HttpRequest[] = [];
  const signals: Array<AbortSignalLike | undefined> = [];
  const http: HttpClient = {
    fetch: async (req, opts): Promise<HttpResponse> => {
      requests.push(req); signals.push(opts?.signal);
      return { status: 200, headers: {}, body };
    },
    fetchStream: async (req, opts): Promise<HttpResponse> => {
      requests.push(req); signals.push(opts.signal);
      for (const event of streamEvents) {
        const bytes = new TextEncoder().encode(`data: ${JSON.stringify(event)}\n\n`);
        opts.onChunk(bytes.buffer as ArrayBuffer, false);
        if (opts.shouldStop?.() === true) break;
      }
      opts.onChunk(new ArrayBuffer(0), true);
      return { status: 200, headers: {}, body: '' };
    },
  };
  return { http, requests, signals };
};

test('Google provider codec preserves independent modes, local refs and legacy key until migration', () => {
  const setting = makeProviderSettingGoogle({
    authMode: 'antigravity_oauth', oauthClientId: 'client', oauthRedirectUri: 'http://localhost:1234/cb',
    oauthClientSecretRef: 'secret-ref', privateKeyRef: 'key-ref', privateKey: 'legacy',
  });
  const decoded = parseProviderSetting(serializeProviderSetting(setting));
  assert.deepEqual(decoded, setting);
  assert.deepEqual(copyProviderSettingWithModels(setting, []), setting);
  assert.equal(hasUsableAuth(setting), true);
  assert.equal(hasUsableAuth(makeProviderSettingGoogle({ authMode: 'antigravity_oauth', apiKey: 'token' })), false);
  assert.equal(hasUsableAuth(makeProviderSettingGoogle({ useServiceAccount: true, privateKeyRef: 'key-ref' })), false);
  assert.equal(hasUsableAuth(makeProviderSettingGoogle({
    vertexAI: true, useServiceAccount: true, privateKeyRef: 'key-ref', serviceAccountEmail: 'e', projectId: 'p',
  })), true);
});

test('sanitized Google import retains identity metadata with new IDs and discards supplied secrets/refs', () => {
  const imported = providerFromImportJson(JSON.stringify({
    format: 'amber-provider', version: 1, id: 'old-provider', type: 'google', name: 'Imported',
    baseUrl: 'https://daily-cloudcode-pa.googleapis.com', authMode: 'antigravity_oauth',
    useServiceAccount: true, vertexAI: true, serviceAccountEmail: 'sa@example.com', projectId: 'p',
    oauthClientId: 'client', oauthRedirectUri: 'http://localhost:1234/cb',
    privateKey: 'do-not-import', privateKeyRef: 'local-ref', oauthClientSecretRef: 'secret-ref',
    apiKey: 'do-not-import', accessToken: 'do-not-import', refreshToken: 'do-not-import',
    models: [{ id: 'old-model', modelId: 'gemini-real', displayName: 'Real', type: 'chat' }],
  }));
  assert.equal(imported.type, 'google');
  if (imported.type !== 'google') throw new Error('Google import expected');
  assert.notEqual(imported.id, 'old-provider'); assert.notEqual(imported.models[0].id, 'old-model');
  assert.equal(imported.authMode, 'antigravity_oauth'); assert.equal(imported.useServiceAccount, true);
  assert.equal(imported.serviceAccountEmail, 'sa@example.com'); assert.equal(imported.projectId, 'p');
  assert.equal(imported.oauthClientId, 'client'); assert.equal(imported.oauthRedirectUri, 'http://localhost:1234/cb');
  assert.equal(imported.privateKey, ''); assert.equal(imported.privateKeyRef, '');
  assert.equal(imported.oauthClientSecretRef, ''); assert.equal(imported.apiKey, '');
  assert.equal(serializeProviderSetting(imported).includes('do-not-import'), false);
});

test('API key Gemini/Vertex preserves roulette and never resolves OAuth/SA', async () => {
  for (const vertexAI of [false, true]) {
    const f = fixture(responseText);
    const api = createGoogleChatApi({
      http: f.http, setting: makeProviderSettingGoogle({ vertexAI, apiKey: 'keys' }),
      keyRoulette: { next: () => 'chosen' },
      resolveAuth: async () => { throw new Error('API key must not resolve'); },
    });
    await api.generateText(messages, params);
    assert.equal(vertexAI ? f.requests[0].url.includes('key=chosen') : f.requests[0].headers['x-goog-api-key'] === 'chosen', true);
    assert.equal(f.requests[0].headers['Authorization'], undefined);
  }
});

test('SA awaits original-signal resolver, routes encoded project/location/model and uses Bearer only', async () => {
  const signal: AbortSignalLike = { aborted: false };
  const f = fixture(responseText);
  const api = createGoogleChatApi({
    http: f.http, setting: makeProviderSettingGoogle({ vertexAI: true, useServiceAccount: true }),
    keyRoulette: { next: () => { throw new Error('no roulette'); } },
    resolveAuth: async (_setting, supplied) => {
      assert.equal(supplied, signal);
      return { kind: 'service_account', accessToken: 'sa', projectId: 'p/a', location: 'us central1' };
    },
  });
  await api.generateText(messages, makeTextGenerationParams({ model: makeChatModel({ modelId: 'gemini/x' }) }), { signal });
  assert.equal(f.requests[0].url, 'https://aiplatform.googleapis.com/v1/projects/p%2Fa/locations/us%20central1/publishers/google/models/gemini%2Fx:generateContent');
  assert.equal(f.requests[0].headers['Authorization'], 'Bearer sa');
  assert.equal(f.requests[0].headers['x-goog-api-key'], undefined);
  assert.equal(f.signals[0], signal);
});

test('CodeAssist and Antigravity keep independent hosts/wrappers and unwrap nonstream response once', async () => {
  for (const mode of ['gemini_code_assist_oauth', 'antigravity_oauth'] as GoogleOAuthMode[]) {
    const f = fixture(JSON.stringify({ response }));
    const kind = mode === 'antigravity_oauth' ? 'antigravity' : 'code_assist';
    const api = createGoogleChatApi({
      http: f.http, setting: makeProviderSettingGoogle({ authMode: mode, baseUrl: 'https://wrong.example' }),
      keyRoulette: { next: () => { throw new Error('no roulette'); } },
      resolveAuth: async (): Promise<GoogleRequestAuth> => ({ kind, accessToken: 'oauth', projectId: 'project', location: '' }),
    });
    const result = await api.generateText(messages, params);
    assert.equal(result.choices[0].message?.parts[0].type, 'text');
    const request = f.requests[0];
    assert.equal(request.url, `https://${mode === 'antigravity_oauth' ? 'daily-' : ''}cloudcode-pa.googleapis.com/v1internal:generateContent`);
    assert.equal(request.headers['Authorization'], 'Bearer oauth');
    const body = JSON.parse(request.body ?? '') as JsonObject;
    assert.equal(body['model'], 'gemini-3-pro'); assert.equal(body['project'], 'project');
    assert.ok(body['request']);
    if (kind === 'antigravity') {
      assert.equal(body['userAgent'], 'antigravity'); assert.equal(body['requestType'], 'agent');
      assert.ok(String(body['requestId']).startsWith('agent-')); assert.equal(body['user_prompt_id'], undefined);
    } else {
      assert.equal(typeof body['user_prompt_id'], 'string'); assert.equal(body['requestType'], undefined);
    }
  }
});

test('auth cancellation before/after resolver and mismatched identity prevent any request', async () => {
  const signal = { aborted: false };
  const f = fixture(responseText);
  const setting = makeProviderSettingGoogle({ authMode: 'antigravity_oauth' });
  const api = createGoogleChatApi({ http: f.http, setting, resolveAuth: async () => {
    signal.aborted = true;
    return { kind: 'antigravity', accessToken: 'a', projectId: 'p', location: '' };
  } });
  await assert.rejects(api.generateText(messages, params, { signal }), /abort|cancel/i);
  assert.equal(f.requests.length, 0);
  let calls = 0;
  const alreadyCancelled = createGoogleChatApi({ http: f.http, setting, resolveAuth: async () => {
    calls++; return { kind: 'antigravity', accessToken: 'a', projectId: 'p', location: '' };
  } });
  await assert.rejects(alreadyCancelled.generateText(messages, params, { signal }), /abort|cancel/i);
  assert.equal(calls, 0);
  const mismatch = createGoogleChatApi({ http: f.http, setting, resolveAuth: async () =>
    ({ kind: 'code_assist', accessToken: 'a', projectId: 'p', location: '' }) });
  await assert.rejects(mismatch.generateText(messages, params), /identity|身份/i);
  assert.equal(f.requests.length, 0);
});

test('OAuth nested errors fail both responses and SSE without resending already consumed content', async () => {
  const setting = makeProviderSettingGoogle({ authMode: 'antigravity_oauth' });
  const resolveAuth = async (): Promise<GoogleRequestAuth> => ({ kind: 'antigravity', accessToken: 'a', projectId: 'p', location: '' });
  const nestedError: JsonObject = { response: { error: { message: 'quota reached', code: 429 } } };
  const nonstream = fixture(JSON.stringify(nestedError));
  await assert.rejects(createGoogleChatApi({ http: nonstream.http, setting, resolveAuth }).generateText(messages, params), /quota reached/);
  const stream = fixture('', [{ response }, nestedError, { response }]);
  let chunks = 0;
  await assert.rejects(createGoogleChatApi({ http: stream.http, setting, resolveAuth }).streamText(
    messages, params, () => { chunks++; }), /quota reached/);
  assert.equal(chunks, 1); assert.equal(stream.requests.length, 1);
});

test('authenticated SSE shares the body parser and original cancellation signal for all three identities', async () => {
  const signal: AbortSignalLike = { aborted: false };
  for (const kind of ['service_account', 'code_assist', 'antigravity'] as GoogleRequestAuth['kind'][]) {
    const mode = kind === 'service_account' ? 'api_key'
      : kind === 'code_assist' ? 'gemini_code_assist_oauth' : 'antigravity_oauth';
    const f = fixture('', [kind === 'service_account' ? response : { response }]);
    const api = createGoogleChatApi({
      http: f.http, setting: makeProviderSettingGoogle({ authMode: mode,
        vertexAI: kind === 'service_account', useServiceAccount: kind === 'service_account' }),
      resolveAuth: async (_setting, actualSignal) => {
        assert.equal(actualSignal, signal);
        return { kind, accessToken: 'a', projectId: 'p', location: 'global' };
      },
    });
    let chunks = 0;
    await api.streamText(messages, params, () => { chunks++; }, { signal });
    assert.equal(chunks, 1); assert.equal(f.signals[0], signal);
    assert.equal(f.requests[0].url.endsWith(':streamGenerateContent?alt=sse'), true);
  }
});

test('server onboarding tier/default/project/validation and LRO errors remain real', () => {
  assert.equal(buildGoogleLoadCodeAssistRequest('gemini_code_assist_oauth', '')['cloudaicompanionProject'], undefined);
  const plan = parseGoogleLoadCodeAssistResponse('gemini_code_assist_oauth', {
    allowedTiers: [{ id: 'standard-tier' }, { id: 'free-tier', isDefault: true }],
  }, 'p');
  assert.equal(plan.tierId, 'free-tier');
  assert.equal(plan.onboardRequest?.['cloudaicompanionProject'], undefined);
  assert.throws(() => parseGoogleLoadCodeAssistResponse('antigravity_oauth', {}, ''), { code: 'ineligible_tier' });
  assert.throws(() => parseGoogleLoadCodeAssistResponse('gemini_code_assist_oauth', { allowedTiers: [{ id: 'standard-tier' }] }, ''), { code: 'project_required' });
  assert.throws(() => parseGoogleLoadCodeAssistResponse('gemini_code_assist_oauth', {
    ineligibleTiers: [{ reasonCode: 'VALIDATION_REQUIRED', validationUrl: 'https://accounts.google.com/verify' }],
  }, ''), { code: 'validation_required', validationUrl: 'https://accounts.google.com/verify' });
  assert.deepEqual(parseGoogleOnboardOperation({ done: true, response: { cloudaicompanionProject: { id: 'real-project' } } }), { done: true, name: null, projectId: 'real-project' });
  assert.throws(() => parseGoogleOnboardOperation({ done: true, error: { message: 'failed' } }), { code: 'onboarding_error' });
  assert.throws(() => parseGoogleOnboardOperation({ done: true }), { code: 'project_missing' });
});

test('token refresh preserves credentials/project; SA exact claims; catalogs use returned IDs without fallback', () => {
  const previous = { accessToken: 'old', refreshToken: 'refresh', expiresAt: 1000, projectId: 'p', tierId: 'standard-tier' };
  assert.deepEqual(parseGoogleOAuthTokenResponse({ access_token: 'new', token_type: 'Bearer', expires_in: 3600 }, 1000, previous),
    { ...previous, accessToken: 'new', expiresAt: 3601000 });
  assert.throws(() => parseGoogleOAuthTokenResponse({ error: 'invalid_grant', error_description: 'private details' }, 0, previous), { code: 'invalid_grant' });
  assert.throws(() => parseGoogleOAuthTokenResponse({ access_token: 'x', token_type: 'Bearer' }, 0, null), { code: 'token_invalid' });
  assert.deepEqual(buildGoogleServiceAccountJwtClaims('sa@example.com', 1234000), {
    iss: 'sa@example.com', scope: 'https://www.googleapis.com/auth/cloud-platform',
    aud: 'https://oauth2.googleapis.com/token', iat: 1234, exp: 4834,
  });
  assert.equal(buildGoogleVertexCatalogUrl('a/b'), 'https://aiplatform.googleapis.com/v1beta1/publishers/google/models?pageSize=100&pageToken=a%2Fb');
  assert.deepEqual(parseGoogleVertexModelCatalog({ publisherModels: [{ name: 'publishers/google/models/gemini-real' }], nextPageToken: 'next' }),
    { models: [{ modelId: 'gemini-real', displayName: 'gemini-real' }], nextPageToken: 'next' });
  assert.deepEqual(parseGoogleAntigravityModelCatalog({ models: { 'gemini-real-high': { displayName: 'Actual' } } }),
    [{ modelId: 'gemini-real-high', displayName: 'Actual' }]);
});
