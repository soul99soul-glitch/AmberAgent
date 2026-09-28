# E1 native Context display consumer

日期：2026-09-28。所有权：`NativeTokenCounter.ets`、`ChatPage.ets` 的 Context 接线、
chat-domain `native_context_tokens.ts` 与测试和索引追加。
未修改原生实现、entry 构建配置、EntryAbility、其他 E0 功能；未操作设备。

## 实现与边界

- `NativeTokenCounter.ets` 把 `libamber_native.so.countBatch` 传入真正生产使用的
  `countNativeContextFootprint`；纯 domain 不导入 SO，Node 测试调用同一 helper。
- OpenAI 支持的聊天模型选取 o200k/cl100k，未知部署别名、其他供应商、gpt-oss
  仍使用既有估算。没有宣称移植 Android 产品映射：Android 实际展示也为字符估算。
  本次再核对官方 `tiktoken/model.py`，包含 gpt-5.x、gpt-4.5-*、chatgpt-4o-*、
  fine-tuned 名称；未增加不在官方表中的推断。
  [OpenAI 官方模型表](https://github.com/openai/tiktoken/blob/main/tiktoken/model.py)
- 收集 text/reasoning/tool input、已执行 tool output（空 output 不计）、document filename、
  mini-app title/description，单次 native batch。角色长度、多媒体 4500 weighted-chars、
  document 80、mini-app 120 的固定开销保留；每组仍用消息数 × 4 的下限。
- compact 使用现有 `validCompletedCompacts`、`selectCompactsForInjection` 和
  `compactInjectionText`：覆盖源消息被替换，summary/recent 两组分别计算下限后相加。
- ChatPage 的 rows/contextCompacts `@Watch` 与四个已有模型刷新点接线。同步 getter
  保持只读；首帧、未知模型及 native failure 返回既有 `estimateConversationInputTokens`。
  native 成功写 `@State nativeContextTokens`，沿原 Context 指示器/用量卡刷新。
- messageNodes/compacts/model/conversation ID 身份缓存保留历史分页结果；streamingTail
  未接 watch，逐 chunk 不启动计数。序列与会话/节点/模型守卫拒绝迟到结果，离页失效序列。
  getter 也要求结果来源仍一致，避免 full conversation 恢复早于 rows/model 刷新期间显示旧数值。
- 发送、裁剪、压缩的同步预算与 provider `lastTurnUsage` 均未改变。显示仍为上下文估算，
  BPE 文本更精确；没有把媒体/协议开销标作精确账单。

## 验证

- 生产 helper 新 tests **10/10 PASS**：两种官方模型族、未知模型保留估算、空 batch、
  全 part 足迹、未执行 tool、compact 替换与独立下限、无效摘要、异步输入快照、native rejection。
- `harmony/chat` 全 `tsc --noEmit` PASS。初次测试 fixture 的 canonical message 字段缺失
  由 tsc 查出并按真实 schema 修正，没有宽松断言绕过类型。
- 三个生产改动文件实际 `LINT_CHUNK=2 harmony/scripts/lint-arkts.sh ...` exit 0，
  **0 error / 37 warning**；其中 ChatPage 4 个现有性能 warning，纯 domain 33 个
  imported canonical types 在 codelinter 独立 TS 解析中的 no-unsafe warning。
  类型由上述真实 tsc 确认。日志 `/tmp/e1-native-consumer-lint.log`。
- `git diff --check` PASS。

HAP 编译、真实 NAPI 加载、支持模型 Context 数字及换模型/分支/compact/离页 UI 回路，
由主 agent 统一执行并补充设备证据；本报告不把纯 helper 验证作为 E1 phase 完成。

## 独立补审 P2：模型读取时序

补审发现并确认：aboutToAppear 在 conversation=null 时发起全局模型读取，历史会话
full-load 随后发起助手绑定模型读取。旧全局请求若后返回，原 alive/visible 守卫仍会接受，
从而覆盖正确模型；原 refreshEffectiveModelState 的 assistantId 守卫也不足以拒绝同助手的旧读取。

- aboutToAppear、onPageShow、full-load/恢复已有出口、selectModelFromMenu 都调用统一
  `refreshEffectiveModelState`；其一次 choice 同时更新 modelLabel、Context model/window 与 reasoning。
- 该入口使用共用 `contextChoiceSeq`，发起时捕获 conversation ID 与 assistant ID，
  success/error 返回均核验顺序与身份；aboutToDisappear 失效该序列，旧请求不会在重入后复活。
- 去掉原位独立 describeEffectiveModel 异步回写，旧 label 请求不再另行覆盖新 choice。
  原 helper 标记未使用并保留，遵守仓库不随手删除死代码的规则。
- 没有引入重试、备用 resolver 或页面源码提取测试。页面异步接线由独立 reviewer 核对；
  生产 helper 仍为上述 10 项真实测试，不以比较源码字符串替代运行结果。
- 补审修复后 chat 全量 tsc 与 diff check 再次通过；单 ChatPage 官方 lint exit 0，
  **0 error / 4 warning**（原性能 warning），日志 `/tmp/e1-native-consumer-model-lint.log`。

## 设备截图 C：Context 卡对比度

独立 reviewer 与本 worker 均查看真实 `/tmp/e1-context-native.jpeg`：native 1430
已呈现为原 UI 的 `1.4K`，但暗色 Context 数值与统计标签的 INK4 在 SURFACE 卡面偏暗。
只把 contextUsagePanel 的 Context 数值（11.5）与 metaStat 标签（10.5）改为 INK2；
字号、布局、边距、数据源和全局 token 均不变。diff check PASS，主 agent 统一构建并补亮暗截图复验。
