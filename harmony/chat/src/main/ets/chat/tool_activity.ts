// tool_activity.ts — AgentToolActivityStore + 沙盒活动时间线(D-122)
// Android 锚点:
//   feature/runtime/api/.../AgentToolActivityStore.kt(全文 155 行):
//     单槽 StateFlow<SandboxActivityUiState?>;start/appendOutput/startTool/
//     complete(exitCode)/complete(output)/fail/cancel/clear;MAX_INPUT_PREVIEW
//     _CHARS=800/MAX_OUTPUT_TAIL_CHARS=1600(:152-153);appendTailLine(:133-140);
//     withConversation ThreadLocal 协程作用域(:20-27)
//   feature/runtime/api/.../AgentRuntimeModels.kt:77-110(ToolActivityStatus
//     五态 + SandboxActivityUiState 十三字段)
//   feature/runtime/api/.../ToolFailure.kt(全文):toAgentToolFailureJson
//     {status:'failed',message:sanitized,recoverable};sanitizedToolFailureMessage
//     正则/360 截断(:15-26);isRecoverable 仅 VirtualMachineError/ThreadDeath
//     false — JS 无等价 → 恒 true(登记)
//   ChatPage.kt:1166-1470(时间线纯函数):MAX_SANDBOX_TIMELINE_ITEMS=24/
//     MAX_SANDBOX_OUTPUT_TAIL_CHARS=1600/MAX_SANDBOX_JSON_PARSE_CHARS=80000
//     (:1166-1168);mergeSandboxTimeline(:1171-1189);deriveSandboxActivities
//     (:1192-1236);idleSandboxActivity(:1239-1250);withStepProgress
//     (:1252-1262);sandboxActivityTools/currentRunMessages(:1264-1273);
//     isSandboxActivityTool 名表(:1275-1318);activityStatus(:1290-1318
//     后段);sandboxTitle(:1321-1361);inputPreview(:1363-1383);
//     defaultRuntime(:1385-1401);defaultWorkspace(:1403-1407);
//     indicatesFailure(:1409-1417);outputTail(:1419-1424);outputJson
//     (:1426-1432);outputText(:1434-1450);getStringContent(:1452-1453);
//     getFirstStringContent(:1455-1461);isActiveOperation(:1462-1463);
//     compactSandboxText(:1465-1468)
// 偏差登记:
//   - ThreadLocal+asContextElement → 模块级 scope 变量(finally 恢复;
//     JS 单线程异步交错下并发 withConversation 语义弱于协程作用域 — 登记)
//   - kotlinx JsonPrimitive.contentOrNull(number/bool → 字符串)语义以
//     getStringContent/jsonIntOrNull/jsonBoolStrictOrNull 三个 helper 复刻
//   - Message.kt Tool.inputAsJson 已有 toolInputAsJson(message.ts:186),复用

import { currentMessages } from './conversation.ts';
import { newId } from './ids.ts';
import { terminalTail } from './terminal/utf8.ts';
import type { Conversation } from './conversation.ts';
import {
  isToolExecuted, toolInputAsJson,
} from './message.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool } from './message.ts';
import type { JsonObject } from './json.ts';
// ToolFailure.kt 已由 tool_dispatcher.ts 移植(D-056;同一 Android 源)—
//   sanitizedToolFailureMessage/isRecoverableToolFailure/toAgentToolFailureJson
//   复用,不重复实现
import { toAgentToolFailureJson } from './tool_dispatcher.ts';

// ===== ToolActivityStatus(AgentRuntimeModels.kt:77-92) =====

export type ToolActivityStatus =
  | 'running'
  | 'waiting_for_permission'
  | 'succeeded'
  | 'failed'
  | 'cancelled';

// ===== SandboxActivityUiState(AgentRuntimeModels.kt:94-110) =====

export interface SandboxActivityUiState {
  toolCallId: string;
  toolName: string;
  title: string;
  status: ToolActivityStatus;
  conversationId: string | null;
  inputPreview: string;
  outputTail: string;
  runtime: string;
  workspace: string;
  startedAtEpochMillis: number | null;
  endedAtEpochMillis: number | null;
  canCancel: boolean;
  stepIndex: number | null;
  stepTotal: number | null;
}

export interface SandboxActivityUiStateOpts {
  toolCallId: string;
  toolName: string;
  title: string;
  status: ToolActivityStatus;
  conversationId?: string | null;
  inputPreview?: string;
  outputTail?: string;
  runtime?: string;
  workspace?: string;
  startedAtEpochMillis?: number | null;
  endedAtEpochMillis?: number | null;
  canCancel?: boolean;
  stepIndex?: number | null;
  stepTotal?: number | null;
}

export const makeSandboxActivityUiState = (
  opts: SandboxActivityUiStateOpts,
): SandboxActivityUiState => ({
  toolCallId: opts.toolCallId,
  toolName: opts.toolName,
  title: opts.title,
  status: opts.status,
  conversationId: opts.conversationId ?? null,
  inputPreview: opts.inputPreview ?? '',
  outputTail: opts.outputTail ?? '',
  runtime: opts.runtime ?? '',
  workspace: opts.workspace ?? '',
  startedAtEpochMillis: opts.startedAtEpochMillis ?? null,
  endedAtEpochMillis: opts.endedAtEpochMillis ?? null,
  canCancel: opts.canCancel ?? false,
  stepIndex: opts.stepIndex ?? null,
  stepTotal: opts.stepTotal ?? null,
});

// ===== ToolFailure(ToolFailure.kt)→ tool_dispatcher.ts 复用(见 import 注) =====

// ===== AgentToolActivityStore(AgentToolActivityStore.kt 全文 155 行) =====

export const ACTIVITY_MAX_INPUT_PREVIEW_CHARS: number = 800;
export const ACTIVITY_MAX_OUTPUT_TAIL_CHARS: number = 1600;

// appendTailLine(:133-140)
const appendTailLine = (current: string, line: string): string => {
  const joined: string = current.trim().length > 0 ? `${current}\n${line}` : line;
  return joined.slice(-ACTIVITY_MAX_OUTPUT_TAIL_CHARS);
};

type ActivityListener = (activity: SandboxActivityUiState | null) => void;

export class AgentToolActivityStore {
  private current: SandboxActivityUiState | null = null;
  private readonly listeners: ActivityListener[] = [];
  // ThreadLocal 协程作用域(:14) → 模块实例级 scope 变量(登记偏差见头注)
  private conversationScope: string | null = null;

  // sandboxActivity StateFlow → 快照 + 订阅(首发射对齐 StateFlow 初值)
  get sandboxActivity(): SandboxActivityUiState | null {
    return this.current;
  }

  subscribe(listener: ActivityListener): () => void {
    this.listeners.push(listener);
    try { listener(this.current); } catch { /* 首次回放同 emit 隔离 */ }
    return (): void => {
      const i: number = this.listeners.indexOf(listener);
      if (i >= 0) this.listeners.splice(i, 1);
    };
  }

  private emit(): void {
    // 逐 listener 隔离:单个订阅者异常不得阻断其余订阅者,也不得把异常
    // 传播回 start/complete/fail 调用路径
    for (const l of [...this.listeners]) {
      try { l(this.current); } catch { /* 订阅者自身异常不阻断广播 */ }
    }
  }

  // withCurrentConversation(:142-148)
  private withCurrentConversation(
    activity: SandboxActivityUiState,
  ): SandboxActivityUiState {
    if (activity.conversationId !== null) return activity;
    return { ...activity, conversationId: this.conversationScope };
  }

  // start(:16-18)
  start(activity: SandboxActivityUiState): void {
    this.current = this.withCurrentConversation(activity);
    this.emit();
  }

  // withConversation(:20-27):blank → 直接执行;否则 scope 内执行(finally 恢复)
  async withConversation<T>(
    conversationId: string | null,
    block: () => Promise<T>,
  ): Promise<T> {
    if (conversationId === null || conversationId.trim().length === 0) {
      return block();
    }
    const prev: string | null = this.conversationScope;
    this.conversationScope = conversationId;
    try {
      return await block();
    } finally {
      this.conversationScope = prev;
    }
  }

  // appendOutput(:29-37)
  appendOutput(toolCallId: string, line: string): void {
    const cur: SandboxActivityUiState | null = this.current;
    if (cur === null || cur.toolCallId !== toolCallId) return;
    this.current = { ...cur, outputTail: appendTailLine(cur.outputTail, line) };
    this.emit();
  }

  // startTool(:39-64)
  startTool(
    toolName: string,
    title: string,
    inputPreview: string = '',
    runtime: string = '',
    workspace: string = '',
    canCancel: boolean = false,
    conversationId: string | null = null,
  ): string {
    // 同名工具同毫秒并发启动会碰撞 → 用唯一 id
    const toolCallId: string = `${toolName}_${newId()}`;
    this.start(makeSandboxActivityUiState({
      toolCallId,
      toolName,
      title,
      status: 'running',
      inputPreview: inputPreview.slice(0, ACTIVITY_MAX_INPUT_PREVIEW_CHARS),
      runtime,
      workspace,
      startedAtEpochMillis: Date.now(),
      canCancel,
      conversationId: conversationId ?? this.conversationScope,
    }));
    return toolCallId;
  }

  // complete(exitCode)(:66-79)
  completeWithExitCode(toolCallId: string, exitCode: number, output: string): void {
    const cur: SandboxActivityUiState | null = this.current;
    if (cur === null || cur.toolCallId !== toolCallId) return;
    this.current = {
      ...cur,
      status: exitCode === 0 ? 'succeeded' : 'failed',
      outputTail: output.trim().slice(-ACTIVITY_MAX_OUTPUT_TAIL_CHARS),
      endedAtEpochMillis: Date.now(),
      canCancel: false,
    };
    this.emit();
  }

  // complete(output)(:81-94)
  complete(toolCallId: string, output: string = ''): void {
    const cur: SandboxActivityUiState | null = this.current;
    if (cur === null || cur.toolCallId !== toolCallId) return;
    this.current = {
      ...cur,
      status: 'succeeded',
      outputTail: output.trim().slice(-ACTIVITY_MAX_OUTPUT_TAIL_CHARS),
      endedAtEpochMillis: Date.now(),
      canCancel: false,
    };
    this.emit();
  }

  // fail(:96-110)— Log.e → log 回调可选(默认 noop,不打印敏感内容)
  fail(toolCallId: string, error: Error): void {
    const cur: SandboxActivityUiState | null = this.current;
    if (cur === null || cur.toolCallId !== toolCallId) return;
    this.current = {
      ...cur,
      status: 'failed',
      outputTail: toAgentToolFailureJson(error).slice(-ACTIVITY_MAX_OUTPUT_TAIL_CHARS),
      endedAtEpochMillis: Date.now(),
      canCancel: false,
    };
    this.emit();
  }

  // cancel(:112-125)
  cancel(toolCallId: string, output: string = ''): void {
    const cur: SandboxActivityUiState | null = this.current;
    if (cur === null || cur.toolCallId !== toolCallId) return;
    this.current = {
      ...cur,
      status: 'cancelled',
      outputTail: output.trim().slice(-ACTIVITY_MAX_OUTPUT_TAIL_CHARS),
      endedAtEpochMillis: Date.now(),
      canCancel: false,
    };
    this.emit();
  }

  // clear(:127-131)
  clear(toolCallId: string): void {
    const cur: SandboxActivityUiState | null = this.current;
    if (cur === null || cur.toolCallId !== toolCallId) return;
    this.current = null;
    this.emit();
  }
}

// ===== 时间线纯函数(ChatPage.kt:1166-1470) =====

export const MAX_SANDBOX_TIMELINE_ITEMS: number = 24;
export const MAX_SANDBOX_OUTPUT_TAIL_CHARS: number = 1600;
export const MAX_SANDBOX_JSON_PARSE_CHARS: number = 80000;

// --- kotlinx JsonPrimitive 语义 helper(登记偏差见头注) ---

// getStringContent(:1452-1453):primitive content(字符串/数字/布尔 → 字符串)
const getStringContent = (obj: JsonObject, key: string): string | null => {
  const v: unknown = obj[key];
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

// getFirstStringContent(:1455-1461):首个非空 blank 跳过
const getFirstStringContent = (obj: JsonObject, keys: string[]): string | null => {
  for (const key of keys) {
    const v: string | null = getStringContent(obj, key);
    if (v !== null && v.length > 0 && v.trim().length > 0) return v;
  }
  return null;
};

// jsonPrimitive intOrNull:整数 number / 整数字符串;否则 null
const jsonIntOrNull = (v: unknown): number | null => {
  if (typeof v === 'number') return Number.isInteger(v) ? v : null;
  if (typeof v === 'string' && /^-?\d+$/.test(v)) return parseInt(v, 10);
  return null;
};

// toBooleanStrictOrNull:仅 'true'/'false' 字面或布尔
const jsonBoolStrictOrNull = (v: unknown): boolean | null => {
  if (typeof v === 'boolean') return v;
  if (v === 'true') return true;
  if (v === 'false') return false;
  return null;
};

// compactSandboxText(:1465-1468)
export const compactSandboxText = (text: string, maxLength: number): string => {
  const compact: string = text.trim().replace(/\s+/g, ' ');
  return compact.length > maxLength ? `${compact.slice(0, maxLength - 1)}…` : compact;
};

// currentRunMessages(:1270-1273):末条 USER 之后(indexOfLast+1 coerceAtLeast 0)
export const currentRunMessages = (conv: Conversation): UIMessage[] => {
  const messages: UIMessage[] = currentMessages(conv);
  let lastUser: number = -1;
  for (let i: number = messages.length - 1; i >= 0; i--) {
    if (messages[i].role === 'user') {
      lastUser = i;
      break;
    }
  }
  return messages.slice(Math.max(lastUser + 1, 0));
};

// isSandboxActivityTool(:1275-1318 名表逐字)
const SANDBOX_TOOL_NAMES: string[] = [
  'search_web',
  'scrape_web',
  'webview_search_open',
  'webview_open',
  'webview_wait_for_load',
  'webview_read',
  'icloud_status',
  'icloud_list',
  'icloud_read',
  'icloud_write',
  'icloud_search',
  'file_list',
  'file_read',
  'file_write',
  'file_edit',
  'file_search',
  'file_move',
  'terminal_execute',
  'terminal_session_start',
  'terminal_session_exec',
  'terminal_session_read',
  'terminal_session_stop',
  'terminal_mosh_session_start',
  'terminal_mosh_session_exec',
  'terminal_mosh_session_read',
  'terminal_mosh_session_stop',
  'python_execute',
  'screen_click',
  'screen_long_click',
  'screen_swipe',
  'screen_input_text',
  'screen_back',
  'screen_home',
  'screen_open_app',
  'screen_read_ui',
  'screen_screenshot',
  'vlm_task',
];

export const isSandboxActivityTool = (toolName: string): boolean =>
  toolName.startsWith('mcp__') || SANDBOX_TOOL_NAMES.indexOf(toolName) >= 0;

// sandboxActivityTools(:1264-1268)
export const sandboxActivityTools = (conv: Conversation): UIMessagePartTool[] => {
  const out: UIMessagePartTool[] = [];
  for (const message of currentRunMessages(conv)) {
    for (const part of message.parts) {
      if (part.type === 'tool' && isSandboxActivityTool(part.toolName)) {
        out.push(part);
      }
    }
  }
  return out;
};

// outputText(:1434-1450):text parts join;限量时从尾部按块截取
export const toolOutputText = (
  tool: UIMessagePartTool,
  maxChars: number = Number.MAX_SAFE_INTEGER,
): string => {
  const textParts: UIMessagePartText[] = tool.output.filter(
    (p: UIMessagePart): p is UIMessagePartText => p.type === 'text');
  if (maxChars === Number.MAX_SAFE_INTEGER) {
    return textParts.map((p: UIMessagePartText): string => p.text).join('\n');
  }
  let remaining: number = maxChars;
  const chunks: string[] = [];
  for (let i: number = textParts.length - 1; i >= 0; i--) {
    if (remaining <= 0) break;
    const text: string = textParts[i].text;
    const chunk: string = text.length > remaining ? text.slice(-remaining) : text;
    chunks.unshift(chunk);
    remaining -= chunk.length;
  }
  return chunks.join('\n');
};

// outputJson(:1426-1432):blank/超长/解析失败/非 object → {}
export const toolOutputJson = (tool: UIMessagePartTool): JsonObject => {
  const limit: number = tool.toolName === 'python_execute' ? 128 * 1024 * 6 + 2048 : MAX_SANDBOX_JSON_PARSE_CHARS;
  const output: string = toolOutputText(tool, limit + 1);
  if (output.trim().length === 0) return {};
  if (output.length > limit) return {};
  try {
    const parsed: unknown = JSON.parse(output);
    if (parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed)) {
      return parsed as JsonObject;
    }
    return {};
  } catch (_e) {
    return {};
  }
};

// activityStatus(:1290-1318 后段)— !isExecuted 两分支均 RUNNING(原文如此)
export const toolActivityStatus = (
  tool: UIMessagePartTool,
  loading: boolean,
  outputJson: JsonObject,
): ToolActivityStatus => {
  if (tool.approvalState.type === 'pending') return 'waiting_for_permission';
  if (tool.approvalState.type === 'denied') return 'cancelled';
  if (!isToolExecuted(tool) && loading) return 'running';
  if (!isToolExecuted(tool)) return 'running';
  if (tool.toolName === 'recipe_import' || tool.toolName.startsWith('recipe__')
    || tool.toolName === 'plugin_test' || tool.toolName === 'plugin_import' || tool.toolName.startsWith('plugin__')) {
    const status: string | null = getStringContent(outputJson, 'status');
    if (status === 'cancelled') return 'cancelled';
    if (status === 'outcome_unknown') return 'failed';
    if (status === 'test_failed') return 'failed';
  }
  if (tool.toolName === 'python_execute') {
    const status: string | null = getStringContent(outputJson, 'status');
    if (status === 'cancelled' || status === 'interrupted') return 'cancelled';
    if (status === 'timed_out') return 'failed';
  }
  if (indicatesFailure(outputJson)) return 'failed';
  return 'succeeded';
};

// indicatesFailure(:1409-1417)
export const indicatesFailure = (outputJson: JsonObject): boolean => {
  const exitCode: number | null = jsonIntOrNull(outputJson['exit_code']);
  const error: string | null = getStringContent(outputJson, 'error');
  const statusRaw: string | null = getStringContent(outputJson, 'status');
  const status: string | null = statusRaw !== null ? statusRaw.toLowerCase() : null;
  const failed: boolean = jsonBoolStrictOrNull(outputJson['failed']) === true;
  return (error !== null && error.trim().length > 0) ||
    (exitCode !== null && exitCode !== 0) ||
    failed ||
    (status !== null &&
      (status === 'failed' || status === 'error' || status === 'denied'));
};

// outputTail(:1419-1424)
export const toolActivityOutputTail = (
  tool: UIMessagePartTool,
  outputJson: JsonObject,
): string => {
  if (tool.toolName === 'python_execute') {
    const stdout: string = getStringContent(outputJson, 'stdout') ?? '';
    const stderr: string = getStringContent(outputJson, 'stderr') ?? '';
    return terminalTail((stdout + (stdout && stderr ? '\n' : '') + stderr).trim(), MAX_SANDBOX_OUTPUT_TAIL_CHARS);
  }
  const output: string = getStringContent(outputJson, 'output') ??
    getStringContent(outputJson, 'error') ??
    toolOutputText(tool, MAX_SANDBOX_OUTPUT_TAIL_CHARS);
  return output.trim().slice(-MAX_SANDBOX_OUTPUT_TAIL_CHARS);
};

// sandboxTitle(:1321-1361 文案逐字)
export const toolSandboxTitle = (tool: UIMessagePartTool, input?: JsonObject): string => {
  const inp: JsonObject = input ?? toolInputAsJson(tool);
  const name: string = tool.toolName;
  if (name === 'search_web') {
    return `网页搜索 ${compactSandboxText(getFirstStringContent(inp, ['query', 'q', 'keyword', 'keywords']) ?? '', 20)}`;
  }
  if (name === 'scrape_web') {
    return `打开网页 ${compactSandboxText(getFirstStringContent(inp, ['url', 'link', 'uri']) ?? '', 24)}`;
  }
  if (name === 'webview_search_open') {
    return `打开搜索页 ${compactSandboxText(getStringContent(inp, 'query') ?? '', 20)}`;
  }
  if (name === 'webview_open') {
    return `打开网页 ${compactSandboxText(getStringContent(inp, 'url') ?? '', 24)}`;
  }
  if (name === 'webview_wait_for_load') return '等待网页加载';
  if (name === 'webview_read') return '读取网页内容';
  if (name === 'icloud_status') return '检查 iCloud 挂载';
  if (name === 'icloud_list') {
    return `列出 iCloud ${compactSandboxText(getStringContent(inp, 'path') ?? '', 18)}`;
  }
  if (name === 'icloud_read') {
    return `读取 iCloud ${compactSandboxText(getStringContent(inp, 'path') ?? '', 20)}`;
  }
  if (name === 'icloud_write') {
    return `写入 iCloud ${compactSandboxText(getStringContent(inp, 'path') ?? '', 20)}`;
  }
  if (name === 'icloud_search') {
    return `搜索 iCloud ${compactSandboxText(getStringContent(inp, 'query') ?? '', 20)}`;
  }
  if (name === 'file_list') {
    return `列出 workspace ${compactSandboxText(getStringContent(inp, 'path') ?? '', 18)}`;
  }
  if (name === 'file_read') {
    return `读取文件 ${compactSandboxText(getStringContent(inp, 'path') ?? '', 20)}`;
  }
  if (name === 'file_write') {
    return `写入文件 ${compactSandboxText(getStringContent(inp, 'path') ?? '', 20)}`;
  }
  if (name === 'file_edit') {
    return `编辑文件 ${compactSandboxText(getStringContent(inp, 'path') ?? '', 20)}`;
  }
  if (name === 'file_search') {
    return `搜索文件 ${compactSandboxText(getStringContent(inp, 'query') ?? '', 20)}`;
  }
  if (name === 'file_move') {
    return `移动文件 ${compactSandboxText(getStringContent(inp, 'from') ?? '', 16)}`;
  }
  if (name === 'terminal_execute') return '执行 SSH 命令';
  if (name === 'python_execute') return '执行本机 Python';
  if (name === 'terminal_mosh_session_start') return '启动 Mosh 会话';
  if (name === 'terminal_mosh_session_exec') return 'Mosh 会话执行';
  if (name === 'terminal_mosh_session_read') return '读取 Mosh 输出';
  if (name === 'terminal_mosh_session_stop') return '停止 Mosh 会话';
  if (name === 'terminal_job_start') return '启动 SSH 任务';
  if (name === 'terminal_job_read') return '读取 SSH 任务';
  if (name === 'terminal_job_wait') return '等待 SSH 任务';
  if (name === 'terminal_job_stop') return '停止 SSH 任务';
  if (name === 'terminal_session_start') return '启动终端会话';
  if (name === 'terminal_session_exec') return '终端会话执行';
  if (name === 'terminal_session_read') return '读取终端输出';
  if (name === 'terminal_session_stop') return '停止终端会话';
  if (name === 'screen_click') return '点击屏幕';
  if (name === 'screen_long_click') return '长按屏幕';
  if (name === 'screen_swipe') return '滑动屏幕';
  if (name === 'screen_input_text') return '输入文字';
  if (name === 'screen_back') return '返回';
  if (name === 'screen_home') return '回到桌面';
  if (name === 'screen_open_app') {
    return `打开应用 ${compactSandboxText(getStringContent(inp, 'package') ?? '', 18)}`;
  }
  if (name === 'screen_read_ui') return '读取当前 UI';
  if (name === 'screen_screenshot') return '获取屏幕截图';
  if (name === 'vlm_task') return '执行 VLM 手机任务';
  if (name.startsWith('mcp__')) {
    return `调用 MCP ${compactSandboxText(name.slice('mcp__'.length), 24)}`;
  }
  return name;
};

// inputPreview(:1363-1383):键取不到 → input.toString() 全串 compact 180
export const toolSandboxInputPreview = (
  tool: UIMessagePartTool,
  input?: JsonObject,
): string => {
  const inp: JsonObject = input ?? toolInputAsJson(tool);
  const name: string = tool.toolName;
  let picked: string | null = null;
  // Provider names, identities and URLs can also contain supplied credentials.
  if (name === 'provider_list') return '查看已配置的提供商';
  if (name === 'provider_configure') return '保存提供商和模型配置';
  if (name === 'provider_models') return '获取提供商模型列表';
  if (name === 'search_web') {
    picked = getFirstStringContent(inp, ['query', 'q', 'keyword', 'keywords']);
  } else if (name === 'scrape_web') {
    picked = getFirstStringContent(inp, ['url', 'link', 'uri']);
  } else if (name === 'webview_search_open') {
    picked = getStringContent(inp, 'query');
  } else if (name === 'webview_open') {
    picked = getStringContent(inp, 'url');
  } else if (name === 'webview_wait_for_load') {
    picked = getStringContent(inp, 'target_url');
  } else if (name === 'webview_read') {
    picked = getStringContent(inp, 'url');
  } else if (name === 'icloud_list' || name === 'icloud_read' || name === 'icloud_write') {
    picked = getStringContent(inp, 'path');
  } else if (name === 'icloud_search') {
    picked = getStringContent(inp, 'query');
  } else if (name === 'python_execute') {
    picked = getStringContent(inp, 'code');
  } else if (name === 'terminal_execute' || name === 'terminal_job_start' || name === 'terminal_session_exec'
    || name === 'terminal_mosh_session_exec') {
    picked = getStringContent(inp, 'command');
  } else if (name === 'file_list' || name === 'file_read' ||
    name === 'file_write' || name === 'file_edit') {
    picked = getStringContent(inp, 'path');
  } else if (name === 'file_search') {
    picked = getStringContent(inp, 'query');
  } else if (name === 'file_move') {
    picked = getStringContent(inp, 'from');
  } else if (name === 'screen_open_app') {
    picked = getStringContent(inp, 'package');
  } else if (name === 'screen_input_text') {
    picked = getStringContent(inp, 'text');
  } else if (name === 'vlm_task') {
    picked = getStringContent(inp, 'goal');
  }
  if (picked !== null) return compactSandboxText(picked, 180);
  return compactSandboxText(JSON.stringify(inp), 180);
};

// defaultRuntime(:1385-1401)
export const toolDefaultRuntime = (toolName: string): string => {
  if (toolName === 'python_execute') return 'embedded_python';
  if (toolName.startsWith('terminal_mosh_session_')) return 'remote_mosh';
  if (toolName === 'search_web') return 'web-search';
  if (toolName === 'scrape_web') return 'webview';
  if (toolName === 'webview_search_open') return 'webview';
  if (toolName === 'webview_open') return 'webview';
  if (toolName === 'webview_wait_for_load') return 'webview';
  if (toolName === 'webview_read') return 'webview';
  if (toolName.startsWith('icloud_')) return 'icloud-web-mount';
  if (toolName === 'terminal_execute' || toolName.startsWith('terminal_job_') ||
    toolName.startsWith('terminal_session_')) return 'remote_ssh';
  if (toolName === 'terminal_install_packages') return 'alpine-proot-stage1';
  if (toolName.startsWith('file_')) return 'saf-workspace';
  if (toolName.startsWith('screen_') || toolName === 'vlm_task') {
    return 'accessibility-service';
  }
  if (toolName.startsWith('mcp__')) return 'mcp';
  return '';
};

// defaultWorkspace(:1403-1407)
export const toolDefaultWorkspace = (toolName: string): string => {
  if (toolName.startsWith('file_')) {
    return '/workspace';
  }
  if (toolName.startsWith('icloud_')) return '/icloud';
  return '';
};

// deriveSandboxActivities(:1192-1236)
export const deriveSandboxActivities = (
  conv: Conversation,
  loading: boolean,
  processingStatus: string | null,
): SandboxActivityUiState[] => {
  const sandboxTools: UIMessagePartTool[] =
    sandboxActivityTools(conv).slice(-MAX_SANDBOX_TIMELINE_ITEMS);
  if (sandboxTools.length === 0) {
    if (loading && processingStatus !== null && processingStatus.trim().length > 0) {
      return [makeSandboxActivityUiState({
        toolCallId: 'processing-status',
        toolName: 'agent_processing',
        title: processingStatus,
        status: 'running',
        conversationId: conv.id,
        runtime: 'agent-run',
        canCancel: true,
        stepIndex: 1,
        stepTotal: 1,
      })];
    }
    return [];
  }
  return sandboxTools.map((tool: UIMessagePartTool, index: number): SandboxActivityUiState => {
    const outputJson: JsonObject = toolOutputJson(tool);
    const input: JsonObject = toolInputAsJson(tool);
    const status: ToolActivityStatus = toolActivityStatus(tool, loading, outputJson);
    return makeSandboxActivityUiState({
      toolCallId: tool.toolCallId,
      toolName: tool.toolName,
      title: toolSandboxTitle(tool, input),
      status,
      conversationId: conv.id,
      inputPreview: toolSandboxInputPreview(tool, input),
      outputTail: toolActivityOutputTail(tool, outputJson),
      runtime: getStringContent(outputJson, 'runtime') ?? toolDefaultRuntime(tool.toolName),
      workspace: getStringContent(outputJson, 'workspace') ?? toolDefaultWorkspace(tool.toolName),
      stepIndex: index + 1,
      stepTotal: sandboxTools.length,
      canCancel: loading &&
        (status === 'running' || status === 'waiting_for_permission'),
    });
  });
};

// mergeSandboxTimeline(:1171-1189)
export const mergeSandboxTimeline = (
  messageActivities: SandboxActivityUiState[],
  liveActivity: SandboxActivityUiState | null,
): SandboxActivityUiState[] => {
  let merged: SandboxActivityUiState[];
  if (liveActivity === null) {
    merged = messageActivities;
  } else {
    const replaced: SandboxActivityUiState[] = messageActivities.map(
      (activity: SandboxActivityUiState): SandboxActivityUiState =>
        activity.toolCallId === liveActivity.toolCallId ? liveActivity : activity);
    const found: boolean = replaced.some(
      (a: SandboxActivityUiState): boolean => a.toolCallId === liveActivity.toolCallId);
    merged = found ? replaced : [...replaced, liveActivity];
  }
  return merged.map(
    (activity: SandboxActivityUiState, index: number): SandboxActivityUiState => ({
      ...activity,
      stepIndex: index + 1,
      stepTotal: merged.length,
    }));
};

// idleSandboxActivity(:1239-1250 文案逐字;ALWAYS 模式空时间线兜底)
export const idleSandboxActivity = (conv: Conversation): SandboxActivityUiState =>
  makeSandboxActivityUiState({
    toolCallId: `agent-idle-${conv.id}`,
    toolName: 'agent_idle',
    title: 'Agent 操作预览',
    status: 'running',
    conversationId: conv.id,
    inputPreview: '等待下一次工具调用',
    runtime: 'standby',
    stepIndex: null,
    stepTotal: null,
  });

// withStepProgress(:1252-1262)
export const withStepProgress = (
  activity: SandboxActivityUiState,
  conv: Conversation,
): SandboxActivityUiState => {
  const sandboxTools: UIMessagePartTool[] =
    sandboxActivityTools(conv).slice(-MAX_SANDBOX_TIMELINE_ITEMS);
  const matchedIndex: number = sandboxTools.findIndex(
    (t: UIMessagePartTool): boolean => t.toolCallId === activity.toolCallId);
  const inferredStep: number = matchedIndex >= 0 ? matchedIndex + 1 : sandboxTools.length + 1;
  return {
    ...activity,
    stepIndex: activity.stepIndex ?? (inferredStep > 0 ? inferredStep : null),
    stepTotal: activity.stepTotal ?? (inferredStep > 0 ? inferredStep : null),
  };
};

// isActiveOperation(:1462-1463)
export const isActiveOperation = (activity: SandboxActivityUiState): boolean =>
  activity.status === 'running' || activity.status === 'waiting_for_permission';
