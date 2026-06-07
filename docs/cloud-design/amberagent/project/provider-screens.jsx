// provider-screens.jsx — 4 unified provider screens:
//   1. ProviderListScreen — list of all providers
//   2. ProviderConfigScreen — single provider's connection config (configure tab)
//   3. ProviderModelsScreen — single provider's enabled models (models tab)
//   4. ProviderAvailableSheet — bottom sheet to add models from the catalog

// ── shared data ──────────────────────────────────────────────────────
const PROVIDER_LIST = [
  { id: 'opencode',     name: 'opencode',          logo: 'O', enabled: true,  count: 3 },
  { id: 'gemini-oauth', name: 'Gemini OAuth',      logo: '✦', enabled: true,  count: 3 },
  { id: 'minimax',      name: 'minimax',           logo: '∼', enabled: false, count: 1 },
  { id: 'openai-codex', name: 'OpenAI Codex OAuth',logo: 'O', enabled: true,  count: 3 },
  { id: 'gemini',       name: 'Gemini',            logo: '✦', enabled: true,  count: 1 },
  { id: 'deepseek',     name: 'DeepSeek',          logo: 'D', enabled: true,  count: 2 },
  { id: 'openrouter',   name: 'OpenRouter',        logo: '↻', enabled: true,  count: 0 },
  { id: 'kimi',         name: 'Kimi',              logo: 'K', enabled: true,  count: 1 },
  { id: 'glm',          name: '智谱 GLM',           logo: 'G', enabled: true,  count: 1 },
  { id: 'mimo',         name: '小米 MiMo',          logo: 'M', enabled: true,  count: 2 },
];

const PROVIDER_MODELS = [
  { id: 'qwen3.6-plus',    logo: 'Q', name: 'qwen3.6-plus',    caps: ['chat','tit','tool','sci'] },
  { id: 'deepseek-v4-flash',logo: 'D', name: 'deepseek-v4-flash', caps: ['chat','t2t','tool','sci'] },
  { id: 'deepseek-v4-pro', logo: 'D', name: 'deepseek-v4-pro', caps: ['chat','t2t','tool','sci'] },
];

const AVAILABLE_MODELS = [
  { id: 'deepseek-v4-flash', logo: 'D', name: 'deepseek-v4-flash', caps: ['chat','t2t','tool','sci'], added: true },
  { id: 'deepseek-v4-pro',   logo: 'D', name: 'deepseek-v4-pro',   caps: ['chat','t2t','tool','sci'], added: true },
  { id: 'glm-5',             logo: 'G', name: 'glm-5',             caps: ['chat','t2t','tool','sci'] },
  { id: 'glm-5.1',           logo: 'G', name: 'glm-5.1',           caps: ['chat','t2t','tool','sci'] },
  { id: 'hy3-preview',       logo: 'H', name: 'hy3-preview',       caps: ['chat','t2t'] },
  { id: 'kimi-k2.5',         logo: 'K', name: 'kimi-k2.5',         caps: ['chat','tit','tool','sci'] },
  { id: 'kimi-k2.6',         logo: 'K', name: 'kimi-k2.6',         caps: ['chat','tit','tool','sci'] },
];

// ── shared building blocks ────────────────────────────────────────────

function ScreenShell({ t, children }) {
  return (
    <div style={{
      width: 380, height: 832, borderRadius: 44,
      background: t.bg,
      position: 'relative', overflow: 'hidden',
      fontFamily: t.bodyFont, color: t.ink,
      display: 'flex', flexDirection: 'column',
    }}>
      {t.haloConvo && (
        <div aria-hidden style={{ position: 'absolute', inset: 0, pointerEvents: 'none', background: t.haloConvo }} />
      )}
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
        <StatusBar t={t} />
        {children}
        <HomeIndicator t={t} />
      </div>
    </div>
  );
}

function BackTitleBar({ t, title, leftLogo, rightActions }) {
  return (
    <div style={{
      padding: '14px 18px 14px',
      display: 'flex', alignItems: 'center', gap: 14,
    }}>
      <div style={{ width: 32, height: 32, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="15 18 9 12 15 6"/>
        </svg>
      </div>
      {leftLogo && (
        <div style={{
          width: 28, height: 28, borderRadius: '50%',
          background: t.modelLogoBg || '#F4F4F4',
          border: `1px solid ${t.hair}`,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 12,
          flexShrink: 0,
        }}>{leftLogo}</div>
      )}
      <div style={{
        flex: 1,
        fontFamily: t.bodyFont, fontSize: 22, fontWeight: 500,
        color: t.ink, letterSpacing: 0.3, lineHeight: 1,
        whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
      }}>{title}</div>
      {rightActions}
    </div>
  );
}

function IconAction({ t, children, accent }) {
  return (
    <div style={{
      width: 32, height: 32, display: 'flex', alignItems: 'center', justifyContent: 'center',
      color: accent ? t.accent : t.ink,
    }}>{children}</div>
  );
}

function ProviderSearch({ t, placeholder = '搜索提供商' }) {
  return (
    <div style={{ padding: '0 16px 12px' }}>
      <div style={{
        display: 'flex', alignItems: 'center', gap: 10,
        padding: '11px 16px', borderRadius: 999,
        background: t.searchBarBg || 'rgba(15,20,25,0.04)',
        border: `1px solid ${t.searchBarEdge || t.hair}`,
      }}>
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.6" y2="16.6"/>
        </svg>
        <span style={{ fontSize: 14, color: t.inkFaint, letterSpacing: 0.2 }}>{placeholder}</span>
      </div>
    </div>
  );
}

// Single-line capability icon row — replaces the multi-color colored pills
// from the old design. Uses the same icons as the model picker for
// cross-screen consistency.
function CapsRow({ t, caps }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 9, color: t.inkFaint }}>
      {caps.map(c => <CapIcon key={c} kind={c} />)}
    </div>
  );
}

// ── 1. Provider List ─────────────────────────────────────────────────

function ProviderListScreen({ t }) {
  return (
    <ScreenShell t={t}>
      <BackTitleBar t={t} title="提供商" rightActions={
        <div style={{ display: 'flex', gap: 4 }}>
          <IconAction t={t}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
              <polyline points="14 2 14 8 20 8"/>
              <polyline points="11 17 7 13 11 9"/>
              <line x1="7" y1="13" x2="14" y2="13"/>
            </svg>
          </IconAction>
          <IconAction t={t}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
          </IconAction>
        </div>
      }/>
      <ProviderSearch t={t} />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 14px' }}>
        {/* group into a single card with hairline dividers */}
        <div style={{
          background: t.cardBg || t.surface,
          border: `1px solid ${t.hair}`,
          borderRadius: 18,
          overflow: 'hidden',
        }}>
          {PROVIDER_LIST.map((p, i) => (
            <React.Fragment key={p.id}>
              {i > 0 && <div style={{ height: 1, background: t.hair, marginLeft: 60 }} />}
              <ProviderListRow t={t} p={p} />
            </React.Fragment>
          ))}
        </div>
      </div>
    </ScreenShell>
  );
}

function ProviderListRow({ t, p }) {
  const dimmed = !p.enabled;
  return (
    <div style={{
      padding: '14px 16px',
      display: 'flex', alignItems: 'center', gap: 14,
    }}>
      <div style={{
        width: 36, height: 36, borderRadius: 10,
        background: t.modelLogoBg || '#F4F4F4',
        border: `1px solid ${t.hair}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: dimmed ? t.inkFaint : t.inkSoft,
        fontFamily: t.bodyFont, fontWeight: 600, fontSize: 15,
        flexShrink: 0,
        opacity: dimmed ? 0.5 : 1,
      }}>{p.logo}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{
          display: 'flex', alignItems: 'baseline', gap: 8,
          color: dimmed ? t.inkFaint : t.ink,
          fontSize: 15, fontWeight: 500, letterSpacing: 0.2, lineHeight: 1.2,
        }}>
          <span>{p.name}</span>
          <span style={{
            fontSize: 12, fontWeight: 400, color: t.inkFaint, letterSpacing: 0.2,
          }}>· {p.count}</span>
        </div>
      </div>
      {dimmed && (
        <span style={{
          fontSize: 11, color: t.inkFaint, letterSpacing: 0.5,
          padding: '3px 9px', borderRadius: 999,
          background: 'rgba(15,20,25,0.05)',
        }}>已禁用</span>
      )}
      <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={t.inkFaint} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0 }}>
        <polyline points="9 18 15 12 9 6"/>
      </svg>
    </div>
  );
}

// ── 2. Provider Config — minimal, one essential field group + one CTA ──

function ProviderConfigScreen({ t }) {
  return (
    <ScreenShell t={t}>
      <BackTitleBar t={t} title="opencode" leftLogo="O" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 18px 120px' }}>
        {/* enabled state — a single hero row */}
        <div style={{
          padding: '14px 16px',
          background: t.cardBg || t.surface,
          border: `1px solid ${t.hair}`,
          borderRadius: 14,
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          marginBottom: 18,
        }}>
          <div>
            <div style={{ fontSize: 15, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>启用此提供商</div>
            <div style={{ marginTop: 3, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2 }}>OpenAI 兼容</div>
          </div>
          <Toggle t={t} on />
        </div>

        {/* essential fields — labels above; no surrounding card. */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Field t={t} label="名称" value="opencode" />
          <Field t={t} label="API Key" value="sk-DrZlNoWLbsatoHLfV6vu7GlFNHK2pY4Go4w1f9FC5hhE8JYBWmNKHBZ2FPiEdxJs" multiline mono />
          <Field t={t} label="Base URL" value="https://opencode.ai/zen/go/v1" mono />
        </div>

        {/* advanced — collapsed by default */}
        <div style={{
          marginTop: 22,
          padding: '12px 4px',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          fontSize: 13.5, color: t.inkSoft, letterSpacing: 0.3,
        }}>
          <span>高级</span>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </div>
      </div>

      {/* single primary action, no extra icon cluster */}
      <ProviderConfigBottom t={t} />
    </ScreenShell>
  );
}

function Field({ t, label, value, multiline, mono }) {
  return (
    <div>
      <div style={{
        fontSize: 12, color: t.inkFaint, letterSpacing: 0.4,
        marginBottom: 8, paddingLeft: 2,
      }}>{label}</div>
      <div style={{
        padding: '12px 14px',
        borderRadius: 12,
        background: t.cardBg || t.surface,
        border: `1px solid ${t.hair}`,
        fontSize: 14.5, color: t.ink, letterSpacing: 0.2, lineHeight: 1.45,
        wordBreak: 'break-all',
        whiteSpace: multiline ? 'normal' : 'nowrap',
        overflow: multiline ? 'visible' : 'hidden',
        textOverflow: 'ellipsis',
        fontFamily: mono ? 'ui-monospace,"SF Mono",Menlo,monospace' : t.bodyFont,
      }}>{value}</div>
    </div>
  );
}

function ProviderConfigBottom({ t }) {
  return (
    <div style={{ position: 'absolute', left: 0, right: 0, bottom: 22 }}>
      <div style={{ padding: '12px 18px 14px' }}>
        <div style={{
          padding: '13px 0', borderRadius: 999,
          background: t.accent, color: '#FFFFFF',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontFamily: t.bodyFont, fontSize: 15, fontWeight: 500, letterSpacing: 0.5,
          boxShadow: `0 4px 14px ${t.sendShadowColor || 'rgba(78,168,232,0.40)'}`,
        }}>保存</div>
      </div>
      <div style={{ display: 'flex', borderTop: `1px solid ${t.hair}`, background: t.bg }}>
        <TabItem t={t} kind="config" label="配置" active />
        <TabItem t={t} kind="models" label="模型" active={false} />
      </div>
    </div>
  );
}

function TabItem({ t, kind, label, active }) {
  const color = active ? t.ink : t.inkFaint;
  return (
    <div style={{
      flex: 1, display: 'flex', flexDirection: 'column',
      alignItems: 'center', justifyContent: 'center',
      padding: '10px 0 8px', gap: 4,
    }}>
      {kind === 'config' && (
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <line x1="4" y1="6" x2="11" y2="6"/><line x1="14" y1="6" x2="20" y2="6"/>
          <line x1="4" y1="12" x2="6" y2="12"/><line x1="9" y1="12" x2="20" y2="12"/>
          <line x1="4" y1="18" x2="14" y2="18"/><line x1="17" y1="18" x2="20" y2="18"/>
          <circle cx="12.5" cy="6" r="1.5"/><circle cx="7.5" cy="12" r="1.5"/><circle cx="15.5" cy="18" r="1.5"/>
        </svg>
      )}
      {kind === 'models' && (
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke={color} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 16V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
          <polyline points="3.3 7 12 12 20.7 7"/>
          <line x1="12" y1="22" x2="12" y2="12"/>
        </svg>
      )}
      <span style={{ fontSize: 11.5, color, letterSpacing: 0.3 }}>{label}</span>
    </div>
  );
}

// ── 3. Provider Models (per-provider model list) ─────────────────────

function ProviderModelsScreen({ t }) {
  return (
    <ScreenShell t={t}>
      <BackTitleBar t={t} title="opencode" leftLogo="O" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 14px 120px' }}>
        <div style={{
          background: t.cardBg || t.surface,
          border: `1px solid ${t.hair}`,
          borderRadius: 18,
          overflow: 'hidden',
        }}>
          {PROVIDER_MODELS.map((m, i) => (
            <React.Fragment key={m.id}>
              {i > 0 && <div style={{ height: 1, background: t.hair, marginLeft: 64 }} />}
              <div style={{
                padding: '12px 14px',
                display: 'flex', alignItems: 'center', gap: 14,
              }}>
                <div style={{
                  width: 36, height: 36, borderRadius: 10,
                  background: t.modelLogoBg || '#F4F4F4',
                  border: `1px solid ${t.hair}`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 14.5,
                  flexShrink: 0,
                }}>{m.logo}</div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 15, fontWeight: 500, color: t.ink, letterSpacing: 0.2, lineHeight: 1.2 }}>{m.name}</div>
                  <div style={{ marginTop: 5 }}>
                    <CapsRow t={t} caps={m.caps} />
                  </div>
                </div>
              </div>
            </React.Fragment>
          ))}
        </div>
      </div>

      {/* single primary action — "添加模型" */}
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 22 }}>
        <div style={{ padding: '12px 18px 14px' }}>
          <div style={{
            padding: '13px 0', borderRadius: 999,
            background: t.accent, color: '#FFFFFF',
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
            fontFamily: t.bodyFont, fontSize: 15, fontWeight: 500, letterSpacing: 0.4,
            boxShadow: `0 4px 14px ${t.sendShadowColor || 'rgba(78,168,232,0.40)'}`,
          }}>
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            添加模型
          </div>
        </div>
        <div style={{ display: 'flex', borderTop: `1px solid ${t.hair}`, background: t.bg }}>
          <TabItem t={t} kind="config" label="配置" active={false} />
          <TabItem t={t} kind="models" label="模型" active />
        </div>
      </div>
    </ScreenShell>
  );
}

// ── 4. Available Models Bottom Sheet ─────────────────────────────────

function ProviderAvailableSheet({ t }) {
  return (
    <ScreenShell t={t}>
      {/* dimmed underlying */}
      <div style={{ position: 'absolute', inset: 0, background: 'rgba(15,20,25,0.22)' }} />

      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0,
        height: 680,
        background: t.bg,
        borderTopLeftRadius: 28, borderTopRightRadius: 28,
        boxShadow: '0 -8px 32px rgba(15,20,25,0.10)',
        display: 'flex', flexDirection: 'column',
        overflow: 'hidden',
      }}>
        <div style={{ display: 'flex', justifyContent: 'center', padding: '10px 0 4px' }}>
          <div style={{ width: 40, height: 4, borderRadius: 2, background: 'rgba(15,20,25,0.18)' }} />
        </div>

        <div style={{ padding: '14px 22px 8px' }}>
          <div style={{ fontFamily: t.bodyFont, fontSize: 18, fontWeight: 500, color: t.ink, letterSpacing: 0.3 }}>
            添加模型
          </div>
          <div style={{ marginTop: 4, fontSize: 12.5, color: t.inkFaint, letterSpacing: 0.2 }}>
            从 opencode 模型库挑选
          </div>
        </div>

        <div style={{ padding: '4px 18px 12px' }}>
          <div style={{
            display: 'flex', alignItems: 'center', gap: 10,
            padding: '10px 14px', borderRadius: 999,
            background: t.searchBarBg || 'rgba(15,20,25,0.04)',
            border: `1px solid ${t.searchBarEdge || t.hair}`,
          }}>
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke={t.inkSoft} strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16.6" y2="16.6"/>
            </svg>
            <span style={{ fontSize: 13.5, color: t.inkFaint, letterSpacing: 0.2 }}>搜索模型</span>
          </div>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '0 14px' }}>
          <div style={{
            background: t.cardBg || t.surface,
            border: `1px solid ${t.hair}`,
            borderRadius: 18,
            overflow: 'hidden',
          }}>
            {AVAILABLE_MODELS.map((m, i) => (
              <React.Fragment key={m.id}>
                {i > 0 && <div style={{ height: 1, background: t.hair, marginLeft: 60 }} />}
                <AvailableRow t={t} m={m} />
              </React.Fragment>
            ))}
          </div>
        </div>

        <div style={{ padding: '12px 18px 14px' }}>
          <div style={{
            padding: '13px 0', borderRadius: 999,
            background: t.accent, color: '#FFFFFF',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontFamily: t.bodyFont, fontSize: 15, fontWeight: 500, letterSpacing: 0.5,
            boxShadow: `0 4px 14px ${t.sendShadowColor || 'rgba(78,168,232,0.40)'}`,
          }}>添加 2 个</div>
        </div>
      </div>
    </ScreenShell>
  );
}

function AvailableRow({ t, m }) {
  return (
    <div style={{
      padding: '12px 14px',
      display: 'flex', alignItems: 'center', gap: 12,
    }}>
      <div style={{
        width: 36, height: 36, borderRadius: 10,
        background: t.modelLogoBg || '#F4F4F4',
        border: `1px solid ${t.hair}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 14,
        flexShrink: 0,
      }}>{m.logo}</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14.5, fontWeight: 500, color: t.ink, letterSpacing: 0.2, lineHeight: 1.2 }}>{m.name}</div>
        <div style={{ marginTop: 5 }}>
          <CapsRow t={t} caps={m.caps} />
        </div>
      </div>
      <div style={{
        width: 20, height: 20, borderRadius: 6,
        background: m.added ? t.accent : 'transparent',
        border: `1.5px solid ${m.added ? t.accent : t.hair}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        flexShrink: 0,
      }}>
        {m.added && (
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#FFFFFF" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
            <polyline points="5 12 10 17 19 7"/>
          </svg>
        )}
      </div>
    </div>
  );
}

// Simple iOS-style toggle. Used in many screens.
function Toggle({ t, on }) {
  return (
    <div style={{
      width: 38, height: 22, borderRadius: 999,
      background: on ? t.accent : 'rgba(15,20,25,0.12)',
      position: 'relative', flexShrink: 0,
      transition: 'background .2s',
    }}>
      <div style={{
        position: 'absolute', top: 2, left: on ? 18 : 2,
        width: 18, height: 18, borderRadius: '50%',
        background: '#FFFFFF',
        boxShadow: '0 1px 2px rgba(15,20,25,0.20)',
      }} />
    </div>
  );
}

Object.assign(window, { ProviderListScreen, ProviderConfigScreen, ProviderModelsScreen, ProviderAvailableSheet, Toggle });
