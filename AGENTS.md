# Repository Guidelines

本文档面向贡献者，概述本仓库的模块结构、开发流程与提交规范，便于快速上手并保持一致的协作质量。

## Build, Test, and Development Commands

使用 Android Studio 或命令行 Gradle：

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
./gradlew lint                   # 运行 Android Lint
```

构建应用需要在 `app/` 下提供 `google-services.json`（用于 Firebase）。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。

命名习惯：模块名为小写目录（如 `ai/`、`tts/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

## Testing Guidelines

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, and PPTX files
- **highlight**: Code syntax highlighting implementation
- **harmony**: HarmonyOS (ArkTS) port of the app (`entry` UI + `chat`/`deepread` shared TS logic); rules and toolchain live in the `harmony-arkts` skill — load it before touching any `.ets`/`.ts` file under `harmony/`
- **search**: Search functionality SDK (Exa, Tavily, Zhipu)
- **tts**: Text-to-speech implementation for different providers
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)

## HarmonyOS (harmony/)

ArkTS 语料稀缺，通用模型凭记忆写鸿蒙代码错误率极高，必须走「skill 铁律 + 官方文档 + 回喂」流程：

- **harmony-arkts skill（必载）**：凡修改 `harmony/` 下 `.ets`/`.ts`、接入新的 `@ohos`/`@kit` API、或执行构建/签名/lint/模拟器验证，先加载 `harmony-arkts` skill。核心铁律：API 基线是 12（`build-profile.json5`），高版本 API 一律 `canIUse()` 包裹；禁 `export let` 做全局状态/主题（用 AppStorage + @StorageProp）；状态装饰器全 V1，禁与 V2 混用；新 API 禁止凭记忆写。
- **官方文档优先**：接入仓库内无先例的 API，先 `harmony/scripts/devecocli.sh docs search <关键词>` 查本地离线官方文档（随 `@deveco/deveco-cli` 内置），命中后 `docs read <id>`；本地没有再 WebFetch developer.huawei.com。
- **deveco-mcp（workspace MCP，`.zcode/config.json` 自动连接）**：`check` 工具提供 ArkTS/C++ LSP 级诊断——在 DevEco Studio 升级到 26 前本机不可用（调用报 "Project initialization failed" 属预期，不要反复重试）；升级后可在 hvigorw 之前作快速单文件诊断。
- **验证回路（改完必走）**：`harmony/scripts/lint-arkts.sh <改动文件>`（error 级退出非 0，基线 0 error/126 warn）→ `node scripts/harmony-inventory/build-harmony-app.mjs` → `harmony/scripts/sign-hap.sh` → 模拟器安装截图目测（`hdc -t 127.0.0.1:5555 ...`，命令见 skill）。改 UI/主题/生命周期逻辑必须截图目测，暗色主题 bug 即只在模拟器上现形。

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/app/amber/agent/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  and pin status. Conversations can be truncated at a specific index and maintain chat suggestions. (
  app/src/main/java/app/amber/agent/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/app/amber/agent/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/app/amber/agent/data/ai/transformers/Transformer.kt)

## Internationalization

- String resources located in `app/src/main/res/values-*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
