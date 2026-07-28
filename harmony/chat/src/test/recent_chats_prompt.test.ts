// recent_chats_prompt — 语义钉死(D-077b)
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  RECENT_CHATS_PROMPT_LIMIT, buildRecentChatsPrompt, prettyPrintRecentChatsJson,
} from '../main/ets/chat/recent_chats_prompt.ts';
import type { RecentChatItem } from '../main/ets/chat/recent_chats_prompt.ts';

test('RECENT_CHATS_PROMPT_LIMIT = 10(DAO LIMIT 语义)', () => {
  assert.equal(RECENT_CHATS_PROMPT_LIMIT, 10);
});

test('prettyPrintRecentChatsJson: kotlinx prettyPrint 4 空格形状', () => {
  const items: RecentChatItem[] = [
    { title: '聊天气泡', lastChat: '2026年7月28日' },
    { title: 'second "quoted"', lastChat: 'Jul 27, 2026' },
  ];
  const json: string = prettyPrintRecentChatsJson(items);
  assert.equal(json, [
    '[',
    '    {',
    '        "title": "聊天气泡",',
    '        "last_chat": "2026年7月28日"',
    '    },',
    '    {',
    '        "title": "second \\"quoted\\"",',
    '        "last_chat": "Jul 27, 2026"',
    '    }',
    ']',
  ].join('\n'));
});

test('buildRecentChatsPrompt: 空 → \'\';非空模板逐字(appendLine 序列)', () => {
  assert.equal(buildRecentChatsPrompt([]), '');
  const out: string = buildRecentChatsPrompt([{ title: 't1', lastChat: 'd1' }]);
  assert.ok(out.startsWith('\n**Recent Chats**\n'));
  assert.ok(out.includes(
    "These are some of the user's recent conversations across AmberAgent."
    + ' You can use them to understand user preferences:\n'));
  assert.ok(out.endsWith('\n'));
  // 完整形状
  assert.equal(out, '\n**Recent Chats**\n'
    + "These are some of the user's recent conversations across AmberAgent."
    + ' You can use them to understand user preferences:\n'
    + '[\n    {\n        "title": "t1",\n        "last_chat": "d1"\n    }\n]'
    + '\n');
});
