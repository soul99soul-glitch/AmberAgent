// subagent_tools — 五件公共 SubAgentTools(D-132c Task 8)
//
// Android 基准: feature/tools/impl/src/main/kotlin/app/amber/feature/tools/SubAgentTools.kt(全文 300 行)
//   五件顺序: subagent_list / subagent_start / subagent_read / subagent_wait / subagent_cancel
//   - listTool: roster 文本 + runtimeSummary/runtimeMode + dynamic_subagents/tool_profiles help
//   - startTool: 委派 manager.start(parentConversationId, input, parentToolsProvider())
//   - read/wait/cancel: 按 run_id 委派 manager;wait 默认 10000,manager 已 clamp 0..60000/200ms poll
//   - listTool.systemPrompt: roster + @-mention 强制委派指令 + self-loop guard;
//     council 状态由工具层单独读取(不塞回 Manager)
// 约束:
//   - parentToolsProvider 注入,返回已 profile 过的 parent base tools;公共工具不进入 child scope
//     (manager.start 自身已过滤 subagent_* 与 allowlist,工具层只透传)
//   - 不新建第二套 registry/dispatcher/manager service interface
//   - council enabled 由 SubAgentToolsDeps.isModelCouncilEnabled 单独读取既有 council settings
//
// 偏差适配登记:
//   - kotlinx JsonObject buildJsonObject/put → 显式 JsonObject 全字段构造(ArkTS 禁对象 spread)
//   - SubAgentToolProfile enum .entries → 本文件 SUB_AGENT_TOOL_PROFILES 常量数组
//     (与 SubAgentModels.kt wire 名逐字;toolProfileHelp = join(', '))
//   - ExternalCliToolRegistry.supportedToolIds → 本文件 EXTERNAL_CLI_TOOL_IDS
//     (ExternalCliToolRegistry.kt:31-126 逐字 id 顺序;first()=gemini_cli)
//     注意:不是 tool_policy.ts 的 EXTERNAL_CLI_COUNCIL_RUNNER_TYPES(后者额外含 'external_cli'/'cli')
//   - JsonElement.runId()/intOrNull/contentOrNull → 本文件输入助手

import type { JsonObject, JsonValue } from './json.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { UIMessage, UIMessagePart } from './message.ts';
import { toText } from './message.ts';
import type { AgentTool } from './tool.ts';
import type { SubAgentRunner } from './subagent_runner.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { SubAgentDefinition, SubAgentMode } from './agent_prompt_config.ts';
import { subAgentExtractMentions } from './agent_prompt_config.ts';
import type { SubAgentManager } from './subagent_manager.ts';

// ExternalCliToolRegistry.kt:31-126 — supportedToolIds = tools.map { it.id } 逐字顺序
const EXTERNAL_CLI_TOOL_IDS: string[] = [
  'gemini_cli', 'antigravity_cli', 'codex_cli', 'claude_code', 'kimi_cli',
];

// SubAgentModels.kt: SubAgentToolProfile enum wire 名(已为 lowercase + 下划线)
const SUB_AGENT_TOOL_PROFILES: string[] = [
  'none', 'read_only', 'workspace_read', 'web_read', 'history_read',
];

const toolProfileHelp = (): string => SUB_AGENT_TOOL_PROFILES.join(', ');

const dynamicSubagentHelp = (mode: SubAgentMode): string => {
  if (mode === 'smart_dynamic') {
    return 'smart_dynamic: create temporary custom_subagent definitions; built-in ids are hidden/disabled; English name may be auto-assigned';
  }
  return 'supported_with_validator: pass custom_subagent and omit subagent_id; name is optional, while invocation description, boundary prompt, report format, and tool profile/allowlist are validated';
};

export interface SubAgentToolsDeps {
  manager: SubAgentManager;
  runner?: SubAgentRunner;
  parentConversationId: string;
  // 只返回已经 profile 过的 parent base tools;public subagent_* 工具不进入 child scope
  // (manager.start 自身过滤 subagent_* 与 allowlist,本 provider 只透传 base tools)
  parentToolsProvider: () => AgentTool[];
  // 工具层单独读取既有 council settings,不塞回 Manager
  isModelCouncilEnabled: () => boolean;
}

const stringProp = (description: string): JsonObject => ({ type: 'string', description });
const integerProp = (description: string): JsonObject => ({ type: 'integer', description });
const numberProp = (description: string): JsonObject => ({ type: 'number', description });

// kotlinx JsonPrimitive.contentOrNull: string/number/boolean 取文本,其余 null
const contentOrNull = (input: JsonValue, key: string): string | null => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return null;
  const value: JsonValue | undefined = (input as JsonObject)[key];
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  return null;
};

// intOrNull: 仅整数(number 且 Number.isInteger)取值
const intOrNull = (input: JsonValue, key: string): number | null => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return null;
  const value: JsonValue | undefined = (input as JsonObject)[key];
  if (typeof value === 'number' && Number.isInteger(value)) return value;
  return null;
};

const runIdOf = (input: JsonValue): string => contentOrNull(input, 'run_id') ?? '';

const textPart = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

// roster 文本(SubAgentTools.kt:40-43):每角色 @id (name) / description / tools(sorted) / routing
const rosterText = (builtIns: SubAgentDefinition[]): string => {
  const blocks: string[] = builtIns.map((agent: SubAgentDefinition): string => {
    const routing: string = agent.routingHint.trim().length > 0
      ? `\nrouting:\n${agent.routingHint}`
      : '';
    const sortedTools: string = agent.toolAllowlist.slice().sort().join(', ');
    return `@${agent.id} (${agent.name})\ndescription: ${agent.description}\ntools: ${sortedTools}${routing}`;
  });
  return blocks.join('\n\n');
};

// === USER MENTION OVERRIDE 指令(SubAgentTools.kt:279-300 逐字) ===
export const buildSubAgentMentionOverrideDirective = (mentioned: string[]): string => {
  if (mentioned.length === 0) return '';
  let out: string = '\n=== USER MENTION OVERRIDE ===\n';
  const subAgentMentions: string[] = mentioned.filter((id: string): boolean => id !== 'council');
  if (mentioned.includes('council')) {
    out += `The user explicitly invoked @council. You MUST call model_council_start for this turn, using the user's message as the council objective. Prefer seat_strategy="agent_planned" with 3-5 planned_seats tailored to the topic; each planned seat should include name, role, system_prompt, and optional model_ref when the user named a model. If the user asks for a terminal CLI participant, set allow_external_cli=true and add a planned seat with runner_type="external_cli", external_tool one of ${EXTERNAL_CLI_TOOL_IDS.join(', ')}, and optional external_runtime/external_model. Then use model_council_wait/read and synthesize the verdict.\n`;
  }
  if (subAgentMentions.length > 0) {
    if (subAgentMentions.length === 1) {
      out += `The user explicitly invoked @${subAgentMentions[0]}. You MUST call subagent_start with subagent_id="${subAgentMentions[0]}" for this turn, filling the task fields from the user's message. After it reports, you may package or follow up on its result.\n`;
    } else {
      out += `The user explicitly invoked: ${subAgentMentions.map((id: string): string => `@${id}`).join(', ')}. You MUST start each of these via subagent_start (in parallel where the subtasks are independent). After they report, synthesize their results.\n`;
    }
  }
  out += '(If you have already started the requested subagent(s) or council run in this turn, do NOT start them again — proceed to wait/read their results.)\n';
  return out;
};

// === system prompt roster 主体(SubAgentTools.kt:60-120 逐字) ===
const buildRosterPrompt = (deps: SubAgentToolsDeps): string => {
  const builtIns: SubAgentDefinition[] = deps.manager.listBuiltIns();
  const mode: SubAgentMode = deps.manager.runtimeMode();
  let out: string = '=== Available Subagents ===\n';
  if (mode === 'smart_dynamic') {
    out += 'Smart dynamic mode is enabled. Ordinary built-in role ids are hidden and disabled. Design a temporary custom_subagent only when the task is complex, clearly bounded, and benefits from isolated work.\n';
    out += 'To delegate: call subagent_start(custom_subagent={description, system_prompt, optional name, tool_profile, optional tool_allowlist}, task={objective, output_format, tools_and_sources, boundaries, context}). If name is omitted or too generic, smart dynamic mode assigns a stable English display name for this run.\n';
    out += 'Never write textual <tool_call>/<function>/<parameter> blocks in your reply; use native tool calls only.\n';
    out += 'display_title is only the tool chip title; do not use it as the subagent name. Put a wanted role display name in custom_subagent.name.\n';
    out += 'custom_subagent.description must say when to invoke the temporary role, e.g. "Use when ..." or "何时调用：...".\n';
    out += 'custom_subagent.system_prompt must include explicit boundaries plus report/output instructions, e.g. "Boundaries: ... Report output as ...".\n';
    out += `tool_profile options: ${toolProfileHelp()}. Default is read_only. tool_allowlist can only narrow that profile; it cannot add write, terminal, send, install, delete, or subagent_* tools.\n`;
    out += 'Example: custom_subagent={"description":"Use when a bounded code-reading check is useful.","system_prompt":"Boundaries: read only, do not edit files, do not ask the user. Report output as findings with evidence and risks.","tool_profile":"workspace_read"}.\n';
  } else {
    out += 'You can delegate bounded subtasks to specialist subagents. Each subagent runs depth-1, with its own tool allowlist (and possibly its own model). Use them when a task is complex, clearly bounded, and benefits from isolation, a different model, or parallel viewpoints. Simple linear tasks must stay in the main agent.\n';
    out += '\n';
    out += 'To delegate: call subagent_start(subagent_id, task={objective, output_format, tools_and_sources, boundaries, context}). Run multiple in parallel by issuing back-to-back subagent_start calls before any subagent_wait. The user can watch the subagent\'s live Markdown panel; subagent_wait/read returns a compact structured result for you to synthesize.\n';
    out += 'To dynamically create a temporary role: call subagent_start(custom_subagent={name, description, system_prompt, tool_profile}, task={...}) and omit subagent_id entirely. custom_subagent.name may be Chinese or English; if omitted, the app assigns a stable display name. Broad names are rejected outside smart dynamic mode. Do not use placeholder ids like "custom" or "dynamic". For pure creative/internal tasks, set tool_profile="none".\n';
    out += 'display_title is only the tool chip title; do not use it as the subagent name. Put a wanted role display name in custom_subagent.name.\n';
  }
  out += '\n';
  builtIns.forEach((agent: SubAgentDefinition): void => {
    out += `@${agent.id} (${agent.name})\n`;
    out += `- ${agent.description}\n`;
    if (agent.routingHint.trim().length > 0) {
      agent.routingHint.split('\n').forEach((line: string): void => {
        const trimmed: string = line.trim();
        if (trimmed.length > 0) out += `  ${trimmed}\n`;
      });
    }
    out += '\n';
  });
  if (deps.isModelCouncilEnabled()) {
    out += '@council (Model Council)\n';
    out += '- @oracle 的多模型加强版：可用 agent_planned 自动设计 3-5 个议题相关席位，也可把外部 CLI 作为 external_cli 临时席位。\n';
    out += '  何时调用：长期影响重大、单一 oracle 不够稳的关键决策 • 想要多视角对比 • 高风险/不可逆操作前的合议。\n';
    out += '  何时不要：常规深思（直接 @oracle）• 时间紧 • 答案已经很清楚 • 简单任务。\n';
    out += `  调用方式：优先 model_council_start(seat_strategy="agent_planned", planned_seats=[name/role/system_prompt/model_ref? 或 runner_type=external_cli/external_tool=${EXTERNAL_CLI_TOOL_IDS[0]}]) → model_council_wait → model_council_read。\n`;
    out += '\n';
  }
  return out;
};

const buildSystemPrompt = (deps: SubAgentToolsDeps, messages: UIMessage[]): string => {
  const roster: string = buildRosterPrompt(deps);
  // rosterIds = built-ins + user-saved custom roles 的 id 集合;不含 'council'
  // (council 属 ModelCouncilTools prompt,subagent disabled 时仍可用)
  const builtIns: SubAgentDefinition[] = deps.manager.listBuiltIns();
  const rosterIds: string[] = builtIns.map((agent: SubAgentDefinition): string => agent.id);
  let lastUserText: string = '';
  for (let i: number = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === 'user') {
      lastUserText = toText(messages[i]);
      break;
    }
  }
  const mentioned: string[] = subAgentExtractMentions(lastUserText, rosterIds);
  return roster + buildSubAgentMentionOverrideDirective(mentioned);
};

export class SubAgentTools {
  private readonly deps: SubAgentToolsDeps;

  constructor(deps: SubAgentToolsDeps) {
    this.deps = deps;
  }

  tools(): AgentTool[] {
    return [
      this.listTool(),
      this.startTool(),
      this.readTool(),
      this.waitTool(),
      this.cancelTool(),
    ];
  }

  getTools(): AgentTool[] {
    return this.tools();
  }

  private listTool(): AgentTool {
    const deps: SubAgentToolsDeps = this.deps;
    return makeAgentTool({
      name: 'subagent_list',
      description: 'List built-in and user-defined subagents with routing rules. The roster is also injected into your system context — you only need to call this for the most up-to-date snapshot of runtime limits and any user-saved custom roles.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({}),
      execute: async (): Promise<UIMessagePart[]> => {
        const builtIns: SubAgentDefinition[] = deps.manager.listBuiltIns();
        const roster: string = rosterText(builtIns);
        const limits: JsonObject = deps.manager.runtimeSummary();
        const mode: SubAgentMode = deps.manager.runtimeMode();
        const payload: JsonObject = {
          status: 'ok',
          limits,
          mode,
          dynamic_subagents: dynamicSubagentHelp(mode),
          tool_profiles: toolProfileHelp(),
          roster,
          built_ins: roster,
        };
        return textPart(payload);
      },
      systemPrompt: (_model, messages: UIMessage[]): string => buildSystemPrompt(deps, messages),
    });
  }

  private startTool(): AgentTool {
    const deps: SubAgentToolsDeps = this.deps;
    return makeAgentTool({
      name: 'subagent_start',
      description: 'Start a built-in or dynamic subagent as an isolated task. Pass exactly one of subagent_id or custom_subagent. In smart dynamic mode, use custom_subagent. Minimal dynamic roles can provide only task.objective; the app will fill safe defaults, but best results come from adding description, boundaries, and report/output instructions.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        subagent_id: stringProp('Roster subagent id. Omit this when using custom_subagent; passing both is invalid. In smart dynamic mode ordinary built-ins are disabled; use custom_subagent instead. Do not pass placeholder ids like "custom" or "dynamic". For OfficePro / terminal scenarios, call the underlying tools directly instead of dispatching a subagent.'),
        custom_subagent: {
          type: 'object',
          description: 'Narrow dynamic subagent definition. name, description, and system_prompt are recommended, not strict: missing/generic names are replaced, and missing boundaries/report instructions are appended.',
          properties: {
            name: stringProp('Optional subagent display name. May be Chinese or English. Missing or broad names such as general/helper/万能/通用 are replaced with a stable generated name. Do not use root display_title as the name.'),
            description: stringProp('Recommended. Explain when to invoke this temporary role, e.g. "Use when ..." or "何时调用：...". If omitted or too terse, the app synthesizes a bounded invocation description.'),
            system_prompt: stringProp('Recommended. Include explicit boundaries plus report/output instructions, e.g. "Boundaries: read only... Report output as findings...". If omitted or too short, the app appends safe defaults.'),
            tool_profile: stringProp(`Optional. One of: ${toolProfileHelp()}. Default read_only. Use none for pure creative/internal tasks. If no tools are available for the profile, the subagent runs without tools.`),
            tool_allowlist: {
              type: 'array',
              description: 'Optional tool names. This can only narrow tool_profile; it cannot add write, terminal, send, install, delete, or subagent_* tools. subagent_* tools are always invalid.',
              items: { type: 'string' },
            },
            model_id: stringProp('Optional chat model UUID for this dynamic subagent. Omit to use the current chat model.'),
            temperature: numberProp('Optional sampling temperature, 0..2. Omit unless the user asks for sampling changes.'),
            reasoning_level: stringProp('Optional reasoning depth: off, auto, low, medium, high, xhigh, or max. Omit for the app default.'),
            max_turns: integerProp('Optional max turns. Capped by the SubAgent runtime setting.'),
            timeout_ms: integerProp('Optional timeout in milliseconds. Capped by the SubAgent runtime setting.'),
            output_budget_chars: integerProp('Optional output budget in characters. Capped by the SubAgent runtime setting.'),
          },
        },
        task: {
          type: 'object',
          description: 'Required task spec.',
          properties: {
            objective: stringProp('Required. The exact subtask objective.'),
            output_format: stringProp('Recommended. How the subagent should report back. Defaults to a concise summary with findings/evidence/risks/next steps.'),
            tools_and_sources: stringProp('Recommended. Which tools/sources it may use or should avoid. Defaults to granted tools only.'),
            boundaries: stringProp('Recommended. Scope limits, non-goals, and safety constraints. Defaults to staying within the objective, no subagents, report once and stop.'),
            context: stringProp('Optional. Minimal context needed for the task; do not paste the whole parent conversation, raw tool results, or large dumps.'),
            session_grant_id: stringProp('Optional. Only use when the user granted historical session access.'),
            history_query: stringProp('Optional. Query for history-read workflows.'),
            source_session_ids: {
              type: 'array',
              description: 'Optional session ids for granted history-read workflows.',
              items: { type: 'string' },
            },
            shard_index: {
              type: 'integer',
              description: 'Optional shard index for parallel history workflows.',
            },
            shard_count: {
              type: 'integer',
              description: 'Optional shard count for parallel history workflows.',
            },
          },
          required: ['objective'],
        },
      }, ['task']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const payload: JsonObject = await deps.manager.start(
          deps.parentConversationId,
          input as JsonObject,
          deps.parentToolsProvider(),
          deps.runner,
        );
        return textPart(payload);
      },
    });
  }

  private readTool(): AgentTool {
    const deps: SubAgentToolsDeps = this.deps;
    return makeAgentTool({
      name: 'subagent_read',
      description: 'Read subagent run status and compact structured result by run_id.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        run_id: stringProp('Subagent run id'),
      }, ['run_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const payload: JsonObject = await deps.manager.read(runIdOf(input));
        return textPart(payload);
      },
    });
  }

  private waitTool(): AgentTool {
    const deps: SubAgentToolsDeps = this.deps;
    return makeAgentTool({
      name: 'subagent_wait',
      description: 'Wait for a subagent run to complete, up to wait_timeout_ms. Use the maximum timeout (60000) on the FIRST call; if it returns running, immediately call wait again with the same arguments — do NOT spend any reasoning between waits, and do NOT narrate "still running, let me wait again". The wait blocks efficiently in the background; thinking between waits just burns tokens and clutters the timeline.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        run_id: stringProp('Subagent run id'),
        wait_timeout_ms: {
          type: 'integer',
          description: 'Maximum wait time in ms. Default 10000; CAPPED at 60000. Pass 60000 for the longest single wait. If the subagent is still running after the wait, call this tool again with the same run_id — do not reason between calls.',
        },
      }, ['run_id']),
      execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
        const waitMs: number = intOrNull(input, 'wait_timeout_ms') ?? 10000;
        const payload: JsonObject = await deps.manager.wait(runIdOf(input), waitMs, signal);
        return textPart(payload);
      },
    });
  }

  private cancelTool(): AgentTool {
    const deps: SubAgentToolsDeps = this.deps;
    return makeAgentTool({
      name: 'subagent_cancel',
      description: 'Cancel a running subagent task by run_id.',
      parameters: (): ReturnType<typeof makeInputSchemaObj> => makeInputSchemaObj({
        run_id: stringProp('Subagent run id'),
      }, ['run_id']),
      execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
        const payload: JsonObject = await deps.manager.cancel(runIdOf(input));
        return textPart(payload);
      },
    });
  }
}
