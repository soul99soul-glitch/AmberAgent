// suggestion 会话建议测试
// Android 基准:AiAuxiliaryGenerator.generateSuggestion(AiAuxiliaryGenerator.kt:81-134)
//   - 每轮生成完成后触发(无条件);content = takeLast(8) summaryAsText 摘要
//   - reasoning OFF;结果 split("\n") → trim → 滤空 → take(10) → 保存
//   - 生成前先清空旧 suggestions(in-memory flow,不落库);失败 onFailure 仅打印
//     (鸿蒙:domain 传播错误,entry 以 hilog 记录 — 非静默且不打扰 UI,见 D-026)
//   - DEFAULT_SUGGESTION_PROMPT(core/ai-prompts/Suggestion.kt:3-21,trimIndent 后逐字)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  makeConversation, toMessageNode, makeUserMessage, makeAssistantMessage,
} from '../main/ets/index.ts';
import type { Conversation } from '../main/ets/index.ts';
import {
  DEFAULT_SUGGESTION_PROMPT, buildSuggestionPrompt, runSuggestion,
} from '../main/ets/chat/suggestion.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';

const convWith = (texts: string[], suggestions: string[] = []): Conversation => ({
  ...makeConversation('conv-s', []),
  chatSuggestions: suggestions,
  messageNodes: texts.map((t: string, i: number) =>
    toMessageNode(i % 2 === 0 ? makeUserMessage(t) : makeAssistantMessage(t))),
});

describe('DEFAULT_SUGGESTION_PROMPT', () => {
  it('与 Android Suggestion.kt:3-21 逐字一致(trimIndent 后)', () => {
    const expected = `I will provide you with some chat content in the \`<content>\` block, including conversations between the User and the AI assistant.
You need to act as the **User** to reply to the assistant, generating 3~5 appropriate and contextually relevant responses to the assistant.

Rules:
1. If the assistant explicitly offers choices or next actions, prefer copying those choices as suggestions.
2. Reply directly with suggestions, do not add any formatting, and separate suggestions with newlines, no need to add markdown list formats.
3. Use {locale} language.
4. Ensure each suggestion is valid.
5. Each suggestion should usually stay within 24 characters unless copying an explicit option from the assistant.
6. Imitate the user's previous conversational style.
7. Act as a User, not an Assistant!

<content>
{content}
</content>`;
    assert.equal(DEFAULT_SUGGESTION_PROMPT, expected);
  });
});

describe('buildSuggestionPrompt', () => {
  it('{locale}/{content} 替换;content = 最后 8 条摘要', () => {
    const conv = convWith(['u1', 'a1', 'u2', 'a2', 'u3', 'a3', 'u4', 'a4', 'u5', 'a5']);
    const prompt = buildSuggestionPrompt(conv, '简体中文');
    assert.ok(prompt.includes('Use 简体中文 language.'));
    // takeLast(8):u1/a1 被排除,u2 起保留
    assert.ok(!prompt.includes('[USER]: u1'));
    assert.ok(prompt.includes('[USER]: u2'));
    assert.ok(prompt.includes('[ASSISTANT]: a5'));
  });
});

describe('runSuggestion', () => {
  it('按行拆分 → trim → 滤空 → 保存并返回', async () => {
    const conv = convWith(['u1', 'a1'], ['旧建议']);
    const store = createMemoryConversationStore();
    const out = await runSuggestion(conv, {
      generateText: (): Promise<string> => Promise.resolve('建议一\n\n  建议二  \n   \n建议三'),
      store,
      locale: '简体中文',
    });
    assert.deepEqual(out.chatSuggestions, ['建议一', '建议二', '建议三']);
    assert.equal(store.saved.length, 1);
    assert.deepEqual(store.saved[0].chatSuggestions, ['建议一', '建议二', '建议三']);
  });

  it('超过 10 条 → take(10)', async () => {
    const conv = convWith(['u1', 'a1']);
    const lines: string = Array.from({ length: 14 }, (_v, i: number): string => `s${i}`).join('\n');
    const out = await runSuggestion(conv, {
      generateText: (): Promise<string> => Promise.resolve(lines),
      store: createMemoryConversationStore(),
      locale: 'English',
    });
    assert.equal(out.chatSuggestions.length, 10);
    assert.equal(out.chatSuggestions[9], 's9');
  });

  it('模型返回空 → suggestions 清空(替换旧值,非保留)', async () => {
    const conv = convWith(['u1', 'a1'], ['旧建议']);
    const out = await runSuggestion(conv, {
      generateText: (): Promise<string> => Promise.resolve(''),
      store: createMemoryConversationStore(),
      locale: 'English',
    });
    assert.deepEqual(out.chatSuggestions, []);
  });

  it('模型抛错 → 传播、不持久化、会话不变', async () => {
    const conv = convWith(['u1', 'a1'], ['旧建议']);
    const store = createMemoryConversationStore();
    await assert.rejects(
      () => runSuggestion(conv, {
        generateText: (): Promise<string> => Promise.reject(new Error('boom')),
        store,
        locale: 'English',
      }),
      /boom/,
    );
    assert.equal(store.saved.length, 0);
  });

  it('实际发送 prompt 含会话摘要与 locale(端到端口径)', async () => {
    const conv = convWith(['推荐几本科幻小说', 'a1']);
    let seen = '';
    await runSuggestion(conv, {
      generateText: (prompt: string): Promise<string> => { seen = prompt; return Promise.resolve('x'); },
      store: createMemoryConversationStore(),
      locale: 'English',
    });
    assert.ok(seen.includes('[USER]: 推荐几本科幻小说'));
    assert.ok(seen.includes('Use English language.'));
  });
});
