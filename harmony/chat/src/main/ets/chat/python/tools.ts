import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTool } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePart } from '../message.ts';
import type { AgentToolActivityStore } from '../tool_activity.ts';
import { ACTIVITY_MAX_OUTPUT_TAIL_CHARS } from '../tool_activity.ts';
import { terminalTail } from '../terminal/utf8.ts';
import { PythonError } from './control.ts';
import { PYTHON_DEFAULT_TIMEOUT_MS, PYTHON_MAX_TIMEOUT_MS } from './models.ts';
import type { PythonSnapshot } from './models.ts';
import type { PythonRuntime } from './runtime.ts';

export interface PythonToolDeps { runtime: PythonRuntime; activityStore: AgentToolActivityStore; conversationId: string; }
const description: string = 'Execute Python locally in the embedded CPython runtime. Supports text stdin and data processing ' +
  '(json, math and available standard modules). Files are passed through Workspace read/write tools. ' +
  'Direct files, networking, subprocesses, pip and third-party native extensions are unavailable. ' +
  'Cancellation is cooperative for Python bytecode; this is not a system security sandbox.';
const textParts = (value: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(value), metadata: null }];
const snapshotJson = (s: PythonSnapshot): JsonObject => ({ runtime: s.runtime, run_id: s.runId,
  status: s.status.toLowerCase(), running: false, exit_code: s.exitCode, stdout: s.stdout, stderr: s.stderr,
  error_code: s.errorCode });
const parseInput = (input: JsonValue): JsonObject => {
  if (input === null || typeof input !== 'object' || Array.isArray(input)) throw new PythonError('invalid_arguments');
  return input;
};
export const createPythonTool = (deps: PythonToolDeps): AgentTool => makeAgentTool({
  name: 'python_execute', description, systemPrompt: (): string => description,
  needsApproval: true, allowsAutoApproval: false,
  parameters: () => makeInputSchemaObj({ code: { type: 'string' }, stdin: { type: 'string' },
    timeout_ms: { type: 'integer', minimum: 1, maximum: PYTHON_MAX_TIMEOUT_MS, default: PYTHON_DEFAULT_TIMEOUT_MS } }, ['code']),
  execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
    let activityId: string | null = null;
    try {
      const j: JsonObject = parseInput(input);
      const source: JsonValue | undefined = j['code'];
      const stdin: JsonValue | undefined = j['stdin'];
      const timeout: JsonValue | undefined = j['timeout_ms'];
      if (typeof source !== 'string' || (stdin !== undefined && typeof stdin !== 'string') ||
        (timeout !== undefined && typeof timeout !== 'number')) throw new PythonError('invalid_arguments');
      activityId = deps.activityStore.startTool('python_execute', 'Python', source, 'embedded_python', '', true, deps.conversationId);
      const result: PythonSnapshot = await deps.runtime.execute({ source, stdin: stdin === undefined ? '' : stdin as string,
        timeoutMs: timeout === undefined ? PYTHON_DEFAULT_TIMEOUT_MS : timeout as number,
        sourceToolName: 'python_execute', sourceConversationId: deps.conversationId }, signal);
      const output: string = result.stdout + (result.stdout && result.stderr ? '\n' : '') + result.stderr;
      const outputTail: string = terminalTail(output.trim(), ACTIVITY_MAX_OUTPUT_TAIL_CHARS);
      if (result.status === 'CANCELLED' || result.status === 'INTERRUPTED') deps.activityStore.cancel(activityId, outputTail);
      else deps.activityStore.completeWithExitCode(activityId, result.status === 'COMPLETED' ? 0 : result.exitCode ?? 1,
        outputTail || (result.errorCode === null ? '' : 'Python: ' + result.errorCode));
      return textParts(snapshotJson(result));
    } catch (error) {
      const code: string = error instanceof PythonError ? error.code : 'python_transport_error';
      if (activityId !== null) deps.activityStore.fail(activityId, new PythonError(code));
      return textParts({ runtime: 'embedded_python', status: 'failed', running: false, exit_code: null,
        stdout: '', stderr: '', error_code: code });
    }
  },
});
