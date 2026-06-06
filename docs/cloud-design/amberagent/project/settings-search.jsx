// settings-search.jsx — 搜索服务 (Search Services)

const FREE_SOURCES = [
  { name: 'Jina Search / Reader', desc: '可无 Key 使用，尽量以 Markdown 搜索和读取网页。' },
  { name: 'DuckDuckGo Lite',      desc: '无需 API Key 的免费公共召回源。' },
  { name: 'Bing HTML',            desc: '免费公共兜底，遇到验证页会明确报错。' },
  { name: 'Wikipedia',            desc: '实体和背景知识源，适合百科类问题。' },
  { name: 'Hacker News',          desc: '技术、开源和 AI 工具生态讨论源。' },
  { name: 'Google WebView 兜底',   desc: '普通搜索弱或深度搜索时，打开可见搜索页兜底。' },
];

const CONFIGURED_SERVICES = [
  { name: 'Tavily',        logo: '↗' },
  { name: 'Bing HTML 兜底', logo: '⌕' },
  { name: 'Perplexity',    logo: '✻' },
  { name: '博查',           logo: '博' },
  { name: '智谱',           logo: '◉' },
  { name: 'Brave',         logo: '◆' },
];

function SearchServicesScreen({ t }) {
  return (
    <SubShell t={t}>
      <SubHeader t={t} title="搜索服务" right={<HeaderPlus t={t} />} />
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 16px 16px' }}>
        <SubCard t={t}>
          <div style={{ padding: '14px 16px', display: 'flex', alignItems: 'flex-start', gap: 12 }}>
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 15, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>Agent 网络搜索</div>
              <div style={{ marginTop: 5, fontSize: 12.5, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.45 }}>
                允许聊天调用 search_web 和 scrape_web，使用已启用服务。
              </div>
              <div style={{ marginTop: 4, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2 }}>已启用 12/12 个服务用于多源搜索。</div>
            </div>
            <Toggle t={t} on />
          </div>
        </SubCard>

        <SubGroupLabel t={t} style={{ paddingLeft: 4, paddingTop: 22 }}>内置免费源</SubGroupLabel>
        <div style={{ paddingLeft: 4, marginTop: -4, marginBottom: 10, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.45 }}>
          API 服务弱或不可用时，用公共源、阅读器和垂直源兜底验证。
        </div>
        <SubCard t={t}>
          {FREE_SOURCES.map((s, i) => (
            <React.Fragment key={i}>
              {i > 0 && <HairDivider t={t} indent={16} />}
              <div style={{ padding: '13px 14px', display: 'flex', alignItems: 'center', gap: 14 }}>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 14.5, color: t.ink, fontWeight: 500, letterSpacing: 0.2, lineHeight: 1.2 }}>{s.name}</div>
                  <div style={{ marginTop: 3, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.4 }}>{s.desc}</div>
                </div>
                <Toggle t={t} on />
              </div>
            </React.Fragment>
          ))}
        </SubCard>

        <SubGroupLabel t={t} style={{ paddingLeft: 4, paddingTop: 22 }}>已配置服务</SubGroupLabel>
        <div style={{ paddingLeft: 4, marginTop: -4, marginBottom: 10, fontSize: 12, color: t.inkFaint, letterSpacing: 0.2, lineHeight: 1.5 }}>
          零成本：Jina + DuckDuckGo + Bing。高质量多源：加 Tavily 或 Brave。
        </div>
        <SubCard t={t}>
          {CONFIGURED_SERVICES.map((p, i) => (
            <React.Fragment key={i}>
              {i > 0 && <HairDivider t={t} indent={60} />}
              <div style={{ padding: '12px 14px', display: 'flex', alignItems: 'center', gap: 14 }}>
                <div style={{
                  width: 32, height: 32, borderRadius: '50%',
                  background: t.modelLogoBg || '#F4F4F4', border: `1px solid ${t.hair}`,
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  color: t.inkSoft, fontFamily: t.bodyFont, fontWeight: 600, fontSize: 13, flexShrink: 0,
                }}>{p.logo}</div>
                <div style={{ flex: 1, fontSize: 14.5, color: t.ink, fontWeight: 500, letterSpacing: 0.2 }}>{p.name}</div>
                <Toggle t={t} on />
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke={t.inkFaint} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" style={{ flexShrink: 0 }}>
                  <polyline points="9 18 15 12 9 6"/>
                </svg>
              </div>
            </React.Fragment>
          ))}
        </SubCard>
      </div>
    </SubShell>
  );
}

window.SearchServicesScreen = SearchServicesScreen;
