// context_compact.test.ts — 上下文压缩纯逻辑(TDD)
//
// Android 基准:
//   ConversationContextPlanner.kt(316 行)/ContextFootprintEstimator.kt(162 行)/
//   CompactSummaryPayload.kt(575 行)/ToolResultCompactor.kt(16 行)/
//   ConversationContextModels.kt(128 行)/PreferencesStore.kt:236-254
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  makeCompactPolicy, compactPlanSourceMessageCount,
  weightedTokenChars, partEstimatedChars, partInputFootprintChars,
  estimateTokens, estimateContextWindow, estimateMessagesFootprint,
  takeMiddle, toolResultSummarize, partSummaryLine, buildCompressionInput,
  cleanRaw, cleanHumanText, looksLikeJsonFragment,
  parseCompactSummary, compactTimelineSummary, compactSearchableText,
  compactInjectionText, compactInjectionTextParts,
  validCompletedCompacts, selectCompactsForInjection,
  compactSentenceCount, isHighQualityPayload,
  normalizeCompactModelOutput, compactFallbackPayload, remapCoveredCompactIds,
  planCompaction, planForceCompaction, prepareMessagesWithCompacts,
  fitMessagesToTokenBudget, estimateConversationInputTokens,
} from '../main/ets/chat/context_compact.ts';
import type { ConversationCompact } from '../main/ets/chat/context_compact.ts';
import { makeUIMessage, makeUserMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';
import { makeConversation } from '../main/ets/chat/conversation.ts';
import type { MessageNode } from '../main/ets/chat/conversation.ts';

// ===== 构造助手 =====

const textMsg = (id: string, role: 'system' | 'user' | 'assistant', text: string): UIMessage => ({
  ...makeUIMessage(role, [{ type: 'text', text, metadata: null }]),
  id,
});

const nodeOf = (m: UIMessage): MessageNode => ({
  id: `node-${m.id}`,
  messages: [m],
  selectIndex: 0,
});

const nodesOfTexts = (texts: string[]): MessageNode[] =>
  texts.map((t: string, i: number): MessageNode =>
    nodeOf(textMsg(`m${i}`, i % 2 === 0 ? 'user' : 'assistant', t)));

const compactOf = (id: string, sourceEndIndex: number, sourceMessageIds: string[],
  summary: string = '', status: string = 'completed', createdAt: number = 1)
  : ConversationCompact => ({
  id, conversationId: 'conv', summary, level: 1,
  sourceStartIndex: 0, sourceEndIndex, sourceMessageIds,
  tokenEstimate: 100, createdAt, updatedAt: createdAt, status,
});

const v2Payload = (handoff: string, covered: string[] = [], timeline: string = '摘要。'): string =>
  JSON.stringify({
    schema_version: 2,
    timeline_summary: timeline,
    handoff_markdown: handoff,
    covered_compact_ids: covered,
    source_message_ids: ['a'],
    created_at: 1,
  });

// ===== weightedTokenChars =====

describe('weightedTokenChars(estimator:18-31)', () => {
  it('ASCII=1/CJK=4/符号段 3000-30FF=4/空串 0', () => {
    assert.equal(weightedTokenChars(''), 0);
    assert.equal(weightedTokenChars('abc'), 3);
    assert.equal(weightedTokenChars('你好'), 8);
    assert.equal(weightedTokenChars('。'), 4); // 300A 在 3000-30FF
    assert.equal(weightedTokenChars('a你b'), 6);
  });
});

describe('takeMiddle(planner:311-315)', () => {
  it('未超限原文;超限取头尾各 half,中间标注省略数', () => {
    assert.equal(takeMiddle('短', 100), '短');
    const s: string = 'x'.repeat(100);
    const out: string = takeMiddle(s, 80); // half = (80-40)/2 = 20
    assert.equal(out, `${'x'.repeat(20)}\n... [60 chars omitted] ...\n${'x'.repeat(20)}`);
  });
});

describe('estimateTokens / estimateContextWindow(planner:12-21)', () => {
  it('chars/4 下取整,下限 size*4;CJK 加权', () => {
    const msgs: UIMessage[] = [textMsg('a', 'user', '你好世界')]; // role 4 + 16 = 20 → 5
    assert.equal(estimateTokens(msgs), Math.max(Math.floor(20 / 4), 4));
    const many: UIMessage[] = [textMsg('a', 'user', ''), textMsg('b', 'user', ''),
      textMsg('c', 'user', '')];
    assert.equal(estimateTokens(many), 12); // floor((4*3)/4)=3 → 下限 3*4
  });

  it('窗口:null/<=0 → 128000;正数原样', () => {
    assert.equal(estimateContextWindow(null), 128000);
    assert.equal(estimateContextWindow(0), 128000);
    assert.equal(estimateContextWindow(200000), 200000);
  });
});

describe('part 估算双口径', () => {
  const toolPart = (executed: boolean): UIMessagePart => ({
    type: 'tool', toolCallId: 't1', toolName: 'search', input: 'query',
    output: executed ? [{ type: 'text', text: 'result', metadata: null }] : [],
    approvalState: { type: 'auto' }, metadata: null,
  });

  it('planner 口径:tool 输出恒计入;estimator 口径:仅 executed 计入', () => {
    const p1: number = partEstimatedChars(toolPart(true));
    const p0: number = partEstimatedChars(toolPart(false));
    assert.equal(p0, p1 - 6); // 'result' 6 字符
    assert.equal(partInputFootprintChars(toolPart(false)), weightedTokenChars('query'));
    assert.equal(partInputFootprintChars(toolPart(true)), weightedTokenChars('query') + 6);
  });

  it('document:planner 不加权/estimator 加权(中文文件名差异)', () => {
    const doc: UIMessagePart = {
      type: 'document', url: 'file://x', fileName: '报告.pdf', mime: 'application/pdf',
      metadata: null,
    };
    assert.equal(partEstimatedChars(doc), '报告.pdf'.length + 80);
    assert.equal(partInputFootprintChars(doc), weightedTokenChars('报告.pdf') + 80);
  });

  it('image/video/audio 恒 4500', () => {
    assert.equal(partEstimatedChars({ type: 'image', url: 'u', metadata: null }), 4500);
    assert.equal(partInputFootprintChars({ type: 'audio', url: 'u', fileName: 'f',
      mime: 'audio/mp3', metadata: null }), 4500);
  });
});

// ===== planCompaction =====

describe('planCompaction(planner:23-78)', () => {
  const policy = makeCompactPolicy();

  it('disabled / 空节点 → disabled', () => {
    const p = planCompaction([], [], policy, null);
    assert.equal(p.reason, 'disabled');
    assert.equal(p.shouldCompact, false);
    assert.equal(planCompaction(nodesOfTexts(['a']), [],
      makeCompactPolicy({ enabled: false }), null).reason, 'disabled');
  });

  it('低于 precompactRatio → below_threshold', () => {
    const p = planCompaction(nodesOfTexts(['短', '短']), [], policy, null);
    assert.equal(p.reason, 'below_threshold');
  });

  it('历史不足(keepRecentTurns*2 后 sourceEnd<1)→ not_enough_history', () => {
    // 构造超限:大文本使 ratio ≥ 0.70;节点数 < keepCount+1
    const big: string = '你'.repeat(400000); // ≈ 400K tokens
    const nodes: MessageNode[] = nodesOfTexts([big, big, big, big]);
    const p = planCompaction(nodes, [], policy, null);
    assert.equal(p.reason, 'not_enough_history');
  });

  it('达标且历史足 → shouldCompact,reason 按 forceRatio 分档;sourceEnd = lastIndex-keepCount', () => {
    const big: string = '你'.repeat(400000);
    const nodes: MessageNode[] = nodesOfTexts(
      [big, big, big, big, big, big, big, big, big, big, big, big, big, big,
        big, big, big, big, big, big, big, big, big, big, big, big, big, big]);
    const p = planCompaction(nodes, [], policy, null);
    assert.equal(p.shouldCompact, true);
    assert.equal(p.reason, 'force_threshold'); // ratio 远超 0.85
    assert.equal(p.sourceEndIndex, 27 - 24); // lastIndex=27, keepCount=24
    assert.equal(p.sourceStartIndex, 0);
    assert.equal(p.sourceMessageIds.length, p.sourceEndIndex - p.sourceStartIndex + 1);
    assert.equal(compactPlanSourceMessageCount(p), p.sourceMessageIds.length);
  });

  it('already_compacted:completed 覆盖至 sourceEnd', () => {
    const big: string = '你'.repeat(400000);
    const nodes: MessageNode[] = nodesOfTexts(
      Array.from({ length: 28 }, (_v, i: number): string => `${big}${i}`));
    const c = compactOf('c1', 3, ['m0', 'm1', 'm2', 'm3']);
    const p = planCompaction(nodes, [c], policy, null);
    assert.equal(p.reason, 'already_compacted');
  });

  it('增量区间:从 latestCoveredEnd+1 起;新区间不足 2 → not_enough_new_history', () => {
    const big: string = '你'.repeat(400000);
    const nodes: MessageNode[] = nodesOfTexts(
      Array.from({ length: 28 }, (_v, i: number): string => `${big}${i}`));
    // 覆盖到 sourceEnd-1 → 新区间仅 1 条
    const c = compactOf('c1', 2, ['m0', 'm1', 'm2']);
    const p = planCompaction(nodes, [c], policy, null);
    assert.equal(p.reason, 'not_enough_new_history');
  });

  it('precompact 档 reason(ratio ∈ [0.70,0.85))', () => {
    // 调窗口使 ratio ≈ 0.75:tokens ≈ 24 条 * (你*20→20 tokens)/1 → 粗控窗口
    const text: string = '你'.repeat(300); // 每条约 300 tokens
    const nodes: MessageNode[] = nodesOfTexts(
      Array.from({ length: 28 }, (): string => text));
    // tokens ≈ 28*300 = 8400 → window 11200 → ratio 0.75
    const p = planCompaction(nodes, [], policy, 11200);
    assert.equal(p.shouldCompact, true);
    assert.equal(p.reason, 'precompact_threshold');
  });

  it('头部 assistant+executed tool 跳过(start++);尾部未执行 tool 回退(end--)', () => {
    const big: string = '你'.repeat(400000);
    const nodes: MessageNode[] = nodesOfTexts(
      Array.from({ length: 28 }, (): string => big));
    // nodes[0] 换成 assistant+executed tool(会被 start 跳过)
    const executedTool: UIMessage = {
      ...makeUIMessage('assistant', [{
        type: 'tool', toolCallId: 't', toolName: 's', input: 'i',
        output: [{ type: 'text', text: 'o', metadata: null }],
        approvalState: { type: 'auto' }, metadata: null,
      }]),
      id: 'm0',
    };
    // nodes[3](=sourceEnd)换成含未执行 tool
    const pendingTool: UIMessage = {
      ...makeUIMessage('assistant', [{
        type: 'tool', toolCallId: 't2', toolName: 's', input: 'i',
        output: [], approvalState: { type: 'pending' }, metadata: null,
      }]),
      id: 'm3',
    };
    nodes[0] = nodeOf(executedTool);
    nodes[3] = nodeOf(pendingTool);
    const p = planCompaction(nodes, [], policy, null);
    assert.equal(p.shouldCompact, true);
    assert.equal(p.sourceStartIndex, 1); // 跳过 nodes[0]
    assert.equal(p.sourceEndIndex, 2); // 回退掉 nodes[3]
  });
});

describe('planForceCompaction(planner:80-135)', () => {
  it('keepRecentTurns 阶梯 [12,6,3,1] → 选最深满足 target 的方案,reason 恒 force_threshold', () => {
    const big: string = '你'.repeat(400000);
    const nodes: MessageNode[] = nodesOfTexts(
      Array.from({ length: 40 }, (): string => big));
    const p = planForceCompaction(nodes, [], makeCompactPolicy(), null);
    assert.equal(p.shouldCompact, true);
    assert.equal(p.reason, 'force_threshold');
    // 最深方案 keepRecentTurns=1 → sourceEnd = 39-2
    assert.equal(p.sourceEndIndex, 37);
  });
});

// ===== prepareMessages =====

describe('prepareMessagesWithCompacts(planner:137-163)', () => {
  const policy = makeCompactPolicy();

  it('无 compacts → 纯 limitContext', () => {
    const msgs: UIMessage[] = [textMsg('a', 'user', '1'), textMsg('b', 'user', '2'),
      textMsg('c', 'user', '3')];
    const out: UIMessage[] = prepareMessagesWithCompacts(msgs, [], policy, 2);
    assert.deepEqual(out.map((m: UIMessage): string => m.id), ['b', 'c']);
  });

  it('disabled → 纯 limitContext', () => {
    const msgs: UIMessage[] = [textMsg('a', 'user', '1'), textMsg('b', 'user', '2')];
    const c = compactOf('c1', 0, ['a'], v2Payload('handoff 内容'));
    const out: UIMessage[] = prepareMessagesWithCompacts(
      msgs, [c], makeCompactPolicy({ enabled: false }), 5);
    assert.deepEqual(out.map((m: UIMessage): string => m.id), ['a', 'b']);
  });

  it('有 compact:注入 system 摘要 + 覆盖消息剔除 + 余量 limitContext', () => {
    const msgs: UIMessage[] = [textMsg('a', 'user', '旧一'), textMsg('b', 'assistant', '旧二'),
      textMsg('c', 'user', '新一'), textMsg('d', 'assistant', '新二'), textMsg('e', 'user', '新三')];
    const c = compactOf('cp1', 1, ['a', 'b'], v2Payload('## Goal\n- 继续'));
    const out: UIMessage[] = prepareMessagesWithCompacts(msgs, [c], policy, 0);
    // 摘要 system 在最前
    assert.equal(out[0].role, 'system');
    const firstPart = out[0].parts[0];
    assert.equal(firstPart.type === 'text'
      && (firstPart as { text: string }).text.includes('[Conversation compact handoff: cp1]'), true);
    // 被覆盖的 a/b 被剔除
    assert.deepEqual(out.slice(1).map((m: UIMessage): string => m.id), ['c', 'd', 'e']);
  });

  it('contextMessageSize=0 → keepLimit = max(keepRecentTurns*2, 12)', () => {
    const msgs: UIMessage[] = [];
    for (let i = 0; i < 30; i++) msgs.push(textMsg(`m${i}`, 'user', `t${i}`));
    const c = compactOf('cp1', 0, ['m0'], v2Payload('handoff'));
    const out: UIMessage[] = prepareMessagesWithCompacts(msgs, [c], policy, 0);
    // m0 被覆盖 → 余 m1..m29 共 29 条 → keepLimit 24 → 取尾 24(m6 起)+ 摘要 1
    assert.equal(out.length, 25);
    assert.equal(out[1].id, 'm6');
  });

  it('compacts 存在但无效(消息 id 不在现有集)→ 纯 limitContext', () => {
    const msgs: UIMessage[] = [textMsg('a', 'user', '1'), textMsg('b', 'user', '2')];
    const c = compactOf('c1', 0, ['ghost'], v2Payload('handoff'));
    const out: UIMessage[] = prepareMessagesWithCompacts(msgs, [c], policy, 5);
    assert.deepEqual(out.map((m: UIMessage): string => m.id), ['a', 'b']);
  });
});

describe('fitMessagesToTokenBudget(planner:165-189)', () => {
  it('maxTokens<=0 或空 → takeLast(1)', () => {
    const msgs: UIMessage[] = [textMsg('a', 'user', '1'), textMsg('b', 'user', '2')];
    assert.deepEqual(fitMessagesToTokenBudget(msgs, 0).map((m: UIMessage): string => m.id), ['b']);
    assert.deepEqual(fitMessagesToTokenBudget([], 5), []);
  });

  it('未超预算 → 原样', () => {
    const msgs: UIMessage[] = [textMsg('a', 'user', '短')];
    assert.equal(fitMessagesToTokenBudget(msgs, 1000).length, 1);
  });

  it('超预算:system 保留 + 尾部倒序纳入,单条超也保一条', () => {
    const sys: UIMessage = textMsg('s', 'system', '系统');
    const big: UIMessage = textMsg('big', 'user', '你'.repeat(4000)); // ≈1000 tokens
    const small: UIMessage = textMsg('small', 'assistant', '好');
    const out: UIMessage[] = fitMessagesToTokenBudget([sys, big, small], 2000);
    assert.equal(out[0].id, 's');
    // 单条 big(≈1005 tokens)也超限 → 仅保留 big(空时回加)
    const out2: UIMessage[] = fitMessagesToTokenBudget([sys, big], 100);
    assert.deepEqual(out2.map((m: UIMessage): string => m.id), ['s', 'big']);
  });
});

describe('buildCompressionInput + summaryLine(planner:191-199/:296-308)', () => {
  it('块格式:message_id/role/parts 行,块间空行', () => {
    const msgs: UIMessage[] = [textMsg('id1', 'user', '你好')];
    assert.equal(buildCompressionInput(msgs),
      'message_id: id1\nrole: user\ntext: 你好');
  });

  it('tool 行:executed 标志 + input takeMiddle(2000)+ output summarize', () => {
    const line: string = partSummaryLine({
      type: 'tool', toolCallId: 'tc', toolName: 'search', input: 'q',
      output: [{ type: 'text', text: 'r', metadata: null }],
      approvalState: { type: 'auto' }, metadata: null,
    });
    assert.equal(line, 'tool: search id=tc executed=true input=q output=r');
  });

  it('reasoning → marker;image → url 尾 80;nested_tool 递归', () => {
    assert.equal(partSummaryLine({
      type: 'reasoning', reasoning: 'abc', createdAt: 'x', finishedAt: null, metadata: null,
    }), 'reasoning_marker: 3 chars');
    assert.equal(partSummaryLine({ type: 'image', url: 'u'.repeat(100), metadata: null }),
      `image: ${'u'.repeat(80)}`);
    assert.equal(toolResultSummarize([{
      type: 'tool', toolCallId: 'n', toolName: 'inner', input: '',
      output: [{ type: 'text', text: 'deep', metadata: null }],
      approvalState: { type: 'auto' }, metadata: null,
    }]), 'nested_tool:inner:deep');
  });
});

// ===== payload 解析 =====

describe('parseCompactSummary(payload:160-182)', () => {
  it('v2 形状解析全字段', () => {
    const p = parseCompactSummary(v2Payload('## Goal\n- x', ['c0'], '时间线。'));
    assert.ok(p !== null);
    assert.equal(p.schemaVersion, 2);
    assert.equal(p.timelineSummary, '时间线。');
    assert.equal(p.handoffMarkdown, '## Goal\n- x');
    assert.deepEqual(p.coveredCompactIds, ['c0']);
  });

  it('围栏包裹(```json)可解析;前言并入 timeline', () => {
    const raw: string = '前言说明\n```json\n' + v2Payload('h') + '\n```';
    const p = parseCompactSummary(raw);
    assert.ok(p !== null);
    assert.equal(p.timelineSummary.includes('前言说明') || p.timelineSummary === '摘要。', true);
  });

  it('非 JSON 文本 → null;无 v2 形状 JSON → null', () => {
    assert.equal(parseCompactSummary('普通文本'), null);
    assert.equal(parseCompactSummary('{"foo":1}'), null);
  });

  it('缺 handoff_markdown → legacy 模板合成(含 ## Goal)', () => {
    const p = parseCompactSummary(JSON.stringify({
      schema_version: 2, timeline_summary: 'T。',
      goals: ['目标一'], source_message_ids: ['m1'],
    }));
    assert.ok(p !== null);
    assert.equal(p.handoffMarkdown.includes('## Goal'), true);
    assert.equal(p.handoffMarkdown.includes('- 目标一'), true);
    assert.equal(p.handoffMarkdown.includes('- Source message ids: m1'), true);
  });
});

describe('compactTimelineSummary(payload:184-198)', () => {
  it('payload/preamble/纯文本/JSON 碎片分支', () => {
    assert.equal(compactTimelineSummary(v2Payload('h', [], 'TL')), 'TL.');
    // v2 JSON + 前言:parse 成功 → timeline_summary 键直取(preamble 不赢,:184-185)
    assert.equal(compactTimelineSummary('前言文字\n{"timeline_summary":"x"}'), 'x.');
    // 非 v2 JSON + 前言:parse null → preamble 分支(:188-192)
    assert.equal(compactTimelineSummary('前言文字\n{"foo":1}'), '前言文字.');
    assert.equal(compactTimelineSummary('纯文本摘要'), '纯文本摘要.');
    assert.equal(compactTimelineSummary('{"timeline_summary":'), null);
    assert.equal(compactTimelineSummary(''), null);
  });
});

describe('compactInjectionText(payload:211-233)', () => {
  it('模板逐字:头/source ids/covered ids(非空才有)/handoff', () => {
    const c = compactOf('cp9', 1, ['a', 'b'], v2Payload('## Goal\n- 继续', ['cp1']));
    const out: string = compactInjectionText(c);
    assert.equal(out,
      '[Conversation compact handoff: cp9]\nSource message ids: a, b\n'
      + 'Covered compact ids: cp1\n\n## Goal\n- 继续');
  });

  it('无 covered → 省略该行;无 payload → timelineSummary 兜底(ensureTerminalPeriod 加句号)', () => {
    const c = compactOf('cp9', 0, ['a'], '纯文本 handoff 内容');
    const out: string = compactInjectionText(c);
    // timelineSummary 分支:coerceTimelineSummary 末端补 '.'(payload:436-440)
    assert.equal(out,
      '[Conversation compact handoff: cp9]\nSource message ids: a\n\n纯文本 handoff 内容.');
  });
});

describe('validCompletedCompacts + selectCompactsForInjection(payload:235-260)', () => {
  it('valid:status/非空/全覆盖过滤 + sourceEndIndex→createdAt 排序', () => {
    const ids = new Set<string>(['a', 'b', 'c']);
    const list: ConversationCompact[] = [
      compactOf('c2', 2, ['a'], 'x', 'completed', 2),
      compactOf('c1', 2, ['a'], 'x', 'completed', 1),
      compactOf('c3', 1, ['ghost'], 'x'),
      compactOf('c4', 1, ['a'], 'x', 'running'),
      compactOf('c0', 0, ['a', 'b'], 'x'),
    ];
    const out: ConversationCompact[] = validCompletedCompacts(list, ids);
    assert.deepEqual(out.map((c: ConversationCompact): string => c.id), ['c0', 'c1', 'c2']);
  });

  it('select:无 payload → 全部;有 payload → 最新 + 未被其传递覆盖的', () => {
    const ids = new Set<string>(['a']);
    const noPayload: ConversationCompact[] = [compactOf('c1', 0, ['a'], '文本'),
      compactOf('c2', 1, ['a'], '文本2')];
    assert.deepEqual(selectCompactsForInjection(noPayload, ids)
      .map((c: ConversationCompact): string => c.id), ['c1', 'c2']);
    // c2 payload 覆盖 c1 → 仅 c2;c3 不被覆盖保留在前
    const withPayload: ConversationCompact[] = [
      compactOf('c1', 0, ['a'], v2Payload('h1')),
      compactOf('c3', 1, ['a'], '无 payload 文本'),
      compactOf('c2', 2, ['a'], v2Payload('h2', ['c1'])),
    ];
    assert.deepEqual(selectCompactsForInjection(withPayload, ids)
      .map((c: ConversationCompact): string => c.id), ['c3', 'c2']);
  });

  it('select:传递覆盖(c2 covers c1 covers c0)→ c0/c1 均剔除', () => {
    const ids = new Set<string>(['a']);
    const list: ConversationCompact[] = [
      compactOf('c0', 0, ['a'], v2Payload('h0')),
      compactOf('c1', 1, ['a'], v2Payload('h1', ['c0'])),
      compactOf('c2', 2, ['a'], v2Payload('h2', ['c1'])),
    ];
    assert.deepEqual(selectCompactsForInjection(list, ids)
      .map((c: ConversationCompact): string => c.id), ['c2']);
  });
});

describe('normalizeCompactModelOutput(payload:86-128)', () => {
  it('干净 v2 JSON 输入 → 规范化(legacy 字段保留)', () => {
    const out: string | null = normalizeCompactModelOutput(
      JSON.stringify({
        schema_version: 2, timeline_summary: 'T', handoff_markdown: 'H',
        goals: ['g1'], custom_key: 'keep',
      }), ['m1', 'm1', 'm2'], ['c0'], 42);
    assert.ok(out !== null);
    const obj = JSON.parse(out as string) as Record<string, unknown>;
    assert.equal(obj['schema_version'], 2);
    assert.equal(obj['timeline_summary'], 'T.');
    assert.equal(obj['handoff_markdown'], 'H');
    // Android quirk(忠实):parsed 非空时 putLegacyFields 以 RAW list 覆盖
    //   :124 的 distinct 版(payload:504 putStringArray(sourceMessageIds))
    assert.deepEqual(obj['source_message_ids'], ['m1', 'm1', 'm2']);
    assert.deepEqual(obj['covered_compact_ids'], ['c0']);
    assert.equal(obj['created_at'], 42);
    assert.deepEqual(obj['goals'], ['g1']);
    assert.equal(obj['custom_key'], 'keep');
  });

  it('纯文本 → timeline=清洗文本,handoff=plainText 模板', () => {
    const out: string | null = normalizeCompactModelOutput('讨论了迁移方案', [], [], 1);
    assert.ok(out !== null);
    const obj = JSON.parse(out as string) as Record<string, unknown>;
    assert.equal(obj['timeline_summary'], '讨论了迁移方案.');
    assert.equal((obj['handoff_markdown'] as string).includes('## Goal'), true);
    assert.equal((obj['handoff_markdown'] as string).includes('- 讨论了迁移方案.'), true);
  });

  it('JSON 碎片(畸形)→ malformed 文案;空白 → null', () => {
    const out: string | null = normalizeCompactModelOutput('{"timeline_summary": "x', [], [], 1);
    assert.ok(out !== null);
    const obj = JSON.parse(out as string) as Record<string, unknown>;
    assert.equal(obj['timeline_summary'],
      'Conversation history was compacted, but the model returned malformed JSON.');
    assert.equal(normalizeCompactModelOutput('   ', [], [], 1), null);
  });
});

describe('compactFallbackPayload(payload:130-158)', () => {
  it('人类文本直接为 timeline;carriedHandoff 空 → covered ids 清空', () => {
    const out: string = compactFallbackPayload('摘要文本', ['m1'], ['c9'], 7, '', '');
    const obj = JSON.parse(out) as Record<string, unknown>;
    assert.equal(obj['timeline_summary'], '摘要文本.');
    assert.deepEqual(obj['covered_compact_ids'], []);
  });

  it('JSON 碎片 → 从 sourceContent 提取片段(中文模板);carried → covered 保留 + Previous 段', () => {
    const src: string = 'message_id: m1\nrole: user\ntext: 第一段内容\n\nmessage_id: m2\nrole: assistant\ntext: 第二段内容';
    const out: string = compactFallbackPayload('{"bad json', ['m1'], ['c9'], 7, src, '旧 handoff');
    const obj = JSON.parse(out) as Record<string, unknown>;
    assert.equal((obj['timeline_summary'] as string).includes('已压缩的历史包含 2 段可读内容。'), true);
    assert.deepEqual(obj['covered_compact_ids'], ['c9']);
    assert.equal((obj['handoff_markdown'] as string).includes('## Previous Compact Handoffs'), true);
  });

  it('无 source 且无文本 → 通用兜底(英文)', () => {
    const out: string = compactFallbackPayload('{"x"', [], [], 1, '', '');
    const obj = JSON.parse(out) as Record<string, unknown>;
    assert.equal((obj['timeline_summary'] as string).startsWith('Conversation history was compacted.'), true);
  });
});

describe('remapCoveredCompactIds(payload:53-84)', () => {
  it('covered ids 重映射 + handoff 引用替换;前言保留', () => {
    const raw: string = '前言\n' + JSON.stringify({
      schema_version: 2, timeline_summary: 'T', handoff_markdown: '见 cp1 的结论',
      covered_compact_ids: ['cp1', 'cpX'],
    });
    const out: string = remapCoveredCompactIds(raw, new Map([['cp1', 'new1']]));
    assert.equal(out.startsWith('前言\n'), true);
    const obj = JSON.parse(out.slice(3)) as Record<string, unknown>;
    assert.deepEqual(obj['covered_compact_ids'], ['new1']); // cpX 无映射丢弃
    assert.equal(obj['handoff_markdown'], '见 new1 的结论');
  });

  it('空映射/非 v2 → 原文', () => {
    assert.equal(remapCoveredCompactIds('任意', new Map()), '任意');
    assert.equal(remapCoveredCompactIds('{"a":1}', new Map([['x', 'y']])), '{"a":1}');
  });
});

describe('质量与计数(payload:262-272)', () => {
  it('sentenceCount:句读计数,无句号保底 1,空白 0', () => {
    assert.equal(compactSentenceCount(''), 0);
    assert.equal(compactSentenceCount('没有句号'), 1);
    assert.equal(compactSentenceCount('一。二！三？四.'), 4);
  });

  it('isHighQualityPayload:handoff≥80 且句数≥4', () => {
    const good: string = v2Payload('h'.repeat(80), [], '一。二。三。四。');
    assert.equal(isHighQualityPayload(good), true);
    assert.equal(isHighQualityPayload(v2Payload('短', [], '一。二。三。四。')), false);
    assert.equal(isHighQualityPayload('非 json'), false);
  });
});

describe('estimateConversationInputTokens(estimator:57-102)', () => {
  it('无 compacts → 原始估算;有 compacts → 摘要+未覆盖口径', () => {
    const msgs: UIMessage[] = [textMsg('a', 'user', '你'.repeat(400)),
      textMsg('b', 'assistant', '好'), textMsg('c', 'user', '新')];
    const raw: number = estimateConversationInputTokens(msgs, []);
    assert.equal(raw, estimateMessagesFootprint(msgs));
    const c = compactOf('cp1', 0, ['a'], v2Payload('短摘要'));
    const withCompact: number = estimateConversationInputTokens(msgs, [c]);
    // 覆盖后:a 剔除 + 摘要注入 → 远小于原始
    assert.equal(withCompact < raw, true);
  });
});

describe('searchableText(payload:200-209)', () => {
  it('payload → timeline+handoff;否则 timelineSummary/原文', () => {
    assert.equal(compactSearchableText(v2Payload('HH', [], 'TT')), 'TT.\nHH');
    assert.equal(compactSearchableText('{"bad"'), '{"bad"');
  });
});
