import type { ProviderSetting } from '@amber/chat-domain';

// 模板新增流使用进程 AppStorage 单槽,避免路由页面间模块变量不共享。
const PENDING_PROVIDER_KEY: string = 'chatPendingProviderDraft';

export const setPendingProvider = (p: ProviderSetting | null): void => {
  if (p === null) AppStorage.delete(PENDING_PROVIDER_KEY);
  else AppStorage.setOrCreate<ProviderSetting>(PENDING_PROVIDER_KEY, p);
};

export const takePendingProvider = (): ProviderSetting | null => {
  const provider: ProviderSetting | null = AppStorage.get<ProviderSetting>(PENDING_PROVIDER_KEY) ?? null;
  AppStorage.delete(PENDING_PROVIDER_KEY);
  return provider;
};

