// html_dom — mini-DOM:容错 HTML 解析 + CSS 选择器子集(D-067)
//
// Android 基准: org.jsoup.Jsoup(BingSearchService/DuckDuckGoSearchService 所用面)
// 支持子集(按两源实际用量裁剪,登记偏差):
//   - 选择器:逗号组 + 后代(空格)/子代(>) 组合 + 复合 [tag][#id][.class]*
//     (不支持:属性选择器/伪类/兄弟组合符 — 两源未用)
//   - 遍历:select/selectFirst/attr/hasClass/parents/parent/nextElementSibling
//   - text():递归聚合 + 空白折叠 + trim(Jsoup appendNormalisedText 近似,
//     块边界空格规则简化登记)
//   - 解析:容错(未闭合自动闭合于文档尾/close-tag 弹栈至匹配);void 元素;
//     script/style rawtext 不解析;属性单双引号/无引号;实体 &amp;/&lt;/&gt;/
//     &quot;/&#39;/&apos;/&nbsp;/&#nnn;/&#xhh;
//   - 不做 HTML5 implied-close 规则(<li> 相邻不互闭 — 与 Jsoup 树形差异登记)

export interface HtmlElement {
  tagName: string;
  attributes: Record<string, string>;
  children: HtmlElement[];
  textChunks: string[]; // 直接文本子节点(解析序,与子元素顺序不混排 — 仅 text() 用)
  parent: HtmlElement | null;
}

export interface HtmlDocument {
  root: HtmlElement;
}

const VOID_TAGS: string[] = [
  'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input',
  'link', 'meta', 'param', 'source', 'track', 'wbr',
];

const RAWTEXT_TAGS: string[] = ['script', 'style'];

// ===== 实体解码 =====

const decodeEntities = (s: string): string =>
  s.replace(/&(amp|lt|gt|quot|apos|nbsp|#39|#x27|#\d+|#x[0-9A-Fa-f]+);/g,
    (whole: string, body: string): string => {
      switch (body) {
        case 'amp': return '&';
        case 'lt': return '<';
        case 'gt': return '>';
        case 'quot': return '"';
        case 'apos':
        case '#39':
        case '#x27': return '\'';
        case 'nbsp': return ' ';
        default: {
          if (body.startsWith('#x') || body.startsWith('#X')) {
            return String.fromCharCode(parseInt(body.slice(2), 16));
          }
          if (body.startsWith('#')) {
            return String.fromCharCode(parseInt(body.slice(1), 10));
          }
          return whole;
        }
      }
    });

// ===== 解析 =====

const makeElement = (tag: string, parent: HtmlElement | null): HtmlElement => ({
  tagName: tag,
  attributes: {},
  children: [],
  textChunks: [],
  parent,
});

// 属性段解析:name(=value)?;value 双/单引号或无引号
const parseAttributes = (s: string, el: HtmlElement): void => {
  let i: number = 0;
  const n: number = s.length;
  while (i < n) {
    while (i < n && /\s/.test(s[i])) i++;
    if (i >= n) break;
    let name: string = '';
    while (i < n && !/[\s=/>]/.test(s[i])) {
      name += s[i];
      i++;
    }
    if (name.length === 0) {
      i++;
      continue;
    }
    while (i < n && /\s/.test(s[i])) i++;
    let value: string = '';
    if (i < n && s[i] === '=') {
      i++;
      while (i < n && /\s/.test(s[i])) i++;
      if (i < n && (s[i] === '"' || s[i] === '\'')) {
        const quote: string = s[i];
        i++;
        const start: number = i;
        while (i < n && s[i] !== quote) i++;
        value = s.slice(start, i);
        i++; // 跳过引号
      } else {
        const start: number = i;
        while (i < n && !/[\s>]/.test(s[i])) i++;
        value = s.slice(start, i);
      }
    }
    el.attributes[name.toLowerCase()] = decodeEntities(value);
  }
};

export const parseHtml = (html: string): HtmlDocument => {
  const root: HtmlElement = makeElement('#document', null);
  let current: HtmlElement = root;
  let i: number = 0;
  const n: number = html.length;
  while (i < n) {
    const lt: number = html.indexOf('<', i);
    if (lt < 0) {
      if (i < n) current.textChunks.push(decodeEntities(html.slice(i)));
      break;
    }
    if (lt > i) current.textChunks.push(decodeEntities(html.slice(i, lt)));
    if (html.startsWith('<!--', lt)) {
      const end: number = html.indexOf('-->', lt + 4);
      i = end < 0 ? n : end + 3;
      continue;
    }
    if (html.startsWith('<!', lt) || html.startsWith('<?', lt)) {
      const end: number = html.indexOf('>', lt + 2);
      i = end < 0 ? n : end + 1;
      continue;
    }
    const gt: number = html.indexOf('>', lt + 1);
    if (gt < 0) break;
    const inner: string = html.slice(lt + 1, gt);
    i = gt + 1;
    if (inner.startsWith('/')) {
      // 闭合标签:弹栈至匹配(无匹配忽略)
      const name: string = inner.slice(1).trim().toLowerCase();
      let p: HtmlElement | null = current;
      while (p !== null && p !== root && p.tagName !== name) {
        p = p.parent;
      }
      if (p !== null && p !== root && p.parent !== null) {
        current = p.parent;
      }
      continue;
    }
    const selfClose: boolean = inner.endsWith('/');
    const body: string = selfClose ? inner.slice(0, -1) : inner;
    const spaceIdx: number = body.search(/\s/);
    const tag: string = (spaceIdx < 0 ? body : body.slice(0, spaceIdx)).toLowerCase();
    if (tag.length === 0) continue;
    const el: HtmlElement = makeElement(tag, current);
    if (spaceIdx >= 0) parseAttributes(body.slice(spaceIdx + 1), el);
    current.children.push(el);
    if (selfClose || VOID_TAGS.includes(tag)) continue;
    if (RAWTEXT_TAGS.includes(tag)) {
      // rawtext:扫至匹配闭合标签,内容作单文本块
      const closeRe: RegExp = new RegExp(`</${tag}\\s*>`, 'i');
      const rest: string = html.slice(i);
      const m: RegExpExecArray | null = closeRe.exec(rest);
      if (m !== null) {
        if (m.index > 0) el.textChunks.push(rest.slice(0, m.index));
        i = i + m.index + m[0].length;
      } else {
        el.textChunks.push(rest);
        i = n;
      }
      continue;
    }
    current = el;
  }
  return { root };
};

// ===== 选择器 =====

interface CompoundSelector {
  tag: string | null;
  id: string | null;
  classes: string[];
}

interface SelectorStep {
  compound: CompoundSelector;
  childOnly: boolean; // 与前一步之间为 '>' 组合符
}

const parseCompound = (s: string): CompoundSelector | null => {
  const m: RegExpExecArray | null =
    /^([A-Za-z][A-Za-z0-9-]*)?((?:[#.][A-Za-z0-9_-]+)+)?$/.exec(s);
  if (m === null || (m[1] === undefined && m[2] === undefined)) return null;
  const classes: string[] = [];
  let id: string | null = null;
  if (m[2] !== undefined) {
    const re: RegExp = /([#.])([A-Za-z0-9_-]+)/g;
    let mm: RegExpExecArray | null = re.exec(m[2]);
    while (mm !== null) {
      if (mm[1] === '#') {
        id = mm[2];
      } else {
        classes.push(mm[2]);
      }
      mm = re.exec(m[2]);
    }
  }
  return {
    tag: m[1] !== undefined ? m[1].toLowerCase() : null,
    id,
    classes,
  };
};

const parseSelectorGroup = (group: string): SelectorStep[] | null => {
  // 组合符:空格(后代)/ > (子代)— 扫描重组,'>' 标记挂右侧 step
  const steps: SelectorStep[] = [];
  const parts: Array<{ token: string; child: boolean }> = [];
  let child: boolean = false;
  let buf: string = '';
  const flush = (): void => {
    if (buf.trim().length > 0) {
      parts.push({ token: buf.trim(), child });
      buf = '';
      child = false;
    }
  };
  for (let i = 0; i < group.length; i++) {
    const c: string = group[i];
    if (c === '>') {
      flush();
      child = true;
    } else if (/\s/.test(c)) {
      flush();
    } else {
      buf += c;
    }
  }
  flush();
  for (const p of parts) {
    const compound = parseCompound(p.token);
    if (compound === null) return null;
    steps.push({ compound, childOnly: p.child });
  }
  return steps.length > 0 ? steps : null;
};

export const hasClass = (el: HtmlElement, cls: string): boolean => {
  const c: string | undefined = el.attributes['class'];
  if (c === undefined) return false;
  return c.split(/\s+/).includes(cls);
};

const matchesCompound = (el: HtmlElement, c: CompoundSelector): boolean => {
  if (c.tag !== null && el.tagName !== c.tag) return false;
  if (c.id !== null && el.attributes['id'] !== c.id) return false;
  for (const cls of c.classes) {
    if (!hasClass(el, cls)) return false;
  }
  return true;
};

// 自右向左匹配:末步命中元素,余步沿祖先(后代)或直系父(子代)
const matchesSteps = (el: HtmlElement, steps: SelectorStep[]): boolean => {
  if (!matchesCompound(el, steps[steps.length - 1].compound)) return false;
  let cursor: HtmlElement | null = el;
  for (let i: number = steps.length - 2; i >= 0; i--) {
    const step: SelectorStep = steps[i];
    // 注意:'>' 标记在右侧 step 上(childOnly 表示该 step 与前一步为子代关系)
    const childRel: boolean = steps[i + 1].childOnly;
    if (childRel) {
      cursor = cursor.parent;
      if (cursor === null || !matchesCompound(cursor, step.compound)) return false;
    } else {
      let found: HtmlElement | null = null;
      let p: HtmlElement | null = cursor.parent;
      while (p !== null) {
        if (matchesCompound(p, step.compound)) {
          found = p;
          break;
        }
        p = p.parent;
      }
      if (found === null) return false;
      cursor = found;
    }
  }
  return true;
};

const collectDescendants = (el: HtmlElement, out: HtmlElement[]): void => {
  for (const c of el.children) {
    out.push(c);
    collectDescendants(c, out);
  }
};

export const select = (
  root: HtmlElement | HtmlDocument, selector: string,
): HtmlElement[] => {
  const base: HtmlElement = 'root' in root ? (root as HtmlDocument).root : root as HtmlElement;
  const groups: string[] = selector.split(',').map((g: string): string => g.trim())
    .filter((g: string): boolean => g.length > 0);
  const all: HtmlElement[] = [];
  collectDescendants(base, all);
  const out: HtmlElement[] = [];
  for (const el of all) {
    for (const g of groups) {
      const steps = parseSelectorGroup(g);
      if (steps !== null && matchesSteps(el, steps)) {
        out.push(el);
        break;
      }
    }
  }
  return out;
};

export const selectFirst = (
  root: HtmlElement | HtmlDocument, selector: string,
): HtmlElement | null => {
  const r: HtmlElement[] = select(root, selector);
  return r.length > 0 ? r[0] : null;
};

// ===== 遍历/取值 =====

export const attr = (el: HtmlElement, name: string): string =>
  el.attributes[name.toLowerCase()] ?? '';

const collectText = (el: HtmlElement, out: string[]): void => {
  // 文本块与子元素按解析序交错失真(textChunks 集中存放)— 近似:先文本后子树,
  // 与 Jsoup 序差异在折叠空白下不影响 Bing/DDG 字段提取(标题/摘要单元素内)
  for (const t of el.textChunks) out.push(t);
  for (const c of el.children) collectText(c, out);
};

export const elementText = (el: HtmlElement | HtmlDocument): string => {
  const base: HtmlElement = 'root' in el ? (el as HtmlDocument).root : el as HtmlElement;
  const chunks: string[] = [];
  collectText(base, chunks);
  return chunks.join(' ').replace(/\s+/g, ' ').trim();
};

export const parentsOf = (el: HtmlElement): HtmlElement[] => {
  const out: HtmlElement[] = [];
  let p: HtmlElement | null = el.parent;
  while (p !== null) {
    out.push(p);
    p = p.parent;
  }
  return out;
};

export const parentOf = (el: HtmlElement): HtmlElement | null => el.parent;

export const nextElementSiblingOf = (el: HtmlElement): HtmlElement | null => {
  if (el.parent === null) return null;
  const siblings: HtmlElement[] = el.parent.children;
  const idx: number = siblings.indexOf(el);
  if (idx < 0 || idx + 1 >= siblings.length) return null;
  return siblings[idx + 1];
};
