// urlAllowedForBackgroundFetch — 私网 gate,防 SSRF
// 照搬 Android DeepReadSourcePrefetcher.kt:343-347 (gate) + ToolRegistry.kt:177-202 (isPrivateNetworkTarget)
//
// Background prefetch must not probe loopback/LAN services on its own. The
// user opts in by enabling both global and high-risk auto-approval — the
// same gate that lets http_request reach private hosts unattended.

// isPrivateNetworkTarget — 照搬 Android ToolRegistry.kt:177-202
// 注意:Android 对 null/blank/解析失败 返回 false(非私网);我们这里 host 解析失败也返回 false,
// 因为无法确定是私网(与 Android URI.host == null → false 行为一致)。
export const isPrivateUrl = (url: string): boolean => {
  const trimmed = url.trim();
  if (trimmed.length === 0) return false;

  // 剥离 userinfo(user@host 的 user 部分,取最后一个 @ 分隔 — 多重 @ 时
  // 只剥第一段仍会污染 host):不剥离时 'attacker@127.0.0.1' 会被当成完整
  // host,IPv4/IPv6 判定失效(SSRF 绕过)
  const schemeMatch: RegExpExecArray | null = /^[a-zA-Z][a-zA-Z0-9+.\-]*:\/\//.exec(trimmed);
  if (schemeMatch !== null) {
    const rest: string = trimmed.substring(schemeMatch[0].length);
    const slash: number = rest.search(/[/?#]/);
    const authorityRaw: string = slash >= 0 ? rest.substring(0, slash) : rest;
    const at: number = authorityRaw.lastIndexOf('@');
    if (at >= 0) {
      const hostPart: string = authorityRaw.substring(at + 1);
      const tailPart: string = slash >= 0 ? rest.substring(slash) : '';
      const rebuilt: string = schemeMatch[0] + hostPart + tailPart;
      return isPrivateUrl(rebuilt);
    }
  }

  let host: string;
  // 从 URL 提取 host(不依赖全局 URL 类,ArkTS 兼容)
  // 支持:protocol://host[:port]/path、protocol://[ipv6][:port]/path
  const bracketM = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/\[([^\]]+)\]/.exec(trimmed);
  const plainM = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\/([^\]/:?]+)/.exec(trimmed);
  const m = bracketM !== null ? bracketM : plainM;
  if (m === null) {
    // 非 URL 格式(含裸 host:port 或无 scheme)→ 视为非私网(照搬 Android runCatching ?: false)
    return false;
  }
  host = m[1].toLowerCase();
  if (host.length === 0) return false;

  // 去除 IPv6 方括号(对应 Android removePrefix("[").removeSuffix("]"))
  const rawHost = host.startsWith('[') && host.endsWith(']')
    ? host.slice(1, -1)
    : host;
  const bare = rawHost.endsWith('.') ? rawHost.slice(0, -1) : rawHost;

  // loopback / mDNS / internal TLD
  if (
    bare === 'localhost' ||
    bare.endsWith('.localhost') ||
    bare.endsWith('.local') ||
    bare.endsWith('.internal') ||
    bare.endsWith('.lan')
  ) {
    return true;
  }

  // IPv6 字面量先展开为八个 16-bit 分组，等价写法共享同一分类。
  if (bare.includes(':')) {
    const groups = parseIpv6Text(bare);
    if (groups === null) {
      // 保留既有非法 mapped/私网前缀的拒绝语义。
      return bare.startsWith('::ffff:') || bare.startsWith('fe80:')
        || bare.startsWith('fc') || bare.startsWith('fd');
    }
    const zeroPrefix = groups.slice(0, 7).every(group => group === 0);
    if (zeroPrefix && (groups[7] === 0 || groups[7] === 1)) return true;
    if ((groups[0] & 0xffc0) === 0xfe80 || (groups[0] & 0xfe00) === 0xfc00) return true;
    if (groups.slice(0, 5).every(group => group === 0) && groups[5] === 0xffff) {
      return isPrivateOctets([groups[6] >> 8, groups[6] & 0xff, groups[7] >> 8, groups[7] & 0xff]);
    }
    return false;
  }

  // IPv4 全形态(inet_aton):整数/十六进制/八进制/短格式/混合进制
  // (2130706433 = 127.0.0.1 — 系统解析器会规范化,只认点分十进制会漏拦)
  const octets: number[] | null = parseIpv4Text(bare);
  if (octets === null) return false;
  return isPrivateOctets(octets);
};

const isHexChar = (c: string): boolean =>
  (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');

const hexValue = (c: string): number => {
  if (c >= '0' && c <= '9') return c.charCodeAt(0) - '0'.charCodeAt(0);
  if (c >= 'a' && c <= 'f') return c.charCodeAt(0) - 'a'.charCodeAt(0) + 10;
  return c.charCodeAt(0) - 'A'.charCodeAt(0) + 10;
};

// inet_aton 数字段解析:0x 前缀十六进制 / 0 前缀八进制 / 十进制;非法 → null
const parseIpSegment = (seg: string): number | null => {
  if (seg.length === 0) return null;
  let radix: number = 10;
  let digits: string = seg;
  if (seg.length > 2 && seg[0] === '0' && (seg[1] === 'x' || seg[1] === 'X')) {
    radix = 16;
    digits = seg.substring(2);
  } else if (seg.length > 1 && seg[0] === '0') {
    radix = 8;
    digits = seg.substring(1);
  }
  if (digits.length === 0) return null;
  let v: number = 0;
  for (let i = 0; i < digits.length; i++) {
    if (!isHexChar(digits[i])) return null;
    const d: number = hexValue(digits[i]);
    if (d >= radix) return null;
    v = v * radix + d;
    if (v > 0xFFFFFFFF) return null;
  }
  return v;
};

// IPv4 文本(含短格式/混合进制)→ 4 字节,失败 → null
const parseIpv4Text = (s: string): number[] | null => {
  const parts: string[] = s.split('.');
  if (parts.length === 0 || parts.length > 4) return null;
  const values: number[] = [];
  for (const part of parts) {
    const v: number | null = parseIpSegment(part);
    if (v === null) return null;
    values.push(v);
  }
  if (parts.length === 4) {
    if (values.some((v: number): boolean => v > 0xFF)) return null;
    return values;
  }
  const lastIdx: number = values.length - 1;
  for (let i = 0; i < lastIdx; i++) {
    if (values[i] > 0xFF) return null;
  }
  const tailBytes: number = 5 - values.length;
  const tailMax: number = 2 ** (8 * tailBytes) - 1;
  if (values[lastIdx] > tailMax) return null;
  const out: number[] = [];
  for (let i = 0; i < lastIdx; i++) out.push(values[i]);
  for (let shift = 8 * (tailBytes - 1); shift >= 0; shift -= 8) {
    out.push((values[lastIdx] >> shift) & 0xFF);
  }
  return out;
};

// 与现有 IPv4 字面量解析同属本地分类，不进行 DNS/网络探测。
const parseIpv6Text = (host: string): number[] | null => {
  let text = host;
  if (text.includes('.')) {
    const colon = text.lastIndexOf(':');
    const octets = parseIpv4Text(text.slice(colon + 1));
    if (octets === null) return null;
    text = text.slice(0, colon + 1) + ((octets[0] << 8) | octets[1]).toString(16)
      + ':' + ((octets[2] << 8) | octets[3]).toString(16);
  }
  const halves = text.split('::');
  if (halves.length > 2) return null;
  const left = halves[0].length > 0 ? halves[0].split(':') : [];
  const right = halves.length === 2 && halves[1].length > 0 ? halves[1].split(':') : [];
  const count = left.length + right.length;
  if ((halves.length === 1 && count !== 8) || (halves.length === 2 && count >= 8)) return null;
  const segments = [...left, ...Array<string>(8 - count).fill('0'), ...right];
  const groups: number[] = [];
  for (const segment of segments) {
    if (segment.length === 0 || segment.length > 4) return null;
    let value = 0;
    for (let index = 0; index < segment.length; index++) {
      if (!isHexChar(segment[index])) return null;
      value = (value << 4) | hexValue(segment[index]);
    }
    groups.push(value);
  }
  return groups;
};

const isPrivateOctets = (octets: number[]): boolean => {
  const a = octets[0];
  const b = octets[1];
  return a === 0 || a === 127 || a === 10 ||
    (a === 192 && b === 168) ||
    (a === 172 && b >= 16 && b <= 31) ||
    (a === 169 && b === 254) ||
    (a === 100 && b >= 64 && b <= 127);   // CGNAT 100.64.0.0/10
};

// 照搬 Android DeepReadSourcePrefetcher.kt:343-347
export const urlAllowedForBackgroundFetch = (
  url: string,
  autoApproveAllToolCalls: boolean,
  autoApproveHighRiskToolCalls: boolean,
): boolean => {
  if (!isPrivateUrl(url)) return true;
  // 私网 URL:只有两个 flag 都开才允许
  return autoApproveAllToolCalls && autoApproveHighRiskToolCalls;
};
