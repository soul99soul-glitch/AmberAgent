// Storage — preferences + Asset Store 封装的接口契约
// 真密钥走 Asset Store Kit,preferences 存配置 + key-set flag

export interface Storage {
  get<T>(key: string, defaultValue: T): Promise<T>;
  set<T>(key: string, value: T): Promise<void>;
  delete(key: string): Promise<void>;
  getSecret(key: string): Promise<string | null>;
  setSecret(key: string, value: string): Promise<void>;
  deleteSecret(key: string): Promise<void>;
  // 全部字符串键值枚举(Preferences getAll 语义;供配额核算等前缀扫描)
  getAllStringEntries(): Promise<Array<{ key: string; value: string }>>;
}
