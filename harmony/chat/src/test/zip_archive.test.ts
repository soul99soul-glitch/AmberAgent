// zip_archive 测试 — EOCD/CD 解析 + 条目提取钉住(D-113)
// 夹具:手工构造 ZIP(stored/deflate);deflate 经 node:zlib 供给/验证
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { deflateRawSync, inflateRawSync } from 'node:zlib';
import {
  extractZipEntryData, readZipEntries, readZipEntryText,
} from '../main/ets/chat/zip_archive.ts';
import type { ZipEntryRecord } from '../main/ets/chat/zip_archive.ts';

const te = new TextEncoder();

interface FixtureEntry { name: string; text: string; method: number; }

// 最小 ZIP 构造器(CD 顺序 = 写序;可乱序写 CD 验证枚举序来源)
const buildZip = (entries: FixtureEntry[], cdOrder?: number[]): Uint8Array => {
  const parts: number[] = [];
  const cd: { e: FixtureEntry; local: number; comp: Uint8Array; }[] = [];
  for (const e of entries) {
    const name = te.encode(e.name);
    const plain = te.encode(e.text);
    const comp = e.method === 8 ? new Uint8Array(deflateRawSync(plain)) : plain;
    const local = parts.length;
    // local header
    const lh = [
      0x50, 0x4b, 0x03, 0x04, 20, 0, 0, 0, e.method, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      comp.length & 0xff, (comp.length >> 8) & 0xff, 0, 0,
      plain.length & 0xff, (plain.length >> 8) & 0xff, 0, 0,
      name.length & 0xff, (name.length >> 8) & 0xff, 0, 0,
    ];
    parts.push(...lh, ...name, ...comp);
    cd.push({ e, local, comp });
  }
  const cdOff = parts.length;
  const order = cdOrder ?? entries.map((_, i) => i);
  for (const idx of order) {
    const { e, local, comp } = cd[idx];
    const name = te.encode(e.name);
    const plain = te.encode(e.text);
    const ch = [
      0x50, 0x4b, 0x01, 0x02, 20, 0, 20, 0, 0, 0, e.method, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      comp.length & 0xff, (comp.length >> 8) & 0xff, 0, 0,
      plain.length & 0xff, (plain.length >> 8) & 0xff, 0, 0,
      name.length & 0xff, (name.length >> 8) & 0xff,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      local & 0xff, (local >> 8) & 0xff, (local >> 16) & 0xff, (local >> 24) & 0xff,
    ];
    parts.push(...ch, ...name);
  }
  const cdSize = parts.length - cdOff;
  const count = order.length;
  parts.push(
    0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0,
    count & 0xff, (count >> 8) & 0xff, count & 0xff, (count >> 8) & 0xff,
    cdSize & 0xff, (cdSize >> 8) & 0xff, (cdSize >> 16) & 0xff, (cdSize >> 24) & 0xff,
    cdOff & 0xff, (cdOff >> 8) & 0xff, (cdOff >> 16) & 0xff, (cdOff >> 24) & 0xff,
    0, 0,
  );
  return new Uint8Array(parts);
};

const nodeInflate = (data: Uint8Array, _expected: number): Promise<Uint8Array> =>
  Promise.resolve(new Uint8Array(inflateRawSync(data)));

test('stored 双条目:CD 枚举序忠实(乱序 CD → 乱序枚举)', () => {
  const zip = buildZip([
    { name: 'a.txt', text: 'AAA', method: 0 },
    { name: 'b.txt', text: 'BBB', method: 0 },
  ], [1, 0]); // CD 写 b 后 a
  const entries = readZipEntries(zip);
  assert.deepEqual(entries.map((e) => e.name), ['b.txt', 'a.txt']);
  assert.equal(entries[0].method, 0);
});

test('stored 条目文本读取', async () => {
  const zip = buildZip([{ name: 'word/document.xml', text: '<doc>你好</doc>', method: 0 }]);
  const rec = readZipEntries(zip)[0];
  assert.equal(rec.name, 'word/document.xml');
  const text = await readZipEntryText(zip, rec, nodeInflate);
  assert.equal(text, '<doc>你好</doc>');
});

test('deflate 条目经 InflateRawPort 解压', async () => {
  const payload = '<slide>content 重复重复重复重复重复</slide>'.repeat(20);
  const zip = buildZip([{ name: 'ppt/slides/slide1.xml', text: payload, method: 8 }]);
  const rec = readZipEntries(zip)[0];
  assert.equal(rec.method, 8);
  assert.ok(rec.compressedSize < rec.uncompressedSize);
  const text = await readZipEntryText(zip, rec, nodeInflate);
  assert.equal(text, payload);
});

test('extractZipEntryData:压缩态切片长度 = compressedSize', () => {
  const zip = buildZip([{ name: 'x.bin', text: 'rawdata'.repeat(50), method: 8 }]);
  const rec: ZipEntryRecord = readZipEntries(zip)[0];
  const raw = extractZipEntryData(zip, rec);
  assert.equal(raw.length, rec.compressedSize);
});

test('EOCD 缺失 → 抛错(非静默)', () => {
  assert.throws(() => readZipEntries(te.encode('not a zip at all.......')), /EOCD not found/);
});

test('未知压缩方法 → 抛错(非静默)', async () => {
  const zip = buildZip([{ name: 'y', text: 'z', method: 0 }]);
  const rec = readZipEntries(zip)[0];
  const weird: ZipEntryRecord = { ...rec, method: 12 };
  await assert.rejects(() => readZipEntryText(zip, weird, nodeInflate), /unsupported compression method 12/);
});

// ===== Phase5 加固回归 =====

test('伪造超大声明 uncompressedSize 的 deflate 条目 → 分配前拒绝(炸弹防护)', async () => {
  const zip = buildZip([{ name: 'bomb.xml', text: '<x/>', method: 8 }]);
  const rec: ZipEntryRecord = readZipEntries(zip)[0];
  const lying: ZipEntryRecord = { ...rec, uncompressedSize: 5 * 1024 * 1024 * 1024 };
  await assert.rejects(
    () => readZipEntryText(zip, lying, nodeInflate),
    /declares oversized output/,
  );
});

test('stored 条目声明与实际不符 → 拒绝(截断数据不再静默当成功)', () => {
  const zip = buildZip([{ name: 's.txt', text: 'abcdef', method: 0 }]);
  const rec: ZipEntryRecord = readZipEntries(zip)[0];
  const lying: ZipEntryRecord = { ...rec, compressedSize: rec.compressedSize - 2 };
  assert.throws(() => extractZipEntryData(zip, lying), /stored entry size mismatch/);
});

test('CD 记录越界(count 超实际) → 抛错而非读出 undefined 字段', () => {
  const zip = buildZip([{ name: 'a', text: 'a', method: 0 }]);
  // 篡改 EOCD count 为 2(实际 1) → 第二轮 off 越界
  const mutated = new Uint8Array(zip);
  const eocd = mutated.length - 22;
  mutated[eocd + 10] = 2;
  mutated[eocd + 12] = 2;
  assert.throws(() => readZipEntries(mutated), /central directory/);
});
