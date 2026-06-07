// settings-screen.jsx — Settings page, themed.
// Layout: back header → grouped cards. Each card has rows with line icon +
// title + subtitle; first row of "通用设置" has a chip on the right.

function SettingsScreen({ t }) {
  return (
    <div style={{
      width: 380, height: 832, borderRadius: 44,
      background: t.bg,
      position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont, color: t.ink,
      display: 'flex', flexDirection: 'column',
    }}>
      {t.haloConvo && (
        <div aria-hidden style={{ position: 'absolute', inset: 0, pointerEvents: 'none', background: t.haloConvo }} />
      )}
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        <StatusBar t={t} />
        <SettingsHeader t={t} />
        <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
          <SettingsGroup t={t} label="通用设置">
            <SettingsRow t={t} icon="theme"
              title="颜色模式" subtitle="跟随系统"
              right={<ModeChip t={t} label="跟随系统" />} />
            <SettingsRow t={t} icon="display"
              title="显示设置" subtitle="管理显示设置" />
          </SettingsGroup>

          <SettingsGroup t={t} label="Agent 设置">
            <SettingsRow t={t} icon="memory" active
              title="核心记忆" subtitle="跨会话保留的 AmberAgent 持久知识" />
            <SettingsRow t={t} icon="skills"
              title="技能与扩展" subtitle="Skill、提示词和外部工具服务" />
            <SettingsRow t={t} icon="display2"
              title="运行与显示" subtitle="工具循环、操作预览和实时状态" />
            <SettingsRow t={t} icon="perms"
              title="权限与批准" subtitle="系统权限和工具批准策略" />
            <SettingsRow t={t} icon="env"
              title="Agent 运行环境" subtitle="Workspace、Alpine/proot 和命令运行状态" />
            <SettingsRow t={t} icon="lab"
              title="实验性功能" subtitle="管理仍需真机验证的 AmberAgent 预览能力" />
          </SettingsGroup>
        </div>
        <HomeIndicator t={t} />
      </div>
    </div>
  );
}

function SettingsHeader({ t }) {
  return (
    <div style={{
      padding: '14px 18px 18px',
      display: 'flex', alignItems: 'center', gap: 14,
    }}>
      <div style={{ width: 32, height: 32, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="15 18 9 12 15 6"/>
        </svg>
      </div>
      <div style={{
        fontFamily: t.bodyFont, fontSize: 24, fontWeight: 500,
        color: t.ink, letterSpacing: 0.3, lineHeight: 1,
      }}>设置</div>
    </div>
  );
}

function SettingsGroup({ t, label, children }) {
  return (
    <div style={{ marginBottom: 22 }}>
      <div style={{
        fontSize: 12, color: t.inkFaint, letterSpacing: 0.6,
        padding: '0 8px 8px',
      }}>{label}</div>
      <div style={{
        background: t.cardBg || t.surface,
        border: `1px solid ${t.hair}`,
        borderRadius: 18,
        overflow: 'hidden',
      }}>{children}</div>
    </div>
  );
}

function SettingsRow({ t, icon, title, subtitle, right, active }) {
  return (
    <div style={{
      position: 'relative',
      padding: '14px 14px',
      display: 'flex', alignItems: 'center', gap: 14,
      background: active ? (t.rowActiveBg || t.accentSoft) : 'transparent',
      // hairline between siblings (not above the first)
      borderTop: '1px solid transparent',
      boxShadow: 'inset 0 1px 0 0 transparent',
    }} className="amber-settings-row">
      <div style={{
        width: 34, height: 34,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: t.inkSoft, flexShrink: 0,
      }}>
        <SettingsIcon kind={icon} color={t.inkSoft} />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 15, fontWeight: 500, color: t.ink, letterSpacing: 0.2, lineHeight: 1.2 }}>{title}</div>
        {subtitle && (
          <div style={{
            fontSize: 12.5, color: t.inkFaint, marginTop: 3,
            letterSpacing: 0.2, lineHeight: 1.35,
            overflow: 'hidden', textOverflow: 'ellipsis',
          }}>{subtitle}</div>
        )}
      </div>
      {right}
    </div>
  );
}

// Mode chip (color mode picker). Pill-shaped, accent text + chevron.
function ModeChip({ t, label }) {
  return (
    <div style={{
      display: 'inline-flex', alignItems: 'center', gap: 6,
      padding: '7px 10px 7px 14px', borderRadius: 999,
      background: t.modeChipBg || t.accentSoft,
      color: t.modeChipInk || t.accent,
      fontFamily: t.bodyFont, fontSize: 13, fontWeight: 500,
      letterSpacing: 0.2, flexShrink: 0,
    }}>
      <span>{label}</span>
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </div>
  );
}

function SettingsIcon({ kind, color }) {
  const p = { width: 21, height: 21, viewBox: '0 0 24 24', fill: 'none', stroke: color, strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' };
  if (kind === 'theme') return (
    <svg {...p}>
      <circle cx="12" cy="12" r="4"/>
      <line x1="12" y1="2" x2="12" y2="4"/><line x1="12" y1="20" x2="12" y2="22"/>
      <line x1="4.93" y1="4.93" x2="6.34" y2="6.34"/><line x1="17.66" y1="17.66" x2="19.07" y2="19.07"/>
      <line x1="2" y1="12" x2="4" y2="12"/><line x1="20" y1="12" x2="22" y2="12"/>
      <line x1="4.93" y1="19.07" x2="6.34" y2="17.66"/><line x1="17.66" y1="6.34" x2="19.07" y2="4.93"/>
    </svg>
  );
  if (kind === 'display') return (
    <svg {...p}>
      <circle cx="12" cy="12" r="9"/>
      <path d="M12 6.5l1.4 3.4 3.6.3-2.7 2.4.8 3.5L12 14.3l-3.1 1.8.8-3.5L7 10.2l3.6-.3z"/>
    </svg>
  );
  if (kind === 'memory') return (
    <svg {...p}>
      <path d="M9 3a3 3 0 0 0-3 3v1a3 3 0 0 0-2 5v1a3 3 0 0 0 2 3 3 3 0 0 0 3 3"/>
      <path d="M15 3a3 3 0 0 1 3 3v1a3 3 0 0 1 2 5v1a3 3 0 0 1-2 3 3 3 0 0 1-3 3"/>
      <line x1="12" y1="3" x2="12" y2="22"/>
      <path d="M9 8h2"/><path d="M13 11h2"/><path d="M9 14h2"/>
    </svg>
  );
  if (kind === 'skills') return (
    <svg {...p}>
      <path d="M21 16V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
      <polyline points="3.3 7 12 12 20.7 7"/>
      <line x1="12" y1="22" x2="12" y2="12"/>
    </svg>
  );
  if (kind === 'display2') return (
    <svg {...p}>
      <circle cx="12" cy="12" r="9"/>
      <line x1="9" y1="10" x2="9" y2="10.01"/>
      <line x1="15" y1="10" x2="15" y2="10.01"/>
      <path d="M9 15c1 1 2 1.5 3 1.5s2-.5 3-1.5"/>
    </svg>
  );
  if (kind === 'perms') return (
    <svg {...p}>
      <path d="M10.3 3.6L2 18a2 2 0 0 0 1.7 3h16.6A2 2 0 0 0 22 18L13.7 3.6a2 2 0 0 0-3.4 0z"/>
      <line x1="12" y1="9" x2="12" y2="13"/>
      <line x1="12" y1="17" x2="12" y2="17.01"/>
    </svg>
  );
  if (kind === 'env') return (
    <svg {...p}>
      <path d="M8 3H6a3 3 0 0 0-3 3v3a3 3 0 0 1-2 3 3 3 0 0 1 2 3v3a3 3 0 0 0 3 3h2"/>
      <path d="M16 21h2a3 3 0 0 0 3-3v-3a3 3 0 0 1 2-3 3 3 0 0 1-2-3V6a3 3 0 0 0-3-3h-2"/>
    </svg>
  );
  if (kind === 'lab') return (
    <svg {...p}>
      <line x1="4.5" y1="19.5" x2="9" y2="15"/>
      <path d="M5 3a4 4 0 0 1 4 4l-1 2 11 11a3 3 0 1 1-4 4L4 12l2-1a4 4 0 0 1-1-8z"/>
    </svg>
  );
  return null;
}

window.SettingsScreen = SettingsScreen;
