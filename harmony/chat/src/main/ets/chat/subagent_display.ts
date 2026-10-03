import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePartTool } from './message.ts';
import { toolInputAsJson } from './message.ts';
import { toolOutputJson } from './tool_activity.ts';
import type { SubAgentTranscriptPort } from './subagent_transcript.ts';
import { readSubAgentDisplayTextFromTranscript } from './subagent_transcript.ts';

const isObject = (value: JsonValue | undefined): value is JsonObject =>
  value !== null && value !== undefined && typeof value === 'object' && !Array.isArray(value);

// 聊天胶囊与运行详情共用身份、任务和状态，正文不承载完整任务。
export const subAgentNameFromTools = (tools: UIMessagePartTool[]): string => {
  for (let index: number = tools.length - 1; index >= 0; index--) {
    const name: JsonValue | undefined = toolOutputJson(tools[index])['subagent_name'];
    if (typeof name === 'string' && name.trim().length > 0) return name.trim();
  }
  for (const tool of tools) {
    if (tool.toolName !== 'subagent_start') continue;
    const input: JsonObject = toolInputAsJson(tool);
    const custom: JsonValue | undefined = input['custom_subagent'];
    const name: JsonValue | undefined = isObject(custom) ? custom['name'] : undefined;
    if (typeof name === 'string' && name.trim().length > 0) return name.trim();
    const id: JsonValue | undefined = input['subagent_id'];
    if (typeof id === 'string' && id.trim().length > 0) return id.trim();
  }
  return 'subagent';
};

export const subAgentObjectiveFromTools = (tools: UIMessagePartTool[]): string => {
  for (const tool of tools) {
    if (tool.toolName !== 'subagent_start') continue;
    const task: JsonValue | undefined = toolInputAsJson(tool)['task'];
    const objective: JsonValue | undefined = isObject(task) ? task['objective'] : undefined;
    if (typeof objective === 'string') return objective;
  }
  for (const tool of tools) {
    const objective: JsonValue | undefined = toolOutputJson(tool)['task_objective'];
    if (typeof objective === 'string') return objective;
  }
  return '';
};

export const subAgentStatusFromTools = (tools: UIMessagePartTool[]): string => {
  for (let index: number = tools.length - 1; index >= 0; index--) {
    const tool: UIMessagePartTool = tools[index];
    if (!['subagent_start', 'subagent_read', 'subagent_wait', 'subagent_cancel'].includes(tool.toolName)) continue;
    const status: JsonValue | undefined = toolOutputJson(tool)['status'];
    if (typeof status === 'string' && status.trim().length > 0) return status.trim();
  }
  return 'running';
};

// iOS shortSubAgentTask 的关键词顺序；胶囊不直接展示长任务原文。
export const subAgentWorkSummary = (tools: UIMessagePartTool[]): string => {
  const objective: string = subAgentObjectiveFromTools(tools).replace(/\\[nr]/g, ' ').trim();
  const raw: string = objective.split(/[。！？!?；;，,\n]/)[0].trim();
  if (raw.length === 0) return '处理任务';
  if (raw.length <= 4 && !/\s/.test(raw)) return raw;
  if (/搜索|检索|查找|search/i.test(raw)) return '搜索资料';
  if (/修复|bug|fix/i.test(raw)) return '修复问题';
  if (/润色|表达|校对|edit|proofread/i.test(raw)) return '润色表达';
  if (/来源|source/i.test(raw)) return '核对来源';
  if (/登录|login/i.test(raw)) return '核对登录';
  if (/页面|网页|浏览|web|browser/i.test(raw)) return '检查网页';
  if (/整理|总结|归纳|organize|summary/i.test(raw)) return '整理内容';
  if (/代码|编程|code|compile/i.test(raw)) return '检查代码';
  return raw.length <= 6 && !/\s/.test(raw) ? raw : '处理任务';
};

export const subAgentStatusLabel = (status: string): string => {
  if (status === 'completed') return '已完成';
  if (status === 'failed') return '执行失败';
  if (status === 'cancelled') return '已取消';
  if (status === 'timed_out') return '超时';
  if (status === 'approval_required') return '等待审批';
  if (status === 'interrupted') return '已中断';
  return '进行中';
};

// 与 Android 的最终文本回退同口径：manager 将 result 编码为 JSON 字符串。
export const subAgentFinalTextFromTools = (runId: string, tools: UIMessagePartTool[]): string => {
  for (let index: number = tools.length - 1; index >= 0; index--) {
    const tool: UIMessagePartTool = tools[index];
    if (!['subagent_start', 'subagent_read', 'subagent_wait', 'subagent_cancel'].includes(tool.toolName)) continue;
    const output: JsonObject = toolOutputJson(tool);
    const input: JsonObject = toolInputAsJson(tool);
    const outputRunId: JsonValue | undefined = output['run_id'];
    const matchingRunId: JsonValue | undefined = typeof outputRunId === 'string' ? outputRunId : input['run_id'];
    if (matchingRunId !== runId) continue;
    let result: JsonValue | undefined = output['result'];
    if (typeof result === 'string') {
      try { result = JSON.parse(result) as JsonValue; } catch { result = null; }
    }
    const nestedSummary: JsonValue | undefined = isObject(result) ? result['summary'] : undefined;
    const summary: JsonValue | undefined = typeof nestedSummary === 'string' && nestedSummary.trim().length > 0
      ? nestedSummary : output['summary'];
    if (typeof summary === 'string' && summary.trim().length > 0) return summary;
    const error: JsonValue | undefined = isObject(result) ? result['error'] : undefined;
    if (typeof error === 'string' && error.trim().length > 0) return error;
  }
  return '';
};

// 历史读取只用当前 run 的固定路径，复用已有 canonical containment 与 256 KiB 尾窗。
export const readSubAgentHistoricalText = async (
  runId: string, tools: UIMessagePartTool[], runRoot: string, files: SubAgentTranscriptPort,
): Promise<string> => {
  const finalText: string = subAgentFinalTextFromTools(runId, tools);
  if (runId.length === 0 || runId.includes('/') || runId.includes('\\')) return finalText;
  const transcriptText: string = await readSubAgentDisplayTextFromTranscript(
    `${runRoot}/${runId}.jsonl`, runRoot, files, true);
  return transcriptText.trim().length > 0 ? transcriptText : finalText;
};
