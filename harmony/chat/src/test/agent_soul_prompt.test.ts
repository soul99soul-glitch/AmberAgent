// agent_soul_prompt 测试(D-090)
// 锚点:GenerationPrompts.kt:14-28 + PreferencesStore.kt:272-300
import assert from 'node:assert/strict';
import test from 'node:test';
import {
  buildAgentSoulPrompt, DEFAULT_AGENT_SOUL_MARKDOWN,
} from '../main/ets/chat/agent_soul_prompt.ts';

test('空白/纯空白 → 空串(:15-16 takeIf isNotBlank)', () => {
  assert.equal(buildAgentSoulPrompt(''), '');
  assert.equal(buildAgentSoulPrompt('   \n\t  '), '');
});

test('非空白 → appendLine 序列逐字(golden,:17-26)', () => {
  assert.equal(
    buildAgentSoulPrompt('x'),
    '\n**AmberAgent Soul / agents.md**\n' +
    'The following app-level behavior guide is injected into every conversation:\n' +
    '<agents_md>\nx\n</agents_md>\n');
});

test('首尾空白 trim(:15)', () => {
  assert.equal(
    buildAgentSoulPrompt('  hello soul  \n'),
    '\n**AmberAgent Soul / agents.md**\n' +
    'The following app-level behavior guide is injected into every conversation:\n' +
    '<agents_md>\nhello soul\n</agents_md>\n');
});

test('DEFAULT_AGENT_SOUL_MARKDOWN 线格式(raw string:首尾换行属值)', () => {
  assert.ok(DEFAULT_AGENT_SOUL_MARKDOWN.startsWith('\n# agents.md\n'));
  assert.ok(DEFAULT_AGENT_SOUL_MARKDOWN.endsWith(
    '- Do not try to launch Android System WebView as a standalone app.\n'));
});

test('默认值经 builder 产出完整注入块(Android 默认即注入每会话)', () => {
  const prompt: string = buildAgentSoulPrompt(DEFAULT_AGENT_SOUL_MARKDOWN);
  assert.ok(prompt.startsWith('\n**AmberAgent Soul / agents.md**\n'));
  assert.ok(prompt.includes('<agents_md>\n# agents.md\n'));
  assert.ok(prompt.endsWith('</agents_md>\n'));
});
