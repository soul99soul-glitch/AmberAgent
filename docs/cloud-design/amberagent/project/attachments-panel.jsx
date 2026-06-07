// attachments-panel.jsx — 3 design variants for the "拍照 / 照片 / 上传文件"
// panel that opens when tapping the + button. Original design uses square
// cards which clash with the capsule input bar; these variants align with
// the capsule's curve language.

// ── A · Single capsule strip with internal dividers
function AttachmentsCapsuleStrip({ t }) {
  return (
    <div style={{
      margin: '8px 14px 0',
      borderRadius: 999,
      background: t.surface,
      border: `1px solid ${t.surfaceEdge}`,
      boxShadow: t.surfaceShadow || 'none',
      display: 'flex', overflow: 'hidden',
    }}>
      {['拍照', '照片', '上传文件'].map((label, i) => (
        <React.Fragment key={label}>
          {i > 0 && <div style={{ width: 1, background: t.hair }} />}
          <div style={{
            flex: 1, padding: '14px 8px',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            color: t.ink, fontFamily: t.bodyFont,
            fontSize: 14, letterSpacing: 0.2,
          }}>
            <AttachIcon kind={['camera', 'image', 'file'][i]} color={t.inkSoft} />
            <span>{label}</span>
          </div>
        </React.Fragment>
      ))}
    </div>
  );
}

// ── B · Round icon row, label below
function AttachmentsIconRow({ t }) {
  return (
    <div style={{
      margin: '12px 14px 0',
      display: 'flex', justifyContent: 'space-around',
      padding: '4px 12px 8px',
    }}>
      {[
        { label: '拍照',     kind: 'camera' },
        { label: '照片',     kind: 'image' },
        { label: '上传文件', kind: 'file' },
      ].map(item => (
        <div key={item.label} style={{
          display: 'flex', flexDirection: 'column',
          alignItems: 'center', gap: 8,
        }}>
          <div style={{
            width: 52, height: 52, borderRadius: '50%',
            background: t.surface,
            border: `1px solid ${t.surfaceEdge}`,
            boxShadow: t.surfaceShadow || 'none',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: t.ink,
          }}>
            <AttachIcon kind={item.kind} color={t.ink} size={22} />
          </div>
          <span style={{
            fontSize: 12, color: t.inkSoft,
            fontFamily: t.bodyFont, letterSpacing: 0.2,
          }}>{item.label}</span>
        </div>
      ))}
    </div>
  );
}

// ── C · Big-radius panel "growing" from the capsule
function AttachmentsBigRadiusPanel({ t }) {
  return (
    <div style={{
      margin: '6px 14px 0',
      borderRadius: 28,
      background: t.surface,
      border: `1px solid ${t.surfaceEdge}`,
      boxShadow: t.surfaceShadow || 'none',
      padding: '14px 6px',
      display: 'flex', gap: 4,
    }}>
      {[
        { label: '拍照', kind: 'camera' },
        { label: '照片', kind: 'image' },
        { label: '上传文件', kind: 'file' },
      ].map(item => (
        <div key={item.label} style={{
          flex: 1, padding: '10px 6px', borderRadius: 20,
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6,
          color: t.ink, fontFamily: t.bodyFont,
        }}>
          <AttachIcon kind={item.kind} color={t.ink} size={22} />
          <span style={{ fontSize: 12, color: t.inkSoft, letterSpacing: 0.2 }}>{item.label}</span>
        </div>
      ))}
    </div>
  );
}

function AttachIcon({ kind, color, size = 19 }) {
  const p = { width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: color, strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' };
  if (kind === 'camera') return (
    <svg {...p}>
      <path d="M3 7h3l2-3h8l2 3h3v12H3z"/>
      <circle cx="12" cy="13" r="4"/>
    </svg>
  );
  if (kind === 'image') return (
    <svg {...p}>
      <rect x="3" y="4" width="18" height="16" rx="2"/>
      <circle cx="8.5" cy="9.5" r="1.5"/>
      <path d="M21 16l-5-5L7 21"/>
    </svg>
  );
  if (kind === 'file') return (
    <svg {...p}>
      <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/>
      <polyline points="14 3 14 8 19 8"/>
    </svg>
  );
  return null;
}

function AttachmentsDemo({ t, variant }) {
  return (
    <div style={{
      width: 380, height: 832, borderRadius: 44,
      background: t.bg, position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont, color: t.ink,
      display: 'flex', flexDirection: 'column',
    }}>
      {t.halo && <div aria-hidden style={{ position: 'absolute', inset: 0, pointerEvents: 'none', background: t.halo }} />}
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        <StatusBar t={t} />
        <Header t={t} />
        <div style={{ flex: 1 }} />
        <ExpandedInputBar t={t} />
        {variant === 'A' && <AttachmentsCapsuleStrip t={t} />}
        {variant === 'B' && <AttachmentsIconRow t={t} />}
        {variant === 'C' && <AttachmentsBigRadiusPanel t={t} />}
        <HomeIndicator t={t} />
      </div>
    </div>
  );
}

function ExpandedInputBar({ t }) {
  return (
    <div style={{ padding: '0 14px 0' }}>
      <div style={{
        height: 68, borderRadius: 999,
        background: t.surface,
        border: `1px solid ${t.surfaceEdge}`,
        boxShadow: t.surfaceShadow || 'none',
        display: 'flex', alignItems: 'center',
        padding: '0 10px 0 20px', gap: 8,
      }}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
        <div style={{ width: 1, height: 20, background: t.hair, marginLeft: 8, marginRight: 6 }} />
        <span style={{ flex: 1, color: t.inkFaint, fontSize: 15, fontFamily: t.bodyFont, letterSpacing: 0.2 }}>输入消息</span>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <line x1="17" y1="5" x2="7" y2="19"/>
        </svg>
        <SendButton t={t} />
      </div>
    </div>
  );
}

window.AttachmentsCapsuleStrip = AttachmentsCapsuleStrip;
window.AttachmentsIconRow = AttachmentsIconRow;
window.AttachmentsBigRadiusPanel = AttachmentsBigRadiusPanel;
window.AttachmentsDemo = AttachmentsDemo;
