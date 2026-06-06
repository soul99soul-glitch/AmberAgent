/* global React, Icons, Dot */
// aa-model.jsx — TOP-anchored provider/model accordion, matching OpenCode Mobile.
// Drops DOWN from under the header (grid-rows 0fr→1fr reveal), provider groups
// expand/collapse with −/+, models listed in mono beneath. This is the reusable
// "expanding menu" template — render <TopModelMenu/> as the first child of a
// position:relative anchor that begins just below the header.
const { useState: useStateM, useEffect: useEffectM } = React;

const PROVIDERS = [
  { id: "amber", name: "Amber", models: [
    { id: "amber-core", name: "amber-core", ctx: "200K", price: "内置", note: "默认" },
    { id: "amber-air",  name: "amber-air",  ctx: "128K", price: "内置", note: "快" },
  ]},
  { id: "anthropic", name: "Anthropic", models: [
    { id: "claude-sonnet-4-5", name: "claude-sonnet-4-5", ctx: "200K", price: "$3/M", note: "推荐" },
    { id: "claude-opus-4-1",   name: "claude-opus-4-1",   ctx: "200K", price: "$15/M" },
    { id: "claude-haiku-4",    name: "claude-haiku-4",    ctx: "200K", price: "$1/M", note: "快" },
  ]},
  { id: "deepseek", name: "DeepSeek", models: [
    { id: "deepseek-v4-pro", name: "deepseek-v4-pro", ctx: "128K", price: "$0.5/M" },
    { id: "deepseek-r2",     name: "deepseek-r2",     ctx: "128K", price: "$2/M", note: "推理" },
  ]},
  { id: "openai", name: "OpenAI", models: [
    { id: "gpt-5",      name: "gpt-5",      ctx: "256K", price: "$5/M" },
    { id: "gpt-5-mini", name: "gpt-5-mini", ctx: "256K", price: "$1/M", note: "快" },
  ]},
  { id: "local", name: "本地 · Ollama", models: [
    { id: "qwen3-32b", name: "qwen3:32b", ctx: "64K", price: "free", note: "本地" },
  ]},
];

function ModelLine({ m, selected, onClick }) {
  return (
    <button className="pressable mono" onClick={onClick} style={{ width: "100%", display: "flex",
      alignItems: "center", gap: 10, padding: "9px 4px 9px 0", background: "none", border: "none",
      cursor: "pointer", textAlign: "left", letterSpacing: 0 }}>
      <span style={{ fontSize: 14.5, fontWeight: selected ? 600 : 400, whiteSpace: "nowrap",
        color: selected ? "var(--accent)" : "var(--ink-3)", flex: "none" }}>{m.name}</span>
      <span style={{ flex: 1 }} />
      <span style={{ fontSize: 11.5, color: "var(--ink-4)", flex: "none" }}>{m.ctx}</span>
    </button>
  );
}

function ProviderGroup({ p, openIds, toggle, selectedModel, onSelect, activeProvider, last }) {
  const open = openIds.includes(p.id);
  const active = p.id === activeProvider;
  return (
    <div style={{ borderBottom: last ? "none" : "1px solid var(--line)" }}>
      <button className="pressable" onClick={() => toggle(p.id)} style={{ width: "100%", display: "flex",
        alignItems: "center", gap: 10, padding: "13px 2px", background: "none", border: "none",
        cursor: "pointer", textAlign: "left" }}>
        <span className="mono" style={{ fontSize: 14.5, fontWeight: active ? 600 : 500, letterSpacing: 0,
          whiteSpace: "nowrap", color: active ? "var(--accent)" : "var(--ink-2)" }}>{p.name}</span>
        <span style={{ flex: 1 }} />
        <span className="mono" style={{ fontSize: 19, lineHeight: 1, fontWeight: 400, color: "var(--ink-4)",
          width: 16, textAlign: "center" }}>{open ? "−" : "+"}</span>
      </button>
      <div style={{ display: "grid", gridTemplateRows: open ? "1fr" : "0fr",
        transition: "grid-template-rows .28s cubic-bezier(.2,.85,.25,1)" }}>
        <div style={{ overflow: "hidden" }}>
          <div style={{ paddingLeft: 16, paddingBottom: open ? 6 : 0 }}>
            {p.models.map(m => (
              <ModelLine key={m.id} m={m} selected={m.id === selectedModel}
                onClick={() => onSelect && onSelect(p, m)} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

// Top dropdown. `open` controls the reveal. Render as first child of a
// position:relative container that starts directly below the header.
function TopModelMenu({ open, providerId = "anthropic", modelId = "claude-sonnet-4-5", onSelect, onClose }) {
  const [openIds, setOpenIds] = useStateM([providerId]);
  useEffectM(() => { if (open) setOpenIds([providerId]); }, [open, providerId]);
  const toggle = (id) => setOpenIds(ids => ids.includes(id) ? ids.filter(x => x !== id) : [...ids, id]);

  return (
    <div style={{ position: "absolute", inset: 0, zIndex: 30, pointerEvents: open ? "auto" : "none" }}>
      {/* backdrop over the area below the menu */}
      <div onClick={onClose} style={{ position: "absolute", inset: 0, background: "rgba(20,18,16,.28)",
        opacity: open ? 1 : 0, transition: "opacity .28s ease", cursor: "pointer" }} />
      {/* the dropdown reveal */}
      <div style={{ position: "absolute", top: 0, left: 0, right: 0, display: "grid",
        gridTemplateRows: open ? "1fr" : "0fr",
        transition: "grid-template-rows .34s cubic-bezier(.2,.85,.25,1)" }}>
        <div style={{ overflow: "hidden" }}>
          <div style={{ background: "var(--surface)", borderBottom: "1px solid var(--line-2)",
            boxShadow: "var(--shadow-lg)", padding: "6px 20px 12px",
            maxHeight: 560, overflowY: "auto" }} className="noscroll">
            {PROVIDERS.map((p, i) => (
              <ProviderGroup key={p.id} p={p} openIds={openIds} toggle={toggle} activeProvider={providerId}
                selectedModel={modelId} onSelect={onSelect} last={i === PROVIDERS.length - 1} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { PROVIDERS, ModelLine, ProviderGroup, TopModelMenu });
