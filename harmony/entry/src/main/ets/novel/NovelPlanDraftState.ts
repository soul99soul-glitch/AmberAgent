import type { NovelComposerDraftStorage } from './NovelComposerState';

export interface NovelControlPlanDraft {
  thisChapterPlan: string;
  futurePlan: string;
  preferences: string;
}

export const sameControlPlanDraft = (left: NovelControlPlanDraft, right: NovelControlPlanDraft): boolean =>
  left.thisChapterPlan === right.thisChapterPlan && left.futurePlan === right.futurePlan &&
  left.preferences === right.preferences;

// 与正式分支设置分开保存；读者等待旧页面的写队列，避免返回时恢复旧稿。
export class NovelPlanDraftStore {
  private storage: NovelComposerDraftStorage;
  private pending: Promise<void> = Promise.resolve();

  constructor(storage: NovelComposerDraftStorage) { this.storage = storage; }

  async load(owner: string): Promise<NovelControlPlanDraft | null> {
    await this.pending;
    const raw: string = await this.storage.get<string>(`novel.plan-draft.${owner}`, '');
    if (raw.length === 0) return null;
    const draft: NovelControlPlanDraft = JSON.parse(raw) as NovelControlPlanDraft;
    if (draft === null || typeof draft.thisChapterPlan !== 'string' || typeof draft.futurePlan !== 'string' ||
      typeof draft.preferences !== 'string') throw new Error('计划草稿格式无效');
    return draft;
  }

  save(owner: string, draft: NovelControlPlanDraft | null): Promise<void> {
    const value: string = draft === null ? '' : JSON.stringify(draft);
    const write: Promise<void> = this.pending.then((): Promise<void> =>
      this.storage.set<string>(`novel.plan-draft.${owner}`, value));
    // 保留失败给调用者；一次磁盘错误不阻断下一次编辑的保存。
    this.pending = write.catch((): void => {});
    return write;
  }
}
