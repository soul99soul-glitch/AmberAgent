// convo-history.jsx — Full-screen conversation history.
// Layout: Amber wordmark → search → nav (with collapsible 更多)
//         → divider → recents (active highlighted) → user footer.

const HISTORY_PRIMARY = [
  { id: 'newchat', label: '新聊天' },
  { id: 'board',   label: '今日看板' },
  { id: 'apps',    label: '小应用' },
];

const HISTORY_MORE = [
  { id: 'folder', label: 'Workspace 文件' },
  { id: 'spark',  label: '伴随智能' },
  { id: 'stats',  label: '聊天热力图统计' },
];

const HISTORY_RECENTS = {
  active: '提问用户控件用法',
  groups: [
    { date: '昨天',   items: ['亚朵房型对比', 'GitHub 仓库无效处理', 'AI 助手角色定位圆桌辩论'] },
    { date: '5月21', items: ['产品策略锐平选择', '晚安和好梦', '升级确认'] },
  ],
};

function HistoryScreen({ t }) {
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
        <HistoryBrand t={t} />
        <div style={{ flex: 1, overflow: 'hidden' }}>
          <SearchBar t={t} />
          <HistoryNav t={t} />
          <QuickRow t={t} />
          <div style={{ height: 1, background: t.hair, margin: '14px 22px 14px' }} />
          <HistoryRecents t={t} />
        </div>
        <HistoryFooter t={t} />
        <HomeIndicator t={t} />
      </div>
    </div>
  );
}

function HistoryBrand({ t }) {
  return (
    <div style={{ padding: '14px 22px 12px' }}>
      <div style={{
        fontFamily: t.brandFont || '"Source Han Serif SC", "Noto Serif SC", "Songti SC", serif',
        fontSize: 32, fontWeight: 500,
        color: t.ink, letterSpacing: 0.5, lineHeight: 1,
      }}>Amber</div>
    </div>
  );
}

function SearchBar({ t }) {
  return (
    <div style={{ padding: '6px 18px 4px' }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 10,
        padding: '10px 14px', borderRadius: 999,
        background: t.searchBg || 'rgba(15,20,25,0.04)',
        border: `1px solid ${t.searchEdge || t.hair}`,
      }}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.6" y2="16.6"/>
        </svg>
        <span style={{ fontSize: 14.5, color: t.inkFaint, letterSpacing: 0.2 }}>搜索聊天</span>
      </div>
    </div>
  );
}

function HistoryNav({ t }) {
  return (
    <div style={{ padding: '6px 18px 0' }}>
      {HISTORY_PRIMARY.map(item => (
        <NavRow key={item.id} t={t} item={item} />
      ))}
    </div>
  );
}

// Quick-access strip: icon-only buttons, horizontally scrollable.
// Represents the same 3 secondary features (Workspace 文件 · 伴随智能 ·
// 聊天热力图统计) without labels.
function QuickRow({ t }) {
  return (
    <div style={{
      padding: '10px 18px 2px',
      display: 'flex', alignItems: 'center', gap: 10,
      overflowX: 'auto', WebkitOverflowScrolling: 'touch',
      scrollbarWidth: 'none',
    }}>
      <QuickBtn t={t} kind="folder" title="Workspace 文件" />
      <QuickBtn t={t} kind="spark"  title="伴随智能" />
      <QuickBtn t={t} kind="stats"  title="聊天热力图统计" />
    </div>
  );
}

function QuickBtn({ t, kind, title }) {
  return (
    <div title={title} style={{
      width: 36, height: 36, borderRadius: 10,
      background: t.quickBtnBg || 'rgba(15,20,25,0.04)',
      border: `1px solid ${t.quickBtnEdge || t.hair}`,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      flexShrink: 0,
    }}>
      <NavIcon kind={kind} color={t.inkSoft} />
    </div>
  );
}

function NavRow({ t, item, chevron, compact }) {
  const accent = item.id === 'newchat';
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 14,
      padding: compact ? '9px 6px' : '11px 6px',
      color: accent ? t.accent : t.ink,
    }}>
      <NavIcon kind={item.id} color={accent ? t.accent : t.inkSoft} />
      <span style={{
        flex: 1, fontSize: compact ? 14 : 15, letterSpacing: 0.2,
        fontWeight: accent ? 500 : 400,
      }}>{item.label}</span>
      {chevron && (
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
          style={{ transform: chevron === 'down' ? 'rotate(0)' : 'rotate(-90deg)', transition: 'transform .2s' }}>
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      )}
    </div>
  );
}

function NavIcon({ kind, color }) {
  const p = { width: 19, height: 19, viewBox: '0 0 24 24', fill: 'none', stroke: color, strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' };
  if (kind === 'newchat') return (
    <svg {...p}>
      <path d="M20.5 11.3a8 8 0 0 1-.85 3.6 8 8 0 0 1-7.15 4.4 8 8 0 0 1-3.6-.85L3.5 20.5l1.45-5.4a8 8 0 0 1-.85-3.6 8 8 0 0 1 4.4-7.15 8 8 0 0 1 3.6-.85h.45a8 8 0 0 1 7.55 7.55v.45z"/>
      <line x1="12" y1="8.4" x2="12" y2="14.6"/>
      <line x1="8.9" y1="11.5" x2="15.1" y2="11.5"/>
    </svg>
  );
  if (kind === 'chats') return <svg {...p}><path d="M21 12a8 8 0 0 1-12 6.9L4 20l1.1-4A8 8 0 1 1 21 12z"/></svg>;
  if (kind === 'board') return <svg {...p}><rect x="3" y="5" width="18" height="14" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="7" y1="13" x2="13" y2="13"/><line x1="7" y1="16" x2="11" y2="16"/></svg>;
  if (kind === 'apps') return <svg {...p}><rect x="4" y="4" width="6" height="6" rx="1.5"/><rect x="14" y="4" width="6" height="6" rx="1.5"/><rect x="4" y="14" width="6" height="6" rx="1.5"/><rect x="14" y="14" width="6" height="6" rx="1.5"/></svg>;
  if (kind === 'more')  return <svg {...p}><circle cx="5" cy="12" r="1.4" fill={color} stroke="none"/><circle cx="12" cy="12" r="1.4" fill={color} stroke="none"/><circle cx="19" cy="12" r="1.4" fill={color} stroke="none"/></svg>;
  if (kind === 'folder')return <svg {...p}><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"/></svg>;
  if (kind === 'spark') return <svg {...p}><path d="M12 3l1.6 4.4L18 9l-4.4 1.6L12 15l-1.6-4.4L6 9l4.4-1.6z"/><path d="M19 16l.7 1.8L21.5 18.5 19.7 19.2 19 21l-.7-1.8L16.5 18.5l1.8-.7z"/></svg>;
  if (kind === 'stats') return <svg {...p}><line x1="6" y1="20" x2="6" y2="12"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="18" y1="20" x2="18" y2="14"/></svg>;
  return null;
}

function HistoryRecents({ t }) {
  return (
    <div style={{ padding: '0 22px' }}>
      <div style={{ fontSize: 12, color: t.inkFaint, letterSpacing: 0.6, marginBottom: 10 }}>最近</div>
      {/* active conversation — highlighted capsule */}
      <div style={{
        margin: '0 -8px 12px',
        padding: '10px 12px', borderRadius: 12,
        background: t.recentActiveBg || t.accentSoft,
        color: t.recentActiveInk || t.accent,
        fontSize: 14.5, fontWeight: 500, letterSpacing: 0.2,
      }}>{HISTORY_RECENTS.active}</div>
      {HISTORY_RECENTS.groups.map((g, gi) => (
        <div key={gi}>
          <div style={{ fontSize: 12, color: t.inkFaint, letterSpacing: 0.4, margin: '12px 0 6px' }}>{g.date}</div>
          {g.items.map((item, ii) => (
            <div key={ii} style={{
              padding: '9px 4px', fontSize: 14.5, color: t.ink, letterSpacing: 0.2,
              lineHeight: 1.3,
              whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
            }}>{item}</div>
          ))}
        </div>
      ))}
    </div>
  );
}

function HistoryFooter({ t }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 12,
      padding: '14px 22px 10px',
      borderTop: `1px solid ${t.hair}`,
    }}>
      <div style={{
        width: 32, height: 32, borderRadius: '50%',
        background: t.avatarBg || t.userBubble,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        border: `1px solid ${t.userBubbleEdge}`,
        flexShrink: 0,
      }}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke={t.accent} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="8" cy="6.5" r="2"/><circle cx="16" cy="6.5" r="2"/>
          <circle cx="12" cy="13" r="6"/>
          <circle cx="10" cy="12" r="0.5" fill={t.accent}/>
          <circle cx="14" cy="12" r="0.5" fill={t.accent}/>
        </svg>
      </div>
      <span style={{ flex: 1, fontSize: 15, color: t.ink, letterSpacing: 0.2 }}>Arquiel</span>
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="3"/>
        <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.6 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/>
      </svg>
    </div>
  );
}

window.HistoryScreen = HistoryScreen;
