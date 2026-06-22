# @amber/deepread-domain

Deep Read 鸿蒙版的 Domain 层。纯 ArkTS interface + 纯函数,零鸿蒙 SDK 依赖,零运行时 mutation(符合 ArkTS 约束)。

## 内容

- `models.ts` — `DeepReadOutput` 及全部子 interface
- `enums.ts` — stage/phase/status/quality 类型 + 常量(timeout/limits)
- `helpers.ts` — `isComplete`/`sectionsReady`/`hasAnyReadySection`/`withInferredSectionStates`/`displayHeroImageUrl`/`displayHeroCaption`/`hasReadableArticle`/`hasEnoughChinese` 等
- `topic_id.ts` — `deriveTopicId`(URL 优先 hash)

## 测试

```bash
cd harmony/deepread
npm install
npm test
```

测试用 node + tsx,不需要 DevEco/模拟器。

## ArkTS 约束

- 禁 `any`/`unknown`(catch clause 除外)
- 禁运行时增删属性 → 用 `{...obj, field: newVal}` 不可变更新
- enum-keyed Map → `Record<Stage, T>`
- 所有 interface 用工厂函数(`makeEmptyDeepReadOutput`)而非 class

## 设计决策

- **不移植 verificationState / VERIFYING phase / VERIFIED quality**:Android 生产代码里这些是死代码(只在 test 调用,生产从不赋值)。鸿蒙版忠实于 Android 生产语义,不发明新字段。
- **topic_id 用 URL 优先 hash**:Android 两条路径(hot-list / template-demo)都不适合独立 app,鸿蒙版统一规则:URL 入口基于 URL,话题入口基于 title,故意不共享 cache。
- **hasReadableArticle 用 AND 逻辑**:timeline AND corePoints AND analysis 三项都必须通过(Android DeepReadModels.kt:321)。

## 后续

Plan 1.2 处理 Platform skeleton(interfaces + RDB schema + SSE assembler + JsonSchemaValidator)。
crypto adapter 替换 node `crypto` 为 `@ohos.security.cryptoHash` 在 Platform 层注入。
