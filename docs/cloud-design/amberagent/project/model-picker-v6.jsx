// model-picker-v6.jsx — Proposal A: preview-then-confirm.
// Single sheet open lets you compare models AND tune their effort
// without re-entering. Tapping a row expands it inline (preview).
// Tapping a level commits and closes; bottom "应用" button does same.

const V6_MODELS = [
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
    defaultLevel: 'high' },
];

function ModelPickerV6({ t,
  committedModelId = 'deepseek-v4-pro',
  committedLevel = 'on',
  previewedModelId = null,
  previewedLevel = null,
}) {
  const committed = V6_MODELS.find(m => m.id === committedModelId);
  const preview = previewedModelId ? V6_MODELS.find(m => m.id === previewedModelId) : null;
  const dirty = preview && (preview.id !== committed.id || previewedLevel !== committedLevel);

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
          <div style={{ width: 36, height: 4, borderRadius: 2, background: t.dragHandleBg || 'rgba(15,20,25,0.14)' }} />
        </div>

        <div style={{ padding: '8px 18px 8px' }}>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 10,
            padding: '11px 16px', borderRadius: 14,
            background: t.searchBarBg || 'rgba(15,20,25,0.04)',
          }}>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke={t.inkFaint} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.6" y2="16.6"/>
            </svg>
            <span style={{ fontSize: 14, color: t.inkFaint, letterSpacing: 0.2 }}>搜索模型</span>
          </div>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '6px 14px 12px' }}>
          {V6_MODELS.map(m => {
            const isCommitted = m.id === committed.id;
            const isPreviewed = preview && m.id === preview.id;
            const expanded = isPreviewed || (!preview && isCommitted);
            const effortValue = isPreviewed ? previewedLevel : (isCommitted ? committedLevel : m.defaultLevel);
            return (
              <V6Row key={m.id} t={t}
                     model={m}
                     committed={isCommitted}
                     previewed={isPreviewed}
                     expanded={expanded}
                     activeLevel={effortValue} />
            );
          })}
        </div>

        {/* footer: shows "应用" if there's a pending change, otherwise stays clean */}
        <V6Footer t={t} dirty={dirty} preview={preview} previewedLevel={previewedLevel} />
      </div>
    </div>
  );
}

function V6Row({ t, model, committed, previewed, expanded, activeLevel }) {
  return (
    <div style={{
      margin: '4px 0',
      borderRadius: 16,
      background: expanded ? t.accentSoft : 'transparent',
      padding: '12px 14px',
      border: previewed ? `1px solid ${t.accent}` : `1px solid transparent`,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <div style={{
          width: 38, height: 38, borderRadius: 11,
          background: expanded ? (t.modelLogoActiveBg || '#FFFFFF') : (t.modelLogoBg || 'rgba(15,20,25,0.04)'),
          border: `1px solid ${t.hair}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 16,
          flexShrink: 0,
        }}>{model.logo}</div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 8,
            fontSize: 15, fontWeight: 500,
            color: expanded ? t.accent : t.ink,
            letterSpacing: 0.2, lineHeight: 1.2,
          }}>
            <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{model.name}</span>
            {committed && (
              <span style={{
                fontSize: 10, fontWeight: 500, letterSpacing: 0.4,
                padding: '2px 7px', borderRadius: 999,
                background: 'rgba(255,255,255,0.6)',
                color: t.inkSoft,
                lineHeight: 1.2,
              }}>当前</span>
            )}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 6, color: t.inkFaint }}>
            {model.caps.map(c => <V6CapIcon key={c} kind={c} />)}
          </div>
        </div>
      </div>

      {expanded && (
        <V6EffortBar t={t} levels={model.levels} active={activeLevel} />
      )}
    </div>
  );
}

function V6EffortBar({ t, levels, active }) {
  return (
    <div style={{
      marginTop: 12, marginLeft: 50,
      display: 'flex', gap: 3,
      padding: 3,
      borderRadius: 999,
      background: 'rgba(255,255,255,0.6)',
    }}>
      {levels.map(l => {
        const isActive = l.id === active;
        return (
          <div key={l.id} style={{
            flex: 1, padding: '6px 4px', borderRadius: 999,
            background: isActive ? t.accent : 'transparent',
            color: isActive ? '#FFFFFF' : t.ink,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 12, fontWeight: 500, letterSpacing: 0.3,
            fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace',
            lineHeight: 1, whiteSpace: 'nowrap',
          }}>{l.label}</div>
        );
      })}
    </div>
  );
}

function V6Footer({ t, dirty, preview, previewedLevel }) {
  return (
    <div style={{
      borderTop: `1px solid ${t.hair}`,
      padding: '12px 16px 16px',
      display: 'flex', alignItems: 'center', gap: 10,
    }}>
      {dirty ? (
        <>
          <div style={{ flex: 1, fontSize: 12.5, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.3 }}>
            将切换至 <span style={{ color: t.ink, fontWeight: 500 }}>{preview.name}</span>
            <span style={{ color: t.inkFaint }}> · </span>
            <span style={{ color: t.ink, fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace' }}>{previewedLevel}</span>
          </div>
          <div style={{
            padding: '9px 18px', borderRadius: 999,
            background: 'transparent', color: t.inkSoft,
            border: `1px solid ${t.hair}`,
            fontSize: 13, fontWeight: 500, letterSpacing: 0.3,
          }}>取消</div>
          <div style={{
            padding: '9px 20px', borderRadius: 999,
            background: t.accent, color: '#FFFFFF',
            fontSize: 13, fontWeight: 500, letterSpacing: 0.3,
            boxShadow: `0 4px 14px ${t.sendShadowColor || 'rgba(78,168,232,0.40)'}`,
          }}>应用</div>
        </>
      ) : (
        <div style={{ flex: 1, fontSize: 12.5, color: t.inkFaint, letterSpacing: 0.2, textAlign: 'center' }}>
          点击模型预览，再点 effort 段确认
        </div>
      )}
    </div>
  );
}

function V6CapIcon({ kind }) {
  const p = { width: 13, height: 13, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.6, strokeLinecap: 'round', strokeLinejoin: 'round' };
  if (kind === 'chat')  return <svg {...p}><path d="M21 12a8 8 0 0 1-12 6.9L4 20l1.1-4A8 8 0 1 1 21 12z"/></svg>;
  if (kind === 't2t')   return <svg {...p}><path d="M4 7h6M7 7v10"/><path d="M14 12l3-3 3 3"/><path d="M17 9v10"/></svg>;
  if (kind === 'tit')   return <svg {...p}><rect x="3" y="5" width="8" height="6" rx="1"/><path d="M14 9l3-3 3 3"/><path d="M17 6v12"/></svg>;
  if (kind === 'tool')  return <svg {...p}><path d="M14.7 6.3a4 4 0 0 0-5.3 5.3L3.7 17.3a1.8 1.8 0 0 0 2.5 2.5l5.7-5.7a4 4 0 0 0 5.3-5.3L15 10.5 13.5 9 16 6.3z"/></svg>;
  if (kind === 'sci')   return <svg {...p}><ellipse cx="12" cy="12" rx="9" ry="4"/><ellipse cx="12" cy="12" rx="9" ry="4" transform="rotate(60 12 12)"/><ellipse cx="12" cy="12" rx="9" ry="4" transform="rotate(120 12 12)"/><circle cx="12" cy="12" r="1.1" fill="currentColor" stroke="none"/></svg>;
  return null;
}

window.ModelPickerV6 = ModelPickerV6;
