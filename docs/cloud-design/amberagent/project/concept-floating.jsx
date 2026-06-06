// concept-floating.jsx — Exploration concept (not production).
// Input remains a clean capsule. Model / Effort / Context become small
// floating chips below it. Three independent surfaces that can pop in / out
// on demand, instead of being baked into the input bar.

function ConceptFloating({ t, model = 'DeepSeek V4 Pro', effort = 'on', tokensUsed = 144, tokensTotal = 400, openPanel = null }) {
  return (
    <div style={{
      width: 380, height: 832, borderRadius: 44,
      background: t.bg,
      position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont, color: t.ink,
      display: 'flex', flexDirection: 'column',
    }}>
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        <StatusBar t={t} />
        <ConceptHeader t={t} title="亚朵房型对比" />
        <div style={{ flex: 1, overflow: 'hidden', position: 'relative' }}>
          <ConceptBody t={t} />
          <div style={{
            position: 'absolute', left: 0, right: 0, bottom: 0, height: 56,
            background: `linear-gradient(180deg, ${hexToRgba(t.bg, 0)} 0%, ${t.bg} 75%)`,
            pointerEvents: 'none',
          }} />
        </div>
        <InputBar t={t} />
        <FloatingChips t={t} model={model} effort={effort} tokensUsed={tokensUsed} tokensTotal={tokensTotal} openPanel={openPanel} />
        <HomeIndicator t={t} />
      </div>
    </div>
  );
}

function ConceptHeader({ t, title }) {
  return (
    <div style={{
      padding: '18px 20px 12px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14, minWidth: 0, flex: 1 }}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 5, alignItems: 'flex-start', flexShrink: 0 }}>
          <div style={{ width: 22, height: 1.6, background: t.ink, borderRadius: 1 }} />
          <div style={{ width: 14, height: 1.6, background: t.ink, borderRadius: 1 }} />
          <div style={{ width: 19, height: 1.6, background: t.ink, borderRadius: 1 }} />
        </div>
        <span style={{
          fontFamily: t.bodyFont, fontSize: 15, fontWeight: 500,
          color: t.ink, letterSpacing: 0.2, lineHeight: 1.2,
          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        }}>{title}</span>
      </div>
      <div style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0 }}>
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <path d="M20.5 11.3a8 8 0 0 1-.85 3.6 8 8 0 0 1-7.15 4.4 8 8 0 0 1-3.6-.85L3.5 20.5l1.45-5.4a8 8 0 0 1-.85-3.6 8 8 0 0 1 4.4-7.15 8 8 0 0 1 3.6-.85h.45a8 8 0 0 1 7.55 7.55v.45z"/>
          <line x1="12" y1="8.4" x2="12" y2="14.6"/>
          <line x1="8.9" y1="11.5" x2="15.1" y2="11.5"/>
        </svg>
      </div>
    </div>
  );
}

function ConceptBody({ t }) {
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', paddingTop: 6 }}>
      <UserTurn t={t} text="北京三里屯亚朵房型对比一下" />
      <AgentHeader t={t} model="Amber" showRing={false} />
      <Para t={t}>
        我帮你查一下三里屯亚朵的房型信息，对比常用房型的差异。
      </Para>
      <Para t={t} dim>
        参考门店：亚朵 · 三里屯 SOHO 店
      </Para>
    </div>
  );
}

// Row of small floating chips between InputBar and HomeIndicator.
function FloatingChips({ t, model, effort, tokensUsed, tokensTotal, openPanel }) {
  return (
    <div style={{ position: 'relative' }}>
      <div style={{
        padding: '8px 22px 6px 14px',
        display: 'flex', alignItems: 'center',
      }}>
        <ModelChip t={t} name={model} />
        <div style={{ flex: 1 }} />
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', position: 'relative' }}>
          <EffortChip t={t} level={effort} />
          <ContextChip t={t} used={tokensUsed} total={tokensTotal} />
        </div>
      </div>
      {openPanel === 'context' && (
        <ContextPanel t={t} used={tokensUsed} total={tokensTotal} />
      )}
      {openPanel === 'effort' && (
        <EffortPanel t={t} active={effort} />
      )}
    </div>
  );
}

const CHIP_STYLE = (t) => ({
  display: 'inline-flex', alignItems: 'center', gap: 6,
  padding: '5px 11px', borderRadius: 999,
  background: t.surface,
  border: `1px solid ${t.surfaceEdge}`,
  boxShadow: '0 1px 2px rgba(15,20,25,0.04), 0 4px 10px rgba(15,20,25,0.05)',
  fontFamily: t.bodyFont, fontSize: 12, fontWeight: 400,
  color: t.ink, letterSpacing: 0.2, lineHeight: 1.1,
  flexShrink: 0,
});

function ModelChip({ t, name }) {
  return (
    <div style={{
      ...CHIP_STYLE(t),
      padding: '5px 12px',
    }}>
      <span style={{ fontWeight: 400, fontSize: 12.5, letterSpacing: 0.2 }}>{name}</span>
    </div>
  );
}

// Effort chip: round chip with just a "thinking" icon (brain glyph).
function EffortChip({ t, level }) {
  return (
    <div style={{
      width: 26, height: 26, borderRadius: '50%',
      background: t.surface,
      border: `1px solid ${t.surfaceEdge}`,
      boxShadow: '0 1px 2px rgba(15,20,25,0.04), 0 4px 10px rgba(15,20,25,0.05)',
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      flexShrink: 0,
    }}>
      {/* lightbulb-ish thinking glyph */}
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke={t.accent} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <path d="M9 18h6"/>
        <path d="M10 22h4"/>
        <path d="M12 2a7 7 0 0 0-4 12.7c.7.6 1 1.4 1 2.3v1h6v-1c0-.9.3-1.7 1-2.3A7 7 0 0 0 12 2z"/>
      </svg>
    </div>
  );
}

// Context chip: round chip with just the donut ring (no numbers).
function ContextChip({ t, used, total }) {
  const v = total > 0 ? used / total : 0;
  const color = v <= 0.001 ? '#D6D9DE' : v < 0.5 ? t.accent : v < 0.75 ? '#E6A23C' : '#D9534F';
  const size = 14, stroke = 2;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const dash = c * v;
  return (
    <div style={{
      width: 26, height: 26, borderRadius: '50%',
      background: t.surface,
      border: `1px solid ${t.surfaceEdge}`,
      boxShadow: '0 1px 2px rgba(15,20,25,0.04), 0 4px 10px rgba(15,20,25,0.05)',
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      flexShrink: 0,
    }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} style={{ display: 'block' }}>
        <circle cx={size/2} cy={size/2} r={r} fill="none" stroke="rgba(15,20,25,0.12)" strokeWidth={stroke} />
        {v > 0 && (
          <circle cx={size/2} cy={size/2} r={r} fill="none" stroke={color} strokeWidth={stroke}
            strokeDasharray={`${dash} ${c}`} strokeLinecap="round"
            transform={`rotate(-90 ${size/2} ${size/2})`} />
        )}
      </svg>
    </div>
  );
}

window.ConceptFloating = ConceptFloating;

// ──────────────────────────────────────────────────────────────
// Floating panels — appear above the chip row when a chip is tapped.

const PANEL_STYLE = (t) => ({
  position: 'absolute',
  bottom: 'calc(100% - 4px)',
  right: 22,
  width: 280,
  background: t.surface,
  border: `1px solid ${t.surfaceEdge}`,
  borderRadius: 18,
  boxShadow: '0 4px 12px rgba(15,20,25,0.06), 0 14px 36px rgba(15,20,25,0.12)',
  padding: '14px 16px 14px',
  fontFamily: t.bodyFont,
});

// Tiny arrow tail anchored to the chip below
function PanelTail({ t, offsetRight = 22 }) {
  return (
    <div style={{
      position: 'absolute',
      bottom: -7, right: offsetRight,
      width: 14, height: 14,
      background: t.surface,
      border: `1px solid ${t.surfaceEdge}`,
      borderTop: 'none', borderLeft: 'none',
      transform: 'rotate(45deg)',
      borderBottomRightRadius: 3,
    }} />
  );
}

function ContextPanel({ t, used, total }) {
  const pctUsed = total > 0 ? used / total : 0;
  return (
    <div style={PANEL_STYLE(t)}>
      <PanelTail t={t} offsetRight={8} />
      {/* header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
        <span style={{ fontSize: 13, color: t.inkSoft, letterSpacing: 0.3 }}>用量与上下文</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4, color: t.inkSoft, fontSize: 11.5, letterSpacing: 0.3 }}>
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="23 4 23 10 17 10"/><path d="M20.5 15A9 9 0 1 1 19.4 6.4L23 10"/>
          </svg>
          <span>刷新</span>
        </div>
      </div>

      <QuotaRow t={t} label="5 小时额度"   value={0.46} caption="46 / 100 次" />
      <QuotaRow t={t} label="本周额度"     value={0.18} caption="450 / 2,500 次" />
      <div style={{ height: 1, background: t.hair, margin: '12px 0 10px' }} />
      <QuotaRow t={t}
        label="Context"
        value={pctUsed}
        caption={`${used}K used / ${total}K`}
        valueColor={pctUsed < 0.5 ? t.accent : pctUsed < 0.75 ? '#E6A23C' : '#D9534F'}
      />
    </div>
  );
}

function QuotaRow({ t, label, value, caption, valueColor }) {
  const v = Math.max(0, Math.min(1, value));
  const color = valueColor || t.accent;
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
        <div style={{
          width: `${v * 100}%`, height: '100%',
          background: color, borderRadius: 999,
        }} />
      </div>
    </div>
  );
}

function EffortPanel({ t, active = 'on' }) {
  const levels = [
    { id: 'off', label: 'off' },
    { id: 'on',  label: 'on' },
    { id: 'max', label: 'max' },
  ];
  const descMap = {
    off: '不进行额外思考，最低延迟',
    on:  '常规思考，平衡延迟与质量',
    max: '深度思考，最高质量',
  };
  return (
    <div style={{ ...PANEL_STYLE(t), width: 240 }}>
      <PanelTail t={t} offsetRight={36} />
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        marginBottom: 10,
      }}>
        <span style={{ fontSize: 13, color: t.inkSoft, letterSpacing: 0.3 }}>思考程度</span>
        <span style={{
          fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace',
          fontSize: 12, color: t.accent, letterSpacing: 0.4,
        }}>{active}</span>
      </div>
      {/* horizontal segmented control — same language as the model picker */}
      <div style={{
        display: 'flex', gap: 3, padding: 3,
        borderRadius: 999,
        background: t.segmentTrack || 'rgba(15,20,25,0.04)',
        border: `1px solid ${t.hair}`,
      }}>
        {levels.map(l => {
          const isActive = l.id === active;
          return (
            <div key={l.id} style={{
              flex: 1, padding: '6px 6px', borderRadius: 999,
              background: isActive ? t.accent : 'transparent',
              color: isActive ? (t.segmentActiveInk || '#FFFFFF') : t.ink,
              fontSize: 12.5, fontWeight: 500, letterSpacing: 0.3,
              fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace',
              textAlign: 'center', lineHeight: 1, whiteSpace: 'nowrap',
            }}>
              {l.label}
            </div>
          );
        })}
      </div>
      {/* helper text */}
      <div style={{
        fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.2,
        lineHeight: 1.45, marginTop: 10,
      }}>{descMap[active]}</div>
    </div>
  );
}
