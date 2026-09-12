import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createWebMountPkce, buildFeishuAuthorizationUrl, buildFeishuTokenBody,
  parseFeishuTokenResponse, webMountTokenUsable, defaultFeishuRedirectUri,
  type WebMountCryptoPort, type WebMountOAuthToken,
} from '../main/ets/chat/webmount_oauth.ts';

const mockCrypto = (): WebMountCryptoPort => ({
  sha256Base64Url: async (input) => `ch_${input}`,
  randomBase64Url: async (n) => `r${n}_abc`,
});

test('pkce generates verifier/challenge/state', async () => {
  const p = await createWebMountPkce(mockCrypto(), 32);
  assert.equal(p.codeVerifier, 'r32_abc');
  assert.equal(p.codeChallenge, 'ch_r32_abc');
  assert.equal(p.state, 'r16_abc');
});

test('feishu authorize URL includes pkce + app_id/client_id', () => {
  const url = buildFeishuAuthorizationUrl({
    appId: 'cli_1',
    state: 'st',
    codeChallenge: 'ch',
    scope: 'docx:document',
  });
  assert.ok(url.startsWith('https://accounts.feishu.cn/open-apis/authen/v1/authorize?'));
  assert.ok(url.indexOf('app_id=cli_1') >= 0);
  assert.ok(url.indexOf('client_id=cli_1') >= 0);
  assert.ok(url.indexOf('code_challenge=ch') >= 0);
  assert.ok(url.indexOf('code_challenge_method=S256') >= 0);
  assert.ok(url.indexOf(encodeURIComponent(defaultFeishuRedirectUri)) >= 0);
});

test('token body and parse', () => {
  const body = JSON.parse(buildFeishuTokenBody({
    appId: 'cli_1',
    appSecret: 'sec',
    code: 'authcode',
    codeVerifier: 'ver',
  })) as Record<string, string>;
  assert.equal(body['grant_type'], 'authorization_code');
  assert.equal(body['code_verifier'], 'ver');
  const token = parseFeishuTokenResponse(
    '{"access_token":"at","refresh_token":"rt","expires_in":3600,"token_type":"Bearer"}',
    1000,
  );
  assert.equal(token.accessToken, 'at');
  assert.equal(token.expiresAtMillis, 1000 + 3600 * 1000);
  assert.throws(() => parseFeishuTokenResponse('{"error":"invalid_grant"}', 0));
});

test('token usable with skew', () => {
  const t: WebMountOAuthToken = {
    accessToken: 'a', refreshToken: null, expiresAtMillis: 120_000, tokenType: 'Bearer', scope: null,
  };
  assert.equal(webMountTokenUsable(t, 0), true);
  assert.equal(webMountTokenUsable(t, 120_000), false);
  assert.equal(webMountTokenUsable(null, 0), false);
});
