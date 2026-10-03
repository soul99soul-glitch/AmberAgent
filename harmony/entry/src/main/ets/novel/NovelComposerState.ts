// 纯状态规则：段落筛选、候选默认位置与输入恢复的 owner 边界。
export type NovelComposeMode = 'discuss' | 'continue' | 'whole_chapter';

export interface NovelComposerDraft {
  text: string;
  mode: NovelComposeMode;
}

export interface NovelComposerDraftStorage {
  get<T>(key: string, defaultValue: T): Promise<T>;
  set<T>(key: string, value: T): Promise<void>;
}

export class NovelComposerDraftStore {
  private storage: NovelComposerDraftStorage;
  private pending: Promise<void> = Promise.resolve();

  constructor(storage: NovelComposerDraftStorage) {
    this.storage = storage;
  }

  async load(owner: string): Promise<NovelComposerDraft> {
    await this.pending;
    const text: string = await this.storage.get<string>(`novel.composer.${owner}`, '');
    if (text.length === 0) return { text: '', mode: 'whole_chapter' };
    const draft: NovelComposerDraft = JSON.parse(text) as NovelComposerDraft;
    if (draft.mode !== 'discuss' && draft.mode !== 'continue' && draft.mode !== 'whole_chapter') {
      throw new Error('创作草稿模式无效');
    }
    return draft;
  }

  save(owner: string, draft: NovelComposerDraft): Promise<void> {
    const value: string = JSON.stringify(draft);
    const write: Promise<void> = this.pending.then((): Promise<void> =>
      this.storage.set<string>(`novel.composer.${owner}`, value));
    // 失败由页面显示；后续用户编辑仍能重新保存。
    this.pending = write.catch((): void => {});
    return write;
  }
}

export interface CollectionChapterRef {
  id: string;
  discarded?: boolean;
}

export interface CollectionLocation {
  mode: number;
  chapterId: string;
}

export const selectedParagraphDraft = (paragraphs: string[], selected: boolean[]): string => {
  const parts: string[] = [];
  for (let index: number = 0; index < paragraphs.length; index++) {
    if (selected[index]) parts.push(paragraphs[index]);
  }
  return parts.join('\n\n');
};

export const draftAfterSelection = (
  paragraphs: string[], selected: boolean[], draft: string, manuallyEdited: boolean,
): string => manuallyEdited ? draft : selectedParagraphDraft(paragraphs, selected);

export const canCollectSelection = (selected: boolean[], draft: string): boolean =>
  selected.some((value: boolean): boolean => value) && draft.trim().length > 0;

export const defaultCollectionLocation = (
  kind: string, sourceChapterId: string | null, granularity: string | null,
  chapters: CollectionChapterRef[],
): CollectionLocation => {
  if (kind === 'regenerate' || kind === 'polish') {
    const sourceExists: boolean = chapters.some(
      (chapter: CollectionChapterRef): boolean => chapter.id === sourceChapterId);
    return { mode: 2, chapterId: sourceExists ? sourceChapterId as string : '' };
  }
  const available: CollectionChapterRef[] = chapters.filter(
    (chapter: CollectionChapterRef): boolean => chapter.discarded !== true);
  const lastId: string = available.length > 0 ? available[available.length - 1].id : '';
  return { mode: granularity === 'continuation' && lastId.length > 0 ? 1 : 0, chapterId: lastId };
};

export const composerOwner = (projectId: string, branchId: string): string =>
  JSON.stringify([projectId, branchId]);

export const canRestoreFailedInput = (
  sentOwner: string, currentOwner: string, sentRevision: number, currentRevision: number,
  currentDraft: string,
): boolean => sentOwner === currentOwner && sentRevision === currentRevision && currentDraft.length === 0;
