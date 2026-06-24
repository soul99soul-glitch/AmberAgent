// RdbRepository — DeepReadRepository 契约的实现
// 对照设计 §3.4/§3.6。ArkTS 兼容:class implements DeepReadRepository。
// MVP:内存 fallback(真实 relationalStore 待 context 注入 + API 验证后接入)。

import { hilog } from '@kit.PerformanceAnalysisKit';
import type {
  DeepReadRepository, DeepReadCacheEntry, Observable,
} from '@amber/deepread-domain';
import type { DeepReadGenerationStage, DeepReadGenerationPhase } from '@amber/deepread-domain';
import type { DeepReadSectionState } from '@amber/deepread-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'DeepRead.RdbRepository';

class MemoryFallbackRepository implements DeepReadRepository {
  private store: Map<string, DeepReadCacheEntry> = new Map();

  async get(topicId: string): Promise<DeepReadCacheEntry | null> {
    const e: DeepReadCacheEntry | undefined = this.store.get(topicId);
    return e !== undefined ? e : null;
  }
  async getByUrl(_url: string): Promise<DeepReadCacheEntry | null> { return null; }
  async getByTitle(_title: string): Promise<DeepReadCacheEntry | null> { return null; }
  async materializeFresh(topicId: string, _title: string, _url: string | null): Promise<DeepReadCacheEntry | null> {
    return this.store.get(topicId) ?? null;
  }
  async upsert(entry: DeepReadCacheEntry): Promise<void> {
    this.store.set(entry.topicId, entry);
  }
  async updatePhase(_topicId: string, _phase: DeepReadGenerationPhase): Promise<void> {}
  async updateSectionState(_topicId: string, _stage: DeepReadGenerationStage, _state: DeepReadSectionState): Promise<void> {}
  async incrementAttempt(_topicId: string, _error: string): Promise<void> {}
  async resetAttempt(_topicId: string): Promise<void> {}
  observe(_topicId: string): Observable<DeepReadCacheEntry | null> {
    return {
      subscribe: (_cb: (v: DeepReadCacheEntry | null) => void): (() => void) => { return () => {}; },
      getCurrent: (): DeepReadCacheEntry | null | undefined => undefined,
    };
  }
  async listHistory(_limit: number): Promise<DeepReadCacheEntry[]> { return []; }
  async delete(topicId: string): Promise<void> { this.store.delete(topicId); }
  async purgeExpired(): Promise<number> { return 0; }
  async purgeHistoryRetention(): Promise<number> { return 0; }
}

export const createRdbRepository = (): DeepReadRepository => {
  hilog.info(DOMAIN, TAG, 'createRdbRepository — memory fallback (RDB TODO)');
  return new MemoryFallbackRepository();
};
