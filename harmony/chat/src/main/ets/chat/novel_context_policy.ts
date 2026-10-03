// Novel provider 发送副本的窗口策略；完整历史始终留在 canonical conversation。
import type { NovelModelRequest, NovelContextPreviewReceipt } from '@amber/deepread-domain';
import type { UIMessage } from './message.ts';
import { isToolExecuted, makeSystemMessage, makeUserMessage } from './message.ts';
import { estimateContextWindow, estimateTokens, makeCompactPolicy } from './context_compact.ts';
import { resolveNovelMaxOutputTokens } from './novel_request_config.ts';

// Claude / Google / Responses 只读取首条 system；合并仅发生在发送副本。
export const mergeNovelSystemMessages = (messages: UIMessage[]): UIMessage[] => {
  const systems: UIMessage[] = messages.filter((message): boolean => message.role === 'system');
  if (systems.length < 2) return messages.slice();
  const merged: UIMessage = { ...systems[0], parts: systems.flatMap((message) => message.parts) };
  return [merged, ...messages.filter((message): boolean => message.role !== 'system')];
};

export interface NovelContextPolicy {
  tokenBudget: number;
  prepare: (messages: UIMessage[], extraSystemText?: string) => UIMessage[];
  assertBudget: (messages: UIMessage[]) => void;
  preview: (messages: UIMessage[], extraSystemText?: string) => NovelContextPreviewReceipt;
}

const groupUserTurns = (messages: UIMessage[]): UIMessage[][] => {
  const turns: UIMessage[][] = [];
  for (const message of messages) {
    if (message.role === 'user' || turns.length === 0) turns.push([]);
    turns[turns.length - 1].push(message);
  }
  return turns;
};

export const novelInputTokenBudget = (maxOutputTokens: number | null, configuredWindowTokens: number | null): number => {
  const windowTokens: number = estimateContextWindow(configuredWindowTokens);
  const outputTokens: number = resolveNovelMaxOutputTokens(maxOutputTokens, null, configuredWindowTokens);
  return Math.min(Math.floor(windowTokens * makeCompactPolicy({}).forceRatio), windowTokens - outputTokens);
};

export const estimateNovelInputTokens = (systemPrompt: string, userPrompt: string): number =>
  estimateTokens([makeSystemMessage(systemPrompt), makeUserMessage(userPrompt)]);

export const createNovelContextPolicy = (
  request: NovelModelRequest, configuredWindowTokens: number | null,
): NovelContextPolicy => {
  const tokenBudget: number = novelInputTokenBudget(request.maxOutputTokens, configuredWindowTokens);
  const historyIds: Set<string> = new Set(request.history.map((message: UIMessage): string => message.id));
  const excludedIds: Set<string> = new Set(request.context?.excludedHistoryMessageIds ?? []);
  const continuationToolId: string | null = request.operation.kind === 'tool_continuation'
    ? request.operation.toolCallId : null;
  const assertBudget = (messages: UIMessage[]): void => {
    if (estimateTokens(messages) > tokenBudget) {
      throw new Error('小说上下文超出当前模型窗口；请缩短本次输入或选择更大上下文的模型。'
        + '作者必要资料、来源正文和本轮工具结果均未截断。');
    }
  };

  const preview = (messages: UIMessage[], extraSystemText: string = ''): NovelContextPreviewReceipt => {
      const turns: UIMessage[][] = groupUserTurns(messages);
      // 新生成的消息不是初始 history 的一部分，不能当成旧历史裁掉。工具续接
      // 的来源 user 和工具所在整轮同样是本次必需输入，即使它已经持久化。
      const requiredTurns: Set<UIMessage[]> = new Set();
      for (const turn of turns) {
        if (turn.some((message: UIMessage): boolean => !historyIds.has(message.id)
          || message.parts.some((part): boolean => part.type === 'tool'
            && (!isToolExecuted(part) || part.toolCallId === continuationToolId)))) {
          requiredTurns.add(turn);
        }
      }
      const requiredMessages: UIMessage[] = turns.filter((turn): boolean => requiredTurns.has(turn)).flat();
      const historyTurns: UIMessage[][] = turns.filter((turn): boolean => !requiredTurns.has(turn))
        .map((turn): UIMessage[] => turn.filter((message: UIMessage): boolean =>
          !historyIds.has(message.id) || !excludedIds.has(message.id)))
        .filter((turn): boolean => turn.length > 0);
      const sections = request.context?.sections;
      const selectedSectionKeys: Set<string> = new Set();
      for (const section of sections ?? []) {
        if (section.required) selectedSectionKeys.add(section.key);
      }
      const makeContextMessages = (): UIMessage[] => {
        const systemText: string = sections !== undefined
          ? sections.filter((section): boolean => selectedSectionKeys.has(section.key))
            .map((section): string => section.text).filter((text): boolean => text.trim().length > 0)
            .join('\n\n')
          : request.systemPrompt;
        return systemText.trim().length > 0 ? [makeSystemMessage(systemText)] : [];
      };
      const extraMessages: UIMessage[] = extraSystemText.trim().length > 0
        ? [makeSystemMessage(extraSystemText)] : [];
      const fits = (selected: UIMessage[]): boolean => estimateTokens([
        ...makeContextMessages(), ...extraMessages, ...selected,
      ]) <= tokenBudget;
      assertBudget([...makeContextMessages(), ...extraMessages, ...requiredMessages]);
      // Optional sections 按领域给出的顺序取完整块，不截取段落或来源正文。
      for (const section of sections ?? []) {
        if (section.required) continue;
        selectedSectionKeys.add(section.key);
        if (!fits(requiredMessages)) selectedSectionKeys.delete(section.key);
      }
      let selectedHistory: UIMessage[] = [];
      for (let index = historyTurns.length - 1; index >= 0; index -= 1) {
        const candidate: UIMessage[] = [...historyTurns[index], ...selectedHistory];
        if (!fits([...candidate, ...requiredMessages])) break;
        selectedHistory = candidate;
      }
      const selectedIds: Set<string> = new Set([
        ...selectedHistory, ...requiredMessages,
      ].map((message: UIMessage): string => message.id));
      const selected: UIMessage[] = messages.filter((message: UIMessage): boolean => selectedIds.has(message.id));
      // 无 context 的 helper 仍由 Chat 组装原 system；只在这里计入预算。
      const preparedMessages: UIMessage[] = sections !== undefined ? [...makeContextMessages(), ...selected] : selected;
      return {
        tokenBudget,
        estimatedInputTokens: estimateTokens([...makeContextMessages(), ...extraMessages, ...selected]),
        maxOutputTokens: request.maxOutputTokens ?? 0,
        modelLabel: '',
        preparedMessages,
        sections: (sections ?? []).map(section => ({ key: section.key, required: section.required,
          included: selectedSectionKeys.has(section.key), estimatedTokens: estimateTokens([makeSystemMessage(section.text)]) })),
        materialDecisions: (request.context?.materialDecisions ?? []).map(decision => {
          const included: boolean = selectedSectionKeys.has(`material:${decision.materialId}`);
          return { ...decision, included, estimatedTokens: estimateTokens([makeSystemMessage(decision.text)]),
            reason: decision.included && !included ? 'budgetTrimmed' : decision.reason };
        }),
        historyMessagesIncluded: selected.filter(message => historyIds.has(message.id)).length,
        historyMessagesExcluded: request.history.length - selected.filter(message => historyIds.has(message.id)).length,
      };
  };
  return { tokenBudget, assertBudget, preview,
    prepare: (messages: UIMessage[], extraSystemText: string = ''): UIMessage[] => preview(messages, extraSystemText).preparedMessages,
  };
};
