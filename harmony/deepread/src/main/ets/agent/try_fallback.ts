// tryFallbackAfterStageFailure — 照搬 Android DeepReadAgentRunManager.kt:632-659
//
// supervisor loop 失败/超时后调用:把 assistant 自由文本 + sources 转成 BASIC 质量 READY section。
// writer.writeFallbackSection 已在 section_writer_tools.ts 实现(Phase 4 Task A);
// 这里是 orchestrator 层:取 latestAssistantText → 调 writeFallbackSection → 返回是否 recovered。

import type { UIMessage } from './message.ts';
import { latestAssistantText } from './message.ts';
import type { SectionWriterTools } from './section_writer_tools.ts';
import type { EvidenceCard } from '../research/evidence_pack.ts';
import type { ReadingLink } from '../domain/models.ts';
import type { DeepReadGenerationStage } from '../domain/enums.ts';
import { statusOf } from '../domain/helpers.ts';

// evidence card → ReadingLink 转换(writeFallbackSection 接收 ReadingLink[])
const cardToReadingLink = (card: EvidenceCard): ReadingLink => ({
  title: card.title,
  url: card.url,
  source: card.source,
  publishedAt: card.publishedAt,
});

export interface TryFallbackParams {
  writer: SectionWriterTools;
  stage: DeepReadGenerationStage;
  messages: UIMessage[];
  sources: EvidenceCard[];
  reason: string;
  allowReadyRewrite: boolean;
}

// 照搬 Android :632-659
// 返回 true = fallback 成功(stage 变 READY);false = 仍非 READY
export const tryFallbackAfterStageFailure = async (p: TryFallbackParams): Promise<boolean> => {
  // 已 READY → 直接成功(对照 Android :640)
  if (statusOf(p.writer.current(), p.stage) === 'READY') return true;
  let fallback;
  try {
    const assistantText = latestAssistantText(p.messages);
    const links = p.sources.map(cardToReadingLink);
    fallback = p.writer.writeFallbackSection(
      p.stage,
      assistantText,
      links,
      p.allowReadyRewrite,
    );
  } catch {
    // 对照 Android :650-653:fallback 抛错 → 返回 false
    return false;
  }
  const ready = statusOf(fallback, p.stage) === 'READY';
  return ready;
};
