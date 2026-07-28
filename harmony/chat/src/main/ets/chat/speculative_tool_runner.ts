// speculative_tool_runner — 推测执行(D-063)
//
// Android 基准: feature/runtime/SpeculativeToolRunner.kt 全文 146 行
// 语义:流式期间对「只读且 eligible」的工具调用提前并发执行;流终模型
//   确认调用(name+input 一致)时复用结果,否则丢弃
// 设置默认(PreferencesStore.kt:226-229):enabled=false/maxConcurrentTools=4
// 裁剪/偏差登记:
//   - CoroutineScope.async/Deferred → Promise + 标志位模型:JS Promise
//     不可取消,cancel 仅置 cancelled 标志(执行继续至完结,但完成回调
//     不覆盖已 discard/cancel 状态,结果永不复用 — 可观察行为等价);
//     Deferred.isCompleted → settled 标志;isCancelled → cancelled 标志;
//     CancellationException → cancel 时同步置 'cancelled'/reusableResults
//     侧置 'discarded'(Android 两处分置同语义)
//   - ConcurrentHashMap.computeIfPresent → 单线程 Map 条件更新(语义等价)

import type { UIMessagePart, UIMessagePartTool } from './message.ts';
import { isToolExecuted } from './message.ts';
import type { AgentTool } from './tool.ts';
import type { ToolInvocationContext } from './tool_permission.ts';
import { toolInvocationPolicyFromText } from './tool_policy.ts';
import type { AgentToolDispatcher } from './tool_dispatcher.ts';

// ===== SpeculativeToolStatus(:13-19;小写序列化,鸿蒙枚举风格) =====

export type SpeculativeToolStatus =
  'pending' | 'completed' | 'failed' | 'cancelled' | 'discarded';

// SpeculativeToolState(:21-28)
export interface SpeculativeToolState {
  toolCallId: string;
  toolName: string;
  input: string;
  status: SpeculativeToolStatus;
  result: UIMessagePartTool | null;
  error: string | null;
}

export interface SpeculativeToolRunnerDeps {
  dispatcher: AgentToolDispatcher;
  // PreferencesStore.kt:228 默认 4
  maxConcurrentTools?: number;
  invocationContext?: ToolInvocationContext;
}

export interface SpeculativeToolRunner {
  observe: (
    tools: UIMessagePartTool[], toolDefinitions: Map<string, AgentTool>,
  ) => void;
  reusableResults: (
    finalTools: UIMessagePartTool[],
  ) => Promise<Map<string, UIMessagePartTool>>;
  snapshot: () => SpeculativeToolState[];
}

interface JobEntry {
  promise: Promise<UIMessagePartTool | null>;
  settled: boolean;
  cancelled: boolean;
}

// isLikelyCompleteJsonObject(:135-138)
const isLikelyCompleteJsonObject = (input: string): boolean => {
  const trimmed: string = input.trim();
  return trimmed.startsWith('{') && trimmed.endsWith('}');
};

export const createSpeculativeToolRunner = (
  deps: SpeculativeToolRunnerDeps,
): SpeculativeToolRunner => {
  const maxConcurrentTools: number = deps.maxConcurrentTools ?? 4;
  const invocationContext: ToolInvocationContext = deps.invocationContext ?? 'normal';
  const states: Map<string, SpeculativeToolState> = new Map<string, SpeculativeToolState>();
  const jobs: Map<string, JobEntry> = new Map<string, JobEntry>();

  // updateStateIfCurrent(:140-153):仅当 state 仍对应该 (name,input) 时更新
  const updateStateIfCurrent = (
    tool: UIMessagePartTool,
    update: (state: SpeculativeToolState) => SpeculativeToolState,
  ): void => {
    const current: SpeculativeToolState | undefined = states.get(tool.toolCallId);
    if (current === undefined) return;
    if (current.toolName === tool.toolName && current.input === tool.input) {
      states.set(tool.toolCallId, update(current));
    }
  };

  const cancelJob = (toolCallId: string): void => {
    const job: JobEntry | undefined = jobs.get(toolCallId);
    if (job !== undefined) job.cancelled = true;
    jobs.delete(toolCallId);
  };

  // observe(:44-106)
  const observe = (
    tools: UIMessagePartTool[], toolDefinitions: Map<string, AgentTool>,
  ): void => {
    const eligible: UIMessagePartTool[] = tools
      .filter((t: UIMessagePartTool): boolean =>
        t.toolCallId.trim().length > 0 && !isToolExecuted(t))
      .filter((t: UIMessagePartTool): boolean => isLikelyCompleteJsonObject(t.input))
      .filter((t: UIMessagePartTool): boolean => {
        const def: AgentTool | undefined = toolDefinitions.get(t.toolName);
        if (def === undefined) return false;
        return toolInvocationPolicyFromText(def, t.input).speculativeEligible;
      })
      .slice(0, maxConcurrentTools);
    for (const tool of eligible) {
      const existing: SpeculativeToolState | undefined = states.get(tool.toolCallId);
      if (existing !== undefined
        && existing.toolName === tool.toolName
        && existing.input === tool.input) {
        continue;
      }
      if (existing !== undefined) {
        // jobs.remove(id)?.cancel()
        cancelJob(tool.toolCallId);
      }
      states.set(tool.toolCallId, {
        toolCallId: tool.toolCallId,
        toolName: tool.toolName,
        input: tool.input,
        status: 'pending',
        result: null,
        error: null,
      });
      const entry: JobEntry = {
        promise: Promise.resolve(null),
        settled: false,
        cancelled: false,
      };
      entry.promise = (async (): Promise<UIMessagePartTool | null> => {
        try {
          const result: UIMessagePartTool | null = await deps.dispatcher.execute(
            tool,
            toolDefinitions.get(tool.toolName) ?? null,
            false, // autoApproveTools 写死 false(:69)
            false, // autoApproveHighRiskTools 写死 false(:70)
            [],
            invocationContext,
          );
          // cancel 后完成不覆盖(JS Promise 不可中断,文件头偏差)
          if (!entry.cancelled) {
            updateStateIfCurrent(tool, (s: SpeculativeToolState): SpeculativeToolState => ({
              ...s, status: 'completed', result,
            }));
          }
          return result;
        } catch (e) {
          const err: Error = e instanceof Error ? e : new Error(String(e));
          if (!entry.cancelled) {
            updateStateIfCurrent(tool, (s: SpeculativeToolState): SpeculativeToolState => ({
              ...s, status: 'failed', error: err.message,
            }));
          }
          return null;
        } finally {
          entry.settled = true;
        }
      })();
      jobs.set(tool.toolCallId, entry);
    }
  };

  // reusableResults(:108-133)
  const reusableResults = async (
    finalTools: UIMessagePartTool[],
  ): Promise<Map<string, UIMessagePartTool>> => {
    const finalById: Map<string, UIMessagePartTool> = new Map<string, UIMessagePartTool>(
      finalTools.map((t: UIMessagePartTool): [string, UIMessagePartTool] => [t.toolCallId, t]));
    // 未进 final 或 name/input 不符 → cancel + discarded
    for (const [toolCallId, job] of [...jobs.entries()]) {
      const state: SpeculativeToolState | undefined = states.get(toolCallId);
      if (state === undefined) continue;
      const final: UIMessagePartTool | undefined = finalById.get(toolCallId);
      if (final === undefined
        || final.toolName !== state.toolName
        || final.input !== state.input) {
        job.cancelled = true;
        jobs.delete(toolCallId);
        states.set(toolCallId, { ...state, status: 'discarded' });
      }
    }
    const out: Map<string, UIMessagePartTool> = new Map<string, UIMessagePartTool>();
    for (const final of finalTools) {
      const state: SpeculativeToolState | undefined = states.get(final.toolCallId);
      if (state === undefined) continue;
      if (state.toolName !== final.toolName || state.input !== final.input) continue;
      const job: JobEntry | undefined = jobs.get(final.toolCallId);
      if (job !== undefined && !job.settled) {
        // 未完结 → 来不及复用:cancel + discarded(:122-126)
        job.cancelled = true;
        jobs.delete(final.toolCallId);
        states.set(final.toolCallId, { ...state, status: 'discarded' });
        continue;
      }
      let result: UIMessagePartTool | null = null;
      if (job !== undefined && !job.cancelled) {
        result = await job.promise;
      } else if (state.status === 'completed') {
        result = state.result;
      }
      if (result !== null) out.set(final.toolCallId, result);
    }
    return out;
  };

  // snapshot(:134):values sortedBy toolCallId
  const snapshot = (): SpeculativeToolState[] => {
    const list: SpeculativeToolState[] = [];
    states.forEach((s: SpeculativeToolState): void => {
      list.push(s);
    });
    list.sort((a: SpeculativeToolState, b: SpeculativeToolState): number =>
      a.toolCallId < b.toolCallId ? -1 : a.toolCallId > b.toolCallId ? 1 : 0);
    return list;
  };

  return { observe, reusableResults, snapshot };
};
