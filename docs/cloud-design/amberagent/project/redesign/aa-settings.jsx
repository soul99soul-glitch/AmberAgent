/* global React, Icons, Dot, PhoneScreen */
// aa-settings.jsx — settings home + subpages, terminal-modern skin.
const { useState: useStateSet } = React;

function SectionLabel({ children }) {
  return (
    <div className="mono" style={{ fontSize: 11, fontWeight: 600, letterSpacing: "0.12em",
      textTransform: "uppercase", color: "var(--ink-3)", margin: "26px 6px 10px",
      display: "flex", alignItems: "center", gap: 6 }}>
      <span style={{ color: "var(--accent)" }}>//</span>{children}
    </div>
  );
}

function SRow({ icon, label, value, mono = true, chevron, last, danger, badge, onClick }) {
  return (
    <button className="pressable" onClick={onClick} style={{ width: "100%", display: "flex", alignItems: "center", gap: 13,
      padding: "14px 16px", background: "none", border: "none",
      borderBottom: last ? "none" : "1px solid var(--line)", cursor: "pointer", textAlign: "left",
      color: "inherit" }}>
      {icon && <span style={{ color: danger ? "#c2553f" : "var(--ink-3)", flex: "none", display: "flex" }}>{icon}</span>}
      <span className="cn" style={{ flex: 1, fontSize: 15, color: danger ? "#c2553f" : "var(--ink)",
        fontWeight: danger ? 600 : 500, letterSpacing: 0 }}>{label}</span>
      {badge && <span className="dot" style={{ marginRight: 2 }} />}
      {value != null && <span className={mono ? "mono" : "cn"} style={{ fontSize: 13.5, color: "var(--ink-3)",
        letterSpacing: 0, whiteSpace: "nowrap" }}>{value}</span>}
      {chevron && <Icons.chevR size={18} style={{ color: "var(--ink-4)" }} />}
    </button>
  );
}

function Seg({ options, value, onPick }) {
  return (
    <div style={{ display: "flex", gap: 4, padding: 4, background: "var(--surface-2)", borderRadius: 13 }}>
      {options.map(o => {
        const on = o.v === value;
        return (
          <div key={o.v} className="pressable" onClick={() => onPick && onPick(o.v)} style={{ flex: 1, display: "flex", alignItems: "center",
            justifyContent: "center", gap: 6, padding: "9px 4px", borderRadius: 10,
            background: on ? "var(--raised)" : "transparent", color: on ? "var(--ink)" : "var(--ink-3)",
            fontWeight: on ? 600 : 500, fontSize: 13.5, boxShadow: on ? "var(--shadow)" : "none",
            cursor: "pointer" }}>
            {o.icon}<span className="cn" style={{ letterSpacing: 0 }}>{o.label}</span>
          </div>
        );
      })}
    </div>
  );
}

function Toggle({ on }) {
  return (
    <span style={{ width: 42, height: 25, borderRadius: 999, flex: "none", position: "relative",
      background: on ? "var(--accent)" : "var(--line-2)", transition: "background .2s" }}>
      <span style={{ position: "absolute", top: 2.5, left: on ? 19.5 : 2.5, width: 20, height: 20,
        borderRadius: 999, background: "#fff", boxShadow: "0 1px 3px rgba(0,0,0,.25)", transition: "left .2s" }} />
    </span>
  );
}

function SettingsHeader({ title }) {
  return (
    <div style={{ flex: "none", display: "flex", alignItems: "center", gap: 8, padding: "4px 16px 8px" }}>
      <button className="pressable" style={{ background: "none", border: "none", color: "var(--ink)",
        cursor: "pointer", display: "flex", padding: 6, margin: -6 }}><Icons.chevL size={24} /></button>
      <div className="cn" style={{ fontSize: 19, fontWeight: 700, letterSpacing: "-0.015em", marginLeft: 2 }}>{title}</div>
    </div>
  );
}

const ACCENTS = ["#b8623a", "#5e9c6e", "#4f86d6", "#9277c4", "#c2607a"];

/* =========================================================================
   SCREEN: Settings home
   ========================================================================= */
function SettingsScreen({ theme = "light", accent = "#b8623a" }) {
  const ov = React.useContext(AmbCtx);
  const liveAccent = ov && ov.locked ? ov.accent : accent;
  return (
    <PhoneScreen theme={theme} accent={accent}>
      <SettingsHeader title="设置" />
      <div className="noscroll" style={{ flex: 1, overflowY: "auto", minHeight: 0, padding: "0 18px 28px" }}>
        {/* account card */}
        <div className="card" style={{ display: "flex", alignItems: "center", gap: 14, padding: "16px 16px", marginTop: 8 }}>
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
          <Seg value={theme} options={[
            { v: "light", label: "浅色", icon: <Icons.sun size={16} /> },
            { v: "dark", label: "深色", icon: <Icons.moon size={16} /> },
          ]} />
        </div>
        <div className="mono" style={{ fontSize: 11, color: "var(--ink-3)", margin: "16px 6px 9px",
          letterSpacing: "0.1em", textTransform: "uppercase", fontWeight: 600 }}>accent</div>
        <div style={{ display: "flex", gap: 12, padding: "2px 6px 4px" }}>
          {ACCENTS.map(a => {
            const on = a === liveAccent;
            return (
              <div key={a} className="pressable" style={{ width: 38, height: 38, borderRadius: 11,
                border: on ? "2px solid var(--ink)" : "2px solid var(--line)", background: a, cursor: "pointer",
                display: "flex", alignItems: "center", justifyContent: "center" }}>
                {on && <Icons.check size={18} style={{ color: "#fff" }} />}
              </div>
            );
          })}
        </div>

        <SectionLabel>Agent</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.cpu size={19} />} label="默认模型" value="Claude Sonnet 4.5" chevron />
          <SRow icon={<Icons.brain size={19} />} label="核心记忆" value="14 条" badge chevron />
          <SRow icon={<Icons.spark size={18} />} label="技能" value="6 个已启用" chevron last />
        </div>

        <SectionLabel>Connectivity</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.globe size={19} />} label="联网搜索" value="自动" chevron />
          <SRow icon={<Icons.link size={18} />} label="服务商与密钥" value="3 个" chevron />
          <SRow icon={<Icons.shield size={18} />} label="隐私与数据" chevron last />
        </div>

        <SectionLabel>About</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.info size={19} />} label="版本" value="2.4.0 (build 128)" />
          <SRow icon={<Icons.bell size={18} />} label="通知" chevron last />
        </div>

        <div style={{ height: 16 }} />
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          <SRow icon={<Icons.user size={18} />} label="退出登录" danger last />
        </div>
        <div className="mono" style={{ textAlign: "center", fontSize: 11, color: "var(--ink-4)",
          marginTop: 22, letterSpacing: 0 }}>amber · 光的设备</div>
      </div>
    </PhoneScreen>
  );
}

/* =========================================================================
   SUBPAGE: 核心记忆 Memory
   ========================================================================= */
function MemoryScreen({ theme = "light", accent }) {
  const mems = [
    { t: "称呼我\u201c光\u201d，语气可以随意一点。", tag: "偏好", pin: true },
    { t: "在上海工作，做产品设计。", tag: "背景" },
    { t: "回答先给结论，再展开理由。", tag: "偏好", pin: true },
    { t: "在学吉他，喜欢指弹。", tag: "兴趣" },
    { t: "不喜欢被夸\u201c好问题\u201d这类客套。", tag: "偏好" },
  ];
  return (
    <PhoneScreen theme={theme} accent={accent}>
      <SettingsHeader title="核心记忆" />
      <div className="noscroll" style={{ flex: 1, overflowY: "auto", minHeight: 0, padding: "0 18px 28px" }}>
        <div className="card" style={{ display: "flex", alignItems: "center", gap: 13, padding: "14px 16px", marginTop: 8 }}>          <Icons.brain size={20} style={{ color: "var(--accent)", flex: "none" }} />
          <div style={{ flex: 1 }}>
            <div className="cn" style={{ fontSize: 14.5, fontWeight: 600, color: "var(--ink)" }}>自动记忆</div>
            <div className="cn" style={{ fontSize: 12, color: "var(--ink-3)", marginTop: 2 }}>Amber 会记住对话里值得留存的信息</div>
          </div>
          <Toggle on />
        </div>

        <div className="mono" style={{ display: "flex", alignItems: "center", justifyContent: "space-between",
          margin: "24px 6px 10px", fontSize: 11, fontWeight: 600, letterSpacing: "0.12em",
          textTransform: "uppercase", color: "var(--ink-3)" }}>
          <span style={{ whiteSpace: "nowrap" }}><span style={{ color: "var(--accent)" }}>//</span> 已存 {mems.length} 条</span>
          <span className="pressable" style={{ color: "var(--accent)", cursor: "pointer", display: "flex",
            alignItems: "center", gap: 4 }}><Icons.plus size={13} sw={2.2} />新增</span>
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
          {mems.map((m, i) => (
            <div key={i} className="card" style={{ padding: "13px 15px" }}>
              <div style={{ display: "flex", alignItems: "flex-start", gap: 10 }}>
                <div className="cn" style={{ flex: 1, fontSize: 14, lineHeight: 1.5, color: "var(--ink)" }}>{m.t}</div>
                {m.pin && <Icons.pin size={14} fill="var(--accent)" style={{ color: "var(--accent)", flex: "none", marginTop: 2 }} />}
              </div>
              <div style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 9 }}>
                <span className="mono" style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: 0,
                  color: "var(--ink-3)", background: "var(--surface-2)", padding: "2px 7px", borderRadius: 5 }}>{m.tag}</span>
                <span style={{ flex: 1 }} />
                <Icons.edit size={15} style={{ color: "var(--ink-4)" }} />
                <Icons.trash size={15} style={{ color: "var(--ink-4)" }} />
              </div>
            </div>
          ))}
        </div>
      </div>
    </PhoneScreen>
  );
}

/* =========================================================================
   SUBPAGE: 默认模型 Models assignment
   ========================================================================= */
function ModelsSubScreen({ theme = "light", accent }) {
  const rows = [
    { task: "日常对话", model: "Claude Sonnet 4.5", prov: "Anthropic" },
    { task: "深度思考", model: "DeepSeek R2", prov: "DeepSeek" },
    { task: "快速回复", model: "Amber Air", prov: "Amber" },
    { task: "联网检索", model: "GPT-5 mini", prov: "OpenAI" },
  ];
  return (
    <PhoneScreen theme={theme} accent={accent}>
      <SettingsHeader title="模型分配" />
      <div className="noscroll" style={{ flex: 1, overflowY: "auto", minHeight: 0, padding: "0 18px 28px" }}>
        <div className="cn" style={{ fontSize: 13, color: "var(--ink-3)", lineHeight: 1.6, margin: "12px 4px 6px" }}>
          为不同场景指定模型。Amber 会按对话意图自动切换。
        </div>
        <SectionLabel>Routing</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          {rows.map((r, i) => (
            <button key={i} className="pressable" style={{ width: "100%", display: "flex", alignItems: "center",
              gap: 12, padding: "14px 16px", background: "none", border: "none", cursor: "pointer", textAlign: "left",
              borderBottom: i === rows.length - 1 ? "none" : "1px solid var(--line)" }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="cn" style={{ fontSize: 15, fontWeight: 500, color: "var(--ink)" }}>{r.task}</div>
                <div className="mono" style={{ fontSize: 11.5, color: "var(--ink-3)", letterSpacing: 0, marginTop: 3 }}>{r.prov}</div>
              </div>
              <span style={{ fontSize: 13.5, fontWeight: 600, color: "var(--accent)", letterSpacing: "-0.01em" }}>{r.model}</span>
              <Icons.chevR size={17} style={{ color: "var(--ink-4)" }} />
            </button>
          ))}
        </div>

        <SectionLabel>Behaviour</SectionLabel>
        <div className="card" style={{ overflow: "hidden", padding: 0 }}>
          {[{ label: "按意图自动路由", on: true }, { label: "回复中显示思考过程", on: true }, { label: "弱网时降级到本地模型", on: false }].map((r, i, arr) => (
            <div key={i} style={{ display: "flex", alignItems: "center", padding: "14px 16px",
              borderBottom: i === arr.length - 1 ? "none" : "1px solid var(--line)" }}>
              <span className="cn" style={{ flex: 1, fontSize: 15, fontWeight: 500, color: "var(--ink)" }}>{r.label}</span>
              <Toggle on={r.on} />
            </div>
          ))}
        </div>
      </div>
    </PhoneScreen>
  );
}

Object.assign(window, {
  SectionLabel, SRow, Seg, Toggle, SettingsHeader, ACCENTS,
  SettingsScreen, MemoryScreen, ModelsSubScreen,
});
