// tool_input_redaction — 持久化前的工具输入脱敏(写入边界的最小实现)
//
// 为什么需要单独一层:
//   maskSensitiveJsonObject(conversation_export)覆盖 api_key/token/password 等通用键,
//   但 SSH 配置工具用的是 password / private_key / private_key_passphrase 这类
//   按工具约定命名的字段,私钥与口令只有本层知道。
//
// 边界:
//   - 生成捕获、持久化和错误回显共用本层。真实凭据只由 tool_input_vault 暂存,
//     执行时取回;内存会话与持久化快照都只保留安全输入。
//   - 只做「按工具名 + 字段名」的定点替换;未登记的工具原样返回,不重构既有脱敏。
//   - 敏感工具的非 JSON / 非对象输入只保留不可回放标记。
//   - restoreStoredToolInput 是反向判定:参数被脱敏过的调用**不可**从存储回放执行
//     (占位符绝不能当凭据写入),调用方必须 fail-closed。

import type { JsonObject, JsonValue } from './json.ts';
import {
  redactSSHProfileToolInput, SSH_PROFILE_SECRET_FIELDS, SSH_PROFILE_TOOL_NAMES,
} from './terminal/profile_tools.ts';
import { SSH_KEY_TOOL_NAMES } from './terminal/ssh_tool_contract.ts';

export const STORED_TOOL_INPUT_REDACTED: string = '***';
const UNAVAILABLE_INPUT_FIELD: string = '_amber_secret_input_unavailable';
const UNAVAILABLE_INPUT: string = '{"_amber_secret_input_unavailable":true}';

// 各种脱敏占位符形态(大小写不敏感):*** / <redacted> / [REDACTED] / REDACTED / ••••
export const SECRET_PLACEHOLDERS: string[] = ['***', '<redacted>', '[redacted]', 'redacted', '(empty)', '\u2022\u2022\u2022\u2022'];

/** 某个值是否是脱敏占位符(占位符绝不能当凭据使用) */
export const isRedactedPlaceholder = (value: string | null | undefined): boolean =>
  typeof value === 'string' && SECRET_PLACEHOLDERS.indexOf(value.trim().toLowerCase()) >= 0;

/** 该工具的输入里是否带着占位符凭据(说明真实值只存在于进程内暂存) */
export const toolInputHasPlaceholder = (toolName: string, input: JsonObject): boolean => {
  if (!toolInputNeedsStoredRedaction(toolName)) return false;
  if (input[UNAVAILABLE_INPUT_FIELD] === true) return true;
  for (const field of SSH_PROFILE_SECRET_FIELDS) {
    if (isRedactedPlaceholder(typeof input[field] === 'string' ? input[field] as string : null)) return true;
  }
  return false;
};

/** 该工具名的输入是否需要在持久化前脱敏 */
export const toolInputNeedsStoredRedaction = (toolName: string): boolean =>
  SSH_PROFILE_TOOL_NAMES.indexOf(toolName) >= 0 || SSH_KEY_TOOL_NAMES.indexOf(toolName) >= 0;

const redactByName = (toolName: string, input: JsonObject): JsonObject =>
  toolInputNeedsStoredRedaction(toolName) ? redactSSHProfileToolInput(toolName, input) : input;

/**
 * 返回可直接持久化的工具输入文本。
 * 脱敏后的输入仍是合法 JSON(占位为字符串),保证回读不会破坏会话结构。
 */
export const redactStoredToolInput = (toolName: string, inputText: string): string => {
  if (!toolInputNeedsStoredRedaction(toolName)) return inputText;
  try {
    const parsed: JsonValue = JSON.parse(inputText.trim().length === 0 ? '{}' : inputText) as JsonValue;
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) return UNAVAILABLE_INPUT;
    return JSON.stringify(redactByName(toolName, parsed as JsonObject));
  } catch {
    // 流式中断也可能已有完整凭据字段,不能把原文交给存储或错误回显。
    return UNAVAILABLE_INPUT;
  }
};

const storedInputCarriesSecret = (toolName: string, input: JsonObject): boolean => {
  if (!toolInputNeedsStoredRedaction(toolName)) return false;
  if (toolInputHasPlaceholder(toolName, input)) return true;
  for (const field of SSH_PROFILE_SECRET_FIELDS) {
    const value: JsonValue | undefined = input[field];
    if (typeof value === 'string' && value.length > 0) return true;
  }
  return false;
};

export interface StoredToolInputRestore {
  /** ok = 可以安全执行;unavailable = 参数曾被脱敏,必须让用户重新提供 */
  status: 'ok' | 'unavailable';
  input: JsonObject;
}

/**
 * 存储输入 → 可执行输入的恢复判定。
 *
 * 脱敏后的参数已经不含真凭据,批准后从存储重新读出时(model resume)绝不能把占位符
 * 当成新密码/私钥写入。这里只覆盖「需要脱敏」的工具:凭据字段是占位符 → unavailable,
 * 没有凭据字段(如只改名/改端口)→ 照常返回。
 */
export const restoreStoredToolInput = (
  toolName: string, inputText: string,
): StoredToolInputRestore => {
  let parsed: JsonValue = {};
  try {
    parsed = JSON.parse(inputText.trim().length === 0 ? '{}' : inputText) as JsonValue;
  } catch {
    return { status: 'unavailable', input: {} };
  }
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { status: 'unavailable', input: {} };
  }
  const input: JsonObject = parsed as JsonObject;
  if (!toolInputNeedsStoredRedaction(toolName)) return { status: 'ok', input };
  // 占位符本身也是「有值」:只要凭据字段非空,就必须视为不可回放。
  return { status: storedInputCarriesSecret(toolName, input) ? 'unavailable' : 'ok', input };
};
