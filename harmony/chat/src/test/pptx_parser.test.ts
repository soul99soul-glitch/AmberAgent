// pptx_parser 测试 — PptxParser.kt 行为钉住(D-113)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { deflateRawSync, inflateRawSync } from 'node:zlib';
import { parsePptxFromZip, parsePptxNotesXml, parsePptxSlideXml } from '../main/ets/chat/pptx_parser.ts';
import { et, factoryOf, st, tx } from './fake_xml_pull.ts';
import type { Token } from './fake_xml_pull.ts';

const nodeInflate = (data: Uint8Array, _expected: number): Promise<Uint8Array> =>
  Promise.resolve(new Uint8Array(inflateRawSync(data)));

// 形状:sp > p > r > t
const shape = (depth: number, paras: Token[]): Token[] => [st('sp', depth), ...paras, et('sp', depth)];
const para = (depth: number, runs: Token[], pPr?: Token[]): Token[] => [
  st('p', depth), ...(pPr ?? []), ...runs, et('p', depth),
];
const run = (depth: number, text: string): Token[] => [
  st('r', depth), st('t', depth + 1), tx(text, depth + 2), et('t', depth + 1), et('r', depth),
];
const slideDoc = (body: Token[]): Token[] => [
  st('sld', 1), st('cSld', 2), st('spTree', 3), ...body, et('spTree', 3), et('cSld', 2), et('sld', 1),
];

test('普通段落 → 文本(形状间空行;结果不 trim 于 slide 级)', () => {
  const out = parsePptxSlideXml(factoryOf(slideDoc([
    ...shape(4, para(5, run(6, 'Hello'))),
    ...shape(4, para(5, run(6, 'World'))),
  ])), '');
  // 每形状 trim 后 + '\n\n';slide 级不 trim
  assert.equal(out, 'Hello\n\nWorld\n\n');
});

test('无序项目符号(buChar)→ "- ";lvl 缩进', () => {
  const pPr: Token[] = [
    st('pPr', 6),
    st('buChar', 7, { char: '•' }), et('buChar', 7),
    st('lvl', 7, { val: '2' }), et('lvl', 7),
    et('pPr', 6),
  ];
  const out = parsePptxSlideXml(factoryOf(slideDoc([
    ...shape(4, [
      ...para(5, run(6, 'intro')),
      ...para(5, run(6, 'point'), pPr),
    ]),
  ])), '');
  assert.equal(out, 'intro\n    - point\n\n');
});

test('编号项目符号(buAutoNum)→ "1. "', () => {
  const pPr: Token[] = [
    st('pPr', 6),
    st('buAutoNum', 7, { type: 'arabicPeriod' }), et('buAutoNum', 7),
    et('pPr', 6),
  ];
  const out = parsePptxSlideXml(factoryOf(slideDoc([
    ...shape(4, [
      ...para(5, run(6, 'first')),
      ...para(5, run(6, 'numbered'), pPr),
    ]),
  ])), '');
  assert.equal(out, 'first\n1. numbered\n\n');
});

test('graphicFrame 表格 → markdown(尾换行在 if 内)', () => {
  const cell = (depth: number, texts: string[]): Token[] => {
    const tokens: Token[] = [st('tc', depth)];
    for (const t of texts) {
      tokens.push(st('t', depth + 1), tx(t, depth + 2), et('t', depth + 1));
    }
    tokens.push(et('tc', depth));
    return tokens;
  };
  const out = parsePptxSlideXml(factoryOf(slideDoc([
    st('graphicFrame', 4),
    st('tbl', 5),
    st('tr', 6), ...cell(7, ['A']), ...cell(7, ['B', 'C']), et('tr', 6),
    et('tbl', 5),
    et('graphicFrame', 4),
  ])), '');
  // 单元格多 t ' ' 连接 → 'B C';单表行 + 分隔;无形状时直接为表格输出
  assert.equal(out, '| A | B C | \n| --- | --- | \n\n');
});

test('slide XML 异常 → 内联错误文案(带换行)', () => {
  const out = parsePptxSlideXml((): never => {
    throw new Error('bad slide');
  }, '<broken');
  assert.equal(out, 'Error parsing slide XML: bad slide\n');
});

test('notes:ph type=body → 提取;p 结束换行;非 body 忽略', () => {
  const notesTokens: Token[] = [
    st('notes', 1),
    // 幻灯片预览形状(ph type 缺失 → false,整个 sp 内容仍在主循环扫过但不提取)
    st('sp', 2), st('nvSpPr', 3), st('ph', 4, { type: 'sldImg' }), et('ph', 4), et('nvSpPr', 3),
    st('p', 3), st('r', 4), st('t', 5), tx('preview', 6), et('t', 5), et('r', 4), et('p', 3),
    et('sp', 2),
    // 备注正文形状
    st('sp', 2), st('nvSpPr', 3), st('ph', 4, { type: 'body' }), et('ph', 4), et('nvSpPr', 3),
    st('p', 3), st('r', 4), st('t', 5), tx('note line', 6), et('t', 5), et('r', 4), et('p', 3),
    et('sp', 2),
    et('notes', 1),
  ];
  const out = parsePptxNotesXml(factoryOf(notesTokens), '');
  assert.equal(out, 'note line');
});

test('notes 异常 → 空串静默', () => {
  const out = parsePptxNotesXml((): never => {
    throw new Error('bad notes');
  }, '<broken');
  assert.equal(out, '');
});

// ===== ZIP 层(端到端:buildZip 夹具) =====

const te = new TextEncoder();
const buildZip = (entries: { name: string; text: string; }[]): Uint8Array => {
  const parts: number[] = [];
  const cd: { name: string; local: number; comp: Uint8Array; plain: Uint8Array; method: number; }[] = [];
  for (const e of entries) {
    const name = te.encode(e.name);
    const plain = te.encode(e.text);
    const comp = new Uint8Array(deflateRawSync(plain));
    const local = parts.length;
    parts.push(
      0x50, 0x4b, 0x03, 0x04, 20, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      comp.length & 0xff, (comp.length >> 8) & 0xff, 0, 0,
      plain.length & 0xff, (plain.length >> 8) & 0xff, 0, 0,
      name.length & 0xff, (name.length >> 8) & 0xff, 0, 0,
      ...name, ...comp,
    );
    cd.push({ name: e.name, local, comp, plain, method: 8 });
  }
  const cdOff = parts.length;
  for (const c of cd) {
    const name = te.encode(c.name);
    parts.push(
      0x50, 0x4b, 0x01, 0x02, 20, 0, 20, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      c.comp.length & 0xff, (c.comp.length >> 8) & 0xff, 0, 0,
      c.plain.length & 0xff, (c.plain.length >> 8) & 0xff, 0, 0,
      name.length & 0xff, (name.length >> 8) & 0xff,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      c.local & 0xff, (c.local >> 8) & 0xff, (c.local >> 16) & 0xff, (c.local >> 24) & 0xff,
      ...name,
    );
  }
  const cdSize = parts.length - cdOff;
  const count = cd.length;
  parts.push(
    0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0,
    count & 0xff, (count >> 8) & 0xff, count & 0xff, (count >> 8) & 0xff,
    cdSize & 0xff, (cdSize >> 8) & 0xff, (cdSize >> 16) & 0xff, (cdSize >> 24) & 0xff,
    cdOff & 0xff, (cdOff >> 8) & 0xff, (cdOff >> 16) & 0xff, (cdOff >> 24) & 0xff,
    0, 0,
  );
  return new Uint8Array(parts);
};

test('端到端:双幻灯片(CD 序)+ 备注 → ## Slide N 分节', async () => {
  const slide1 = '<sld><cSld><spTree><sp><p><r><t>First</t></r></p></sp></spTree></cSld></sld>';
  const slide2 = '<sld><cSld><spTree><sp><p><r><t>Second</t></r></p></sp></spTree></cSld></sld>';
  const notes2 = '<notes><sp><nvSpPr><ph type="body"/></nvSpPr><p><r><t>speaker note</t></r></p></sp></notes>';
  const zip = buildZip([
    { name: 'ppt/slides/slide1.xml', text: slide1 },
    { name: 'ppt/slides/slide2.xml', text: slide2 },
    { name: 'ppt/notesSlides/notesSlide2.xml', text: notes2 },
  ]);
  const out = await parsePptxFromZip(zip, nodeInflate, (xmlText: string) => {
    throw new Error(`fixture uses real xml; got len ${xmlText.length}`);
  });
  // 工厂抛错 → 每 slide 内联错误 + notes 静默空 → 结构可断言
  assert.ok(out.startsWith('## Slide 1'));
  assert.ok(out.includes('## Slide 2'));
  assert.ok(out.includes('Error parsing slide XML: fixture uses real xml'));
});

test('无幻灯片 → No slides found in PPTX file', async () => {
  const zip = buildZip([{ name: 'docProps/app.xml', text: '<x/>' }]);
  const out = await parsePptxFromZip(zip, nodeInflate, factoryOf([]));
  assert.equal(out, 'No slides found in PPTX file');
});

test('损坏 zip → Error parsing PPTX file: <msg>', async () => {
  const out = await parsePptxFromZip(te.encode('junk'), nodeInflate, factoryOf([]));
  assert.ok(out.startsWith('Error parsing PPTX file: '));
});
