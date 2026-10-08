# Phase 1：纸面组件与动效基础

独立 reviewer：studio_phase1_review。修复后源码 review PASS。

实际发现：inactive 状态因主题重挂载的新 StudioEmblem 会因入场回调退出而保持透明，active watcher 未恢复 revealed。先以执行真实组件字段和方法的 fake clock 测试复现（3通过1失败），再精准补上 active=true 时 revealed=true；修后4/4通过。隐藏／销毁清理、过期回调与长按／点击互斥均有行为测试。

组件 V1、主题父页面纪元重挂载、有限timer与active／alive回调边界、按钮禁用释放与回调守卫、无数据和网络副作用检查通过。SDK发现新按钮使用不支持的 FontWeight.SemiBold，已换仓库现有数值600。Compact标题／说明／detail最多2行，非compact正文居中。

本阶段源码结论不替代实际尺寸、浅深色和彩蛋连续帧；留到Phase3核验。
