// BalanceSupport — Provider 账户余额查询(D3-1;ai/ProviderBalanceText.kt getBalance 对齐)
//
// GET {baseUrl}{apiPath}(Authorization: Bearer)→ JSON 按 resultPath 取值:
//   支持 'a.b.c' 点路径、'arr[0].x' 数组下标、'a.b - c.d' 减法(OpenRouter credits)。
//   2 分钟内存缓存(Android 同款);任何失败 → '~'(诚实降级,不造数字)。
import { http } from '@kit.NetworkKit';
import type { ProviderSetting } from '@amber/chat-domain';
import { providerBalanceRequestIdentity } from '@amber/chat-domain';

const CACHE_TTL_MS: number = 120000;

interface BalanceCacheEntry {
  identity: string;
  value: string;
  at: number;
}

const cache: Map<string, BalanceCacheEntry> = new Map<string, BalanceCacheEntry>();

// 单段解析:'name' 或 'name[0]'
const navigateSegment = (cur: Object, segment: string): Object | null => {
  const bracket: number = segment.indexOf('[');
  const key: string = bracket >= 0 ? segment.substring(0, bracket) : segment;
  let next: Object = (cur as Record<string, Object>)[key];
  if (next === undefined || next === null) return null;
  if (bracket >= 0) {
    const idxEnd: number = segment.indexOf(']', bracket);
    const idx: number = parseInt(segment.substring(bracket + 1, idxEnd), 10);
    const arr: Object[] = next as Object[];
    if (Number.isNaN(idx) || idx < 0 || idx >= arr.length) return null;
    next = arr[idx];
  }
  return next;
};

// 'data.total_credits' 点路径取值(失败 → null)
const navigatePath = (root: Object, path: string): number | null => {
  let cur: Object = root;
  for (const seg of path.split('.')) {
    const next: Object | null = navigateSegment(cur, seg.trim());
    if (next === null) return null;
    cur = next;
  }
  const v: number = typeof cur === 'number' ? cur as number : parseFloat(String(cur));
  return Number.isNaN(v) ? null : v;
};

const formatBalance = (v: number): string => {
  const fixed: string = v.toFixed(2);
  return fixed.replace(/\.?0+$/, '');
};

const resolveBalance = (root: Object, resultPath: string): string | null => {
  // 减法表达式:'data.total_credits - data.total_usage'
  const minus: number = resultPath.indexOf(' - ');
  if (minus > 0) {
    const a: number | null = navigatePath(root, resultPath.substring(0, minus).trim());
    const b: number | null = navigatePath(root, resultPath.substring(minus + 3).trim());
    if (a === null || b === null) return null;
    return formatBalance(a - b);
  }
  const v: number | null = navigatePath(root, resultPath.trim());
  return v === null ? null : formatBalance(v);
};

export const fetchProviderBalance = async (provider: ProviderSetting): Promise<string> => {
  const opt = provider.balanceOption;
  const providerId: string = provider.id;
  const apiKey: string = provider.apiKey;
  const resultPath: string = opt.resultPath;
  if (!opt.enabled || apiKey.length === 0 || opt.apiPath.length === 0) return '~';
  const url: string = `${provider.baseUrl.replace(/\/+$/, '')}${opt.apiPath}`;
  const identity: string = providerBalanceRequestIdentity(provider);
  const cached: BalanceCacheEntry | undefined = cache.get(providerId);
  if (cached !== undefined && cached.identity === identity
    && Date.now() - cached.at < CACHE_TTL_MS) return cached.value;
  let value: string = '~';
  try {
    const req: http.HttpRequest = http.createHttp();
    try {
      const resp: http.HttpResponse = await req.request(url, {
        method: http.RequestMethod.GET,
        header: { 'Authorization': `Bearer ${apiKey}` },
        connectTimeout: 10000,
        readTimeout: 15000,
      });
      if (resp.responseCode >= 200 && resp.responseCode < 300 && typeof resp.result === 'string') {
        const resolved: string | null = resolveBalance(
          JSON.parse(resp.result) as Object, resultPath);
        if (resolved !== null) value = resolved;
      }
    } finally {
      try { req.destroy(); } catch { /* 已销毁 */ }
    }
  } catch (_e) {
    value = '~';
  }
  cache.set(providerId, { identity: identity, value: value, at: Date.now() });
  return value;
};
