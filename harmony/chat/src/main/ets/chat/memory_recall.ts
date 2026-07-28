// memory_recall — MemoryRecallStore.kt 召回/评分纯逻辑逐字移植
// Android 锚点:core/memory/recall/MemoryRecallStore.kt
//   rankRecords(:71-95)/score(:97-153)/tokenize(:155-167)/
//   alwaysEligibleReasons(:169-180)/takeBudget(:182-195)/toDebugText(:216-217)
// 本模块只含纯函数;recallSelections(scopes 门 + repository 取数 + touchMemories)
//   在 store 切片(D-085b)组合。

import { toText } from './message.ts';
import type { UIMessage } from './message.ts';
import type {
  MemoryRecallSetting, MemoryRecord,
} from './memory_models.ts';
import { classifyMemoryFreshness } from './memory_time_anchor.ts';

export const USER_ALWAYS_ELIGIBLE_CONFIDENCE: number = 0.70;
const TIME_DECAY_MULTIPLIER: number = 0.35;

export type MemoryRecallFreshness = 'current' | 'time-decayed';

export interface MemoryRecallScore {
  value: number;
  reasons: string[];
  freshness: MemoryRecallFreshness;
}

export interface MemoryRecallSelection {
  record: MemoryRecord;
  score: MemoryRecallScore;
}

// toDebugText(:216-217):"score=%.1f, reasons=r1|r2, freshness=wire"
export const memoryRecallScoreToDebugText = (score: MemoryRecallScore): string =>
  `score=${score.value.toFixed(1)}, reasons=${score.reasons.join('|')}, freshness=${score.freshness}`;

// tokenize(:155-167):小写 → 词元(len 2..48)+ CJK 4 字窗(step 2,取 80)→ 并集取 160
export const tokenizeMemoryQuery = (text: string): Set<string> => {
  const normalized: string = text.toLowerCase();
  const wordTerms: Set<string> = new Set<string>();
  const wordRegex: RegExp = /[\p{L}\p{N}_-]{2,}/gu;
  let match: RegExpExecArray | null = wordRegex.exec(normalized);
  while (match !== null) {
    if (match[0].length <= 48) wordTerms.add(match[0]);
    match = wordRegex.exec(normalized);
  }
  // compact = 仅字母/数字(for...of 按码点迭代,对齐 Kotlin Char 序列 BMP 外差异登记:
  //   Kotlin Char 为 UTF-16 code unit,代理对会被拆开;JS for...of 按码点 — 对
  //   CJK(BMP 内)无差异,对 emoji 有差异但 emoji 非字母数字均被滤除,结果一致)
  let compact: string = '';
  for (const ch of normalized) {
    if (/[\p{L}\p{N}]/u.test(ch)) compact += ch;
  }
  const out: Set<string> = new Set<string>(wordTerms);
  if (compact.length >= 4) {
    // windowed(size=4, step=2, partialWindows=false).take(80)
    let taken: number = 0;
    for (let i = 0; i + 4 <= compact.length && taken < 80; i += 2) {
      out.add(compact.slice(i, i + 4));
      taken += 1;
    }
  }
  // (wordTerms + cjkHints).take(160) — LinkedHashSet 序:词元先,CJK 后
  const capped: Set<string> = new Set<string>();
  let count: number = 0;
  for (const term of out) {
    if (count >= 160) break;
    capped.add(term);
    count += 1;
  }
  return capped;
};

// alwaysEligibleReasons(:169-180)
const alwaysEligibleReasons = (record: MemoryRecord): string[] => {
  const reasons: string[] = [];
  if (record.pinned) reasons.push('pinned');
  if (record.scope === 'core') reasons.push('core');
  if (record.kind === 'feedback') reasons.push('feedback');
  if (record.scope === 'long_term' && record.kind === 'user' &&
    record.confidence >= USER_ALWAYS_ELIGIBLE_CONFIDENCE) {
    reasons.push('durable-user');
  }
  return reasons;
};

// score(:97-153)
export const scoreMemoryRecord = (
  record: MemoryRecord,
  terms: Set<string>,
  currentText: string,
  now: number,
): MemoryRecallScore => {
  const reasons: string[] = [];
  const content: string = record.content.toLowerCase();
  let relevance: number = 0;
  // current-exact:content 含 currentText 小写前 60 字符
  if (currentText.trim().length > 0 &&
    content.indexOf(currentText.toLowerCase().slice(0, 60)) >= 0) {
    relevance += 30.0;
    reasons.push('current-exact');
  }
  for (const term of terms) {
    if (term.length >= 2 && content.indexOf(term) >= 0) {
      relevance += Math.max(2.0, Math.min(term.length, 12));
      if (reasons.indexOf('term-match') < 0) reasons.push('term-match');
    }
  }

  const eligible: string[] = alwaysEligibleReasons(record);
  if (relevance <= 0.0 && eligible.length === 0) {
    return {
      value: 0.0,
      reasons: ['no-match'],
      freshness: 'current',
    };
  }
  for (const r of eligible) reasons.push(r);

  let score: number = 0.0;
  if (record.pinned) score += 100.0;
  switch (record.kind) {
    case 'feedback': score += 52.0; break;
    case 'user': score += 44.0; break;
    case 'project': score += 24.0; break;
    case 'routine': score += 18.0; break;
    case 'reference': score += 12.0; break;
    case 'note': score += 6.0; break;
  }
  switch (record.scope) {
    case 'core': score += 26.0; break;
    case 'long_term': score += 18.0; break;
    case 'short_term': score += 14.0; break;
  }
  score += relevance;
  if (record.lastUsedAt !== null) {
    const ageDays: number = Math.max(now - record.lastUsedAt, 0) / 86400000.0;
    score += Math.min(8.0 / (1.0 + ageDays), 8.0);
  }
  const updateAgeDays: number = Math.max(now - record.updatedAt, 0) / 86400000.0;
  score += Math.min(10.0 / (1.0 + updateAgeDays), 10.0);
  const freshness: MemoryRecallFreshness =
    classifyMemoryFreshness(record.content, now) === 'current' ? 'current' : 'time-decayed';
  if (freshness === 'time-decayed') {
    score *= TIME_DECAY_MULTIPLIER;
    reasons.push('time-decayed');
  }
  // confidence.coerceIn(0.1f, 1f)
  score *= Math.min(Math.max(record.confidence, 0.1), 1);
  // reasons.distinct()(保序去重)
  const distinct: string[] = [];
  for (const r of reasons) {
    if (distinct.indexOf(r) < 0) distinct.push(r);
  }
  return {
    value: score,
    reasons: distinct,
    freshness,
  };
};

// takeBudget(:182-195):cost = content.length + 32;首条恒收,后续超预算跳过;
//   达 maxItems 即停
const takeRecallBudget = (
  sorted: MemoryRecallSelection[],
  maxItems: number,
  maxChars: number,
): MemoryRecallSelection[] => {
  const selected: MemoryRecallSelection[] = [];
  let used: number = 0;
  for (const selection of sorted) {
    const cost: number = selection.record.content.length + 32;
    if (selected.length > 0 && used + cost > maxChars) continue;
    selected.push(selection);
    used += cost;
    if (selected.length >= maxItems) break;
  }
  return selected;
};

// rankRecords(:71-95)
export const rankMemoryRecords = (
  recallSetting: MemoryRecallSetting,
  messages: UIMessage[],
  records: MemoryRecord[],
  now: number = Date.now(),
): MemoryRecallSelection[] => {
  // takeLast(16)(>0,slice 等价)+ joinToString("\n"){ toText() }
  const queryText: string = messages.slice(-16)
    .map((m: UIMessage): string => toText(m))
    .join('\n');
  const currentText: string = messages.length > 0 ? toText(messages[messages.length - 1]) : '';
  const terms: Set<string> = tokenizeMemoryQuery(`${currentText}\n${queryText}`);
  const maxItems: number = Math.min(Math.max(recallSetting.maxItems, 1), 40);
  const maxChars: number = Math.min(Math.max(recallSetting.maxPromptChars, 256), 12000);

  const scored: MemoryRecallSelection[] = [];
  for (const record of records) {
    scored.push({ record, score: scoreMemoryRecord(record, terms, currentText, now) });
  }
  const filtered: MemoryRecallSelection[] = scored.filter(
    (s: MemoryRecallSelection): boolean => s.score.value > 0 || terms.size === 0);
  // sortedWith:pinned desc → score desc → updatedAt desc(JS sort 稳定 ≡ Kotlin sortedWith)
  const sorted: MemoryRecallSelection[] = [...filtered].sort(
    (a: MemoryRecallSelection, b: MemoryRecallSelection): number => {
      if (a.record.pinned !== b.record.pinned) return a.record.pinned ? -1 : 1;
      if (b.score.value !== a.score.value) return b.score.value - a.score.value;
      return b.record.updatedAt - a.record.updatedAt;
    });
  return takeRecallBudget(sorted, maxItems, maxChars);
};
