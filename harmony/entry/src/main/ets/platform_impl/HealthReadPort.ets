// HealthReadPort — 运动健康/Health Connect 等价读数（诚实不可用）
//
// 鸿蒙公开 API 与 Android Health Connect 不一一对应；无可靠只读源时返回
// available=false，工具输出 ok=false，禁止编造步数/心率。

import type { HealthReadPort } from '@amber/chat-domain';

export const createEntryHealthReadPort = (): HealthReadPort => ({
  readRecent: (): Promise<{ available: boolean; records: []; note: string }> =>
    Promise.resolve({
      available: false,
      records: [],
      note: 'HarmonyOS 运动健康只读接口尚未接线（无公开等价 Health Connect 时保持不可用）',
    }),
});
