# Phase 2：独立页面接入

独立 reviewer：studio_phase2_review。修复后静态接入 review PASS。

实际发现并修复：新增动效影响宿主Board和Novel行背景反馈，现以getProductKind限定；宿主BoardHeader与HEAD原文对照一致。关注词全量拼接可能撑高固定Hero，现只限制展示行数、省略超出内容，不改保存或过滤数据。

核对创建／导入／恢复／项目管理／FAB及深读刷新／随机阅读／设置／历史／文章route闭环通过。统计来自真实项目／章节，disabled保持业务守卫，页面hide/disappear关闭Hero.active，宿主现有请求／缓存／错误处理保留。

Root统一验证：5个改动生产文件scoped lint无缺陷、双独立应用构建／现有AGC签名退出0；候选Entry测试172/172（含4个新增motion lifecycle行为测试）。领域逻辑无修改，不重复全量domain测试。

最终视觉审查在Phase3进行，本报告不声明源码检查等于设备通过。

## 核心页面补充 review

Reviewer studio_phase1_review：源码 PASS。StudioTabs 保留原索引与去重、原切换回调和 host Seg；轨道、滑块按同一等分宽度计算，滑块不参与命中测试。资料／导出／字号保持原条件与回调；首用确认仍在 KV 写入成功后加载，原 pageAlive/pageToken/错误处理保留。compact 按钮 44 vp；Tabs 外轨道 44 vp，文本命中区域 36 vp，不混淆二者。

8 生产文件 lint 退出 0：0 errors，2 条原有 DeepReadArticlePage JSON deep-clone performance warnings；未扩大范围修改旧克隆逻辑。主题重挂载隐藏期间新插画随后返回的 reveal 状态已通过行为测试复现并修复。
