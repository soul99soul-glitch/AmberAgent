// miniapp_html_validator — 22 条 blockedPatterns + 结构/尺寸/图片规则
//
// Android 基准: feature/miniapp/MiniAppHtmlValidator.kt(全文 65 行)
// 偏差:
//   - Kotlin Regex("(?is)…") 内联标志 → JS new RegExp(source, 'is')
//     (JS 不支持内联 (?is) 标志;逐字转移源模式)
//   - encodeToByteArray().size → utf8ByteLength(手写 UTF-8 计数,models 导出)
//   - 图片资源正则需要 'g' 标志(JS matchAll 要求),源模式不变

import {
  MINI_APP_MAX_HTML_BYTES, MiniAppValidationException, utf8ByteLength,
} from './miniapp_models.ts';

const requiredHtmlPattern: RegExp = new RegExp('<\\s*(html\\b|!doctype\\s+html)', 'is');

// MiniAppHtmlValidator.kt:7-30 22 条逐字
const BLOCKED_PATTERNS: Array<[RegExp, string]> = [
  [new RegExp('<\\s*script\\b[^>]*\\bsrc\\s*=', 'is'), 'External scripts are not allowed'],
  [new RegExp('<\\s*(iframe|object|embed|form)\\b', 'is'), 'Embedded/submit-capable elements are not allowed'],
  [new RegExp('<\\s*(img|source)\\b[^>]*\\bsrcset\\s*=', 'is'), 'Image srcset is not supported'],
  [new RegExp('<\\s*link\\b[^>]*\\bhref\\s*=\\s*[\'"]?\\s*https?:', 'is'), 'External stylesheets are not allowed'],
  [new RegExp('@import\\s+([\'"]?\\s*)?(https?:|//|file:|content:)', 'is'), 'CSS imports are not allowed'],
  [new RegExp('url\\s*\\(\\s*([\'"]?)\\s*(https?:|//|file:|content:)', 'is'), 'External CSS URLs are not allowed'],
  [new RegExp('\\beval\\s*\\(', 'is'), 'eval() is not allowed'],
  [new RegExp('\\bnew\\s+Function\\b', 'is'), 'new Function is not allowed'],
  [new RegExp('\\bimport\\s*\\(', 'is'), 'dynamic import() is not allowed'],
  [new RegExp('\\bimport\\s+[\'"]', 'is'), 'static import is not allowed'],
  [new RegExp('\\bWebSocket\\b', 'is'), 'WebSocket is not allowed'],
  [new RegExp('\\bEventSource\\b', 'is'), 'EventSource is not allowed'],
  [new RegExp('\\bXMLHttpRequest\\b', 'is'), 'XMLHttpRequest is not allowed'],
  [new RegExp('\\blocalStorage\\b', 'is'), 'localStorage is not allowed'],
  [new RegExp('\\bsessionStorage\\b', 'is'), 'sessionStorage is not allowed'],
  [new RegExp('\\bindexedDB\\b', 'is'), 'indexedDB is not allowed'],
  [new RegExp('\\bnavigator\\s*\\.\\s*geolocation\\b', 'is'), 'geolocation is not allowed'],
  [new RegExp('\\bnavigator\\s*\\.\\s*mediaDevices\\b', 'is'), 'mediaDevices is not allowed'],
  [new RegExp('\\bnavigator\\s*\\.\\s*clipboard\\b', 'is'), 'native clipboard is not allowed'],
  [new RegExp('\\bnavigator\\s*\\[\\s*[\'"]\\s*(geolocation|mediaDevices|clipboard)\\s*[\'"]\\s*\\]', 'is'),
    'computed access to blocked navigator APIs is not allowed'],
  [new RegExp('\\bwindow\\s*\\[\\s*[\'"]\\s*(XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|indexedDB)\\s*[\'"]\\s*\\]', 'is'),
    'computed access to blocked browser APIs is not allowed'],
  [new RegExp('\\bglobalThis\\s*\\[\\s*[\'"]\\s*(XMLHttpRequest|WebSocket|EventSource|localStorage|sessionStorage|indexedDB)\\s*[\'"]\\s*\\]', 'is'),
    'computed access to blocked browser APIs is not allowed'],
];

// MiniAppHtmlValidator.kt:31-34(需要 'g':JS matchAll 要求)
const quotedImageResourcePattern: RegExp = new RegExp(
  '<\\s*(img|source)\\b[^>]*\\b(src|srcset)\\s*=\\s*([\'"])(.*?)\\3', 'gis');
const unquotedImageResourcePattern: RegExp = new RegExp(
  '<\\s*(img|source)\\b[^>]*\\b(src|srcset)\\s*=\\s*([^\\s"\'=<>`]+)', 'gis');

const isAllowedImageUrl = (value: string): boolean => {
  const lower: string = value.toLowerCase();
  return lower.startsWith('data:image/') || lower.startsWith('https://');
};

// MiniAppHtmlValidator.kt:52-60(Kotlin findAll + groupValues)
const hasInvalidImageResource = (html: string): boolean => {
  for (const match of html.matchAll(quotedImageResourcePattern)) {
    const url: string = match[4] === undefined ? '' : match[4];
    if (!isAllowedImageUrl(url.trim())) return true;
  }
  for (const match of html.matchAll(unquotedImageResourcePattern)) {
    const url: string = match[3] === undefined ? '' : match[3];
    if (!isAllowedImageUrl(url.trim())) return true;
  }
  return false;
};

// MiniAppHtmlValidator.kt:36-50
export const validateMiniAppHtml = (html: string): void => {
  const sizeBytes: number = utf8ByteLength(html);
  if (sizeBytes > MINI_APP_MAX_HTML_BYTES) {
    throw new MiniAppValidationException(`HTML is too large: ${sizeBytes} bytes`);
  }
  if (!requiredHtmlPattern.test(html)) {
    throw new MiniAppValidationException('HTML must include <html> or <!DOCTYPE html>');
  }
  for (const [pattern, reason] of BLOCKED_PATTERNS) {
    if (pattern.test(html)) {
      throw new MiniAppValidationException(reason);
    }
  }
  if (hasInvalidImageResource(html)) {
    throw new MiniAppValidationException('MiniApp images must use data:image or https URLs');
  }
};
