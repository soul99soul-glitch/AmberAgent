// vision_health 测试 — VisionModelHealthChecker.kt 行为钉住(D-111)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  isVisionHealthAvailable,
  probeVisionModelHealth,
  VISION_PROBE_SYSTEM_PROMPT,
  VISION_PROBE_TINY_PNG,
  VISION_PROBE_USER_TEXT,
} from '../main/ets/chat/vision_health.ts';
import type {
  VisionProbeDeps,
} from '../main/ets/chat/vision_health.ts';
import type { ProviderModel, ProviderSetting } from '../main/ets/chat/provider_settings.ts';
import { makeProviderModel, makeProviderSettingOpenAIVariant } from '../main/ets/chat/provider_settings.ts';
import type { UIMessage } from '../main/ets/chat/message.ts';

const visionModel = (): ProviderModel =>
  makeProviderModel({ id: 'm-vision', modelId: 'gpt-4o', inputModalities: ['text', 'image'] });

const textOnlyModel = (): ProviderModel =>
  makeProviderModel({ id: 'm-text', modelId: 'gpt-4o-mini', inputModalities: ['text'] });

const providerOf = (models: ProviderModel[]): ProviderSetting =>
  makeProviderSettingOpenAIVariant({ id: 'p1', name: 'P', baseUrl: 'https://x', apiKey: 'k', models });

const depsOf = (
  model: ProviderModel | null,
  provider: ProviderSetting | null,
  generateText?: (provider: ProviderSetting, model: ProviderModel, messages: UIMessage[]) => Promise<unknown>,
): VisionProbeDeps => ({
  findOcrModel: (): ProviderModel | null => model,
  modelSupportsImage: (m: ProviderModel): boolean => m.inputModalities.includes('image'),
  findProvider: (): ProviderSetting | null => provider,
  generateText: generateText ?? ((): Promise<unknown> => Promise.resolve('OK')),
});

test('模型未配置 → NOT_CONFIGURED 未配置', async () => {
  const h = await probeVisionModelHealth(depsOf(null, null));
  assert.deepEqual(h, { kind: 'not_configured', label: '未配置' });
});

test('模型不支持图片 → UNSUPPORTED 不支持图片', async () => {
  const m = textOnlyModel();
  const h = await probeVisionModelHealth(depsOf(m, providerOf([m])));
  assert.deepEqual(h, { kind: 'unsupported', label: '不支持图片' });
});

test('提供商缺失 → PROVIDER_MISSING 提供商不可用', async () => {
  const h = await probeVisionModelHealth(depsOf(visionModel(), null));
  assert.deepEqual(h, { kind: 'provider_missing', label: '提供商不可用' });
});

test('探测成功 → AVAILABLE 可用;isAvailable true', async () => {
  const m = visionModel();
  const h = await probeVisionModelHealth(depsOf(m, providerOf([m])));
  assert.deepEqual(h, { kind: 'available', label: '可用' });
  assert.equal(isVisionHealthAvailable(h), true);
});

test('探测消息形态:system + user[Text, Image(TINY_PNG)] 逐字', async () => {
  const m = visionModel();
  let captured: UIMessage[] = [];
  const h = await probeVisionModelHealth(depsOf(m, providerOf([m]),
    (_p, _m, messages: UIMessage[]): Promise<unknown> => {
      captured = messages;
      return Promise.resolve('OK');
    }));
  assert.equal(h.kind, 'available');
  assert.equal(captured.length, 2);
  assert.equal(captured[0].role, 'system');
  assert.equal(captured[0].parts[0].type, 'text');
  if (captured[0].parts[0].type === 'text') {
    assert.equal(captured[0].parts[0].text, VISION_PROBE_SYSTEM_PROMPT);
  }
  assert.equal(captured[1].role, 'user');
  assert.equal(captured[1].parts.length, 2);
  const p0 = captured[1].parts[0];
  const p1 = captured[1].parts[1];
  assert.equal(p0.type, 'text');
  if (p0.type === 'text') assert.equal(p0.text, VISION_PROBE_USER_TEXT);
  assert.equal(p1.type, 'image');
  if (p1.type === 'image') assert.equal(p1.url, VISION_PROBE_TINY_PNG);
  assert.ok(VISION_PROBE_TINY_PNG.startsWith('data:image/png;base64,iVBORw0KG'));
});

test('探测异常(有 message)→ FAILED 不可用：<msg>(全角冒号)', async () => {
  const m = visionModel();
  const h = await probeVisionModelHealth(depsOf(m, providerOf([m]),
    (): Promise<unknown> => Promise.reject(new Error('HTTP 401'))));
  assert.deepEqual(h, { kind: 'failed', label: '不可用：HTTP 401' });
});

test('探测异常(非 Error)→ FAILED 不可用：检测失败', async () => {
  const m = visionModel();
  const h = await probeVisionModelHealth(depsOf(m, providerOf([m]),
    (): Promise<unknown> => Promise.reject('boom')));
  assert.deepEqual(h, { kind: 'failed', label: '不可用：检测失败' });
});
