// epub_parser 测试 — EpubParser.kt 行为钉住(D-114)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { deflateRawSync, inflateRawSync } from 'node:zlib';
import {
  parseEpubContainerXml, parseEpubFromZip, parseEpubOpfXml, parseEpubXhtml,
} from '../main/ets/chat/epub_parser.ts';
import { et, factoryOf, FakePull, st, tx } from './fake_xml_pull.ts';
import type { Token } from './fake_xml_pull.ts';
import type { XmlPullEvent, XmlPullPort } from '../main/ets/chat/xml_pull.ts';

const nodeInflate = (data: Uint8Array, _expected: number): Promise<Uint8Array> =>
  Promise.resolve(new Uint8Array(inflateRawSync(data)));

test('xhtml 富结构 → markdown(标题/粗斜/列表/引用/hr/img/空白折叠)', () => {
  const tokens: Token[] = [
    st('html', 1), st('body', 2),
    st('h1', 3), tx('Title', 4), et('h1', 3),
    st('p', 3),
    st('strong', 4), tx('bold', 5), et('strong', 4),
    tx(' and ', 4),
    st('em', 4), tx('it', 5), et('em', 4),
    et('p', 3),
    st('ul', 3),
    st('li', 4), tx('a', 5), et('li', 4),
    st('li', 4), tx('b', 5), et('li', 4),
    et('ul', 3),
    st('blockquote', 3), tx('quote', 4), et('blockquote', 3),
    st('hr', 3), et('hr', 3),
    st('img', 3, { alt: 'pic' }), et('img', 3),
    et('body', 2), et('html', 1),
  ];
  const out = parseEpubXhtml(factoryOf(tokens), '');
  assert.equal(out, '# Title\n\n**bold** and *it*\n\n- a\n- b\n\n> quote\n\n---\n[image: pic]');
});

test('ol 共享计数 → "N. ";文本 \\s+ 折叠', () => {
  const tokens: Token[] = [
    st('html', 1), st('body', 2),
    st('ol', 3),
    st('li', 4), tx('x', 5), et('li', 4),
    st('li', 4), tx('y', 5), et('li', 4),
    et('ol', 3),
    st('p', 3), tx('a\t  b\n c', 4), et('p', 3),
    et('body', 2), et('html', 1),
  ];
  const out = parseEpubXhtml(factoryOf(tokens), '');
  assert.equal(out, '1. x\n2. y\n\na b c');
});

test('body 外内容忽略;img 无 alt 不输出', () => {
  const tokens: Token[] = [
    st('html', 1),
    st('head', 2), st('title', 3), tx('DocTitle', 4), et('title', 3), et('head', 2),
    st('body', 2),
    st('img', 3), et('img', 3),
    st('p', 3), tx('real', 4), et('p', 3),
    et('body', 2), et('html', 1),
  ];
  const out = parseEpubXhtml(factoryOf(tokens), '');
  assert.equal(out, 'real');
});

test('next() 异常 → break 保留部分结果(畸形容忍)', () => {
  class ThrowingPull extends FakePull {
    private n: number = 0;
    override next(): XmlPullEvent {
      this.n++;
      if (this.n >= 4) throw new Error('malformed tail');
      return super.next();
    }
  }
  const port: XmlPullPort = new ThrowingPull([st('body', 1), tx('Hi', 2), et('body', 1)]);
  const out = parseEpubXhtml((): XmlPullPort => port, '');
  assert.equal(out, 'Hi');
});

test('构造抛错 → 空串静默', () => {
  const out = parseEpubXhtml((): never => {
    throw new Error('bad xml');
  }, '<broken');
  assert.equal(out, '');
});

test('container:rootfile full-path 提取;缺失 → null', () => {
  const ok = parseEpubContainerXml(factoryOf([
    st('container', 1),
    st('rootfiles', 2),
    st('rootfile', 3, { 'full-path': 'OEBPS/content.opf', 'media-type': 'application/oebps-package+xml' }),
    et('rootfile', 3),
    et('rootfiles', 2),
    et('container', 1),
  ]), '');
  assert.equal(ok, 'OEBPS/content.opf');
  const missing = parseEpubContainerXml(factoryOf([st('container', 1), et('container', 1)]), '');
  assert.equal(missing, null);
});

test('opf:manifest 映射 + spine 顺序', () => {
  const opf = parseEpubOpfXml(factoryOf([
    st('package', 1),
    st('manifest', 2),
    st('item', 3, { id: 'ch1', href: 'ch1.xhtml', 'media-type': 'application/xhtml+xml' }), et('item', 3),
    st('item', 3, { id: 'cov', href: 'cover.png', 'media-type': 'image/png' }), et('item', 3),
    et('manifest', 2),
    st('spine', 2),
    st('itemref', 3, { idref: 'ch1' }), et('itemref', 3),
    st('itemref', 3, { idref: 'cov' }), et('itemref', 3),
    et('spine', 2),
    et('package', 1),
  ]), '');
  assert.equal(opf.spine.join(','), 'ch1,cov');
  const ch1 = opf.manifest.get('ch1');
  assert.equal(ch1?.href, 'ch1.xhtml');
  assert.equal(ch1?.mediaType, 'application/xhtml+xml');
});

// ===== ZIP 层 =====

const te = new TextEncoder();
const buildZip = (entries: { name: string; text: string; }[]): Uint8Array => {
  const parts: number[] = [];
  const cd: { name: string; local: number; comp: Uint8Array; plain: Uint8Array; }[] = [];
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
    cd.push({ name: e.name, local, comp, plain });
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

const throwFactory = (xmlText: string): never => {
  throw new Error(`no real parser in test (len ${xmlText.length})`);
};

test('fromZip:container 缺失 → Unable to find OPF file in EPUB', async () => {
  const zip = buildZip([{ name: 'x.html', text: '<html/>' }]);
  const out = await parseEpubFromZip(zip, nodeInflate, throwFactory, throwFactory);
  assert.equal(out, 'Unable to find OPF file in EPUB');
});

test('fromZip:opf 条目缺失 → Unable to read OPF file in EPUB', async () => {
  const zip = buildZip([{ name: 'META-INF/container.xml', text: '<container/>' }]);
  // 工厂返回 rootfile 指向不存在条目
  const out = await parseEpubFromZip(zip, nodeInflate,
    () => new FakePull([st('rootfile', 1, { 'full-path': 'missing/content.opf' })]),
    throwFactory);
  assert.equal(out, 'Unable to read OPF file in EPUB');
});

test('fromZip:解析失败内容空 → No readable content found in EPUB file', async () => {
  const zip = buildZip([
    { name: 'META-INF/container.xml', text: '<container/>' },
    { name: 'OEBPS/content.opf', text: '<package/>' },
    { name: 'OEBPS/ch1.xhtml', text: '<html><body><p>x</p></body></html>' },
  ]);
  // container → rootfile;opf → manifest+spine;xhtml 工厂抛错 → '' → 无内容
  let phase = 0;
  const seqFactory = (xmlText: string): XmlPullPort => {
    phase++;
    if (phase === 1) return new FakePull([st('rootfile', 1, { 'full-path': 'OEBPS/content.opf' })]);
    if (phase === 2) {
      return new FakePull([
        st('item', 1, { id: 'c1', href: 'ch1.xhtml', 'media-type': 'application/xhtml+xml' }),
        st('itemref', 2, { idref: 'c1' }),
      ]);
    }
    return throwFactory(xmlText);
  };
  const out = await parseEpubFromZip(zip, nodeInflate, seqFactory, throwFactory);
  assert.equal(out, 'No readable content found in EPUB file');
});

test('fromZip:损坏 zip → Error parsing EPUB file: <msg>', async () => {
  const out = await parseEpubFromZip(te.encode('junk'), nodeInflate, throwFactory, throwFactory);
  assert.ok(out.startsWith('Error parsing EPUB file: '));
});
