import type { Storage } from '@amber/deepread-domain';

export interface DeepReadAppearanceChoice { id: string; name: string; detail: string; }
export interface DeepReadReaderAppearance { readerLayout: string; readerStyle: string; }
export interface DeepReadReaderCanvas { background: string; foreground: string; surface: string; muted: string; border: string; }
export const READER_LAYOUT_KEY: string = 'deepread.appearance.readerLayout';
export const READER_STYLE_KEY: string = 'deepread.appearance.readerStyle';
export const READER_APPEARANCE_EPOCH: string = 'deepreadAppearanceEpoch';
export const DEEPREAD_READER_LAYOUTS: DeepReadAppearanceChoice[] = [
  { id: 'classic', name: '经典', detail: '按话题类型安排：事件先看时间轴，争议先看各方立场。' },
  { id: 'brief', name: '速读', detail: '先看关键判断和各方立场，时间轴放到后面。' },
  { id: 'timeline', name: '时间线', detail: '以时间轴为主线，事件串成一条竖线。' },
  { id: 'debate', name: '观点对照', detail: '各方立场做成卡片，核心争议放大。' },
];
export const DEEPREAD_READER_STYLES: DeepReadAppearanceChoice[] = [
  { id: 'classic', name: '经典杂志', detail: '暖纸衬线，留白克制。' },
  { id: 'scroll', name: '书卷', detail: '古籍书页，居中标题与衬线正文。' },
  { id: 'briefing', name: '简报', detail: '无衬线标题，编号要点，信息密度高。' },
  { id: 'journal', name: '手账', detail: '暖色纸张、荧光笔与胶带标签。' },
  { id: 'broadsheet', name: '报纸', detail: '双线报头、粗体大标题。' },
  { id: 'minimal', name: '极简', detail: '纯白无衬线，去掉分隔线，只留内容。' },
  { id: 'academic', name: '学术', detail: '摘要框、章节编号与编号参考文献。' },
  { id: 'colorPage', name: '彩页', detail: '主题色报头色块，卡片式脉络，引言反白。' },
];
export const readerLayoutName = (id: string): string =>
  DEEPREAD_READER_LAYOUTS.find((choice: DeepReadAppearanceChoice): boolean => choice.id === id)?.name ?? '经典';
export const readerStyleName = (id: string): string =>
  DEEPREAD_READER_STYLES.find((choice: DeepReadAppearanceChoice): boolean => choice.id === id)?.name ?? '经典杂志';
const validChoice = (choices: DeepReadAppearanceChoice[], id: string): string =>
  choices.some((choice: DeepReadAppearanceChoice): boolean => choice.id === id) ? id : 'classic';

// Reading appearance is independent of a generation template or an article's captured HTML.
export const loadDeepReadAppearance = async (storage: Storage): Promise<DeepReadReaderAppearance> => {
  const layout: string = validChoice(DEEPREAD_READER_LAYOUTS, await storage.get<string>(READER_LAYOUT_KEY, 'classic'));
  const style: string = validChoice(DEEPREAD_READER_STYLES, await storage.get<string>(READER_STYLE_KEY, 'classic'));
  AppStorage.setOrCreate(READER_LAYOUT_KEY, layout);
  AppStorage.setOrCreate(READER_STYLE_KEY, style);
  return { readerLayout: layout, readerStyle: style };
};
const saveChoice = async (storage: Storage, key: string, value: string): Promise<void> => {
  await storage.set<string>(key, value);
  AppStorage.setOrCreate(key, value);
  AppStorage.setOrCreate(READER_APPEARANCE_EPOCH, (AppStorage.get<number>(READER_APPEARANCE_EPOCH) ?? 0) + 1);
};
export const saveDeepReadReaderLayout = (storage: Storage, id: string): Promise<void> =>
  saveChoice(storage, READER_LAYOUT_KEY, validChoice(DEEPREAD_READER_LAYOUTS, id));
export const saveDeepReadReaderStyle = (storage: Storage, id: string): Promise<void> =>
  saveChoice(storage, READER_STYLE_KEY, validChoice(DEEPREAD_READER_STYLES, id));

export const readerCanvas = (style: string, dark: boolean): DeepReadReaderCanvas => {
  const key: string = style + (dark ? ':dark' : ':light');
  switch (key) {
    case 'classic:light': return { background: '#FBF7F1', foreground: '#2A2320', surface: '#F2EADE', muted: '#6E6254', border: '#DBCEBC' };
    case 'classic:dark': return { background: '#201C19', foreground: '#F1E9DF', surface: '#2A2520', muted: '#B9AA98', border: '#54493D' };
    case 'scroll:light': return { background: '#F3EBDA', foreground: '#2B2118', surface: '#EADFC9', muted: '#6F604C', border: '#D8C9AE' };
    case 'scroll:dark': return { background: '#1C1813', foreground: '#EADFCB', surface: '#26201A', muted: '#A8977F', border: '#433829' };
    case 'briefing:light': return { background: '#F6F6F3', foreground: '#141414', surface: '#ECECE7', muted: '#5E5E58', border: '#DADAD3' };
    case 'briefing:dark': return { background: '#121212', foreground: '#EDEDEA', surface: '#1D1D1C', muted: '#9C9C96', border: '#343432' };
    case 'journal:light': return { background: '#FBF8F2', foreground: '#2D2A26', surface: '#F1ECE2', muted: '#6E675F', border: '#E2D9CB' };
    case 'journal:dark': return { background: '#1E1D1B', foreground: '#ECE6DC', surface: '#292724', muted: '#A39C92', border: '#3D3A35' };
    case 'broadsheet:light': return { background: '#F5F1E8', foreground: '#1A1714', surface: '#EBE5D8', muted: '#6B645A', border: '#CFC6B5' };
    case 'broadsheet:dark': return { background: '#171513', foreground: '#E9E3D8', surface: '#221F1C', muted: '#9E968A', border: '#3A3530' };
    case 'minimal:light': return { background: '#FFFFFF', foreground: '#1C1C1E', surface: '#F2F2F7', muted: '#6C6C70', border: '#E5E5EA' };
    case 'minimal:dark': return { background: '#000000', foreground: '#F2F2F7', surface: '#1C1C1E', muted: '#98989F', border: '#2C2C2E' };
    case 'academic:light': return { background: '#FDFDFB', foreground: '#222222', surface: '#F3F2EE', muted: '#63635D', border: '#D9D8D2' };
    case 'academic:dark': return { background: '#1A1A1A', foreground: '#E6E6E3', surface: '#242424', muted: '#9D9D98', border: '#3A3A3A' };
    case 'colorPage:light': return { background: '#FFFCF7', foreground: '#1F1A17', surface: '#F5EEE4', muted: '#6B5F55', border: '#E6DBCD' };
    case 'colorPage:dark': return { background: '#1A1716', foreground: '#F0E8DE', surface: '#26221F', muted: '#A89A8C', border: '#3E3733' };
    default: return readerCanvas('classic', dark);
  }
};

export const readerSectionOrder = (topic: string, layout: string = 'classic'): string[] => {
  if (layout === 'brief') return ['core', 'analysis', 'uncertainties', 'outlook', 'diagram', 'timeline'];
  if (layout === 'timeline') return ['timeline', 'diagram', 'core', 'analysis', 'uncertainties', 'outlook'];
  if (layout === 'debate') return ['analysis', 'uncertainties', 'outlook', 'core', 'timeline', 'diagram'];
  if (topic === 'opinion') return ['core', 'analysis', 'outlook', 'uncertainties', 'timeline', 'diagram'];
  if (topic === 'product') return ['core', 'diagram', 'analysis', 'outlook', 'uncertainties', 'timeline'];
  if (topic === 'person') return ['core', 'timeline', 'analysis', 'uncertainties', 'outlook', 'diagram'];
  return ['core', 'timeline', 'diagram', 'analysis', 'uncertainties', 'outlook'];
};

// CSS targets the existing safe editorial markup; no second article renderer is introduced.
export const readerStyleCss = (style: string): string => {
  switch (style) {
    case 'scroll': return `
      .headline{text-align:center;padding-top:14px}.title{font-size:calc(30px * var(--ds));letter-spacing:.04em}
      .title::after{content:'❦';display:block;color:var(--dr-accent);font-size:18px;margin:10px 0 4px}
      .sec{text-align:center;letter-spacing:.35em;font-size:calc(15px * var(--ds))}.sec::before{display:none}
      section p{text-indent:2em;text-align:justify}.headline .summary{text-align:left}
      .headline .summary p:first-child::first-letter{float:left;font-size:3.2em;line-height:.9;margin:.06em .12em 0 0;color:var(--dr-accent)}
      blockquote{border:0;text-align:center;font-style:italic;background:transparent}`;
    case 'briefing': return `
      .title,.sec,.holder,.date{font-family:var(--dr-sans)}.title{font-weight:800;line-height:1.15}
      .kicker{display:inline-block;background:var(--dr-accent);color:var(--dr-bg);padding:3px 8px;border-radius:4px}
      .summary{background:var(--dr-surface);border-left:4px solid var(--dr-accent);padding:14px 16px;border-radius:0 12px 12px 0}
      .sec{font-family:var(--dr-sans);font-size:calc(13px * var(--ds));border-top:3px solid var(--dr-ink);padding-top:8px}.sec::before{display:none}
      section{counter-reset:point}.point{counter-increment:point;position:relative;padding-left:46px}
      .point::before{content:counter(point,decimal-leading-zero);position:absolute;left:10px;top:14px;color:var(--dr-accent);font-family:var(--dr-sans);font-size:24px;font-weight:800}`;
    case 'journal': return `
      body{background-image:radial-gradient(var(--dr-border) 1px,transparent 1.3px);background-size:18px 18px}
      .title{font-size:calc(28px * var(--ds));line-height:1.45;background:linear-gradient(transparent 60%,color-mix(in srgb,var(--dr-accent) 28%,transparent) 60%)}
      .summary{background:var(--dr-surface);padding:14px 16px;border-radius:14px;transform:rotate(-.4deg);box-shadow:0 2px 0 var(--dr-border)}
      .sec{display:inline-block;background:color-mix(in srgb,var(--dr-accent) 20%,var(--dr-bg));padding:4px 12px;transform:rotate(-1.5deg);font-size:calc(13px * var(--ds))}.sec::before{display:none}
      strong{background:linear-gradient(transparent 58%,color-mix(in srgb,var(--dr-accent) 30%,transparent) 58%)}.ev,.point,.persp{border-top:1px dashed var(--dr-border)}
      blockquote{border:0;background:var(--dr-surface);padding:12px 14px;border-radius:12px}`;
    case 'broadsheet': return `
      .headline{border-top:4px double var(--dr-ink);border-bottom:1px solid var(--dr-ink);padding:10px 0 14px;text-align:center}
      .title{font-weight:900;font-size:calc(34px * var(--ds));line-height:1.08}.kicker{color:var(--dr-ink);letter-spacing:.4em}
      .sec{text-align:center;border-top:2px solid var(--dr-ink);border-bottom:1px solid var(--dr-ink);padding:5px 0;font-size:calc(15px * var(--ds))}.sec::before{display:none}
      section p{text-align:justify}blockquote{border-radius:0;border-left:0;border-right:0;background:transparent;text-align:center;font-weight:700}
      @media(min-width:640px){.summary{column-count:2;column-gap:28px;column-rule:1px solid var(--dr-border)}}`;
    case 'minimal': return `
      body{font-family:var(--dr-sans)}.title{font-size:calc(28px * var(--ds));font-weight:700;line-height:1.2}
      .sec{font-size:calc(12px * var(--ds));letter-spacing:.06em;margin-top:40px}.sec::before{display:none}
      .point,.persp,.reading a,.impacts li{border:0;background:transparent}.point{padding:0}.diagram-node{border:0}
      blockquote{border:0;border-left:2px solid var(--dr-accent);background:transparent;border-radius:0}`;
    case 'academic': return `
      body{counter-reset:chapter}.headline{text-align:center}.title{font-weight:600;font-size:calc(26px * var(--ds));line-height:1.35}
      .summary{text-align:justify;font-size:calc(14px * var(--ds));border-top:1px solid var(--dr-ink);border-bottom:1px solid var(--dr-ink);padding:12px 4px}
      .summary::before{content:'摘要';display:block;text-align:center;font-family:var(--dr-sans);font-weight:700;font-size:12px;letter-spacing:.3em;margin-bottom:8px}
      .sec{counter-increment:chapter;font-size:calc(15px * var(--ds));font-weight:700}.sec::before{content:counter(chapter) '. ';width:auto;height:auto;background:transparent;margin-right:0}
      section p{text-align:justify}`;
    case 'colorPage': return `
      .headline{background:var(--dr-accent);padding:24px 22px 22px;margin:-24px -20px 24px}
      .headline,.headline .title,.headline .kicker,.headline .summary,.headline .bottom-line,.headline .meta{color:var(--dr-bg)}
      .sec{display:inline-block;background:var(--dr-ink);color:var(--dr-bg);padding:4px 10px;font-size:calc(13px * var(--ds))}.sec::before{display:none}
      .ev,.point{border:0;background:color-mix(in srgb,var(--dr-accent) 8%,var(--dr-bg));border-radius:12px;padding:12px;margin-bottom:8px}
      .point h3{color:var(--dr-accent)}blockquote{border:0;background:var(--dr-accent);color:var(--dr-bg);padding:14px 16px}blockquote .by{color:inherit}`;
    default: return '';
  }
};
export const readerLayoutCss = (layout: string): string => {
  if (layout === 'brief') return '.ev{padding:7px 0}.ev figure,.point figure{display:none}.point h3{font-size:calc(17px * var(--ds))}';
  if (layout === 'timeline') return '.ev{border:0;padding:10px 0 14px}.date{font-size:calc(12px * var(--ds));font-weight:700}.ev p{font-size:calc(16px * var(--ds))}';
  if (layout === 'debate') return '.persp{background:var(--dr-surface);border-left:3px solid var(--dr-accent);border-radius:12px;padding:12px 14px;margin:0 0 10px}.persp .holder{font-weight:700}.dispute,blockquote{font-size:calc(21px * var(--ds));line-height:1.45}';
  return '';
};
