# E0 Provider 余额修复

状态：组件与余额请求逻辑已修复；纯逻辑测试、窄 TypeScript 检查与实际代码转译探针通过。完整 ArkTS lint、构建和设备参数更新/UI 验收由主任务整合。

## 原因与改动

- `ProviderBalanceText.ets` 原来仅在 `aboutToAppear` 查询。已给 `setting` 加 V1 `@Watch`，每次设置变更先重置 `~` 并重新查询；关闭余额、传入 `null` 或组件离开后旧回包失效。
- 组件请求增加局部递增序号，只有当前请求且组件仍在显示时才写入余额；旧请求晚完成不能覆盖当前值。
- `BalanceSupport.ets` 保留现有 Provider ID Map 与 120000 ms TTL。缓存条目补充请求身份，实际请求 URL、API Key、解析路径或 Provider ID 不一致时不能命中；空 Key、空路径、关闭余额在缓存读取前退出。
- 查询开始时固定 URL、Key、Provider ID 与解析路径字符串，响应解析和缓存登记使用同一请求参数，避免等待 HTTP 时读取已修改的设置对象。
- `provider_balance.ts` 为 11 行普通请求身份函数，被平台余额查询实际使用；身份仅供内存比较，禁止日志输出和持久化。没有新增 cache class、重试或通用请求层。
- `index.ts` export 由 `fix_d3_d4` 统一追加。

## 验证证据

修复前，运行真实 `BalanceSupport.ets` 的 TypeScript 转译版本，HTTP 边缘使用响应桩：同一 ID 的首次余额为 `10`，更换 Key 后仍返回 `10` 且只有一次 HTTP 调用；预期第二次查询应返回 `20`，缺陷已复现。

修复后：

1. `cd harmony/chat && ./node_modules/.bin/tsx --test src/test/provider_balance.test.ts`：3/3 通过。覆盖 Key、baseUrl、apiPath、resultPath、Provider ID 的隔离；名称/模型更新与 URL 尾斜线归一；字段分隔符碰撞。
2. 使用 `harmony/chat/tsconfig.json` 的选项创建仅包含新增 helper/test 的 TypeScript program：0 diagnostics。
3. 再次运行真实平台代码，验证相同请求命中缓存，更换 Key 重新 HTTP 查询，清空 Key、禁用余额返回 `~` 且不触发 HTTP。
4. 对真实组件的非 UI 方法转译运行（只转换 `struct` 与移除 ArkUI 装饰器、模板），注入可控余额 Promise。验证设置变更立即清空、回包乱序时当前值保持、禁用与离开后不更新。探针直接调用生产 `onSettingChange` 与生命周期方法，没有复刻其逻辑。原生 `@Watch` 分发与页面返回传参仍须设备验收。
5. 三个源文件均执行 `harmony/scripts/lint-arkts.sh`。工具最终 exit 0 与 `No defects found`，但同时提示报告可能不完整；实际工具日志为 `Failed to load plugin '@typescript' ... Cannot find module '@typescript/eslint-plugin'`，因此本轮不能登记有效完整 lint PASS。此问题已交主任务处理，未擅改环境。

## 整合验收点

`ChatProviderSettingsPage.all` 原为普通字段，列表 `ForEach` 使用固定 `on-id`/`off-id`，余额参数来自 `settingFor(row.id)`。已交 `fix_d3_d4` 检查并负责必要的宿主页状态依赖修复。设备验收应覆盖详情页补 Key/换 Key/关闭余额后返回列表，确认新设置真正传入保留的子组件。

本子任务未运行构建、签名、安装、截图，未提交代码。
