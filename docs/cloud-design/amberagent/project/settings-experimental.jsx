// settings-experimental.jsx — 实验性功能 (Experimental Features)

const EXPERIMENTAL = [
  { id: 'webmount',  title: 'WebMount 站点',   desc: '实验性 — 把网站变成确定性 agent 工具。打开后所有 Assistant 自动获得 WebMount 能力。', icon: 'mount' },
  { id: 'icloud',    title: 'iCloud WebMount', desc: '通过 iCloud.com 登录态访问 Drive，不保存 Apple ID 密码。', icon: 'cloud' },
  { id: 'feishu',    title: '飞书办公增强模式',  desc: '读取可见屏幕、通知和使用情况信号，生成 workspace 工作上下文。', icon: 'doc' },
  { id: 'subagent',  title: '子代理实验模式',   desc: '让主 Agent 为边界清楚的子任务启动隔离子代理。包含多模型审议（Council）。', icon: 'doc' },
  { id: 'today',     title: '今日看板',        desc: 'Agent 主动整理每日信号，生成待办与关注项。', icon: 'board' },
  { id: 'apps',      title: '小应用',          desc: '让 Amber 生成、保存并运行轻量 HTML 工具。', icon: 'apps' },
];

function ExperimentalScreen({ t }) {
  return (
    <SubShell t={t}>
      <SubHeader t={t} title="实验性功能" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
        <SubGroupLabel t={t} style={{ paddingLeft: 4 }}>实验性功能</SubGroupLabel>
        <SubCard t={t}>
          {EXPERIMENTAL.map((e, i) => (
            <React.Fragment key={e.id}>
              {i > 0 && <HairDivider t={t} indent={60} />}
              <IconRow t={t} title={e.title} desc={e.desc} icon={<XIcon kind={e.icon} color={t.inkSoft} />} />
            </React.Fragment>
          ))}
        </SubCard>
      </div>
    </SubShell>
  );
}

window.ExperimentalScreen = ExperimentalScreen;
