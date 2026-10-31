import { createPreferencesStorage } from '../platform_impl/PreferencesStorage.ets';
import { createEntrySecretFieldStore } from '../platform_impl/ProviderKeyVault.ets';
import { NovelPlanDraftStore } from './NovelPlanDraftState';

// 草稿键均非密;注入凭据库只为满足 Storage 的构造契约(P-1 静息凭据边界)
const planDraftStore: NovelPlanDraftStore =
  new NovelPlanDraftStore(createPreferencesStorage(createEntrySecretFieldStore()));
export const getNovelPlanDraftStore = (): NovelPlanDraftStore => planDraftStore;
