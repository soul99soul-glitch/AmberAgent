// ocr_transformer 规格测试(D-071a)
// Android 基准: app/.../core/ai/transformers/OcrTransformer.kt(全文 141 行)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import { createOcrTransformer, performImageRecognition } from '../main/ets/chat/ocr_transformer.ts';
import type { OcrTransformerDeps } from '../main/ets/chat/ocr_transformer.ts';
import { createVisionCache } from '../main/ets/chat/vision_cache.ts';
import type { VisionCache } from '../main/ets/chat/vision_cache.ts';
import {
  DEFAULT_VISION_RECOGNITION_PROMPT, javaStringHashCode,
} from '../main/ets/chat/vision_fallback.ts';
import { makeProviderModel, makeProviderSettingOpenAIVariant } from '../main/ets/chat/provider_settings.ts';
import type { ProviderModel, ProviderSetting } from '../main/ets/chat/provider_settings.ts';
import { makeUserMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart, UIMessagePartImage } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { TransformerContext } from '../main/ets/chat/transformer_pipeline.ts';

const ocrModel: ProviderModel = makeProviderModel({ id: 'ocr-1', inputModalities: ['text', 'image'] });
const textOnlyModel: ProviderModel = makeProviderModel({ id: 'txt-1', inputModalities: ['text'] });
const providerSetting: ProviderSetting = makeProviderSettingOpenAIVariant({});

const imageMsg = (url: string): UIMessage => ({
  ...makeUserMessage('t'),
  parts: [{ type: 'image', url, metadata: null }],
});

const ctxOf = (forceImageToText?: boolean, statuses?: Array<string | null>): TransformerContext => ({
  assistant: makeAssistant({}),
  forceImageToText,
  processingStatus: statuses !== undefined
    ? (s: string | null): void => { statuses.push(s); }
    : undefined,
});

const makeDeps = (overrides: Partial<OcrTransformerDeps> = {}): OcrTransformerDeps => ({
  modelSupportsImageInput: false,
  findOcrModel: (): ProviderModel | null => ocrModel,
  modelSupportsImage: (m: ProviderModel): boolean => m.inputModalities.includes('image'),
  findProviderForModel: (): ProviderSetting | null => providerSetting,
  generateText: (): Promise<string | null> => Promise.resolve('识别结果'),
  ocrPrompt: (): string => '',
  cache: createVisionCache({ read: (): string | null => null, write: (): void => {} }),
  ...overrides,
});

// ===== transform 门(OcrTransformer.kt:48-56) =====

test('transform:模型支持图像 && 非 force → 原样引用返回(不识别)', async () => {
  const deps = makeDeps({ modelSupportsImageInput: true });
  const t = createOcrTransformer(deps);
  const msgs: UIMessage[] = [imageMsg('file://a.png')];
  const out = await t.transform!(ctxOf(), msgs);
  assert.equal(out, msgs);
});

test('transform:无图消息 → 原样返回', async () => {
  const t = createOcrTransformer(makeDeps());
  const msgs: UIMessage[] = [makeUserMessage('纯文本')];
  assert.equal(await t.transform!(ctxOf(), msgs), msgs);
});

test('transform:图片 → image_context 文本替换;状态文案 正在识别图片... → null(:57-72)', async () => {
  const statuses: Array<string | null> = [];
  const t = createOcrTransformer(makeDeps());
  const msgs: UIMessage[] = [makeUserMessage('前文'), imageMsg('file://a.png')];
  const out = await t.transform!(ctxOf(undefined, statuses), msgs);
  assert.deepEqual(statuses, ['正在识别图片...', null]);
  const parts = out[1].parts;
  assert.equal(parts.length, 1);
  assert.equal(parts[0].type, 'text');
  const text = (parts[0] as { text: string }).text;
  assert.equal(
    text,
    '<image_context>\n识别结果\n</image_context>\n' +
    "* The image_context tag contains visual recognition results for an image uploaded by the user, not the user's prompt.");
  assert.deepEqual(out[0], msgs[0], '无图消息内容不动(Android messages.map 全量 copy,不保鲜引用)');
});

test('transform:模型支持图像但 forceImageToText=true → 仍识别(vision fallback 路径)', async () => {
  let generated = 0;
  const deps = makeDeps({
    modelSupportsImageInput: true,
    generateText: (): Promise<string | null> => { generated += 1; return Promise.resolve('强转'); },
  });
  const t = createOcrTransformer(deps);
  const out = await t.transform!(ctxOf(true), [imageMsg('file://a.png')]);
  assert.equal(generated, 1);
  assert.equal((out[0].parts[0] as { text: string }).text.includes('<image_context>\n强转\n'), true);
});

// ===== performImageRecognition 错误链(:91-140) =====

test('错误链:未配置模型 / 模型不支持 / 提供商不可用 / 调用失败(全角冒号+ cause)/ 空内容', async () => {
  const part = imageMsg('file://a.png').parts[0] as UIMessagePartImage;
  await assert.rejects(
    performImageRecognition(part, makeDeps({ findOcrModel: (): ProviderModel | null => null })),
    /请先配置视觉识别模型/);
  await assert.rejects(
    performImageRecognition(part, makeDeps({ findOcrModel: (): ProviderModel | null => textOnlyModel })),
    /视觉识别模型不支持图片输入/);
  await assert.rejects(
    performImageRecognition(part, makeDeps({ findProviderForModel: (): ProviderSetting | null => null })),
    /视觉识别模型的提供商不可用/);
  const cause = new Error('http 500');
  const callErr = await performImageRecognition(part, makeDeps({
    generateText: (): Promise<string | null> => Promise.reject(cause),
    cache: createVisionCache({ read: (): string | null => null, write: (): void => {} }),
  })).then((): null => null, (e: Error): Error => e);
  assert.equal(callErr !== null && callErr.message, '视觉识别模型调用失败：http 500');
  assert.equal(callErr !== null && callErr.cause, cause);
  await assert.rejects(
    performImageRecognition(part, makeDeps({ generateText: (): Promise<string | null> => Promise.resolve('  ') })),
    /视觉识别模型没有返回可用内容/);
  await assert.rejects(
    performImageRecognition(part, makeDeps({ generateText: (): Promise<string | null> => Promise.resolve(null) })),
    /视觉识别模型没有返回可用内容/);
});

test('generateText 调用面:system=prompt / user=image part(metadata 透传)', async () => {
  const seen: UIMessage[][] = [];
  const part = imageMsg('file://a.png').parts[0] as UIMessagePartImage;
  await performImageRecognition(part, makeDeps({
    generateText: (_p: ProviderSetting, _m: ProviderModel, msgs: UIMessage[]): Promise<string | null> => {
      seen.push(msgs);
      return Promise.resolve('x');
    },
  }));
  assert.equal(seen[0][0].role, 'system');
  assert.equal((seen[0][0].parts[0] as { text: string }).text, DEFAULT_VISION_RECOGNITION_PROMPT);
  assert.equal(seen[0][1].role, 'user');
  assert.deepEqual(seen[0][1].parts[0], part);
});

test('promptOverride:非 blank 覆盖;blank → resolveVisionRecognitionPrompt(ocrPrompt)', async () => {
  const prompts: string[] = [];
  const part = imageMsg('file://a.png').parts[0] as UIMessagePartImage;
  const deps = makeDeps({
    ocrPrompt: (): string => 'You are an OCR assistant. legacy',
    generateText: (_p: ProviderSetting, _m: ProviderModel, msgs: UIMessage[]): Promise<string | null> => {
      prompts.push((msgs[0].parts[0] as { text: string }).text);
      return Promise.resolve('x');
    },
  });
  await performImageRecognition(part, deps, '  自定义  ', false);
  assert.equal(prompts[0], '自定义', 'override trim 后使用');
  await performImageRecognition(part, deps, '   ', false);
  assert.equal(prompts[1], DEFAULT_VISION_RECOGNITION_PROMPT, '旧 OCR 提示词迁移为默认');
});

test('缓存:cacheKey=url|modelId|hash(prompt);命中不再调用;useCache=false 旁路', async () => {
  const storeKeys: string[] = [];
  let generated = 0;
  const cache: VisionCache = {
    get: (key: string): string | null => {
      storeKeys.push(`get:${key}`);
      return storeKeys.filter((k) => k === `put:${key}`).length > 0 ? 'CACHED' : null;
    },
    put: (key: string, _v: string): void => { storeKeys.push(`put:${key}`); },
    remove: (_key: string): void => {},
    size: (): number => 0,
  };
  const part = imageMsg('file://a.png').parts[0] as UIMessagePartImage;
  const deps = makeDeps({
    cache,
    generateText: (): Promise<string | null> => { generated += 1; return Promise.resolve('x'); },
  });
  const expectedKey = `file://a.png|ocr-1|${javaStringHashCode(DEFAULT_VISION_RECOGNITION_PROMPT)}`;
  const first = await performImageRecognition(part, deps);
  assert.equal(generated, 1);
  assert.equal(storeKeys[0], `get:${expectedKey}`);
  assert.equal(storeKeys[1], `put:${expectedKey}`);
  assert.equal(first.startsWith('<image_context>'), true);
  const second = await performImageRecognition(part, deps);
  assert.equal(second, 'CACHED', '命中缓存直返');
  assert.equal(generated, 1, '不再调用模型');
  await performImageRecognition(part, deps, null, false);
  assert.equal(generated, 2, 'useCache=false 旁路读取与写入');
});
