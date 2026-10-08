// 上下文组装(system prompt 静态块 + 截断 + 会话默认 + 参数合并 + 预设播种)
//
// Android 基准:
//   GenerationHandler.kt buildSystemPromptParts(:712-767)/generateInternal(:413-468)
//   PreferencesStore.kt resolveSessionDefaults(:419-438)
//   ChatService.kt:567-572(预设消息在会话创建时播种)
//   ai/core/SystemPromptMarkers.kt
//
// 裁剪(D-018):
//   - dynamic/tool system prompt(memory/generativeUI/loopBudget/recentChats/tool prompts)= P1
//   - ConversationContextEngine compact 路径 = P1;MVP = limitContext(先截断后前置 system)
//   - groupDefault/defaultReasoningLevel 由调用方注入(settings 反查不进纯逻辑层)

import type { Assistant, CustomHeader } from './assistant.ts';
import type { Conversation } from './conversation.ts';
import { toMessageNode } from './conversation.ts';
import { limitContext } from './conversation.ts';
import type { UIMessage, UIMessagePartText } from './message.ts';
import { makeUIMessage } from './message.ts';
import type { ChatModel, CustomBody, Modality, ReasoningLevel } from './provider_model.ts';
import type { ProviderModel } from './provider_settings.ts';
import type { AgentTool } from './tool.ts';

// ===== SystemPromptMarkers.kt =====

export const SYSTEM_PROMPT_CACHE_CONTROL_METADATA = 'system_prompt_cache_control';
export const SYSTEM_PROMPT_CACHE_DISABLED = 'disabled';
export const SYSTEM_PROMPT_CACHE_EPHEMERAL = 'ephemeral';

// ===== 静态 system prompt 块(buildSystemPromptParts 静态子集,:721-724,:743-754) =====

export const buildStaticSystemPromptParts = (
  agentSoul: string,
  assistant: Assistant,
): UIMessagePartText[] => {
  const staticPrompt = [agentSoul, assistant.systemPrompt]
    .filter((s: string): boolean => s.trim().length > 0)
    .join('\n\n');
  if (staticPrompt.length === 0) return [];
  return [{
    type: 'text',
    text: staticPrompt,
    metadata: {
      [SYSTEM_PROMPT_CACHE_CONTROL_METADATA]: SYSTEM_PROMPT_CACHE_EPHEMERAL,
      system_prompt_block: 'static',
    },
  }];
};

// ===== tool system prompt 块(buildSystemPromptParts:739-741,:763-770) =====
// tools.mapNotNull { systemPrompt(model, messages).takeIf(isNotBlank) }.joinToString('\n\n')
export const buildToolSystemPrompt = (
  tools: AgentTool[], model: ChatModel, messages: UIMessage[],
): string => model.abilities.indexOf('tool') < 0 ? '' : tools
  .map((t: AgentTool): string => t.systemPrompt(model, messages))
  .filter((s: string): boolean => s.trim().length > 0)
  .join('\n\n');

// ===== internal messages 组装(generateInternal:434-446 骨架) =====

export interface AssembleInternalInput {
  messages: UIMessage[];
  assistant: Assistant;
  agentSoul: string;
  contextMessageSize: number;
  // D-057:动态 system 块(buildSystemPromptParts:726-736 — memory/loopBudget/
  //   generativeUI/recentChats 之现存子集);合并为一个 dynamic part 附于静态块后
  //   (metadata system_prompt_block:'dynamic',:755-761;无 cache_control)
  extraSystemBlocks?: string[];
  // D-077a:tool system prompt 块(:739-741 buildToolSystemPrompt 产出,
  //   :763-770 附加为第三 part,metadata system_prompt_block:'tool_prompts')
  toolPrompt?: string;
}

export const assembleInternalMessages = (input: AssembleInternalInput): UIMessage[] => {
  const systemParts: UIMessagePartText[] = buildStaticSystemPromptParts(input.agentSoul, input.assistant);
  const dynamicPrompt: string = (input.extraSystemBlocks ?? [])
    .filter((s: string): boolean => s.trim().length > 0)
    .join('\n\n');
  if (dynamicPrompt.length > 0) {
    systemParts.push({
      type: 'text',
      text: dynamicPrompt,
      metadata: { system_prompt_block: 'dynamic' },
    });
  }
  // D-077a:tool prompt 块(dynamic 之后,:763-770)
  const toolPrompt: string = input.toolPrompt ?? '';
  if (toolPrompt.trim().length > 0) {
    systemParts.push({
      type: 'text',
      text: toolPrompt,
      metadata: { system_prompt_block: 'tool_prompts' },
    });
  }
  const truncated = limitContext(input.messages, input.contextMessageSize);
  const out: UIMessage[] = [];
  if (systemParts.length > 0) {
    out.push(makeUIMessage('system', systemParts));
  }
  for (const m of truncated) out.push(m);
  return out;
};

// ===== resolveSessionDefaults(PreferencesStore.kt:419-438) =====

export interface SessionGroupDefault {
  contextMessageSize?: number;
  maxTokens?: number | null;
}

export interface ResolvedSessionDefaults {
  reasoningLevel: ReasoningLevel;
  contextMessageSize: number;
  maxTokens: number | null;
}

export const defaultReasoningLevelForModel = (model: ProviderModel): ReasoningLevel => {
  if (model.abilities.indexOf('reasoning') < 0) return 'auto';
  const modelId: string = model.modelId.toLowerCase();
  if (modelId.includes('gpt') || modelId.includes('codex') || /\bo\d+/.test(modelId)) {
    return 'medium';
  }
  if (modelId.includes('deepseek')) return 'high';
  return 'auto';
};

export const resolveSessionDefaults = (
  assistant: Assistant,
  groupDefault: SessionGroupDefault | null,
  defaultReasoningLevel: ReasoningLevel,
): ResolvedSessionDefaults => ({
  reasoningLevel: assistant.reasoningLevel === 'auto'
    ? defaultReasoningLevel
    : assistant.reasoningLevel,
  contextMessageSize: assistant.contextMessageSize === 0
    ? (groupDefault?.contextMessageSize ?? 0)
    : assistant.contextMessageSize,
  maxTokens: assistant.maxTokens ?? (groupDefault?.maxTokens ?? null),
});

// ===== params 合并(GenerationHandler.kt:453-468) =====

export interface MergedCustomParams {
  temperature: number | null;
  topP: number | null;
  customHeaders: CustomHeader[];
  customBodies: CustomBody[];
}

export const mergeCustomParams = (assistant: Assistant, model: ProviderModel): MergedCustomParams => ({
  temperature: assistant.temperature,
  topP: assistant.topP,
  customHeaders: [...assistant.customHeaders, ...model.customHeaders],
  customBodies: [...assistant.customBodies, ...model.customBodies],
});

// ===== ProviderModel → 请求侧 ChatModel(D-017 双模型映射) =====

export const toChatModel = (m: ProviderModel): ChatModel => ({
  modelId: m.modelId,
  displayName: m.displayName,
  abilities: m.abilities.slice(),
  inputModalities: m.inputModalities.slice() as Modality[],
  outputModalities: m.outputModalities.slice() as Modality[],
  tools: m.tools.slice(),
});

// ===== 预设消息播种(ChatService.kt:567-572:新会话创建时 presets 成初始节点) =====

export const seedConversationWithPresets = (
  conversation: Conversation,
  assistant: Assistant,
): Conversation => {
  if (assistant.presetMessages.length === 0) return conversation;
  if (conversation.messageNodes.length > 0) return conversation; // 仅创建时播种
  return {
    ...conversation,
    messageNodes: assistant.presetMessages.map(toMessageNode),
  };
};
