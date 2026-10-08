// tool_loop.test.ts — agentic 工具循环(D-057 TDD)
//
// Android 基准: GenerationHandler.kt:161-391 + ChatService:1349-1352
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  runChatTurnWithTools, runToolLoopContinuation, runAgenticToolLoop,
  runRegenerateAtWithTools,
} from '../main/ets/chat/tool_loop.ts';
import type { ToolLoopOptions } from '../main/ets/chat/tool_loop.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatStreamProvider, ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';
import { makeConversation, currentMessages, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { MessageChunk, UIMessage, UIMessagePart, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { makeUIMessage, makeUserMessage, makeAssistantMessage, toText } from '../main/ets/chat/message.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { ChatToolDefinition } from '../main/ets/chat/provider_model.ts';
import type { JsonValue } from '../main/ets/chat/json.ts';
import { createTimeTool } from '../main/ets/chat/builtin_time_tool.ts';
import { createToolRegistry } from '../main/ets/chat/tool_registry.ts';
import { createToolSearchTool } from '../main/ets/chat/builtin_introspection_tools.ts';
import { jevUnverifiedChanges } from '../main/ets/chat/jev_completion.ts';

// ===== 构造助手 =====

const textDelta = (text: string): MessageChunk => ({
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
      annotations: [], createdAt: '2026-07-28T00:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    },
    message: null, finishReason: 'unknown',
  }],
  usage: null,
});

interface ScriptedProvider extends ChatStreamProvider {
  calls: UIMessage[][];
  callCount: number;
}

// 每次 streamText 调用取下一组 chunk(用尽后给空文本收尾)
const scriptedProvider = (script: MessageChunk[][]): ScriptedProvider => {
  const state: { calls: UIMessage[][]; idx: number } = { calls: [], idx: 0 };
  return {
    calls: state.calls,
    get callCount(): number { return state.calls.length; },
    streamText(messages: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> {
      state.calls.push(messages);
      const group: MessageChunk[] = state.idx < script.length ? script[state.idx] : [textDelta('(end)')];
      state.idx++;
      for (const c of group) onChunk(c);
      return Promise.resolve();
    },
  };
};

const baseDeps = (provider: ChatStreamProvider, store = createMemoryConversationStore()): ChatTurnDeps => ({
  assistant: makeAssistant({}),
  inputTransformers: [],
  outputTransformers: [],
  provider,
  store,
});

const echoTool = (name: string, needsApproval = false): AgentTool => makeAgentTool({
  name,
  description: `echo ${name}`,
  parameters: () => makeInputSchemaObj({ q: { type: 'string' } }),
  needsApproval,
  execute: (input: JsonValue): Promise<UIMessagePart[]> => {
    const q = (input as { q?: string }).q ?? '';
    return Promise.resolve([{ type: 'text', text: `ran:${name}:${q}`, metadata: null }]);
  },
});

const loopOf = (provider: ChatStreamProvider, tools: AgentTool[], extra: Partial<ToolLoopOptions> = {}): ToolLoopOptions => ({
  tools,
  makeProviderForStep: (_defs: ChatToolDefinition[]): ChatStreamProvider => provider,
  ...extra,
});

const toolPartOf = (m: UIMessage, callId: string): UIMessagePartTool =>
  m.parts.find((p: UIMessagePart): boolean =>
    p.type === 'tool' && (p as UIMessagePartTool).toolCallId === callId) as UIMessagePartTool;

describe('runChatTurnWithTools 单步与循环', () => {
  it('非流式两次审批续跑保留工具结果，最终回复仍有待验证的修改事实', async () => {
    const path = 'notes/nonstream-continuation.ts';
    const responses = [
      toolDelta('write-call', 'file_write', JSON.stringify({ path, content: 'marker' })),
      toolDelta('edit-call', 'file_edit', JSON.stringify({ path, old_text: 'marker', new_text: 'verified' })),
      textDelta('已完成并验证文件修改'),
    ].map((chunk): MessageChunk => ({
      ...chunk, choices: chunk.choices.map((choice) => ({ ...choice, message: choice.delta, delta: null })),
    }));
    const calls: UIMessage[][] = [];
    const provider: ChatStreamProvider = {
      streamText: (): Promise<void> => Promise.reject(new Error('must use generateText')),
      generateText: (messages: UIMessage[]): Promise<MessageChunk> => {
        calls.push(messages);
        assert.ok(calls.length <= responses.length, 'tool continuation must terminate after the final reply');
        return Promise.resolve(responses[calls.length - 1]);
      },
    };
    const executed: string[] = [];
    const tools = ['file_write', 'file_edit'].map((name): AgentTool => makeAgentTool({
      name, description: name, needsApproval: true,
      execute: (): Promise<UIMessagePart[]> => {
        executed.push(name);
        return Promise.resolve([{ type: 'text', metadata: null,
          text: JSON.stringify(name === 'file_write' ? { path, size_bytes: 6 } : { path, replace_count: 1 }) }]);
      },
    }));
    const deps: ChatTurnDeps = { ...baseDeps(provider), assistant: makeAssistant({ streamOutput: false }) };
    const loop = loopOf(provider, tools);
    const approve = (conv: Conversation, id: string): Conversation => ({
      ...conv, messageNodes: conv.messageNodes.map((node) => ({
        ...node, messages: node.messages.map((message) => ({
          ...message, parts: message.parts.map((part): UIMessagePart =>
            part.type === 'tool' && part.toolCallId === id ? { ...part, approvalState: { type: 'approved' } } : part),
        })),
      })),
    });
    const writePending = await runChatTurnWithTools(makeConversation('nonstream-tools', []), '修改文件', deps, loop);
    assert.equal(toolPartOf(currentMessages(writePending).at(-1)!, 'write-call').approvalState.type, 'pending');
    const editPending = await runToolLoopContinuation(approve(writePending, 'write-call'), deps, loop);
    assert.equal(toolPartOf(currentMessages(editPending).at(-1)!, 'edit-call').approvalState.type, 'pending');
    const out = await runToolLoopContinuation(approve(editPending, 'edit-call'), deps, loop);
    const canonical = currentMessages(out);
    assert.deepEqual(executed, ['file_write', 'file_edit']);
    assert.equal(calls.length, 3);
    for (const id of ['write-call', 'edit-call']) {
      const savedTool = toolPartOf(canonical.at(-1)!, id);
      const sentTool = toolPartOf(calls[2].at(-1)!, id);
      assert.ok(savedTool, `${id} remains in canonical history`);
      assert.ok(sentTool, `${id} reaches the final provider request`);
      assert.equal(savedTool.output.length, 1);
      assert.equal(sentTool.output.length, 1);
    }
    assert.equal(toText(canonical.at(-1)!).trim(), '已完成并验证文件修改');
    assert.deepEqual(jevUnverifiedChanges(canonical)?.changedFiles, [path]);
  });

  it('tool call chunk 后 abort:保留部分工具消息但不执行工具副作用', async () => {
    const signalState: { aborted: boolean } = { aborted: false };
    let ran: boolean = false;
    const tool = makeAgentTool({
      name: 'conversation_search',
      description: 'must not run after abort',
      parameters: () => makeInputSchemaObj({ q: { type: 'string' } }),
      execute: (): Promise<UIMessagePart[]> => {
        ran = true;
        return Promise.resolve([{ type: 'text', text: 'unexpected', metadata: null }]);
      },
    });
    const provider: ChatStreamProvider = {
      streamText: (_messages: UIMessage[], onChunk: (c: MessageChunk) => void): Promise<void> => {
        onChunk(toolDelta('abort-call', 'conversation_search', '{"q":"x"}'));
        signalState.aborted = true;
        return Promise.reject(new Error('stream aborted'));
      },
    };
    const store = createMemoryConversationStore();
    const depsWithAbort: ChatTurnDeps = {
      ...baseDeps(provider, store),
      abortSignal: signalState,
    };
    const out = await runChatTurnWithTools(
      makeConversation('abort-tools', []), 'stop', depsWithAbort, loopOf(provider, [tool]));
    const last = currentMessages(out)[currentMessages(out).length - 1];
    const pending = toolPartOf(last, 'abort-call');
    assert.equal(ran, false);
    assert.equal(pending.output.length, 0);
  });

  it('无工具调用 → 单步结束(与单跑同构)', async () => {
    const conv: Conversation = makeConversation('c1', []);
    const provider = scriptedProvider([[textDelta('你好')]]);
    const store = createMemoryConversationStore();
    const out = await runChatTurnWithTools(
      conv, 'hi', baseDeps(provider, store), loopOf(provider, [echoTool('conversation_search')]));
    const msgs = currentMessages(out);
    assert.equal(msgs.length, 2);
    assert.equal(provider.callCount, 1);
  });
  it('工具调用 → 执行 → 结果写回同一 assistant 消息(不发 TOOL 消息)→ 第二步生成', async () => {
    const conv: Conversation = makeConversation('c1', []);
    const provider = scriptedProvider([
      [toolDelta('call-1', 'get_time_info', '{}')],
      [textDelta('现在三点')],
    ]);
    const store = createMemoryConversationStore();
    const out = await runChatTurnWithTools(
      conv, '几点了', baseDeps(provider, store), loopOf(provider, [createTimeTool()]));
    const msgs = currentMessages(out);
    // user + 单条 assistant(工具结果写回 + 第二步文本就地合并进同一消息,
    //   Android accumulator snapshot 全量覆盖末元素语义)
    assert.equal(msgs.length, 2);
    const toolMsg = msgs[1];
    const tp = toolPartOf(toolMsg, 'call-1');
    assert.equal(tp.output.length, 1);
    const payload = JSON.parse((tp.output[0] as { text: string }).text);
    assert.equal(typeof payload.timestamp_ms, 'number');
    // 第二步文本合入同一 assistant 消息尾部
    const lastPart = toolMsg.parts[toolMsg.parts.length - 1];
    assert.equal(lastPart.type, 'text');
    assert.equal((lastPart as { text: string }).text, '现在三点');
    // 第二步 provider 收到的 messages 含工具输出(同一 assistant 消息内)
    const secondCall = provider.calls[1];
    const sentToolMsg = secondCall[secondCall.length - 1];
    const sentTool = toolPartOf(sentToolMsg, 'call-1');
    assert.equal(sentTool.output.length, 1);
    assert.equal(provider.callCount, 2);
  });
  it('第二步 provider 收到的工具定义来自 makeProviderForStep(toChatToolDefinition)', async () => {
    const conv: Conversation = makeConversation('c1', []);
    const provider = scriptedProvider([[textDelta('ok')]]);
    let seenDefs: ChatToolDefinition[] = [];
    const out = await runChatTurnWithTools(conv, 'hi', baseDeps(provider), {
      tools: [echoTool('conversation_search')],
      makeProviderForStep: (defs: ChatToolDefinition[]): ChatStreamProvider => {
        seenDefs = defs;
        return provider;
      },
    });
    void out;
    assert.equal(seenDefs.length, 1);
    assert.equal(seenDefs[0].name, 'conversation_search');
    assert.equal(seenDefs[0].parameters['type'], 'object');
  });
  it('tool step providerOverride 仍经过统一 provider wrapper', async () => {
    const provider = scriptedProvider([[textDelta('ok')]]);
    let wrapped: number = 0;
    const deps: ChatTurnDeps = {
      ...baseDeps(provider),
      wrapStreamProvider: (source: ChatStreamProvider): ChatStreamProvider => {
        wrapped += 1;
        return source;
      },
    };
    await runChatTurnWithTools(
      makeConversation('wrapped-step', []), 'hi', deps,
      loopOf(provider, [echoTool('conversation_search')]));
    assert.equal(wrapped, 1);
  });
});

describe('审批门(:288-339)', () => {
  it('needsApproval 工具 → 置 Pending + permission_trace → break(不执行)', async () => {
    const conv: Conversation = makeConversation('c1', []);
    let ran = false;
    const gated = makeAgentTool({
      name: 'memory_write', description: 'd', needsApproval: true,
      execute: (): Promise<UIMessagePart[]> => {
        ran = true;
        return Promise.resolve([{ type: 'text', text: 'wrote', metadata: null }]);
      },
    });
    const provider = scriptedProvider([
      [toolDelta('call-1', 'memory_write', '{"q":"x"}')],
      [textDelta('done')],
    ]);
    const out = await runChatTurnWithTools(
      conv, '记下来', baseDeps(provider), loopOf(provider, [gated]));
    assert.equal(ran, false);
    assert.equal(provider.callCount, 1); // 未进第二步
    const msgs = currentMessages(out);
    const tp = toolPartOf(msgs[msgs.length - 1], 'call-1');
    assert.equal(tp.approvalState.type, 'pending');
    assert.ok(tp.metadata !== null && tp.metadata['permission_trace'] !== undefined);
  });
  it('resume:approved 工具 → 续跑直接执行(跳过生成)→ 写回 → 再生成', async () => {
    // 先跑出 pending 态
    const conv0: Conversation = makeConversation('c1', []);
    const gated = makeAgentTool({
      name: 'memory_write', description: 'd', needsApproval: true,
      execute: (): Promise<UIMessagePart[]> =>
        Promise.resolve([{ type: 'text', text: 'wrote:x', metadata: null }]),
    });
    const provider = scriptedProvider([
      [toolDelta('call-1', 'memory_write', '{"q":"x"}')],
      [textDelta('已写入')],
    ]);
    const pendingConv = await runChatTurnWithTools(
      conv0, '记', baseDeps(provider), loopOf(provider, [gated]));
    // 用户批准(改写 approvalState)
    const approved: Conversation = {
      ...pendingConv,
      messageNodes: pendingConv.messageNodes.map((n) => ({
        ...n,
        messages: n.messages.map((m) => ({
          ...m,
          parts: m.parts.map((p): UIMessagePart => {
            if (p.type === 'tool' && (p as UIMessagePartTool).toolCallId === 'call-1') {
              return { ...(p as UIMessagePartTool), approvalState: { type: 'approved' } };
            }
            return p;
          }),
        })),
      })),
    };
    const out = await runToolLoopContinuation(
      approved, baseDeps(provider), loopOf(provider, [gated]));
    const msgs = currentMessages(out);
    // 写回 + 续写都在同一条 assistant 消息内
    const tp = toolPartOf(msgs[msgs.length - 1], 'call-1');
    assert.equal((tp.output[0] as { text: string }).text, 'wrote:x');
    assert.equal(provider.callCount, 2); // 只多一次生成(resume 步不生成)
  });
  it('denied 工具 resume → denied JSON 写回(不触达 execute)', async () => {
    let ran = false;
    const gated = makeAgentTool({
      name: 'memory_write', description: 'd', needsApproval: true,
      execute: (): Promise<UIMessagePart[]> => {
        ran = true;
        return Promise.resolve([]);
      },
    });
    const provider = scriptedProvider([[textDelta('好吧')]]);
    const toolProvider = scriptedProvider([[toolDelta('call-1', 'memory_write', '{}')]]);
    const conv: Conversation = makeConversation('c1', []);
    const withDenied = await runChatTurnWithTools(
      conv, 'hi',
      baseDeps(toolProvider),
      loopOf(toolProvider, [gated]));
    const denied: Conversation = {
      ...withDenied,
      messageNodes: withDenied.messageNodes.map((n) => ({
        ...n,
        messages: n.messages.map((m) => ({
          ...m,
          parts: m.parts.map((p): UIMessagePart => {
            if (p.type === 'tool') {
              return { ...(p as UIMessagePartTool), approvalState: { type: 'denied', reason: '别写' } };
            }
            return p;
          }),
        })),
      })),
    };
    const out = await runToolLoopContinuation(denied, baseDeps(provider), loopOf(provider, [gated]));
    assert.equal(ran, false);
    const msgs = currentMessages(out);
    const tp = toolPartOf(msgs[msgs.length - 1], 'call-1');
    const payload = JSON.parse((tp.output[0] as { text: string }).text);
    assert.equal(payload.status, 'denied');
    assert.ok(String(payload.message).includes('别写'));
  });
});

describe('R08 空 toolCallId 写回按 part 身份', () => {
  // 非合规 provider 全程不给 id:两个 blank 工具,其中一个已在本步之前被批准/执行
  // (blank + 已执行 output),批次只含未执行的另一个。旧"双 blank 位置即对位"会把
  // 新结果写到已执行 part 上;身份回写必须只更新对应 part。
  it('blank 真子集 resume:结果只写到未执行的 blank part,已执行 blank 不动', async () => {
    const ran: string[] = [];
    const mkTool = (name: string): AgentTool => makeAgentTool({
      name,
      description: name,
      execute: (input: JsonValue): Promise<UIMessagePart[]> => {
        ran.push(`${name}:${String((input as { q?: string }).q ?? '')}`);
        return Promise.resolve([{ type: 'text', text: `out:${name}`, metadata: null }]);
      },
    });
    const done: UIMessagePartTool = {
      type: 'tool', toolCallId: '', toolName: 'file_read', input: '{"q":"old"}',
      output: [{ type: 'text', text: 'out:old', metadata: null }],
      approvalState: { type: 'auto' }, metadata: null,
    };
    const target: UIMessagePartTool = {
      type: 'tool', toolCallId: '', toolName: 'file_list', input: '{"q":"new"}',
      output: [], approvalState: { type: 'approved' }, metadata: null,
    };
    const conv: Conversation = makeConversation('c-blank', [
      toMessageNode(makeUserMessage('go')),
      toMessageNode(makeUIMessage('assistant', [done, target])),
    ]);
    const provider = scriptedProvider([[textDelta('done')]]);
    const out = await runToolLoopContinuation(
      conv, baseDeps(provider), loopOf(provider, [mkTool('file_read'), mkTool('file_list')]));
    const last = currentMessages(out)[currentMessages(out).length - 1];
    const tools = last.parts.filter(
      (p: UIMessagePart): boolean => p.type === 'tool') as UIMessagePartTool[];
    assert.deepEqual(ran, ['file_list:new']);
    // 已执行 blank part 结果不变
    assert.equal((tools[0].output[0] as { text: string }).text, 'out:old');
    // 未执行 blank part 拿到自己工具的结果(而非错配到 file_read)
    assert.equal((tools[1].output[0] as { text: string }).text, 'out:file_list');
  });
});

describe('预算与步数', () => {
  it('main public path clamps maxSteps 1 to 16 observable provider calls', async () => {
    const conv: Conversation = makeConversation('c1', []);
    const script: MessageChunk[][] = [];
    // 每步参数不同,确保验证步数预算而不是重复调用守护。
    for (let i: number = 0; i < 15; i++) {
      script.push([toolDelta(`main-clamp-${i}`, 'conversation_search', `{"q":"step-${i}"}`)]);
    }
    script.push([textDelta('final main step')]);
    const provider = scriptedProvider(script);

    await runChatTurnWithTools(conv, 'go', baseDeps(provider), {
      tools: [echoTool('conversation_search')],
      maxSteps: 1,
      makeProviderForStep: (): ChatStreamProvider => provider,
    });

    assert.equal(provider.callCount, 16);
  });
  it('工具循环不止:每步都调工具,直至 FINAL 步隐藏工具后模型纯文本收尾', async () => {
    const conv: Conversation = makeConversation('c1', []);
    // 16 步(maxSteps 下限):前 14 步返回工具调用,FINAL 步(stepIndex=14,remaining=2)
    //   hideTools → provider 无工具可用 → 脚本给文本
    const script: MessageChunk[][] = [];
    for (let i = 0; i < 14; i++) {
      script.push([toolDelta(`call-${i}`, 'conversation_search', `{"q":"step-${i}"}`)]);
    }
    script.push([textDelta('收敛答复')]);
    const provider = scriptedProvider(script);
    const seenTools: number[] = [];
    const out = await runChatTurnWithTools(conv, 'go', baseDeps(provider), {
      tools: [echoTool('conversation_search')],
      maxSteps: 16,
      makeProviderForStep: (defs: ChatToolDefinition[]): ChatStreamProvider => {
        seenTools.push(defs.length);
        return provider;
      },
    });
    const msgs = currentMessages(out);
    // 全部步骤合入同一 assistant 消息;FINAL 步文本收尾在末 part
    const lastMsg = msgs[msgs.length - 1];
    const lastPart = lastMsg.parts[lastMsg.parts.length - 1];
    assert.equal(lastPart.type, 'text');
    assert.equal((lastPart as { text: string }).text, '收敛答复');
    // 14 个工具调用全部执行写回同一消息,没有隐藏工具后的伪造调用。
    const toolParts: UIMessagePartTool[] = lastMsg.parts.filter(
      (p: UIMessagePart): boolean => p.type === 'tool') as UIMessagePartTool[];
    assert.equal(toolParts.length, 14);
    toolParts.forEach((part: UIMessagePartTool, i: number): void => {
      assert.equal((part.output[0] as { text: string }).text, `ran:conversation_search:step-${i}`);
    });
    assert.equal(provider.callCount, 15);
    // FINAL 步(stepIndex=14)隐藏工具(agentLoopShouldHideTools)
    assert.equal(seenTools[seenTools.length - 1], 0);
    assert.equal(seenTools[0], 1);
  });
  it('budget prompt 注入 dynamic system 块(WARN 阶段起)', async () => {
    const conv: Conversation = makeConversation('c1', []);
    // maxSteps=16,第一步 stepIndex=0 → remaining=16 → WARN(<=12? 16>12 → null)
    //   第二步 stepIndex=1 → remaining 15 → null;到 stepIndex=4 → remaining 12 → WARN
    const script: MessageChunk[][] = [];
    for (let i = 0; i < 4; i++) {
      script.push([toolDelta(`call-${i}`, 'conversation_search', `{"q":"step-${i}"}`)]);
    }
    script.push([textDelta('end')]);
    const provider = scriptedProvider(script);
    await runChatTurnWithTools(conv, 'go', baseDeps(provider), {
      tools: [echoTool('conversation_search')],
      maxSteps: 16,
      makeProviderForStep: (): ChatStreamProvider => provider,
    });
    // 第 5 次调用(stepIndex=4,remaining=12 → WARN)的 system 消息应含 budget 块
    const warnCall = provider.calls[4];
    const sysMsg = warnCall[0];
    assert.equal(sysMsg.role, 'system');
    const dynamicPart = sysMsg.parts.find((p: UIMessagePart): boolean =>
      p.type === 'text' && (p.metadata?.['system_prompt_block'] === 'dynamic'));
    assert.ok(dynamicPart !== undefined);
    assert.ok((dynamicPart as { text: string }).text.startsWith('Agent loop budget: 12 steps remain.'));
  });
});

describe('steer 与退化路径', () => {
  it('工具执行后 steer 消息入列,下一步生成带上', async () => {
    const conv: Conversation = makeConversation('c1', []);
    const provider = scriptedProvider([
      [toolDelta('call-1', 'conversation_search', '{}')],
      [textDelta('综合')],
    ]);
    let steerConsumed = false;
    const steerMsg: UIMessage = {
      id: 'steer-1', role: 'user',
      parts: [{ type: 'text', text: '补充一下', metadata: null }],
      annotations: [], createdAt: '2026-07-28T01:00:00Z', finishedAt: null,
      modelId: null, usage: null, translation: null,
    };
    const out = await runChatTurnWithTools(conv, '查', baseDeps(provider), {
      tools: [echoTool('conversation_search')],
      makeProviderForStep: (): ChatStreamProvider => provider,
      consumeSteerMessages: (): Promise<UIMessage[]> => {
        if (steerConsumed) return Promise.resolve([]);
        steerConsumed = true;
        return Promise.resolve([steerMsg]);
      },
    });
    const msgs = currentMessages(out);
    // user + assistant(工具) + user(steer) + assistant(最终)
    assert.equal(msgs.length, 4);
    assert.equal(msgs[2].role, 'user');
    // 第二步 provider 末条为 steer user 消息
    const secondCall = provider.calls[1];
    assert.equal(secondCall[secondCall.length - 1].id, 'steer-1');
  });
  it('空工具集 → 退化 runChatTurn(单步,无循环)', async () => {
    const conv: Conversation = makeConversation('c1', []);
    const provider = scriptedProvider([[textDelta('hi')]]);
    const out = await runChatTurnWithTools(
      conv, 'hi', baseDeps(provider), loopOf(provider, []));
    assert.equal(currentMessages(out).length, 2);
    assert.equal(provider.callCount, 1);
  });
  it('空输入 no-op(引用原样返回)', async () => {
    const conv: Conversation = makeConversation('c1', []);
    const provider = scriptedProvider([[textDelta('hi')]]);
    const out = await runChatTurnWithTools(
      conv, '', baseDeps(provider), loopOf(provider, [echoTool('conversation_search')]));
    assert.equal(out, conv);
    assert.equal(provider.callCount, 0);
  });
  it('continuation 空工具集 → 原样返回', async () => {
    const conv: Conversation = makeConversation('c1', []);
    const out = await runToolLoopContinuation(conv, baseDeps(scriptedProvider([])), loopOf(scriptedProvider([]), []));
    assert.equal(out, conv);
  });
});

describe('并行执行', () => {
  it('两个只读并行资格工具 → Promise.all 并行(结果按调用序)', async () => {
    const conv: Conversation = makeConversation('c1', []);
    const provider = scriptedProvider([
      [
        toolDelta('call-1', 'conversation_search', '{"q":"a"}'),
        toolDelta('call-2', 'conversation_search', '{"q":"b"}'),
      ],
      [textDelta('两个都查完')],
    ]);
    const out = await runChatTurnWithTools(
      conv, '查两个', baseDeps(provider), loopOf(provider, [echoTool('conversation_search')]));
    const msgs = currentMessages(out);
    const toolMsg = msgs[1];
    assert.equal((toolPartOf(toolMsg, 'call-1').output[0] as { text: string }).text, 'ran:conversation_search:a');
    assert.equal((toolPartOf(toolMsg, 'call-2').output[0] as { text: string }).text, 'ran:conversation_search:b');
  });
});

describe('runRegenerateAtWithTools 分支再生(ChatService.regenerateAtMessage:1078-1118)', () => {
  const seedConv = (): Conversation => makeConversation('c1', [
    toMessageNode(makeUserMessage('问题一')),
    toMessageNode(makeAssistantMessage('回答一')),
    toMessageNode(makeUserMessage('问题二')),
    toMessageNode(makeAssistantMessage('回答二')),
  ]);
  it('assistant 分支再生:工具执行写回占位节点 → 并入目标 alternatives(追加不覆盖)', async () => {
    const conv = seedConv();
    const targetNodeId = conv.messageNodes[3].id;
    const provider = scriptedProvider([
      [toolDelta('call-1', 'conversation_search', '{"q":"x"}')],
      [textDelta('再生回答')],
    ]);
    const out = await runRegenerateAtWithTools(
      conv, targetNodeId, baseDeps(provider), loopOf(provider, [echoTool('conversation_search')]));
    // 节点数不变(无 steer),目标节点多一个分支
    assert.equal(out.messageNodes.length, 4);
    const target = out.messageNodes[3];
    assert.equal(target.messages.length, 2);
    assert.equal(target.selectIndex, 1);
    const alt = target.messages[1];
    // 工具结果 + 收尾文本合入同一分支消息
    const tp = toolPartOf(alt, 'call-1');
    assert.equal((tp.output[0] as { text: string }).text, 'ran:conversation_search:x');
    const lastPart = alt.parts[alt.parts.length - 1];
    assert.equal((lastPart as { text: string }).text, '再生回答');
    // 原分支不动
    assert.equal(toText(target.messages[0]), '回答二');
    // 占位空消息不发往 provider(contextInput 过滤)
    const firstCall = provider.calls[0];
    assert.ok(!firstCall.some((m: UIMessage): boolean => m.parts.length === 0));
  });
  it('user 节点截断再生:截断先持久化,工具循环产出追加为新节点', async () => {
    const conv = seedConv();
    const userNodeId = conv.messageNodes[2].id;
    const provider = scriptedProvider([
      [toolDelta('call-1', 'conversation_search', '{}')],
      [textDelta('重跑回答')],
    ]);
    const out = await runRegenerateAtWithTools(
      conv, userNodeId, baseDeps(provider), loopOf(provider, [echoTool('conversation_search')]));
    // [u1, a1, u2] + 新 assistant 节点(工具 + 文本同一消息)
    assert.equal(out.messageNodes.length, 4);
    const msgs = currentMessages(out);
    assert.equal(msgs.length, 4);
    const tp = toolPartOf(msgs[3], 'call-1');
    assert.equal(tp.output.length, 1);
    const lastPart = msgs[3].parts[msgs[3].parts.length - 1];
    assert.equal((lastPart as { text: string }).text, '重跑回答');
  });
  it('空工具集 → 退化 runRegenerateAt(单步,分支追加)', async () => {
    const conv = seedConv();
    const targetNodeId = conv.messageNodes[3].id;
    const provider = scriptedProvider([[textDelta('单步再生')]]);
    const out = await runRegenerateAtWithTools(
      conv, targetNodeId, baseDeps(provider), loopOf(provider, []));
    assert.equal(provider.callCount, 1);
    const target = out.messageNodes[3];
    assert.equal(target.messages.length, 2);
    assert.equal(toText(target.messages[1]), '单步再生');
  });
});

// ===== D-062 懒暴露循环接线(GenerationHandler:159/:167/:183/:364) =====

describe('ToolExposureState 懒暴露接线', () => {
  it('>40 工具:首步仅常驻可见;tool_search expanded_tools 步间暴露', async () => {
    const hidden: AgentTool[] = [];
    for (let i = 0; i < 44; i++) hidden.push(echoTool(`hidden_tool_${i}`));
    const registry = createToolRegistry(hidden);
    const tools: AgentTool[] = [...hidden, createToolSearchTool(registry)];
    const capturedDefs: string[][] = [];
    const provider = scriptedProvider([
      [toolDelta('c1', 'tool_search', '{"query":"hidden_tool_7"}')],
      [textDelta('完成')],
    ]);
    const loop: ToolLoopOptions = {
      tools,
      makeProviderForStep: (defs: ChatToolDefinition[]): ChatStreamProvider => {
        capturedDefs.push(defs.map((d: ChatToolDefinition): string => d.name));
        return provider;
      },
    };
    const conv: Conversation = makeConversation('c-exp', []);
    const out = await runChatTurnWithTools(conv, 'hi', baseDeps(provider), loop);
    // 45 非发现工具 > 40 且含 tool_search → 懒开:首步仅 tool_search 常驻
    assert.deepEqual(capturedDefs[0], ['tool_search']);
    // tool_search 执行输出 expanded_tools=['hidden_tool_7'] →
    //   第二步暴露(toolsForStep 保原序:hidden_tool_7 在前)
    assert.deepEqual(capturedDefs[1], ['hidden_tool_7', 'tool_search']);
    // hidden_tool_7 第二步未被调用(模型只回了文本)→ 循环正常收尾;
    //   toText 非文本段映射 '' 再 '\n' join(Message.kt:164-169 逐字)
    //   → [tool, text('完成')] = '\n完成'
    assert.equal(toText(currentMessages(out)[currentMessages(out).length - 1]), '\n完成');
  });

  it('≤40 工具:懒模式关,步内 defs 恒全量(行为与 D-061 前一致)', async () => {
    const tools: AgentTool[] = [echoTool('conversation_search')];
    const capturedDefs: string[][] = [];
    const provider = scriptedProvider([
      [toolDelta('c1', 'conversation_search', '{"q":"x"}')],
      [textDelta('完')],
    ]);
    const loop: ToolLoopOptions = {
      tools,
      makeProviderForStep: (defs: ChatToolDefinition[]): ChatStreamProvider => {
        capturedDefs.push(defs.map((d: ChatToolDefinition): string => d.name));
        return provider;
      },
    };
    const conv: Conversation = makeConversation('c-full', []);
    await runChatTurnWithTools(conv, 'hi', baseDeps(provider), loop);
    assert.deepEqual(capturedDefs[0], ['conversation_search']);
    assert.deepEqual(capturedDefs[1], ['conversation_search']);
  });
});

// ===== D-063 推测执行接线(GenerationHandler:187-197/:519-526/:540-543/:356) =====

describe('SpeculativeToolRunner 接线', () => {
  it('enabled + streamOutput:flush 触发推测执行;未完结 → 丢弃后真实执行(共 2 次)', async () => {
    let calls: number = 0;
    const slowTool: AgentTool = makeAgentTool({
      name: 'spec_ro_tool',
      description: 'read only',
      execute: (_input: JsonValue): Promise<UIMessagePart[]> => {
        calls += 1;
        // 宏任务完结 — reusableResults(微任务链)时点恒未 settled
        return new Promise((resolve): void => {
          setTimeout((): void => {
            resolve([{ type: 'text', text: `run#${calls}`, metadata: null }]);
          }, 0);
        });
      },
    });
    const provider = scriptedProvider([
      [toolDelta('c1', 'spec_ro_tool', '{}')],
      [textDelta('收尾')],
    ]);
    const conv: Conversation = makeConversation('c-spec', []);
    const out = await runChatTurnWithTools(conv, 'hi', baseDeps(provider), {
      tools: [slowTool],
      makeProviderForStep: (_defs: ChatToolDefinition[]): ChatStreamProvider => provider,
      speculativeEnabled: true,
    });
    // 推测执行 1 次(flush 观察)+ 未完结丢弃 → dispatcher 真实执行 1 次
    assert.equal(calls, 2);
    // 第二步文本并入同一 assistant 消息(D-057 语义)→ 工具在末条消息
    const msgs = currentMessages(out);
    const tp = toolPartOf(msgs[msgs.length - 1], 'c1');
    assert.equal((tp.output[0] as { text: string }).text, 'run#2');
  });

  it('默认(未传 speculativeEnabled):不推测,仅真实执行 1 次', async () => {
    let calls: number = 0;
    const tool: AgentTool = makeAgentTool({
      name: 'spec_ro_tool',
      description: 'read only',
      execute: (_input: JsonValue): Promise<UIMessagePart[]> => {
        calls += 1;
        return Promise.resolve([{ type: 'text', text: 'ran', metadata: null }]);
      },
    });
    const provider = scriptedProvider([
      [toolDelta('c1', 'spec_ro_tool', '{}')],
      [textDelta('收尾')],
    ]);
    const conv: Conversation = makeConversation('c-nospec', []);
    await runChatTurnWithTools(conv, 'hi', baseDeps(provider), {
      tools: [tool],
      makeProviderForStep: (_defs: ChatToolDefinition[]): ChatStreamProvider => provider,
    });
    assert.equal(calls, 1);
  });
});
