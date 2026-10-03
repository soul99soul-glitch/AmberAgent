// 纯逻辑 transformer 三件套 — Template / ThinkTag / Regex
//
// Android 基准:
//   TemplateTransformer.kt(applyTemplate:42-49)
//   ThinkTagTransformer.kt(THINKING_REGEX / transformMessage)
//   RegexOutputTransformer.kt + AssistantRegexProcessor.kt(replaceRegexes)
//
// 偏差(D-012/PD-005):
//   - 模板直接取 ctx.assistant.messageTemplate(Android 经 settingsStore 按 id 反查,等价扁平化)
//   - replaceRegexes 无 Rust native 路由(Android 性能优化);统一 JS RegExp 解释,
//     Java-only 语法规则编译失败即跳过(Android JVM 可编译) → PD-005
//   - Template/ThinkTag 对无 text part 的消息保持引用(ArkUI 身份保持,内容等价)

import type { UIMessage, UIMessagePart, UIMessagePartReasoning } from './message.ts';
import type { AssistantRegex, AssistantAffectScope } from './assistant.ts';
import type {
  MessageTransformer, OutputMessageTransformer, TailSafeOutputMessageTransformer,
  TransformerContext,
} from './transformer_pipeline.ts';
import { clockOf } from './transformer_pipeline.ts';

// ===== applyTemplate(TemplateTransformer.kt:42-49) =====

export interface TemplateVars {
  message: string;
  role: string;
  time: string;
  date: string;
}

export const applyTemplate = (template: string, vars: TemplateVars): string => {
  let result: string = template;
  const pairs: Array<[string, string]> = [
    ['message', vars.message],
    ['role', vars.role],
    ['time', vars.time],
    ['date', vars.date],
  ];
  for (const [key, value] of pairs) {
    result = result.split(`{{ ${key} }}`).join(value).split(`{{${key}}}`).join(value);
  }
  return result;
};

// ===== TemplateTransformer =====
// 注:Android 对每条消息 text part 套模板;此处对无 text part 的消息保持引用(内容等价)
// D-016:Assistant.messageTemplate 恒为 string(默认 '{{ message }}'),
// Android TemplateTransformer 总是套用(assistant 找到即 apply,默认模板恒等)——去掉 null-skip。

export const createTemplateTransformer = (): MessageTransformer => ({
  transform(ctx: TransformerContext, messages: UIMessage[]): UIMessage[] {
    const template: string = ctx.assistant.messageTemplate;
    const clock = clockOf(ctx);
    const time: string = clock.localTime();
    const date: string = clock.localDate();
    return messages.map((message: UIMessage): UIMessage => {
      if (!message.parts.some((p: UIMessagePart): boolean => p.type === 'text')) return message;
      return {
        ...message,
        parts: message.parts.map((part: UIMessagePart): UIMessagePart =>
          part.type === 'text'
            ? {
              ...part,
              text: applyTemplate(template, {
                message: part.text, role: message.role, time, date,
              }),
            }
            : part),
      };
    });
  },
});

// ===== ThinkTagTransformer =====

const THINKING_REGEX = /<think>([\s\S]*?)(?:<\/think>|$)/;
const CLOSING_TAG_REGEX = /<\/think>/;

const transformThinkMessage = (
  ctx: TransformerContext, message: UIMessage, finishOpenReasoningAt: string | null,
  finishedTimes?: Map<string, string>,
): UIMessage => {
  if (message.role !== 'assistant') return message;
  if (!message.parts.some((p: UIMessagePart): boolean => p.type === 'text')) return message;
  let changed: boolean = false;
  const parts: UIMessagePart[] = [];
  for (let index = 0; index < message.parts.length; index++) {
    const part = message.parts[index];
    if (part.type === 'text' && THINKING_REGEX.test(part.text)) {
      changed = true;
      const m: RegExpMatchArray | null = part.text.match(THINKING_REGEX);
      const stripped: string = part.text.replace(THINKING_REGEX, '');
      const reasoning: string = (m !== null && m[1] !== undefined ? m[1] : '').trim();
      const hasClosingTag: boolean = CLOSING_TAG_REGEX.test(part.text);
      const timeKey: string = `${message.id}/${message.createdAt}/${index}`;
      const finishedAt: string | null = finishedTimes?.get(timeKey)
        ?? finishOpenReasoningAt ?? (hasClosingTag ? clockOf(ctx).nowIso() : null);
      if (finishedAt !== null) finishedTimes?.set(timeKey, finishedAt);
      const reasoningPart: UIMessagePartReasoning = {
        type: 'reasoning',
        reasoning,
        createdAt: message.createdAt,
        finishedAt,
        metadata: null,
      };
      parts.push(reasoningPart);
      parts.push({ ...part, text: stripped });
    } else {
      parts.push(part);
    }
  }
  return changed ? { ...message, parts } : message;
};

const makeThinkTagTransformer = (finishedTimes?: Map<string, string>): TailSafeOutputMessageTransformer => ({
  visualTransform(ctx: TransformerContext, messages: UIMessage[]): UIMessage[] {
    let changed: boolean = false;
    const out: UIMessage[] = messages.map((m: UIMessage): UIMessage => {
      const next: UIMessage = transformThinkMessage(ctx, m, null, finishedTimes);
      if (next !== m) changed = true;
      return next;
    });
    return changed ? out : messages;
  },

  visualTransformTail(ctx: TransformerContext, message: UIMessage): UIMessage {
    return transformThinkMessage(ctx, message, null, finishedTimes);
  },

  onGenerationFinish(ctx: TransformerContext, messages: UIMessage[]): UIMessage[] {
    const now: string = clockOf(ctx).nowIso();
    let changed: boolean = false;
    const out: UIMessage[] = messages.map((m: UIMessage): UIMessage => {
      const next: UIMessage = transformThinkMessage(ctx, m, now, finishedTimes);
      if (next !== m) changed = true;
      return next;
    });
    return changed ? out : messages;
  },
});

// Chat 每次运行单独持有首个闭合时间，后续正文 chunk 和终态不延长思考时长。
export const createThinkTagTransformer = (): TailSafeOutputMessageTransformer =>
  makeThinkTagTransformer(new Map<string, string>());

// 保留其他消费者的无状态接口，不引入跨会话缓存。
export const thinkTagTransformer: TailSafeOutputMessageTransformer = makeThinkTagTransformer();

// ===== replaceRegexes(AssistantRegexProcessor.kt:9-42,JVM 路径) =====
// 过滤 enabled && visualOnly == visual && scope 命中;非法规则编译失败即跳过;顺序 fold

export const replaceRegexes = (
  input: string, rules: AssistantRegex[], scope: AssistantAffectScope, visual: boolean,
): string => {
  const applicable: AssistantRegex[] = rules.filter(
    (r: AssistantRegex): boolean =>
      r.enabled && r.visualOnly === visual && r.affectingScope.includes(scope),
  );
  let acc: string = input;
  for (const rule of applicable) {
    let re: RegExp;
    try {
      re = new RegExp(rule.findRegex, 'g');
    } catch (_e) {
      continue; // 对齐 Android runCatching{Regex(...)}.getOrNull() → 跳过
    }
    try {
      acc = acc.replace(re, rule.replaceString);
    } catch (_e) {
      // 对齐 Android applyJvm 的 try/catch → 保留 acc
    }
  }
  return acc;
};

// ===== RegexOutputTransformer =====
// 忠实移植:只实现 visualTransform/visualTransformTail(onGenerationFinish 缺省 = 恒等)

const transformRegexMessage = (ctx: TransformerContext, message: UIMessage): UIMessage => {
  const rules: AssistantRegex[] = ctx.assistant.regexes;
  if (rules.length === 0) return message;
  if (message.role !== 'assistant') return message;
  let changed: boolean = false;
  const parts: UIMessagePart[] = message.parts.map((part: UIMessagePart): UIMessagePart => {
    if (part.type === 'text') {
      const text: string = replaceRegexes(part.text, rules, 'assistant', false);
      if (text !== part.text) changed = true;
      return text === part.text ? part : { ...part, text };
    }
    if (part.type === 'reasoning') {
      const reasoning: string = replaceRegexes(part.reasoning, rules, 'assistant', false);
      if (reasoning !== part.reasoning) changed = true;
      return reasoning === part.reasoning ? part : { ...part, reasoning };
    }
    return part;
  });
  return changed ? { ...message, parts } : message;
};

export const regexOutputTransformer: TailSafeOutputMessageTransformer = {
  visualTransform(ctx: TransformerContext, messages: UIMessage[]): UIMessage[] {
    if (ctx.assistant.regexes.length === 0) return messages;
    let changed: boolean = false;
    const out: UIMessage[] = messages.map((m: UIMessage): UIMessage => {
      const next: UIMessage = transformRegexMessage(ctx, m);
      if (next !== m) changed = true;
      return next;
    });
    return changed ? out : messages;
  },

  visualTransformTail(ctx: TransformerContext, message: UIMessage): UIMessage {
    if (ctx.assistant.regexes.length === 0) return message;
    return transformRegexMessage(ctx, message);
  },
};
