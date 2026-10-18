# 第二轮修复 Git 交付

本次交付只包含第二轮 B01–B25 的生产修复、回归测试和审查摘要；不会把接手前的 26 项未提交修复整体带入提交。原始审查报告里的 4928 项回归、66 个输入哈希和签名 HAP 对应当时包含既有 WIP 的本地整合快照，不冒充本次隔离交付树的验证。

## 提交隔离

- 起点：`e6d6c26b6bf151ee3c58cbaa0b4e2acf61deac55`，分支 `harmony-preview`。
- 在独立工作树三方比较 HEAD、接手快照与本轮最终文件，只移植本轮增量。原工作区 126 个已修改或未跟踪文件已保存 SHA-256 供交付后复核，原索引保持不变。
- 排除 `search_tools.test.ts` 中只为旧 WIP 测试补充类型的改动，和此前已存在但未跟踪的 `file_store_lock.test.cjs`；这两处不属于可独立发布的本轮新增测试。
- 新召回测试夹具增加 `conversation` 字段，匹配干净 HEAD 的既有运行上下文；不改变生产逻辑。共享 Responses 测试仅移植两条本轮新回归。
- `harmony/build-profile.json5`、先前报告、pelican 文件、签名材料、依赖目录和构建 HAP 不进入提交。
- 将必要的审查摘要与五张截图收录至 [证据目录](2026-10-18-review-round2-evidence/README.md)，原始完整日志和签名包仍留本机。

## 远端整合

远端 `origin/harmony-preview` 为 `104f07f8c`，与本地共有基点 `3bf267a1f`。本轮修复先独立提交为 `37a9da867`，再正常合并远端；保留本地 12 个已有提交和远端 1 个界面揭示提交。

两处 Smart UI 冲突保留本地 controller、状态、nodeId 事件、80 组件及主题重挂载，并接入远端的一次性测量覆盖层。独立 subagent 对照合并双方复核通过；修正了远端新增 Node 测试中 TypeScript 依赖的引用路径，使其与同目录既有测试一致。详见 [冲突处理](2026-10-18-review-round2-evidence/results/merge-reveal-resolution.md) 与 [独立复核](2026-10-18-review-round2-evidence/results/merge-independent-review.md)。

## 验证

最终交付树的验证结果：

- Chat、DeepRead 类型检查通过；远端合并没有改动这两个 domain 的生产 TypeScript。
- Harmony 完整回归 **4882/4882 通过，167 suites，0 fail/skip**。完整重跑显式配置了既有 Chat 依赖路径；随后清除该环境变量，新增揭示测试按修正后的引用路径单独执行，4/4 通过。
- 39 个相关 Harmony 生产文件 lint：**0 error、2952 warn**；ArkTS 编译通过。
- Debug `assembleApp` 与本地签名通过。新工作树的 source-dependent Mosh CMake 缓存已重建；打包使用 DevEco JBR 和原工作区已有的有效本机签名配置，完成后恢复隔离树受版本控制的配置。另用仓库离线签名脚本验证，输出 `Verify success`。签名材料和 HAP 均只保留本机。
- 在 API 24 模拟器上以 `install -r` 安装最终 Debug HAP、启动并回放已有 Smart UI fixture；完成后原生控件可见，点击“服务器证书”能更新选中状态和说明。截图：[初始状态](2026-10-18-review-round2-evidence/merged-smart-ui-interaction.jpeg)、[选择后的稳定状态](2026-10-18-review-round2-evidence/merged-smart-ui-selected-final.jpeg)。该 fixture 未开启持久化。验证结束后以 `install -r` 恢复本轮此前保存的原工作区 Debug 包并重新启动，未卸载或清除应用数据。
- 浏览器诊断完整执行 8/8 场景并 exit 0。原样复跑完成 8 个场景断言与截图，但卡在最终 `browser.close()`，停止后 exit 130；不将这次原样命令记作通过。首跑的一次 `started()` 超时未再复现，尚无精确根因或产品缺陷证据；没有为此改动产品或添加重试。详见 [浏览器诊断](2026-10-18-review-round2-evidence/results/browser-integration-review.md)。
- `git diff --check` 与暂存区检查通过；原工作区 126 个已修改/未跟踪文件哈希、索引及 Git 状态与交付前快照一致。

以上是隔离交付树的源码、回归、构建、签名和受控模拟器检查。原始报告的 4928 项回归对应含旧 WIP 的历史整合快照；本次没有新增物理设备、API 12、真实供应商或外部服务验收。
