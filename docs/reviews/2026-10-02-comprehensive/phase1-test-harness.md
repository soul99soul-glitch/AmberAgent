# Phase 1：测试 host 与当前 production 接口同步

范围仅为三个 CJS 测试文件；没有改 production 源码，也没有删除或跳过测试。

## Red

2026-10-02，在 `harmony/chat` 执行：

```sh
npx tsx --test ../tests/chat-overlay-lifecycle.test.cjs ../tests/novel-editor-lifecycle.test.cjs src/test/memory_topic_store.test.cjs
```

初次复现 26 tests / 17 pass / 9 fail，与全量基线中的 9 个失败一致。

- Chat SubAgent 的 hide/disappear 用例：production 生命周期新增了滚动导航、键盘归还、回顾关闭和帧率更新，fixture 尚未提供相应方法/SDK host。
- Novel 编辑/键盘用例：fixture 缺 `getNovelComposerDraftStore`、`TextInputController`、`TextAreaController`。补齐后又发现 `openNewChapter()` 使用的真实 `nextNovelChapterOrdinal` 未注入；这个错误被 production 的 catch 捕获，导致编辑器未打开。
- Memory topic 用例：production 已从模型 ID 链转为 `TaskModelReference` 链，但 fixture AppContainer 仍只实现旧接口。首次聚合因此返回 `failed`；属于测试 host 问题。

## 最小改动

- Chat：扩展精确 production 方法提取名单，直接运行 `hideScrollNavigation`、`restoreKeyboardAvoidance`、`syncStreamingFrameRate`、`closeRecap`；只补计时器函数、SDK 帧率 host 和必要初始字段。
- Novel：使用真实 `NovelComposerDraftStore` 配合内存 storage；仅 SDK 输入 controller 使用 `stopEditing()` host；注入真实 `models.nextNovelChapterOrdinal`。
- Memory topic：提供当前 `readTaskModelReference`、`currentChatModelReference`、`resolveTaskModelReferenceChain` 接口；使用真实 reference 编解码辅助函数记录原始模型链顺序，保留已有顺序、持久化、取消、超时和写入断言，增加压缩 slot key 断言。

## 二次复核

仅补 fixture 后 26 tests / 25 pass / 1 fail，日志 `/tmp/amber-review-phase1-harness.log`。

剩余 reader 键盘用例的固定 setter 次数预期与当前设计不一致：`NovelChapterReaderPage.enableKeyboardAvoidance()` 首次捕获 previous mode，但每次 `onPageShow` 都写入 RESIZE，以保证共享窗口显示时的模式。重复 show 的实际写入为 `[RESIZE, RESIZE, NONE]`；原测试预期 `[RESIZE, NONE]`。状态归还和隐藏后销毁的幂等清理均正确，属于过时预期，而不是产品错误。主 agent 已复核并确认可以同步预期；没有改变 production。

## Green

同步 reader 的 host 写次数预期，并增加专门用例：同一可见生命周期内共享窗口被写为 OFFSET 后，重复 show 重新写 RESIZE，hide 仍归还首次捕获的 NONE；重复 hide 不重复归还，隐藏后销毁不覆盖另一页面的模式。

相同定向命令最终 **27 tests / 27 pass / 0 fail**，退出码 0，日志 `/tmp/amber-review-phase1-harness-green.log`。全部原行为断言保留，只有已核实的 setter 次数预期同步。

这些 host 测试验证状态与真实逻辑调用；不能证明 ArkUI 键盘尺寸、布局或实际设备手感。
