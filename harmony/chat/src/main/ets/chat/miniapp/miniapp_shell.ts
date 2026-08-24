// miniapp_shell — BASE_URL + CSP/token/guard 前缀注入
//
// Android 基准: feature/miniapp/MiniAppShell.kt(全文 70 行)
// 偏差:
//   - Kotlin Json.encodeToString(sessionToken) → JSON.stringify(sessionToken)
//     (两者均产出双引号 JSON 字符串字面量)
//   - trimIndent 语义由拼装文本直接体现(无公共缩进)

export const MINI_APP_SHELL_BASE_URL: string = 'https://miniapp.amberagent.local/';

// MiniAppShell.kt:10-14
const tokenScript = (sessionToken: string): string =>
  `<script>\nwindow.__AMBER_MINIAPP_SESSION_TOKEN__ = ${JSON.stringify(sessionToken)};\n</script>`;

// MiniAppShell.kt:15-59 逐字(guard 封禁清单 + MutationObserver 占位图逻辑)
const guardScript: string = [
  '<script>',
  '(function () {',
  "  const block = function (name) {",
  "    try { Object.defineProperty(window, name, { value: undefined, writable: false, configurable: false }); } catch (_) {}",
  '  };',
  "  block('XMLHttpRequest');",
  "  block('WebSocket');",
  "  block('EventSource');",
  "  block('localStorage');",
  "  block('sessionStorage');",
  "  block('indexedDB');",
  "  try { Object.defineProperty(navigator, 'geolocation', { value: undefined, writable: false, configurable: false }); } catch (_) {}",
  "  try { Object.defineProperty(navigator, 'mediaDevices', { value: undefined, writable: false, configurable: false }); } catch (_) {}",
  "  try { Object.defineProperty(navigator, 'clipboard', { value: undefined, writable: false, configurable: false }); } catch (_) {}",
  '  const makeOfflineImage = function (label) {',
  "    const svg = '<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"480\" viewBox=\"0 0 800 480\"><defs><linearGradient id=\"g\" x1=\"0\" x2=\"1\" y1=\"0\" y2=\"1\"><stop stop-color=\"#f3f4f6\"/><stop offset=\"1\" stop-color=\"#e5e7eb\"/></linearGradient></defs><rect width=\"800\" height=\"480\" fill=\"url(#g)\"/><text x=\"50%\" y=\"50%\" text-anchor=\"middle\" dominant-baseline=\"middle\" fill=\"#6b7280\" font-size=\"28\" font-family=\"sans-serif\">' + label + '</text></svg>';",
  "    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);",
  '  };',
  '  const normalizeImages = function () {',
  "    document.querySelectorAll('img').forEach(function (img) {",
  "      const raw = img.getAttribute('src') || '';",
  "      const normalized = raw.trim().toLowerCase();",
  "      if (!(normalized.startsWith('data:image/') || normalized.startsWith('https://'))) {",
  "        img.setAttribute('data-amber-original-src', raw);",
  "        img.src = makeOfflineImage('图片需使用内联 data URI');",
  '      }',
  '    });',
  '  };',
  "  if (document.readyState === 'loading') {",
  "    document.addEventListener('DOMContentLoaded', normalizeImages, { once: true });",
  '  } else {',
  '    normalizeImages();',
  '  }',
  '  try {',
  '    new MutationObserver(normalizeImages).observe(document.documentElement, {',
  '      childList: true,',
  '      subtree: true,',
  '      attributes: true,',
  "      attributeFilter: ['src', 'srcset']",
  '    });',
  '  } catch (_) {}',
  '})();',
  '</script>',
].join('\n');

// MiniAppShell.kt:60-69 逐字(CSP meta + token + guard + bridge + html)
export const injectMiniAppShell = (html: string, bridgeScript: string, sessionToken: string): string => {
  const prefix: string = [
    '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'; style-src \'unsafe-inline\'; img-src data: https:; connect-src \'none\'; font-src data:;">',
    tokenScript(sessionToken),
    guardScript,
    '<script>',
    bridgeScript,
    '</script>',
  ].join('\n');
  return `${prefix}\n${html}`;
};
