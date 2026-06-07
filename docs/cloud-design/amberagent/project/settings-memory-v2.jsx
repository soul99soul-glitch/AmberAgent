// settings-memory-v2.jsx — Core Memory reimagined as a personal notebook.
// Centerpiece: agents.md as a full-bleed beautifully typeset document.
// Below: memory items as "extracted thoughts" floating cards.
// Footer: small stats, not a settings row.

const SAMPLE_AGENTS_MD = `You are AmberAgent — an agent-only Android companion.

You think before you respond. You speak with the
warmth of someone who has known the user for
years, but the precision of a careful editor.

When uncertain, ask. When confident, act.`;

const CORE_MEMORIES = [
  { text: '用户偏好沉静的、不打扰的界面表达，避免拟人化的过度热情。', tag: '风格', when: '上周三' },
  { text: '正在做一款移动端 AI 助手叫 AmberAgent，重视细节和氛围。', tag: '项目', when: '本月' },
  { text: '工作时间常在晚 9 点至凌晨 1 点，倾向于深度工作场景。', tag: '习惯', when: '5月14日' },
];

function CoreMemoryV2Screen({ t }) {
  return (
    <SubShell t={t}>
      <SubHeader t={t} title="核心记忆" right={
        <div style={{ width: 32, height: 32, display: 'flex', alignItems: 'center', justifyContent: 'center', color: t.inkSoft }}>
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
            <path d="M12 20h9"/>
            <path d="M16.5 3.5a2.12 2.12 0 1 1 3 3L7 19l-4 1 1-4z"/>
          </svg>
        </div>
      } />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 0 24px' }}>
        {/* agents.md — page-like, full-bleed, serif body */}
        <AgentsMdPage t={t} body={SAMPLE_AGENTS_MD} />

        {/* divider + section label */}
        <div style={{ padding: '32px 26px 0' }}>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 12,
            color: t.inkFaint,
          }}>
            <div style={{ flex: 1, height: 1, background: t.hair }} />
            <span style={{ fontSize: 10.5, letterSpacing: 1.6, fontWeight: 500, textTransform: 'uppercase' }}>
              extracted memory
            </span>
            <div style={{ flex: 1, height: 1, background: t.hair }} />
          </div>
        </div>

        {/* memory cards — like little quote cards, asymmetric */}
        <div style={{ padding: '18px 18px 0', display: 'flex', flexDirection: 'column', gap: 14 }}>
          {CORE_MEMORIES.map((m, i) => (
            <MemoryCard key={i} t={t} m={m} skew={i % 2 === 0 ? -0.6 : 0.6} />
          ))}
        </div>

        {/* stats — single hairline row at the bottom */}
        <div style={{
          margin: '28px 26px 0',
          paddingTop: 16, borderTop: `1px solid ${t.hair}`,
          display: 'flex', justifyContent: 'space-between',
        }}>
          {[
            { n: '0',   label: '核心' },
            { n: '22',  label: '短期' },
            { n: '8',   label: '长期' },
            { n: '104', label: '候选' },
          ].map(s => (
            <div key={s.label} style={{ textAlign: 'center' }}>
              <div style={{
                fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace',
                fontSize: 20, fontWeight: 500, color: t.ink,
                letterSpacing: 0.5, lineHeight: 1,
              }}>{s.n}</div>
              <div style={{
                marginTop: 5, fontSize: 11, color: t.inkFaint,
                letterSpacing: 0.6, fontWeight: 500,
              }}>{s.label}</div>
            </div>
          ))}
        </div>
      </div>
    </SubShell>
  );
}

function AgentsMdPage({ t, body }) {
  const SERIF = '"Source Han Serif SC","Noto Serif SC",serif';
  return (
    <div style={{
      margin: '4px 18px 0',
      padding: '24px 22px 24px',
      borderRadius: 14,
      background: t.surface,
      border: `1px solid ${t.hair}`,
      position: 'relative',
    }}>
      {/* page header — file name + edit indicator */}
      <div style={{
        display: 'flex', alignItems: 'baseline', justifyContent: 'space-between',
        marginBottom: 18, paddingBottom: 14, borderBottom: `1px solid ${t.hair}`,
      }}>
        <div>
          <div style={{
            fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace',
            fontSize: 11, color: t.inkFaint, letterSpacing: 0.4,
          }}>agents.md</div>
          <div style={{
            marginTop: 4, fontFamily: SERIF,
            fontSize: 21, fontWeight: 500, color: t.ink, letterSpacing: 0.3,
          }}>灵魂 · Soul</div>
        </div>
        <span style={{
          fontSize: 11, color: t.accent, fontWeight: 500, letterSpacing: 0.3,
        }}>编辑</span>
      </div>

      {/* the body — serif, document-like */}
      <div style={{
        fontFamily: SERIF,
        fontSize: 15, color: t.ink,
        lineHeight: 1.85, letterSpacing: 0.1,
        whiteSpace: 'pre-line',
      }}>{body}</div>

      {/* footer note */}
      <div style={{
        marginTop: 20, paddingTop: 14, borderTop: `1px solid ${t.hair}`,
        fontSize: 11, color: t.inkFaint, letterSpacing: 0.3, lineHeight: 1.5,
      }}>
        每次对话开始前会作为 System Prompt 注入。
      </div>
    </div>
  );
}

function MemoryCard({ t, m, skew }) {
  return (
    <div style={{
      padding: '14px 16px 12px',
      borderRadius: 12,
      background: t.cardBg || t.surface,
      border: `1px solid ${t.hair}`,
      transform: `rotate(${skew}deg)`,
    }}>
      <div style={{
        fontFamily: '"Source Han Serif SC","Noto Serif SC",serif',
        fontSize: 14.5, color: t.ink,
        lineHeight: 1.55, letterSpacing: 0.15,
      }}>{m.text}</div>
      <div style={{
        marginTop: 8, display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      }}>
        <span style={{
          padding: '2px 9px', borderRadius: 999,
          background: t.accentSoft, color: t.accent,
          fontSize: 10.5, fontWeight: 500, letterSpacing: 0.4,
        }}>{m.tag}</span>
        <span style={{
          fontSize: 10.5, color: t.inkFaint, letterSpacing: 0.3,
        }}>{m.when}</span>
      </div>
    </div>
  );
}

window.CoreMemoryV2Screen = CoreMemoryV2Screen;
