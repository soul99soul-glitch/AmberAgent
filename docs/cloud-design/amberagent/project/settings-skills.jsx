// settings-skills.jsx — Skill 技能 page, redesigned in Amber's minimal language.

const SKILLS = [
  { id: 'skill-creator',     name: 'skill-creator',     desc: 'Use when the user wants to create a new AmberAgent skill, update an…', enabled: true,  tags: [] },
  { id: 'deep-read-fact',    name: 'deep-read-fact-check', desc: 'Use when verifying a long article, news analysis, social-media clai…', enabled: false, tags: [] },
  { id: 'ssh-mac-mini',      name: 'ssh-mac-mini',      desc: '通过 SSH 远程操作 Mac mini，常用于离线视频转码、文件整理与备份。', enabled: true,  tags: [] },
  { id: 'feishu-doc-reader', name: 'feishu-doc-reader', desc: '读取飞书文档、知识库与多维表格，回答内部检索类问题。', enabled: true,  tags: [] },
  { id: 'amap-mcp',          name: 'amap-mcp',          desc: '高德地图 MCP，地点搜索 / 路线规划 / 公交查询。', enabled: true,  tags: ['mcp'] },
  { id: 'feishu-mcp',        name: 'feishu-mcp',        desc: '飞书官方 MCP，会议 / 日程 / 消息收发。', enabled: true,  tags: ['mcp'] },
];

function SkillsScreen({ t }) {
  return (
    <SubShell t={t}>
      <SubHeader t={t} title="Skill 技能" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
        {/* Skill 库 hero card */}
        <SubCard t={t}>
          <div style={{ padding: '16px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              <IconCircle t={t}>
                <SkillGlyph color={t.inkSoft} size={22} />
              </IconCircle>
              <div style={{ fontSize: 17, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>Skill 库</div>
            </div>
            {/* count chips — single accent color, no green */}
            <div style={{ display: 'flex', gap: 8, marginTop: 12, flexWrap: 'wrap' }}>
              <CountChip t={t} label="已安装" n={7} kind="neutral" />
              <CountChip t={t} label="已启用" n={6} kind="accent" />
              <CountChip t={t} label="未启用" n={1} kind="neutral" />
            </div>
            <div style={{ marginTop: 12, fontSize: 12.5, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.5 }}>
              Agent 会在每次运行前重新扫描已安装 Skill。
            </div>
            {/* actions — a quiet row of icon-only buttons, single primary text-link */}
            <div style={{ marginTop: 14, display: 'flex', alignItems: 'center', gap: 8 }}>
              <ActionIcon t={t}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
                </svg>
              </ActionIcon>
              <ActionIcon t={t}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>
                </svg>
              </ActionIcon>
              <ActionIcon t={t}>
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="20 5 20 11 14 11"/><path d="M19 12a8 8 0 1 1-1.6-4.8L20 11"/>
                </svg>
              </ActionIcon>
              <div style={{ flex: 1 }} />
              <div style={{
                padding: '6px 12px', borderRadius: 999,
                background: t.accentSoft, color: t.accent,
                fontSize: 12.5, fontWeight: 500, letterSpacing: 0.3,
              }}>全量规整</div>
            </div>
          </div>
        </SubCard>

        {/* Skills list — single card with hairlines */}
        <SubGroupLabel t={t} style={{ paddingLeft: 4, paddingTop: 22 }}>已安装</SubGroupLabel>
        <SubCard t={t}>
          {SKILLS.map((s, i) => (
            <React.Fragment key={s.id}>
              {i > 0 && <HairDivider t={t} indent={60} />}
              <SkillRow t={t} s={s} />
            </React.Fragment>
          ))}
        </SubCard>
      </div>
    </SubShell>
  );
}

function SkillRow({ t, s }) {
  return (
    <div style={{ padding: '14px 14px', display: 'flex', alignItems: 'flex-start', gap: 14 }}>
      <IconCircle t={t}>
        <SkillGlyph color={s.enabled ? t.inkSoft : t.inkFaint} size={20} />
      </IconCircle>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontSize: 14.5, color: s.enabled ? t.ink : t.inkFaint,
          fontWeight: 500, letterSpacing: 0.2, lineHeight: 1.2,
        }}>{s.name}</div>
        <div style={{
          marginTop: 4, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.45,
          display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden',
        }}>{s.desc}</div>
        {(!s.enabled || s.tags.length > 0) && (
          <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
            {!s.enabled && (
              <span style={{
                padding: '3px 10px', borderRadius: 999,
                background: 'rgba(15,20,25,0.05)',
                fontSize: 11, color: t.inkFaint, letterSpacing: 0.4,
              }}>未启用</span>
            )}
            {s.tags.includes('mcp') && (
              <span style={{
                padding: '3px 10px', borderRadius: 999,
                background: t.accentSoft, color: t.accent,
                fontSize: 11, fontWeight: 500, letterSpacing: 0.4,
              }}>MCP</span>
            )}
          </div>
        )}
      </div>
      {/* single tertiary affordance: chevron — taps into detail page */}
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={t.inkFaint} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0, marginTop: 2 }}>
        <polyline points="9 18 15 12 9 6"/>
      </svg>
    </div>
  );
}

function CountChip({ t, label, n, kind }) {
  const bg = kind === 'accent' ? t.accentSoft : 'rgba(15,20,25,0.05)';
  const ink = kind === 'accent' ? t.accent : t.inkSoft;
  return (
    <div style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: '4px 10px', borderRadius: 999,
      background: bg, color: ink,
      fontSize: 11.5, fontWeight: 500, letterSpacing: 0.3,
    }}>
      <span>{label}</span>
      <span style={{ fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace' }}>{n}</span>
    </div>
  );
}

function ActionIcon({ t, children }) {
  return (
    <div style={{
      width: 32, height: 32, borderRadius: 10,
      background: 'transparent', border: `1px solid ${t.hair}`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: t.inkSoft, flexShrink: 0,
    }}>{children}</div>
  );
}

function SkillGlyph({ color, size = 20 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M14 4a2 2 0 0 1 4 0v2h2a2 2 0 0 1 0 4h-1v3h3a2 2 0 0 1 0 4h-3v3a2 2 0 0 1-2 2h-3v-1a2 2 0 0 0-4 0v1H7a2 2 0 0 1-2-2v-3H4a2 2 0 0 1 0-4h1v-3H4a2 2 0 0 1 0-4h2V4a2 2 0 0 1 2-2h2v1a2 2 0 0 0 4 0z"/>
    </svg>
  );
}

window.SkillsScreen = SkillsScreen;
