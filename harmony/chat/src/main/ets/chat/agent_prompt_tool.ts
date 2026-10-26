// 一个 prompt config 命令的文件写入与 settings 镜像顺序；不宣称跨文件/KV 事务。
import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { SubAgentRuntimeSetting } from './subagent_definitions.ts';
import type { AgentPromptConfigRepository, PromptConfigWriteResult } from './agent_prompt_repository.ts';
import type { ImagePromptInjectionConfig } from './agent_prompt_codec.ts';

// SettingsAggregator 抽象:subAgent/modelCouncil 两子树读写(entry RMW)
export interface AgentPromptSettingsPort<Setting> {
  getSubAgentSetting(): Promise<SubAgentRuntimeSetting>;
  updateSubAgentSetting(next: SubAgentRuntimeSetting): Promise<void>;
  getCouncilSetting(): Promise<Setting>;
  updateCouncilSetting(next: Setting): Promise<void>;
}

export interface AgentPromptConfigToolDeps<Setting, Seat> {
  repository: AgentPromptConfigRepository<Setting, Seat>;
  settings: AgentPromptSettingsPort<Setting>;
}

// :231-232 stringValue:contentOrNull?.trim().orEmpty()
const toolStringValue = (input: JsonValue, key: string): string => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return '';
  const v: JsonValue | undefined = (input as JsonObject)[key];
  return typeof v === 'string' ? v.trim() : '';
};

// :139-140 — contentOrNull?.toBooleanStrictOrNull():布尔直值或 'true'/'false'
//   严格串;其余 → undefined(保持 current)
const toolStrictBoolean = (input: JsonValue, key: string): boolean | undefined => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return undefined;
  const v: JsonValue | undefined = (input as JsonObject)[key];
  if (v === true) return true;
  if (v === false) return false;
  if (typeof v === 'string') {
    if (v === 'true') return true;
    if (v === 'false') return false;
  }
  return undefined;
};

// :141-144 — contentOrNull?.trim()(非字符串/缺 → undefined → current)
const toolTrimmedString = (input: JsonValue, key: string): string | undefined => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return undefined;
  const v: JsonValue | undefined = (input as JsonObject)[key];
  return typeof v === 'string' ? v.trim() : undefined;
};

// :225-229
const buildWriteResponse = (file: string, updatedId: string): JsonObject => ({
  status: 'ok',
  updated: updatedId,
  file,
});

const toolTextPart = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

export const createAgentPromptConfigTool = <Setting, Seat>(
  deps: AgentPromptConfigToolDeps<Setting, Seat>,
): AgentTool => makeAgentTool({
  name: 'agent_prompt_config',
  // trimIndent().replace("\n", " ") 逐字(:22-28)
  description: 'Read or update AmberAgent\'s local Markdown prompt configuration. Use this when the user asks to tune persistent defaults for image generation, context compaction handoff prompts, built-in SubAgent role prompts, or Model Council seat prompts. The tool writes files under app-private agent_prompts/ and mirrors SubAgent/Council changes into Settings immediately.',
  systemPrompt: (): string => 'AmberAgent has agent-editable local Markdown prompt configuration.\nUse `agent_prompt_config` instead of long-term memory when the user wants persistent behavior changes for:\n- image generation defaults or negative tendencies;\n- context compaction / handoff summary format;\n- built-in SubAgent role prompts (explorer, historian, oracle, designer, writer, fixer);\n- Model Council seat prompts.\nThe tool writes app-private Markdown files under `agent_prompts/` and mirrors SubAgent/Council prompt edits into settings. If the user says "以后生图都...", "压缩上下文以后...", "把 designer 改成...", "这个 council 席位以后...", or asks where these prompts live, inspect or update this config.',
  needsApproval: true,
  allowsAutoApproval: true,
  parameters: () => makeInputSchemaObj(
    {
      action: {
        type: 'string',
        enum: [
          'get',
          'update_image_generation',
          'update_context_compaction_prompt',
          'update_subagent_prompt',
          'update_council_seat_prompt',
          'sync_markdown_to_settings',
        ],
      },
      target: {
        type: 'string',
        description: 'Optional target for get: image_generation, context_compaction, subagents, model_council, or all.',
      },
      enabled: {
        type: 'boolean',
        description: 'For image_generation: whether default prompt injection is enabled.',
      },
      prompt: {
        type: 'string',
        description: 'New prompt content.',
      },
      negative_prompt: {
        type: 'string',
        description: 'For image_generation: constraints to avoid or de-emphasize.',
      },
      subagent_id: {
        type: 'string',
        description: 'Built-in SubAgent id, such as explorer, historian, oracle, designer, writer, or fixer.',
      },
      seat_id_or_role: {
        type: 'string',
        description: 'Model Council seat id, role, or visible seat name.',
      },
    },
    ['action'],
  ),
  execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
    const repo: AgentPromptConfigRepository<Setting, Seat> = deps.repository;
    const action: string = toolStringValue(input, 'action');
    if (action === 'get') {
      const subAgent: SubAgentRuntimeSetting = await deps.settings.getSubAgentSetting();
      const council: Setting = await deps.settings.getCouncilSetting();
      await repo.ensureMarkdownMirrors(subAgent, council);
      const targetRaw: string = toolStringValue(input, 'target');
      const target: string = targetRaw.length === 0 ? 'all' : targetRaw;
      const files: JsonObject = {};
      const markdown: JsonObject = {};
      if (target === 'all' || target === 'image_generation') {
        files['image_generation'] = repo.imagePromptFilePath();
      }
      if (target === 'all' || target === 'context_compaction') {
        files['context_compaction'] = repo.contextCompactionPromptFilePath();
      }
      if (target === 'all' || target === 'subagents') {
        files['subagents'] = repo.subAgentPromptFilePath();
      }
      if (target === 'all' || target === 'model_council') {
        files['model_council'] = repo.modelCouncilPromptFilePath();
      }
      if (target === 'all' || target === 'image_generation') {
        markdown['image_generation'] = repo.readFileText(repo.imagePromptFilePath());
      }
      if (target === 'all' || target === 'context_compaction') {
        // :123-124 quirk:先 readContextCompactionPrompt()(确保文件存在)再读原文
        await repo.readContextCompactionPrompt();
        markdown['context_compaction'] = repo.readFileText(repo.contextCompactionPromptFilePath());
      }
      if (target === 'all' || target === 'subagents') {
        markdown['subagents'] = repo.readFileText(repo.subAgentPromptFilePath());
      }
      if (target === 'all' || target === 'model_council') {
        markdown['model_council'] = repo.readFileText(repo.modelCouncilPromptFilePath());
      }
      const payload: JsonObject = {
        status: 'ok',
        target,
        files,
        markdown,
      };
      return toolTextPart(payload);
    }
    if (action === 'update_image_generation') {
      const current: ImagePromptInjectionConfig = await repo.readImageConfig();
      const enabledArg: boolean | undefined = toolStrictBoolean(input, 'enabled');
      const promptArg: string | undefined = toolTrimmedString(input, 'prompt');
      const negativeArg: string | undefined = toolTrimmedString(input, 'negative_prompt');
      const next: ImagePromptInjectionConfig = {
        enabled: enabledArg !== undefined ? enabledArg : current.enabled,
        defaultPrompt: promptArg !== undefined ? promptArg : current.defaultPrompt,
        negativePrompt: negativeArg !== undefined ? negativeArg : current.negativePrompt,
      };
      const result: PromptConfigWriteResult = await repo.writeImageConfig(next);
      return toolTextPart(buildWriteResponse(result.file, result.updatedId));
    }
    if (action === 'update_context_compaction_prompt') {
      const prompt: string = toolStringValue(input, 'prompt');
      if (prompt.length === 0) throw new Error('prompt is required');
      const result: PromptConfigWriteResult = await repo.writeContextCompactionPrompt(prompt);
      return toolTextPart(buildWriteResponse(result.file, result.updatedId));
    }
    if (action === 'update_subagent_prompt') {
      const subAgentId: string = toolStringValue(input, 'subagent_id');
      const prompt: string = toolStringValue(input, 'prompt');
      if (subAgentId.length === 0) throw new Error('subagent_id is required');
      if (prompt.length === 0) throw new Error('prompt is required');
      const setting: SubAgentRuntimeSetting = await deps.settings.getSubAgentSetting();
      const pair: [SubAgentRuntimeSetting, PromptConfigWriteResult] =
        await repo.writeSubAgentPrompt(setting, subAgentId, prompt);
      await deps.settings.updateSubAgentSetting(pair[0]);
      return toolTextPart(buildWriteResponse(pair[1].file, pair[1].updatedId));
    }
    if (action === 'update_council_seat_prompt') {
      const seatKey: string = toolStringValue(input, 'seat_id_or_role');
      const prompt: string = toolStringValue(input, 'prompt');
      if (seatKey.length === 0) throw new Error('seat_id_or_role is required');
      if (prompt.length === 0) throw new Error('prompt is required');
      const setting: Setting = await deps.settings.getCouncilSetting();
      const pair: [Setting, PromptConfigWriteResult] =
        await repo.writeModelCouncilSeatPrompt(setting, seatKey, prompt);
      await deps.settings.updateCouncilSetting(pair[0]);
      return toolTextPart(buildWriteResponse(pair[1].file, pair[1].updatedId));
    }
    if (action === 'sync_markdown_to_settings') {
      const subAgent: SubAgentRuntimeSetting = await deps.settings.getSubAgentSetting();
      const council: Setting = await deps.settings.getCouncilSetting();
      const nextSubAgent: SubAgentRuntimeSetting =
        await repo.applySubAgentMarkdownToSetting(subAgent);
      const nextCouncil: Setting = await repo.applyModelCouncilMarkdownToSetting(council);
      await deps.settings.updateSubAgentSetting(nextSubAgent);
      await deps.settings.updateCouncilSetting(nextCouncil);
      const payload: JsonObject = {
        status: 'ok',
        synced: true,
        subagents: repo.subAgentPromptFilePath(),
        model_council: repo.modelCouncilPromptFilePath(),
      };
      return toolTextPart(payload);
    }
    throw new Error(`Unsupported action: ${action}`);
  },
});
