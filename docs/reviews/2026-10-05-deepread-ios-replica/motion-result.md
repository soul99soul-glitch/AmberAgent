# 深度阅读 iOS 动效、设置细节与两款图标复核

2026-10-05，Harmony 工作树 HEAD `b2d054bd7a81c64795497098d54cf30ad049e04f`；iOS 参照为当前 `/Users/arquiel/Downloads/AI/amberagent-ios` 的独立 DeepReadApp。保留已有 WIP，不用整个 dirty diff 代表这轮修改；未提交或推送。此前第一轮 UI 报告与 build6 证据保持原样，本文件记录后续动效补修。

## 结论与范围

按固定 31 组用户可辨认交互核对，当前 **14 组语义与主要参数接齐、14 组平台近似或含局部缺口、3 组未接**。多数核心动效已有真实触发与生命周期清理；不能称为全部与 iOS 逐帧相同。[固定 before 清单](motion-ios-inventory.md) 与 [逐项 after 表](motion-ios-after-review.md) 保留相同分母。普通系统 sheet/menu/spinner 及 AppIcon 不计入这 31 组。

| 结果 | 数量 | 主要内容与实际边界 |
| --- | ---: | --- |
| 语义、主要幅度和时序接齐 | 14 | 夜读台灯、根标签切换、刊头横线、五连点盖章、错误卡、骨架、库筛选、活跃点、正文入场、完成盖章、扩散环、活字、格言、强调色环 |
| 平台近似或局部缺口 | 14 | SF Symbols 用整体图标弹簧/缩放/淡入替代；滚动卡片用可见阈值代替连续 phase；进度文字用淡移代替 numericText；部分创建模式、文章排版选择尚未出现对应触发 |
| 未接 | 3 | iOS 原生 Tab 下滚收缩、来源卡到文章共享 zoom、原文/阅读样式/编排三类选择触感 |

S/P 是源码语义覆盖，设备证据另外列。触感为平台 10/35/55 ms touch vibration 近似，模拟器不证明物理手感。系统减少动画读取/订阅仅在 API ≥23 且 capability 支持时启用；API 12–22 保留普通动效路径。

## 这轮补齐的行为

- 发现：列表与分组标题分批弹簧入场、卡片按压、横线展开、徽章与空态有限弹跳、错误卡与骨架；连点章分离落章和墨色淡入。真实加速度订阅与显式随机共用候选集，散报后显示幸运横幅，8 秒清理；弹层、后台、离页取消自身订阅与计时。
- 阅读库与创建：移动筛选胶囊、运行状态点呼吸、卡片滚动淡缩和空态弹跳；文件增删、错误提示过渡，创建成功反馈绑定实际已持久保存的 draft。保持原有立即导航，不人为延迟业务来展示动画。
- 编辑室和正文：真实阶段推进轨道，扩散环完全淡出后再复位，活字从上方落盘且退场仅淡出；格言每 4.2 秒轮换，减少动效时仍轮换。完成盖章以实际四段验证与持久成功驱动，分开落章、墨色、字幕和退出；正文 500 ms 淡入上移。
- 设置与模板：选中强调色色环、缩放和 check，44×44 步进按钮、整数百分比、选中勾和保存短提示；模板选择的图标与勾反馈。未保存字体/字号只传入模板预览，旧 KV 回读不覆盖预览，未提前应用到阅读页。
- 设置正确性：搜索草稿上移到页面，主题重挂载和子页返回均保留；按稳定服务 ID 合并新目录，保持最新凭据。父模板持久 baseline 跟踪子页新默认，避免保存父页时写回旧默认。独立版模型选择等统一保存，宿主原有保存语义保留；独立热点来源偏好即时保存。
- 最后设备复查另外发现根标签 replace 销毁设置页面也会丢草稿。补充 typed AppStorage 内存快照，只含搜索标量/首选 ID、阅读草稿/模板 baseline 和脏模型 pair；不缓存目录或凭据、不写 KV。返回仍读取最新目录；保存成功清稿，新 Ability 创建清稿。具体 iOS 与 Harmony 10→11 对照、修复后与冷启动设备证据见下文。

## 当前设备证据

设备为 `127.0.0.1:5555` 的 API 24 Pura 90 模拟器，1320×2868。截图和取样证明可见行为与中间状态，不代表 60/120 FPS 或全程无卡顿。

| 路径 | 结果 | 证据 |
| --- | --- | --- |
| 强调色朱砂→黛蓝→朱砂 | 连续取样中看到色环沿横向移动；主题重挂载没有截断为直接跳变，选中名称/check 同步 | [黛蓝取样](motion/device/accent-blue/result.json)、[中间帧](motion/device/accent-blue/frame-2.jpeg)、[iOS 中间帧](motion/reference/04-ios-accent-intermediate.jpg) |
| 根标签切阅读库、库筛选生成中 | 捕获过渡中间状态和筛选胶囊移动；空态仍可见 | [标签取样](motion/device/tab-library/result.json)、[筛选取样](motion/device/library-filter/result.json)、[筛选中间帧](motion/device/library-filter/frame-2.jpeg) |
| 子页返回、强调色触发主题重挂载 | 结果 11 / DuckDuckGo OFF 草稿仍在，随后恢复原 10 / ON，未将这次草稿保存 | [草稿恢复截图](motion/device/10-settings-draft-survives.jpeg) |
| 根标签切换／冷启动／统一保存 | 最终包中 11/OFF 跨根标签保留；未保存冷启动回 10/ON；保存 11 后冷启动仍 11。最后恢复并保存原 10/ON | [设备断言结果](motion/device/settings-session-device-result.json)、[跨标签草稿](motion/device/17-session-cross-tab.jpeg)、[保存后冷启动](motion/device/18-session-saved-cold.jpeg)、[原设置恢复](motion/device/18-session-original-restored.jpeg) |
| 刊头五连点 | 891 ms 内五点触发真实“深读”章，首帧取样看到较大章，随后收敛 | [落章取样](motion/device/discovery-stamp/frame-0.jpeg)、[收敛截图](motion/device/discovery-stamp/after.jpeg) |
| 编辑室真实生成阶段 | 活字悬空到落盘、轨道推进、图标/标题阶段替换可见 | [正常活字中间帧](motion/device/14-desk-normal-5.jpeg) |
| 系统过渡动画 OFF 后回应用 | 编辑室活字全部静态排好，减少动效分支实际生效；之后恢复系统原 ON | [减少动效截图](motion/device/14-desk-reduced-1.jpeg)、[原设置恢复](motion/device/14-system-motion-restored.json) |
| 成功完成、章与正文 | 取样捕获付印章、字幕、退出及正文；阅读库显示完成，重进读取完整文章 | [连续取样总览](motion/device/completion/contact-sheet.jpeg)、[完整正文](motion/device/completion/after.jpeg)、[持久阅读库](motion/device/15-library-persisted.jpeg)、[重新进入](motion/device/15-article-reopened.jpeg) |

上述成功流程使用有界 loopback 本地模型，模型只返回固定合法 JSON，真实页面/规划/四段校验/保存调用链执行。请求计数为 plan/overview/narrative/analysis/extension 各一次。手动文本和实际搜索资料进入流程；4 个资料来源读取失败仍明确显示，未隐藏失败或放宽 validator。此证据不证明真实云端生成质量。测试文章 `IOS-Motion-Final-20261005` 保留，旧失败/完成文章及小说项目未删除；模型选择、搜索条数和开关、强调色、系统动画均恢复原设置，任务独占 mock server 与 reverse port 已清理。

物理摇动命中、马达触感、完整动画帧率尚未真机验证；幸运横幅、骨架/运行点的完整周期和全部 SFsymbol 近似效果也没有逐项设备验收。iOS 原生未接项保持公开，不用默认导航充当 source zoom。

## 图标

小说创作与深度阅读均使用各自 iOS AppIcon 标准 PNG；来源和源目标 SHA-256 已逐字节核对。两款已签名、覆盖安装、冷启动并查看 [桌面图标](motion/device/06-ios-icons-desktop.jpeg)。小说红色“文”章，深读红色书本。详见 [图标报告](ios-icons-reuse.md)。

深读最终源码构建使用原图输入，SDK 在 HAP 内编译为 512×512 PNG；包内哈希不冒充源图哈希。小说为了保护原已验收运行代码，原 HAP 109 项载荷仅替换 base/phone 两个 PNG，再官方签名；ABC/native 与其余载荷字节保持，并验证 4 KB 代码对齐。小说图标交付不声称本轮小说功能源码构建。

## 集成验证

全部生产修改已停写，最后会话草稿增量纳入最终集成与安装：

- `node scripts/test-harmony.mjs`：**4,861 / 4,861 通过，184 suites，0 fail / skipped / cancelled**。[最后全量日志](motion/full-tests-final4.log)。会话新增 5 个实际页面重建行为用例及 1 个真实 EntryAbility.onCreate 行为用例，覆盖未提交不写 KV、凭据刷新、模型/服务删除、模板 baseline、新 Ability 清稿与 Novel 不清稿。
- 各生产修改 scope 的 ArkTS lint 均 0 errors；最后 EntryAbility / SettingsPage 两文件为 **0 errors / 7 warnings**，警告为已有局部状态计算与 JSON clone 建议，不将其称为零警告。[最后 lint](motion/session-final-lint.log)。
- 最终 DeepRead 已从当前共享源码构建、官方签名并 verify-app 成功：[构建/签名日志](motion/deepread-session-final-build-sign.log)。846 个 main TS/ETS 逐项比较源与生成工程相同：[源码闭包哈希](motion/deepread-session-runtime-source-sha256.json)。此前生成/动效取样包与此最终包仅随后设置会话及新 Ability 清稿不同；动效实现没有再修改。
- 最终包覆盖安装成功，冷启动及上表设置边界已按实际布局断言并查看截图：[最后安装](motion/deepread-session-final-install.log)。本轮签名是模拟器用离线证书，零售真机正式 AGC 签名与安装未验。

| 产品 | 最终已安装 HAP SHA-256 | 字节数 | 交付含义 |
| --- | --- | ---: | --- |
| 深度阅读 | `c76700f55309b579d2ea469ba3e242f0c3c940a42fb97c57418128896e8821f9` | 62,360,621 | 当前深读源码构建 + iOS 原图输入 + 官方签名 |
| 小说创作 | `a6a1a29559f752204451688040c3fd5f2c024b2d4a6791c9eddb7ac0499d7f3b` | 63,077,953 | 原已验收运行载荷仅替换 iOS 图标 + 官方签名 |

HAP 均在 `.harmony-standalone/<product>/entry/build/default/outputs/default/signed/entry-default-signed.hap`；完整路径、源 PNG / 包 PNG 与权限记录见 [最终产物清单](motion/final-artifacts.json)。Novel 不包含本轮深读共享源码交付；没有用一次两产品全重建去替换其已验收代码。

规范复核与逐项规格复核分别见 [Standards](motion-standards-review.md) 和 [Spec](motion-ios-after-review.md)。此前 Standards 确认的宿主热点刷新 callback 提前写 KV 已修复，维持宿主显式保存；后续会话草稿按真实根路由/冷启动边界追加复核。
