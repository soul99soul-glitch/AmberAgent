# Phase 3：设备、动效与最终复审

结论：PASS。独立 reviewer `studio_phase2_review` 完成源码、实际截图和有时间记录的连续帧复审；root 完成最终构建、签名、安装和 UI 操作。所有阶段均已闭环。

## 最终交付

小说首页采用纸页书房插画、真实项目／章节统计和明确的创建／导入入口；深读首页采用阅读星轨、发现层级和热榜卡片。按钮与行加入弹性按压，刷新图标采用有限旋转。小说工作区主页签与设定子页签使用滑动纸面标记；深读阅读工具采用 compact 按钮。

轻点插画会展开／转动，长按会显示星点和隐藏文案，随后自动收回。动效只改变局部 UI 状态，不写作品、不启动生成。隐藏／销毁会清理延迟回调；主题重挂载后返回可再次触发。

## 实际发现与最小修复

| 确证问题 | 修复 | 复核 |
| --- | --- | --- |
| 隐藏页面期间主题重挂载，插画返回后可能未 reveal | active 恢复时显示插画；先以行为测试复现 | 新增 4 个生命周期行为测试、设备主题返回再次长按 |
| 深读窄屏标题出现孤字换行 | 标题缩短为“把热点，读成见解” | 模拟器浅深色、真机浅色 |
| 小说暗色纸页层次较弱 | 只调整插画纸面填充、页线与边框的既有 token | 暗色实拍及独立复审 |
| compact 插画整体下偏 | 内部 144×144 居中缩放，外部真实占位 94×100 | 真机星轨中心与 Hero 中线一致 |
| 小说列表 FAB 出现直角矩形底 | 移除外层 Column 重复 shadow 一行，保留按钮圆角阴影 | 最终重新安装后的浅／深色截图 |

不修改全局主题、领域逻辑或无关页面；宿主产品保留原 controls 与调用链。

## 验证

- 8 个生产文件 scoped lint：退出 0，0 errors；2 条既有 `DeepReadArticlePage` JSON deep-clone performance warnings。最终 FAB 一行修复后，小说首页单文件 lint 无缺陷。
- Entry 候选测试 172/172，含 4 个新增 motion lifecycle 行为测试。最终视觉修复后 motion lifecycle 4/4 再次通过。
- 两个独立应用最终构建、AGC 签名均退出 0；真实设备 `6HK0226627000384` 覆盖安装成功，两应用冷启动与浅色首页实拍完成。
- 模拟器 `127.0.0.1:5555` 使用原匹配签名覆盖安装，两应用浅／深色检查完成，原深色模式已恢复。没有卸载或清除数据。
- 工作区实际点击创作→正文→设定、角色→世界观→更多→角色，滑块与内容一致；返回正常，没有启动写作／分析。
- 已保存文章的 A+ 放大、A− 恢复完成：正文 bounds 从 `[129,1224][1253,1954]` 到 `[129,1245][1253,2012]`，随后恢复原值；资料和导出入口均可达。没有触发重新生成或实际文件导出。
- 小说／深读真机长按连续帧显示彩蛋出现与收回；模拟器主题切换隐藏返回后可重新触发。取样中 caption 不推动布局，纸页／星点不遮挡图文。
- 从已提交 `b2d054b` 的独立检出加本轮 8 文件构建，输入逐文件 SHA 对齐，未混入主工作树其他 WIP。最终 HAP 与源码 SHA 见旁边 receipts JSON。未提交或推送本轮修改。

Entry 测试需使用仓库既有 TS 转译器与 alias 配置：

```sh
TSX_TSCONFIG_PATH=harmony/chat/tsconfig.json \
  node --import ./harmony/chat/node_modules/tsx/dist/loader.mjs \
  --test harmony/entry/src/test/*.test.cjs
```

## 实拍与动效预览

| 内容 | 证据 |
| --- | --- |
| 真机小说空态 | [浅色首页](device/real-novel-final-light.jpeg) |
| 真机深读首页 | [浅色首页](device/real-deepread-final-light.jpeg) |
| 模拟器小说项目列表 | [浅色](device/emulator-novel-final-light.jpeg)、[深色](device/emulator-novel-final-dark.jpeg) |
| 模拟器深读首页 | [浅色](device/emulator-deepread-final-light.jpeg)、[深色](device/emulator-deepread-final-dark.jpeg) |
| 核心写作页签 | [创作](device/emulator-workspace-create.jpeg)、[正文](device/emulator-workspace-text.jpeg)、[角色](device/emulator-workspace-settings.jpeg)、[世界观](device/emulator-workspace-world.jpeg)、[更多](device/emulator-workspace-more.jpeg) |
| 阅读字号／导出入口 | [原字号](device/emulator-reader-final-dark.jpeg)、[放大](device/emulator-reader-zoom-up.jpeg)、[恢复](device/emulator-reader-zoom-restored.jpeg)、[导出面板](device/emulator-reader-export.jpeg) |
| 小说彩蛋 | [实际取样帧预览](device/real-novel-egg.gif)、[展开帧](device/real-novel-egg-frame-03.jpeg) |
| 深读彩蛋 | [实际取样帧预览](device/real-deepread-egg.gif)、[展开帧](device/real-deepread-egg-frame-03.jpeg) |

GIF 由实际设备截图按测得采样间隔组合，原始帧时序 JSON 一并保留。它证明状态与恢复顺序，不能用于声明帧率或全程无卡顿。本轮入口复核也不替代模型生成、资料分析或实际文件导出的验收；系统大字号、横屏及 API 12 实机未在本轮新增设备验收。
