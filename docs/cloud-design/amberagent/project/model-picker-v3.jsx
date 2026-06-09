// model-picker-v3.jsx — Fresh structure: hero + list.
// The currently-used model lives in a top hero card with breathing room for
// the effort selector. The list below is uniform compact rows — no special
// active treatment, no mixed heights, no per-row effort cramming.

const V3_MODELS = [
  { id: 'deepseek-v4-flash', provider: 'DeepSeek',  logo: 'D', name: 'deepseek-v4-flash', caps: ['对话','工具'] },
  { id: 'deepseek-v4-pro',   provider: 'DeepSeek',  logo: 'D', name: 'deepseek-v4-pro',   caps: ['对话','工具','视觉'], fav: true,
    levels: [{ id: 'off' }, { id: 'on' }, { id: 'max' }], defaultLevel: 'on' },
  { id: 'kimi-for-coding',   provider: 'Kimi',      logo: 'K', name: 'kimi-for-coding',   caps: ['对话','工具'],
    levels: [{ id: 'off' }, { id: 'on' }], defaultLevel: 'on' },
  { id: 'glm-5.1',           provider: '智谱 GLM',   logo: 'G', name: 'glm-5.1',           caps: ['对话','工具','视觉'],
    levels: [{ id: 'off' }, { id: 'on' }], defaultLevel: 'off' },
  { id: 'glm-4.7',           provider: '智谱 GLM',   logo: 'G', name: 'glm-4.7',           caps: ['对话','工具'] },
  { id: 'gpt-5',             provider: 'OpenAI',     logo: 'O', name: 'gpt-5',             caps: ['对话','工具','视觉'],
    levels: [{ id: 'low' }, { id: 'med', label:'medium' }, { id: 'high' }, { id: 'xhigh' }], defaultLevel: 'med' },
  { id: 'claude-opus-4.7',   provider: 'Anthropic',  logo: 'A', name: 'claude-opus-4.7',   caps: ['对话','工具','视觉'],
    levels: [{ id: 'low' }, { id: 'med', label:'medium' }, { id: 'high' }, { id: 'xhigh' }, { id: 'max' }],
    defaultLevel: 'high' },
];

function ModelPickerV3({ t, activeModelId = 'deepseek-v4-pro', activeProvider = '全部' }) {
  const active = V3_MODELS.find(m => m.id === activeModelId) || V3_MODELS[0];
  const others = V3_MODELS.filter(m => m.id !== active.id);
  const providers = ['全部', ...new Set(V3_MODELS.map(m => m.provider))];

  return (
    <div style={{
      width: 380, height: 832, borderRadius: 44,
      background: t.bg, position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont, color: t.ink,
      display: 'flex', flexDirection: 'column',
    }}>
      <div style={{ position: 'absolute', inset: 0, background: t.sheetBackdrop || 'rgba(15,20,25,0.18)' }} />
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0,
        height: 740, background: t.bg,
        borderTopLeftRadius: 28, borderTopRightRadius: 28,
        boxShadow: '0 -8px 32px rgba(15,20,25,0.10)',
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
      }}>
        <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 4px' }}>
          <div style={{ width: 40, height: 4, borderRadius: 2, background: t.dragHandleBg || 'rgba(15,20,25,0.18)' }} />
        </div>

        {/* HERO: currently using */}
        <V3HeroCard t={t} model={active} />

        {/* Provider filter tabs */}
        <div style={{
          display: 'flex', gap: 0, padding: '0 18px',
          overflowX: 'auto', scrollbarWidth: 'none',
          borderBottom: `1px solid ${t.hair}`,
        }}>
          {providers.map(p => {
            const isActive = p === activeProvider;
            return (
              <div key={p} style={{
                padding: '12px 12px 10px',
                fontSize: 13, fontWeight: isActive ? 500 : 400,
                color: isActive ? t.accent : t.inkSoft,
                letterSpacing: 0.2, lineHeight: 1.2,
                borderBottom: `2px solid ${isActive ? t.accent : 'transparent'}`,
                flexShrink: 0, whiteSpace: 'nowrap',
              }}>{p}</div>
            );
          })}
        </div>

        {/* SEARCH */}
        <div style={{ padding: '12px 18px 6px' }}>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 10,
            padding: '11px 16px', borderRadius: 999,
            background: t.searchBarBg || 'rgba(15,20,25,0.04)',
            border: `1px solid ${t.searchBarEdge || t.hair}`,
          }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.6" y2="16.6"/>
            </svg>
            <span style={{ fontSize: 14, color: t.inkFaint, letterSpacing: 0.2 }}>搜索模型</span>
          </div>
        </div>

        {/* LIST — uniform compact rows */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '4px 0 18px' }}>
          <div style={{ padding: '8px 22px 6px', fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.6 }}>
            可用模型
          </div>
          {others.map((m, i) => (
            <React.Fragment key={m.id}>
              {i > 0 && <div style={{ height: 1, background: t.hair, marginLeft: 62 }} />}
              <V3ListRow t={t} model={m} />
            </React.Fragment>
          ))}
        </div>
      </div>
    </div>
  );
}

function V3HeroCard({ t, model }) {
  const levels = model.levels || [];
  const activeLevel = model.defaultLevel;
  return (
    <div style={{
      margin: '6px 16px 14px',
      padding: '16px 18px 14px',
      borderRadius: 20,
      background: t.heroCardBg || t.accentSoft,
      border: `1px solid ${t.modelActiveEdge || 'rgba(14,156,235,0.18)'}`,
    }}>
      {/* small "在用" pill */}
      <div style={{
        display: 'inline-flex', alignItems: 'center', gap: 5,
        padding: '3px 9px', borderRadius: 999,
        background: t.accent, color: '#FFFFFF',
        fontSize: 10.5, fontWeight: 500, letterSpacing: 0.5,
        marginBottom: 12,
      }}>
        <div style={{ width: 5, height: 5, borderRadius: '50%', background: '#FFFFFF' }} />
        正在使用
      </div>

      {/* model identity row */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
        <div style={{
          width: 44, height: 44, borderRadius: 12,
          background: t.heroLogoBg || '#FFFFFF',
          border: `1px solid ${t.hair}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 17,
          flexShrink: 0,
        }}>{model.logo}</div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{
            fontSize: 11, color: t.heroProviderInk || t.accent, letterSpacing: 0.5, fontWeight: 500,
            lineHeight: 1, marginBottom: 4,
          }}>{model.provider}</div>
          <div style={{
            fontSize: 17, fontWeight: 600, color: t.heroNameInk || t.ink,
            letterSpacing: 0.2, lineHeight: 1.2,
          }}>{model.name}</div>
        </div>
      </div>

      {/* capabilities */}
      <div style={{ display: 'flex', gap: 6, marginTop: 12, flexWrap: 'wrap' }}>
        {model.caps.map(c => (
          <span key={c} style={{
            padding: '3px 10px', borderRadius: 999,
            background: t.heroCapBg || 'rgba(255,255,255,0.5)',
            color: t.heroCapInk || t.inkSoft,
            fontSize: 11, letterSpacing: 0.3, lineHeight: 1.3,
          }}>{c}</span>
        ))}
      </div>

      {/* effort — full width, breathing layout */}
      {levels.length > 0 && (
        <div style={{ marginTop: 16 }}>
          <div style={{
            fontSize: 11, color: t.heroEffortLabel || t.inkSoft,
            letterSpacing: 0.5, fontWeight: 500, marginBottom: 8,
          }}>思考程度</div>
          <div style={{
            display: 'grid',
            gridTemplateColumns: `repeat(${levels.length}, 1fr)`,
            gap: 4,
            padding: 3,
            borderRadius: 999,
            background: t.heroEffortTrack || 'rgba(255,255,255,0.55)',
          }}>
            {levels.map(l => {
              const isActive = l.id === activeLevel;
              const label = l.label || l.id;
              return (
                <div key={l.id} style={{
                  padding: '8px 4px', borderRadius: 999,
                  background: isActive ? t.accent : 'transparent',
                  color: isActive ? '#FFFFFF' : t.ink,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: 12.5, fontWeight: 500, letterSpacing: 0.3,
                  lineHeight: 1, whiteSpace: 'nowrap',
                  fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace',
                }}>{label}</div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

function V3ListRow({ t, model }) {
  return (
    <div style={{
      padding: '12px 22px',
      display: 'flex', alignItems: 'center', gap: 14,
    }}>
      <div style={{
        width: 32, height: 32, borderRadius: 9,
        background: t.modelLogoBg || '#F4F4F4',
        border: `1px solid ${t.hair}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 13,
        flexShrink: 0,
      }}>{model.logo}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontSize: 14.5, fontWeight: 500, color: t.ink,
          letterSpacing: 0.2, lineHeight: 1.2,
        }}>{model.name}</div>
        <div style={{ fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.3, marginTop: 3 }}>
          {model.provider} · {model.caps.join(' / ')}
        </div>
      </div>
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={t.inkFaint} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0 }}>
        <polyline points="9 18 15 12 9 6"/>
      </svg>
    </div>
  );
}

window.ModelPickerV3 = ModelPickerV3;
