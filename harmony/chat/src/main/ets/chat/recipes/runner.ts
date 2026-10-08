import { permissionDecisionTraceToJson } from '../tool_permission.ts';
import type { AbortSignalLike } from '@amber/deepread-domain';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool } from '../message.ts';
import type { Conversation, MessageNode } from '../conversation.ts';
import { newId } from '../ids.ts';
import type { AgentTool } from '../tool.ts';
import { sanitizedToolFailureMessage } from '../tool_dispatcher.ts';
import { TerminalController, relayAbort } from '../terminal/control.ts';
import type { InstalledRecipe, RecipeDescriptor, RecipeImportCheckpoint, RecipeRunCheckpoint } from './models.ts';
import { RECIPE_DEFAULT_TIMEOUT_SECONDS } from './models.ts';
import type { RecipeExecutionPort, RecipeLoopAdapter, RecipeStore } from './ports.ts';
import { canonicalRecipeJSON, decodeRecipe, normalizeRecipeInputs, parseRecipeBinding, validateRecipe } from './validation.ts';

export type { RecipeExecutionPort, RecipeLoopAdapter } from './ports.ts';
export interface RecipeLoopAdapterDeps { store: RecipeStore; installed: InstalledRecipe[]; }
type Checkpoint = RecipeRunCheckpoint | RecipeImportCheckpoint;
type RunStatus = 'succeeded' | 'failed' | 'cancelled' | 'outcome_unknown';
class RecipeRunError extends Error {
  constructor(readonly code: string, message: string, readonly status: RunStatus = 'failed', readonly outputs: JsonObject = {}) { super(message); }
}
const objectValue = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;
const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const pinnedDescriptor = (descriptor: RecipeDescriptor): RecipeDescriptor => {
  const manifest = decodeRecipe(descriptor.canonicalJSON);
  if (canonicalRecipeJSON(descriptor.manifest) !== canonicalRecipeJSON(manifest) || !descriptor.hash) {
    throw new RecipeRunError('invalid_checkpoint', 'Pinned Recipe content does not match its descriptor.');
  }
  return { hash: descriptor.hash, canonicalJSON: canonicalRecipeJSON(manifest), manifest };
};
const withCheckpoint = (parent: UIMessagePartTool, checkpoint: Checkpoint): UIMessagePartTool => ({
  ...parent, metadata: { ...parent.metadata, recipe_v1: copy(checkpoint) as unknown as JsonValue },
});
const checkpointOf = (parent: UIMessagePartTool): Checkpoint => {
  const raw = objectValue(parent.metadata?.['recipe_v1']);
  if (raw === null) throw new RecipeRunError('invalid_checkpoint', 'Recipe execution has no pinned checkpoint.');
  const checkpoint = copy(raw) as unknown as Checkpoint;
  if (checkpoint.kind === 'import' && parent.toolName === 'recipe_import') {
    if (!checkpoint.preview || typeof checkpoint.preview.workspacePath !== 'string' ||
      (checkpoint.preview.baseHash !== null && typeof checkpoint.preview.baseHash !== 'string')) {
      throw new RecipeRunError('invalid_checkpoint', 'Invalid Recipe import checkpoint.');
    }
    checkpoint.preview.candidate = pinnedDescriptor(checkpoint.preview.candidate);
    return checkpoint;
  }
  if (checkpoint.kind !== 'run' || !checkpoint.descriptor || typeof checkpoint.executionId !== 'string') {
    throw new RecipeRunError('invalid_checkpoint', 'Invalid Recipe run checkpoint.');
  }
  checkpoint.descriptor = pinnedDescriptor(checkpoint.descriptor);
  checkpoint.inputs = normalizeRecipeInputs(checkpoint.descriptor.manifest, checkpoint.inputs);
  const steps = checkpoint.descriptor.manifest.steps;
  if (parent.toolName !== 'recipe__' + checkpoint.descriptor.manifest.name || !Number.isInteger(checkpoint.nextIndex) ||
    checkpoint.nextIndex < 0 || checkpoint.nextIndex > steps.length || !Array.isArray(checkpoint.completedSteps) ||
    checkpoint.completedSteps.length !== checkpoint.nextIndex ||
    checkpoint.completedSteps.some((id, i) => id !== steps[i]?.id) ||
    objectValue(checkpoint.stepOutputs as unknown as JsonValue) === null ||
    checkpoint.completedSteps.some((id) => typeof checkpoint.stepOutputs[id] !== 'string') ||
    !['ready', 'awaiting_approval', 'started', 'finished'].includes(checkpoint.phase)) {
    throw new RecipeRunError('invalid_checkpoint', 'Recipe checkpoint progress is inconsistent.');
  }
  if (checkpoint.phase === 'awaiting_approval' || checkpoint.phase === 'started') {
    const pending = checkpoint.pendingStep;
    if (!pending || pending.type !== 'tool' || !pending.toolCallId || pending.toolName !== steps[checkpoint.nextIndex]?.tool ||
      typeof pending.input !== 'string' || !Array.isArray(pending.output) || pending.output.length !== 0 || !pending.approvalState) {
      throw new RecipeRunError('invalid_checkpoint', 'Recipe checkpoint is missing its pinned step.');
    }
  } else if (checkpoint.pendingStep !== null) throw new RecipeRunError('invalid_checkpoint', 'Unexpected pending Recipe step.');
  return checkpoint;
};
export const createRecipeRun = (descriptor: RecipeDescriptor, inputs: JsonValue): RecipeRunCheckpoint => {
  const pinned = pinnedDescriptor(descriptor);
  return { kind: 'run', executionId: newId(), descriptor: pinned, inputs: normalizeRecipeInputs(pinned.manifest, inputs),
    nextIndex: 0, completedSteps: [], stepOutputs: {}, phase: 'ready', pendingStep: null };
};
const outputObject = (checkpoint: RecipeRunCheckpoint, id: string): JsonObject => {
  let value: JsonValue;
  try { value = JSON.parse(checkpoint.stepOutputs[id] ?? '') as JsonValue; }
  catch { throw new RecipeRunError('invalid_step_output', 'Step ' + id + ' did not return a JSON object.'); }
  const object = objectValue(value);
  if (object === null) throw new RecipeRunError('invalid_step_output', 'Step ' + id + ' did not return a JSON object.');
  return object;
};
const resolveBinding = (checkpoint: RecipeRunCheckpoint, value: JsonValue): JsonValue => {
  const binding = parseRecipeBinding(value);
  if (binding === null) return copy(value);
  if (binding.kind === 'input') return checkpoint.inputs[binding.name]!;
  const output = outputObject(checkpoint, binding.name);
  const field = binding.field!;
  if (!Object.prototype.hasOwnProperty.call(output, field)) {
    throw new RecipeRunError('missing_output_field', 'Step ' + binding.name + ' has no output field ' + field + '.');
  }
  return copy(output[field]!);
};
// Primitive deadlines are shortened before capture/approval. Signal-only JS cancellation cannot stop its runtime.
const primitiveDeadline = (name: string, args: JsonObject, milliseconds: number): void => {
  let limit: number;
  let fallback: number;
  if (name === 'python_execute') { limit = 60000; fallback = 15000; }
  else if (name === 'javascript_execute') { limit = 30000; fallback = 10000; }
  else if (name === 'terminal_execute' || name === 'terminal_job_start') { limit = 600000; fallback = 60000; }
  else return;
  const requested = args['timeout_ms'];
  if (requested === undefined) args['timeout_ms'] = Math.min(fallback, limit, milliseconds);
  else if (typeof requested === 'number' && Number.isFinite(requested) && requested > 0) {
    args['timeout_ms'] = Math.min(requested, limit, milliseconds);
  }
};
export const nextRecipeStep = (checkpoint: RecipeRunCheckpoint): UIMessagePartTool | null => {
  if (checkpoint.phase !== 'ready') throw new RecipeRunError('invalid_checkpoint', 'Recipe is not ready for a new step.');
  const step = checkpoint.descriptor.manifest.steps[checkpoint.nextIndex];
  if (!step) return null;
  const args: JsonObject = {};
  for (const key of Object.keys(step.arguments)) args[key] = resolveBinding(checkpoint, step.arguments[key]!);
  primitiveDeadline(step.tool, args, (step.timeoutSeconds ?? RECIPE_DEFAULT_TIMEOUT_SECONDS) * 1000);
  return { type: 'tool', toolCallId: checkpoint.executionId + '-' + checkpoint.nextIndex, toolName: step.tool, input: JSON.stringify(args), output: [],
    approvalState: { type: 'auto' }, metadata: null };
};
const resultText = (result: UIMessagePartTool): string => result.output.filter((p) => p.type === 'text')
  .map((p) => (p as UIMessagePartText).text).join('\n');
const resultObject = (text: string): JsonObject | null => {
  try { return objectValue(JSON.parse(text) as JsonValue); } catch { return null; }
};
const assertResult = (toolName: string, value: JsonObject | null): void => {
  if (value === null) {
    if (toolName === 'python_execute') throw new RecipeRunError('step_failed', 'Python did not return its completed result.');
    if (toolName.startsWith('terminal_')) throw new RecipeRunError('step_outcome_unknown', 'Remote command returned no structured state.', 'outcome_unknown');
    return;
  }
  const status = typeof value['status'] === 'string' ? value['status'].toLowerCase() : '';
  if (['failed', 'denied', 'cancelled', 'interrupted', 'timed_out'].includes(status) || value['ok'] === false ||
    (typeof value['exit_code'] === 'number' && value['exit_code'] !== 0) ||
    (toolName === 'python_execute' && status !== 'completed')) {
    const code = status === 'denied' ? 'step_denied' : status === 'cancelled' || status === 'interrupted' ? 'step_cancelled' :
      status === 'timed_out' ? 'step_timed_out' : 'step_failed';
    throw new RecipeRunError(code, 'Step ' + toolName + ' returned an unsuccessful result.',
      code === 'step_cancelled' ? 'cancelled' : 'failed');
  }
  if (toolName.startsWith('terminal_') && toolName !== 'terminal_job_start' &&
    (value['running'] === true || status === 'running' || status === 'queued' || toolName.endsWith('_session_exec'))) {
    throw new RecipeRunError('step_outcome_unknown', 'Remote command completion has not been confirmed.', 'outcome_unknown', value);
  }
};
export const acceptRecipeStep = (checkpoint: RecipeRunCheckpoint, result: UIMessagePartTool): RecipeRunCheckpoint => {
  const pending = checkpoint.pendingStep;
  if (checkpoint.phase !== 'started' || !pending || result.toolCallId !== pending.toolCallId || result.toolName !== pending.toolName) {
    throw new RecipeRunError('invalid_step_result', 'Result does not belong to the started Recipe step.');
  }
  if (result.output.length === 0) throw new RecipeRunError('step_outcome_unknown', 'Step returned no terminal result.', 'outcome_unknown');
  const text = resultText(result);
  assertResult(result.toolName, resultObject(text));
  const step = checkpoint.descriptor.manifest.steps[checkpoint.nextIndex]!;
  return { ...checkpoint, phase: 'ready', pendingStep: null, nextIndex: checkpoint.nextIndex + 1,
    completedSteps: [...checkpoint.completedSteps, step.id], stepOutputs: { ...checkpoint.stepOutputs, [step.id]: text } };
};
const finalOutputs = (checkpoint: RecipeRunCheckpoint): JsonObject => {
  const outputs: JsonObject = {};
  for (const key of Object.keys(checkpoint.descriptor.manifest.outputs)) {
    outputs[key] = resolveBinding(checkpoint, checkpoint.descriptor.manifest.outputs[key]!);
  }
  return outputs;
};
const unconfirmedJob = (checkpoint: RecipeRunCheckpoint): JsonObject | null => {
  for (let i = 0; i < checkpoint.nextIndex; i++) {
    const step = checkpoint.descriptor.manifest.steps[i]!;
    if (step.tool !== 'terminal_job_start') continue;
    const started = resultObject(checkpoint.stepOutputs[step.id]!);
    if (!started || started['running'] !== true) continue;
    const confirmed = checkpoint.descriptor.manifest.steps.slice(i + 1, checkpoint.nextIndex).some((next) => {
      if (next.tool !== 'terminal_job_read' && next.tool !== 'terminal_job_wait') return false;
      const value = resultObject(checkpoint.stepOutputs[next.id]!);
      return value !== null && value['job_id'] === started['job_id'] && value['running'] === false && value['status'] === 'completed';
    });
    if (!confirmed) return started;
  }
  return null;
};
const failure = (error: Error): RecipeRunError => error instanceof RecipeRunError ? error :
  new RecipeRunError((error as RecipeRunError).code ?? 'recipe_failed', sanitizedToolFailureMessage(error));
const terminalParent = (parent: UIMessagePartTool, checkpoint: Checkpoint, status: RunStatus,
  error: RecipeRunError | null = null, outputs: JsonObject = {}): UIMessagePartTool => {
  const descriptor = checkpoint.kind === 'run' ? checkpoint.descriptor : checkpoint.preview.candidate;
  const value: JsonObject = { status, recipe: descriptor.manifest.name, version: descriptor.manifest.version, hash: descriptor.hash,
    completed_steps: checkpoint.kind === 'run' ? checkpoint.completedSteps : [],
    failed_step: checkpoint.kind === 'run' && status !== 'succeeded' ? descriptor.manifest.steps[checkpoint.nextIndex]?.id ?? null : null,
    outputs: error?.outputs && Object.keys(error.outputs).length > 0 ? error.outputs : outputs,
    error_code: error?.code ?? null, message: error?.message ?? 'Recipe completed.' };
  const finished: Checkpoint = checkpoint.kind === 'run' ? { ...checkpoint, phase: 'finished', pendingStep: null } : checkpoint;
  return { ...withCheckpoint(parent, finished), approvalState: { type: 'auto' },
    output: [{ type: 'text', text: JSON.stringify(value), metadata: null }] };
};

// The caller must exclude active runs before restoring a persisted conversation.
export const recoverInterruptedRecipes = (conversation: Conversation): Conversation => {
  let changed: boolean = false;
  const messageNodes: MessageNode[] = conversation.messageNodes.map((node: MessageNode): MessageNode => {
    let nodeChanged: boolean = false;
    const messages: UIMessage[] = node.messages.map((message: UIMessage): UIMessage => {
      let messageChanged: boolean = false;
      const parts: UIMessagePart[] = message.parts.map((part: UIMessagePart): UIMessagePart => {
        if (part.type !== 'tool' || !part.toolName.startsWith('recipe__') || part.output.length > 0) return part;
        const raw: JsonObject | null = objectValue(part.metadata?.['recipe_v1']);
        if (raw?.['kind'] !== 'run' || raw['phase'] !== 'started') return part;
        let checkpoint: Checkpoint;
        try { checkpoint = checkpointOf(part); }
        catch { return part; } // Malformed checkpoints cannot identify a pinned started step.
        if (checkpoint.kind !== 'run' || checkpoint.phase !== 'started') return part;
        messageChanged = true;
        return terminalParent(part, checkpoint, 'outcome_unknown', new RecipeRunError('outcome_unknown',
          'A started Recipe step has no saved result and will not be replayed.', 'outcome_unknown'));
      });
      if (!messageChanged) return message;
      nodeChanged = true;
      return { ...message, parts };
    });
    if (!nodeChanged) return node;
    changed = true;
    return { ...node, messages };
  });
  return changed ? { ...conversation, messageNodes } : conversation;
};

const saveTerminal = async (parent: UIMessagePartTool, checkpoint: Checkpoint, port: RecipeExecutionPort,
  error: RecipeRunError | null = null, outputs: JsonObject = {}): Promise<UIMessagePartTool> => {
  const result = terminalParent(parent, checkpoint, error?.status ?? 'succeeded', error, outputs);
  await port.saveParent(result);
  return result;
};
const dispatchStep = async (checkpoint: RecipeRunCheckpoint, step: UIMessagePartTool, def: AgentTool,
  port: RecipeExecutionPort, signal?: AbortSignalLike): Promise<UIMessagePartTool> => {
  if (signal?.aborted) throw new RecipeRunError('step_cancelled', 'Recipe execution was cancelled before dispatch.', 'cancelled');
  const controller = new TerminalController();
  const detach = relayAbort(signal, () => controller.abort());
  let timedOut = false;
  const timeout = setTimeout(() => { timedOut = true; controller.abort(); },
    (checkpoint.descriptor.manifest.steps[checkpoint.nextIndex]!.timeoutSeconds ?? RECIPE_DEFAULT_TIMEOUT_SECONDS) * 1000);
  try {
    const result = await port.dispatch(step, def, controller.signal);
    if (timedOut) throw new RecipeRunError('step_timed_out', 'Recipe step exceeded its timeout after cancellation settled.');
    if (signal?.aborted) throw new RecipeRunError('step_cancelled', 'Recipe execution was cancelled.', 'cancelled');
    if (result === null) throw new RecipeRunError('step_outcome_unknown', 'Started step returned no result.', 'outcome_unknown');
    return result;
  } catch (error) {
    if (timedOut) throw new RecipeRunError('step_timed_out', 'Recipe step exceeded its timeout after cancellation settled.');
    if (signal?.aborted) throw new RecipeRunError('step_cancelled', 'Recipe execution was cancelled.', 'cancelled');
    throw error;
  } finally { clearTimeout(timeout); detach(); }
};
export const createRecipeLoopAdapter = (deps: RecipeLoopAdapterDeps): RecipeLoopAdapter => {
  const installed = copy(deps.installed);
  return {
    supports: (name) => name === 'recipe_import' || name.startsWith('recipe__'),
    prepare: async (parent, primitives) => {
      if (parent.metadata?.['recipe_v1'] !== undefined) { checkpointOf(parent); return copy(parent); }
      const input = JSON.parse(parent.input || '{}') as JsonValue;
      if (parent.toolName === 'recipe_import') {
        const args = objectValue(input);
        if (!args || typeof args['workspace_path'] !== 'string') throw new RecipeRunError('input_invalid', 'workspace_path must be a string.');
        return withCheckpoint(parent, { kind: 'import', preview: await deps.store.prepareImport(args['workspace_path'], primitives) });
      }
      const recipe = installed.find((v) => v.enabled && 'recipe__' + v.descriptor.manifest.name === parent.toolName);
      if (!recipe) throw new RecipeRunError('missing_recipe', 'Recipe is disabled or unavailable.');
      const issues = validateRecipe(recipe.descriptor.manifest, primitives);
      if (issues.length > 0) throw new RecipeRunError('invalid_recipe', issues.map((v) => v.path + ': ' + v.message).join('; '));
      return withCheckpoint(parent, createRecipeRun(recipe.descriptor, input));
    },
    advance: async (parent, port, signal) => {
      let checkpoint = checkpointOf(parent);
      if (parent.output.length > 0) return parent;
      if (checkpoint.kind === 'import') {
        if (signal?.aborted) return saveTerminal(parent, checkpoint, port, new RecipeRunError('cancelled', 'Import cancelled.', 'cancelled'));
        const def = port.primitive('recipe_import');
        if (!def) return saveTerminal(parent, checkpoint, port, new RecipeRunError('missing_tool', 'recipe_import is unavailable.'));
        if (parent.approvalState.type === 'pending') return parent;
        const decision = await port.decide(parent, def, signal);
        if (signal?.aborted) return saveTerminal(parent, checkpoint, port, new RecipeRunError('cancelled', 'Import cancelled.', 'cancelled'));
        if (decision.action === 'deny' || parent.approvalState.type === 'denied') {
          return saveTerminal(parent, checkpoint, port, new RecipeRunError('import_denied', decision.reason));
        }
        if (decision.action === 'ask') {
          const pending = { ...parent, output: [], approvalState: { type: 'pending' as const },
            metadata: { ...parent.metadata, permission_trace: permissionDecisionTraceToJson(decision.trace) } };
          await port.saveParent(pending); return pending;
        }
        let result: RecipeDescriptor;
        try { result = await deps.store.applyImport(checkpoint.preview,
          checkpoint.preview.candidate.manifest.steps.map((step) => port.primitive(step.tool)).filter((v): v is AgentTool => v !== null)); }
        catch (error) { return saveTerminal(parent, checkpoint, port, failure(error as Error)); }
        return saveTerminal(parent, checkpoint, port, null, { name: result.manifest.name, hash: result.hash });
      }
      if (checkpoint.phase === 'started' || checkpoint.phase === 'finished') {
        return saveTerminal(parent, checkpoint, port, new RecipeRunError('outcome_unknown', 'A started Recipe step has no saved result and will not be replayed.', 'outcome_unknown'));
      }
      while (checkpoint.nextIndex < checkpoint.descriptor.manifest.steps.length) {
        if (signal?.aborted) return saveTerminal(parent, checkpoint, port, new RecipeRunError('step_cancelled', 'Recipe execution was cancelled.', 'cancelled'));
        let step: UIMessagePartTool;
        try { step = checkpoint.pendingStep ?? nextRecipeStep(checkpoint)!; }
        catch (error) { return saveTerminal(parent, checkpoint, port, failure(error as Error)); }
        const def = port.primitive(step.toolName);
        if (!def) return saveTerminal(parent, checkpoint, port, new RecipeRunError('missing_tool', 'Primitive ' + step.toolName + ' is unavailable.'));
        if (checkpoint.phase === 'awaiting_approval') {
          if (parent.approvalState.type === 'denied') return saveTerminal(parent, checkpoint, port, new RecipeRunError('step_denied', 'Recipe step was denied.'));
          if (step.toolName === 'ask_user') {
            if (parent.approvalState.type === 'answered') step = { ...step, approvalState: parent.approvalState };
            else {
              if (parent.approvalState.type === 'pending') return parent;
              const waiting = { ...parent, approvalState: { type: 'pending' as const } };
              await port.saveParent(waiting); return waiting;
            }
          } else if (parent.approvalState.type === 'approved') step = { ...step, approvalState: { type: 'approved' } };
          else return parent;
        } else {
          try { step = { ...step, metadata: port.capture(step) }; }
          catch (error) { return saveTerminal(parent, checkpoint, port, failure(error as Error)); }
        }
        const decision = await port.decide(step, def, signal);
        if (signal?.aborted) return saveTerminal(parent, checkpoint, port, new RecipeRunError('step_cancelled', 'Recipe execution was cancelled.', 'cancelled'));
        if (decision.action === 'deny') return saveTerminal(parent, checkpoint, port, new RecipeRunError('step_denied', decision.reason));
        if (decision.action === 'ask') {
          checkpoint = { ...checkpoint, phase: 'awaiting_approval', pendingStep: { ...step, approvalState: { type: 'pending' },
            metadata: { ...step.metadata, permission_trace: permissionDecisionTraceToJson(decision.trace) } } };
          const pending = { ...withCheckpoint(parent, checkpoint), output: [], approvalState: { type: 'pending' as const } };
          await port.saveParent(pending); return pending;
        }
        checkpoint = { ...checkpoint, phase: 'started', pendingStep: step };
        parent = { ...withCheckpoint(parent, checkpoint), output: [], approvalState: { type: 'auto' } };
        await port.saveParent(parent);
        try { checkpoint = acceptRecipeStep(checkpoint, await dispatchStep(checkpoint, step, def, port, signal)); }
        catch (error) { return saveTerminal(parent, checkpoint, port, failure(error as Error)); }
        parent = withCheckpoint(parent, checkpoint);
        // Keep this save outside the dispatch catch: failed persistence must retain durable started, never claim a safe retry.
        await port.saveParent(parent);
      }
      try {
        const job = unconfirmedJob(checkpoint);
        if (job !== null) return saveTerminal(parent, checkpoint, port,
          new RecipeRunError('step_outcome_unknown', 'Remote job has no explicitly confirmed terminal result.', 'outcome_unknown', job));
        return saveTerminal(parent, checkpoint, port, null, finalOutputs(checkpoint));
      } catch (error) { return saveTerminal(parent, checkpoint, port, failure(error as Error)); }
    },
  };
};
