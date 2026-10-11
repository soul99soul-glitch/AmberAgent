import { fileIo } from '@kit.CoreFileKit';
import { url } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';

export const MODEL_ENTERPRISE_CA_FILE_NAME: string = 'model-enterprise-ca.pem';
// caPath replaces the system preset CA, so provisioning must include public roots plus the enterprise root.

// Xiaomi enterprise proxy covers these seven provider endpoints; exact-host matching is intentional.
export const MODEL_ENTERPRISE_CA_HOSTS: string[] = [
  'open.bigmodel.cn',
  'api.kimi.com',
  'api.minimax.cn',
  'opencode.ai',
  'api.commandcode.ai',
  'api.deepseek.com',
  'token-plan.cn-beijing.maas.aliyuncs.com',
  // Compatibility endpoints used by existing provider settings.
  'api.minimaxi.com',
  'api.moonshot.cn',
];

const normalizedHostname = (rawUrl: string): string => {
  let parsed: url.URL;
  try {
    parsed = url.URL.parseURL(rawUrl);
  } catch (_error) {
    return '';
  }
  if (parsed.protocol.toLowerCase() !== 'https:') return '';
  if (parsed.username.length > 0 || parsed.password.length > 0) return '';
  let hostname: string = parsed.hostname.toLowerCase();
  if (hostname.endsWith('.')) hostname = hostname.slice(0, hostname.length - 1);
  return hostname;
};

export const isModelEnterpriseCaHost = (rawUrl: string): boolean => {
  const hostname: string = normalizedHostname(rawUrl);
  return hostname.length > 0 && MODEL_ENTERPRISE_CA_HOSTS.indexOf(hostname) >= 0;
};

export const modelEnterpriseCaPath = (): string | undefined => {
  const context: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (context === undefined || context.filesDir.length === 0) return undefined;
  const path: string = `${context.filesDir}/${MODEL_ENTERPRISE_CA_FILE_NAME}`;
  try {
    return fileIo.accessSync(path) ? path : undefined;
  } catch (_error) {
    return undefined;
  }
};

export const modelEnterpriseCaPathForUrl = (rawUrl: string): string | undefined =>
  isModelEnterpriseCaHost(rawUrl) ? modelEnterpriseCaPath() : undefined;
