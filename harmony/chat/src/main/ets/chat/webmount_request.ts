import type { AbortSignalLike, HttpClient, HttpRequest, HttpResponse } from '@amber/deepread-domain';

export type WebMountEnabledCheck = () => Promise<boolean>;

const throwIfCancelled = (signal?: AbortSignalLike): void => {
  if (signal !== undefined && signal.aborted) {
    const error: Error = new Error('WebMount cancelled');
    error.name = 'AbortError';
    throw error;
  }
};

export const assertWebMountRequestActive = async (
  checkEnabled: WebMountEnabledCheck, signal?: AbortSignalLike,
): Promise<void> => {
  throwIfCancelled(signal);
  const enabled: boolean = await checkEnabled();
  throwIfCancelled(signal);
  if (!enabled) throw new Error('WebMount is disabled for this conversation');
};

// WebMount 和飞书共用的真实请求边界；许可沿用宿主的 global || assistant 规则。
export const fetchWebMountRequest = async (
  http: HttpClient, request: HttpRequest, checkEnabled: WebMountEnabledCheck,
  signal?: AbortSignalLike,
): Promise<HttpResponse> => {
  await assertWebMountRequestActive(checkEnabled, signal);
  const response: HttpResponse = await http.fetch(request, { signal: signal });
  throwIfCancelled(signal);
  return response;
};
