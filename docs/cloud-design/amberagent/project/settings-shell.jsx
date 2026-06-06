// settings-shell.jsx — Shared shell + helpers for settings sub-screens.

function SubShell({ t, children }) {
  return (
    <div style={{
      width: 380, height: 832, borderRadius: 44, background: t.bg,
      position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont, color: t.ink,
      display: 'flex', flexDirection: 'column',
    }}>
      {t.haloConvo && <div aria-hidden style={{ position: 'absolute', inset: 0, pointerEvents: 'none', background: t.haloConvo }} />}
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        <StatusBar t={t} />
        {children}
        <HomeIndicator t={t} />
      </div>
    </div>
  );
}

function SubHeader({ t, title, right }) {
  return (
    <div style={{ padding: '14px 18px 14px', display: 'flex', alignItems: 'center', gap: 14 }}>
      <div style={{ width: 32, height: 32, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="15 18 9 12 15 6"/>
        </svg>
      </div>
      <div style={{
        flex: 1, fontFamily: t.bodyFont, fontSize: 22, fontWeight: 500,
        color: t.ink, letterSpacing: 0.3, lineHeight: 1,
      }}>{title}</div>
      {right}
    </div>
  );
}

function SubGroupLabel({ t, children, style }) {
  return <div style={{ fontSize: 12, color: t.inkFaint, letterSpacing: 0.6, padding: '0 8px 8px', ...style }}>{children}</div>;
}

function SubCard({ t, children }) {
  return (
    <div style={{ background: t.cardBg || t.surface, border: `1px solid ${t.hair}`, borderRadius: 18, overflow: 'hidden' }}>
      {children}
    </div>
  );
}

function HairDivider({ t, indent = 16 }) {
  return <div style={{ height: 1, background: t.hair, marginLeft: indent }} />;
}

function ValueChip({ t, value }) {
  return (
    <div style={{
      padding: '5px 12px', borderRadius: 999,
      background: t.accentSoft, color: t.accent,
      fontSize: 12, fontWeight: 500, letterSpacing: 0.3,
      display: 'flex', alignItems: 'center', gap: 5, flexShrink: 0,
    }}>
      <span>{value}</span>
      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="6 9 12 15 18 9"/>
      </svg>
    </div>
  );
}

function HeaderPlus({ t }) {
  return (
    <div style={{ width: 32, height: 32, display: 'flex', alignItems: 'center', justifyContent: 'center', color: t.accent }}>
      <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
      </svg>
    </div>
  );
}

function IconCircle({ t, children, size = 32 }) {
  return <div style={{ width: size, height: size, display: 'flex', alignItems: 'center', justifyContent: 'center', color: t.inkSoft, flexShrink: 0 }}>{children}</div>;
}

function IconRow({ t, icon, title, desc, right }) {
  return (
    <div style={{ padding: '14px 14px', display: 'flex', alignItems: 'flex-start', gap: 14 }}>
      {icon && <IconCircle t={t}>{icon}</IconCircle>}
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14.5, color: t.ink, fontWeight: 500, letterSpacing: 0.2, lineHeight: 1.2 }}>{title}</div>
        {desc && <div style={{ marginTop: 4, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.45 }}>{desc}</div>}
      </div>
      {right}
    </div>
  );
}

function XIcon({ kind, color }) {
  const p = { width: 20, height: 20, viewBox: '0 0 24 24', fill: 'none', stroke: color, strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' };
  if (kind === 'mount')  return <svg {...p}><circle cx="12" cy="12" r="3"/><line x1="12" y1="2" x2="12" y2="6"/><line x1="12" y1="18" x2="12" y2="22"/><line x1="2" y1="12" x2="6" y2="12"/><line x1="18" y1="12" x2="22" y2="12"/></svg>;
  if (kind === 'cloud')  return <svg {...p}><path d="M17 18a4 4 0 0 0 1-7.9 6 6 0 0 0-11.8-1A4 4 0 0 0 7 18z"/></svg>;
  if (kind === 'doc')    return <svg {...p}><rect x="6" y="3" width="13" height="18" rx="2"/><line x1="9" y1="8" x2="16" y2="8"/><line x1="9" y1="12" x2="16" y2="12"/></svg>;
  if (kind === 'board')  return <svg {...p}><rect x="3" y="5" width="18" height="14" rx="2"/><line x1="3" y1="9" x2="21" y2="9"/><line x1="7" y1="13" x2="13" y2="13"/></svg>;
  if (kind === 'apps')   return <svg {...p}><rect x="4" y="4" width="6" height="6" rx="1.5"/><rect x="14" y="4" width="6" height="6" rx="1.5"/><rect x="4" y="14" width="6" height="6" rx="1.5"/><rect x="14" y="14" width="6" height="6" rx="1.5"/></svg>;
  if (kind === 'globe')  return <svg {...p}><circle cx="12" cy="12" r="9"/><line x1="3" y1="12" x2="21" y2="12"/><path d="M12 3a13 13 0 0 1 0 18M12 3a13 13 0 0 0 0 18"/></svg>;
  if (kind === 'bubble') return <svg {...p}><path d="M21 12a8 8 0 0 1-12 6.9L4 20l1.1-4A8 8 0 1 1 21 12z"/></svg>;
  if (kind === 'spark')  return <svg {...p}><path d="M12 3l1.6 4.4L18 9l-4.4 1.6L12 15l-1.6-4.4L6 9l4.4-1.6z"/></svg>;
  if (kind === 'folder') return <svg {...p}><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z"/></svg>;
  if (kind === 'braces') return <svg {...p}><path d="M8 3H7a3 3 0 0 0-3 3v3a3 3 0 0 1-2 3 3 3 0 0 1 2 3v3a3 3 0 0 0 3 3h1"/><path d="M16 3h1a3 3 0 0 1 3 3v3a3 3 0 0 0 2 3 3 3 0 0 0-2 3v3a3 3 0 0 1-3 3h-1"/></svg>;
  if (kind === 'server') return <svg {...p}><rect x="3" y="4" width="18" height="6" rx="2"/><rect x="3" y="14" width="18" height="6" rx="2"/></svg>;
  return null;
}

Object.assign(window, { SubShell, SubHeader, SubGroupLabel, SubCard, HairDivider, ValueChip, HeaderPlus, IconCircle, IconRow, XIcon });
