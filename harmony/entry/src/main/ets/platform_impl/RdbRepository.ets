// RdbRepository — Database + Repository 契约的真实实现
// @kit.ArkData relationalStore
// 对照设计 §3.4(RDB schema) + §3.6(Repository interface)
//
// DEEP_READ_SCHEMA_SQL 已在 deepread/domain/database.ts 定义(Plan 1.2)。
// TODO(toolchain/ArkTS 验证): relationalStore.getRdbStore / executeSql 签名以 API 24 为准。

import { relationalStore } from '@kit.ArkData';
import type { DeepReadOutput, DeepReadRepository, Storage } from '@amber/deepread-domain';
import { DEEP_READ_SCHEMA_SQL } from '@amber/deepread-domain';
import { pollingObservable } from '@amber/deepread-domain';

interface RdbContext {
  store: relationalStore.RdbStore;
}

const STORE_NAME = 'deep_read_cache.db';

export const createRdbRepository = (_storage: Storage): DeepReadRepository => {
  let ctx: RdbContext | null = null;

  const init = async (): Promise<RdbContext> => {
    if (ctx !== null) return ctx;
    // TODO(arkts): context 由 UIAbility 传入;这里需要 ability context。
    // MVP: 用 globalThis.__abilityContext(由 EntryAbility 写入)
    const context = (globalThis as Record<string, object>).__abilityContext as unknown as Context;
    const config: relationalStore.StoreConfig = {
      name: STORE_NAME,
      securityLevel: relationalStore.SecurityLevel.S1,
    };
    const store = await relationalStore.getRdbStore(context, config);
    // 建 schema:DEEP_READ_SCHEMA_SQL 是含多条 CREATE TABLE IF NOT EXISTS 的 SQL 文本,
    // 按 ';' 分割逐条执行(每条非空)
    const statements = DEEP_READ_SCHEMA_SQL.split(';').map(s => s.trim()).filter(s => s.length > 0);
    for (const sql of statements) {
      await store.executeSql(sql);
    }
    ctx = { store };
    return ctx;
  };

  const getDeepRead = async (topicId: string): Promise<DeepReadOutput | null> => {
    const { store } = await init();
    const pred = new relationalStore.RdbPredicates('deep_read_cache');
    pred.equalTo('topic_id', topicId);
    const rs = await store.query(pred, ['output_json', 'updated_at']);
    let result: DeepReadOutput | null = null;
    if (rs.goToNextRow()) {
      const json = rs.getString(rs.getColumnIndex('output_json'));
      try {
        result = JSON.parse(json) as DeepReadOutput;
      } catch {
        result = null;
      }
    }
    rs.close();
    return result;
  };

  const saveDeepRead = async (topicId: string, output: DeepReadOutput): Promise<void> => {
    const { store } = await init();
    const json = JSON.stringify(output);
    const now = Date.now();
    // TODO(arkts): upsert 语法(relationalStore insertWithConflictResolution ON CONFLICT REPLACE)
    const pred = new relationalStore.RdbPredicates('deep_read_cache');
    pred.equalTo('topic_id', topicId);
    const existing = await store.query(pred, ['topic_id']);
    const has = existing.goToNextRow();
    existing.close();
    if (has) {
      const upd = new relationalStore.RdbPredicates('deep_read_cache');
      upd.equalTo('topic_id', topicId);
      await store.update({ output_json: json, updated_at: now } as Record<string,ValueType>, upd);
    } else {
      await store.insert('deep_read_cache', { topic_id: topicId, output_json: json, updated_at: now } as Record<string, ValueType>);
    }
  };

  const clearDeepRead = async (topicId: string): Promise<void> => {
    const { store } = await init();
    const pred = new relationalStore.RdbPredicates('deep_read_cache');
    pred.equalTo('topic_id', topicId);
    await store.delete(pred);
  };

  const observeDeepRead = (topicId: string) => {
    // MVP 轮询(对照 observable.ts pollingObservable,1s)
    return pollingObservable(async () => {
      const o = await getDeepRead(topicId);
      return o ?? ({} as DeepReadOutput);
    }, { intervalMs: 1000 });
  };

  return { getDeepRead, saveDeepRead, clearDeepRead, observeDeepRead };
};
