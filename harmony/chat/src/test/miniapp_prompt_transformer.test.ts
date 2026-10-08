// miniapp_prompt_transformer — 显式意图判定/修订链路/注入行为(MiniAppPromptTransformer.kt)
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  createMiniAppPromptTransformer, isExplicitMiniAppRequest, revisionAppId, revisionVersion, MINI_APP_INSTRUCTION
} from '../main/ets/chat/miniapp/miniapp_prompt_transformer.ts';

import { makeUserMessage, makeAssistantMessage, toText } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';
import type { MiniAppRecord } from '../main/ets/chat/miniapp/miniapp_models.ts';
import type { TransformerContext } from '../main/ets/chat/transformer_pipeline.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';

// ===== 显式意图(MiniAppPromptTransformerTest.onlyExplicitMiniAppRequestsTrigger) =====

test('only explicit miniapp requests trigger', () => {
  assert.equal(isExplicitMiniAppRequest('帮我做一个小应用：喝水记录器'), true);
  assert.equal(isExplicitMiniAppRequest('Create a MiniApp for timers'), true);
  assert.equal(isExplicitMiniAppRequest('做个小程序'), true);

  assert.equal(isExplicitMiniAppRequest('做一个今日看板总结'), false);
  assert.equal(isExplicitMiniAppRequest('写一个工具调用方案'), false);
  assert.equal(isExplicitMiniAppRequest('生成一个计算器思路'), false);
});

// ===== PPT 消歧(MiniAppPromptTransformerTest.presentationRequests...) =====

test('presentation requests do not accidentally trigger miniapp harness', () => {
  assert.equal(isExplicitMiniAppRequest('不要做小应用，给我做 guizang PPT 预览'), false);
  assert.equal(isExplicitMiniAppRequest('别跑去做小应用，用 guizang-ppt-skill 做演示稿'), false);
  assert.equal(isExplicitMiniAppRequest('用 guizang skill 做一个演示，别给我小程序'), false);

  assert.equal(isExplicitMiniAppRequest('把这个 PPT 做成小应用'), true);
  assert.equal(isExplicitMiniAppRequest('生成一个幻灯片小应用版'), true);
});

// ===== 修订 appId/version 提取 =====

test('extracts revision appId from modify prompt', () => {
  const id: string = '123e4567-e89b-12d3-a456-426614174000';
  assert.equal(
    revisionAppId(`修改小应用\nappId: ${id}\n用户修改意见：\n按钮改小一点`),
    id,
  );
  assert.equal(
    revisionVersion(`修改小应用\nappId: ${id}\ncurrentVersion: 7`),
    7,
  );
  assert.equal(revisionAppId('没有 appId 的文本'), null);
  assert.equal(revisionVersion('currentVersion: abc'), null);
});

// ===== 指令自检(MiniAppPromptTransformerTest.miniAppInstructionUsesSelfCheck...) =====

// ===== Transformer 行为 =====

const ctx: TransformerContext = { assistant: makeAssistant() };

const htmlOf = (title: string): string =>
  `<!DOCTYPE html><html><body><script>Amber.toast('${title}')</script></body></html>`;

const recordFor = (id: string, version: number): MiniAppRecord => ({
  id,
  title: '测试小应用',
  description: 'desc',
  htmlContent: htmlOf('old'),
  sourceConversationId: null,
  sourceMessageId: null,
  iconEmoji: null,
  category: 'tool',
  permissions: ['toast'],
  pinned: false,
  runCount: 0,
  boardSummary: null,
  version,
  htmlHash: 'hash',
  createdAt: 0,
  updatedAt: 0,
});

test('transform: enabled=false → 原引用;非显式请求 → 原引用', async () => {
  let enabled: boolean = false;
  const t = createMiniAppPromptTransformer({
    enabled: (): boolean => enabled,
    loadApp: async (): Promise<MiniAppRecord | null> => null,
  });
  const msgs: UIMessage[] = [makeUserMessage('帮我做一个小应用')];
  assert.equal(await t.transform!(ctx, msgs), msgs);
  enabled = true;
  const notExplicit: UIMessage[] = [makeUserMessage('写一个工具调用方案')];
  assert.equal(await t.transform!(ctx, notExplicit), notExplicit);
});

test('transform: 显式请求 → 尾部追加指令,保留 metadata', async () => {
  const t = createMiniAppPromptTransformer({
    enabled: (): boolean => true,
    loadApp: async (): Promise<MiniAppRecord | null> => null,
  });
  const user: UIMessage = {
    ...makeUserMessage('帮我做一个小应用'),
    parts: [{ type: 'text', text: '帮我做一个小应用', metadata: { k: 'v' } }],
  };
  const msgs: UIMessage[] = [user, makeAssistantMessage('ok')];
  const out: UIMessage[] = await t.transform!(ctx, msgs) as UIMessage[];
  assert.equal(out.length, 2);
  const textPart: UIMessagePart = out[0].parts[0];
  assert.equal(textPart.type, 'text');
  const text: string = (textPart as { text: string }).text;
  assert.equal(text.endsWith(`\n\n${MINI_APP_INSTRUCTION}`), true);
  assert.equal(text.startsWith('帮我做一个小应用'), true);
  assert.deepEqual((textPart as { metadata: unknown }).metadata, { k: 'v' });
});

test('transform: 修订链路 missing → 缺失指令;revision → 修订指令 + safeHtmlContext', async () => {
  const id: string = '123e4567-e89b-12d3-a456-426614174000';
  let app: MiniAppRecord | null = null;
  const t = createMiniAppPromptTransformer({
    enabled: (): boolean => true,
    loadApp: async (appId: string): Promise<MiniAppRecord | null> =>
      appId === id ? app : null,
  });
  const request: string = `修改小应用\nappId: ${id}\n用户修改意见：\n按钮改小一点`;

  // missing
  const out1: UIMessage[] = await t.transform!(ctx, [makeUserMessage(request)]) as UIMessage[];
  assert.equal(toText(out1[0]).includes('目标小应用不存在或已被删除'), true);
  assert.equal(toText(out1[0]).includes('不要输出 MiniApp JSON'), true);

  // stale
  app = recordFor(id, 5);
  const staleReq: string = `修改小应用\nappId: ${id}\ncurrentVersion: 3\n用户修改意见：\n改大`;
  const out2: UIMessage[] = await t.transform!(ctx, [makeUserMessage(staleReq)]) as UIMessage[];
  assert.equal(toText(out2[0]).includes('已经从 v3 更新到 v5'), true);

  // revision
  const out3: UIMessage[] = await t.transform!(ctx, [makeUserMessage(request)]) as UIMessage[];
  const injected: string = toText(out3[0]);
  assert.equal(injected.includes('你必须基于下面的当前版本继续迭代'), true);
  assert.equal(injected.includes(htmlOf('old')), true);
  assert.equal(injected.includes('<miniapp-html-context>'), true);
  assert.equal(injected.includes(MINI_APP_INSTRUCTION), true);
});

test('transform: safeHtmlContext 转义闭合标签与围栏', async () => {
  const id: string = '123e4567-e89b-12d3-a456-426614174000';
  const app: MiniAppRecord = {
    ...recordFor(id, 1),
    htmlContent: '<div>a</div></miniapp-html-context><div>``` b</div>',
  };
  const t = createMiniAppPromptTransformer({
    enabled: (): boolean => true,
    loadApp: async (): Promise<MiniAppRecord | null> => app,
  });
  const out: UIMessage[] = await t.transform!(
    ctx, [makeUserMessage(`修改小应用\nappId: ${id}\n用户修改意见：\nx`)]) as UIMessage[];
  const text: string = toText(out[0]);
  const count = (haystack: string, needle: string): number =>
    haystack.split(needle).length - 1;
  // html 内的闭合标签被转义(模板自带的闭合行仍存在 → 恰好 1 处)
  assert.equal(count(text, '<\\/miniapp-html-context>'), 1);
  assert.equal(count(text, '</miniapp-html-context>'), 1);
  // html 内围栏被转义(模板无其它 ```)
  assert.equal(count(text, '` ` `'), 1);
  assert.equal(count(text, '```'), 0);
});
