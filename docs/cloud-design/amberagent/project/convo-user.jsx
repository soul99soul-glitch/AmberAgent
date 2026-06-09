// convo-bubbles.jsx — Self-contained conversation pieces (user + agent + chunks).
// Reads colors from a single theme `t` passed in by the parent screen.

function UserTurn({ t, text }) {
  return (
    <div style={{ padding: '4px 20px 14px' }}>
      {/* bubble — capsule, right-aligned */}
      <div style={{
        display: 'flex', justifyContent: 'flex-end',
        paddingLeft: 60,
      }}>
        <div style={{
          background: t.userBubble,
          border: `1px solid ${t.userBubbleEdge}`,
          borderRadius: 999,
          padding: '10px 18px',
          fontFamily: t.bodyFont, fontSize: 15, lineHeight: 1.5,
          color: t.ink, letterSpacing: 0.2,
          maxWidth: '100%',
        }}>{text}</div>
      </div>
    </div>
  );
}

function IconBtn({ t, children }) {
  return (
    <div style={{
      width: 22, height: 22, display: 'flex',
      alignItems: 'center', justifyContent: 'center',
      color: t.inkSoft, cursor: 'default',
    }}>{children}</div>
  );
}

window.UserTurn = UserTurn;
