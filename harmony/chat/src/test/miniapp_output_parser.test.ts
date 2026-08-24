// miniapp_output_parser — 解析/别名/校验钉死(对齐 Android MiniAppOutputParserTest)
import test from 'node:test';
import assert from 'node:assert/strict';
import { MiniAppOutputParser } from '../main/ets/chat/miniapp/miniapp_output_parser.ts';
import type { MiniAppGeneratedOutput } from '../main/ets/chat/miniapp/miniapp_models.ts';

const parser: MiniAppOutputParser = new MiniAppOutputParser();

test('parses fenced miniapp json', () => {
  const output: MiniAppGeneratedOutput = parser.parse(
    [
      '```json',
      '{',
      '  "title": "喝水记录器",',
      '  "description": "记录每天喝水量",',
      '  "icon": "水",',
      '  "category": "tool",',
      '  "permissions": ["storage", "toast", "theme"],',
      '  "html": "<!DOCTYPE html><html><body><script>Amber.toast(\'hi\')</script></body></html>"',
      '}',
      '```',
    ].join('\n'),
  );
  assert.equal(output.title, '喝水记录器');
  assert.deepEqual(output.permissions, ['storage', 'toast', 'theme']);
});

test('parses miniapp json surrounded by assistant text', () => {
  const output: MiniAppGeneratedOutput | null = parser.parseOrNull(
    [
      '我先把最终版本整理成 JSON：',
      '{',
      '  "title": "冒险卡片",',
      '  "description": "真心话大冒险抽题",',
      '  "icon": "卡",',
      '  "category": "game",',
      '  "permissions": ["toast"],',
      '  "html": "<!DOCTYPE html><html><body><button onclick=\\"Amber.toast(\'go\')\\">抽题</button></body></html>"',
      '}',
    ].join('\n'),
  );
  assert.equal(output !== null && output.title, '冒险卡片');
  assert.deepEqual(output !== null && output.permissions, ['toast']);
});

test('accepts v2 permissions', () => {
  const output: MiniAppGeneratedOutput = parser.parse(
    [
      '{',
      '  "title": "新闻工具",',
      '  "description": "搜索并展示新闻",',
      '  "category": "info",',
      '  "permissions": ["network", "externalImages", "search", "clipboard.copy", "host.updateBoardSummary"],',
      '  "html": "<!DOCTYPE html><html><body><img src=\\"https://example.com/a.png\\"><script>Amber.search({query:\'AI\'}); Amber.fetch({url:\'https://example.com/api\'});</script></body></html>"',
      '}',
    ].join('\n'),
  );
  assert.deepEqual(output.permissions,
    ['network', 'externalImages', 'search', 'clipboard.copy', 'host.updateBoardSummary']);
});

test('accepts v3 permissions', () => {
  const output: MiniAppGeneratedOutput = parser.parse(
    [
      '{',
      '  "title": "上下文助手",',
      '  "description": "读取摘要并调用 AI",',
      '  "category": "tool",',
      '  "permissions": ["host.context", "host.sendToConversation", "host.createArtifact", "ai.generate", "sharedStore", "eventBus", "launch", "sensor", "location", "clipboard.read"],',
      '  "html": "<!DOCTYPE html><html><body><script>Amber.host.getConversationContext({mode:\'summary\'}); Amber.ai.generate({prompt:\'hi\'});</script></body></html>"',
      '}',
    ].join('\n'),
  );
  assert.equal(output.permissions[0], 'host.context');
  assert.equal(output.permissions[output.permissions.length - 1], 'clipboard.read');
});

test('normalizes common permission aliases', () => {
  const output: MiniAppGeneratedOutput = parser.parse(
    [
      '{',
      '  "title": "感应面板",',
      '  "description": "读取网络和传感器",',
      '  "category": "tool",',
      '  "permissions": ["fetch", "Gyroscope", "light", "external_images"],',
      '  "html": "<!DOCTYPE html><html><body><script>fetch(\'https://example.com/api\'); Amber.sensor.subscribe({type:\'ambientLight\'}, () => {});</script></body></html>"',
      '}',
    ].join('\n'),
  );
  assert.deepEqual(output.permissions, ['network', 'sensor', 'externalImages']);
});

test('rejects unknown permissions', () => {
  const output: MiniAppGeneratedOutput | null = parser.parseOrNull(
    [
      '{',
      '  "title": "定位工具",',
      '  "description": "不开放联系人",',
      '  "category": "tool",',
      '  "permissions": ["contacts.read"],',
      '  "html": "<!DOCTYPE html><html><body>ok</body></html>"',
      '}',
    ].join('\n'),
  );
  assert.equal(output, null);
});

test('rejects invalid title/description/icon/category/html', () => {
  // 注意:不重复 description 键(JSON.parse 后者覆盖前者)
  const validHtml: string = '<!DOCTYPE html><html><body>x</body></html>';
  const base: string = `"category": "tool", "permissions": [], "html": "${validHtml}"`;
  assert.equal(parser.parseOrNull(`{ "title": "", "description": "d", ${base} }`), null);
  assert.equal(parser.parseOrNull(`{ "title": "${'t'.repeat(21)}", "description": "d", ${base} }`), null);
  assert.equal(parser.parseOrNull(`{ "title": "ok", "description": "", ${base} }`), null);
  assert.equal(parser.parseOrNull(`{ "title": "ok", "description": "${'d'.repeat(81)}", ${base} }`), null);
  assert.equal(parser.parseOrNull(`{ "title": "ok", "description": "d", "icon": "abc", ${base} }`), null);
  assert.equal(parser.parseOrNull(`{ "title": "ok", "description": "d", "category": "bogus", "permissions": [], "html": "${validHtml}" }`), null);
  assert.equal(parser.parseOrNull(`{ "title": "ok", "description": "d", "category": "tool", "permissions": [], "html": "<div>no html tag</div>" }`), null);
});

test('no json object found throws', () => {
  assert.throws((): MiniAppGeneratedOutput => parser.parse('hello world'));
});

// kotlinx 非空类型语义:category/permissions 显式 null = 解码失败(保留原文);
//   icon 为 String? 允许 null;缺省 category/permissions 用默认值
test('explicit null non-nullable fields reject; nullable icon accepts null', () => {
  const validHtml: string = '<!DOCTYPE html><html><body>x</body></html>';
  const base: string = `"title": "ok", "description": "d", "html": "${validHtml}"`;
  assert.equal(parser.parseOrNull(`{ ${base}, "category": null, "permissions": [] }`), null);
  assert.equal(parser.parseOrNull(`{ ${base}, "category": "tool", "permissions": null }`), null);
  const withNullIcon: MiniAppGeneratedOutput | null =
    parser.parseOrNull(`{ ${base}, "icon": null, "category": "tool", "permissions": [] }`);
  assert.notEqual(withNullIcon, null);
  assert.equal(withNullIcon!.icon, null);
  const omitted: MiniAppGeneratedOutput | null =
    parser.parseOrNull(`{ ${base} }`);
  assert.notEqual(omitted, null);
  assert.equal(omitted?.category, 'tool');
  assert.deepEqual(omitted?.permissions, []);
});
