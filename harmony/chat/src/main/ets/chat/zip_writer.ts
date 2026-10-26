// Shared ZIP bytes writer. Workspace tools and Novel ZIP/EPUB use this same implementation.

// raw deflate 压缩 Port(entry = zlib deflateInit2 负 windowBits)
export type DeflateRawPort = (data: Uint8Array) => Promise<Uint8Array>;

// ===== CRC32(域层纯实现;zip 写器用) =====

const CRC_TABLE: Uint32Array = ((): Uint32Array => {
  const table: Uint32Array = new Uint32Array(256);
  for (let n: number = 0; n < 256; n++) {
    let c: number = n;
    for (let k: number = 0; k < 8; k++) {
      c = (c & 1) !== 0 ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    }
    table[n] = c >>> 0;
  }
  return table;
})();

export const crc32 = (data: Uint8Array): number => {
  let crc: number = 0xffffffff;
  for (let i: number = 0; i < data.length; i++) {
    crc = CRC_TABLE[(crc ^ data[i]) & 0xff] ^ (crc >>> 8);
  }
  return (crc ^ 0xffffffff) >>> 0;
};

// ===== zip 写器(method 8;尺寸预计算,无 data descriptor — 布局偏差登记) =====

const utf8Encode = (text: string): Uint8Array => {
  // 手写 UTF-8 编码(域层无 TextEncoder 依赖,与 zip_archive utf8Decode 对称)
  const out: number[] = [];
  for (let i: number = 0; i < text.length; i++) {
    let cp: number = text.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length) {
      const lo: number = text.charCodeAt(i + 1);
      if (lo >= 0xdc00 && lo <= 0xdfff) {
        cp = 0x10000 + ((cp - 0xd800) << 10) + (lo - 0xdc00);
        i++;
      }
    }
    if (cp < 0x80) {
      out.push(cp);
    } else if (cp < 0x800) {
      out.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    } else if (cp < 0x10000) {
      out.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    } else {
      out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f),
        0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    }
  }
  return new Uint8Array(out);
};

export interface ZipWriteEntry {
  name: string;
  data: Uint8Array;
  compression?: 'stored' | 'deflate';
}

export const buildZipBytes = async (
  entries: ZipWriteEntry[], deflateRaw: DeflateRawPort, dosTime: number, dosDate: number,
): Promise<Uint8Array> => {
  const chunks: Uint8Array[] = [];
  const central: Uint8Array[] = [];
  let offset: number = 0;
  const u16le = (v: number): number[] => [v & 0xff, (v >> 8) & 0xff];
  const u32le = (v: number): number[] =>
    [v & 0xff, (v >> 8) & 0xff, (v >> 16) & 0xff, ((v >> 24) & 0xff)];

  for (const entry of entries) {
    const nameBytes: Uint8Array = utf8Encode(entry.name);
    const crc: number = crc32(entry.data);
    const method: number = entry.compression === 'stored' ? 0 : 8;
    const compressed: Uint8Array = method === 0 ? entry.data : await deflateRaw(entry.data);
    const local: number[] = [
      ...u32le(0x04034b50), ...u16le(20), ...u16le(0x0800), ...u16le(method),
      ...u16le(dosTime), ...u16le(dosDate), ...u32le(crc),
      ...u32le(compressed.length), ...u32le(entry.data.length),
      ...u16le(nameBytes.length), ...u16le(0),
    ];
    const localArr: Uint8Array = new Uint8Array(local);
    chunks.push(localArr, nameBytes, compressed);
    const cd: number[] = [
      ...u32le(0x02014b50), ...u16le(20), ...u16le(20), ...u16le(0x0800),
      ...u16le(method), ...u16le(dosTime), ...u16le(dosDate), ...u32le(crc),
      ...u32le(compressed.length), ...u32le(entry.data.length),
      ...u16le(nameBytes.length), ...u16le(0), ...u16le(0), ...u16le(0),
      ...u16le(0), ...u32le(0), ...u32le(offset),
    ];
    central.push(new Uint8Array(cd), nameBytes);
    offset += localArr.length + nameBytes.length + compressed.length;
  }
  let cdSize: number = 0;
  central.forEach((c: Uint8Array): void => { cdSize += c.length; });
  const eocd: number[] = [
    ...u32le(0x06054b50), ...u16le(0), ...u16le(0),
    ...u16le(entries.length), ...u16le(entries.length),
    ...u32le(cdSize), ...u32le(offset), ...u16le(0),
  ];
  const all: Uint8Array[] = [...chunks, ...central, new Uint8Array(eocd)];
  let total: number = 0;
  all.forEach((c: Uint8Array): void => { total += c.length; });
  const out: Uint8Array = new Uint8Array(total);
  let pos: number = 0;
  all.forEach((c: Uint8Array): void => {
    out.set(c, pos);
    pos += c.length;
  });
  return out;
};

// DOS 时间/日期(Date → zip 字段;工具调用侧注入当前时刻)
export const dosTimeDate = (d: Date): { time: number; date: number } => ({
  time: (d.getHours() << 11) | (d.getMinutes() << 5) | Math.floor(d.getSeconds() / 2),
  date: (((d.getFullYear() - 1980) << 9) | ((d.getMonth() + 1) << 5) | d.getDate()),
});
