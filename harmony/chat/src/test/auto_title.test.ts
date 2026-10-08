// auto title 自动命名测试
// Android 基准:
//   - AiAuxiliaryGenerator.generateTitle(AiAuxiliaryGenerator.kt:27-71):
//     title 空白(或 force)→ titlePrompt.applyPlaceholders(locale, content) →
//     generateText(reasoning OFF) → trim → 保存;失败 runCatching → 会话不变,错误上浮
//   - content = currentMessages.takeLast(4).joinToString("\n\n") { summaryAsText() }
//   - DEFAULT_TITLE_PROMPT(core/ai-prompts/TitleSummary.kt:3-15,逐字)
// 裁剪:Android 用独立 titleModelId 设置;鸿蒙 MVP 由调用方注入 generateText Port
//   (entry 用当前 runtime 的 api.generateText),titleModel 设置项 = P1
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { makeConversation, toMessageNode, makeUserMessage, makeAssistantMessage } from '../main/ets/index.ts';
import type { Conversation } from '../main/ets/index.ts';
import { buildTitlePrompt, runAutoTitle } from '../main/ets/chat/auto_title.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';

const convWith = (texts: string[], title: string = ''): Conversation => ({
  ...makeConversation('conv-t', []),
  title,
  messageNodes: texts.map((t: string, i: number) =>
    toMessageNode(i % 2 === 0 ? makeUserMessage(t) : makeAssistantMessage(t))),
});

describe('buildTitlePrompt', () => {
  it('{locale}/{content} 占位替换;content = 最后 4 条 [ROLE]: 摘要', () => {
    const conv = convWith(['u1', 'a1']);
    const prompt = buildTitlePrompt(conv, '简体中文');
    assert.ok(prompt.includes('Summarize using 简体中文 language'));
    assert.ok(prompt.includes('<content>\n[USER]: u1\n\n[ASSISTANT]: a1\n</content>'));
    assert.ok(!prompt.includes('{locale}') && !prompt.includes('{content}'));
  });

  it('超过 4 条只取最后 4 条(takeLast(4))', () => {
    const conv = convWith(['u1', 'a1', 'u2', 'a2', 'u3', 'a3']);
    const prompt = buildTitlePrompt(conv, 'English');
    assert.ok(!prompt.includes('u1') && !prompt.includes('[ASSISTANT]: a1\n'));
    assert.ok(prompt.includes('[USER]: u2'));
    assert.ok(prompt.includes('[ASSISTANT]: a3'));
  });
});

describe('runAutoTitle', () => {
  it('title 空白 → 生成 trim 后标题并持久化', async () => {
    const conv = convWith(['u1', 'a1']);
    const store = createMemoryConversationStore();
    const out = await runAutoTitle(conv, {
      generateText: (_prompt: string): Promise<string> => Promise.resolve('  量子力学入门  '),
      store,
      locale: '简体中文',
    });
    assert.equal(out.title, '量子力学入门');
    assert.equal(store.saved.length, 1);
    assert.equal(store.saved[0].title, '量子力学入门');
    assert.ok(out.updateAt >= conv.updateAt);
  });

  it('title 非空白且非 force → 不调用模型,原样返回', async () => {
    const conv = convWith(['u1', 'a1'], '已有标题');
    let called = 0;
    const out = await runAutoTitle(conv, {
      generateText: (): Promise<string> => { called++; return Promise.resolve('x'); },
      store: createMemoryConversationStore(),
      locale: '简体中文',
    });
    assert.equal(called, 0);
    assert.equal(out.title, '已有标题');
  });

  it('force=true → 即使已有标题也重新生成', async () => {
    const conv = convWith(['u1', 'a1'], '旧标题');
    const out = await runAutoTitle(conv, {
      generateText: (): Promise<string> => Promise.resolve('新标题'),
      store: createMemoryConversationStore(),
      locale: '简体中文',
    }, true);
    assert.equal(out.title, '新标题');
  });

  it('模型抛错 → 传播且会话不变、不持久化(runCatching 语义由调用方上浮)', async () => {
    const conv = convWith(['u1', 'a1']);
    const store = createMemoryConversationStore();
    await assert.rejects(
      () => runAutoTitle(conv, {
        generateText: (): Promise<string> => Promise.reject(new Error('boom')),
        store,
        locale: '简体中文',
      }),
      /boom/,
    );
    assert.equal(store.saved.length, 0);
  });

  it('prompt 实际发送内容含会话摘要(端到端口径)', async () => {
    const conv = convWith(['什么是黑洞', 'a1']);
    let seen = '';
    await runAutoTitle(conv, {
      generateText: (prompt: string): Promise<string> => { seen = prompt; return Promise.resolve('t'); },
      store: createMemoryConversationStore(),
      locale: 'English',
    });
    assert.ok(seen.includes('[USER]: 什么是黑洞'));
    assert.ok(seen.includes('Summarize using English language'));
  });
});
