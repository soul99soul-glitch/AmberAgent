// health_summary — 健康只读聚合（Android HealthSummaryModels 对齐）
//
// 纯 reducer：记录数组 → 今日 + 7 日汇总；entry 注入 Health Connect / 运动健康读数。
// 隐私：结果不含原始轨迹，仅聚合数字；不进对话默认上下文。

export type HealthRecordType = 'steps' | 'heart_rate' | 'sleep' | 'weight';

export interface HealthMetricRecord {
  type: HealthRecordType;
  startEpochMillis: number;
  endEpochMillis: number;
  value: number;
}

export interface HealthDailySummary {
  date: string; // YYYY-MM-DD
  steps: number;
  heartRateBpm: number | null;
  sleepHours: number;
  weightKg: number | null;
}

export interface HealthWeeklySummary {
  steps: number;
  averageHeartRateBpm: number | null;
  sleepHours: number;
  latestWeightKg: number | null;
}

export interface HealthSummary {
  days: HealthDailySummary[];
  weekly: HealthWeeklySummary;
}

const dayKey = (epochMs: number): string => {
  const d = new Date(epochMs);
  const y = d.getFullYear();
  const m = `${d.getMonth() + 1}`.padStart(2, '0');
  const day = `${d.getDate()}`.padStart(2, '0');
  return `${y}-${m}-${day}`;
};

const lastNDayKeys = (nowMs: number, n: number): string[] => {
  const keys: string[] = [];
  for (let i = n - 1; i >= 0; i--) {
    keys.push(dayKey(nowMs - i * 86400000));
  }
  return keys;
};

export const buildHealthSummary = (
  records: HealthMetricRecord[], nowMs: number = Date.now(),
): HealthSummary => {
  const days = lastNDayKeys(nowMs, 7);
  const byDay = new Map<string, { steps: number; hrSum: number; hrN: number; sleepMs: number; weight: number | null }>();
  for (const key of days) {
    byDay.set(key, { steps: 0, hrSum: 0, hrN: 0, sleepMs: 0, weight: null });
  }
  for (const rec of records) {
    const key = dayKey(rec.startEpochMillis);
    const slot = byDay.get(key);
    if (slot === undefined) continue;
    if (rec.type === 'steps') slot.steps += Math.max(0, Math.round(rec.value));
    else if (rec.type === 'heart_rate') {
      if (rec.value > 0) {
        slot.hrSum += rec.value;
        slot.hrN += 1;
      }
    } else if (rec.type === 'sleep') {
      slot.sleepMs += Math.max(0, rec.endEpochMillis - rec.startEpochMillis);
    } else if (rec.type === 'weight') {
      slot.weight = rec.value;
    }
  }
  const daily: HealthDailySummary[] = days.map((key): HealthDailySummary => {
    const slot = byDay.get(key) as { steps: number; hrSum: number; hrN: number; sleepMs: number; weight: number | null };
    return {
      date: key,
      steps: slot.steps,
      heartRateBpm: slot.hrN > 0 ? Math.round((slot.hrSum / slot.hrN) * 10) / 10 : null,
      sleepHours: Math.round((slot.sleepMs / 3600000) * 100) / 100,
      weightKg: slot.weight,
    };
  });
  let steps = 0;
  let hrSum = 0;
  let hrN = 0;
  let sleepMs = 0;
  let latestWeight: number | null = null;
  for (const rec of records) {
    const key = dayKey(rec.startEpochMillis);
    if (!days.includes(key)) continue;
    if (rec.type === 'steps') steps += Math.max(0, Math.round(rec.value));
    else if (rec.type === 'heart_rate' && rec.value > 0) {
      hrSum += rec.value;
      hrN += 1;
    } else if (rec.type === 'sleep') {
      sleepMs += Math.max(0, rec.endEpochMillis - rec.startEpochMillis);
    } else if (rec.type === 'weight') {
      latestWeight = rec.value;
    }
  }
  return {
    days: daily,
    weekly: {
      steps,
      averageHeartRateBpm: hrN > 0 ? Math.round((hrSum / hrN) * 10) / 10 : null,
      sleepHours: Math.round((sleepMs / 3600000) * 100) / 100,
      latestWeightKg: latestWeight,
    },
  };
};

export const healthSummaryToolJson = (summary: HealthSummary): string =>
  JSON.stringify({
    ok: true,
    tool: 'health_summary',
    privacy: 'user_authorized_health_data',
    days: summary.days,
    weekly: summary.weekly,
  });
