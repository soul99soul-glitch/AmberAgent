// memory_time_anchor — MemoryTimeAnchorParser.kt 逐字移植
// Android 锚点:core/memory/time/MemoryTimeAnchorParser.kt 全文
// 时区语义:ZoneId.systemDefault → JS Date 本地时区(getFullYear/getMonth/getDate
//   与 new Date(y,m,d) 构造);设备时区即系统时区,语义等价。

export type MemoryFreshness = 'current' | 'time_decayed';

// ===== 本地日期工具(ZoneId.systemDefault 语义)=====

interface LocalYmd {
  y: number;
  m: number; // 1-12
  d: number; // 1-31
}

const localYmdOf = (nowMs: number): LocalYmd => {
  const date: Date = new Date(nowMs);
  return { y: date.getFullYear(), m: date.getMonth() + 1, d: date.getDate() };
};

const cmpYmd = (a: LocalYmd, b: LocalYmd): number => {
  if (a.y !== b.y) return a.y - b.y;
  if (a.m !== b.m) return a.m - b.m;
  return a.d - b.d;
};

const daysInMonth = (y: number, m: number): number => new Date(y, m, 0).getDate();

// ===== MemoryTimeAnchor(:76-97)=====

interface MemoryTimeAnchor {
  y: number;
  m: number;
  d: number | null; // null = 仅年月锚
}

// LocalDate.of / YearMonth.of 校验失败 → null(runCatching.getOrNull :88-95)
const anchorFrom = (year: number, month: number, day: number | null): MemoryTimeAnchor | null => {
  if (!Number.isInteger(year) || !Number.isInteger(month)) return null;
  if (month < 1 || month > 12) return null;
  if (day !== null) {
    if (!Number.isInteger(day) || day < 1 || day > daysInMonth(year, month)) return null;
  }
  return { y: year, m: month, d: day };
};

// isBefore(:80-81):date?.isBefore(today) ?: month.isBefore(currentMonth)
const anchorIsBefore = (anchor: MemoryTimeAnchor, today: LocalYmd): boolean => {
  if (anchor.d !== null) {
    return cmpYmd({ y: anchor.y, m: anchor.m, d: anchor.d }, today) < 0;
  }
  if (anchor.y !== today.y) return anchor.y < today.y;
  return anchor.m < today.m;
};

// expiresAtMillis(:83-87):date+1d startOfDay / month+1 首日 startOfDay(本地时区)
const anchorExpiresAtMillis = (anchor: MemoryTimeAnchor): number => {
  if (anchor.d !== null) {
    return new Date(anchor.y, anchor.m - 1, anchor.d + 1).getTime();
  }
  return new Date(anchor.y, anchor.m, 1).getTime();
};

// ===== 正则(:68-73)=====
const ABSOLUTE_DATE_REGEX: RegExp = /\b((?:19|20)\d{2})-(\d{1,2})(?:-(\d{1,2}))?\b/g;
const CHINESE_DATE_REGEX: RegExp = /((?:19|20)\d{2})年(\d{1,2})月(?:(\d{1,2})日)?/g;
const ENGLISH_FUTURE_REGEX: RegExp =
  /\b(?:will|plan|plans|planned|planning|going\s+to|trip|travel|visit)\b/;

// anchors(:33-46):absolute 全量 + chinese 全量(顺序保持)
const anchors = (content: string): MemoryTimeAnchor[] => {
  const out: MemoryTimeAnchor[] = [];
  ABSOLUTE_DATE_REGEX.lastIndex = 0;
  let match: RegExpExecArray | null = ABSOLUTE_DATE_REGEX.exec(content);
  while (match !== null) {
    const year: number = Number.parseInt(match[1], 10);
    const month: number = Number.parseInt(match[2], 10);
    const dayRaw: string | undefined = match[3];
    const day: number | null =
      dayRaw !== undefined && dayRaw.length > 0 ? Number.parseInt(dayRaw, 10) : null;
    const anchor: MemoryTimeAnchor | null = anchorFrom(year, month, day);
    if (anchor !== null) out.push(anchor);
    match = ABSOLUTE_DATE_REGEX.exec(content);
  }
  CHINESE_DATE_REGEX.lastIndex = 0;
  match = CHINESE_DATE_REGEX.exec(content);
  while (match !== null) {
    const year: number = Number.parseInt(match[1], 10);
    const month: number = Number.parseInt(match[2], 10);
    const dayRaw: string | undefined = match[3];
    const day: number | null =
      dayRaw !== undefined && dayRaw.length > 0 ? Number.parseInt(dayRaw, 10) : null;
    const anchor: MemoryTimeAnchor | null = anchorFrom(year, month, day);
    if (anchor !== null) out.push(anchor);
    match = CHINESE_DATE_REGEX.exec(content);
  }
  return out;
};

// hasFutureIntent(:48-55)
const CHINESE_FUTURE_HINTS: readonly string[] = Object.freeze([
  '计划', '打算', '准备', '要去', '将去', '行程', '旅行安排', '出差安排', '旅行',
]);

const hasFutureIntent = (content: string): boolean => {
  const lower: string = content.toLowerCase();
  for (const hint of CHINESE_FUTURE_HINTS) {
    if (lower.indexOf(hint) >= 0) return true;
  }
  return ENGLISH_FUTURE_REGEX.test(lower);
};

// hasHistoricalIntent(:57-64)
const HISTORICAL_HINTS: readonly string[] = Object.freeze([
  '去过', '去了', '已去', '已经去', '回来', '结束',
  'visited', 'went to', 'traveled to', 'have been', 'has been',
]);

const hasHistoricalIntent = (content: string): boolean => {
  const lower: string = content.toLowerCase();
  for (const hint of HISTORICAL_HINTS) {
    if (lower.indexOf(hint) >= 0) return true;
  }
  return false;
};

// classifyFreshness(:9-18)
export const classifyMemoryFreshness = (
  content: string, now: number = Date.now(),
): MemoryFreshness => {
  if (!hasFutureIntent(content) || hasHistoricalIntent(content)) {
    return 'current';
  }
  const today: LocalYmd = localYmdOf(now);
  for (const anchor of anchors(content)) {
    if (anchorIsBefore(anchor, today)) return 'time_decayed';
  }
  return 'current';
};

// deriveExpiresAt(:20-31):未来意图且非历史 → 未过期锚中最早 expiresAt
export const deriveMemoryExpiresAt = (
  content: string, now: number = Date.now(),
): number | null => {
  if (!hasFutureIntent(content) || hasHistoricalIntent(content)) return null;
  const today: LocalYmd = localYmdOf(now);
  let best: number | null = null;
  for (const anchor of anchors(content)) {
    if (anchorIsBefore(anchor, today)) continue;
    const expiresAt: number = anchorExpiresAtMillis(anchor);
    if (best === null || expiresAt < best) best = expiresAt;
  }
  return best;
};
