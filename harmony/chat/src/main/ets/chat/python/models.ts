import type { AgentTaskStatus } from '../agent_task.ts';

export const PYTHON_MAX_SOURCE_BYTES: number = 256 * 1024;
export const PYTHON_MAX_STDIN_BYTES: number = 64 * 1024;
export const PYTHON_DEFAULT_TIMEOUT_MS: number = 15000;
export const PYTHON_MAX_TIMEOUT_MS: number = 60000;

export type PythonNativeStatus = 'completed' | 'failed' | 'cancelled' | 'timed_out';
export interface PythonExecuteOptions { source: string; stdin: string; timeoutMs: number; }
export interface PythonNativeResult {
  status: PythonNativeStatus;
  exitCode: number | null;
  stdout: string;
  stderr: string;
  errorCode: string | null;
}
export interface PythonExecuteRequest extends PythonExecuteOptions {
  sourceToolName: string | null;
  sourceConversationId: string | null;
}
export interface PythonSnapshot {
  runId: string;
  runtime: 'embedded_python';
  status: AgentTaskStatus;
  exitCode: number | null;
  stdout: string;
  stderr: string;
  errorCode: string | null;
}
