// council/runner — 议会模型调用端口
// 对应 Android ModelCouncilTextRunner。entry 用流式 OpenAI 兼容客户端实现
// (ModelCouncilRunner.ets),Node 测试用 mock。onChunk 回传「累积文本」。

import type { ModelConfig } from '../domain/model_config.ts';
import type { CouncilApprovalRequest, CouncilSeatRunKey, CouncilSource, CouncilToolMode,
  CouncilToolSnapshot, CouncilToolVerdict, ReasoningLevel } from './models.ts';
import type { UIMessage } from '../agent/message.ts';
import type { AbortSignalLike } from '../platform/runtime_api.ts';

export interface CouncilGenerateRequest {
  model: ModelConfig;
  systemPrompt: string;
  userPrompt: string;
  outputBudgetChars: number;
  reasoningLevel: ReasoningLevel | null;
  temperature: number | null;
  // 累积文本回调(非增量);runner 内部按 ~32ms 合并
  onChunk: (cumulativeText: string) => void;
  signal: AbortSignalLike | null;
  key?: CouncilSeatRunKey;
  toolMode?: CouncilToolMode;
  onTools?: (snapshot: CouncilToolSnapshot) => void;
  requestApproval?: (request: CouncilApprovalRequest, signal: AbortSignalLike) => Promise<CouncilToolVerdict>;
}

export interface ModelCouncilTextResult {
  text: string;
  warnings: string[];
  toolMessages?: UIMessage[];
  sources?: CouncilSource[];
}

export interface ModelCouncilTextRunner {
  generate(req: CouncilGenerateRequest): Promise<ModelCouncilTextResult>;
}
