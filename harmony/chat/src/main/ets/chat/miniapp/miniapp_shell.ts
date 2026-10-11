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

// Trusted interaction gate. Inline previews keep vertical scrolling in the
// chat as the default. A real click on a canvas arms its native owner; only
// then are the canvas' own pointer/touch handlers allowed to see events.
// The script is injected before generated HTML scripts so a generated canvas
// cannot register a handler ahead of the gate.
export const CANVAS_INTERACTION_JS: string = [
  '<script>',
  '(function () {',
  '  var active = false;',
  '  var root = document.documentElement;',
  '  var supportsPointer = typeof window.PointerEvent === "function";',
  '  var supportsTouch = "ontouchstart" in window;',
  '  var startPoint = null;',
  '  var moved = false;',
  '  var canvasTarget = function (target) {',
  '    return target && target.closest ? target.closest("canvas") : null;',
  '  };',
  '  var controlTarget = function (target) {',
  '    return target && target.closest ? target.closest("button,input,textarea,select,[contenteditable=\\"true\\"]") : null;',
  '  };',
  '  var notify = function (focused) {',
  '    try {',
  '      if (window.AmberNative && typeof window.AmberNative.postInteraction === "function") {',
  '        window.AmberNative.postInteraction(JSON.stringify({ type: "canvasFocus", focused: !!focused, sessionToken: window.__AMBER_MINIAPP_SESSION_TOKEN__ || "" }));',
  '      }',
  '    } catch (_) {}',
  '  };',
  '  var pointOf = function (event) {',
  '    var source = event.touches && event.touches.length > 0 ? event.touches[0] :',
  '      (event.changedTouches && event.changedTouches.length > 0 ? event.changedTouches[0] : event);',
  '    return { x: Number(source.clientX || 0), y: Number(source.clientY || 0) };',
  '  };',
  '  var resetTracking = function () { startPoint = null; moved = false; };',
  '  var trackStart = function (event) {',
  '    if (!active && canvasTarget(event.target) !== null) { startPoint = pointOf(event); moved = false; }',
  '  };',
  '  var trackMove = function (event) {',
  '    if (startPoint === null || active) return;',
  '    var point = pointOf(event);',
  '    if (Math.abs(point.x - startPoint.x) > 10 || Math.abs(point.y - startPoint.y) > 10) moved = true;',
  '  };',
  '  var trackEnd = function (event) {',
  '    if (startPoint !== null && (event.type === "pointercancel" || event.type === "touchcancel")) moved = true;',
  '  };',
  '  var setCanvasStyle = function (canvas) {',
  '    if (canvas && canvas.style) canvas.style.setProperty("touch-action", active ? "none" : "pan-y", "important");',
  '  };',
  '  var syncAncestors = function () {',
  '    if (root && root.style) root.style.setProperty("touch-action", "pan-y", "important");',
  '    if (document.body && document.body.style) document.body.style.setProperty("touch-action", "pan-y", "important");',
  '  };',
  '  var syncCanvases = function () {',
  '    syncAncestors();',
  '    document.querySelectorAll("canvas").forEach(setCanvasStyle);',
  '  };',
  '  window.__amberSetCanvasInteraction = function (focused) {',
  '    active = !!focused;',
  '    resetTracking();',
  '    if (root && root.classList) root.classList.toggle("amber-canvas-active", active);',
  '    syncCanvases();',
  '  };',
  '  var gate = function (event) {',
  '    var canvas = canvasTarget(event.target);',
  '    if (canvas === null) {',
  '      if (active && (event.type === "pointerdown" || event.type === "touchstart" || event.type === "mousedown")) notify(false);',
  '      return;',
  '    }',
  '    if (active) return;',
  '    if (event.type === "click") {',
  '      event.stopImmediatePropagation();',
  '      event.stopPropagation();',
  '      if (event.isTrusted === true && startPoint !== null && !moved) notify(true);',
  '      resetTracking();',
  '      return;',
  '    }',
  '    event.stopImmediatePropagation();',
  '    event.stopPropagation();',
  '  };',
  '  var trackingTypes = supportsPointer ? ["pointerdown", "pointermove", "pointerup", "pointercancel"] :',
  '    (supportsTouch ? ["touchstart", "touchmove", "touchend", "touchcancel"] : ["mousedown", "mousemove", "mouseup"]);',
  '  trackingTypes.forEach(function (type) {',
  '    var listener = type.endsWith("down") || type === "touchstart" ? trackStart :',
  '      (type.endsWith("move") ? trackMove : trackEnd);',
  '    document.addEventListener(type, listener, true);',
  '  });',
  '  ["pointerdown", "pointermove", "pointerup", "pointercancel",',
  '    "touchstart", "touchmove", "touchend", "touchcancel",',
  '    "mousedown", "mousemove", "mouseup", "click"].forEach(function (type) {',
  '    document.addEventListener(type, gate, true);',
  '  });',
  '  document.addEventListener("focusin", function (event) {',
  '    if (active && controlTarget(event.target) !== null) notify(false);',
  '  }, true);',
  '  try {',
  '    new MutationObserver(syncCanvases).observe(document.documentElement || document, { childList: true, subtree: true });',
  '  } catch (_) {}',
  '  window.__amberSetCanvasInteraction(false);',
  '})();',
  '</script>',
].join('\n');

// MiniAppShell.kt:60-69 逐字(CSP meta + token + guard + bridge + html)
export const injectMiniAppShell = (
  html: string, bridgeScript: string, sessionToken: string, inlineInteraction: boolean = false): string => {
  const prefixParts: string[] = [
    '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; script-src \'unsafe-inline\'; style-src \'unsafe-inline\'; img-src data: https:; connect-src \'none\'; font-src data:;">',
    tokenScript(sessionToken),
    guardScript,
  ];
  if (inlineInteraction) prefixParts.push(CANVAS_INTERACTION_JS);
  prefixParts.push('<script>', bridgeScript, '</script>');
  const prefix: string = prefixParts.join('\n');
  // Keep the doctype first so the app's CSS uses standards mode, with guards before app scripts.
  const head = /^\s*(?:<!doctype[^>]*>\s*)?(?:<html\b[^>]*>\s*)?<head\b[^>]*>/i.exec(html);
  const doctype = /^\s*<!doctype[^>]*>/i.exec(html);
  const insertAt = head !== null ? head[0].length : (doctype === null ? 0 : doctype[0].length);
  return html.slice(0, insertAt) + '\n' + prefix + '\n' + html.slice(insertAt);
};
