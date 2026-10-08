// FavoriteStore — 收藏最小真实存储(KV JSON,对齐 Android FavoriteDAO 语义子集)
//
// Android 基准: core/model Favorite.kt + FavoriteDAO(按 conversation 级联删除)。
//   鸿蒙 RDB 无 favorites 表(cleanupDeletedConversation 注释登记),
//   用 KV 单键 JSON 数组落盘:列表/新增/删除/按会话级联。

import { nodeCurrentMessage, toText } from '@amber/chat-domain';
import type { Conversation, MessageNode } from '@amber/chat-domain';
import { getChatKvStore, getChatRepository } from '../di/AppContainer.ets';

const FAVORITES_KEY: string = 'favorites_json';

export interface FavoriteEntry {
  id: string;
  title: string;
  content: string;
  conversationId: string;
  // Legacy text favorites have no node reference and still open their conversation.
  nodeId?: string;
  createdAt: number;
}

interface FavoriteJson {
  id?: string;
  title?: string;
  content?: string;
  conversationId?: string;
  nodeId?: string;
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
      nodeId: j.nodeId ?? '',
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
    if (list.some((item: FavoriteEntry): boolean => item.id === entry.id
      || ((entry.nodeId ?? '').length > 0 && item.conversationId === entry.conversationId && item.nodeId === entry.nodeId))) return;
    list.push(entry);
    await getChatKvStore().put(FAVORITES_KEY, encode(list));
    blockedReason = '';
  });

export const listFavoriteNodeIds = async (conversationId: string): Promise<string[]> => {
  const entries: FavoriteEntry[] = await listFavorites();
  return entries.filter((entry: FavoriteEntry): boolean => entry.conversationId === conversationId
    && (entry.nodeId ?? '').length > 0).map((entry: FavoriteEntry): string => entry.nodeId ?? '');
};

export const setNodeFavorite = (conversationId: string, nodeId: string, selected: boolean): Promise<void> =>
  enqueueFavoriteWrite(async (): Promise<void> => {
    if (nodeId.length === 0) throw new Error('消息节点不存在');
    const list: FavoriteEntry[] = await requireWritable();
    const matches = (entry: FavoriteEntry): boolean => entry.conversationId === conversationId && entry.nodeId === nodeId;
    if (!selected) {
      const next: FavoriteEntry[] = list.filter((entry: FavoriteEntry): boolean => !matches(entry));
      if (next.length === list.length) return;
      await getChatKvStore().put(FAVORITES_KEY, encode(next));
      blockedReason = '';
      return;
    }
    if (list.some(matches)) return;
    const conversation: Conversation | null = await getChatRepository().getById(conversationId);
    if (conversation === null) throw new Error('来源会话已不存在');
    const node: MessageNode | undefined = conversation.messageNodes.find((item: MessageNode): boolean => item.id === nodeId);
    if (node === undefined) throw new Error('待收藏消息已不存在');
    const message = nodeCurrentMessage(node);
    const text: string = toText(message).trim();
    const preview: string = text.length > 0 ? text : message.role === 'user' ? '[用户消息]' : '[助手消息]';
    const entry: FavoriteEntry = {
      id: `node:${conversationId}:${nodeId}`, conversationId, nodeId,
      title: conversation.title.trim().length > 0 ? conversation.title : preview.split('\n')[0].substring(0, 40),
      content: preview, createdAt: Date.now(),
    };
    list.push(entry);
    await getChatKvStore().put(FAVORITES_KEY, encode(list));
    blockedReason = '';
  });

export interface FavoriteSource {
  conversationId: string;
  nodeId: string;
}

export const resolveFavoriteSource = async (favorite: FavoriteEntry): Promise<FavoriteSource> => {
  const conversation: Conversation | null = await getChatRepository().getById(favorite.conversationId);
  if (conversation === null) throw new Error('来源会话已不存在');
  const nodeId: string = favorite.nodeId ?? '';
  if (nodeId.length > 0 && !conversation.messageNodes.some((node: MessageNode): boolean => node.id === nodeId)) {
    throw new Error('原消息已删除，无法定位');
  }
  return { conversationId: conversation.id, nodeId };
};

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
