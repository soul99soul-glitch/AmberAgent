import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTool } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePart } from '../message.ts';
import type { AgentToolActivityStore } from '../tool_activity.ts';
import { ACTIVITY_MAX_OUTPUT_TAIL_CHARS } from '../tool_activity.ts';
import { agentTaskStatusRunning } from '../agent_task.ts';
import type { SSHTargetSnapshot, TerminalJobSnapshot, TerminalSessionSnapshot } from './models.ts';
import type { SSHProfileStore } from './profile_store.ts';
import { TerminalError } from './profile_store.ts';
import type { TerminalRuntime } from './runtime.ts';
import { terminalFailure } from './control.ts';
import { terminalTail } from './utf8.ts';

export interface TerminalToolsDeps {
  runtime: TerminalRuntime;
  profiles: SSHProfileStore;
  activityStore: AgentToolActivityStore;
  conversationId: string;
}
const inputObject = (input: JsonValue): JsonObject => {
  if (input === null || typeof input !== 'object' || Array.isArray(input)) throw new TerminalError('invalid_arguments');
  return input;
};
const stringArg = (j: JsonObject, key: string, optional: boolean = false): string | null => {
  const value: JsonValue | undefined = j[key];
  if (optional && (value === undefined || value === null || value === '')) return null;
  if (typeof value !== 'string' || !value.trim()) throw new TerminalError('invalid_arguments', 'Missing ' + key);
  return value;
};
const numberArg = (j: JsonObject, key: string, fallback: number): number => {
  const value: JsonValue | undefined = j[key];
  if (value === undefined || value === null) return fallback;
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new TerminalError('invalid_arguments');
  return value;
};
const textParts = (j: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(j), metadata: null }];
const jobJson = (s: TerminalJobSnapshot): JsonObject => ({ runtime: 'remote_ssh', profile_id: s.profileId,
  job_id: s.jobId, command: s.command, cwd: s.cwd, status: s.status.toLowerCase(), running: agentTaskStatusRunning(s.status),
  exit_code: s.exitCode, output: s.outputTail, stdout: s.stdoutTail, stderr: s.stderrTail,
  output_log_path: s.outputLogPath, error_code: s.errorCode, error: s.errorMessage });
const sessionJson = (s: TerminalSessionSnapshot): JsonObject => ({ runtime: 'remote_ssh', profile_id: s.profileId,
  session_id: s.sessionId, status: s.status.toLowerCase(), running: agentTaskStatusRunning(s.status), exit_code: s.exitCode,
  output: s.outputTail, output_log_path: s.outputLogPath, columns: s.columns, rows: s.rows,
  error_code: s.errorCode, error: s.errorMessage });

export const createTerminalTools = (deps: TerminalToolsDeps): AgentTool[] => {
  const targets: Map<string, SSHTargetSnapshot> = new Map();
  const descriptions: string[] = [];
  for (const p of deps.profiles.snapshot().profiles) {
    targets.set(p.id, deps.runtime.captureTarget(p.id));
    descriptions.push(p.id + ': ' + p.name + ' (' + p.username + '@' + p.host + ':' + p.port + ')');
  }
  const defaultProfile = deps.profiles.defaultProfile();
  const defaultTarget: SSHTargetSnapshot | null = defaultProfile === null ? null : deps.runtime.captureTarget(null);
  const targetDescription: string = 'Remote SSH targets: ' + (descriptions.join('; ') || 'none') +
    '. Default profile: ' + (defaultTarget?.profileId ?? 'none') + '. All commands execute on that remote host.';
  const targetFor = (j: JsonObject): SSHTargetSnapshot => {
    const id: string | null = stringArg(j, 'profile_id', true);
    const target: SSHTargetSnapshot | null = id === null ? defaultTarget : targets.get(id) ?? null;
    if (target === null) throw new TerminalError('profile_missing');
    return { profileId: target.profileId, digest: target.digest, usesDefault: target.usesDefault };
  };
  const finishActivity = (id: string, s: TerminalJobSnapshot | TerminalSessionSnapshot): void => {
    if (agentTaskStatusRunning(s.status)) {
      const current = deps.activityStore.sandboxActivity;
      if (current !== null && current.toolCallId === id) {
        deps.activityStore.start({ toolCallId: current.toolCallId, toolName: current.toolName, title: current.title,
          status: current.status, conversationId: current.conversationId, inputPreview: current.inputPreview,
          outputTail: terminalTail(s.outputTail.trim(), ACTIVITY_MAX_OUTPUT_TAIL_CHARS), runtime: current.runtime,
          workspace: current.workspace, startedAtEpochMillis: current.startedAtEpochMillis,
          endedAtEpochMillis: current.endedAtEpochMillis, canCancel: current.canCancel,
          stepIndex: current.stepIndex, stepTotal: current.stepTotal });
      }
      return;
    }
    if (s.exitCode !== null) deps.activityStore.completeWithExitCode(id, s.exitCode, s.outputTail);
    else if (s.status === 'CANCELLED') deps.activityStore.cancel(id, s.outputTail);
    else deps.activityStore.fail(id, new TerminalError(s.errorCode ?? s.status.toLowerCase(), s.errorMessage ?? s.status));
  };
  const followJob = async (activityId: string, snapshot: TerminalJobSnapshot): Promise<void> => {
    let current: TerminalJobSnapshot = snapshot;
    while (agentTaskStatusRunning(current.status) && deps.activityStore.sandboxActivity?.toolCallId === activityId) {
      current = await deps.runtime.waitJob(current.jobId, 60000);
      finishActivity(activityId, current);
    }
  };
  const followSession = (activityId: string, snapshot: TerminalSessionSnapshot): void => {
    let unsubscribe: () => void = (): void => undefined;
    unsubscribe = deps.runtime.subscribeSession(snapshot.sessionId, (event): void => {
      finishActivity(activityId, event.snapshot);
      if (!agentTaskStatusRunning(event.snapshot.status) || deps.activityStore.sandboxActivity?.toolCallId !== activityId) unsubscribe();
    });
    if (!agentTaskStatusRunning(snapshot.status)) unsubscribe();
  };
  interface ToolSpec { name: string; description: string; required: string[]; properties: JsonObject; approval: boolean; }
  const stringProperty: JsonObject = { type: 'string' };
  const numberProperty: JsonObject = { type: 'number' };
  const specs: ToolSpec[] = [
    { name: 'terminal_execute', description: 'Execute a remote command and wait up to 5 seconds; return its real job state.',
      required: ['command'], properties: { profile_id: stringProperty, command: stringProperty, cwd: stringProperty, timeout_ms: numberProperty }, approval: true },
    { name: 'terminal_job_start', description: 'Start a remote command as a cancellable task.', required: ['command'],
      properties: { profile_id: stringProperty, command: stringProperty, cwd: stringProperty, timeout_ms: numberProperty }, approval: true },
    { name: 'terminal_job_read', description: 'Read cached remote job state and output tail.', required: ['job_id'],
      properties: { job_id: stringProperty }, approval: false },
    { name: 'terminal_job_wait', description: 'Wait at most timeout_ms for remote job state; cancelling this wait leaves the job running.',
      required: ['job_id'], properties: { job_id: stringProperty, timeout_ms: numberProperty }, approval: false },
    { name: 'terminal_job_stop', description: 'Stop the client connection for a remote job; remote detached daemons may remain.',
      required: ['job_id'], properties: { job_id: stringProperty }, approval: true },
    { name: 'terminal_session_start', description: 'Start an interactive remote SSH shell (xterm-256color).', required: [],
      properties: { profile_id: stringProperty, cwd: stringProperty, columns: numberProperty, rows: numberProperty }, approval: true },
    { name: 'terminal_session_exec', description: 'Write command plus newline to the approved remote shell. The shell returns session state, without a per-command exit code.',
      required: ['session_id', 'command'], properties: { session_id: stringProperty, command: stringProperty }, approval: true },
    { name: 'terminal_session_read', description: 'Read cached remote shell output; preserves terminal UI bytes.',
      required: ['session_id'], properties: { session_id: stringProperty }, approval: false },
    { name: 'terminal_session_stop', description: 'Close the specified remote SSH shell.', required: ['session_id'],
      properties: { session_id: stringProperty }, approval: true },
  ];
  return specs.map((spec: ToolSpec): AgentTool => makeAgentTool({
    name: spec.name, description: spec.description + '\n' + targetDescription,
    systemPrompt: (): string => targetDescription,
    needsApproval: spec.approval, allowsAutoApproval: false,
    parameters: () => makeInputSchemaObj(spec.properties, spec.required),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      let activityId: string | null = null;
      try {
        const j: JsonObject = inputObject(input);
        let job: TerminalJobSnapshot | null = null;
        let session: TerminalSessionSnapshot | null = null;
        const preview: string = stringArg(j, 'command', true) ?? stringArg(j, 'job_id', true) ?? stringArg(j, 'session_id', true) ?? '';
        activityId = deps.activityStore.startTool(spec.name, 'Remote SSH', preview, 'remote_ssh',
          stringArg(j, 'cwd', true) ?? '', spec.approval, deps.conversationId);
        if (spec.name === 'terminal_execute' || spec.name === 'terminal_job_start') {
          const request = { target: targetFor(j), command: stringArg(j, 'command')!, cwd: stringArg(j, 'cwd', true),
            timeoutMs: numberArg(j, 'timeout_ms', 60000), sourceToolName: spec.name, sourceConversationId: deps.conversationId };
          job = spec.name === 'terminal_execute' ? await deps.runtime.execute(request, signal) : await deps.runtime.startJob(request, signal);
        } else if (spec.name === 'terminal_job_read') job = deps.runtime.readJob(stringArg(j, 'job_id')!);
        else if (spec.name === 'terminal_job_wait') job = await deps.runtime.waitJob(stringArg(j, 'job_id')!, numberArg(j, 'timeout_ms', 5000), signal);
        else if (spec.name === 'terminal_job_stop') job = await deps.runtime.stopJob(stringArg(j, 'job_id')!);
        else if (spec.name === 'terminal_session_start') session = await deps.runtime.startSession({ target: targetFor(j),
          cwd: stringArg(j, 'cwd', true), columns: numberArg(j, 'columns', 80), rows: numberArg(j, 'rows', 24),
          sourceToolName: spec.name, sourceConversationId: deps.conversationId }, signal);
        else if (spec.name === 'terminal_session_exec') {
          const id: string = stringArg(j, 'session_id')!;
          const profileId: string = deps.runtime.readSession(id).profileId;
          const approved: SSHTargetSnapshot | undefined = targets.get(profileId);
          if (approved === undefined) throw new TerminalError('target_changed');
          session = await deps.runtime.execSession(id, stringArg(j, 'command')!, approved, signal);
        } else if (spec.name === 'terminal_session_read') session = deps.runtime.readSession(stringArg(j, 'session_id')!);
        else session = await deps.runtime.stopSession(stringArg(j, 'session_id')!);
        const snapshot: TerminalJobSnapshot | TerminalSessionSnapshot = job ?? session!;
        finishActivity(activityId, snapshot);
        if (job !== null && agentTaskStatusRunning(job.status)) {
          void followJob(activityId, job).catch((): void => { /* later activity may supersede this invocation */ });
        }
        if (session !== null && agentTaskStatusRunning(session.status)) followSession(activityId, session);
        return textParts(job !== null ? jobJson(job) : sessionJson(session!));
      } catch (error) {
        const failure = terminalFailure(error as Error);
        if (activityId !== null) {
          if (failure.code === 'cancelled') deps.activityStore.cancel(activityId);
          else deps.activityStore.fail(activityId, new TerminalError(failure.code, failure.message));
        }
        return textParts({ runtime: 'remote_ssh', status: 'failed', running: false, exit_code: null,
          error_code: failure.code, error: failure.message });
      }
    },
  }));
};
