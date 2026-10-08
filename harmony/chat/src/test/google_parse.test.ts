// Google 解析规格测试(D-045)
//
// Android 基准: GoogleProvider.kt parseMessagePart(:676-727) /
//   parseMessage(:640-658) / parseUsageMeta(:861-876) /
//   generateText 响应提取(:246-280) / streamText onEvent(:357-414) +
//   withStableToolCallIds(:331-349)

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  parseGoogleMessagePart, parseGoogleMessage, parseGoogleUsageMeta,
  parseGoogleResponseBody, parseGoogleStreamEventData,
  createGoogleStreamToolIdAllocator, googleRoleToCommonRole,
  parseSearchGroundingMetadata,
} from '../main/ets/chat/google_parse.ts';
import type {
  UIMessagePartImage, UIMessagePartReasoning, UIMessagePartTool,
} from '../main/ets/chat/message.ts';
import { toText } from '../main/ets/chat/message.ts';
import { STREAM_TOOL_INDEX_METADATA_KEY } from '../main/ets/chat/tool_merge.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

test('parseGoogleMessagePart:text;thought=true → reasoning part', () => {
  const t = parseGoogleMessagePart({ text: '你好' });
  assert.equal(t.type, 'text');
  const r = parseGoogleMessagePart({ text: '推演', thought: true }) as UIMessagePartReasoning;
  assert.equal(r.type, 'reasoning');
  assert.equal(r.reasoning, '推演');
  assert.equal(r.finishedAt, null);
});

test('parseGoogleMessagePart:functionCall → tool part(args 序列化;thoughtSignature metadata;args 缺失 → 空对象)', () => {
  const t = parseGoogleMessagePart({
    functionCall: { name: 'search', args: { q: 'x' } },
    thoughtSignature: 'sig-1',
  }) as UIMessagePartTool;
  assert.equal(t.toolName, 'search');
  assert.equal(t.input, '{"q":"x"}');
  assert.deepEqual(t.metadata, { thoughtSignature: 'sig-1' });
  assert.ok(t.toolCallId.length > 0, '随机 id(流式由 allocator 覆盖)');

  const noArgs = parseGoogleMessagePart({
    functionCall: { name: 'ping' },
  }) as UIMessagePartTool;
  assert.equal(noArgs.input, '{}');
  assert.deepEqual(noArgs.metadata, { thoughtSignature: null });
});

test('parseGoogleMessagePart:inlineData → image;thought → [Draft Image] reasoning;非 image mime 抛错;未知 part 抛错', () => {
  const img = parseGoogleMessagePart({
    inlineData: { mimeType: 'image/png', data: 'QUJD' },
    thoughtSignature: 'sig',
  }) as UIMessagePartImage;
  assert.equal(img.type, 'image');
  assert.equal(img.url, 'QUJD', 'url = 裸 base64(Android :718)');
  assert.deepEqual(img.metadata, { thoughtSignature: 'sig' });

  const draft = parseGoogleMessagePart({
    inlineData: { mimeType: 'image/png', data: 'QUJD' }, thought: true,
  }) as UIMessagePartReasoning;
  assert.equal(draft.type, 'reasoning');
  assert.equal(draft.reasoning, '[Draft Image]\n');

  assert.throws(
    () => parseGoogleMessagePart({ inlineData: { mimeType: 'application/pdf', data: 'x' } }),
    /Only image mime type is supported/,
  );
  assert.throws(() => parseGoogleMessagePart({ weird: true }), /unknown message part type/);
});

test('parseGoogleMessage:role 映射 + parts + grounding annotations;未知 role 抛错;无 content 抛错', () => {
  const msg = parseGoogleMessage({
    role: 'model',
    content: { parts: [{ text: '答' }] },
    groundingMetadata: {
      groundingChunks: [
        { web: { uri: 'https://a.com', title: 'A' } },
        { web: { uri: 'https://b.com' } }, // 缺 title → 跳过
        { other: {} },
      ],
    },
  });
  assert.equal(msg.role, 'assistant');
  assert.equal(toText(msg), '答');
  assert.deepEqual(msg.annotations, [{ type: 'url_citation', title: 'A', url: 'https://a.com' }]);

  assert.equal(googleRoleToCommonRole('user'), 'user');
  assert.throws(() => googleRoleToCommonRole('tool'), /Unknown role/);
  assert.throws(() => parseGoogleMessage({ role: 'model' }), /No content/);
  assert.deepEqual(parseSearchGroundingMetadata(null), []);
});

test('parseGoogleUsageMeta:completion = candidates + thoughts;null → null', () => {
  const u = parseGoogleUsageMeta({
    promptTokenCount: 100, candidatesTokenCount: 20, thoughtsTokenCount: 30,
    cachedContentTokenCount: 5, totalTokenCount: 155,
  });
  assert.deepEqual(u, {
    promptTokens: 100, completionTokens: 50, cachedTokens: 5, totalTokens: 155,
  });
  assert.equal(parseGoogleUsageMeta(null), null);
});

test('parseGoogleResponseBody:blockReason 抛错;无 candidates 抛错;content 缺失 + finishReason 抛错;happy path', () => {
  assert.throws(
    () => parseGoogleResponseBody({ promptFeedback: { blockReason: 'SAFETY' } }, 'm'),
    (e: Error) => e.message === 'Google blocked the prompt: SAFETY',
  );
  assert.throws(
    () => parseGoogleResponseBody({}, 'm'),
    /no response candidates/,
  );
  assert.throws(
    () => parseGoogleResponseBody(
      { candidates: [{ finishReason: 'RECITATION' }] }, 'm'),
    /no content for candidate with finishReason=RECITATION/,
  );

  const chunk = parseGoogleResponseBody({
    candidates: [{
      role: 'model',
      content: { parts: [{ text: '完整回答' }] },
      finishReason: 'STOP',
    }],
    usageMetadata: { promptTokenCount: 10, candidatesTokenCount: 4, totalTokenCount: 14 },
  }, 'gemini-2.5-flash');
  assert.equal(chunk.model, 'gemini-2.5-flash');
  assert.equal(chunk.choices.length, 1);
  assert.equal(chunk.choices[0].delta, null);
  assert.equal(toText(chunk.choices[0].message!), '完整回答');
  assert.equal(chunk.choices[0].finishReason, 'STOP', '保留与流式一致的真实终态');
  assert.equal(chunk.usage!.promptTokens, 10);
});

test('parseGoogleStreamEventData:{response} 解包;blockReason 抛错;空 candidates → null;delta 组装', () => {
  const alloc = createGoogleStreamToolIdAllocator();
  // cloudcode-pa 包装
  const wrapped = parseGoogleStreamEventData(JSON.stringify({
    response: { candidates: [{ content: { parts: [{ text: '你' }] } }] },
  }), 'm', alloc);
  assert.notEqual(wrapped, null);
  assert.equal(toText(wrapped!.choices[0].delta!), '你');
  assert.equal(wrapped!.choices[0].finishReason, null);

  // 公共 API 顶层载荷 + finishReason
  const plain = parseGoogleStreamEventData(JSON.stringify({
    candidates: [{ content: { parts: [{ text: '好' }] }, finishReason: 'STOP' }],
    usageMetadata: { promptTokenCount: 3, totalTokenCount: 3 },
  }), 'm', alloc);
  assert.equal(plain!.choices[0].finishReason, 'STOP');
  assert.equal(plain!.usage!.promptTokens, 3);

  // blockReason
  assert.throws(
    () => parseGoogleStreamEventData(JSON.stringify({
      promptFeedback: { blockReason: 'SAFETY' },
    }), 'm', alloc),
    (e: Error) => e.message === 'Prompt feedback: SAFETY',
  );

  // candidates 缺失/空 → null(跳过事件)
  assert.equal(parseGoogleStreamEventData('{}', 'm', alloc), null);
  assert.equal(parseGoogleStreamEventData('{"candidates":[]}', 'm', alloc), null);
});

test('流式 tool id 稳定分配:同流前缀一致 + 序号递增 + stream index metadata;非 tool 消息不动', () => {
  const alloc = createGoogleStreamToolIdAllocator();
  const c1 = parseGoogleStreamEventData(JSON.stringify({
    candidates: [{ content: { parts: [{ functionCall: { name: 'a', args: {} } }] } }],
  }), 'm', alloc);
  const c2 = parseGoogleStreamEventData(JSON.stringify({
    candidates: [{ content: { parts: [
      { functionCall: { name: 'b', args: {} } },
      { functionCall: { name: 'c', args: {} } },
    ] } }],
  }), 'm', alloc);

  const t1 = c1!.choices[0].delta!.parts[0] as UIMessagePartTool;
  const t2 = c2!.choices[0].delta!.parts[0] as UIMessagePartTool;
  const t3 = c2!.choices[0].delta!.parts[1] as UIMessagePartTool;

  assert.ok(t1.toolCallId.startsWith('google-fc-'), 'google-fc 前缀(Android :334)');
  assert.ok(t1.toolCallId.endsWith('-0'));
  assert.ok(t2.toolCallId.endsWith('-1'));
  assert.ok(t3.toolCallId.endsWith('-2'), '序号单调递增');
  const prefix1 = t1.toolCallId.slice(0, t1.toolCallId.lastIndexOf('-'));
  const prefix2 = t2.toolCallId.slice(0, t2.toolCallId.lastIndexOf('-'));
  assert.equal(prefix1, prefix2, '同流前缀一致');
  assert.equal((t1.metadata as JsonObject)[STREAM_TOOL_INDEX_METADATA_KEY], 0);
  assert.equal((t3.metadata as JsonObject)[STREAM_TOOL_INDEX_METADATA_KEY], 2);

  // 非 tool 消息原样
  const text = parseGoogleStreamEventData(JSON.stringify({
    candidates: [{ content: { parts: [{ text: 'x' }] } }],
  }), 'm', alloc);
  assert.equal(text!.choices[0].delta!.parts.length, 1);
});
