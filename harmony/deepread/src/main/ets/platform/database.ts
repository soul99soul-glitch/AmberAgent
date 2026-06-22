// Database — 鸿蒙 ArkData relationalStore 封装的接口契约
// 实现留后续(RdbDatabase 用 @kit.ArkData 的 relationalStore.getRdbStore)

export interface Row {
  [column: string]: string | number | null;
}

export interface Database {
  execute(sql: string, params?: Array<string | number | null>): Promise<void>;
  query(sql: string, params?: Array<string | number | null>): Promise<Row[]>;
  transaction<T>(fn: (tx: Transaction) => Promise<T>): Promise<T>;
}

export interface Transaction {
  execute(sql: string, params?: Array<string | number | null>): Promise<void>;
  query(sql: string, params?: Array<string | number | null>): Promise<Row[]>;
}

// Deep Read RDB schema — 对应 Android deep_read_cache
// 鸿蒙新增列:attempt_count / last_error(WorkManager 没有 runAttemptCount,自己存)
export const DEEP_READ_SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS deep_read_cache (
  topic_id      TEXT PRIMARY KEY,
  title         TEXT NOT NULL,
  source_url    TEXT,
  output_json   TEXT NOT NULL,
  phase         TEXT NOT NULL,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error    TEXT,
  created_at    INTEGER NOT NULL,
  updated_at    INTEGER NOT NULL,
  expires_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_drc_expires ON deep_read_cache(expires_at);
CREATE INDEX IF NOT EXISTS idx_drc_topic   ON deep_read_cache(topic_id);
CREATE INDEX IF NOT EXISTS idx_drc_title   ON deep_read_cache(title);
`.trim();
