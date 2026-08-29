// WebMountSites — 用户站点注册表(WebMount Stations)
// Android 基准: usersites/UserSiteRegistry(SharedPreferences + 域匹配);
// 鸿蒙 MVP:KV 'webmount_sites' JSON 数组 + host 精确/子域匹配。
// 站点是 WebMount 的授权边界:wm_open 仅允许命中已注册站点(工具层校验)。

import type { KeyValueStore, JsonObject } from '@amber/chat-domain';
import { newId } from '@amber/chat-domain';

export interface UserSiteEntry {
  id: string;
  name: string;
  url: string;   // 站点首页;域匹配取 host
}

const SITES_KEY: string = 'webmount_sites';

export const loadWebMountSites = async (kv: KeyValueStore): Promise<UserSiteEntry[]> => {
  try {
    const raw: string | null = await kv.get(SITES_KEY);
    if (raw === null) return [];
    return JSON.parse(raw) as UserSiteEntry[];
  } catch (_e) {
    return [];
  }
};

export const saveWebMountSites = async (kv: KeyValueStore, sites: UserSiteEntry[]): Promise<void> => {
  await kv.put(SITES_KEY, JSON.stringify(sites));
};

export const makeUserSite = (name: string, url: string): UserSiteEntry => ({
  id: newId(),
  name: name.trim(),
  url: url.trim(),
});

// url 的 host;非法输入返回空串
export const hostOf = (url: string): string => {
  const m: RegExpMatchArray | null = url.trim().match(/^[a-z][a-z0-9+.-]*:\/\/([^/?#]+)/i);
  if (m !== null) return m[1].toLowerCase().split(':')[0];
  return '';
};

// 授权判定:url 命中任一站点(host 相等或为站点 host 的子域)
export const siteAllowsUrl = (sites: UserSiteEntry[], url: string): boolean => {
  const host: string = hostOf(url);
  if (host.length === 0) return false;
  for (const s of sites) {
    const siteHost: string = hostOf(s.url) || s.url.trim().toLowerCase();
    if (siteHost.length === 0) continue;
    if (host === siteHost || host.endsWith(`.${siteHost}`)) return true;
  }
  return false;
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
