// time_reminder_transformer — 时间提醒注入(D-072)
// Android 基准: app/.../core/ai/transformers/TimeReminderTransformer.kt(全文 77 行)
//   - gate: ctx.settings.agentRuntime.enableTimeReminder(PreferencesStore.kt:156 默认 false)
//   - i>0 且 gap > 3600s(严格大于)→ 在 current 前注入 UIMessage.user(time_reminder)
//   - 文案:"<time_reminder>Current time: $dayOfWeek, $timeStr ($gapText since last message)</time_reminder>"
//   - formatGap:<3600 → N min;<86400 → N h;else N d(Kotlin Long 除法 = 向零截断)
// 偏差:
//   - createdAt 为 UTC+Z ISO 串(PD-004 已登记;Android LocalDateTime+系统时区,
//     同 tz 双减对 gap 抵消,DST 跨界除外)→ Date.parse 直减,解析失败 NaN
//     比较恒 false → 不注入(安全退化,登记)
//   - 时区/.locale 格式化在 entry(formatters 注入,HAR 纯逻辑)
import type { UIMessage } from './message.ts';
import { makeUserMessage } from './message.ts';
import type { MessageTransformer, TransformerContext } from './transformer_pipeline.ts';

export const TIME_GAP_THRESHOLD_SECONDS: number = 3600; // 1 小时

// TextStyle.FULL dayOfWeek(Locale.getDefault())+ LocalDateTime.toString() 由注入实现
export interface TimeReminderFormatters {
  formatDayOfWeek: (epochMs: number) => string;
  formatLocalDateTime: (epochMs: number) => string;
}

export interface TimeReminderDeps extends TimeReminderFormatters {
  // agentRuntime.enableTimeReminder(默认 false;读取时机 = transform 调用时,
  //   对齐 Android settingsFlow.value)
  enabled: () => boolean;
}

// Kotlin Long 除法/inWholeSeconds:向零截断
const truncDiv = (a: number, b: number): number => Math.trunc(a / b);

export const formatGap = (seconds: number): string => {
  if (seconds < 3600) return `${truncDiv(seconds, 60)} min`;
  if (seconds < 86400) return `${truncDiv(seconds, 3600)} h`;
  return `${truncDiv(seconds, 86400)} d`;
};

const buildTimeReminderMessage = (
  gapSeconds: number, currMs: number, fmt: TimeReminderFormatters,
): UIMessage =>
  makeUserMessage(
    `<time_reminder>Current time: ${fmt.formatDayOfWeek(currMs)}, ` +
    `${fmt.formatLocalDateTime(currMs)} (${formatGap(gapSeconds)} since last message)</time_reminder>`);

// applyTimeReminder(:30-55 全文忠实):gap 严格大于阈值 → current 前注入
export const applyTimeReminder = (
  messages: UIMessage[], fmt: TimeReminderFormatters,
): UIMessage[] => {
  const result: UIMessage[] = [];
  for (let i = 0; i < messages.length; i++) {
    const current: UIMessage = messages[i];
    if (i > 0) {
      const prevMs: number = Date.parse(messages[i - 1].createdAt);
      const currMs: number = Date.parse(current.createdAt);
      const gapSeconds: number = Math.trunc((currMs - prevMs) / 1000);
      if (gapSeconds > TIME_GAP_THRESHOLD_SECONDS) {
        result.push(buildTimeReminderMessage(gapSeconds, currMs, fmt));
      }
    }
    result.push(current);
  }
  return result;
};

// transform(:22-28):gate 关闭 → 原引用返回
export const createTimeReminderTransformer = (deps: TimeReminderDeps): MessageTransformer => ({
  transform: (ctx: TransformerContext, messages: UIMessage[]): UIMessage[] => {
    if (!deps.enabled()) return messages;
    return applyTimeReminder(messages, deps);
  },
});

// ===== 格式化助手(HAR 纯逻辑,entry 以 i18n 语言注入;测试确定性锚点) =====

const pad = (n: number, w: number): string => {
  let s: string = String(n);
  while (s.length < w) s = `0${s}`;
  return s;
};

// java.time LocalDateTime.toString() 规则:秒与毫秒均 0 → 省略秒;
//   毫秒 0 → 'ss';否则 'ss.SSS'(毫秒精度恒 3 位,Java nano 3/6/9 组的 3 位档)
//   本地时区(new Date(epochMs) = systemDefault,对齐 Android atZone(systemDefault))
export const javaLocalDateTimeString = (epochMs: number): string => {
  const d: Date = new Date(epochMs);
  const base: string =
    `${d.getFullYear()}-${pad(d.getMonth() + 1, 2)}-${pad(d.getDate(), 2)}` +
    `T${pad(d.getHours(), 2)}:${pad(d.getMinutes(), 2)}`;
  const sec: number = d.getSeconds();
  const ms: number = d.getMilliseconds();
  if (sec === 0 && ms === 0) return base;
  if (ms === 0) return `${base}:${pad(sec, 2)}`;
  return `${base}:${pad(sec, 2)}.${pad(ms, 3)}`;
};

// TextStyle.FULL 英/中全名(偏差登记:Java Locale.getDefault 全语言 → zh/en 二档,
//   entry 以 i18n.System.getSystemLanguage() 选择)
export const DAY_OF_WEEK_FULL_EN: string[] = [
  'Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday',
];
export const DAY_OF_WEEK_FULL_ZH: string[] = [
  '星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六',
];

export const dayOfWeekFullName = (language: string, epochMs: number): string => {
  const names: string[] = language.startsWith('zh') ? DAY_OF_WEEK_FULL_ZH : DAY_OF_WEEK_FULL_EN;
  return names[new Date(epochMs).getDay()];
};
