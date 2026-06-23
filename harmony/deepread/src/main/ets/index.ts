// deepread-domain 公共入口 barrel — 供 entry 模块 ArkTS import
// 汇总 domain/agent/research 各层对外 API。
//
// 注意:ArkTS 编译器对 barrel 的要求 = 各源文件本身可过 ArkTS 严格模式。
// 纯逻辑层已按 interface+factory、无 any、spread 不可变约定编写(对照设计 §0.4)。

// ===== Domain =====
// ArkTS 安全:topic_id.ts 提供 createTopicIdWithRuntime(注入 runtime)+ normalizeUrlArkTs。
// deriveTopicId 在 topic_id_node.ts(仅 node 测试,import 'crypto'),ArkTS 不引用。
export { createTopicIdWithRuntime, normalizeTitle, normalizeUrlArkTs } from './domain/topic_id.ts';
export type { TopicIdRuntime } from './domain/topic_id.ts';
export type { DeepReadOutput, TimelineEvent, CorePoint, ReadingLink, Perspective, Quote, DeepAnalysis, DeepReadImageAsset, DiagramNode, DiagramEdge, DeepReadDiagram, DeepReadSectionState } from './domain/models.ts';
export { makeEmptyDeepReadOutput } from './domain/models.ts';
export type { DeepReadGenerationStage, DeepReadGenerationPhase, DeepReadSectionStatus } from './domain/enums.ts';
export { STAGE_ORDER, STAGE_LABELS, IMAGE_CONFIDENCE, WRITER_TOOL_NAMES } from './domain/enums.ts';
export { statusOf, sectionsReady, isComplete, displayHeroImageUrl, displayHeroCaption, hasReadableArticle } from './domain/helpers.ts';

// ===== Platform 契约(供 entry 注入真实实现) =====
export type { HttpClient, HttpRequest, HttpResponse } from './platform/http.ts';
export type { Database } from './platform/database.ts';
export type { Storage } from './platform/storage.ts';
export type { SearchProvider, SearchProviderRegistry, SearchHit } from './platform/search.ts';
export type { AiClient } from './platform/ai_client.ts';
export type { DeepReadRepository } from './platform/repository.ts';
export type { Observable } from './platform/observable.ts';
export { pollingObservable } from './platform/observable.ts';
export { DEEP_READ_SCHEMA_SQL } from './platform/database.ts';
export type { Notifier, NotificationKind, NotificationRequest } from './platform/notifier.ts';

// ===== Agent / Research 入口 =====
export { createSectionWriterTools } from './agent/section_writer_tools.ts';
export type { SectionWriterTools } from './agent/section_writer_tools.ts';
export { run, runSection, createRunContext, generateStages } from './agent/run_manager.ts';
export type { RunManagerDeps, RunResult } from './agent/run_manager.ts';
export { runAgentLoop } from './agent/agent_loop.ts';
export type { AgentLoopDeps, AgentLoopResult, StopReason } from './agent/agent_loop.ts';
export type { ToolDefinition, ApprovalMatrix } from './agent/tool_execution.ts';
export { createScheduler, isInterruptedPhase } from './agent/scheduler.ts';
export type { DeepReadScheduler, SchedulerRunOptions } from './agent/scheduler.ts';
export { createSourcePrefetcher } from './research/source_prefetcher.ts';
export type { SourcePrefetcher, DeepReadSource } from './research/source_prefetcher.ts';
export { buildEvidencePack, cardsFor } from './research/evidence_pack.ts';
export type { DeepReadEvidencePack, DeepReadArticlePlan } from './research/evidence_pack.ts';
export { generateArticlePlan, fallbackPlan } from './research/article_plan.ts';
