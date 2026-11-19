// turn_tool_grouping — 对齐 iOS ChatAssistantTurnToolGrouping 的纯函数 L1 投影
// - 识别同一助手回合（head / member / none）与 isAssistantContinuation
// - 聚合常规只读工具与思考状态为头部唯一单行 TurnProgressSummary
// - 严格豁免：待审批（pending）、ask_user、媒体/MiniApp/文档产物、未恢复失败工具

import type { UIMessage, UIMessagePart, UIMessagePartTool, UIMessagePartReasoning } from './message.ts';
import { toolInputAsJson } from './message.ts';
import { toolInputDisplayTitle, toolStaticLabel } from './tool_display.ts';
import { toolOutputJson } from './tool_activity.ts';

export type AssistantTurnRole = 'head' | 'member' | 'none';

export type TurnProgressStatusKind = 'thinking' | 'tool' | 'writing' | 'completed' | 'error';

export interface TurnProgressSummary {
  showSingleLineStatus: boolean;
  statusKind: TurnProgressStatusKind;
  headline: string;
  stepCount: number;
  activeToolName: string;
  hasError: boolean;
  allTools: UIMessagePartTool[];
}

export interface MessageTurnProjection {
  isAssistantContinuation: boolean;
  turnRole: AssistantTurnRole;
  turnProgress: TurnProgressSummary | null;
}

const SUBAGENT_OR_COUNCIL_TOOLS: ReadonlySet<string> = new Set<string>([
  'subagent_start', 'subagent_wait', 'subagent_read', 'subagent_cancel',
  'model_council_start', 'model_council_wait', 'model_council_read',
  'model_council_cancel', 'model_council_make_report',
]);

export const isToolFailureUnrecovered = (
  part: UIMessagePartTool,
  laterSucceededTools: ReadonlySet<string>,
): boolean => {
  if (part.approvalState.type === 'denied') return true;
  if (part.output.length === 0) return false;
  const outJson = toolOutputJson(part);
  const hasExplicitError: boolean = outJson['ok'] === false
    || (typeof outJson['error'] === 'string' && outJson['error'].trim().length > 0)
    || (part.metadata !== null && part.metadata['tool_error'] === true);
  if (!hasExplicitError) return false;
  return !laterSucceededTools.has(part.toolName);
};

// 严格豁免原则：待审批 / ask_user / 媒体产物 / 子代理与议会运行卡 / 未恢复工具失败绝不被单行折叠隐藏
export const isExemptStandaloneToolPart = (
  part: UIMessagePartTool,
  laterSucceededTools: ReadonlySet<string>,
): boolean => {
  if (part.approvalState.type === 'pending') return true;
  if (part.toolName === 'ask_user') return true;
  if (part.metadata !== null && (part.metadata['recipe_manual'] === true || part.metadata['plugin_manual'] === true)) {
    return true;
  }
  if (SUBAGENT_OR_COUNCIL_TOOLS.has(part.toolName)) return true;
  if (part.toolName === 'generate_image') {
    for (const out of part.output) {
      if (out.type === 'image') return true;
    }
  }
  if (isToolFailureUnrecovered(part, laterSucceededTools)) return true;
  return false;
};

const toolDisplayPhrase = (part: UIMessagePartTool): string => {
  try {
    const inputObj = toolInputAsJson(part);
    const customTitle: string | null = toolInputDisplayTitle(inputObj);
    if (customTitle !== null && customTitle.length > 0) return customTitle;
    const staticLabel: string | null = toolStaticLabel(part.toolName, inputObj);
    if (staticLabel !== null && staticLabel.length > 0) return staticLabel;
  } catch (_e) {
    const fallback: string | null = toolStaticLabel(part.toolName);
    if (fallback !== null && fallback.length > 0) return fallback;
  }
  return part.toolName;
};

const hasVisibleTextContent = (msg: UIMessage): boolean => {
  for (const part of msg.parts as UIMessagePart[]) {
    if (part.type === 'text' && part.text.trim().length > 0) return true;
  }
  return false;
};

export const buildLaterSucceededToolSet = (messages: UIMessage[]): Set<string> => {
  const succeeded: Set<string> = new Set<string>();
  for (const msg of messages) {
    for (const part of msg.parts as UIMessagePart[]) {
      if (part.type !== 'tool') continue;
      const t = part as UIMessagePartTool;
      if (t.approvalState.type === 'denied' || t.approvalState.type === 'pending') continue;
      if (t.output.length === 0) continue;
      const outJson = toolOutputJson(t);
      const err: boolean = outJson['ok'] === false
        || (typeof outJson['error'] === 'string' && outJson['error'].trim().length > 0)
        || (t.metadata !== null && t.metadata['tool_error'] === true);
      if (!err) succeeded.add(t.toolName);
    }
  }
  return succeeded;
};

const summarizeAssistantTurn = (
  turnMessages: UIMessage[],
  isStreamingTurn: boolean,
): TurnProgressSummary | null => {
  const allTools: UIMessagePartTool[] = [];
  let reasoningCount: number = 0;
  let activeReasoning: boolean = false;
  let activeTool: UIMessagePartTool | null = null;
  let lastTool: UIMessagePartTool | null = null;
  let hasOutputText: boolean = false;

  const succeededAfter: Set<string> = buildLaterSucceededToolSet(turnMessages);
  let hasUnrecoveredError: boolean = false;

  for (const msg of turnMessages) {
    if (hasVisibleTextContent(msg)) hasOutputText = true;
    for (const part of msg.parts as UIMessagePart[]) {
      if (part.type === 'reasoning') {
        const r = part as UIMessagePartReasoning;
        if (r.reasoning.trim().length > 0 || r.finishedAt === null) {
          reasoningCount++;
          if (r.finishedAt === null) activeReasoning = true;
        }
      } else if (part.type === 'tool') {
        const t = part as UIMessagePartTool;
        allTools.push(t);
        lastTool = t;
        if (t.output.length === 0 && t.approvalState.type !== 'denied') {
          activeTool = t;
        }
        if (isToolFailureUnrecovered(t, succeededAfter)) {
          hasUnrecoveredError = true;
        }
      }
    }
  }

  const stepCount: number = allTools.length + reasoningCount;
  if (stepCount === 0 && !isStreamingTurn) return null;

  let statusKind: TurnProgressStatusKind = 'completed';
  let headline: string = '';

  if (activeTool !== null) {
    statusKind = 'tool';
    headline = `${toolDisplayPhrase(activeTool)}…`;
  } else if (isStreamingTurn && activeReasoning && !hasOutputText) {
    statusKind = 'thinking';
    headline = '正在思考…';
  } else if (isStreamingTurn && hasOutputText) {
    statusKind = 'writing';
    headline = allTools.length > 0 ? `已参考 ${allTools.length} 步结果，正在组织回复…` : '正在组织回复…';
  } else if (isStreamingTurn) {
    statusKind = 'thinking';
    headline = '正在处理…';
  } else if (hasUnrecoveredError) {
    statusKind = 'error';
    headline = lastTool !== null ? `${toolDisplayPhrase(lastTool)} · 存在未恢复错误` : '步骤存在未恢复错误';
  } else if (allTools.length > 0) {
    statusKind = 'completed';
    const lastPhrase: string = lastTool !== null ? toolDisplayPhrase(lastTool) : '';
    headline = allTools.length === 1 && lastPhrase.length > 0
      ? `已完成 · ${lastPhrase}`
      : `已完成 ${allTools.length} 个步骤`;
  } else if (reasoningCount > 0) {
    statusKind = 'completed';
    headline = '已完成思考';
  }

  return {
    showSingleLineStatus: headline.length > 0,
    statusKind,
    headline,
    stepCount,
    activeToolName: activeTool !== null ? activeTool.toolName : '',
    hasError: hasUnrecoveredError,
    allTools,
  };
};

// 对已按顺序排布的消息（含可选 Episode 分割边界），计算每条消息的回合投影
export const projectAssistantTurns = (
  messages: UIMessage[],
  streamingTailIds: ReadonlySet<string> = new Set<string>(),
  episodeBoundaryAfterIds: ReadonlySet<string> = new Set<string>(),
): MessageTurnProjection[] => {
  const result: MessageTurnProjection[] = [];
  let i: number = 0;

  while (i < messages.length) {
    const msg: UIMessage = messages[i];
    if (msg.role !== 'assistant') {
      result.push({ isAssistantContinuation: false, turnRole: 'none', turnProgress: null });
      i++;
      continue;
    }

    // 收集同一连续助手回合（遇到 user/system 或跨过 episode 分割边界即切段）
    const turnStart: number = i;
    const turnMsgs: UIMessage[] = [];
    let isStreamingTurn: boolean = false;
    while (i < messages.length && messages[i].role === 'assistant') {
      const cur: UIMessage = messages[i];
      turnMsgs.push(cur);
      if (streamingTailIds.has(cur.id)) isStreamingTurn = true;
      i++;
      if (episodeBoundaryAfterIds.has(cur.id)) break;
    }

    const summary: TurnProgressSummary | null = summarizeAssistantTurn(turnMsgs, isStreamingTurn);
    for (let k: number = 0; k < turnMsgs.length; k++) {
      const isHead: boolean = k === 0;
      result.push({
        isAssistantContinuation: !isHead,
        turnRole: isHead ? 'head' : 'member',
        turnProgress: isHead ? summary : null,
      });
    }
    if (turnStart === i) i++;
  }
  return result;
};
