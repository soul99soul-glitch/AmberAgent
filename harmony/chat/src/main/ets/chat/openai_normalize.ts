// normalizeOpenAIStreamDataLines — ChatCompletionsAPI.kt:72-98 的忠实移植
//
// OkHttp EventSource 已按 SSE 语义把同一 event 的多行 data 用 \n 合并成一个字符串。
// 标准语义下整个 data 就是一个 payload;服务端把单个 JSON 拆成多行 data 是合法的,
// 无脑按行拆会把它拆碎。因此:多行时先尝试整体作为一个 JSON payload,失败才退回
// 按行拆分(兼容嵌套 data: 前缀的 wrapper 和单 event 多 JSON 行的异常 provider)。

// withoutNestedSseDataPrefix(ChatCompletionsAPI.kt:92-98):逐行剥离嵌套 data: 前缀
const withoutNestedSseDataPrefix = (value: string): string => {
  let v: string = value.trimStart();
  while (v.startsWith('data:')) {
    v = v.slice(5).trimStart();
  }
  return v;
};

export const normalizeOpenAIStreamDataLines = (data: string): string[] => {
  const lines: string[] = data
    .split('\n')
    .map((l: string): string => withoutNestedSseDataPrefix(l.trim()))
    .filter((l: string): boolean => l.length > 0 && l !== '[DONE]');
  if (lines.length <= 1) return lines;
  const joined: string = lines.join('\n');
  try {
    JSON.parse(joined);
    return [joined];
  } catch (_e) {
    return lines;
  }
};
