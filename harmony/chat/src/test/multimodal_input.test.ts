// multimodal input 多模态输入测试(runChatTurn parts 变体)
// Android 基准:ChatService.sendMessage(parts) — user 消息可含 text+image parts;
//   空输入(isEmptyInputMessage)→ no-op 不入列
// FileEncoder.kt:68-103:data:/http(s): 直通;file:// 读文件编码(compress = Android
//   图像处理,鸿蒙 MVP 不压缩,PARITY_DEBT 登记)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  makeConversation, makeAssistant,
} from '../main/ets/index.ts';
import type { Conversation, MessageChunk, UIMessage, UIMessagePart } from '../main/ets/index.ts';
import { runChatTurn, createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';

const chunkOf = (text: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{
    index: 0,
    delta: {
      id: 'd', role: 'assistant',
      parts: [{ type: 'text', text, metadata: null }],
      annotations: [], createdAt: '2026-07-28T00:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    },
    message: null, finishReason: 'unknown',
  }],
  usage: null,
});

const baseDeps = (): ChatTurnDeps => ({
  assistant: makeAssistant({ name: 'T' }),
  inputTransformers: [],
  outputTransformers: [],
  provider: {
    streamText: (_m: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> => {
      onChunk(chunkOf('ok'));
      return Promise.resolve();
    },
  },
  store: createMemoryConversationStore(),
});

describe('runChatTurn 多模态 parts 输入', () => {
  it('parts 输入:text+image 均入 user 消息,且发往 provider', async () => {
    const seen: UIMessage[][] = [];
    const deps: ChatTurnDeps = baseDeps();
    deps.provider = {
      streamText: (m: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> => {
        seen.push(m);
        onChunk(chunkOf('ok'));
        return Promise.resolve();
      },
    };
    const parts: UIMessagePart[] = [
      { type: 'text', text: '看图说话', metadata: null },
      { type: 'image', url: 'data:image/png;base64,QUJD', metadata: null },
    ];
    const out = await runChatTurn(makeConversation('c-mm', []), parts, deps);
    const userMsg = out.messageNodes[0].messages[0];
    assert.equal(userMsg.role, 'user');
    assert.equal(userMsg.parts.length, 2);
    assert.equal(userMsg.parts[1].type, 'image');
    // provider 收到的 internalMessages 也含 image part
    const sentUser = seen[0].find((m: UIMessage): boolean => m.role === 'user');
    assert.ok(sentUser !== undefined);
    assert.ok(sentUser.parts.some((p: UIMessagePart): boolean => p.type === 'image'));
  });

  it('纯空文本 parts → no-op(isEmptyInputMessage 语义),不持久化', async () => {
    const deps: ChatTurnDeps = baseDeps();
    const conv = makeConversation('c-empty', []);
    const out = await runChatTurn(conv, [{ type: 'text', text: '  ', metadata: null }], deps);
    assert.equal(out.messageNodes.length, 0);
    assert.equal((deps.store as ReturnType<typeof createMemoryConversationStore>).saved.length, 0);
  });

  it('字符串输入行为不变(回归)', async () => {
    const deps: ChatTurnDeps = baseDeps();
    const out = await runChatTurn(makeConversation('c-str', []), 'hello', deps);
    assert.equal(out.messageNodes.length, 2);
    const userMsg = out.messageNodes[0].messages[0];
    assert.equal(userMsg.parts[0].type === 'text'
      ? (userMsg.parts[0] as { text: string }).text : '', 'hello');
  });
});
