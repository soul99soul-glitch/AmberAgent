// auto title — 会话自动命名
//
// Android 基准:AiAuxiliaryGenerator.generateTitle(AiAuxiliaryGenerator.kt:27-71)
//   - 触发:生成完成后(ChatService.kt:1489,独立 launch,非阻塞主流程)
//   - 条件:title 空白或 force
//   - prompt = titlePrompt.applyPlaceholders(locale → Locale displayName,
//     content → currentMessages.takeLast(4).joinToString("\n\n") { summaryAsText() })
//   - generateText(reasoning OFF) → trim → 保存;失败 runCatching → 会话不变
//
// 裁剪(登记):
//   - Android 用独立 titleModelId 设置(resolveTaskChatModel 回退);鸿蒙 MVP 由
//     调用方注入 generateText Port(entry 用当前 runtime 的 api.generateText,
//     reasoning 由调用方参数控制),titleModel 设置项 = P1
//   - locale 由调用方传入(Android 取系统 Locale displayName)

import type { Conversation } from './conversation.ts';
import { currentMessages } from './conversation.ts';
import type { UIMessage } from './message.ts';
import { summaryAsText } from './message.ts';
import type { ConversationStore } from './chat_turn.ts';
import { nowIso } from './ids.ts';

// DEFAULT_TITLE_PROMPT — core/ai-prompts/TitleSummary.kt:3-15 逐字(trimIndent 后)
export const DEFAULT_TITLE_PROMPT: string = `I will give you some dialogue content in the \`<content>\` block.
You need to summarize the conversation between user and assistant into a short title.
1. The title language should be consistent with the user's primary language
2. Do not use punctuation or other special symbols
3. Reply directly with the title
4. Summarize using {locale} language
5. The title should not exceed 10 characters

<content>
{content}
</content>`;

export const buildTitlePrompt = (
  conv: Conversation, locale: string, template: string = DEFAULT_TITLE_PROMPT,
): string => {
  const content: string = currentMessages(conv)
    .slice(-4)
    .map((m: UIMessage): string => summaryAsText(m))
    .join('\n\n');
  return template.split('{locale}').join(locale).split('{content}').join(content);
};

export interface AutoTitleDeps {
  // 生成 Port:输入完整 prompt,返回模型文本(调用方负责模型/参数选择)
  generateText: (prompt: string) => Promise<string>;
  store: ConversationStore;
  locale: string;
  template?: string;
}

export const runAutoTitle = async (
  conv: Conversation, deps: AutoTitleDeps, force: boolean = false,
): Promise<Conversation> => {
  if (!force && conv.title.trim().length > 0) {
    return conv;
  }
  const prompt: string = buildTitlePrompt(conv, deps.locale, deps.template ?? DEFAULT_TITLE_PROMPT);
  const title: string = (await deps.generateText(prompt)).trim();
  const out: Conversation = { ...conv, title, updateAt: nowIso() };
  await deps.store.save(out);
  return out;
};
