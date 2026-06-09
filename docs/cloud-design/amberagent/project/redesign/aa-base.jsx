/* global React */
// aa-base.jsx — shared primitives for the Amber redesign:
// PhoneScreen shell, StatusBar, icon set, Cursor, Dot, mock data.
const { useState: useStateBase, useEffect: useEffectBase, useRef: useRefBase } = React;

/* ---------------------------------------------------------------- icons --- */
// minimal stroke icons, inherit currentColor
function Ic({ d, size = 22, sw = 1.7, fill, children, vb = 24, style }) {
  return (
    <svg width={size} height={size} viewBox={`0 0 ${vb} ${vb}`} fill="none"
      stroke={fill ? "none" : "currentColor"} strokeWidth={sw}
      strokeLinecap="round" strokeLinejoin="round" style={style}
      xmlns="http://www.w3.org/2000/svg">
      {d ? <path d={d} fill={fill || "none"} /> : children}
    </svg>
  );
}
const Icons = {
  chevL:   (p) => <Ic {...p} d="M15 5l-7 7 7 7" />,
  chevR:   (p) => <Ic {...p} d="M9 5l7 7-7 7" />,
  chevD:   (p) => <Ic {...p} d="M6 9l6 6 6-6" />,
  plus:    (p) => <Ic {...p} d="M12 5v14M5 12h14" />,
  arrowUp: (p) => <Ic {...p} d="M12 19V6M6 12l6-6 6 6" />,
  edit:    (p) => <Ic {...p}>{<><path d="M4.5 19.5h15" /><path d="M16.2 4.3a1.9 1.9 0 0 1 2.7 2.7L8.5 17.4 4.5 18.5l1.1-4z" /></>}</Ic>,
  history: (p) => <Ic {...p}>{<><path d="M3.5 12a8.5 8.5 0 1 0 2.6-6.1L3.5 8" /><path d="M3.5 4v4h4" /><path d="M12 7.5V12l3 2" /></>}</Ic>,
  gear:    (p) => <Ic {...p}>{<><circle cx="12" cy="12" r="3.1" /><path d="M12 3.2v2.1M12 18.7v2.1M5.2 5.2l1.5 1.5M17.3 17.3l1.5 1.5M3.2 12h2.1M18.7 12h2.1M5.2 18.8l1.5-1.5M17.3 6.7l1.5-1.5" /></>}</Ic>,
  copy:    (p) => <Ic {...p}>{<><rect x="8.5" y="8.5" width="11" height="11" rx="2.3" /><path d="M5.5 15.5H5a1.5 1.5 0 0 1-1.5-1.5V5A1.5 1.5 0 0 1 5 3.5h9A1.5 1.5 0 0 1 15.5 5v.5" /></>}</Ic>,
  retry:   (p) => <Ic {...p}>{<><path d="M20 5v6h-6" /><path d="M19 11a8 8 0 1 0-2 5.3" /></>}</Ic>,
  tts:     (p) => <Ic {...p}>{<><path d="M11 5 6 9H3v6h3l5 4z" /><path d="M16 9a4 4 0 0 1 0 6" /><path d="M19.2 6.4a8 8 0 0 1 0 11.2" /></>}</Ic>,
  more:    (p) => <Ic {...p} fill="currentColor" sw={0}>{<><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></>}</Ic>,
  wrench:  (p) => <Ic {...p} d="M14.7 6.3a4 4 0 0 0-5.3 5.3L3.7 17.3a1.8 1.8 0 0 0 2.5 2.5l5.7-5.7a4 4 0 0 0 5.3-5.3L15 10.5 13.5 9 16 6.3z" />,
  search:  (p) => <Ic {...p}>{<><circle cx="11" cy="11" r="6.5" /><path d="M20 20l-4.3-4.3" /></>}</Ic>,
  check:   (p) => <Ic {...p} d="M5 12.5 10 17l9-10" />,
  qmark:   (p) => <Ic {...p}>{<><circle cx="12" cy="12" r="9" /><path d="M9.4 9.2a2.6 2.6 0 0 1 5 .2c0 1.6-2.2 2-2.4 3.4" /><path d="M12 16.6h.01" /></>}</Ic>,
  sun:     (p) => <Ic {...p}>{<><circle cx="12" cy="12" r="4" /><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5 5l1.4 1.4M17.6 17.6 19 19M5 19l1.4-1.4M17.6 6.4 19 5" /></>}</Ic>,
  moon:    (p) => <Ic {...p} d="M20 13.5A8 8 0 0 1 10.5 4a8 8 0 1 0 9.5 9.5Z" />,
  cpu:     (p) => <Ic {...p}>{<><rect x="6.5" y="6.5" width="11" height="11" rx="2.2" /><rect x="10" y="10" width="4" height="4" rx="1" /><path d="M9 3.5v2M15 3.5v2M9 18.5v2M15 18.5v2M3.5 9h2M3.5 15h2M18.5 9h2M18.5 15h2" /></>}</Ic>,
  brain:   (p) => <Ic {...p}>{<><path d="M9.5 4.5a2.6 2.6 0 0 0-2.5 2 2.6 2.6 0 0 0-1.6 4.2A2.8 2.8 0 0 0 6.8 16a2.5 2.5 0 0 0 2.7 2.2c.9 0 1.6-.4 1.6-1V5.6c0-.7-.7-1.1-1.6-1.1Z" /><path d="M14.5 4.5a2.6 2.6 0 0 1 2.5 2 2.6 2.6 0 0 1 1.6 4.2A2.8 2.8 0 0 1 17.2 16a2.5 2.5 0 0 1-2.7 2.2c-.9 0-1.6-.4-1.6-1V5.6c0-.7.7-1.1 1.6-1.1Z" /></>}</Ic>,
  spark:   (p) => <Ic {...p} fill="currentColor" sw={0} d="M12 3l1.7 5.1L19 9.8l-4.4 3 1.6 5.2L12 14.9 7.8 18l1.6-5.2L5 9.8l5.3-.7z" />,
  bell:    (p) => <Ic {...p}>{<><path d="M18 8a6 6 0 1 0-12 0c0 7-2.5 9-2.5 9h17S18 15 18 8Z" /><path d="M10.3 20.5a2 2 0 0 0 3.4 0" /></>}</Ic>,
  user:    (p) => <Ic {...p}>{<><circle cx="12" cy="8" r="3.4" /><path d="M5.5 19.5a6.5 6.5 0 0 1 13 0" /></>}</Ic>,
  globe:   (p) => <Ic {...p}>{<><circle cx="12" cy="12" r="8.5" /><path d="M3.5 12h17M12 3.5c2.4 2.3 3.6 5.3 3.6 8.5S14.4 18.2 12 20.5C9.6 18.2 8.4 15.2 8.4 12S9.6 5.8 12 3.5Z" /></>}</Ic>,
  shield:  (p) => <Ic {...p} d="M12 3.5 5 6v5.5c0 4.3 3 7.4 7 9 4-1.6 7-4.7 7-9V6z" />,
  link:    (p) => <Ic {...p}>{<><path d="M10 13.5a3.5 3.5 0 0 0 5 0l2.5-2.5a3.5 3.5 0 0 0-5-5L11 7.5" /><path d="M14 10.5a3.5 3.5 0 0 0-5 0L6.5 13a3.5 3.5 0 0 0 5 5L13 16.5" /></>}</Ic>,
  info:    (p) => <Ic {...p}>{<><circle cx="12" cy="12" r="8.4" /><path d="M12 11v5M12 8h.01" /></>}</Ic>,
  trash:   (p) => <Ic {...p}>{<><path d="M4.5 7h15M9 7V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V7M6.5 7l.8 11.3A1.6 1.6 0 0 0 8.9 20h6.2a1.6 1.6 0 0 0 1.6-1.7L17.5 7" /></>}</Ic>,
  pin:     (p) => <Ic {...p}>{<><path d="M12 21v-7" /><path d="M9 3h6l-.7 4.2 2.7 2.3v2.5H7V9.5l2.7-2.3z" /></>}</Ic>,
  image:   (p) => <Ic {...p}>{<><rect x="3.5" y="5" width="17" height="14" rx="2.2" /><circle cx="9" cy="10" r="1.6" /><path d="M20 15.5l-4.5-4.5L7 19.5" /></>}</Ic>,
  clip:    (p) => <Ic {...p} d="M20.4 11.3l-8 8a4.2 4.2 0 0 1-5.9-5.9l8.5-8.5a2.8 2.8 0 0 1 3.9 3.9l-8.5 8.5a1.4 1.4 0 0 1-2-2l7.6-7.6" />,
  close:   (p) => <Ic {...p} d="M6 6l12 12M18 6L6 18" />,
};

/* ---------------------------------------------------------------- status -- */
function StatusBar() {
  const ink = "var(--ink)";
  return (
    <div style={{
      height: 44, flex: "none", display: "flex", alignItems: "center",
      justifyContent: "space-between", padding: "0 24px 0 28px",
      color: ink, position: "relative", zIndex: 5,
    }}>
      <span className="mono" style={{ fontSize: 14.5, fontWeight: 600, letterSpacing: "0.02em" }}>9:41</span>
      <div style={{ display: "flex", alignItems: "center", gap: 7 }}>
        <svg width="17" height="11" viewBox="0 0 18 12" fill={ink}><rect x="0" y="7" width="3" height="5" rx="1"/><rect x="5" y="4.5" width="3" height="7.5" rx="1"/><rect x="10" y="2" width="3" height="10" rx="1"/><rect x="15" y="0" width="3" height="12" rx="1"/></svg>
        <svg width="16" height="11" viewBox="0 0 17 12" fill={ink}><path d="M8.5 2.2c2.7 0 5.2 1 7.1 2.7l1.4-1.6A12.4 12.4 0 0 0 8.5 0 12.4 12.4 0 0 0 0 3.3l1.4 1.6A10.4 10.4 0 0 1 8.5 2.2Z"/><path d="M8.5 6c1.5 0 2.9.5 4 1.5l1.4-1.6A8.3 8.3 0 0 0 8.5 3.8 8.3 8.3 0 0 0 3.1 5.9L4.5 7.5A6.3 6.3 0 0 1 8.5 6Z"/><path d="m8.5 12 2.6-3a3.9 3.9 0 0 0-5.2 0Z"/></svg>
        <svg width="25" height="12" viewBox="0 0 26 13" fill="none"><rect x="0.5" y="0.5" width="22" height="12" rx="3.2" stroke={ink} opacity="0.4"/><rect x="2.2" y="2.2" width="18.6" height="8.6" rx="2" fill={ink}/><rect x="24" y="4" width="2" height="5" rx="1" fill={ink} opacity="0.4"/></svg>
      </div>
    </div>
  );
}

/* ---------------------------------------------------------------- shell --- */
// Shared override context: when `locked` is true, every PhoneScreen adopts the
// global { theme, accent } (the Tweaks mix-and-match); otherwise each screen
// keeps its own designed combo.
const AmbCtx = React.createContext(null);

// accent-ink: text/icon color drawn ON the accent. Light-ish accents (green)
// need dark ink; saturated mid-tones take white.
const ACCENT_INK = {
  "#b8623a": "#ffffff", "#5e9c6e": "#0f150e", "#4f86d6": "#ffffff",
  "#9277c4": "#ffffff", "#c2607a": "#ffffff", "#c79a4a": "#1a1408",
};
function inkFor(hex) { return ACCENT_INK[(hex || "").toLowerCase()] || "#ffffff"; }

// Bare rounded phone screen (no heavy bezel) — matches the design-canvas
// convention used elsewhere. 380×832.
//   theme  = light | dark | sage | sage-dark   (base neutrals)
//   accent = hex string                         (accent, applied inline)
function PhoneScreen({ theme = "light", accent = "#b8623a", footerBg = "var(--bg)", children }) {
  const ov = React.useContext(AmbCtx);
  const t = ov && ov.locked ? ov.theme : theme;
  const a = ov && ov.locked ? ov.accent : accent;
  return (
    <div className="amb noscroll" data-theme={t} style={{
      width: 380, height: 832, borderRadius: 40, overflow: "hidden",
      position: "relative", display: "flex", flexDirection: "column",
      background: "var(--bg)",
      boxShadow: "0 1px 0 rgba(0,0,0,0.03) inset",
      "--accent": a, "--accent-ink": inkFor(a),
    }}>
      <StatusBar />
      <div style={{ flex: 1, minHeight: 0, position: "relative", display: "flex", flexDirection: "column" }}>
        {children}
      </div>
      <HomeIndicator bg={footerBg} />
    </div>
  );
}

function HomeIndicator({ bg = "var(--bg)" }) {
  return (
    <div style={{ height: 24, flex: "none", display: "flex", alignItems: "center", justifyContent: "center",
      background: bg }}>
      <div style={{ width: 128, height: 5, borderRadius: 3, background: "var(--ink)", opacity: 0.28 }} />
    </div>
  );
}

/* ---------------------------------------------------------------- atoms --- */
function Cursor({ kind }) { return <span className={"cursor " + (kind || "")} />; }
function Dot({ idle }) { return <span className={"dot" + (idle ? " idle" : "")} />; }

// Amber wordmark — mono lowercase + accent cursor block. The "terminal" logo.
function Wordmark({ size = 21, withCursor = true }) {
  return (
    <span className="mono" style={{ fontSize: size, fontWeight: 700, letterSpacing: "-0.02em",
      color: "var(--ink)", display: "inline-flex", alignItems: "baseline" }}>
      amber{withCursor && <Cursor kind="accent" />}
    </span>
  );
}

Object.assign(window, { Icons, StatusBar, PhoneScreen, HomeIndicator, Cursor, Dot, Wordmark, AmbCtx, inkFor });
