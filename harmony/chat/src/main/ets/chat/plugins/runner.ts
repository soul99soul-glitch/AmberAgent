import { permissionDecisionTraceToJson } from '../tool_permission.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessagePartTool, UIMessagePartText } from '../message.ts';
import type { UIMessage, UIMessagePart } from '../message.ts';
import type { Conversation, MessageNode } from '../conversation.ts';
import type { AgentTool } from '../tool.ts';
import { newId } from '../ids.ts';
import { sanitizedToolFailureMessage } from '../tool_dispatcher.ts';
import { TerminalController, relayAbort } from '../terminal/control.ts';
import { terminalUTF8Encode } from '../terminal/utf8.ts';
import { createRecipeRun, nextRecipeStep, acceptRecipeStep } from '../recipes/runner.ts';
import { canonicalRecipeJSON, parseRecipeBinding } from '../recipes/validation.ts';
import type { InstalledPlugin, PluginCheckpoint, PluginDescriptor, PluginFailureKind, PluginHttpMethod,
  PluginJsEvent, PluginRunCheckpoint, PluginSource, PluginTestContext } from './models.ts';
import { PluginHttpError } from './models.ts';
import type { PluginExecutionPort, PluginHttpPort, PluginJsPort, PluginLoopAdapter, PluginMcpPort,
  PluginStore, PluginWebMountPort } from './ports.ts';
import { normalizePluginInputs, validatePluginOutput } from './validation.ts';
import { pluginJSONEqual } from './json_schema.ts';
import { checkPluginHostCall } from './broker.ts';

export type { PluginExecutionPort, PluginLoopAdapter } from './ports.ts';
export interface PluginLoopAdapterDeps extends PluginMcpPort {
  store: PluginStore; installed: InstalledPlugin[]; js: PluginJsPort; http: PluginHttpPort; webMount: PluginWebMountPort;
}
type RunStatus = 'succeeded' | 'failed' | 'cancelled' | 'outcome_unknown' | 'test_failed';
class PluginRunError extends Error {
  constructor(readonly code: string, message: string, readonly status: RunStatus = 'failed',
    readonly failureKind: PluginFailureKind | null = null) { super(message); }
}
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const object = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;
const stringArg = (input: JsonObject, key: string): string => {
  const value = input[key];
  if (typeof value !== 'string' || value.length === 0) throw new PluginRunError('invalid_inputs', key + ' must be a nonempty string.');
  return value;
};
export const pluginSourceFromInput = (input: JsonObject): PluginSource => {
  if ((input['workspace_directory'] === undefined) === (input['archive_path'] === undefined)) {
    throw new PluginRunError('invalid_inputs', 'Specify exactly one of workspace_directory or archive_path.');
  }
  return input['workspace_directory'] !== undefined ? { kind: 'directory', workspacePath: stringArg(input, 'workspace_directory') } :
    { kind: 'archive', workspacePath: stringArg(input, 'archive_path') };
};
export const createPluginRun = (descriptor: PluginDescriptor, input: JsonValue,
  test: PluginTestContext | null = null): PluginRunCheckpoint => {
  const pinned = copy(descriptor);
  return { kind: 'run', executionId: newId(), descriptor: pinned, inputs: normalizePluginInputs(pinned, input),
    phase: 'ready', pendingStep: null, pendingCallId: null, test: copy(test),
    recipeState: pinned.implementation.kind === 'recipe' ? createRecipeRun({ hash: pinned.packageHash,
      canonicalJSON: canonicalRecipeJSON(pinned.implementation.manifest), manifest: pinned.implementation.manifest }, input) : null };
};
const withCheckpoint = (parent: UIMessagePartTool, checkpoint: PluginCheckpoint): UIMessagePartTool => ({ ...parent,
  metadata: { ...parent.metadata, plugin_v1: copy(checkpoint) as unknown as JsonValue } });
const checkpointOf = (parent: UIMessagePartTool): PluginCheckpoint => {
  const raw = object(parent.metadata?.['plugin_v1']);
  if (raw === null) throw new PluginRunError('invalid_checkpoint', 'Plugin has no pinned checkpoint.');
  const checkpoint = copy(raw) as unknown as PluginCheckpoint;
  if (checkpoint.kind === 'import' && parent.toolName === 'plugin_import' && checkpoint.preview?.candidate?.hash) return checkpoint;
  if (checkpoint.kind !== 'run' || !checkpoint.executionId || !checkpoint.descriptor?.packageHash ||
    (parent.toolName !== 'plugin_test' && parent.toolName !== checkpoint.descriptor.toolId) ||
    !['ready', 'running', 'awaiting_approval', 'started', 'finished'].includes(checkpoint.phase)) {
    throw new PluginRunError('invalid_checkpoint', 'Invalid Plugin execution checkpoint.');
  }
  checkpoint.inputs = normalizePluginInputs(checkpoint.descriptor, checkpoint.inputs);
  if (checkpoint.phase === 'awaiting_approval' || checkpoint.phase === 'started') {
    if (!checkpoint.pendingStep || checkpoint.pendingStep.type !== 'tool' || !checkpoint.pendingStep.toolCallId ||
      checkpoint.pendingStep.output.length !== 0) throw new PluginRunError('invalid_checkpoint', 'Plugin checkpoint has no exact pending child.');
  }
  if ((parent.toolName === 'plugin_test') !== (checkpoint.test !== null) ||
    (checkpoint.test !== null && checkpoint.test.candidateHash !== checkpoint.descriptor.packageHash)) {
    throw new PluginRunError('invalid_checkpoint', 'Plugin test identity is inconsistent.');
  }
  return checkpoint;
};
const failure = (error: Error): PluginRunError => error instanceof PluginRunError ? error :
  new PluginRunError((error as PluginRunError).code ?? 'plugin_exception', sanitizedToolFailureMessage(error), 'failed', 'exception');
const textResult = (part: UIMessagePartTool): string => part.output.filter((p) => p.type === 'text')
  .map((p) => (p as UIMessagePartText).text).join('\n');
const parsedOutput = (text: string): JsonValue | undefined => { try { return JSON.parse(text) as JsonValue; } catch { return undefined; } };
const hostResult = (part: UIMessagePartTool): JsonValue => {
  if (part.output.length === 0) throw new PluginRunError('outcome_unknown', 'Started child returned no result.', 'outcome_unknown');
  const text = textResult(part), value = parsedOutput(text), json = object(value);
  const status = typeof json?.['status'] === 'string' ? json['status'].toLowerCase() : '';
  if (status === 'outcome_unknown' || json?.['may_have_applied'] === true) {
    throw new PluginRunError('outcome_unknown', 'Remote side effect completion is unknown.', 'outcome_unknown');
  }
  if (['cancelled', 'interrupted'].includes(status)) throw new PluginRunError('cancelled', 'Plugin child was interrupted.', 'cancelled');
  if (status === 'denied') throw new PluginRunError('denied', 'Plugin child was denied.');
  if (['failed', 'timed_out'].includes(status) || json?.['ok'] === false) {
    throw new PluginRunError(status === 'timed_out' ? 'timed_out' : 'host_call_failed', 'Plugin child failed.', 'failed', status === 'timed_out' ? 'timeout' : 'exception');
  }
  return value === undefined ? text : value;
};
const decodeOutput = (descriptor: PluginDescriptor, text: string): JsonValue => {
  if (text.length > descriptor.maxOutputChars) throw new PluginRunError('output_limit', 'Plugin output exceeds its character budget.', 'failed', 'schema');
  if (descriptor.output === 'string') return text;
  const value = parsedOutput(text);
  if (value === undefined) throw new PluginRunError('invalid_output', 'Plugin output must be complete JSON.', 'failed', 'schema');
  return value;
};
const recipeOutput = (checkpoint: PluginRunCheckpoint): JsonObject => {
  const state = checkpoint.recipeState!, output: JsonObject = {};
  for (const key of Object.keys(state.descriptor.manifest.outputs)) {
    const binding = parseRecipeBinding(state.descriptor.manifest.outputs[key]!);
    const value = binding === null ? null : object(parsedOutput(state.stepOutputs[binding.name] ?? ''));
    if (!binding || !value || !Object.prototype.hasOwnProperty.call(value, binding.field!)) {
      throw new PluginRunError('invalid_output', 'Private Recipe output binding is missing: ' + key, 'failed', 'schema');
    }
    output[key] = value[binding.field!]!;
  }
  return output;
};
const child = (checkpoint: PluginRunCheckpoint, name: string, args: JsonObject): UIMessagePartTool => ({
  type: 'tool', toolCallId: checkpoint.executionId + '-child', toolName: name, input: JSON.stringify(args),
  output: [], approvalState: { type: 'auto' }, metadata: null,
});
const fixedChild = (saved: UIMessagePartTool | null, expected: UIMessagePartTool): UIMessagePartTool => {
  if (saved === null) return expected;
  if (saved.toolCallId !== expected.toolCallId || saved.toolName !== expected.toolName ||
    !pluginJSONEqual(JSON.parse(saved.input) as JsonValue, JSON.parse(expected.input) as JsonValue)) {
    throw new PluginRunError('invalid_checkpoint', 'Pending child differs from the pinned backend and inputs.');
  }
  return saved;
};
const dispatchChild = async (checkpoint: PluginRunCheckpoint, step: UIMessagePartTool, def: AgentTool,
  deps: PluginLoopAdapterDeps, port: PluginExecutionPort, signal?: AbortSignalLike): Promise<UIMessagePartTool> => {
  if (signal?.aborted) throw new PluginRunError('cancelled', 'Plugin was cancelled before dispatch.', 'cancelled');
  const controller = new TerminalController(); let timedOut = false;
  const detach = relayAbort(signal, () => controller.abort());
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, checkpoint.descriptor.timeoutMs);
  try {
    const operation = (): Promise<UIMessagePartTool | null> => port.dispatch(step, def, controller.signal);
    const result = step.toolName.startsWith('wm_') ? await deps.webMount.withScope(checkpoint.descriptor.capabilities.networkDomains,
      operation, step.toolName !== 'wm_open' && step.toolName !== 'wm_tab_select') : await operation();
    if (result !== null) {
      const envelope = object(parsedOutput(textResult(result)));
      if (envelope?.['status'] === 'outcome_unknown' || envelope?.['may_have_applied'] === true) return result;
    }
    if (timedOut) throw new PluginRunError('timed_out', 'Plugin host call timeout settled.', 'failed', 'timeout');
    if (signal?.aborted) throw new PluginRunError('cancelled', 'Plugin was cancelled.', 'cancelled');
    if (result === null) throw new PluginRunError('outcome_unknown', 'Started child returned no result.', 'outcome_unknown');
    return result;
  } catch (error) {
    if (error instanceof PluginHttpError && error.mayHaveApplied) {
      throw new PluginRunError('outcome_unknown', 'Remote side effect completion is unknown.', 'outcome_unknown');
    }
    if (error instanceof PluginRunError && error.status === 'outcome_unknown') throw error;
    if (timedOut) throw new PluginRunError('timed_out', 'Plugin host call timeout settled.', 'failed', 'timeout');
    if (signal?.aborted) throw new PluginRunError('cancelled', 'Plugin was cancelled.', 'cancelled');
    throw error;
  } finally { clearTimeout(timer); detach(); }
};
const httpDefinition = (checkpoint: PluginRunCheckpoint, current: AgentTool, deps: PluginLoopAdapterDeps): AgentTool => {
  const descriptor = checkpoint.descriptor;
  if (descriptor.implementation.kind !== 'remote') throw new PluginRunError('invalid_checkpoint', 'HTTP descriptor is missing.');
  const remote = descriptor.implementation.remote;
  return { ...current, execute: async (_input, signal): Promise<UIMessagePart[]> => {
    try {
      const result = await deps.http.execute({ url: remote.url!, method: remote.method as PluginHttpMethod, input: checkpoint.inputs,
        networkDomains: descriptor.capabilities.networkDomains, timeoutMs: descriptor.timeoutMs, maxOutputChars: descriptor.maxOutputChars }, signal);
      return [{ type: 'text', text: JSON.stringify(result), metadata: null }];
    } catch (error) {
      const unknown = error instanceof PluginHttpError && error.mayHaveApplied;
      return [{ type: 'text', text: JSON.stringify({ status: unknown ? 'outcome_unknown' : 'failed',
        may_have_applied: unknown, error_code: (error as PluginHttpError).code ?? 'remote_error', message: sanitizedToolFailureMessage(error as Error) }), metadata: null }];
    }
  } };
};
// Only a non-active conversation may be passed by the caller. No VM, execution, persistence or health effects occur here.
export const recoverInterruptedPlugins = (conversation: Conversation,
  hasJsSession: (executionId: string) => boolean): Conversation => {
  let changed = false;
  const messageNodes = conversation.messageNodes.map((node: MessageNode): MessageNode => {
    let nodeChanged = false;
    const messages = node.messages.map((message: UIMessage): UIMessage => {
      let messageChanged = false;
      const parts = message.parts.map((part: UIMessagePart): UIMessagePart => {
        if (part.type !== 'tool' || part.output.length > 0 ||
          (!part.toolName.startsWith('plugin__') && part.toolName !== 'plugin_test')) return part;
        let checkpoint: PluginCheckpoint;
        try { checkpoint = checkpointOf(part); } catch { return part; }
        if (checkpoint.kind !== 'run') return part;
        const vmLost = checkpoint.descriptor.implementation.kind === 'javascript' &&
          ['running', 'awaiting_approval'].includes(checkpoint.phase) && !hasJsSession(checkpoint.executionId);
        if (checkpoint.phase !== 'started' && !vmLost) return part;
        const value: JsonObject = { status: 'outcome_unknown', plugin_id: checkpoint.descriptor.pluginId,
          tool: checkpoint.descriptor.toolId, package_hash: checkpoint.descriptor.packageHash, result: null,
          error_code: 'outcome_unknown', message: 'Interrupted plugin execution will not be replayed.', logs: [], quarantined: false };
        if (checkpoint.test !== null) {
          value['candidate_test'] = true; value['candidate_hash'] = checkpoint.test.candidateHash; value['registered'] = false;
          if (checkpoint.test.expectedProvided) value['expected_match'] = false;
        }
        messageChanged = true;
        return { ...withCheckpoint(part, { ...checkpoint, phase: 'finished', pendingStep: null, pendingCallId: null }),
          approvalState: { type: 'auto' }, output: [{ type: 'text', text: JSON.stringify(value), metadata: null }] };
      });
      if (!messageChanged) return message;
      nodeChanged = true; return { ...message, parts };
    });
    if (!nodeChanged) return node;
    changed = true; return { ...node, messages };
  });
  return changed ? { ...conversation, messageNodes } : conversation;
};
export const createPluginLoopAdapter = (deps: PluginLoopAdapterDeps): PluginLoopAdapter => {
  const installed = copy(deps.installed);
  const save = async (parent: UIMessagePartTool, checkpoint: PluginCheckpoint, port: PluginExecutionPort): Promise<void> => {
    try { await port.saveParent(parent); }
    catch (error) {
      if (checkpoint.kind === 'run' && checkpoint.descriptor.implementation.kind === 'javascript') deps.js.cancel(checkpoint.executionId);
      throw error;
    }
  };
  const finish = async (parent: UIMessagePartTool, checkpoint: PluginCheckpoint, port: PluginExecutionPort,
    error: PluginRunError | null = null, actual: JsonValue = null, logs: string[] = []): Promise<UIMessagePartTool> => {
    const descriptor = checkpoint.kind === 'run' ? checkpoint.descriptor : null;
    if (descriptor && error === null) {
      const issues = validatePluginOutput(descriptor, actual);
      if (issues.length > 0) error = new PluginRunError('invalid_output', issues.map((issue) => issue.path + ': ' + issue.message).join('; '), 'failed', 'schema');
      else if (JSON.stringify(actual).length > descriptor.maxOutputChars) error = new PluginRunError('output_limit', 'Plugin output exceeds its character budget.', 'failed', 'schema');
    }
    const test = checkpoint.kind === 'run' ? checkpoint.test : null;
    const expectedMatch = test?.expectedProvided && error === null ? pluginJSONEqual(actual, test.expectedResult) : undefined;
    if (expectedMatch === false) error = new PluginRunError('expected_mismatch', 'Successful result does not match expected_result.', 'test_failed');
    let quarantined = false;
    if (descriptor && test === null) {
      if (error?.failureKind) quarantined = (await deps.store.recordFailure(descriptor.pluginId, descriptor.packageHash,
        descriptor.toolId, error.failureKind, error.message)).quarantinedAt !== null;
      else if (error === null) await deps.store.recordSuccess(descriptor.pluginId, descriptor.packageHash);
    }
    const value: JsonObject = { status: error?.status ?? 'succeeded', plugin_id: descriptor?.pluginId ??
      (checkpoint.kind === 'import' ? checkpoint.preview.candidate.manifest.id : ''), tool: descriptor?.toolId ?? 'plugin_import',
      package_hash: descriptor?.packageHash ?? (checkpoint.kind === 'import' ? checkpoint.preview.candidate.hash : ''),
      result: actual, error_code: error?.code ?? null, message: error?.message ?? 'Plugin completed.', logs, quarantined };
    if (test !== null) {
      value['candidate_hash'] = test.candidateHash; value['candidate_test'] = true; value['registered'] = false;
      if (test.expectedProvided) value['expected_match'] = expectedMatch ?? false;
    }
    const finalCheckpoint: PluginCheckpoint = checkpoint.kind === 'run' ? { ...checkpoint, phase: 'finished', pendingStep: null, pendingCallId: null } : checkpoint;
    const result = { ...withCheckpoint(parent, finalCheckpoint), approvalState: { type: 'auto' as const },
      output: [{ type: 'text' as const, text: JSON.stringify(value), metadata: null }] };
    await save(result, checkpoint, port); return result;
  };
  const rejectJs = async (checkpoint: PluginRunCheckpoint, error: PluginRunError): Promise<void> => {
    if (checkpoint.descriptor.implementation.kind !== 'javascript' || !deps.js.hasSession(checkpoint.executionId)) return;
    if (checkpoint.pendingCallId !== null) {
      try { await deps.js.reject(checkpoint.executionId, checkpoint.pendingCallId, error.code); } finally { deps.js.cancel(checkpoint.executionId); }
    } else deps.js.cancel(checkpoint.executionId);
  };
  return {
    supports: (name) => name === 'plugin_import' || name === 'plugin_test' || name.startsWith('plugin__'),
    prepare: async (parent, primitives): Promise<UIMessagePartTool> => {
      if (parent.metadata?.['plugin_v1'] !== undefined) { checkpointOf(parent); return copy(parent); }
      const input = object(JSON.parse(parent.input || '{}') as JsonValue);
      if (input === null) throw new PluginRunError('invalid_inputs', 'Plugin inputs must be a JSON object.');
      if (parent.toolName === 'plugin_import') {
        if (input['enable'] !== undefined && typeof input['enable'] !== 'boolean') throw new PluginRunError('invalid_inputs', 'enable must be boolean.');
        const preview = await deps.store.prepareImport(pluginSourceFromInput(input), primitives, input['enable'] === true);
        if (preview.candidate.hash !== stringArg(input, 'expected_candidate_hash')) throw new PluginRunError('stale_candidate', 'Candidate changed after its visible preview or test.');
        if (Object.prototype.hasOwnProperty.call(input, 'expected_base_hash') && input['expected_base_hash'] !== preview.baseHash) {
          throw new PluginRunError('stale_base', 'Installed plugin changed after its visible preview.');
        }
        return withCheckpoint(parent, { kind: 'import', preview });
      }
      if (parent.toolName === 'plugin_test') {
        const read = await deps.store.readPackage(pluginSourceFromInput(input), primitives);
        if (read.candidate.hash !== stringArg(input, 'expected_candidate_hash')) throw new PluginRunError('stale_candidate', 'Candidate changed after its visible preview.');
        const name = stringArg(input, 'tool');
        const descriptor = read.candidate.tools.find((d) => d.name === name || d.toolId === name);
        if (!descriptor) throw new PluginRunError('missing_tool', 'Candidate tool is unavailable.');
        return withCheckpoint(parent, createPluginRun(descriptor, input['inputs'] ?? {}, { kind: 'candidate_test',
          candidateHash: read.candidate.hash, expectedProvided: Object.prototype.hasOwnProperty.call(input, 'expected_result'),
          expectedResult: input['expected_result'] ?? null }));
      }
      const descriptor = installed.filter((entry) => entry.enabled && entry.package !== null)
        .flatMap((entry) => entry.package!.tools).find((d) => d.toolId === parent.toolName);
      if (!descriptor) throw new PluginRunError('missing_plugin', 'Plugin is disabled, quarantined or unavailable.');
      return withCheckpoint(parent, createPluginRun(descriptor, input));
    },
    advance: async (parent, port, signal): Promise<UIMessagePartTool> => {
      let checkpoint = checkpointOf(parent);
      if (parent.output.length > 0) return parent;
      if (checkpoint.kind === 'import') {
        const def = port.primitive('plugin_import');
        if (!def) return finish(parent, checkpoint, port, new PluginRunError('missing_tool', 'Plugin import is unavailable.'));
        if (signal?.aborted) return finish(parent, checkpoint, port, new PluginRunError('cancelled', 'Plugin import cancelled.', 'cancelled'));
        if (parent.approvalState.type === 'denied') return finish(parent, checkpoint, port, new PluginRunError('denied', 'Plugin import was denied.'));
        if (parent.approvalState.type !== 'approved') {
          if (parent.approvalState.type === 'pending') return parent;
          const pending = { ...parent, approvalState: { type: 'pending' as const }, output: [] };
          await save(pending, checkpoint, port); return pending;
        }
        const decision = await port.decide(parent, def, signal);
        if (signal?.aborted) return finish(parent, checkpoint, port, new PluginRunError('cancelled', 'Plugin import cancelled.', 'cancelled'));
        if (decision.action === 'deny') return finish(parent, checkpoint, port, new PluginRunError('denied', 'Plugin import is denied by current policy.'));
        let receipt: JsonValue;
        try {
          const primitives = checkpoint.preview.candidate.tools.flatMap((d) => d.primitiveTools)
            .map((name) => port.primitive(name)).filter((tool): tool is AgentTool => tool !== null);
          receipt = copy(await deps.store.applyImport(checkpoint.preview, primitives)) as unknown as JsonValue;
        } catch (error) { return finish(parent, checkpoint, port, failure(error as Error)); }
        return finish(parent, checkpoint, port, null, receipt);
      }
      const implementation = checkpoint.descriptor.implementation;
      if (checkpoint.phase === 'started' || checkpoint.phase === 'running' || checkpoint.phase === 'finished') {
        deps.js.cancel(checkpoint.executionId);
        return finish(parent, checkpoint, port, new PluginRunError('outcome_unknown', 'Started execution has no saved result and will not be replayed.', 'outcome_unknown'));
      }
      if (implementation.kind === 'javascript' && checkpoint.phase === 'awaiting_approval' && !deps.js.hasSession(checkpoint.executionId)) {
        return finish(parent, checkpoint, port, new PluginRunError('outcome_unknown', 'The suspended VM no longer exists; source will not be re-evaluated.', 'outcome_unknown'));
      }
      if (signal?.aborted) {
        const cancelled = new PluginRunError('cancelled', 'Plugin was cancelled before starting.', 'cancelled');
        await rejectJs(checkpoint, cancelled); return finish(parent, checkpoint, port, cancelled);
      }
      const executionId = checkpoint.executionId;
      const detach = implementation.kind === 'javascript' ? relayAbort(signal, () => deps.js.cancel(executionId)) : (): void => undefined;
      try {
        let event: PluginJsEvent | null = null;
        if (implementation.kind === 'javascript' && checkpoint.phase === 'ready') {
          checkpoint = { ...checkpoint, phase: 'running' }; parent = withCheckpoint(parent, checkpoint); await save(parent, checkpoint, port);
          try { event = await deps.js.start({ executionId: checkpoint.executionId, source: implementation.source, inputJson: JSON.stringify(checkpoint.inputs),
            hostTools: implementation.hostTools, timeoutMs: checkpoint.descriptor.timeoutMs, maxOutputChars: checkpoint.descriptor.maxOutputChars }, signal); }
          catch (error) { deps.js.cancel(checkpoint.executionId); return finish(parent, checkpoint, port, failure(error as Error)); }
        }
        while (true) {
          if (signal?.aborted) {
            const cancelled = new PluginRunError('cancelled', 'Plugin was cancelled.', 'cancelled');
            await rejectJs(checkpoint, cancelled); return finish(parent, checkpoint, port, cancelled);
          }
          if (event !== null) {
            if (event.sessionId !== checkpoint.executionId) throw new PluginRunError('invalid_event', 'JS event belongs to another execution.');
            if (event.type === 'failed') return finish(parent, checkpoint, port,
              new PluginRunError(event.errorCode, event.message, event.errorCode === 'cancelled' ? 'cancelled' : 'failed',
                event.errorCode === 'cancelled' ? null : ['timeout', 'timed_out'].includes(event.errorCode) ? 'timeout' : 'exception'), null, event.logs);
            if (event.type === 'finished') {
              const actual = parsedOutput(event.resultJson);
              if (actual === undefined) return finish(parent, checkpoint, port, new PluginRunError('invalid_output', 'JS result is not complete JSON.', 'failed', 'schema'));
              return finish(parent, checkpoint, port, null, actual, event.logs);
            }
            checkpoint = { ...checkpoint, phase: 'ready', pendingCallId: event.callId };
            try {
              const args = object(JSON.parse(event.argsJson) as JsonValue);
              if (args === null) throw new PluginRunError('invalid_arguments', 'JS host arguments must be a JSON object.', 'failed', 'exception');
              checkpoint.pendingStep = child(checkpoint, event.toolName, args);
            } catch (error) {
              const stopped = failure(error as Error); await rejectJs(checkpoint, stopped); return finish(parent, checkpoint, port, stopped);
            }
            checkpoint.pendingStep!.toolCallId = checkpoint.executionId + '-' + event.callId;
            event = null;
          }
          let step: UIMessagePartTool | null = checkpoint.pendingStep;
          let def: AgentTool | null = null;
          try {
            if (implementation.kind === 'recipe') {
              const expected = nextRecipeStep({ ...checkpoint.recipeState!, phase: 'ready', pendingStep: null });
              if (expected === null) return finish(parent, checkpoint, port, null, recipeOutput(checkpoint));
              step = fixedChild(step, expected);
            } else if (implementation.kind === 'command') {
              if (!checkpoint.descriptor.capabilities.localRuntimes.includes('embedded_python')) throw new PluginRunError('capability_denied', 'embedded_python is not declared.');
              const stdin = implementation.stdinInput === null ? JSON.stringify(checkpoint.inputs) : checkpoint.inputs[implementation.stdinInput];
              if (typeof stdin !== 'string') throw new PluginRunError('invalid_inputs', 'stdin_input must reference an exact string input.');
              step = fixedChild(step, child(checkpoint, 'python_execute', { code: implementation.source, stdin, timeout_ms: Math.min(checkpoint.descriptor.timeoutMs, 60000) }));
            } else if (implementation.kind === 'remote') {
              const remote = implementation.remote;
              if (remote.kind === 'mcp') {
                def = deps.resolveMcpPrimitive(remote.server!, remote.tool!);
                if (!def || port.primitive(def.name) === null) throw new PluginRunError('missing_tool', 'Fixed MCP tool is outside the current assistant scope.');
                if (step !== null && step.toolName !== def.name) throw new PluginRunError('target_changed', 'Fixed MCP alias changed while awaiting approval.');
                step = fixedChild(step, child(checkpoint, def.name, checkpoint.inputs));
              } else step = fixedChild(step, child(checkpoint, 'http_request', { url: remote.url!, method: remote.method!,
                input: checkpoint.inputs, network_domains: checkpoint.descriptor.capabilities.networkDomains }));
            }
            if (step === null) throw new PluginRunError('invalid_checkpoint', 'Plugin has no executable child.');
            if (implementation.kind === 'javascript' || implementation.kind === 'recipe') {
              checkPluginHostCall(checkpoint.descriptor, step.toolName, JSON.parse(step.input) as JsonValue);
            }
            def = def ?? port.primitive(step.toolName);
            if (!def) throw new PluginRunError('missing_tool', 'Current primitive is unavailable: ' + step.toolName);
            if (implementation.kind === 'remote' && implementation.remote.kind === 'openapi') def = httpDefinition(checkpoint, def, deps);
          } catch (error) {
            const stopped = failure(error as Error); await rejectJs(checkpoint, stopped); return finish(parent, checkpoint, port, stopped);
          }
          if (checkpoint.phase === 'awaiting_approval') {
            if (parent.approvalState.type === 'denied') {
              const denied = new PluginRunError('denied', 'Plugin host call was denied.');
              await rejectJs(checkpoint, denied); return finish(parent, checkpoint, port, denied);
            }
            if (step!.toolName === 'ask_user') {
              if (parent.approvalState.type === 'answered') step = { ...step!, approvalState: parent.approvalState };
              else {
                if (parent.approvalState.type === 'pending') return parent;
                const waiting = { ...parent, approvalState: { type: 'pending' as const } };
                await save(waiting, checkpoint, port); return waiting;
              }
            } else {
              if (parent.approvalState.type !== 'approved') return parent;
              step = { ...step!, approvalState: { type: 'approved' } };
            }
          } else step = { ...step!, metadata: { ...step!.metadata, ...port.capture(step!) } };
          const decision = await port.decide(step, def!, signal);
          if (signal?.aborted) {
            const cancelled = new PluginRunError('cancelled', 'Plugin host call cancelled.', 'cancelled');
            await rejectJs(checkpoint, cancelled); return finish(parent, checkpoint, port, cancelled);
          }
          if (decision.action === 'deny') {
            const denied = new PluginRunError('denied', decision.reason); await rejectJs(checkpoint, denied); return finish(parent, checkpoint, port, denied);
          }
          if (decision.action === 'ask') {
            checkpoint = { ...checkpoint, phase: 'awaiting_approval', pendingStep: { ...step, approvalState: { type: 'pending' },
            metadata: { ...step.metadata, permission_trace: permissionDecisionTraceToJson(decision.trace) } } };
            const pending = { ...withCheckpoint(parent, checkpoint), output: [], approvalState: { type: 'pending' as const } };
            await save(pending, checkpoint, port); return pending;
          }
          checkpoint = { ...checkpoint, phase: 'started', pendingStep: step,
            recipeState: checkpoint.recipeState === null ? null : { ...checkpoint.recipeState, phase: 'started', pendingStep: step } };
          parent = { ...withCheckpoint(parent, checkpoint), approvalState: { type: 'auto' } }; await save(parent, checkpoint, port);
          let actual: JsonValue;
          try {
            const result = await dispatchChild(checkpoint, step, def!, deps, port, signal);
            const hostValue = hostResult(result);
            if (implementation.kind === 'recipe') {
              checkpoint = { ...checkpoint, phase: 'ready', pendingStep: null,
                recipeState: acceptRecipeStep(checkpoint.recipeState!, result) };
              parent = withCheckpoint(parent, checkpoint); await save(parent, checkpoint, port); continue;
            }
            if (implementation.kind === 'javascript') {
              // Persist the known child before resuming the existing stack. Persistence failure leaves started, never dispatches twice.
              checkpoint = { ...checkpoint, phase: 'running', pendingStep: null };
              parent = withCheckpoint(parent, checkpoint); await save(parent, checkpoint, port);
              const reply = JSON.stringify(hostValue);
              if (terminalUTF8Encode(reply).byteLength > 64 * 1024) throw new PluginRunError('host_result_limit', 'Host result exceeds the native bridge 64KiB bound.', 'failed', 'schema');
              event = await deps.js.reply(checkpoint.executionId, checkpoint.pendingCallId!, reply); continue;
            }
            if (implementation.kind === 'command') {
              const python = object(hostValue);
              if (python?.['status'] !== 'completed' || python['exit_code'] !== 0 || typeof python['stdout'] !== 'string') {
                throw new PluginRunError('python_failed', 'Python command did not complete successfully.', 'failed', 'exception');
              }
              actual = decodeOutput(checkpoint.descriptor, python['stdout']);
            } else if (implementation.remote.kind === 'openapi') {
              const response = object(hostValue);
              if (!response || typeof response['status'] !== 'number' || response['status'] < 200 || response['status'] >= 300 || typeof response['body'] !== 'string') {
                throw new PluginRunError('remote_failed', 'OpenAPI request did not return a successful complete body.', 'failed', 'remote');
              }
              actual = decodeOutput(checkpoint.descriptor, response['body']);
            } else actual = decodeOutput(checkpoint.descriptor, textResult(result));
          } catch (error) {
            // Do not turn persistence errors into retryable terminal output. The last durable state remains started/running.
            if (!(error instanceof PluginRunError)) { deps.js.cancel(checkpoint.executionId); throw error; }
            await rejectJs(checkpoint, error); return finish(parent, checkpoint, port, error);
          }
          return finish(parent, checkpoint, port, null, actual);
        }
      } finally { detach(); }
    },
  };
};
