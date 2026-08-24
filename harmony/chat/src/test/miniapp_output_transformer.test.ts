// miniapp_output_transformer — onGenerationFinish 解析/保存/改写(MiniAppOutputTransformer.kt)
import test from 'node:test';
import assert from 'node:assert/strict';
import { createMiniAppOutputTransformer } from '../main/ets/chat/miniapp/miniapp_output_transformer.ts';
import {
  mightContainMiniApp, revisionChangeNote,
} from '../main/ets/chat/miniapp/miniapp_output_transformer.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart, UIMessagePartMiniApp } from '../main/ets/chat/message.ts';
import {
  createMemoryMiniAppRepository,
} from '../main/ets/chat/miniapp/miniapp_repository.ts';
import type { MemoryMiniAppRepository, MiniAppRepository } from '../main/ets/chat/miniapp/miniapp_repository.ts';
import type { MiniAppGeneratedOutput, MiniAppRecord } from '../main/ets/chat/miniapp/miniapp_models.ts';
import type { TransformerContext } from '../main/ets/chat/transformer_pipeline.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';

const ctx: TransformerContext = { assistant: makeAssistant() };

const sha256Hex = (s: string): string => {
  let h: number = 0;
  for (let i: number = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
  return h.toString(16);
};

const makeRepo = (): MemoryMiniAppRepository =>
  createMemoryMiniAppRepository({ sha256Hex });

const html: string = '<!DOCTYPE html><html><body><script>Amber.toast(\'hi\')</script></body></html>';

const miniAppJson: string = JSON.stringify({
  title: '喝水记录器',
  description: '记录每天喝水量',
  icon: '水',
  category: 'tool',
  permissions: ['storage', 'toast', 'theme'],
  html,
});

const userRequest: string = '帮我做一个小应用：喝水记录器';

const assistantWithJson = (text: string): UIMessage =>
  makeUIMessage('assistant', [{ type: 'text', text, metadata: null }]);

const buildMessages = (assistantText: string): UIMessage[] => [
  makeUIMessage('user', [{ type: 'text', text: userRequest, metadata: null }]),
  assistantWithJson(assistantText),
];

test('mightContainMiniApp', () => {
  assert.equal(mightContainMiniApp(miniAppJson), true);
  assert.equal(mightContainMiniApp('plain text'), false);
  assert.equal(mightContainMiniApp('{"html":"x"}'), false);
});

test('revisionChangeNote 提取用户意见并跳过修订指令段', () => {
  const text: string = `修改小应用\nappId: 123\n用户修改意见：\n按钮改小一点\n第二行意见\n请基于下面的当前版本继续迭代,不要从零重写`;
  assert.equal(revisionChangeNote(text), '按钮改小一点\n第二行意见');
  assert.equal(revisionChangeNote('没有标记'), '没有标记');
  assert.equal(revisionChangeNote('用户修改意见：\n  \n'), 'MiniApp revision');
});

test('onGenerationFinish: enabled=false → 原引用;无显式请求 → 原引用', async () => {
  let enabled: boolean = false;
  const t = createMiniAppOutputTransformer({
    enabled: (): boolean => enabled,
    repository: makeRepo(),
  });
  const msgs: UIMessage[] = buildMessages(miniAppJson);
  assert.equal(await t.onGenerationFinish!(ctx, msgs), msgs);
  enabled = true;
  const notExplicit: UIMessage[] = [
    makeUIMessage('user', [{ type: 'text', text: '写一个工具调用方案', metadata: null }]),
    assistantWithJson(miniAppJson),
  ];
  assert.equal(await t.onGenerationFinish!(ctx, notExplicit), notExplicit);
});

test('onGenerationFinish: 已含 mini_app part → 不重复处理', async () => {
  const t = createMiniAppOutputTransformer({
    enabled: (): boolean => true,
    repository: makeRepo(),
  });
  const already: UIMessage = makeUIMessage('assistant', [
    { type: 'text', text: '已生成', metadata: null },
    {
      type: 'mini_app', appId: 'a', title: 't', description: 'd', iconEmoji: null,
      category: null, permissions: [], htmlHash: null, version: 1, metadata: null,
    },
  ]);
  const msgs: UIMessage[] = [
    makeUIMessage('user', [{ type: 'text', text: userRequest, metadata: null }]),
    already,
  ];
  assert.equal(await t.onGenerationFinish!(ctx, msgs), msgs);
});

test('onGenerationFinish: 非 MiniApp JSON → 原引用', async () => {
  const t = createMiniAppOutputTransformer({
    enabled: (): boolean => true,
    repository: makeRepo(),
  });
  const msgs: UIMessage[] = buildMessages('{"title":"ok","description":"d"}');
  assert.equal(await t.onGenerationFinish!(ctx, msgs), msgs);
});

test('onGenerationFinish: 生成路径 → text part 替换为状态文本 + 追加 mini_app part', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const t = createMiniAppOutputTransformer({
    enabled: (): boolean => true,
    repository: repo,
  });
  const assistant: UIMessage = makeUIMessage('assistant', [
    { type: 'reasoning', reasoning: 'thinking', createdAt: '2026-01-01T00:00:00Z', finishedAt: null, metadata: null },
    { type: 'text', text: `我生成如下：\n${miniAppJson}`, metadata: { m: 1 } },
  ]);
  const msgs: UIMessage[] = [
    makeUIMessage('user', [{ type: 'text', text: userRequest, metadata: null }]),
    assistant,
  ];
  const out: UIMessage[] = await t.onGenerationFinish!(ctx, msgs) as UIMessage[];
  const parts: UIMessagePart[] = out[1].parts;
  assert.equal(parts.length, 3);
  assert.equal(parts[0].type, 'reasoning');
  const status: UIMessagePart = parts[1];
  assert.equal(status.type, 'text');
  assert.equal((status as { text: string }).text, '已生成小应用：喝水记录器');
  assert.deepEqual((status as { metadata: unknown }).metadata, { m: 1 });
  const card: UIMessagePartMiniApp = parts[2] as UIMessagePartMiniApp;
  assert.equal(card.type, 'mini_app');
  assert.equal(card.title, '喝水记录器');
  assert.equal(card.description, '记录每天喝水量');
  assert.equal(card.iconEmoji, '水');
  assert.equal(card.category, 'tool');
  assert.deepEqual(card.permissions, ['storage', 'toast', 'theme']);
  assert.equal(card.htmlHash, sha256Hex(html));
  assert.equal(card.version, 1);
  assert.equal(card.metadata, null);
  // 落库
  const records: MiniAppRecord[] = await repo.listAll();
  assert.equal(records.length, 1);
  assert.equal(records[0].title, '喝水记录器');
  assert.equal(records[0].version, 1);
});

test('onGenerationFinish: 修订路径成功 → 更新 v2;失败 → 失败文案替换', async () => {
  const repo: MemoryMiniAppRepository = makeRepo();
  const first: MiniAppRecord = await repo.saveGenerated({
    title: '记录器', description: 'd', icon: null, category: 'tool', permissions: [], html,
  });
  const appId: string = first.id;
  const t = createMiniAppOutputTransformer({
    enabled: (): boolean => true,
    repository: repo,
  });
  const reviseRequest: string = `修改小应用\nappId: ${appId}\ncurrentVersion: 1\n用户修改意见：\n按钮改小一点`;
  const revised: MiniAppGeneratedOutput = {
    title: '记录器2', description: 'd2', icon: null, category: 'tool',
    permissions: ['toast'],
    html: '<!DOCTYPE html><html><body><button onclick="Amber.toast(\'go\')">新</button></body></html>',
  };
  const msgs: UIMessage[] = [
    makeUIMessage('user', [{ type: 'text', text: reviseRequest, metadata: null }]),
    assistantWithJson(JSON.stringify(revised)),
  ];
  const out: UIMessage[] = await t.onGenerationFinish!(ctx, msgs) as UIMessage[];
  const card: UIMessagePartMiniApp = out[1].parts[1] as UIMessagePartMiniApp;
  assert.equal((out[1].parts[0] as { text: string }).text, '已更新小应用：记录器2 v2');
  assert.equal(card.type, 'mini_app');
  assert.equal(card.title, '记录器2');
  assert.equal(card.version, 2);
  const updated: MiniAppRecord | null = await repo.getById(appId);
  assert.equal(updated !== null && updated.version, 2);
  assert.equal(updated !== null && updated.title, '记录器2');

  // 乐观并发冲突:base version 已过期 → 失败文案
  const staleRequest: string = `修改小应用\nappId: ${appId}\ncurrentVersion: 1\n用户修改意见：\n再来一次`;
  const msgs2: UIMessage[] = [
    makeUIMessage('user', [{ type: 'text', text: staleRequest, metadata: null }]),
    assistantWithJson(JSON.stringify(revised)),
  ];
  const out2: UIMessage[] = await t.onGenerationFinish!(ctx, msgs2) as UIMessage[];
  assert.equal(
    (out2[1].parts[0] as { text: string }).text,
    '小应用更新失败：目标小应用不存在，或已经被更新。请打开最新的小应用卡片后重新点击「修改」。',
  );
  assert.equal(out2[1].parts.length, 1);
});
