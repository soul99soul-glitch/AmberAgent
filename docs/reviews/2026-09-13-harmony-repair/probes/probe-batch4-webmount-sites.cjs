// probe-batch4-webmount-sites.cjs — L02 regression probe (site authorization boundary).
//
// Drives the REAL `checkWebMountUrl` / `hostOf` / `siteAllowsUrl` / `makeSiteGate`
// from harmony/entry/src/main/ets/platform_impl/WebMountSites.ets. The official
// `@kit.ArkTS` url module is shimmed onto Node's WHATWG URL parser, which is the
// reference standard parser — the probe does not reimplement URL logic and touches
// no network / browser / real user data.
//
// Key assertions:
//   - the deceptive userinfo URL that the old authority.split(':')[0] gate accepted
//     (host = outside.invalid) is now rejected;
//   - non-http(s) schemes (javascript:/file:/data:) are rejected;
//   - hostname/scheme case, port, IPv6, trailing dot normalize;
//   - for every parsable candidate, our hostname equals the standard URL hostname;
//   - subdomain matches but lookalike suffixes (github.com.evil.com, evilgithub.com) do not.

const path = require('node:path');
const assert = require('node:assert/strict');
const { REPO_ROOT, createLoader } = require('../../2026-09-13-harmony-merged-evidence/probe-lib.cjs');

const ETS = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/platform_impl');
const NativeURL = globalThis.URL;

class UrlShim {
  static parseURL(s, base) { return new NativeURL(s, base); }
}

const { load } = createLoader((name, loadFn) => {
  if (name === '@kit.ArkTS') return { url: { URL: UrlShim } };
  if (name === '@amber/chat-domain') return { newId: () => 'synthetic-id' };
  if (name === './WebMountSites.ets') return loadFn(path.join(ETS, 'WebMountSites.ets'));
  return undefined;
});

const sites = load(path.join(ETS, 'WebMountSites.ets'));
const registered = [{ id: 'github', name: 'GitHub', url: 'https://github.com/login' }];

// 1) the exact deceptive URL from the finding: github.com userinfo, real host outside.invalid
const deceptive = 'https://github.com:x@outside.invalid/private';
assert.equal(new NativeURL(deceptive).hostname, 'outside.invalid');
assert.equal(sites.siteAllowsUrl(registered, deceptive), false);
assert.equal(sites.hostOf(deceptive), '');
console.log('[L02-T1] userinfo URL -> siteAllowsUrl=false, hostOf="" (old gate accepted it as github.com)');

// 2) no-colon userinfo was already denied before; still denied
assert.equal(sites.siteAllowsUrl(registered, 'https://github.com@outside.invalid/'), false);

// 3) non-http(s) schemes
for (const bad of ['javascript:alert(1)', 'file:///etc/passwd', 'data:text/html,<b>x</b>',
  'ftp://github.com/x', 'about:blank', 'chrome://settings']) {
  assert.equal(sites.siteAllowsUrl(registered, bad), false, 'should reject ' + bad);
  assert.equal(sites.hostOf(bad), '', 'hostOf should be empty for ' + bad);
}
console.log('[L02-T2] non-http(s) schemes rejected');

// 4) legitimate normalization: case, port, subdomain, trailing dot
assert.equal(sites.siteAllowsUrl(registered, 'HTTPS://GitHub.COM/Path'), true);
assert.equal(sites.siteAllowsUrl(registered, 'https://github.com:8443/x'), true);
assert.equal(sites.siteAllowsUrl(registered, 'https://gist.github.com/a'), true);
assert.equal(sites.siteAllowsUrl(registered, 'https://github.com./login'), true);
assert.equal(sites.hostOf('HTTPS://GitHub.COM/Path'), 'github.com');
console.log('[L02-T3] case/port/subdomain/trailing-dot normalize and match');

// 5) lookalikes must NOT match
assert.equal(sites.siteAllowsUrl(registered, 'https://evilgithub.com/x'), false);
assert.equal(sites.siteAllowsUrl(registered, 'https://github.com.evil.com/x'), false);
assert.equal(sites.siteAllowsUrl(registered, 'https://notgithub.com/x'), false);
console.log('[L02-T4] lookalike hosts rejected');

// 6) userinfo with a matching host is still rejected (userinfo ban is independent)
assert.equal(sites.checkWebMountUrl('https://user:pass@github.com/').ok, false);
assert.equal(sites.checkWebMountUrl('https://user@github.com/').ok, false);

// 7) IPv6 + explicit port
const v6 = [{ id: 'v6', name: 'v6', url: 'http://[::1]:8080/' }];
assert.equal(sites.siteAllowsUrl(v6, 'http://[::1]/path'), true);
assert.equal(sites.siteAllowsUrl(v6, 'http://[::2]/path'), false);
assert.equal(sites.hostOf('http://[::1]:8080/x'), '[::1]');
console.log('[L02-T5] IPv6 host normalized with brackets');

// 8) property: wherever our http(s)+no-userinfo gate succeeds, our hostname ===
//    standard hostname lowercased. (The deceptive URL is intentionally rejected, so
//    it is asserted separately: parsable by the standard parser but gate ok=false.)
for (const u of ['HTTPS://GitHub.COM/Path', 'https://github.com:8443/x',
  'https://github.com./login', 'http://[::1]:8080/x', 'https://sub.deep.example.com/p?q=1#h']) {
  const mine = sites.checkWebMountUrl(u);
  assert.equal(mine.ok, true, 'should parse ' + u);
  // our normalization also strips a single trailing dot (example.com. === example.com)
  const expectedHost = new NativeURL(u).hostname.toLowerCase().replace(/\.$/, '');
  assert.equal(mine.hostname, expectedHost, 'hostname mismatch for ' + u);
}
assert.equal(sites.checkWebMountUrl(deceptive).ok, false);
assert.equal(new NativeURL(deceptive).hostname, 'outside.invalid');
console.log('[L02-T6] hostname equals standard URL.hostname for all parsable candidates');

// 9) synchronous gate used by onLoadIntercept
const gate = sites.makeSiteGate(registered);
assert.equal(gate('https://github.com/x'), true);
assert.equal(gate('https://gist.github.com/a'), true);
assert.equal(gate('https://outside.invalid/x'), false);
assert.equal(gate(deceptive), false);
assert.equal(gate('javascript:alert(1)'), false);
assert.equal(gate('about:blank'), false); // session allows about: separately as internal
console.log('[L02-T7] makeSiteGate mirrors siteAllowsUrl for sync navigation interception');

console.log('\nAll batch-4 L02 assertions passed. Standard WHATWG URL shim only; no network/browser/user data.');
