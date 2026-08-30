// zip_archive — ZIP 读取(EOCD + 中央目录 + 条目提取;D-113)
// Android 基准:java.util.zip.ZipFile(PptxParser.kt:19-49)
//   - entries().toList() = **中央目录顺序**(枚举序忠实;PptxParser 排序键恒 0
//     quirk 下决定幻灯片顺序,见 pptx_parser.ts)
//   - getEntry(name)/getInputStream(entry) → 按名查找 + 单条目解压
// 端口化:method 8(deflate)解压 = 平台能力 → InflateRawPort 注入
//   (entry = @ohos.zlib inflateInit2 负 windowBits raw deflate)
// 纯二进制解析:EOCD 尾部 64KB 扫描 → CD 顺序遍历 → local header 定位数据段

export interface ZipEntryRecord {
  name: string;
  method: number; // 0=stored 8=deflate
  compressedSize: number;
  uncompressedSize: number;
  localHeaderOffset: number;
}

// method 8 raw deflate 解压 Port(平台注入)
export type InflateRawPort = (data: Uint8Array, expectedSize: number) => Promise<Uint8Array>;

// 单条目解压输出上限:中央目录声明的 uncompressedSize 是不可信输入,
// 直接按其分配输出缓冲可被 GB 级声明击穿(压缩炸弹)
export const MAX_ZIP_ENTRY_OUTPUT_BYTES: number = 64 * 1024 * 1024;

const EOCD_SIG: number = 0x06054b50;
const CD_SIG: number = 0x02014b50;
const LOCAL_SIG: number = 0x04034b50;

const u16 = (b: Uint8Array, off: number): number => b[off] | (b[off + 1] << 8);
const u32 = (b: Uint8Array, off: number): number =>
  (b[off] | (b[off + 1] << 8) | (b[off + 2] << 16) | (b[off + 3] << 24)) >>> 0;

// D-123:导出供 skills 域复用(Kotlin decodeToString() UTF-8 同语义)
export const utf8Decode = (bytes: Uint8Array): string => {
  // 解码器经 TextDecoder(ArkTS util 同语义);域层纯函数 → 手写 UTF-8 解码
  let out: string = '';
  let i: number = 0;
  while (i < bytes.length) {
    const c: number = bytes[i];
    if (c < 0x80) {
      out += String.fromCharCode(c);
      i += 1;
    } else if ((c & 0xe0) === 0xc0) {
      out += String.fromCharCode(((c & 0x1f) << 6) | (bytes[i + 1] & 0x3f));
      i += 2;
    } else if ((c & 0xf0) === 0xe0) {
      out += String.fromCharCode(
        ((c & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f));
      i += 3;
    } else {
      // 4 字节 → surrogate pair
      const cp: number =
        ((c & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) |
        ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f);
      out += String.fromCharCode(0xd800 + ((cp - 0x10000) >> 10), 0xdc00 + ((cp - 0x10000) & 0x3ff));
      i += 4;
    }
  }
  return out;
};

// ZipFile 打开:EOCD 定位(注释 ≤65535;尾部 22+65536 窗口反向扫)
export const readZipEntries = (bytes: Uint8Array): ZipEntryRecord[] => {
  const window: number = Math.max(0, bytes.length - (22 + 65536));
  let eocd: number = -1;
  for (let i: number = bytes.length - 22; i >= window; i--) {
    if (u32(bytes, i) === EOCD_SIG) {
      eocd = i;
      break;
    }
  }
  if (eocd < 0) throw new Error('EOCD not found (not a zip file)');
  const count: number = u16(bytes, eocd + 10);
  let off: number = u32(bytes, eocd + 16);
  const entries: ZipEntryRecord[] = [];
  for (let i: number = 0; i < count; i++) {
    // 边界校验:伪造 count/name/extra/comment 可把读取游标推出文件尾
    if (off < 0 || off + 46 > bytes.length) {
      throw new Error('central directory corrupted');
    }
    if (u32(bytes, off) !== CD_SIG) throw new Error('central directory corrupted');
    const nameLen: number = u16(bytes, off + 28);
    const extraLen: number = u16(bytes, off + 30);
    const commentLen: number = u16(bytes, off + 32);
    if (off + 46 + nameLen + extraLen + commentLen > bytes.length) {
      throw new Error('central directory record exceeds archive bounds');
    }
    const rec: ZipEntryRecord = {
      name: utf8Decode(bytes.subarray(off + 46, off + 46 + nameLen)),
      method: u16(bytes, off + 10),
      compressedSize: u32(bytes, off + 20),
      uncompressedSize: u32(bytes, off + 24),
      localHeaderOffset: u32(bytes, off + 42),
    };
    entries.push(rec);
    off += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
};

// getInputStream(entry):local header → 数据段切片(压缩态原样)
export const extractZipEntryData = (bytes: Uint8Array, rec: ZipEntryRecord): Uint8Array => {
  const off: number = rec.localHeaderOffset;
  if (off < 0 || off + 30 > bytes.length || u32(bytes, off) !== LOCAL_SIG) {
    throw new Error(`local header missing for ${rec.name}`);
  }
  const nameLen: number = u16(bytes, off + 26);
  const extraLen: number = u16(bytes, off + 28);
  const dataOff: number = off + 30 + nameLen + extraLen;
  if (dataOff > bytes.length || dataOff + rec.compressedSize > bytes.length) {
    // subarray 超界只会静默截断,损坏/截断归档必须显式失败
    throw new Error(`entry data out of bounds for ${rec.name}`);
  }
  if (rec.method === 0 && rec.compressedSize !== rec.uncompressedSize) {
    throw new Error(`stored entry size mismatch for ${rec.name}`);
  }
  return bytes.subarray(dataOff, dataOff + rec.compressedSize);
};

// 条目文本读取:stored 直取 / deflate 经 Port / 其余方法抛错(非静默)
export const readZipEntryBytes = async (
  bytes: Uint8Array, rec: ZipEntryRecord, inflateRaw: InflateRawPort,
): Promise<Uint8Array> => {
  const raw: Uint8Array = extractZipEntryData(bytes, rec);
  if (rec.method === 0) return new Uint8Array(raw);
  if (rec.method === 8) {
    if (rec.uncompressedSize > MAX_ZIP_ENTRY_OUTPUT_BYTES) {
      throw new Error(`zip entry '${rec.name}' declares oversized output`);
    }
    const plain: Uint8Array = await inflateRaw(raw, rec.uncompressedSize);
    if (plain.length !== rec.uncompressedSize) {
      throw new Error(`zip entry '${rec.name}' inflate size mismatch`);
    }
    return plain;
  }
  throw new Error(`unsupported compression method ${rec.method} for ${rec.name}`);
};

export const readZipEntryText = async (
  bytes: Uint8Array, rec: ZipEntryRecord, inflateRaw: InflateRawPort,
): Promise<string> => utf8Decode(await readZipEntryBytes(bytes, rec, inflateRaw));
