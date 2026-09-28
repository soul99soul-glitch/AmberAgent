// webmount/url — 纯 domain 的最小 URL 工具(E12 冻结合同:小而显式测试,非 URL 框架)
//
// Harmony 纯 domain 不假设全局 URL;平台侧标准解析(@kit.ArkTS url)仍是
// 授权/重定向的权威。这里只提供 origin 恒等、展示脱敏与 Android NetworkLog
// 对齐的 mutation-like 判定,供 HAR/template 投影与 replay 资格使用。

// scheme://host[:port],http/https 且无 userinfo;非法输入返回 null(fail closed)
export const webMountOriginOf = (raw: string): string | null => {
  const match: RegExpExecArray | null = /^(https?):\/\/([a-z0-9.-]+|\[[0-9a-f:]+\])(:\d{1,5})?(?:[/?#]|$)/i.exec(raw.trim());
  if (match === null) return null;
  const host: string = match[2].toLowerCase();
  if (host.length === 0 || host.startsWith('.') || host.endsWith('.') || host.includes('..')) return null;
  return `${match[1].toLowerCase()}://${host}${match[3] ?? ''}`;
};

// 绝对 http/https URL(无 userinfo);path/query 原样保留
export const isWebMountAbsoluteHttpUrl = (raw: string): boolean =>
  webMountOriginOf(raw) !== null;

// path + 去值 query 名(<redacted>),用于 model/UI 展示;绝不回显 query 值
export const webMountRedactedDisplayUrl = (raw: string): string => {
  const withoutHash: string = raw.split('#')[0];
  const qIndex: number = withoutHash.indexOf('?');
  const base: string = qIndex < 0 ? withoutHash : withoutHash.substring(0, qIndex);
  if (qIndex < 0) return base;
  const names: string[] = [];
  for (const pair of withoutHash.substring(qIndex + 1).split('&')) {
    const name: string = pair.split('=')[0];
    if (name.length > 0 && !names.includes(name)) names.push(name);
  }
  if (names.length === 0) return base;
  return `${base}?${names.map((name: string): string => `${name}=<redacted>`).join('&')}`;
};

// Android NetworkLog.MUTATING_PATH_HINTS 逐字对齐
const MUTATING_PATH_HINTS: string[] = [
  'logout', 'signout', 'delete', 'remove', 'mark_read', 'mark-read', 'vote', 'like',
  'follow', 'unfollow', 'subscribe', 'unsubscribe', 'create', 'update', 'edit', 'publish',
  'send', 'archive', 'cancel', 'join', 'leave', 'star', 'pin', 'enable', 'disable',
  'mutate', 'mutation', 'write', 'save', 'checkout', 'purchase', 'payment', 'pay',
  'submit', 'confirm',
];
const MUTATING_QUERY_KEYS: string[] = ['action', 'op', 'cmd', 'command', 'mutation', 'method'];

// Android isProbablyMutatingReplayUrl 对齐:path+query token 命中 hint,或
// 动作类 query 键的值命中 hint
export const isWebMountMutatingReplayUrl = (raw: string): boolean => {
  const lowered: string = raw.toLowerCase();
  const haystack: string = lowered.split('#')[0];
  const tokens: string[] = haystack.match(/[a-z0-9_-]+/g) ?? [];
  if (tokens.some((token: string): boolean =>
    MUTATING_PATH_HINTS.some((hint: string): boolean => token.includes(hint)))) return true;
  const qIndex: number = haystack.indexOf('?');
  if (qIndex < 0) return false;
  for (const pair of haystack.substring(qIndex + 1).split('&')) {
    const key: string = pair.split('=')[0];
    const value: string = pair.includes('=') ? pair.substring(pair.indexOf('=') + 1) : '';
    if (MUTATING_QUERY_KEYS.includes(key)
      && MUTATING_PATH_HINTS.some((hint: string): boolean => value.includes(hint))) return true;
  }
  return false;
};
