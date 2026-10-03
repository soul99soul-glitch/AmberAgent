# P3 实际设备验收

设备 127.0.0.1:5555，1320 × 2856；两个最终包构建/签名/覆盖安装成功。只操作 app.amber.novel 自建 Standalone-verification 项目，无宿主数据操作。

- 真实资料编辑弹层：创建人物 Rhea，别名 CaptainRhea，标签 storm，智能匹配，所有分支共享。键盘打开时正文可输入、取消与保存均可见。metadata滚动可达，保存卡片显示真实字段。
- 当前分支编辑后产生覆盖；恢复继承带范围确认，确认后重新显示继承共享底稿。截图 branch-override 与 inheritance-confirmed。该设备步骤仅证明覆盖状态转换，不宣称不同正文的对比。
- 真实讨论输入 Discuss CaptainRhea and the storm. →项目控制滚动入口→预览：实际模型fixture-context-16k；16384窗口/4096输出，输入预算12288；角色122分入选，别名与标签参与实际选择。
- 本次排除后预算1501（默认1526），滚动到底部完整应用按钮→实际返回原工作区，显示仅下一次有效排除1项，原输入保留。
- 实际发送至本机临时验证 provider /v1/chat/completions：排除请求没有资料正文；紧接着第二次默认发送包含资料正文、Aliases: CaptainRhea 和 Tags。两次都真实流式完成并显示消息，单次设置清除。request-receipts.json保存最小请求证据；未记录凭据。

证据：device/p3/*.jpeg、相应AX JSON、request-receipts.json；最终构建 /tmp/amber-p3-final-build.log；小说领域394/394，Entry相关19/19与root12/12，类型与 scoped lint 0 error。

验证模型为本机可控fixture，仅证明实际设备入口、发送链、字段、单次消费和流式可见结果；不代表外部云服务质量。亮暗主题、大字号综合验收归P7。
