// format_number — 数字 K/M/B 缩写 + 上下文 token 显示格式(D-110)
//
// Android 基准:
//   core/utils/StringUtils.kt:56-87(Int.formatNumber — <1000 原样;
//     <1e6 → XK / X.XK;<1e9 → XM / X.XM;否则 XB / X.XB;整数档无小数,
//     非整档 toFixed(1);负号前置)
//   feature/ui/components/ai/ChatInputUsage.kt:164-168
//     (formatContextTokens:≤0 → '0';<1000 → '<1K';否则 formatNumber)
// 注:Kotlin toFixed = HALF_UP;JS toFixed 在典型引擎同为就近/远离零,
//   半值边界(如 2.65)可能有末位差异 — 显示级影响,登记不处理

// Int.formatNumber(StringUtils.kt:56-87 逐字)
export const formatNumberInt = (value: number): string => {
  const v: number = Math.trunc(value);
  const absValue: number = Math.abs(v);
  const sign: string = v < 0 ? '-' : '';
  if (absValue < 1000) return String(v);
  if (absValue < 1000000) {
    const x: number = absValue / 1000.0;
    return Number.isInteger(x) ? `${sign}${x}K` : `${sign}${x.toFixed(1)}K`;
  }
  if (absValue < 1000000000) {
    const x: number = absValue / 1000000.0;
    return Number.isInteger(x) ? `${sign}${x}M` : `${sign}${x.toFixed(1)}M`;
  }
  const x: number = absValue / 1000000000.0;
  return Number.isInteger(x) ? `${sign}${x}B` : `${sign}${x.toFixed(1)}B`;
};

// formatContextTokens(ChatInputUsage.kt:164-168 逐字)
export const formatContextTokens = (tokens: number): string => {
  const t: number = Math.trunc(tokens);
  if (t <= 0) return '0';
  if (t < 1000) return '<1K';
  return formatNumberInt(t);
};
