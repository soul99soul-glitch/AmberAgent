// Storage — preferences 字符串键值层
//
// 键值层本身不区分密与非密;静息加密由设备端适配器
// entry/platform_impl/PreferencesStorage.ets 统一处理:
//   - providers / model_configs / search_services:落盘前经
//     secret_fields_blob.sealSecretFields 剥离 apiKey|privateKey|password
//   - *_api_key(ai_api_key、tavily/exa/bocha/brave 等检索服务键):经
//     sealScalarSecret 整体加密
// 真值由 ProviderKeyVault(Asset 主密钥 + AES-GCM)保管;SSH / Google / WebMount
// 各有自己的 Asset 封装。曾经存在的 getSecret/setSecret/deleteSecret 会把「密钥」
// 直接写进 Preferences,是无调用方的死路径,已删除以免未来误用。

export interface Storage {
  get<T>(key: string, defaultValue: T): Promise<T>;
  set<T>(key: string, value: T): Promise<void>;
  delete(key: string): Promise<void>;
  // 全部字符串键值枚举(Preferences getAll 语义;供配额核算等前缀扫描)
  getAllStringEntries(): Promise<Array<{ key: string; value: string }>>;
}
