// vision_fallback 规格测试(D-071a)
// Android 基准:
//   GenerationHandler.kt:822-823(hasImageParts)/:1122-1138(分类器)/:601-665(内层兜底)
//   FileEncoder.kt:37-40(ImageEncodingException)/OcrPrompt.kt(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  ImageEncodingError, VisualRecognitionError, hasImageParts,
  shouldFallbackToVisionRecognition, resolveVisionRecognitionPrompt,
  DEFAULT_VISION_RECOGNITION_PROMPT, javaStringHashCode,
  runWithVisionFallback, VISION_FALLBACK_STATUS,
} from '../main/ets/chat/vision_fallback.ts';
import type { VisionFallbackHook } from '../main/ets/chat/vision_fallback.ts';
import { runChatTurn, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import { runRegenerateAt } from '../main/ets/chat/regenerate.ts';
import type { RegenerateDeps } from '../main/ets/chat/regenerate.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeConversation, currentMessages, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeUserMessage, makeAssistantMessage, toText } from '../main/ets/chat/message.ts';
import type { MessageChunk, UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';
import type { MessageTransformer, TransformerContext } from '../main/ets/chat/transformer_pipeline.ts';

const chunkOf = (text: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{
    index: 0,
    delta: {
      id: 'd', role: 'assistant',
      parts: [{ type: 'text', text, metadata: null }],
      annotations: [], createdAt: '2026-07-28T00:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    },
    message: null, finishReason: 'unknown',
  }],
  usage: null,
});

const imageParts: UIMessagePart[] = [{ type: 'image', url: 'file://img/1.png', metadata: null }];

// forceImageToText 记录探针( rebuild 是否以 force=true 重跑管线)
const makeForceSpy = (flags: Array<boolean | undefined>): MessageTransformer => ({
  transform: (c: TransformerContext, msgs: UIMessage[]): UIMessage[] => {
    flags.push(c.forceImageToText);
    return msgs;
  },
});

// ===== 分类器(GenerationHandler.kt:1122-1138) =====

test('ImageEncodingError:消息格式 FileEncoder.kt:37-40,分类直 true', () => {
  const err = new ImageEncodingError('file://a.png', new Error('boom'));
  assert.equal(err.message, 'Failed to encode image: boom');
  assert.equal(err.imageUrl, 'file://a.png');
  assert.equal(shouldFallbackToVisionRecognition(err), true);
});

test('分类器:9 关键词命中(image/vision/modalit/unsupported url/... 大小写不敏感)', () => {
  assert.equal(shouldFallbackToVisionRecognition(new Error('Invalid MIME type: image/webp')), true);
  assert.equal(shouldFallbackToVisionRecognition(new Error('UNSUPPORTED URL scheme')), true);
  assert.equal(shouldFallbackToVisionRecognition(new Error('failed to Decode payload')), true);
  assert.equal(shouldFallbackToVisionRecognition(new Error('model modality mismatch')), true);
  assert.equal(shouldFallbackToVisionRecognition(new Error('network down')), false);
  assert.equal(shouldFallbackToVisionRecognition(new Error('boom')), false);
});

test('分类器:cause 链拼接判定(外层无关键词,内层命中);环防护不死循环', () => {
  const inner = new Error('invalid file format');
  const outer = new Error('request failed', { cause: inner });
  assert.equal(shouldFallbackToVisionRecognition(outer), true);
  // 环:a.cause=b,b.cause=a — generateSequence 在 Kotlin 会无限,这里登记防护
  const a = new Error('x');
  const b = new Error('y');
  (a as { cause?: unknown }).cause = b;
  (b as { cause?: unknown }).cause = a;
  assert.equal(shouldFallbackToVisionRecognition(a), false);
});

test('VisualRecognitionError:可选 cause 透传', () => {
  const cause = new Error('http 500');
  const err = new VisualRecognitionError('视觉识别模型调用失败：http 500', cause);
  assert.equal(err.cause, cause);
  assert.equal(new VisualRecognitionError('请先配置视觉识别模型').cause, undefined);
});

// ===== OcrPrompt.kt =====

test('resolveVisionRecognitionPrompt:旧 OCR 提示词迁移(大小写不敏感);blank → 默认;自定义保留', () => {
  assert.equal(resolveVisionRecognitionPrompt('You are an OCR assistant. do x'), DEFAULT_VISION_RECOGNITION_PROMPT);
  assert.equal(resolveVisionRecognitionPrompt('DO NOT INTERPRET OR TRANSLATE anything'), DEFAULT_VISION_RECOGNITION_PROMPT);
  assert.equal(resolveVisionRecognitionPrompt('   '), DEFAULT_VISION_RECOGNITION_PROMPT);
  assert.equal(resolveVisionRecognitionPrompt('  自定义提示  '), '自定义提示');
  assert.equal(DEFAULT_VISION_RECOGNITION_PROMPT.startsWith('You are a visual recognition assistant.'), true);
});

test('javaStringHashCode:31 滚哈希(Kotlin String.hashCode)', () => {
  assert.equal(javaStringHashCode(''), 0);
  assert.equal(javaStringHashCode('abc'), 96354); // ((97*31)+98)*31+99
});

test('hasImageParts:image part url 非 blank 才算(:822-823)', () => {
  assert.equal(hasImageParts([makeUserMessage('t')]), false);
  assert.equal(hasImageParts([
    { ...makeUserMessage('t'), parts: [{ type: 'image', url: '  ', metadata: null }] },
  ]), false);
  assert.equal(hasImageParts([{ ...makeUserMessage('t'), parts: imageParts }]), true);
});

// ===== runWithVisionFallback(:601-665 内层兜底单元) =====

test('兜底单元:首轮成功 → 不重建;状态不置兜底文案', async () => {
  const statuses: Array<string | null> = [];
  let rebuilds = 0;
  const hook: VisionFallbackHook = {
    modelSupportsImageInput: true,
    rebuildInternalMessages: (): Promise<UIMessage[]> => { rebuilds += 1; return Promise.resolve([]); },
  };
  await runWithVisionFallback(
    [{ ...makeUserMessage('t'), parts: imageParts }], hook,
    (_m: UIMessage[]): Promise<void> => Promise.resolve(),
    (s: string | null): void => { statuses.push(s); });
  assert.equal(rebuilds, 0);
  assert.deepEqual(statuses, []);
});

test('兜底单元:图像错误 → 状态文案 + force 重建消息重投同一 callProvider', async () => {
  const statuses: Array<string | null> = [];
  const forced: UIMessage[] = [makeUserMessage('OCR 文本')];
  const seen: UIMessage[][] = [];
  const hook: VisionFallbackHook = {
    modelSupportsImageInput: true,
    rebuildInternalMessages: (): Promise<UIMessage[]> => Promise.resolve(forced),
  };
  let calls = 0;
  await runWithVisionFallback(
    [{ ...makeUserMessage('t'), parts: imageParts }], hook,
    (m: UIMessage[]): Promise<void> => {
      calls += 1;
      seen.push(m);
      return calls === 1
        ? Promise.reject(new ImageEncodingError('u', new Error('x')))
        : Promise.resolve();
    },
    (s: string | null): void => { statuses.push(s); });
  assert.equal(calls, 2);
  assert.equal(seen[1], forced, '第二次调用收到重建消息');
  assert.deepEqual(statuses, [VISION_FALLBACK_STATUS]);
  assert.equal(VISION_FALLBACK_STATUS, '正在改用视觉识别模型读取图片...');
});

test('兜底单元:模型不支持图像 / 无图消息 / 错误不命中 → 原错误重抛不重建', async () => {
  const imgMsgs: UIMessage[] = [{ ...makeUserMessage('t'), parts: imageParts }];
  let rebuilds = 0;
  const hook = (supports: boolean): VisionFallbackHook => ({
    modelSupportsImageInput: supports,
    rebuildInternalMessages: (): Promise<UIMessage[]> => { rebuilds += 1; return Promise.resolve([]); },
  });
  const fail = (_m: UIMessage[]): Promise<void> =>
    Promise.reject(new ImageEncodingError('u', new Error('x')));
  // 模型不支持
  await assert.rejects(runWithVisionFallback(imgMsgs, hook(false), fail), /Failed to encode image/);
  // 无图消息
  await assert.rejects(runWithVisionFallback([makeUserMessage('t')], hook(true), fail), /Failed to encode image/);
  // hook 缺失
  await assert.rejects(runWithVisionFallback(imgMsgs, undefined, fail), /Failed to encode image/);
  // 错误不命中分类器
  await assert.rejects(
    runWithVisionFallback(imgMsgs, hook(true), (_m: UIMessage[]): Promise<void> =>
      Promise.reject(new Error('network down'))),
    /network down/);
  assert.equal(rebuilds, 0);
});

test('兜底单元:兜底自身错误向外传播(进外层重试循环分类,:655-664)', async () => {
  const hook: VisionFallbackHook = {
    modelSupportsImageInput: true,
    rebuildInternalMessages: (): Promise<UIMessage[]> =>
      Promise.reject(new VisualRecognitionError('请先配置视觉识别模型')),
  };
  await assert.rejects(
    runWithVisionFallback(
      [{ ...makeUserMessage('t'), parts: imageParts }], hook,
      (_m: UIMessage[]): Promise<void> =>
        Promise.reject(new ImageEncodingError('u', new Error('x')))),
    /请先配置视觉识别模型/);
});

// ===== runChatTurn 集成(D-071a 接线) =====

test('chatTurn 视觉兜底:首轮 ImageEncodingError → force 重建 → 同一 accumulator 续写', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const forceFlags: Array<boolean | undefined> = [];
  const statuses: Array<string | null> = [];
  const seen: UIMessage[][] = [];
  let calls = 0;
  const provider: ChatStreamProvider = {
    streamText(messages: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> {
      calls += 1;
      seen.push(messages);
      if (calls === 1) {
        onChunk(chunkOf('残')); // 首轮部分输出 — 兜底接续同一 accumulator,不丢弃
        return Promise.reject(new ImageEncodingError('file://img/1.png', new Error('boom')));
      }
      onChunk(chunkOf('全'));
      return Promise.resolve();
    },
  };
  const store = createMemoryConversationStore();
  const out = await runChatTurn(conv, imageParts, {
    assistant: makeAssistant({}),
    inputTransformers: [makeForceSpy(forceFlags)],
    outputTransformers: [],
    provider,
    store,
    modelSupportsImageInput: true,
    onRetryStatus: (s: string | null): void => { statuses.push(s); },
  });
  assert.equal(calls, 2, '首轮 + 兜底重跑');
  assert.deepEqual(forceFlags, [undefined, true], '重建以 forceImageToText=true 重跑管线');
  assert.deepEqual(statuses, ['正在改用视觉识别模型读取图片...', null]);
  const msgs = currentMessages(out);
  assert.equal(toText(msgs[msgs.length - 1]), '残全', '同一 accumulator:首轮部分输出保留并续写');
});

test('chatTurn 视觉兜底:模型不支持图像 → 不重建原错误传播(等价 Android 模型无 IMAGE 模态)', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const forceFlags: Array<boolean | undefined> = [];
  const provider: ChatStreamProvider = {
    streamText: (_m: UIMessage[], _c: (c: MessageChunk) => void): Promise<void> =>
      Promise.reject(new ImageEncodingError('u', new Error('x'))),
  };
  await assert.rejects(
    runChatTurn(conv, imageParts, {
      assistant: makeAssistant({}),
      inputTransformers: [makeForceSpy(forceFlags)],
      outputTransformers: [],
      provider,
      store: createMemoryConversationStore(),
      modelSupportsImageInput: false,
    }),
    /Failed to encode image/);
  assert.deepEqual(forceFlags, [undefined], '不触发重建');
});

test('chatTurn 视觉兜底:错误不命中分类器 → 原错误传播(boom 不可重试,D-050 同)', async () => {
  const conv: Conversation = makeConversation('conv1', [], { title: 't' });
  const forceFlags: Array<boolean | undefined> = [];
  const provider: ChatStreamProvider = {
    streamText: (_m: UIMessage[], _c: (c: MessageChunk) => void): Promise<void> =>
      Promise.reject(new Error('boom')),
  };
  await assert.rejects(
    runChatTurn(conv, imageParts, {
      assistant: makeAssistant({}),
      inputTransformers: [makeForceSpy(forceFlags)],
      outputTransformers: [],
      provider,
      store: createMemoryConversationStore(),
      modelSupportsImageInput: true,
    }),
    /boom/);
  assert.deepEqual(forceFlags, [undefined]);
});

// ===== runRegenerateAt 集成(regenerate 内联循环同语义接线) =====

test('regenerate 视觉兜底:assistant 分支再生触发 force 重建', async () => {
  const conv: Conversation = {
    ...makeConversation('conv-1', []),
    messageNodes: [
      toMessageNode({ ...makeUserMessage('看图'), parts: imageParts }),
      toMessageNode(makeAssistantMessage('a1')),
    ],
  };
  const forceFlags: Array<boolean | undefined> = [];
  let calls = 0;
  const provider: ChatStreamProvider = {
    streamText(_messages: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> {
      calls += 1;
      if (calls === 1) {
        return Promise.reject(new ImageEncodingError('file://img/1.png', new Error('x')));
      }
      onChunk(chunkOf('重生'));
      return Promise.resolve();
    },
  };
  const deps: RegenerateDeps = {
    assistant: makeAssistant({}),
    inputTransformers: [makeForceSpy(forceFlags)],
    outputTransformers: [],
    provider,
    store: createMemoryConversationStore(),
    sleep: (): Promise<void> => Promise.resolve(),
    modelSupportsImageInput: true,
  };
  const out = await runRegenerateAt(conv, conv.messageNodes[1].id, deps);
  assert.equal(calls, 2);
  assert.deepEqual(forceFlags, [undefined, true]);
  const node = out.messageNodes[1];
  assert.equal(node.messages.length, 2, '新分支追加进同一节点');
  assert.equal(toText(node.messages[1]), '重生');
});
