import { createPreferencesStorage } from '../platform_impl/PreferencesStorage.ets';
import { createEntrySecretFieldStore } from '../platform_impl/ProviderKeyVault.ets';
import { NovelComposerDraftStore } from './NovelComposerState';

// 页面离开后重进也等待同一个写队列，不能在旧页 flush 完成前读到旧稿。
// 草稿键均非密;注入凭据库只为满足 Storage 的构造契约(P-1 静息凭据边界)
const composerDraftStore: NovelComposerDraftStore =
  new NovelComposerDraftStore(createPreferencesStorage(createEntrySecretFieldStore()));
export const getNovelComposerDraftStore = (): NovelComposerDraftStore => composerDraftStore;
