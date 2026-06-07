// provider-screens-v2.jsx — Redesigned provider detail screens, faithful to
// the real app's fields but rebuilt in the V2 refined language.
//
//   1. ProviderConfigV2Screen — connection config (协议 / 启用 / 连接 / 选项)
//   2. ProviderModelsV2Screen  — per-provider model list + 模型库 / 添加新模型
//   3. EditModelSheet          — bottom sheet, tabbed (基本 / 高级 / 内置工具)
//
// Uses globals from other files: SubShell, Toggle, CapIcon, TabItem.

// ── shared small pieces ───────────────────────────────────────────────

// Single-select segmented control, matching the model-picker ThinkingLevel look.
function Segmented({ t, options, active }) {
  return (
    <div style={{
      display: 'flex', gap: 3, padding: 3,
      borderRadius: 12,
      background: t.segmentTrack || 'rgba(255,255,255,0.7)',
      border: `1px solid ${t.hair}`,
    }}>
      {options.map(o => {
        const on = o === active;
        return (
          <div key={o} style={{
            flex: 1, padding: '8px 4px', borderRadius: 9,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5,
            background: on ? t.accent : 'transparent',
            color: on ? (t.segmentActiveInk || '#FFFFFF') : t.inkSoft,
            fontSize: 13, fontWeight: 500, letterSpacing: 0.3,
            transition: 'background .18s',
          }}>
            {on && (
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="5 12 10 17 19 7"/>
              </svg>
            )}
            <span>{o}</span>
          </div>
        );
      })}
    </div>
  );
}

// Multi-select chips with check. value = array of active labels.
function ModalityChips({ t, options, active }) {
  return (
    <div style={{ display: 'flex', gap: 8 }}>
      {options.map(o => {
        const on = active.includes(o);
        return (
          <div key={o} style={{
            flex: 1, padding: '9px 4px', borderRadius: 11,
            display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5,
            background: on ? t.accentSoft : 'transparent',
            border: `1px solid ${on ? 'transparent' : t.hair}`,
            color: on ? t.accent : t.inkSoft,
            fontSize: 13, fontWeight: 500, letterSpacing: 0.3,
          }}>
            {on && (
              <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="5 12 10 17 19 7"/>
              </svg>
            )}
            <span>{o}</span>
          </div>
        );
      })}
    </div>
  );
}

function FieldLabel({ t, children, style }) {
  return (
    <div style={{
      fontSize: 12, color: t.inkFaint, letterSpacing: 0.4,
      fontWeight: 500, marginBottom: 9, paddingLeft: 2, ...style,
    }}>{children}</div>
  );
}

// Editable-looking field box: bordered, label sits above (rendered by caller).
function FieldBox({ t, children, mono, locked, right }) {
  return (
    <div style={{
      padding: '12px 14px', borderRadius: 12,
      background: locked ? (t.modelLogoBg || 'rgba(15,20,25,0.04)') : (t.cardBg || t.surface),
      border: `1px solid ${t.hair}`,
      display: 'flex', alignItems: 'center', gap: 10,
      fontSize: 14.5, color: locked ? t.inkFaint : t.ink, letterSpacing: 0.2,
      fontFamily: mono ? 'ui-monospace,"SF Mono",Menlo,monospace' : t.bodyFont,
    }}>
      <div style={{ flex: 1, minWidth: 0, wordBreak: 'break-all' }}>{children}</div>
      {right}
    </div>
  );
}

// A stacked label+value row inside a unified card (hairline separated).
function ConnRow({ t, label, value, mono, right }) {
  return (
    <div style={{ padding: '12px 16px' }}>
      <div style={{ fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.4, fontWeight: 500, marginBottom: 5 }}>{label}</div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{
          flex: 1, minWidth: 0,
          fontSize: 14.5, color: t.ink, letterSpacing: 0.2, lineHeight: 1.4,
          wordBreak: 'break-all',
          fontFamily: mono ? 'ui-monospace,"SF Mono",Menlo,monospace' : t.bodyFont,
        }}>{value}</div>
        {right}
      </div>
    </div>
  );
}

function OptionRow({ t, title, desc, right }) {
  return (
    <div style={{ padding: '13px 16px', display: 'flex', alignItems: 'center', gap: 12 }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14.5, color: t.ink, fontWeight: 500, letterSpacing: 0.2, lineHeight: 1.2 }}>{title}</div>
        {desc && <div style={{ marginTop: 4, fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.4 }}>{desc}</div>}
      </div>
      {right}
    </div>
  );
}

function MiniHeader({ t, children, style }) {
  return (
    <div style={{
      padding: '14px 16px 8px',
      fontSize: 11, color: t.inkFaint, letterSpacing: 1.4, fontWeight: 500, textTransform: 'uppercase',
      ...style,
    }}>{children}</div>
  );
}

function ProviderHeaderV2({ t, title, logo }) {
  return (
    <div style={{ padding: '14px 18px', display: 'flex', alignItems: 'center', gap: 12 }}>
      <div style={{ width: 30, height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center', marginLeft: -4 }}>
        <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke={t.ink} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="15 18 9 12 15 6"/>
        </svg>
      </div>
      <div style={{
        width: 28, height: 28, borderRadius: '50%',
        background: t.accentSoft,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: t.accent, fontFamily: t.bodyFont, fontWeight: 700, fontSize: 13,
        flexShrink: 0,
      }}>{logo}</div>
      <div style={{ flex: 1, fontSize: 21, fontWeight: 500, color: t.ink, letterSpacing: 0.3, lineHeight: 1 }}>{title}</div>
      <div style={{ width: 30, height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center', color: t.inkSoft }}>
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7"/>
          <polyline points="16 6 12 2 8 6"/><line x1="12" y1="2" x2="12" y2="15"/>
        </svg>
      </div>
    </div>
  );
}

function PrimaryButton({ t, children }) {
  return (
    <div style={{
      padding: '13px 0', borderRadius: 999,
      background: t.accent, color: '#FFFFFF',
      display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 8,
      fontFamily: t.bodyFont, fontSize: 15, fontWeight: 500, letterSpacing: 0.5,
      boxShadow: `0 4px 14px ${t.sendShadowColor || 'rgba(78,168,232,0.40)'}`,
    }}>{children}</div>
  );
}

function EyeIcon({ t }) {
  return (
    <div style={{ width: 26, height: 26, display: 'flex', alignItems: 'center', justifyContent: 'center', color: t.inkFaint, flexShrink: 0 }}>
      <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
        <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/>
        <line x1="3" y1="3" x2="21" y2="21" opacity="0.55"/>
      </svg>
    </div>
  );
}

// ── 1. Provider Config V2 ─────────────────────────────────────────────

function ProviderConfigV2Screen({ t }) {
  return (
    <SubShell t={t}>
      <ProviderHeaderV2 t={t} title="DeepSeek" logo="D" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 150px' }}>

        {/* protocol */}
        <div style={{ paddingTop: 2 }}>
          <FieldLabel t={t}>接口协议</FieldLabel>
          <Segmented t={t} options={['OpenAI', 'Google', 'Claude']} active="OpenAI" />
        </div>

        {/* enable hero */}
        <div style={{
          marginTop: 18, padding: '15px 16px',
          background: t.cardBg || t.surface, border: `1px solid ${t.hair}`, borderRadius: 16,
          display: 'flex', alignItems: 'center', gap: 12,
        }}>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 15.5, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>启用此提供商</div>
            <div style={{ marginTop: 4, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2 }}>OpenAI 兼容 · 已连接</div>
          </div>
          <Toggle t={t} on />
        </div>

        {/* connection */}
        <div style={{ marginTop: 20, background: t.cardBg || t.surface, border: `1px solid ${t.hair}`, borderRadius: 18, overflow: 'hidden' }}>
          <MiniHeader t={t}>连接</MiniHeader>
          <ConnRow t={t} label="名称" value="DeepSeek" />
          <HairDivider t={t} indent={16} />
          <ConnRow t={t} label="API KEY" mono
            value={<span>sk-cdd272<span style={{ color: t.inkFaint, letterSpacing: 1 }}>••••••••••••••••</span>708b91</span>}
            right={<EyeIcon t={t} />} />
          <HairDivider t={t} indent={16} />
          <ConnRow t={t} label="API BASE URL" value="https://api.deepseek.com/v1" mono />
          <HairDivider t={t} indent={16} />
          <ConnRow t={t} label="API 路径" value="/chat/completions" mono />
        </div>

        {/* options */}
        <div style={{ marginTop: 20, background: t.cardBg || t.surface, border: `1px solid ${t.hair}`, borderRadius: 18, overflow: 'hidden' }}>
          <MiniHeader t={t}>选项</MiniHeader>
          <OptionRow t={t} title="Response API" desc="使用 /responses 端点（实验性）" right={<Toggle t={t} on={false} />} />
          <HairDivider t={t} indent={16} />
          <OptionRow t={t} title="获取账户余额" desc={
            <span>余额 <span style={{ color: t.accent, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>¥48.20</span> · 每次启动刷新</span>
          } right={<Toggle t={t} on />} />
        </div>
      </div>

      {/* save + tab bar */}
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 22 }}>
        <div style={{ padding: '12px 16px 14px' }}>
          <PrimaryButton t={t}>保存</PrimaryButton>
        </div>
        <div style={{ display: 'flex', borderTop: `1px solid ${t.hair}`, background: t.bg }}>
          <TabItem t={t} kind="config" label="配置" active />
          <TabItem t={t} kind="models" label="模型" active={false} />
        </div>
      </div>
    </SubShell>
  );
}

// ── 2. Provider Models V2 ─────────────────────────────────────────────

const MODELS_V2 = [
  { id: 'deepseek-v4-flash', name: 'deepseek-v4-flash', type: '聊天', caps: ['t2t', 'tool', 'sci'], ctx: '1M' },
  { id: 'deepseek-v4-pro',   name: 'deepseek-v4-pro',   type: '聊天', caps: ['t2t', 'tool', 'sci'], ctx: '1M' },
];

function ProviderModelsV2Screen({ t }) {
  return (
    <SubShell t={t}>
      <ProviderHeaderV2 t={t} title="DeepSeek" logo="D" />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 150px' }}>
        <div style={{ background: t.cardBg || t.surface, border: `1px solid ${t.hair}`, borderRadius: 18, overflow: 'hidden' }}>
          {MODELS_V2.map((m, i) => (
            <React.Fragment key={m.id}>
              {i > 0 && <HairDivider t={t} indent={64} />}
              <ModelRowV2 t={t} m={m} />
            </React.Fragment>
          ))}
        </div>
      </div>

      {/* floating action bar: 模型库 (catalog) + 添加新模型 */}
      <div style={{ position: 'absolute', left: 0, right: 0, bottom: 22 }}>
        <div style={{ padding: '12px 16px 14px', display: 'flex', alignItems: 'center', gap: 12 }}>
          {/* catalog pill with badge */}
          <div style={{
            position: 'relative',
            width: 50, height: 48, borderRadius: 16,
            background: t.cardBg || t.surface, border: `1px solid ${t.hair}`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            color: t.inkSoft, flexShrink: 0,
          }}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <path d="M21 16V8a2 2 0 0 0-1-1.7l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.7l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
              <polyline points="3.3 7 12 12 20.7 7"/><line x1="12" y1="22" x2="12" y2="12"/>
            </svg>
            <div style={{
              position: 'absolute', top: -6, right: -6,
              minWidth: 18, height: 18, padding: '0 5px', borderRadius: 9,
              background: t.accent, color: '#FFFFFF',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 11, fontWeight: 600, fontVariantNumeric: 'tabular-nums',
              border: `2px solid ${t.bg}`,
            }}>2</div>
          </div>
          <div style={{ flex: 1 }}>
            <PrimaryButton t={t}>
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>
              </svg>
              添加新模型
            </PrimaryButton>
          </div>
        </div>
        <div style={{ display: 'flex', borderTop: `1px solid ${t.hair}`, background: t.bg }}>
          <TabItem t={t} kind="config" label="配置" active={false} />
          <TabItem t={t} kind="models" label="模型" active />
        </div>
      </div>
    </SubShell>
  );
}

function ModelRowV2({ t, m }) {
  return (
    <div style={{ padding: '13px 16px', display: 'flex', alignItems: 'center', gap: 14 }}>
      <div style={{
        width: 36, height: 36, borderRadius: 11,
        background: t.accentSoft,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: t.accent, fontFamily: t.bodyFont, fontWeight: 700, fontSize: 15,
        flexShrink: 0,
      }}>D</div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 15, fontWeight: 500, color: t.ink, letterSpacing: 0.2, lineHeight: 1.2,
          fontFamily: 'ui-monospace,"SF Mono",Menlo,monospace' }}>{m.name}</div>
        <div style={{ marginTop: 7, display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{
            fontSize: 11, color: t.inkSoft, letterSpacing: 0.3,
            padding: '2px 8px', borderRadius: 6, background: t.modelLogoBg || 'rgba(15,20,25,0.05)',
          }}>{m.type}</span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, color: t.inkFaint }}>
            {m.caps.map(c => <CapIcon key={c} kind={c} />)}
          </div>
          <span style={{ fontSize: 11, color: t.inkFaint, letterSpacing: 0.3, fontVariantNumeric: 'tabular-nums' }}>· {m.ctx}</span>
        </div>
      </div>
      <div style={{ width: 30, height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center', color: t.inkSoft, flexShrink: 0 }}>
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M12 20h9"/><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"/>
        </svg>
      </div>
    </div>
  );
}

// ── 3. Edit Model Sheet ───────────────────────────────────────────────

function EditModelSheet({ t }) {
  return (
    <SubShell t={t}>
      <div style={{ position: 'absolute', inset: 0, background: t.sheetBackdrop || 'rgba(15,20,25,0.22)' }} />

      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 0, height: 712,
        background: t.bg,
        borderTopLeftRadius: 28, borderTopRightRadius: 28,
        boxShadow: '0 -8px 32px rgba(15,20,25,0.10)',
        display: 'flex', flexDirection: 'column', overflow: 'hidden',
      }}>
        {/* header */}
        <div style={{ padding: '16px 18px 6px', display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ width: 30, height: 30, display: 'flex', alignItems: 'center', justifyContent: 'center', color: t.ink, marginLeft: -4 }}>
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </div>
          <div style={{ flex: 1, fontSize: 17, fontWeight: 600, color: t.ink, letterSpacing: 0.3 }}>编辑模型</div>
        </div>

        {/* underline tabs */}
        <div style={{ display: 'flex', gap: 4, padding: '8px 18px 0' }}>
          {[['基本设置', true], ['高级设置', false], ['内置工具', false]].map(([label, on]) => (
            <div key={label} style={{ flex: 1, paddingBottom: 11, textAlign: 'center', position: 'relative' }}>
              <span style={{ fontSize: 14, fontWeight: on ? 600 : 400, color: on ? t.ink : t.inkFaint, letterSpacing: 0.3 }}>{label}</span>
              {on && <div style={{ position: 'absolute', left: '28%', right: '28%', bottom: 0, height: 2.5, borderRadius: 2, background: t.accent }} />}
            </div>
          ))}
        </div>
        <div style={{ height: 1, background: t.hair }} />

        {/* body */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '20px 18px 16px', display: 'flex', flexDirection: 'column', gap: 20 }}>
          <div>
            <FieldLabel t={t}>模型 ID</FieldLabel>
            <FieldBox t={t} mono locked right={
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke={t.inkFaint} strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <rect x="5" y="11" width="14" height="9" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>
              </svg>
            }>deepseek-v4-flash</FieldBox>
          </div>
          <div>
            <FieldLabel t={t}>模型名称</FieldLabel>
            <FieldBox t={t} mono>deepseek-v4-flash</FieldBox>
          </div>
          <div>
            <FieldLabel t={t}>上下文长度</FieldLabel>
            <FieldBox t={t} right={<span style={{ fontSize: 12, color: t.inkFaint }}>tokens</span>}>
              <span style={{ fontVariantNumeric: 'tabular-nums' }}>1,000,000</span>
            </FieldBox>
          </div>
          <div>
            <FieldLabel t={t}>模型类型</FieldLabel>
            <Segmented t={t} options={['聊天', '图像', '嵌入']} active="聊天" />
          </div>
          <div>
            <FieldLabel t={t}>输入模态</FieldLabel>
            <ModalityChips t={t} options={['文本', '图片', '音频']} active={['文本', '图片']} />
          </div>
          <div>
            <FieldLabel t={t}>输出模态</FieldLabel>
            <ModalityChips t={t} options={['文本', '图片', '音频']} active={['文本']} />
          </div>
        </div>

        {/* footer actions */}
        <div style={{ display: 'flex', gap: 12, padding: '12px 18px 16px', borderTop: `1px solid ${t.hair}` }}>
          <div style={{
            flex: 1, padding: '12px 0', borderRadius: 999,
            border: `1px solid ${t.hair}`, color: t.inkSoft,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 15, fontWeight: 500, letterSpacing: 0.5,
          }}>取消</div>
          <div style={{ flex: 1 }}>
            <PrimaryButton t={t}>确认</PrimaryButton>
          </div>
        </div>
      </div>
    </SubShell>
  );
}

Object.assign(window, { ProviderConfigV2Screen, ProviderModelsV2Screen, EditModelSheet });
