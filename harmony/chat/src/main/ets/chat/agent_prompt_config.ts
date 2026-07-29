// agent_prompt_config — agent 可编辑 Markdown 提示词配置(D-128)
//
// Android 基准(逐字锚点):
//   feature/subagent/api/.../SubAgentModels.kt(子集:SubAgentMode/
//     SubAgentOverride/SubAgentDefinition/SubAgentRuntimeSetting/applyOverride
//     + 常量;TaskSpec/Run/Result/Status 随 subagent 运行子系统 = P1 登记)
//   feature/subagent/.../SubAgentDefinitions.kt(全文 325 行:六内置角色 +
//     find/builtInIds/extractMentions + rolePrompt 模板)
//   feature/prompts/AgentPromptConfigRepository.kt(全文 466 行)
//   app/core/ai/tools/AgentPromptConfigTool.kt(全文 232 行)
//   LocalTools.kt:183 — 无条件 add(agentPromptConfigTool)
//   core/settings/PreferencesStore.kt:150-177 — AgentRuntimeSetting.subAgent
//     /modelCouncil 两字段(设置 RMW 由 entry 端口承载)
//
// 偏差适配登记:
//   - Uuid → string(harmony 模型 id 为 string,既有约定);Set<String> → string[]
//   - java.io.File → PromptConfigFilePort(exists/readText/atomicWrite/mkdirs;
//     writeTextAtomically 语义 = 体量 require + parent mkdirs + tmp + rename,
//     rename 失败 copy+delete 兜底,entry fileIo 实现;POSIX rename 可覆盖)
//   - Kotlin data class copy(保留未知字段)→ CouncilSettingStrategy 注入
//     (council 席位/设置的全部字段读写经策略;entry 直配 deepread HAR
//     ModelCouncilRuntimeSetting/ModelCouncilSeat,extra 字段零丢失)
//   - suspend withContext(Dispatchers.IO)+Mutex → async + AsyncMutex(promise 链)
//   - Char.isWhitespace/isLetterOrDigit → ASCII 近似(extractMentions;真实 id
//     为 ASCII,异域空白差异登记,同 D-064 先例)
//   - kotlinx toBooleanStrictOrNull → 严格 'true'/'false' 串/布尔直值

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { ReasoningLevel } from './provider_model.ts';

// ===== 常量(SubAgentModels.kt:8-14 / Repository.kt:16-36,451-465) =====

export const DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS: number = 2;
export const DEFAULT_SUB_AGENT_TIMEOUT_MS: number = 5 * 60000;
export const DEFAULT_SUB_AGENT_MAX_TURNS: number = 4;
export const DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS: number = 12000;

export const DEFAULT_IMAGE_PROMPT_INJECTION: string =
  'Prefer clean composition, clear focal subject, natural lighting, coherent anatomy, restrained details, and a polished final image. Keep the scene readable at mobile size.';

export const DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION: string =
  'Overcrowded micro-details, muddy textures, distorted hands or faces, unreadable text, duplicated limbs, heavy artifacts, random decorative clutter, and low-contrast composition.';

export const DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT: string = `# Context Compaction Handoff

Write a continuation handoff for another model that will resume the same conversation.

Return valid JSON only. The JSON must include:
- \`schema_version\`: 2
- \`timeline_summary\`: 4-5 human-readable sentences in the user's language, written for the chat timeline
- \`handoff_markdown\`: dense Markdown for the next model, with sections: Goal, Constraints, Progress, Decisions, Current State, Next Steps, Critical Context, Relevant Files
- \`covered_compact_ids\`: the compact ids from the provided previous handoffs that this handoff carries forward
- \`source_message_ids\`: exactly the source ids provided for this compact pass
- \`created_at\`: the unix epoch millis provided by the app

The timeline summary is for humans. The handoff Markdown is for the model. Preserve concrete names, files, commands, errors, user preferences, approvals, rejected approaches, and unresolved decisions. Do not include raw tool logs unless they are needed to continue safely.`;

const DIRECTORY_NAME: string = 'agent_prompts';
const IMAGE_PROMPT_FILE: string = 'image-generation.md';
const SUB_AGENT_PROMPT_FILE: string = 'subagents.md';
const MODEL_COUNCIL_PROMPT_FILE: string = 'model-council.md';
const CONTEXT_COMPACTION_PROMPT_FILE: string = 'context-compaction-handoff.md';

const MAX_IMAGE_PROMPT_CHARS: number = 4000;
const MAX_IMAGE_NEGATIVE_PROMPT_CHARS: number = 4000;
const MAX_SUB_AGENT_PROMPT_CHARS: number = 8000;
const MAX_COUNCIL_PROMPT_CHARS: number = 2000;
const MAX_CONTEXT_COMPACTION_PROMPT_CHARS: number = 8000;
const MAX_MARKDOWN_FILE_CHARS: number = 80000;

// ===== SubAgent 模型(SubAgentModels.kt 子集) =====

export type SubAgentMode = 'roster' | 'smart_dynamic';

export interface SubAgentOverride {
  systemPrompt: string | null;
  modelId: string | null;
  temperature: number | null;
  reasoningLevel: ReasoningLevel | null;
  maxTurnsOverride: number | null;
  timeoutMsOverride: number | null;
  outputBudgetOverride: number | null;
}

export const makeSubAgentOverride = (): SubAgentOverride => ({
  systemPrompt: null,
  modelId: null,
  temperature: null,
  reasoningLevel: null,
  maxTurnsOverride: null,
  timeoutMsOverride: null,
  outputBudgetOverride: null,
});

// SubAgentOverride() 全空判定(overrides - id 移除判据,Repository.kt:105)
const isEmptyOverride = (o: SubAgentOverride): boolean =>
  o.systemPrompt === null && o.modelId === null && o.temperature === null &&
  o.reasoningLevel === null && o.maxTurnsOverride === null &&
  o.timeoutMsOverride === null && o.outputBudgetOverride === null;

export interface SubAgentDefinition {
  id: string;
  name: string;
  description: string;
  systemPrompt: string;
  toolAllowlist: string[];
  maxTurns: number;
  timeoutMs: number;
  outputBudgetChars: number;
  dynamic: boolean;
  modelId: string | null;
  temperature: number | null;
  reasoningLevel: ReasoningLevel | null;
  routingHint: string;
  supportsModelOverride: boolean;
  phaseLabels: string[];
}

export interface SubAgentDefinitionInit {
  id: string;
  name: string;
  description: string;
  systemPrompt: string;
  toolAllowlist: string[];
  maxTurns?: number;
  timeoutMs?: number;
  outputBudgetChars?: number;
  dynamic?: boolean;
  modelId?: string | null;
  temperature?: number | null;
  reasoningLevel?: ReasoningLevel | null;
  routingHint?: string;
  supportsModelOverride?: boolean;
  phaseLabels?: string[];
}

export const makeSubAgentDefinition = (init: SubAgentDefinitionInit): SubAgentDefinition => ({
  id: init.id,
  name: init.name,
  description: init.description,
  systemPrompt: init.systemPrompt,
  toolAllowlist: init.toolAllowlist,
  maxTurns: init.maxTurns ?? DEFAULT_SUB_AGENT_MAX_TURNS,
  timeoutMs: init.timeoutMs ?? DEFAULT_SUB_AGENT_TIMEOUT_MS,
  outputBudgetChars: init.outputBudgetChars ?? DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  dynamic: init.dynamic ?? false,
  modelId: init.modelId !== undefined ? init.modelId : null,
  temperature: init.temperature !== undefined ? init.temperature : null,
  reasoningLevel: init.reasoningLevel !== undefined ? init.reasoningLevel : null,
  routingHint: init.routingHint ?? '',
  supportsModelOverride: init.supportsModelOverride ?? true,
  phaseLabels: init.phaseLabels ?? [],
});

export interface SubAgentRuntimeSetting {
  enabled: boolean;
  mode: SubAgentMode;
  allowDynamicSubAgents: boolean;
  maxConcurrentRuns: number;
  timeoutMs: number;
  maxTurns: number;
  outputBudgetChars: number;
  overrides: Map<string, SubAgentOverride>;
  customDefinitions: SubAgentDefinition[];
}

export const makeSubAgentRuntimeSetting = (): SubAgentRuntimeSetting => ({
  enabled: false,
  mode: 'roster',
  allowDynamicSubAgents: true,
  maxConcurrentRuns: DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS,
  timeoutMs: DEFAULT_SUB_AGENT_TIMEOUT_MS,
  maxTurns: DEFAULT_SUB_AGENT_MAX_TURNS,
  outputBudgetChars: DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS,
  overrides: new Map<string, SubAgentOverride>(),
  customDefinitions: [],
});

// applyOverride(SubAgentModels.kt:108-119):非 null 字段覆盖内置默认;
//   systemPrompt blank → 回退内置
export const subAgentApplyOverride = (
  def: SubAgentDefinition, o: SubAgentOverride | null,
): SubAgentDefinition => {
  if (o === null) return def;
  return {
    id: def.id,
    name: def.name,
    description: def.description,
    systemPrompt: o.systemPrompt !== null && o.systemPrompt.trim().length > 0
      ? o.systemPrompt
      : def.systemPrompt,
    toolAllowlist: def.toolAllowlist,
    maxTurns: o.maxTurnsOverride !== null ? o.maxTurnsOverride : def.maxTurns,
    timeoutMs: o.timeoutMsOverride !== null ? o.timeoutMsOverride : def.timeoutMs,
    outputBudgetChars: o.outputBudgetOverride !== null
      ? o.outputBudgetOverride
      : def.outputBudgetChars,
    dynamic: def.dynamic,
    modelId: o.modelId !== null ? o.modelId : def.modelId,
    temperature: o.temperature !== null ? o.temperature : def.temperature,
    reasoningLevel: o.reasoningLevel !== null ? o.reasoningLevel : def.reasoningLevel,
    routingHint: def.routingHint,
    supportsModelOverride: def.supportsModelOverride,
    phaseLabels: def.phaseLabels,
  };
};

// ===== SubAgentDefinitions.kt(全文) =====

// rolePrompt(:299-324):Role/Capabilities/Behavior/Output/Constraints 模板;
//   extraConstraints 非 blank → '- X' 行,否则空行(trimIndent 后该行空白)
const subAgentRolePrompt = (
  role: string, capabilities: string, behavior: string, output: string,
  extraConstraints: string = '',
): string => {
  const constraintLine: string = extraConstraints.trim().length > 0
    ? `- ${extraConstraints}`
    : '';
  return `You are ${role}.

=== HARD BOUNDARIES ===
- You are a subagent. Do NOT spawn subagents.
- Execute only the assigned task. Do not continue into implementation unless it is explicitly inside the task boundaries.
- Use only the tools granted to this run.
- Report once and stop.
${constraintLine}

Capabilities:
${capabilities}

Behavior:
${behavior}

Output Format:
${output}`;
};

export const SUB_AGENT_BUILT_INS: SubAgentDefinition[] = [
  makeSubAgentDefinition({
    id: 'explorer',
    name: 'Explorer',
    description: '跨多源（网页 / 文件 / 历史会话 / MCP / 外部文档）快速并行侦察。回答「X 在哪里」「Y 大概有些什么」，速度优先，不深挖。',
    systemPrompt: subAgentRolePrompt(
      'Explorer — a fast multi-source reconnaissance specialist for AmberAgent',
      `- Web search/scrape (search_web, scrape_web)
- Workspace files (file_list, file_read, file_search)
- Conversation/session history (conversation_search, conversation_expand, session_search)
- MCP service listing (mcp_list)
- Skill discovery (skills_list)`,
      `- Fire searches in parallel when sources are independent.
- Be exhaustive but concise; prefer source-backed snippets over prose.
- Stop once you have enough evidence for the supervisor — do NOT plan or implement.
- If the task says "deep dive", expand on the most promising 1–2 sources; otherwise stay broad and shallow.`,
      'Findings (each with source), evidence list, gaps you couldn\'t resolve.',
      'READ-ONLY. No writes, no app driving.',
    ),
    toolAllowlist: [
      'tools_list', 'search_web', 'scrape_web',
      'file_list', 'file_read', 'file_search',
      'conversation_search', 'conversation_expand', 'session_search',
      'mcp_list', 'skills_list',
    ],
    routingHint: `何时调用：需要在多个来源快速并行侦察 • 范围广或不确定时 • 决策前要先摸清都有些什么。
何时不要：你已经知道具体文件/路径只想读 • 一次性具体查找 • 即将立刻执行下一步。
经验：「X 大概有些什么？」→ @explorer。「读这个具体文件」→ 自己干。`,
    phaseLabels: ['撒网', '翻看', '整理'],
  }),
  makeSubAgentDefinition({
    id: 'historian',
    name: 'Historian',
    description: '历史会话搜索 / 主题挖掘 / 跨分片综合。在 task.context 里设置 mode=read|mine|synthesize 区分用法。',
    systemPrompt: subAgentRolePrompt(
      'Historian — a bounded historical-session specialist for AmberAgent',
      `- session_search, session_read, session_expand
- conversation_search, conversation_expand
- Three modes (declared in task.context):
  * mode=read: Read 1 session or a small shard, extract questions/decisions/open items.
  * mode=mine: Topic-focused excerpt extraction across granted sessions.
  * mode=synthesize: Merge worker outputs into deduplicated themes/timelines.`,
      `- Stay strictly within the provided SessionAccessGrant.
- Keep source_message_ids in every finding when available.
- Mark missing/partial shards explicitly; never invent across gaps.
- For synthesize mode: dedupe aggressively, surface contradictions, build a timeline if temporal info exists.`,
      'Findings (each with source_session_id + source_message_ids), open_items, gaps. For synthesize: deduplicated themes + cross-session timeline.',
      'READ-ONLY. Do not broaden the search beyond grant or topic without supervisor instructions.',
    ),
    toolAllowlist: [
      'tools_list', 'session_search', 'session_read', 'session_expand',
      'conversation_search', 'conversation_expand',
    ],
    routingHint: `何时调用：需要回忆过去对话/决策 • 跨多个会话挖某个主题 • 合并多个分片的会话摘要。
何时不要：当前对话已经有答案 • 在当前会话里查单条消息。
经验：「我们之前是不是聊过 X？」→ @historian。`,
    phaseLabels: ['翻档', '比对', '编年'],
  }),
  makeSubAgentDefinition({
    id: 'oracle',
    name: 'Oracle',
    description: '深度推理与评审：架构决策、艰难取舍、反复修不好的 bug 根因、代码/方案 review、关键决定前的二次复议、destructive 操作的权限/隐私/数据风险评估。',
    systemPrompt: subAgentRolePrompt(
      'Oracle — a high-judgment strategic advisor and reviewer for AmberAgent',
      `- Deep reasoning over provided context (file_*, conversation_*, session_search)
- Architecture-level tradeoffs and second opinions
- Code/plan review with focus on edge cases, security, and missing tests
- Pre-flight risk review for destructive or sensitive actions (rm / install /
  send message / share / write external state): assess permission, privacy, and
  data-loss exposure; return an explicit allow / block / ask recommendation with
  one-line justification per concern.`,
      `- State your recommendation up front, then briefly why.
- Acknowledge uncertainty; flag where evidence is thin.
- Push back on unnecessary complexity. Prefer the simpler design when complexity doesn't earn its keep.
- Point to specific files/lines/messages when relevant.
- You think harder, not faster. It's OK to use the full token budget on the right answer.`,
      'Recommendation • brief reasoning • tradeoffs • risks • what evidence is missing.',
      'READ-ONLY. You advise; you don\'t execute.',
    ),
    toolAllowlist: [
      'tools_list', 'file_list', 'file_read', 'file_search',
      'conversation_search', 'conversation_expand', 'session_search',
      'permissions_status', 'apps_list', 'apps_installed_list',
    ],
    routingHint: `何时调用：长期影响大的决定 • 同一问题改了 2+ 次还没好 • 高风险重构 • 提交前想要二次复议 • 代码/架构 review • destructive 操作前的「真的要做吗？」。
何时不要：日常普通选择 • 时间紧、足够好就行 • 你已经很有把握 • 只读或常规操作。
经验：「这是架构层判断」→ @oracle。「重写还是打补丁？」→ @oracle。「rm/install/发消息前评估风险」→ @oracle。「直接打补丁」→ 自己干。`,
    phaseLabels: ['审视', '权衡', '拍板'],
  }),
  makeSubAgentDefinition({
    id: 'designer',
    name: 'Designer',
    description: '视觉产出专家：SVG / HTML PPT / HTML widget / VChart 的版式、配色、字体、信息密度、视觉意图。',
    systemPrompt: subAgentRolePrompt(
      'Designer — a visual-output specialist for AmberAgent\'s generative widgets',
      `- Specify concrete design values: hex colors, font families/sizes, viewBox, layout grid, spacing.
- Review existing widget code (SVG/HTML/VChart) for visual quality.
- Tools: file_read (refs), conversation_search/expand (recall design context).`,
      `- Default to clean, modern, readable. Avoid clutter, gratuitous gradients, AI-tacky stock styles.
- For Chinese content: pick fonts/sizes that work at the device DPR; respect line-height and breathing room.
- Justify each major choice in one line.
- When reviewing: actionable findings with priority (must-fix / nice-to-have).`,
      'A design spec the supervisor can hand directly to a renderer (or paste into code). For reviews: prioritized findings.',
      'READ-ONLY. You specify; the supervisor implements.',
    ),
    toolAllowlist: [
      'tools_list', 'file_read', 'file_search',
      'conversation_search', 'conversation_expand',
    ],
    routingHint: `何时调用：要生成 SVG/PPT/HTML 卡片且在意视觉质量 • 需要设计 system / 配色 / 版式规格 • 评审已有视觉产物。
何时不要：随手丢的草图 • 纯数据图表，不在意美感。
经验：「用户会看且会评判」→ @designer。`,
    phaseLabels: ['构图', '配色', '调版'],
  }),
  makeSubAgentDefinition({
    id: 'writer',
    name: 'Writer',
    description: '中文写作专家：公众号、小红书、邮件、短文、朋友圈、文学性改写、文案润色。重视文笔、节奏、情感、留白。',
    systemPrompt: `You are Writer — a Chinese-first prose specialist for AmberAgent.

=== HARD BOUNDARIES ===
- You are a subagent. Do NOT spawn subagents.
- Execute only the assigned task. Do not continue into implementation unless it is explicitly inside the task boundaries.
- Use only the tools granted to this run.
- Report once and stop.

Role: High-quality Chinese writing for 公众号 / 小红书 / 邮件 / 短文 / 朋友圈 / 文学性改写 / 故事 / 文案润色.
Focus on rhythm, emotional layer, restraint (留白), specificity (show-don't-tell).

Behavior:
- Write in Chinese unless the task explicitly says otherwise.
- Avoid AI-talk: 排比堆砌、无意义升华、空洞抒情、翻译腔, "首先/其次/最后", "总而言之", "希望对你有帮助", strained metaphors.
- Prefer concrete sensory detail over abstract description (具体场景代替抽象描述).
- Use idioms / 典故 sparingly, never to show off.
- Mind cadence: vary sentence length; leave breathing space; one short sentence after a long one is often the right move.
- For polish/rewrite tasks: preserve the author's voice and intent; do not rewrite into your own style.
- For 小红书: the FIRST line must be a punchy hook wrapped in \`**...**\` (Markdown bold, acts as the post title); emoji used with restraint; line breaks for scan-ability; end with one concrete CTA or thought.
- For 朋友圈: 50–150 字, one image-worthy sentence, no hashtags unless asked.

Tools: conversation_search / conversation_expand / file_read for reference material only.

Output:
The piece itself, then 1–2 lines on key choices made (e.g., "第二段保留了模糊性，避免把情绪挑明").
Do NOT pad with meta-commentary, "希望这段对你有帮助", or "如果需要调整请告诉我".`,
    toolAllowlist: [
      'tools_list', 'file_read', 'file_search',
      'conversation_search', 'conversation_expand',
    ],
    routingHint: `何时调用：用户要的中文写作 / 文案 / 故事 / 朋友圈 / 公众号 / 邮件，且对质量有要求 • 给现有文字润色，调整气质和节奏。
何时不要：纯事实总结 • 通顺翻译 • 只要英文输出。
经验：「写得打动人」→ @writer。「翻译这段公告」→ @fixer 或自己干。`,
    phaseLabels: ['构思', '起笔', '调律', '收尾'],
  }),
  makeSubAgentDefinition({
    id: 'fixer',
    name: 'Fixer',
    description: '便宜模型 + 边界清晰的执行：批量翻译、格式转换（JSON↔Markdown↔YAML）、抽取列表、文件命名、模板填充。',
    systemPrompt: subAgentRolePrompt(
      'Fixer — a fast, cheap, bounded-execution specialist for AmberAgent',
      `- Mechanical text transformations: translate, reformat, restructure, extract, normalize.
- Tools: file_read, conversation_search; whatever transformation tools the supervisor allowlists.`,
      `- Just do the task. No research, no architectural decisions, no creative writing, no embellishment.
- If the task is ambiguous, return a "needs_clarification" result instead of guessing.
- Prefer the simplest correct output.
- Keep formatting clean and parseable when the result will feed another tool.`,
      'Just the result. No commentary unless the task explicitly asks for it.',
      'Stay in scope. If the task wants quality writing, return needs_clarification suggesting @writer instead.',
    ),
    toolAllowlist: [
      'tools_list', 'file_read', 'file_search',
      'conversation_search', 'conversation_expand',
    ],
    routingHint: `何时调用：边界清晰的机械变换 • 批量翻译 / 格式化 / 抽取 • 便宜模型显然能搞定。
何时不要：需要研究 / 决策 / 审美判断 / 强写作 / 中文文笔。
经验：「把这堆改成 Markdown」→ @fixer。「这段重写得更有调性」→ @writer。
建议：搭配快速便宜的模型 + 推理设为 OFF 或 LOW。`,
    phaseLabels: ['拆解', '处理', '输出'],
  }),
];

// find(:37-38):id 精确 或 name 忽略大小写
export const subAgentFindDefinition = (id: string): SubAgentDefinition | null => {
  const hit: SubAgentDefinition | undefined = SUB_AGENT_BUILT_INS.find(
    (d: SubAgentDefinition): boolean =>
      d.id === id || d.name.toLowerCase() === id.toLowerCase());
  return hit === undefined ? null : hit;
};

export const SUB_AGENT_BUILT_IN_IDS: string[] =
  SUB_AGENT_BUILT_INS.map((d: SubAgentDefinition): string => d.id);

// extractMentions(:51-73):'@' 前置 文首/空白;id 后随非 id 字符;
//   长度降序优先;去重保序
const isMentionWhitespace = (ch: string): boolean => /\s/.test(ch);
const isMentionLetterOrDigit = (ch: string): boolean => /[A-Za-z0-9]/.test(ch);

export const subAgentExtractMentions = (
  text: string, validIds: string[] = SUB_AGENT_BUILT_IN_IDS,
): string[] => {
  if (text.indexOf('@') < 0 || validIds.length === 0) return [];
  const result: string[] = [];
  const idsSorted: string[] = [...validIds].sort(
    (a: string, b: string): number => b.length - a.length);
  let i: number = 0;
  while (i < text.length) {
    if (text[i] === '@' && (i === 0 || isMentionWhitespace(text[i - 1]))) {
      const rest: string = text.substring(i + 1);
      const matched: string | undefined = idsSorted.find((id: string): boolean => {
        if (!rest.toLowerCase().startsWith(id.toLowerCase())) return false;
        if (rest.length === id.length) return true;
        const next: string = rest[id.length];
        return !isMentionLetterOrDigit(next) && next !== '-';
      });
      if (matched !== undefined) {
        if (result.indexOf(matched) < 0) result.push(matched);
        i += matched.length + 1;
        continue;
      }
    }
    i++;
  }
  return result;
};

// ===== 端口与策略(Repository.kt 承载层) =====

// java.io.File 抽象:atomicWrite = writeTextAtomically(:433-444)语义
//   (体量 require 在域层,端口只做事:parent mkdirs + tmp 写 + rename,
//   rename 失败 copy+delete 兜底 — 由 entry 实现,测试内存假)
export interface PromptConfigFilePort {
  exists(path: string): boolean;
  // File.readText():不存在 → 抛
  readText(path: string): string;
  mkdirs(path: string): void;
  atomicWrite(path: string, content: string): void;
}

// Council 设置策略:Kotlin data class copy(保留全部未触字段)→ 访问器注入
//   Setting/Seat 泛型由 entry 绑定 deepread HAR 类型
export interface CouncilSettingStrategy<Setting, Seat> {
  seatsOf(setting: Setting): Seat[];
  withSeats(setting: Setting, seats: Seat[]): Setting;
  seatIdOf(seat: Seat): string;
  seatNameOf(seat: Seat): string;
  seatRoleOf(seat: Seat): string;
  seatPromptOf(seat: Seat): string;
  withSeatPrompt(seat: Seat, systemPrompt: string): Seat;
}

export interface ImagePromptInjectionConfig {
  enabled: boolean;
  defaultPrompt: string;
  negativePrompt: string;
}

export const makeImagePromptInjectionConfig = (): ImagePromptInjectionConfig => ({
  enabled: true,
  defaultPrompt: DEFAULT_IMAGE_PROMPT_INJECTION,
  negativePrompt: DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION,
});

export interface PromptConfigWriteResult {
  file: string;
  updatedId: string;
}

interface MarkdownSection {
  heading: string;
  body: string;
}

// FENCE_REGEX(:458):DOTALL ```(text|prompt|markdown)?\s*\n(.*?)\n```
const FENCE_REGEX: RegExp = /```(?:text|prompt|markdown)?\s*\n([\s\S]*?)\n```/;

// AsyncMutex(同 D-127 playbook 模式)
class AsyncMutex {
  private tail: Promise<void> = Promise.resolve();

  withLock<T>(block: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.tail.then(block);
    this.tail = run.then((): void => undefined, (): void => undefined);
    return run;
  }
}

// ===== AgentPromptConfigRepository(Repository.kt:49-465 全文) =====

export class AgentPromptConfigRepository<Setting, Seat> {
  private readonly files: PromptConfigFilePort;
  private readonly council: CouncilSettingStrategy<Setting, Seat>;
  private readonly mutex: AsyncMutex = new AsyncMutex();
  // context.filesDir/agent_prompts(directory getter :55-56 每次 mkdirs)
  private readonly directory: string;

  constructor(
    filesDir: string,
    files: PromptConfigFilePort,
    council: CouncilSettingStrategy<Setting, Seat>,
  ) {
    this.directory = `${filesDir}/${DIRECTORY_NAME}`;
    this.files = files;
    this.council = council;
  }

  private ensureDirectory(): string {
    this.files.mkdirs(this.directory);
    return this.directory;
  }

  // :58-68 四路径(取路径即 mkdirs,Android directory getter 语义)
  imagePromptFilePath(): string {
    return `${this.ensureDirectory()}/${IMAGE_PROMPT_FILE}`;
  }

  subAgentPromptFilePath(): string {
    return `${this.ensureDirectory()}/${SUB_AGENT_PROMPT_FILE}`;
  }

  modelCouncilPromptFilePath(): string {
    return `${this.ensureDirectory()}/${MODEL_COUNCIL_PROMPT_FILE}`;
  }

  contextCompactionPromptFilePath(): string {
    return `${this.ensureDirectory()}/${CONTEXT_COMPACTION_PROMPT_FILE}`;
  }

  // File.readText 直通(工具 get action 读原文用)
  readFileText(path: string): string {
    return this.files.readText(path);
  }

  // :70-72
  async effectiveImagePrompt(userPrompt: string): Promise<string> {
    return this.applyImagePrompt(userPrompt, this.readImageConfigBlocking());
  }

  // :74-76
  async readImageConfig(): Promise<ImagePromptInjectionConfig> {
    return this.readImageConfigBlocking();
  }

  // :78-91
  async writeImageConfig(config: ImagePromptInjectionConfig): Promise<PromptConfigWriteResult> {
    const next: ImagePromptInjectionConfig = {
      enabled: config.enabled,
      defaultPrompt: this.validPrompt(
        config.defaultPrompt, MAX_IMAGE_PROMPT_CHARS, 'image default prompt'),
      negativePrompt: this.validPrompt(
        config.negativePrompt, MAX_IMAGE_NEGATIVE_PROMPT_CHARS, 'image negative prompt'),
    };
    return this.mutex.withLock(async (): Promise<PromptConfigWriteResult> => {
      this.writeTextAtomically(this.imagePromptFilePath(), this.renderImageConfig(next));
      return { file: this.imagePromptFilePath(), updatedId: 'image_generation' };
    });
  }

  // :93-115 — 返回 [nextSetting, result](Pair 逐字)
  async writeSubAgentPrompt(
    setting: SubAgentRuntimeSetting, subAgentId: string, prompt: string,
  ): Promise<[SubAgentRuntimeSetting, PromptConfigWriteResult]> {
    const builtIn: SubAgentDefinition | null = subAgentFindDefinition(subAgentId);
    if (builtIn === null) throw new Error(`Unknown built-in subagent id: ${subAgentId}`);
    const trimmed: string = this.validPrompt(prompt, MAX_SUB_AGENT_PROMPT_CHARS, 'subagent prompt');
    const current: SubAgentOverride = setting.overrides.get(builtIn.id) ?? makeSubAgentOverride();
    const promptOverride: string | null =
      trimmed.length > 0 && trimmed !== builtIn.systemPrompt.trim() ? trimmed : null;
    const nextOverride: SubAgentOverride = {
      systemPrompt: promptOverride,
      modelId: current.modelId,
      temperature: current.temperature,
      reasoningLevel: current.reasoningLevel,
      maxTurnsOverride: current.maxTurnsOverride,
      timeoutMsOverride: current.timeoutMsOverride,
      outputBudgetOverride: current.outputBudgetOverride,
    };
    const nextOverrides: Map<string, SubAgentOverride> = new Map(setting.overrides);
    if (isEmptyOverride(nextOverride)) {
      nextOverrides.delete(builtIn.id);
    } else {
      nextOverrides.set(builtIn.id, nextOverride);
    }
    const copied: SubAgentRuntimeSetting = this.copySetting(setting);
    const nextSetting: SubAgentRuntimeSetting = {
      enabled: copied.enabled,
      mode: copied.mode,
      allowDynamicSubAgents: copied.allowDynamicSubAgents,
      maxConcurrentRuns: copied.maxConcurrentRuns,
      timeoutMs: copied.timeoutMs,
      maxTurns: copied.maxTurns,
      outputBudgetChars: copied.outputBudgetChars,
      overrides: nextOverrides,
      customDefinitions: copied.customDefinitions,
    };
    await this.mutex.withLock(async (): Promise<void> => {
      this.writeSubAgentMarkdownBlocking(nextSetting);
    });
    return [nextSetting, { file: this.subAgentPromptFilePath(), updatedId: builtIn.id }];
  }

  // :117-121
  async writeSubAgentMarkdown(setting: SubAgentRuntimeSetting): Promise<string> {
    return this.mutex.withLock(async (): Promise<string> => {
      return this.writeSubAgentMarkdownBlocking(setting);
    });
  }

  // :123-141
  async applySubAgentMarkdownToSetting(
    setting: SubAgentRuntimeSetting,
  ): Promise<SubAgentRuntimeSetting> {
    if (!this.files.exists(this.subAgentPromptFilePath())) return setting;
    const prompts: Map<string, string> =
      this.parseIdPromptSections(this.files.readText(this.subAgentPromptFilePath()));
    const next: SubAgentRuntimeSetting = this.copySetting(setting);
    prompts.forEach((prompt: string, key: string): void => {
      const builtIn: SubAgentDefinition | null = subAgentFindDefinition(key);
      if (builtIn === null) return;
      const valid: string = this.validPrompt(prompt, MAX_SUB_AGENT_PROMPT_CHARS, 'subagent prompt');
      const cur: SubAgentOverride = next.overrides.get(builtIn.id) ?? makeSubAgentOverride();
      const promptOverride: string | null =
        valid.length > 0 && valid !== builtIn.systemPrompt.trim() ? valid : null;
      const nextOverride: SubAgentOverride = {
        systemPrompt: promptOverride,
        modelId: cur.modelId,
        temperature: cur.temperature,
        reasoningLevel: cur.reasoningLevel,
        maxTurnsOverride: cur.maxTurnsOverride,
        timeoutMsOverride: cur.timeoutMsOverride,
        outputBudgetOverride: cur.outputBudgetOverride,
      };
      if (isEmptyOverride(nextOverride)) {
        next.overrides.delete(builtIn.id);
      } else {
        next.overrides.set(builtIn.id, nextOverride);
      }
    });
    return next;
  }

  // :143-165
  async writeModelCouncilSeatPrompt(
    setting: Setting, seatKey: string, prompt: string,
  ): Promise<[Setting, PromptConfigWriteResult]> {
    const normalizedKey: string = seatKey.trim();
    const seat: Seat | undefined = this.council.seatsOf(setting).find(
      (s: Seat): boolean => this.seatMatches(s, normalizedKey));
    if (seat === undefined) throw new Error(`Unknown model council seat: ${seatKey}`);
    const trimmed: string = this.validPrompt(prompt, MAX_COUNCIL_PROMPT_CHARS, 'model council prompt');
    const seatId: string = this.council.seatIdOf(seat);
    const nextSetting: Setting = this.council.withSeats(
      setting,
      this.council.seatsOf(setting).map((current: Seat): Seat =>
        this.council.seatIdOf(current) === seatId
          ? this.council.withSeatPrompt(current, trimmed)
          : current));
    await this.mutex.withLock(async (): Promise<void> => {
      this.writeModelCouncilMarkdownBlocking(nextSetting);
    });
    return [nextSetting, { file: this.modelCouncilPromptFilePath(), updatedId: seatId }];
  }

  // :167-172
  async writeModelCouncilMarkdown(setting: Setting): Promise<string> {
    return this.mutex.withLock(async (): Promise<string> => {
      return this.writeModelCouncilMarkdownBlocking(setting);
    });
  }

  // :174-176
  async readContextCompactionPrompt(): Promise<string> {
    return this.readContextCompactionPromptBlocking();
  }

  // :178-185
  async writeContextCompactionPrompt(prompt: string): Promise<PromptConfigWriteResult> {
    const trimmed: string = this.validPrompt(
      prompt, MAX_CONTEXT_COMPACTION_PROMPT_CHARS, 'context compaction prompt');
    return this.mutex.withLock(async (): Promise<PromptConfigWriteResult> => {
      this.writeTextAtomically(
        this.contextCompactionPromptFilePath(), this.renderContextCompactionPrompt(trimmed));
      return { file: this.contextCompactionPromptFilePath(), updatedId: 'context_compaction' };
    });
  }

  // :187-203
  async applyModelCouncilMarkdownToSetting(setting: Setting): Promise<Setting> {
    if (!this.files.exists(this.modelCouncilPromptFilePath())) return setting;
    const prompts: Map<string, string> =
      this.parseIdPromptSections(this.files.readText(this.modelCouncilPromptFilePath()));
    const seats: Seat[] = this.council.seatsOf(setting).map((seat: Seat): Seat => {
      let found: string | null = null;
      prompts.forEach((value: string, key: string): void => {
        if (found === null && this.seatMatches(seat, key)) found = value;
      });
      if (found === null || (found as string).trim().length === 0) return seat;
      return this.council.withSeatPrompt(
        seat, this.validPrompt(found, MAX_COUNCIL_PROMPT_CHARS, 'model council prompt'));
    });
    return this.council.withSeats(setting, seats);
  }

  // :205-221
  async ensureMarkdownMirrors(subAgent: SubAgentRuntimeSetting, council: Setting): Promise<void> {
    return this.mutex.withLock(async (): Promise<void> => {
      if (!this.files.exists(this.subAgentPromptFilePath())) {
        this.writeSubAgentMarkdownBlocking(subAgent);
      }
      if (!this.files.exists(this.modelCouncilPromptFilePath())) {
        this.writeModelCouncilMarkdownBlocking(council);
      }
      if (!this.files.exists(this.imagePromptFilePath())) {
        this.writeTextAtomically(
          this.imagePromptFilePath(), this.renderImageConfig(makeImagePromptInjectionConfig()));
      }
      if (!this.files.exists(this.contextCompactionPromptFilePath())) {
        this.writeTextAtomically(
          this.contextCompactionPromptFilePath(),
          this.renderContextCompactionPrompt(DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT));
      }
    });
  }

  // :223-228
  private readImageConfigBlocking(): ImagePromptInjectionConfig {
    if (!this.files.exists(this.imagePromptFilePath())) {
      this.writeTextAtomically(
        this.imagePromptFilePath(), this.renderImageConfig(makeImagePromptInjectionConfig()));
    }
    return this.parseImageConfig(this.files.readText(this.imagePromptFilePath()));
  }

  // :230-238
  private writeSubAgentMarkdownBlocking(setting: SubAgentRuntimeSetting): string {
    const path: string = this.subAgentPromptFilePath();
    this.writeTextAtomically(path, this.renderSubAgentPrompts(setting));
    return path;
  }

  private writeModelCouncilMarkdownBlocking(setting: Setting): string {
    const path: string = this.modelCouncilPromptFilePath();
    this.writeTextAtomically(path, this.renderModelCouncilPrompts(setting));
    return path;
  }

  // :240-248
  private readContextCompactionPromptBlocking(): string {
    if (!this.files.exists(this.contextCompactionPromptFilePath())) {
      this.writeTextAtomically(
        this.contextCompactionPromptFilePath(),
        this.renderContextCompactionPrompt(DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT));
    }
    const extracted: string = this.extractFencedSection(
      this.files.readText(this.contextCompactionPromptFilePath()), 'Prompt');
    return extracted.trim().length === 0 ? DEFAULT_CONTEXT_COMPACTION_HANDOFF_PROMPT : extracted;
  }

  // :250-265
  private applyImagePrompt(userPrompt: string, config: ImagePromptInjectionConfig): string {
    const defaultPrompt: string = config.defaultPrompt.trim();
    const negativePrompt: string = config.negativePrompt.trim();
    if (!config.enabled || (defaultPrompt.length === 0 && negativePrompt.length === 0)) {
      return userPrompt;
    }
    let out: string = userPrompt.trim();
    out += '\n\nImage generation defaults to apply unless the user explicitly conflicts:\n';
    if (defaultPrompt.length > 0) out += defaultPrompt;
    if (negativePrompt.length > 0) {
      out += '\n\nAvoid / de-emphasize:\n';
      out += negativePrompt;
    }
    return out;
  }

  // :267-276 — enabled: 无 'false' 元值 → true(不等于 false ignoreCase)
  private parseImageConfig(markdown: string): ImagePromptInjectionConfig {
    const enabledMeta: string | null = this.findMetadataValue(markdown, 'enabled');
    const enabled: boolean = !(enabledMeta !== null && enabledMeta.toLowerCase() === 'false');
    const dp: string = this.extractFencedSection(markdown, 'Default prompt');
    const np: string = this.extractFencedSection(markdown, 'Negative prompt');
    return {
      enabled,
      defaultPrompt: dp.trim().length === 0 ? DEFAULT_IMAGE_PROMPT_INJECTION : dp,
      negativePrompt: np.trim().length === 0 ? DEFAULT_IMAGE_NEGATIVE_PROMPT_INJECTION : np,
    };
  }

  // :278-297
  private renderImageConfig(config: ImagePromptInjectionConfig): string {
    return `# Image Generation Prompt Injection

enabled: ${config.enabled}

Agent-editable defaults appended to every image-generation request. Keep this file concise:
it is injected into the provider prompt, not shown as a UI preset.

## Default prompt

\`\`\`text
${config.defaultPrompt.trim().replace(/```/g, '` ` `')}
\`\`\`

## Negative prompt

\`\`\`text
${config.negativePrompt.trim().replace(/```/g, '` ` `')}
\`\`\`
` + '';
  }

  // :299-317
  private renderSubAgentPrompts(setting: SubAgentRuntimeSetting): string {
    let out: string = '# SubAgent Prompt Overrides\n';
    out += '\n';
    out += 'Edit built-in role prompts here. AmberAgent settings and agent tools mirror these entries.\n';
    out += 'Use the built-in id as the level-2 heading.\n';
    out += '\n';
    SUB_AGENT_BUILT_INS.forEach((def: SubAgentDefinition): void => {
      const effective: SubAgentDefinition = subAgentApplyOverride(
        def, setting.overrides.get(def.id) ?? null);
      out += `## ${def.id}\n`;
      out += '\n';
      out += `name: ${def.name}\n`;
      out += '\n';
      out += '```text\n';
      out += effective.systemPrompt.trim().replace(/```/g, '` ` `') + '\n';
      out += '```\n';
      out += '\n';
    });
    return out;
  }

  // :318-334
  private renderModelCouncilPrompts(setting: Setting): string {
    let out: string = '# Model Council Seat Prompts\n';
    out += '\n';
    out += 'Edit persistent council seat prompts here. Use the seat id heading for stable sync.\n';
    out += '\n';
    this.council.seatsOf(setting).forEach((seat: Seat): void => {
      out += `## ${this.council.seatIdOf(seat)}\n`;
      out += '\n';
      out += `name: ${this.council.seatNameOf(seat)}\n`;
      out += `role: ${this.council.seatRoleOf(seat)}\n`;
      out += '\n';
      out += '```text\n';
      out += this.council.seatPromptOf(seat).trim().replace(/```/g, '` ` `') + '\n';
      out += '```\n';
      out += '\n';
    });
    return out;
  }

  // :336-347
  private renderContextCompactionPrompt(prompt: string): string {
    return `# Context Compaction Handoff Prompt

Agent-editable instructions appended to every conversation compaction request.
Keep the JSON contract intact unless the app is updated to parse a new schema.

## Prompt

\`\`\`text
${prompt.trim().replace(/```/g, '` ` `')}
\`\`\`
` + '';
  }

  // :349-352
  private extractFencedSection(markdown: string, heading: string): string {
    const section: string = this.extractSection(markdown, heading);
    const m: RegExpMatchArray | null = section.match(FENCE_REGEX);
    const body: string = m !== null && m[1] !== undefined ? m[1] : '';
    return body.trim();
  }

  // :354-359
  private extractSection(markdown: string, heading: string): string {
    const hit: MarkdownSection | undefined = this.splitSecondLevelSections(markdown)
      .find((s: MarkdownSection): boolean => s.heading.toLowerCase() === heading.toLowerCase());
    return hit === undefined ? '' : hit.body;
  }

  // :361-369
  private parseIdPromptSections(markdown: string): Map<string, string> {
    const out: Map<string, string> = new Map<string, string>();
    this.splitSecondLevelSections(markdown).forEach((s: MarkdownSection): void => {
      const id: string = s.heading.trim();
      const m: RegExpMatchArray | null = s.body.match(FENCE_REGEX);
      const prompt: string = (m !== null && m[1] !== undefined ? m[1] : '').trim();
      if (id.length > 0 && prompt.length > 0 && !out.has(id)) out.set(id, prompt);
    });
    return out;
  }

  // :371-400 — '## ' 段切(### 不算);fence 内 '##' 不切
  private splitSecondLevelSections(markdown: string): MarkdownSection[] {
    const sections: MarkdownSection[] = [];
    let inFence: boolean = false;
    let heading: string | null = null;
    let body: string = '';
    const flush = (): void => {
      if (heading === null) return;
      sections.push({ heading, body });
      body = '';
    };
    markdown.split('\n').forEach((line: string): void => {
      const trimmedStart: string = line.replace(/^\s+/, '');
      const isFence: boolean = trimmedStart.startsWith('```');
      if (!inFence && trimmedStart.startsWith('## ') && !trimmedStart.startsWith('###')) {
        flush();
        heading = trimmedStart.substring(3).trim();
        return;
      }
      if (heading !== null) {
        body += line + '\n';
      }
      if (isFence) {
        inFence = !inFence;
      }
    });
    flush();
    return sections;
  }

  // :402-415 — fence 外首个 'key:' 行(ignoreCase)的值
  private findMetadataValue(markdown: string, key: string): string | null {
    let inFence: boolean = false;
    const lines: string[] = markdown.split('\n');
    for (const line of lines) {
      const trimmed: string = line.trim();
      if (trimmed.startsWith('```')) {
        inFence = !inFence;
        continue;
      }
      if (!inFence && trimmed.toLowerCase().startsWith(`${key.toLowerCase()}:`)) {
        const idx: number = trimmed.indexOf(':');
        return trimmed.substring(idx + 1).trim();
      }
    }
    return null;
  }

  // :417-421 — seatId/role/name 任一忽略大小写相等
  private seatMatches(seat: Seat, key: string): boolean {
    const k: string = key.toLowerCase();
    return this.council.seatIdOf(seat).toLowerCase() === k
      || this.council.seatRoleOf(seat).toLowerCase() === k
      || this.council.seatNameOf(seat).toLowerCase() === k;
  }

  // :425-431
  private validPrompt(input: string, maxChars: number, label: string): string {
    const trimmed: string = input.trim();
    if (trimmed.length > maxChars) {
      throw new Error(`${label} is too long: ${trimmed.length} chars, max ${maxChars}`);
    }
    return trimmed;
  }

  // :433-444 — 体量 require 在域层逐字;原子落盘由端口承载
  private writeTextAtomically(path: string, text: string): void {
    if (text.length > MAX_MARKDOWN_FILE_CHARS) {
      throw new Error(
        `Prompt config file is too large: ${text.length} chars, max ${MAX_MARKDOWN_FILE_CHARS}`);
    }
    this.files.atomicWrite(path, text);
  }

  // setting.copy(overrides = X) 辅助(全字段显式拷贝)
  private copySetting(setting: SubAgentRuntimeSetting): SubAgentRuntimeSetting {
    return {
      enabled: setting.enabled,
      mode: setting.mode,
      allowDynamicSubAgents: setting.allowDynamicSubAgents,
      maxConcurrentRuns: setting.maxConcurrentRuns,
      timeoutMs: setting.timeoutMs,
      maxTurns: setting.maxTurns,
      outputBudgetChars: setting.outputBudgetChars,
      overrides: new Map<string, SubAgentOverride>(setting.overrides),
      customDefinitions: setting.customDefinitions,
    };
  }
}

// ===== agent_prompt_config 工具(AgentPromptConfigTool.kt 全文) =====

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
