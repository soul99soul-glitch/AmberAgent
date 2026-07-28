// tool_loop.test.ts — agentic 工具循环(D-057 TDD)
//
// Android 基准: GenerationHandler.kt:161-391 + ChatService:1349-1352
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  runChatTurnWithTools, runToolLoopContinuation, runAgenticToolLoop,
  runRegenerateAtWithTools,
  MIN_AGENT_TOOL_LOOP_STEPS, MAX_AGENT_TOOL_LOOP_STEPS, DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS,
} from '../main/ets/chat/tool_loop.ts';
import type { ToolLoopOptions } from '../main/ets/chat/tool_loop.ts';
import { createMemoryConversationStore } from '../main/ets/chat/chat_turn.ts';
import type { ChatStreamProvider, ChatTurnDeps } from '../main/ets/chat/chat_turn.ts';
import { makeConversation, currentMessages, toMessageNode } from '../main/ets/chat/conversation.ts';
import type { Conversation } from '../main/ets/chat/conversation.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import type { MessageChunk, UIMessage, UIMessagePart, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { makeUserMessage, makeAssistantMessage, toText } from '../main/ets/chat/message.ts';
import { makeAgentTool, makeInputSchemaObj } from '../main/ets/chat/tool.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { ChatToolDefinition } from '../main/ets/chat/provider_model.ts';
import type { JsonValue } from '../main/ets/chat/json.ts';
import { createTimeTool } from '../main/ets/chat/builtin_time_tool.ts';

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

describe('预算与步数', () => {
  it('maxSteps coerce:1→16,9999→512,默认 256', () => {
    assert.equal(MIN_AGENT_TOOL_LOOP_STEPS, 16);
    assert.equal(MAX_AGENT_TOOL_LOOP_STEPS, 512);
    assert.equal(DEFAULT_AGENT_MAX_TOOL_LOOP_STEPS, 256);
  });
  it('工具循环不止:每步都调工具,直至 FINAL 步隐藏工具后模型纯文本收尾', async () => {
    const conv: Conversation = makeConversation('c1', []);
    // 16 步(maxSteps 下限):前 15 步返回工具调用,但 FINAL 步(stepIndex=15)
    //   hideTools → provider 无工具可用 → 脚本给文本
    const script: MessageChunk[][] = [];
    for (let i = 0; i < 15; i++) {
      script.push([toolDelta(`call-${i}`, 'conversation_search', '{"q":"x"}')]);
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
    // 15 个工具调用全部执行写回同一消息
    assert.equal(lastMsg.parts.filter((p: UIMessagePart): boolean => p.type === 'tool').length, 15);
    // FINAL 步(stepIndex=15)隐藏工具(agentLoopShouldHideTools)
    assert.equal(seenTools[seenTools.length - 1], 0);
    assert.equal(seenTools[0], 1);
  });
  it('budget prompt 注入 dynamic system 块(WARN 阶段起)', async () => {
    const conv: Conversation = makeConversation('c1', []);
    // maxSteps=16,第一步 stepIndex=0 → remaining=16 → WARN(<=12? 16>12 → null)
    //   第二步 stepIndex=1 → remaining 15 → null;到 stepIndex=4 → remaining 12 → WARN
    const script: MessageChunk[][] = [];
    for (let i = 0; i < 4; i++) {
      script.push([toolDelta(`call-${i}`, 'conversation_search', '{}')]);
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
