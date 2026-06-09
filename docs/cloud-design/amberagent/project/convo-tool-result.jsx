// convo-tool-result.jsx — Tool-result preview row.
// Compact preview thumbnail (left) + status-indicator pill with pagination
// (right). Sits inline in the chat stream like ToolCard.

function ToolResultPreview({ t, tool = '网页搜索', query = '亚朵酒店', page = 1, total = 1 }) {
  return (
    <div style={{
      padding: '10px 32px 8px 14px',
      display: 'flex', gap: 10, alignItems: 'flex-end',
    }}>
      <PreviewThumb t={t} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <ResultPill t={t} tool={tool} query={query} page={page} total={total} />
      </div>
    </div>
  );
}

function PreviewThumb({ t }) {
  return (
    <div style={{
      width: 72, height: 96, borderRadius: 8,
      background: t.previewBg || '#FFFFFF',
      border: `1px solid ${t.previewEdge || t.hair}`,
      boxShadow: t.previewShadow || '0 2px 6px rgba(15,20,25,0.06)',
      overflow: 'hidden', flexShrink: 0,
      display: 'flex', flexDirection: 'column',
      fontFamily: t.bodyFont,
    }}>
      <FakeBrowser t={t} />
    </div>
  );
}

// Tiny, vibe-only browser mock — readable as "webpage" without being literal.
function FakeBrowser({ t }) {
  const dim = t.previewDim || 'rgba(15,20,25,0.08)';
  const dimSoft = t.previewDimSoft || 'rgba(15,20,25,0.04)';
  return (
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column' }}>
      {/* brand wordmark */}
      <div style={{
        padding: '6px 7px 3px', fontSize: 8, fontWeight: 700,
        color: t.previewBrand || '#4285F4', letterSpacing: 0.3,
        lineHeight: 1,
      }}>Google</div>
      {/* search input */}
      <div style={{
        margin: '0 7px', height: 11, borderRadius: 6,
        background: dimSoft, border: `0.5px solid ${dim}`,
        display: 'flex', alignItems: 'center', padding: '0 4px', gap: 3,
      }}>
        <div style={{ width: 3, height: 3, borderRadius: '50%', border: `0.5px solid ${t.inkSoft}`, opacity: 0.6 }} />
        <div style={{ flex: 1, height: 2, background: dim, borderRadius: 1 }} />
      </div>
      {/* tab strip */}
      <div style={{ display: 'flex', gap: 4, padding: '5px 7px 4px', fontSize: 5.5, lineHeight: 1 }}>
        <span style={{ color: t.inkFaint }}>AI</span>
        <span style={{ color: t.accent, fontWeight: 700 }}>全部</span>
        <span style={{ color: t.inkFaint }}>图片</span>
        <span style={{ color: t.inkFaint }}>购物</span>
      </div>
      {/* result block 1 */}
      <div style={{ padding: '2px 7px 4px', display: 'flex', flexDirection: 'column', gap: 2 }}>
        <div style={{ height: 2, width: '45%', background: t.accent, borderRadius: 1, opacity: 0.85 }} />
        <div style={{ height: 1.5, width: '90%', background: dim, borderRadius: 1 }} />
        <div style={{ height: 1.5, width: '80%', background: dim, borderRadius: 1 }} />
      </div>
      {/* divider */}
      <div style={{ height: 0.5, background: dimSoft, margin: '2px 7px' }} />
      {/* result block 2 */}
      <div style={{ padding: '2px 7px', display: 'flex', flexDirection: 'column', gap: 2 }}>
        <div style={{ height: 2, width: '38%', background: t.accent, borderRadius: 1, opacity: 0.7 }} />
        <div style={{ height: 1.5, width: '85%', background: dim, borderRadius: 1 }} />
      </div>
    </div>
  );
}

function ResultPill({ t, tool, query, page, total }) {
  const badgeBg = t.toolDoneBg || t.accent;
  const badgeInk = t.toolDoneBadgeInk || '#FFFFFF';
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 8,
      width: '100%',
      padding: '3px 10px 3px 3px',
      borderRadius: 999,
      background: t.toolPillBg || '#F4F4F4',
      border: `1px solid ${t.toolPillEdge || t.hair}`,
      fontFamily: t.bodyFont,
    }}>
      {/* status badge */}
      <div style={{
        width: 16, height: 16, borderRadius: '50%',
        background: badgeBg,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke={badgeInk} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="5 12 10 17 19 7"/>
        </svg>
      </div>
      {/* tool · query */}
      <span style={{
        flex: 1, minWidth: 0,
        fontSize: 11.5, letterSpacing: 0.2, fontWeight: 500,
        color: t.toolLabelInk || t.ink,
        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
      }}>
        {tool}<span style={{ color: t.toolNameInk || t.inkSoft, fontWeight: 400 }}> {query}</span>
      </span>
      {/* pagination */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, color: t.inkSoft, flexShrink: 0 }}>
        <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="15 18 9 12 15 6"/>
        </svg>
        <span style={{ fontSize: 10.5, letterSpacing: 0.4, fontVariantNumeric: 'tabular-nums' }}>{page}/{total}</span>
        <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="9 18 15 12 9 6"/>
        </svg>
      </div>
    </div>
  );
}

window.ToolResultPreview = ToolResultPreview;
