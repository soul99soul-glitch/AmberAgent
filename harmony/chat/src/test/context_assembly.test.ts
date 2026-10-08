// 上下文组装规格测试(system prompt 静态块 + 截断 + 会话默认 + 参数合并 + 预设播种)
//
// Android 基准:
//   GenerationHandler.kt buildSystemPromptParts(:712-767,静态块子集)
//     generateInternal(:413-468,system 前置 + preparedContext + params 合并)
//   PreferencesStore.kt resolveSessionDefaults(:419-438)
//   ChatService.kt:567-572(预设消息在会话创建时播种)
//   ai/core/SystemPromptMarkers.kt(metadata 常量)
//
// 裁剪(D-018):dynamic/tool prompt(memory/generativeUI/loopBudget/recentChats)= P1;
//   ConversationContextEngine 的 compact 路径 = P1,MVP 走 limitContext;
//   groupDefault/defaultReasoningLevel 由 settings 注入(不进纯逻辑层反查)。

import { test } from 'node:test';
import assert from 'node:assert/strict';

import {
  SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_EPHEMERAL,
  buildStaticSystemPromptParts, buildToolSystemPrompt, assembleInternalMessages,
  defaultReasoningLevelForModel, resolveSessionDefaults, mergeCustomParams,
  toChatModel, seedConversationWithPresets,
} from '../main/ets/chat/context_assembly.ts';
import { makeAgentTool } from '../main/ets/chat/tool.ts';
import { makeAssistant } from '../main/ets/chat/assistant.ts';
import { makeProviderModel } from '../main/ets/chat/provider_settings.ts';
import { makeUserMessage, makeAssistantMessage } from '../main/ets/chat/message.ts';
import type { UIMessagePartText } from '../main/ets/chat/message.ts';
import { makeConversation, currentMessages } from '../main/ets/chat/conversation.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';

// ===== 静态 system prompt 块 =====

test('static prompt: soul+systemPrompt \\n\\n 连接,blank 过滤,metadata 标记', () => {
  const parts = buildStaticSystemPromptParts('SOUL', makeAssistant({ systemPrompt: 'SP' }));
  assert.equal(parts.length, 1);
  assert.equal(parts[0].text, 'SOUL\n\nSP');
  const meta = parts[0].metadata as JsonObject;
  assert.equal(meta[SYSTEM_PROMPT_CACHE_CONTROL_METADATA], SYSTEM_PROMPT_CACHE_EPHEMERAL);
  assert.equal(meta['system_prompt_block'], 'static');

  assert.equal(buildStaticSystemPromptParts('', makeAssistant({ systemPrompt: 'SP' }))[0].text, 'SP');
  assert.equal(buildStaticSystemPromptParts('', makeAssistant({})).length, 0,
    'assistant.systemPrompt 默认 "" + soul 空 → 无静态块');
});

test('assembleInternalMessages: system 前置 + 截断只作用于会话消息(先截断后前置)', () => {
  const history: ReturnType<typeof makeUserMessage>[] = [];
  for (let i = 0; i < 6; i++) history.push(makeUserMessage(`m${i}`));
  const out = assembleInternalMessages({
    messages: history,
    assistant: makeAssistant({ systemPrompt: 'SP' }),
    agentSoul: '',
    contextMessageSize: 3,
  });
  assert.equal(out.length, 4, 'system + 3 条截断消息');
  assert.equal(out[0].role, 'system');
  assert.equal(out[1].role, 'user');
  const first = out[1].parts[0] as UIMessagePartText;
  assert.equal(first.text, 'm3', '保留末尾 3 条');
  // 无 system prompt → 不前置
  const noSys = assembleInternalMessages({
    messages: history, assistant: makeAssistant({}), agentSoul: '', contextMessageSize: 0,
  });
  assert.equal(noSys.length, 6, 'contextMessageSize 0 → 不截断');
  assert.equal(noSys[0].role, 'user');
});

// ===== resolveSessionDefaults(PreferencesStore.kt:419) =====

test('resolveSessionDefaults: AUTO→默认,显式保留,contextSize 0→组默认,maxTokens 回退链', () => {
  const auto = makeAssistant({});
  const d1 = resolveSessionDefaults(auto, null, 'high');
  assert.equal(d1.reasoningLevel, 'high', 'assistant AUTO → 注入默认');
  assert.equal(d1.contextMessageSize, 0);
  assert.equal(d1.maxTokens, null);

  const explicit = makeAssistant({ reasoningLevel: 'low', contextMessageSize: 20, maxTokens: 4096 });
  const d2 = resolveSessionDefaults(explicit, { contextMessageSize: 50, maxTokens: 8192 }, 'high');
  assert.equal(d2.reasoningLevel, 'low');
  assert.equal(d2.contextMessageSize, 20, 'assistant 显式优先');
  assert.equal(d2.maxTokens, 4096);

  const inherit = makeAssistant({ contextMessageSize: 0, maxTokens: null });
  const d3 = resolveSessionDefaults(inherit, { contextMessageSize: 50, maxTokens: 8192 }, 'auto');
  assert.equal(d3.contextMessageSize, 50, 'assistant 0 → 组默认');
  assert.equal(d3.maxTokens, 8192, 'assistant null → 组默认');
});

test('defaultReasoningLevelForModel: selected model family supplies Android AUTO default', () => {
  const reasoning = (modelId: string): ReturnType<typeof makeProviderModel> =>
    makeProviderModel({ modelId, abilities: ['reasoning'] });
  assert.equal(defaultReasoningLevelForModel(reasoning('gpt-5.4')), 'medium');
  assert.equal(defaultReasoningLevelForModel(reasoning('codex-mini')), 'medium');
  assert.equal(defaultReasoningLevelForModel(reasoning('openai/o3-mini')), 'medium');
  assert.equal(defaultReasoningLevelForModel(reasoning('deepseek-reasoner')), 'high');
  assert.equal(defaultReasoningLevelForModel(makeProviderModel({ modelId: 'glm-5' })), 'auto');

  const auto = resolveSessionDefaults(
    makeAssistant({ maxTokens: null }),
    null,
    defaultReasoningLevelForModel(reasoning('gpt-5.4')),
  );
  assert.equal(auto.reasoningLevel, 'medium');
  assert.equal(auto.maxTokens, null);

  const explicit = resolveSessionDefaults(
    makeAssistant({ reasoningLevel: 'low', maxTokens: 2048 }),
    null,
    defaultReasoningLevelForModel(makeProviderModel({ modelId: 'deepseek-reasoner' })),
  );
  assert.equal(explicit.reasoningLevel, 'low');
  assert.equal(explicit.maxTokens, 2048);
});

// ===== params 合并(GenerationHandler.kt:453-468) =====

test('mergeCustomParams: headers/bodies assistant 在前 model 在后;temperature/topP 取自 assistant', () => {
  const assistant = makeAssistant({
    temperature: 0.7, topP: 0.9,
    customHeaders: [{ name: 'A', value: '1' }],
    customBodies: [{ key: 'a', value: 1 }],
  });
  const model = makeProviderModel({
    customHeaders: [{ name: 'M', value: '2' }],
    customBodies: [{ key: 'm', value: 2 }],
  });
  const merged = mergeCustomParams(assistant, model);
  assert.deepEqual(merged.customHeaders, [{ name: 'A', value: '1' }, { name: 'M', value: '2' }]);
  assert.deepEqual(merged.customBodies, [{ key: 'a', value: 1 }, { key: 'm', value: 2 }]);
  assert.equal(merged.temperature, 0.7);
  assert.equal(merged.topP, 0.9);
});

// ===== toChatModel(ProviderModel → 请求侧子集) =====

// ===== 预设消息播种(ChatService.kt:567-572) =====

test('seedConversationWithPresets: 空会话 → presets 成节点;非空 → 不动', () => {
  const assistant = makeAssistant({ presetMessages: [makeAssistantMessage('预设1'), makeUserMessage('预设2')] });
  const fresh = makeConversation('c1', []);
  const seeded = seedConversationWithPresets(fresh, assistant);
  const msgs = currentMessages(seeded);
  assert.equal(msgs.length, 2);
  assert.equal(msgs[0].role, 'assistant');
  assert.equal(seeded.messageNodes.length, 2);

  const existing = makeConversation('c2', []);
  const withHistory = seedConversationWithPresets(existing, makeAssistant({}));
  assert.equal(withHistory.messageNodes.length, 0, '无 presets → 不动');
});

// ===== D-077a:tool system prompt 块(buildSystemPromptParts:739-741,:763-770) =====

test('buildToolSystemPrompt: 非 blank 收集 + \\n\\n 连接', () => {
  const model = toChatModel(makeProviderModel({ abilities: ['tool'] }));
  const noop = (): Promise<never[]> => Promise.resolve([]);
  const tools = [
    makeAgentTool({
      name: 'a', description: '', execute: noop,
      systemPrompt: (): string => 'PA',
    }),
    makeAgentTool({ name: 'b', description: '', execute: noop }), // 默认 '' → 过滤
    makeAgentTool({
      name: 'c', description: '', execute: noop,
      systemPrompt: (): string => '  ', // blank → 过滤
    }),
    makeAgentTool({
      name: 'd', description: '', execute: noop,
      systemPrompt: (): string => 'PD',
    }),
  ];
  assert.equal(buildToolSystemPrompt(tools, model, []), 'PA\n\nPD');
  assert.equal(buildToolSystemPrompt([], model, []), '');
});

test('buildToolSystemPrompt: model/messages 透传给每个工具', () => {
  const model = toChatModel(makeProviderModel({ modelId: 'm-x', abilities: ['tool'] }));
  const msgs = [makeUserMessage('hello')];
  let seen = '';
  const tools = [
    makeAgentTool({
      name: 'a', description: '', execute: (): Promise<never[]> => Promise.resolve([]),
      systemPrompt: (m, ms): string => {
        seen = `${m.modelId}|${ms.length}`;
        return 'P';
      },
    }),
  ];
  buildToolSystemPrompt(tools, model, msgs);
  assert.equal(seen, 'm-x|1');
});

test('assembleInternalMessages: tool_prompts 块附加于 dynamic 之后,metadata 逐字', () => {
  const out = assembleInternalMessages({
    messages: [makeUserMessage('m')],
    assistant: makeAssistant({ systemPrompt: 'SP' }),
    agentSoul: '',
    contextMessageSize: 0,
    extraSystemBlocks: ['DYN'],
    toolPrompt: 'TOOLP',
  });
  const sysParts = out[0].parts as UIMessagePartText[];
  assert.equal(sysParts.length, 3);
  assert.equal(sysParts[0].text, 'SP');
  assert.equal(sysParts[1].text, 'DYN');
  assert.equal((sysParts[1].metadata as JsonObject)['system_prompt_block'], 'dynamic');
  assert.equal(sysParts[2].text, 'TOOLP');
  assert.equal((sysParts[2].metadata as JsonObject)['system_prompt_block'], 'tool_prompts');
  // tool block 无 cache_control(:766-768 仅 system_prompt_block)
  assert.equal((sysParts[2].metadata as JsonObject)[SYSTEM_PROMPT_CACHE_CONTROL_METADATA], undefined);
});

test('assembleInternalMessages: toolPrompt blank/缺省 → 无 tool 块', () => {
  const base = {
    messages: [makeUserMessage('m')],
    assistant: makeAssistant({ systemPrompt: 'SP' }),
    agentSoul: '',
    contextMessageSize: 0,
  };
  assert.equal(assembleInternalMessages(base)[0].parts.length, 1);
  assert.equal(assembleInternalMessages({ ...base, toolPrompt: '  ' })[0].parts.length, 1);
});
