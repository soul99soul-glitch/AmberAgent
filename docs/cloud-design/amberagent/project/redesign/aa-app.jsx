/* global React, Icons, PhoneScreen, Wordmark, ChatHeader, InputBar, UserBubble, AgentTurn,
   AgentBody, AgentActions, ThinkingStrip, ToolCall, TopModelMenu, PROVIDERS, SectionLabel,
   SRow, Seg, Toggle, ACCENTS, PageHeader, IconBtn, Body, SessionsView, SEED_SESSIONS,
   ProvidersView, ProviderDetailView, PROVIDER_CFG, SkillsView, SEED_SKILLS, PrivacyView,
   useTweaks, TweaksPanel, TweakSection, TweakRadio, TweakColor */
// aa-app.jsx — interactive prototype shell: Frame + router + wired views.
const { useState: useS, useEffect: useE, useRef: useR } = React;

/* ----------------------------------------------------- reply generator ---- */
function makeReply(prompt) {
  const p = (prompt || "").toLowerCase();
  if (/出差|行程|安排|订|高铁|酒店/.test(prompt))
    return { thinking: ["信息还不全：出发城市、到达时间、要不要订住宿。", "先问清楚再给方案，省得返工。"],
      ask: true, text: "" };
  if (/润色|改写|修改|这段话/.test(prompt))
    return { thinking: ["先判断语气目标：更口语还是更正式。", "保留原意，缩短长句，去掉书面词。"],
      tool: null, text: "改好了，给你三版：\n\n- **自然版**：日常聊天的语气，最顺口。\n- **简洁版**：砍掉一半字，只留核心。\n- **正式版**：用于邮件或汇报。\n\n你贴一下原文，我直接改。" };
  if (/解释|什么是|原理|概念|机制/.test(prompt))
    return { thinking: ["找个贴近生活的比喻，别堆术语。", "先给一句话结论，再展开。"],
      tool: { name: "web_search", arg: prompt.slice(0, 16), status: "done" },
      text: "一句话：**它在决定\u201c该重点看哪部分\u201d**。\n\n- 把输入拆成很多小块\n- 给每块算一个\u201c相关度\u201d权重\n- 按权重加权汇总，重要的被放大\n\n要不要我用一个点菜的例子再讲细一点？" };
  return { thinking: ["对方比较开放，给个有嚼劲又轻松的切入。", "用几个对立视角直接铺开，比平铺更有意思。"],
    tool: { name: "web_search", arg: "热门讨论 观点", status: "done" },
    text: "给你摆个四方辩台，主题是 **AI 助手该像工具，还是像朋友**：\n\n- **实用派**：它就是个高级扳手——别谈感情，把事办利索。\n- **共情派**：人会对回应方式上瘾，温度本身就是功能。\n- **警惕派**：越像朋友，越容易让你交出判断力。\n- **解构派**：\u201c工具 / 朋友\u201d是假对立，它其实是面镜子。\n\n想从哪个角度先聊？" };
}

const SEEDED_CONVOS = {
  s1: [{ role: "user", text: "随便跟我聊点有意思的吧" }, { role: "assistant", ...makeReply("随便") }],
  s2: [{ role: "user", text: "帮我安排下周一去上海出差的行程" }, { role: "assistant", ...makeReply("出差行程") }],
  s3: [{ role: "user", text: "帮我把这段话润色得更口语一点" }, { role: "assistant", ...makeReply("润色这段话") }],
  s4: [{ role: "user", text: "解释一下注意力机制" }, { role: "assistant", ...makeReply("解释注意力机制") }],
};

/* ------------------------------------------------------------ ask card ---- */
function AskInline({ onPick }) {
  const qs = [
    { q: "从哪座城市出发？大概几点要到？", options: ["北京 · 上午到", "深圳 · 中午前", "其它"] },
    { q: "需要我把酒店和往返高铁也订了吗？", options: ["都要", "只订高铁", "先看方案"] },
  ];
  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: 9, color: "var(--accent)", marginBottom: 14 }}>
        <Icons.qmark size={15} sw={1.8} />
        <span className="cn" style={{ fontSize: 14, fontWeight: 600 }}>询问 2 个问题</span>
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
        {qs.map((q, qi) => (
          <div key={qi}>
            {qi > 0 && <div style={{ height: 1, background: "var(--line)", marginBottom: 16 }} />}
            <div className="cn" style={{ fontSize: 14, color: "var(--ink)", marginBottom: 11, lineHeight: 1.5 }}>{q.q}</div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
              {q.options.map((opt, oi) => (
                <button key={oi} className="pressable cn" onClick={() => onPick(opt)}
                  style={{ background: "var(--surface)", border: "1px solid var(--line-2)", borderRadius: 999,
                    padding: "8px 14px", fontSize: 13, color: "var(--ink-2)", cursor: "pointer" }}>{opt}</button>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------ pending ----- */
function PendingTurn() {
  return (
    <div style={{ animation: "fadeRise .3s ease" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 7, marginBottom: 11 }}>
        <span className="mono" style={{ fontSize: 12, fontWeight: 700, color: "var(--accent)" }}>amber</span>
      </div>
      <div style={{ display: "flex", alignItems: "center", gap: 8, color: "var(--ink-3)" }}>
        <Icons.brain size={14} style={{ color: "var(--ink-3)" }} />
        <span className="mono" style={{ fontSize: 12.5, fontWeight: 600 }}>Thinking</span>
        <span className="thinkdots" style={{ display: "inline-flex", gap: 3 }}>
          {[0, 1, 2].map(i => <span key={i} style={{ width: 4, height: 4, borderRadius: 9, background: "var(--ink-4)",
            animation: `blink 1.1s ${i * 0.18}s infinite` }} />)}
        </span>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------ chat turn --- */
function AssistantTurn({ m }) {
  return (
    <AgentTurn>
      {m.thinking && <ThinkingStrip seconds={m.sec || "3.2"} thoughts={m.thinking} />}
      {m.tool && <ToolCall name={m.tool.name} arg={m.tool.arg} status={m.tool.status || "done"} />}
      {m.ask && <AskCardWrap />}
      {m.text && <AgentBody text={m.text} />}
      {m.text && <AgentActions />}
    </AgentTurn>
  );
}
function AskCardWrap() {
  const [picked, setPicked] = useS(null);
  return picked
    ? <AgentBody text={`好的，按\u201c**${picked}**\u201d来安排。我先拉个草稿：上午到 → 直接去会场，午餐订在附近，傍晚的高铁返程。要我把时间点列出来吗？`} />
    : <AskInline onPick={setPicked} />;
}

/* =========================================================================
   SCREEN: Home (empty state)
   ========================================================================= */
function HomeScreenApp({ model, onModel, onStart, onSessions, nav }) {
  const [menu, setMenu] = useS(false);
  const [text, setText] = useS("");
  const chips = ["陪我把今天捋一捋", "解释一个概念", "帮我润色这段话", "随便聊聊"];
  const h = new Date().getHours();
  const greet = h < 5 ? "夜深了，光" : h < 11 ? "早上好，光" : h < 14 ? "中午好，光" : h < 18 ? "下午好，光" : "晚上好，光";
  return (
    <>
      <ChatHeader back={false} withMeter={false} title="新会话" model={model.model} menuOpen={menu}
        onLeft={onSessions} onModelTap={() => setMenu(o => !o)} onEdit={() => setText("")} />
      <div style={{ position: "relative", flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
        <div style={{ flex: 1, minHeight: 0, display: "flex", flexDirection: "column", alignItems: "center",
          justifyContent: "center", padding: "0 30px", gap: 18 }}>
          <Wordmark size={30} />
          <div className="cn" style={{ fontSize: 21, fontWeight: 500, color: "var(--ink)", letterSpacing: "-0.01em",
            textAlign: "center" }}>{greet}</div>
          <div className="cn" style={{ fontSize: 13.5, color: "var(--ink-3)", textAlign: "center", maxWidth: 250,
            lineHeight: 1.6, marginTop: -6 }}>今天想聊点什么？或者从下面挑一个开始。</div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 9, justifyContent: "center", maxWidth: 300, marginTop: 6 }}>
            {chips.map(s => (
              <button key={s} className="pressable cn" onClick={() => onStart(s)} style={{ padding: "8px 14px",
                borderRadius: 999, border: "1px solid var(--line-2)", background: "var(--surface)",
                color: "var(--ink-2)", fontSize: 13, cursor: "pointer" }}>{s}</button>
            ))}
          </div>
        </div>
        <InputBar interactive value={text} onChange={setText} onSend={() => { onStart(text); setText(""); }} />
        <TopModelMenu open={menu} providerId={model.provider} modelId={model.model}
          onSelect={(p, m) => { onModel({ provider: p.id, model: m.id }); setMenu(false); }}
          onClose={() => setMenu(false)} />
      </div>
    </>
  );
}

/* =========================================================================
   SCREEN: Chat
   ========================================================================= */
function ChatScreenApp({ model, onModel, session, onBack, onNew }) {
  const [menu, setMenu] = useS(false);
  const [text, setText] = useS("");
  const [msgs, setMsgs] = useS(() => session?.seed ? session.seed : (session?.prompt
    ? [{ role: "user", text: session.prompt }, { role: "assistant", pending: true }] : []));
  const scroller = useR(null);
  const title = session?.title || "新会话";

  // resolve any pending assistant turn
  useE(() => {
    const idx = msgs.findIndex(m => m.role === "assistant" && m.pending);
    if (idx === -1) return;
    const prompt = msgs[idx - 1]?.text || "";
    const t = setTimeout(() => {
      setMsgs(cur => cur.map((m, i) => i === idx ? { role: "assistant", ...makeReply(prompt) } : m));
    }, 1150);
    return () => clearTimeout(t);
  }, [msgs]);

  useE(() => { if (scroller.current) scroller.current.scrollTop = scroller.current.scrollHeight; }, [msgs]);

  const send = (val) => {
    const v = (val ?? text).trim(); if (!v) return;
    setMsgs(cur => [...cur, { role: "user", text: v }, { role: "assistant", pending: true }]);
    setText("");
  };

  return (
    <>
      <ChatHeader back title={title} model={model.model} menuOpen={menu}
        onLeft={onBack} onModelTap={() => setMenu(o => !o)} onEdit={onNew} />
      <div style={{ position: "relative", flex: 1, minHeight: 0, display: "flex", flexDirection: "column" }}>
        <div ref={scroller} className="noscroll" style={{ flex: 1, overflowY: "auto", minHeight: 0,
          padding: "20px 20px 8px", display: "flex", flexDirection: "column", gap: 22 }}>
          {msgs.map((m, i) => m.role === "user"
            ? <UserBubble key={i} text={m.text} />
            : m.pending ? <PendingTurn key={i} /> : <AssistantTurn key={i} m={m} />)}
        </div>
        <InputBar interactive value={text} onChange={setText} onSend={() => send()} />
        <TopModelMenu open={menu} providerId={model.provider} modelId={model.model}
          onSelect={(p, m) => { onModel({ provider: p.id, model: m.id }); setMenu(false); }}
          onClose={() => setMenu(false)} />
      </div>
    </>
  );
}

/* =========================================================================
   SCREEN: Settings (wired, live theming)
   ========================================================================= */
function SettingsScreenApp({ t, setTweak, nav, onBack, model }) {
  return (
    <>
      <PageHeader title="设置" onBack={onBack} />
      <Body>
        <div className="card pressable" style={{ display: "flex", alignItems: "center", gap: 14,
          padding: "16px 16px", marginTop: 8, cursor: "pointer" }} onClick={() => nav.push("account")}>
          <div className="mono" style={{ width: 46, height: 46, borderRadius: 14, flex: "none",
            background: "var(--ink)", color: "var(--bg)", display: "flex", alignItems: "center",
            justifyContent: "center", fontSize: 17, fontWeight: 700 }}>光</div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="cn" style={{ fontSize: 16, fontWeight: 600, color: "var(--ink)" }}>光</div>
            <div className="mono" style={{ fontSize: 11.5, color: "var(--ink-3)", letterSpacing: 0, marginTop: 3 }}>Amber Plus · 续费于 6/30</div>
          </div>
          <Icons.chevR size={18} style={{ color: "var(--ink-4)" }} />
        </div>

        <SectionLabel>Appearance</SectionLabel>
        <div style={{ padding: "0 2px" }}>
          <Seg value={t.mode} options={[
            { v: "light", label: "浅色", icon: <Icons.sun size={16} /> },
            { v: "dark", label: "深色", icon: <Icons.moon size={16} /> },
          ]} onPick={(v) => setTweak("mode", v)} />
        </div>
        <div style={{ display: "flex", gap: 8, padding: "10px 2px 0" }}>
          {[{ v: "warm", label: "暖 Warm" }, { v: "sage", label: "鼠尾草 Sage" }].map(o => {
            const on = t.family === o.v;
            return (
              <button key={o.v} className="pressable cn" onClick={() => setTweak("family", o.v)}
                style={{ flex: 1, padding: "10px", borderRadius: 11, cursor: "pointer", fontSize: 13.5,
                  fontWeight: on ? 600 : 500, border: "1px solid " + (on ? "var(--ink)" : "var(--line-2)"),
                  background: on ? "var(--surface)" : "transparent", color: on ? "var(--ink)" : "var(--ink-3)" }}>{o.label}</button>
            );
          })}
        </div>
        <div className="mono" style={{ fontSize: 11, color: "var(--ink-3)", margin: "16px 6px 9px",
          letterSpacing: "0.1em", textTransform: "uppercase", fontWeight: 600 }}>accent</div>
        <div style={{ display: "flex", gap: 12, padding: "2px 6px 4px" }}>
          {ACCENTS.map(a => {
            const on = a === t.accent;
            return (
              <button key={a} className="pressable" onClick={() => setTweak("accent", a)} style={{ width: 38, height: 38,
                borderRadius: 11, border: on ? "2px solid var(--ink)" : "2px solid var(--line)", background: a,
                cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center" }}>
                {on && <Icons.check size={18} style={{ color: "#fff" }} />}
              </button>
            );
          })}
        </div>

        <SectionLabel>Agent</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.cpu size={19} />} label="模型分配" value={model.model} chevron onClick={() => nav.push("models")} />
          <SRow icon={<Icons.brain size={19} />} label="核心记忆" value="14 条" chevron onClick={() => nav.push("memory")} />
          <SRow icon={<Icons.spark size={18} />} label="技能" value="" chevron last onClick={() => nav.push("skills")} />
        </div>

        <SectionLabel>Connectivity</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.link size={18} />} label="服务商与密钥" value="" chevron onClick={() => nav.push("providers")} />
          <SRow icon={<Icons.shield size={18} />} label="隐私与数据" chevron last onClick={() => nav.push("privacy")} />
        </div>

        <SectionLabel>About</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.info size={19} />} label="版本" value="2.4.0 (128)" />
          <SRow icon={<Icons.bell size={18} />} label="通知" chevron last />
        </div>

        <div style={{ height: 16 }} />
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.user size={18} />} label="退出登录" danger last />
        </div>
        <div className="mono" style={{ textAlign: "center", fontSize: 11, color: "var(--ink-4)",
          marginTop: 22, letterSpacing: 0 }}>amber · 光的设备</div>
      </Body>
    </>
  );
}

/* ----------------------------------------------------- Memory (wired) ----- */
const SEED_MEM = [
  { t: "称呼我\u201c光\u201d，语气随意一点。", tag: "偏好", pin: true },
  { t: "在上海工作，做产品设计。", tag: "背景" },
  { t: "回答先给结论，再展开理由。", tag: "偏好", pin: true },
  { t: "在学吉他，喜欢指弹。", tag: "兴趣" },
  { t: "不喜欢被夸\u201c好问题\u201d这类客套。", tag: "偏好" },
];
function MemoryScreenApp({ onBack }) {
  const [auto, setAuto] = useS(true);
  const [mems, setMems] = useS(SEED_MEM);
  return (
    <>
      <PageHeader title="核心记忆" eyebrow="Memory" onBack={onBack} />
      <Body>
        <div className="card" style={{ display: "flex", alignItems: "center", gap: 13, padding: "14px 16px", marginTop: 8 }}>
          <Icons.brain size={20} style={{ color: "var(--accent)", flex: "none" }} />
          <div style={{ flex: 1 }}>
            <div className="cn" style={{ fontSize: 14.5, fontWeight: 600, color: "var(--ink)" }}>自动记忆</div>
            <div className="cn" style={{ fontSize: 12, color: "var(--ink-3)", marginTop: 2 }}>记住对话里值得留存的信息</div>
          </div>
          <button onClick={() => setAuto(a => !a)} style={{ background: "none", border: "none", padding: 0, cursor: "pointer" }}><Toggle on={auto} /></button>
        </div>
        <div className="mono" style={{ display: "flex", alignItems: "center", justifyContent: "space-between",
          margin: "24px 6px 10px", fontSize: 11, fontWeight: 600, letterSpacing: "0.12em",
          textTransform: "uppercase", color: "var(--ink-3)" }}>
          <span style={{ whiteSpace: "nowrap" }}><span style={{ color: "var(--accent)" }}>//</span> 已存 {mems.length} 条</span>
          <span className="pressable" style={{ color: "var(--accent)", cursor: "pointer", display: "flex", alignItems: "center", gap: 4 }}><Icons.plus size={13} sw={2.2} />新增</span>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          {mems.map((m, i) => (
            <div key={i} className="card" style={{ padding: "13px 15px" }}>
              <div style={{ display: "flex", alignItems: "flex-start", gap: 10 }}>
                <div className="cn" style={{ flex: 1, fontSize: 14, lineHeight: 1.5, color: "var(--ink)" }}>{m.t}</div>
                <button onClick={() => setMems(cur => cur.map((x, j) => j === i ? { ...x, pin: !x.pin } : x))}
                  style={{ background: "none", border: "none", padding: 0, cursor: "pointer", flex: "none", marginTop: 2 }}>
                  <Icons.pin size={14} fill={m.pin ? "var(--accent)" : "none"} style={{ color: m.pin ? "var(--accent)" : "var(--ink-4)" }} />
                </button>
              </div>
              <div style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 9 }}>
                <span className="mono" style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: 0, color: "var(--ink-3)",
                  background: "var(--surface-2)", padding: "2px 7px", borderRadius: 5 }}>{m.tag}</span>
                <span style={{ flex: 1 }} />
                <Icons.edit size={15} style={{ color: "var(--ink-4)" }} />
                <button onClick={() => setMems(cur => cur.filter((_, j) => j !== i))} style={{ background: "none", border: "none", padding: 0, cursor: "pointer" }}>
                  <Icons.trash size={15} style={{ color: "var(--ink-4)" }} /></button>
              </div>
            </div>
          ))}
        </div>
      </Body>
    </>
  );
}

/* ----------------------------------------------------- Models (wired) ----- */
function ModelsScreenApp({ onBack }) {
  const rows = [
    { task: "日常对话", model: "claude-sonnet-4-5", prov: "Anthropic" },
    { task: "深度思考", model: "deepseek-r2", prov: "DeepSeek" },
    { task: "快速回复", model: "amber-air", prov: "Amber" },
    { task: "联网检索", model: "gpt-5-mini", prov: "OpenAI" },
  ];
  const [behav, setBehav] = useS({ route: true, think: true, fallback: false });
  return (
    <>
      <PageHeader title="模型分配" eyebrow="Routing" onBack={onBack} />
      <Body>
        <div className="cn" style={{ fontSize: 13, color: "var(--ink-3)", lineHeight: 1.6, margin: "12px 4px 4px" }}>
          为不同场景指定模型，Amber 会按对话意图自动切换。
        </div>
        <SectionLabel>场景</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          {rows.map((r, i) => (
            <button key={i} className="pressable" style={{ width: "100%", display: "flex", alignItems: "center",
              gap: 12, padding: "13px 16px", background: "none", border: "none", cursor: "pointer", textAlign: "left",
              borderBottom: i === rows.length - 1 ? "none" : "1px solid var(--line)" }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="cn" style={{ fontSize: 15, fontWeight: 500, color: "var(--ink)" }}>{r.task}</div>
                <div className="mono" style={{ fontSize: 11, color: "var(--ink-4)", letterSpacing: 0, marginTop: 3 }}>{r.prov}</div>
              </div>
              <span className="mono" style={{ fontSize: 12.5, fontWeight: 500, color: "var(--accent)", whiteSpace: "nowrap" }}>{r.model}</span>
              <Icons.chevR size={16} style={{ color: "var(--ink-4)", flex: "none" }} />
            </button>
          ))}
        </div>
        <SectionLabel>Behaviour</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          {[{ id: "route", label: "按意图自动路由" }, { id: "think", label: "回复中显示思考过程" }, { id: "fallback", label: "弱网时降级到本地模型" }].map((r, i, arr) => (
            <div key={r.id} style={{ display: "flex", alignItems: "center", padding: "14px 16px",
              borderBottom: i === arr.length - 1 ? "none" : "1px solid var(--line)" }}>
              <span className="cn" style={{ flex: 1, fontSize: 15, fontWeight: 500, color: "var(--ink)" }}>{r.label}</span>
              <button onClick={() => setBehav(b => ({ ...b, [r.id]: !b[r.id] }))} style={{ background: "none", border: "none", padding: 0, cursor: "pointer" }}>
                <Toggle on={behav[r.id]} /></button>
            </div>
          ))}
        </div>
      </Body>
    </>
  );
}

function AccountScreenApp({ onBack }) {
  return (
    <>
      <PageHeader title="账户" eyebrow="Account" onBack={onBack} />
      <Body>
        <div className="card" style={{ display: "flex", flexDirection: "column", alignItems: "center",
          gap: 10, padding: "26px 16px", marginTop: 8 }}>
          <div className="mono" style={{ width: 64, height: 64, borderRadius: 20, background: "var(--ink)",
            color: "var(--bg)", display: "flex", alignItems: "center", justifyContent: "center", fontSize: 24, fontWeight: 700 }}>光</div>
          <div className="cn" style={{ fontSize: 18, fontWeight: 700, color: "var(--ink)" }}>光</div>
          <div className="mono" style={{ fontSize: 12, color: "var(--ink-3)", letterSpacing: 0 }}>guang@amber.chat</div>
          <div className="cn" style={{ fontSize: 11.5, fontWeight: 600, color: "var(--accent)",
            border: "1px solid color-mix(in srgb,var(--accent) 40%, transparent)", padding: "3px 11px", borderRadius: 999, marginTop: 4 }}>Amber Plus</div>
        </div>
        <SectionLabel>Plan</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.spark size={18} />} label="订阅" value="Plus · 6/30 续费" chevron />
          <SRow icon={<Icons.info size={19} />} label="用量" value="本月 2.4M tokens" last />
        </div>
      </Body>
    </>
  );
}

/* =========================================================================
   App: theme store + router
   ========================================================================= */
const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "family": "warm",
  "mode": "light",
  "accent": "#b8623a",
  "motion": true
}/*EDITMODE-END*/;

function baseTheme(family, mode) {
  const dark = mode === "dark";
  return family === "sage" ? (dark ? "sage-dark" : "sage") : (dark ? "dark" : "light");
}

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const theme = baseTheme(t.family, t.mode);

  const [stack, setStack] = useS([{ name: "home" }]);
  const [dir, setDir] = useS("push");
  const nav = {
    push: (name, params) => { setDir("push"); setStack(s => [...s, { name, params }]); },
    back: () => { setDir("pop"); setStack(s => s.length > 1 ? s.slice(0, -1) : s); },
    reset: (name, params) => { setDir("pop"); setStack([{ name, params: params || {} }]); },
  };
  const cur = stack[stack.length - 1];

  const [model, setModel] = useS({ provider: "anthropic", model: "claude-sonnet-4-5" });
  const [sessions] = useS(SEED_SESSIONS);
  const [skills, setSkills] = useS(() => Object.fromEntries(SEED_SKILLS.map(s => [s.id, s.on])));
  const [providerStatus, setProviderStatus] = useS(() => Object.fromEntries(PROVIDERS.map(p => [p.id, PROVIDER_CFG[p.id].status])));
  const [privacy, setPrivacy] = useS({ train: false, history: true, encrypt: true });

  const startChat = (prompt) => { if (!prompt || !prompt.trim()) return; nav.push("chat", { prompt: prompt.trim() }); };
  const openSession = (s) => nav.push("chat", { title: s.title, seed: SEEDED_CONVOS[s.id] || [{ role: "user", text: s.snippet }, { role: "assistant", ...makeReply(s.title) }] });

  let screen;
  switch (cur.name) {
    case "home": screen = <HomeScreenApp model={model} onModel={setModel} onStart={startChat} onSessions={() => nav.push("sessions")} nav={nav} />; break;
    case "board": screen = <BoardScreen onBack={() => nav.back()} onHistory={() => nav.push("sessions")} onSettings={() => nav.push("settings")} />; break;
    case "chat": screen = <ChatScreenApp model={model} onModel={setModel} session={cur.params} onBack={() => nav.back()} onNew={() => nav.reset("home")} />; break;
    case "sessions": screen = <SessionsView sessions={sessions} onBack={() => nav.back()} onOpen={openSession} onNew={() => nav.reset("home")} onSettings={() => nav.push("settings")} />; break;
    case "settings": screen = <SettingsScreenApp t={t} setTweak={setTweak} nav={nav} onBack={() => nav.back()} model={model} />; break;
    case "memory": screen = <MemoryScreenApp onBack={() => nav.back()} />; break;
    case "models": screen = <ModelsScreenApp onBack={() => nav.back()} />; break;
    case "providers": screen = <ProvidersView onBack={() => nav.back()} onOpen={(p) => nav.push("providerDetail", { provider: p })} providerStatus={providerStatus} />; break;
    case "providerDetail": screen = <ProviderDetailView provider={cur.params.provider} onBack={() => nav.back()} status={providerStatus} setStatus={(id, st) => setProviderStatus(s => ({ ...s, [id]: st }))} />; break;
    case "skills": screen = <SkillsView onBack={() => nav.back()} skills={skills} toggle={(id) => setSkills(s => ({ ...s, [id]: !s[id] }))} />; break;
    case "privacy": screen = <PrivacyView onBack={() => nav.back()} privacy={privacy} toggle={(id) => setPrivacy(p => ({ ...p, [id]: !p[id] }))} />; break;
    case "account": screen = <AccountScreenApp onBack={() => nav.back()} />; break;
    default: screen = <HomeScreenApp model={model} onModel={setModel} onStart={startChat} onSessions={() => nav.push("sessions")} nav={nav} />;
  }

  const anim = t.motion ? (dir === "push" ? "slideInR" : "slideInL") : "none";
  const hasComposer = cur.name === "home" || cur.name === "chat";

  return (
    <div style={{ minHeight: "100vh", display: "flex", alignItems: "center", justifyContent: "center", padding: 20 }}>
      <PhoneScreen theme={theme} accent={t.accent} footerBg={hasComposer ? "var(--surface)" : "var(--bg)"}>
        <div key={stack.length + ":" + cur.name} style={{ position: "absolute", inset: 0, display: "flex",
          flexDirection: "column", background: "var(--bg)", animation: `${anim} .3s cubic-bezier(.2,.8,.2,1)` }}>
          {screen}
        </div>
      </PhoneScreen>
      <TweaksPanel title="Tweaks">
        <TweakSection label="主题" />
        <TweakRadio label="色系" value={t.family} options={[{ value: "warm", label: "暖" }, { value: "sage", label: "鼠尾草" }]} onChange={(v) => setTweak("family", v)} />
        <TweakRadio label="明暗" value={t.mode} options={[{ value: "light", label: "浅色" }, { value: "dark", label: "深色" }]} onChange={(v) => setTweak("mode", v)} />
        <TweakColor label="强调色" value={t.accent} options={ACCENTS} onChange={(v) => setTweak("accent", v)} />
        <TweakSection label="动效" />
        <TweakRadio label="切换动画" value={t.motion ? "on" : "off"} options={[{ value: "on", label: "开" }, { value: "off", label: "关" }]} onChange={(v) => setTweak("motion", v === "on")} />
      </TweaksPanel>
    </div>
  );
}

Object.assign(window, { App });
