// settings-search-v2.jsx — Redesigned 搜索服务.
//
// V1 problems:
//   - 4 different row styles on one page (hero / list with desc / list w/o desc / green card with 3 icons)
//   - Big green capsule cards for configured services clash with hairline list rows
//   - "推荐组合" is a wall of grey text
//   - Every row has its own switch — 12 switches on one page = visual noise
//
// V2 approach:
//   - Hero card for "Agent 网络搜索" master switch with one-line counter subtitle
//   - SINGLE unified service list, internally grouped by uppercase mini-headers
//   - Rows: logo + name + (subtle desc on tap) + switch + chevron — same shape for ALL services
//   - Edit/delete/more moved out of rows into the detail page (tap chevron)
//   - "推荐组合" becomes 3 small recipe chips at the bottom instead of a wall of text

const FREE_SOURCES_V2 = [
  { name: 'Jina',         desc: '可无 Key，Markdown 阅读',   logo: 'J' },
  { name: 'DuckDuckGo',   desc: '无 Key 的免费召回源',       logo: 'D' },
  { name: 'Bing HTML',    desc: '免费公共兜底',             logo: 'B' },
  { name: 'Wikipedia',    desc: '百科类问题的实体源',        logo: 'W' },
  { name: 'Hacker News',  desc: '技术、开源、AI 生态讨论',    logo: 'H' },
  { name: 'Google WebView', desc: '深度搜索时的可见兜底',     logo: 'G' },
];

const CONFIGURED_V2 = [
  { name: 'Tavily',        logo: 'T', color: '#D88860' },
  { name: 'Bing HTML 兜底', logo: 'B', color: '#5E7A78' },
  { name: 'Perplexity',    logo: 'P', color: '#6B8E5A' },
  { name: '博查',          logo: '博', color: '#C97476' },
  { name: '智谱',          logo: 'G', color: '#8169B5' },
  { name: 'Brave',         logo: 'B', color: '#B85A48' },
];

const RECIPES = [
  { title: '零成本', detail: 'Jina + DuckDuckGo + Bing' },
  { title: 'Google 结果', detail: 'Serper / SerpAPI' },
  { title: '高质量多源', detail: 'Tavily 或 Brave' },
];

function SearchServicesV2Screen({ t }) {
  return (
    <SubShell t={t}>
      <SubHeader t={t} title="搜索服务" right={<HeaderPlus t={t} />} />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
        {/* Hero — master switch */}
        <div style={{
          padding: '16px 18px',
          background: t.cardBg || t.surface,
          border: `1px solid ${t.hair}`,
          borderRadius: 18,
        }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 16, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>Agent 网络搜索</div>
              <div style={{
                marginTop: 6, fontSize: 12, color: t.inkFaint,
                letterSpacing: 0.2, lineHeight: 1.5,
              }}>
                允许聊天调用 search_web 和 scrape_web
              </div>
            </div>
            <Toggle t={t} on />
          </div>
          <div style={{
            marginTop: 12, paddingTop: 12, borderTop: `1px solid ${t.hair}`,
            display: 'flex', alignItems: 'center', gap: 8,
            fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.3,
          }}>
            <div style={{ width: 6, height: 6, borderRadius: '50%', background: t.accent }} />
            <span style={{ color: t.ink, fontWeight: 500, fontVariantNumeric: 'tabular-nums' }}>12</span>
            <span>个服务已启用 · 多源召回</span>
          </div>
        </div>

        {/* Unified service list */}
        <div style={{ marginTop: 22, background: t.cardBg || t.surface, border: `1px solid ${t.hair}`, borderRadius: 18, overflow: 'hidden' }}>
          {/* 内置免费源 mini-header */}
          <div style={{
            padding: '14px 16px 6px',
            fontSize: 11, color: t.inkFaint, letterSpacing: 1.4, fontWeight: 500, textTransform: 'uppercase',
          }}>内置免费源</div>
          {FREE_SOURCES_V2.map((s, i) => (
            <React.Fragment key={s.name}>
              {i > 0 && <HairDivider t={t} indent={60} />}
              <ServiceRowV2 t={t} s={s} on />
            </React.Fragment>
          ))}

          {/* separator */}
          <div style={{ height: 1, background: t.hair }} />

          {/* 已配置 mini-header — sortable */}
          <div style={{
            padding: '14px 16px 8px',
            display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', gap: 12,
          }}>
            <div style={{
              fontSize: 11, color: t.inkFaint, letterSpacing: 1.4, fontWeight: 500, textTransform: 'uppercase',
            }}>已配置（API Key）</div>
            <div style={{
              fontSize: 10.5, color: t.inkFaint, letterSpacing: 0.4,
              display: 'flex', alignItems: 'center', gap: 4,
            }}>
              <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="8 18 12 22 16 18"/>
                <polyline points="8 6 12 2 16 6"/>
                <line x1="12" y1="2" x2="12" y2="22"/>
              </svg>
              <span>拖动调整优先级</span>
            </div>
          </div>
          {CONFIGURED_V2.map((s, i) => (
            <React.Fragment key={s.name}>
              {i > 0 && <HairDivider t={t} indent={60} />}
              <ServiceRowV2 t={t} s={s} on configured rank={i + 1} />
            </React.Fragment>
          ))}
        </div>

        {/* 通用选项 — global params, integrated as a hairline card */}
        <div style={{ marginTop: 22, background: t.cardBg || t.surface, border: `1px solid ${t.hair}`, borderRadius: 18, overflow: 'hidden' }}>
          <div style={{
            padding: '14px 16px 6px',
            fontSize: 11, color: t.inkFaint, letterSpacing: 1.4, fontWeight: 500, textTransform: 'uppercase',
          }}>通用选项</div>
          <div style={{ padding: '10px 14px 14px', display: 'flex', alignItems: 'center', gap: 12 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 14.5, color: t.ink, fontWeight: 500, letterSpacing: 0.2, lineHeight: 1.2 }}>结果数量</div>
              <div style={{ marginTop: 3, fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.4 }}>
                每次搜索最多返回的结果条数
              </div>
            </div>
            <Stepper t={t} value={30} />
          </div>
        </div>

        {/* Recipes footer */}
        <div style={{ marginTop: 22 }}>
          <SubGroupLabel t={t} style={{ paddingLeft: 4 }}>推荐组合</SubGroupLabel>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
            {RECIPES.map(r => (
              <div key={r.title} style={{
                padding: '12px 14px',
                background: 'transparent',
                border: `1px solid ${t.hair}`,
                borderRadius: 12,
                display: 'flex', alignItems: 'center', gap: 10,
              }}>
                <div style={{
                  fontSize: 13, color: t.accent, fontWeight: 500, letterSpacing: 0.3,
                  flexShrink: 0,
                }}>{r.title}</div>
                <div style={{ flex: 1, fontSize: 12.5, color: t.inkSoft, letterSpacing: 0.2 }}>{r.detail}</div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </SubShell>
  );
}

function ServiceRowV2({ t, s, on, configured, rank }) {
  return (
    <div style={{ padding: '12px 14px', display: 'flex', alignItems: 'center', gap: 12 }}>
      {configured && (
        <div style={{
          width: 14, flexShrink: 0,
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: t.inkFaint, cursor: 'grab',
          marginLeft: -4, marginRight: -4,
        }} aria-label="拖动排序">
          <svg width="14" height="16" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
            <circle cx="9"  cy="6"  r="1.6"/>
            <circle cx="15" cy="6"  r="1.6"/>
            <circle cx="9"  cy="12" r="1.6"/>
            <circle cx="15" cy="12" r="1.6"/>
            <circle cx="9"  cy="18" r="1.6"/>
            <circle cx="15" cy="18" r="1.6"/>
          </svg>
        </div>
      )}
      <div style={{
        width: 32, height: 32, borderRadius: '50%',
        background: configured && s.color ? s.color : (t.modelLogoBg || '#F4F4F4'),
        border: `1px solid ${t.hair}`,
        display: 'flex', alignItems: 'center', justifyContent: 'center',
        color: configured ? '#FFFFFF' : t.inkSoft,
        fontFamily: t.bodyFont, fontWeight: 600, fontSize: 13,
        flexShrink: 0,
        position: 'relative',
      }}>
        {s.logo}
        {configured && rank != null && (
          <div style={{
            position: 'absolute', top: -4, right: -4,
            minWidth: 16, height: 16, padding: '0 4px',
            borderRadius: 8,
            background: t.cardBg || t.surface,
            border: `1px solid ${t.hair}`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 10, color: t.inkSoft, fontWeight: 600,
            fontVariantNumeric: 'tabular-nums', letterSpacing: 0,
          }}>{rank}</div>
        )}
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14.5, color: t.ink, fontWeight: 500, letterSpacing: 0.2, lineHeight: 1.2 }}>{s.name}</div>
        {s.desc && (
          <div style={{ marginTop: 3, fontSize: 11.5, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.4 }}>
            {s.desc}
          </div>
        )}
      </div>
      <Toggle t={t} on={on} />
      {configured && (
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={t.inkFaint} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0 }}>
          <polyline points="9 18 15 12 9 6"/>
        </svg>
      )}
    </div>
  );
}

function Stepper({ t, value }) {
  const btn = {
    width: 30, height: 30, borderRadius: 8,
    display: 'flex', alignItems: 'center', justifyContent: 'center',
    color: t.inkSoft, cursor: 'pointer',
    background: 'transparent', border: 'none', padding: 0,
  };
  return (
    <div style={{
      display: 'flex', alignItems: 'center',
      background: t.modelLogoBg || '#F4F4F4',
      border: `1px solid ${t.hair}`, borderRadius: 10,
      padding: 2, flexShrink: 0,
    }}>
      <button type="button" style={btn} aria-label="减少">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
      </button>
      <div style={{
        minWidth: 32, textAlign: 'center',
        fontSize: 14, color: t.ink, fontWeight: 500,
        fontVariantNumeric: 'tabular-nums', letterSpacing: 0.4,
      }}>{value}</div>
      <button type="button" style={btn} aria-label="增加">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round">
          <line x1="12" y1="5" x2="12" y2="19"/>
          <line x1="5" y1="12" x2="19" y2="12"/>
        </svg>
      </button>
    </div>
  );
}

window.SearchServicesV2Screen = SearchServicesV2Screen;
