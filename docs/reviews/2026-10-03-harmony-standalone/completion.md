# 鸿蒙小说、深读独立应用交付记录

2026-10-03 已交付两份可安装试用的独立 HAP，补齐本轮直接核对的主要能力差异。不能将本记录理解为全部 iOS 能力、真实云服务和系统后台已经全面验收。

## 产品与源码边界

| 产品 | Bundle | 启动页 | 签名 HAP |
| --- | --- | --- | --- |
| Amber 小说 | `app.amber.novel` | 小说项目列表 | [小说 HAP](../../../.harmony-standalone/novel/entry/build/default/outputs/default/signed/entry-default-signed.hap) |
| Amber 深读 | `app.amber.deepread.reader` | 深度阅读发现页 | [深读 HAP](../../../.harmony-standalone/deepread/entry/build/default/outputs/default/signed/entry-default-signed.hap) |

原宿主使用 `app.amber.deepread`，因此独立深读另取包名。两个新包各有自己的数据库、模型凭据、偏好及任务目标，不自动读取宿主私有目录。小说可通过既有公开工作区 ZIP/native backup 显式迁移作品。主应用没有在本轮重新安装；已有脏改和未跟踪文件保留，未提交、推送或创建 PR。

独立产品复用当前共享源码，通过 [生成/构建入口](../../../scripts/harmony-standalone/build.mjs) 创建 `.harmony-standalone/` 工程。每产品 1,373 个未覆写的清单源码/资源文件与当前 `harmony/` hash 相同；有意覆写 5 个产品身份与构建配置文件。共享页面、域模块及原生依赖仍保留，尚未做包体裁剪。生成器保留构建缓存和各产品离线证书，以支持同证书覆盖安装。

鸿蒙工作树基点：`harmony-preview` / `5ea51f7a548198ee4676fc63ef0b3bbfa34ba290`，开始时已有大量未提交改动。iOS 参考基点：`ede7cbd5b463240315256c4d6a2d492cccf58d70`，同样有未提交改动。iOS `DeepReadApp` 是实际独立实现；`NovelApp` 的当前入口仍是 `Text("Novel")`，小说能力对比使用它引用的共享 `NovelCreation` 实现。

## 本轮主要补齐

- 小说：独立专属设置；写作、审稿、剧情同步三职责默认模型实际接入运行路径；应用级润色要求与上下文开关；讨论中的可选联网研究；任意非连续章批量润色；章内查找及替换；五种真实仓库出口的系统分享入口；快速开始输入随首次创建保存为资料。
- 深读：主题/粘贴文本/多网页/多文件创建；TXT、MD、JSON、CSV、文本 PDF、DOCX 提取；来源、状态、错误和正文保存查看；完整阅读库搜索及状态筛选；一致的 70%–180% 字号；按搜索设置取结果数；部分稿文本/Markdown 导出；PDF 接入当前模板、字体、字号和配色。
- 独立运行：入口导航、专属设置、实际 bundle 的通知与后台定位；小说保留自己的 WorkAbility；独立应用跳过宿主专属 cron、网关同步、调试数据和其他无关启动任务。
- 验收修正：新滚动页面顶部对齐；深色模式输入框显式主题占位色。保留旧文章、取消/迟到运行不覆盖已保存稿件的测试覆盖。

详细映射及残余差异：[小说 80 项矩阵](novel-parity.md)、[深读对比](deepread-parity.md)。小说高级分支/检查点、多步撤回、资料继承与智能注入、结构化事件/合同、连续性自动修复等差异仍按矩阵保留。快速开始结构化项目种子与每项目润色偏好仅部分补齐，不能写为完全对齐。

## 验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| chat TypeScript 领域测试 | 2,601 / 2,601 | `/tmp/amber-standalone-chat-tests.log` |
| deepread/novel TypeScript 领域测试 | 1,025 / 1,025 | `/tmp/amber-source-domain-tests-final.log` |
| 页面、平台与集成 CJS 测试 | 561 / 561 | `/tmp/amber-standalone-entry-tests.log` |
| 独立工程生成器行为测试 | 6 / 6 | `/tmp/amber-standalone-generator-tests-final.log` |
| 两领域最终 TypeScript 检查 | 通过 | `/tmp/amber-standalone-chat-tsc-final.log`、`/tmp/amber-standalone-deepread-tsc-final.log` |
| 修改 ArkTS lint | 0 errors；存在既有/性能 warnings | `/tmp/amber-standalone-final-ui-lint.log` 及各模块聚焦日志 |
| 两独立产品最终构建及离线签名 | BUILD SUCCESSFUL；两个 verify-app success | `/tmp/amber-standalone-final-build.log` |
| 同证书覆盖安装及冷启动 | 两个新 bundle 均成功 | 现有 `127.0.0.1:5555` 模拟器；未新建/启动额外模拟器 |
| 源码与最终构建工程一致性 | 每产品 1,373 文件，差异 0 | [最终产物 hash](artifacts.json) |

领域/集成测试合计 4,187 项，加生成器 6 项。测试通过证明覆盖范围内的行为，不能替代云模型、系统面板与后台调度验收。

## 已操作的设备流程

- 两个新应用首次安装、授权自身通知、各自根页面和专属设置打开；模型均未配置时可见明确提示。模型配置没有从主应用自动搬入。
- 小说创建 `Standalone-verification` 空项目；创建一章 `Alpha test. Alpha chapter content.`；查找 `Alpha` 得到两处；替换当前仅改变草稿；保存后正文为 `Beta test. Alpha chapter content.`。截图：[键盘](device/novel-find-keyboard.jpeg)、[草稿状态](device/novel-find-replaced-draft.jpeg)、[保存结果](device/novel-find-saved.jpeg)、[最终包重启后正文](device/novel-final-reader-after-restart.jpeg)。
- 深读创建 `Standalone-reading-verification`，提交 267 字符粘贴来源；生成明确提示尚未配置聊天模型。离开页面、覆盖安装和冷启动后，阅读库记录及完整来源正文仍存在。[重启后的来源](device/deepread-final-source-after-restart.jpeg)。
- 阅读库标题搜索与完成状态过滤已操作：[搜索](device/deepread-library-search.jpeg)、[完成筛选空结果](device/deepread-library-filter-complete.jpeg)。未配置模型的记录仍为未完成，未冒充已成功生成。
- 创建页输入时键盘避让、独立设置浅色/深色、创建页深色占位色进行了截图核对：[小说深色设置](device/novel-final-settings-dark.jpeg)、[深读深色创建页](device/deepread-final-compose-dark.jpeg)。设备尺寸为 1,320 × 2,856，检查使用真实 AX 控件边界。

两个验证样例只存在新独立应用沙盒，暂保留供手工复核；未删除任何宿主作品。

## 尚未验收

真实 Provider 的创作/阅读生成、付费搜索、各格式系统文件选择及长文导入、系统分享接收端、系统 PDF 保存与成品、后台 WorkAbility 唤醒和通知点击的真实任务流程均未完成设备验收。PDF 需要 API 14 能力，API 12 不承诺支持。离线签名只用于模拟器；零售真机和发布需要各 bundle 的有效 AGC 签名。

重建命令与签名说明见 [构建说明](../../plans/2026-10-03-harmony-standalone-build.md)。
