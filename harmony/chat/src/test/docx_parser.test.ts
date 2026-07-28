// docx_parser 测试 — DocxParser.kt 行为钉住(D-112)
// token 脚本驱动 fake XmlPullPort;输出与 Android 逐构造对齐
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseDocxDocumentXml, parseDocxFromZip } from '../main/ets/chat/docx_parser.ts';
import type { XmlPullEvent, XmlPullFactory, XmlPullPort } from '../main/ets/chat/xml_pull.ts';

interface Token {
  event: XmlPullEvent;
  name?: string;
  depth?: number;
  text?: string | null;
  attrs?: Record<string, string>;
}

class FakePull implements XmlPullPort {
  private idx: number = 0; // 初始 START_DOCUMENT(Kotlin parser 同)
  private tokens: Token[];
  constructor(tokens: Token[]) {
    this.tokens = [{ event: 'start_document', depth: 0 }, ...tokens, { event: 'end_document', depth: 0 }];
  }
  private get cur(): Token {
    return this.tokens[this.idx];
  }
  get eventType(): XmlPullEvent {
    return this.cur.event;
  }
  get name(): string {
    return this.cur.name ?? '';
  }
  get depth(): number {
    return this.cur.depth ?? 0;
  }
  get text(): string | null {
    return this.cur.text ?? null;
  }
  getAttributeValue(_namespace: string | null, name: string): string | null {
    const a = this.cur.attrs;
    if (a === undefined) return null;
    return Object.prototype.hasOwnProperty.call(a, name) ? a[name] : null;
  }
  next(): XmlPullEvent {
    if (this.idx < this.tokens.length - 1) this.idx++;
    return this.eventType;
  }
}

const factoryOf = (tokens: Token[]): XmlPullFactory => (): XmlPullPort => new FakePull(tokens);

const st = (name: string, depth: number, attrs?: Record<string, string>): Token =>
  ({ event: 'start_tag', name, depth, attrs });
const et = (name: string, depth: number): Token => ({ event: 'end_tag', name, depth });
const tx = (text: string, depth: number): Token => ({ event: 'text', text, depth });

// 常用包装:document > body > [内容] > end
const doc = (body: Token[]): Token[] => [
  st('document', 1), st('body', 2), ...body, et('body', 2), et('document', 1),
];
// 段落:runs = 文本数组(可带 rPr)
const para = (depth: number, runs: Token[], pPr?: Token[]): Token[] => [
  st('p', depth), ...(pPr ?? []), ...runs, et('p', depth),
];
const run = (depth: number, text: string, rPr?: Token[]): Token[] => [
  st('r', depth), ...(rPr ?? []), st('t', depth + 1), tx(text, depth + 2), et('t', depth + 1), et('r', depth),
];
const bold: Token[] = [st('rPr', 4), st('b', 5), et('b', 5), et('rPr', 4)];
const italic: Token[] = [st('rPr', 4), st('i', 5), et('i', 5), et('rPr', 4)];
const boldItalic: Token[] = [st('rPr', 4), st('b', 5), et('b', 5), st('i', 5), et('i', 5), et('rPr', 4)];

test('普通段落 → 原文(文档级 trim)', () => {
  const out = parseDocxDocumentXml(factoryOf(doc(para(3, run(4, 'Hello world')))), '');
  assert.equal(out, 'Hello world');
});

test('标题(pStyle Heading2)→ ## 前缀;heading 小写同命中', () => {
  const pPr: Token[] = [st('pPr', 4), st('pStyle', 5, { val: 'Heading2' }), et('pStyle', 5), et('pPr', 4)];
  const out = parseDocxDocumentXml(factoryOf(doc(para(3, run(4, 'Title'), pPr))), '');
  assert.equal(out, '## Title');
  const pPrLower: Token[] = [st('pPr', 4), st('pStyle', 5, { val: 'heading3' }), et('pStyle', 5), et('pPr', 4)];
  const out2 = parseDocxDocumentXml(factoryOf(doc(para(3, run(4, 'Sub'), pPrLower))), '');
  assert.equal(out2, '### Sub');
});

test('pStyle 非数字结尾 → 级别 1(digitToIntOrNull ?: 1)', () => {
  const pPr: Token[] = [st('pPr', 4), st('pStyle', 5, { val: 'HeadingX' }), et('pStyle', 5), et('pPr', 4)];
  const out = parseDocxDocumentXml(factoryOf(doc(para(3, run(4, 'H'), pPr))), '');
  assert.equal(out, '# H');
});

test('粗体/斜体/粗斜体 → **/*/*** 包裹', () => {
  const tokens = doc([
    ...para(3, [
      ...run(4, 'b', bold), ...run(4, ' '), ...run(4, 'i', italic), ...run(4, ' '), ...run(4, 'bi', boldItalic),
    ]),
  ]);
  const out = parseDocxDocumentXml(factoryOf(tokens), '');
  assert.equal(out, '**b** *i* ***bi***');
});

test('有序列表(numId 存在)→ 缩进 + "1. "(number 恒 1,Android 原样;前导段落保住缩进)', () => {
  const pPr: Token[] = [
    st('pPr', 4),
    st('numPr', 5),
    st('ilvl', 6, { val: '1' }), et('ilvl', 6),
    st('numId', 6, { val: '5' }), et('numId', 6),
    et('numPr', 5),
    et('pPr', 4),
  ];
  const out = parseDocxDocumentXml(factoryOf(doc([
    ...para(3, run(4, 'intro')),
    ...para(3, run(4, 'item'), pPr),
  ])), '');
  assert.equal(out, 'intro\n\n  1. item');
});

test('无序列表(ilvl>0 无 numId)→ 缩进 + "- "', () => {
  const pPr: Token[] = [
    st('pPr', 4),
    st('numPr', 5),
    st('ilvl', 6, { val: '2' }), et('ilvl', 6),
    et('numPr', 5),
    et('pPr', 4),
  ];
  const out = parseDocxDocumentXml(factoryOf(doc([
    ...para(3, run(4, 'intro')),
    ...para(3, run(4, 'bullet'), pPr),
  ])), '');
  assert.equal(out, 'intro\n\n    - bullet');
});

test('表格 → markdown(首行后 --- 分隔;缺列补空;尾换行后文档 trim)', () => {
  const cell = (depth: number, text: string): Token[] => [
    st('tc', depth), st('p', depth + 1), ...run(depth + 2, text), et('p', depth + 1), et('tc', depth),
  ];
  const tokens = doc([
    st('tbl', 3),
    st('tr', 4), ...cell(5, 'A'), ...cell(5, 'B'), et('tr', 4),
    st('tr', 4), ...cell(5, '1'), et('tr', 4),
    et('tbl', 3),
  ]);
  const out = parseDocxDocumentXml(factoryOf(tokens), '');
  assert.equal(out, '| A | B | \n| --- | --- | \n| 1 |  |');
});

test('单元格多段落 → 空格连接', () => {
  const tokens = doc([
    st('tbl', 3),
    st('tr', 4),
    st('tc', 5),
    st('p', 6), ...run(7, 'first'), et('p', 6),
    st('p', 6), ...run(7, 'second'), et('p', 6),
    et('tc', 5),
    et('tr', 4),
    et('tbl', 3),
  ]);
  const out = parseDocxDocumentXml(factoryOf(tokens), '');
  assert.equal(out, '| first second | \n| --- |');
});

test('body 外段落忽略', () => {
  const tokens = [
    st('document', 1), ...para(2, run(3, 'outside')), et('document', 1),
  ];
  const out = parseDocxDocumentXml(factoryOf(tokens), '');
  assert.equal(out, '');
});

test('空段落(blank)跳过', () => {
  const tokens = doc([
    ...para(3, run(4, '   ')),
    ...para(3, run(4, 'real')),
  ]);
  const out = parseDocxDocumentXml(factoryOf(tokens), '');
  assert.equal(out, 'real');
});

test('zip 未命中条目 → Unable to find document content in DOCX file', async () => {
  const out = await parseDocxFromZip((): Promise<string | null> => Promise.resolve(null), factoryOf([]));
  assert.equal(out, 'Unable to find document content in DOCX file');
});

test('zip 读取异常 → Error parsing DOCX file: <msg>', async () => {
  const out = await parseDocxFromZip(
    (): Promise<string | null> => Promise.reject(new Error('corrupt zip')), factoryOf([]));
  assert.equal(out, 'Error parsing DOCX file: corrupt zip');
});

test('命中条目 → 走 document.xml 解析', async () => {
  const out = await parseDocxFromZip(
    (name: string): Promise<string | null> => {
      assert.equal(name, 'word/document.xml');
      return Promise.resolve('<xml/>');
    },
    factoryOf(doc(para(3, run(4, 'via zip')))),
  );
  assert.equal(out, 'via zip');
});

test('parser 构造抛错 → Error parsing document XML: <msg>', () => {
  const bad: XmlPullFactory = (): XmlPullPort => {
    throw new Error('malformed');
  };
  const out = parseDocxDocumentXml(bad, '<broken');
  assert.equal(out, 'Error parsing document XML: malformed');
});
