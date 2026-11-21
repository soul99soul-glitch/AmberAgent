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
import type { UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool } from './message.ts';
import { summaryAsText } from './message.ts';
import type { ConversationStore } from './chat_turn.ts';
import { nowIso } from './ids.ts';

export const SUGGESTION_CONTEXT_BUDGET: number = 24000;
export const MAX_COMPOSER_SUGGESTIONS: number = 3;

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

// ===== P1-1: 按完整用户回合分配 24,000 字预算的上下文预测（对齐 iOS ChatSuggestionContext） =====

export interface SuggestionContextEntry {
  role: 'User' | 'Assistant';
  text: string;
}

export interface SuggestionContextOptions {
  summary?: string | null;
  pendingWork?: string[];
  budget?: number;
}

export interface BuiltSuggestionContext {
  content: string;
  latestUserText: string;
  latestAssistantText: string;
}

export interface CloudSuggestionEventLike {
  seq: number;
  id: string;
  kind: string;
  role: string;
  text: string;
  topicId?: string | null;
  workItemId?: string | null;
  outcome?: string | null;
}

export const excerptSuggestionText = (text: string, limit: number): string => {
  if (text.length <= limit) return text;
  const half: number = Math.max(0, Math.floor((limit - 24) / 2));
  return `${text.substring(0, half)}\n[content omitted]\n${text.substring(text.length - half)}`;
};

export const buildTurnBudgetedSuggestionContext = (
  entries: SuggestionContextEntry[],
  options: SuggestionContextOptions = {},
): BuiltSuggestionContext => {
  const budget: number = Math.max(128, options.budget ?? SUGGESTION_CONTEXT_BUDGET);
  let latestUserText: string = '';
  let latestAssistantText: string = '';
  for (let i: number = entries.length - 1; i >= 0; i--) {
    if (latestUserText.length === 0 && entries[i].role === 'User') latestUserText = entries[i].text;
    if (latestAssistantText.length === 0 && entries[i].role === 'Assistant') latestAssistantText = entries[i].text;
    if (latestUserText.length > 0 && latestAssistantText.length > 0) break;
  }

  const turns: SuggestionContextEntry[][] = [];
  for (const entry of entries) {
    if (entry.role === 'User' || turns.length === 0) {
      turns.push([]);
    }
    turns[turns.length - 1].push(entry);
  }

  let header: string = '';
  const summary: string = (options.summary ?? '').trim();
  if (summary.length > 0) {
    header += `Earlier context summary:\n${summary.substring(0, Math.min(4000, Math.floor(budget / 3)))}\n\n`;
  }
  const pendingWork: string[] = (options.pendingWork ?? []).filter((s: string): boolean => s.trim().length > 0);
  if (pendingWork.length > 0) {
    const joined: string = pendingWork.slice(0, 8).join('\n').substring(0, Math.floor(budget / 6));
    header += `Work still in progress (do not restart it):\n${joined}\n\n`;
  }

  const selected: string[] = [];
  let remaining: number = Math.max(0, budget - header.length);
  for (let i: number = turns.length - 1; i >= 0; i--) {
    const turn: SuggestionContextEntry[] = turns[i];
    const block: string = turn.map((e: SuggestionContextEntry): string => `${e.role}: ${e.text}`).join('\n\n');
    if (block.length + 2 > remaining) {
      if (selected.length === 0) {
        // 超长工具回合：保留首条用户提问与末尾最多 8 条结果
        const essentials: SuggestionContextEntry[] = turn.length > 9
          ? [turn[0], ...turn.slice(turn.length - 8)]
          : turn;
        const perEntry: number = Math.max(24, Math.floor(remaining / Math.max(1, essentials.length)) - 20);
        selected.push(
          essentials
            .map((e: SuggestionContextEntry): string => `${e.role}: ${excerptSuggestionText(e.text, perEntry)}`)
            .join('\n\n'),
        );
      }
      break;
    }
    selected.push(block);
    remaining -= block.length + 2;
  }

  const body: string = selected.reverse().join('\n\n');
  const full: string = header + body;
  return {
    content: full.length > budget ? full.substring(0, budget) : full,
    latestUserText,
    latestAssistantText,
  };
};

export const buildLocalSuggestionContext = (
  messages: UIMessage[],
  options: SuggestionContextOptions = {},
): BuiltSuggestionContext | null => {
  if (messages.length === 0) return null;
  const last: UIMessage = messages[messages.length - 1];
  if (last.role !== 'assistant') return null;

  const reversedEntries: SuggestionContextEntry[] = [];
  let characters: number = 0;
  const budget: number = Math.max(128, options.budget ?? SUGGESTION_CONTEXT_BUDGET);

  for (let i: number = messages.length - 1; i >= 0; i--) {
    const message: UIMessage = messages[i];
    if (message.role !== 'user' && message.role !== 'assistant') continue;
    const lines: string[] = [];
    for (const part of message.parts) {
      if (part.type === 'text') {
        const txt: string = (part as UIMessagePartText).text;
        if (txt.trim().length > 0) lines.push(excerptSuggestionText(txt, 8000));
      } else if (part.type === 'tool') {
        const tool: UIMessagePartTool = part as UIMessagePartTool;
        const output: string = tool.output
          .filter((p: UIMessagePart): boolean => p.type === 'text')
          .map((p: UIMessagePart): string => (p as UIMessagePartText).text)
          .join('\n');
        if (output.trim().length > 0) {
          lines.push(`Tool result [${tool.toolName}]: ${excerptSuggestionText(output, 2000)}`);
        } else {
          lines.push(`Tool [${tool.toolName}]: not completed`);
        }
      }
    }
    const value: string = lines.join('\n');
    if (value.trim().length > 0) {
      reversedEntries.push({
        role: message.role === 'user' ? 'User' : 'Assistant',
        text: value,
      });
      characters += value.length;
    }
    if (characters >= budget && message.role === 'user') break;
  }

  if (reversedEntries.length === 0) return null;
  const entries: SuggestionContextEntry[] = reversedEntries.reverse();
  const ctx: BuiltSuggestionContext = buildTurnBudgetedSuggestionContext(entries, options);
  if (ctx.latestAssistantText.trim().length === 0) return null;
  return ctx;
};

export const isUpfrontCloudAcknowledgement = (ev: CloudSuggestionEventLike): boolean => {
  if (ev.kind !== 'assistant_reply') return false;
  if (ev.outcome !== undefined && ev.outcome !== null && ev.outcome.trim().length > 0) return false;
  if (ev.id.startsWith('ack:') || ev.id.startsWith('ack-')) return true;
  if (ev.workItemId !== undefined && ev.workItemId !== null && ev.workItemId.trim().length > 0) return false;
  const trimmed: string = ev.text.trim();
  return trimmed.startsWith('收到，我来处理')
    || trimmed.startsWith('收到，正在处理')
    || trimmed.startsWith('正在处理，请稍候');
};

export const buildCloudSuggestionContext = (
  events: CloudSuggestionEventLike[],
  replyId: string,
  pendingWork: string[] = [],
): BuiltSuggestionContext | null => {
  let reply: CloudSuggestionEventLike | null = null;
  for (let i: number = events.length - 1; i >= 0; i--) {
    if (events[i].id === replyId) {
      reply = events[i];
      break;
    }
  }
  if (reply === null) return null;
  if (reply.kind !== 'assistant_reply' && reply.kind !== 'task_result') return null;
  if (reply.outcome === 'failed' || reply.outcome === 'cancelled') return null;
  if (reply.kind === 'task_result' && reply.outcome !== 'completed') return null;
  if (isUpfrontCloudAcknowledgement(reply)) return null;
  if (reply.text.trim().length === 0) return null;

  const entries: SuggestionContextEntry[] = [];
  for (const ev of events) {
    if (ev.seq > reply.seq) continue;
    if (ev.kind === 'user_message' || ev.kind === 'followup') {
      entries.push({ role: 'User', text: excerptSuggestionText(ev.text, 8000) });
    } else if (ev.kind === 'assistant_reply' || ev.kind === 'task_result') {
      if (isUpfrontCloudAcknowledgement(ev)) continue;
      entries.push({ role: 'Assistant', text: excerptSuggestionText(ev.text, 8000) });
    }
  }
  if (entries.length === 0) return null;
  return buildTurnBudgetedSuggestionContext(entries, { pendingWork });
};

const normalizeSuggestionForDedup = (text: string): string =>
  text.toLowerCase().replace(/[\s\p{P}\p{S}]+/gu, '');

const GENERIC_SUGGESTIONS: Set<string> = new Set([
  '好', '好的', '继续', '继续吧', '谢谢', 'ok', 'okay', 'yes', 'goon', 'nosuggestion', 'null', 'none',
]);

const ASSISTANT_SELF_PREFIXES: string[] = [
  'assistant:', '作为ai', '作为一个ai', '我来帮你', '我可以帮你', '以下是', 'as an ai', 'i can help you', 'here are',
];

export const filterSuggestionCandidates = (
  raw: string,
  latestUserText: string = '',
  latestAssistantText: string = '',
): string[] => {
  if (raw.includes('```') || raw.includes('[NO_SUGGESTION]')) return [];
  const lines: string[] = raw.split(/\r?\n/).map((line: string): string => line.trim()).filter((line: string): boolean => line.length > 0);
  const seen: Set<string> = new Set<string>();
  const latestUserNorm: string = normalizeSuggestionForDedup(latestUserText);
  const latestAssistantNorm: string = normalizeSuggestionForDedup(latestAssistantText);
  const result: string[] = [];

  for (const line of lines) {
    const cleaned: string = line
      .replace(/^\s*(?:[-*•]|\d+[.)、])\s+/, '')
      .replace(/^["“”「」]+|["“”「」]+$/g, '')
      .trim();
    if (cleaned.length === 0 || cleaned.length > 160) continue;
    if (cleaned.includes('```') || cleaned.includes('<content>')) continue;
    if (/[\u0000-\u001F\u007F]/.test(cleaned)) continue;
    const lower: string = cleaned.toLowerCase();
    if (ASSISTANT_SELF_PREFIXES.some((prefix: string): boolean => lower.startsWith(prefix))) continue;
    const norm: string = normalizeSuggestionForDedup(cleaned);
    if (norm.length === 0 || GENERIC_SUGGESTIONS.has(norm)) continue;
    if (norm === latestUserNorm || norm === latestAssistantNorm) continue;
    if (seen.has(norm)) continue;
    seen.add(norm);
    result.push(cleaned);
    if (result.length >= MAX_COMPOSER_SUGGESTIONS) break;
  }
  return result;
};

export interface ContextAwareSuggestionDeps {
  generateText: (prompt: string) => Promise<string>;
  locale: string;
  template?: string;
}

export const runContextAwareSuggestions = async (
  context: BuiltSuggestionContext,
  deps: ContextAwareSuggestionDeps,
): Promise<string[]> => {
  const template: string = deps.template ?? DEFAULT_SUGGESTION_PROMPT;
  const prompt: string = template
    .split('{locale}').join(deps.locale)
    .split('{content}').join(context.content);
  const raw: string = await deps.generateText(prompt);
  return filterSuggestionCandidates(raw, context.latestUserText, context.latestAssistantText);
};
