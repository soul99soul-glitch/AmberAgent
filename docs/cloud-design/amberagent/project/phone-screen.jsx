// PhoneScreen.jsx — Faithful reproduction of the original chat screen,
// themeable. Same elements, refined aesthetics.
//
// THEME shape:
// {
//   name, sub,
//   bg, bgGradient,       // page background
//   ink, inkSoft, inkFaint, // text colors (primary / secondary / placeholder)
//   hair,                  // hairline color
//   accent, accentSoft,    // accent + tinted bg
//   surface, surfaceEdge,  // input pill bg + its border
//   titleFont, bodyFont,   // font families
//   titleWeight, titleSize, titleLetter,
//   statusInk,             // status bar ink
//   appIcons,              // array of 4 {bg, glyph}
//   battery,               // {bg, ink}
//   roundIcon,             // header right circle bg
//   sendBg,                // send button bg
//   sendArrow,             // send arrow color
//   homeBar,               // home indicator color
// }

function PhoneScreen({ t }) {
  return (
    <div style={{
      width: 380, height: 832, borderRadius: 44,
      background: t.bg,
      position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont,
      color: t.ink,
      boxShadow: '0 1px 0 rgba(0,0,0,0.04) inset',
      display: 'flex', flexDirection: 'column',
    }}>
      {/* Ambient halo bloom — sits behind everything */}
      <div aria-hidden style={{
        position: 'absolute', inset: 0, pointerEvents: 'none',
        background: t.halo,
      }} />
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        <StatusBar t={t} />
        <Header t={t} />
        <Hero t={t} />
        <InputBar t={t} />
        <HomeIndicator t={t} />
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
function StatusBar({ t }) {
  return (
    <div style={{
      height: 38, display: 'flex', alignItems: 'center',
      justifyContent: 'space-between', padding: '0 22px 0 22px',
      color: t.statusInk, fontFamily: t.bodyFont,
      fontSize: 14, fontWeight: 600, letterSpacing: 0.2,
    }}>
      {/* left: time + 4 app icons */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span>00:35</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 4, marginLeft: 4 }}>
          {t.appIcons.map((ic, i) => (
            <div key={i} style={{
              width: 14, height: 14, borderRadius: 4,
              background: ic.bg,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              color: ic.glyphColor || '#fff', fontSize: 8, fontWeight: 700,
            }}>{ic.glyph}</div>
          ))}
        </div>
      </div>

      {/* right: bell-off · BT · KB/s · wifi · 5G · battery */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        {/* bell off */}
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={t.statusInk} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M6 8a6 6 0 0 1 9.5-4.9"/>
          <path d="M18 8c0 7 3 9 3 9H3s3-2 3-9"/>
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>
          <line x1="2" y1="2" x2="22" y2="22"/>
        </svg>
        {/* bluetooth */}
        <svg width="10" height="13" viewBox="0 0 24 24" fill="none" stroke={t.statusInk} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="6.5 6.5 17.5 17.5 12 23 12 1 17.5 6.5 6.5 17.5"/>
        </svg>
        {/* KB/s */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', lineHeight: 1, fontSize: 9, fontWeight: 500, gap: 1 }}>
          <span style={{ fontSize: 10, fontWeight: 600 }}>0.20</span>
          <span style={{ opacity: 0.7, letterSpacing: 0.3 }}>KB/s</span>
        </div>
        {/* wifi */}
        <svg width="14" height="11" viewBox="0 0 24 18" fill="none" stroke={t.statusInk} strokeWidth="1.8" strokeLinecap="round">
          <path d="M2 6a16 16 0 0 1 20 0"/>
          <path d="M5.5 9.5a11 11 0 0 1 13 0"/>
          <path d="M9 13a6 6 0 0 1 6 0"/>
          <circle cx="12" cy="16" r="1" fill={t.statusInk}/>
        </svg>
        {/* 5G + signal bars */}
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 1 }}>
          <span style={{ fontSize: 8, fontWeight: 700, marginBottom: 3, marginRight: 1, lineHeight: 1 }}>5G</span>
          {[3, 5, 7, 9].map(h => (
            <div key={h} style={{ width: 2, height: h, background: t.statusInk, borderRadius: 0.5 }} />
          ))}
        </div>
        {/* battery */}
        <div style={{
          padding: '2px 4px', borderRadius: 4,
          background: t.battery.bg, color: t.battery.ink,
          fontSize: 9, fontWeight: 700, letterSpacing: 0.3, lineHeight: 1,
        }}>100</div>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
function Header({ t }) {
  return (
    <div style={{
      padding: '18px 22px 8px', display: 'flex',
      alignItems: 'center', justifyContent: 'space-between',
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        {/* personality hamburger — three lines of varied lengths */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 5, alignItems: 'flex-start' }}>
          <div style={{ width: 22, height: 1.6, background: t.ink, borderRadius: 1 }} />
          <div style={{ width: 14, height: 1.6, background: t.ink, borderRadius: 1 }} />
          <div style={{ width: 19, height: 1.6, background: t.ink, borderRadius: 1 }} />
        </div>
        {/* title + chevron */}
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
          <span style={{
            fontFamily: t.bodyFont,
            fontSize: 15,
            fontWeight: 500,
            letterSpacing: 0.2,
            color: t.ink,
            lineHeight: 1.2,
          }}>Deepseek V4 Pro</span>
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" style={{ transform: 'translateY(-1px)' }}>
            <polyline points="6 9 12 15 18 9"/>
          </svg>
        </div>
      </div>

      {/* naked compose icon — no surrounding circle */}
      <div style={{
        width: 36, height: 36,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          {/* speech bubble */}
          <path d="M20.5 11.3a8 8 0 0 1-.85 3.6 8 8 0 0 1-7.15 4.4 8 8 0 0 1-3.6-.85L3.5 20.5l1.45-5.4a8 8 0 0 1-.85-3.6 8 8 0 0 1 4.4-7.15 8 8 0 0 1 3.6-.85h.45a8 8 0 0 1 7.55 7.55v.45z"/>
          <line x1="12" y1="8.4" x2="12" y2="14.6"/>
          <line x1="8.9" y1="11.5" x2="15.1" y2="11.5"/>
        </svg>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
// AmberMark — geometric arc weave. Three vesica-shaped lenses overlap at
// the center, each lens drawn as a pair of mirror arcs. Rotational
// 3-fold symmetry, structured like Perplexity's mark but with our own
// proportions; rendered as strokes over a soft luminous core.
function AmberMark({ size = 72, t }) {
  const stops = t.heroGem || ['#CFE3FF', '#5E8FE0', '#1F3A8A'];
  const glow = t.heroGlow || 'rgba(94,143,224,0.40)';
  const id = React.useMemo(() => 'g' + Math.random().toString(36).slice(2, 8), []);

  // One arc, drawn from top vertex curving outward and down. We mirror
  // it horizontally to form a lens, then rotate the lens 0/120/240°.
  // Arc geometry: from (32,8) to (32,56), bulging right to x≈54.
  const arcR  = "M32 8 C 48 18, 54 32, 32 56";
  const arcL  = "M32 8 C 16 18, 10 32, 32 56";

  return (
    <div style={{ position: 'relative', width: size, height: size }}>
      {/* ambient bloom */}
      <div style={{
        position: 'absolute', inset: -size * 0.55,
        background: `radial-gradient(50% 50% at 50% 50%, ${glow} 0%, transparent 72%)`,
      }} />
      <svg width={size} height={size} viewBox="0 0 64 64" style={{ position: 'relative', display: 'block' }}>
        <defs>
          {/* gradient applied along each arc: brighter at center, fading at ends */}
          <linearGradient id={id + '-stroke'} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%"   stopColor={stops[2]} stopOpacity="0.25" />
            <stop offset="50%"  stopColor={stops[1]} stopOpacity="1" />
            <stop offset="100%" stopColor={stops[2]} stopOpacity="0.25" />
          </linearGradient>
          <radialGradient id={id + '-core'} cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor="rgba(255,255,255,0.85)" />
            <stop offset="55%" stopColor={stops[0]} stopOpacity="0.45" />
            <stop offset="100%" stopColor="rgba(255,255,255,0)" />
          </radialGradient>
        </defs>

        {/* soft luminous nucleus */}
        <circle cx="32" cy="32" r="10" fill={`url(#${id}-core)`} />

        {/* three rotated vesica lenses — geometric weave */}
        <g fill="none" stroke={`url(#${id}-stroke)`} strokeWidth="2.6" strokeLinecap="round">
          {[0, 120, 240].map(deg => (
            <g key={deg} transform={`rotate(${deg} 32 32)`}>
              <path d={arcR} />
              <path d={arcL} />
            </g>
          ))}
        </g>

        {/* small bright dot at exact center — focal point */}
        <circle cx="32" cy="32" r="1.8" fill={stops[0]} opacity="0.95" />
      </svg>
    </div>
  );
}

function Hero({ t }) {
  return (
    <div style={{
      flex: 1,
      display: 'flex', flexDirection: 'column',
      alignItems: 'center', justifyContent: 'center',
      padding: '0 36px',
      paddingBottom: 60,
    }}>
      <div style={{
        textAlign: 'center',
        fontFamily: t.heroFont || t.titleFont,
        fontSize: t.heroSize || 26,
        fontWeight: t.heroWeight || 500,
        letterSpacing: t.heroLetter ?? 0.2,
        lineHeight: 1.35,
        color: t.heroInk || t.ink,
      }}>
        Hi 光，今天想聊点什么？
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
function InputBar({ t }) {
  return (
    <div style={{ padding: '0 14px 10px' }}>
      <div style={{
        height: 68, borderRadius: 999,
        background: t.surface,
        border: `1px solid ${t.surfaceEdge}`,
        boxShadow: t.surfaceShadow || 'none',
        display: 'flex', alignItems: 'center',
        padding: '0 10px 0 20px', gap: 8,
      }}>
        {/* + */}
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <line x1="12" y1="5" x2="12" y2="19"/>
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
        {/* divider */}
        <div style={{ width: 1, height: 20, background: t.hair, marginLeft: 8, marginRight: 6 }} />
        {/* placeholder */}
        <span style={{
          flex: 1, color: t.inkFaint, fontSize: 15,
          fontFamily: t.bodyFont, letterSpacing: 0.2,
        }}>输入消息</span>
        {/* slash glyph */}
        <div style={{
          width: 28, height: 28, display: 'flex',
          alignItems: 'center', justifyContent: 'center',
          color: t.inkSoft, fontFamily: t.bodyFont,
          fontSize: 22, fontWeight: 300, lineHeight: 1,
        }}>
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
            <line x1="17" y1="5" x2="7" y2="19"/>
          </svg>
        </div>
        {/* send — glass orb + breathing halo */}
        <SendButton t={t} />
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
function SendButton({ t }) {
  // Lazy-inject the keyframes once.
  React.useEffect(() => {
    if (document.getElementById('aa-send-kf')) return;
    const s = document.createElement('style');
    s.id = 'aa-send-kf';
    s.textContent = `
      @keyframes aa-breathe {
        0%, 100% { transform: scale(1);    opacity: 0.55; }
        50%      { transform: scale(1.18); opacity: 0.95; }
      }
      @keyframes aa-breathe-soft {
        0%, 100% { transform: scale(0.95); opacity: 0.30; }
        50%      { transform: scale(1.35); opacity: 0.70; }
      }
      @keyframes aa-shimmer {
        0%, 100% { opacity: 0.55; }
        50%      { opacity: 0.85; }
      }
    `;
    document.head.appendChild(s);
  }, []);

  const base = t.sendBg;
  // glass shell — translucent gradient + inner top highlight + bottom shadow
  return (
    <div style={{
      position: 'relative',
      width: 48, height: 48,
      marginLeft: 4,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      {/* outer halo — slow breathe */}
      <div style={{
        position: 'absolute', inset: -14,
        borderRadius: '50%',
        background: `radial-gradient(50% 50% at 50% 50%, ${t.sendHalo || base} 0%, transparent 70%)`,
        animation: 'aa-breathe-soft 3.6s ease-in-out infinite',
        pointerEvents: 'none',
      }} />
      {/* inner halo — tighter, faster */}
      <div style={{
        position: 'absolute', inset: -4,
        borderRadius: '50%',
        background: `radial-gradient(50% 50% at 50% 50%, ${t.sendHalo || base} 0%, transparent 65%)`,
        animation: 'aa-breathe 2.8s ease-in-out infinite',
        pointerEvents: 'none',
      }} />
      {/* the orb — flat, clean */}
      <div style={{
        position: 'relative',
        width: 48, height: 48, borderRadius: '50%',
        background: t.sendBg,
        boxShadow: t.sendShadow || 'none',
        display: 'flex', alignItems: 'center', justifyContent: 'center',
      }}>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none"
          stroke={t.sendArrow} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
          <line x1="12" y1="19" x2="12" y2="5"/>
          <polyline points="5 12 12 5 19 12"/>
        </svg>
      </div>
    </div>
  );
}

// ──────────────────────────────────────────────────────────────
function HomeIndicator({ t }) {
  return (
    <div style={{ height: 22, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
      <div style={{ width: 120, height: 4, borderRadius: 2, background: t.homeBar }} />
    </div>
  );
}

Object.assign(window, { PhoneScreen, AmberMark, StatusBar, HomeIndicator, SendButton, InputBar });
