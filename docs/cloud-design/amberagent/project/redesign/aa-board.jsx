/* global React, Icons, Dot, IconBtn, Body */
// aa-board.jsx — 「今日看板」(Today's Board).
//   大看板 — rubric-grouped hotspots: LeadStory (hero) + IndexRow, hairline-split.
//   任务流 — agent task list: TaskRow (run = breathing accent / done = signal green / wait = grey).
// Terminal × Modern: machine-facts (rank / source / time / status) are mono, human
// copy (.cn) is sans; single --accent for rank+lead+cursor, --signal green only for
// live/online/done. Flat + hairline; no shadow, no candy. Reuses aa-base / oc-amber.

const { useState: useStateBd } = React;

/* ----------------------------------------------------------------- data --- */
// items[0] of each rubric renders as LeadStory; the rest as IndexRow.
const BOARD = [
  {
    rubric: "综合热点", status: "刚刚更新",
    items: [
      { rank: "01", title: "OpenAI 准备对 ChatGPT 启动史上最大改版，转型「超级应用」",
        dek: "计划整合编码工具与智能体，并新增高管看好的营收型产品线。",
        source: "财联社 电报", meta: "NewsNow #1" },
      { rank: "02", title: "苹果 WWDC26 前瞻：Siri 或接入第三方大模型，开放更多智能体接口",
        source: "36氪", meta: "8 分钟前" },
      { rank: "03", title: "华为 Mate80 系列预约破百万，Pro Max 全系标配卫星通话",
        source: "酷安", meta: "21 分钟前" },
      { rank: "04", title: "国产 GPU 厂商集体冲刺 IPO，算力国产化进入兑现期",
        source: "第一财经", meta: "33 分钟前" },
    ],
  },
  {
    rubric: "科技 · AI", status: "热度榜",
    items: [
      { rank: "01", title: "DeepSeek 开源新一代 MoE 模型，推理成本再降 40%",
        source: "量子位", meta: "1 小时前" },
    ],
  },
];

const TASKS = [
  { state: "run",  title: "整理本周 AI 行业要闻，输出一页简报", meta: "进行中 · 2 分钟" },
  { state: "done", title: "对比三家国产 GPU 招股书的毛利率",   meta: "已完成 · 14:02" },
  { state: "done", title: "把「超级应用」改版追踪做成每日提醒", meta: "已完成 · 昨天" },
  { state: "wait", title: "草拟 Mate80 系列卖点对比表",       meta: "待开始" },
];

/* --------------------------------------------------------------- atoms ---- */
// mono「来源 · 细节」— source in ink-3, separator+detail in ink-4.
function Meta({ source, detail, top }) {
  return (
    <span className="mono" style={{ display: "block", fontSize: 11, marginTop: top, letterSpacing: 0 }}>
      <span style={{ color: "var(--ink-3)" }}>{source}</span>
      <span style={{ color: "var(--ink-4)" }}> · {detail}</span>
    </span>
  );
}

// mono rubric label「// 综合热点」+ right-side live Dot + status.
function RubricHead({ rubric, status }) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between",
      margin: "22px 6px 10px" }}>
      <span className="mono" style={{ fontSize: 12, fontWeight: 600, letterSpacing: "0.02em",
        color: "var(--ink-2)" }}>
        <span style={{ color: "var(--accent)" }}>//</span> {rubric}
      </span>
      <span style={{ display: "flex", alignItems: "center", gap: 6 }}>
        <Dot />
        <span className="mono" style={{ fontSize: 11, color: "var(--ink-3)", letterSpacing: 0 }}>{status}</span>
      </span>
    </div>
  );
}

/* ---------------------------------------------------------------- rows ----- */
// Hero: big accent mono rank + 19.5px title + optional dek + mono meta.
function LeadStory({ item, onOpen }) {
  return (
    <button className="pressable" onClick={onOpen} style={{ width: "100%", display: "flex",
      alignItems: "flex-start", gap: 14, padding: "10px 6px 15px", background: "none", border: "none",
      borderBottom: "1px solid var(--line)", cursor: "pointer", textAlign: "left" }}>
      <span className="mono" style={{ fontSize: 29, fontWeight: 700, lineHeight: 1, letterSpacing: "-0.02em",
        color: "var(--accent)", flex: "none", marginTop: 2 }}>{item.rank}</span>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span className="cn" style={{ display: "block", fontSize: 19.5, fontWeight: 700, lineHeight: 1.32,
          letterSpacing: "-0.015em", color: "var(--ink)" }}>{item.title}</span>
        {item.dek && <span className="cn" style={{ display: "block", fontSize: 14, lineHeight: 1.5,
          color: "var(--ink-3)", marginTop: 7 }}>{item.dek}</span>}
        <Meta source={item.source} detail={item.meta} top={9} />
      </span>
    </button>
  );
}

// Index: small grey mono rank + 16px 2-line-clamped title + mono meta.
function IndexRow({ item, onOpen, last }) {
  return (
    <button className="pressable" onClick={onOpen} style={{ width: "100%", display: "flex",
      alignItems: "flex-start", gap: 14, padding: "13px 6px", background: "none", border: "none",
      borderBottom: last ? "none" : "1px solid var(--line)", cursor: "pointer", textAlign: "left" }}>
      <span className="mono" style={{ fontSize: 13, fontWeight: 600, lineHeight: 1.45, letterSpacing: 0,
        color: "var(--ink-4)", flex: "none", width: 20, marginTop: 1 }}>{item.rank}</span>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span className="cn" style={{ display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical",
          overflow: "hidden", fontSize: 16, fontWeight: 600, lineHeight: 1.4, letterSpacing: "-0.01em",
          color: "var(--ink)" }}>{item.title}</span>
        <Meta source={item.source} detail={item.meta} top={6} />
      </span>
    </button>
  );
}

// Task: status dot (run=breathing accent / done=signal green / wait=grey idle)
// + .cn title + mono status·time + chevron.
function TaskDot({ state }) {
  if (state === "run")  return <span className="dot" style={{ "--signal": "var(--accent)" }} />;
  if (state === "done") return <span className="dot idle" style={{ background: "var(--signal)" }} />;
  return <span className="dot idle" />;
}
function TaskRow({ task, onOpen, last }) {
  return (
    <button className="pressable" onClick={onOpen} style={{ width: "100%", display: "flex",
      alignItems: "flex-start", gap: 12, padding: "14px 4px 14px 6px", background: "none", border: "none",
      borderBottom: last ? "none" : "1px solid var(--line)", cursor: "pointer", textAlign: "left" }}>
      <span style={{ flex: "none", marginTop: 5 }}><TaskDot state={task.state} /></span>
      <span style={{ flex: 1, minWidth: 0 }}>
        <span className="cn" style={{ display: "block", fontSize: 15.5, fontWeight: 600, lineHeight: 1.4,
          letterSpacing: "-0.01em", color: "var(--ink)" }}>{task.title}</span>
        <span className="mono" style={{ display: "block", fontSize: 11.5, color: "var(--ink-3)",
          marginTop: 5, letterSpacing: 0 }}>{task.meta}</span>
      </span>
      <Icons.chevR size={18} style={{ color: "var(--ink-4)", flex: "none", marginTop: 2 }} />
    </button>
  );
}

// mono end-of-list footer.
function BoardFooter({ count }) {
  return (
    <div className="mono" style={{ textAlign: "center", fontSize: 11, color: "var(--ink-4)",
      letterSpacing: 0, padding: "26px 0 4px" }}>· 已到底 · 共 {count} 条 ·</div>
  );
}

/* --------------------------------------------------------------- views ---- */
function BoardView({ onOpen }) {
  const count = BOARD.reduce((n, g) => n + g.items.length, 0);
  return (
    <>
      {BOARD.map((g, gi) => (
        <div key={gi}>
          <RubricHead rubric={g.rubric} status={g.status} />
          {g.items.map((it, i) => i === 0
            ? <LeadStory key={i} item={it} onOpen={() => onOpen(it)} />
            : <IndexRow key={i} item={it} onOpen={() => onOpen(it)} last={i === g.items.length - 1} />)}
        </div>
      ))}
      <BoardFooter count={count} />
    </>
  );
}

function TaskFlow({ onOpen }) {
  return (
    <>
      <RubricHead rubric="智能体任务" status="实时" />
      {TASKS.map((t, i) => <TaskRow key={i} task={t} onOpen={() => onOpen(t)} last={i === TASKS.length - 1} />)}
      <BoardFooter count={TASKS.length} />
    </>
  );
}

/* -------------------------------------------------------------- chrome ---- */
// header: back + 今日看板 + history + sun. No bottom hairline (tabs own it).
function BoardHeader({ onBack, onHistory, onSettings }) {
  return (
    <div style={{ flex: "none", display: "flex", alignItems: "center", gap: 8, padding: "2px 14px 10px" }}>
      <button className="pressable" onClick={onBack} style={{ background: "none", border: "none",
        color: "var(--ink)", cursor: "pointer", display: "flex", padding: 6, margin: -6, flex: "none" }}>
        <Icons.chevL size={24} />
      </button>
      <div className="cn" style={{ flex: 1, minWidth: 0, marginLeft: 2, fontSize: 18.5, fontWeight: 700,
        letterSpacing: "-0.015em", color: "var(--ink)" }}>今日看板</div>
      <div style={{ display: "flex", gap: 2, flex: "none" }}>
        <IconBtn icon={<Icons.history size={20} />} onClick={onHistory} />
        <IconBtn icon={<Icons.sun size={20} />} onClick={onSettings} />
      </div>
    </div>
  );
}

// two tabs with a 2.5px accent cursor under the active label, sitting on the row hairline.
function BoardTabs({ tab, onTab }) {
  const tabs = [{ id: "board", label: "大看板" }, { id: "tasks", label: "任务流" }];
  return (
    <div className="hair" style={{ display: "flex", flex: "none", padding: "0 14px" }}>
      {tabs.map(tb => {
        const on = tb.id === tab;
        return (
          <button key={tb.id} className="pressable" onClick={() => onTab(tb.id)} style={{ flex: 1,
            display: "flex", justifyContent: "center", background: "none", border: "none",
            cursor: "pointer", padding: "14px 0", position: "relative" }}>
            <span className="cn" style={{ fontSize: 15.5, fontWeight: on ? 700 : 500, letterSpacing: "-0.01em",
              color: on ? "var(--ink)" : "var(--ink-3)" }}>{tb.label}</span>
            {on && <span style={{ position: "absolute", left: "50%", bottom: -1, transform: "translateX(-50%)",
              width: 34, height: 2.5, borderRadius: 2, background: "var(--accent)" }} />}
          </button>
        );
      })}
    </div>
  );
}

function BoardScreen({ onBack, onHistory = () => {}, onSettings = () => {}, onOpen = () => {} }) {
  const [tab, setTab] = useStateBd("board");
  return (
    <>
      <BoardHeader onBack={onBack} onHistory={onHistory} onSettings={onSettings} />
      <BoardTabs tab={tab} onTab={setTab} />
      <Body pad="0 18px 36px">
        <div key={tab} style={{ animation: "fadeIn .22s ease" }}>
          {tab === "board" ? <BoardView onOpen={onOpen} /> : <TaskFlow onOpen={onOpen} />}
        </div>
      </Body>
    </>
  );
}

Object.assign(window, {
  BoardScreen, BoardView, TaskFlow, RubricHead, LeadStory, IndexRow, TaskRow, BOARD, TASKS,
});
