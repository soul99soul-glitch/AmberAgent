// placeholder_transformer 规格测试(D-073)
// Android 基准: PlaceholderTransformer.kt(全文)
import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  createPlaceholderTransformer,
  replaceAllIgnoreCase,
  replacePlaceholders,
} from '../main/ets/chat/placeholder_transformer.ts';
import type {
  PlaceholderValues,
} from '../main/ets/chat/placeholder_transformer.ts';
import { makeUserMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { UIMessage } from '../main/ets/chat/message.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { TransformerContext } from '../main/ets/chat/transformer_pipeline.ts';

const values = (overrides: Partial<PlaceholderValues> = {}): PlaceholderValues => ({
  curDate: (): string => 'DATE',
  curTime: (): string => 'TIME',
  curDatetime: (): string => 'DATETIME',
  modelId: 'gpt-test',
  modelName: 'GPT Test',
  localeName: (): string => 'LOCALE',
  timezoneName: (): string => 'TZ',
  systemVersion: 'SYSVER',
  deviceInfo: 'DEV',
  batteryLevel: (): string => '88',
  nickname: (): string => '',
  ...overrides,
});

const ctx = (assistantName: string): TransformerContext => ({
  assistant: makeAssistant({ name: assistantName }),
});

test('替换:{{key}} 与 {key} 双形态 + 大小写不敏感 + 全部替换(:173-178)', () => {
  const out: string = replacePlaceholders(
    '{{model_id}} {MODEL_ID} {{Model_Id}}', values(), 'A');
  assert.equal(out, 'gpt-test gpt-test gpt-test');
  const mixed: string = replacePlaceholders('{cur_date} at {{CUR_TIME}}', values(), 'A');
  assert.equal(mixed, 'DATE at TIME');
});

test('替换:值含 $ 不做 JS 特殊解释(Kotlin 字面语义);顺序执行后键命中前键产出', () => {
  const v = values({ nickname: (): string => 'Co$100' });
  assert.equal(replacePlaceholders('{nickname}', v, 'A'), 'Co$100');
  // 前键产出含后键形态:nickname='{char}' → 顺序执行到 char 时被替换
  const chained = values({ nickname: (): string => '{char}' });
  assert.equal(replacePlaceholders('{nickname}', chained, 'Hero'), 'Hero',
    'nickname 先插入 {char},char 键后执行命中(LinkedHashMap 序忠实)');
});

test('transform:text part 全量替换;非 text part 不动;assistant.name 注入 char', async () => {
  const t = createPlaceholderTransformer(values());
  const img: UIMessage = {
    ...makeUserMessage('x'),
    parts: [{ type: 'image', url: 'data:x', metadata: null }],
  };
  const msgs: UIMessage[] = [
    makeUserMessage('你好 {char},我是 {{user}}'),
    makeAssistantMessage('模型 {model_name}'),
    img,
  ];
  const out = await t.transform!(ctx('小琥'), msgs);
  const text0 = (out[0].parts[0] as { text: string }).text;
  assert.equal(text0, '你好 小琥,我是 user');
  const text1 = (out[1].parts[0] as { text: string }).text;
  assert.equal(text1, '模型 GPT Test');
  assert.equal(out[2].parts[0], img.parts[0], '非 text part 引用不动');
  assert.notEqual(out, msgs, '全量 copy(Android map+copy 无恒等短路)');
});

test('replaceAllIgnoreCase:正则元字符键/值安全', () => {
  assert.equal(replaceAllIgnoreCase('a.b a.B', 'a.b', 'X'), 'X X');
  assert.equal(replaceAllIgnoreCase('x{cur_date}y', '{cur_date}', '$&'), 'x$&y');
});
