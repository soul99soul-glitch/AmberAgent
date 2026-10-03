import { createPreferencesStorage } from '../platform_impl/PreferencesStorage.ets';
import { NovelComposerDraftStore } from './NovelComposerState';

// 页面离开后重进也等待同一个写队列，不能在旧页 flush 完成前读到旧稿。
const composerDraftStore: NovelComposerDraftStore = new NovelComposerDraftStore(createPreferencesStorage());
export const getNovelComposerDraftStore = (): NovelComposerDraftStore => composerDraftStore;
