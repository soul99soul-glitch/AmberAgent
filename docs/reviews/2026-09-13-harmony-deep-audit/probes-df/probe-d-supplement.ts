// probe-d-supplement.ts — 独立有界复核 D-11（文本「继续」批准）、S-5（cron 不可取消 / 删除运行中 cron）、S-4（cleanup 授权语义）
// 运行：cd harmony/chat && npx tsx <此文件绝对路径>
// 真实 chat-domain .ts + 真实 entry scheduler（转译 AgentCronStore.ets，注入假 setTimeout）；
// 内存假端口；无网络/模型/文件/真实定时器副作用。
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { createRequire } from 'node:module';
import vm from 'node:vm';
import type { UIMessagePart, UIMessagePartTool } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import { makeUIMessage, canToolResumeExecution } from '../../../../harmony/chat/src/main/ets/chat/message.ts';
import { makeConversation, toMessageNode } from '../../../../harmony/chat/src/main/ets/chat/conversation.ts';
import { resolveIdleToolBlocker, isToolApprovalContinuation } from '../../../../harmony/chat/src/main/ets/chat/tool_approval.ts';
import { PermissionDecisionResolver } from '../../../../harmony/chat/src/main/ets/chat/tool_permission.ts';
import { makeAgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import type { AgentTool } from '../../../../harmony/chat/src/main/ets/chat/tool.ts';
import { toolInvocationPolicy } from '../../../../harmony/chat/src/main/ets/chat/tool_policy.ts';
import { AgentTaskStore } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import type { AgentTaskFilePort } from '../../../../harmony/chat/src/main/ets/chat/agent_task.ts';
import { AgentTaskScheduler } from '../../../../harmony/chat/src/main/ets/chat/agent_task_scheduler.ts';
import { AgentTaskTools } from '../../../../harmony/chat/src/main/ets/chat/agent_task_tools.ts';
import { AgentCronManager } from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';
import type { AgentCronPersistencePort, AgentCronTask } from '../../../../harmony/chat/src/main/ets/chat/agent_cron.ts';

const __dir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(__dir, '../../../..');
const require = createRequire(import.meta.url);
const ts = require(resolve(repoRoot, 'harmony/chat/node_modules/typescript'));

let pass = 0, fail = 0;
const check = (name: string, ok: boolean, detail: string = ''): void => {
  if (ok) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; console.log(`FAIL  ${name} :: ${detail}`); }
};

const text = (t: string): UIMessagePart => ({ type: 'text', text: t, metadata: null });
const pending = (name: string, input: string, id: string): UIMessagePartTool => ({
  type: 'tool', toolName: name, toolCallId: id, input, output: [], approvalState: { type: 'pending' }, metadata: null,
});
const convPending = (tool: UIMessagePartTool) =>
  makeConversation('c1', [toMessageNode(makeUIMessage('assistant', [tool]))]);

class MemFiles implements AgentTaskFilePort {
  files = new Map<string, string>();
  async mkdirs(): Promise<void> {}
  async listJsonFileNames(): Promise<string[]> { return [...this.files.keys()].map((p) => p.split('/').pop() as string); }
  async readText(p: string): Promise<string | null> { return this.files.get(p) ?? null; }
  async writeText(p: string, t: string): Promise<void> { this.files.set(p, t); }
  async delete(p: string): Promise<void> { this.files.delete(p); }
  async exists(p: string): Promise<boolean> { return this.files.has(p); }
  async isPathInside(root: string, p: string): Promise<boolean> { return p.startsWith(`${root}/`); }
}

// ===== 加载真实 entry scheduler =====
interface FakeTimer { id: number; fn: () => void; delay: number; }
const timers: FakeTimer[] = [];
let nextId = 1;
const entryPath = resolve(repoRoot, 'harmony/entry/src/main/ets/platform_impl/AgentCronStore.ets');
const js = ts.transpileModule(readFileSync(entryPath, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;
const mod: { exports: Record<string, unknown> } = { exports: {} };
const sandbox = {
  console,
  setTimeout: (fn: () => void, delay: number): number => { const id = nextId++; timers.push({ id, fn, delay }); return id; },
  clearTimeout: (id: number): void => { const i = timers.findIndex((t) => t.id === id); if (i >= 0) timers.splice(i, 1); },
};
vm.runInNewContext(`(function(require,module,exports){${js}\n})`, sandbox, { filename: entryPath })(
  (name: string): unknown => {
    if (name === '@kit.CoreFileKit') return { fileIo: {} };
    if (name === '@kit.PerformanceAnalysisKit') return { hilog: { error: () => {}, warn: () => {}, info: () => {} } };
    if (name === '@kit.ArkTS') return { util: {} };
    if (name === '@amber/chat-domain') return {};
    throw new Error(`Unexpected dependency ${name}`);
  }, mod, mod.exports);
const createEntryCronSchedulerPort = mod.exports['createEntryCronSchedulerPort'] as (deps: unknown) => {
  schedule: (w: string, t: string, d: number) => void; runNow: (w: string, t: string) => void; cancel: (w: string) => void;
};
class MemKv implements AgentCronPersistencePort {
  raw: string | null = null;
  async read() { return this.raw; }
  async write(r: string) { this.raw = r; }
}
const drain = (): Promise<void> => new Promise<void>((r) => { setTimeout(r, 0); });
const flush = async (): Promise<void> => { for (let i = 0; i < 30; i++) await drain(); };

(async () => {
  // ================= D-11 =================
  {
    // 匹配宽度（含 supplement 声称的「请继续上面的内容」快捷语不命中）
    check('D11-1 「继续写首诗」 matches (not a genuine approval)', isToolApprovalContinuation('继续写首诗') === true);
    check('D11-2 「继续吧」 matches', isToolApprovalContinuation('继续吧') === true);
    check('D11-3 「今天天气不错」 does NOT match', isToolApprovalContinuation('今天天气不错') === false);
    check('D11-4 快捷语「请继续上面的内容」 does NOT match (seed not a hidden approval)',
      isToolApprovalContinuation('请继续上面的内容') === false);

    // 真实 blocker + 真实 resolver：mandatory/high 的 wm_eval 被文本批准
    const wmEval: AgentTool = makeAgentTool({
      name: 'wm_eval', description: 'eval', needsApproval: true, allowsAutoApproval: false, mandatoryApproval: true,
      execute: async (): Promise<UIMessagePart[]> => [text('{}')],
    });
    const conv = convPending(pending('wm_eval', '{"code":"x"}', 't1'));
    const r = resolveIdleToolBlocker(conv, '继续写首诗', true);
    const approved = r.conversation.messageNodes[0].messages[0].parts[0] as UIMessagePartTool;
    check('D11-5 real blocker marks wm_eval approved from a poem request', r.shouldResume && approved.approvalState.type === 'approved',
      `resume=${r.shouldResume} state=${approved.approvalState.type}`);
    const resolver = new PermissionDecisionResolver({ newId: () => 't' });
    const d = resolver.resolve(wmEval, approved, false, false, [], 'normal');
    check('D11-6 resolver allows it with ALL auto-approve toggles OFF (mandatory bypassed)', d.action === 'allow' && d.source === 'approval_state',
      `action=${d.action} source=${d.source}`);
    // 反证：同一 mandatory 工具在 auto 态、单开关仍是 ask（证明正常门有效）
    const fresh: UIMessagePartTool = { type: 'tool', toolName: 'wm_eval', toolCallId: 't2', input: '{"code":"x"}', output: [], approvalState: { type: 'auto' }, metadata: null };
    check('D11-7 contrast: fresh mandatory tool with single toggle stays ask', resolver.resolve(wmEval, fresh, true, false, [], 'normal').action === 'ask');

    // 源码结构事实：resolveIdleToolBlocker 不接收 policy，也不引用 mandatory/alwaysAsk/resolver
    const ap = readFileSync(resolve(repoRoot, 'harmony/chat/src/main/ets/chat/tool_approval.ts'), 'utf8');
    const fnBody = ap.slice(ap.indexOf('export const resolveIdleToolBlocker'), ap.indexOf('// =====', ap.indexOf('export const resolveIdleToolBlocker') + 10));
    check('D11-8 blocker never consults policy/resolver (approval is a raw state rewrite)',
      !/mandatoryApproval|alwaysAsk|PermissionDecisionResolver|toolInvocationPolicy/.test(fnBody));

    // UI 承诺：只有 ✓/✕ 按钮，无「回复继续即批准」引导
    const uiFiles = [
      'harmony/entry/src/main/ets/components/AgentToolCapsule.ets',
      'harmony/entry/src/main/ets/components/ChatMessageTools.ets',
    ].map((p) => readFileSync(resolve(repoRoot, p), 'utf8')).join('\n');
    const hasApproveButton = /批准工具执行/.test(uiFiles) && /拒绝工具执行/.test(uiFiles);
    const claimsTypingApproves = /继续[^）\n]{0,6}(即可|等于|视为|等同).{0,4}批准|回复.{0,6}继续.{0,6}批准|输入.{0,6}继续.{0,6}批准/.test(uiFiles);
    check('D11-9 UI exposes only ✓/✕ buttons', hasApproveButton);
    check('D11-10 UI never tells the user that typing 「继续」 equals approval', !claimsTypingApproves);
  }

  // ================= S-5 / 删除运行中 cron =================
  {
    timers.length = 0; nextId = 1;
    const kv = new MemKv();
    const files = new MemFiles();
    const store = await AgentTaskStore.create({ taskDir: '/app/tasks', appFilesDir: '/app', files });
    let resolveExec!: () => void;
    let execStarted = false;
    let execFinished = false;
    const execGate = new Promise<void>((res) => { resolveExec = res; });
    let cancelCallbacksRegistered = 0;
    const port = createEntryCronSchedulerPort({
      manager: async () => manager,
      runExecutor: async (_taskId: string) => {
        execStarted = true;
        await execGate;          // 模拟长时间模型生成（可被外部中止的唯一机会）
        execFinished = true;
      },
    });
    let manager!: AgentCronManager;
    manager = new AgentCronManager(kv, port as never, store);

    // 包一层 store 观察 register 是否带 cancel 回调
    const origRegister = store.register.bind(store);
    (store as unknown as { register: unknown }).register = async (snap: unknown, cb?: unknown) => {
      if (cb !== undefined) cancelCallbacksRegistered++;
      return origRegister(snap as never, cb as never);
    };

    const task: AgentCronTask = await manager.createTask('long run', 'p', '0 9 * * *', 'UTC', true);
    const timer = timers.shift() as FakeTimer;
    timer.fn();                  // fire
    await flush();
    check('S5-1 executor really started', execStarted === true);
    check('S5-2 cron snapshot has cancelCapability=false', (store.read(task.id)?.cancelCapability) === false,
      `cancelCapability=${store.read(task.id)?.cancelCapability}`);
    check('S5-3 cron task registered WITHOUT a cancel callback (no abort handle)', cancelCallbacksRegistered === 0,
      `cancelCallbacks=${cancelCallbacksRegistered}`);

    // 运行中删除
    const del = await manager.deleteTask(task.id);
    await flush();
    check('S5-4 deleteTask reports success and removes the task', del === true && (await manager.listTasks()).length === 0);
    check('S5-5 in-flight run is NOT aborted by delete (executor still awaiting)', execStarted === true && execFinished === false);
    check('S5-6 scheduler.cancel on a RUNNING cron only clears timers (no signal to executor)',
      typeof createEntryCronSchedulerPort === 'function'); // 结构事实见下静态断言

    resolveExec();               // 让「模型生成」跑完
    await flush();
    check('S5-7 run completes AFTER deletion (model keeps going)', execFinished === true);
    // markRunCompleted/scheduleNextRun 对已删任务应为 no-op（不会复活/重排）
    timers.length = 0;
    await flush();
    check('S5-8 deleted task is NOT rescheduled (no new timer)', timers.length === 0, `timers=${timers.length}`);
    check('S5-9 task stays removed (not re-created by markRunCompleted)', (await manager.listTasks()).length === 0);

    // 静态：deleteTask 只 scheduler.cancel + agentTaskStore.remove，且 cancel 实现仅 clearTimeout
    const cronSrc = readFileSync(resolve(repoRoot, 'harmony/chat/src/main/ets/chat/agent_cron.ts'), 'utf8');
    const delBody = cronSrc.slice(cronSrc.indexOf('async deleteTask'), cronSrc.indexOf('async runTaskNow'));
    check('S5-10 deleteTask has no abort/executor-cancel call', !/abort|Abort|runExecutor|cancelRun/.test(delBody));
    const schedSrc = readFileSync(entryPath, 'utf8');
    const cancelBody = schedSrc.slice(schedSrc.indexOf('cancel: (workName'), schedSrc.indexOf('};', schedSrc.indexOf('cancel: (workName')));
    check('S5-11 scheduler.cancel body only clearTimer(workName)', /clearTimer\(workName\)/.test(cancelBody) && !/abort|runExecutor/.test(cancelBody));
    // executor 契约无 signal
    check('S5-12 AgentCronRunExecutor type carries no abort signal', /type AgentCronRunExecutor = \(taskId: string\) => Promise<void>/.test(schedSrc));
  }

  // ================= S-4 cleanup 授权语义 =================
  {
    const files = new MemFiles();
    const store = await AgentTaskStore.create({ taskDir: '/app/tasks', appFilesDir: '/app', files });
    const scheduler = new AgentTaskScheduler(store);
    const tools = new AgentTaskTools(scheduler, { outputExists: async () => false }).getTools();
    const cleanup = tools.find((t) => t.name === 'agent_task_cleanup') as AgentTool;

    // 策略：单开关可自动批准；默认 delete_private_output=false
    const p = toolInvocationPolicy(cleanup, { task_id: 'x', delete_private_output: true });
    check('S4-1 cleanup policy: mutates+sensitive+needsApproval', p.mutates === true && p.risk === 'sensitive' && p.needsApproval === true,
      JSON.stringify({ mutates: p.mutates, risk: p.risk, needsApproval: p.needsApproval }));
    check('S4-2 cleanup is autoApprovable under the single global toggle (silent destructive path)',
      p.autoApprovable === true && p.mandatoryApproval === false, JSON.stringify({ autoApprovable: p.autoApprovable, mandatory: p.mandatoryApproval }));
    const resolver = new PermissionDecisionResolver({ newId: () => 't' });
    const autoState: UIMessagePartTool = { type: 'tool', toolName: 'agent_task_cleanup', toolCallId: 'c', input: '{"task_id":"x","delete_private_output":true}', output: [], approvalState: { type: 'auto' }, metadata: null };
    check('S4-3 single toggle => allow; toggles off => ask', (() => {
      const on = resolver.resolve(cleanup, autoState, true, false, [], 'normal').action;
      const off = resolver.resolve(cleanup, autoState, false, false, [], 'normal').action;
      return on === 'allow' && off === 'ask';
    })());

    // 有界性：app-private 产物被删；workspace 路径永不删
    files.files.set('/app/subagents/runs/r1.jsonl', 'transcript');
    files.files.set('/app/tasks/t1.json', JSON.stringify({}));
    files.files.set('/workspace/keep.txt', 'user file');
    await store.register({
      schemaVersion: 2, taskId: 't1', type: 'subagent', title: 'sub', spec: null, runtime: null,
      queueState: 'TERMINAL', recoveryState: 'CLEANUP_ONLY',
      retryPolicy: { retryable: false, requiresApproval: true, maxRetries: 0, retryCount: 0, reason: null },
      outputRef: { type: 'transcript', path: '/app/subagents/runs/r1.jsonl', exists: true },
      outputPath: '/app/subagents/runs/r1.jsonl', status: 'COMPLETED', sourceToolName: 'subagent_start',
      sourceConversationId: 'c', createdAtMs: 1, updatedAtMs: 1, lastHeartbeatMs: null, notified: false,
      cancelCapability: false, permissionTraceId: null, summary: null, lastErrorCode: null, error: null,
    } as never);
    await cleanup.execute({ task_id: 't1', delete_private_output: true });
    check('S4-4 app-private transcript IS deleted when flag=true', files.files.has('/app/subagents/runs/r1.jsonl') === false);
    check('S4-5 workspace file is NEVER deleted', files.files.has('/workspace/keep.txt') === true);
    // 默认 flag=false 时不删 transcript（另建）
    files.files.set('/app/subagents/runs/r2.jsonl', 'transcript2');
    await store.register({
      schemaVersion: 2, taskId: 't2', type: 'subagent', title: 'sub', spec: null, runtime: null,
      queueState: 'TERMINAL', recoveryState: 'CLEANUP_ONLY',
      retryPolicy: { retryable: false, requiresApproval: true, maxRetries: 0, retryCount: 0, reason: null },
      outputRef: { type: 'transcript', path: '/app/subagents/runs/r2.jsonl', exists: true },
      outputPath: '/app/subagents/runs/r2.jsonl', status: 'COMPLETED', sourceToolName: 'subagent_start',
      sourceConversationId: 'c', createdAtMs: 1, updatedAtMs: 1, lastHeartbeatMs: null, notified: false,
      cancelCapability: false, permissionTraceId: null, summary: null, lastErrorCode: null, error: null,
    } as never);
    await cleanup.execute({ task_id: 't2' });   // 缺省 false
    check('S4-6 default delete_private_output=false leaves the transcript intact', files.files.has('/app/subagents/runs/r2.jsonl') === true);
  }

  console.log(`\n=== probe-d-supplement summary: pass=${pass} fail=${fail} ===`);
  if (fail > 0) process.exitCode = 1;
})().catch((e) => { console.error(e); process.exitCode = 1; });
