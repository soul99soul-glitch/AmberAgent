# Deep Read 鸿蒙版 — Domain Layer 实现计划(Phase 1.1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现鸿蒙 Deep Read 的纯 ArkTS Domain 层 —— `DeepReadModels` 全部 interface + 4 个枚举 + 辅助纯函数(`isComplete` / `sectionsReady` / `hasAnyReadySection` / `withInferredSectionStates` / `displayHeroImageUrl` / `displayHeroCaption` / `deriveTopicId`),100% 可用 node 单测覆盖,零鸿蒙 SDK 依赖,零工具链依赖(不需要 DevEco/模拟器)。

**Architecture:** Domain 层是纯 ArkTS interface + 纯函数,放在 `harmony/deepread/src/main/ets/domain/`。测试用 node + 待定的 ArkTS-testable 子集(或纯 TS transpile)。所有代码严格遵守 ArkTS 约束(无 `any`/`unknown`,固定对象布局,interface 而非 class mutation)。这一层是 §11 Phase 1 的前半段,后续 Plan 1.2 处理 Platform skeleton(interfaces + RDB + SSE assembler)。

**Tech Stack:** ArkTS(TypeScript 严格子集)/ node test runner / 现有 fixtures 风格(`harmony/tests/fixtures/`)

---

## 前置知识

### ArkTS 约束速查(写代码时必须遵守)

- **禁** `any` / `unknown`(除了 catch clause 的 `unknown`)
- **禁** 运行时增删属性 → 用 `interface` + spread `{...obj, field: newVal}` 不可变更新
- **禁** `delete`
- **禁** `Object` / `{}` 类型 → 用具体 interface
- **禁** `Record<string, any>` → 用 `Record<string, string>` 或具体 interface
- **推荐** 箭头函数 > `function`
- **推荐** `switch(discriminant)` 配 string literal union(本 plan 用 `'kind'` discriminant)
- enum-keyed Map → `Record<Stage, T>` + keyof 断言

### 测试运行方式

Domain 层是纯 ArkTS。两种测试途径,选其一:

**途径 A(推荐,零工具链)**:用 `tsc` 把 `.ts` 文件 transpile 到 JS,用 node 内置 `node:test` 跑。`.ets` 文件其实是 TS 超集,纯 domain 代码不含 ArkUI 装饰器时和 TS 语法完全兼容,可以用 `.ts` 后缀在 node 下跑。

**途径 B(需要 DevEco)**:用 `@ohos/hypium` 在 DevEco test 框架下跑。本 plan 不采用(避免工具链依赖)。

本 plan 所有 domain 文件用 **`.ts` 后缀**(不是 `.ets`),放在 `harmony/deepread/src/main/ets/domain/`。理由:
1. 纯 domain 代码无 ArkUI 装饰器,`.ts` 和 `.ets` 语法等价
2. `.ts` 能被 node 直接跑(后续并入鸿蒙工程时,DevEco 也支持 `.ts`)
3. 测试零工具链依赖

### 目录结构

```
harmony/deepread/
├── src/
│   ├── main/
│   │   └── ets/
│   │       └── domain/
│   │           ├── models.ts              # Task 1-2: 所有 interface
│   │           ├── enums.ts               # Task 3: 枚举 + 常量
│   │           ├── helpers.ts             # Task 4-7: 纯函数辅助
│   │           └── topic_id.ts            # Task 8: topic_id 派生
│   └── test/
│       ├── models.test.ts
│       ├── enums.test.ts
│       ├── helpers.test.ts
│       ├── inferred_states.test.ts
│       ├── hero_display.test.ts
│       └── topic_id.test.ts
└── oh-package.json5                       # Task 9: 模块声明
```

### 参考的 Android 源码

| 行为 | Android 源 | 行号 |
|---|---|---|
| `DeepReadOutput` 全字段 | `feature/board/impl/src/main/kotlin/app/amber/feature/board/hotlist/deepread/DeepReadModels.kt` | 6-43 |
| 枚举 | 同上 | 46-52 |
| `DeepReadGenerationStage` | `feature/board/impl/.../DeepReadPrompt.kt` | 219-224 |
| `isComplete`/`sectionsReady`/`hasAnyReadySection` | `DeepReadModels.kt` | 90-113 |
| `withInferredSectionStates` | `DeepReadModels.kt` | 115-141 |
| `displayHeroImageUrl`/`displayHeroCaption` | `DeepReadModels.kt` | 334-353 |
| `topicId` 派生 | `app/src/main/java/app/amber/feature/board/hotlist/HotListRepository.kt` | 208-212 |

---

## Task 1: `DeepReadOutput` 核心接口

**Files:**
- Create: `harmony/deepread/src/main/ets/domain/models.ts`
- Create: `harmony/deepread/src/test/models.test.ts`

- [ ] **Step 1: 写失败测试 — interface 能被构造且字段默认值正确**

`harmony/deepread/src/test/models.test.ts`:
```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeEmptyDeepReadOutput } from '../src/main/ets/domain/models.ts';

test('empty DeepReadOutput has correct defaults', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(o.topicType, 'event');
  assert.equal(o.generationComplete, false);
  assert.equal(o.generationPhase, 'IDLE');
  assert.equal(o.summary, '');
  assert.deepEqual(o.keyEntities, []);
  assert.equal(o.timeline, null);
  assert.equal(o.corePoints, null);
  assert.deepEqual(o.extendedReading, []);
  assert.equal(o.heroImageUrl, null);
  assert.deepEqual(o.imageAssets, []);
  assert.equal(o.diagram, null);
  assert.deepEqual(o.references, []);
  assert.deepEqual(o.sectionStates, {});
  assert.deepEqual(o.sectionQualities, {});
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd harmony/deepread && npx tsx --test src/test/models.test.ts`(或 `node --import tsx/esm --test src/test/models.test.ts`)

> 如果 tsx 没装:`npm i -D tsx typescript @types/node` 在 `harmony/deepread/` 下。

Expected: FAIL with "Cannot find module '../src/main/ets/domain/models.ts'"

- [ ] **Step 3: 写最小实现**

`harmony/deepread/src/main/ets/domain/models.ts`:
```typescript
// Deep Read output data model — HarmonyOS port of Android DeepReadModels.kt
// 严格遵守 ArkTS 约束:interface + 工厂函数,无运行时 mutation

export interface TimelineEvent {
  date: string;
  event: string;
  isHighlight: boolean;
  imageUrl: string | null;
  imageCaption: string | null;
}

export interface CorePoint {
  point: string;
  supporting: string | null;
  imageUrl: string | null;
  imageCaption: string | null;
}

export interface ReadingLink {
  title: string;
  url: string;
  source: string | null;
  publishedAt: string | null;
}

export interface Perspective {
  holder: string | null;
  viewpoint: string;
}

export interface Quote {
  text: string;
  attribution: string | null;
}

export interface DeepAnalysis {
  coreDispute: string | null;
  perspectives: Perspective[];
  implications: string | null;
  quotes: Quote[];
}

export interface DeepReadImageAsset {
  url: string;
  caption: string | null;
  confidence: string;  // 'hero' | 'inline' | 'reject'
  score: number;
  source: string | null;
  qualityHint: string | null;
  selectionReason: string | null;
}

export interface DiagramNode {
  id: string;
  label: string;
  note: string | null;
  group: string | null;
}

export interface DiagramEdge {
  from: string;
  to: string;
  label: string | null;
}

export interface DeepReadDiagram {
  type: string;
  title: string;
  reason: string | null;
  nodes: DiagramNode[];
  edges: DiagramEdge[];
  caption: string | null;
}

export interface HeroSelection {
  url: string;
  reason: string;
}

export interface InlineSelection {
  url: string;
  reason: string;
}

export interface RejectedImage {
  url: string;
  score: number;
  reason: string;
}

export interface DeepReadVisualDiagnostics {
  candidateCount: number;
  heroSelection: HeroSelection | null;
  inlineSelections: InlineSelection[];
  rejectedImages: RejectedImage[];
}

export interface DeepReadSectionState {
  status: string;  // 'PENDING' | 'RUNNING' | 'READY' | 'FAILED'(用 string 而非 enum,ArkTS 友好)
  errorMessage: string | null;
}

export interface DeepReadOutput {
  topicType: string;
  generationComplete: boolean;
  generationPhase: string;  // DeepReadGenerationPhase
  summary: string;
  keyEntities: string[];
  timeline: TimelineEvent[] | null;
  corePoints: CorePoint[] | null;
  analysis: DeepAnalysis;
  extendedReading: ReadingLink[];
  heroImageQuery: string | null;
  heroImageUrl: string | null;
  heroCaption: string | null;
  heroImageConfidence: string | null;
  imageAssets: DeepReadImageAsset[];
  diagram: DeepReadDiagram | null;
  visualDiagnostics: DeepReadVisualDiagnostics | null;
  references: ReadingLink[];
  sectionStates: Record<string, DeepReadSectionState>;  // key = DeepReadGenerationStage
  sectionQualities: Record<string, string>;  // key = stage, value = quality
  // 注意:不移植 verificationState(Android 生产死代码,见 design 0.1)
}

export const makeEmptyDeepReadOutput = (): DeepReadOutput => ({
  topicType: 'event',
  generationComplete: false,
  generationPhase: 'IDLE',
  summary: '',
  keyEntities: [],
  timeline: null,
  corePoints: null,
  analysis: { coreDispute: null, perspectives: [], implications: null, quotes: [] },
  extendedReading: [],
  heroImageQuery: null,
  heroImageUrl: null,
  heroCaption: null,
  heroImageConfidence: null,
  imageAssets: [],
  diagram: null,
  visualDiagnostics: null,
  references: [],
  sectionStates: {},
  sectionQualities: {},
});
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd harmony/deepread && npx tsx --test src/test/models.test.ts`
Expected: PASS(1 test)

- [ ] **Step 5: 提交**

```bash
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview
git add harmony/deepread/src/main/ets/domain/models.ts harmony/deepread/src/test/models.test.ts
git commit -m "feat(deepread): add DeepReadOutput interface and empty factory

Port of Android DeepReadModels.kt:6-43. Pure ArkTS interface, no SDK
deps. verificationState omitted (Android production dead code per
design doc 0.1)."
```

---

## Task 2: JSON 序列化往返测试

**Files:**
- Modify: `harmony/deepread/src/test/models.test.ts`(追加测试)
- Create: `harmony/deepread/src/test/fixtures/deepread_output_complete.json`(完整输出 fixture)

理由:`output_json` 字段在 RDB 里存 JSON,序列化往返必须稳定。

- [ ] **Step 1: 创建完整 fixture**

`harmony/deepread/src/test/fixtures/deepread_output_complete.json`:
```json
{
  "topicType": "event",
  "generationComplete": true,
  "generationPhase": "COMPLETE",
  "summary": "某地发生重大事件,本文梳理来龙去脉。",
  "keyEntities": ["实体A", "实体B"],
  "timeline": [
    { "date": "2026-06-01", "event": "事件一发生", "isHighlight": true, "imageUrl": null, "imageCaption": null }
  ],
  "corePoints": null,
  "analysis": {
    "coreDispute": "争议焦点在于...",
    "perspectives": [{ "holder": "方A", "viewpoint": "认为..." }],
    "implications": "可能影响...",
    "quotes": [{ "text": "原话", "attribution": "某人" }]
  },
  "extendedReading": [{ "title": "相关阅读", "url": "https://example.com", "source": null, "publishedAt": null }],
  "heroImageQuery": null,
  "heroImageUrl": "https://example.com/hero.jpg",
  "heroCaption": "图片说明",
  "heroImageConfidence": "hero",
  "imageAssets": [
    { "url": "https://example.com/hero.jpg", "caption": "图片说明", "confidence": "hero", "score": 0.9, "source": "source-1", "qualityHint": "high", "selectionReason": "topic match" }
  ],
  "diagram": {
    "type": "causal_chain", "title": "因果链", "reason": "展示事件因果",
    "nodes": [{ "id": "n1", "label": "起因", "note": null, "group": null }],
    "edges": [], "caption": null
  },
  "visualDiagnostics": {
    "candidateCount": 5, "heroSelection": { "url": "https://example.com/hero.jpg", "reason": "best" },
    "inlineSelections": [], "rejectedImages": []
  },
  "references": [{ "title": "来源", "url": "https://src.com", "source": null, "publishedAt": null }],
  "sectionStates": {
    "OVERVIEW": { "status": "READY", "errorMessage": null },
    "NARRATIVE": { "status": "READY", "errorMessage": null },
    "ANALYSIS": { "status": "READY", "errorMessage": null },
    "EXTENDED_READING": { "status": "READY", "errorMessage": null }
  },
  "sectionQualities": {
    "OVERVIEW": "STANDARD", "NARRATIVE": "STANDARD",
    "ANALYSIS": "STANDARD", "EXTENDED_READING": "STANDARD"
  }
}
```

- [ ] **Step 2: 写失败测试 — JSON 往返**

追加到 `harmony/deepread/src/test/models.test.ts`:
```typescript
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const __dirname_here = dirname(fileURLToPath(import.meta.url));

test('DeepReadOutput survives JSON round-trip', () => {
  const fixturePath = join(__dirname_here, 'fixtures', 'deepread_output_complete.json');
  const json = readFileSync(fixturePath, 'utf-8');
  const parsed = JSON.parse(json) as DeepReadOutput;
  // 往返:obj → json → obj
  const roundTripped = JSON.parse(JSON.stringify(parsed)) as DeepReadOutput;
  assert.deepEqual(roundTripped, parsed);
  // 关键字段保留
  assert.equal(parsed.generationComplete, true);
  assert.equal(parsed.sectionStates['OVERVIEW'].status, 'READY');
  assert.equal(parsed.heroImageConfidence, 'hero');
});
```

(注意:在 test 文件顶部加 `import { DeepReadOutput } from '../src/main/ets/domain/models.ts';`)

- [ ] **Step 3: 跑测试确认通过(应该直接通过,因为 interface 是数据形状)**

Run: `cd harmony/deepread && npx tsx --test src/test/models.test.ts`
Expected: PASS(2 tests)

- [ ] **Step 4: 提交**

```bash
git add harmony/deepread/src/test/models.test.ts harmony/deepread/src/test/fixtures/deepread_output_complete.json
git commit -m "test(deepread): JSON round-trip stability for DeepReadOutput"
```

---

## Task 3: 枚举与常量

**Files:**
- Create: `harmony/deepread/src/main/ets/domain/enums.ts`
- Create: `harmony/deepread/src/test/enums.test.ts`

- [ ] **Step 1: 写失败测试 — 枚举值 + STAGE_ORDER 顺序**

`harmony/deepread/src/test/enums.test.ts`:
```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  STAGE_ORDER, STAGE_LABELS, IMAGE_CONFIDENCE,
  WRITER_TOOL_NAMES, STAGE_TIMEOUT_MS, STAGE_SOURCE_LIMIT, STAGE_EXCERPT_LIMIT,
} from '../src/main/ets/domain/enums.ts';

test('STAGE_ORDER is in declaration order', () => {
  assert.deepEqual(STAGE_ORDER, ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING']);
});

test('STAGE_LABELS covers all stages', () => {
  for (const s of STAGE_ORDER) {
    assert.ok(s in STAGE_LABELS, `stage ${s} missing label`);
    assert.ok(STAGE_LABELS[s].length > 0);
  }
});

test('IMAGE_CONFIDENCE constants', () => {
  assert.equal(IMAGE_CONFIDENCE.HERO, 'hero');
  assert.equal(IMAGE_CONFIDENCE.INLINE, 'inline');
  assert.equal(IMAGE_CONFIDENCE.REJECT, 'reject');
});

test('WRITER_TOOL_NAMES maps each stage to its tool', () => {
  assert.equal(WRITER_TOOL_NAMES.OVERVIEW, 'deep_read_write_overview');
  assert.equal(WRITER_TOOL_NAMES.NARRATIVE, 'deep_read_write_narrative');
  assert.equal(WRITER_TOOL_NAMES.ANALYSIS, 'deep_read_write_analysis');
  assert.equal(WRITER_TOOL_NAMES.EXTENDED_READING, 'deep_read_write_extended_reading');
});

test('STAGE_TIMEOUT_MS per-stage values match Android', () => {
  assert.equal(STAGE_TIMEOUT_MS.OVERVIEW, 90_000);
  assert.equal(STAGE_TIMEOUT_MS.NARRATIVE, 110_000);
  assert.equal(STAGE_TIMEOUT_MS.ANALYSIS, 150_000);
  assert.equal(STAGE_TIMEOUT_MS.EXTENDED_READING, 90_000);
});

test('STAGE_SOURCE_LIMIT and STAGE_EXCERPT_LIMIT per-stage values', () => {
  assert.equal(STAGE_SOURCE_LIMIT.OVERVIEW, 6);
  assert.equal(STAGE_SOURCE_LIMIT.NARRATIVE, 9);
  assert.equal(STAGE_SOURCE_LIMIT.ANALYSIS, 8);
  assert.equal(STAGE_SOURCE_LIMIT.EXTENDED_READING, 12);
  assert.equal(STAGE_EXCERPT_LIMIT.OVERVIEW, 1000);
  assert.equal(STAGE_EXCERPT_LIMIT.EXTENDED_READING, 700);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd harmony/deepread && npx tsx --test src/test/enums.test.ts`
Expected: FAIL with "Cannot find module"

- [ ] **Step 3: 写实现**

`harmony/deepread/src/main/ets/domain/enums.ts`:
```typescript
// Deep Read 枚举与常量 — HarmonyOS port
// 用 string literal union + 常量对象,避免 ArkTS 对 enum 的限制

export type DeepReadGenerationStage = 'OVERVIEW' | 'NARRATIVE' | 'ANALYSIS' | 'EXTENDED_READING';
// 顺序由 STAGE_ORDER 保证(stage 间依赖:后 stage 读前 stage 输出)
export const STAGE_ORDER: DeepReadGenerationStage[] = ['OVERVIEW', 'NARRATIVE', 'ANALYSIS', 'EXTENDED_READING'];

export const STAGE_LABELS: Record<DeepReadGenerationStage, string> = {
  OVERVIEW: '概览',
  NARRATIVE: '时间轴叙事',
  ANALYSIS: '深度分析',
  EXTENDED_READING: '扩展阅读',
};

// 注意:无 VERIFYING(Android 生产死代码,design 0.1)
export type DeepReadGenerationPhase = 'IDLE' | 'COLLECTING' | 'PLANNING' | 'WRITING' | 'COMPLETE';

export type DeepReadSectionStatus = 'PENDING' | 'RUNNING' | 'READY' | 'FAILED';

// 注意:无 VERIFIED(Android 生产死代码)
export type DeepReadSectionQuality = 'BASIC' | 'STANDARD';

export const IMAGE_CONFIDENCE = {
  HERO: 'hero',
  INLINE: 'inline',
  REJECT: 'reject',
} as const;

export const WRITER_TOOL_NAMES: Record<DeepReadGenerationStage, string> = {
  OVERVIEW: 'deep_read_write_overview',
  NARRATIVE: 'deep_read_write_narrative',
  ANALYSIS: 'deep_read_write_analysis',
  EXTENDED_READING: 'deep_read_write_extended_reading',
};

export const VISUALS_TOOL_NAME = 'deep_read_write_visuals';
export const DIAGRAM_TOOL_NAME = 'deep_read_write_diagram';
export const FINISH_TOOL_NAME = 'deep_read_finish';

// Per-stage timeout (ms) — 照搬 Android collectRunTimeoutFor
export const STAGE_TIMEOUT_MS: Record<DeepReadGenerationStage, number> = {
  OVERVIEW: 90_000,
  NARRATIVE: 110_000,
  ANALYSIS: 150_000,
  EXTENDED_READING: 90_000,
};

// Per-stage prompt source/excerpt caps — 照搬 Android DeepReadAgentRunManager.kt:1111-1123
export const STAGE_SOURCE_LIMIT: Record<DeepReadGenerationStage, number> = {
  OVERVIEW: 6,
  NARRATIVE: 9,
  ANALYSIS: 8,
  EXTENDED_READING: 12,
};

export const STAGE_EXCERPT_LIMIT: Record<DeepReadGenerationStage, number> = {
  OVERVIEW: 1000,
  NARRATIVE: 1400,
  ANALYSIS: 1400,
  EXTENDED_READING: 700,
};

// 全局 prompt 上限
export const PROMPT_SOURCE_LIMIT = 12;
export const PROMPT_SOURCE_EXCERPT_LIMIT = 2_000;
export const PLAYBOOK_PROMPT_LIMIT = 12_000;

// Writer tool 校验阈值
export const OVERVIEW_SUMMARY_MIN_CHARS = 24;
export const OVERVIEW_SUMMARY_STORAGE_MAX_CHARS = 1_200;

// Diagram 上限
export const MAX_DIAGRAM_NODES = 6;
export const MAX_LINEAR_DIAGRAM_EDGES = 5;    // causal_chain / process_flow
export const MAX_RELATION_DIAGRAM_EDGES = 6;  // 其他 type

// Retry classifier(照搬 Android GenerationRetry.kt)
export const MAX_GENERATION_STEPS = 32;
export const MAX_SUPERVISOR_PASSES = 2;
export const MAX_LLM_RETRIES = 5;
export const RETRY_INITIAL_DELAY_MS = 1_000;
export const RETRY_MAX_DELAY_MS = 16_000;
export const RETRY_JITTER_RATIO = 0.15;

// 缓存 TTL
export const DEEP_READ_TTL_MS = 24 * 60 * 60 * 1_000;       // 24h fresh
export const DEEP_READ_HISTORY_RETENTION_MS = 7 * 24 * 60 * 60 * 1_000;  // 7d history

// Prefetch
export const PREFETCH_WALL_BUDGET_MS = 36_000;
export const PREFETCH_LRU_TTL_MS = 10 * 60 * 1_000;
export const PREFETCH_LRU_MAX_ENTRIES = 16;
export const MAX_SOURCES = 12;
export const MAX_SEARCH_RESULTS = 14;
export const MIN_SOURCE_CHARS = 280;
export const MIN_SEED_SOURCE_CHARS = 15;
export const READER_NATIVE_MIN_CHARS = 18;
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd harmony/deepread && npx tsx --test src/test/enums.test.ts`
Expected: PASS(6 tests)

- [ ] **Step 5: 提交**

```bash
git add harmony/deepread/src/main/ets/domain/enums.ts harmony/deepread/src/test/enums.test.ts
git commit -m "feat(deepread): add enums and constants (stages, timeouts, limits)

Port of Android stage constants. No VERIFYING/VERIFIED (dead code per
design 0.1). Per-stage timeout/source/excerpt caps match Android
DeepReadAgentRunManager.kt:1064-1123."
```

---

## Task 4: 完成判定辅助函数(`isComplete` / `sectionsReady` / `hasAnyReadySection` / `statusOf`)

**Files:**
- Create: `harmony/deepread/src/main/ets/domain/helpers.ts`
- Create: `harmony/deepread/src/test/helpers.test.ts`

- [ ] **Step 1: 写失败测试**

`harmony/deepread/src/test/helpers.test.ts`:
```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { makeEmptyDeepReadOutput } from '../src/main/ets/domain/models.ts';
import {
  isComplete, sectionsReady, hasAnyReadySection, statusOf,
} from '../src/main/ets/domain/helpers.ts';
import { STAGE_ORDER } from '../src/main/ets/domain/enums.ts';

const readyState = { status: 'READY', errorMessage: null };
const pendingState = { status: 'PENDING', errorMessage: null };

test('sectionsReady false on empty sectionStates', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(sectionsReady(o), false);
});

test('sectionsReady true when all 4 stages READY', () => {
  const o = makeEmptyDeepReadOutput();
  for (const s of STAGE_ORDER) o.sectionStates[s] = readyState;
  assert.equal(sectionsReady(o), true);
});

test('sectionsReady false when any stage not READY', () => {
  const o = makeEmptyDeepReadOutput();
  for (const s of STAGE_ORDER) o.sectionStates[s] = readyState;
  o.sectionStates['ANALYSIS'] = pendingState;
  assert.equal(sectionsReady(o), false);
});

test('sectionsReady false when extra stages present but a required one missing', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates['OVERVIEW'] = readyState;
  o.sectionStates['NARRATIVE'] = readyState;
  o.sectionStates['ANALYSIS'] = readyState;
  // EXTENDED_READING 缺失
  assert.equal(sectionsReady(o), false);
});

test('hasAnyReadySection false when none ready', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(hasAnyReadySection(o), false);
});

test('hasAnyReadySection true when at least one READY', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates['OVERVIEW'] = readyState;
  assert.equal(hasAnyReadySection(o), true);
});

test('isComplete requires generationComplete AND sectionsReady', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(isComplete(o), false);
  o.generationComplete = true;
  assert.equal(isComplete(o), false);  // sections still not ready
  for (const s of STAGE_ORDER) o.sectionStates[s] = readyState;
  assert.equal(isComplete(o), true);
});

test('statusOf returns PENDING for missing stage', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(statusOf(o, 'OVERVIEW'), 'PENDING');
});

test('statusOf returns actual status', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates['OVERVIEW'] = { status: 'FAILED', errorMessage: 'timeout' };
  assert.equal(statusOf(o, 'OVERVIEW'), 'FAILED');
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd harmony/deepread && npx tsx --test src/test/helpers.test.ts`
Expected: FAIL

- [ ] **Step 3: 写实现**

`harmony/deepread/src/main/ets/domain/helpers.ts`:
```typescript
// Deep Read 纯函数辅助 — HarmonyOS port of Android DeepReadModels.kt helpers
import { DeepReadOutput, DeepReadSectionState } from './models.ts';
import { STAGE_ORDER, DeepReadGenerationStage, DeepReadSectionStatus } from './enums.ts';

export const statusOf = (o: DeepReadOutput, stage: DeepReadGenerationStage): DeepReadSectionStatus => {
  return o.sectionStates[stage]?.status ?? 'PENDING';
};

export const sectionsReady = (o: DeepReadOutput): boolean => {
  // 严格:4 个 stage 都必须存在且 READY(照搬 Android DeepReadModels.kt:109-110)
  return STAGE_ORDER.every(s => o.sectionStates[s]?.status === 'READY');
};

export const hasAnyReadySection = (o: DeepReadOutput): boolean => {
  return STAGE_ORDER.some(s => o.sectionStates[s]?.status === 'READY');
};

export const isComplete = (o: DeepReadOutput): boolean => {
  // 照搬 Android isComplete = generationComplete && isVerifiedComplete()
  // isVerifiedComplete = sectionsReady()(verificationState 不参与,design 0.1)
  return o.generationComplete && sectionsReady(o);
};

export const firstFailureMessage = (o: DeepReadOutput): string | null => {
  // 照搬 Android sectionFailureMessage:声明顺序第一个 FAILED
  for (const s of STAGE_ORDER) {
    const state = o.sectionStates[s];
    if (state?.status === 'FAILED') return state.errorMessage;
  }
  return null;
};

export const withSectionStatus = (
  o: DeepReadOutput,
  stage: DeepReadGenerationStage,
  status: DeepReadSectionStatus,
  errorMessage: string | null = null,
): DeepReadOutput => {
  // 不可变更新(ArkTS 禁运行时 mutation)
  const newState: DeepReadSectionState = { status, errorMessage };
  return {
    ...o,
    sectionStates: { ...o.sectionStates, [stage]: newState },
  };
};

export const withSectionQuality = (
  o: DeepReadOutput,
  stage: DeepReadGenerationStage,
  quality: string,
): DeepReadOutput => {
  return {
    ...o,
    sectionQualities: { ...o.sectionQualities, [stage]: quality },
  };
};

export const withPhase = (
  o: DeepReadOutput,
  phase: string,
  extra: Partial<DeepReadOutput> = {},
): DeepReadOutput => {
  return { ...o, generationPhase: phase, ...extra };
};
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd harmony/deepread && npx tsx --test src/test/helpers.test.ts`
Expected: PASS(9 tests)

- [ ] **Step 5: 提交**

```bash
git add harmony/deepread/src/main/ets/domain/helpers.ts harmony/deepread/src/test/helpers.test.ts
git commit -m "feat(deepread): add completion helpers (isComplete/sectionsReady/etc)

Port of Android DeepReadModels.kt:90-113. isComplete =
generationComplete && sectionsReady(). Immutable withSectionStatus/
withSectionQuality/withPhase for ArkTS compliance."
```

---

## Task 5: `withInferredSectionStates`(老数据迁移)

**Files:**
- Modify: `harmony/deepread/src/main/ets/domain/helpers.ts`(追加函数)
- Create: `harmony/deepread/src/test/inferred_states.test.ts`

- [ ] **Step 1: 写失败测试 — 老数据推断**

`harmony/deepread/src/test/inferred_states.test.ts`:
```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { withInferredSectionStates } from '../src/main/ets/domain/helpers.ts';
import { makeEmptyDeepReadOutput } from '../src/main/ets/domain/models.ts';

test('withInferredSectionStates is no-op when sectionStates populated', () => {
  const o = makeEmptyDeepReadOutput();
  o.sectionStates = { OVERVIEW: { status: 'READY', errorMessage: null } };
  const result = withInferredSectionStates(o);
  assert.equal(result, o);  // 同一引用(早返回)
});

test('infers READY from summary for OVERVIEW', () => {
  const o = makeEmptyDeepReadOutput();
  o.summary = '这是一段足够长的摘要用于推断 overview ready';
  o.sectionStates = {};  // 空,触发推断
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['OVERVIEW']?.status, 'READY');
});

test('infers READY from timeline for NARRATIVE', () => {
  const o = makeEmptyDeepReadOutput();
  o.timeline = [{ date: '2026-06-01', event: '这是一段足够长的事件描述', isHighlight: false, imageUrl: null, imageCaption: null }];
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['NARRATIVE']?.status, 'READY');
});

test('infers READY from analysis perspectives for ANALYSIS', () => {
  const o = makeEmptyDeepReadOutput();
  o.analysis = {
    coreDispute: null, implications: null,
    perspectives: [{ holder: null, viewpoint: '这是一段足够长的观点描述用于分析' }],
    quotes: [],
  };
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['ANALYSIS']?.status, 'READY');
});

test('infers READY from extendedReading for EXTENDED_READING', () => {
  const o = makeEmptyDeepReadOutput();
  o.extendedReading = [{ title: 'x', url: 'https://example.com', source: null, publishedAt: null }];
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  assert.equal(result.sectionStates['EXTENDED_READING']?.status, 'READY');
});

test('legacyComplete forces all stages READY', () => {
  const o = makeEmptyDeepReadOutput();
  o.generationComplete = true;  // legacy
  o.summary = '';  // 内容空
  o.sectionStates = {};
  const result = withInferredSectionStates(o);
  // legacyComplete=true 时所有 stage READY,且重算 generationComplete
  assert.equal(result.sectionStates['OVERVIEW']?.status, 'READY');
  assert.equal(result.sectionStates['EXTENDED_READING']?.status, 'READY');
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd harmony/deepread && npx tsx --test src/test/inferred_states.test.ts`
Expected: FAIL(function 不存在)

- [ ] **Step 3: 实现 `withInferredSectionStates`**

追加到 `harmony/deepread/src/main/ets/domain/helpers.ts`:
```typescript
export const withInferredSectionStates = (o: DeepReadOutput): DeepReadOutput => {
  // 照搬 Android DeepReadModels.kt:115-141
  // sectionStates 已填充 → no-op
  if (Object.keys(o.sectionStates).length > 0) return o;

  const legacyComplete = o.generationComplete;

  const overviewReady = o.summary.trim().length > 0;
  const narrativeReady =
    (o.timeline?.some(e => e.event.length >= 20) ?? false) ||
    (o.corePoints?.some(p => p.point.length >= 10 && (p.supporting?.length ?? 0) >= 30) ?? false);
  const analysisReady =
    (o.analysis.coreDispute?.trim().length ?? 0) >= 40 ||
    (o.analysis.implications?.trim().length ?? 0) >= 40 ||
    o.analysis.perspectives.some(p => p.viewpoint.trim().length >= 30);
  const readingReady = o.extendedReading.length > 0 || o.references.length > 0;

  const states: Record<string, DeepReadSectionState> = {};
  const stageReady: Record<DeepReadGenerationStage, boolean> = {
    OVERVIEW: overviewReady,
    NARRATIVE: narrativeReady,
    ANALYSIS: analysisReady,
    EXTENDED_READING: readingReady,
  };
  for (const s of STAGE_ORDER) {
    if (legacyComplete || stageReady[s]) {
      states[s] = { status: 'READY', errorMessage: null };
    }
  }

  const merged: DeepReadOutput = { ...o, sectionStates: states };
  // 重算 generationComplete = legacyComplete && sectionsReady(merged)
  const recomputedComplete = legacyComplete && sectionsReady(merged);
  return { ...merged, generationComplete: recomputedComplete };
};
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd harmony/deepread && npx tsx --test src/test/inferred_states.test.ts`
Expected: PASS(6 tests)

- [ ] **Step 5: 提交**

```bash
git add harmony/deepread/src/main/ets/domain/helpers.ts harmony/deepread/src/test/inferred_states.test.ts
git commit -m "feat(deepread): add withInferredSectionStates legacy migration

Port of Android DeepReadModels.kt:115-141. Infers section status from
content fields when sectionStates is empty (legacy data path).
No-op when sectionStates already populated."
```

---

## Task 6: Hero 图片显示辅助(`displayHeroImageUrl` / `displayHeroCaption` / `verifiedImageUrls`)

**Files:**
- Modify: `harmony/deepread/src/main/ets/domain/helpers.ts`(追加)
- Create: `harmony/deepread/src/test/hero_display.test.ts`

- [ ] **Step 1: 写失败测试**

`harmony/deepread/src/test/hero_display.test.ts`:
```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { displayHeroImageUrl, displayHeroCaption } from '../src/main/ets/domain/helpers.ts';
import { makeEmptyDeepReadOutput } from '../src/main/ets/domain/models.ts';
import { IMAGE_CONFIDENCE } from '../src/main/ets/domain/enums.ts';

const httpUrl = 'https://example.com/hero.jpg';

test('displayHeroImageUrl null when no hero and no imageAssets', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(displayHeroImageUrl(o), null);
});

test('displayHeroImageUrl returns heroImageUrl when confidence=hero and in assets', () => {
  const o = makeEmptyDeepReadOutput();
  o.heroImageUrl = httpUrl;
  o.heroImageConfidence = IMAGE_CONFIDENCE.HERO;
  o.imageAssets = [{ url: httpUrl, caption: null, confidence: IMAGE_CONFIDENCE.HERO, score: 0.9, source: null, qualityHint: null, selectionReason: null }];
  assert.equal(displayHeroImageUrl(o), httpUrl);
});

test('displayHeroImageUrl ignores heroImageUrl when confidence != hero', () => {
  const o = makeEmptyDeepReadOutput();
  o.heroImageUrl = httpUrl;
  o.heroImageConfidence = IMAGE_CONFIDENCE.INLINE;  // 不是 hero
  o.imageAssets = [{ url: httpUrl, caption: null, confidence: IMAGE_CONFIDENCE.INLINE, score: 0.5, source: null, qualityHint: null, selectionReason: null }];
  // 没有 hero confidence 的 asset,fallback 到第一个非 reject
  assert.equal(displayHeroImageUrl(o), httpUrl);
});

test('displayHeroImageUrl skips REJECT assets', () => {
  const o = makeEmptyDeepReadOutput();
  o.imageAssets = [
    { url: 'https://example.com/a.jpg', caption: null, confidence: IMAGE_CONFIDENCE.REJECT, score: 0.1, source: null, qualityHint: null, selectionReason: null },
    { url: 'https://example.com/b.jpg', caption: null, confidence: IMAGE_CONFIDENCE.INLINE, score: 0.6, source: null, qualityHint: null, selectionReason: null },
  ];
  assert.equal(displayHeroImageUrl(o), 'https://example.com/b.jpg');
});

test('displayHeroImageUrl skips non-http urls', () => {
  const o = makeEmptyDeepReadOutput();
  o.imageAssets = [
    { url: 'data:image/png;base64,xxx', caption: null, confidence: IMAGE_CONFIDENCE.INLINE, score: 0.6, source: null, qualityHint: null, selectionReason: null },
  ];
  assert.equal(displayHeroImageUrl(o), null);
});

test('displayHeroCaption null when no image', () => {
  const o = makeEmptyDeepReadOutput();
  assert.equal(displayHeroCaption(o), null);
});

test('displayHeroCaption returns heroCaption when matching heroImageUrl', () => {
  const o = makeEmptyDeepReadOutput();
  o.heroImageUrl = httpUrl;
  o.heroCaption = '主图说明';
  o.heroImageConfidence = IMAGE_CONFIDENCE.HERO;
  o.imageAssets = [{ url: httpUrl, caption: null, confidence: IMAGE_CONFIDENCE.HERO, score: 0.9, source: null, qualityHint: null, selectionReason: null }];
  assert.equal(displayHeroCaption(o), '主图说明');
});

test('displayHeroCaption falls back to asset caption', () => {
  const o = makeEmptyDeepReadOutput();
  o.imageAssets = [{ url: httpUrl, caption: 'asset 说明', confidence: IMAGE_CONFIDENCE.INLINE, score: 0.6, source: null, qualityHint: null, selectionReason: null }];
  assert.equal(displayHeroCaption(o), 'asset 说明');
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd harmony/deepread && npx tsx --test src/test/hero_display.test.ts`
Expected: FAIL

- [ ] **Step 3: 实现**

追加到 `harmony/deepread/src/main/ets/domain/helpers.ts`:
```typescript
import { IMAGE_CONFIDENCE } from './enums.ts';

const isHttpUrl = (url: string): boolean => {
  return url.startsWith('http://') || url.startsWith('https://');
};

export const verifiedImageUrls = (o: DeepReadOutput): Set<string> => {
  // 照搬 Android DeepReadModels.kt:334-339
  const result = new Set<string>();
  for (const a of o.imageAssets) {
    if (a.confidence !== IMAGE_CONFIDENCE.REJECT && isHttpUrl(a.url)) {
      result.add(a.url);
    }
  }
  return result;
};

export const displayHeroImageUrl = (o: DeepReadOutput): string | null => {
  // 照搬 Android DeepReadModels.kt:341-347
  const safe = verifiedImageUrls(o);
  if (o.heroImageUrl && safe.has(o.heroImageUrl) && o.heroImageConfidence === IMAGE_CONFIDENCE.HERO) {
    return o.heroImageUrl;
  }
  // fallback:第一个非 reject 的 http asset
  for (const a of o.imageAssets) {
    if (safe.has(a.url)) return a.url;
  }
  return null;
};

export const displayHeroCaption = (o: DeepReadOutput, imageUrl: string | null = displayHeroImageUrl(o)): string | null => {
  // 照搬 Android DeepReadModels.kt:349-353
  if (imageUrl === null) return null;
  if (o.heroImageUrl === imageUrl && o.heroCaption && o.heroCaption.trim().length > 0) {
    return o.heroCaption;
  }
  for (const a of o.imageAssets) {
    if (a.url === imageUrl && a.caption && a.caption.trim().length > 0) {
      return a.caption;
    }
  }
  return null;
};
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd harmony/deepread && npx tsx --test src/test/hero_display.test.ts`
Expected: PASS(8 tests)

- [ ] **Step 5: 提交**

```bash
git add harmony/deepread/src/main/ets/domain/helpers.ts harmony/deepread/src/test/hero_display.test.ts
git commit -m "feat(deepread): add hero image display helpers

Port of Android DeepReadModels.kt:334-353. Hero gated on
confidence=='hero' AND url in verified pool. Falls back to first
non-reject http asset. Caption falls back from hero to asset caption."
```

---

## Task 7: 文章可读性判定(`hasReadableArticle` / `hasEnoughChinese`)

**Files:**
- Modify: `harmony/deepread/src/main/ets/domain/helpers.ts`
- Modify: `harmony/deepread/src/test/helpers.test.ts`(追加)

- [ ] **Step 1: 写失败测试 — 阈值边界**

追加到 `harmony/deepread/src/test/helpers.test.ts`:
```typescript
import { hasReadableArticle, hasEnoughChinese } from '../src/main/ets/domain/helpers.ts';

test('hasReadableArticle false on empty output', () => {
  assert.equal(hasReadableArticle(makeEmptyDeepReadOutput()), false);
});

test('hasReadableArticle true with rich content', () => {
  const o = makeEmptyDeepReadOutput();
  o.summary = '这是一段足够长的摘要至少八十字符这是一段足够长的摘要至少八十字符这是一段足够长的摘要至少八十字符';
  o.timeline = [
    { date: '2026-06-01', event: '事件一发生且描述足够长超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
    { date: '2026-06-02', event: '事件二发生且描述足够长超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
  ];
  assert.equal(hasReadableArticle(o), true);
});

test('hasReadableArticle false when summary contains fallback phrase', () => {
  const o = makeEmptyDeepReadOutput();
  o.summary = '当前可抓取信息仍偏薄这是一段足够长的摘要至少八十字符这是一段足够长的摘要至少八十字符这是一段足够长的摘要至少八十字符';
  o.timeline = [
    { date: '2026-06-01', event: '事件一发生且描述足够长超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
    { date: '2026-06-02', event: '事件二发生且描述足够长超过二十个字符', isHighlight: false, imageUrl: null, imageCaption: null },
  ];
  assert.equal(hasReadableArticle(o), false);  // 命中 lowInformationFallbackPhrases
});

test('hasEnoughChinese true for predominantly CJK text', () => {
  assert.equal(hasEnoughChinese('这是一段中文内容,字符数超过八十这是一段中文内容,字符数超过八十这是一段中文内容,字符数超过八十'), true);
});

test('hasEnoughChinese false for predominantly latin text', () => {
  assert.equal(hasEnoughChinese('This is english text with some 中文 twelve chars'), false);
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd harmony/deepread && npx tsx --test src/test/helpers.test.ts`
Expected: FAIL on hasReadableArticle/hasEnoughChinese

- [ ] **Step 3: 实现**

追加到 `harmony/deepread/src/main/ets/domain/helpers.ts`:
```typescript
// 照搬 Android DeepReadModels.kt:374-387 lowInformationFallbackPhrases
const LOW_INFORMATION_FALLBACK_PHRASES: string[] = [
  '当前可抓取信息仍偏薄',
  '可用信息不足以形成稳定脉络',
  '抓取摘要不足',
  '来源提供了相关背景线索',
  '来源链接统一收在扩展阅读',
  '避免把来源列表误写成深度分析',
  '后续深读应围绕',
  '模型输出格式不稳定',
  '目前来源未覆盖',
  '更多材料',
  '链接见扩展阅读',
  '来源见扩展阅读',
];

const containsAny = (text: string, phrases: string[]): boolean => {
  return phrases.some(p => text.includes(p));
};

export const hasEnoughChinese = (text: string): boolean => {
  // 照搬 Android DeepReadModels.kt:324-332
  const cjk = (text.match(/[\u4e00-\u9fff]/g) ?? []).length;
  const latin = (text.match(/[a-zA-Z]/g) ?? []).length;
  if (cjk >= 80) return true;
  if (cjk < 12 && latin > 30) return false;
  return cjk / (cjk + latin) >= 0.35;
};

export const hasReadableArticle = (o: DeepReadOutput): boolean => {
  // 照搬 Android DeepReadModels.kt:309-322
  if (o.summary.trim().length < 80) return false;
  if (containsAny(o.summary, LOW_INFORMATION_FALLBACK_PHRASES)) return false;

  const timelineCount = o.timeline?.filter(e => e.event.length >= 20).length ?? 0;
  const corePointsCount = o.corePoints?.filter(p => p.point.length >= 10 && (p.supporting?.length ?? 0) >= 30).length ?? 0;
  if (timelineCount < 2 && corePointsCount < 2) return false;

  const analysisOk =
    (o.analysis.coreDispute?.trim().length ?? 0) >= 40 ||
    (o.analysis.implications?.trim().length ?? 0) >= 40 ||
    o.analysis.perspectives.some(p => p.viewpoint.trim().length >= 30);
  if (!analysisOk) return false;

  return true;
};
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd harmony/deepread && npx tsx --test src/test/helpers.test.ts`
Expected: PASS(全部,含新 5 个)

- [ ] **Step 5: 提交**

```bash
git add harmony/deepread/src/main/ets/domain/helpers.ts harmony/deepread/src/test/helpers.test.ts
git commit -m "feat(deepread): add hasReadableArticle and hasEnoughChinese

Port of Android DeepReadModels.kt:309-387. Detects low-information
fallback drafts via 12 Chinese phrases. CJK ratio check 80/12/0.35
thresholds match Android."
```

---

## Task 8: `deriveTopicId`(URL 优先 hash)

**Files:**
- Create: `harmony/deepread/src/main/ets/domain/topic_id.ts`
- Create: `harmony/deepread/src/test/topic_id.test.ts`

- [ ] **Step 1: 写失败测试**

`harmony/deepread/src/test/topic_id.test.ts`:
```typescript
import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { deriveTopicId, normalizeUrl, normalizeTitle } from '../src/main/ets/domain/topic_id.ts';

test('URL input: hash of normalized URL, 32 hex chars', () => {
  const id = deriveTopicId({ url: 'https://example.com/news/123', title: 'ignored' });
  assert.match(id, /^[a-f0-9]{32}$/);
});

test('URL input: same URL → same id', () => {
  const a = deriveTopicId({ url: 'https://example.com/news/123', title: 'x' });
  const b = deriveTopicId({ url: 'https://example.com/news/123', title: 'y' });
  assert.equal(a, b);
});

test('URL normalization: strip tracking params', () => {
  const a = deriveTopicId({ url: 'https://example.com/news/123', title: 'x' });
  const b = deriveTopicId({ url: 'https://example.com/news/123?utm_source=foo&utm_medium=bar', title: 'x' });
  assert.equal(a, b, 'utm_ params should not affect topic_id');
});

test('URL normalization: lowercase host', () => {
  const a = deriveTopicId({ url: 'https://Example.com/News/123', title: 'x' });
  const b = deriveTopicId({ url: 'https://example.com/News/123', title: 'x' });
  assert.equal(a, b, 'host case-insensitive');
});

test('URL normalization: strip fragment', () => {
  const a = deriveTopicId({ url: 'https://example.com/news/123', title: 'x' });
  const b = deriveTopicId({ url: 'https://example.com/news/123#section', title: 'x' });
  assert.equal(a, b);
});

test('title input: hash of normalized title, 32 hex chars', () => {
  const id = deriveTopicId({ url: null, title: '某话题标题' });
  assert.match(id, /^[a-f0-9]{32}$/);
});

test('title normalization: lowercase + single space + trim', () => {
  const a = deriveTopicId({ url: null, title: '某  话题   标题' });
  const b = deriveTopicId({ url: null, title: '某 话题 标题' });
  // Android topicId() 用 lowercase + \s+ → 单空格 + trim
  // 这两个 title 规范化后都是 '某 话题 标题'(单空格)
  assert.equal(a, b);
});

test('URL input and title input produce different ids', () => {
  const urlId = deriveTopicId({ url: 'https://example.com/abc', title: '某话题' });
  const titleId = deriveTopicId({ url: null, title: '某话题' });
  assert.notEqual(urlId, titleId);
});

test('normalizeUrl: non-http URL passes through (not treated as URL)', () => {
  // 非 http/https 的 "url" 不应走 URL 路径
  const id = deriveTopicId({ url: 'ftp://example.com', title: '某话题' });
  const titleId = deriveTopicId({ url: null, title: '某话题' });
  assert.equal(id, titleId, 'non-http url falls back to title path');
});

test('normalizeUrl exports', () => {
  assert.equal(normalizeUrl('https://example.com/path'), 'https://example.com/path');
  assert.equal(normalizeUrl('HTTPS://EXAMPLE.com/path'), 'https://example.com/path');
});

test('normalizeTitle exports', () => {
  assert.equal(normalizeTitle('  Hello   World  '), 'hello world');
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd harmony/deepread && npx tsx --test src/test/topic_id.test.ts`
Expected: FAIL

- [ ] **Step 3: 实现**

`harmony/deepread/src/main/ets/domain/topic_id.ts`:
```typescript
// Deep Read topic_id 派生 — HarmonyOS 独立 app 统一规则
// 参见 design 3.5。Android 两条路径(hot-list / template-demo)都不适用,
// 鸿蒙版用 URL 优先 + 规范化。

import { createHash } from 'crypto';  // node 内置(测试用);鸿蒙运行时替换为 @ohos.security.cryptoHash

const HTTP_PREFIX = /^https?:\/\//i;

const TRACKING_PARAMS = new Set([
  'utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content',
  'spm', 'from', 'ref', 'ref_src', 'fbclid', 'gclid',
]);

export const normalizeUrl = (url: string): string => {
  // 1. 小写 host(保留 path 大小写,因为 path 可能大小写敏感)
  // 2. 去 fragment
  // 3. 去 tracking param
  try {
    const u = new URL(url);
    u.hash = '';
    u.hostname = u.hostname.toLowerCase();
    const params = new URLSearchParams(u.search);
    const filtered = new URLSearchParams();
    for (const [k, v] of params.entries()) {
      if (!TRACKING_PARAMS.has(k.toLowerCase())) filtered.append(k, v);
    }
    u.search = filtered.toString();
    return u.toString();
  } catch {
    // URL 解析失败,原样返回
    return url;
  }
};

export const normalizeTitle = (title: string): string => {
  // 照搬 Android HotListRepository.kt:208-212: lowercase + \s+ → 单空格 + trim
  return title.toLowerCase().replace(/\s+/g, ' ').trim();
};

const sha256Hex32 = (input: string): string => {
  return createHash('sha256').update(input, 'utf-8').digest('hex').substring(0, 32);
};

export const deriveTopicId = (input: { url: string | null | undefined; title: string }): string => {
  if (input.url && HTTP_PREFIX.test(input.url)) {
    return sha256Hex32(normalizeUrl(input.url));
  }
  // 非 http URL(ftp 等)或 url 为空 → 走 title 路径
  return sha256Hex32(normalizeTitle(input.title));
};
```

> **注意**:`createHash` 是 node 内置,测试用。鸿蒙运行时替换为 `@ohos.security.cryptoHash` 的 `createHash('sha256')`。替换点用一个 adapter 函数封装,见 Task 9。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd harmony/deepread && npx tsx --test src/test/topic_id.test.ts`
Expected: PASS(11 tests)

- [ ] **Step 5: 提交**

```bash
git add harmony/deepread/src/main/ets/domain/topic_id.ts harmony/deepread/src/test/topic_id.test.ts
git commit -m "feat(deepread): add deriveTopicId with URL-priority hash

design 3.5: URL input → sha256(normalizeUrl)[:32]; title input →
sha256(normalizeTitle)[:32]. URL normalization strips tracking params
(utm_*, spm, fbclid, etc), lowercases host, drops fragment. URL and
title paths never collide (different hash inputs).

Note: uses node crypto for testing; HarmonyOS runtime uses
@ohos.security.cryptoHash via an adapter (Task 9)."
```

---

## Task 9: 模块声明 + node 测试基础设施

**Files:**
- Create: `harmony/deepread/package.json`
- Create: `harmony/deepread/tsconfig.json`
- Create: `harmony/deepread/oh-package.json5`
- Create: `harmony/deepread/README.md`
- Create: `harmony/deepread/.gitignore`

- [ ] **Step 1: 写 package.json**

`harmony/deepread/package.json`:
```json
{
  "name": "@amber/deepread-domain",
  "version": "0.0.1",
  "private": true,
  "description": "Deep Read domain layer (pure ArkTS, zero SDK deps)",
  "type": "module",
  "scripts": {
    "test": "tsx --test \"src/test/**/*.test.ts\"",
    "test:watch": "tsx watch --test \"src/test/**/*.test.ts\""
  },
  "devDependencies": {
    "@types/node": "^22.0.0",
    "tsx": "^4.19.0",
    "typescript": "^5.5.0"
  }
}
```

- [ ] **Step 2: 写 tsconfig.json**

`harmony/deepread/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "strict": true,
    "noImplicitAny": true,
    "strictNullChecks": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "verbatimModuleSyntax": true,
    "noUncheckedIndexedAccess": false,
    "lib": ["ES2022"],
    "types": ["node"]
  },
  "include": ["src/**/*.ts"],
  "exclude": ["node_modules"]
}
```

- [ ] **Step 3: 写 oh-package.json5(鸿蒙模块声明,后续并入鸿蒙工程用)**

`harmony/deepread/oh-package.json5`:
```json5
{
  "name": "@amber/deepread-domain",
  "version": "0.0.1",
  "description": "Deep Read domain layer (pure ArkTS)",
  "main": "src/main/ets/domain/index.ts",
  "license": "Apache-2.0",
  "dependencies": {}
}
```

- [ ] **Step 4: 写 README**

`harmony/deepread/README.md`:
```markdown
# @amber/deepread-domain

Deep Read 鸿蒙版的 Domain 层。纯 ArkTS interface + 纯函数,零鸿蒙 SDK 依赖,零运行时 mutation(符合 ArkTS 约束)。

## 内容

- `models.ts` — `DeepReadOutput` 及全部子 interface
- `enums.ts` — stage/phase/status/quality 类型 + 常量(timeout/limits)
- `helpers.ts` — `isComplete`/`sectionsReady`/`hasAnyReadySection`/`withInferredSectionStates`/`displayHeroImageUrl`/`hasReadableArticle` 等
- `topic_id.ts` — `deriveTopicId`(URL 优先 hash)

## 测试

\`\`\`bash
cd harmony/deepread
npm install
npm test
\`\`\`

测试用 node + tsx,不需要 DevEco/模拟器。

## ArkTS 约束

- 禁 `any`/`unknown`(catch clause 除外)
- 禁运行时增删属性 → 用 `{...obj, field: newVal}` 不可变更新
- enum-keyed Map → `Record<Stage, T>`
- 所有 interface 用工厂函数(`makeEmptyDeepReadOutput`)而非 class

## 后续

Plan 1.2 处理 Platform skeleton(interfaces + RDB schema + SSE assembler + JsonSchemaValidator)。
crypto adapter 替换 node `crypto` 为 `@ohos.security.cryptoHash` 在 Platform 层注入。
```

- [ ] **Step 5: 写 .gitignore**

`harmony/deepread/.gitignore`:
```
node_modules/
*.log
.DS_Store
```

- [ ] **Step 6: 安装依赖 + 跑全部测试**

```bash
cd harmony/deepread
npm install
npm test
```

Expected: 所有测试 PASS(约 40 个 test)

- [ ] **Step 7: 提交**

```bash
cd /Users/mi/Downloads/AI/AmberAgent-harmony-preview
git add harmony/deepread/package.json harmony/deepread/tsconfig.json harmony/deepread/oh-package.json5 harmony/deepread/README.md harmony/deepread/.gitignore
git commit -m "build(deepread): add node test infrastructure (tsx + node:test)

Domain layer is pure ArkTS testable via node + tsx without DevEco.
oh-package.json5 declares the module for future HarmonyOS integration.
README documents ArkTS constraints and test workflow."
```

---

## Self-Review

### Spec coverage

| Spec(设计文档)条目 | 对应 Task |
|---|---|
| §3.1 DeepReadOutput schema | Task 1 |
| §3.1 子结构(Timeline/CorePoint/ReadingLink/...) | Task 1 |
| §3.2 枚举(STAGE_ORDER/labels/confidence/timeout/source/excerpt limits) | Task 3 |
| §3.3 isComplete/sectionsReady/hasAnyReadySection/statusOf | Task 4 |
| §3.3 withInferredSectionStates | Task 5 |
| §3.3 displayHeroImageUrl/displayHeroCaption | Task 6 |
| hasReadableArticle/hasEnoughChinese(Android helpers,UI 渲染用) | Task 7 |
| §3.5 deriveTopicId | Task 8 |
| 测试基础设施 | Task 9 |

### 未覆盖(留给 Plan 1.2 Platform skeleton)

- §3.4 RDB schema SQL → Plan 1.2
- §3.6 DeepReadRepository interface → Plan 1.2
- §3.7 Observable 实现 → Plan 1.2
- §3.8 Preferences + Asset Store → Plan 1.2
- §8.1 HttpClient interface → Plan 1.2
- §8.2 SseAssembler → Plan 1.2
- §8.3 Database interface → Plan 1.2
- §8.4 Storage interface → Plan 1.2
- §8.9 JsonSchemaValidator → Plan 1.2

### Placeholder scan

✅ 无 TBD/TODO
✅ 所有步骤含完整代码
✅ 类型一致(models.ts 的 `DeepReadOutput` 在所有后续 Task 引用一致)

### Type consistency check

- `DeepReadGenerationStage` 在 enums.ts 定义为 string literal union,在 helpers.ts 用 `Record<DeepReadGenerationStage, _>` ✓
- `DeepReadSectionState.status: string`(不是 enum),与 enums.ts 的 `DeepReadSectionStatus` 类型一致(string literal union) ✓
- `makeEmptyDeepReadOutput` 在 Task 1 定义,Task 4/5/6/7 测试用 ✓
- `IMAGE_CONFIDENCE` 在 Task 3 定义,Task 6 测试用 ✓

---

## 执行选择

Plan 完成并保存到 `docs/superpowers/plans/2026-06-22-deepread-domain-layer.md`。两种执行方式:

**1. Subagent-Driven(推荐)** — 每个 Task dispatch 一个新 subagent,Task 间 review,迭代快

**2. Inline Execution** — 在本 session 用 executing-plans,batch 执行 + checkpoint review

哪种?
