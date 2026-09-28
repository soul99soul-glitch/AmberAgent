import type { UIMessagePartTool, JsonValue, JsonObject } from '@amber/chat-domain';
import { toolInputAsJson } from '@amber/chat-domain';

const isMoshApprovalTool = (name: string): boolean => name === 'terminal_mosh_session_start' || name === 'terminal_mosh_session_exec';
export const isSSHApprovalTool = (name: string): boolean => name === 'terminal_execute' ||
  name === 'terminal_job_start' || name === 'terminal_session_start' || name === 'terminal_session_exec' || isMoshApprovalTool(name);

// Protocol is frozen with the approval. Legacy SSH calls can omit the field;
// a Mosh call without its own protocol must never be presented as SSH.
export const sshApprovalProtocolLabel = (part: UIMessagePartTool): string => {
  const protocol: JsonValue | undefined = part.metadata?.['terminal_protocol'];
  if (isMoshApprovalTool(part.toolName)) return protocol === 'remote_mosh' ? 'Remote Mosh' : 'Mosh 协议不可用';
  return protocol === 'remote_ssh' || protocol === undefined ? 'Remote SSH' : 'SSH 协议不可用';
};

// This is the persisted invocation label, never a lookup of the current default.
export const sshApprovalTargetLabel = (part: UIMessagePartTool): string => {
  const label: JsonValue | undefined = part.metadata?.['terminal_target_label'];
  return typeof label === 'string' && label.length > 0 ? label : isMoshApprovalTool(part.toolName) ? 'Mosh 目标不可用' : 'SSH 目标不可用';
};

export const sshApprovalCommand = (part: UIMessagePartTool): string => {
  if (part.toolName === 'terminal_mosh_session_start') return '启动远程 Mosh 交互 Shell';
  const input: JsonObject = toolInputAsJson(part);
  const command: JsonValue | undefined = input['command'];
  if (typeof command === 'string') return command;
  if (part.toolName === 'terminal_session_start') {
    const cwd: JsonValue | undefined = input['cwd'];
    return typeof cwd === 'string' && cwd.length > 0 ? `启动交互 Shell · 初始目录: ${cwd}` : '启动远程交互 Shell';
  }
  return '命令参数不可用';
};
