// 兼容原公开路径；角色、专属 Markdown codec、文件仓库与工具各保唯一实现。
export {
  DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS,
  DEFAULT_SUB_AGENT_TIMEOUT_MS,
  DEFAULT_SUB_AGENT_MAX_TURNS,
  DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  makeSubAgentOverride,
  makeSubAgentDefinition,
  makeSubAgentRuntimeSetting,
  subAgentApplyOverride,
  SUB_AGENT_BUILT_INS,
  subAgentFindDefinition,
  SUB_AGENT_BUILT_IN_IDS,
  subAgentExtractMentions,
} from './subagent_definitions.ts';
export type {
  SubAgentMode,
  SubAgentOverride,
  SubAgentDefinition,
  SubAgentDefinitionInit,
  SubAgentRuntimeSetting,
} from './subagent_definitions.ts';
export {
  DEFAULT_IMAGE_PROMPT_INJECTION,
  DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION,
  DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT,
  makeImagePromptInjectionConfig,
} from './agent_prompt_codec.ts';
export type {
  CouncilSettingStrategy,
  ImagePromptInjectionConfig,
} from './agent_prompt_codec.ts';
export {
  AgentPromptConfigRepository,
} from './agent_prompt_repository.ts';
export type {
  PromptConfigFilePort,
  PromptConfigWriteResult,
} from './agent_prompt_repository.ts';
export {
  createAgentPromptConfigTool,
} from './agent_prompt_tool.ts';
export type {
  AgentPromptSettingsPort,
  AgentPromptConfigToolDeps,
} from './agent_prompt_tool.ts';
