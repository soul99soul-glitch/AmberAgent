// SubAgent 角色、默认值和 mention 解析；不依赖提示词文件或设置 IO。
import type { ReasoningLevel } from './provider_model.ts';

export const DEFAULT_SUB_AGENT_MAX_CONCURRENT_RUNS: number = 2;
export const DEFAULT_SUB_AGENT_TIMEOUT_MS: number = 5 * 60000;
export const DEFAULT_SUB_AGENT_MAX_TURNS: number = 4;
export const DEFAULT_SUB_AGENT_OUTPUT_BUDGET_CHARS: number = 12000;

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

