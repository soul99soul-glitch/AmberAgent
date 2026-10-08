import { permissionDecisionTraceToJson } from '../tool_permission.ts';
// webmount/goal — wm_run_goal 有界目标编排(E12 冻结合同 C)
//
// 复用既有 ordered host loop:goal adapter 实现 RecipeLoopAdapter,经
//   RecipeExecutionPort 的 capture/decide/dispatch/saveParent 推进,不复制
//   工具执行。checkpoint 即 metadata.goal_v1(冻结合同 DTO)。
// 真实边界:候选只来自当轮观察/请求(≤32,固定 ID);决策端口由 root 注入
//   (主模型选择器或 E11 Jev 接缝);无 DONE 捷径 — 完成只由新观察中
//   URL/标题/可见文本命中 completionText 判定;started 后无结果即
//   outcome_unknown,绝不重放;等待期间文档变化则丢弃旧 child 重新审批。

import type { AbortSignalLike } from '@amber/deepread-domain';
import type { Conversation, MessageNode } from '../conversation.ts';
import type { JsonObject, JsonValue } from '../json.ts';
import type { UIMessage, UIMessagePart, UIMessagePartText, UIMessagePartTool } from '../message.ts';
import type { AgentTool, InputSchemaObj } from '../tool.ts';
import { makeAgentTool, makeInputSchemaObj } from '../tool.ts';
import type { RecipeExecutionPort, RecipeLoopAdapter } from '../recipes/ports.ts';
import type {
  WebMountDocumentToken, WebMountElement, WebMountGoalCheckpoint,
  WebMountGoalDecision, WebMountGoalRequest, WebMountObservation,
} from './models.ts';

const GOAL_TOOL_NAME: string = 'wm_run_goal';
const CHECKPOINT_KEY: string = 'goal_v1';
const MAX_CANDIDATES: number = 32;
const FIXED_ACTIONS: string[] = ['click', 'scroll', 'select', 'type'];
const DEFAULT_MAX_DECISIONS: number = 12;
const DEFAULT_MAX_SECONDS: number = 60;
const DEFAULT_MAX_NO_PROGRESS: number = 3;
// 提交类意图永不进入候选(有界提示过滤;审批卡仍是最终人工闸)
const SUBMIT_HINT: RegExp = /submit|publish|delete|pay|login|sign[ -]?in|log[ -]?in|captcha|发表|发布|删除|支付|登录|登入|验证码/i;

export interface WebMountGoalCandidate {
  id: string;
  kind: string;
  description: string;
  part: UIMessagePartTool;
}

export interface WebMountGoalHostPorts {
  observe: (signal?: AbortSignalLike) => Promise<WebMountObservation>;
  // owner/bot 租约/enablement/Stations/文档就绪的一次性核验;失败即 handback
  assertUsable: () => Promise<void>;
  choose: (
    request: WebMountGoalRequest, observation: WebMountObservation,
    candidates: WebMountGoalCandidate[], signal?: AbortSignalLike,
  ) => Promise<WebMountGoalDecision>;
  now: () => number;
}

const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const objectOf = (value: JsonValue | undefined): JsonObject | null =>
  value !== null && typeof value === 'object' && !Array.isArray(value) ? value as JsonObject : null;
const stringOf = (value: JsonValue | undefined): string | null =>
  typeof value === 'string' && value.length > 0 ? value : null;

// FNV-1a 32bit(纯小哈希;goal subject 的请求身份,不是安全散列)
const fnv1aHex = (text: string): string => {
  let hash: number = 0x811c9dc5;
  for (let i: number = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = (hash * 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
};

export const webMountGoalRequestHash = (request: WebMountGoalRequest): string =>
  `goal-${fnv1aHex(JSON.stringify(request))}`;

// 已保存 checkpoint 内 request 对象的同口径身份(jev subject 用)
export const webMountGoalRequestHashJson = (request: JsonObject): string =>
  `goal-${fnv1aHex(JSON.stringify(request))}`;

// ===== 请求规范化(prepare;defaults/caps 12/60/3) =====

const clampInt = (value: JsonValue | undefined, fallback: number, min: number, max: number): number => {
  const num: number = typeof value === 'number' && Number.isFinite(value) ? Math.floor(value) : fallback;
  return Math.max(min, Math.min(num, max));
};

const normalizeGoalRequest = (input: JsonValue): WebMountGoalRequest => {
  const args: JsonObject | null = objectOf(input);
  if (args === null) throw new Error('wm_run_goal input must be a JSON object');
  const goal: string | null = stringOf(args['goal']);
  const completionText: string | null = stringOf(args['completion_text']);
  if (goal === null || goal.length > 500) throw new Error('goal must be a non-empty string up to 500 chars');
  if (completionText === null || completionText.length > 200) {
    throw new Error('completion_text must be a non-empty string up to 200 chars');
  }
  const allowedActions: string[] = [];
  const rawActions: JsonValue | undefined = args['allowed_actions'];
  if (Array.isArray(rawActions)) {
    for (const action of rawActions) {
      if (typeof action !== 'string' || !FIXED_ACTIONS.includes(action)) {
        throw new Error(`allowed_actions may only narrow the fixed actions: ${FIXED_ACTIONS.join(', ')}`);
      }
      if (!allowedActions.includes(action)) allowedActions.push(action);
    }
  }
  const draftValue: JsonValue | undefined = args['draft_value'];
  if (draftValue !== undefined && draftValue !== null
    && (typeof draftValue !== 'string' || (draftValue as string).length > 2000)) {
    throw new Error('draft_value must be a string up to 2000 chars');
  }
  const sessionId: string = stringOf(args['session_id']) ?? 'main';
  if (sessionId !== 'main') throw new Error('only the main WebMount session supports goals');
  const decisionSource: string = stringOf(args['decision_source']) ?? 'main_model';
  if (decisionSource !== 'main_model' && decisionSource !== 'jev') {
    throw new Error('decision_source must be main_model or jev');
  }
  return {
    sessionId: sessionId,
    goal: goal,
    completionText: completionText,
    allowedActions: allowedActions,
    draftValue: typeof draftValue === 'string' ? draftValue as string : null,
    maxActionDecisions: clampInt(args['max_action_decisions'], DEFAULT_MAX_DECISIONS, 1, DEFAULT_MAX_DECISIONS),
    maxSeconds: clampInt(args['max_seconds'], DEFAULT_MAX_SECONDS, 5, DEFAULT_MAX_SECONDS),
    maxNoProgress: clampInt(args['max_no_progress'], DEFAULT_MAX_NO_PROGRESS, 1, DEFAULT_MAX_NO_PROGRESS),
    decisionSource: decisionSource,
  };
};

// ===== checkpoint 编解码(严格;坏 checkpoint 不推进) =====

const withCheckpoint = (parent: UIMessagePartTool, checkpoint: WebMountGoalCheckpoint): UIMessagePartTool => ({
  ...parent, metadata: { ...parent.metadata, [CHECKPOINT_KEY]: copy(checkpoint) as unknown as JsonValue },
});

const pendingToolOf = (value: JsonValue | undefined): UIMessagePartTool | null => {
  const part: JsonObject | null = objectOf(value);
  if (part === null || part['type'] !== 'tool' || stringOf(part['toolCallId']) === null
    || stringOf(part['toolName']) === null || typeof part['input'] !== 'string'
    || !Array.isArray(part['output']) || objectOf(part['approvalState']) === null) return null;
  return part as unknown as UIMessagePartTool;
};

const requestOf = (value: JsonValue | undefined): WebMountGoalRequest | null => {
  const map: JsonObject | null = objectOf(value);
  if (map === null) return null;
  try {
    const request: WebMountGoalRequest = {
      sessionId: stringOf(map['sessionId']) ?? '',
      goal: stringOf(map['goal']) ?? '',
      completionText: stringOf(map['completionText']) ?? '',
      allowedActions: [],
      draftValue: typeof map['draftValue'] === 'string' ? map['draftValue'] as string : null,
      maxActionDecisions: clampInt(map['maxActionDecisions'], 0, 0, DEFAULT_MAX_DECISIONS),
      maxSeconds: clampInt(map['maxSeconds'], 0, 0, DEFAULT_MAX_SECONDS),
      maxNoProgress: clampInt(map['maxNoProgress'], 0, 0, DEFAULT_MAX_NO_PROGRESS),
      decisionSource: map['decisionSource'] === 'jev' ? 'jev' : 'main_model',
    };
    const rawActions: JsonValue | undefined = map['allowedActions'];
    if (!Array.isArray(rawActions)) return null;
    for (const action of rawActions) {
      if (typeof action !== 'string' || !FIXED_ACTIONS.includes(action)) return null;
      request.allowedActions.push(action);
    }
    if (request.sessionId.length === 0 || request.goal.length === 0 || request.completionText.length === 0
      || request.maxActionDecisions < 1 || request.maxSeconds < 1 || request.maxNoProgress < 1) return null;
    return request;
  } catch (_error) {
    return null;
  }
};

const observationOf = (value: JsonValue | undefined): WebMountObservation | null => {
  const map: JsonObject | null = objectOf(value);
  if (map === null) return null;
  const token: JsonObject | null = objectOf(map['token']);
  if (token === null || stringOf(token['sessionId']) === null || stringOf(token['documentId']) === null
    || typeof token['revision'] !== 'number' || typeof map['url'] !== 'string'
    || typeof map['title'] !== 'string' || typeof map['text'] !== 'string'
    || !Array.isArray(map['elements'])) return null;
  return map as unknown as WebMountObservation;
};

const checkpointOf = (parent: UIMessagePartTool): WebMountGoalCheckpoint => {
  const raw: JsonObject | null = objectOf(parent.metadata?.[CHECKPOINT_KEY]);
  if (raw === null || raw['version'] !== 1) throw new Error('WebMount goal has no pinned checkpoint');
  const request: WebMountGoalRequest | null = requestOf(raw['request']);
  const phase: JsonValue | undefined = raw['phase'];
  const decisions: JsonValue | undefined = raw['decisions'];
  const noProgress: JsonValue | undefined = raw['noProgress'];
  const startedAtMillis: JsonValue | undefined = raw['startedAtMillis'];
  const deadlineMillis: JsonValue | undefined = raw['deadlineMillis'];
  if (request === null || (phase !== 'ready' && phase !== 'awaiting_approval' && phase !== 'started' && phase !== 'finished')
    || typeof decisions !== 'number' || decisions < 0 || typeof noProgress !== 'number' || noProgress < 0
    || typeof startedAtMillis !== 'number' || typeof deadlineMillis !== 'number') {
    throw new Error('WebMount goal checkpoint is inconsistent');
  }
  const observation: JsonValue | undefined = raw['observation'];
  const pendingStep: JsonValue | undefined = raw['pendingStep'];
  const parsedObservation: WebMountObservation | null = observation === null ? null : observationOf(observation);
  const parsedStep: UIMessagePartTool | null = pendingStep === null ? null : pendingToolOf(pendingStep);
  if ((observation !== null && parsedObservation === null) || (pendingStep !== null && parsedStep === null)
    || ((phase === 'awaiting_approval' || phase === 'started') && parsedStep === null)) {
    throw new Error('WebMount goal checkpoint is missing its pinned step or observation');
  }
  return {
    version: 1, request: request, phase: phase,
    observation: parsedObservation, pendingStep: parsedStep,
    decisions: decisions, noProgress: noProgress,
    startedAtMillis: startedAtMillis, deadlineMillis: deadlineMillis,
  };
};

// ===== 候选生成(只来自当轮观察/请求;固定 ID;≤32) =====

const stepPart = (toolName: string, input: JsonObject): UIMessagePartTool => ({
  type: 'tool', toolCallId: '', toolName: toolName, input: JSON.stringify(input),
  output: [], approvalState: { type: 'auto' }, metadata: null,
});

const truncate = (text: string, max: number): string =>
  text.length > max ? `${text.substring(0, max)}…` : text;

export const buildWebMountGoalCandidates = (
  request: WebMountGoalRequest, observation: WebMountObservation,
): WebMountGoalCandidate[] => {
  const allowed: string[] = FIXED_ACTIONS.filter((action: string): boolean =>
    request.allowedActions.length === 0 || request.allowedActions.includes(action));
  const token: WebMountDocumentToken = observation.token;
  const candidates: WebMountGoalCandidate[] = [];
  const push = (kind: string, description: string, part: UIMessagePartTool): void => {
    if (candidates.length < MAX_CANDIDATES) {
      candidates.push({ id: `cand-${candidates.length}`, kind: kind, description: truncate(description, 120), part: part });
    }
  };
  const binding = (extra: JsonObject): JsonObject => ({
    ...extra, 'document_id': token.documentId, 'revision': token.revision,
  });
  for (const element of observation.elements) {
    if (candidates.length >= MAX_CANDIDATES) break;
    const label: string = element.text.length > 0 ? element.text : element.href ?? '';
    if (SUBMIT_HINT.test(label)) continue;
    if (allowed.includes('click') && element.tag === 'a' && element.href !== null && /^https?:/i.test(element.href)) {
      push('click', `open link "${truncate(label, 60)}"`,
        stepPart('wm_click', binding({ 'target': element.ref })));
    } else if (allowed.includes('click') && element.tag === 'button') {
      push('click', `press "${truncate(label, 60)}"`,
        stepPart('wm_click', binding({ 'target': element.ref })));
    } else if (allowed.includes('select') && element.tag === 'select') {
      for (const option of element.options) {
        push('select', `select "${truncate(option, 40)}"`,
          stepPart('wm_select', binding({ 'selector': `[data-wm-ref="${element.ref}"]`, 'value': option })));
      }
    } else if (allowed.includes('type') && request.draftValue !== null
      && (element.tag === 'input' || element.tag === 'textarea')
      && element.inputType !== 'password' && element.text !== '***') {
      push('type', `type draft into "${truncate(label, 40)}"`,
        stepPart('wm_type', binding({ 'selector': `[data-wm-ref="${element.ref}"]`, 'text': request.draftValue })));
    }
  }
  if (allowed.includes('scroll')) {
    push('scroll', 'scroll down', stepPart('wm_scroll', binding({ 'dy': 600 })));
    push('scroll', 'scroll up', stepPart('wm_scroll', binding({ 'dy': -600 })));
  }
  return candidates;
};

// ===== 终态 =====

type GoalTerminalStatus = 'completed' | 'handback' | 'cancelled' | 'failed' | 'outcome_unknown';

const finishGoal = async (
  parent: UIMessagePartTool, checkpoint: WebMountGoalCheckpoint, port: RecipeExecutionPort,
  status: GoalTerminalStatus, message: string, observation: WebMountObservation | null = null,
): Promise<UIMessagePartTool> => {
  const finished: WebMountGoalCheckpoint = { ...checkpoint, phase: 'finished' };
  const output: JsonObject = {
    'status': status,
    'goal': truncate(checkpoint.request.goal, 200),
    'message': truncate(message, 300),
    'decisions': checkpoint.decisions,
    'final_url': observation?.url ?? checkpoint.observation?.url ?? null,
    'final_title': observation?.title ?? checkpoint.observation?.title ?? null,
  };
  const part: UIMessagePartTool = {
    ...withCheckpoint({ ...parent, approvalState: { type: 'auto' } }, finished),
    output: [{ type: 'text', text: JSON.stringify(output), metadata: null }],
  };
  await port.saveParent(part);
  return part;
};

const goalCompleted = (observation: WebMountObservation, completionText: string): boolean => {
  const needle: string = completionText.toLowerCase();
  return observation.url.toLowerCase().includes(needle)
    || observation.title.toLowerCase().includes(needle)
    || observation.text.toLowerCase().includes(needle);
};

const signatureOf = (observation: WebMountObservation): string => {
  const text: string = `${observation.url}\n${observation.title}\n${observation.text}`;
  let hash: number = 0x811c9dc5;
  for (let i: number = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = (hash * 0x01000193) >>> 0;
  }
  return `${observation.token.documentId}:${hash.toString(16)}`;
};

const messageOf = (error: unknown): string =>
  error instanceof Error ? error.message : String(error);

const abortedOf = (signal?: AbortSignalLike): boolean => signal?.aborted === true;

// ===== 模型工具(wrapper;直接执行一律拒绝,必须经编排宿主) =====

export const createWebMountGoalTool = (): AgentTool => makeAgentTool({
  name: GOAL_TOOL_NAME,
  description: 'Run a bounded multi-step goal in the WebMount browser: observe the page, choose one '
    + 'of the finite observed candidates per step (link/button clicks, scroll, select options, '
    + 'draft text into non-secret fields), and stop only when a fresh observation matches the '
    + 'completion text or a real budget/handback outcome is reached. No submit/publish/delete/'
    + 'pay/login/captcha actions, no arbitrary evaluation, no DONE shortcut.',
  parameters: (): InputSchemaObj | null => makeInputSchemaObj({
    'session_id': { type: 'string', description: 'WebMount session id (main).' },
    'goal': { type: 'string', description: 'What to achieve (<= 500 chars).' },
    'completion_text': { type: 'string', description: 'Text that must appear in the URL, title or visible page text (<= 200 chars).' },
    'allowed_actions': { type: 'string', description: 'Optional comma-separated narrowing of click,scroll,select,type.' },
    'draft_value': { type: 'string', description: 'Optional frozen draft text for input candidates (<= 2000 chars).' },
    'max_action_decisions': { type: 'number', description: 'Max action decisions (default 12, cap 12).' },
    'max_seconds': { type: 'number', description: 'Time budget in seconds (default 60, cap 60).' },
    'max_no_progress': { type: 'number', description: 'Stop after this many rounds without observable progress (default 3, cap 3).' },
    'decision_source': { type: 'string', description: 'Decision port: main_model (default) or jev.' },
  } as JsonObject, ['goal', 'completion_text']),
  execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
    throw new Error('adapterRequired: wm_run_goal must run inside the ordered goal host loop');
  },
});

// allowed_actions 也接受逗号分隔(模型友好的单字符串形态)
const parseAllowedActions = (args: JsonObject): void => {
  const raw: JsonValue | undefined = args['allowed_actions'];
  if (typeof raw === 'string') {
    args['allowed_actions'] = raw.split(',').map((item: string): string => item.trim())
      .filter((item: string): boolean => item.length > 0);
  }
};

// ===== goal adapter =====

export const createWebMountGoalAdapter = (deps: WebMountGoalHostPorts): RecipeLoopAdapter => ({
  supports: (toolName: string): boolean => toolName === GOAL_TOOL_NAME,
  prepare: async (parent: UIMessagePartTool, _primitives: AgentTool[]): Promise<UIMessagePartTool> => {
    if (parent.metadata?.[CHECKPOINT_KEY] !== undefined) {
      checkpointOf(parent);
      return copy(parent);
    }
    if (parent.toolName !== GOAL_TOOL_NAME) throw new Error('WebMount goal adapter only owns wm_run_goal');
    let input: JsonValue;
    try {
      input = JSON.parse(parent.input.trim().length === 0 ? '{}' : parent.input) as JsonValue;
    } catch (_error) {
      throw new Error('wm_run_goal input is not valid JSON');
    }
    const args: JsonObject | null = objectOf(input);
    if (args === null) throw new Error('wm_run_goal input must be a JSON object');
    parseAllowedActions(args);
    const request: WebMountGoalRequest = normalizeGoalRequest(args);
    const now: number = deps.now();
    const checkpoint: WebMountGoalCheckpoint = {
      version: 1, request: request, phase: 'ready', observation: null, pendingStep: null,
      decisions: 0, noProgress: 0, startedAtMillis: now, deadlineMillis: now + request.maxSeconds * 1000,
    };
    return withCheckpoint(parent, checkpoint);
  },
  advance: async (parent: UIMessagePartTool, port: RecipeExecutionPort,
    signal?: AbortSignalLike): Promise<UIMessagePartTool> => {
    let checkpoint: WebMountGoalCheckpoint = checkpointOf(parent);
    if (parent.output.length > 0 || checkpoint.phase === 'finished') return parent;
    if (checkpoint.phase === 'started') {
      // started 后没有已保存结果:绝不重放(recoverInterrupted 处理冷恢复;同代保存失败经 saveParent 抛出)
      return finishGoal(parent, checkpoint, port, 'outcome_unknown',
        'A started goal action has no saved result and will not be replayed.');
    }
    if (checkpoint.phase === 'awaiting_approval') {
      const pinned: UIMessagePartTool = checkpoint.pendingStep as UIMessagePartTool;
      if (parent.approvalState.type === 'pending') return parent;
      if (parent.approvalState.type === 'denied') {
        return finishGoal(parent, checkpoint, port, 'failed', 'The goal action was denied by the user.');
      }
      if (parent.approvalState.type === 'answered') {
        return finishGoal(parent, checkpoint, port, 'failed', 'A goal action cannot be answered; approve or deny it.');
      }
      // approved:等待期间文档若已变化,丢弃旧 child、清掉这次批准,重新观察与审批
      const bound: JsonObject | null = objectOf(pinned.metadata?.['wm_document_v1']);
      let fresh: WebMountObservation;
      try {
        await deps.assertUsable();
        fresh = await deps.observe(signal);
      } catch (error) {
        if (abortedOf(signal)) {
          return finishGoal(parent, checkpoint, port, 'cancelled', 'Goal execution was cancelled.');
        }
        return finishGoal(parent, checkpoint, port, 'handback',
          `Goal lost its page or capability: ${messageOf(error)}`);
      }
      if (bound === null || bound['documentId'] !== fresh.token.documentId
        || bound['revision'] !== fresh.token.revision) {
        checkpoint = { ...checkpoint, phase: 'ready', pendingStep: null, observation: fresh };
        parent = withCheckpoint({ ...parent, approvalState: { type: 'auto' } }, checkpoint);
        await port.saveParent(parent);
        // 落入 ready 主循环,全新候选与全新审批
      } else {
        const approvedStep: UIMessagePartTool = { ...pinned, approvalState: { type: 'approved' } };
        const approvedDef: AgentTool | null = port.primitive(approvedStep.toolName);
        if (approvedDef === null) {
          return finishGoal(parent, checkpoint, port, 'handback',
            `Primitive ${approvedStep.toolName} is unavailable in the current tool scope.`);
        }
        checkpoint = { ...checkpoint, phase: 'started', pendingStep: approvedStep };
        parent = withCheckpoint({ ...parent, approvalState: { type: 'auto' } }, checkpoint);
        await port.saveParent(parent);
        const result: UIMessagePartTool | null = await port.dispatch(approvedStep, approvedDef, signal);
        if (result === null) {
          if (abortedOf(signal)) {
            return finishGoal(parent, checkpoint, port, 'cancelled', 'Goal execution was cancelled.');
          }
          return finishGoal(parent, checkpoint, port, 'outcome_unknown',
            'A started goal action returned no result and will not be replayed.');
        }
        checkpoint = { ...checkpoint, phase: 'ready', pendingStep: result };
        parent = withCheckpoint(parent, checkpoint);
        await port.saveParent(parent);
      }
    }
    // ready 主循环:观察 → 完成核验 → 候选 → 决策 → 审批/执行 → 再观察
    while (true) {
      if (abortedOf(signal)) {
        return finishGoal(parent, checkpoint, port, 'cancelled', 'Goal execution was cancelled.');
      }
      const now: number = deps.now();
      if (now >= checkpoint.deadlineMillis) {
        return finishGoal(parent, checkpoint, port, 'failed', 'Goal exceeded its time budget.');
      }
      if (checkpoint.decisions >= checkpoint.request.maxActionDecisions) {
        return finishGoal(parent, checkpoint, port, 'failed', 'Goal exhausted its action decision budget.');
      }
      let observation: WebMountObservation;
      try {
        await deps.assertUsable();
        observation = await deps.observe(signal);
      } catch (error) {
        if (abortedOf(signal)) {
          return finishGoal(parent, checkpoint, port, 'cancelled', 'Goal execution was cancelled.');
        }
        return finishGoal(parent, checkpoint, port, 'handback',
          `Goal lost its page or capability: ${messageOf(error)}`);
      }
      if (goalCompleted(observation, checkpoint.request.completionText)) {
        return finishGoal(parent, { ...checkpoint, observation: observation }, port, 'completed',
          'Completion text matched a fresh observation.', observation);
      }
      const progressed: boolean = checkpoint.observation === null
        || signatureOf(observation) !== signatureOf(checkpoint.observation);
      checkpoint = {
        ...checkpoint, observation: observation,
        noProgress: progressed ? 0 : checkpoint.noProgress + 1,
      };
      if (checkpoint.noProgress >= checkpoint.request.maxNoProgress) {
        return finishGoal(parent, checkpoint, port, 'failed', 'Goal made no observable progress.');
      }
      const candidates: WebMountGoalCandidate[] = buildWebMountGoalCandidates(checkpoint.request, observation);
      if (candidates.length === 0) {
        // 空候选按无进展处理(预算守卫会终止);先落盘 observation/noProgress
        parent = withCheckpoint(parent, checkpoint);
        await port.saveParent(parent);
        continue;
      }
      let decision: WebMountGoalDecision;
      try {
        decision = await deps.choose(checkpoint.request, observation, candidates, signal);
      } catch (error) {
        if (abortedOf(signal)) {
          return finishGoal(parent, checkpoint, port, 'cancelled', 'Goal execution was cancelled.');
        }
        return finishGoal(parent, checkpoint, port, 'handback', `Decision unavailable: ${messageOf(error)}`);
      }
      const candidate: WebMountGoalCandidate | undefined = decision.kind === 'action' && decision.candidateId !== null
        ? candidates.find((item: WebMountGoalCandidate): boolean => item.id === decision.candidateId)
        : undefined;
      if (candidate === undefined) {
        return finishGoal(parent, checkpoint, port, 'handback',
          decision.kind === 'handback' && decision.reason.length > 0
            ? decision.reason : 'Decision did not choose a valid candidate.');
      }
      checkpoint = { ...checkpoint, decisions: checkpoint.decisions + 1 };
      let step: UIMessagePartTool = {
        ...candidate.part,
        toolCallId: `goal-${checkpoint.startedAtMillis}-${checkpoint.decisions}`,
        metadata: {
          'wm_document_v1': {
            'documentId': observation.token.documentId, 'revision': observation.token.revision,
          },
        },
      };
      try {
        const captured: JsonObject | null = port.capture(step);
        if (captured !== null) step = { ...step, metadata: { ...step.metadata, ...captured } };
      } catch (error) {
        return finishGoal(parent, checkpoint, port, 'failed', `Goal action capture failed: ${messageOf(error)}`);
      }
      const definition: AgentTool | null = port.primitive(step.toolName);
      if (definition === null) {
        return finishGoal(parent, checkpoint, port, 'handback',
          `Primitive ${step.toolName} is unavailable in the current tool scope.`);
      }
      const permission = await port.decide(step, definition, signal);
      if (abortedOf(signal)) return finishGoal(parent, checkpoint, port, 'cancelled', 'Goal execution was cancelled.');
      if (permission.action === 'deny') {
        return finishGoal(parent, checkpoint, port, 'failed', permission.reason);
      }
      if (permission.action === 'ask') {
        checkpoint = {
          ...checkpoint, phase: 'awaiting_approval',
          pendingStep: { ...step, approvalState: { type: 'pending' },
            metadata: { ...step.metadata, permission_trace: permissionDecisionTraceToJson(permission.trace) } },
        };
        parent = withCheckpoint({ ...parent, approvalState: { type: 'pending' } }, checkpoint);
        await port.saveParent(parent);
        return parent;
      }
      checkpoint = { ...checkpoint, phase: 'started', pendingStep: step };
      parent = withCheckpoint(parent, checkpoint);
      await port.saveParent(parent);
      const result: UIMessagePartTool | null = await port.dispatch(step, definition, signal);
      if (result === null) {
        if (abortedOf(signal)) {
          return finishGoal(parent, checkpoint, port, 'cancelled', 'Goal execution was cancelled.');
        }
        return finishGoal(parent, checkpoint, port, 'outcome_unknown',
          'A started goal action returned no result and will not be replayed.');
      }
      checkpoint = { ...checkpoint, phase: 'ready', pendingStep: result };
      parent = withCheckpoint(parent, checkpoint);
      // 结果保存失败必须保留 durable started,不得伪装安全重试
      await port.saveParent(parent);
    }
  },
});

// ===== 审批/恢复接缝 =====

// 等待人工时的 pinned child(供审批投影/subject 构建;非 awaiting 一律 null)
export const webMountGoalPendingPart = (parent: UIMessagePartTool): UIMessagePartTool | null => {
  const checkpoint: WebMountGoalCheckpoint | null = ((): WebMountGoalCheckpoint | null => {
    try { return checkpointOf(parent); } catch (_error) { return null; }
  })();
  if (checkpoint === null || checkpoint.phase !== 'awaiting_approval' || checkpoint.pendingStep === null) return null;
  const step: UIMessagePartTool = checkpoint.pendingStep;
  return step.approvalState.type === 'pending' && step.output.length === 0 ? step : null;
};

// 冷恢复:started 的 goal 动作在宿主重启后无结果,标 outcome_unknown,不重放
export const recoverInterruptedWebMountGoals = (conversation: Conversation): Conversation => {
  let changed: boolean = false;
  const messageNodes: MessageNode[] = conversation.messageNodes.map((node: MessageNode): MessageNode => {
    let nodeChanged: boolean = false;
    const messages: UIMessage[] = node.messages.map((message: UIMessage): UIMessage => {
      let messageChanged: boolean = false;
      const parts: UIMessagePart[] = message.parts.map((part: UIMessagePart): UIMessagePart => {
        if (part.type !== 'tool' || part.toolName !== GOAL_TOOL_NAME || part.output.length > 0) return part;
        const raw: JsonObject | null = objectOf(part.metadata?.[CHECKPOINT_KEY]);
        if (raw === null || raw['phase'] !== 'started') return part;
        let checkpoint: WebMountGoalCheckpoint;
        try { checkpoint = checkpointOf(part); } catch (_error) { return part; }
        if (checkpoint.phase !== 'started') return part;
        messageChanged = true;
        const finished: WebMountGoalCheckpoint = { ...checkpoint, phase: 'finished' };
        const output: JsonObject = {
          'status': 'outcome_unknown',
          'goal': truncate(checkpoint.request.goal, 200),
          'message': 'A started goal action has no saved result after the host restarted and will not be replayed.',
          'decisions': checkpoint.decisions,
          'final_url': checkpoint.observation?.url ?? null,
          'final_title': checkpoint.observation?.title ?? null,
        };
        return {
          ...withCheckpoint({ ...part, approvalState: { type: 'auto' } }, finished),
          output: [{ type: 'text', text: JSON.stringify(output), metadata: null } as UIMessagePartText],
        };
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
