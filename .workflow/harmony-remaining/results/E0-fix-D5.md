# E0 修复：D5

范围：`E0-review-D5-D6.md` 问题 1、4、5、6、7。未修改 ChatPage、D6 网络工具、运行宿主架构；未执行全仓构建、签名、设备安装或提交。其他 worker 的编辑保留。

## 修复结果

1. **Jev 候选绑定**：把实际 noul batch 构造与概率回绑移到 `chat/jev_client.ts` 的小函数，JevSupport 调用同一实现。每道 `cN` instructions 明确引用 `state.candidates["原始候选键"]`，候选键用 JSON 字符串编码，概率仍回写原始 ID。保持一次请求及现有 off / 失败回退。SHADOW 的 context_selection / memory_recall 调用点分别在 ChatPage 的 `!batch.shadow` / `batch.shadow` 门后消费，原序路径保持。
2. **实际动态席位与容量**：`resolveCouncilDynamicSeats` 复用 `buildDefaultSeats` 的核心注入、role 去重、defaultSeats、extraLens 和 maxSeats 规则，仅返回实际纳入的动态 seatId 及剩余容量。CouncilPage 在读取设置、切换视角、生成完成与开始运行前使用该结果；满容量时不请求 AI，不展示未纳入动态席。页面显示剩余容量，生成数量最多为剩余容量（上限 3）。发生归约时提示用户；开始前若席位发生归约先展示调整结果，下一次点击再发起。
3. **生成生命周期**：CouncilPage 复用 EntryAbortController，页面退出 abort 并递增独立生成 token。设置读取及生成返回后检查页面/token，catch / finally 仅提交当前调用的状态，退出清理不依赖网络任务结束。生成中开始按钮禁用，startCouncil 同步门也拒绝；异步 prepare 返回后检查原页面加载 token，避免从已离开页面发起运行。此项为代码链路审查，未以页面类再造测试。
4. **真实子代理终态**：活动条从保留的 runId 读取 manager.snapshot，在四秒窗口展示真实 completed / failed / cancelled / timed_out / interrupted / approval_required。生产 pure presentation 区分文案、成功/错误/提示色语义及 check/close；混合结果逐类列出数量。新 active run 将结束计时归零，四秒窗口与来源会话跳转沿用原逻辑。
5. **模型行点击高度**：SettingSubAgentPage 模型单选行 minHeight 36 改为 52，保持单行省略和 check。
6. **补审发现的既有 enabled 门遗漏**：动态席位生成读取设置后复用 startCouncil 的 enabled 条件与拒绝文案；关闭议会时不调用模型，按钮禁用并展示关闭原因。没有新增失败回退或运行架构。
7. **C 暗色截图补修**：review_d5_d6 独立审看 `/tmp/e0-subagent-model-picker-dark.jpeg`，发现默认 Explorer 提示词 placeholder 黑字在暗色背景不可读。仅在该 TextArea 增加现有 `.placeholderColor(INK3)`，未改其他输入控件；修后截图由主任务统一复核。

## 回归与静态验证

- 初始诊断复现了题目缺少候选引用、满容量仍调用/显示动态席、生成中仍可开始、活动条保留 running 快照等失败。正式回归只测试实际生产 pure 函数；未保留 ETS 页面转译/替身机制。
- 新增 `jev_noul_batch.test.ts`：相反候选各题唯一定位、原 ID 概率回绑、键转义、查询截断和原 keys 顺序。
- 新增 `subagent_activity.test.ts`：完成/失败/取消文案及颜色语义、混合数量、超时/中断/待审批。
- 新增 council validator 回归：容量只容纳一个动态席时仅展示该席、可选视角占满后动态席归零、核心/default/lens/dynamic 重复 role 不会被重复纳入；与 parseTask 输出比较有效 seatId。
- chat 上述新增测试 + subagent_models：**11 / 11 通过**。
- deepread council_validator + council_manager：**32 / 32 通过**。
- 限定三个测试文件及其真实依赖图的 `tsc --noEmit`：**通过**（临时配置继承 chat tsconfig，沿用仓库路径解析）。
- 七个生产改动文件 codelinter：**exit 0，0 error / 160 warn**。警告为 domain TS 在 codelinter 中的导入推断等诊断；限定依赖图的 TypeScript 严格检查通过。工具初次写 Huawei 日志受到沙箱限制，按原命令授权重试完成。
- CouncilPage 最后按钮状态调整后的单文件 codelinter：**exit 0，No defects**。
- SettingSubAgentPage C 截图占位字色修复后的单文件 codelinter：**exit 0，No defects**。
- `git diff --check`：**通过**。

## A / B / C 边界

- **A / B**：指定问题的生产逻辑与接线已修，并由相关 pure 回归支持；页面取消/过期提交、新 active 重置四秒计时的行为经当前代码检查，设备运行证据待主任务统一补齐。
- **C**：本轮只完成静态修复。模型长名、窄屏、深色、席位容量文案/芯片布局、活动条失败/取消视觉与四秒收起仍需统一构建安装截图；本文件不将其标成视觉已验。
