/* global React, Icons, Cursor, Dot, Wordmark */
// aa-chat.jsx — Amber home (empty) + conversation states, terminal-modern skin.
const { useState: useStateChat } = React;

/* ----------------------------------------------------------- markdown ----- */
function mdInline(text, key) {
  const parts = []; const re = /(\*\*[^*]+\*\*|`[^`]+`)/g;
  let last = 0, m, i = 0;
  while ((m = re.exec(text))) {
    if (m.index > last) parts.push(text.slice(last, m.index));
    const tok = m[0];
    if (tok.startsWith("**")) parts.push(<strong key={key + "b" + i}>{tok.slice(2, -2)}</strong>);
    else parts.push(<code key={key + "c" + i}>{tok.slice(1, -1)}</code>);
    last = m.index + tok.length; i++;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts;
}
function Markdown({ text }) {
  const blocks = text.split("\n").reduce((acc, line) => {
    if (/^\s*-\s+/.test(line)) {
      const item = line.replace(/^\s*-\s+/, "");
      if (acc.length && acc[acc.length - 1].t === "ul") acc[acc.length - 1].items.push(item);
      else acc.push({ t: "ul", items: [item] });
    } else if (line.trim() === "") { acc.push({ t: "sp" }); }
    else {
      if (acc.length && acc[acc.length - 1].t === "p") acc[acc.length - 1].text += "\n" + line;
      else acc.push({ t: "p", text: line });
    }
    return acc;
  }, []);
  return (
    <div className="md cn">
      {blocks.map((b, i) => {
        if (b.t === "ul") return <ul key={i}>{b.items.map((it, j) => <li key={j}>{mdInline(it, i + "-" + j)}</li>)}</ul>;
        if (b.t === "sp") return null;
        return <p key={i}>{b.text.split("\n").map((ln, j) => <React.Fragment key={j}>{j > 0 && <br />}{mdInline(ln, i + "_" + j)}</React.Fragment>)}</p>;
      })}
    </div>
  );
}

/* -------------------------------------------------------- context ring ---- */
// Tiny mono usage readout — terminal-style, no donut glow. Tap reveals counts.
function ContextMeter({ used = 62, total = 200, open = false }) {
  const pct = Math.round((used / total) * 100);
  return (
    <button className="pressable mono" style={{ display: "flex", alignItems: "center", gap: 7,
      background: "none", border: "none", cursor: "pointer", padding: "3px 0", color: "var(--ink-3)" }}>
      <span style={{ display: "flex", gap: 2, alignItems: "flex-end" }}>
        {[0,1,2,3,4].map(i => (
          <span key={i} style={{ width: 2.5, height: 9, borderRadius: 1,
            background: (i / 5) < (used / total) ? "var(--accent)" : "var(--line-2)" }} />
        ))}
      </span>
      <span style={{ fontSize: 11.5, letterSpacing: 0, color: "var(--ink-3)" }}>{pct}%</span>
    </button>
  );
}

/* ------------------------------------------------------------ thinking ---- */
function ThinkingStrip({ seconds = "5.4", mode = "auto", thoughts, defaultOpen = false }) {
  const [open, setOpen] = useStateChat(defaultOpen);
  const lines = thoughts || [
    "用户说\u201c随便聊\u201d——把选择权交给我了。",
    "找个轻松又有嚼劲的题目：\u201cAI 助手该像工具还是朋友\u201d最近讨论得多，挺有共鸣。",
    "不如设四个视角直接辩，比我自己讲有意思。",
  ];
  return (
    <div style={{ marginBottom: 13 }}>
      <button className="pressable" onClick={() => setOpen(o => !o)}
        style={{ display: "flex", alignItems: "center", gap: 8, background: "none", border: "none",
          cursor: "pointer", padding: "2px 0", color: "var(--ink-3)", whiteSpace: "nowrap" }}>
        <Icons.brain size={14} sw={1.7} style={{ color: "var(--ink-3)", flex: "none" }} />
        <span className="mono" style={{ fontSize: 12.5, fontWeight: 600, letterSpacing: 0 }}>Thinking</span>
        <span className="mono" style={{ fontSize: 11.5, color: "var(--ink-4)", letterSpacing: 0 }}>{seconds}s · {mode}</span>
        <Icons.chevD size={13} sw={2} style={{ color: "var(--ink-4)", transition: "transform .22s",
          transform: open ? "rotate(180deg)" : "none" }} />
      </button>
      {open && (
        <div className="cn" style={{ marginTop: 10, marginLeft: 7, paddingLeft: 14,
          borderLeft: "2px solid var(--line-2)", fontSize: 13, lineHeight: 1.72,
          color: "var(--ink-3)", display: "flex", flexDirection: "column", gap: 7,
          animation: "fadeRise .25s ease" }}>
          {lines.map((l, i) => <div key={i}>{l}</div>)}
        </div>
      )}
    </div>
  );
}

/* ------------------------------------------------------------ tool call --- */
function ToolCall({ name = "web_search", arg, status = "done" }) {
  const color = status === "done" ? "var(--signal)" : status === "run" ? "var(--accent)" : "var(--ink-4)";
  return (
    <div className="mono" style={{ display: "flex", alignItems: "center", gap: 9, padding: "8px 12px",
      background: "var(--code-bg)", borderRadius: 9, fontSize: 12.5, letterSpacing: 0, marginBottom: 11 }}>
      <span style={{ width: 7, height: 7, borderRadius: 2, background: color, flex: "none" }} />
      <span style={{ color: "var(--accent)", fontWeight: 600 }}>{name}</span>
      {arg && <span style={{ color: "var(--ink-3)", flex: 1, minWidth: 0, overflow: "hidden",
        textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{arg}</span>}
      <span style={{ flex: arg ? "none" : 1 }} />
      {status === "done" && <Icons.check size={14} style={{ color: "var(--signal)" }} />}
      {status === "run" && <span className="dot" />}
    </div>
  );
}

/* ------------------------------------------------------------ ask user ---- */
function AskUserCard({ count = 2, questions }) {
  const qs = questions || [
    { q: "想让这场辩论更偏哪种气质？", options: ["犀利对撞", "温和共识", "天马行空"] },
    { q: "篇幅控制在多长？", options: ["三五句各表态", "展开来讲", "你来定"] },
  ];
  return (
    <div style={{ marginBottom: 4 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 9, color: "var(--accent)", marginBottom: 14 }}>
        <Icons.qmark size={15} sw={1.8} />
        <span className="cn" style={{ flex: 1, fontSize: 14, fontWeight: 600, letterSpacing: 0 }}>
          询问 {count} 个问题
        </span>
        <Icons.chevD size={13} sw={2} style={{ transform: "rotate(180deg)" }} />
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        {qs.map((q, qi) => (
          <div key={qi}>
            {qi > 0 && <div style={{ height: 1, background: "var(--line)", marginBottom: 16 }} />}
            <div className="cn" style={{ fontSize: 14, color: "var(--ink)", marginBottom: 11, lineHeight: 1.5 }}>{q.q}</div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
              {q.options.map((opt, oi) => (
                <button key={oi} className="pressable cn" style={{ background: "var(--surface)",
                  border: "1px solid var(--line-2)", borderRadius: 999, padding: "8px 14px",
                  fontSize: 13, color: "var(--ink-2)", cursor: "pointer", letterSpacing: 0 }}>{opt}</button>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------ messages ---- */
function UserBubble({ text }) {
  return (
    <div style={{ display: "flex", justifyContent: "flex-end", animation: "fadeRise .3s ease" }}>
      <div className="cn" style={{ maxWidth: "82%", background: "var(--user-bg)", color: "var(--user-ink)",
        padding: "11px 15px", borderRadius: "16px 16px 5px 16px", fontSize: 15, lineHeight: 1.55 }}>{text}</div>
    </div>
  );
}

function AgentTurn({ children, streaming }) {
  return (
    <div style={{ animation: "fadeRise .3s ease" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 7, marginBottom: 11 }}>
        <span className="mono" style={{ fontSize: 12, fontWeight: 700, color: "var(--accent)", letterSpacing: 0 }}>amber</span>
        {streaming && <Cursor kind="accent" />}
      </div>
      <div>{children}</div>
    </div>
  );
}

function AgentBody({ text, streaming }) {
  return (
    <div style={{ fontSize: 15, lineHeight: 1.66, color: "var(--ink)" }}>
      <Markdown text={text} />
      {streaming && <Cursor />}
    </div>
  );
}

function AgentActions() {
  const items = [Icons.copy, Icons.retry, Icons.tts, Icons.more];
  return (
    <div style={{ display: "flex", gap: 20, alignItems: "center", marginTop: 14, color: "var(--ink-3)" }}>
      {items.map((I, i) => (
        <button key={i} className="pressable" style={{ background: "none", border: "none", cursor: "pointer",
          padding: 0, color: "inherit", display: "flex" }}><I size={17} /></button>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------- header ----- */
// Two-line title block à la OpenCode: bold session title + mono model id.
// The model row is the menu trigger (chevron). 
function ChatHeader({ title = "新会话", model = "claude-sonnet-4-5", back, withMeter = true, onModelTap, menuOpen, onLeft, onEdit }) {
  return (
    <div className="hair" style={{ flex: "none", display: "flex", alignItems: "center", gap: 10,
      padding: "2px 14px 11px" }}>
      {back
        ? <button className="pressable" onClick={onLeft} style={{ background: "none", border: "none", color: "var(--ink)",
            cursor: "pointer", display: "flex", padding: 6, margin: -6 }}><Icons.chevL size={24} /></button>
        : <button className="pressable" onClick={onLeft} style={{ background: "none", border: "none", color: "var(--ink-2)",
            cursor: "pointer", display: "flex", padding: 6, margin: "-6px 0 -6px -6px" }}><Icons.history size={22} /></button>}
      <button className="pressable" onClick={onModelTap} style={{ display: "flex", flexDirection: "column",
        alignItems: "flex-start", gap: 1, flex: 1, minWidth: 0, background: "none", border: "none",
        cursor: "pointer", marginLeft: 2 }}>
        <span className="cn" style={{ fontSize: 16, fontWeight: 700, letterSpacing: "-0.01em", color: "var(--ink)" }}>{title}</span>
        <span style={{ display: "flex", alignItems: "center", gap: 4 }}>
          <span className="mono" style={{ fontSize: 12, fontWeight: 500, color: "var(--ink-3)", letterSpacing: 0, whiteSpace: "nowrap" }}>{model}</span>
          <Icons.chevD size={13} sw={2} style={{ color: "var(--ink-4)",
            transition: "transform .26s", transform: menuOpen ? "rotate(180deg)" : "none" }} />
        </span>
      </button>
      {withMeter && <ContextMeter />}
      <button className="pressable" onClick={onEdit} style={{ background: "none", border: "none", color: "var(--ink-2)",
        cursor: "pointer", display: "flex", padding: 6, margin: "-6px -2px -6px 4px" }}><Icons.edit size={21} /></button>
    </div>
  );
}

/* ------------------------------------------------------------- input ------ */
// Composer matching OpenCode: three separate rounded surfaces with gaps —
// circular [+] · pill input · circular [↑] send. Amber's slash-command lives
// at the right edge of the center pill so the feature is preserved.
function InputBar({ placeholder = "输入消息", active = false, interactive = false, value = "", onChange, onSend, onPlus, onSlash }) {
  const has = interactive ? value.trim().length > 0 : active;
  const [expanded, setExpanded] = useStateChat(false);
  const send = () => { if (interactive && value.trim() && onSend) onSend(); };
  const surf = { background: "var(--surface-2)", border: "1px solid var(--line)" };
  const tool = (Ico, label) => (
    <button className="pressable" title={label} style={{ flex: "none", width: 40, height: 48, border: "none",
      background: "transparent", color: "var(--ink-2)", cursor: "pointer", display: "flex",
      alignItems: "center", justifyContent: "center" }}><Ico size={21} /></button>
  );
  return (
    <div style={{ flex: "none", borderTop: "1px solid var(--line)", background: "var(--surface)",
      padding: "10px 14px 14px", display: "flex", alignItems: "flex-end", gap: 9 }}>
      {/* attach capsule — morphs from [+] to [×|image|clip] */}
      <div style={{ ...surf, flex: "none", height: 48, borderRadius: 999, display: "flex", alignItems: "center",
        overflow: "hidden", transition: "width .3s cubic-bezier(.2,.85,.25,1)",
        width: expanded ? 144 : 48 }}>
        <button className="pressable" onClick={() => { setExpanded(e => !e); onPlus && onPlus(); }} title="附件"
          style={{ flex: "none", width: 46, height: 48, border: "none", background: "transparent",
            color: "var(--ink-2)", cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center" }}>
          <Icons.plus size={22} style={{ transition: "transform .3s cubic-bezier(.2,.85,.25,1)",
            transform: expanded ? "rotate(135deg)" : "none" }} /></button>
        <div style={{ display: "flex", alignItems: "center", opacity: expanded ? 1 : 0,
          transition: "opacity .22s ease", pointerEvents: expanded ? "auto" : "none" }}>
          {tool(Icons.image, "图片")}
          {tool(Icons.clip, "文件")}
        </div>
      </div>

      {/* center input pill */}
      <div style={{ flex: 1, minWidth: 0, display: "flex", alignItems: "flex-end",
        ...surf, padding: "0 18px", borderRadius: 26, minHeight: 48 }}>
        {interactive
          ? <textarea value={value} onChange={e => onChange && onChange(e.target.value)} rows={1}
              onKeyDown={e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); } }}
              placeholder={placeholder} spellCheck={false} className="cn"
              style={{ flex: 1, border: "none", outline: "none", background: "transparent", resize: "none",
                color: "var(--ink)", fontSize: 15.5, fontFamily: "var(--font-cn)", lineHeight: 1.4,
                maxHeight: 92, padding: "14px 0", minWidth: 0 }} />
          : <div className="cn" style={{ flex: 1, fontSize: 15.5, color: active ? "var(--ink)" : "var(--ink-4)",
              padding: "14px 0", lineHeight: 1.4 }}>{placeholder}</div>}
      </div>

      {/* send (separate circle) */}
      <button onClick={send} className="pressable" style={{ flex: "none", width: 48, height: 48, borderRadius: 999,
        cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center",
        border: has ? "1px solid var(--accent)" : "1px solid var(--line)",
        background: has ? "var(--accent)" : "var(--surface-2)",
        color: has ? "var(--accent-ink)" : "var(--ink-4)",
        transition: "background-color .18s ease, color .18s ease, border-color .18s ease" }}>
        <Icons.arrowUp size={21} sw={2.2} /></button>
    </div>
  );
}

/* =========================================================================
   SCREEN: Home / empty state
   ========================================================================= */
function HomeScreen({ theme = "light", accent }) {
  const chips = ["陪我把今天捋一捋", "解释一个概念", "帮我润色这段话", "随便聊聊"];
  return (
    <PhoneScreen theme={theme} accent={accent}>
      <ChatHeader withMeter={false} />
      <div style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column",
        alignItems: "center", justifyContent: "center", padding: "0 30px", gap: 18 }}>
        <Wordmark size={30} />
        <div className="cn" style={{ fontSize: 21, fontWeight: 500, color: "var(--ink)", letterSpacing: "-0.01em",
          textAlign: "center", lineHeight: 1.4 }}>晚上好，光</div>
        <div className="cn" style={{ fontSize: 13.5, color: "var(--ink-3)", textAlign: "center", maxWidth: 250,
          lineHeight: 1.6, marginTop: -6 }}>今天想聊点什么？或者从下面挑一个开始。</div>
        <div style={{ display: "flex", flexWrap: "wrap", gap: 9, justifyContent: "center", maxWidth: 300, marginTop: 6 }}>
          {chips.map(s => (
            <button key={s} className="pressable cn" style={{ padding: "8px 14px", borderRadius: 999,
              border: "1px solid var(--line-2)", background: "var(--surface)", color: "var(--ink-2)",
              fontSize: 13, cursor: "pointer", letterSpacing: 0 }}>{s}</button>
          ))}
        </div>
      </div>
      <InputBar />
    </PhoneScreen>
  );
}

/* =========================================================================
   SCREEN: Conversation — thinking + tool + reply + actions
   ========================================================================= */
function ConversationScreen({ theme = "light", accent, openSheet = false }) {
  const [sel, setSel] = useStateChat({ provider: "anthropic", model: "claude-sonnet-4-5" });
  const [open, setOpen] = useStateChat(openSheet);
  return (
    <PhoneScreen theme={theme} accent={accent}>
      <ChatHeader title="随便聊聊" model={sel.model} back menuOpen={open} onModelTap={() => setOpen(o => !o)} />
      {/* anchor: starts right below the header so the menu drops down from there */}
      <div style={{ position: "relative", flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
        <div className="noscroll" style={{ flex: 1, overflowY: "hidden", minHeight: 0,
          padding: "20px 20px 6px", display: "flex", flexDirection: "column", gap: 22 }}>
          <UserBubble text="随便跟我聊点有意思的吧" />
          <AgentTurn>
            <ThinkingStrip defaultOpen />
            <ToolCall name="web_search" arg="AI 助手 工具还是朋友 讨论" status="done" />
            <AgentBody text={"给你摆个四方辩台，主题是 **AI 助手该像工具，还是像朋友**：\n\n- **实用派**：它就是个高级扳手——别谈感情，把事办利索。\n- **共情派**：人会对回应方式上瘾，温度本身就是功能。\n- **警惕派**：越像朋友，越容易让你交出判断力。\n- **解构派**：\u201c工具 / 朋友\u201d是假对立，它其实是面镜子。"} />
            <AgentActions />
          </AgentTurn>
        </div>
        <InputBar />
        <TopModelMenu open={open} providerId={sel.provider} modelId={sel.model}
          onSelect={(p, m) => { setSel({ provider: p.id, model: m.id }); setOpen(false); }}
          onClose={() => setOpen(false)} />
      </div>
    </PhoneScreen>
  );
}

/* =========================================================================
   SCREEN: Conversation — ask-user + streaming
   ========================================================================= */
function AskScreen({ theme = "light", accent }) {
  return (
    <PhoneScreen theme={theme} accent={accent}>
      <ChatHeader title="出差行程" model="claude-sonnet-4-5" back />
      <div className="noscroll" style={{ flex: 1, overflowY: "hidden", minHeight: 0,
        padding: "20px 20px 6px", display: "flex", flexDirection: "column", gap: 22 }}>
        <UserBubble text="帮我安排下周一去上海出差的行程" />
        <AgentTurn streaming>
          <ThinkingStrip seconds="2.1" mode="auto" thoughts={[
            "信息不全：不知道出发城市、几点的会、要不要订酒店。",
            "与其乱猜，不如先问清三件事，再给方案。",
          ]} />
          <AskUserCard count={2} questions={[
            { q: "从哪座城市出发？大概几点要到？", options: ["北京 · 上午到", "深圳 · 中午前", "其它"] },
            { q: "需要我一起把酒店和往返高铁也订了吗？", options: ["都要", "只订高铁", "先看方案"] },
          ]} />
        </AgentTurn>
      </div>
      <InputBar active />
    </PhoneScreen>
  );
}

Object.assign(window, {
  Markdown, ContextMeter, ThinkingStrip, ToolCall, AskUserCard, UserBubble, AgentTurn,
  AgentBody, AgentActions, ChatHeader, InputBar, HomeScreen, ConversationScreen, AskScreen,
});
