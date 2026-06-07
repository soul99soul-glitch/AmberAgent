// tablet-screen.jsx — Tablet layout: sidebar (history) + main (conversation).
// Reuses all the same components as the phone screens; just composes them
// in a two-column shell.

function TabletScreen({ t }) {
  return (
    <div style={{
      width: 1180, height: 820,
      background: t.bg,
      position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont, color: t.ink,
      display: 'flex', flexDirection: 'column',
    }}>
      {t.haloConvo && (
        <div aria-hidden style={{ position: 'absolute', inset: 0, pointerEvents: 'none', background: t.haloConvo }} />
      )}
      <TabletStatusBar t={t} />
      <div style={{ position: 'relative', flex: 1, display: 'flex', minHeight: 0 }}>
        {/* sidebar */}
        <div style={{
          width: 300, borderRight: `1px solid ${t.hair}`,
          display: 'flex', flexDirection: 'column', minHeight: 0,
        }}>
          <HistoryBrand t={t} />
          <div style={{ flex: 1, overflowY: 'auto', minHeight: 0 }}>
            <SearchBar t={t} />
            <HistoryNav t={t} />
            <QuickRow t={t} />
            <div style={{ height: 1, background: t.hair, margin: '14px 22px 14px' }} />
            <HistoryRecents t={t} />
          </div>
          <HistoryFooter t={t} />
        </div>

        {/* main panel */}
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, position: 'relative' }}>
          <TabletConvoHeader t={t} title="Lhasa 简介版本叙事对比" />
          <div style={{ flex: 1, overflow: 'hidden', position: 'relative' }}>
            <div style={{
              position: 'absolute', inset: 0, padding: '8px 0',
              maxWidth: 760, margin: '0 auto', left: 0, right: 0,
            }}>
              <UserTurn t={t} text="@council 随便开会聊个什么" />
              <AgentHeader t={t} model="Amber" />
              <ThinkingStrip t={t} seconds="5.4" mode="auto" />
              <Para t={t}>
                好，随便聊，那就聊个轻松的但不乏深度的话题。我先看看 council 工具，然后设几个席位。
              </Para>
              <ToolCard t={t} name="tool_search" status="完成" />
              <Para t={t}>
                开一场轻松但有嚼头的圆桌会。话题：「AI 助手到底应该更像工具，还是更像朋友？」
              </Para>
              <Para t={t} dim>
                四个席位，各自立场鲜明，两轮辩论互相碰撞——
              </Para>
            </div>
            <div style={{
              position: 'absolute', left: 0, right: 0, bottom: 0, height: 56,
              background: `linear-gradient(180deg, ${hexToRgba(t.bg, 0)} 0%, ${t.bg} 75%)`,
              pointerEvents: 'none',
            }} />
          </div>
          <div style={{ maxWidth: 760, width: '100%', margin: '0 auto', alignSelf: 'center', padding: '0 0 12px' }}>
            <InputBar t={t} />
          </div>
        </div>
      </div>
    </div>
  );
}

// Wide status bar — time + dots cluster left, status cluster right, "..." center.
function TabletStatusBar({ t }) {
  return (
    <div style={{
      height: 42, display: 'flex', alignItems: 'center',
      justifyContent: 'space-between', padding: '0 28px',
      color: t.statusInk, fontFamily: t.bodyFont,
      fontSize: 14, fontWeight: 600, letterSpacing: 0.2,
      position: 'relative',
    }}>
      {/* left: time + app icons */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span>10:42</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginLeft: 4 }}>
          {(t.appIcons || []).map((ic, i) => (
            <div key={i} style={{
              width: 14, height: 14, borderRadius: 4,
              background: ic.bg,
            }} />
          ))}
        </div>
      </div>

      {/* center: foldable hinge dots */}
      <div style={{
        position: 'absolute', left: '50%', top: '50%',
        transform: 'translate(-50%, -50%)',
        display: 'flex', gap: 4,
      }}>
        {[0,1,2].map(i => (
          <div key={i} style={{ width: 3, height: 3, borderRadius: '50%', background: t.statusInk, opacity: 0.5 }} />
        ))}
      </div>

      {/* right: bell/bt/wifi/5G/battery */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={t.statusInk} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M6 8a6 6 0 0 1 9.5-4.9"/><path d="M18 8c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/><line x1="2" y1="2" x2="22" y2="22"/>
        </svg>
        <svg width="10" height="13" viewBox="0 0 24 24" fill="none" stroke={t.statusInk} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="6.5 6.5 17.5 17.5 12 23 12 1 17.5 6.5 6.5 17.5"/>
        </svg>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', lineHeight: 1, fontSize: 9, fontWeight: 500, gap: 1 }}>
          <span style={{ fontSize: 10, fontWeight: 600 }}>320</span>
          <span style={{ opacity: 0.7, letterSpacing: 0.3 }}>KB/s</span>
        </div>
        <svg width="14" height="11" viewBox="0 0 24 18" fill="none" stroke={t.statusInk} strokeWidth="1.8" strokeLinecap="round">
          <path d="M2 6a16 16 0 0 1 20 0"/><path d="M5.5 9.5a11 11 0 0 1 13 0"/><path d="M9 13a6 6 0 0 1 6 0"/>
          <circle cx="12" cy="16" r="1" fill={t.statusInk}/>
        </svg>
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 1 }}>
          <span style={{ fontSize: 8, fontWeight: 700, marginBottom: 3, marginRight: 1, lineHeight: 1 }}>5G</span>
          {[3,5,7,9].map(h => (
            <div key={h} style={{ width: 2, height: h, background: t.statusInk, borderRadius: 0.5 }} />
          ))}
        </div>
        <div style={{
          padding: '2px 4px', borderRadius: 4,
          background: t.battery.bg, color: t.battery.ink,
          fontSize: 9, fontWeight: 700, letterSpacing: 0.3, lineHeight: 1,
        }}>60</div>
      </div>
    </div>
  );
}

function TabletConvoHeader({ t, title }) {
  return (
    <div style={{
      padding: '14px 28px 12px',
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      gap: 12,
    }}>
      <span style={{
        fontFamily: t.titleFont, fontSize: 16, fontWeight: 500,
        color: t.ink, letterSpacing: 0.2, lineHeight: 1.2,
        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
        flex: 1,
      }}>{title}</span>
      <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
        <div style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
            <line x1="4" y1="7" x2="20" y2="7"/><line x1="4" y1="12" x2="20" y2="12"/><line x1="4" y1="17" x2="14" y2="17"/>
          </svg>
        </div>
        <div style={{ width: 36, height: 36, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
            <path d="M20.5 11.3a8 8 0 0 1-.85 3.6 8 8 0 0 1-7.15 4.4 8 8 0 0 1-3.6-.85L3.5 20.5l1.45-5.4a8 8 0 0 1-.85-3.6 8 8 0 0 1 4.4-7.15 8 8 0 0 1 3.6-.85h.45a8 8 0 0 1 7.55 7.55v.45z"/>
            <line x1="12" y1="8.4" x2="12" y2="14.6"/>
            <line x1="8.9" y1="11.5" x2="15.1" y2="11.5"/>
          </svg>
        </div>
      </div>
    </div>
  );
}

window.TabletScreen = TabletScreen;
