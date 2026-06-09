// convo-ask.jsx — "询问 N 个问题" inline form, themed.
//
// Questions data shape:
//   [{ q: string, options: string[] }, ...]
//
// Visual: a slim accent-colored title row with chevron, then questions
// stacked below, each with capsule-pill options that wrap.
// Sits in the chat stream like ToolCard does — no chrome around it.

function AskUserCard({ t, count, questions, expanded = true }) {
  const n = count != null ? count : (questions ? questions.length : 0);
  return (
    <div style={{ margin: '4px 20px 14px', fontFamily: t.bodyFont }}>
      {/* title bar */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 10,
        color: t.accent,
        marginBottom: expanded ? 14 : 0,
      }}>
        {/* ? glyph in a circle */}
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="10" />
          <path d="M9.4 9a2.6 2.6 0 0 1 5 0c0 1.5-2 2-2.4 3" />
          <line x1="12" y1="16.5" x2="12" y2="16.6" />
        </svg>
        <span style={{ flex: 1, fontSize: 14.5, fontWeight: 500, letterSpacing: 0.2 }}>
          询问 {n} 个问题
        </span>
        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
          style={{ transform: expanded ? 'rotate(180deg)' : 'rotate(0)', transition: 'transform .2s' }}>
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </div>

      {expanded && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          {(questions || []).map((q, qi) => (
            <AskQuestion key={qi} t={t} q={q} hairlineTop={qi > 0} />
          ))}
        </div>
      )}
    </div>
  );
}

function AskQuestion({ t, q, hairlineTop }) {
  return (
    <div>
      {hairlineTop && (
        <div style={{ height: 1, background: t.hair, marginBottom: 16 }} />
      )}
      <div style={{
        fontFamily: t.bodyFont, fontSize: 14.5,
        color: t.ink, letterSpacing: 0.2, marginBottom: 12, lineHeight: 1.5,
      }}>{q.q}</div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
        {(q.options || []).map((opt, oi) => (
          <button key={oi} style={{
            background: t.askOptBg || t.surface,
            border: `1px solid ${t.askOptEdge || t.hair}`,
            borderRadius: 999,
            padding: '8px 14px',
            fontFamily: t.bodyFont, fontSize: 13.5,
            color: t.ink, letterSpacing: 0.2, lineHeight: 1.3,
            cursor: 'pointer',
            boxShadow: t.askOptShadow || 'none',
          }}>{opt}</button>
        ))}
      </div>
    </div>
  );
}

window.AskUserCard = AskUserCard;
