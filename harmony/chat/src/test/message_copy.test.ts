import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeAssistantMessage, makeUIMessage } from '../main/ets/chat/message.ts';
import { markdownToPlainText, textPartCopyText, messageCopyText } from '../main/ets/chat/message_copy.ts';

test('plain copy drops presentation markers while preserving readable content', () => {
  const markdown = '# 标题\n\n**重点**、*说明*、[文档](https://example.com) 和 `const x = 1`';
  assert.equal(messageCopyText(makeAssistantMessage(markdown), false), '标题\n\n重点、说明、文档 和 const x = 1');
});

test('plain copy preserves literal Markdown characters inside code blocks', () => {
  const markdown = '说明\n\n```ts\n  const label = "**保留**";\n  const url = "[a](b)";\n```';
  assert.equal(messageCopyText(makeAssistantMessage(markdown), false),
    '说明\n\n  const label = "**保留**";\n  const url = "[a](b)";');
});

test('segment copy follows the selected format and does not strip code literals', () => {
  const markdown = '**重点** 和 `**代码**`';
  assert.equal(textPartCopyText(markdown, false), '重点 和 **代码**');
  assert.equal(textPartCopyText(markdown, true), markdown);
  assert.equal(messageCopyText(makeAssistantMessage(markdown), true), markdown);
});

test('lists retain nesting and numbering as readable plain text', () => {
  const markdown = '- **一**\n  - [子项](https://example.com)\n- `三`\n\n3. *甲*\n4. 乙';
  assert.equal(markdownToPlainText(markdown), '• 一\n  • 子项\n• 三\n\n3. 甲\n4. 乙');
});

test('quotes, image labels, math, and tables keep content without presentation syntax', () => {
  const markdown = '> **引用**\n> 下一行\n\n![示意图](https://example.com/a.png) $x^2$\n\n'
    + '| **名称** | 说明 |\n| --- | --- |\n| [文档](https://example.com) | `a\\|b` |';
  assert.equal(markdownToPlainText(markdown),
    '引用\n下一行\n\n示意图 x^2\n\n名称\t说明\n文档\ta|b');
});

test('formatted link labels use the existing inline parser', () => {
  assert.equal(markdownToPlainText('[**阅读文档**](https://example.com)'), '阅读文档');
});

test('whole copy keeps the existing reasoning and tool policy in both formats', () => {
  const message = makeUIMessage('assistant', [
    { type: 'reasoning', reasoning: '第一行\n第二行', createdAt: '', finishedAt: null, metadata: null },
    { type: 'text', text: '**结论**', metadata: null },
    { type: 'tool', toolName: 'read_file', toolCallId: 't1', input: '{}', output: [],
      approvalState: { type: 'approved' }, metadata: null },
    { type: 'text', text: '[后续](https://example.com)', metadata: null },
  ]);
  assert.equal(messageCopyText(message, false), '结论\n\n后续');
  assert.equal(messageCopyText(message, true),
    '> **思考过程:**\n> 第一行\n> 第二行\n\n**结论**\n\n**工具调用:** `read_file`\n\n[后续](https://example.com)');
});

test('blank text and separators do not create phantom copied content', () => {
  assert.equal(markdownToPlainText(' \n\n'), '');
  assert.equal(markdownToPlainText('---'), '');
});
