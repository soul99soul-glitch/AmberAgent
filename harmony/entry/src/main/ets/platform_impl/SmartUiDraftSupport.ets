import type { KeyValueStore, SmartUiDraftStore } from '@amber/chat-domain';
import { createSmartUiDraftStore } from '@amber/chat-domain';

// Share pending KV writes with deletion and backup restore; UI values stay on ChatPage.
let draftStore: SmartUiDraftStore | null = null;

export const getChatSmartUiDraftStore = (kv: KeyValueStore): SmartUiDraftStore => {
  if (draftStore === null) draftStore = createSmartUiDraftStore(kv);
  return draftStore;
};
