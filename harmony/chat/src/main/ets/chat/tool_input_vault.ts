// tool_input_vault — 进程内凭据暂存(SSH 配置工具的「只写」凭据通道)
//
// 要解决的问题:
//   凭据绝不能进 RDB / 导出 / 日志 / 发给模型的请求,但工具执行必须拿到**真实**凭据。
//   如果只在持久化层脱敏,内存里的 input 仍带明文,而且批准后从 RDB 重读拿到的是
//   `***`——直接执行就会把占位符写成凭据。
//
// 做法:
//   1) 工具调用生成时:captureSecretToolInput 把真实参数按 toolCallId 放进本表,
//      并返回脱敏后的 input 文本。内存 / RDB / 屏障看到的是同一份 `***`。
//   2) 执行时:restoreSecretToolInput 按 toolCallId 取出真实参数,取出即删。
//      暂存丢失(冷启动 / 进程重启 / 已被消费)→ fail-closed,返回明确错误码,
//      让模型重新发起调用。
//   3) 拒绝、取消、会话删除:clearSecretToolInput 擦除该 toolCallId(幂等)。
//
// 边界:
//   - 只在需要脱敏的工具(SSH 配置写操作)上生效;其它工具 cput/cget 直接透传。
//   - 纯内存,不落盘;表容量有上限,避免长时间会话无限增长(超限丢最旧的)。

import type { JsonObject, JsonValue } from './json.ts';
import { redactStoredToolInput, toolInputHasPlaceholder, toolInputNeedsStoredRedaction } from './tool_input_redaction.ts';

/** 一次工具调用允许暂存的凭据条数上限(超出丢弃最旧条目) */
export const SECRET_TOOL_INPUT_MAX_ENTRIES: number = 64;

interface SecretEntry {
  input: JsonObject;
  sequence: number;
}

let entries: Map<string, SecretEntry> = new Map();
let sequence: number = 0;

const evictOldest = (): void => {
  while (entries.size > SECRET_TOOL_INPUT_MAX_ENTRIES) {
    let oldestKey: string | null = null;
    let oldestSequence: number = Number.MAX_SAFE_INTEGER;
    entries.forEach((entry: SecretEntry, key: string): void => {
      if (entry.sequence < oldestSequence) { oldestSequence = entry.sequence; oldestKey = key; }
    });
    if (oldestKey === null) return;
    entries.delete(oldestKey);
  }
};

/**
 * 生成期登记:把真实参数放进暂存表,返回脱敏后的输入文本。
 * 非凭据工具(或解析不出对象)原样返回、不登记。
 */
export const captureSecretToolInput = (
  toolName: string, toolCallId: string, inputText: string,
): { input: string; captured: boolean } => {
  if (!toolInputNeedsStoredRedaction(toolName) || toolCallId.length === 0) {
    return { input: inputText, captured: false };
  }
  const redacted: string = redactStoredToolInput(toolName, inputText);
  if (redacted === inputText) return { input: inputText, captured: false };
  let parsed: JsonValue = null;
  try {
    parsed = JSON.parse(inputText) as JsonValue;
  } catch {
    return { input: inputText, captured: false };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { input: inputText, captured: false };
  }
  entries.set(toolCallId, { input: parsed as JsonObject, sequence: ++sequence });
  evictOldest();
  return { input: redacted, captured: true };
};

export interface SecretToolInputRestore {
  ok: boolean;
  /** ok=false 时的错误码:secret_input_unavailable */
  code: string;
  args: JsonValue;
}

/**
 * 执行期取用:命中则返回真实参数并立刻删除;未命中返回 fail-closed 结果。
 * 输入本身不含凭据字段(如只改名)时无需暂存,直接透传解析结果。
 */
export const restoreSecretToolInput = (
  toolName: string, toolCallId: string, parsedArgs: JsonValue,
): SecretToolInputRestore => {
  if (!toolInputNeedsStoredRedaction(toolName)) return { ok: true, code: '', args: parsedArgs };
  const entry: SecretEntry | undefined = toolCallId.length > 0 ? entries.get(toolCallId) : undefined;
  if (entry !== undefined) {
    entries.delete(toolCallId);
    return { ok: true, code: '', args: entry.input };
  }
  // 暂存缺失:参数里带着占位符就必须失败(占位符绝不能被执行)。
  if (parsedArgs !== null && typeof parsedArgs === 'object' && !Array.isArray(parsedArgs)
    && toolInputHasPlaceholder(toolName, parsedArgs as JsonObject)) {
    return { ok: false, code: 'secret_input_unavailable', args: parsedArgs };
  }
  return { ok: true, code: '', args: parsedArgs };
};

/** 拒绝 / 取消 / 打断 / 删会话时擦除(幂等) */
export const clearSecretToolInput = (toolCallId: string): void => {
  if (toolCallId.length > 0) entries.delete(toolCallId);
};

/** 批量擦除(会话删除、账号切换等粗粒度清理) */
export const clearAllSecretToolInputs = (): void => { entries.clear(); };

/**
 * 按会话清理:会话被删除 / 被清空时,把里面所有 pending 工具调用的暂存擦掉。
 * 会话结构由调用方给出(entry 层拿到 Conversation),这里只读 toolCallId。
 */
export const clearSecretsForConversation = (
  messages: Array<{ parts: Array<{ type: string; toolCallId?: string }> }>,
): void => {
  for (const message of messages) {
    for (const part of message.parts) {
      if (part.type === 'tool' && typeof part.toolCallId === 'string') clearSecretToolInput(part.toolCallId);
    }
  }
};

/** 仅供测试与诊断:当前暂存条数 */
export const secretToolInputCount = (): number => entries.size;
