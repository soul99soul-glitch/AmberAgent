/* Phase 2 M2.2 — Bilibili WBI signing shim (host-defined).
 *
 * Defines `window.__amberBiliWbi(url, method, body, extraParams)` returning
 * a Promise<{ url, method, headers, body }> suitable for fetch().
 *
 * The WBI algorithm is well-documented in public reverse-engineering posts:
 *  1. Fetch /x/web-interface/nav to get the two mixin URLs.
 *  2. Extract the filename-without-ext from each, concatenate → 64-char raw key.
 *  3. Permute via a hard-coded 64-entry index table to derive a 32-char mixin_key.
 *  4. Append `wts` (unix seconds), sort all params, md5(sorted_query + mixin_key) → w_rid.
 *  5. Append wts + w_rid to the request as query parameters.
 *
 * The shim caches mixin_key for 5 minutes (Bilibili rotates the URLs
 * occasionally; cache TTL keeps the page's `nav` traffic minimal).
 *
 * SAFETY: This function is invoked only through ProfileBridge.callSign, which
 * checks the profile's `call_page_fn:__amberBiliWbi` permission and the
 * page's origin allow-list. No agent-controllable input reaches the shim
 * without passing those checks first.
 */
(function() {
  'use strict';
  if (window.__amberBiliWbi) return;

  // Public-knowledge mixin order; if Bilibili rotates this we update the asset.
  var MIXIN = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
  ];

  var cachedKey = null;
  var cachedAt = 0;
  var CACHE_TTL_MS = 5 * 60 * 1000;

  function basename(u) {
    if (!u) return '';
    var p = u.split('/').pop() || '';
    var dot = p.lastIndexOf('.');
    return dot > 0 ? p.substring(0, dot) : p;
  }

  async function getMixinKey() {
    var now = Date.now();
    if (cachedKey && (now - cachedAt) < CACHE_TTL_MS) return cachedKey;
    var resp = await fetch('https://api.bilibili.com/x/web-interface/nav', { credentials: 'include' });
    if (!resp.ok) throw new Error('wbi nav fetch failed: ' + resp.status);
    var j = await resp.json();
    var img = (j && j.data && j.data.wbi_img && j.data.wbi_img.img_url) || '';
    var sub = (j && j.data && j.data.wbi_img && j.data.wbi_img.sub_url) || '';
    var raw = basename(img) + basename(sub);
    var picked = '';
    for (var i = 0; i < 32; i++) {
      var idx = MIXIN[i];
      if (idx < raw.length) picked += raw.charAt(idx);
    }
    cachedKey = picked;
    cachedAt = now;
    return cachedKey;
  }

  // Minimal MD5 — public-domain implementation (Joseph Myers, paulkernfeld port).
  // SubtleCrypto doesn't expose MD5 on Android WebView; we ship our own.
  function md5(str) {
    function safeAdd(x, y) {
      var lsw = (x & 0xFFFF) + (y & 0xFFFF);
      var msw = (x >> 16) + (y >> 16) + (lsw >> 16);
      return (msw << 16) | (lsw & 0xFFFF);
    }
    function bitRol(num, cnt) { return (num << cnt) | (num >>> (32 - cnt)); }
    function md5cmn(q, a, b, x, s, t) {
      return safeAdd(bitRol(safeAdd(safeAdd(a, q), safeAdd(x, t)), s), b);
    }
    function md5ff(a, b, c, d, x, s, t) { return md5cmn((b & c) | ((~b) & d), a, b, x, s, t); }
    function md5gg(a, b, c, d, x, s, t) { return md5cmn((b & d) | (c & (~d)), a, b, x, s, t); }
    function md5hh(a, b, c, d, x, s, t) { return md5cmn(b ^ c ^ d, a, b, x, s, t); }
    function md5ii(a, b, c, d, x, s, t) { return md5cmn(c ^ (b | (~d)), a, b, x, s, t); }
    function binl(x, len) {
      x[len >> 5] |= 0x80 << (len % 32);
      x[(((len + 64) >>> 9) << 4) + 14] = len;
      var a = 1732584193, b = -271733879, c = -1732584194, d = 271733878;
      for (var i = 0; i < x.length; i += 16) {
        var olda = a, oldb = b, oldc = c, oldd = d;
        a = md5ff(a, b, c, d, x[i + 0], 7, -680876936); d = md5ff(d, a, b, c, x[i + 1], 12, -389564586);
        c = md5ff(c, d, a, b, x[i + 2], 17, 606105819); b = md5ff(b, c, d, a, x[i + 3], 22, -1044525330);
        a = md5ff(a, b, c, d, x[i + 4], 7, -176418897); d = md5ff(d, a, b, c, x[i + 5], 12, 1200080426);
        c = md5ff(c, d, a, b, x[i + 6], 17, -1473231341); b = md5ff(b, c, d, a, x[i + 7], 22, -45705983);
        a = md5ff(a, b, c, d, x[i + 8], 7, 1770035416); d = md5ff(d, a, b, c, x[i + 9], 12, -1958414417);
        c = md5ff(c, d, a, b, x[i + 10], 17, -42063); b = md5ff(b, c, d, a, x[i + 11], 22, -1990404162);
        a = md5ff(a, b, c, d, x[i + 12], 7, 1804603682); d = md5ff(d, a, b, c, x[i + 13], 12, -40341101);
        c = md5ff(c, d, a, b, x[i + 14], 17, -1502002290); b = md5ff(b, c, d, a, x[i + 15], 22, 1236535329);
        a = md5gg(a, b, c, d, x[i + 1], 5, -165796510); d = md5gg(d, a, b, c, x[i + 6], 9, -1069501632);
        c = md5gg(c, d, a, b, x[i + 11], 14, 643717713); b = md5gg(b, c, d, a, x[i + 0], 20, -373897302);
        a = md5gg(a, b, c, d, x[i + 5], 5, -701558691); d = md5gg(d, a, b, c, x[i + 10], 9, 38016083);
        c = md5gg(c, d, a, b, x[i + 15], 14, -660478335); b = md5gg(b, c, d, a, x[i + 4], 20, -405537848);
        a = md5gg(a, b, c, d, x[i + 9], 5, 568446438); d = md5gg(d, a, b, c, x[i + 14], 9, -1019803690);
        c = md5gg(c, d, a, b, x[i + 3], 14, -187363961); b = md5gg(b, c, d, a, x[i + 8], 20, 1163531501);
        a = md5gg(a, b, c, d, x[i + 13], 5, -1444681467); d = md5gg(d, a, b, c, x[i + 2], 9, -51403784);
        c = md5gg(c, d, a, b, x[i + 7], 14, 1735328473); b = md5gg(b, c, d, a, x[i + 12], 20, -1926607734);
        a = md5hh(a, b, c, d, x[i + 5], 4, -378558); d = md5hh(d, a, b, c, x[i + 8], 11, -2022574463);
        c = md5hh(c, d, a, b, x[i + 11], 16, 1839030562); b = md5hh(b, c, d, a, x[i + 14], 23, -35309556);
        a = md5hh(a, b, c, d, x[i + 1], 4, -1530992060); d = md5hh(d, a, b, c, x[i + 4], 11, 1272893353);
        c = md5hh(c, d, a, b, x[i + 7], 16, -155497632); b = md5hh(b, c, d, a, x[i + 10], 23, -1094730640);
        a = md5hh(a, b, c, d, x[i + 13], 4, 681279174); d = md5hh(d, a, b, c, x[i + 0], 11, -358537222);
        c = md5hh(c, d, a, b, x[i + 3], 16, -722521979); b = md5hh(b, c, d, a, x[i + 6], 23, 76029189);
        a = md5hh(a, b, c, d, x[i + 9], 4, -640364487); d = md5hh(d, a, b, c, x[i + 12], 11, -421815835);
        c = md5hh(c, d, a, b, x[i + 15], 16, 530742520); b = md5hh(b, c, d, a, x[i + 2], 23, -995338651);
        a = md5ii(a, b, c, d, x[i + 0], 6, -198630844); d = md5ii(d, a, b, c, x[i + 7], 10, 1126891415);
        c = md5ii(c, d, a, b, x[i + 14], 15, -1416354905); b = md5ii(b, c, d, a, x[i + 5], 21, -57434055);
        a = md5ii(a, b, c, d, x[i + 12], 6, 1700485571); d = md5ii(d, a, b, c, x[i + 3], 10, -1894986606);
        c = md5ii(c, d, a, b, x[i + 10], 15, -1051523); b = md5ii(b, c, d, a, x[i + 1], 21, -2054922799);
        a = md5ii(a, b, c, d, x[i + 8], 6, 1873313359); d = md5ii(d, a, b, c, x[i + 15], 10, -30611744);
        c = md5ii(c, d, a, b, x[i + 6], 15, -1560198380); b = md5ii(b, c, d, a, x[i + 13], 21, 1309151649);
        a = md5ii(a, b, c, d, x[i + 4], 6, -145523070); d = md5ii(d, a, b, c, x[i + 11], 10, -1120210379);
        c = md5ii(c, d, a, b, x[i + 2], 15, 718787259); b = md5ii(b, c, d, a, x[i + 9], 21, -343485551);
        a = safeAdd(a, olda); b = safeAdd(b, oldb); c = safeAdd(c, oldc); d = safeAdd(d, oldd);
      }
      return [a, b, c, d];
    }
    function bin2rstr(input) {
      var output = '';
      for (var i = 0; i < input.length * 32; i += 8) output += String.fromCharCode((input[i >> 5] >>> (i % 32)) & 0xFF);
      return output;
    }
    function rstr2hex(input) {
      var hex = '0123456789abcdef', out = '';
      for (var i = 0; i < input.length; i++) {
        var x = input.charCodeAt(i);
        out += hex.charAt((x >>> 4) & 0x0F) + hex.charAt(x & 0x0F);
      }
      return out;
    }
    function str2rstr(s) {
      var out = '';
      for (var i = 0; i < s.length; i++) {
        var c = s.charCodeAt(i);
        if (c < 128) out += String.fromCharCode(c);
        else if (c < 2048) out += String.fromCharCode(0xC0 | (c >> 6)) + String.fromCharCode(0x80 | (c & 0x3F));
        else out += String.fromCharCode(0xE0 | (c >> 12)) + String.fromCharCode(0x80 | ((c >> 6) & 0x3F)) + String.fromCharCode(0x80 | (c & 0x3F));
      }
      return out;
    }
    function rstr2binl(input) {
      var output = new Array(input.length >> 2);
      for (var i = 0; i < output.length; i++) output[i] = 0;
      for (var j = 0; j < input.length * 8; j += 8) output[j >> 5] |= (input.charCodeAt(j / 8) & 0xFF) << (j % 32);
      return output;
    }
    var rstr = str2rstr(str);
    return rstr2hex(bin2rstr(binl(rstr2binl(rstr), rstr.length * 8)));
  }

  /**
   * Sign a Bilibili request URL with WBI parameters.
   * Returns the signed URL string. Useful for debugging or if the caller
   * wants to issue the fetch themselves.
   */
  window.__amberBiliWbiSign = async function(url, extraParams) {
    var u = new URL(url, location.href);
    var params = {};
    u.searchParams.forEach(function (v, k) { params[k] = v; });
    if (extraParams && typeof extraParams === 'object') {
      Object.keys(extraParams).forEach(function (k) { params[k] = String(extraParams[k]); });
    }
    var mixinKey = await getMixinKey();
    params.wts = Math.round(Date.now() / 1000);
    var sortedKeys = Object.keys(params).sort();
    var queryParts = sortedKeys.map(function (k) {
      var v = String(params[k]).replace(/[!'()*]/g, '');
      return encodeURIComponent(k) + '=' + encodeURIComponent(v);
    });
    var query = queryParts.join('&');
    var w_rid = md5(query + mixinKey);
    var signed = new URL(u.origin + u.pathname);
    sortedKeys.forEach(function (k) { signed.searchParams.append(k, String(params[k])); });
    signed.searchParams.append('w_rid', w_rid);
    return { url: signed.toString(), w_rid: w_rid, wts: params.wts };
  };

  /**
   * Sign AND fetch in one call. Profile.scripts.sign_request points here
   * — the agent passes `(url, method, body, extraParams)` and gets back
   * `{ok, status, body, headers}`. The fetch runs IN-PAGE so the cookie
   * jar attached to bilibili.com is naturally included, and the Referer
   * header from `location.href` is set correctly.
   *
   * Response body is returned as text; the caller (Kotlin wm_extract) can
   * parse JSON if needed. Caps body at 1 MB to keep the bridge transfer
   * tractable (agent budgets handle the rest).
   */
  window.__amberBiliWbi = async function(url, method, body, extraParams) {
    var signed = await window.__amberBiliWbiSign(url, extraParams);
    var opts = {
      method: (method || 'GET').toUpperCase(),
      credentials: 'include',
      headers: { 'Referer': 'https://www.bilibili.com/' }
    };
    if (body != null && opts.method !== 'GET' && opts.method !== 'HEAD') {
      opts.body = typeof body === 'string' ? body : JSON.stringify(body);
      if (!opts.headers['Content-Type']) {
        opts.headers['Content-Type'] = 'application/json';
      }
    }
    var resp = await fetch(signed.url, opts);
    var text = await resp.text();
    var BODY_CAP = 1024 * 1024;
    var truncated = text.length > BODY_CAP;
    var headers = {};
    resp.headers.forEach(function (v, k) { headers[k] = v; });
    return {
      ok: resp.ok,
      status: resp.status,
      url: resp.url,
      headers: headers,
      body: truncated ? text.substring(0, BODY_CAP) : text,
      body_truncated: truncated,
      body_chars: text.length,
      __signed_meta: { wts: signed.wts, w_rid: signed.w_rid }
    };
  };
})();
