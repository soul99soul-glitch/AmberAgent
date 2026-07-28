// prompt_injection_transformer — 提示词注入(ModeInjection + Lorebook)(D-075)
// Android 基准:
//   app/.../core/ai/transformers/PromptInjectionTransformer.kt(全文 264 行)
//   core/model/Assistant.kt:144-280(InjectionPosition/PromptInjection/Lorebook/
//     isTriggered/extractContextForMatching)
//   ai/core/SystemPromptMarkers.kt:3-4(cache metadata 常量)
// 偏差:
//   - ctx.settings → deps 注入(modeInjections/lorebooks 读取时机 = transform 调用时)
//   - Java Regex → JS RegExp(PD-005 已登记;构造/匹配异常 → 该 keyword false)
//   - Uuid → string id
import type { Assistant } from './assistant.ts';
import { newId } from './ids.ts';
import { getTools, makeAssistantMessage, makeUserMessage, makeUIMessage, toText } from './message.ts';
import type { UIMessage, UIMessagePart } from './message.ts';
import {
  SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED,
} from './context_assembly.ts';
import type { JsonObject } from './json.ts';
import type { MessageTransformer, TransformerContext } from './transformer_pipeline.ts';

// 常量定义于 context_assembly.ts(D-045;SystemPromptMarkers.kt:3-4),经 index.ts 导出
export { SYSTEM_PROMPT_CACHE_CONTROL_METADATA, SYSTEM_PROMPT_CACHE_DISABLED };

// InjectionPosition(SerialName 小写逐字)
export type InjectionPosition =
  | 'before_system_prompt'
  | 'after_system_prompt'
  | 'top_of_chat'
  | 'bottom_of_chat'
  | 'at_depth';

// 注入角色:USER 或 ASSISTANT(merged 时 else → user,:230-233)
export type InjectionRole = 'user' | 'assistant';

export interface PromptInjection {
  id: string;
  name: string;
  enabled: boolean;
  priority: number;
  position: InjectionPosition;
  content: string;
  injectDepth: number; // default 4;AT_DEPTH 用,从最新消息往前数
  role: InjectionRole;
}

export interface RegexInjection extends PromptInjection {
  keywords: string[];
  useRegex: boolean;
  caseSensitive: boolean;
  scanDepth: number; // default 4
  constantActive: boolean;
}

export interface Lorebook {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  entries: RegexInjection[];
}

// ===== factories(Android 默认值逐字) =====

export const makeModeInjection = (
  opts: Partial<PromptInjection> = {},
): PromptInjection => ({
  id: opts.id ?? newId(),
  name: opts.name ?? '',
  enabled: opts.enabled ?? true,
  priority: opts.priority ?? 0,
  position: opts.position ?? 'after_system_prompt',
  content: opts.content ?? '',
  injectDepth: opts.injectDepth ?? 4,
  role: opts.role ?? 'user',
});

export const makeRegexInjection = (
  opts: Partial<RegexInjection> = {},
): RegexInjection => ({
  ...makeModeInjection(opts),
  keywords: opts.keywords ?? [],
  useRegex: opts.useRegex ?? false,
  caseSensitive: opts.caseSensitive ?? false,
  scanDepth: opts.scanDepth ?? 4,
  constantActive: opts.constantActive ?? false,
});

export const makeLorebook = (opts: Partial<Lorebook> = {}): Lorebook => ({
  id: opts.id ?? newId(),
  name: opts.name ?? '',
  description: opts.description ?? '',
  enabled: opts.enabled ?? true,
  entries: opts.entries ?? [],
});

// ===== 触发判定(Assistant.kt:236-257) =====

export const isTriggered = (entry: RegexInjection, context: string): boolean => {
  if (!entry.enabled) return false;
  if (entry.constantActive) return true;
  if (entry.keywords.length === 0) return false;
  return entry.keywords.some((keyword: string): boolean => {
    if (entry.useRegex) {
      try {
        // RegexOption.IGNORE_CASE → 'i';构造/匹配异常 → false(:247-249)
        return new RegExp(keyword, entry.caseSensitive ? '' : 'i').test(context);
      } catch {
        return false;
      }
    }
    if (entry.caseSensitive) return context.includes(keyword);
    return context.toLowerCase().includes(keyword.toLowerCase());
  });
};

// ===== 上下文提取(Assistant.kt:266-274) =====
// takeLast(scanDepth):0 → 空;负 → Kotlin IllegalArgumentException(钉住语义)
export const extractContextForMatching = (messages: UIMessage[], scanDepth: number): string => {
  if (scanDepth < 0) {
    throw new Error(`Requested element count ${scanDepth} is less than zero.`);
  }
  const tail: UIMessage[] = scanDepth === 0 ? [] : messages.slice(-scanDepth);
  return tail.map((m: UIMessage): string => toText(m)).join('\n');
};

// ===== 收集(:69-101) =====

export const collectInjections = (
  messages: UIMessage[],
  assistant: Assistant,
  modeInjections: PromptInjection[],
  lorebooks: Lorebook[],
): PromptInjection[] => {
  const injections: PromptInjection[] = [];
  // 1. 关联 ModeInjection(enabled && assistant.modeInjectionIds.contains)
  for (const mi of modeInjections) {
    if (mi.enabled && assistant.modeInjectionIds.includes(mi.id)) injections.push(mi);
  }
  // 2. 关联 Lorebook 中被触发的 RegexInjection(只取非 SYSTEM 消息匹配)
  const enabledLorebooks: Lorebook[] = lorebooks.filter(
    (lb: Lorebook): boolean => lb.enabled && assistant.lorebookIds.includes(lb.id));
  if (enabledLorebooks.length > 0) {
    const nonSystemMessages: UIMessage[] = messages.filter(
      (m: UIMessage): boolean => m.role !== 'system');
    for (const lorebook of enabledLorebooks) {
      for (const entry of lorebook.entries) {
        const context: string = extractContextForMatching(nonSystemMessages, entry.scanDepth);
        if (isTriggered(entry, context)) injections.push(entry);
      }
    }
  }
  return injections;
};

// ===== 应用(:106-219) =====

const textPart = (text: string, metadata: JsonObject | null = null): UIMessagePart => ({
  type: 'text', text, metadata,
});

export const cacheDisabledMetadata = (): JsonObject => {
  const m: JsonObject = {};
  m[SYSTEM_PROMPT_CACHE_CONTROL_METADATA] = SYSTEM_PROMPT_CACHE_DISABLED;
  return m;
};

const joinContents = (list: PromptInjection[] | undefined): string =>
  list === undefined ? '' : list.map((i: PromptInjection): string => i.content).join('\n');

// createMergedInjectionMessages(:225-235):按 role 分组(首见序)合并;
//   assistant → UIMessage.assistant,else → UIMessage.user
export const createMergedInjectionMessages = (injections: PromptInjection[]): UIMessage[] => {
  const byRole = new Map<InjectionRole, PromptInjection[]>();
  for (const i of injections) {
    const g: PromptInjection[] | undefined = byRole.get(i.role);
    if (g === undefined) byRole.set(i.role, [i]);
    else g.push(i);
  }
  const out: UIMessage[] = [];
  for (const [role, grouped] of byRole) {
    const mergedContent: string = grouped.map((i: PromptInjection): string => i.content).join('\n');
    out.push(role === 'assistant' ? makeAssistantMessage(mergedContent) : makeUserMessage(mergedContent));
  }
  return out;
};

// findSafeInsertIndex(:243-264):不前插到 USER → ASSISTANT(含Tool) 之间
export const findSafeInsertIndex = (messages: UIMessage[], targetIndex: number): number => {
  let index: number = Math.max(0, Math.min(targetIndex, messages.length));
  while (index > 0) {
    const prevMessage: UIMessage | undefined = index - 1 >= 0 ? messages[index - 1] : undefined;
    const currentMessage: UIMessage | undefined = index < messages.length ? messages[index] : undefined;
    const isPrevUser: boolean = prevMessage !== undefined && prevMessage.role === 'user';
    const isCurrentAssistantWithTools: boolean = currentMessage !== undefined
      && currentMessage.role === 'assistant' && getTools(currentMessage).length > 0;
    if (isPrevUser && isCurrentAssistantWithTools) {
      index--;
    } else {
      break;
    }
  }
  return index;
};

// applyInjections(:106-219 全文忠实)
export const applyInjections = (
  messages: UIMessage[],
  byPosition: Map<InjectionPosition, PromptInjection[]>,
): UIMessage[] => {
  const result: UIMessage[] = [...messages];

  const systemIndex: number = result.findIndex((m: UIMessage): boolean => m.role === 'system');

  // BEFORE_SYSTEM_PROMPT / AFTER_SYSTEM_PROMPT
  const beforeContent: string = joinContents(byPosition.get('before_system_prompt'));
  const afterContent: string = joinContents(byPosition.get('after_system_prompt'));
  if (systemIndex >= 0) {
    if (beforeContent.length > 0 || afterContent.length > 0) {
      const systemMessage: UIMessage = result[systemIndex];
      const parts: UIMessagePart[] = [];
      if (beforeContent.length > 0) {
        parts.push(textPart(beforeContent, cacheDisabledMetadata()));
      }
      for (const p of systemMessage.parts) parts.push(p);
      if (afterContent.length > 0) {
        parts.push(textPart(afterContent));
      }
      result[systemIndex] = { ...systemMessage, parts };
    }
  } else if (beforeContent.length > 0 || afterContent.length > 0) {
    // 没有系统消息 → 新建系统消息插到 0(:144-174)
    const parts: UIMessagePart[] = [];
    if (beforeContent.length > 0) {
      parts.push(textPart(beforeContent, cacheDisabledMetadata()));
    }
    if (afterContent.length > 0) {
      parts.push(textPart(beforeContent.length > 0 ? `\n${afterContent}` : afterContent));
    }
    result.unshift(makeUIMessage('system', parts));
  }

  // TOP_OF_CHAT:第一条 user 前(:176-187)
  const topInjections: PromptInjection[] | undefined = byPosition.get('top_of_chat');
  if (topInjections !== undefined && topInjections.length > 0) {
    const firstUser: number = result.findIndex((m: UIMessage): boolean => m.role === 'user');
    let insertIndex: number = firstUser >= 0 ? firstUser : result.length;
    insertIndex = findSafeInsertIndex(result, insertIndex);
    for (const message of createMergedInjectionMessages(topInjections)) {
      result.splice(insertIndex, 0, message);
      insertIndex++;
    }
  }

  // BOTTOM_OF_CHAT:最后一条消息之前(:189-198)
  const bottomInjections: PromptInjection[] | undefined = byPosition.get('bottom_of_chat');
  if (bottomInjections !== undefined && bottomInjections.length > 0) {
    let insertIndex: number = Math.max(result.length - 1, 0);
    insertIndex = findSafeInsertIndex(result, insertIndex);
    for (const message of createMergedInjectionMessages(bottomInjections)) {
      result.splice(insertIndex, 0, message);
      insertIndex++;
    }
  }

  // AT_DEPTH:按 injectDepth 分组,深度从大到小(:200-216)
  const atDepthInjections: PromptInjection[] | undefined = byPosition.get('at_depth');
  if (atDepthInjections !== undefined && atDepthInjections.length > 0) {
    const byDepth = new Map<number, PromptInjection[]>();
    for (const i of atDepthInjections) {
      const g: PromptInjection[] | undefined = byDepth.get(i.injectDepth);
      if (g === undefined) byDepth.set(i.injectDepth, [i]);
      else g.push(i);
    }
    const depths: number[] = [...byDepth.keys()].sort(
      (a: number, b: number): number => b - a);
    for (const depth of depths) {
      const injections: PromptInjection[] = byDepth.get(depth) as PromptInjection[];
      let insertIndex: number = Math.max(
        0, Math.min(result.length - Math.max(depth, 1), result.length));
      insertIndex = findSafeInsertIndex(result, insertIndex);
      for (const message of createMergedInjectionMessages(injections)) {
        result.splice(insertIndex, 0, message);
        insertIndex++;
      }
    }
  }

  return result;
};

// transformMessages(:39-64):空 → 原引用;否则 priority 降序(stable)分组应用
export const transformMessages = (
  messages: UIMessage[],
  assistant: Assistant,
  modeInjections: PromptInjection[],
  lorebooks: Lorebook[],
): UIMessage[] => {
  const injections: PromptInjection[] =
    collectInjections(messages, assistant, modeInjections, lorebooks);
  if (injections.length === 0) return messages;
  const sorted: PromptInjection[] = [...injections].sort(
    (a: PromptInjection, b: PromptInjection): number => b.priority - a.priority);
  const byPosition = new Map<InjectionPosition, PromptInjection[]>();
  for (const i of sorted) {
    const g: PromptInjection[] | undefined = byPosition.get(i.position);
    if (g === undefined) byPosition.set(i.position, [i]);
    else g.push(i);
  }
  return applyInjections(messages, byPosition);
};

// ===== transformer(deps 注入 settings 两列表;读取时机 = transform 调用时) =====

export interface PromptInjectionDeps {
  modeInjections: () => PromptInjection[];
  lorebooks: () => Lorebook[];
}

export const createPromptInjectionTransformer = (
  deps: PromptInjectionDeps,
): MessageTransformer => ({
  transform: (ctx: TransformerContext, messages: UIMessage[]): UIMessage[] =>
    transformMessages(messages, ctx.assistant, deps.modeInjections(), deps.lorebooks()),
});
