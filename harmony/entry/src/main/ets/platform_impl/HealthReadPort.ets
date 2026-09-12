// HealthReadPort — 运动健康只读（诚实不可用）

import type { HealthReadPort, HealthMetricRecord } from '@amber/chat-domain';

interface HealthReadResult {
  available: boolean;
  records: HealthMetricRecord[];
  note: string;
}

const unavailableResult = (): HealthReadResult => {
  const records: HealthMetricRecord[] = [];
  return {
    available: false,
    records,
    note: 'HarmonyOS 运动健康只读接口尚未接线',
  };
};

export const createEntryHealthReadPort = (): HealthReadPort => ({
  readRecent: (): Promise<HealthReadResult> => Promise.resolve(unavailableResult()),
});
