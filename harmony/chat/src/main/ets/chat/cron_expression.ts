// Five-field cron parsing and timezone-aware next-run calculation.
// The formatter cache belongs to this calculation module.

// ===== CronExpression(全文 106) =====

const CRON_MAX_LOOKAHEAD_DAYS: number = 366;
const MINUTE_MS: number = 60_000;

export interface CronExpression {
  raw: string;
  minutes: Set<number>;
  hours: Set<number>;
  daysOfMonth: Set<number>;
  months: Set<number>;
  daysOfWeek: Set<number>;
  dayOfMonthWildcard: boolean;
  dayOfWeekWildcard: boolean;
}

// Kotlin toIntOrNull 等价:可选负号 + 全数字('01' 合法)
const kotlinToIntOrNull = (raw: string): number | null => {
  if (!/^-?\d+$/.test(raw)) return null;
  const n: number = Number.parseInt(raw, 10);
  return Number.isSafeInteger(n) ? n : null;
};

// :78-103
const cronParseSegment = (segment: string, min: number, max: number): number[] => {
  if (segment.trim().length === 0) throw new Error('Cron segment cannot be blank');
  const slashIdx: number = segment.indexOf('/');
  const rangePart: string = slashIdx < 0 ? segment : segment.substring(0, slashIdx);
  const stepStr: string = slashIdx < 0 ? '1' : segment.substring(slashIdx + 1);
  const step: number | null = kotlinToIntOrNull(stepStr);
  if (step === null) throw new Error(`Invalid cron step in '${segment}'`);
  if (step <= 0) throw new Error(`Cron step must be greater than 0 in '${segment}'`);

  let start: number;
  let end: number;
  if (rangePart === '*') {
    start = min;
    end = max;
  } else if (rangePart.indexOf('-') >= 0) {
    const dashIdx: number = rangePart.indexOf('-');
    const startRaw: string = rangePart.substring(0, dashIdx);
    const endRaw: string = rangePart.substring(dashIdx + 1);
    const s: number | null = kotlinToIntOrNull(startRaw);
    if (s === null) throw new Error(`Invalid cron range start in '${segment}'`);
    const e: number | null = kotlinToIntOrNull(endRaw);
    if (e === null) throw new Error(`Invalid cron range end in '${segment}'`);
    start = s;
    end = e;
  } else {
    const value: number | null = kotlinToIntOrNull(rangePart);
    if (value === null) throw new Error(`Invalid cron value in '${segment}'`);
    start = value;
    end = value;
  }
  if (start < min || start > max || end < min || end > max || start > end) {
    throw new Error(`Cron segment '${segment}' is outside ${min}..${max}`);
  }
  const out: number[] = [];
  for (let v: number = start; v <= end; v += step) out.push(v);
  return out;
};

// :70-76
const cronParseField = (field: string, min: number, max: number): Set<number> => {
  if (field.trim().length === 0) throw new Error('Cron field cannot be blank');
  const values: Set<number> = new Set();
  field.split(',').forEach((rawSegment: string) => {
    cronParseSegment(rawSegment.trim(), min, max).forEach((v: number) => { values.add(v); });
  });
  if (values.size === 0) throw new Error(`Cron field '${field}' produced no values`);
  return values;
};

export const cronExpressionParse = (expression: string): CronExpression => {
  const parts: string[] = expression.trim().split(/\s+/);
  if (parts.length !== 5) {
    throw new Error('Cron expression must have 5 fields: minute hour day-of-month month day-of-week');
  }
  const domWildcard: boolean = parts[2] === '*';
  const dowWildcard: boolean = parts[4] === '*';
  const dowRaw: Set<number> = cronParseField(parts[4], 0, 7);
  const daysOfWeek: Set<number> = new Set();
  dowRaw.forEach((v: number) => { daysOfWeek.add(v === 0 ? 7 : v); });
  return {
    raw: expression.trim(),
    minutes: cronParseField(parts[0], 0, 59),
    hours: cronParseField(parts[1], 0, 23),
    daysOfMonth: cronParseField(parts[2], 1, 31),
    months: cronParseField(parts[3], 1, 12),
    daysOfWeek,
    dayOfMonthWildcard: domWildcard,
    dayOfWeekWildcard: dowWildcard,
  };
};

// ===== 时区本地字段(Intl 承载 ZonedDateTime) =====

interface ZonedParts {
  minute: number;
  hour: number;
  dayOfMonth: number;
  month: number;
  dayOfWeek: number;   // Java MONDAY=1..SUNDAY=7
}

const WEEKDAY_TO_JAVA: Record<string, number> = {
  Mon: 1, Tue: 2, Wed: 3, Thu: 4, Fri: 5, Sat: 6, Sun: 7,
};

const zonedFormatters: Map<string, Intl.DateTimeFormat> = new Map();

const zonedFormatter = (zoneId: string): Intl.DateTimeFormat => {
  let fmt: Intl.DateTimeFormat | undefined = zonedFormatters.get(zoneId);
  if (fmt === undefined) {
    fmt = new Intl.DateTimeFormat('en-US', {
      timeZone: zoneId,
      hourCycle: 'h23',
      year: 'numeric', month: 'numeric', day: 'numeric',
      hour: 'numeric', minute: 'numeric', weekday: 'short',
    });
    zonedFormatters.set(zoneId, fmt);
  }
  return fmt;
};

const zonedPartsOf = (epochMs: number, zoneId: string): ZonedParts => {
  const parts: Intl.DateTimeFormatPart[] = zonedFormatter(zoneId).formatToParts(new Date(epochMs));
  let minute: number = 0;
  let hour: number = 0;
  let dayOfMonth: number = 1;
  let month: number = 1;
  let dayOfWeek: number = 1;
  parts.forEach((part: Intl.DateTimeFormatPart) => {
    if (part.type === 'minute') minute = Number.parseInt(part.value, 10);
    else if (part.type === 'hour') hour = Number.parseInt(part.value, 10);
    else if (part.type === 'day') dayOfMonth = Number.parseInt(part.value, 10);
    else if (part.type === 'month') month = Number.parseInt(part.value, 10);
    else if (part.type === 'weekday') dayOfWeek = WEEKDAY_TO_JAVA[part.value] ?? 1;
  });
  return { minute, hour, dayOfMonth, month, dayOfWeek };
};

// ZoneId.of 校验等价:非法 id 抛错(resolveZone catch 回退)
export const cronIsValidZoneId = (zoneId: string): boolean => {
  try {
    zonedFormatter(zoneId).format(new Date(0));
    return true;
  } catch {
    return false;
  }
};

export const cronSystemZoneId = (): string => {
  try {
    const id: string | undefined = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (id !== undefined && id.length > 0) return id;
  } catch {
    // fallthrough
  }
  return 'UTC';
};

// :32-46
const cronMatches = (expr: CronExpression, parts: ZonedParts): boolean => {
  if (!expr.minutes.has(parts.minute)) return false;
  if (!expr.hours.has(parts.hour)) return false;
  if (!expr.months.has(parts.month)) return false;
  const dayOfMonthMatches: boolean = expr.daysOfMonth.has(parts.dayOfMonth);
  const dayOfWeekMatches: boolean = expr.daysOfWeek.has(parts.dayOfWeek);
  if (!expr.dayOfMonthWildcard && !expr.dayOfWeekWildcard) {
    return dayOfMonthMatches || dayOfWeekMatches;
  }
  return dayOfMonthMatches && dayOfWeekMatches;
};

// :18-30  cursor = epoch+1min 截断至分钟;逐分钟扫至 +366 天
export const cronNextRunAfter = (
  expr: CronExpression, epochMs: number, zoneId: string,
): number | null => {
  let cursor: number = Math.floor(epochMs / MINUTE_MS) * MINUTE_MS + MINUTE_MS;
  const deadline: number = cursor + CRON_MAX_LOOKAHEAD_DAYS * 24 * 60 * MINUTE_MS;
  while (cursor <= deadline) {
    if (cronMatches(expr, zonedPartsOf(cursor, zoneId))) return cursor;
    cursor += MINUTE_MS;
  }
  return null;
};

