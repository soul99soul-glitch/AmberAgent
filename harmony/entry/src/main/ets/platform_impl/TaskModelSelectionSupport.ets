import type { KeyValueStore, TaskModelPair, TaskModelReference } from '@amber/chat-domain';
import { DEFAULT_AUTO_MODEL_ID, decodeTaskModelReference, encodeTaskModelReference } from '@amber/chat-domain';

export { findTaskProviderModel } from '@amber/chat-domain';

export const loadTaskModelReference = async (
  kv: KeyValueStore, key: string,
): Promise<TaskModelReference> => decodeTaskModelReference(await kv.get(key));

export const storeTaskModelPair = async (
  kv: KeyValueStore, key: string, pair: TaskModelPair,
): Promise<void> => {
  await kv.put(key, encodeTaskModelReference({ kind: 'fixed', pair }));
};

export const clearTaskModelReference = async (
  kv: KeyValueStore, key: string, defaultValue: string = DEFAULT_AUTO_MODEL_ID,
): Promise<void> => {
  await kv.put(key, defaultValue);
};
