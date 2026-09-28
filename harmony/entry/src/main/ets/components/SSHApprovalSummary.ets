import type { UIMessagePartTool, JsonValue, JsonObject } from '@amber/chat-domain';
import { toolInputAsJson } from '@amber/chat-domain';

export const isSSHApprovalTool = (name: string): boolean => name === 'terminal_execute' ||
  name === 'terminal_job_start' || name === 'terminal_session_start' || name === 'terminal_session_exec';

// This is the persisted invocation label, never a lookup of the current default.
export const sshApprovalTargetLabel = (part: UIMessagePartTool): string => {
  const label: JsonValue | undefined = part.metadata?.['terminal_target_label'];
  return typeof label === 'string' && label.length > 0 ? label : 'SSH 目标不可用';
};

export const sshApprovalCommand = (part: UIMessagePartTool): string => {
  const input: JsonObject = toolInputAsJson(part);
  const command: JsonValue | undefined = input['command'];
  if (typeof command === 'string') return command;
  if (part.toolName === 'terminal_session_start') {
    const cwd: JsonValue | undefined = input['cwd'];
    return typeof cwd === 'string' && cwd.length > 0 ? `启动交互 Shell · 初始目录: ${cwd}` : '启动远程交互 Shell';
  }
  return '命令参数不可用';
};
