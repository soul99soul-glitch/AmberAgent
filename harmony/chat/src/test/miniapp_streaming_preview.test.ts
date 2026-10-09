// miniapp_streaming_preview — 流式小应用卡片代码窗口(IOSMiniAppStreamingCodePreviewState 对齐)
import test from 'node:test';
import assert from 'node:assert/strict';
import { MiniAppStreamingCodePreview } from '../main/ets/chat/miniapp/miniapp_streaming_preview.ts';

const PAYLOAD: string = JSON.stringify({
  title: '计时器',
  description: '番茄钟',
  html: '<!DOCTYPE html>\n<p class="x">A\\B 你好 😀</p>',
});
const HTML: string = '<!DOCTYPE html>\n<p class="x">A\\B 你好 😀</p>';

const feed = (text: string, step: number): MiniAppStreamingCodePreview => {
  const preview = new MiniAppStreamingCodePreview();
  for (let end: number = step; end < text.length + step; end += step) {
    preview.update(text.slice(0, Math.min(end, text.length)));
  }
  return preview;
};

test('decodes the html field from a complete payload', () => {
  const preview = new MiniAppStreamingCodePreview();
  preview.update('```json\n' + PAYLOAD + '\n```');
  assert.equal(preview.displayText(), HTML);
});

test('char-by-char snapshots decode the same as one shot, including split escapes and keys', () => {
  for (const step of [1, 2, 3, 7]) {
    assert.equal(feed(PAYLOAD, step).displayText(), HTML, `step ${step}`);
  }
});

test('\\u escapes (incl. surrogate pairs) split across snapshots', () => {
  const payload: string = '{"title":"t","description":"d","html":"\\u4f60\\ud83d\\ude00!"}';
  assert.equal(feed(payload, 1).displayText(), '你😀!');
});

test('shows the raw payload before the html value starts', () => {
  const preview = new MiniAppStreamingCodePreview();
  preview.update('{"title":"计时器","description":"番茄');
  assert.equal(preview.displayText(), '{"title":"计时器","description":"番茄');
});

test('non-append snapshot resets the decoder', () => {
  const preview = new MiniAppStreamingCodePreview();
  preview.update('{"html":"old content');
  preview.update('{"html":"new');
  assert.equal(preview.displayText(), 'new');
});

test('caps the window to the tail with an omitted-count notice', () => {
  const preview = new MiniAppStreamingCodePreview();
  preview.update('{"html":"' + 'a'.repeat(30) + 'TAIL');
  assert.equal(preview.displayText(10), '已省略 24 字\n' + 'aaaaaaTAIL');
});
