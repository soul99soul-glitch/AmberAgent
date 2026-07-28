// prompt_injection_transformer — 语义钉死(D-075)
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  applyInjections, cacheDisabledMetadata, collectInjections,
  createMergedInjectionMessages, createPromptInjectionTransformer,
  extractContextForMatching, findSafeInsertIndex, isTriggered,
  makeLorebook, makeModeInjection, makeRegexInjection, transformMessages,
  SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED,
} from '../main/ets/chat/prompt_injection_transformer.ts';
import type { InjectionPosition, PromptInjection, RegexInjection } from '../main/ets/chat/prompt_injection_transformer.ts';
import { makeAssistantMessage, makeUIMessage, makeUserMessage, toText } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { TransformerContext } from '../main/ets/chat/transformer_pipeline.ts';

const toolPart = (): UIMessagePart => ({
  type: 'tool', toolCallId: 't1', toolName: 'x', input: '{}', output: [],
  approvalState: { type: 'auto' }, metadata: null,
});

// ===== 触发判定(Assistant.kt:234-255) =====

test('isTriggered: enabled=false → false;constantActive → true;keywords 空 → false', () => {
  assert.equal(isTriggered(makeRegexInjection({ enabled: false, constantActive: true }), 'x'), false);
  assert.equal(isTriggered(makeRegexInjection({ constantActive: true }), ''), true);
  assert.equal(isTriggered(makeRegexInjection({ keywords: [] }), 'hello'), false);
});

test('isTriggered: 普通关键字大小写敏感/不敏感', () => {
  const kw: Partial<RegexInjection> = { keywords: ['Sword'] };
  assert.equal(isTriggered(makeRegexInjection({ ...kw, caseSensitive: false }), 'a SWORD!'), true);
  assert.equal(isTriggered(makeRegexInjection({ ...kw, caseSensitive: true }), 'a SWORD!'), false);
  assert.equal(isTriggered(makeRegexInjection({ ...kw, caseSensitive: true }), 'a Sword!'), true);
});

test('isTriggered: regex 模式 + 构造异常 → false', () => {
  assert.equal(
    isTriggered(makeRegexInjection({ keywords: ['sw.rd'], useRegex: true }), 'a sword'), true);
  assert.equal(
    isTriggered(makeRegexInjection({ keywords: ['SWORD'], useRegex: true, caseSensitive: true }),
      'a sword'), false);
  assert.equal(
    isTriggered(makeRegexInjection({ keywords: ['('], useRegex: true }), 'anything'), false);
});

// ===== 上下文提取(:264-271) =====

test('extractContextForMatching: takeLast 语义,scanDepth=0 → 空', () => {
  const msgs: UIMessage[] = [makeUserMessage('a'), makeAssistantMessage('b'), makeUserMessage('c')];
  assert.equal(extractContextForMatching(msgs, 2), 'b\nc');
  assert.equal(extractContextForMatching(msgs, 99), 'a\nb\nc');
  assert.equal(extractContextForMatching(msgs, 0), '');
  assert.throws((): string => extractContextForMatching(msgs, -1));
});

// ===== 收集(:69-101) =====

test('collectInjections: modeInjection 过滤 enabled+关联;lorebook 按 scanDepth 触发', () => {
  const mi1: PromptInjection = makeModeInjection({ id: 'm1', name: 'on' });
  const mi2: PromptInjection = makeModeInjection({ id: 'm2', enabled: false });
  const mi3: PromptInjection = makeModeInjection({ id: 'm3' }); // 未关联
  const assistant = makeAssistant({ modeInjectionIds: ['m1', 'm2'], lorebookIds: ['lb1'] });
  const lb = makeLorebook({
    id: 'lb1',
    entries: [
      makeRegexInjection({ id: 'e1', keywords: ['dragon'], scanDepth: 2 }),
      makeRegexInjection({ id: 'e2', keywords: ['ancient'], scanDepth: 2 }), // 超出窗口
    ],
  });
  const lbDisabled = makeLorebook({ id: 'lb2', enabled: false, entries: [
    makeRegexInjection({ id: 'e3', constantActive: true }),
  ] });
  const assistant2 = makeAssistant({ ...assistant, lorebookIds: ['lb1', 'lb2'] });
  const msgs: UIMessage[] = [
    makeUIMessage('system', [{ type: 'text', text: 'dragon in system', metadata: null }]),
    makeUserMessage('ancient tale'),
    makeUserMessage('a dragon appears'),
    makeAssistantMessage('ok'),
  ];
  const out: PromptInjection[] = collectInjections(
    msgs, assistant2, [mi1, mi2, mi3], [lb, lbDisabled]);
  // system 消息不参与匹配(:88)
  assert.deepEqual(out.map((i: PromptInjection): string => i.id), ['m1', 'e1']);
});

// ===== findSafeInsertIndex(:243-264) =====

test('findSafeInsertIndex: USER → ASSISTANT(含Tool) 之间回退', () => {
  const msgs: UIMessage[] = [
    makeUserMessage('q'),
    makeUIMessage('assistant', [{ type: 'text', text: 'a', metadata: null }, toolPart()]),
    makeUserMessage('next'),
  ];
  assert.equal(findSafeInsertIndex(msgs, 1), 0); // 回退到 user 前
  assert.equal(findSafeInsertIndex(msgs, 2), 2); // assistant 与 user 之间安全
  assert.equal(findSafeInsertIndex(msgs, 99), 3); // clamp 到末尾
  assert.equal(findSafeInsertIndex(msgs, -5), 0); // clamp 到 0
});

// ===== createMergedInjectionMessages(:225-235) =====

test('createMergedInjectionMessages: 按 role 分组合并,assistant→assistant else→user', () => {
  const merged: UIMessage[] = createMergedInjectionMessages([
    makeModeInjection({ id: '1', content: 'c1', role: 'user' }),
    makeModeInjection({ id: '2', content: 'c2', role: 'assistant' }),
    makeModeInjection({ id: '3', content: 'c3', role: 'user' }),
  ]);
  assert.equal(merged.length, 2);
  assert.equal(merged[0].role, 'user');
  assert.equal(toText(merged[0]), 'c1\nc3');
  assert.equal(merged[1].role, 'assistant');
  assert.equal(toText(merged[1]), 'c2');
});

// ===== applyInjections(:106-219) =====

const inj = (content: string, position: InjectionPosition, extra: Partial<PromptInjection> = {}): PromptInjection =>
  makeModeInjection({ content, position, ...extra });

test('applyInjections: 有 system → before/after 拼 parts,before 带 cache-disabled metadata', () => {
  const sys: UIMessage = makeUIMessage('system', [{ type: 'text', text: 'BASE', metadata: null }]);
  const byPosition = new Map<InjectionPosition, PromptInjection[]>([
    ['before_system_prompt', [inj('B1', 'before_system_prompt'), inj('B2', 'before_system_prompt')]],
    ['after_system_prompt', [inj('A1', 'after_system_prompt')]],
  ]);
  const out: UIMessage[] = applyInjections([sys, makeUserMessage('hi')], byPosition);
  const parts: UIMessagePart[] = out[0].parts;
  assert.equal(parts.length, 3);
  assert.equal(parts[0].type, 'text');
  assert.equal((parts[0] as { text: string }).text, 'B1\nB2');
  assert.deepEqual(parts[0].metadata, { [SYSTEM_PROMPT_CACHE_CONTROL_METADATA]: SYSTEM_PROMPT_CACHE_DISABLED });
  assert.equal((parts[1] as { text: string }).text, 'BASE');
  assert.equal((parts[2] as { text: string }).text, 'A1');
  assert.equal(parts[2].metadata, null);
});

test('applyInjections: 无 system → 新建 system 消息,after 加 \\n 前缀(:168)', () => {
  const byPosition = new Map<InjectionPosition, PromptInjection[]>([
    ['before_system_prompt', [inj('B', 'before_system_prompt')]],
    ['after_system_prompt', [inj('A', 'after_system_prompt')]],
  ]);
  const out: UIMessage[] = applyInjections([makeUserMessage('hi')], byPosition);
  assert.equal(out.length, 2);
  assert.equal(out[0].role, 'system');
  assert.deepEqual(out[0].parts.map((p: UIMessagePart): string => (p as { text: string }).text),
    ['B', '\nA']);
  // 仅 after 无前缀
  const onlyAfter = new Map<InjectionPosition, PromptInjection[]>([
    ['after_system_prompt', [inj('A', 'after_system_prompt')]],
  ]);
  const out2: UIMessage[] = applyInjections([makeUserMessage('hi')], onlyAfter);
  assert.equal((out2[0].parts[0] as { text: string }).text, 'A');
});

test('applyInjections: TOP_OF_CHAT 第一条 user 前;无 user → 末尾', () => {
  const byPosition = new Map<InjectionPosition, PromptInjection[]>([
    ['top_of_chat', [inj('TOP', 'top_of_chat')]],
  ]);
  const out: UIMessage[] = applyInjections(
    [makeUIMessage('system', [{ type: 'text', text: 's', metadata: null }]), makeUserMessage('u1')],
    byPosition);
  assert.equal(toText(out[1]), 'TOP');
  assert.equal(toText(out[2]), 'u1');
  const noUser: UIMessage[] = applyInjections([makeAssistantMessage('a')], byPosition);
  assert.equal(toText(noUser[noUser.length - 1]), 'TOP');
});

test('applyInjections: BOTTOM_OF_CHAT 最后一条之前;AT_DEPTH 按深度倒序', () => {
  const byPosition = new Map<InjectionPosition, PromptInjection[]>([
    ['bottom_of_chat', [inj('BOTTOM', 'bottom_of_chat')]],
  ]);
  const out: UIMessage[] = applyInjections(
    [makeUserMessage('u1'), makeUserMessage('u2')], byPosition);
  assert.deepEqual(out.map(toText), ['u1', 'BOTTOM', 'u2']);

  const atDepth = new Map<InjectionPosition, PromptInjection[]>([
    ['at_depth', [
      inj('D2', 'at_depth', { injectDepth: 2 }),
      inj('D1', 'at_depth', { injectDepth: 1 }),
    ]],
  ]);
  const msgs: UIMessage[] = [makeUserMessage('m1'), makeUserMessage('m2'), makeUserMessage('m3')];
  const out2: UIMessage[] = applyInjections(msgs, atDepth);
  // depth 2 先处理:size 3 - 2 = 1;depth 1:size 4 - 1 = 3
  assert.deepEqual(out2.map(toText), ['m1', 'D2', 'm2', 'D1', 'm3']);
});

// ===== transformMessages(:39-64) =====

test('transformMessages: 无注入 → 原引用;priority 降序稳定', () => {
  const assistant = makeAssistant({ modeInjectionIds: ['a', 'b'] });
  const msgs: UIMessage[] = [makeUserMessage('hi')];
  assert.equal(transformMessages(msgs, assistant, [], []), msgs);

  const low: PromptInjection = inj('LOW', 'after_system_prompt', { id: 'a', priority: 1 });
  const high1: PromptInjection = inj('H1', 'after_system_prompt', { id: 'b', priority: 5 });
  const high2: PromptInjection = inj('H2', 'after_system_prompt', { id: 'c', priority: 5 });
  const a2 = makeAssistant({ modeInjectionIds: ['a', 'b', 'c'] });
  const out: UIMessage[] = transformMessages(
    [makeUIMessage('system', [{ type: 'text', text: 'S', metadata: null }])],
    a2, [low, high1, high2], []);
  const last: UIMessagePart = out[0].parts[out[0].parts.length - 1];
  assert.equal((last as { text: string }).text, 'H1\nH2\nLOW');
});

// ===== transformer 工厂(deps 读取时机 = transform 调用时) =====

test('createPromptInjectionTransformer: 每次 transform 重新读 deps', async () => {
  let list: PromptInjection[] = [];
  const t = createPromptInjectionTransformer({
    modeInjections: (): PromptInjection[] => list,
    lorebooks: () => [],
  });
  const assistant = makeAssistant({ modeInjectionIds: ['x'] });
  const ctx: TransformerContext = { assistant };
  const msgs: UIMessage[] = [makeUserMessage('hi')];
  assert.equal(await t.transform!(ctx, msgs), msgs);
  list = [inj('LATE', 'bottom_of_chat', { id: 'x' })];
  const out: UIMessage[] = await t.transform!(ctx, msgs) as UIMessage[];
  assert.equal(out.length, 2);
  assert.equal(toText(out[0]), 'LATE');
});

test('cacheDisabledMetadata 常量逐字', () => {
  assert.equal(SYSTEM_PROMPT_CACHE_CONTROL_METADATA, 'system_prompt_cache_control');
  assert.equal(SYSTEM_PROMPT_CACHE_DISABLED, 'disabled');
  assert.deepEqual(cacheDisabledMetadata(), { system_prompt_cache_control: 'disabled' });
});
