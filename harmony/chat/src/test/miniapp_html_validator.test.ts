// miniapp_html_validator — 校验规则钉死(对齐 Android MiniAppHtmlValidatorTest)
import test from 'node:test';
import assert from 'node:assert/strict';
import { validateMiniAppHtml } from '../main/ets/chat/miniapp/miniapp_html_validator.ts';
import { MINI_APP_MAX_HTML_BYTES } from '../main/ets/chat/miniapp/miniapp_models.ts';

const fails = (html: string): boolean => {
  try {
    validateMiniAppHtml(html);
    return false;
  } catch (_e) {
    return true;
  }
};

test('allows offline single file html', () => {
  validateMiniAppHtml(
    `<!DOCTYPE html>
<html>
  <head><style>body { font-family: sans-serif; }</style></head>
  <body><button onclick="Amber.toast('ok')">OK</button></body>
</html>`);
  validateMiniAppHtml(
    '<!DOCTYPE html><html><body><svg viewBox="0 0 10 10"><rect width="10" height="10"/></svg>' +
    '<img src="data:image/png;base64,iVBORw0KGgo="></body></html>');
  validateMiniAppHtml(
    '<!DOCTYPE html><html><body><img src="https://example.com/a.png">' +
    "<script>Amber.fetch({url:'https://example.com/api'}); Amber.search({query:'AI', limit:3});</script></body></html>");
  validateMiniAppHtml(
    "<!DOCTYPE html><html><body><script>fetch('https://example.com/api').then(r => r.json())</script></body></html>");
  validateMiniAppHtml(
    "<!DOCTYPE html><html><body><script>Amber.ai.generate({prompt:'hi'}); Amber.host.getConversationContext({mode:'summary'}); Amber.clipboard.read(); Amber.location.getCurrent({accuracy:'coarse'});</script></body></html>");
});

test('rejects external and dangerous apis', () => {
  const rejected: string[] = [
    '<!DOCTYPE html><html><script src="https://example.com/a.js"></script></html>',
    "<!DOCTYPE html><html><script src = 'https://example.com/a.js'></script></html>",
    '<!DOCTYPE html><html><img src="http://example.com/a.png"></html>',
    '<!DOCTYPE html><html><img src="/images/a.png"></html>',
    '<!DOCTYPE html><html><img src="file:///sdcard/a.png"></html>',
    '<!DOCTYPE html><html><source srcset="https://example.com/a.png 1x, http://example.com/a.png 2x"></html>',
    '<!DOCTYPE html><html><style>@import "https://example.com/a.css";</style></html>',
    '<!DOCTYPE html><html><iframe srcdoc="x"></iframe></html>',
    '<!DOCTYPE html><html><script>XMLHttpRequest</script></html>',
    '<!DOCTYPE html><html><script>EventSource</script></html>',
    "<!DOCTYPE html><html><script>localStorage.setItem('a','b')</script></html>",
    "<!DOCTYPE html><html><script>indexedDB.open('x')</script></html>",
    '<!DOCTYPE html><html><script>navigator.mediaDevices.getUserMedia({audio:true})</script></html>',
    "<!DOCTYPE html><html><script>navigator['geolocation'].getCurrentPosition(()=>{})</script></html>",
    "<!DOCTYPE html><html><script>navigator['mediaDevices'].getUserMedia({audio:true})</script></html>",
    '<!DOCTYPE html><html><script>navigator.clipboard.readText()</script></html>',
  ];
  for (const html of rejected) {
    assert.equal(fails(html), true, `Expected validation failure for ${html}`);
  }
});

test('rejects oversized html', () => {
  const html: string = `<!DOCTYPE html><html>${'x'.repeat(MINI_APP_MAX_HTML_BYTES)}</html>`;
  assert.equal(fails(html), true);
});

test('rejects missing html root element', () => {
  assert.equal(fails('<div>plain body without html tag</div>'), true);
  assert.equal(fails('<HTML><body>x</body></HTML>'), false);
});
