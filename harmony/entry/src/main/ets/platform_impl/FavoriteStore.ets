// FavoriteStore — 收藏最小真实存储(KV JSON,对齐 Android FavoriteDAO 语义子集)
//
// Android 基准: core/model Favorite.kt + FavoriteDAO(按 conversation 级联删除)。
//   鸿蒙 RDB 无 favorites 表(cleanupDeletedConversation 注释登记),
//   用 KV 单键 JSON 数组落盘:列表/新增/删除/按会话级联。

import { getChatKvStore } from '../di/AppContainer.ets';

const FAVORITES_KEY: string = 'favorites_json';

export interface FavoriteEntry {
  id: string;
  title: string;
  content: string;
  conversationId: string;
  createdAt: number;
}

interface FavoriteJson {
  id?: string;
  title?: string;
  content?: string;
  conversationId?: string;
  createdAt?: number;
}

const decode = (raw: string | null): FavoriteEntry[] => {
  if (raw === null) return [];
  try {
    const arr: FavoriteJson[] = JSON.parse(raw) as FavoriteJson[];
    const out: FavoriteEntry[] = [];
    for (const j of arr) {
      out.push({
        id: j.id ?? '',
        title: j.title ?? '',
        content: j.content ?? '',
        conversationId: j.conversationId ?? '',
        createdAt: j.createdAt ?? 0,
      });
    }
    return out;
  } catch (e) {
    return [];
  }
};

const encode = (list: FavoriteEntry[]): string => JSON.stringify(list);

export const listFavorites = async (): Promise<FavoriteEntry[]> => {
  const raw: string | null = await getChatKvStore().get(FAVORITES_KEY);
  const list: FavoriteEntry[] = decode(raw);
  list.sort((a: FavoriteEntry, b: FavoriteEntry): number => b.createdAt - a.createdAt);
  return list;
};

export const addFavorite = async (entry: FavoriteEntry): Promise<void> => {
  const raw: string | null = await getChatKvStore().get(FAVORITES_KEY);
  const list: FavoriteEntry[] = decode(raw);
  list.push(entry);
  await getChatKvStore().put(FAVORITES_KEY, encode(list));
};

export const removeFavorite = async (id: string): Promise<void> => {
  const raw: string | null = await getChatKvStore().get(FAVORITES_KEY);
  const next: FavoriteEntry[] =
    decode(raw).filter((f: FavoriteEntry): boolean => f.id !== id);
  await getChatKvStore().put(FAVORITES_KEY, encode(next));
};

// 会话删除级联(对齐 FavoriteDAO.deleteByConversation)
export const removeFavoritesOfConversation = async (conversationId: string): Promise<void> => {
  const raw: string | null = await getChatKvStore().get(FAVORITES_KEY);
  const next: FavoriteEntry[] =
    decode(raw).filter((f: FavoriteEntry): boolean => f.conversationId !== conversationId);
  await getChatKvStore().put(FAVORITES_KEY, encode(next));
};
