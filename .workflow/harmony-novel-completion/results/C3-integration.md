# C3 Integration

## Accepted

- 新增 `amber.novel.workspace` v1 manifest/path contract、ASCII 三位章节文件名和严格路径校验。
- `FileStore` 增加二进制读写、目录 rename/delete/isDirectory；Entry 使用真实文件系统实现。
- 生产 repository 迁为 visible tree + `.amber` private state，使用 staging/live/backup 原子目录切换，
  并在启动/加载时恢复中断安装。
- 旧 `projects/*.novel.json` 双读一次，工作区完整落盘后才删除旧源；后续只写工作区。
- 复用 Chat ZIP central-directory/read/write/raw-deflate 链，工作区 ZIP 导入导出具备条目数、
  单项/累计压缩与展开限额、重复/冲突/Zip Slip/私有路径拒绝。
- 当前分支可导出 TXT、Markdown、EPUB；EPUB 包含 container/package/nav/章节 XHTML，
  首项 `mimetype` 使用 shared ZIP writer 的 stored 模式。
- 项目页提供 ZIP 导入与软删恢复；工作区顶部提供 ZIP/TXT/Markdown/EPUB 系统保存器。

## Review Fixes

- 按 API 12 官方 FAQ 修复 zlib：`deflate/inflate` 后通过 `getZStream()` 读取最新计数，
  输出字节仍从调用方持有的 buffer 读取；消除设备上 0-byte deflate 条目。
- 导入和所有后续业务更新逐字节保留公开 `project.md`；缺少该文件的 v1 ZIP 明确拒绝。
- `deleteProject` 与 update 进入同一 project lock，消除安装中删除后被 staging 复活的竞态。
- `DocumentViewPicker` 传入官方推荐的 `UIAbilityContext`，导入按 `.zip` 过滤。
- 项目页补常态错误反馈与“正在恢复…”忙碌态；导出失败三页签均有 toast。
- 顶部“导出”固定 44×44vp；review 复核未发现其他错位、截断、间距或字号问题。

## Runtime / Device Evidence

- 原项目「修仙日常」在迁移后保持 1 章、19 对话。
- `/tmp/c3-export-fixed.epub` 来自真机公共目录，4,954 B；标准 `unzip -t` 五项全部 OK，
  `mimetype` 20 B stored，四个内容项均为非零 deflate。
- `/tmp/c3-workspace.zip` 来自真机系统保存器，3,803 B；仅包含 `manifest.yaml`、
  `project.md` 和当前分支章节，不含 `.amber`，标准 `unzip -t` 全 OK。
- 同一 ZIP 经系统文件选择器重新导入后，项目页出现第二个独立「修仙日常」：1 章、0 私有对话；
  原项目仍为 1 章、19 对话，证明公开交换树与私有状态边界有效。
- `/tmp/c3-final-projects-stable.jpeg` 与 `/tmp/c3-after-project-open.jpeg` 为最终签名包稳定截图。

## Verification Evidence

- DeepRead：682 passed，0 failed。
- Chat：2022 passed，0 failed。
- ArkTS lint：0 defects。
- AGC build：`BUILD SUCCESSFUL`。
- Signed HAP：install success；EntryAbility start success；工作区进程存活且稳定显示。
- Logic review + fix review：PASS，无阻断。
- UI review + fix review：PASS，无阻断。

## Rejected

- 未新建第二套 ZIP writer、EPUB 压缩器或文件 picker 抽象。
- 未把 `.amber` 私有 ledger/transcript 混入公开交换包。
- 未用复制文本到剪贴板冒充文件导出。
- 未用额外 fallback 掩盖非法工作区、压缩炸弹或路径冲突。

## Commit

本文件与 C3 实现同一提交；SHA 以该提交 Git 记录为准。
