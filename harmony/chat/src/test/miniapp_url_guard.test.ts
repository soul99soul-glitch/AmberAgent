// miniapp_url_guard — SSRF 语义钉死(对齐 Android MiniAppUrlGuardTest 全部用例)
import test from 'node:test';
import assert from 'node:assert/strict';
import { MiniAppUrlGuard, resolveRedirectUrl, isIpLiteralHost } from '../main/ets/chat/miniapp/miniapp_url_guard.ts';
import type { MiniAppUrlRecord } from '../main/ets/chat/miniapp/miniapp_url_guard.ts';

const guardResolving = (...ips: string[]): MiniAppUrlGuard =>
  new MiniAppUrlGuard(async (host: string): Promise<string[]> => ips);

const assertRejected = async (fn: () => Promise<unknown>, message: string = 'Expected to reject'): Promise<void> => {
  await assert.rejects(fn(), (err: unknown) => {
    assert.equal(err instanceof Error, true, message);
    return true;
  });
};

test('allows public https hosts', async () => {
  const guard: MiniAppUrlGuard = guardResolving('93.184.216.34');
  const url: MiniAppUrlRecord = await guard.check('https://example.com/news');
  assert.equal(url.protocol, 'https');
  assert.equal(url.host, 'example.com');
});

test('rejects non-https', async () => {
  const guard: MiniAppUrlGuard = guardResolving('93.184.216.34');
  await assertRejected((): Promise<MiniAppUrlRecord> => guard.check('http://example.com'));
});

test('rejects private and loopback ipv4', async () => {
  const blocked: string[] = [
    '0.0.0.0',
    '10.1.2.3',
    '127.0.0.1',
    '100.64.0.1',
    '169.254.1.1',
    '172.16.0.1',
    '172.31.255.255',
    '192.168.1.1',
    '224.0.0.1',
  ];
  for (const ip of blocked) {
    const guard: MiniAppUrlGuard = guardResolving(ip);
    await assertRejected(
      (): Promise<MiniAppUrlRecord> => guard.check('https://example.com'),
      `Expected ${ip} to be rejected`,
    );
  }
});

test('rejects private and loopback ipv6', async () => {
  const blocked: string[] = [
    '::1',
    'fc00::1',
    'fd12::1',
    'fe80::1',
    'ff02::1',
    '::ffff:127.0.0.1',
    '::ffff:192.168.1.1',
  ];
  for (const ip of blocked) {
    const guard: MiniAppUrlGuard = guardResolving(ip);
    await assertRejected(
      (): Promise<MiniAppUrlRecord> => guard.check('https://example.com'),
      `Expected ${ip} to be rejected`,
    );
  }
});

test('resolveAllowed uses same private address guard', async () => {
  const guard: MiniAppUrlGuard = guardResolving('127.0.0.1');
  await assertRejected((): Promise<string[]> => guard.resolveAllowed('example.com'));
});

test('allows public ipv6 hosts', async () => {
  const guard: MiniAppUrlGuard = guardResolving('2001:4860:4860::8888');
  const url: MiniAppUrlRecord = await guard.check('https://example.com');
  assert.equal(url.host, 'example.com');
});

test('rejects unresolvable and empty resolution', async () => {
  const throwing: MiniAppUrlGuard = new MiniAppUrlGuard(async (): Promise<string[]> => {
    throw new Error('dns fail');
  });
  await assertRejected((): Promise<MiniAppUrlRecord> => throwing.check('https://example.com'));
  const empty: MiniAppUrlGuard = new MiniAppUrlGuard(async (): Promise<string[]> => []);
  await assertRejected((): Promise<string[]> => empty.resolveAllowed('example.com'));
});

test('rejects literal private ip without resolver', async () => {
  const guard: MiniAppUrlGuard = new MiniAppUrlGuard(null);
  await assertRejected((): Promise<MiniAppUrlRecord> => guard.check('https://127.0.0.1/'));
  const url: MiniAppUrlRecord = await guard.check('https://93.184.216.34/');
  assert.equal(url.host, '93.184.216.34');
});

// 回归:方括号 IPv6 字面量的 host 提取(曾因端口剥离误判把 "[::1]" 解析成 "[:" 而绕过字面校验)
test('extracts bracketed ipv6 host without port', async () => {
  const guard: MiniAppUrlGuard = new MiniAppUrlGuard(null);
  const url: MiniAppUrlRecord = await guard.check('https://[2606:2800:220:1:248:1893:25c8:19e6]/');
  assert.equal(url.host, '2606:2800:220:1:248:1893:25c8:19e6');
});

test('extracts bracketed ipv6 host with port', async () => {
  const guard: MiniAppUrlGuard = new MiniAppUrlGuard(null);
  const url: MiniAppUrlRecord = await guard.check('https://[2606:2800:220:1:248:1893:25c8:19e6]:8443/');
  assert.equal(url.host, '2606:2800:220:1:248:1893:25c8:19e6');
});

test('rejects bracketed loopback ipv6 literal without port', async () => {
  const guard: MiniAppUrlGuard = new MiniAppUrlGuard(null);
  await assertRejected((): Promise<MiniAppUrlRecord> => guard.check('https://[::1]/'));
  await assertRejected((): Promise<MiniAppUrlRecord> => guard.check('https://[::ffff:127.0.0.1]/'));
});

test('rejects bracketed link-local ipv6 literal', async () => {
  const guard: MiniAppUrlGuard = new MiniAppUrlGuard(null);
  await assertRejected((): Promise<MiniAppUrlRecord> => guard.check('https://[fe80::1]:443/'));
});

test('rejects malformed ipv6 authority forms', async () => {
  const guard: MiniAppUrlGuard = new MiniAppUrlGuard(null);
  await assertRejected((): Promise<MiniAppUrlRecord> => guard.check('https://[:/')); // 未闭合方括号
  await assertRejected((): Promise<MiniAppUrlRecord> => guard.check('https://::1/')); // 未加方括号的多冒号
  await assertRejected((): Promise<MiniAppUrlRecord> => guard.check('https://[::1]443/')); // ']' 后非端口
});

// 重定向相对引用解析(okhttp HttpUrl.resolve 子集)
test('resolveRedirectUrl handles absolute and protocol-relative locations', () => {
  assert.equal(resolveRedirectUrl('https://a.com/x', 'https://b.com/y'), 'https://b.com/y');
  assert.equal(resolveRedirectUrl('https://a.com/x', '//b.com/y'), 'https://b.com/y');
  assert.equal(resolveRedirectUrl('http://a.com/x', '//b.com/y'), 'http://b.com/y');
});

test('resolveRedirectUrl handles root-relative and relative locations', () => {
  assert.equal(resolveRedirectUrl('https://a.com/dir/page?q=1', '/root'), 'https://a.com/root');
  assert.equal(resolveRedirectUrl('https://a.com/dir/page', 'next'), 'https://a.com/dir/next');
  assert.equal(resolveRedirectUrl('https://a.com/dir/page', './next'), 'https://a.com/dir/next');
  assert.equal(resolveRedirectUrl('https://a.com/dir/page', '../up'), 'https://a.com/up');
  assert.equal(resolveRedirectUrl('https://a.com/dir/page', 'sub/deep'), 'https://a.com/dir/sub/deep');
  assert.equal(resolveRedirectUrl('https://a.com/dir/', 'x'), 'https://a.com/dir/x');
});

test('resolveRedirectUrl collapses dot segments', () => {
  assert.equal(resolveRedirectUrl('https://a.com/dir/page', '/a/./b/../c'), 'https://a.com/a/c');
  assert.equal(resolveRedirectUrl('https://a.com/dir/page', '/a/b/..'), 'https://a.com/a/');
  assert.equal(resolveRedirectUrl('https://a.com/x', '/..'), 'https://a.com/');
});

test('resolveRedirectUrl keeps query and fragment only forms on base path', () => {
  assert.equal(resolveRedirectUrl('https://a.com/dir/page?old=1', '?new=1'), 'https://a.com/dir/page?new=1');
});

test('resolveRedirectUrl rejects invalid inputs', () => {
  assert.equal(resolveRedirectUrl('https://a.com/x', ''), null);
  assert.equal(resolveRedirectUrl('https://a.com/x', '   '), null);
  assert.equal(resolveRedirectUrl('ftp://a.com/x', '/y'), null); // 非 http(s) 基址
});

test('isIpLiteralHost detects literal ips', () => {
  assert.equal(isIpLiteralHost('93.184.216.34'), true);
  assert.equal(isIpLiteralHost('::1'), true);
  assert.equal(isIpLiteralHost('::ffff:127.0.0.1'), true);
  assert.equal(isIpLiteralHost('2606:2800:220:1:248:1893:25c8:19e6'), true);
  assert.equal(isIpLiteralHost('example.com'), false);
  assert.equal(isIpLiteralHost('[::1]'), false); // 方括号已在上游剥离
});
