// secret_fields_blob — 配置 blob 的凭据剥离 / 回填(静息加密边界)
//
// 背景:D-017 把 apiKey/privateKey 作为模型内的静息字段,设备端 adapter 决定最终
//   存储形态(D-016)。鸿蒙端此前把 providers / model_configs / search_services 明文
//   写进 Preferences,普通模型 API Key 因此静息在明文键值库里。
//
// 本模块把「凭据在 blob 内的位置」与「凭据实际落在哪里」分开:
//   - sealSecretFields():写路径,内存值即权威。非空写库并清空字段,空值删除库条目,
//     再清理已删除记录/模型的残留。落盘前调用。
//   - migrateSecretFields():读路径,只把发现的明文搬进库,绝不删除、绝不 prune。
//     读路径看到的是「盘上的样子」,不是权威值,做删除会误伤其他已加密条目。
//   - openSecretFields():读取后按记录 id 回填,调用方(D-017 消费者)对存储形态无感。
//   - stripSecretFields():恢复路径。主密钥丢失时把全部密文字段清空、保留非密字段,
//     不触碰凭据库(库由调用方整体丢弃)。
//   - blobHasInlineSecrets():读路径是否还需要一次迁移。
//
// namespace 区分不同的持久化 blob(providers / models / search),避免同名 id 互相覆盖。
// 标量凭据(ai_api_key / <source>_api_key)用 sealScalarSecret / openScalarSecret。
//
// 三层记录的凭据位置:
//   顶层 id 行      → apiKey / privateKey / password
//   models[] 每个模型 → customHeaders(按位置存 values 数组,名称留在明文) / providerOverwrite.apiKey
//
// 语义约定:
//   - open 的 store 读取失败必须上抛,不允许静默降级成空串,否则下一次保存会把真实
//     凭据删掉。
//   - 无法安全归属的记录(缺 id / 结构异常)保持原样,不写入也不清空。
//   - 只有对象数组中带字符串 id 的条目参与迁移;整体解析失败原样返回。

// 主密钥不可用、密文字段被清空时,存储适配器写这个标记;provider 设置页读一次即清除,
// 让用户知道「密钥为什么空了」而不是静静地看着空输入框。
export const SECRET_RECOVERY_NOTICE_KEY: string = 'provider_secret_recovery';

export type SecretField = 'apiKey' | 'privateKey' | 'password' | 'customHeaders' | 'headers';

export interface SecretFieldStore {
  read(ownerKey: string, field: SecretField): Promise<string | null>;
  write(ownerKey: string, field: SecretField, value: string): Promise<void>;
  remove(ownerKey: string, field: SecretField): Promise<void>;
  /** 现存全部 ownerKey,供 seal 清理残留条目 */
  listOwnerKeys(): Promise<string[]>;
  /** 整体丢弃凭据库(主密钥不可用时唯一的恢复手段),必须幂等 */
  discardAll(): Promise<void>;
}

// 顶层行的密字段。privateKey:Google service account 私钥;password:searxng 等自建检索口令。
const FLAT_FIELDS: ReadonlyArray<SecretField> = ['apiKey', 'privateKey', 'password'];
// models[].customHeaders 的密文槽位(存 values 数组的 JSON)
const MODEL_HEADER_SLOT: SecretField = 'customHeaders';
// commonOptions.headers(MCP 服务器)的密文槽位,同样存 values 数组
const MCP_HEADER_SLOT: SecretField = 'headers';

type SecretRow = Record<string, unknown>;

const isRow = (value: unknown): value is SecretRow =>
  typeof value === 'object' && value !== null && !Array.isArray(value);

const ownerIdOf = (row: SecretRow): string => {
  const id: unknown = row['id'];
  return typeof id === 'string' ? id.trim() : '';
};

const ownerKeyOf = (namespace: string, ownerId: string): string => `${namespace}/${ownerId}`;

// 解析成「对象数组」;任何不是这个形状的输入都不做处理
const parseRows = (raw: string): SecretRow[] | null => {
  if (raw.length === 0) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    // 非 JSON 输入属于可预期的外部数据,不是异常路径:交给调用方原样放行
    return null;
  }
  // 空数组是合法状态(删掉最后一个记录),由 seal 走 prune 分支,不能当成「不处理」
  if (!Array.isArray(value)) return null;
  if (value.length === 0) return [];
  const rows: SecretRow[] = [];
  for (const item of value) {
    if (!isRow(item)) return null;
    rows.push(item);
  }
  return rows;
};

const stringFieldOf = (row: SecretRow, field: SecretField): string | null => {
  const value: unknown = row[field];
  return typeof value === 'string' ? value : null;
};

const modelRowsOf = (row: SecretRow): SecretRow[] => {
  const models: unknown = row['models'];
  if (!Array.isArray(models)) return [];
  const rows: SecretRow[] = [];
  for (const item of models) {
    if (isRow(item)) rows.push(item);
  }
  return rows;
};

const headerRowsOf = (model: SecretRow): SecretRow[] => {
  const headers: unknown = model['customHeaders'];
  if (!Array.isArray(headers)) return [];
  const rows: SecretRow[] = [];
  for (const item of headers) {
    if (isRow(item)) rows.push(item);
  }
  return rows;
};

const headerValuesOf = (headers: SecretRow[]): string[] => {
  const values: string[] = [];
  for (const header of headers) {
    const value: unknown = header['value'];
    values.push(typeof value === 'string' ? value : '');
  }
  return values;
};

const hasNonEmpty = (values: string[]): boolean => {
  for (const value of values) {
    if (value.length > 0) return true;
  }
  return false;
};

const REDACTED_SECRET_PLACEHOLDER_RE: RegExp =
  /^(?:<redacted>|\[redacted\]|\(redacted\)|redacted|\*{3,}|[•●·]{3,}|(?:\S+)?(?:\.{2,}|…)(?:\*{2,}|[•●·]{2,}))$/i;

/** 识别 `<redacted>`、`***`、`••••` 等脱敏占位符，拒绝把占位符写进凭据库覆盖真实密钥 */
export const isRedactedSecretPlaceholder = (value: string): boolean => {
  const trimmed: string = value.trim();
  if (trimmed.length === 0) return false;
  return REDACTED_SECRET_PLACEHOLDER_RE.test(trimmed)
    || /<redacted>|\[redacted\]/i.test(trimmed);
};

const sanitizeHeaderValuesForStore = async (
  incoming: string[], store: SecretFieldStore, ownerKey: string, slot: SecretField,
): Promise<string[]> => {
  let hasPlaceholder: boolean = false;
  for (const v of incoming) {
    if (isRedactedSecretPlaceholder(v)) {
      hasPlaceholder = true;
      break;
    }
  }
  if (!hasPlaceholder) return incoming;
  const storedRaw: string | null = await store.read(ownerKey, slot);
  const existing: string[] = storedRaw === null ? [] : (parseHeaderValues(storedRaw) ?? []);
  const resolved: string[] = [];
  for (let i: number = 0; i < incoming.length; i++) {
    const v: string = incoming[i];
    resolved.push(isRedactedSecretPlaceholder(v) ? (i < existing.length ? existing[i] : '') : v);
  }
  return resolved;
};

/** 把 header 行的 `value` 清空(名称/顺序保持不动) */
const blankHeaderValues = (headers: SecretRow[]): void => {
  for (const header of headers) {
    if (typeof header['value'] === 'string') header['value'] = '';
  }
};

const headerValueAt = (header: SecretRow): string => {
  const value: unknown = header['value'];
  return typeof value === 'string' ? value : '';
};

const writeHeaderValue = (header: SecretRow, value: string): void => {
  header['value'] = value;
};

const overrideRowOf = (model: SecretRow): SecretRow | null => {
  const override: unknown = model['providerOverwrite'];
  return isRow(override) ? override : null;
};

// ===== MCP 服务器 header(McpCommonOptions.headers 是 [name, value] 对) =====
// 复用模型 customHeaders 的形状:名称留在明文,value 数组整体入一个槽位。

const mcpHeaderRowsOf = (row: SecretRow): SecretRow[] | null => {
  const common: unknown = row['commonOptions'];
  if (!isRow(common)) return null;
  const headers: unknown = common['headers'];
  if (!Array.isArray(headers)) return null;
  const out: SecretRow[] = [];
  for (const item of headers) {
    if (isRow(item)) out.push(item);
  }
  return out;
};

const mcpHeaderValuesOf = (headers: SecretRow[]): string[] => {
  const values: string[] = [];
  for (const header of headers) {
    const value: unknown = header['second'];
    values.push(typeof value === 'string' ? value : '');
  }
  return values;
};

const blankMcpHeaderValues = (headers: SecretRow[]): void => {
  for (const header of headers) {
    if (typeof header['second'] === 'string') header['second'] = '';
  }
};

const applyHeaderSecrets = async (
  headers: SecretRow[], store: SecretFieldStore, ownerKey: string, authoritative: boolean,
): Promise<boolean> => {
  const rawValues: string[] = mcpHeaderValuesOf(headers);
  if (hasNonEmpty(rawValues)) {
    const sanitized: string[] = await sanitizeHeaderValuesForStore(rawValues, store, ownerKey, MCP_HEADER_SLOT);
    if (hasNonEmpty(sanitized)) {
      await store.write(ownerKey, MCP_HEADER_SLOT, JSON.stringify(sanitized));
    }
    blankMcpHeaderValues(headers);
    return true;
  }
  if (authoritative && await store.read(ownerKey, MCP_HEADER_SLOT) !== null) {
    await store.remove(ownerKey, MCP_HEADER_SLOT);
  }
  return false;
};

/**
 * 处理一条记录的凭据。
 * authoritative=true(写路径):空值即用户清空 → 删除库条目。
 * authoritative=false(读路径迁移):只写不删,避免把别的已加密条目误删。
 * 返回是否改动了 blob。
 */
const applyRowSecrets = async (
  row: SecretRow, store: SecretFieldStore, namespace: string, authoritative: boolean,
): Promise<boolean> => {
  const ownerId: string = ownerIdOf(row);
  if (ownerId.length === 0) return false;
  const ownerKey: string = ownerKeyOf(namespace, ownerId);
  let changed: boolean = false;

  for (const field of FLAT_FIELDS) {
    const value: string | null = stringFieldOf(row, field);
    if (value === null) continue;
    if (value.length > 0) {
      if (!isRedactedSecretPlaceholder(value)) {
        await store.write(ownerKey, field, value);
      }
      row[field] = '';
      changed = true;
    } else if (authoritative && await store.read(ownerKey, field) !== null) {
      await store.remove(ownerKey, field);
    }
  }

  // 顶层 customHeaders(如 assistants[].customHeaders[].value)
  const rowHeaders: SecretRow[] = headerRowsOf(row);
  if (rowHeaders.length > 0 && hasNonEmpty(headerValuesOf(rowHeaders))) {
    const sanitized: string[] = await sanitizeHeaderValuesForStore(
      headerValuesOf(rowHeaders), store, ownerKey, MODEL_HEADER_SLOT);
    if (hasNonEmpty(sanitized)) {
      await store.write(ownerKey, MODEL_HEADER_SLOT, JSON.stringify(sanitized));
    }
    blankHeaderValues(rowHeaders);
    changed = true;
  } else if (Array.isArray(row['customHeaders']) && authoritative && await store.read(ownerKey, MODEL_HEADER_SLOT) !== null) {
    await store.remove(ownerKey, MODEL_HEADER_SLOT);
  }

  // MCP 服务器:仅 commonOptions.headers 形状的 blob 会命中
  const mcpHeaders: SecretRow[] | null = mcpHeaderRowsOf(row);
  if (mcpHeaders !== null && mcpHeaders.length > 0) {
    if (await applyHeaderSecrets(mcpHeaders, store, ownerKey, authoritative)) changed = true;
  }

  for (const model of modelRowsOf(row)) {
    const modelId: string = ownerIdOf(model);
    if (modelId.length === 0) continue;
    const modelKey: string = ownerKeyOf(namespace, `${ownerId}/${modelId}`);
    const headers: SecretRow[] = headerRowsOf(model);
    if (headers.length > 0 && hasNonEmpty(headerValuesOf(headers))) {
      const sanitized: string[] = await sanitizeHeaderValuesForStore(
        headerValuesOf(headers), store, modelKey, MODEL_HEADER_SLOT);
      if (hasNonEmpty(sanitized)) {
        await store.write(modelKey, MODEL_HEADER_SLOT, JSON.stringify(sanitized));
      }
      blankHeaderValues(headers);
      changed = true;
    } else if (headers.length > 0 && authoritative && await store.read(modelKey, MODEL_HEADER_SLOT) !== null) {
      await store.remove(modelKey, MODEL_HEADER_SLOT);
    }
    const override: SecretRow | null = overrideRowOf(model);
    if (override === null) continue;
    // providerOverwrite 是完整的 ProviderSetting(可能是 google 变体),所以不只有
    // apiKey:privateKey/service-account 私钥 同样敏感。用同一组 FLAT_FIELDS 走一遍,
    // 槽位名不冲突(apiKey / privateKey 各自独立),对旧数据向后兼容。
    for (const field of FLAT_FIELDS) {
      const value: string | null = stringFieldOf(override, field);
      if (value === null) continue;
      if (value.length > 0) {
        if (!isRedactedSecretPlaceholder(value)) {
          await store.write(modelKey, field, value);
        }
        override[field] = '';
        changed = true;
      } else if (authoritative && await store.read(modelKey, field) !== null) {
        await store.remove(modelKey, field);
      }
    }
  }
  return changed;
};

/** 记录与其全部模型所占的 ownerKey,用于 prune 判定 */
const collectOwners = (row: SecretRow, namespace: string, owners: Set<string>): void => {
  const ownerId: string = ownerIdOf(row);
  if (ownerId.length === 0) return;
  owners.add(ownerKeyOf(namespace, ownerId));
  for (const model of modelRowsOf(row)) {
    const modelId: string = ownerIdOf(model);
    if (modelId.length > 0) owners.add(ownerKeyOf(namespace, `${ownerId}/${modelId}`));
  }
};

/** 删除 namespace 下全部凭据(blob 被整体删除时用) */
export const pruneNamespace = async (store: SecretFieldStore, namespace: string): Promise<void> => {
  for (const ownerKey of await store.listOwnerKeys()) {
    if (!ownerKey.startsWith(`${namespace}/`)) continue;
    for (const field of ALL_SLOTS) await store.remove(ownerKey, field);
  }
};

const ALL_SLOTS: ReadonlyArray<SecretField> = [
  'apiKey', 'privateKey', 'password', MODEL_HEADER_SLOT, MCP_HEADER_SLOT,
];

/**
 * 写路径:凭据收进 store,返回不含明文的 blob。
 * 内存值即权威:空值表示用户清空,会删除对应库条目,并清理已删除记录的残留。
 */
export const sealSecretFields = async (
  raw: string, store: SecretFieldStore, namespace: string,
): Promise<string> => {
  const rows: SecretRow[] | null = parseRows(raw);
  if (rows === null) return raw;
  if (rows.length === 0) {
    await pruneNamespace(store, namespace);
    return raw;
  }
  let changed: boolean = false;
  const owners: Set<string> = new Set<string>();
  for (const row of rows) {
    collectOwners(row, namespace, owners);
    if (await applyRowSecrets(row, store, namespace, true)) changed = true;
  }
  // 一个都没归属上(结构变了 / 记录全缺 id)时不能 prune:否则会把整个 namespace
  // 的凭据清空,而调用方其实什么都没改。真正「删掉最后一个记录」由 rows.length === 0 覆盖。
  if (owners.size === 0) return changed ? JSON.stringify(rows) : raw;
  // 已删除的记录/模型不再出现在 blob 里,其凭据一并清掉
  for (const storedKey of await store.listOwnerKeys()) {
    if (!storedKey.startsWith(`${namespace}/`) || owners.has(storedKey)) continue;
    for (const field of ALL_SLOTS) await store.remove(storedKey, field);
  }
  return changed ? JSON.stringify(rows) : raw;
};

/**
 * 读路径迁移:只把发现的明文搬进 store,不删除、不 prune。
 * 读到的 blob 可能同时含有「已加密(空值)」和「新增明文」两类记录,删除语义在这里
 * 不成立——空值只代表还没回填,不代表用户清空。
 */
export const migrateSecretFields = async (
  raw: string, store: SecretFieldStore, namespace: string,
): Promise<string> => {
  const rows: SecretRow[] | null = parseRows(raw);
  if (rows === null || rows.length === 0) return raw;
  let changed: boolean = false;
  for (const row of rows) {
    if (await applyRowSecrets(row, store, namespace, false)) changed = true;
  }
  return changed ? JSON.stringify(rows) : raw;
};

/**
 * 恢复路径:清空全部密文字段,保留非密字段,不访问凭据库。
 * 用于主密钥丢失后让配置重新可用(用户重新填写密钥)。
 */
export const stripSecretFields = (raw: string): string => {
  const rows: SecretRow[] | null = parseRows(raw);
  if (rows === null || rows.length === 0) return raw;
  for (const row of rows) {
    for (const field of FLAT_FIELDS) {
      if (typeof row[field] === 'string') row[field] = '';
    }
    blankHeaderValues(headerRowsOf(row));
    const mcpHeaders: SecretRow[] | null = mcpHeaderRowsOf(row);
    if (mcpHeaders !== null) blankMcpHeaderValues(mcpHeaders);
    for (const model of modelRowsOf(row)) {
      blankHeaderValues(headerRowsOf(model));
      const override: SecretRow | null = overrideRowOf(model);
      if (override === null) continue;
      for (const field of FLAT_FIELDS) {
        if (typeof override[field] === 'string') override[field] = '';
      }
    }
  }
  return JSON.stringify(rows);
};

/** 快速判定 blob 内是否还有未剥离的凭据(含 models[] 嵌套);读路径据此决定是否迁移 */
export const blobHasInlineSecrets = (raw: string): boolean => {
  const rows: SecretRow[] | null = parseRows(raw);
  if (rows === null) return false;
  for (const row of rows) {
    if (ownerIdOf(row).length === 0) continue;
    for (const field of FLAT_FIELDS) {
      const value: string | null = stringFieldOf(row, field);
      if (value !== null && value.length > 0) return true;
    }
    if (hasNonEmpty(headerValuesOf(headerRowsOf(row)))) return true;
    const mcpHeaders: SecretRow[] | null = mcpHeaderRowsOf(row);
    if (mcpHeaders !== null && hasNonEmpty(mcpHeaderValuesOf(mcpHeaders))) return true;
    for (const model of modelRowsOf(row)) {
      if (hasNonEmpty(headerValuesOf(headerRowsOf(model)))) return true;
      const override: SecretRow | null = overrideRowOf(model);
      if (override === null) continue;
      for (const field of FLAT_FIELDS) {
        const value: string | null = stringFieldOf(override, field);
        if (value !== null && value.length > 0) return true;
      }
    }
  }
  return false;
};

/**
 * 用 store 内的凭据回填 blob 的空字段,返回可直接交给既有解析器的 blob。
 * store 读取异常一律上抛(见文件头语义约定)。
 */
export const openSecretFields = async (
  raw: string, store: SecretFieldStore, namespace: string,
): Promise<string> => {
  const rows: SecretRow[] | null = parseRows(raw);
  if (rows === null || rows.length === 0) return raw;
  let changed: boolean = false;
  for (const row of rows) {
    const ownerId: string = ownerIdOf(row);
    if (ownerId.length === 0) continue;
    const ownerKey: string = ownerKeyOf(namespace, ownerId);
    for (const field of FLAT_FIELDS) {
      const value: string | null = stringFieldOf(row, field);
      if (value === null || value.length > 0) continue;
      const stored: string | null = await store.read(ownerKey, field);
      if (stored === null) continue;
      row[field] = stored;
      changed = true;
    }
    const rowHeaders: SecretRow[] = headerRowsOf(row);
    if (rowHeaders.length > 0 && !hasNonEmpty(headerValuesOf(rowHeaders))) {
      const stored: string | null = await store.read(ownerKey, MODEL_HEADER_SLOT);
      const values: string[] | null = stored === null ? null : parseHeaderValues(stored);
      if (values !== null) {
        for (let index: number = 0; index < rowHeaders.length && index < values.length; index++) {
          writeHeaderValue(rowHeaders[index], values[index]);
        }
        changed = true;
      }
    }
    const mcpHeaders: SecretRow[] | null = mcpHeaderRowsOf(row);
    if (mcpHeaders !== null && mcpHeaders.length > 0
      && !hasNonEmpty(mcpHeaderValuesOf(mcpHeaders))) {
      const stored: string | null = await store.read(ownerKey, MCP_HEADER_SLOT);
      const values: string[] | null = stored === null ? null : parseHeaderValues(stored);
      if (values !== null) {
        for (let index: number = 0; index < mcpHeaders.length && index < values.length; index++) {
          mcpHeaders[index]['second'] = values[index];
        }
        changed = true;
      }
    }
    for (const model of modelRowsOf(row)) {
      const modelId: string = ownerIdOf(model);
      if (modelId.length === 0) continue;
      const modelKey: string = ownerKeyOf(namespace, `${ownerId}/${modelId}`);
      const headers: SecretRow[] = headerRowsOf(model);
      if (headers.length > 0 && !hasNonEmpty(headerValuesOf(headers))) {
        const stored: string | null = await store.read(modelKey, MODEL_HEADER_SLOT);
        const values: string[] | null = stored === null ? null : parseHeaderValues(stored);
        if (values !== null) {
          for (let index: number = 0; index < headers.length && index < values.length; index++) {
            writeHeaderValue(headers[index], values[index]);
          }
          changed = true;
        }
      }
      const override: SecretRow | null = overrideRowOf(model);
      if (override === null) continue;
      for (const field of FLAT_FIELDS) {
        const value: string | null = stringFieldOf(override, field);
        if (value === null || value.length > 0) continue;
        const stored: string | null = await store.read(modelKey, field);
        if (stored === null) continue;
        override[field] = stored;
        changed = true;
      }
    }
  }
  return changed ? JSON.stringify(rows) : raw;
};

/** 解出 header values 数组;形状不符按「没有库记录」处理 */
const parseHeaderValues = (raw: string): string[] | null => {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!Array.isArray(value)) return null;
  const values: string[] = [];
  for (const item of value) {
    if (typeof item !== 'string') return null;
    values.push(item);
  }
  return values;
};

/**
 * 标量凭据(整段字符串就是密钥,如 ai_api_key):写入 store 并把 Preferences 值清空。
 * 返回应当落盘的字符串。空值即「用户清空」,与数组版同口径删除 store 条目。
 */
export const sealScalarSecret = async (
  raw: string, store: SecretFieldStore, ownerKey: string, field: SecretField,
): Promise<string> => {
  if (raw.length === 0) {
    if (await store.read(ownerKey, field) !== null) await store.remove(ownerKey, field);
    return '';
  }
  if (isRedactedSecretPlaceholder(raw)) {
    return '';
  }
  await store.write(ownerKey, field, raw);
  return '';
};

/**
 * 标量凭据回填:Preferences 里还有值(迁移尚未落盘)时以它为准,否则读 store。
 * 调用方应把刚读到的原始值传进来,而不是写死空串——否则这条分支永远走不到,
 * 回归也就测不出来。
 */
export const openScalarSecret = async (
  stored: string, store: SecretFieldStore, ownerKey: string, field: SecretField,
): Promise<string> => {
  if (stored.length > 0 && !isRedactedSecretPlaceholder(stored)) return stored;
  const value: string | null = await store.read(ownerKey, field);
  return value === null ? '' : value;
};
