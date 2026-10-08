import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { isPrivateUrl, urlAllowedForBackgroundFetch } from '../main/ets/research/url_filter.ts';

// ===== isPrivateUrl: public hosts are not private =====

test('isPrivateUrl: example.com is public (not private)', () => {
  assert.equal(isPrivateUrl('https://example.com'), false);
});

test('isPrivateUrl: reuters.com path is public', () => {
  assert.equal(isPrivateUrl('https://www.reuters.com/article/abc-123'), false);
});

// ===== loopback =====

test('isPrivateUrl: localhost is private', () => {
  assert.equal(isPrivateUrl('http://localhost:8080'), true);
});

test('isPrivateUrl: 127.0.0.1 is private', () => {
  assert.equal(isPrivateUrl('http://127.0.0.1/'), true);
});

test('isPrivateUrl: 127.x.x.x entire loopback range is private', () => {
  assert.equal(isPrivateUrl('http://127.255.255.254/'), true);
});

test('isPrivateUrl: 0.0.0.0 is private', () => {
  assert.equal(isPrivateUrl('http://0.0.0.0/'), true);
});

test('isPrivateUrl: ::1 IPv6 loopback is private', () => {
  assert.equal(isPrivateUrl('http://[::1]/'), true);
});

test('isPrivateUrl: :: IPv6 unspecified is private', () => {
  assert.equal(isPrivateUrl('http://[::]/'), true);
});

// ===== LAN / private ranges =====

test('isPrivateUrl: 192.168.x.x is private', () => {
  assert.equal(isPrivateUrl('http://192.168.1.1/'), true);
  assert.equal(isPrivateUrl('http://192.168.0.50:3000/x'), true);
});

test('isPrivateUrl: 10.x.x.x is private', () => {
  assert.equal(isPrivateUrl('http://10.0.0.1/'), true);
});

test('isPrivateUrl: 172.16-31.x.x is private', () => {
  assert.equal(isPrivateUrl('http://172.16.0.1/'), true);
  assert.equal(isPrivateUrl('http://172.20.0.1/'), true);
  assert.equal(isPrivateUrl('http://172.31.255.255/'), true);
});

test('isPrivateUrl: 172.32.x.x is PUBLIC (boundary)', () => {
  assert.equal(isPrivateUrl('http://172.32.0.1/'), false);
});

test('isPrivateUrl: 172.15.x.x is PUBLIC (below range)', () => {
  assert.equal(isPrivateUrl('http://172.15.0.1/'), false);
});

test('isPrivateUrl: 169.254.x.x link-local is private', () => {
  assert.equal(isPrivateUrl('http://169.254.1.1/'), true);
});

test('isPrivateUrl: 169.253.x.x is PUBLIC (not link-local)', () => {
  assert.equal(isPrivateUrl('http://169.253.1.1/'), false);
});

test('isPrivateUrl: 100.64-127.x.x CGNAT is private', () => {
  assert.equal(isPrivateUrl('http://100.64.0.1/'), true);
  assert.equal(isPrivateUrl('http://100.100.0.1/'), true);
  assert.equal(isPrivateUrl('http://100.127.255.255/'), true);
});

test('isPrivateUrl: 100.63.x.x is PUBLIC (below CGNAT)', () => {
  assert.equal(isPrivateUrl('http://100.63.0.1/'), false);
});

test('isPrivateUrl: 100.128.x.x is PUBLIC (above CGNAT)', () => {
  assert.equal(isPrivateUrl('http://100.128.0.1/'), false);
});

// ===== IPv6 ULA + link-local =====

test('isPrivateUrl: IPv6 ULA fc00:: is private', () => {
  assert.equal(isPrivateUrl('http://[fc00::1]/'), true);
});

test('isPrivateUrl: IPv6 ULA fd12:: is private', () => {
  assert.equal(isPrivateUrl('http://[fd12:3456::1]/'), true);
});

test('isPrivateUrl: IPv6 link-local fe80:: is private', () => {
  assert.equal(isPrivateUrl('http://[fe80::1]/'), true);
});

// ===== mDNS / internal TLDs =====

test('isPrivateUrl: .local mDNS is private', () => {
  assert.equal(isPrivateUrl('http://myhost.local/'), true);
});

test('isPrivateUrl: .localhost is private', () => {
  assert.equal(isPrivateUrl('http://svc.localhost/'), true);
});

test('isPrivateUrl: .internal is private', () => {
  assert.equal(isPrivateUrl('http://api.internal/'), true);
});

test('isPrivateUrl: .lan is private', () => {
  assert.equal(isPrivateUrl('http://router.lan/'), true);
});

// ===== edge cases =====

test('isPrivateUrl: empty/blank url returns false (matches Android)', () => {
  assert.equal(isPrivateUrl(''), false);
  assert.equal(isPrivateUrl('   '), false);
});

test('isPrivateUrl: unparseable url returns false (no throw)', () => {
  assert.equal(isPrivateUrl('not a url at all'), false);
});

// ===== urlAllowedForBackgroundFetch gate =====

test('gate: public url always allowed regardless of flags', () => {
  assert.equal(urlAllowedForBackgroundFetch('https://example.com', false, false), true);
  assert.equal(urlAllowedForBackgroundFetch('https://example.com', true, false), true);
});

test('gate: private url blocked when flags off', () => {
  assert.equal(urlAllowedForBackgroundFetch('http://localhost:8080', false, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://192.168.1.1', false, false), false);
});

test('gate: private url blocked when only one flag on', () => {
  assert.equal(urlAllowedForBackgroundFetch('http://localhost:8080', true, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://localhost:8080', false, true), false);
});

test('gate: private url allowed only when BOTH flags on', () => {
  assert.equal(urlAllowedForBackgroundFetch('http://localhost:8080', true, true), true);
  assert.equal(urlAllowedForBackgroundFetch('http://10.0.0.1', true, true), true);
});

// ===== Phase9 SSRF 全形态回归 =====
test('inet_aton 全形态私网地址被拒绝(整数/十六进制/八进制/短格式)', () => {
  assert.equal(urlAllowedForBackgroundFetch('http://2130706433/', false, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://0x7f000001/', false, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://017700000001/', false, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://127.1/', false, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://0x7f.1/', false, false), false);
});

test('IPv4-mapped IPv6 私网地址被拒绝(dotted 与十六进制尾)', () => {
  assert.equal(urlAllowedForBackgroundFetch('http://[::ffff:127.0.0.1]/', false, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://[::ffff:7f00:1]/', false, false), false);
});

test('公网全形态地址仍放行(整数 8.8.8.8 / 短格式 8.8)', () => {
  assert.equal(urlAllowedForBackgroundFetch('http://134744072/', false, false), true);
  assert.equal(urlAllowedForBackgroundFetch('http://8.8/', false, false), true);
});

test('userinfo 不构成绕过:attacker@127.0.0.1 仍按私网拒绝', () => {
  assert.equal(urlAllowedForBackgroundFetch('http://attacker@127.0.0.1/', false, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://u@[::1]/', false, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://user@8.8.8.8/', false, false), true);
});

test('多重 @ userinfo 也被正确剥离(a@b@127.0.0.1)', () => {
  assert.equal(urlAllowedForBackgroundFetch('http://a@b@127.0.0.1/', false, false), false);
  assert.equal(urlAllowedForBackgroundFetch('http://a@b@8.8.8.8/', false, false), true);
});

test('absolute DNS names retain the same private/public classification', () => {
  for (const host of ['localhost.', 'svc.localhost.', 'router.local.', 'api.internal.', 'host.lan.', '127.0.0.1.']) {
    assert.equal(urlAllowedForBackgroundFetch(`http://${host}/`, false, false), false, host);
    assert.equal(urlAllowedForBackgroundFetch(`http://${host}/`, true, true), true, host);
  }
  assert.equal(urlAllowedForBackgroundFetch('https://example.com./', false, false), true);
});

test('IPv6 equivalent loopback, unspecified, link-local and mapped private addresses are blocked', () => {
  for (const host of ['0:0:0:0:0:0:0:1', '0000:0:0::1', '0:0:0:0:0:0:0:0',
    'fe90::1', 'febf::1', 'fe80:0:0:0:0:0:0:1',
    '0:0:0:0:0:ffff:127.0.0.1', '0:0:0:0:0:ffff:7f00:1', '0000::ffff:192.168.1.1']) {
    assert.equal(urlAllowedForBackgroundFetch(`http://[${host}]/`, false, false), false, host);
    assert.equal(urlAllowedForBackgroundFetch(`http://[${host}]/`, true, true), true, host);
  }
  for (const host of ['2001:4860:4860::8888', 'fec0::1', '0:0:0:0:0:ffff:8.8.8.8']) {
    assert.equal(urlAllowedForBackgroundFetch(`http://[${host}]/`, false, false), true, host);
  }
});
