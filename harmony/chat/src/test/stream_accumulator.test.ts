// Streaming accumulator 规格测试
// 基准: ai/ui/MessageStreamAccumulator.kt(主路径) + ai/ui/Message.kt appendChunk/handleMessageChunk
// 规格来源: STREAMING_SEMANTICS_MATRIX.csv 全部 merge_rule 行
//
// 已知 Android 内部不一致(记 PARITY_DEBT 待核实):
//   reasoning 内容追加时,appendChunk 强制 finishedAt=null;
//   accumulator 采用 delta 的 finishedAt(可能非 null)。本实现两者各自忠实。

import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeUserMessage, makeAssistantMessage, makeUIMessage } from '../main/ets/chat/message.ts';
import type {
  UIMessage,
  UIMessagePart,
  UIMessagePartTool,
  UIMessagePartText,
  UIMessagePartReasoning,
  UIMessagePartImage,
  MessageChunk,
} from '../main/ets/chat/message.ts';
import {
  MessageStreamAccumulator,
  appendChunkToMessage,
  handleMessageChunk,
  coalesceStreamParts,
} from '../main/ets/chat/stream_accumulator.ts';
import {
  withStreamToolIndex,
  withStreamArgsReplace,
  REASONING_CONTENT_PRESENT_METADATA_KEY,
} from '../main/ets/chat/tool_merge.ts';

// ===== 构造工具 =====

const textDelta = (text: string): UIMessagePartText => ({ type: 'text', text, metadata: null });
const reasoningDelta = (r: string, finishedAt: string | null = null): UIMessagePartReasoning =>
  ({ type: 'reasoning', reasoning: r, createdAt: '2026-07-28T00:00:00Z', finishedAt, metadata: null });
const toolDelta = (id: string, name: string, input: string, output: UIMessagePart[] = []): UIMessagePartTool =>
  ({ type: 'tool', toolCallId: id, toolName: name, input, output, approvalState: { type: 'auto' }, metadata: null });

const chunk = (parts: UIMessagePart[], role: 'assistant' | 'user' = 'assistant', usage?: { promptTokens: number; completionTokens: number; cachedTokens: number; totalTokens: number }): MessageChunk => ({
  id: 'c1', model: 'm1',
  choices: [{ index: 0, delta: makeUIMessage(role, parts), message: null, finishReason: null }],
  usage: usage ?? null,
});

const finalChunk = (msg: UIMessage): MessageChunk => ({
  id: 'c2', model: 'm1',
  choices: [{ index: 0, delta: null, message: msg, finishReason: 'stop' }],
  usage: null,
});

const seed = (): UIMessage[] => [makeUserMessage('q'), makeAssistantMessage('')];

// ===== delta vs message 替换(merge_rule: delta vs message) =====

test('delta==null 且 message!=null → 整体替换 active', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([textDelta('partial')]));
  const final = makeAssistantMessage('完整回答');
  acc.append(finalChunk(final));
  const snap = acc.snapshot();
  assert.equal(snap.length, 2);
  assert.equal((snap[1].parts[0] as UIMessagePartText).text, '完整回答', 'final 整体替换,不保留 partial');
});

test('replace 路径: replacement 无 modelId 时用 accumulator 的 modelId 填充', () => {
  const acc = new MessageStreamAccumulator(seed(), 'model-42');
  const final = makeAssistantMessage('done');
  acc.append(finalChunk(final));
  assert.equal(acc.snapshot()[1].modelId, 'model-42');
});

test('replace 路径: role 不同 → 旧 active 封存进 prefix', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([textDelta('assistant 说到一半')]));
  acc.append(finalChunk(makeUIMessage('user', [textDelta('用户插话')])));
  const snap = acc.snapshot();
  assert.equal(snap.length, 3, '旧 assistant 被封存 + 新 user active');
  assert.equal(snap[2].role, 'user');
});

// ===== role 变化封存(merge_rule: role 变化) =====

test('delta role 变化 → 旧 active 封存,新 active 带 modelId', () => {
  const acc = new MessageStreamAccumulator(seed(), 'm-7');
  acc.append(chunk([textDelta('a')]));
  acc.append(chunk([textDelta('b')], 'user'));
  const snap = acc.snapshot();
  assert.equal(snap.length, 3);
  assert.equal(snap[1].role, 'assistant');
  assert.equal(snap[2].role, 'user');
  assert.equal(snap[2].modelId, 'm-7');
});

// ===== Text append(merge_rule: Text append) =====

test('text delta 追加到末尾 text part;空 text 跳过;metadata 后者非空优先', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([textDelta('你')]));
  acc.append(chunk([textDelta('')]));
  acc.append(chunk([{ type: 'text', text: '好', metadata: { k: 1 } }]));
  const snap = acc.snapshot();
  const parts = snap[1].parts;
  assert.equal(parts.length, 1);
  assert.equal((parts[0] as UIMessagePartText).text, '你好');
  assert.deepEqual(parts[0].metadata, { k: 1 });
});

// ===== Image append(merge_rule: Image append) =====

test('image delta: 新建自动加 data:image/png;base64 前缀;连续 delta 拼接', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([{ type: 'image', url: 'AAAA', metadata: null }]));
  acc.append(chunk([{ type: 'image', url: 'BBBB', metadata: null }]));
  const img = acc.snapshot()[1].parts[0] as UIMessagePartImage;
  assert.equal(img.url, 'data:image/png;base64,AAAABBBB');
});

// ===== Reasoning append 与关闭(merge_rule: Reasoning 关闭规则) =====

test('reasoning delta 追加到末尾 reasoning;内容追加采用 delta 的 finishedAt(accumulator 语义)', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([reasoningDelta('思')]));
  acc.append(chunk([reasoningDelta('考')]));
  const r = acc.snapshot()[1].parts[0] as UIMessagePartReasoning;
  assert.equal(r.reasoning, '思考');
  assert.equal(r.finishedAt, null);
});

test('空 reasoning 且无 metadata → 跳过', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([reasoningDelta('')]));
  assert.equal(acc.snapshot()[1].parts.length, 0);
});

test('关闭规则: 已有未完成 reasoning,本 chunk 无 reasoning 内容但有非空 text → 全部打 finishedAt', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([reasoningDelta('想完了')]));
  acc.append(chunk([textDelta('正式回答')]));
  const r = acc.snapshot()[1].parts[0] as UIMessagePartReasoning;
  assert.ok(r.finishedAt !== null, 'reasoning 应被关闭');
});

test('关闭规则: tool delta 也触发关闭', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([reasoningDelta('想')]));
  acc.append(chunk([toolDelta('c1', 'search', '{"q"')]));
  const r = acc.snapshot()[1].parts[0] as UIMessagePartReasoning;
  assert.ok(r.finishedAt !== null);
});

test('关闭规则: reasoning delta 自带 finishedAt 触发关闭(finishedAt 由关闭规则打当前时间)', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([reasoningDelta('想')]));
  acc.append(chunk([reasoningDelta('', '2026-07-28T01:00:00Z')]));
  const r = acc.snapshot()[1].parts[0] as UIMessagePartReasoning;
  // Android 语义:空 reasoning+无 metadata 的 delta 被 appendReasoning 跳过,
  // 关闭规则统一打 Clock.System.now(),不采用 delta 自带的 finishedAt
  assert.ok(r.finishedAt !== null);
});

test('不关闭: 本 chunk 有 reasoning 内容 → 不触发关闭规则', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([reasoningDelta('想'), textDelta('穿插')]));
  const parts = acc.snapshot()[1].parts;
  // 同一 chunk 内既有 reasoning 内容又有 text:deltaHasReasoningContent=true → 不关闭
  const r = parts.find((p): p is UIMessagePartReasoning => p.type === 'reasoning');
  assert.ok(r !== undefined);
  assert.equal(r.finishedAt, null);
});

// ===== Tool 合并目标(merge_rule: Tool 合并目标) =====

test('tool: 非空 id 按 toolCallId 匹配合并', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([toolDelta('c1', 'sea', '{"q')]));
  acc.append(chunk([toolDelta('c1', 'rch', '":1}')]));
  const t = acc.snapshot()[1].parts[0] as UIMessagePartTool;
  assert.equal(t.toolName, 'search');
  assert.equal(t.input, '{"q":1}');
});

test('tool: blank id 按 streamToolIndex 匹配;后到的非空 id 被采纳', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([withStreamToolIndex(toolDelta('', 'get', '{"a'), 0)]));
  acc.append(chunk([withStreamToolIndex(toolDelta('call_9', '_weather', '":1}'), 0)]));
  const t = acc.snapshot()[1].parts[0] as UIMessagePartTool;
  assert.equal(t.toolCallId, 'call_9', '后到的非空 id 必须采纳');
  assert.equal(t.toolName, 'get_weather');
});

test('tool: 无 id 无 index → 回退最后一个未执行 tool', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([toolDelta('c1', 'fn', '{"x')]));
  acc.append(chunk([toolDelta('', '', ':2}')]));
  const t = acc.snapshot()[1].parts[0] as UIMessagePartTool;
  assert.equal(t.input, '{"x:2}');
});

test('tool: blank id + index 找不到 → 新建 part 不串线', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([withStreamToolIndex(toolDelta('', 'a', '{'), 0)]));
  acc.append(chunk([withStreamToolIndex(toolDelta('', 'b', '{'), 1)]));
  const tools = acc.snapshot()[1].parts.filter((p): p is UIMessagePartTool => p.type === 'tool');
  assert.equal(tools.length, 2, 'index 不匹配必须新建,不串到别的 tool');
});

test('tool: 已执行的 tool 不再是合并目标(第二轮 index 从 0 重计)', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([withStreamToolIndex(toolDelta('c1', 'fn', '{}', [textDelta('done')]), 0)]));
  acc.append(chunk([withStreamToolIndex(toolDelta('', 'fn2', '{'), 0)]));
  const tools = acc.snapshot()[1].parts.filter((p): p is UIMessagePartTool => p.type === 'tool');
  assert.equal(tools.length, 2, '第二轮 index=0 不能串进已执行的 tool');
});

// ===== Tool merge append/replace(merge_rule: Tool merge append/replace) =====

test('tool merge 默认 append:toolName/input 拼接', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([toolDelta('c1', 'wea', '{"ci')]));
  acc.append(chunk([toolDelta('c1', 'ther', 'ty":"BJ"}')]));
  const t = acc.snapshot()[1].parts[0] as UIMessagePartTool;
  assert.equal(t.toolName, 'weather');
  assert.equal(t.input, '{"city":"BJ"}');
});

test('tool merge replace 语义:带 stream_tool_args_replace 整体替换 input', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([toolDelta('c1', 'fn', '{"partial":')]));
  acc.append(chunk([withStreamArgsReplace(toolDelta('c1', 'fn', '{"full":true}'))]));
  const t = acc.snapshot()[1].parts[0] as UIMessagePartTool;
  assert.equal(t.input, '{"full":true}', 'replace 整体替换,不重复拼接');
});

test('tool merge replace: 空 input 不抹掉已累积参数(异常 provider 兜底)', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([toolDelta('c1', 'fn', '{"a":1}')]));
  acc.append(chunk([withStreamArgsReplace(toolDelta('c1', 'fn', ''))]));
  const t = acc.snapshot()[1].parts[0] as UIMessagePartTool;
  assert.equal(t.input, '{"a":1}');
});

test('replace 控制标记不持久化:新建 part 与 merge 结果都不携带', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([withStreamArgsReplace(toolDelta('c1', 'fn', '{}'))]));
  const t = acc.snapshot()[1].parts[0] as UIMessagePartTool;
  assert.equal(t.metadata, null, 'replace 标记随合并剥离');
});

// ===== annotations(merge_rule: annotations) =====

test('annotations: append + distinct,绝不整体替换', () => {
  const acc = new MessageStreamAccumulator(seed());
  const cite = { type: 'url_citation' as const, title: 'T', url: 'https://x' };
  acc.append(chunk([{ ...textDelta('a') }]));
  // 手动构造带 annotation 的 chunk
  acc.append({
    id: 'c', model: 'm',
    choices: [{
      index: 0,
      delta: { ...makeUIMessage('assistant', [textDelta('b')]), annotations: [cite] },
      message: null, finishReason: null,
    }],
    usage: null,
  });
  acc.append({
    id: 'c', model: 'm',
    choices: [{
      index: 0,
      delta: { ...makeUIMessage('assistant', [textDelta('c')]), annotations: [cite] }, // 重发全量
      message: null, finishReason: null,
    }],
    usage: null,
  });
  const anns = acc.snapshot()[1].annotations;
  assert.equal(anns.length, 1, '重复 citation 去重,不翻倍');
});

// ===== usage(merge_rule: usage) =====

test('usage: delta 路径与 replace 路径都合并', () => {
  const u1 = { promptTokens: 10, completionTokens: 5, cachedTokens: 0, totalTokens: 15 };
  const u2 = { promptTokens: 10, completionTokens: 7, cachedTokens: 2, totalTokens: 17 };
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([textDelta('a')], 'assistant', u1));
  acc.append(chunk([textDelta('b')], 'assistant', u2));
  const usage = acc.snapshot()[1].usage;
  assert.ok(usage !== null);
  assert.equal(usage.completionTokens, 7);
  assert.equal(usage.cachedTokens, 2);
  assert.equal(usage.totalTokens, 17);
});

// ===== coalesceStreamParts(merge_rule: snapshot 规整) =====

test('coalesce: 相邻 text 合并;空 text 丢弃', () => {
  const parts: UIMessagePart[] = [
    textDelta('a'), textDelta(''), textDelta('b'), reasoningDelta('r'),
  ];
  const out = coalesceStreamParts(parts);
  assert.equal(out.length, 2);
  assert.equal((out[0] as UIMessagePartText).text, 'ab');
});

test('coalesce: 空 reasoning 丢弃;带 explicit 标记的空 reasoning 保留一个', () => {
  const explicit: UIMessagePartReasoning = {
    ...reasoningDelta(''), metadata: { [REASONING_CONTENT_PRESENT_METADATA_KEY]: true },
  };
  const explicit2: UIMessagePartReasoning = {
    ...reasoningDelta(''), metadata: { [REASONING_CONTENT_PRESENT_METADATA_KEY]: true },
  };
  const out = coalesceStreamParts([reasoningDelta(''), explicit, explicit2, textDelta('t')]);
  const reasonings = out.filter((p): p is UIMessagePartReasoning => p.type === 'reasoning');
  assert.equal(reasonings.length, 1, 'explicit 空 reasoning 只保留第一个');
});

test('coalesce: 非空 reasoning 丢弃 pending explicit 标记', () => {
  const explicit: UIMessagePartReasoning = {
    ...reasoningDelta(''), metadata: { [REASONING_CONTENT_PRESENT_METADATA_KEY]: true },
  };
  const out = coalesceStreamParts([explicit, reasoningDelta('真内容')]);
  const reasonings = out.filter((p): p is UIMessagePartReasoning => p.type === 'reasoning');
  assert.equal(reasonings.length, 1);
  assert.equal(reasonings[0].reasoning, '真内容');
});

test('appendChunkToMessage: reasoning 内容追加强制 finishedAt=null(appendChunk 语义)', () => {
  const m: UIMessage = makeUIMessage('assistant', [reasoningDelta('已', null)]);
  const withFinished = appendChunkToMessage(m, chunk([reasoningDelta('完', '2026-07-28T01:00:00Z')]));
  const r = withFinished.parts[0] as UIMessagePartReasoning;
  // appendChunk 语义:内容追加总是重置 finishedAt=null(与 accumulator 采用 delta 值不同)
  assert.equal(r.finishedAt, null);
});

test('handleMessageChunk: role 变化开新消息;同 role 合并;空列表抛错', () => {
  const msgs = seed();
  const r1 = handleMessageChunk(msgs, chunk([textDelta('a')]));
  assert.equal(r1.length, 2);
  const r2 = handleMessageChunk(r1, chunk([textDelta('b')], 'user'));
  assert.equal(r2.length, 3);
  assert.equal(r2[2].role, 'user');
  assert.throws(() => handleMessageChunk([], chunk([textDelta('x')])));
});

// ===== usage-only 尾块(choices:[] + usage;stream_options.include_usage 形态) =====

test('usage-only chunk: accumulator/appendChunkToMessage/handleMessageChunk 均合并 usage', () => {
  const u1 = { promptTokens: 10, completionTokens: 5, cachedTokens: 0, totalTokens: 15 };
  const uOnly = { promptTokens: 12, completionTokens: 9, cachedTokens: 3, totalTokens: 21 };
  const usageOnly: MessageChunk = {
    id: 'x', model: 'm',
    choices: [],
    usage: uOnly as UIMessage['usage'],
  } as MessageChunk;

  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([textDelta('a')], 'assistant', u1));
  acc.append(usageOnly);
  assert.equal(acc.snapshot()[1].usage?.completionTokens, 9);
  assert.equal(acc.snapshot()[1].usage?.totalTokens, 21);

  let msg = appendChunkToMessage(makeAssistantMessage('a'), chunk([textDelta('t')], 'assistant', u1));
  assert.equal(msg.usage?.completionTokens, 5, '带 choice 的 delta chunk 的 usage 也要合并(不可变 API)');
  msg = appendChunkToMessage(msg, usageOnly);
  assert.equal(msg.usage?.completionTokens, 9);

  let msgs = handleMessageChunk(seed(), chunk([textDelta('t')], 'assistant', u1));
  msgs = handleMessageChunk(msgs, usageOnly);
  assert.equal(msgs[msgs.length - 1].usage?.completionTokens, 9);
});

// ===== 图片 data URI 前缀守卫 =====

test('image: 完整 data URI(JPEG) 原样保留,裸 base64 才补 PNG 前缀', () => {
  const jpegUri = 'data:image/jpeg;base64,/9j/4AAQ';
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([{ type: 'image', url: jpegUri, metadata: null }]));
  const viaAcc = (acc.snapshot()[1].parts[0] as UIMessagePartImage).url;
  assert.equal(viaAcc, jpegUri);

  const acc2 = new MessageStreamAccumulator(seed());
  acc2.append(chunk([{ type: 'image', url: 'aGVsbG8=', metadata: null }]));
  const viaBare = (acc2.snapshot()[1].parts[0] as UIMessagePartImage).url;
  assert.equal(viaBare, 'data:image/png;base64,aGVsbG8=');

  const msg = appendChunkToMessage(
    makeUIMessage('assistant', []),
    chunk([{ type: 'image', url: jpegUri, metadata: null }]),
  );
  assert.equal((msg.parts[0] as UIMessagePartImage).url, jpegUri);
});

test('finish-only chunk(有 choice 但 delta/message 双 null)携带 usage 也合并', () => {
  const acc = new MessageStreamAccumulator(seed());
  acc.append(chunk([textDelta('a')], 'assistant'));
  const finishOnly: MessageChunk = {
    id: 'c3', model: 'm1',
    choices: [{ index: 0, delta: null, message: null, finishReason: 'stop' }],
    usage: { promptTokens: 10, completionTokens: 4, cachedTokens: 0, totalTokens: 14 } as UIMessage['usage'],
  } as MessageChunk;
  acc.append(finishOnly);
  assert.equal(acc.snapshot()[1].usage?.completionTokens, 4);
});
