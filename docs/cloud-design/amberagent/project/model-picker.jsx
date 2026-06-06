// model-picker.jsx — Bottom-sheet model picker with embedded thinking-level
// control on the active model. Each model declares its own level set, so we
// can handle: off/on/max (DeepSeek), off/on (Kimi/GLM), low/med/high/xhigh
// (GPT), and the full Claude variant with adaptive.

const MODELS = [
  { id: 'deepseek-v4-pro', provider: 'DeepSeek',  logo: 'D', name: 'deepseek-v4-pro', caps: ['chat','t2t','tool','sci'], fav: true,
    levels: [{ id: 'off', label: 'off' }, { id: 'on', label: 'on' }, { id: 'max', label: 'max' }],
    defaultLevel: 'on' },
  { id: 'kimi-for-coding', provider: 'Kimi',      logo: 'K', name: 'kimi-for-coding', caps: ['chat','tit','tool','sci'],
    levels: [{ id: 'off', label: 'off' }, { id: 'on', label: 'on' }],
    defaultLevel: 'on' },
  { id: 'glm-5.1',         provider: '智谱 GLM',   logo: 'G', name: 'glm-5.1',         caps: ['chat','t2t','tool','sci'],
    levels: [{ id: 'off', label: 'off' }, { id: 'on', label: 'on' }],
    defaultLevel: 'off' },
  { id: 'glm-4.7',         provider: '智谱 GLM',   logo: 'G', name: 'glm-4.7',         caps: ['chat','t2t','tool'],
    levels: [{ id: 'off', label: 'off' }, { id: 'on', label: 'on' }],
    defaultLevel: 'off' },
  { id: 'gpt-5',           provider: 'OpenAI',     logo: 'O', name: 'gpt-5',           caps: ['chat','tit','tool','sci'],
    levels: [{ id: 'low', label: 'low' }, { id: 'med', label: 'medium' }, { id: 'high', label: 'high' }, { id: 'xhigh', label: 'xhigh' }],
    defaultLevel: 'med' },
  { id: 'claude-opus-4.7', provider: 'Anthropic',  logo: 'A', name: 'claude-opus-4.7', caps: ['chat','tit','tool','sci'],
    levels: [{ id: 'low', label: 'low' }, { id: 'med', label: 'medium' }, { id: 'high', label: 'high' }, { id: 'xhigh', label: 'xhigh' }, { id: 'max', label: 'max' }],
    defaultLevel: 'high',
    hasAdaptive: true,
    adaptiveOn: false },
];

const PROVIDERS = ['DeepSeek', 'Kimi', '智谱 GLM', 'OpenAI', 'Anthropic'];

function ModelPicker({ t, activeModelId = 'deepseek-v4-pro', adaptiveOn = false }) {
  const baseModel = MODELS.find(m => m.id === activeModelId) || MODELS[0];
  // shallow-override adaptiveOn so picker artboards can show both states
  const activeModel = baseModel.hasAdaptive ? { ...baseModel, adaptiveOn } : baseModel;
  const rest = MODELS.filter(m => m.id !== activeModel.id);
  return (
    <div style={{
      width: 380, height: 832, borderRadius: 44,
      background: t.bg,
      position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont, color: t.ink,
      display: 'flex', flexDirection: 'column',
    }}>
      <div style={{ position: 'absolute', inset: 0, background: t.bg }} />
      <div style={{ position: 'absolute', inset: 0, background: t.sheetBackdrop || 'rgba(15,20,25,0.18)' }} />

      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0,
        height: 740,
        background: t.bg,
        borderTopLeftRadius: 28, borderTopRightRadius: 28,
        boxShadow: '0 -8px 32px rgba(15,20,25,0.10)',
        display: 'flex', flexDirection: 'column',
        overflow: 'hidden',
      }}>
        <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 4px' }}>
          <div style={{ width: 40, height: 4, borderRadius: 2, background: t.dragHandleBg || 'rgba(15,20,25,0.18)' }} />
        </div>

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
            <span style={{ fontSize: 14.5, color: t.inkFaint, letterSpacing: 0.2 }}>输入模型名称搜索</span>
          </div>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '8px 0 0' }}>
          {/* active model first — always its own card with the level segment */}
          <ModelCard t={t} model={activeModel} active />
          {/* remaining grouped by provider; same provider models live in one shared card */}
          {groupByProvider(rest).map(group => (
            <React.Fragment key={group.provider}>
              <div style={{ padding: '14px 22px 8px', fontSize: 13, color: t.accent, letterSpacing: 0.3 }}>
                {group.provider}
              </div>
              <ModelGroupCard t={t} models={group.models} />
            </React.Fragment>
          ))}
        </div>

        <ProviderChips t={t} />
      </div>
    </div>
  );
}

function groupByProvider(models) {
  const map = new Map();
  models.forEach(m => {
    if (!map.has(m.provider)) map.set(m.provider, []);
    map.get(m.provider).push(m);
  });
  return Array.from(map, ([provider, ms]) => ({ provider, models: ms }));
}

// Multiple models from one provider live inside a single card, separated by
// hairlines. Visually conveys "these belong together" without repeating chrome.
function ModelGroupCard({ t, models }) {
  return (
    <div style={{
      margin: '6px 14px',
      padding: 0,
      borderRadius: 16,
      background: t.modelCardBg || '#FFFFFF',
      border: `1px solid ${t.hair}`,
      overflow: 'hidden',
    }}>
      {models.map((m, i) => (
        <React.Fragment key={m.id}>
          {i > 0 && <div style={{ height: 1, background: t.hair, margin: '0 14px' }} />}
          <ModelRow t={t} model={m} />
        </React.Fragment>
      ))}
    </div>
  );
}

function ModelRow({ t, model }) {
  return (
    <div style={{
      padding: '12px 14px',
      display: 'flex', alignItems: 'center', gap: 12,
    }}>
      <div style={{
        width: 40, height: 40, borderRadius: 10,
        background: t.modelLogoBg || '#F4F4F4',
        border: `1px solid ${t.hair}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 16,
        flexShrink: 0,
      }}>{model.logo}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontSize: 15, fontWeight: 500, color: t.ink,
          letterSpacing: 0.2, lineHeight: 1.2,
        }}>{model.name}</div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 6, color: t.inkFaint }}>
          {model.caps.map(c => <CapIcon key={c} kind={c} />)}
        </div>
      </div>
      <svg width="20" height="20" viewBox="0 0 24 24"
        fill={model.fav ? t.accent : 'none'}
        stroke={model.fav ? t.accent : t.inkSoft}
        strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"
        style={{ flexShrink: 0 }}>
        <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/>
      </svg>
    </div>
  );
}

function ModelCard({ t, model, active = false }) {
  return (
    <div style={{
      margin: '6px 14px',
      padding: '12px 14px',
      borderRadius: 16,
      background: active ? t.accentSoft : (t.modelCardBg || '#FFFFFF'),
      border: `1px solid ${active ? (t.modelActiveEdge || 'rgba(14,156,235,0.18)') : t.hair}`,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{
          width: 40, height: 40, borderRadius: 10,
          background: active
            ? (t.modelLogoActiveBg || '#FFFFFF')
            : (t.modelLogoBg || '#F4F4F4'),
          border: `1px solid ${t.hair}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 16,
          flexShrink: 0,
        }}>{model.logo}</div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{
            fontSize: 15, fontWeight: 500, color: active ? t.accent : t.ink,
            letterSpacing: 0.2, lineHeight: 1.2,
          }}>{model.name}</div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 6, color: t.inkFaint }}>
            {model.caps.map(c => <CapIcon key={c} kind={c} />)}
          </div>
        </div>
        {active && model.hasAdaptive && <AdaptiveButton t={t} on={model.adaptiveOn} />}
        <svg width="20" height="20" viewBox="0 0 24 24"
          fill={model.fav ? t.accent : 'none'}
          stroke={model.fav ? t.accent : t.inkSoft}
          strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"
          style={{ flexShrink: 0 }}>
          <path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/>
        </svg>
      </div>

      {active && <ThinkingLevel t={t} levels={model.levels} active={model.defaultLevel} dimmed={!!model.adaptiveOn} />}
    </div>
  );
}

// Small toggle pill: ✨ + "auto". Lights up in accent when on.
function AdaptiveButton({ t, on }) {
  return (
    <div style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: '4px 9px 4px 7px', borderRadius: 999,
      background: on ? t.accent : 'transparent',
      border: `1px solid ${on ? t.accent : t.hair}`,
      color: on ? '#FFFFFF' : t.accent,
      fontSize: 11.5, fontWeight: 500, letterSpacing: 0.3, lineHeight: 1,
      flexShrink: 0,
    }}>
      <svg width="10" height="10" viewBox="0 0 24 24" fill="currentColor">
        <path d="M12 2l2 6.5 6.5 2-6.5 2L12 19l-2-6.5L3.5 10.5l6.5-2z"/>
      </svg>
      auto
    </div>
  );
}

function CapIcon({ kind }) {
  const p = { width: 14, height: 14, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.7, strokeLinecap: 'round', strokeLinejoin: 'round' };
  if (kind === 'chat')  return <svg {...p}><path d="M21 12a8 8 0 0 1-12 6.9L4 20l1.1-4A8 8 0 1 1 21 12z"/></svg>;
  if (kind === 't2t')   return <svg {...p}><path d="M4 7h6M7 7v10"/><path d="M14 12l3-3 3 3"/><path d="M17 9v10"/></svg>;
  if (kind === 'tit')   return <svg {...p}><rect x="3" y="5" width="8" height="6" rx="1"/><path d="M14 9l3-3 3 3"/><path d="M17 6v12"/></svg>;
  if (kind === 'tool')  return <svg {...p}><path d="M14.7 6.3a4 4 0 0 0-5.3 5.3L3.7 17.3a1.8 1.8 0 0 0 2.5 2.5l5.7-5.7a4 4 0 0 0 5.3-5.3L15 10.5 13.5 9 16 6.3z"/></svg>;
  if (kind === 'sci')   return <svg {...p}><ellipse cx="12" cy="12" rx="9" ry="4"/><ellipse cx="12" cy="12" rx="9" ry="4" transform="rotate(60 12 12)"/><ellipse cx="12" cy="12" rx="9" ry="4" transform="rotate(120 12 12)"/><circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none"/></svg>;
  return null;
}

// Compact segmented control. Auto-scales to N levels.
function ThinkingLevel({ t, levels, active, dimmed = false }) {
  return (
    <div style={{
      marginTop: 12,
      display: 'flex', gap: 3,
      padding: 3,
      borderRadius: 999,
      background: t.segmentTrack || 'rgba(255,255,255,0.7)',
      border: `1px solid ${t.hair}`,
      opacity: dimmed ? 0.4 : 1,
      pointerEvents: dimmed ? 'none' : 'auto',
    }}>
      {levels.map(l => {
        const isActive = !dimmed && l.id === active;
        const isSpecial = !!l.special;
        return (
          <div key={l.id} style={{
            flex: 1, padding: '5px 6px', borderRadius: 999,
            background: isActive ? t.accent : 'transparent',
            color: isActive ? (t.segmentActiveInk || '#FFFFFF') : (isSpecial ? t.accent : t.ink),
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            gap: 3, minWidth: 0,
            fontSize: 12, fontWeight: 500, letterSpacing: 0.2,
            lineHeight: 1, whiteSpace: 'nowrap',
          }}>
            {isSpecial && (
              <svg width="9" height="9" viewBox="0 0 24 24" fill="currentColor">
                <path d="M12 2l2 6.5 6.5 2-6.5 2L12 19l-2-6.5L3.5 10.5l6.5-2z"/>
              </svg>
            )}
            <span>{l.label}</span>
          </div>
        );
      })}
    </div>
  );
}

function ProviderChips({ t }) {
  return (
    <div style={{
      borderTop: `1px solid ${t.hair}`,
      padding: '10px 12px 14px',
      display: 'flex', gap: 8, overflowX: 'auto',
      scrollbarWidth: 'none',
    }}>
      {PROVIDERS.map(p => (
        <div key={p} style={{
          display: 'flex', alignItems: 'center', gap: 8,
          padding: '8px 14px', borderRadius: 12,
          background: t.modelCardBg || '#FFFFFF',
          border: `1px solid ${t.hair}`,
          fontSize: 13, color: t.ink,
          letterSpacing: 0.2,
          flexShrink: 0,
        }}>
          <div style={{
            width: 18, height: 18, borderRadius: 5,
            background: t.modelLogoBg || '#F4F4F4',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: t.inkSoft, fontWeight: 600, fontSize: 10,
          }}>{p[0]}</div>
          {p}
        </div>
      ))}
    </div>
  );
}

window.ModelPicker = ModelPicker;
