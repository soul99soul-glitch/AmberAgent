import { createPreferencesStorage } from '../platform_impl/PreferencesStorage.ets';
import { NovelPlanDraftStore } from './NovelPlanDraftState';

const planDraftStore: NovelPlanDraftStore = new NovelPlanDraftStore(createPreferencesStorage());
export const getNovelPlanDraftStore = (): NovelPlanDraftStore => planDraftStore;
