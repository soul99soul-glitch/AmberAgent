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

// 读状态:缺失 / 损坏 / IO 错必须区分——
// 旧实现把所有解析失败压成 [] 再写回,一次损坏就整库丢失(R10)。
export type FavoriteReadStatus = 'ok' | 'missing' | 'blocked' | 'io_error';

interface FavoriteDecode {
  status: FavoriteReadStatus;
  items: FavoriteEntry[];
}

// 本进程一旦发现损坏/读失败,在其被显式恢复前锁定写路径。
// (KV 存储是本地单例,进程内 latch 即可阻断「错误基准覆盖」;无需回写 KV 以免
//  存储层自身故障时把标记也写坏)
let blockedReason: string = '';

const toEntries = (arr: FavoriteJson[]): FavoriteEntry[] => {
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
};

const decodeStatus = (raw: string | null): FavoriteDecode => {
  if (raw === null) {
    const missing: FavoriteDecode = { status: 'missing', items: [] };
    return missing;
  }
  let items: FavoriteEntry[] | null = null;
  try {
    const arr: FavoriteJson[] = JSON.parse(raw) as FavoriteJson[];
    if (Array.isArray(arr)) items = toEntries(arr);
  } catch {
    items = null;
  }
  if (items === null) {
    const corrupt: FavoriteDecode = { status: 'blocked', items: [] };
    return corrupt;
  }
  const ok: FavoriteDecode = { status: 'ok', items };
  return ok;
};

// KV 自身抛错(与解析损坏不同)显式区分:不吞成「空收藏」
const readFavorites = async (): Promise<FavoriteDecode> => {
  let raw: string | null;
  try {
    raw = await getChatKvStore().get(FAVORITES_KEY);
  } catch {
    blockedReason = '收藏数据读取失败,已阻止写入';
    const failed: FavoriteDecode = { status: 'io_error', items: [] };
    return failed;
  }
  const outcome: FavoriteDecode = decodeStatus(raw);
  if (outcome.status === 'blocked') {
    blockedReason = '收藏数据损坏,已阻止写入以保留原值(需导出/恢复后处理)';
  }
  return outcome;
};

// 写路径统一入口:blocked/io_error 时抛错,绝不拿空列表覆盖
const requireWritable = async (): Promise<FavoriteEntry[]> => {
  const outcome: FavoriteDecode = await readFavorites();
  if (outcome.status === 'blocked' || outcome.status === 'io_error') {
    throw new Error(blockedReason.length > 0 ? blockedReason : '收藏数据不可用,拒绝覆盖');
  }
  return outcome.items;
};

export const getFavoritesStatus = (): FavoriteReadStatus =>
  blockedReason.length > 0 ? 'blocked' : 'ok';

const encode = (list: FavoriteEntry[]): string => JSON.stringify(list);

export const listFavorites = async (): Promise<FavoriteEntry[]> => {
  const outcome: FavoriteDecode = await readFavorites();
  const list: FavoriteEntry[] = outcome.items;
  list.sort((a: FavoriteEntry, b: FavoriteEntry): number => b.createdAt - a.createdAt);
  return list;
};

// 写串行:收藏增删都是 read-modify-write,快速连续操作会互相覆盖
// (同时收藏两条只剩一条;删除与新增并发时删除被旧快照写回)
let favoriteWriteTail: Promise<void> = Promise.resolve();

const enqueueFavoriteWrite = (op: () => Promise<void>): Promise<void> => {
  const run: Promise<void> = favoriteWriteTail.catch((): void => {}).then(op);
  favoriteWriteTail = run.then((): void => {}, (): void => {});
  return run;
};

export const addFavorite = (entry: FavoriteEntry): Promise<void> =>
  enqueueFavoriteWrite(async (): Promise<void> => {
    const list: FavoriteEntry[] = await requireWritable();
    list.push(entry);
    await getChatKvStore().put(FAVORITES_KEY, encode(list));
    blockedReason = '';
  });

export const removeFavorite = (id: string): Promise<void> =>
  enqueueFavoriteWrite(async (): Promise<void> => {
    const next: FavoriteEntry[] =
      (await requireWritable()).filter((f: FavoriteEntry): boolean => f.id !== id);
    await getChatKvStore().put(FAVORITES_KEY, encode(next));
    blockedReason = '';
  });

// 会话删除级联(对齐 FavoriteDAO.deleteByConversation)
export const removeFavoritesOfConversation = (conversationId: string): Promise<void> =>
  enqueueFavoriteWrite(async (): Promise<void> => {
    const next: FavoriteEntry[] = (await requireWritable())
      .filter((f: FavoriteEntry): boolean => f.conversationId !== conversationId);
    await getChatKvStore().put(FAVORITES_KEY, encode(next));
    blockedReason = '';
  });
