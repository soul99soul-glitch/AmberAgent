import type { DeepReadCacheEntry, DeepReadOutput, DeepReadRepository } from '@amber/deepread-domain';
import { isComplete } from '@amber/deepread-domain';
import { hilog } from '@kit.PerformanceAnalysisKit';

export type DeepReadCommittedCallback = (entry: DeepReadCacheEntry) => Promise<void>;

const firstOutputError = (output: DeepReadOutput): string | null => {
  const stages: string[] = ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'];
  for (const stage of stages) {
    const error: string | null | undefined = output.sectionStates[stage]?.errorMessage;
    if (error !== undefined && error !== null && error.length > 0) return error;
  }
  return null;
};

// 缓存只反映已持久化的版本；过期标记用于刷新策略，不删除用户已有的文章。
export class DeepReadRunRepositoryAdapter {
  private readonly repository: DeepReadRepository;
  private readonly cache: Map<string, DeepReadCacheEntry> = new Map<string, DeepReadCacheEntry>();
  private readonly onCommittedComplete: DeepReadCommittedCallback | null;

  constructor(repository: DeepReadRepository, onCommittedComplete: DeepReadCommittedCallback | null = null) {
    this.repository = repository;
    this.onCommittedComplete = onCommittedComplete;
  }

  async hydrate(): Promise<void> {
    const rows: DeepReadCacheEntry[] = await this.repository.listHistory(100);
    for (const row of rows) this.cache.set(row.topicId, row);
  }

  async load(topicId: string): Promise<void> {
    const entry: DeepReadCacheEntry | null = await this.repository.get(topicId);
    if (entry === null) this.cache.delete(topicId);
    else this.cache.set(topicId, entry);
  }

  get(topicId: string, _title: string): DeepReadOutput | null {
    return this.cache.get(topicId)?.output ?? null;
  }

  async save(topicId: string, title: string, output: DeepReadOutput): Promise<void> {
    const now: number = Date.now();
    const previous: DeepReadCacheEntry | undefined = this.cache.get(topicId);
    const entry: DeepReadCacheEntry = {
      topicId,
      title,
      sourceUrl: output.inputSourceUrls?.[0] ?? previous?.sourceUrl ?? null,
      output,
      templateId: output.templateSnapshot?.id ?? output.templateId,
      phase: output.generationPhase,
      attemptCount: previous?.attemptCount ?? 0,
      lastError: firstOutputError(output),
      createdAt: previous?.createdAt ?? now,
      updatedAt: now,
      expiresAt: now + 24 * 60 * 60 * 1000,
    };
    await this.repository.upsert(entry);
    this.cache.set(topicId, entry);
    if (this.onCommittedComplete !== null && isComplete(output)) {
      try {
        await this.onCommittedComplete(entry);
      } catch (error) {
        // A secondary artifact failure cannot reverse a successful article commit.
        hilog.warn(0x0001, 'DeepReadRunRepository', 'committed article artifact callback failed: %{public}s',
          (error as Error).message);
      }
    }
  }

  async clear(topicId: string): Promise<void> {
    await this.repository.delete(topicId);
    this.cache.delete(topicId);
  }
}
