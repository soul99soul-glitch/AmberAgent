// E12 A domain controlled group: OAuth protocol, full HAR consumption, readonly profiles.
// Pure logic only; no platform, no network, no live-site claim.
const { test } = require('node:test');
const assert = require('node:assert/strict');
require('tsx/cjs');
const oauth = require('../main/ets/chat/webmount/oauth.ts');
const har = require('../main/ets/chat/webmount/har.ts');
const profiles = require('../main/ets/chat/webmount/profiles.ts');
const { webMountOriginOf, webMountRedactedDisplayUrl, isWebMountMutatingReplayUrl } = require('../main/ets/chat/webmount/url.ts');

const application = (overrides = {}) => ({
  siteId: 'example', bindingHash: '',
  authorizationEndpoint: 'https://accounts.example.com/authorize',
  tokenEndpoint: 'https://accounts.example.com/token',
  clientId: 'client-1', redirectUri: 'http://127.0.0.1:53682/callback', scope: 'read write',
  tokenEncoding: 'form', clientAuthentication: 'body',
  apiOrigins: ['https://api.example.com'], clientSecretRef: 'ref-1',
  ...overrides,
});

test('oauth validation is fail-closed and binding covers config plus secret reference', () => {
  const valid = oauth.validateWebMountOAuthApplication(application());
  assert.deepEqual(valid.apiOrigins, ['https://api.example.com']);
  for (const bad of [
    { siteId: 'UPPER CASE' },
    { authorizationEndpoint: 'http://accounts.example.com/authorize' },
    { authorizationEndpoint: 'https://user@accounts.example.com/authorize' },
    { tokenEndpoint: 'https://accounts.example.com/token#frag' },
    { redirectUri: 'https://evil.example.com/callback' },
    { redirectUri: 'http://127.0.0.1/callback' },
    { apiOrigins: [] },
    { apiOrigins: ['http://api.example.com'] },
    { apiOrigins: ['https://api.example.com/path'] },
    { clientAuthentication: 'basic', tokenEncoding: 'xml' },
  ]) assert.throws(() => oauth.validateWebMountOAuthApplication(application(bad)), /invalid/);
  assert.deepEqual(oauth.validateWebMountOAuthApplication(application({ apiOrigins: ['http://127.0.0.1:8080'] })).apiOrigins,
    ['http://127.0.0.1:8080']);
  const canonical = oauth.canonicalWebMountOAuthBinding(valid, 'ref-1');
  assert.notEqual(oauth.canonicalWebMountOAuthBinding(valid, 'ref-2'), canonical);
  assert.notEqual(oauth.canonicalWebMountOAuthBinding(oauth.validateWebMountOAuthApplication(application({ scope: 'read' })), 'ref-1'), canonical);
});

test('oauth authorize URL, token request shapes and response parsing', () => {
  const valid = oauth.validateWebMountOAuthApplication(application());
  const url = oauth.buildWebMountAuthorizationUrl(valid, { state: 'state-1', codeChallenge: 'challenge-1' });
  assert.match(url, /^https:\/\/accounts\.example\.com\/authorize\?/);
  for (const part of ['response_type=code', 'client_id=client-1', 'state=state-1', 'code_challenge=challenge-1',
    'code_challenge_method=S256', `redirect_uri=${encodeURIComponent('http://127.0.0.1:53682/callback')}`, 'scope=read%20write'])
    assert.ok(url.includes(part), part);
  const code = oauth.buildWebMountTokenRequest(valid, 'secret-1', { grantType: 'authorization_code', code: 'code-1', codeVerifier: 'verifier-1', refreshToken: null });
  assert.equal(code.method, 'POST'); assert.equal(code.url, 'https://accounts.example.com/token');
  assert.equal(code.contentType, 'application/x-www-form-urlencoded'); assert.equal(code.authorizationHeader, null);
  for (const part of ['grant_type=authorization_code', 'code=code-1', 'code_verifier=verifier-1', 'client_id=client-1', 'client_secret=secret-1'])
    assert.ok(code.body.includes(part), part);
  const basic = oauth.buildWebMountTokenRequest(oauth.validateWebMountOAuthApplication(application({ clientAuthentication: 'basic', tokenEncoding: 'json' })),
    'secret-1', { grantType: 'refresh_token', code: null, codeVerifier: null, refreshToken: 'refresh-1' });
  assert.equal(basic.contentType, 'application/json');
  assert.equal(basic.authorizationHeader, `Basic ${Buffer.from('client-1:secret-1').toString('base64')}`);
  assert.deepEqual(JSON.parse(basic.body), { grant_type: 'refresh_token', refresh_token: 'refresh-1' });
  assert.throws(() => oauth.buildWebMountTokenRequest(oauth.validateWebMountOAuthApplication(application({ clientAuthentication: 'basic' })), null,
    { grantType: 'refresh_token', code: null, codeVerifier: null, refreshToken: 'r' }), /secret/);
  const token = oauth.parseWebMountTokenResponse('{"access_token":"a","refresh_token":"r","expires_in":60,"token_type":"Bearer"}', 1000);
  assert.equal(token.accessToken, 'a'); assert.equal(token.refreshToken, 'r'); assert.equal(token.expiresAtMillis, 61000);
  assert.throws(() => oauth.parseWebMountTokenResponse('{"error":"invalid_grant","error_description":"expired code"}', 0), /expired code/);
  assert.equal(oauth.webMountOAuthAllowsApiOrigin(valid, 'https://api.example.com/v1/data?x=1'), true);
  assert.equal(oauth.webMountOAuthAllowsApiOrigin(valid, 'https://other.example.com/v1/data'), false);
  assert.equal(oauth.webMountOAuthAllowsApiOrigin(valid, 'https://api.example.com.evil.com/'), false);
});

const HAR_DOC = JSON.stringify({
  log: {
    version: '1.2', creator: { name: 'fixture', version: '1' },
    pages: [{ id: 'page-1', startedDateTime: '2026-09-28T00:00:00Z', title: 't', pageTimings: {} }],
    entries: [
      {
        startedDateTime: '2026-09-28T00:00:01Z', time: 12,
        request: {
          method: 'GET', url: 'https://api.example.com/v1/items?token=secret&page=2', httpVersion: 'HTTP/2',
          cookies: [], headers: [{ name: 'Accept', value: 'application/json' }],
          queryString: [{ name: 'token', value: 'secret' }, { name: 'page', value: '2' }], headersSize: -1, bodySize: 0,
        },
        response: {
          status: 200, statusText: 'OK', httpVersion: 'HTTP/2', cookies: [], headers: [],
          content: { size: 20, mimeType: 'application/json', text: '{"items":[1,2,3]}' },
          redirectURL: '', headersSize: -1, bodySize: 20,
        },
        timings: { send: 1, wait: 10, receive: 1, blocked: -1, dns: -1 },
        _extension: { kept: true },
      },
      {
        startedDateTime: '2026-09-28T00:00:02Z', time: 8,
        request: {
          method: 'POST', url: 'https://api.example.com/v1/items', httpVersion: 'HTTP/2',
          cookies: [], headers: [], queryString: [], headersSize: -1, bodySize: 15,
          postData: { mimeType: 'application/json', text: '{"name":"x"}' },
        },
        response: {
          status: 201, statusText: 'Created', httpVersion: 'HTTP/2', cookies: [], headers: [],
          content: { size: 2, text: '{}' }, redirectURL: '', headersSize: -1, bodySize: 2,
        },
        timings: { send: 2, wait: 5, receive: 1 },
      },
    ],
  },
});

test('complete HAR retains structure and extension fields; replay projection stays narrower', () => {
  const archive = har.parseWebMountHar(HAR_DOC, 'archive-1', 123);
  assert.equal(archive.id, 'archive-1'); assert.equal(archive.importedAtMillis, 123);
  assert.equal(archive.har.log.entries[0]._extension.kept, true); // complete JSON retained
  assert.equal(archive.har.log.entries[1].request.postData.text, '{"name":"x"}');
  const templates = har.webMountHarTemplates(archive, 'main');
  assert.deepEqual(templates.map(t => t.id), ['archive-1-0', 'archive-1-1']);
  assert.deepEqual(templates.map(t => t.method), ['GET', 'POST']);
  assert.ok(templates.every(t => t.source === 'imported_har' && t.sourceId === 'archive-1' && t.documentId === null));
  const page = 'https://api.example.com/dashboard';
  assert.equal(har.webMountReplayAllowed(templates[0], page), true);   // same-origin GET
  assert.equal(har.webMountReplayAllowed(templates[1], page), false);  // POST never replays
  assert.equal(har.webMountReplayAllowed(templates[0], 'https://other.example.com/x'), false); // cross-origin
  assert.equal(har.webMountReplayAllowed(templates[0], 'not a url'), false);
  const mutating = { ...templates[0], url: 'https://api.example.com/v1/items?action=delete' };
  assert.equal(har.webMountReplayAllowed(mutating, page), false);
});

test('HAR validation rejects malformed, oversized and wrong-version input', () => {
  const mutate = (fn) => { const doc = JSON.parse(HAR_DOC); fn(doc); return JSON.stringify(doc); };
  assert.throws(() => har.parseWebMountHar('{not json', 'a', 0), /not valid JSON/);
  assert.throws(() => har.parseWebMountHar(mutate(d => { d.log.version = '1.1'; }), 'a', 0), /1\.2/);
  assert.throws(() => har.parseWebMountHar(mutate(d => { delete d.log.creator; }), 'a', 0), /creator/);
  assert.throws(() => har.parseWebMountHar(mutate(d => { d.log.entries[0].request.url = 'javascript:alert(1)'; }), 'a', 0), /absolute/);
  assert.throws(() => har.parseWebMountHar(mutate(d => { d.log.entries[0].timings.send = 'fast'; }), 'a', 0), /send/);
  assert.throws(() => har.parseWebMountHar(mutate(d => { d.log.entries[0].response.headers = { Accept: 'x' }; }), 'a', 0), /headers/);
  assert.throws(() => har.parseWebMountHar(mutate(d => { d.log.entries[0].time = -5; }), 'a', 0), /time/);
  const big = mutate(d => { d.log.entries = Array.from({ length: 201 }, () => d.log.entries[0]); });
  assert.throws(() => har.parseWebMountHar(big, 'a', 0), /200 entries/);
});

test('url helpers: origin identity, redacted display, android-aligned mutation heuristic', () => {
  assert.equal(webMountOriginOf('https://API.example.com:8443/x?q=1'), 'https://api.example.com:8443');
  assert.equal(webMountOriginOf('https://user@example.com/'), null);
  assert.equal(webMountOriginOf('ftp://example.com/'), null);
  assert.equal(webMountRedactedDisplayUrl('https://a.com/p?token=secret&page=2&token=again#frag'),
    'https://a.com/p?token=<redacted>&page=<redacted>');
  assert.equal(isWebMountMutatingReplayUrl('https://a.com/v1/post/delete'), true);
  assert.equal(isWebMountMutatingReplayUrl('https://a.com/v1/items?action=submit'), true);
  assert.equal(isWebMountMutatingReplayUrl('https://a.com/v1/items?page=2'), false);
});

test('nine readonly profiles exist; generated script extracts only visible text and public links', () => {
  assert.equal(profiles.WEBMOUNT_READONLY_SITE_PROFILES.length, 9);
  const script = profiles.buildWebMountSiteAdapterScript('hackernews', 2);
  const el = (text, { visible = true, href = null, tag = 'TD', inner = null } = {}) => ({
    tagName: tag, innerText: text, textContent: text,
    offsetWidth: visible ? 10 : 0, offsetHeight: visible ? 10 : 0,
    getClientRects: () => (visible ? [1] : []),
    querySelector: (sel) => (sel === 'a[href]' ? inner : null),
    href,
  });
  const link = (href) => ({ href });
  const stories = [
    el('真实故事一', { inner: link('https://news.ycombinator.com/item?id=1') }),
    el('真实故事二', { inner: link('javascript:alert(1)') }),
    el('隐藏故事', { visible: false }),
  ];
  const users = [el('user-a', { href: 'https://news.ycombinator.com/user?id=a', tag: 'A' })];
  const document = {
    querySelectorAll: (selector) => (selector.includes('titlelink') || selector.includes('storylink') ? stories
      : selector.includes('hnuser') ? users : []),
  };
  const result = JSON.parse(new Function('document', `return (${script});`)(document));
  assert.equal(result.matched, 3); // two visible stories + one user; hidden excluded
  assert.equal(result.truncated, false);
  assert.deepEqual(result.fields.story_title, [
    { text: '真实故事一', href: 'https://news.ycombinator.com/item?id=1' },
    { text: '真实故事二' }, // javascript: href never becomes a public link
  ]);
  assert.deepEqual(result.fields.user_link, [{ text: 'user-a', href: 'https://news.ycombinator.com/user?id=a' }]);
  assert.equal(result.fields.comment_tree.length, 0);
  const capped = JSON.parse(new Function('document', `return (${profiles.buildWebMountSiteAdapterScript('hackernews', 1)});`)(document));
  assert.equal(capped.fields.story_title.length, 1);
  assert.equal(capped.truncated, true);
  assert.throws(() => profiles.buildWebMountSiteAdapterScript('unknown_site'), /unknown/);
  assert.equal(profiles.webMountSiteMatchesUrl(profiles.webMountSiteProfile('bilibili'), 'https://www.bilibili.com/video/BV1'), true);
  assert.equal(profiles.webMountSiteMatchesUrl(profiles.webMountSiteProfile('bilibili'), 'https://passport.bilibili.com/login'), false);
});
