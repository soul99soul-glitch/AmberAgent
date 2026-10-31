import { promptAction } from '@kit.ArkUI';
import type { Conversation, ConversationSummary, ConversationRepository, KeyValueStore } from '@amber/chat-domain';
import { patchConversation } from '@amber/chat-domain';
import type { ConversationRunService } from '../ConversationRunService.ets';
import { beginConversationRestore } from '../ConversationRunGuard.ets';
import { MOTION_ENTER } from '../../design/tokens.ets';

const SNACK_DURATION_MS: number = 4000;
const PREVIEW_CONCURRENCY: number = 8;
export const HOME_LIST_VERSION_KEY: string = 'homeListVersion';
const bumpHomeListVersion = (): void => {
  AppStorage.setOrCreate<number>(HOME_LIST_VERSION_KEY, (AppStorage.get<number>(HOME_LIST_VERSION_KEY) ?? 0) + 1);
};

// Shared across retained home panes and the replacement chat-page left pane.
// Only this module reads or writes list/count caches and the pending-delete identity.
class HomeConversationCache {
  cachedItems: ConversationSummary[] | null = null;
  cachedCounts: Record<string, number> = {};
  cachedCountStamps: Record<string, string> = {};
  pendingDeleteId: string = '';
}
const shared: HomeConversationCache = new HomeConversationCache();

export const invalidateHomeConversationCache = (): void => {
  shared.cachedItems = null;
  shared.cachedCounts = {};
  shared.cachedCountStamps = {};
  bumpHomeListVersion();
};
export interface HomeConversationCollectionPorts {
  getRepository: () => ConversationRepository;
  getRunService: () => ConversationRunService;
  getKv: () => KeyValueStore;
  cleanupDeletedConversation: (conversation: Conversation, repository: ConversationRepository, kv: KeyValueStore) => Promise<void>;
}

@Observed
export class HomeConversationCollection {
  items: ConversationSummary[] = [];
  runningIds: string[] = [];
  loaded: boolean = false;
  snackVisible: boolean = false;
  msgCounts: Record<string, number> = {};
  private pendingDeleted: Conversation | null = null;
  private snackTimer: number = -1;
  private pageAlive: boolean = false;
  private unsubscribeRunning: (() => void) | null = null;
  private refreshSeq: number = 0;
  private activeDeleted: (id: string) => void = (_id: string): void => {};
  private readonly ports: HomeConversationCollectionPorts;
  constructor(ports: HomeConversationCollectionPorts) { this.ports = ports; }

  activate(seedFromCache: boolean, activeDeleted: (id: string) => void): boolean {
    this.pageAlive = true;
    this.activeDeleted = activeDeleted;
    const seeded: boolean = seedFromCache && shared.cachedItems !== null;
    if (seeded) {
      this.items = shared.cachedItems as ConversationSummary[];
      this.msgCounts = shared.cachedCounts;
      this.loaded = true;
    }
    return seeded;
  }

  dispose(): void {
    this.pageAlive = false;
    this.stopRunningSubscription();
    if (this.pendingDeleted !== null) this.commitPendingDelete();
  }

  observeRunning(): void {
    if (!this.pageAlive || this.unsubscribeRunning !== null) return;
    this.unsubscribeRunning = this.ports.getRunService().subscribeRunning((ids: string[]): void => {
      if (!this.pageAlive) return;
      const removed: boolean = this.runningIds.some((id: string): boolean => ids.indexOf(id) < 0);
      // 两栏时右侧新会话首条消息即开跑:列表里还没有它,补一次刷新让左栏出现该行
      const added: boolean = ids.some((id: string): boolean =>
        !this.items.some((item: ConversationSummary): boolean => item.id === id));
      this.runningIds = ids;
      if (removed || added) this.refresh();
    });
  }

  stopRunningSubscription(): void {
    const unsubscribe: (() => void) | null = this.unsubscribeRunning;
    this.unsubscribeRunning = null;
    if (unsubscribe !== null) unsubscribe();
  }

  refresh(): void {
    const repo = this.ports.getRepository();
    const seq: number = ++this.refreshSeq;
    const future: Promise<ConversationSummary[]> = repo.list();
    future.then((list: ConversationSummary[]): void => {
      if (seq !== this.refreshSeq || !this.pageAlive) return;
      const pendingId: string = this.pendingDeleted !== null ? this.pendingDeleted.id : '';
      const visible: ConversationSummary[] = list.filter(
        (item: ConversationSummary): boolean => item.id !== pendingId && item.id !== shared.pendingDeleteId);
      shared.cachedItems = visible;
      // 首次填充走 animateTo,让列表项交错入场 transition 生效;后续刷新保持即时
      if (!this.loaded && visible.length > 0) {
        animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => {
          this.items = visible;
          this.loaded = true;
        });
      } else {
        this.items = visible;
        this.loaded = true;
      }

      // 会话行条数: F08 限并发 getById(8) + 聚合后一次批量提交。
      this.loadCounts(visible, seq);
    });
  }

  // F08:受限并发拉取条数;结果先聚合到局部 Record,全部完成后按 seq 校验一次提交。

  private loadCounts(list: ConversationSummary[], seq: number): void {
    const repo = this.ports.getRepository();
    const nextCounts: Record<string, number> = {};
    for (const key of Object.keys(this.msgCounts)) nextCounts[key] = this.msgCounts[key];
    let cursor: number = 0;
    const worker = async (): Promise<void> => {
      while (cursor < list.length) {
        if (seq !== this.refreshSeq || !this.pageAlive) return;
        const summary: ConversationSummary = list[cursor];
        cursor++;
        if (nextCounts[summary.id] !== undefined && shared.cachedCountStamps[summary.id] === summary.updateAt) continue;
        let count: number = 0;
        try {
          // 只取节点总数;size=0 不加载消息,避免列表为计数解析所有图片 blob。
          count = (await repo.getConversationNodePage(summary.id, 0, 0)).totalNodeCount;
        } catch (_e) {
          continue;
        }
        if (seq !== this.refreshSeq) continue;
        nextCounts[summary.id] = count;
        shared.cachedCountStamps[summary.id] = summary.updateAt;
      }
    };
    const workers: Promise<void>[] = [];
    const workerCount: number = Math.min(PREVIEW_CONCURRENCY, list.length);
    for (let w = 0; w < workerCount; w++) workers.push(worker());
    Promise.all(workers).then((): void => {
      if (seq !== this.refreshSeq || !this.pageAlive) return;
      this.msgCounts = nextCounts;
      shared.cachedCounts = nextCounts;
    }).catch((): void => {});
  }

  togglePin(item: ConversationSummary): void {
    this.ports.getRunService().mutateStoredConversation(item.id, (latest: Conversation): Conversation =>
      patchConversation(latest, { isPinned: !latest.isPinned })).then((updated: Conversation | null): void => {
      if (updated !== null) this.refresh();
    });
  }

  onDelete(item: ConversationSummary): void {
    if (this.snackVisible && this.pendingDeleted !== null) {
      this.commitPendingDelete();
    }
    const repo = this.ports.getRepository();
    repo.getById(item.id).then((fullConversation: Conversation | null): void => {
      if (fullConversation === null) return;
      this.pendingDeleted = fullConversation;
      shared.pendingDeleteId = fullConversation.id;
      animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
        this.snackVisible = true;
      });
      this.refresh();
      this.snackTimer = setTimeout((): void => {
        this.commitPendingDelete();
      }, SNACK_DURATION_MS);
      // 两栏删的是右侧正打开的会话:右栏换成新对话,不能停在待删会话上继续写
      //   (换页会销毁本左栏并立即提交删除,撤销条随之消失)
      this.activeDeleted(fullConversation.id);
    }).catch((error: Error): void => {
      promptAction.showToast({ message: `删除失败：${error.message}`, duration: 1800 });
    });
  }

  private clearSnackTimer(): void {
    if (this.snackTimer >= 0) {
      clearTimeout(this.snackTimer);
      this.snackTimer = -1;
    }
  }

  undoDelete(): void {
    this.pendingDeleted = null;
    shared.pendingDeleteId = '';
    this.clearSnackTimer();
    animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
      this.snackVisible = false;
    });
    this.refresh();
  }

  private commitPendingDelete(): void {
    const fullConversation: Conversation | null = this.pendingDeleted;
    this.pendingDeleted = null;
    this.clearSnackTimer();
    animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
      this.snackVisible = false;
    });
    if (fullConversation === null) return;
    const settle = (): void => {
      if (shared.pendingDeleteId === fullConversation.id) shared.pendingDeleteId = '';
      bumpHomeListVersion();
    };
    if (this.ports.getRunService().isBusy(fullConversation.id)) {
      if (this.pageAlive) {
        promptAction.showToast({ message: '该对话仍在运行，请等待任务结束后再删除', duration: 2000 });
      }
      settle();
      return;
    }
    const repo = this.ports.getRepository();
    const commit = async (): Promise<void> => {
      const release: () => void = beginConversationRestore([fullConversation.id]);
      try {
        await repo.delete(fullConversation.id);
        this.ports.getRunService().forgetDeletedConversation(fullConversation.id);
        await this.ports.cleanupDeletedConversation(fullConversation, repo, this.ports.getKv());
      } finally { release(); }
    };
    commit()
      .then((): void => { settle(); })
      .catch((error: Error): void => {
        settle();
        if (!this.pageAlive) return;
        promptAction.showToast({ message: `删除清理失败：${error.message}`, duration: 2000 });
      });
  }

  messageCount(id: string): number {
    const count: number | undefined = this.msgCounts[id];
    return count === undefined ? 0 : count;
  }
}
