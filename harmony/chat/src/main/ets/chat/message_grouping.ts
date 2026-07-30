// message_grouping — 消息 parts 分组与折叠纯逻辑(阶段 A, audit 1.4)
//
// Android 基准: app/.../feature/ui/components/message/ChatMessageCot.kt(全文 319 行)
//   groupMessageParts / ThinkingStep / MessagePartBlock /
//   coalesceSubAgentSteps / coalesceCouncilSteps / mergeDuplicateRunBlocks
//
// 适配:
//   - Kotlin sealed interface → ArkTS 命名 discriminated union(kind 字段判别)
//   - Kotlin data class copy → 全字段显式拷贝
//   - kotlinx JsonObject/JsonPrimitive → 复用 message.ts toolInputAsJson + tool_activity.ts toolOutputJson
//   - fastForEachIndexed → 普通 for 循环
//   - inline fun → 普通函数(ArkTS 无 inline)
//
// 此模块为纯域逻辑,零 SDK 依赖,零 UI 依赖。是 ChatMessage 组件族拆分的前置基石。

import type { JsonObject } from './json.ts';
import type {
  UIMessagePart, UIMessagePartText, UIMessagePartReasoning, UIMessagePartTool,
} from './message.ts';
import { toolInputAsJson } from './message.ts';
import { toolOutputJson } from './tool_activity.ts';

// ===== 常量(ChatMessageCot.kt:10-26) =====

const SUBAGENT_TASK_TOOLS: ReadonlySet<string> = new Set<string>([
  'subagent_start', 'subagent_wait', 'subagent_read', 'subagent_cancel',
]);

const COUNCIL_TASK_TOOLS: ReadonlySet<string> = new Set<string>([
  'model_council_start', 'model_council_wait', 'model_council_read',
  'model_council_cancel', 'model_council_make_report',
]);

// ===== ThinkingStep(ChatMessageCot.kt:31-67) =====
// ArkTS 命名 discriminated union:kind 字段判别,显式 cast 后访问字段。

export type ThinkingStep =
  | { kind: 'reasoning'; reasoning: UIMessagePartReasoning }
  | { kind: 'tool'; tool: UIMessagePartTool }
  | SubAgentTaskStep
  | CouncilTaskStep;

// SubAgentTaskStep.anchor: Prefer subagent_start; fallback first(ChatMessageCot.kt:52-53)
const subAgentAnchor = (tools: UIMessagePartTool[]): UIMessagePartTool => {
  for (const t of tools) {
    if (t.toolName === 'subagent_start') return t;
  }
  return tools[0];
};

// CouncilTaskStep.anchor: Prefer model_council_start; fallback first(:64-65)
const councilAnchor = (tools: UIMessagePartTool[]): UIMessagePartTool => {
  for (const t of tools) {
    if (t.toolName === 'model_council_start') return t;
  }
  return tools[0];
};

// ===== MessagePartBlock(ChatMessageCot.kt:72-85) =====

// SubAgent/Council step 的具名类型(避免 Extract 工具类型,ArkTS 兼容)
export interface SubAgentTaskStep {
  kind: 'subagent_task';
  runId: string;
  tools: UIMessagePartTool[];
  anchor: UIMessagePartTool;
}

export interface CouncilTaskStep {
  kind: 'council_task';
  runId: string;
  tools: UIMessagePartTool[];
  anchor: UIMessagePartTool;
}

export type MessagePartBlock =
  | { kind: 'thinking'; steps: ThinkingStep[] }
  | { kind: 'content'; part: UIMessagePart; index: number }
  | { kind: 'subagent'; step: SubAgentTaskStep }
  | { kind: 'council'; step: CouncilTaskStep };

// ===== run_id 提取(ChatMessageCot.kt:92-115) =====
// start 工具从 output JSON 取 run_id(服务端分配);其余从 input JSON 取。
// 解析失败/空 → null。

const extractRunId = (
  toolPart: UIMessagePartTool, startToolName: string,
): string | null => {
  try {
    let raw: unknown;
    if (toolPart.toolName === startToolName) {
      const outputJson: JsonObject = toolOutputJson(toolPart);
      raw = outputJson['run_id'];
    } else {
      const inputJson: JsonObject = toolInputAsJson(toolPart);
      raw = inputJson['run_id'];
    }
    if (typeof raw !== 'string') return null;
    return raw.trim().length > 0 ? raw : null;
  } catch (_e) {
    return null;
  }
};

const subagentRunId = (toolPart: UIMessagePartTool): string | null => {
  if (!SUBAGENT_TASK_TOOLS.has(toolPart.toolName)) return null;
  return extractRunId(toolPart, 'subagent_start');
};

const councilRunId = (toolPart: UIMessagePartTool): string | null => {
  if (!COUNCIL_TASK_TOOLS.has(toolPart.toolName)) return null;
  return extractRunId(toolPart, 'model_council_start');
};

// ===== coalesceTaskSteps(ChatMessageCot.kt:143-172) =====
// 通用 coalesce:遍历 steps,ToolStep 通过 runIdOf 取 run_id;
// 同 run_id 的工具折叠为一个 wrapped step,插入位置 = 首次出现处。
// runIdOf 返回 null 的 ToolStep 原样透传。

interface CoalesceAcc {
  tools: UIMessagePartTool[];
  placeholderIndex: number;
}

const coalesceTaskSteps = (
  steps: ThinkingStep[],
  runIdOf: (tool: UIMessagePartTool) => string | null,
  wrap: (runId: string, tools: UIMessagePartTool[]) => ThinkingStep,
): ThinkingStep[] => {
  const out: ThinkingStep[] = [];
  // LinkedHashMap 语义:插入顺序保持;ArkTS Map 保持插入顺序
  const acc: Map<string, CoalesceAcc> = new Map<string, CoalesceAcc>();

  for (const step of steps) {
    if (step.kind === 'tool') {
      const runId: string | null = runIdOf(step.tool);
      if (runId !== null) {
        let bucket: CoalesceAcc | undefined = acc.get(runId);
        if (bucket === undefined) {
          bucket = { tools: [], placeholderIndex: out.length };
          acc.set(runId, bucket);
          // 占位:先放一个空 wrapped step,后续回填
          out.push(wrap(runId, []));
        }
        bucket.tools.push(step.tool);
        continue;
      }
    }
    out.push(step);
  }
  // 回填占位 step
  acc.forEach((value: CoalesceAcc, runId: string): void => {
    out[value.placeholderIndex] = wrap(runId, value.tools);
  });
  return out;
};

// coalesceSubAgentSteps(:123-128)
const wrapSubAgent = (runId: string, tools: UIMessagePartTool[]): ThinkingStep => ({
  kind: 'subagent_task',
  runId,
  tools,
  anchor: subAgentAnchor(tools),
});

const coalesceSubAgentSteps = (steps: ThinkingStep[]): ThinkingStep[] =>
  coalesceTaskSteps(steps, subagentRunId, wrapSubAgent);

// coalesceCouncilSteps(:131-136)
const wrapCouncil = (runId: string, tools: UIMessagePartTool[]): ThinkingStep => ({
  kind: 'council_task',
  runId,
  tools,
  anchor: councilAnchor(tools),
});

const coalesceCouncilSteps = (steps: ThinkingStep[]): ThinkingStep[] =>
  coalesceTaskSteps(steps, councilRunId, wrapCouncil);

// ===== groupMessageParts(ChatMessageCot.kt:178-265) =====

export const groupMessageParts = (parts: UIMessagePart[]): MessagePartBlock[] => {
  const result: MessagePartBlock[] = [];
  let currentThinkingSteps: ThinkingStep[] = [];
  let pendingText: UIMessagePartText | null = null;
  let pendingTextBuilder: string[] | null = null;
  let pendingTextIndex: number = -1;

  const flushThinkingSteps = (): void => {
    if (currentThinkingSteps.length === 0) return;
    // 先 coalesce subagent,再 coalesce council(顺序不影响结果,两族不重叠)
    const coalesced: ThinkingStep[] =
      coalesceCouncilSteps(coalesceSubAgentSteps(currentThinkingSteps));
    // 将 SubAgent/Council step 提升为顶层独立 block(不被 ThinkingBlock collapse 隐藏)
    let pending: ThinkingStep[] = [];
    const flushPending = (): void => {
      if (pending.length > 0) {
        result.push({ kind: 'thinking', steps: pending });
        pending = [];
      }
    };
    for (const step of coalesced) {
      if (step.kind === 'subagent_task') {
        flushPending();
        result.push({ kind: 'subagent', step });
      } else if (step.kind === 'council_task') {
        flushPending();
        result.push({ kind: 'council', step });
      } else {
        pending.push(step);
      }
    }
    flushPending();
    currentThinkingSteps = [];
  };

  const flushText = (): void => {
    if (pendingText !== null) {
      const mergedText: string = pendingTextBuilder !== null
        ? pendingTextBuilder.join('')
        : pendingText.text;
      // text part copy(text = mergedText) — 全字段显式拷贝
      const mergedPart: UIMessagePartText = {
        type: 'text',
        text: mergedText,
        metadata: pendingText.metadata,
      };
      result.push({ kind: 'content', part: mergedPart, index: pendingTextIndex });
    }
    pendingText = null;
    pendingTextBuilder = null;
    pendingTextIndex = -1;
  };

  for (let index: number = 0; index < parts.length; index++) {
    const part: UIMessagePart = parts[index];
    if (part.type === 'reasoning') {
      if (part.reasoning.trim().length > 0) {
        flushText();
        currentThinkingSteps.push({ kind: 'reasoning', reasoning: part });
      }
    } else if (part.type === 'tool') {
      flushText();
      currentThinkingSteps.push({ kind: 'tool', tool: part });
    } else if (part.type === 'text') {
      flushThinkingSteps();
      if (pendingText === null) {
        pendingTextIndex = index;
        pendingTextBuilder = [part.text];
        // copy as-is;metadata 将由后续 text 覆盖(:249-251)
        pendingText = { type: 'text', text: part.text, metadata: part.metadata };
      } else {
        pendingTextBuilder!.push(part.text);
        // metadata: 第二个 text 的 metadata 非 null → 覆盖;null → 保留第一个
        if (part.metadata !== null) {
          pendingText = { type: 'text', text: pendingText.text, metadata: part.metadata };
        }
      }
    } else {
      // image/video/audio/document/mini_app → 独立 ContentBlock
      flushText();
      flushThinkingSteps();
      result.push({ kind: 'content', part, index });
    }
  }
  flushText();
  flushThinkingSteps();
  return mergeDuplicateRunBlocks(result);
};

// ===== mergeDuplicateRunBlocks(ChatMessageCot.kt:275-319) =====
// 合并同 runId 的 SubAgent/Council block(被中间 Text 拆开的情况),
// 保留首次出现位置,拼接 tools。不合并 → LazyColumn duplicate key crash。

const mergeDuplicateRunBlocks = (blocks: MessagePartBlock[]): MessagePartBlock[] => {
  const subAgentIndex: Map<string, number> = new Map<string, number>();
  const councilIndex: Map<string, number> = new Map<string, number>();
  const merged: MessagePartBlock[] = [];

  for (const block of blocks) {
    if (block.kind === 'subagent') {
      const runId: string = block.step.runId;
      const existing: number | undefined = subAgentIndex.get(runId);
      if (existing === undefined) {
        subAgentIndex.set(runId, merged.length);
        merged.push(block);
      } else {
        const prior: SubAgentTaskStep =
          (merged[existing] as { kind: 'subagent'; step: SubAgentTaskStep }).step;
        const combinedTools: UIMessagePartTool[] = prior.tools.concat(block.step.tools);
        merged[existing] = {
          kind: 'subagent',
          step: {
            kind: 'subagent_task',
            runId,
            tools: combinedTools,
            anchor: subAgentAnchor(combinedTools),
          },
        };
      }
    } else if (block.kind === 'council') {
      const runId: string = block.step.runId;
      const existing: number | undefined = councilIndex.get(runId);
      if (existing === undefined) {
        councilIndex.set(runId, merged.length);
        merged.push(block);
      } else {
        const prior: CouncilTaskStep =
          (merged[existing] as { kind: 'council'; step: CouncilTaskStep }).step;
        const combinedTools: UIMessagePartTool[] = prior.tools.concat(block.step.tools);
        merged[existing] = {
          kind: 'council',
          step: {
            kind: 'council_task',
            runId,
            tools: combinedTools,
            anchor: councilAnchor(combinedTools),
          },
        };
      }
    } else {
      merged.push(block);
    }
  }
  return merged;
};
