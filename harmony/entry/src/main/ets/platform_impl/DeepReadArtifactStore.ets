import { deepReadToMarkdown, isComplete } from '@amber/deepread-domain';
import type { DeepReadCacheEntry, DeepReadRepository } from '@amber/deepread-domain';
import type { KeyValueStore } from '@amber/chat-domain';
import { KeyedAsyncMutex, writeTextFileAtomic } from './AtomicJsonFile.ets';
import { workspaceRootAbs } from './WorkspaceStore.ets';
import { sha256HexUtf8 } from './EntryLocalToolPorts.ets';

export interface DeepReadArtifactStatus {
  path: string | null;
  savedAt: number | null;
  articleUpdatedAt: number | null;
  error: string | null;
  // The file may have succeeded even if its status could not be persisted.
  statusError: string | null;
}

const statusKey = (topicId: string): string => `deepread_artifact_${sha256HexUtf8(topicId)}`;
const errorText = (error: Error | Object): string => error instanceof Error ? error.message : String(error);

// One stable file per topic. The repository remains the source of article truth.
export class DeepReadArtifactStore {
  private readonly repository: DeepReadRepository;
  private readonly kv: KeyValueStore;
  private readonly mutex: KeyedAsyncMutex = new KeyedAsyncMutex();
  private readonly statuses: Map<string, DeepReadArtifactStatus> = new Map<string, DeepReadArtifactStatus>();

  constructor(repository: DeepReadRepository, kv: KeyValueStore) {
    this.repository = repository;
    this.kv = kv;
  }

  async getStatus(topicId: string): Promise<DeepReadArtifactStatus | null> {
    const current: DeepReadArtifactStatus | undefined = this.statuses.get(topicId);
    if (current !== undefined) return current;
    const raw: string | null = await this.kv.get(statusKey(topicId));
    if (raw === null) return null;
    const status: DeepReadArtifactStatus = JSON.parse(raw) as DeepReadArtifactStatus;
    this.statuses.set(topicId, status);
    return status;
  }

  async save(topicId: string): Promise<DeepReadArtifactStatus> {
    const path: string = `${workspaceRootAbs()}/deepread/${sha256HexUtf8(topicId)}.md`;
    return this.mutex.withLock(path, async (): Promise<DeepReadArtifactStatus> => {
      let entry: DeepReadCacheEntry | null = null;
      const status: DeepReadArtifactStatus = {
        path: null, savedAt: null, articleUpdatedAt: null, error: null, statusError: null,
      };
      try {
        // Re-read inside the target-path lock: a queued retry must export the latest committed article.
        entry = await this.repository.get(topicId);
        if (entry === null || !isComplete(entry.output)) throw new Error('只有已完整保存的文章可以保存到 Workspace');
        status.articleUpdatedAt = entry.updatedAt;
        await writeTextFileAtomic(path, deepReadToMarkdown(entry));
        status.path = path;
        status.savedAt = Date.now();
      } catch (error) {
        status.error = errorText(error as Error);
      }
      this.statuses.set(topicId, status);
      try {
        await this.kv.put(statusKey(topicId), JSON.stringify(status));
      } catch (error) {
        status.statusError = `Workspace 保存状态未能持久化：${errorText(error as Error)}`;
      }
      return status;
    });
  }
}
