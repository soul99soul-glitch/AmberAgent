# DeepRead Standards 独立再复核

本报告为只读复核；没有改生产、测试、配置或设备。HEAD 为 `b2d054bd7a81c64795497098d54cf30ad049e04f`，对比上一安装源码 `/tmp/amber-deepread-motion-baseline-20261005` 与当前 `harmony/{entry,chat,deepread}/src/main`。本轮新增/修改 28 个 ETS 文件，按排序路径 + NUL + 原始字节累计 SHA-256 为 `1f1f8e18acc1943d0d07cee1b42ebcee5263914cbe1b14e299065d9130f15a58`。没有把全部 408 项工作树变更归责本轮。

规范依据：用户本回合 AGENTS 行为规则、仓库 AGENTS.md、`.agents/skills/harmony-arkts/SKILL.md`、`.editorconfig`，辅以 Fowler smells 作为 heuristic。跳过已有 lint 强制项；未以近似动画参数差异替代 Spec 审查。

## 确认缺陷

### P2 — 发现设置即时保存后立即返回，发现页可能使用旧偏好

触发：作者在发现设置修改关注词、关注模式或热点来源后，在当前写队列完成前立即返回发现页；快速连续编辑、磁盘 flush 较慢时更易触发。

调用链：`DeepReadDiscoverySettingsPage.ets:103` 的 `persistPreference` 将每次写入串到页面私有 `writeQueue`（行 111–128）。离页（行 50–53）允许队列继续，但没有等待、发布完成信号或促使发现页重读。`BoardPage.ets:240` 的 `onPageShow` 立即调用 `loadHotlist`；行 505–527 一次读取并应用持久偏好。写队列稍后完成时发现页仍保持旧投影。

影响：界面声明“修改即时保存，返回发现页后应用”，但首次返回可能没有应用刚修改的关注/来源设置；磁盘最终正确，发现列表需再次进出或刷新才更新。这属于并发交界缺口，不是保存顺序逆转，也不会归责宿主原显式保存路径。

最小复现：[standards-return-race-repro.cjs](./standards-return-race-repro.cjs)。从仓库根运行 `node docs/reviews/2026-10-05-deepread-ios-replica/subagent-review/standards-return-race-repro.cjs`；脚本抽取当前生产的 `persistPreference`、`aboutToDisappear`、`loadHotlist` 方法，用 deferred Storage.set 表达尚未完成的真实异步持久化。输出：

```json
{"firstReturnApplied":["旧关注"],"afterQueuePersisted":"新关注","boardStillApplied":["旧关注"],"pendingWrites":0}
```

现有测试“independent source edits persist in event order and continue after immediate Back”只证明队列最后落盘，未覆盖首次返回读取偏好的同步边界。建议最小修复在离开前等待当前写入完成并覆盖系统返回/标题返回，或让发现页在这些有限持久写完成后重新投影。不要为此引入通用事务/事件框架。

## 已核实的闭环与隔离

- session 草稿由 standalone root 持有，AppStorage 显式 typed snapshot 不持久化、不缓存 API 凭据；主题子树 remount 后仍由父页 @Link 保留。root 替换恢复标量草稿，同时按稳定 ID 重读最新服务目录、凭据与模型可用性；保存成功清理，DeepRead ability 新 session 清理，正文仍消费持久字体设置。
- 发现 sensor 的订阅/取消使用同一 callback，当前页可见、前台、无 sheet 才订阅；销毁只移除自身观察。散报/骰子/横幅有有限 timer、expiry cycle 与失活清理，主题重挂不重复创建 sensor owner。
- API 23 reduce-animation 入口同时检查 sdkApiVersion 和 capability；API 12 路径不调用新 API。EntryAbility 持有观察者，前台 refresh、销毁 off，销毁后的已排队回调不再写 AppStorage。
- haptics 只作用独立 DeepRead、前台与支持硬件；异常不阻断业务，不以 reduceMotion 禁掉独立触感。宿主发现设置标量入口有 getProductKind 门控，保留原显式保存；prepare 的加速度权限限定 DeepRead。
- 编辑室 rail/stage 来自真实进度；活字/格言/扩散环只驱动展示。timer identity 和 motion owner 防止旧编辑室任务重入；完成章 trigger 保持真实持久完成链路，降低动效、后台、隐藏与销毁清理有限动画。
- 模板选择与 icon feedback 有 lifecycle/motion token；未保存阅读字号只沿 typed 预览参数传递，晚到 KV 不覆盖该参数；创建 upsert 成功后才设置 pending ID，重试打开复用已持久 ID，不把视觉反馈当业务成功。

## 验证与边界

独立执行 `deepread_motion_preferences`、`deepread_library_settings_motion`、`deepread_settings_session_draft`、`deepread_ios_settings_replica` 四份现有测试：40/40 通过。这并未覆盖上述首次返回 race，故通过测试不能关闭该发现。没有重复全仓构建，也没有操作模拟器；可见几何/动效仍由专门 UI reviewer 和主 agent 的设备证据判定。

没有发现额外硬规范违例，也没有足以要求重构的 Fowler smell。SettingDeepReadPage 当前 1431 行、Board 1012 行、Article 994 行；保留软上限之内的窄改动，不建议为此次 review 大规模拆宿主遗留配置。

额外验证请求（不作为确认缺陷）：强调色 chooseAccent 在异步持久化后使用表单 UIContext，而成功分支没有 alive 检查；原实现直接更新全局令牌。如果作者点强调色马上切 Tab，需设备验证已销毁 UIContext 是否仍能成功同步 AppStorage/主题。这项只表示尚未验证的交界，不将假设的运行时异常计入缺陷数。


## 窄增量：模板预览会话边界的条件风险（设备未复现，不计确认缺陷）

最初 `view_image` 呈现让 `device/15-existing-manual-preview.jpeg` 看似只有 header，主 agent 随后查证发现旧 14/15 和 16/17 各自实际文件 SHA 完全一致，渲染工具的呈现存在矛盾，不能把呈现差异当设备症状。新的 30/31 首开 +3/+11 秒、32/33 关闭后同主题第二开 +3/+11 秒均用唯一 remote snapshot 路径；四份图片 SHA 都为 `b1b2e190bae86f0808086219d29198a164a9b2837bf0b23001fc012496421e5e`，四份 dump 均含“示例文章标题”。因此此次设备检查未复现二次预览空白，此项只保留条件风险，不计 confirmed P2。此 reviewer 未操作设备，也未宣称 native sheet 内部一定采用某种缓存策略。

生产状态链：`DeepReadTemplateWorkbench.ets:438` 每次关闭后 `openPreview` 都增加普通 private `previewId`，清除 `webAttached/webInitialized`。`bindSheet`（行 583）按值传 `PreviewSheet(this.previewId)`，Web 子树的 ForEach key（行 855）只含 theme epoch。attach（行 414）与 pageEnd（行 420）严格校验 captured id；缺新 attach 或 captured id 陈旧时，不进入 loadData。onDisappear（行 585）清掉 web ready 状态，因此仅保留旧 Web 时也不会重新调用 loadData。

独立实际方法 probe：[standards-preview-session-probe.cjs](./standards-preview-session-probe.cjs)，从仓库根运行。结果：首开 id=1，loadData 1 次；第二次 id=2，输入旧 id=1 的 attach/pageEnd 时保持 previewOpen=true、webAttached=false、webInitialized=false，loadData 仍 1 次；直接 loadPreview 同样被 ready guard 拒绝；输入新 id=2 的 attach/pageEnd 后 loadData 变成 2 次。此 probe 只证明假定旧回调/缺 attach 时的条件因果关系，**不证明正常设备会发生这些事件，也不证明 native 的重建事件顺序**。实测首开及同主题重开均有正文，故不能据此要求作为 bug 修复。

本地官方文档依据：通过 `harmony/scripts/devecocli.sh docs read .../Builder装饰器_自定义构建函数/arkts-builder` 查询，primitive 参数默认按值传，参数变化不使 Builder 内部 UI 刷新，私有 Builder 推荐直接 this 读取状态。bindSheet 官方生命周期则 onDisappear 在退出动画结束后。当前预览会话 ID 没有作为状态在实际 Web identity 中读取，不能用正确的旧 session guard 代替新会话 attach。

仅在后续确证 session identity 不更新或缺 attach 事件时的最小方向：把 previewId 作为 @State，在无参 PreviewSheet 内直接读取它，用 session 与 themeEpoch 共同构造 Web 重挂载 key，并保留 guard 隔离旧回调。当前设备没有复现，不要求基于此假设修改生产。未来若实施，需首开、关闭、同主题再次打开、直接预览、主题切换及旧 id 回调不误加载验证；无需新 Web 池或延时重试。


降级结论：Standards 当前仍只有 **1 个 confirmed P2（发现设置写入与首次返回读取的 race）**。模板预览为设备未复现的 conditional probe；重复工具呈现不得计为用户可见缺陷。
