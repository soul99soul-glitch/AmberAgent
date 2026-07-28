// 纯逻辑 transformer 规格测试
//
// Android 基准:
//   core/ai/transformers/api/.../Transformer.kt(接口/pipeline/不变量)
//   app/core/ai/transformers/TemplateTransformer.kt / ThinkTagTransformer.kt /
//   RegexOutputTransformer.kt / AssistantRegexProcessor.kt(replaceRegexes)
//
// 裁剪(纯逻辑子集,记 D-012):
//   - TransformerContext 只含 assistant + clock(Android 的 Context/settings/Model 不进纯逻辑层)
//   - TemplateTransformer 模板直接取 assistant.messageTemplate(Android 经 settingsStore 按 id 反查)
//   - replaceRegexes 无 Rust native 路由(Android 性能优化),统一 JS RegExp 解释 → PD-005

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  makeAssistant, makeAssistantRegex,
} from '../main/ets/chat/assistant.ts';
import type { Assistant } from '../main/ets/chat/assistant.ts';
import {
  applyTemplate, createTemplateTransformer,
  thinkTagTransformer, regexOutputTransformer,
} from '../main/ets/chat/transformers.ts';
import {
  applyInputTransformers, applyVisualTransformers,
  applyVisualTransformersStreamingTail, applyOnGenerationFinish,
} from '../main/ets/chat/transformer_pipeline.ts';
import type {
  MessageTransformer, OutputMessageTransformer, TransformerContext,
} from '../main/ets/chat/transformer_pipeline.ts';
import {
  makeUserMessage, makeAssistantMessage, makeSystemMessage, makeUIMessage,
} from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';

const ctx = (assistant: Assistant): TransformerContext => ({ assistant });

// ===== applyTemplate(TemplateTransformer.kt:42-49) =====

test('applyTemplate: {{var}} 与 {{ var }} 两种写法都替换', () => {
  const out = applyTemplate('{{message}}|{{ message }}|{{role}}|{{ role }}', {
    message: 'hi', role: 'user', time: 'T', date: 'D',
  });
  assert.equal(out, 'hi|hi|user|user');
});

test('applyTemplate: 未知变量保留原文', () => {
  const out = applyTemplate('{{unknown}} {{message}}', {
    message: 'm', role: 'r', time: 't', date: 'd',
  });
  assert.equal(out, '{{unknown}} m');
});

// ===== TemplateTransformer =====

test('template: 默认模板 "{{ message }}" → 套用后恒等(D-016:无 null-skip,忠实 Android)', async () => {
  const t = createTemplateTransformer();
  const msgs = [makeUserMessage('hi')];
  const out = await t.transform!(ctx(makeAssistant({})), msgs);
  assert.notEqual(out, msgs, '总是套用(新数组)');
  assert.equal(out[0].parts[0].type === 'text' ? out[0].parts[0].text : '', 'hi', '默认模板恒等');
});

test('template: 所有消息的 text part 都套模板,非 text part 不动', async () => {
  const t = createTemplateTransformer();
  const assistant = makeAssistant({ messageTemplate: '[{{role}}] {{message}}' });
  const imgMsg = makeUIMessage('user', [{ type: 'image', url: 'data:x', metadata: null }]);
  const out = await t.transform!(ctx(assistant), [makeUserMessage('hello'), imgMsg]);
  const first = out[0].parts[0];
  assert.equal(first.type === 'text' ? first.text : '', '[user] hello');
  assert.equal(out[1], imgMsg, '无 text part 的消息保持引用');
});

// ===== ThinkTagTransformer =====

test('thinkTag visualTransform: 闭合 think 块 → reasoning(finished)+ 剥离正文', () => {
  const msg = makeAssistantMessage('前置<think>想一下</think>正文');
  const out = thinkTagTransformer.visualTransform!(ctx(makeAssistant({})), [msg]);
  const parts = out[0].parts;
  assert.equal(parts[0].type, 'reasoning');
  if (parts[0].type === 'reasoning') {
    assert.equal(parts[0].reasoning, '想一下');
    assert.ok(parts[0].finishedAt !== null, '闭合标签 → finishedAt 有值');
    assert.equal(parts[0].createdAt, msg.createdAt, 'reasoning createdAt 沿用消息时间');
  }
  assert.equal(parts[1].type, 'text');
  if (parts[1].type === 'text') assert.equal(parts[1].text, '前置正文');
});

test('thinkTag visualTransform: 未闭合 think 块 → reasoning finishedAt=null', () => {
  const msg = makeAssistantMessage('答<think>还在想');
  const out = thinkTagTransformer.visualTransform!(ctx(makeAssistant({})), [msg]);
  const r = out[0].parts[0];
  assert.equal(r.type, 'reasoning');
  if (r.type === 'reasoning') {
    assert.equal(r.reasoning, '还在想');
    assert.equal(r.finishedAt, null);
  }
});

test('thinkTag onGenerationFinish: 未闭合 reasoning 强制关闭', async () => {
  const msg = makeAssistantMessage('<think>流中断了');
  const out = await thinkTagTransformer.onGenerationFinish!(ctx(makeAssistant({})), [msg]);
  const r = out[0].parts[0];
  assert.equal(r.type, 'reasoning');
  if (r.type === 'reasoning') assert.ok(r.finishedAt !== null);
});

test('thinkTag: 非 assistant / 无 think 标签 → 引用相等不触碰', () => {
  const user = makeUserMessage('<think>用户写的不算</think>');
  const plain = makeAssistantMessage('没有标签');
  const out = thinkTagTransformer.visualTransform!(ctx(makeAssistant({})), [user, plain]);
  assert.equal(out[0], user);
  assert.equal(out[1], plain);
});

test('thinkTag visualTransformTail: 单消息版本', () => {
  const msg = makeAssistantMessage('<think>t</think>x');
  const out = thinkTagTransformer.visualTransformTail(ctx(makeAssistant({})), msg);
  assert.equal(out.parts[0].type, 'reasoning');
});

// ===== RegexOutputTransformer(replaceRegexes) =====

test('regex: enabled+scope+visualOnly 过滤,顺序 fold 替换 text 与 reasoning', () => {
  const assistant = makeAssistant({
    regexes: [
      makeAssistantRegex({ findRegex: 'a+', replaceString: 'b', affectingScope: ['assistant'] }), // 适用
      makeAssistantRegex({ findRegex: 'x', replaceString: 'y', enabled: false, affectingScope: ['assistant'] }), // 禁用
      makeAssistantRegex({ findRegex: 'b', replaceString: 'Z', visualOnly: true, affectingScope: ['assistant'] }), // visual≠false 排除
      makeAssistantRegex({ findRegex: 'b', replaceString: 'W', affectingScope: ['user'] }), // scope 不含
      makeAssistantRegex({ findRegex: 'b', replaceString: 'V' }), // D-016:scope 默认 [](Android emptySet)→ 不适用
    ],
  });
  const msg = makeUIMessage('assistant', [
    { type: 'reasoning', reasoning: 'raax', createdAt: '2026-07-28T00:00:00Z', finishedAt: null, metadata: null },
    { type: 'text', text: 'taax', metadata: null },
  ]);
  const out = regexOutputTransformer.visualTransform!(ctx(assistant), [msg]);
  const r = out[0].parts[0];
  const t = out[0].parts[1];
  assert.equal(r.type === 'reasoning' ? r.reasoning : '', 'rbx');
  assert.equal(t.type === 'text' ? t.text : '', 'tbx');
});

test('regex: 非法正则被跳过不影响其他规则;user 消息不动', () => {
  const assistant = makeAssistant({
    regexes: [
      makeAssistantRegex({ findRegex: '([', replaceString: 'x', affectingScope: ['assistant'] }),
      makeAssistantRegex({ findRegex: 'b', replaceString: 'c', affectingScope: ['assistant'] }),
    ],
  });
  const user = makeUserMessage('b');
  const asst = makeAssistantMessage('b');
  const out = regexOutputTransformer.visualTransform!(ctx(assistant), [user, asst]);
  assert.equal(out[0], user);
  const p = out[1].parts[0];
  assert.equal(p.type === 'text' ? p.text : '', 'c');
});

test('regex: 无规则 → 引用相等', () => {
  const msgs = [makeAssistantMessage('a')];
  const out = regexOutputTransformer.visualTransform!(ctx(makeAssistant({ regexes: [] })), msgs);
  assert.equal(out, msgs);
});

// ===== pipeline 不变量(Transformer.kt:174-211) =====

test('pipeline: 移除 system 消息的 transformer → 抛错', async () => {
  const evil: MessageTransformer = {
    transform: (_c: TransformerContext, msgs: UIMessage[]): UIMessage[] =>
      msgs.filter((m: UIMessage): boolean => m.role !== 'system'),
  };
  // D-071a:pipeline 异步化 → 同步 throw 变 rejection
  await assert.rejects(
    applyInputTransformers([makeSystemMessage('s'), makeUserMessage('u')], [evil], ctx(makeAssistant({}))),
    /removed the system message/,
  );
});

test('pipeline: 改动 tool 签名 → 抛错;合法转换通过', async () => {
  const toolMsg = makeUIMessage('assistant', [{
    type: 'tool', toolCallId: 'c1', toolName: 'search', input: '{}',
    output: [], approvalState: { type: 'auto' }, metadata: null,
  }]);
  const evil: MessageTransformer = {
    transform: (_c: TransformerContext, msgs: UIMessage[]): UIMessage[] =>
      msgs.map((m: UIMessage): UIMessage => ({
        ...m,
        parts: m.parts.map((p: UIMessagePart): UIMessagePart =>
          p.type === 'tool' ? { ...p, input: '{"evil":1}' } : p),
      })),
  };
  // D-071a:pipeline 异步化 → 同步 throw 变 rejection
  await assert.rejects(
    applyInputTransformers([toolMsg], [evil], ctx(makeAssistant({}))),
    /modified tool call\/result ordering/,
  );
  const benign: MessageTransformer = {
    transform: (_c: TransformerContext, msgs: UIMessage[]): UIMessage[] =>
      msgs.map((m: UIMessage): UIMessage => ({ ...m })),
  };
  const out = await applyInputTransformers([toolMsg], [benign], ctx(makeAssistant({})));
  assert.equal(out.length, 1);
});

// ===== streaming tail(Transformer.kt:118-149) =====

test('visualTransformsStreamingTail: 只转换最后一条 assistant;无 assistant → 原样', () => {
  const msgs = [
    makeAssistantMessage('<think>old</think>旧'),
    makeUserMessage('问'),
    makeAssistantMessage('<think>new</think>新'),
  ];
  const out = applyVisualTransformersStreamingTail(msgs, [thinkTagTransformer], ctx(makeAssistant({})));
  assert.equal(out[0], msgs[0], '历史 assistant 不动');
  assert.equal(out[1], msgs[1]);
  assert.equal(out[2].parts[0].type, 'reasoning');

  const noAsst = [makeUserMessage('a'), makeUserMessage('b')];
  assert.equal(applyVisualTransformersStreamingTail(noAsst, [thinkTagTransformer], ctx(makeAssistant({}))), noAsst);
});

test('applyVisualTransformers / applyOnGenerationFinish 只调用对应钩子', () => {
  const calls: string[] = [];
  const t: OutputMessageTransformer = {
    visualTransform: (_c: TransformerContext, msgs: UIMessage[]): UIMessage[] => {
      calls.push('visual');
      return msgs;
    },
    onGenerationFinish: (_c: TransformerContext, msgs: UIMessage[]): UIMessage[] => {
      calls.push('finish');
      return msgs;
    },
  };
  applyVisualTransformers([makeUserMessage('a')], [t], ctx(makeAssistant({})));
  assert.deepEqual(calls, ['visual']);
  applyOnGenerationFinish([makeUserMessage('a')], [t], ctx(makeAssistant({})));
  assert.deepEqual(calls, ['visual', 'finish']);
});
