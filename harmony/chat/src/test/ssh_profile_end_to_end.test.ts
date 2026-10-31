// ssh_profile_end_to_end.test.ts — 凭据「只写不读」的真实主链路
//
// 复刻 ConversationRunService.perform 的顺序:
//   生成工具调用 → 落盘(RDB 模拟:externalize = 脱敏) → 用户批准 → loadCanonical 重读
//   → runToolLoopContinuation → 工具执行。
// 这里不 mock 暂存表,全程走生产代码:
//   tool_loop 生成期 captureSecretToolInput、dispatcher 执行期 restoreSecretToolInput。

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createMemoryKeyValueStore } from '../main/ets/chat/kv_store.ts';
import { SSHProfileStore } from '../main/ets/chat/terminal/profile_store.ts';
import { createSSHProfileTools } from '../main/ets/chat/terminal/profile_tools.ts';
import type { SSHCredential, SSHCredentialBinding } from '../main/ets/chat/terminal/models.ts';
import type { SSHCredentialStorePort } from '../main/ets/chat/terminal/ports.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { ChatStreamProvider } from '../main/ets/chat/chat_turn.ts';
import type { ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeConversation, currentMessages } from '../main/ets/chat/conversation.ts';
import type { MessageChunk } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { runChatTurnWithTools, runToolLoopContinuation } from '../main/ets/chat/tool_loop.ts';
import type { ToolLoopOptions } from '../main/ets/chat/tool_loop.ts';
import { redactStoredToolInput } from '../main/ets/chat/tool_input_redaction.ts';
import { clearAllSecretToolInputs, secretToolInputCount } from '../main/ets/chat/tool_input_vault.ts';
import { externalizeConversation } from '../main/ets/chat/conversation_blobs.ts';
import type { ChatBlobPort } from '../main/ets/chat/conversation_blobs.ts';

class Credentials implements SSHCredentialStorePort {
  entries = new Map<string, SSHCredential>();
  async save(ref: string, _binding: SSHCredentialBinding, value: SSHCredential): Promise<void> {
    this.entries.set(ref, value);
  }
  async load(ref: string): Promise<SSHCredential | null> { return this.entries.get(ref) ?? null; }
  async exists(ref: string): Promise<boolean> { return this.entries.has(ref); }
  async delete(ref: string): Promise<void> { this.entries.delete(ref); }
}

const PASSWORD: string = 'pw-e2e-marker-5c71';

const textDelta = (text: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{
    index: 0,
    delta: {
      id: 'd', role: 'assistant',
      parts: [{ type: 'text', text, metadata: null }],
      annotations: [], createdAt: '2026-10-31T00:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    },
    message: null, finishReason: 'unknown',
  }],
  usage: null,
});

const toolDelta = (callId: string, name: string, input: string): MessageChunk => ({
  id: 'c', model: 'm',
  choices: [{
    index: 0,
    delta: {
      id: 'd', role: 'assistant',
      parts: [{
        type: 'tool', toolCallId: callId, toolName: name, input,
        output: [], approvalState: { type: 'auto' }, metadata: null,
      }],
      annotations: [], createdAt: '2026-10-31T00:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    },
    message: null, finishReason: 'unknown',
  }],
  usage: null,
});

const scriptedProvider = (script: MessageChunk[][]): ChatStreamProvider & { calls: UIMessage[][] } => {
  const calls: UIMessage[][] = [];
  return {
    calls,
    streamText(messages: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> {
      calls.push(messages);
      const group: MessageChunk[] = calls.length - 1 < script.length ? script[calls.length - 1] : [textDelta('(end)')];
      for (const c of group) onChunk(c);
      return Promise.resolve();
    },
  };
};

// RDB 模拟:externalizeConversation 是真实持久化路径(内部会脱敏 input)
const memoryBlobs = (): ChatBlobPort => {
  const data = new Map<string, string>();
  return {
    hash: (value: string): string => `h${value.length}`,
    write: async (hash: string, value: string): Promise<void> => { data.set(hash, value); },
    read: async (hash: string): Promise<string | null> => data.get(hash) ?? null,
    list: async (): Promise<string[]> => Array.from(data.keys()),
    remove: async (hash: string): Promise<void> => { data.delete(hash); },
  };
};

const toolPartOf = (conv: Conversation, callId: string): UIMessagePartTool => {
  for (const message of currentMessages(conv)) {
    for (const part of message.parts) {
      if (part.type === 'tool' && part.toolCallId === callId) return part;
    }
  }
  throw new Error(`tool call ${callId} not found`);
};

const approve = (conv: Conversation, callId: string): Conversation => ({
  ...conv,
  messageNodes: conv.messageNodes.map((node) => ({
    ...node,
    messages: node.messages.map((message) => ({
      ...message,
      parts: message.parts.map((part: UIMessagePart): UIMessagePart =>
        part.type === 'tool' && part.toolCallId === callId
          ? { ...part, approvalState: { type: 'approved' } } : part),
    })),
  })),
});

const setup = async () => {
  clearAllSecretToolInputs();
  const credentials = new Credentials();
  const profiles = await SSHProfileStore.create({ kv: createMemoryKeyValueStore(), credentials });
  const tools: AgentTool[] = createSSHProfileTools({ profiles });
  return { credentials, profiles, tools };
};

const callArgs = (): string => JSON.stringify({
  name: 'prod', host: '10.0.0.9', port: 22, username: 'deploy',
  auth_method: 'password', password: PASSWORD,
});

test('E2E: generated call is redacted in memory, survives loadCanonical, and executes after approval', async () => {
  const s = await setup();
  const provider = scriptedProvider([
    [toolDelta('ssh-call-1', 'ssh_profile_create', callArgs())],
    [textDelta('已创建')],
  ]);
  const deps: ChatTurnDeps = {
    assistant: makeAssistant({ streamOutput: false }), inputTransformers: [], outputTransformers: [],
    provider, store: createMemoryConversationStore(),
  };
  const loop: ToolLoopOptions = { tools: s.tools, makeProviderForStep: () => provider,
    needsApproval: () => true } as unknown as ToolLoopOptions;

  const pending: Conversation = await runChatTurnWithTools(makeConversation('e2e', []), '建个 SSH', deps, loop);
  const pendingPart: UIMessagePartTool = toolPartOf(pending, 'ssh-call-1');
  assert.equal(pendingPart.approvalState.type, 'pending');
  // 1) 内存里的 input 已经是占位符,明文只活在进程内暂存
  assert.equal(pendingPart.input.includes(PASSWORD), false);
  assert.ok(pendingPart.input.includes('***'));
  assert.equal(secretToolInputCount(), 1);

  // 2) 落盘 → loadCanonical 重读(externalizeConversation 是真实写库路径)
  const stored = (await externalizeConversation(pending, memoryBlobs())).conversation;
  const reloadedPart: UIMessagePartTool = toolPartOf(stored, 'ssh-call-1');
  assert.equal(reloadedPart.input.includes(PASSWORD), false);
  assert.equal(reloadedPart.input, pendingPart.input);

  // 3) 批准后 continuation 执行:真实凭据由暂存供给,写入成功
  const approved: Conversation = approve(stored, 'ssh-call-1');
  const out: Conversation = await runToolLoopContinuation(approved, deps, loop);
  const result = toolPartOf(out, 'ssh-call-1');
  const text: string = result.output.filter((p: UIMessagePart): boolean => p.type === 'text')
    .map((p: UIMessagePart): string => (p as UIMessagePartText).text).join('');
  assert.equal(JSON.parse(text)['status'], 'ok');
  assert.equal(JSON.parse(text)['action'], 'created');
  // 凭据真的存进去了,而且取完即删
  const saved = s.profiles.snapshot().profiles[0];
  assert.equal(saved.name, 'prod');
  assert.equal((await s.credentials.load(saved.credentialRef!))?.secret, PASSWORD);
  assert.equal(secretToolInputCount(), 0);
  // 工具结果与对话记录里都没有明文
  assert.equal(JSON.stringify(out).includes(PASSWORD), false);
});

test('E2E: a cold process (vault lost) fails closed instead of saving the placeholder', async () => {
  const s = await setup();
  const provider = scriptedProvider([
    [toolDelta('ssh-call-2', 'ssh_profile_create', callArgs())],
    [textDelta('done')],
  ]);
  const deps: ChatTurnDeps = {
    assistant: makeAssistant({ streamOutput: false }), inputTransformers: [], outputTransformers: [],
    provider, store: createMemoryConversationStore(),
  };
  const loop: ToolLoopOptions = { tools: s.tools, makeProviderForStep: () => provider,
    needsApproval: () => true } as unknown as ToolLoopOptions;
  const pending: Conversation = await runChatTurnWithTools(makeConversation('e2e2', []), '建个 SSH', deps, loop);
  const stored = (await externalizeConversation(pending, memoryBlobs())).conversation;

  // 进程重启:暂存清空
  clearAllSecretToolInputs();
  const out: Conversation = await runToolLoopContinuation(approve(stored, 'ssh-call-2'), deps, loop);
  const text: string = toolPartOf(out, 'ssh-call-2').output
    .filter((p: UIMessagePart): boolean => p.type === 'text')
    .map((p: UIMessagePart): string => (p as UIMessagePartText).text).join('');
  const payload = JSON.parse(text) as Record<string, unknown>;
  assert.equal(payload['status'], 'failed');
  assert.equal(payload['error_code'], 'secret_input_unavailable');
  assert.equal(payload['recoverable'], true);
  // 没有被写成 ***
  assert.equal(s.profiles.snapshot().profiles.length, 0);
  assert.equal(s.credentials.entries.size, 0);
});

test('E2E: denial clears the vault entry', async () => {
  const s = await setup();
  const provider = scriptedProvider([
    [toolDelta('ssh-call-3', 'ssh_profile_create', callArgs())],
    [textDelta('ok')],
  ]);
  const deps: ChatTurnDeps = {
    assistant: makeAssistant({ streamOutput: false }), inputTransformers: [], outputTransformers: [],
    provider, store: createMemoryConversationStore(),
  };
  const loop: ToolLoopOptions = { tools: s.tools, makeProviderForStep: () => provider,
    needsApproval: () => true } as unknown as ToolLoopOptions;
  const pending: Conversation = await runChatTurnWithTools(makeConversation('e2e3', []), '建个 SSH', deps, loop);
  assert.equal(secretToolInputCount(), 1);
  const denied: Conversation = {
    ...pending,
    messageNodes: pending.messageNodes.map((node) => ({
      ...node,
      messages: node.messages.map((message) => ({
        ...message,
        parts: message.parts.map((part: UIMessagePart): UIMessagePart =>
          part.type === 'tool' && part.toolCallId === 'ssh-call-3'
            ? { ...part, approvalState: { type: 'denied', reason: '测试' } } : part),
      })),
    })),
  };
  await runToolLoopContinuation(denied, deps, loop);
  assert.equal(secretToolInputCount(), 0);
});

test('E2E: request payload sent to the model never contains the credential', async () => {
  const s = await setup();
  const provider = scriptedProvider([
    [toolDelta('ssh-call-4', 'ssh_profile_create', callArgs())],
    [textDelta('已创建')],
  ]);
  const deps: ChatTurnDeps = {
    assistant: makeAssistant({ streamOutput: false }), inputTransformers: [], outputTransformers: [],
    provider, store: createMemoryConversationStore(),
  };
  const loop: ToolLoopOptions = { tools: s.tools, makeProviderForStep: () => provider,
    needsApproval: () => true } as unknown as ToolLoopOptions;
  const pending: Conversation = await runChatTurnWithTools(makeConversation('e2e4', []), '建个 SSH', deps, loop);
  await runToolLoopContinuation(approve(pending, 'ssh-call-4'), deps, loop);
  // 第二轮请求(continuation)带着上一轮的工具调用历史
  assert.ok(provider.calls.length >= 2);
  for (const call of provider.calls) {
    assert.equal(JSON.stringify(call).includes(PASSWORD), false);
  }
  // 直接确认脱敏函数覆盖了该输入
  assert.equal(redactStoredToolInput('ssh_profile_create', callArgs()).includes(PASSWORD), false);
});
