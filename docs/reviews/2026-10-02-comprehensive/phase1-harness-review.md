# Phase1 测试 harness 独立复审

结论：通过。没有发现需要阻止下一阶段的问题。

只读对照 `/tmp/amber-harmony-review-20261002-baseline` 下三个指定测试文件与当前工作树；本复审仅写报告，没有改 production 或其他 agent 文件。尚在修改中的 novel/database 产品修复不属于本次审查范围。

## 变更核查

- `harmony/tests/chat-overlay-lifecycle.test.cjs`：新增提取四个真实 production 方法 `hideScrollNavigation`、`restoreKeyboardAvoidance`、`syncStreamingFrameRate`、`closeRecap`；仅补充它们需要的定时器、状态字段和原生宿主依赖。原有方法提取机制保留原方法体，不将待测逻辑改写为测试实现。13个显式 test 声明、36个 assert 调用全部保留。
- `harmony/tests/novel-editor-lifecycle.test.cjs`：加载真实 `NovelComposerDraftStore` 和真实 `nextNovelChapterOrdinal`；用内存 KV 满足 store 依赖，为文本控制器补 `stopEditing` 宿主接口。保持生产页面方法 body，仅去装饰器并将 struct 转换成测试可执行 class。显式 test 声明5→6，assert31→35。没有删除测试、添加 skip/todo/only 或改掉已有验证行为。
- `harmony/chat/src/test/memory_topic_store.test.cjs`：把过时的 model-id fixture 接口替换为生产 `MemoryDreamOps.ets:26-27,48-50` 实际使用的 task reference 接口；用域层真实 `taskModelReferenceFromId/taskModelReferenceId` 编解码记录。原有候选链 `daydream-choice → compress-choice → chat-choice` 的断言完整保留，并增加 compress key 正确性断言。显式 test1→1，assert43→44。

## 重复 onPageShow 的预期是否被弱化

不是。对比基线与当前，阅读器 `enableKeyboardAvoidance` 和 `restoreKeyboardAvoidance` 两个 production 方法内容完全一致：只有第一次 visible lifetime 读取并保存原模式，但每次 `onPageShow` 都执行 `setKeyboardAvoidMode(RESIZE)`。因此原 harness 期待重复 show 不产生第二次 RESIZE 写入，确实与当前既有设计不符。

预期改成阅读器 `[RESIZE, RESIZE, NONE]`，工作区仍为 `[RESIZE, NONE]`；恢复原模式、隐藏页面不能覆盖他页、直接销毁恢复原模式等断言仍全部存在。新增测试还故意在同一 visible lifetime 把宿主切到 OFFSET，确认重复 show 重申 RESIZE，同时最终 hide 恢复首次保存的 NONE；再次 hide 不重复恢复，隐藏后销毁不覆盖他页设置。

## 实际验证

在 `harmony/chat` 执行：

```bash
npx tsx --test ../tests/chat-overlay-lifecycle.test.cjs ../tests/novel-editor-lifecycle.test.cjs src/test/memory_topic_store.test.cjs
```

结果：27 tests，27 pass，0 fail，0 skipped，0 todo；退出码0。

验证界限：测试证明真实抽取方法的状态/生命周期行为和实际存储/协调逻辑；不证明 ArkUI 实际布局、键盘几何或设备性能。该界限没有被此次 harness 修复改变。
