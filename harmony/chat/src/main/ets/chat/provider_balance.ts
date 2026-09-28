import type { ProviderSetting } from './provider_settings.ts';

// 仅用于内存中的余额请求比较，含凭据，不可写入日志或持久化。
export const providerBalanceRequestIdentity = (provider: ProviderSetting): string =>
  JSON.stringify([
    provider.id,
    `${provider.baseUrl.replace(/\/+$/, '')}${provider.balanceOption.apiPath}`,
    provider.apiKey,
    provider.balanceOption.resultPath,
  ]);
