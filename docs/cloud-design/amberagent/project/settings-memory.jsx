// settings-memory.jsx — 核心记忆 (Core Memory)

function CoreMemoryScreen({ t }) {
  const sections = [
    { t: '记忆开关',   d: '核心、短期、长期、最近会话、时间提醒、选择性召回。' },
    { t: '自动整理',   d: '后台提取、Daydream 自动管理、空闲和充电条件 · 待审核 104 条' },
    { t: '上下文管理', d: '压缩策略、提醒模式、默认阈值。' },
    { t: '记忆库',     d: '核心 0 · 短期 22 · 长期 8 · 候选 104' },
  ];
  return (
    <SubShell t={t}>
      <SubHeader t={t} title="核心记忆" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
        <div style={{ padding: '0 4px' }}>
          <div style={{ fontSize: 17, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>agents.md / Soul</div>
          <div style={{ marginTop: 6, fontSize: 13, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.5 }}>
            这段应用级 Markdown 行为准则会在每次对话前注入到 System Prompt 中。
          </div>
        </div>
        <div style={{ marginTop: 14 }}>
          <SubCard t={t}>
            <div style={{
              padding: '14px 16px', fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace',
              fontSize: 13, color: t.ink, lineHeight: 1.55,
            }}>
              <div># agents.md</div>
              <div style={{ marginTop: 10 }}>You are AmberAgent, an agent-only Androi…</div>
            </div>
          </SubCard>
        </div>
        <div style={{ padding: '12px 4px 0', fontSize: 13.5, color: t.accent, fontWeight: 500, letterSpacing: 0.3 }}>
          点击编辑 agents.md
        </div>

        <div style={{ marginTop: 22 }}>
          {sections.map((s, i) => (
            <div key={i} style={{ padding: '14px 4px', borderBottom: i < sections.length - 1 ? `1px solid ${t.hair}` : 'none' }}>
              <div style={{ fontSize: 15, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>{s.t}</div>
              <div style={{ marginTop: 5, fontSize: 12.5, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.45 }}>{s.d}</div>
            </div>
          ))}
        </div>
      </div>
    </SubShell>
  );
}

window.CoreMemoryScreen = CoreMemoryScreen;
