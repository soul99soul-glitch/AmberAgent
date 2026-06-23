# Deep Read 鸿蒙版 — Platform Skeleton 实现计划(Phase 1.2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans.

**Goal:** 定义 Deep Read 鸿蒙版的 Platform 层 interfaces + 2 个纯逻辑组件(SseAssembler、JsonSchemaValidator)。这是 Phase 1 的后半段,接 Domain layer(已完成的 Plan 1.1)。

**Architecture:** Platform 层是鸿蒙 SDK 封装的**接口契约**,实现可替换。本 plan 只做:interface 定义(零实现,纯 node 可测)+ 2 个纯逻辑组件(无 SDK 依赖,纯 node 可测)。实际 SDK 实现(RcpHttpClient / RdbDatabase 等)留到后续 plan,等 DevEco 工具链装好。

**Tech Stack:** ArkTS interface / node + tsx 测试

---

## 范围说明

### 本 plan 做(纯 node 可测)
- `HttpClient` / `Database` / `Storage` / `AiClient` / `SearchProvider` / `Notifier` interface 定义
- `DeepReadRepository` interface + 类型(实现留后续)
- `DeepReadCacheEntry` 类型
- `RDB schema SQL`(纯字符串,可解析校验)
- **`SseAssembler`** — 完整实现(ArrayBuffer → string → chunk 边界 buffer → event 切分)+ 测试
- **`JsonSchemaValidator`** — 轻量实现(type/required/properties/items/enum/minLength/maxLength)+ 测试
- `Observable<T>` interface + 轮询实现(纯 TS,可测)

### 本 plan 不做(留后续 plan)
- RcpHttpClient 实现(需要 RCP SDK)
- RdbDatabase 实现(需要 relationalStore SDK)
- AssetStoreStorage 实现(需要 Asset Store SDK)
- OpenAiCompatibleAiClient 实现(需要 AiClient + UIMessage,Plan 2)
- 真实 SearchProvider 实现(需要 search SDK)

## 目录结构

```
harmony/deepread/src/main/ets/
├── domain/                      # Plan 1.1(已完成)
└── platform/
    ├── http.ts                  # HttpClient interface + types
    ├── database.ts              # Database interface + RDB schema
    ├── storage.ts               # Storage interface
    ├── ai_client.ts             # AiClient interface + types(暂无实现)
    ├── search.ts                # SearchProvider interface
    ├── notifier.ts              # Notifier interface + types
    ├── observable.ts            # Observable<T> + 轮询实现
    ├── sse_assembler.ts         # SseAssembler 完整实现(纯逻辑)
    ├── json_schema_validator.ts # JsonSchemaValidator 轻量实现(纯逻辑)
    └── repository.ts            # DeepReadRepository interface + DeepReadCacheEntry
```

测试:
```
harmony/deepread/src/test/
├── sse_assembler.test.ts
├── json_schema_validator.test.ts
├── observable.test.ts
└── schema_sql.test.ts
```

---

## Task 1: Platform interface 定义(纯类型,无逻辑)

**Files:** Create 7 interface 文件

### 1.1 `harmony/deepread/src/main/ets/platform/http.ts`

```typescript
// HttpClient — 鸿蒙 RCP 封装的接口契约
// 实现留后续(RcpHttpClient 用 @kit.NetworkKit 的 rcp.createSession)

export interface HttpRequest {
  url: string;
  method: 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH';
  headers: Record<string, string>;
  body?: string;
}

export interface HttpResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
}

export interface HttpClient {
  fetch(req: HttpRequest, opts?: { signal?: AbortSignal }): Promise<HttpResponse>;
  fetchStream(
    req: HttpRequest,
    opts: {
      onChunk: (chunk: ArrayBuffer, end: boolean) => void;
      signal?: AbortSignal;
    },
  ): Promise<HttpResponse>;
}
```

### 1.2 `harmony/deepread/src/main/ets/platform/database.ts`

```typescript
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

// Deep Read RDB schema — 对应 Android deep_read_cache + hot_topic_cache
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
```

### 1.3 `harmony/deepread/src/main/ets/platform/storage.ts`

```typescript
// Storage — preferences + Asset Store 封装的接口契约
// 真密钥走 Asset Store Kit,preferences 存配置 + key-set flag

export interface Storage {
  get<T>(key: string, defaultValue: T): Promise<T>;
  set<T>(key: string, value: T): Promise<void>;
  delete(key: string): Promise<void>;
  getSecret(key: string): Promise<string | null>;
  setSecret(key: string, value: string): Promise<void>;
  deleteSecret(key: string): Promise<void>;
}
```

### 1.4 `harmony/deepread/src/main/ets/platform/notifier.ts`

```typescript
// Notifier — NotificationKit 封装的接口契约
// 三类通知:running / completed / failed,点击回 app 文章页

export type NotificationKind = 'running' | 'completed' | 'failed';

export interface NotificationRequest {
  topicId: string;
  title: string;
  kind: NotificationKind;
  sourceUrl: string | null;
  isComplete: boolean;  // completed 时区分"已生成"vs"已部分生成"
}

export interface Notifier {
  notifyRunning(topicId: string, title: string): Promise<void>;
  notifyCompleted(topicId: string, title: string, isComplete: boolean): Promise<void>;
  notifyFailed(topicId: string, title: string, errorMessage: string): Promise<void>;
  cancelRunning(topicId: string): Promise<void>;
}
```

### 1.5 `harmony/deepread/src/main/ets/platform/search.ts`

```typescript
// SearchProvider — 搜索 provider 抽象
// 实现留后续(每 provider 一个 class)

export interface SearchHit {
  title: string;
  url: string;
  snippet: string | null;
  source: string;  // provider name
}

export interface SearchProvider {
  readonly name: string;
  search(queries: string[]): Promise<SearchHit[]>;
}

export interface SearchProviderRegistry {
  enabled(): SearchProvider[];  // 按 api key 存在性排序
  fallback(): SearchProvider[]; // BingLocal + Jina(始终)
}
```

### 1.6 `harmony/deepread/src/main/ets/platform/ai_client.ts`

```typescript
// AiClient — OpenAI-compatible chat completion 客户端
// 注意:UIMessage 类型留 Plan 2 定义,这里只占位

import type { DeepReadOutput } from '../domain/models.ts';

// Plan 2 会展开 UIMessage,这里先简化为不透明类型
export type UIMessage = unknown;

export interface Tool {
  name: string;
  description: string;
  schema: object;  // JSON schema
}

export interface GenerateResult {
  messages: UIMessage[];
}

export interface AiClient {
  generateText(params: {
    model: string;
    messages: UIMessage[];
    tools?: Tool[];
    maxSteps?: number;
    autoApproveTools?: boolean;
    autoApproveHighRiskTools?: boolean;
    autoApprovedToolNames?: string[];
    stream?: boolean;
    signal?: AbortSignal;
  }): Promise<UIMessage[]>;
}
```

### 1.7 `harmony/deepread/src/main/ets/platform/repository.ts`

```typescript
// DeepReadRepository — 持久化访问封装
// 实现留后续(RdbDeepReadRepository 用 Database + JSON parse)

import type { DeepReadOutput } from '../domain/models.ts';
import type { DeepReadGenerationStage, DeepReadGenerationPhase, DeepReadSectionState } from '../domain/enums.ts';
import type { Observable } from './observable.ts';

export interface DeepReadCacheEntry {
  topicId: string;
  title: string;
  sourceUrl: string | null;
  output: DeepReadOutput;
  phase: DeepReadGenerationPhase;
  attemptCount: number;
  lastError: string | null;
  createdAt: number;
  updatedAt: number;
  expiresAt: number;
}

export interface DeepReadRepository {
  get(topicId: string): Promise<DeepReadCacheEntry | null>;
  getByUrl(url: string): Promise<DeepReadCacheEntry | null>;
  getByTitle(title: string): Promise<DeepReadCacheEntry | null>;
  materializeFresh(topicId: string, title: string, url: string | null): Promise<DeepReadCacheEntry | null>;
  upsert(entry: DeepReadCacheEntry): Promise<void>;
  updatePhase(topicId: string, phase: DeepReadGenerationPhase): Promise<void>;
  updateSectionState(topicId: string, stage: DeepReadGenerationStage, state: DeepReadSectionState): Promise<void>;
  incrementAttempt(topicId: string, error: string): Promise<void>;
  resetAttempt(topicId: string): Promise<void>;
  observe(topicId: string): Observable<DeepReadCacheEntry | null>;
  listHistory(limit: number): Promise<DeepReadCacheEntry[]>;
  delete(topicId: string): Promise<void>;
  purgeExpired(): Promise<number>;
  purgeHistoryRetention(): Promise<number>;
}
```

### Task 1 测试

写一个 schema 解析测试验证 SQL 字符串语法合理(不连真 DB,只验证结构):`harmony/deepread/src/test/schema_sql.test.ts`

```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { DEEP_READ_SCHEMA_SQL } from '../main/ets/platform/database.ts';

test('DEEP_READ_SCHEMA_SQL contains deep_read_cache table', () => {
  assert.ok(DEEP_READ_SCHEMA_SQL.includes('CREATE TABLE IF NOT EXISTS deep_read_cache'));
});

test('DEEP_READ_SCHEMA_SQL has all required columns', () => {
  const requiredCols = [
    'topic_id', 'title', 'source_url', 'output_json', 'phase',
    'attempt_count', 'last_error', 'created_at', 'updated_at', 'expires_at',
  ];
  for (const col of requiredCols) {
    assert.ok(DEEP_READ_SCHEMA_SQL.includes(col), `missing column: ${col}`);
  }
});

test('DEEP_READ_SCHEMA_SQL has 3 indexes', () => {
  const indexCount = (DEEP_READ_SCHEMA_SQL.match(/CREATE INDEX/g) ?? []).length;
  assert.equal(indexCount, 3);
});

test('DEEP_READ_SCHEMA_SQL marks topic_id as PRIMARY KEY', () => {
  assert.ok(DEEP_READ_SCHEMA_SQL.includes('topic_id') && DEEP_READ_SCHEMA_SQL.includes('PRIMARY KEY'));
});

test('attempt_count has default 0', () => {
  assert.ok(/attempt_count\s+INTEGER\s+NOT\s+NULL\s+DEFAULT\s+0/i.test(DEEP_READ_SCHEMA_SQL));
});
```

提交:`feat(deepread): add platform layer interfaces (HttpClient/Database/Storage/...)`

---

## Task 2: Observable<T> + 轮询实现

**Files:** Create `harmony/deepread/src/main/ets/platform/observable.ts` + test

### 2.1 实现

```typescript
// Observable<T> — MVP 用 1s 轮询实现(对应 Android Room Flow)
// 升级路径:后续可换事件总线

export interface Observable<T> {
  subscribe(cb: (value: T) => void): () => void;  // 返回 unsubscribe
  getCurrent(): T | undefined;
}

export interface PollingOptions {
  intervalMs?: number;  // 默认 1000
  signal?: AbortSignal;
}

// 创建轮询 Observable
export const pollingObservable = <T>(
  fetcher: () => Promise<T>,
  opts: PollingOptions = {},
): Observable<T> => {
  const intervalMs = opts.intervalMs ?? 1000;
  const signal = opts.signal;
  const subscribers = new Set<(value: T) => void>();
  let current: T | undefined = undefined;
  let timer: ReturnType<typeof setInterval> | null = null;
  let lastNotifiedSerialized: string | undefined = undefined;

  const notify = (value: T): void => {
    // 只在值变化时回调(深比较序列化)
    const serialized = JSON.stringify(value);
    if (serialized === lastNotifiedSerialized) return;
    lastNotifiedSerialized = serialized;
    current = value;
    for (const cb of subscribers) {
      try { cb(value); } catch { /* 单个回调失败不影响其他 */ }
    }
  };

  const poll = async (): Promise<void> => {
    if (signal?.aborted) {
      if (timer) clearInterval(timer);
      timer = null;
      return;
    }
    try {
      const value = await fetcher();
      notify(value);
    } catch {
      // fetcher 失败静默,下次轮询重试
    }
  };

  const start = (): void => {
    if (timer !== null) return;
    poll();  // 立即跑一次
    timer = setInterval(() => { poll(); }, intervalMs);
  };

  const stop = (): void => {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  };

  signal?.addEventListener('abort', stop);

  return {
    subscribe(cb: (value: T) => void): () => void {
      subscribers.add(cb);
      if (subscribers.size === 1) start();
      // 立即给新订阅者当前值(若有)
      if (current !== undefined) {
        try { cb(current); } catch { /* ignore */ }
      }
      return () => {
        subscribers.delete(cb);
        if (subscribers.size === 0) stop();
      };
    },
    getCurrent(): T | undefined {
      return current;
    },
  };
};
```

### 2.2 测试 `harmony/deepread/src/test/observable.test.ts`

```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { pollingObservable } from '../main/ets/platform/observable.ts';
import { setTimeout as delay } from 'node:timers/promises';

test('subscribe immediately calls back with current value if available', async () => {
  let calls = 0;
  const obs = pollingObservable(async () => ({ n: ++calls }), { intervalMs: 50 });
  await delay(10);  // 等 first poll
  let received = 0;
  obs.subscribe(v => { received = (v as { n: number }).n; });
  await delay(5);
  assert.ok(received > 0, 'should get current value immediately');
  // cleanup
  obs.subscribe(() => {})();  // hack unsubscribe 不完美,接受 GC
});

test('observable only notifies on value change', async () => {
  let fetchCount = 0;
  let receivedCount = 0;
  const obs = pollingObservable(async () => {
    fetchCount++;
    return 'same';  // 永远相同
  }, { intervalMs: 20 });
  const unsub = obs.subscribe(() => { receivedCount++; });
  await delay(100);
  unsub();
  assert.ok(fetchCount >= 3, `fetcher called multiple times (${fetchCount})`);
  assert.equal(receivedCount, 1, 'only first value triggers callback (rest unchanged)');
});

test('subscribe returns unsubscribe function', async () => {
  let count = 0;
  const obs = pollingObservable(async () => ({ n: ++count }), { intervalMs: 20 });
  const unsub = obs.subscribe(() => {});
  assert.equal(typeof unsub, 'function');
  unsub();
  await delay(50);
  // unsubscribed — no more polling
});

test('AbortSignal stops polling', async () => {
  const controller = new AbortController();
  let fetchCount = 0;
  const obs = pollingObservable(async () => ++fetchCount, { intervalMs: 20, signal: controller.signal });
  obs.subscribe(() => {});
  await delay(50);
  const beforeAbort = fetchCount;
  controller.abort();
  await delay(80);
  // abort 后 fetchCount 不再增长(允许 1 次在途)
  assert.ok(fetchCount <= beforeAbort + 1, `fetch stopped after abort (before=${beforeAbort}, after=${fetchCount})`);
});
```

提交:`feat(deepread): add Observable<T> with polling implementation`

---

## Task 3: SseAssembler(纯逻辑组件)

**Files:** Create `harmony/deepread/src/main/ets/platform/sse_assembler.ts` + test

### 3.1 实现

关键设计:
- RCP 的 `onReceiveDataBlock(data: ArrayBuffer, end: boolean)` 给的是 ArrayBuffer
- 一个 SSE event 由 `data:` 行组成,以空行(`\n\n`)分隔
- 一个 event 可能跨多个 ArrayBuffer chunk
- 必须用 buffer 累积未完成的行

```typescript
// SseAssembler — 把 RCP 的 ArrayBuffer chunk 流转换成 SSE event
// 对应设计 §8.2:RCP 给 ArrayBuffer 不是 string,跨 chunk 的 event 必须累积

export interface SseEvent {
  data: string;   // data: 行拼接后的 payload(去 data: 前缀)
  done: boolean;  // 是否 [DONE]
}

export class SseAssembler {
  private buffer: string = '';

  // 喂入一个 chunk,返回本 chunk 完整解析出的 events
  // chunk 跨 event 边界的部分留在 buffer 等下次
  feed(chunk: ArrayBuffer): SseEvent[] {
    const text = this.buffer + new TextDecoder('utf-8').decode(chunk);
    this.buffer = '';
    const events: SseEvent[] = [];
    // 按空行切 event(SSE 规范:event 间空行分隔)
    let searchFrom = 0;
    while (true) {
      const idx = text.indexOf('\n\n', searchFrom);
      if (idx < 0) break;
      const raw = text.slice(searchFrom, idx);
      searchFrom = idx + 2;
      const evt = parseSseBlock(raw);
      if (evt !== null) events.push(evt);
    }
    // 剩余不完整的部分留在 buffer
    this.buffer = text.slice(searchFrom);
    return events;
  }

  // 流结束时调用,处理 buffer 里残留
  flush(): SseEvent[] {
    if (this.buffer.trim().length === 0) {
      this.buffer = '';
      return [];
    }
    const evt = parseSseBlock(this.buffer);
    this.buffer = '';
    return evt !== null ? [evt] : [];
  }
}

// 解析一个 SSE event block(已按 \n\n 切出来的)
// 累积所有 data: 行,检测 [DONE]
const parseSseBlock = (raw: string): SseEvent | null => {
  const lines = raw.split(/\r?\n/);
  const dataParts: string[] = [];
  for (const line of lines) {
    if (line.startsWith('data:')) {
      dataParts.push(line.slice(5).trimStart());
    } else if (line.startsWith('data: ')) {
      // 有些 server 用 'data: '(Android normalizeOpenAIStreamDataLines 处理过,这里防御)
      dataParts.push(line.slice(6));
    }
    // 其他行(event: id: retry:)暂忽略,Deep Read 不需要
  }
  if (dataParts.length === 0) return null;
  const data = dataParts.join('\n');
  // 多层 data: 前缀剥离(有些 provider 嵌套 data: data: {...})
  let stripped = data;
  while (stripped.startsWith('data:')) {
    stripped = stripped.slice(5).trimStart();
  }
  if (stripped === '[DONE]' || stripped === '[DONE]\n') {
    return { data: '[DONE]', done: true };
  }
  return { data: stripped, done: false };
};

// 便利函数:把 RCP onReceiveDataBlock 回调适配成 SseEvent 流
export const createSseStreamHandler = (
  onEvent: (evt: SseEvent) => void,
  onDone?: () => void,
): { onDataBlock: (data: ArrayBuffer, end: boolean) => void; getAssembler: () => SseAssembler } => {
  const assembler = new SseAssembler();
  return {
    onDataBlock: (data: ArrayBuffer, end: boolean): void => {
      for (const evt of assembler.feed(data)) {
        if (evt.done) { onDone?.(); return; }
        onEvent(evt);
      }
      if (end) {
        for (const evt of assembler.flush()) {
          if (evt.done) { onDone?.(); return; }
          onEvent(evt);
        }
      }
    },
    getAssembler: () => assembler,
  };
};
```

### 3.2 测试 `harmony/deepread/src/test/sse_assembler.test.ts`

```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { SseAssembler, createSseStreamHandler } from '../main/ets/platform/sse_assembler.ts';

const enc = (s: string): ArrayBuffer => {
  // node TextEncoder 编码 Uint8Array,ArrayBuffer 兼容
  return new TextEncoder().encode(s).buffer as ArrayBuffer;
};

test('single complete event in one chunk', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: {"v":1}\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, '{"v":1}');
  assert.equal(events[0].done, false);
});

test('[DONE] marker sets done flag', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: [DONE]\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].done, true);
  assert.equal(events[0].data, '[DONE]');
});

test('event split across chunks', () => {
  const a = new SseAssembler();
  const r1 = a.feed(enc('data: {"v":1}\n'));
  assert.equal(r1.length, 0, 'no complete event yet (no trailing blank line)');
  const r2 = a.feed(enc('\n'));
  assert.equal(r2.length, 1);
  assert.equal(r2[0].data, '{"v":1}');
});

test('data line split mid-content across chunks', () => {
  const a = new SseAssembler();
  const r1 = a.feed(enc('data: {"v":1,"na'));
  assert.equal(r1.length, 0);
  const r2 = a.feed(enc('me":"foo"}\n\n'));
  assert.equal(r2.length, 1);
  assert.equal(r2[0].data, '{"v":1,"name":"foo"}');
});

test('multiple events in one chunk', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: a\n\ndata: b\n\ndata: c\n\n'));
  assert.equal(events.length, 3);
  assert.deepEqual(events.map(e => e.data), ['a', 'b', 'c']);
});

test('multi-line data payload (joined with newline)', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: line1\ndata: line2\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, 'line1\nline2');
});

test('nested data: prefix stripped', () => {
  const a = new SseAssembler();
  const events = a.feed(enc('data: data: data: {"v":1}\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, '{"v":1}');
});

test('empty lines and comments ignored', () => {
  const a = new SseAssembler();
  const events = a.feed(enc(': heartbeat\n\ndata: {"v":1}\n\n'));
  assert.equal(events.length, 1);
  assert.equal(events[0].data, '{"v":1}');
});

test('flush handles trailing partial event', () => {
  const a = new SseAssembler();
  a.feed(enc('data: trailing-no-blank-line'));
  const flushed = a.flush();
  assert.equal(flushed.length, 1);
  assert.equal(flushed[0].data, 'trailing-no-blank-line');
});

test('flush returns empty for whitespace-only buffer', () => {
  const a = new SseAssembler();
  a.feed(enc('   \n  '));
  assert.equal(a.flush().length, 0);
});

test('createSseStreamHandler emits events then done', () => {
  const received: SseEvent[] = [];
  let doneCalled = false;
  const handler = createSseStreamHandler(
    evt => received.push(evt),
    () => { doneCalled = true; },
  );
  handler.onDataBlock(enc('data: {"a":1}\n\n'), false);
  handler.onDataBlock(enc('data: {"a":2}\n\n'), false);
  handler.onDataBlock(enc('data: [DONE]\n\n'), true);
  assert.equal(received.length, 2);
  assert.equal(received[0].data, '{"a":1}');
  assert.equal(received[1].data, '{"a":2}');
  assert.equal(doneCalled, true);
});

// import SseEvent type at top
import type { SseEvent } from '../main/ets/platform/sse_assembler.ts';
```

提交:`feat(deepread): add SseAssembler (ArrayBuffer chunk boundary buffer)`

---

## Task 4: JsonSchemaValidator(纯逻辑组件)

**Files:** Create `harmony/deepread/src/main/ets/platform/json_schema_validator.ts` + test

### 4.1 实现

轻量 validator,只支持 type/required/properties/items/enum/minLength/maxLength/string/number/boolean/object/array/null。够 Deep Read stage tool 用。

```typescript
// JsonSchemaValidator — 轻量 JSON schema 校验
// 支持 type/required/properties/items/enum/minLength/maxLength
// 够 Deep Read stage tool 参数校验用,不需要 ajv 全功能

export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };

export interface JsonSchema {
  type?: 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array' | 'null';
  required?: string[];
  properties?: Record<string, JsonSchema>;
  items?: JsonSchema;
  enum?: JsonValue[];
  minLength?: number;
  maxLength?: number;
  minimum?: number;
  maximum?: number;
}

export interface ValidationResult {
  valid: boolean;
  errors: string[];
}

export const validateJsonSchema = (value: unknown, schema: JsonSchema, path: string = ''): ValidationResult => {
  const errors: string[] = [];
  validateNode(value, schema, path, errors);
  return { valid: errors.length === 0, errors };
};

const validateNode = (value: unknown, schema: JsonSchema, path: string, errors: string[]): void => {
  if (schema.type !== undefined) {
    if (!checkType(value, schema.type)) {
      errors.push(`${path || 'root'}: expected ${schema.type}, got ${actualType(value)}`);
      return;  // 类型错就不继续
    }
  }
  if (schema.enum !== undefined && !schema.enum.some(v => deepEqual(v, value))) {
    errors.push(`${path || 'root'}: value not in enum`);
  }
  if (schema.type === 'string' && typeof value === 'string') {
    if (schema.minLength !== undefined && value.length < schema.minLength) {
      errors.push(`${path}: string shorter than minLength ${schema.minLength}`);
    }
    if (schema.maxLength !== undefined && value.length > schema.maxLength) {
      errors.push(`${path}: string longer than maxLength ${schema.maxLength}`);
    }
  }
  if ((schema.type === 'number' || schema.type === 'integer') && typeof value === 'number') {
    if (schema.minimum !== undefined && value < schema.minimum) {
      errors.push(`${path}: number less than minimum ${schema.minimum}`);
    }
    if (schema.maximum !== undefined && value > schema.maximum) {
      errors.push(`${path}: number greater than maximum ${schema.maximum}`);
    }
  }
  if (schema.type === 'object' && typeof value === 'object' && value !== null && !Array.isArray(value)) {
    const obj = value as Record<string, unknown>;
    if (schema.required) {
      for (const req of schema.required) {
        if (!(req in obj)) {
          errors.push(`${path}: missing required property '${req}'`);
        }
      }
    }
    if (schema.properties) {
      for (const [key, subSchema] of Object.entries(schema.properties)) {
        if (key in obj) {
          validateNode(obj[key], subSchema, path ? `${path}.${key}` : key, errors);
        }
      }
    }
  }
  if (schema.type === 'array' && Array.isArray(value)) {
    if (schema.items) {
      for (let i = 0; i < value.length; i++) {
        validateNode(value[i], schema.items, `${path}[${i}]`, errors);
      }
    }
  }
};

const checkType = (value: unknown, type: JsonSchema['type']): boolean => {
  switch (type) {
    case 'string': return typeof value === 'string';
    case 'number': return typeof value === 'number';
    case 'integer': return typeof value === 'number' && Number.isInteger(value);
    case 'boolean': return typeof value === 'boolean';
    case 'null': return value === null;
    case 'object': return typeof value === 'object' && value !== null && !Array.isArray(value);
    case 'array': return Array.isArray(value);
    default: return true;
  }
};

const actualType = (value: unknown): string => {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  return typeof value;
};

const deepEqual = (a: unknown, b: unknown): boolean => {
  if (a === b) return true;
  if (typeof a !== typeof b) return false;
  if (typeof a !== 'object' || a === null || b === null) return false;
  const aJson = JSON.stringify(a);
  const bJson = JSON.stringify(b);
  return aJson === bJson;
};
```

### 4.2 测试 `harmony/deepread/src/test/json_schema_validator.test.ts`

```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { validateJsonSchema } from '../main/ets/platform/json_schema_validator.ts';

test('validates correct string type', () => {
  const r = validateJsonSchema('hello', { type: 'string' });
  assert.equal(r.valid, true);
});

test('rejects wrong type', () => {
  const r = validateJsonSchema(42, { type: 'string' });
  assert.equal(r.valid, false);
  assert.match(r.errors[0], /expected string/);
});

test('validates object with required fields', () => {
  const schema = {
    type: 'object' as const,
    required: ['summary', 'topic_type'],
    properties: {
      summary: { type: 'string' as const },
      topic_type: { type: 'string' as const },
    },
  };
  assert.equal(validateJsonSchema({ summary: 'x', topic_type: 'event' }, schema).valid, true);
  const r = validateJsonSchema({ topic_type: 'event' }, schema);
  assert.equal(r.valid, false);
  assert.ok(r.errors.some(e => e.includes("'summary'")));
});

test('validates array items', () => {
  const schema = {
    type: 'array' as const,
    items: { type: 'string' as const },
  };
  assert.equal(validateJsonSchema(['a', 'b'], schema).valid, true);
  const r = validateJsonSchema(['a', 1], schema);
  assert.equal(r.valid, false);
});

test('validates minLength / maxLength', () => {
  const schema = { type: 'string' as const, minLength: 24, maxLength: 1200 };
  assert.equal(validateJsonSchema('x'.repeat(24), schema).valid, true);
  assert.equal(validateJsonSchema('short', schema).valid, false);
  assert.equal(validateJsonSchema('x'.repeat(1201), schema).valid, false);
});

test('validates enum', () => {
  const schema = { enum: ['hero', 'inline', 'reject'] };
  assert.equal(validateJsonSchema('hero', schema).valid, true);
  assert.equal(validateJsonSchema('logo', schema).valid, false);
});

test('validates integer vs number', () => {
  assert.equal(validateJsonSchema(42, { type: 'integer' }).valid, true);
  assert.equal(validateJsonSchema(42.5, { type: 'integer' }).valid, false);
  assert.equal(validateJsonSchema(42.5, { type: 'number' }).valid, true);
});

test('validates nested object', () => {
  const schema = {
    type: 'object' as const,
    required: ['analysis'],
    properties: {
      analysis: {
        type: 'object' as const,
        required: ['coreDispute'],
        properties: {
          coreDispute: { type: 'string' as const },
        },
      },
    },
  };
  assert.equal(validateJsonSchema({ analysis: { coreDispute: 'x' } }, schema).valid, true);
  assert.equal(validateJsonSchema({ analysis: {} }, schema).valid, false);
});

test('minimum / maximum for numbers', () => {
  assert.equal(validateJsonSchema(5, { type: 'number', minimum: 1 }).valid, true);
  assert.equal(validateJsonSchema(0, { type: 'number', minimum: 1 }).valid, false);
  assert.equal(validateJsonSchema(100, { type: 'number', maximum: 50 }).valid, false);
});

test('null type', () => {
  assert.equal(validateJsonSchema(null, { type: 'null' }).valid, true);
  assert.equal(validateJsonSchema('x', { type: 'null' }).valid, false);
});

// Deep Read stage tool schema 真实验证(对应 design §4.7)
test('deep_read_write_overview schema accepts valid input', () => {
  const overviewSchema = {
    type: 'object' as const,
    required: ['summary'],
    properties: {
      topic_type: { type: 'string' as const },
      summary: { type: 'string' as const, minLength: 24, maxLength: 1200 },
      key_entities: { type: 'array' as const, items: { type: 'string' as const } },
    },
  };
  const valid = {
    topic_type: 'event',
    summary: '这是一段足够长的概览摘要满足最小长度要求条件',
    key_entities: ['实体A', '实体B'],
  };
  assert.equal(validateJsonSchema(valid, overviewSchema).valid, true);
});

test('deep_read_write_overview rejects too-short summary', () => {
  const overviewSchema = {
    type: 'object' as const,
    required: ['summary'],
    properties: {
      summary: { type: 'string' as const, minLength: 24 },
    },
  };
  assert.equal(validateJsonSchema({ summary: '短' }, overviewSchema).valid, false);
});
```

提交:`feat(deepread): add JsonSchemaValidator (lightweight)`

---

## Task 5: 更新 README + oh-package main + 最终测试

**Files:** Modify `harmony/deepread/README.md`, verify `oh-package.json5`

### 5.1 更新 README 末尾

追加:
```markdown
## Platform 层(Phase 1.2)

- `platform/http.ts` — HttpClient interface(RCP 封装契约,实现留后续)
- `platform/database.ts` — Database interface + RDB schema SQL
- `platform/storage.ts` — Storage interface(preferences + Asset Store)
- `platform/notifier.ts` — Notifier interface(3 类通知契约)
- `platform/search.ts` — SearchProvider interface
- `platform/ai_client.ts` — AiClient interface(UIMessage 占位待 Plan 2)
- `platform/repository.ts` — DeepReadRepository interface
- `platform/observable.ts` — Observable<T> + 轮询实现 ✅ 已实现
- `platform/sse_assembler.ts` — SseAssembler(ArrayBuffer chunk 边界) ✅ 已实现
- `platform/json_schema_validator.ts` — JsonSchemaValidator(轻量) ✅ 已实现

后续:RcpHttpClient / RdbDatabase / AssetStorage / 真实 SearchProvider 实现等 DevEco 工具链就绪。
```

### 5.2 最终测试 + 提交

```bash
cd harmony/deepread
npm test  # 应有 ~95 tests pass
npx tsc --noEmit
```

提交:`docs(deepread): document platform layer (Phase 1.2 complete)`

---

## Self-Review

### Spec coverage
| Spec 章节 | Task |
|---|---|
| §3.4 RDB schema | Task 1.2 |
| §3.6 DeepReadRepository interface | Task 1.7 |
| §3.7 Observable | Task 2 |
| §8.1 HttpClient | Task 1.1 |
| §8.2 SseAssembler | Task 3 |
| §8.3 Database | Task 1.2 |
| §8.4 Storage | Task 1.3 |
| §8.5 AiClient(interface only) | Task 1.6 |
| §8.6 SearchProvider | Task 1.5 |
| §8.8 Notifier | Task 1.4 |
| §8.9 JsonSchemaValidator | Task 4 |

### Placeholder scan
✅ 无 TBD/TODO
✅ 每步含完整代码
✅ 类型一致(DeepReadOutput / Stage / Phase 跨文件引用一致)
