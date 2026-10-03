// novel/prompt_catalog — 提示词目录(port iOS monorepo NovelPromptCatalog.kt 234 行)
//
// 6 种 Run 各自独立 system 模板;润色用哨兵 `<AMBER_NOVEL_POLISH_COMPLETE>` 标记
// "润色后全文结束"(剥离后取哨兵前内容)。promptKind 由 RunKind 映射(见 runKindToPrompt)。
// 模板 = 指令变体,活资料/章节/对话上下文仍由 context_builder 装配进 system。

export type NovelRunKind =
  | 'quick_start'
  | 'discussion'
  | 'prose_continuation'
  | 'prose_whole_chapter'
  | 'polish'
  | 'regenerate';

export interface NovelPromptTemplate {
  kind: NovelRunKind;
  version: string;
  systemText: string;
}

export const POLISH_COMPLETION_SENTINEL: string = '<AMBER_NOVEL_POLISH_COMPLETE>';

const INK_GUIDE: string = '写作要求:保持人物性格、语气与情节连贯;不虚构与已有内容矛盾的设定;语言自然,避免翻译腔与过度形容词。';

const QUICK_START_SYSTEM: string = `你是小说创作的起点助手。把作者的念头发展成可写的设定与开篇建议。
只输出严格 JSON，不要代码围栏或额外文字：
{"overview":"清楚易读的创作概览，包含题材基调、核心冲突和第一章建议","proposals":[{"kind":"world|character|relationship|outline|requirement|other","title":"资料标题","content":"一项可独立审阅、编辑、采纳的完整资料","aliases":[],"tags":[],"customKind":"","injectionMode":"smart"}]}
proposals 必须有 1–12 项。每项只能包含一个主题；所有提案需作者逐项确认后才成为正式资料。
不要输出章节正文，不要自行虚构作者的写作禁令。
${INK_GUIDE}`;

const DISCUSSION_SYSTEM: string = `你是小说创作的讨论伙伴。与作者讨论设定、人物、情节走向;
给出具体、可操作的建议而不是空泛评价。作者可能随时把你说的设定采用为正式设定。
先输出给作者阅读的自然正文。需要作者补充会实质影响建议的信息时,调用 ask_user 工具,每次只问 1-2 题。
只有当你提出具体、可独立采纳的工作区变更时,才调用 novel_workspace_write 工具提交待确认提案;
proposal_id 对同一变更保持稳定,patches 必须包含拟写入的完整内容。工具调用只会暂存提案,
必须由作者明确批准后才会写入工作区;不要把普通建议或空泛想法包装成提案。
不要输出 fenced JSON 协议块,必须使用原生工具调用。
${INK_GUIDE}`;

const PROSE_CONTINUATION_SYSTEM: string = `你是小说续写引擎。紧接现有正文续写一个自然片段:
保持人物、语气与情节连贯;不重复已有内容;不要输出章节标题;直接从上文的叙事节奏继续。
${INK_GUIDE}`;

const PROSE_WHOLE_CHAPTER_SYSTEM: string = `你是小说章节写作引擎。根据讨论上下文与活资料,写出完整的章节正文:
- 直接输出正文,不要输出章节标题(标题由应用管理)
- 篇幅完整(有起承转合),不要写成摘要或梗概
${INK_GUIDE}`;

const POLISH_SYSTEM: string = `你是小说润色引擎。对给出的章节全文做润色:
- 提升语言质感(节奏、用词、画面感),不改变情节、人物与事实
- 保留分段结构;不新增或删除情节线
- 输出润色后的完整章节全文,并在最后一行单独输出完成标记 ${POLISH_COMPLETION_SENTINEL}`;

export const CHAPTER_CONTRACT_PROPOSAL_SYSTEM: string =
  '你是小说本章策划助手。根据作者资料提出草稿，只输出完整 JSON：'
  + '{"outlinePlacement":"","goalAndConflict":"","mustHappen":[],"mustNotHappen":[],"endingHook":"","visibleFacts":[]}。'
  + 'visibleFacts 仅写本章视角可知事实；未获作者确认的提案不是正式事实。';

const REGENERATE_SYSTEM: string = `你是小说章节重写引擎。对给出的章节做全新的重写(不是润色):
- 按作者改稿要求重新设计章节；允许改变情节、人物认知与事实，修复指出的矛盾
- 原章作为改写来源；作者明确修改的事实优先，未要求改变的资料继续作为约束
- 直接输出完整章节正文,不要输出章节标题
保持语言自然、人物动机可理解，避免翻译腔与过度形容词。
不要把润色的“不得改变事实”规则用于本次重生成。`;

export const novelPromptTemplate = (kind: NovelRunKind): NovelPromptTemplate => {
  const map: Record<NovelRunKind, string> = {
    'quick_start': QUICK_START_SYSTEM,
    'discussion': DISCUSSION_SYSTEM,
    'prose_continuation': PROSE_CONTINUATION_SYSTEM,
    'prose_whole_chapter': PROSE_WHOLE_CHAPTER_SYSTEM,
    'polish': POLISH_SYSTEM,
    'regenerate': REGENERATE_SYSTEM,
  };
  return { kind: kind, version: '1', systemText: map[kind] };
};

// 剥离润色哨兵:返回哨兵前的正文;无哨兵时返回全文(模型可能漏标)
export const stripPolishSentinel = (output: string): string => {
  const idx: number = output.lastIndexOf(POLISH_COMPLETION_SENTINEL);
  if (idx < 0) return output.trim();
  return output.substring(0, idx).trim();
};

export const hasPolishSentinel = (output: string): boolean =>
  output.includes(POLISH_COMPLETION_SENTINEL);
