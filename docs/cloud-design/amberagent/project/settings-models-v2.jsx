// settings-models-v2.jsx — Redesigned 模型分配 / Model Assignment.
//
// V1 problems:
//   - Each row had too many tiers (icon | title + desc | "参数" pill | meta "当前使用: xxx" | another chip)
//   - Model name appeared twice
//   - "参数" pill competed with the main selector
//
// V2 approach:
//   - Hero chat model: compact card, no big decorative icon, just label + model pill
//   - Assist tasks: uniform settings row — name on left, model pill on right, chevron
//   - "参数" / prompts live on the detail page (tap the row)
//   - Linked-to-chat state is a tiny annotation, not a fragile-feeling button

function ModelAssignScreenV2({ t }) {
  const tasks = [
    { icon: 'doc',    name: '标题总结模型', linked: true,  logo: 'O', model: 'gpt-5.5' },
    { icon: 'bubble', name: '聊天建议模型', linked: true,  logo: 'O', model: 'gpt-5.5' },
    { icon: 'globe',  name: '翻译模型',    linked: false, logo: null, model: null },
    { icon: 'spark',  name: '生图模型',    linked: false, logo: 'O', model: 'gpt-image-2' },
    { icon: 'doc',    name: '视觉识别模型', linked: false, logo: null, model: null },
  ];

  return (
    <SubShell t={t}>
      <SubHeader t={t} title="模型分配" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
        {/* Hero: chat model */}
        <div style={{
          padding: '16px 18px',
          background: t.cardBg || t.surface,
          border: `1px solid ${t.hair}`,
          borderRadius: 18,
        }}>
          <div style={{
            fontSize: 11.5, color: t.inkFaint, letterSpacing: 1.4,
            fontWeight: 500, textTransform: 'uppercase', marginBottom: 8,
          }}>聊天模型</div>
          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 12,
          }}>
            <div style={{ fontSize: 15, color: t.ink, letterSpacing: 0.2 }}>全局默认</div>
            <ModelPillV2 t={t} logo="O" name="gpt-5.5" emphasis />
          </div>
        </div>

        <SubGroupLabel t={t} style={{ paddingLeft: 4, paddingTop: 24 }}>辅助任务</SubGroupLabel>
        <SubCard t={t}>
          {tasks.map((r, i) => (
            <React.Fragment key={r.name}>
              {i > 0 && <HairDivider t={t} indent={16} />}
              <AssistRowV2 t={t} task={r} />
            </React.Fragment>
          ))}
        </SubCard>

        <div style={{
          marginTop: 16, padding: '0 4px',
          fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.55,
        }}>
          点击任一行可调整使用的模型、参数及提示词。
        </div>
      </div>
    </SubShell>
  );
}

function AssistRowV2({ t, task }) {
  return (
    <div style={{ padding: '14px 14px', display: 'flex', alignItems: 'center', gap: 14 }}>
      <IconCircle t={t}><XIcon kind={task.icon} color={t.inkSoft} /></IconCircle>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          fontSize: 14.5, color: t.ink, fontWeight: 500,
          letterSpacing: 0.2, lineHeight: 1.2,
        }}>{task.name}</div>
        {task.linked && (
          <div style={{
            marginTop: 3, fontSize: 11.5, color: t.inkFaint,
            letterSpacing: 0.2, lineHeight: 1.3,
          }}>跟随聊天模型</div>
        )}
      </div>
      {task.model ? (
        <ModelPillV2 t={t} logo={task.logo} name={task.model} muted={task.linked} />
      ) : (
        <span style={{
          fontSize: 13, color: t.accent, fontWeight: 500, letterSpacing: 0.2,
        }}>选择模型</span>
      )}
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={t.inkFaint} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0 }}>
        <polyline points="9 18 15 12 9 6"/>
      </svg>
    </div>
  );
}

function ModelPillV2({ t, logo, name, emphasis, muted }) {
  return (
    <div style={{
      display: 'inline-flex', alignItems: 'center', gap: 7,
      padding: emphasis ? '7px 13px 7px 7px' : '5px 11px 5px 5px',
      borderRadius: 999,
      background: muted ? 'transparent' : t.accentSoft,
      border: muted ? `1px solid ${t.hair}` : 'none',
      flexShrink: 0,
    }}>
      {logo && (
        <div style={{
          width: emphasis ? 22 : 18, height: emphasis ? 22 : 18, borderRadius: 6,
          background: '#FFFFFF', border: `1px solid ${t.hair}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600,
          fontSize: emphasis ? 11 : 10, flexShrink: 0,
        }}>{logo}</div>
      )}
      <span style={{
        fontSize: emphasis ? 14 : 12.5,
        fontWeight: 500,
        color: muted ? t.inkSoft : t.accent,
        letterSpacing: 0.2, lineHeight: 1.2,
      }}>{name}</span>
    </div>
  );
}

window.ModelAssignScreenV2 = ModelAssignScreenV2;
