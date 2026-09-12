// KvResponseResumeStore — P6-01 resume cursor 的 KV 落盘 Port
// 键: responses-resume/<runId>  → ResponseCursor JSON
// 原子: KV 单键 put/get/delete

import type { KeyValueStore } from './kv_store.ts';
import type { ResponseCursor, ResponseResumeStore } from './openai_responses_request.ts';

export const responsesResumeKey = (runId: string): string =>
  `responses-resume/${runId}`;

export const createKvResponseResumeStore = (kv: KeyValueStore): ResponseResumeStore => ({
  save: async (runId: string, responseId: string, sequence: number, providerId: string): Promise<void> => {
    const cursor: ResponseCursor = { responseId, sequence, providerId };
    await kv.put(responsesResumeKey(runId), JSON.stringify(cursor));
  },
  load: async (runId: string): Promise<ResponseCursor | null> => {
    const raw: string | null = await kv.get(responsesResumeKey(runId));
    if (raw === null || raw.length === 0) return null;
    try {
      const parsed: unknown = JSON.parse(raw);
      if (typeof parsed !== 'object' || parsed === null) return null;
      const obj = parsed as Record<string, unknown>;
      if (typeof obj['responseId'] !== 'string' || typeof obj['sequence'] !== 'number'
        || typeof obj['providerId'] !== 'string') {
        return null;
      }
      return {
        responseId: obj['responseId'],
        sequence: obj['sequence'],
        providerId: obj['providerId'],
      };
    } catch {
      return null;
    }
  },
  clear: async (runId: string): Promise<void> => {
    await kv.delete(responsesResumeKey(runId));
  },
});
