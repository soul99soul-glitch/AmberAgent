// D-085a 测试 — memory 读路径纯模块逐字锁定
// Android 基准:MemoryModels.kt/MemoryEnums.kt/MemoryPromptBuilder.kt/
//   MemoryContentSafety.kt/MemoryTimeAnchorParser.kt/MemoryRecallStore.kt
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  DEFAULT_MEMORY_RECALL_SETTING, makeMemoryRecord, memoryCandidateStatusFromWireName, memoryEventTypeFromWireName,
  memoryKindFromWireName, memoryScopeFromWireName,
} from '../main/ets/chat/memory_models.ts';
import {
  buildMemoryContext, isSensitiveMemoryContent, SENSITIVE_MEMORY_TERMS,
} from '../main/ets/chat/memory_prompt_builder.ts';
import {
  classifyMemoryFreshness, deriveMemoryExpiresAt,
} from '../main/ets/chat/memory_time_anchor.ts';
import {
  rankMemoryRecords, scoreMemoryRecord, tokenizeMemoryQuery, USER_ALWAYS_ELIGIBLE_CONFIDENCE
} from '../main/ets/chat/memory_recall.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { MemoryRecord } from '../main/ets/chat/memory_models.ts';

// 固定 now:2026-07-28 本地中午(避免时区边界)
const NOW: number = new Date(2026, 6, 28, 12, 0, 0).getTime();

const record = (opts: {
  id?: number; content: string; scope?: 'core' | 'short_term' | 'long_term';
  kind?: 'user' | 'feedback' | 'project' | 'reference' | 'routine' | 'note';
  pinned?: boolean; confidence?: number; updatedAt?: number; lastUsedAt?: number | null;
}): MemoryRecord => makeMemoryRecord({
  id: opts.id ?? 1,
  content: opts.content,
  scope: opts.scope ?? 'long_term',
  kind: opts.kind ?? 'note',
  pinned: opts.pinned ?? false,
  confidence: opts.confidence ?? 1,
  updatedAt: opts.updatedAt ?? NOW,
  lastUsedAt: opts.lastUsedAt === undefined ? null : opts.lastUsedAt,
});

// ===== 模型 =====

test('wireName 回退逐字(scope→long_term,kind→note,status→pending,event→extraction_skipped)', () => {
  assert.equal(memoryScopeFromWireName('core'), 'core');
  assert.equal(memoryScopeFromWireName('bogus'), 'long_term');
  assert.equal(memoryScopeFromWireName(null), 'long_term');
  assert.equal(memoryKindFromWireName('feedback'), 'feedback');
  assert.equal(memoryKindFromWireName('bogus'), 'note');
  assert.equal(memoryCandidateStatusFromWireName('filtered'), 'filtered');
  assert.equal(memoryCandidateStatusFromWireName('bogus'), 'pending');
  assert.equal(memoryEventTypeFromWireName('memory_created'), 'memory_created');
  assert.equal(memoryEventTypeFromWireName('bogus'), 'extraction_skipped');
  assert.deepEqual(DEFAULT_MEMORY_RECALL_SETTING,
    { maxItems: 12, maxPromptChars: 2000, debug: false });
  assert.equal(USER_ALWAYS_ELIGIBLE_CONFIDENCE, 0.70);
});

// ===== PromptBuilder =====

test('buildMemoryContext: 空 → 空串;单条逐字节', () => {
  assert.equal(buildMemoryContext([]), '');
  const out: string = buildMemoryContext([
    record({ content: '喜欢简体中文回复', scope: 'core', kind: 'user' }),
  ], false, {}, NOW);
  assert.equal(out,
    '<memory_context>\n'
    + '今天是 2026-07-28。相对时间以记忆的记录日期为准。\n'
    + '以下是与当前请求相关的记忆；若与当前用户消息冲突，以当前用户消息为准。\n'
    + '- [core/user] 喜欢简体中文回复 [memory:1] (记录日期=2026-07-28)'
    + '\n使用某条记忆时附上它的 [[memory:编号]] 标记，只引用实际用于回答的记忆。\n</memory_context>');
});

test('buildMemoryContext: pinned 标记 + 多行压单行 + trim + 多条换行', () => {
  const out: string = buildMemoryContext([
    record({ id: 1, content: '  第一行\n第二行  ', pinned: true }),
    record({ id: 2, content: '第二条', kind: 'feedback', scope: 'short_term' }),
  ], false, {}, NOW);
  assert.equal(out,
    '<memory_context>\n'
    + '今天是 2026-07-28。相对时间以记忆的记录日期为准。\n'
    + '以下是与当前请求相关的记忆；若与当前用户消息冲突，以当前用户消息为准。\n'
    + '- [long_term/note/pinned] 第一行 第二行 [memory:1] (记录日期=2026-07-28)\n'
    + '- [short_term/feedback] 第二条 [memory:2] (记录日期=2026-07-28)'
    + '\n使用某条记忆时附上它的 [[memory:编号]] 标记，只引用实际用于回答的记忆。\n</memory_context>');
});

test('buildMemoryContext: debug 后缀(id/confidence %.2f/details)', () => {
  const out: string = buildMemoryContext(
    [record({ id: 7, content: 'x', confidence: 0.5 })],
    true,
    { 7: 'score=95.0, reasons=pinned|core, freshness=current' },
  );
  assert.ok(out.indexOf(
    '- [long_term/note] x [memory:7] (记录日期=2026-07-28) (id=7, confidence=0.50, score=95.0, reasons=pinned|core, freshness=current)') >= 0);
  // debug 但无 details → 无 details 段
  const out2: string = buildMemoryContext(
    [record({ id: 8, content: 'y', confidence: 1 })], true);
  assert.ok(out2.indexOf('(id=8, confidence=1.00)') >= 0);
});

// ===== Safety =====

test('isSensitiveMemoryContent: 术语逐字 + 大小写不敏感', () => {
  assert.equal(SENSITIVE_MEMORY_TERMS.length, 12);
  assert.equal(isSensitiveMemoryContent('我的身份证号是…'), true);
  assert.equal(isSensitiveMemoryContent('my PASSWORD is x'), true);
  assert.equal(isSensitiveMemoryContent('credit card 尾号'), true);
  assert.equal(isSensitiveMemoryContent('喜欢爬山'), false);
});

// ===== TimeAnchor =====

test('classifyFreshness: 无未来意图 → current;历史意图优先', () => {
  assert.equal(classifyMemoryFreshness('喜欢简体中文', NOW), 'current');
  // 历史意图短路(hasHistoricalIntent 优先于未来意图)
  assert.equal(classifyMemoryFreshness('去过 2020-01-01 旅行', NOW), 'current');
});

test('classifyFreshness: 未来意图 + 过期锚 → time_decayed;未来锚 → current', () => {
  assert.equal(classifyMemoryFreshness('计划 2020-01-01 去旅行', NOW), 'time_decayed');
  assert.equal(classifyMemoryFreshness('计划 2030-01-01 去旅行', NOW), 'current');
  // 中文日期锚
  assert.equal(classifyMemoryFreshness('打算 2020年5月 出行', NOW), 'time_decayed');
  assert.equal(classifyMemoryFreshness('打算 2030年5月1日 出行', NOW), 'current');
  // 仅年月锚:2026-07 不早于当月 → current;2026-06 → decayed
  assert.equal(classifyMemoryFreshness('计划 2026-07 出差', NOW), 'current');
  assert.equal(classifyMemoryFreshness('计划 2026-06 出差', NOW), 'time_decayed');
});

test('classifyFreshness: 英文未来意图正则逐字', () => {
  assert.equal(classifyMemoryFreshness('Will travel on 2020-01-01', NOW), 'time_decayed');
  assert.equal(classifyMemoryFreshness('going to visit 2020-01-01', NOW), 'time_decayed');
  // 无锚 → current(未来意图但无日期)
  assert.equal(classifyMemoryFreshness('计划明年去看看', NOW), 'current');
});

test('classifyFreshness: 非法日期锚被忽略(runCatching → null)', () => {
  // 2026-13-99 非法 → 锚丢弃 → 无过期锚 → current
  assert.equal(classifyMemoryFreshness('计划 2026-13-99 出行', NOW), 'current');
  // 2026-02-30 非法
  assert.equal(classifyMemoryFreshness('计划 2026-02-30 出行', NOW), 'current');
});

test('deriveMemoryExpiresAt: 日锚 → 次日零点;月锚 → 次月 1 日零点;最早未过期', () => {
  // 日锚 2030-05-01 → expiresAt = 2030-05-02T00:00 local
  const dayAnchor: number | null = deriveMemoryExpiresAt('计划 2030-05-01 出行', NOW);
  assert.equal(dayAnchor, new Date(2030, 4, 2).getTime());
  // 月锚 2030-05 → 2030-06-01T00:00
  const monthAnchor: number | null = deriveMemoryExpiresAt('打算 2030-05 出行', NOW);
  assert.equal(monthAnchor, new Date(2030, 5, 1).getTime());
  // 混合:过期 2020 + 未来 2030 → 取未过期最早
  const mixed: number | null =
    deriveMemoryExpiresAt('计划 2020-01-01 与 2030-01-01 出行', NOW);
  assert.equal(mixed, new Date(2030, 0, 2).getTime());
  // 无未来意图 → null
  assert.equal(deriveMemoryExpiresAt('2030-01-01 记录', NOW), null);
});

// ===== Recall tokenize =====

test('tokenize: 词元(2..48)+ CJK 4 字窗 step2 + 160 上限', () => {
  const terms: Set<string> = tokenizeMemoryQuery('Hello World 你好世界人们啊');
  assert.ok(terms.has('hello'));
  assert.ok(terms.has('world'));
  // CJK:compact = 'helloworld你好世界人们啊';窗口 step2 含 '你好世界'/'世界人们'…
  assert.ok(terms.has('你好世界'));
  // 单字词元不入(长度 < 2)
  assert.ok(!tokenizeMemoryQuery('a b cc').has('a'));
  // >48 词元被滤
  const long: Set<string> = tokenizeMemoryQuery('x'.repeat(60));
  assert.equal(long.has('x'.repeat(60)), false);
});

// ===== Recall score =====

test('score: no-match → 0 分 reasons=[no-match]', () => {
  const s = scoreMemoryRecord(
    record({ content: '毫无关联的内容' }), new Set(['zzzz']), '当前消息', NOW);
  assert.equal(s.value, 0);
  assert.deepEqual(s.reasons, ['no-match']);
  assert.equal(s.freshness, 'current');
});

test('score: current-exact +30 与 term-match 递增', () => {
  const now: number = NOW;
  const r: MemoryRecord = record({
    content: '用户喜欢简体中文回复', updatedAt: now,
  });
  const s = scoreMemoryRecord(r, new Set(), '用户喜欢简体中文', now);
  // current-exact:30 + kind(note 6) + scope(long_term 18) + updateAge(10)
  assert.equal(s.value, (30 + 6 + 18 + 10) * 1);
  assert.ok(s.reasons.indexOf('current-exact') >= 0);
});

test('score: pinned/core/feedback 恒合格 + 系数', () => {
  const pinned: MemoryRecord = record({
    content: 'zzzz 不匹配', pinned: true, updatedAt: NOW,
  });
  const s = scoreMemoryRecord(pinned, new Set(['qqqq']), '无关', NOW);
  // pinned 100 + note 6 + long_term 18 + updateAge 10 = 134
  assert.equal(s.value, 134);
  assert.deepEqual(s.reasons, ['pinned']);
});

test('score: time-decayed ×0.35 + confidence 钳制 [0.1,1]', () => {
  const decayed: MemoryRecord = record({
    content: '计划 2020-01-01 去旅行', kind: 'user', scope: 'core',
    confidence: 0.5, updatedAt: NOW,
  });
  const s = scoreMemoryRecord(decayed, new Set(), '无关消息', NOW);
  // base: user 44 + core 26 + updateAge 10 = 80 → ×0.35 = 28 → ×0.5 = 14
  assert.equal(s.value, 14);
  assert.ok(s.reasons.indexOf('time-decayed') >= 0);
  assert.equal(s.freshness, 'time-decayed');
  // confidence < 0.1 → 按 0.1
  const low: MemoryRecord = record({
    content: 'zz', pinned: true, confidence: 0, updatedAt: NOW,
  });
  const s2 = scoreMemoryRecord(low, new Set(), '', NOW);
  assert.equal(s2.value, 134 * 0.1);
});

test('score: lastUsedAt 新近度加成(0 天 → +8)', () => {
  const r: MemoryRecord = record({
    content: 'x', pinned: true, updatedAt: NOW, lastUsedAt: NOW,
  });
  const s = scoreMemoryRecord(r, new Set(), '', NOW);
  // 134 + lastUsed 8 = 142
  assert.equal(s.value, 142);
});

// ===== rankRecords =====

test('rankRecords: 过滤(score>0 或 terms 空)+ 排序 pinned>score>updatedAt + 预算', () => {
  const setting = { maxItems: 12, maxPromptChars: 2000, debug: false };
  const messages = [makeUIMessage('user', [{ type: 'text', text: '中文', metadata: null }])];
  const records: MemoryRecord[] = [
    record({ id: 1, content: '毫无关联zz' }),
    record({ id: 2, content: '中文偏好', kind: 'user', updatedAt: NOW }),
    record({ id: 3, content: '无关但置顶', pinned: true, updatedAt: NOW }),
  ];
  const ranked = rankMemoryRecords(setting, messages, records, NOW);
  // id=3 pinned 最前;id=2 term-match 次之;id=1 no-match 被滤
  assert.deepEqual(ranked.map((s) => s.record.id), [3, 2]);
});

test('rankRecords: terms 为空时全量保留(0 分也过)', () => {
  const setting = { maxItems: 12, maxPromptChars: 2000, debug: false };
  const records: MemoryRecord[] = [record({ id: 1, content: 'zz 无关' })];
  const ranked = rankMemoryRecords(setting, [], records, NOW);
  assert.equal(ranked.length, 1);
});

test('rankRecords: 字符预算 — 首条恒收,超预算跳过(continue),maxItems 截断', () => {
  const setting = { maxItems: 2, maxPromptChars: 256, debug: false };
  const big: string = '中'.repeat(300); // cost 332 > 256 — 首条仍收
  const records: MemoryRecord[] = [
    record({ id: 1, content: big, pinned: true, updatedAt: NOW }),
    record({ id: 2, content: '第二条', pinned: true, updatedAt: NOW }),
    record({ id: 3, content: '第三条', pinned: true, updatedAt: NOW }),
  ];
  const ranked = rankMemoryRecords(setting, [], records, NOW);
  // 首条收(used 332);第二条 cost 38 → 332+38>256 跳过;第三条同跳过
  assert.deepEqual(ranked.map((s) => s.record.id), [1]);
});

test('rankRecords: 钳制 maxItems [1,40] / maxPromptChars [256,12000]', () => {
  const setting = { maxItems: 0, maxPromptChars: 10, debug: false };
  const records: MemoryRecord[] = [
    record({ id: 1, content: '一', updatedAt: NOW }),
    record({ id: 2, content: '二', updatedAt: NOW }),
  ];
  // maxItems → 1
  const ranked = rankMemoryRecords(setting, [], records, NOW);
  assert.equal(ranked.length, 1);
});
