// 部分 NetworkKit 实现以空值保存 HTTP 状态行，其他实现只提供普通响应头。
// 底层累计头按字典序提供，不能将 key 顺序当响应顺序。
// 剔除 1xx / 3xx 后只接受唯一状态；歧义交给 requestInStream completion。
export function httpResponseStatusFromHeaders(headers: Record<string, string>): number | null {
  let status: number | null = null;
  for (const key of Object.keys(headers)) {
    const match: RegExpMatchArray | null = key.match(/^HTTP\/\S+\s+(\d{3})(?:\s|$)/i);
    if (match === null) continue;
    const candidate: number = Number(match[1]);
    if (candidate < 200 || (candidate >= 300 && candidate < 400)) continue;
    if (status !== null && status !== candidate) return null;
    status = candidate;
  }
  return status;
}

export function isEventStreamResponse(headers: Record<string, string>): boolean {
  for (const key of Object.keys(headers)) {
    if (key.toLowerCase() === 'content-type') {
      return headers[key].split(';')[0].trim().toLowerCase() === 'text/event-stream';
    }
  }
  return false;
}
