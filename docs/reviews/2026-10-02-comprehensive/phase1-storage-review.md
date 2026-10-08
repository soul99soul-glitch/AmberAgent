# Phase 1 Storage 独立复审

结论：当前已完成的 Storage 改动通过产品复审，没有发现阻断问题。审查者未参与实现，仅改本报告。

范围：与 `/tmp/amber-harmony-review-20261002-baseline` 精确比较 `ChatRdbRepository.ets`、`RdbRepository.ets`；检查新增 `harmony/chat/src/test/rdb_repository.test.cjs`。Novel 尚未完成部分未审。

## 核查结果

| 项目 | 结果与证据 |
|---|---|
| 消息搜索最新真实匹配 | `ChatRdbRepository.ets:509` 仅在解析正文并命中 needle 后更新 `latestByConv`。比真实旧行更新的 JSON discriminator/metadata 粗筛假阳性不再覆盖真实命中；结果集无序也按 nodeIndex 选最新正文匹配。 |
| 会话排序与 limit | 第二次查询保留 `orderByDesc('update_at')`；随后按查询顺序组装结果，达到 limit 停止。新增测试返回 recent → old，并验证 limit=1 只保留 recent。 |
| 空搜索与无效 limit | trim 后空串、limit <= 0 直接空结果，避免原先 limit=0 仍推入一条。fallback memory 路径也使用相同 trim term。 |
| 初始化失败 | 两个 adapter 的无人消费 `attempt.finally` 派生拒绝已改为 `attempt.then(clearPending, clearPending)`。处理器没有异步 work/throw；任一结算路径清 pending，不再附加 unhandled rejection。 |
| 清理所有权与重试 | `this.initPromise === attempt` 防迟到清理覆盖新 attempt。store 发布仍在 schema/migration 成功后；DeepRead 保留真实 reject，Chat 保留既有暂时 memory degraded 语义，再次 init 成功恢复 degraded=false。 |
| 修改范围 | 与本次 baseline 相比，仅最新正文精筛/查询边界和两个 pending 清理发生变化。git diff 中已有 `RdbRepository` inputSourceUrls 字段不是本 phase 新改，不混为本次行为变化。 |

## 测试

在 `harmony/chat` 执行：

```sh
node --import ./node_modules/tsx/dist/loader.mjs --test src/test/rdb_repository.test.cjs
```

**5/5 通过，exit 0，约 1.09 秒。** 测试运行实际 Entry adapter 经 TS transpile 的实现；SDK/query result 使用可控 host port。两个初始化 probe 以独立子进程检查 actual process-level unhandledRejection，失败后 retry 为第二次 attempt；不是静态源码字符串断言。

第一次从仓库根直接执行缺少 chat tsconfig 的 `@amber/deepread-domain` paths，测试 import 失败；校正 cwd 后全部通过。该次失败不是实现回归，不算产品 finding。

测试不证明真机 RDB engine 或磁盘权限恢复；当前改动是 Promise 清理和已有查询投影逻辑，没有引入新的平台 API。现存 LIKE 对 JSON 转义字符的粗筛限制未由本 phase 引入，代码已明确说明，未追加无依据兜底。
