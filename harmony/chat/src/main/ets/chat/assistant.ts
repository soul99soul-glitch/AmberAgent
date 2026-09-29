// Assistant 全量模型(纯数据层)
//
// Android 基准:
//   core/model/.../Assistant.kt(30 字段,默认值逐字)+ AssistantRegex
//   core/model/.../Avatar.kt / LocalToolOption.kt / MainAgentToolProfile.kt
//
// D-016:
//   - messageTemplate 默认 '{{ message }}'(Android 原值;此前子集用 null,语义等价
//     因为默认模板套用后恒等 — 见 transformers.ts TemplateTransformer)
//   - 线格式(kotlinx JSON,DataStore 存储形态)见 assistant_serialize.ts
//   - Uuid → string;Float → number;Set → array(保序)

import { newId } from './ids.ts';
import type { UIMessage } from './message.ts';
import type { ReasoningLevel, CustomBody } from './provider_model.ts';

// ===== Avatar(Avatar.kt sealed) =====

export type Avatar =
  | { type: 'dummy' }
  | { type: 'emoji'; content: string }
  | { type: 'image'; url: string };

// ===== LocalToolOption(LocalToolOption.kt,13 variant,snake_case serial) =====

export type LocalToolOption =
  | 'javascript_engine' | 'time_info' | 'clipboard' | 'tts' | 'ask_user'
  | 'workspace_files' | 'terminal' | 'python' | 'screen_automation' | 'system_access'
  | 'webview' | 'icloud_drive' | 'webmount' | 'webmount_eval';

// ===== MainAgentToolProfile(MainAgentToolProfile.kt) =====

export type MainAgentToolProfile =
  | 'full' | 'minimal' | 'web_read' | 'workspace_read' | 'coding' | 'mobile_control';

// ===== CustomHeader(Provider.kt:85) =====

export interface CustomHeader {
  name: string;
  value: string;
}

// ===== AssistantRegex(Assistant.kt:125) =====

export type AssistantAffectScope = 'user' | 'assistant';

export interface AssistantRegex {
  id: string;
  name: string;
  enabled: boolean;
  findRegex: string;
  replaceString: string;
  affectingScope: AssistantAffectScope[];
  visualOnly: boolean;
}

export const makeAssistantRegex = (opts: Partial<AssistantRegex> = {}): AssistantRegex => ({
  id: opts.id ?? newId(),
  name: opts.name ?? '',
  enabled: opts.enabled ?? true,
  findRegex: opts.findRegex ?? '',
  replaceString: opts.replaceString ?? '',
  // Android 默认 emptySet();此前子集默认 ['user','assistant'] 偏离基准,D-016 校正
  affectingScope: opts.affectingScope ?? [],
  visualOnly: opts.visualOnly ?? false,
});

// ===== Assistant(Assistant.kt:15-50,30 字段,声明序) =====

export interface Assistant {
  id: string;
  chatModelId: string | null;
  imageGenerationModelId: string | null;
  name: string;
  avatar: Avatar;
  useAssistantAvatar: boolean;
  tags: string[];
  systemPrompt: string;
  temperature: number | null;
  topP: number | null;
  contextMessageSize: number;
  streamOutput: boolean;
  enableMemory: boolean;
  useGlobalMemory: boolean;
  enableRecentChatsReference: boolean;
  messageTemplate: string;
  presetMessages: UIMessage[];
  quickMessageIds: string[];
  regexes: AssistantRegex[];
  reasoningLevel: ReasoningLevel;
  maxTokens: number | null;
  customHeaders: CustomHeader[];
  customBodies: CustomBody[];
  mcpServers: string[];
  localTools: LocalToolOption[];
  toolProfile: MainAgentToolProfile;
  background: string | null;
  backgroundOpacity: number;
  enabledSkills: string[];
  enableTimeReminder: boolean;
  rememberedReasoningLevelsByModelId: Record<string, ReasoningLevel>;
}

// patchAssistant:返回应用 patch 后的新 Assistant(不可变更新)
// 鸿蒙增补工具:ArkTS entry(.ets)禁用接口对象展开(arkts-no-spread),
// 编辑页经此 helper 做字段更新,语义同 Kotlin data class copy()
export const patchAssistant = (base: Assistant, patch: Partial<Assistant>): Assistant => ({
  ...base,
  ...patch,
});

export const makeAssistant = (opts: Partial<Assistant> = {}): Assistant => ({
  id: opts.id ?? newId(),
  chatModelId: opts.chatModelId ?? null,
  imageGenerationModelId: opts.imageGenerationModelId ?? null,
  name: opts.name ?? '',
  avatar: opts.avatar ?? { type: 'dummy' },
  useAssistantAvatar: opts.useAssistantAvatar ?? false,
  tags: opts.tags ?? [],
  systemPrompt: opts.systemPrompt ?? '',
  temperature: opts.temperature ?? null,
  topP: opts.topP ?? null,
  contextMessageSize: opts.contextMessageSize ?? 0,
  streamOutput: opts.streamOutput ?? true,
  enableMemory: opts.enableMemory ?? false,
  useGlobalMemory: opts.useGlobalMemory ?? false,
  enableRecentChatsReference: opts.enableRecentChatsReference ?? false,
  messageTemplate: opts.messageTemplate ?? '{{ message }}',
  presetMessages: opts.presetMessages ?? [],
  quickMessageIds: opts.quickMessageIds ?? [],
  regexes: opts.regexes ?? [],
  reasoningLevel: opts.reasoningLevel ?? 'auto',
  maxTokens: opts.maxTokens ?? null,
  customHeaders: opts.customHeaders ?? [],
  customBodies: opts.customBodies ?? [],
  mcpServers: opts.mcpServers ?? [],
  localTools: opts.localTools ?? ['time_info'],
  toolProfile: opts.toolProfile ?? 'full',
  background: opts.background ?? null,
  backgroundOpacity: opts.backgroundOpacity ?? 1,
  enabledSkills: opts.enabledSkills ?? [],
  enableTimeReminder: opts.enableTimeReminder ?? false,
  rememberedReasoningLevelsByModelId: opts.rememberedReasoningLevelsByModelId ?? {},
});

// reasoningLevelForModel(Assistant.kt:52-62)
export const reasoningLevelForModel = (
  assistant: Assistant,
  modelId: string | null,
  defaultReasoningLevel: ReasoningLevel,
): ReasoningLevel => {
  const remembered = modelId !== null
    ? assistant.rememberedReasoningLevelsByModelId[modelId]
    : undefined;
  if (remembered !== undefined) return remembered;
  return assistant.reasoningLevel === 'auto' ? defaultReasoningLevel : assistant.reasoningLevel;
};

// withReasoningLevelForModel(core/model/Assistant.kt:64-77)— 写侧:
//   记忆 map 按 modelId 更新,assistant.reasoningLevel 同步置为新档位。
//   鸿蒙 map key = modelId 字符串(对齐读侧 reasoningLevelForModel,非 Uuid)
export const withReasoningLevelForModel = (
  assistant: Assistant,
  modelId: string | null,
  level: ReasoningLevel,
): Assistant => {
  const remembered: Record<string, ReasoningLevel> = { ...assistant.rememberedReasoningLevelsByModelId };
  if (modelId !== null) {
    remembered[modelId] = level;
  }
  return patchAssistant(assistant, {
    reasoningLevel: level,
    rememberedReasoningLevelsByModelId: remembered,
  });
};
