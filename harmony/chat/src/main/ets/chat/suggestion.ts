// suggestion — 会话建议(下一轮提问推荐)
//
// Android 基准:AiAuxiliaryGenerator.generateSuggestion(AiAuxiliaryGenerator.kt:81-134)
//   - 触发:每轮生成完成后(无条件);先清空旧 suggestions(in-memory,不落库)
//   - prompt = suggestionPrompt.applyPlaceholders(locale, content=
//     currentMessages.takeLast(8).joinToString("\n\n") { summaryAsText() })
//   - reasoning OFF;结果 split("\n") → trim → 滤空 → take(10) → 持久化
//   - 失败 onFailure 仅 printStackTrace(鸿蒙:domain 传播,entry hilog 记录 — D-026)
//
// 裁剪:同 auto_title — suggestionModelId 设置项 = P1,MVP 注入 generateText Port

import type { Conversation } from './conversation.ts';
import { currentMessages } from './conversation.ts';
import type { UIMessage } from './message.ts';
import { summaryAsText } from './message.ts';
import type { ConversationStore } from './chat_turn.ts';
import { nowIso } from './ids.ts';

// DEFAULT_SUGGESTION_PROMPT — core/ai-prompts/Suggestion.kt:3-21 逐字(trimIndent 后)
export const DEFAULT_SUGGESTION_PROMPT: string = `I will provide you with some chat content in the \`<content>\` block, including conversations between the User and the AI assistant.
You need to act as the **User** to reply to the assistant, generating 3~5 appropriate and contextually relevant responses to the assistant.

Rules:
1. If the assistant explicitly offers choices or next actions, prefer copying those choices as suggestions.
2. Reply directly with suggestions, do not add any formatting, and separate suggestions with newlines, no need to add markdown list formats.
3. Use {locale} language.
4. Ensure each suggestion is valid.
5. Each suggestion should usually stay within 24 characters unless copying an explicit option from the assistant.
6. Imitate the user's previous conversational style.
7. Act as a User, not an Assistant!

<content>
{content}
</content>`;

export const buildSuggestionPrompt = (
  conv: Conversation, locale: string, template: string = DEFAULT_SUGGESTION_PROMPT,
): string => {
  const content: string = currentMessages(conv)
    .slice(-8)
    .map((m: UIMessage): string => summaryAsText(m))
    .join('\n\n');
  return template.split('{locale}').join(locale).split('{content}').join(content);
};

export interface SuggestionDeps {
  generateText: (prompt: string) => Promise<string>;
  store: ConversationStore;
  locale: string;
  template?: string;
}

export const runSuggestion = async (
  conv: Conversation, deps: SuggestionDeps,
): Promise<Conversation> => {
  const prompt: string = buildSuggestionPrompt(
    conv, deps.locale, deps.template ?? DEFAULT_SUGGESTION_PROMPT);
  const text: string = await deps.generateText(prompt);
  const suggestions: string[] = text
    .split('\n')
    .map((line: string): string => line.trim())
    .filter((line: string): boolean => line.length > 0)
    .slice(0, 10);
  const out: Conversation = { ...conv, chatSuggestions: suggestions, updateAt: nowIso() };
  await deps.store.save(out);
  return out;
};
