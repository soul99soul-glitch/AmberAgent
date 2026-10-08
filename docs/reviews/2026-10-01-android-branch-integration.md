# Android 分支整合核验（2026-10-01）

用户指出小说项目列表顶部割裂、空项目有两个新建入口，并要求完整合并此前修复所在的分支。

## 原因与合并范围

- 原构建分支是本地 `main`，HEAD `ff1b70f3dbfccc5073206cf2d046fd6e52f69061`。
- 两处既有 UI 修复在 `da67ca3d888c9a9dcac13f4ce04b1801a53d9279`，属于 `fix/streaming-render` 的历史；原本地 `main` 不包含它。
- 本次用 `git ls-remote origin refs/heads/main` 核对服务端：`e4d4b611158ceca108666f8ff458c811874f4649`，与该分支及本地缓存远端一致。
- 完整纳入 `554b4c6`（小说性能）、`da67ca3`（主题、会话回顾、聊天渲染）、`e4d4b61`（构建 CI），共 94 个文件。
- 合并提交 `6dfb01aeb72037b9fe5545af38c4f043e17a2748` 的父提交分别为原本地 main 与该分支 HEAD；两端均已核验为当前 main 的祖先。

原工作区已有大量未提交开发，不能直接用分支文件覆盖。备份在 `/tmp/amber-android-full-branch-integration-20261001/backup`，包含 92 个已修改文件、62 个未跟踪文件、完整二进制 WIP patch、原 index 和 SHA256 清单。隔离工作树合并提交后，对当前开发内容逐文件做三方融合，再将经过核验的结果落回 main；原未提交开发保持未提交。

## 重叠内容的处理

14 个文件重叠，7 个需要文本冲突处理。78 个不重叠已修改文件及 62 个未跟踪文件逐字保留；三个 worker 按职责处理冲突，没有改写原工作区。

- 小说界面：保留当前 36/40 dp 可见高度、48 dp 触控和静止透明度反馈，同时纳入分支连续圆角；透明顶栏贯通原画布，空项目只显示中央新建入口，有项目才显示悬浮新建。
- 小说状态：保留当前章节历史、时间线、恢复 epoch 和 CAS，融合文件快照缓存与导航 generation 校验。缓存命中仍按当前分支祖先计算草稿创建时间；快照与轮询复用已持有账本。
- 通知与生成：保留当前通知动作、锁屏公开版本、失败分类及 Jev 门禁，纳入惰性 PendingIntent 创建、会话回顾及生成加载优化。
- 三套资源：追加的 Novel 与主题/回顾资源名称无交集，全部保留；XML 解析和资源名唯一性检查通过。

## 验证证据

整合前，旧主包在隔离 Android 模拟器的真实项目列表复现两项问题：新建动作数为 2，顶部与正文背景最大 RGB 差值为 33。复现脚本 `/tmp/amber-novel-projects-fix/check-ui.py` 对这两项实际 UI 断言失败。

整合差异 `/tmp/amber-android-full-branch-integration-20261001/integration.diff` 比较合并前后含 WIP 的实际工作区；`manifest.json`、`overlay-result.json`、`integration-result.json` 保存来源、逐字保留和候选与原工作区相等核验。

独立 Standards 与 Spec/调用链 review 均为 0 项可定位新增问题；Spec reviewer 另核验 140 个文件的 SHA256、分支资源值与缓存/恢复链路，并检查本轮实际项目列表、工作区、设置和 Reader 截图，未发现新增错位、尺寸或间距缺陷。

- 统一回归：`feature:novel-workspace:test` 与 app 定点 Novel、主题、流式/时间线、会话回顾及通知测试完成，532 项通过，0 失败/错误。1 项 `NovelWorkspaceExchangeRunnerTest` 因未配置外部 `AMBER_W15_ROOT` 夹具而跳过；没有将其算作通过。冻结 XML 和统计在备份目录的 `verified-results/`、`regression-summary.json`、`regression-skips.json`。
- `:app:assembleGraphite` 在 4 分 29 秒完成，Rust native 验证通过；`build-graphite.log` 保存构建证据。
- 本轮真实项目列表检查：空态 1 个中央新建动作，非空列表 1 个悬浮新建动作，二者背景差值均为 0。新建项目通过真实 UI 进入工作区；正文、设定、设置、阅读器首章/下一章/上一章/返回目录均已执行并保存截图/XML。阅读器状态断言通过：全屏、标题正文同步、不同章回开头、边界操作禁用。项目和正文夹具仅在隔离模拟器使用合成项目。
- 本轮动作录制为 `ui/branch-merge-motion.mp4`，媒体实际时长 22.6425 秒。AVFoundation 抽帧、变化区间及实际画面在 `ui/motion-frames/`、`ui/motion-groups.json`；不能以静态截图或模拟器录制替代真机流畅度/触感验收。
- Spec reviewer 实际看过进章及上/下一章中间帧：标题正文同步、过渡末态无残影/缩放/错位；上一章帧可见从左回到原位。下一章采样不覆盖每一中间轨迹，方向源码保留。录制结尾仍在首章 Reader，返回目录仅由另存的截图与实际点击证明，本视频不作为返回目录过渡的动态证据。

## 最终主包交付

- 包名 `app.amber.agent`，版本 2.6.8 / 396；签名 SHA256 `30929ee5479b4ae117ee36c5c706cd6e1f9e536569ff7b00e09d44493d67f5ed`，与手机原主应用一致，16KB zipalign 校验通过。
- APK `/tmp/amber-android-full-branch-integration-20261001/Amber-2.6.8-main-merged.apk`，114244844 bytes，SHA256 `1196e99b76391a038221b417b2d814db2314405d32e5f0a26bde452997d5549f`。
- OPPO PMA110 / `3B164901CEF00000` 同包名覆盖安装成功；从手机回读的公开 APK 与构建产物 SHA256 一致。
- 原 UID 10406、首次安装时间 `2026-09-26 01:57:41`、数据目录均保持；更新时间 `2026-10-01 02:33:16`。没有卸载/清空主包，也没有重新安装独立测试包，手机上仅有主包。
- 启动 `Status: ok`，进程运行，crash buffer 未见新增崩溃。手机随后已解锁，经真实首页入口打开小说项目列表，并核验唯一新建入口与贯通顶部：动作数 1、顶部与内容 RGB 同为 `(238,231,213)`。截图/XML/JSON 在 `/tmp/amber-novel-projects-fix/phone-final-projects.*`。其余工作区和 Reader 界面/动作检查在隔离模拟器完成，不外推真机帧率或触感。
- 本地 main 已合并完整分支；原开发内容仍未提交，本轮没有推送。
