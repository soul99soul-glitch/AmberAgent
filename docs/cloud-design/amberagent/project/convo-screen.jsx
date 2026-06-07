// convo-screen.jsx — Conversation screen. Reuses StatusBar / HomeIndicator /
// InputBar from phone-screen.jsx so input chrome stays identical to the
// empty state.

function ConvoScreen({ t, expandedThought = false, expandedRing = false, showAsk = false, showPreview = false, ringInHeader = true, ringUsed = 144, ringTotal = 400 }) {
  return (
    <div style={{
      width: 380, height: 832, borderRadius: 44,
      background: t.bg,
      position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont, color: t.ink,
      boxShadow: '0 1px 0 rgba(0,0,0,0.04) inset',
      display: 'flex', flexDirection: 'column',
    }}>
      {/* halo only if the theme explicitly opts in for convo mode */}
      {t.haloConvo && (
        <div aria-hidden style={{
          position: 'absolute', inset: 0, pointerEvents: 'none',
          background: t.haloConvo,
        }} />
      )}
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        <StatusBar t={t} />
        <ConvoHeader t={t} ringInHeader={ringInHeader} ringOpen={expandedRing} ringUsed={ringUsed} ringTotal={ringTotal} />
        <div style={{ flex: 1, overflow: 'hidden', position: 'relative' }}>
          <ConvoBody t={t} expandedThought={expandedThought} expandedRing={expandedRing} showAsk={showAsk} showPreview={showPreview} ringInHeader={ringInHeader} />
          <div style={{
            position: 'absolute', left: 0, right: 0, bottom: 0, height: 56,
            background: `linear-gradient(180deg, ${hexToRgba(t.bg, 0)} 0%, ${t.bg} 75%)`,
            pointerEvents: 'none',
          }} />
        </div>
        {/* sticky tool-result preview shelf, just above the input bar */}
        {showPreview && (
          <ToolResultPreview t={t} tool="网页搜索" query="亚朵酒店 三里屯" page={1} total={3} />
        )}
        {/* shared input bar from the empty state */}
        <InputBar t={t} />
        <HomeIndicator t={t} />
      </div>
    </div>
  );
}

function hexToRgba(hex, a) {
  if (!hex || !hex.startsWith('#')) return `rgba(250,251,252,${a})`;
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}

function ConvoHeader({ t, ringInHeader, ringOpen = false, ringUsed = 144, ringTotal = 400 }) {
  return (
    <div style={{
      padding: '18px 20px 12px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      gap: 12,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14, minWidth: 0, flex: 1 }}>
        {/* hamburger — varied lengths */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 5, alignItems: 'flex-start', flexShrink: 0 }}>
          <div style={{ width: 22, height: 1.6, background: t.ink, borderRadius: 1 }} />
          <div style={{ width: 14, height: 1.6, background: t.ink, borderRadius: 1 }} />
          <div style={{ width: 19, height: 1.6, background: t.ink, borderRadius: 1 }} />
        </div>
        {/* model name — clean text, no chip background */}
        <div style={{
          display: 'inline-flex', alignItems: 'center',
          color: t.ink,
          fontFamily: t.bodyFont, fontSize: 15, fontWeight: 500,
          letterSpacing: 0.2,
        }}>
          <span>DeepSeek V4 Pro</span>
        </div>
      </div>
      {/* right cluster: optional ring + new-chat icon */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 4, flexShrink: 0 }}>
        {ringInHeader && (
          <div style={{
            width: 36, height: 36,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            flexShrink: 0,
            transform: 'translateY(1.5px)',
          }}>
            <HeaderContextRing t={t} used={ringUsed} total={ringTotal} initialOpen={ringOpen} />
          </div>
        )}
        <div style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
            <g transform="translate(0, -1.6)">
              <path d="M7.9 20A9 9 0 1 0 4 16.1L2 22Z"/>
              <line x1="8" y1="12" x2="16" y2="12"/>
              <line x1="12" y1="8" x2="12" y2="16"/>
            </g>
          </svg>
        </div>
      </div>
    </div>
  );
}

function ConvoBody({ t, expandedThought, expandedRing, showAsk, showPreview, ringInHeader }) {
  if (showAsk) {
    return (
      <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', paddingTop: 6 }}>
        <UserTurn t={t} text="帮我设计一下今天的安排" />
        <AgentHeader t={t} model="Amber" showRing={!ringInHeader} />
        <ThinkingStrip t={t} seconds="2.3" mode="auto" />
        <AskUserCard t={t} count={3} questions={[
          {
            q: '此刻你是什么样的心情？',
            options: [
              '充满能量，想做点什么',
              '有点疲惫，想放松一下',
              '好奇探索中，看看 AmberAgent 能做什么',
              '只是在测试功能 😄',
            ],
          },
          {
            q: '如果现在立刻聊一个话题，你更想聊哪个方向？',
            options: ['科技 / AI / 产品思考', '生活 / 旅行 / 美食', '阅读 / 电影 / 创作'],
          },
          {
            q: '我该怎么称呼你？',
            options: [],
          },
        ]} />
      </div>
    );
  }
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', paddingTop: 6 }}>
      <UserTurn t={t} name="Arquiel" text="@council 随便开会聊个什么" />
      <AgentHeader t={t} model="Amber" ringOpen={expandedRing} showRing={!ringInHeader} />
      <ThinkingStrip t={t} seconds="5.4" mode="auto" expanded={expandedThought} />
      {!expandedThought && (
        <Para t={t}>
          好，随便聊，那就聊个轻松的但不乏深度的话题。我先看看 council 工具，然后设几个席位。
        </Para>
      )}
      {!expandedThought && <ToolCard t={t} name="tool_search" status="完成" />}
      {!expandedThought && <ThinkingStrip t={t} seconds="10.6" mode="auto" />}
      {!expandedThought && (
        <Para t={t}>
          开一场轻松但有嚼头的圆桌会。话题：「AI 助手到底应该更像工具，还是更像朋友？」
        </Para>
      )}
      {!expandedThought && (
        <Para t={t} dim>
          四个席位，各自立场鲜明，两轮辩论互相碰撞——
        </Para>
      )}
      {!expandedThought && <AgentActions t={t} />}
    </div>
  );
}

window.ConvoScreen = ConvoScreen;
