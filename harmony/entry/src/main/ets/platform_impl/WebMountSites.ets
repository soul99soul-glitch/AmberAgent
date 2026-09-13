// WebMountSites — 用户站点注册表(WebMount Stations)
// Android 基准: usersites/UserSiteRegistry(SharedPreferences + 域匹配);
// 鸿蒙 MVP:KV 'webmount_sites' JSON 数组 + hostname 精确/子域匹配。
// 站点是 WebMount 的授权边界:wm_open / 每次导航 / 每个页面工具都按此核验。
//
// L02 修复:不再用自制正则 split(':') 取 authority(会把 userinfo 当 host,并放行
//   javascript:/file: 等非 HTTP scheme)。统一走官方 url.URL.parseURL
//   (@ohos.url,API 9,ArkTS SystemCapability.Utils.Lang),只允许 http/https、
//   拒绝 userinfo、hostname 转小写规范化后再做精确/子域匹配。

import type { KeyValueStore, JsonObject } from '@amber/chat-domain';
import { newId } from '@amber/chat-domain';
import { url } from '@kit.ArkTS';

export interface UserSiteEntry {
  id: string;
  name: string;
  url: string;   // 站点首页;域匹配取 hostname
}

const SITES_KEY: string = 'webmount_sites';

// 站点变更通知(P2-1):所有写入的唯一收口是 saveWebMountSites,成功后同步回调,
// 让当前 session 立即换 gate 并在当前 URL 失权时复位——无需等下一次工具调用。
// 单槽轻量回调,不是 eventbus;Sites 不反向 import Session(无循环)。
export type WebMountSitesListener = (sites: UserSiteEntry[]) => void;
let sitesListener: WebMountSitesListener | null = null;

export const setWebMountSitesListener = (listener: WebMountSitesListener | null): void => {
  sitesListener = listener;
};

// 内置站点(UserSiteRegistry.kt SEED_SITES 对齐,id 同名;登录态/OAuth 为后续梯队)
const seedSite = (id: string, name: string, url: string): UserSiteEntry =>
  ({ id: id, name: name, url: url });

export const SEED_SITES: UserSiteEntry[] = [
  seedSite('hackernews', 'Hacker News', 'https://news.ycombinator.com'),
  seedSite('reddit', 'Reddit', 'https://www.reddit.com'),
  seedSite('github', 'GitHub', 'https://github.com/login'),
  seedSite('bilibili', 'Bilibili', 'https://passport.bilibili.com/login'),
  seedSite('x_com', 'X.com', 'https://x.com/i/flow/login'),
  seedSite('weibo', '微博', 'https://m.weibo.cn'),
  seedSite('juejin', '掘金', 'https://juejin.cn/login'),
  seedSite('zhihu', '知乎', 'https://www.zhihu.com/signin'),
  seedSite('feishu_docs', '飞书云文档', 'https://www.feishu.cn/wiki'),
];

export const isSeedSite = (id: string): boolean =>
  SEED_SITES.some((s: UserSiteEntry): boolean => s.id === id);

// URL 规范化解析结果(L02)。标准解析 + 授权前置校验,失败即拒绝(fail closed)。
export interface WebMountUrlCheck {
  ok: boolean;
  url: string;        // 规范化后的绝对 URL(ok=false 时为空串)
  hostname: string;   // 小写 hostname(不含端口;IPv6 保留方括号)
  error: string;
}

const rejectUrl = (error: string): WebMountUrlCheck =>
  ({ ok: false, url: '', hostname: '', error: error });

// 官方标准解析(url.URL.parseURL,@ohos.url API 9):http/https、无 userinfo、有 hostname。
// 任何异常/不合法输入都返回 ok=false,调用方据此拒绝。
export const checkWebMountUrl = (raw: string): WebMountUrlCheck => {
  const text: string = raw.trim();
  if (text.length === 0) return rejectUrl('empty url');
  let parsed: url.URL;
  try {
    parsed = url.URL.parseURL(text);
  } catch (e) {
    return rejectUrl('unparsable url');
  }
  const protocol: string = parsed.protocol.toLowerCase();
  if (protocol !== 'http:' && protocol !== 'https:') {
    return rejectUrl(`scheme not allowed: ${protocol}`);
  }
  // userinfo 一律拒绝(即使非空也只是 UI 展示;host 授权必须对应真实 authority 主机)
  if (parsed.username.length > 0 || parsed.password.length > 0) {
    return rejectUrl('userinfo not allowed in url');
  }
  let hostname: string = parsed.hostname.toLowerCase();
  // 末尾单点(example.com.)等价同主机;统一去掉后再比较
  if (hostname.endsWith('.')) hostname = hostname.slice(0, hostname.length - 1);
  if (hostname.length === 0) return rejectUrl('missing hostname');
  return { ok: true, url: parsed.href, hostname: hostname, error: '' };
};

// 恢复缺失的内置站点(restoreMissingSeeds 对齐:只补内置,不动用户自建/删除选择
// 之外的现状;返回恢复数)
export const restoreMissingSeedSites = async (kv: KeyValueStore): Promise<number> => {
  const current: UserSiteEntry[] = await loadWebMountSites(kv);
  const missing: UserSiteEntry[] = SEED_SITES.filter(
    (s: UserSiteEntry): boolean => !current.some((c: UserSiteEntry): boolean => c.id === s.id));
  if (missing.length === 0) return 0;
  await saveWebMountSites(kv, [...current, ...missing]);
  return missing.length;
};

// 读取:空库(未初始化)→ 种子落库后返回。可解析 JSON 数组即权威,
// 含空数组(用户清空列表后不复活);仅 null/坏数据才重种。
export const loadWebMountSites = async (kv: KeyValueStore): Promise<UserSiteEntry[]> => {
  try {
    const raw: string | null = await kv.get(SITES_KEY);
    if (raw !== null) {
      const parsed: UserSiteEntry[] = JSON.parse(raw) as UserSiteEntry[];
      if (Array.isArray(parsed)) return parsed;
    }
  } catch (_e) {
    // 读取失败/解码失败 → 重新种子
  }
  await saveWebMountSites(kv, SEED_SITES);
  return SEED_SITES;
};

export const saveWebMountSites = async (kv: KeyValueStore, sites: UserSiteEntry[]): Promise<void> => {
  await kv.put(SITES_KEY, JSON.stringify(sites));
  // 持久化成功后才通知(失败不改变现有授权)
  if (sitesListener !== null) sitesListener(sites);
};

export const makeUserSite = (name: string, url: string): UserSiteEntry => ({
  id: newId(),
  name: name.trim(),
  url: url.trim(),
});

// url 的 hostname;仅接受 http/https 且无 userinfo,否则返回空串(fail closed)
export const hostOf = (urlText: string): string => {
  const check: WebMountUrlCheck = checkWebMountUrl(urlText);
  return check.ok ? check.hostname : '';
};

// hostname 是否命中站点 host(相等或为子域)
export const hostMatchesSite = (hostname: string, siteHost: string): boolean =>
  siteHost.length > 0 && (hostname === siteHost || hostname.endsWith(`.${siteHost}`));

// 授权判定:url 标准解析后命中任一站点(hostname 相等或子域)
export const siteAllowsUrl = (sites: UserSiteEntry[], rawUrl: string): boolean => {
  const check: WebMountUrlCheck = checkWebMountUrl(rawUrl);
  if (!check.ok) return false;
  for (const s of sites) {
    if (hostMatchesSite(check.hostname, hostOf(s.url))) return true;
  }
  return false;
};

// 同步导航门:onLoadIntercept 每次主框架导航调用(标准解析 + hostname 授权)。
// 预先解析站点 hostname 列表,回调内不再做 JSON/IO,适合同步拦截热路径。
export type WebMountUrlGate = (url: string) => boolean;

export const makeSiteGate = (sites: UserSiteEntry[]): WebMountUrlGate => {
  const allowedHosts: string[] = [];
  for (const s of sites) {
    const h: string = hostOf(s.url);
    if (h.length > 0) allowedHosts.push(h);
  }
  return (candidate: string): boolean => {
    const check: WebMountUrlCheck = checkWebMountUrl(candidate);
    if (!check.ok) return false;
    for (const h of allowedHosts) {
      if (hostMatchesSite(check.hostname, h)) return true;
    }
    return false;
  };
};

// 校验失败时的引导文案(列出已注册站点)
export const sitesDeniedMessage = (sites: UserSiteEntry[], url: string): string =>
  JSON.stringify({
    ok: false,
    error: `URL not allowed by WebMount Stations: ${url}`,
    hint: 'Ask the user to add this site on the WebMount settings page, or add it via wm_site_add (requires approval).',
    allowed_sites: sites.map((s: UserSiteEntry): JsonObject =>
      ({ id: s.id, name: s.name, url: s.url } as JsonObject)),
  } as JsonObject);
