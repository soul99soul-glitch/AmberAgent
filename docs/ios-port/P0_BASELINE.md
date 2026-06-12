# P0-T1 Android 基线报告

> 日期：2026-06-12
> 执行者：AI Agent

## 环境信息

| 项目 | 值 |
|---|---|
| OS | macOS 26.6 (aarch64) |
| Gradle | 9.4.1 |
| Gradle Daemon JVM | JetBrains JDK 21 (toolchain auto-provisioned via foojay) |
| Launcher JVM | OpenJDK 17.0.19 (Homebrew) |
| Kotlin | 2.3.21 (catalog) / 2.3.0 (Gradle bundled) |
| AGP | 9.2.0 |

## `./gradlew :app:assembleDebug`

**结果：✅ BUILD SUCCESSFUL**

- 耗时：1m 48s
- 任务数：693 actionable tasks (361 executed, 332 up-to-date)
- 产出：Debug APK

包含 Rust JNI 构建（7 个 crate：html-diff-normalizer, json-expr, markdown-parser, markdown-preprocess, reader-extractor, regex-transformer, sync-crypto）+ highlight-parser + office-parsers。

## `./gradlew test`

**结果：✅ BUILD SUCCESSFUL**

- 耗时：33s
- 任务数：707 actionable tasks (137 executed, 570 up-to-date)
- 所有测试通过，无失败

### 有测试的模块

| 模块 | 测试结果 |
|---|---|
| `:app` | ✅ testDebugUnitTest 通过 |
| `:common` | ✅ testDebugUnitTest 通过 |
| `:document` | ✅ testDebugUnitTest 通过 |
| `:highlight` | ✅ testDebugUnitTest 通过 |
| `:search` | ✅ testDebugUnitTest 通过 |
| `:tts` | ✅ testDebugUnitTest 通过 |
| `:core:agent-runtime` | ✅ test 通过 |
| `:core:settings` | ✅ testDebugUnitTest 通过 |

### 无测试的模块（NO-SOURCE）

`:core:agent-utils`、`:core:ai-prompts`、`:core:event`、`:core:llm`、`:core:sync:api`、`:feature:history`、`:feature:webview`、`:feature:board:api`、`:feature:chat:api`、`:feature:deepread:api`、`:feature:live:api`、`:feature:office:api`、`:feature:terminal:api` 等纯 JVM 模块均为 NO-SOURCE。

### 编译警告（非阻塞）

3 处 `createComposeRule` deprecated 警告（`app` 模块测试代码），建议迁移到 v2 API，不影响构建。

### 已知警告（非阻塞）

`app/src/debug/AndroidManifest.xml` 中 `WorkManagerInitializer` meta-data 的移除声明无对应目标，无影响。

## 结论

Android 基线完全健康，可作为后续任务的回归对照基准。
