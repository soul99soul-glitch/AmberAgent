// workspace_artifacts 测试 — D-125 WorkspaceArtifactTools 十一件钉住
// Android 锚点:WorkspaceArtifactTools.kt(行号见 workspace_artifacts.ts 头注)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  createWorkspaceArtifactTools, requireHttpUrl, fileNameFromUrl, safeFileName,
  safeBaseName, mimeFromName, gunzipSplit, safeArchiveTarget, archiveTypeOf,
  crc32, buildZipBytes, dosTimeDate, stripXml, parseXlsxText, artifactScaleDims,
  PDF_BLOCKED_MESSAGE, MAX_HTTP_BODY_BYTES,
} from '../main/ets/chat/workspace_artifacts.ts';
import type {
  ArtifactHttpRequest, ArtifactHttpResponse, ArtifactImageInfo,
  WorkspaceArtifactToolsDeps,
} from '../main/ets/chat/workspace_artifacts.ts';
import {
  PosixWorkspaceManager,
} from '../main/ets/chat/workspace.ts';
import type { WorkspaceFsPort, WorkspaceFsEntry } from '../main/ets/chat/workspace.ts';
import { readZipEntries, extractZipEntryData, readZipEntryBytes } from '../main/ets/chat/zip_archive.ts';
import { AgentToolActivityStore } from '../main/ets/chat/tool_activity.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type { XmlPullPort } from '../main/ets/chat/xml_pull.ts';

const te = new TextEncoder();
const td = new TextDecoder();

// ===== 内存 WorkspaceFsPort(与 workspace.test.ts 同形;二进制存储) =====

const norm = (p: string): string => {
  const parts: string[] = [];
  p.split('/').forEach((seg: string): void => {
    if (seg === '' || seg === '.') return;
    parts.push(seg);
  });
  return '/' + parts.join('/');
};

class MemWsFs implements WorkspaceFsPort {
  dirs: Set<string> = new Set<string>(['/']);
  files: Map<string, Uint8Array> = new Map<string, Uint8Array>();

  exists(abs: string): boolean {
    const p: string = norm(abs);
    return this.dirs.has(p) || this.files.has(p);
  }

  isDirectory(abs: string): boolean {
    return this.dirs.has(norm(abs));
  }

  isFile(abs: string): boolean {
    return this.files.has(norm(abs));
  }

  listNames(abs: string): WorkspaceFsEntry[] {
    const dir: string = norm(abs);
    const out: WorkspaceFsEntry[] = [];
    const seen: Set<string> = new Set<string>();
    const prefix: string = dir === '/' ? '/' : dir + '/';
    const consider = (p: string, isDir: boolean): void => {
      if (p === dir || !p.startsWith(prefix)) return;
      const rest: string = p.substring(prefix.length);
      if (rest.indexOf('/') >= 0) return;
      if (seen.has(rest)) return;
      seen.add(rest);
      out.push({
        name: rest,
        directory: isDir,
        sizeBytes: isDir ? null : (this.files.get(p)?.length ?? 0),
      });
    };
    Array.from(this.dirs).forEach((d: string): void => consider(d, true));
    Array.from(this.files.keys()).forEach((f: string): void => consider(f, false));
    return out;
  }

  readText(abs: string): string {
    return td.decode(this.readBytes(abs));
  }

  writeText(abs: string, content: string, append: boolean): void {
    this.writeBytes(abs, te.encode(content), append);
  }

  readBytes(abs: string): Uint8Array {
    const p: string = norm(abs);
    const content: Uint8Array | undefined = this.files.get(p);
    if (content === undefined) throw new Error(`NoSuchFile: ${p}`);
    return content;
  }

  writeBytes(abs: string, bytes: Uint8Array, append: boolean): void {
    const p: string = norm(abs);
    const parent: string = p.substring(0, p.lastIndexOf('/')) || '/';
    if (!this.dirs.has(parent)) throw new Error(`NoSuchDirectory: ${parent}`);
    if (append && this.files.has(p)) {
      const prev: Uint8Array = this.files.get(p) as Uint8Array;
      const merged: Uint8Array = new Uint8Array(prev.length + bytes.length);
      merged.set(prev, 0);
      merged.set(bytes, prev.length);
      this.files.set(p, merged);
    } else {
      this.files.set(p, new Uint8Array(bytes));
    }
  }

  fileSize(abs: string): number {
    return this.files.get(norm(abs))?.length ?? 0;
  }

  mkdirs(abs: string): void {
    const segs: string[] = norm(abs).split('/').filter((s: string): boolean => s.length > 0);
    let cur: string = '';
    segs.forEach((seg: string): void => {
      cur += '/' + seg;
      this.dirs.add(cur);
    });
  }

  rename(fromAbs: string, toAbs: string): void {
    const f: string = norm(fromAbs);
    const t: string = norm(toAbs);
    const prefix: string = f + '/';
    const moved: Array<[string, string]> = [];
    Array.from(this.files.keys()).forEach((k: string): void => {
      if (k === f || k.startsWith(prefix)) moved.push([t + k.substring(f.length), k]);
    });
    const movedDirs: Array<[string, string]> = [];
    Array.from(this.dirs).forEach((d: string): void => {
      if (d === f || d.startsWith(prefix)) movedDirs.push([t + d.substring(f.length), d]);
    });
    moved.forEach((pair: [string, string]): void => {
      const content: Uint8Array = this.files.get(pair[1]) as Uint8Array;
      this.files.delete(pair[1]);
      this.files.set(pair[0], content);
    });
    movedDirs.forEach((pair: [string, string]): void => {
      this.dirs.delete(pair[1]);
      this.dirs.add(pair[0]);
    });
  }

  copyFile(fromAbs: string, toAbs: string): void {
    const content: Uint8Array | undefined = this.files.get(norm(fromAbs));
    if (content === undefined) throw new Error(`NoSuchFile: ${fromAbs}`);
    const t: string = norm(toAbs);
    const parent: string = t.substring(0, t.lastIndexOf('/')) || '/';
    if (!this.dirs.has(parent)) throw new Error(`NoSuchDirectory: ${parent}`);
    this.files.set(t, content);
  }

  deleteRecursively(abs: string): boolean {
    const p: string = norm(abs);
    if (!this.exists(p)) return false;
    const prefix: string = p + '/';
    Array.from(this.files.keys()).forEach((f: string): void => {
      if (f === p || f.startsWith(prefix)) this.files.delete(f);
    });
    Array.from(this.dirs).forEach((d: string): void => {
      if (d !== '/' && (d === p || d.startsWith(prefix))) this.dirs.delete(d);
    });
    return true;
  }
}

const ROOT: string = '/files/amberagent/workspace-mirror';

const seed = (fs: MemWsFs, rel: string, content: string | Uint8Array): void => {
  fs.mkdirs(ROOT);
  const dir: string = rel.substring(0, rel.lastIndexOf('/'));
  if (dir.length > 0) fs.mkdirs(`${ROOT}/${dir}`);
  fs.writeBytes(`${ROOT}/${rel}`, typeof content === 'string' ? te.encode(content) : content, false);
};

// ===== 假端口 =====

const identityInflate = async (data: Uint8Array, _expectedSize: number): Promise<Uint8Array> =>
  new Uint8Array(data);

const identityDeflate = async (data: Uint8Array): Promise<Uint8Array> => new Uint8Array(data);

interface HttpCall extends ArtifactHttpRequest {
  // 记录用
}

class HttpSpy {
  calls: HttpCall[] = [];
  response: ArtifactHttpResponse = {
    status: 200, url: 'https://example.com/', headers: {}, body: te.encode('ok'),
  };

  port = async (req: ArtifactHttpRequest): Promise<ArtifactHttpResponse> => {
    this.calls.push({ ...req });
    return this.response;
  };
}

class ImageSpy {
  infoResult: ArtifactImageInfo = {
    width: 100, height: 50, mimeType: 'image/png', exifOrientation: 1,
  };
  infoThrows: boolean = false;
  convertArgs: Array<{
    targetWidth: number | null; targetHeight: number | null; format: string; quality: number;
  }> = [];
  convertResult: Uint8Array = te.encode('PNG-OUT');

  port = {
    info: async (_bytes: Uint8Array): Promise<ArtifactImageInfo> => {
      if (this.infoThrows) throw new Error('decode failed');
      return this.infoResult;
    },
    convert: async (
      _bytes: Uint8Array, targetWidth: number | null, targetHeight: number | null,
      format: string, quality: number,
    ): Promise<Uint8Array> => {
      this.convertArgs.push({ targetWidth, targetHeight, format, quality });
      return this.convertResult;
    },
  };
}

const makeDeps = (
  fs: MemWsFs, store: AgentToolActivityStore, http: HttpSpy, image: ImageSpy,
): WorkspaceArtifactToolsDeps => ({
  workspaceManager: new PosixWorkspaceManager({ port: fs, rootAbs: ROOT }),
  activityStore: store,
  http: http.port,
  image: image.port,
  inflateRaw: identityInflate,
  deflateRaw: identityDeflate,
  newXmlParser: (_xml: string): XmlPullPort => {
    throw new Error('xml parser not expected in this test');
  },
});

const findTool = (tools: AgentTool[], name: string): AgentTool => {
  const hit: AgentTool | undefined = tools.find((t: AgentTool): boolean => t.name === name);
  assert.ok(hit !== undefined, `tool ${name} present`);
  return hit as AgentTool;
};

const textOf = (parts: UIMessagePart[]): string => {
  if (parts[0].type !== 'text') throw new Error('text part');
  return parts[0].text;
};

// ===== 纯函数 =====

test('requireHttpUrl:http/https 通过;ftp/大写/无 scheme → 抛', () => {
  requireHttpUrl('http://a.com/x');
  requireHttpUrl('https://a.com');
  assert.throws(() => requireHttpUrl('ftp://a.com'), /Only http and https URLs are allowed/);
  assert.throws(() => requireHttpUrl('HTTP://a.com'), /Only http and https URLs are allowed/);
  assert.throws(() => requireHttpUrl('a.com/x'), /Only http and https URLs are allowed/);
});

test('fileNameFromUrl:路径末段/query 剥离/空 → download.bin', () => {
  assert.equal(fileNameFromUrl('https://h.com/a/b.png?x=1'), 'b.png');
  assert.equal(fileNameFromUrl('https://h.com/a/'), 'download.bin');
  assert.equal(fileNameFromUrl('https://h.com'), 'download.bin');
  assert.equal(safeFileName('a b/c?.png'), 'a_b_c_.png');
  // '***' → '___'(非 blank,ifBlank 不触发;*** → ___ 逐字)
  assert.equal(safeFileName('***'), '___');
  assert.equal(safeFileName(''), 'file.bin');
  // substringBeforeLast('.') 仅剥最后扩展名;safeFileName 保留 '.'
  assert.equal(safeBaseName('dir/report.final.md'), 'report.final');
  assert.equal(safeBaseName('noext'), 'noext');
});

test('mimeFromName:表内命中/大小写/表外 octet-stream', () => {
  assert.equal(mimeFromName('a.PNG'), 'image/png');
  assert.equal(mimeFromName('a.docx'),
    'application/vnd.openxmlformats-officedocument.wordprocessingml.document');
  assert.equal(mimeFromName('a.xyz'), 'application/octet-stream');
  assert.equal(mimeFromName('noext'), 'application/octet-stream');
});

test('gunzipSplit:flag 感知头剥离 + ISIZE;坏魔数 → 抛', () => {
  const payload: Uint8Array = te.encode('TARDATA');
  // flags = FNAME(8) + FHCRC(2)
  const head: number[] = [0x1f, 0x8b, 8, 10, 0, 0, 0, 0, 0, 0];
  const nameField: number[] = [...te.encode('n.tar'), 0];
  const trailer: number[] = [1, 2, 3, 4,
    payload.length & 0xff, (payload.length >> 8) & 0xff, 0, 0];
  const gz: Uint8Array = new Uint8Array([...head, ...nameField, 9, 9, ...payload, ...trailer]);
  const split = gunzipSplit(gz);
  assert.equal(td.decode(split.payload), 'TARDATA');
  assert.equal(split.isize, payload.length);
  assert.throws(() => gunzipSplit(te.encode('not-gzip-at-all-xxxx')), /Not in GZIP format/);
});

test('safeArchiveTarget:正常拼接;Zip Slip/绝对/空 → 抛', () => {
  assert.equal(safeArchiveTarget('extracted/pkg', 'a/b.txt'), 'extracted/pkg/a/b.txt');
  assert.throws(() => safeArchiveTarget('d', '../evil'), /Unsafe archive entry: \.\.\/evil/);
  assert.throws(() => safeArchiveTarget('d', '/abs'), /Unsafe archive entry: \/abs/);
  assert.throws(() => safeArchiveTarget('d', 'x/../../y'), /Unsafe archive entry/);
  assert.equal(archiveTypeOf('a.ZIP'), 'zip');
  assert.equal(archiveTypeOf('a.tar.gz'), 'targz');
  assert.equal(archiveTypeOf('a.tgz'), 'targz');
  assert.equal(archiveTypeOf('a.tar'), 'tar');
  assert.throws(() => archiveTypeOf('a.rar'), /Unsupported archive type: a\.rar/);
});

test('crc32:标准向量;dosTimeDate 位布局', () => {
  assert.equal(crc32(te.encode('123456789')), 0xCBF43926);
  const d: Date = new Date(2026, 6, 28, 13, 40, 20);
  const td2 = dosTimeDate(d);
  assert.equal(td2.time, (13 << 11) | (40 << 5) | 10);
  assert.equal(td2.date, ((2026 - 1980) << 9) | (7 << 5) | 28);
});

// ===== zip 写器回环 =====

test('buildZipBytes:method 8 + UTF-8 名 + readZipEntries 回读', async () => {
  const entries = [
    { name: 'a.txt', data: te.encode('hello') },
    { name: '子/笔记.md', data: te.encode('中文内容') },
  ];
  const zip: Uint8Array = await buildZipBytes(entries, identityDeflate, 0, 0);
  const recs = readZipEntries(zip);
  assert.equal(recs.length, 2);
  assert.equal(recs[0].name, 'a.txt');
  assert.equal(recs[0].method, 8);
  assert.equal(recs[0].uncompressedSize, 5);
  assert.equal(recs[1].name, '子/笔记.md');
  // identity deflate → 数据段即原文
  const data0: Uint8Array = extractZipEntryData(zip, recs[0]);
  assert.equal(td.decode(data0), 'hello');
  const data1: Uint8Array = extractZipEntryData(zip, recs[1]);
  assert.equal(td.decode(data1), '中文内容');
});

test('buildZipBytes supports EPUB stored mimetype without forking the ZIP writer', async () => {
  const zip: Uint8Array = await buildZipBytes([
    { name: 'mimetype', data: te.encode('application/epub+zip'), compression: 'stored' },
    { name: 'OEBPS/nav.xhtml', data: te.encode('<nav/>'), compression: 'deflate' },
  ], identityDeflate, 0, 0);
  const recs = readZipEntries(zip);
  assert.equal(recs[0].method, 0);
  assert.equal(recs[1].method, 8);
  assert.equal(td.decode(await readZipEntryBytes(zip, recs[0], identityInflate)),
    'application/epub+zip');
  assert.equal(td.decode(await readZipEntryBytes(zip, recs[1], identityInflate)), '<nav/>');
});

// ===== xlsx =====

test('stripXml + parseXlsxText:sharedStrings/sheet 过滤 + ## 头', async () => {
  // 标签 → ' ' 替换:' x &<b> y'(trim 后)
  assert.equal(stripXml('<a>x</a>&amp;&lt;b&gt;  y'), 'x &<b> y');
  const zip: Uint8Array = await buildZipBytes([
    { name: 'xl/sharedStrings.xml', data: te.encode('<sst><si><t>hello</t></si></sst>') },
    { name: 'xl/worksheets/sheet1.xml', data: te.encode('<sheet><v>42</v></sheet>') },
    { name: 'xl/styles.xml', data: te.encode('<x/>') },
  ], identityDeflate, 0, 0);
  // identity deflate 的 method 8 条目经 identityInflate 回读
  const text: string = await parseXlsxText(zip, identityInflate);
  assert.equal(text,
    '## xl/sharedStrings.xml\nhello\n## xl/worksheets/sheet1.xml\n42\n');
});

// ===== 缩放 =====

test('artifactScaleDims:无限制/等比取小/不放大', () => {
  assert.deepEqual(artifactScaleDims(100, 50, null, null), { width: 100, height: 50, scaled: false });
  assert.deepEqual(artifactScaleDims(100, 50, 50, null), { width: 50, height: 25, scaled: true });
  assert.deepEqual(artifactScaleDims(100, 50, 200, 200), { width: 100, height: 50, scaled: false });
  assert.deepEqual(artifactScaleDims(100, 50, 0, 20), { width: 40, height: 20, scaled: true });
});

// ===== 工具表 =====

test('createWorkspaceArtifactTools:十一件齐 + 审批标记', () => {
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(new MemWsFs(), new AgentToolActivityStore(), new HttpSpy(), new ImageSpy()));
  assert.deepEqual(tools.map((t: AgentTool): string => t.name), [
    'http_request', 'download_file', 'archive_list', 'archive_extract', 'archive_create',
    'pdf_read', 'pdf_render_page', 'office_read', 'image_info', 'image_convert', 'ocr_image',
  ]);
  ['download_file', 'archive_extract', 'archive_create'].forEach((n: string): void => {
    const t: AgentTool = findTool(tools, n);
    assert.equal(t.needsApproval, true, n);
    assert.equal(t.allowsAutoApproval, false, n);
  });
  // :354 — image_convert 仅 needsApproval(allowsAutoApproval 默认)
  assert.equal(findTool(tools, 'image_convert').needsApproval, true);
  assert.equal(findTool(tools, 'image_convert').allowsAutoApproval, true);
});

// ===== http_request =====

test('http_request:payload 五键/headers trim/method+url 门/UA 之外头透传', async () => {
  const http: HttpSpy = new HttpSpy();
  http.response = {
    status: 201, url: 'https://api.x.com/v1',
    headers: { 'content-type': 'application/json' }, body: te.encode('{"a":1}'),
  };
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(new MemWsFs(), new AgentToolActivityStore(), http, new ImageSpy()));
  const t: AgentTool = findTool(tools, 'http_request');
  const parts: UIMessagePart[] = await t.execute({
    method: 'post', url: 'https://api.x.com/v1',
    headers: { 'X-Token': 'abc', 'X-Num': 42 }, body: 'q', timeout_ms: 5000,
  });
  const payload = JSON.parse(textOf(parts));
  assert.equal(payload['status_code'], 201);
  assert.equal(payload['url'], 'https://api.x.com/v1');
  assert.equal(payload['headers']['content-type'], 'application/json');
  assert.equal(payload['body'], '{"a":1}');
  assert.equal(payload['truncated'], false);
  assert.equal(http.calls[0].method, 'POST');
  assert.equal(http.calls[0].headers['X-Token'], 'abc');
  assert.equal(http.calls[0].headers['X-Num'], '42');
  assert.equal(http.calls[0].body, 'q');
  assert.equal(http.calls[0].timeoutMs, 5000);
  await assert.rejects(t.execute({ method: 'OPTIONS', url: 'https://a.com' }),
    /Unsupported HTTP method: OPTIONS/);
  await assert.rejects(t.execute({ url: 'file:///etc' }), /Only http and https URLs are allowed/);
});

test('http_request:512KB 截断标志(>= MAX)', async () => {
  const http: HttpSpy = new HttpSpy();
  http.response = {
    status: 200, url: 'https://a.com', headers: {},
    body: new Uint8Array(MAX_HTTP_BODY_BYTES + 10).fill(0x61),
  };
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(new MemWsFs(), new AgentToolActivityStore(), http, new ImageSpy()));
  const payload = JSON.parse(textOf(await findTool(tools, 'http_request')
    .execute({ url: 'https://a.com' })));
  assert.equal(payload['truncated'], true);
  assert.equal(payload['body'].length, MAX_HTTP_BODY_BYTES);
});

// ===== download_file =====

test('download_file:默认落点/mime 截取/2xx 门/审批标记', async () => {
  const fs: MemWsFs = new MemWsFs();
  const http: HttpSpy = new HttpSpy();
  http.response = {
    status: 200, url: 'https://h.com/a/report.pdf?dl=1',
    headers: { 'content-type': 'application/pdf; charset=binary' },
    body: te.encode('PDFBYTES'),
  };
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(fs, new AgentToolActivityStore(), http, new ImageSpy()));
  const t: AgentTool = findTool(tools, 'download_file');
  const payload = JSON.parse(textOf(await t.execute({ url: 'https://h.com/a/report.pdf?dl=1' })));
  assert.deepEqual(payload, {
    status: 'saved', path: 'downloads/report.pdf',
    size_bytes: 8, mime_type: 'application/pdf',
  });
  assert.equal(fs.readText(`${ROOT}/downloads/report.pdf`), 'PDFBYTES');
  assert.equal(http.calls[0].timeoutMs, 30000);
  // 非 2xx
  http.response = { status: 404, url: 'https://h.com/x', headers: {}, body: te.encode('') };
  await assert.rejects(t.execute({ url: 'https://h.com/x' }), /Download failed with HTTP 404/);
  // 自定义落点
  http.response = {
    status: 200, url: 'https://h.com/x.bin', headers: {}, body: te.encode('zz'),
  };
  const p2 = JSON.parse(textOf(await t.execute({ url: 'https://h.com/x.bin', workspace_path: 'data/x.bin' })));
  assert.equal(p2['path'], 'data/x.bin');
  assert.equal(p2['mime_type'], 'application/octet-stream');
});

// ===== archive_list / extract / create =====

test('archive_list:zip 条目 + limit;tar 头解析', async () => {
  const fs: MemWsFs = new MemWsFs();
  const zip: Uint8Array = await buildZipBytes([
    { name: 'pkg/', data: new Uint8Array(0) },
    { name: 'pkg/a.txt', data: te.encode('aaa') },
    { name: 'pkg/b.txt', data: te.encode('bb') },
  ], identityDeflate, 0, 0);
  seed(fs, 'pack.zip', zip);
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(fs, new AgentToolActivityStore(), new HttpSpy(), new ImageSpy()));
  const t: AgentTool = findTool(tools, 'archive_list');
  const payload = JSON.parse(textOf(await t.execute({ path: 'pack.zip' })));
  assert.equal(payload['path'], 'pack.zip');
  assert.deepEqual(payload['entries'], [
    { path: 'pkg/', directory: true, size_bytes: 0 },
    { path: 'pkg/a.txt', directory: false, size_bytes: 3 },
    { path: 'pkg/b.txt', directory: false, size_bytes: 2 },
  ]);
  const limited = JSON.parse(textOf(await t.execute({ path: 'pack.zip', limit: 1 })));
  assert.equal(limited['entries'].length, 1);
  // tar:两文件 + 零块结束
  const tarParts: number[] = [];
  const addTarFile = (name: string, data: Uint8Array): void => {
    const head: number[] = new Array<number>(512).fill(0);
    const nb: Uint8Array = te.encode(name);
    nb.forEach((b: number, i: number): void => { head[i] = b; });
    const sizeOct: string = data.length.toString(8);
    for (let i: number = 0; i < sizeOct.length; i++) head[124 + i] = sizeOct.charCodeAt(i);
    head[156] = 0x30;
    tarParts.push(...head, ...data);
    const pad: number = (512 - (data.length % 512)) % 512;
    for (let i: number = 0; i < pad; i++) tarParts.push(0);
  };
  addTarFile('x.txt', te.encode('12345'));
  addTarFile('y.txt', te.encode('ab'));
  const tar: Uint8Array = new Uint8Array([...tarParts, ...new Array<number>(1024).fill(0)]);
  seed(fs, 'pack.tar', tar);
  const tarPayload = JSON.parse(textOf(await t.execute({ path: 'pack.tar' })));
  assert.deepEqual(tarPayload['entries'], [
    { path: 'x.txt', directory: false, size_bytes: 5 },
    { path: 'y.txt', directory: false, size_bytes: 2 },
  ]);
});

test('archive_extract:zip 落盘 + 统计;overwrite 门;Zip Slip 阻断', async () => {
  const fs: MemWsFs = new MemWsFs();
  const zip: Uint8Array = await buildZipBytes([
    { name: 'pkg/', data: new Uint8Array(0) },
    { name: 'pkg/a.txt', data: te.encode('aaa') },
    { name: 'pkg/b.md', data: te.encode('bb') },
  ], identityDeflate, 0, 0);
  seed(fs, 'pack.zip', zip);
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(fs, new AgentToolActivityStore(), new HttpSpy(), new ImageSpy()));
  const t: AgentTool = findTool(tools, 'archive_extract');
  const payload = JSON.parse(textOf(await t.execute({ path: 'pack.zip' })));
  assert.deepEqual(payload, {
    path: 'pack.zip', destination_path: 'extracted/pack',
    files_written: 2, directories_seen: 1, bytes_written: 5,
  });
  assert.equal(fs.readText(`${ROOT}/extracted/pack/pkg/a.txt`), 'aaa');
  // 重放 → overwrite=false → 抛
  await assert.rejects(t.execute({ path: 'pack.zip' }),
    /Target already exists: extracted\/pack\/pkg\/a\.txt/);
  // overwrite=true → 通过
  const again = JSON.parse(textOf(await t.execute({ path: 'pack.zip', overwrite: true })));
  assert.equal(again['files_written'], 2);
  // Zip Slip
  const evil: Uint8Array = await buildZipBytes([
    { name: '../evil.txt', data: te.encode('x') },
  ], identityDeflate, 0, 0);
  seed(fs, 'evil.zip', evil);
  await assert.rejects(t.execute({ path: 'evil.zip' }), /Unsafe archive entry: \.\.\/evil\.txt/);
});

test('archive_create:format/空源门 + 目录直子打包回读', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'notes/a.md', 'A');
  seed(fs, 'notes/b.md', 'BB');
  seed(fs, 'notes/sub/c.md', 'CCC'); // 直子目录 → 不进包(Android 不递归)
  seed(fs, 'single.txt', 'S');
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(fs, new AgentToolActivityStore(), new HttpSpy(), new ImageSpy()));
  const t: AgentTool = findTool(tools, 'archive_create');
  await assert.rejects(
    t.execute({ source_paths: ['notes'], destination_path: 'x.zip', format: 'tar' }),
    /archive_create stage1 supports format=zip only/);
  await assert.rejects(
    t.execute({ source_paths: [], destination_path: 'x.zip' }),
    /source_paths must not be empty/);
  const payload = JSON.parse(textOf(await t.execute({
    source_paths: ['notes', 'single.txt'], destination_path: 'out/pack.zip',
  })));
  assert.equal(payload['path'], 'out/pack.zip');
  assert.equal(payload['format'], 'zip');
  const zip: Uint8Array = fs.readBytes(`${ROOT}/out/pack.zip`);
  assert.equal(zip.length, payload['size_bytes']);
  const recs = readZipEntries(zip);
  assert.deepEqual(recs.map((r): string => r.name), ['notes/a.md', 'notes/b.md', 'single.txt']);
  assert.equal(td.decode(extractZipEntryData(zip, recs[1])), 'BB');
});

// ===== office_read =====

test('office_read:xlsx 文本 + 截断;不支持扩展 → 抛', async () => {
  const fs: MemWsFs = new MemWsFs();
  const xlsx: Uint8Array = await buildZipBytes([
    { name: 'xl/sharedStrings.xml', data: te.encode('<t>Quarterly Report</t>') },
  ], identityDeflate, 0, 0);
  seed(fs, 'data/q.xlsx', xlsx);
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(fs, new AgentToolActivityStore(), new HttpSpy(), new ImageSpy()));
  const t: AgentTool = findTool(tools, 'office_read');
  const payload = JSON.parse(textOf(await t.execute({ path: 'data/q.xlsx' })));
  assert.equal(payload['path'], 'data/q.xlsx');
  assert.equal(payload['text'], '## xl/sharedStrings.xml\nQuarterly Report\n');
  assert.equal(payload['truncated'], false);
  const cut = JSON.parse(textOf(await t.execute({ path: 'data/q.xlsx', max_chars: 10 })));
  assert.equal(cut['text'], '## xl/shar');
  assert.equal(cut['truncated'], true);
  assert.equal(typeof cut['text_chars'], 'number');
  seed(fs, 'data/q.doc', 'legacy');
  await assert.rejects(t.execute({ path: 'data/q.doc' }),
    /Unsupported Office extension: data\/q\.doc/);
});

// ===== image_info / image_convert / ocr =====

test('image_info:payload 六键 + 64MB cap 文案', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'img/p.png', te.encode('PNGBYTES'));
  const image: ImageSpy = new ImageSpy();
  image.infoResult = { width: 640, height: 480, mimeType: 'image/png', exifOrientation: 6 };
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(fs, new AgentToolActivityStore(), new HttpSpy(), image));
  const payload = JSON.parse(textOf(await findTool(tools, 'image_info')
    .execute({ path: 'img/p.png' })));
  assert.deepEqual(payload, {
    path: 'img/p.png', width: 640, height: 480, mime_type: 'image/png',
    size_bytes: 8, exif_orientation: 6,
  });
});

test('image_convert:缩放透传/格式默认/质量钳制/解码失败文案', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'img/p.png', te.encode('PNGBYTES'));
  const image: ImageSpy = new ImageSpy();
  image.infoResult = { width: 100, height: 50, mimeType: 'image/png', exifOrientation: 1 };
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(fs, new AgentToolActivityStore(), new HttpSpy(), image));
  const t: AgentTool = findTool(tools, 'image_convert');
  const payload = JSON.parse(textOf(await t.execute({
    path: 'img/p.png', destination_path: 'out/p.webp', max_width: 50, quality: 200,
  })));
  assert.deepEqual(payload, { path: 'out/p.webp', mime_type: 'image/webp', size_bytes: 7 });
  assert.deepEqual(image.convertArgs[0], {
    targetWidth: 50, targetHeight: 25, format: 'webp', quality: 100,
  });
  assert.equal(fs.readText(`${ROOT}/out/p.webp`), 'PNG-OUT');
  // 不缩放 → null 尺寸;format 显式优先
  await t.execute({ path: 'img/p.png', destination_path: 'out/q.png', format: 'jpg' });
  assert.deepEqual(image.convertArgs[1], {
    targetWidth: null, targetHeight: null, format: 'jpg', quality: 90,
  });
  // 解码失败
  image.infoThrows = true;
  await assert.rejects(t.execute({ path: 'img/p.png', destination_path: 'out/z.png' }),
    /Unable to decode image: img\/p\.png/);
});

test('ocr_image:stage1 桩 payload 逐字', async () => {
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(new MemWsFs(), new AgentToolActivityStore(), new HttpSpy(), new ImageSpy()));
  const payload = JSON.parse(textOf(await findTool(tools, 'ocr_image')
    .execute({ path: 'img/p.png' })));
  assert.deepEqual(payload, {
    path: 'img/p.png', status: 'unavailable', runtime: 'stage1-no-local-ocr',
    message: 'Local OCR is not bundled yet. Use image_info plus a configured ' +
      'remote multimodal/VLM model for OCR in this build.',
  });
});

// ===== pdf 阻断 + track =====

test('pdf_read/pdf_render_page:平台阻断显式抛 + activity fail', async () => {
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(new MemWsFs(), store, new HttpSpy(), new ImageSpy()));
  await assert.rejects(findTool(tools, 'pdf_read').execute({ path: 'a.pdf' }),
    (e: Error): boolean => e.message === PDF_BLOCKED_MESSAGE);
  assert.equal(store.sandboxActivity?.status, 'failed');
  assert.equal(store.sandboxActivity?.runtime, 'MuPDF');
  await assert.rejects(findTool(tools, 'pdf_render_page').execute({ path: 'a.pdf' }),
    (e: Error): boolean => e.message === PDF_BLOCKED_MESSAGE);
  assert.equal(store.sandboxActivity?.runtime, 'PdfRenderer');
});

test('track:inputPreview 1200 截断 + runtime 标签 + complete 事件', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'img/p.png', te.encode('PNGBYTES'));
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  const tools: AgentTool[] = createWorkspaceArtifactTools(
    makeDeps(fs, store, new HttpSpy(), new ImageSpy()));
  await findTool(tools, 'image_info').execute({
    path: 'img/p.png', pad: 'x'.repeat(1500),
  });
  const activity = store.sandboxActivity;
  assert.equal(activity?.toolName, 'image_info');
  assert.equal(activity?.title, '读取图片信息');
  assert.equal(activity?.runtime, 'Workspace artifact');
  assert.equal(activity?.workspace, '/workspace');
  assert.equal(activity?.status, 'succeeded');
  // 工具侧 take(1200) 后 store 再 take(MAX_INPUT_PREVIEW_CHARS=800)
  //   (AgentToolActivityStore.kt:55/:152 忠实)
  assert.equal(activity?.inputPreview?.length, 800);
});
