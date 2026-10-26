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
