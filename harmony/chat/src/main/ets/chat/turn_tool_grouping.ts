// turn_tool_grouping — 对齐 iOS ChatAssistantTurnToolGrouping 的纯函数 L1 投影
// - 识别同一助手回合（head / member / none）与 isAssistantContinuation
// - 聚合常规只读工具与思考状态为头部唯一单行 TurnProgressSummary
// - 严格豁免：待审批（pending）、ask_user、媒体/MiniApp/文档产物、未恢复失败工具

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool, UIMessagePartReasoning } from './message.ts';
import { toolInputAsJson } from './message.ts';
import { toolInputDisplayTitle, toolStaticLabel } from './tool_display.ts';
import type { ToolActivityStatus } from './tool_activity.ts';
import { toolActivityStatus, toolOutputJson } from './tool_activity.ts';

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
  reasoning: string[];
}

export interface MessageTurnProjection {
  isAssistantContinuation: boolean;
  turnRole: AssistantTurnRole;
  turnProgress: TurnProgressSummary | null;
  standaloneToolIds: string[];
}

const SUBAGENT_OR_COUNCIL_TOOLS: ReadonlySet<string> = new Set<string>([
  'subagent_start', 'subagent_wait', 'subagent_read', 'subagent_cancel',
  'model_council_start', 'model_council_wait', 'model_council_read',
  'model_council_cancel', 'model_council_make_report',
]);

const turnToolStatus = (part: UIMessagePartTool): ToolActivityStatus =>
  part.metadata?.['tool_error'] === true && part.output.length > 0
    ? 'failed' : toolActivityStatus(part, false, toolOutputJson(part));

// 严格豁免原则：待审批 / ask_user / 媒体产物 / 子代理与议会运行卡 / 未恢复工具失败绝不被单行折叠隐藏
const isStandaloneToolPart = (part: UIMessagePartTool): boolean => {
  if (part.approvalState.type === 'pending') return true;
  if (part.toolName === 'ask_user') return true;
  if (part.metadata !== null && (part.metadata['recipe_manual'] === true || part.metadata['plugin_manual'] === true)) {
    return true;
  }
  if (SUBAGENT_OR_COUNCIL_TOOLS.has(part.toolName)) return true;
  for (const out of part.output) {
    if (out.type === 'image' || out.type === 'document') return true;
  }
  return false;
};

const unrecoveredFailures = (tools: UIMessagePartTool[]): Set<UIMessagePartTool> => {
  const succeededLater: Set<string> = new Set<string>();
  const failures: Set<UIMessagePartTool> = new Set<UIMessagePartTool>();
  for (let i: number = tools.length - 1; i >= 0; i--) {
    const part: UIMessagePartTool = tools[i];
    const status: ToolActivityStatus = turnToolStatus(part);
    if (part.approvalState.type === 'denied' || (status === 'failed' && !succeededLater.has(part.toolName))) {
      failures.add(part);
    } else if (status === 'succeeded') {
      succeededLater.add(part.toolName);
    }
  }
  return failures;
};

export const consecutiveTurnToolFailures = (messages: UIMessage[]): number => {
  let count: number = 0;
  for (let i: number = messages.length - 1; i >= 0 && messages[i].role === 'assistant'; i--) {
    const parts: UIMessagePart[] = messages[i].parts;
    for (let j: number = parts.length - 1; j >= 0; j--) {
      const part: UIMessagePart = parts[j];
      if (part.type !== 'tool') continue;
      if (part.approvalState.type === 'denied' || turnToolStatus(part) === 'failed') count++;
      else return count;
    }
  }
  return count;
};

const extractShortStringArg = (input: JsonObject | null, keys: string[], maxChars: number = 14): string => {
  if (input === null) return '';
  for (const key of keys) {
    const raw: JsonValue | undefined = input[key];
    if (typeof raw !== 'string') continue;
    const compact: string = raw.replace(/[\u0000-\u001f\u007f\u2028\u2029]+/g, ' ').replace(/\s+/g, ' ').trim();
    if (compact.length === 0) continue;
    const chars: string[] = Array.from(compact);
    return chars.length <= maxChars ? compact : `${chars.slice(0, maxChars - 1).join('')}…`;
  }
  return '';
};

const looksMechanicalToolLabel = (text: string): boolean => {
  const trimmed: string = text.trim();
  if (trimmed.length === 0) return true;
  if (/^[a-z0-9_.:/-]+$/i.test(trimmed)) return true;
  return trimmed.includes('调用工具') || trimmed.includes('执行工具') || trimmed.includes('MCP 工具');
};

const humanToolNameFallback = (toolName: string, inputObj: JsonObject | null): string => {
  const lower: string = toolName.trim().toLowerCase();
  if (lower === 'get_time_info') return '正在查看当前时间';
  if (lower === 'search_web' || lower === 'web_search' || lower === 'tavily_search' ||
    lower === 'exa_search' || lower === 'bocha_search' || lower === 'brave_search' ||
    lower === 'google_search' || lower === 'bing_search' || lower === 'duckduckgo_search' ||
    (lower.includes('search') && lower.includes('web'))) {
    const q: string = extractShortStringArg(inputObj, ['query', 'q', 'keyword', 'keywords', 'search_query']);
    return q.length > 0 ? `正在搜索「${q}」` : '正在上网查资料';
  }
  if (lower === 'scrape_web' || lower === 'web_scrape' || lower === 'fetch_url' ||
    lower === 'web_fetch' || lower === 'read_url' || lower === 'url_read' || lower === 'http_get') {
    return '正在阅读网页内容';
  }
  if (lower === 'wm_clear_session') return '正在清理网页登录状态';
  if (lower === 'wm_act') return '正在操作网页';
  if (lower.startsWith('wm_') || lower.startsWith('browser_')) return '正在查看网页';
  if (lower.startsWith('subagent_')) return '正在后台核查资料';
  if (lower.startsWith('model_council_')) return '正在多角度分析';
  if (lower === 'episode_recall' || lower === 'memory_search' || lower === 'memory_query' ||
    lower === 'memory_read' || lower === 'memory_get' || lower === 'memory_list' ||
    lower === 'memory_recent' || lower === 'memory_briefing') {
    return '正在回忆之前聊过的内容';
  }
  if (lower === 'memory_write' || lower === 'memory_save' || lower === 'memory_update' ||
    lower === 'memory_remember' || lower === 'memory_patch_page' || lower === 'memory_write_page') {
    return '正在记下重要信息';
  }
  if (lower === 'file_read' || lower === 'read_file' || lower === 'read_slices' ||
    lower === 'document_read' || lower === 'pdf_read') {
    return '正在查看文件内容';
  }
  if (lower === 'file_search' || lower === 'file_list' || lower === 'glob' ||
    lower === 'grep' || lower === 'batch_search' || lower === 'search_files') {
    return '正在查找相关文件';
  }
  if (lower === 'file_write' || lower === 'write_file' || lower === 'file_edit' ||
    lower === 'edit_file' || lower === 'apply_edits') {
    return '正在整理文档内容';
  }
  if (lower === 'weather_read' || lower === 'get_weather' || lower.startsWith('weather_')) {
    const city: string = extractShortStringArg(inputObj, ['city', 'location', 'place'], 8);
    return city.length > 0 ? `正在查${city}天气` : '正在查天气';
  }
  if (lower === 'calendar_events_list') return '正在查看日程';
  if (lower.startsWith('calendar_')) return '正在整理日程';
  if (lower === 'reminders_list') return '正在查看提醒事项';
  if (lower.startsWith('reminder')) return '正在整理提醒事项';
  if (lower === 'alarms_list') return '正在查看闹钟';
  if (lower.startsWith('alarm')) return '正在设置闹钟';
  if (lower.startsWith('notification_')) return '正在安排提醒通知';
  if (lower === 'contacts_pick') return '正在查看联系人';
  if (lower === 'photos_pick') return '正在查看照片';
  if (lower === 'image_generate' || lower === 'generate_image' || lower === 'image_gen' || lower === 'draw_image') {
    return '正在绘制图片';
  }
  if (lower === 'python_execute' || lower === 'code_execute' || lower === 'run_python' || lower.startsWith('sandbox_')) {
    return '正在运行计算';
  }
  if (lower === 'terminal_execute' || lower === 'shell_execute' || lower.startsWith('ssh_')) {
    return '正在处理设备任务';
  }
  if (lower === 'agent_soul_get') return '正在查看形象设定';
  if (lower === 'agent_soul_update') return '正在更新形象设定';
  if (lower.startsWith('agent_task_') || lower.startsWith('task_') || lower.startsWith('cron_')) {
    return '正在安排任务';
  }
  if (lower.startsWith('mini_app_') || lower.startsWith('widget_')) return '正在准备小工具';
  return '';
};

export const humanToolActivityPhrase = (part: UIMessagePartTool): string => {
  try {
    const inputObj = toolInputAsJson(part);
    const customTitle: string | null = toolInputDisplayTitle(inputObj);
    if (customTitle !== null && !looksMechanicalToolLabel(customTitle)) return customTitle;
    const mapped: string = humanToolNameFallback(part.toolName, inputObj);
    if (mapped.length > 0) return mapped;
    const staticLabel: string | null = toolStaticLabel(part.toolName, inputObj);
    if (staticLabel !== null && !looksMechanicalToolLabel(staticLabel)) {
      return staticLabel.startsWith('正在') ? staticLabel : `正在${staticLabel}`;
    }
  } catch (_e) {
    const mapped: string = humanToolNameFallback(part.toolName, null);
    if (mapped.length > 0) return mapped;
    const fallback: string | null = toolStaticLabel(part.toolName);
    if (fallback !== null && !looksMechanicalToolLabel(fallback)) {
      return fallback.startsWith('正在') ? fallback : `正在${fallback}`;
    }
  }
  return '正在查资料';
};

const humanToolErrorHeadline = (part: UIMessagePartTool): string => {
  const phrase: string = humanToolActivityPhrase(part).replace(/^正在/, '').replace(/…$/, '').trim();
  return phrase.length > 0 ? `${phrase}时遇到问题` : '处理请求时遇到问题';
};

const hasVisibleTextContent = (msg: UIMessage): boolean => {
  for (const part of msg.parts as UIMessagePart[]) {
    if (part.type === 'text' && part.text.trim().length > 0) return true;
  }
  return false;
};

const summarizeAssistantTurn = (
  turnMessages: UIMessage[],
  isStreamingTurn: boolean,
  standaloneToolIds: Set<string>,
): TurnProgressSummary | null => {
  const allTools: UIMessagePartTool[] = [];
  const reasoning: string[] = [];
  let reasoningCount: number = 0;
  let activeReasoning: boolean = false;
  let activeTool: UIMessagePartTool | null = null;
  let lastTool: UIMessagePartTool | null = null;
  let hasOutputText: boolean = false;

  for (const msg of turnMessages) {
    if (hasVisibleTextContent(msg)) hasOutputText = true;
    for (const part of msg.parts as UIMessagePart[]) {
      if (part.type === 'reasoning') {
        const r = part as UIMessagePartReasoning;
        if (r.reasoning.trim().length > 0 || r.finishedAt === null) {
          reasoningCount++;
          if (r.reasoning.trim().length > 0) reasoning.push(r.reasoning);
          if (r.finishedAt === null) activeReasoning = true;
        }
      } else if (part.type === 'tool') {
        const t = part as UIMessagePartTool;
        allTools.push(t);
        lastTool = t;
        if (t.output.length === 0 && t.approvalState.type !== 'denied') {
          activeTool = t;
        }
      }
    }
  }

  const stepCount: number = allTools.length + reasoningCount;
  if (stepCount === 0 && !isStreamingTurn) return null;
  const failures: Set<UIMessagePartTool> = unrecoveredFailures(allTools);
  let lastFailure: UIMessagePartTool | null = null;
  for (const tool of allTools) {
    const failed: boolean = failures.has(tool);
    if (failed) lastFailure = tool;
    if (failed || isStandaloneToolPart(tool)) standaloneToolIds.add(tool.toolCallId);
  }

  let statusKind: TurnProgressStatusKind = 'completed';
  let headline: string = '';

  if (activeTool !== null) {
    statusKind = 'tool';
    const phrase: string = humanToolActivityPhrase(activeTool);
    headline = phrase.endsWith('…') ? phrase : `${phrase}…`;
  } else if (isStreamingTurn && activeReasoning && !hasOutputText) {
    statusKind = 'thinking';
    headline = '正在思考';
  } else if (isStreamingTurn && hasOutputText) {
    statusKind = 'writing';
    headline = '正在回复';
  } else if (isStreamingTurn) {
    statusKind = 'thinking';
    headline = allTools.length > 0 ? '正在整理查到的内容' : '正在思考';
  } else if (lastFailure !== null) {
    statusKind = 'error';
    headline = humanToolErrorHeadline(lastFailure);
  } else if (allTools.length > 0) {
    statusKind = 'completed';
    headline = '已完成';
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
    hasError: lastFailure !== null,
    allTools,
    reasoning,
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
      result.push({ isAssistantContinuation: false, turnRole: 'none', turnProgress: null, standaloneToolIds: [] });
      i++;
      continue;
    }

    // 收集同一连续助手回合（遇到 user/system 或跨过 episode 分割边界即切段）
    const turnMsgs: UIMessage[] = [];
    let isStreamingTurn: boolean = false;
    while (i < messages.length && messages[i].role === 'assistant') {
      const cur: UIMessage = messages[i];
      turnMsgs.push(cur);
      if (streamingTailIds.has(cur.id)) isStreamingTurn = true;
      i++;
      if (episodeBoundaryAfterIds.has(cur.id)) break;
    }

    const standaloneToolIds: Set<string> = new Set<string>();
    const summary: TurnProgressSummary | null = summarizeAssistantTurn(turnMsgs, isStreamingTurn, standaloneToolIds);
    for (let k: number = 0; k < turnMsgs.length; k++) {
      const isHead: boolean = k === 0;
      const visible: string[] = [];
      for (const part of turnMsgs[k].parts) {
        if (part.type === 'tool' && standaloneToolIds.has(part.toolCallId)) visible.push(part.toolCallId);
      }
      result.push({
        isAssistantContinuation: !isHead,
        turnRole: isHead ? 'head' : 'member',
        turnProgress: isHead ? summary : null,
        standaloneToolIds: visible,
      });
    }
  }
  return result;
};
