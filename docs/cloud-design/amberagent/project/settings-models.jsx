// settings-models.jsx — 模型分配 (Model Assignment)

function ModelAssignScreen({ t }) {
  const tasks = [
    { icon: 'doc',    name: '标题总结模型', desc: '用于总结对话标题的模型，推荐使用快速且便宜的模型', logo: 'M', model: 'mimo-v2.5', act: '参数' },
    { icon: 'bubble', name: '聊天建议模型', desc: '用于生成对话建议的模型，推荐使用快速且便宜的模型', logo: 'M', model: 'mimo-v2.5', act: '参数' },
    { icon: 'globe',  name: '翻译模型',    desc: '用于翻译功能的模型', logo: 'M', model: 'mimo-v2.5', act: '参数' },
    { icon: 'spark',  name: '生图模型',    desc: 'generate_image 工具的全局默认生图模型。', logo: 'O', model: 'gpt-image-2', act: '提示词' },
  ];
  return (
    <SubShell t={t}>
      <SubHeader t={t} title="模型分配" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
        <SubCard t={t}>
          <div style={{ padding: '14px 14px' }}>
            <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
              <div style={{
                width: 36, height: 36, borderRadius: 10, background: t.accentSoft, color: t.accent,
                display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
              }}>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M21 12a8 8 0 0 1-12 6.9L4 20l1.1-4A8 8 0 1 1 21 12z"/>
                </svg>
              </div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 15, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>聊天模型</div>
                <div style={{ marginTop: 3, fontSize: 12.5, color: t.inkFaint, letterSpacing: 0.2 }}>全局默认的聊天模型</div>
              </div>
            </div>
            <div style={{ marginTop: 12, paddingLeft: 50, display: 'flex', alignItems: 'center', gap: 8 }}>
              <div style={{
                width: 22, height: 22, borderRadius: 7, background: t.modelLogoBg || '#F4F4F4', border: `1px solid ${t.hair}`,
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 11,
              }}>O</div>
              <span style={{ fontSize: 14.5, color: t.accent, fontWeight: 500, letterSpacing: 0.2 }}>gpt-5.5</span>
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke={t.accent} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="6 9 12 15 18 9"/>
              </svg>
            </div>
          </div>
        </SubCard>

        <SubGroupLabel t={t} style={{ paddingLeft: 4, paddingTop: 22 }}>辅助任务</SubGroupLabel>
        <SubCard t={t}>
          {tasks.map((r, i) => (
            <React.Fragment key={i}>
              {i > 0 && <HairDivider t={t} indent={16} />}
              <div style={{ padding: '14px 14px' }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
                  <IconCircle t={t}><XIcon kind={r.icon} color={t.inkSoft} /></IconCircle>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 14.5, color: t.ink, fontWeight: 500, letterSpacing: 0.2, lineHeight: 1.2 }}>{r.name}</div>
                    <div style={{ marginTop: 4, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.45 }}>{r.desc}</div>
                  </div>
                  <div style={{
                    padding: '5px 12px', borderRadius: 999, background: t.accentSoft, color: t.accent,
                    fontSize: 12, fontWeight: 500, letterSpacing: 0.4, flexShrink: 0,
                  }}>{r.act}</div>
                </div>
                <div style={{ marginTop: 8, paddingLeft: 46, display: 'flex', alignItems: 'center', gap: 8 }}>
                  <div style={{
                    width: 20, height: 20, borderRadius: 6, background: t.modelLogoBg || '#F4F4F4', border: `1px solid ${t.hair}`,
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 10,
                  }}>{r.logo}</div>
                  <span style={{ fontSize: 13.5, color: t.accent, fontWeight: 500, letterSpacing: 0.2 }}>{r.model}</span>
                </div>
              </div>
            </React.Fragment>
          ))}
        </SubCard>
      </div>
    </SubShell>
  );
}

window.ModelAssignScreen = ModelAssignScreen;
