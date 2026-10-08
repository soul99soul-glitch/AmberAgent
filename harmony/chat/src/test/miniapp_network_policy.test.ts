// miniapp_network_policy — 限额常量/header allowlist/请求计划纯逻辑(MiniAppNetwork.kt)
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  MINI_APP_MAX_REQUEST_BODY_BYTES, isAllowedRequestHeader, buildMiniAppRequestPlan, resolveMiniAppResponseType
} from '../main/ets/chat/miniapp/miniapp_network_policy.ts';
import { MiniAppUrlGuard } from '../main/ets/chat/miniapp/miniapp_url_guard.ts';
import type { MiniAppRequestPlan } from '../main/ets/chat/miniapp/miniapp_network_policy.ts';

const guard: MiniAppUrlGuard = new MiniAppUrlGuard(async (): Promise<string[]> => ['93.184.216.34']);

test('isAllowedHeader: allowlist + cookie/authorization/proxy/x-forwarded 拒绝', () => {
  assert.equal(isAllowedRequestHeader('accept'), true);
  assert.equal(isAllowedRequestHeader('content-type'), true);
  assert.equal(isAllowedRequestHeader('user-agent'), true);
  assert.equal(isAllowedRequestHeader('Accept'), true); // 大小写不敏感
  assert.equal(isAllowedRequestHeader('cookie'), false);
  assert.equal(isAllowedRequestHeader('authorization'), false);
  assert.equal(isAllowedRequestHeader('proxy-authorization'), false);
  assert.equal(isAllowedRequestHeader('x-forwarded-for'), false);
  assert.equal(isAllowedRequestHeader('x-custom'), false);
});

test('buildMiniAppRequestPlan: 仅 https/GET+POST/header 过滤/body 限额', async () => {
  const httpGuard: MiniAppUrlGuard = new MiniAppUrlGuard(null);
  await assert.rejects((): Promise<MiniAppRequestPlan> =>
    buildMiniAppRequestPlan(httpGuard, { url: 'http://example.com' }));

  const plan: MiniAppRequestPlan = await buildMiniAppRequestPlan(guard, {
    url: 'https://example.com/api',
    method: 'post',
    headers: { 'accept': 'application/json', 'cookie': 'secret=1', 'authorization': 'Bearer x' },
    body: '{"a":1}',
  });
  assert.equal(plan.method, 'POST');
  assert.equal(plan.body, '{"a":1}');
  // 默认 accept + user-agent + 仅允许的 header
  assert.deepEqual(
    Object.keys(plan.headers).sort(),
    ['accept', 'user-agent'],
  );

  await assert.rejects((): Promise<MiniAppRequestPlan> =>
    buildMiniAppRequestPlan(guard, { url: 'https://example.com', method: 'DELETE' }));
  await assert.rejects((): Promise<MiniAppRequestPlan> =>
    buildMiniAppRequestPlan(guard, {
      url: 'https://example.com',
      body: 'x'.repeat(MINI_APP_MAX_REQUEST_BODY_BYTES + 1),
    }));
  await assert.rejects((): Promise<MiniAppRequestPlan> =>
    buildMiniAppRequestPlan(guard, {})); // 缺 url
});

test('resolveMiniAppResponseType: 缺省 text,小写归一', () => {
  assert.equal(resolveMiniAppResponseType({}), 'text');
  assert.equal(resolveMiniAppResponseType({ responseType: 'JSON' }), 'json');
  assert.equal(resolveMiniAppResponseType({ responseType: 'dataurl' }), 'dataurl');
  assert.equal(resolveMiniAppResponseType({ responseType: 'text' }), 'text');
});

// ===== Phase 5 回归:inet_aton 数字形式环回地址必须被识别为字面 IP =====

test('url guard: inet_aton numeric loopback forms are recognized and blocked', async () => {
  const guard = new MiniAppUrlGuard(null);
  const blocked: string[] = [
    'https://2130706433/x',   // 127.0.0.1 十进制 32 位
    'https://0x7f000001/x',   // 十六进制
    'https://017700000001/x', // 八进制
    'https://127.1/x',        // a.b 缩写
    'https://0x7f.1/x',       // 混合进制
    'https://10.1/x',         // 10.0.0.1 私网缩写
    'https://0.0.0.0/x',      // 原有形态不回归
    'https://127.0.0.1/x',
    'https://192.168.1.1/x',
  ];
  for (const url of blocked) {
    await assert.rejects(guard.check(url), /Blocked private or reserved host/,
      `expected blocked: ${url}`);
  }
  // 公网字面 IP 与普通域名仍放行(域名在无 resolver 路径仅字面校验)
  const allowed: string[] = ['https://8.8.8.8/x', 'https://1.2.3.4/x', 'https://example.com/x'];
  for (const url of allowed) {
    const rec = await guard.check(url);
    assert.ok(rec !== null, `expected allowed: ${url}`);
  }
  // 非法数字形态 → Invalid URL / 非 IP 字面 → 不抛 Blocked(仍按域名处理或拒绝)
  const junk = await guard.check('https://1.2.3.999/x').then(
    (): string => 'allowed', (e: Error): string => e.message);
  assert.ok(junk === 'Invalid URL' || junk === 'allowed' || /Blocked/.test(junk) === false
    || junk.includes('Invalid'), `unexpected: ${junk}`);
});
