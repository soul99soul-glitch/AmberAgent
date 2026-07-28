// image_attachment_guard 测试 — ImageAttachmentValidator.kt 行为钉住(D-111)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  checkingImageAttachment, firstImageBlockingIssue, firstImageBlockingIssueForSend,
  imageAttachmentBlocksSend, inspectImageAttachment, MAX_IMAGES_PER_MESSAGE,
  readableImageError,
} from '../main/ets/chat/image_attachment_guard.ts';
import type { ImageGuardSettings } from '../main/ets/chat/image_attachment_guard.ts';
import { ImageEncodingError } from '../main/ets/chat/vision_fallback.ts';
import type { ProviderModel, ProviderSetting } from '../main/ets/chat/provider_settings.ts';
import { makeProviderModel, makeProviderSettingOpenAIVariant } from '../main/ets/chat/provider_settings.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type { VisionModelHealth } from '../main/ets/chat/vision_health.ts';

const IMG = 'data:image/png;base64,AAAA';
const imgPart = (url: string = IMG): UIMessagePart => ({ type: 'image', url, metadata: null });

const okCheck = (): void => {};
const failCheck = (message: string): ((url: string) => void) =>
  (): void => {
    throw new ImageEncodingError(IMG, new Error(message));
  };

const visionModel = (): ProviderModel =>
  makeProviderModel({ id: 'm-vision', modelId: 'gpt-4o', inputModalities: ['text', 'image'] });
const textOnlyModel = (id: string = 'm-chat'): ProviderModel =>
  makeProviderModel({ id, modelId: 'gpt-4o-mini', inputModalities: ['text'] });
const providerOf = (models: ProviderModel[]): ProviderSetting =>
  makeProviderSettingOpenAIVariant({ id: 'p1', name: 'P', baseUrl: 'https://x', apiKey: 'k', models });

// chat 直读场景:chat 模型支持 IMAGE
const readySettings = (): ImageGuardSettings => ({
  chatModel: visionModel(),
  ocrModelId: 'm-vision',
  providers: [providerOf([visionModel()])],
});
// 回退场景:chat 不支持 IMAGE,视觉模型可用
const fallbackSettings = (): ImageGuardSettings => ({
  chatModel: textOnlyModel(),
  ocrModelId: 'm-vision',
  providers: [providerOf([textOnlyModel(), visionModel()])],
});

test('checking() = CHECKING 正在检查图片;blocksSend 仅 blocked', () => {
  const c = checkingImageAttachment();
  assert.deepEqual(c, { kind: 'checking', message: '正在检查图片' });
  assert.equal(imageAttachmentBlocksSend(c), false);
  assert.equal(imageAttachmentBlocksSend({ kind: 'blocked', message: 'x' }), true);
});

test('encode 失败 → BLOCKED + readableImageError 映射', () => {
  const s = inspectImageAttachment(IMG, readySettings(), failCheck('File does not exist: /x.png'));
  assert.equal(s.kind, 'blocked');
  assert.equal(s.message, '图片文件不存在或已被删除');
});

test('readableImageError 关键词表逐字(未命中 → 图片不可读取：<msg>)', () => {
  const wrap = (msg: string): string => readableImageError(new ImageEncodingError(IMG, new Error(msg)));
  assert.equal(wrap('Unsupported URL format: ftp://x'), '图片来源暂不支持');
  assert.equal(wrap('HEIC format requires Android 9'), 'HEIC 格式需要 Android 9 或更高版本');
  assert.equal(wrap('AVIF format requires Android 12'), 'AVIF 格式需要 Android 12 或更高版本');
  assert.equal(wrap('Failed to decode image'), '图片格式无法解码（可能是不支持的格式或文件损坏）');
  assert.equal(wrap('Failed to guess MIME type'), '图片格式暂不支持（支持 JPEG/PNG/WebP/GIF/HEIC/AVIF）');
  assert.equal(wrap('some low-level io error'), '图片不可读取：some low-level io error');
});

test('chat 模型为空 → BLOCKED 请先选择模型', () => {
  const s = inspectImageAttachment(IMG, { chatModel: null, ocrModelId: '', providers: [] }, okCheck);
  assert.deepEqual(s, { kind: 'blocked', message: '请先选择模型' });
});

test('chat 模型支持 IMAGE → READY 图片可由当前模型读取', () => {
  const s = inspectImageAttachment(IMG, readySettings(), okCheck);
  assert.deepEqual(s, { kind: 'ready', message: '图片可由当前模型读取' });
});

test('视觉模型未配置 → BLOCKED 请先配置视觉识别模型', () => {
  const settings: ImageGuardSettings = { chatModel: textOnlyModel(), ocrModelId: 'missing', providers: [] };
  const s = inspectImageAttachment(IMG, settings, okCheck);
  assert.deepEqual(s, { kind: 'blocked', message: '请先配置视觉识别模型' });
});

test('视觉模型不支持图片 → BLOCKED 视觉识别模型不支持图片输入', () => {
  const noImg = textOnlyModel('m-vision');
  const settings: ImageGuardSettings = {
    chatModel: textOnlyModel(), ocrModelId: 'm-vision', providers: [providerOf([noImg])],
  };
  const s = inspectImageAttachment(IMG, settings, okCheck);
  assert.deepEqual(s, { kind: 'blocked', message: '视觉识别模型不支持图片输入' });
});

test('完整回退链 → FALLBACK 将先由视觉识别模型读取图片', () => {
  const s = inspectImageAttachment(IMG, fallbackSettings(), okCheck);
  assert.deepEqual(s, { kind: 'fallback', message: '将先由视觉识别模型读取图片' });
});

test('firstBlockingIssue:>4 张 → BLOCKED 一次最多发送 4 张图片', () => {
  const parts: UIMessagePart[] = [];
  for (let i = 0; i < MAX_IMAGES_PER_MESSAGE + 1; i++) parts.push(imgPart());
  const s = firstImageBlockingIssue(parts, readySettings(), okCheck);
  assert.deepEqual(s, { kind: 'blocked', message: '一次最多发送 4 张图片' });
});

test('firstBlockingIssue:逐张首个 blocked 短路;无图 → null', () => {
  const bad = firstImageBlockingIssue([imgPart()], readySettings(), failCheck('Failed to decode image'));
  assert.equal(bad?.message, '图片格式无法解码（可能是不支持的格式或文件损坏）');
  const noImg = firstImageBlockingIssue(
    [{ type: 'text', text: 'hi', metadata: null }], readySettings(), failCheck('x'));
  assert.equal(noImg, null);
});

test('forSend:无回退需求 → 不探测(null)', async () => {
  let probed = false;
  const s = await firstImageBlockingIssueForSend([imgPart()], readySettings(), okCheck,
    (): Promise<VisionModelHealth> => {
      probed = true;
      return Promise.resolve({ kind: 'failed', label: '不可用：x' });
    });
  assert.equal(s, null);
  assert.equal(probed, false);
});

test('forSend:回退 + 探测可用 → null;不可用 → BLOCKED 健康标签原文', async () => {
  const ok = await firstImageBlockingIssueForSend([imgPart()], fallbackSettings(), okCheck,
    (): Promise<VisionModelHealth> => Promise.resolve({ kind: 'available', label: '可用' }));
  assert.equal(ok, null);
  const bad = await firstImageBlockingIssueForSend([imgPart()], fallbackSettings(), okCheck,
    (): Promise<VisionModelHealth> => Promise.resolve({ kind: 'failed', label: '不可用：HTTP 500' }));
  assert.deepEqual(bad, { kind: 'blocked', message: '不可用：HTTP 500' });
});
