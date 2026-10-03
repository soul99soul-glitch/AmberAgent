// Google 请求构建规格测试(D-045)
//
// Android 基准: GoogleProvider.kt buildCompletionRequestBody(:465-620) /
//   buildContents(:729-791) / ModelDsl.kt tokenize+TokenSequenceMatcher
// 逐条锁定:systemInstruction/generationConfig/thinkingConfig 全分支/
//   tools schema 裁剪/contents 映射(functionCall+functionResponse)/safetySettings

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  buildGoogleCompletionRequestBody, buildGoogleContents, toGooglePart,
  isGemini3Series, isGemini25Pro, removeJsonElements, commonRoleToGoogleRole,
} from '../main/ets/chat/google_request.ts';
import type { GoogleEncodedImage } from '../main/ets/chat/google_request.ts';
import { makeTextGenerationParams, makeChatModel } from '../main/ets/chat/provider_model.ts';
import type { TextGenerationParams } from '../main/ets/chat/provider_model.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

const encoder = (_url: string): GoogleEncodedImage => ({ mimeType: 'image/png', base64: 'QUJD' });

const baseParams = (over: Partial<TextGenerationParams> = {}): TextGenerationParams =>
  makeTextGenerationParams({ model: makeChatModel({ modelId: 'gemini-2.5-flash' }), ...over });

const genConfig = (req: JsonObject): JsonObject => req['generationConfig'] as JsonObject;

test('token matcher:GEMINI_3_SERIES 全成员命中;2.5/2.0 不命中;isGemini25Pro', () => {
  assert.ok(isGemini3Series('gemini-3-pro'));
  assert.ok(isGemini3Series('gemini-3-flash'));
  assert.ok(isGemini3Series('gemini-3.1-pro-preview'));
  assert.ok(isGemini3Series('gemini-3.1-pro-preview-customtools'));
  assert.ok(isGemini3Series('gemini-3-pro-image'), 'token 子序列:image 尾部不影响');
  assert.ok(!isGemini3Series('gemini-2.5-pro'));
  assert.ok(!isGemini3Series('gemini-2.0-flash'));
  assert.ok(isGemini25Pro('gemini-2.5-pro'));
  assert.ok(isGemini25Pro('Gemini-2.5-Pro-Exp'), 'IGNORE_CASE');
  assert.ok(!isGemini25Pro('gemini-2.5-flash'));
});

test('骨架:systemInstruction 合并 \n\n;temperature/topP/maxOutputTokens;safetySettings 5 条 OFF', () => {
  const sys: UIMessage = makeUIMessage('system', [
    { type: 'text', text: '块一', metadata: null },
    { type: 'text', text: '块二', metadata: null },
  ]);
  const req = buildGoogleCompletionRequestBody({
    messages: [sys, makeUserMessage('你好')],
    params: baseParams({ temperature: 0.7, topP: 0.9, maxTokens: 2048 }),
  });
  assert.deepEqual(req['systemInstruction'], { parts: [{ text: '块一\n\n块二' }] });
  const gc = genConfig(req);
  assert.equal(gc['temperature'], 0.7);
  assert.equal(gc['topP'], 0.9);
  assert.equal(gc['maxOutputTokens'], 2048);
  const safety = req['safetySettings'] as JsonObject[];
  assert.equal(safety.length, 5);
  assert.ok(safety.every((s: JsonObject): boolean => s['threshold'] === 'OFF'));
  assert.equal(safety[4]['category'], 'HARM_CATEGORY_CIVIC_INTEGRITY');
});

test('图片输出模型:不带 systemInstruction;responseModalities TEXT+IMAGE', () => {
  const sys: UIMessage = makeUIMessage('system', [{ type: 'text', text: 's', metadata: null }]);
  const req = buildGoogleCompletionRequestBody({
    messages: [sys, makeUserMessage('画个猫')],
    params: baseParams({
      model: makeChatModel({ modelId: 'gemini-3-pro-image', outputModalities: ['text', 'image'] }),
    }),
  });
  assert.equal('systemInstruction' in req, false);
  assert.deepEqual(genConfig(req)['responseModalities'], ['TEXT', 'IMAGE']);
});

test('thinkingConfig:AUTO 仅 includeThoughts;OFF 非 3 系非 pro → budget 0 + 不含 thoughts', () => {
  const reasoningFlash = makeChatModel({ modelId: 'gemini-2.5-flash', abilities: ['reasoning'] });
  const auto = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: reasoningFlash, reasoningLevel: 'auto' }),
  });
  assert.deepEqual(genConfig(auto)['thinkingConfig'], { includeThoughts: true });

  const off = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: reasoningFlash, reasoningLevel: 'off' }),
  });
  assert.deepEqual(genConfig(off)['thinkingConfig'],
    { includeThoughts: false, thinkingBudget: 0 });
});

test('thinkingConfig OFF:3 系 → thinkingLevel minimal(OAuth 传输下不强制);2.5 pro → 不覆盖', () => {
  const g3 = makeChatModel({ modelId: 'gemini-3-pro', abilities: ['reasoning'] });
  const off3 = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: g3, reasoningLevel: 'off' }),
  });
  assert.deepEqual(genConfig(off3)['thinkingConfig'],
    { includeThoughts: true, thinkingLevel: 'minimal' });

  const off3oauth = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: g3, reasoningLevel: 'off' }),
    isCodeAssistOAuth: true,
  });
  assert.deepEqual(genConfig(off3oauth)['thinkingConfig'], { includeThoughts: true },
    'cloudcode-pa 拒绝 minimal → 不强制(Android :507-512)');

  const g25pro = makeChatModel({ modelId: 'gemini-2.5-pro', abilities: ['reasoning'] });
  const off25 = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: g25pro, reasoningLevel: 'off' }),
  });
  assert.deepEqual(genConfig(off25)['thinkingConfig'], { includeThoughts: true },
    '2.5 pro 不支持关思考 → 不覆盖(Android :516 isGeminiPro 排除)');
});

test('thinkingConfig 显式档:3 系 → thinkingLevel 映射(low/medium/high);非 3 系 → thinkingBudget', () => {
  const g3 = makeChatModel({ modelId: 'gemini-3-pro', abilities: ['reasoning'] });
  const low = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: g3, reasoningLevel: 'low' }),
  });
  assert.equal((genConfig(low)['thinkingConfig'] as JsonObject)['thinkingLevel'], 'low');
  const max = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: g3, reasoningLevel: 'max' }),
  });
  assert.equal((genConfig(max)['thinkingConfig'] as JsonObject)['thinkingLevel'], 'high',
    'XHIGH/MAX → high(Android :527)');

  const flash = makeChatModel({ modelId: 'gemini-2.5-flash', abilities: ['reasoning'] });
  const high = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({ model: flash, reasoningLevel: 'high' }),
  });
  assert.equal((genConfig(high)['thinkingConfig'] as JsonObject)['thinkingBudget'], 8000);

  // 无 reasoning 能力 → 无 thinkingConfig
  const plain = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({ reasoningLevel: 'high' }),
  });
  assert.equal('thinkingConfig' in genConfig(plain), false);
});

test('tools:functionDeclarations + parameters 六键递归裁剪;无 tool 能力 → 无 tools 块', () => {
  const toolModel = makeChatModel({ modelId: 'gemini-2.5-flash', abilities: ['tool'] });
  const req = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({
      model: toolModel,
      tools: [{
        name: 'search', description: 'd',
        parameters: {
          type: 'object', format: 'skip', enum: ['a'],
          properties: { q: { type: 'string', const: 'x', description: 'keep' } },
          additionalProperties: false,
        },
      }],
    }),
  });
  const tools = req['tools'] as JsonObject[];
  const decl = (tools[0]['functionDeclarations'] as JsonObject[])[0];
  assert.equal(decl['name'], 'search');
  const params = decl['parameters'] as JsonObject;
  assert.equal('format' in params, false);
  assert.equal('enum' in params, false);
  assert.equal('additionalProperties' in params, false);
  const q = (params['properties'] as JsonObject)['q'] as JsonObject;
  assert.equal('const' in q, false, '递归移除嵌套键');
  assert.equal(q['description'], 'keep');

  const noAbility = buildGoogleCompletionRequestBody({
    messages: [makeUserMessage('x')],
    params: baseParams({
      model: makeChatModel({ abilities: [] }),
      tools: [{ name: 't', description: 'd', parameters: {} }],
    }),
  });
  assert.equal('tools' in noAbility, false);
});

test('removeJsonElements:数组递归 + 基本类型不动', () => {
  const out = removeJsonElements(
    [{ a: 1, drop: 2 }, [{ b: 3, drop: 4 }], 'keep'] as never,
    ['drop'],
  );
  assert.deepEqual(out, [{ a: 1 }, [{ b: 3 }], 'keep']);
});

test('contents:user/tool → user;assistant → model;reasoning part 丢弃', () => {
  const conv: UIMessage[] = [
    makeUserMessage('问'),
    makeUIMessage('assistant', [
      { type: 'reasoning', reasoning: '想', createdAt: '2026-07-28T00:00:00Z',
        finishedAt: null, metadata: null },
      { type: 'text', text: '答', metadata: null },
    ]),
  ];
  const contents = buildGoogleContents(conv, encoder);
  assert.equal(contents.length, 2);
  assert.equal(contents[0]['role'], 'user');
  assert.equal(contents[1]['role'], 'model');
  assert.deepEqual(contents[1]['parts'], [{ text: '答' }], 'reasoning 不进 Google(Android else→null)');
  assert.equal(commonRoleToGoogleRole('tool'), 'user');
});

test('contents:assistant 已执行 tool → model functionCall + user functionResponse 配对', () => {
  const tool: UIMessagePartTool = {
    type: 'tool', toolCallId: 'c1', toolName: 'search', input: '{"q":"x"}',
    output: [{ type: 'text', text: '结果一', metadata: null },
      { type: 'text', text: '结果二', metadata: null }],
    approvalState: { type: 'auto' },
    metadata: { thoughtSignature: 'sig-1' },
  };
  const msg: UIMessage = makeUIMessage('assistant', [
    { type: 'text', text: '调用', metadata: null }, tool,
  ]);
  const contents = buildGoogleContents([msg], encoder);
  assert.equal(contents.length, 2);
  assert.deepEqual(contents[0], {
    role: 'model',
    parts: [
      { text: '调用' },
      { functionCall: { name: 'search', args: { q: 'x' } }, thoughtSignature: 'sig-1' },
    ],
  });
  assert.deepEqual(contents[1], {
    role: 'user',
    parts: [{ functionResponse: { name: 'search', response: { result: '结果一\n结果二' } } }],
  });
});

test('toGooglePart:图片 inlineData + thoughtSignature;video 编码失败跳过(getOrNull)', () => {
  const img = toGooglePart(
    { type: 'image', url: 'file:///p.png', metadata: { thoughtSignature: 's' } }, encoder);
  assert.deepEqual(img, {
    inlineData: { mimeType: 'image/png', data: 'QUJD' },
    thoughtSignature: 's',
  });

  const failEncoder = (): GoogleEncodedImage => {
    throw new Error('read fail');
  };
  const video = toGooglePart(
    { type: 'video', url: 'file:///v.bin', mime: 'video/quicktime', metadata: null }, failEncoder);
  assert.equal(video, null, 'video/audio 编码失败跳过(Android getOrNull)');
  // 图片失败必须抛(getOrThrow)
  assert.throws(() => toGooglePart(
    { type: 'image', url: 'file:///x.png', metadata: null }, failEncoder));

  // mime 兜底
  const okVideo = toGooglePart(
    { type: 'video', url: 'file:///v.bin', mime: 'bin', metadata: null }, encoder);
  assert.equal((okVideo as JsonObject)['inlineData'] !== undefined, true);
});

test('customBody 合并;system 与无效消息不进 contents', () => {
  const sys: UIMessage = makeUIMessage('system', [{ type: 'text', text: 's', metadata: null }]);
  const empty = makeUIMessage('assistant', []);
  const req = buildGoogleCompletionRequestBody({
    messages: [sys, empty, makeUserMessage('保留')],
    params: baseParams({ customBody: [{ key: 'cachedContent', value: 'c-1' }] }),
  });
  const contents = req['contents'] as JsonObject[];
  assert.equal(contents.length, 1);
  assert.equal(req['cachedContent'], 'c-1');
});
