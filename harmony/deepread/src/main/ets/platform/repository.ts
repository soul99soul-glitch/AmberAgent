// DeepReadRepository — 持久化访问封装
// 实现留后续(RdbDeepReadRepository 用 Database + JSON parse)

import type { DeepReadOutput, DeepReadSectionState } from '../domain/models.ts';
import type { DeepReadGenerationStage, DeepReadGenerationPhase } from '../domain/enums.ts';
import type { Observable } from './observable.ts';

export interface DeepReadCacheEntry {
  /** Indexed projection of output.templateId; persisted in the existing output JSON. */
  templateId?: string;
  topicId: string;
  title: string;
  sourceUrl: string | null;
  output: DeepReadOutput;
  phase: DeepReadGenerationPhase;
  attemptCount: number;
  lastError: string | null;
  createdAt: number;
  updatedAt: number;
  expiresAt: number;
}

export interface DeepReadRepository {
  get(topicId: string): Promise<DeepReadCacheEntry | null>;
  getByUrl(url: string): Promise<DeepReadCacheEntry | null>;
  getByTitle(title: string): Promise<DeepReadCacheEntry | null>;
  materializeFresh(topicId: string, title: string, url: string | null): Promise<DeepReadCacheEntry | null>;
  upsert(entry: DeepReadCacheEntry): Promise<void>;
  updatePhase(topicId: string, phase: DeepReadGenerationPhase): Promise<void>;
  updateSectionState(topicId: string, stage: DeepReadGenerationStage, state: DeepReadSectionState): Promise<void>;
  incrementAttempt(topicId: string, error: string): Promise<void>;
  resetAttempt(topicId: string): Promise<void>;
  observe(topicId: string): Observable<DeepReadCacheEntry | null>;
  observeHistory(limit: number): Observable<DeepReadCacheEntry[]>;
  listHistory(limit: number): Promise<DeepReadCacheEntry[]>;
  delete(topicId: string): Promise<void>;
  purgeExpired(): Promise<number>;
  purgeHistoryRetention(): Promise<number>;
}
