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
  const bare = host.startsWith('[') && host.endsWith(']')
    ? host.slice(1, -1)
    : host;

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

  // IPv6(含 ':')::: / ::1 / fe80: (link-local) / fc / fd (ULA)
  if (bare.includes(':')) {
    return bare === '::' || bare === '::1' ||
      bare.startsWith('fe80:') ||
      bare.startsWith('fc') ||
      bare.startsWith('fd');
  }

  // IPv4 dotted quad
  const parts = bare.split('.');
  if (parts.length !== 4) return false;
  const octets: number[] = [];
  for (const p of parts) {
    const n = /^[0-9]+$/.test(p) ? parseInt(p, 10) : NaN;
    if (!Number.isInteger(n) || n < 0 || n > 255) return false;
    octets.push(n);
  }
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
