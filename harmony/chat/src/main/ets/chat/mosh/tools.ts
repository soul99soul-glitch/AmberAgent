import type { AbortSignalLike } from '@amber/deepread-domain';
import type { AgentTool } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePart } from '../message.ts';
import type { AgentToolActivityStore } from '../tool_activity.ts';
import { ACTIVITY_MAX_OUTPUT_TAIL_CHARS } from '../tool_activity.ts';
import { agentTaskStatusRunning } from '../agent_task.ts';
import type { SSHTargetSnapshot } from '../terminal/models.ts';
import type { SSHProfileStore } from '../terminal/profile_store.ts';
import { TerminalError } from '../terminal/profile_store.ts';
import { terminalTail } from '../terminal/utf8.ts';
import type { MoshRuntime } from './runtime.ts';
import type { MoshServerLocale, MoshSessionSnapshot } from './models.ts';
import { moshFailure } from './bootstrap.ts';

export interface MoshToolsDeps {
  runtime: MoshRuntime;
  profiles: SSHProfileStore;
  activityStore: AgentToolActivityStore;
  conversationId: string;
}
const objectArg = (input: JsonValue): JsonObject => {
  if (input === null || typeof input !== 'object' || Array.isArray(input)) throw new TerminalError('invalid_arguments');
  return input;
};
const stringArg = (input: JsonObject, name: string, optional: boolean = false): string | null => {
  const value: JsonValue | undefined = input[name];
  if (optional && (value === undefined || value === null || value === '')) return null;
  if (typeof value !== 'string' || !value.trim()) throw new TerminalError('invalid_arguments');
  return value;
};
const numberArg = (input: JsonObject, name: string, fallback: number): number => {
  const value: JsonValue | undefined = input[name];
  if (value === undefined || value === null) return fallback;
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new TerminalError('invalid_arguments');
  return value;
};
const localeArg = (input: JsonObject): MoshServerLocale => {
  const value: JsonValue | undefined = input['server_locale'];
  if (value === undefined) return 'C.UTF-8';
  if (value !== 'C.UTF-8' && value !== 'en_US.UTF-8') throw new TerminalError('invalid_arguments');
  return value;
};
const textParts = (json: JsonObject): UIMessagePart[] => [{ type: 'text', text: JSON.stringify(json), metadata: null }];
const sessionJson = (s: MoshSessionSnapshot): JsonObject => ({ runtime: s.runtime, session_id: s.sessionId, profile_id: s.profileId,
  status: s.status.toLowerCase(), running: agentTaskStatusRunning(s.status), connection_state: s.connectionState,
  reconnect_last_heard_ms: s.reconnectLastHeardMs, output: s.outputTail, columns: s.columns, rows: s.rows,
  error_code: s.errorCode, error: s.errorMessage });

export const createMoshTools = (deps: MoshToolsDeps): AgentTool[] => {
  const targets: Map<string, SSHTargetSnapshot> = new Map();
  const labels: string[] = [];
  for (const profile of deps.profiles.snapshot().profiles) {
    targets.set(profile.id, deps.runtime.captureTarget(profile.id));
    labels.push(profile.id + ': ' + profile.name + ' (' + profile.username + '@' + profile.host + ':' + profile.port + ')');
  }
  const defaultTarget: SSHTargetSnapshot | null = deps.profiles.defaultProfile() === null ? null : deps.runtime.captureTarget(null);
  const description: string = 'Remote Mosh targets: ' + (labels.join('; ') || 'none') + '. Default profile: ' +
    (defaultTarget?.profileId ?? 'none') + '. Requires mosh-server and reachable UDP. Commands use the remote interactive shell.';
  const targetFor = (input: JsonObject): SSHTargetSnapshot => {
    const id: string | null = stringArg(input, 'profile_id', true);
    const target: SSHTargetSnapshot | null = id === null ? defaultTarget : targets.get(id) ?? null;
    if (target === null) throw new TerminalError('profile_missing');
    return { profileId: target.profileId, digest: target.digest, usesDefault: target.usesDefault };
  };
  const finishActivity = (id: string, snapshot: MoshSessionSnapshot): void => {
    const current = deps.activityStore.sandboxActivity;
    if (current === null || current.toolCallId !== id) return;
    if (agentTaskStatusRunning(snapshot.status)) {
      deps.activityStore.start({ ...current, outputTail: terminalTail(snapshot.outputTail, ACTIVITY_MAX_OUTPUT_TAIL_CHARS) });
    } else if (snapshot.status === 'COMPLETED') deps.activityStore.complete(id, snapshot.outputTail);
    else if (snapshot.status === 'CANCELLED') deps.activityStore.cancel(id, snapshot.outputTail);
    else deps.activityStore.fail(id, new TerminalError(snapshot.errorCode ?? snapshot.status.toLowerCase(),
      snapshot.errorMessage ?? ('Remote Mosh: ' + snapshot.status.toLowerCase())));
  };
  const follow = (id: string, snapshot: MoshSessionSnapshot): void => {
    let unsubscribe: () => void = (): void => undefined;
    unsubscribe = deps.runtime.subscribeSession(snapshot.sessionId, (event): void => {
      finishActivity(id, event.snapshot);
      if (!agentTaskStatusRunning(event.snapshot.status) || deps.activityStore.sandboxActivity?.toolCallId !== id) unsubscribe();
    });
    if (!agentTaskStatusRunning(snapshot.status)) unsubscribe();
  };
  interface Spec { name: string; description: string; required: string[]; properties: JsonObject; }
  const string: JsonObject = { type: 'string' }; const number: JsonObject = { type: 'number' };
  const specs: Spec[] = [
    { name: 'terminal_mosh_session_start', description: 'Start a Mosh UDP interactive session using a trusted SSH bootstrap.',
      required: [], properties: { profile_id: string, columns: number, rows: number, udp_port: number,
        server_locale: { type: 'string', enum: ['C.UTF-8', 'en_US.UTF-8'] } } },
    { name: 'terminal_mosh_session_exec', description: 'Write command plus newline to the approved Mosh shell. Returns session state without a per-command exit code.',
      required: ['session_id', 'command'], properties: { session_id: string, command: string } },
    { name: 'terminal_mosh_session_read', description: 'Read cached Mosh state and terminal output without consuming UI bytes.',
      required: ['session_id'], properties: { session_id: string } },
    { name: 'terminal_mosh_session_stop', description: 'Close this Mosh client. A remote daemon may remain if the shutdown was not acknowledged.',
      required: ['session_id'], properties: { session_id: string } },
  ];
  return specs.map((spec: Spec): AgentTool => makeAgentTool({ name: spec.name, description: spec.description + '\n' + description,
    systemPrompt: (): string => description, needsApproval: spec.name !== 'terminal_mosh_session_read', allowsAutoApproval: false,
    parameters: () => makeInputSchemaObj(spec.properties, spec.required),
    execute: async (input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => {
      let activityId: string | null = null;
      try {
        const json: JsonObject = objectArg(input);
        activityId = deps.activityStore.startTool(spec.name, 'Remote Mosh', stringArg(json, 'command', true) ??
          stringArg(json, 'session_id', true) ?? '', 'remote_mosh', '', spec.name !== 'terminal_mosh_session_read', deps.conversationId);
        let snapshot: MoshSessionSnapshot;
        if (spec.name === 'terminal_mosh_session_start') {
          snapshot = await deps.runtime.startSession({ target: targetFor(json), columns: numberArg(json, 'columns', 80),
            rows: numberArg(json, 'rows', 24), udpPort: json['udp_port'] == null ? null : numberArg(json, 'udp_port', 0),
            serverLocale: localeArg(json),
            sourceToolName: spec.name, sourceConversationId: deps.conversationId }, signal);
        } else {
          const id: string = stringArg(json, 'session_id')!;
          if (spec.name === 'terminal_mosh_session_exec') {
            const target: SSHTargetSnapshot | undefined = targets.get(deps.runtime.readSession(id).profileId);
            if (target === undefined) throw new TerminalError('target_changed');
            snapshot = await deps.runtime.execSession(id, stringArg(json, 'command')!, target, signal);
          } else snapshot = spec.name === 'terminal_mosh_session_read' ? deps.runtime.readSession(id) : await deps.runtime.stopSession(id);
        }
        finishActivity(activityId, snapshot);
        if (agentTaskStatusRunning(snapshot.status)) follow(activityId, snapshot);
        return textParts(sessionJson(snapshot));
      } catch (error) {
        const failure = moshFailure(error as Error);
        if (activityId !== null) {
          if (failure.code === 'cancelled') deps.activityStore.cancel(activityId);
          else deps.activityStore.fail(activityId, new TerminalError(failure.code, failure.message));
        }
        return textParts({ runtime: 'remote_mosh', status: failure.code === 'cancelled' ? 'cancelled' : 'failed', running: false,
          error_code: failure.code, error: failure.message });
      }
    },
  }));
};
