/* global React, Icons, Dot, SectionLabel, SRow, Seg, Toggle, PROVIDERS */
// aa-pages.jsx — frame-less page VIEWS for the interactive prototype.
// Each view returns header + scrollable body; the app Frame supplies the
// status bar / home indicator. Shared PageHeader lives here.
const { useState: useStateP } = React;

/* --------------------------------------------------------- PageHeader ----- */
// back chevron + title (+ optional mono eyebrow + right-side action node)
function PageHeader({ title, eyebrow, onBack, right }) {
  return (
    <div className="hair" style={{ flex: "none", display: "flex", alignItems: "center", gap: 8,
      padding: "2px 14px 11px" }}>
      <button className="pressable" onClick={onBack} style={{ background: "none", border: "none",
        color: "var(--ink)", cursor: "pointer", display: "flex", padding: 6, margin: -6, flex: "none" }}>
        <Icons.chevL size={24} /></button>
      <div style={{ flex: 1, minWidth: 0, marginLeft: 2 }}>
        {eyebrow && <div className="mono" style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: "0.12em",
          textTransform: "uppercase", color: "var(--ink-3)" }}>{eyebrow}</div>}
        <div className="cn" style={{ fontSize: 18.5, fontWeight: 700, letterSpacing: "-0.015em",
          color: "var(--ink)" }}>{title}</div>
      </div>
      {right}
    </div>
  );
}

function IconBtn({ icon, onClick, active }) {
  return (
    <button className="pressable" onClick={onClick} style={{ width: 36, height: 36, borderRadius: 999,
      border: "none", cursor: "pointer", flex: "none", display: "flex", alignItems: "center",
      justifyContent: "center", background: active ? "var(--surface-2)" : "transparent",
      color: "var(--ink-2)" }}>{icon}</button>
  );
}

function Body({ children, pad = "0 18px 28px" }) {
  return <div className="noscroll" style={{ flex: 1, overflowY: "auto", minHeight: 0, padding: pad,
    animation: "fadeIn .26s ease" }}>{children}</div>;
}

/* ============================================================ SESSIONS ==== */
const SEED_SESSIONS = [
  { id: "s1", title: "AI 助手该像工具还是朋友", snippet: "给你摆个四方辩台…", time: "14:22", model: "claude-sonnet-4-5", group: "今天", pinned: true },
  { id: "s2", title: "上海出差行程安排", snippet: "需要先确认出发城市和到达时间", time: "11:08", model: "claude-sonnet-4-5", group: "今天" },
  { id: "s3", title: "把这段话润色得更口语", snippet: "改了三版，第二版最自然", time: "昨天", model: "amber-core", group: "昨天" },
  { id: "s4", title: "解释一下注意力机制", snippet: "用点菜的比喻来讲…", time: "昨天", model: "deepseek-r2", group: "昨天" },
  { id: "s5", title: "周末徒步路线推荐", snippet: "三条难度递增的线路", time: "周二", model: "gpt-5", group: "更早" },
  { id: "s6", title: "帮我debug这段Python", snippet: "是闭包变量捕获的问题", time: "上周", model: "claude-sonnet-4-5", group: "更早" },
];

function SessionRow({ s, onOpen }) {
  return (
    <button className="pressable" onClick={() => onOpen(s)} style={{ width: "100%", display: "flex",
      alignItems: "flex-start", gap: 11, padding: "13px 6px", background: "none", border: "none",
      cursor: "pointer", textAlign: "left", borderBottom: "1px solid var(--line)" }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 7 }}>
          {s.pinned && <Icons.pin size={13} fill="var(--accent)" style={{ color: "var(--accent)", flex: "none" }} />}
          <span className="cn" style={{ fontSize: 15, fontWeight: 600, letterSpacing: "-0.01em",
            color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{s.title}</span>
        </div>
        <div className="cn" style={{ fontSize: 13, color: "var(--ink-3)", marginTop: 3, whiteSpace: "nowrap",
          overflow: "hidden", textOverflow: "ellipsis", lineHeight: 1.4 }}>{s.snippet}</div>
        <div className="mono" style={{ fontSize: 10.5, color: "var(--ink-4)", marginTop: 6, letterSpacing: 0 }}>{s.model}</div>
      </div>
      <span className="mono" style={{ fontSize: 11, color: "var(--ink-4)", flex: "none", paddingTop: 2 }}>{s.time}</span>
    </button>
  );
}

function SessionsView({ sessions, onBack, onOpen, onNew, onSettings }) {
  const [q, setQ] = useStateP("");
  const list = sessions.filter(s => !q || (s.title + s.snippet).toLowerCase().includes(q.toLowerCase()));
  const groups = ["今天", "昨天", "更早"];
  return (
    <>
      <PageHeader title="对话历史" onBack={onBack}
        right={<div style={{ display: "flex", gap: 2 }}>
          <IconBtn icon={<Icons.edit size={20} />} onClick={onNew} />
          <IconBtn icon={<Icons.gear size={21} />} onClick={onSettings} />
        </div>} />
      <Body pad="0 18px 28px">
        <div className="field" style={{ display: "flex", alignItems: "center", gap: 9, padding: "9px 13px",
          borderRadius: 13, marginTop: 8, marginBottom: 6 }}>
          <Icons.search size={17} style={{ color: "var(--ink-3)", flex: "none" }} />
          <input value={q} onChange={e => setQ(e.target.value)} placeholder="搜索历史对话"
            className="cn" style={{ flex: 1, border: "none", outline: "none", background: "none",
              fontSize: 14.5, color: "var(--ink)" }} />
        </div>
        {groups.map(g => {
          const items = list.filter(s => s.group === g);
          if (!items.length) return null;
          return (
            <div key={g}>
              <div className="mono" style={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.12em",
                textTransform: "uppercase", color: "var(--ink-3)", margin: "20px 6px 4px" }}>
                <span style={{ color: "var(--accent)" }}>//</span> {g}
              </div>
              {items.map(s => <SessionRow key={s.id} s={s} onOpen={onOpen} />)}
            </div>
          );
        })}
        {!list.length && <div className="cn" style={{ textAlign: "center", color: "var(--ink-4)",
          fontSize: 13.5, padding: "40px 0" }}>没有匹配的对话</div>}
      </Body>
    </>
  );
}

/* ============================================================ PROVIDERS === */
// connection config layered onto the model PROVIDERS list
const PROVIDER_CFG = {
  amber:     { status: "built-in", key: null,                 badge: "AM" },
  anthropic: { status: "connected", key: "sk-ant-•••• 4f2a",  badge: "AN" },
  deepseek:  { status: "connected", key: "sk-•••• 9c1b",      badge: "DS" },
  openai:    { status: "off",       key: null,                 badge: "OA" },
  local:     { status: "connected", key: "localhost:11434",   badge: "LO" },
};

function StatusPill({ status }) {
  const map = {
    "built-in":  { t: "内置",  c: "var(--ink-3)", bd: "var(--line-2)" },
    "connected": { t: "已连接", c: "var(--accent)", bd: "color-mix(in srgb,var(--accent) 40%, transparent)" },
    "off":       { t: "未连接", c: "var(--ink-4)", bd: "var(--line-2)" },
  };
  const s = map[status] || map.off;
  return <span className="cn" style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: 0, color: s.c,
    border: `1px solid ${s.bd}`, padding: "1px 7px", borderRadius: 5, flex: "none" }}>{s.t}</span>;
}

function ProviderBadge({ text, dim }) {
  return <span className="mono" style={{ width: 34, height: 34, borderRadius: 9, flex: "none",
    display: "flex", alignItems: "center", justifyContent: "center", fontSize: 12, fontWeight: 700,
    letterSpacing: 0, background: "var(--surface-2)", color: dim ? "var(--ink-4)" : "var(--ink-2)" }}>{text}</span>;
}

function ProvidersView({ onBack, onOpen, providerStatus }) {
  return (
    <>
      <PageHeader title="服务商与密钥" eyebrow="Providers" onBack={onBack}
        right={<IconBtn icon={<Icons.plus size={21} />} />} />
      <Body>
        <div className="cn" style={{ fontSize: 13, color: "var(--ink-3)", lineHeight: 1.6, margin: "12px 4px 4px" }}>
          连接服务商后即可在对话中切换其模型。密钥仅存于本设备。
        </div>
        <SectionLabel>Connected</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          {PROVIDERS.map((p, i, arr) => {
            const cfg = PROVIDER_CFG[p.id] || {};
            const status = providerStatus[p.id] || cfg.status;
            const off = status === "off";
            return (
              <button key={p.id} className="pressable" onClick={() => onOpen(p)} style={{ width: "100%",
                display: "flex", alignItems: "center", gap: 12, padding: "12px 14px", background: "none",
                border: "none", cursor: "pointer", textAlign: "left",
                borderBottom: i === arr.length - 1 ? "none" : "1px solid var(--line)" }}>
                <ProviderBadge text={cfg.badge} dim={off} />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div className="cn" style={{ fontSize: 15, fontWeight: 600, color: off ? "var(--ink-3)" : "var(--ink)" }}>{p.name}</div>
                  <div className="mono" style={{ fontSize: 11, color: "var(--ink-4)", letterSpacing: 0, marginTop: 3 }}>
                    {status === "off" ? `${p.models.length} 个模型 · 待连接` : (cfg.key || `${p.models.length} 个模型`)}
                  </div>
                </div>
                <StatusPill status={status} />
                <Icons.chevR size={17} style={{ color: "var(--ink-4)", flex: "none" }} />
              </button>
            );
          })}
        </div>
      </Body>
    </>
  );
}

function ProviderDetailView({ provider, onBack, status, setStatus }) {
  const cfg = PROVIDER_CFG[provider.id] || {};
  const cur = status[provider.id] || cfg.status;
  const connected = cur === "connected" || cur === "built-in";
  const [enabled, setEnabled] = useStateP(() => Object.fromEntries(provider.models.map(m => [m.id, true])));
  return (
    <>
      <PageHeader title={provider.name} eyebrow="Provider" onBack={onBack} />
      <Body>
        <div className="card" style={{ display: "flex", alignItems: "center", gap: 13, padding: "15px 16px", marginTop: 8 }}>
          <ProviderBadge text={cfg.badge} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div className="cn" style={{ fontSize: 15.5, fontWeight: 600, color: "var(--ink)" }}>{provider.name}</div>
            <div className="mono" style={{ fontSize: 11, color: "var(--ink-4)", letterSpacing: 0, marginTop: 3 }}>{provider.models.length} models</div>
          </div>
          <StatusPill status={cur} />
        </div>

        {cfg.status !== "built-in" && (
          <>
            <SectionLabel>API Key</SectionLabel>
            <div className="field" style={{ display: "flex", alignItems: "center", gap: 10, padding: "11px 13px", borderRadius: 12 }}>
              <Icons.shield size={17} style={{ color: "var(--ink-3)", flex: "none" }} />
              <span className="mono" style={{ flex: 1, fontSize: 13, color: connected ? "var(--ink-2)" : "var(--ink-4)",
                letterSpacing: 0, whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                {cfg.key || "粘贴你的 API Key…"}</span>
              {connected && <Icons.check size={16} style={{ color: "var(--accent)", flex: "none" }} />}
            </div>
            <button className="pressable" onClick={() => setStatus(provider.id, connected ? "off" : "connected")}
              style={{ width: "100%", marginTop: 12, padding: "13px", borderRadius: 13, border: "none",
                cursor: "pointer", fontSize: 14.5, fontWeight: 600,
                background: connected ? "var(--surface-2)" : "var(--ink)",
                color: connected ? "var(--ink-2)" : "var(--bg)" }}>
              <span className="cn">{connected ? "断开连接" : "连接服务商"}</span>
            </button>
          </>
        )}

        <SectionLabel>Models</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0, opacity: connected ? 1 : 0.5 }}>
          {provider.models.map((m, i, arr) => (
            <div key={m.id} style={{ display: "flex", alignItems: "center", gap: 10, padding: "13px 15px",
              borderBottom: i === arr.length - 1 ? "none" : "1px solid var(--line)" }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="mono" style={{ fontSize: 13.5, color: "var(--ink)", letterSpacing: 0 }}>{m.name}</div>
                <div className="mono" style={{ fontSize: 11, color: "var(--ink-4)", letterSpacing: 0, marginTop: 3 }}>{m.ctx} ctx · {m.price}</div>
              </div>
              <button onClick={() => connected && setEnabled(e => ({ ...e, [m.id]: !e[m.id] }))}
                style={{ background: "none", border: "none", padding: 0, cursor: connected ? "pointer" : "default" }}>
                <Toggle on={connected && enabled[m.id]} />
              </button>
            </div>
          ))}
        </div>
      </Body>
    </>
  );
}

/* ============================================================ SKILLS ====== */
const SEED_SKILLS = [
  { id: "web",    name: "联网搜索",   desc: "实时检索网页信息", icon: "globe", group: "core", on: true },
  { id: "code",   name: "代码执行",   desc: "运行并验证代码片段", icon: "cpu", group: "core", on: true },
  { id: "memory", name: "核心记忆",   desc: "记住你的偏好与背景", icon: "brain", group: "core", on: true },
  { id: "vision", name: "图像理解",   desc: "看懂截图、照片、图表", icon: "spark", group: "core", on: true },
  { id: "doc",    name: "文档读取",   desc: "解析 PDF / Word / 表格", icon: "info", group: "ext", on: true },
  { id: "voice",  name: "语音朗读",   desc: "把回复读出来", icon: "tts", group: "ext", on: false },
  { id: "cal",    name: "日程提醒",   desc: "创建提醒与日历事件", icon: "bell", group: "ext", on: false },
  { id: "fetch",  name: "网页抓取",   desc: "提取指定链接的正文", icon: "link", group: "ext", on: true },
];

function SkillRow({ s, on, toggle, last }) {
  const Ico = Icons[s.icon] || Icons.spark;
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 13, padding: "13px 15px",
      borderBottom: last ? "none" : "1px solid var(--line)" }}>
      <span style={{ width: 34, height: 34, borderRadius: 9, flex: "none", display: "flex",
        alignItems: "center", justifyContent: "center",
        background: on ? "color-mix(in srgb,var(--accent) 14%, var(--surface-2))" : "var(--surface-2)",
        color: on ? "var(--accent)" : "var(--ink-4)" }}><Ico size={19} /></span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div className="cn" style={{ fontSize: 15, fontWeight: 500, color: "var(--ink)" }}>{s.name}</div>
        <div className="cn" style={{ fontSize: 12.5, color: "var(--ink-3)", marginTop: 2 }}>{s.desc}</div>
      </div>
      <button onClick={toggle} style={{ background: "none", border: "none", padding: 0, cursor: "pointer" }}>
        <Toggle on={on} />
      </button>
    </div>
  );
}

function SkillsView({ onBack, skills, toggle }) {
  const onCount = Object.values(skills).filter(Boolean).length;
  const core = SEED_SKILLS.filter(s => s.group === "core");
  const ext = SEED_SKILLS.filter(s => s.group === "ext");
  return (
    <>
      <PageHeader title="技能" eyebrow={`Skills · ${onCount} 已启用`} onBack={onBack} />
      <Body>
        <div className="cn" style={{ fontSize: 13, color: "var(--ink-3)", lineHeight: 1.6, margin: "12px 4px 4px" }}>
          技能决定 Amber 能调用哪些工具。关掉不需要的可以让回应更快、更专注。
        </div>
        <SectionLabel>核心能力</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          {core.map((s, i) => <SkillRow key={s.id} s={s} on={skills[s.id]} toggle={() => toggle(s.id)} last={i === core.length - 1} />)}
        </div>
        <SectionLabel>扩展技能</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          {ext.map((s, i) => <SkillRow key={s.id} s={s} on={skills[s.id]} toggle={() => toggle(s.id)} last={i === ext.length - 1} />)}
        </div>
      </Body>
    </>
  );
}

/* ============================================================ PRIVACY ===== */
function PrivacyView({ onBack, privacy, toggle }) {
  const rows = [
    { id: "train", label: "用我的对话改进模型", desc: "匿名化后用于训练" },
    { id: "history", label: "保存对话历史", desc: "关闭后新对话不留存" },
    { id: "encrypt", label: "本地加密存储", desc: "用设备密钥加密缓存" },
  ];
  return (
    <>
      <PageHeader title="隐私与数据" eyebrow="Privacy" onBack={onBack} />
      <Body>
        <SectionLabel>Data</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          {rows.map((r, i) => (
            <div key={r.id} style={{ display: "flex", alignItems: "center", gap: 12, padding: "13px 16px",
              borderBottom: i === rows.length - 1 ? "none" : "1px solid var(--line)" }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="cn" style={{ fontSize: 15, fontWeight: 500, color: "var(--ink)" }}>{r.label}</div>
                <div className="cn" style={{ fontSize: 12.5, color: "var(--ink-3)", marginTop: 2 }}>{r.desc}</div>
              </div>
              <button onClick={() => toggle(r.id)} style={{ background: "none", border: "none", padding: 0, cursor: "pointer" }}>
                <Toggle on={privacy[r.id]} />
              </button>
            </div>
          ))}
        </div>
        <SectionLabel>Manage</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.link size={18} />} label="导出我的数据" chevron />
          <SRow icon={<Icons.trash size={17} />} label="清除所有对话" danger last />
        </div>
        <div className="mono" style={{ textAlign: "center", fontSize: 10.5, color: "var(--ink-4)",
          marginTop: 22, letterSpacing: 0, lineHeight: 1.7 }}>
          数据存储于本设备<br />amber · privacy v2
        </div>
      </Body>
    </>
  );
}

Object.assign(window, {
  PageHeader, IconBtn, Body, SessionsView, SEED_SESSIONS,
  ProvidersView, ProviderDetailView, PROVIDER_CFG,
  SkillsView, SEED_SKILLS, PrivacyView,
});
