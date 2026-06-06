// convo-agent.jsx — Agent header, thinking strip, tool-call card, body text.

function AgentHeader({ t, model = 'Amber', tokensUsed = 144, tokensTotal = 400, ringOpen = false, showRing = true }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'baseline', gap: 8,
      padding: '4px 20px 14px',
    }}>
      <span style={{
        fontFamily: t.bodyFont, fontSize: 17, fontWeight: 500,
        color: t.ink, letterSpacing: 0.2, lineHeight: 1,
      }}>{model}</span>
      {showRing && <ContextRing t={t} used={tokensUsed} total={tokensTotal} initialOpen={ringOpen} />}
    </div>
  );
}

// Tiny ring + click-to-reveal token count. Thresholds:
//   0%        → gray (empty)
//   0-50%     → blue
//   50-75%    → amber
//   75-100%   → red
function ContextRing({ t, used = 0, total = 1, unit = 'k', initialOpen = false }) {
  const [open, setOpen] = React.useState(initialOpen);
  const pct = total > 0 ? used / total : 0;
  const v = Math.max(0, Math.min(1, pct));
  const empty = v <= 0.001;
  const color = empty
    ? (t.contextEmpty || '#D6D9DE')
    : v < 0.5
      ? (t.contextLow || '#3D8FD4')
      : v < 0.75
        ? (t.contextMid || '#E6A23C')
        : (t.contextHigh || '#D9534F');
  const trackColor = t.contextTrack || 'rgba(15,20,25,0.10)';
  const size = 12;
  const stroke = 1.8;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const dash = empty ? c : c * v;
  return (
    <div
      onClick={(e) => { e.stopPropagation(); setOpen(o => !o); }}
      title={`context ${Math.round(v * 100)}%`}
      style={{
        display: 'inline-flex', alignItems: 'center', gap: 6,
        cursor: 'pointer', userSelect: 'none',
      }}>
      <div style={{ display: 'inline-block', width: size, height: size }}>
        <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ display: 'block' }}>
          <circle cx={size/2} cy={size/2} r={r}
            fill="none" stroke={empty ? color : trackColor} strokeWidth={stroke} />
          {!empty && (
            <circle cx={size/2} cy={size/2} r={r}
              fill="none" stroke={color} strokeWidth={stroke}
              strokeDasharray={`${dash} ${c}`}
              strokeLinecap="round"
              transform={`rotate(-90 ${size/2} ${size/2})`}
            />
          )}
        </svg>
      </div>
      {open && (
        <span style={{
          fontFamily: t.bodyFont, fontSize: 12,
          color: t.inkFaint, letterSpacing: 0.3, lineHeight: 1,
          fontVariantNumeric: 'tabular-nums',
        }}>
          {used}{unit}/{total}{unit}
        </span>
      )}
    </div>
  );
}

// Reasoning strip — thin left rule + dim text. Same visual on both states;
// expanded reveals indented thought content under the header line.
function ThinkingStrip({ t, seconds = '5.4', mode = 'auto', expanded = false, thoughts }) {
  const defaultThoughts = [
    '用户说"随便聊"，意思是把选择权交给我。',
    '想找个既轻松又有嚼劲的题目——「AI 助手该像工具还是朋友」最近在产品圈讨论得多，挺有共鸣。',
    '设四个席位：实用派、共情派、警惕派、解构派。让他们直接辩，比我自己讲有意思。',
  ];
  const lines = thoughts || defaultThoughts;
  return (
    <div style={{
      margin: '0 20px 14px',
      paddingLeft: 14,
      position: 'relative',
      fontFamily: t.bodyFont,
    }}>
      {/* vertical accent rule */}
      <div style={{
        position: 'absolute', top: 4, bottom: 4, left: 0,
        width: 2, borderRadius: 1,
        background: t.thinkRule || t.hair,
      }} />
      {/* header line */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        color: t.thinkHeaderInk || t.inkSoft, fontSize: 13, letterSpacing: 0.2,
        lineHeight: 1.4,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span>思考了 {seconds} 秒</span>
          <span style={{ opacity: 0.5 }}>·</span>
          <span style={{ opacity: 0.85 }}>{mode}</span>
        </div>
        <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke={t.thinkHeaderInk || t.inkSoft} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"
          style={{ transform: expanded ? 'rotate(180deg)' : 'rotate(0)', transition: 'transform .2s' }}>
          <polyline points="6 9 12 15 18 9"/>
        </svg>
      </div>
      {/* expanded body */}
      {expanded && (
        <div style={{
          marginTop: 10,
          color: t.thinkInk || t.inkSoft,
          fontSize: 13.5, lineHeight: 1.7,
          letterSpacing: 0.2,
          display: 'flex', flexDirection: 'column', gap: 8,
        }}>
          {lines.map((l, i) => <div key={i}>{l}</div>)}
        </div>
      )}
    </div>
  );
}

// Tool call — slim pill, single line. "完成" uses theme tokens (no green badge).
function ToolCard({ t, name = 'tool_search', status = '完成' }) {
  // pill bg / done ink: prefer explicit theme tokens; fall back to accent system
  const pillBg = t.toolPillBg || t.accentSoft;
  const isBlueBg = !pillBg || pillBg === t.accentSoft;
  // If pill background is white-ish, use light-blue ink for status. If pill bg
  // is the tinted-blue accentSoft, use white for status to keep contrast.
  const doneInk = t.toolDoneInk || (isBlueBg ? '#FFFFFF' : t.accent);
  // Actually the user spec: blue pill → white "完成"; white pill → light blue "完成"
  // So compute based on luminance of pillBg.
  const useWhite = isPillTinted(pillBg);
  const doneColor = t.toolDoneInk || (useWhite ? '#FFFFFF' : t.accent);
  const labelColor = t.toolLabelInk || t.accent;
  const iconColor = t.toolIconInk || t.accent;
  const badgeBg = t.toolDoneBg || t.accent;
  const badgeInk = t.toolDoneBadgeInk || '#FFFFFF';
  return (
    <div style={{ margin: '4px 20px 14px' }}>
      <div style={{
        display: 'inline-flex', alignItems: 'center', gap: 10,
        padding: '6px 8px 6px 12px',
        borderRadius: 999,
        background: pillBg,
        border: t.toolPillEdge ? `1px solid ${t.toolPillEdge}` : 'none',
        fontFamily: t.bodyFont,
        maxWidth: '100%',
      }}>
        {/* wrench icon */}
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke={iconColor} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M14.7 6.3a4 4 0 0 0-5.3 5.3L3.7 17.3a1.8 1.8 0 0 0 2.5 2.5l5.7-5.7a4 4 0 0 0 5.3-5.3L15 10.5 13.5 9 16 6.3z"/>
        </svg>
        <span style={{ fontSize: 13, color: labelColor, letterSpacing: 0.3, fontWeight: 500 }}>
          调用工具 <span style={{ fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace', fontSize: 12.5, fontWeight: 400, opacity: 0.85, color: t.toolNameInk || t.inkSoft }}>· {name}</span>
        </span>
        {/* filled check circle — status as a graphic, not a word */}
        <div style={{
          width: 18, height: 18, borderRadius: '50%',
          background: badgeBg,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          flexShrink: 0,
        }}>
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke={badgeInk} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="5 12 10 17 19 7"/>
          </svg>
        </div>
      </div>
    </div>
  );
}

// Crude luminance check: returns true if the pill bg is dark enough that
// status text needs to be white. We treat the tinted "accentSoft" (always
// a pale tint of accent) as "blue", and white-ish surfaces as "white".
function isPillTinted(bg) {
  if (!bg) return true;
  if (bg === '#FFFFFF' || bg === '#fff' || bg.toLowerCase() === 'white') return false;
  if (bg.startsWith('rgba')) return true;
  if (bg.startsWith('#')) {
    const n = parseInt(bg.slice(1), 16);
    const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255;
    return (r + g + b) / 3 < 240; // anything not near-white is "tinted"
  }
  return true;
}

function Para({ t, children, dim }) {
  return (
    <div style={{
      padding: '0 20px 14px',
      fontFamily: t.bodyFont, fontSize: 15, lineHeight: 1.65,
      color: dim ? t.inkSoft : t.ink, letterSpacing: 0.2,
    }}>{children}</div>
  );
}

window.AgentHeader = AgentHeader;
window.ThinkingStrip = ThinkingStrip;
window.ToolCard = ToolCard;
window.Para = Para;
window.AgentActions = AgentActions;

// Agent reply footer — copy / retry / TTS / menu. Sits below the final
// paragraph of an assistant turn. Token / speed / hit-rate metadata is no
// longer shown here; tap the context ring in the top-right for that.
function AgentActions({ t }) {
  const stroke = t.inkSoft;
  const items = [
    // copy
    <svg key="copy" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <rect x="9" y="9" width="12" height="12" rx="2.5"/>
      <path d="M5 15V5a2 2 0 0 1 2-2h10"/>
    </svg>,
    // retry
    <svg key="retry" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="20 5 20 11 14 11"/>
      <path d="M19 11a8 8 0 1 0-2.2 5.5"/>
    </svg>,
    // tts (speaker waves)
    <svg key="tts" width="17" height="17" viewBox="0 0 24 24" fill="none" stroke={stroke} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M11 5L6 9H3v6h3l5 4z"/>
      <path d="M16 9a4 4 0 0 1 0 6"/>
      <path d="M19.5 6.5a8 8 0 0 1 0 11"/>
    </svg>,
    // menu (three dots)
    <svg key="menu" width="17" height="17" viewBox="0 0 24 24" fill={stroke}>
      <circle cx="5" cy="12" r="1.7"/>
      <circle cx="12" cy="12" r="1.7"/>
      <circle cx="19" cy="12" r="1.7"/>
    </svg>,
  ];
  return (
    <div style={{ padding: '0 20px 14px', display: 'flex', gap: 20, alignItems: 'center', color: stroke }}>
      {items}
    </div>
  );
}

// Header context ring — refined donut: confident stroke, soft accent glow,
// a small leading "head" dot on the progress arc, plus a usage panel that
// pops up on tap. Familiar pattern, polished execution.
function HeaderContextRing({ t, used = 0, total = 1, initialOpen = false }) {
  const [open, setOpen] = React.useState(initialOpen);
  const v = Math.max(0, Math.min(1, total > 0 ? used / total : 0));
  const empty = v <= 0.001;
  const color = empty
    ? (t.contextEmpty || '#D6D9DE')
    : v < 0.5
      ? (t.contextLow || '#3D8FD4')
      : v < 0.75
        ? (t.contextMid || '#E6A23C')
        : (t.contextHigh || '#D9534F');
  const trackColor = t.contextTrack || 'rgba(15,20,25,0.10)';

  const size = 22;
  const stroke = 2.6;
  const cx = size / 2, cy = size / 2;
  const r = (size - stroke) / 2 - 1; // leave 1px for the outer halo
  const c = 2 * Math.PI * r;
  const dash = empty ? 0 : c * v;
  const headAngle = (v * 2 - 0.5) * Math.PI;
  const headX = cx + r * Math.cos(headAngle);
  const headY = cy + r * Math.sin(headAngle);

  return (
    <div style={{ position: 'relative' }}>
      <div
        onClick={(e) => { e.stopPropagation(); setOpen(o => !o); }}
        style={{
          width: size, height: size, cursor: 'pointer', userSelect: 'none',
          display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
        }}>
        <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ display: 'block' }}>
          <circle cx={cx} cy={cy} r={r} fill="none" stroke={trackColor} strokeWidth={stroke} />
          {!empty && (
            <circle cx={cx} cy={cy} r={r}
              fill="none" stroke={color} strokeWidth={stroke}
              strokeDasharray={`${dash} ${c}`} strokeLinecap="round"
              transform={`rotate(-90 ${cx} ${cy})`}
            />
          )}
          {!empty && v < 0.999 && (
            <circle cx={headX} cy={headY} r={stroke / 2 + 0.4} fill={color} />
          )}
        </svg>
      </div>
      {open && <ContextUsagePanel t={t} used={used} total={total} progressColor={color} />}
    </div>
  );
}

// Detailed usage panel anchored to the header ring (top-right of screen).
function ContextUsagePanel({ t, used, total, progressColor }) {
  const v = total > 0 ? used / total : 0;
  return (
    <div style={{
      position: 'absolute',
      top: 'calc(100% + 10px)',
      right: -8,
      width: 290,
      background: t.popoverBg || t.cardBg || '#FFFFFF',
      border: `1px solid ${t.surfaceEdge}`,
      borderRadius: 18,
      boxShadow: '0 4px 12px rgba(15,20,25,0.10), 0 14px 36px rgba(15,20,25,0.20)',
      padding: '14px 16px 14px',
      fontFamily: t.bodyFont, zIndex: 20,
    }}>
      {/* arrow tail pointing up to the ring */}
      <div style={{
        position: 'absolute', top: -7, right: 14,
        width: 14, height: 14,
        background: t.popoverBg || t.cardBg || '#FFFFFF',
        border: `1px solid ${t.surfaceEdge}`,
        borderBottom: 'none', borderRight: 'none',
        transform: 'rotate(45deg)',
        borderTopLeftRadius: 3,
      }} />

      <div style={{ fontSize: 13, color: t.inkSoft, letterSpacing: 0.3, marginBottom: 10 }}>用量与上下文</div>

      <UsageRow t={t} label="5 小时额度" value={0.46} caption="46 / 100 次" />
      <UsageRow t={t} label="本周额度"   value={0.18} caption="450 / 2,500 次" />

      <div style={{ height: 1, background: t.hair, margin: '12px 0 10px' }} />

      <UsageRow t={t} label="Context" value={v} caption={`${used}K / ${total}K`} color={progressColor} />

      {/* response stats — quiet meta strip */}
      <div style={{
        marginTop: 14, paddingTop: 12,
        borderTop: `1px solid ${t.hair}`,
        display: 'flex', flexWrap: 'wrap', gap: '10px 18px',
        fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.3,
        fontVariantNumeric: 'tabular-nums',
      }}>
        <span><span style={{ color: t.inkSoft }}>本次</span> 2,420 tok</span>
        <span><span style={{ color: t.inkSoft }}>缓存命中</span> 68%</span>
        <span><span style={{ color: t.inkSoft }}>速度</span> 78 tok/s</span>
      </div>
    </div>
  );
}

function UsageRow({ t, label, value, caption, color }) {
  const v = Math.max(0, Math.min(1, value));
  const fill = color || t.accent;
  return (
    <div style={{ marginBottom: 10 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 6 }}>
        <span style={{ fontSize: 13, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>{label}</span>
        <span style={{ fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.3, fontVariantNumeric: 'tabular-nums' }}>{caption}</span>
      </div>
      <div style={{
        height: 5, borderRadius: 999,
        background: 'rgba(15,20,25,0.06)',
        overflow: 'hidden',
      }}>
        <div style={{ width: `${v * 100}%`, height: '100%', background: fill, borderRadius: 999 }} />
      </div>
    </div>
  );
}

window.HeaderContextRing = HeaderContextRing;
