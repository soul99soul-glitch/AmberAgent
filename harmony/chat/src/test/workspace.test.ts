// workspace 测试 — D-124 WorkspaceTools 六件 + PosixWorkspaceManager 钉住
// Android 锚点:WorkspaceManager.kt/WorkspacePaths.kt/WorkspaceTools.kt/ToolJson.kt
//   (行号见 workspace.ts 头注)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  PosixWorkspaceManager,
  normalizeWorkspacePath,
  createWorkspaceTools,
} from '../main/ets/chat/workspace.ts';
import type {
  WorkspaceFsPort,
  WorkspaceFsEntry,
} from '../main/ets/chat/workspace.ts';
import { AgentToolActivityStore } from '../main/ets/chat/tool_activity.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

// ===== 内存 WorkspaceFsPort =====

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
    const p: string = norm(abs);
    const content: Uint8Array | undefined = this.files.get(p);
    if (content === undefined) throw new Error(`NoSuchFile: ${p}`);
    return new TextDecoder().decode(content);
  }

  writeText(abs: string, content: string, append: boolean): void {
    this.writeBytes(abs, new TextEncoder().encode(content), append);
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

const makeManager = (fs: MemWsFs): PosixWorkspaceManager =>
  new PosixWorkspaceManager({ port: fs, rootAbs: ROOT });

const seed = (fs: MemWsFs, rel: string, content: string): void => {
  // 镜像根恒存在(Android WorkspaceManager.requireRoot 自动建 mirror 目录);
  //   顶层文件种子也要先建根,否则 writeText 无父目录
  fs.mkdirs(ROOT);
  const dir: string = rel.substring(0, rel.lastIndexOf('/'));
  if (dir.length > 0) fs.mkdirs(`${ROOT}/${dir}`);
  fs.writeText(`${ROOT}/${rel}`, content, false);
};

// ===== WorkspacePaths =====

test('normalize:blank/./\//workspace 别名 → .;前缀剥离;绝对路径/逃逸 → 抛', () => {
  assert.equal(normalizeWorkspacePath(''), '.');
  assert.equal(normalizeWorkspacePath('.'), '.');
  assert.equal(normalizeWorkspacePath('/'), '.');
  assert.equal(normalizeWorkspacePath('/workspace'), '.');
  assert.equal(normalizeWorkspacePath('workspace'), '.');
  assert.equal(normalizeWorkspacePath('/workspace/notes/a.md'), 'notes/a.md');
  assert.equal(normalizeWorkspacePath('workspace/notes'), 'notes');
  assert.equal(normalizeWorkspacePath('a/./b/'), 'a/b');
  assert.throws(() => normalizeWorkspacePath('/etc/passwd'), /Only \/workspace paths are allowed/);
  assert.throws(() => normalizeWorkspacePath('a/../b'), /Path traversal is not allowed/);
});

// ===== PosixWorkspaceManager =====

test('list:目录优先 + name 小写排序;缺目录/非目录 → 抛', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'notes/b.md', 'b');
  seed(fs, 'notes/A.md', 'a');
  seed(fs, 'notes/sub/c.md', 'c');
  const m: PosixWorkspaceManager = makeManager(fs);
  const entries = await m.list('notes');
  assert.deepEqual(entries.map((e): string => e.name), ['sub', 'A.md', 'b.md']);
  assert.equal(entries[0].directory, true);
  assert.equal(entries[0].sizeBytes, null);
  assert.equal(entries[1].path, 'notes/A.md');
  assert.equal(entries[1].sizeBytes, 1);
  assert.equal(entries[1].mimeType, 'text/markdown');
  await assert.rejects(m.list('ghost'), /Path not found: ghost/);
  await assert.rejects(m.list('notes/A.md'), /Not a directory: notes\/A\.md/);
});

test('readText:非文件/缺失 → 抛;writeText:建父目录 + append + 返回项', async () => {
  const fs: MemWsFs = new MemWsFs();
  const m: PosixWorkspaceManager = makeManager(fs);
  await assert.rejects(m.readText('a.md'), /Path not found: a\.md/);
  const e1 = await m.writeText('notes/a.md', 'hello');
  assert.equal(e1.path, 'notes/a.md');
  assert.equal(e1.sizeBytes, 5);
  assert.equal(e1.mimeType, 'text/markdown');
  const e2 = await m.writeText('notes/a.md', ' world', true);
  assert.equal(e2.sizeBytes, 11);
  assert.equal(await m.readText('notes/a.md'), 'hello world');
  await assert.rejects(m.readText('notes'), /Not a file: notes/);
  // requireFilePath:'.' → 抛
  await assert.rejects(m.writeText('.', 'x'), /A file path under \/workspace is required: \./);
});

test('editText:首替换/replaceAll 计数/未命中/空 old_text;$ 字面替换', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'a.md', 'foo bar foo');
  const m: PosixWorkspaceManager = makeManager(fs);
  const r1 = await m.editText('a.md', 'foo', 'baz', false);
  assert.deepEqual(r1, { path: 'a.md', replaceCount: 1 });
  assert.equal(await m.readText('a.md'), 'baz bar foo');
  const r2 = await m.editText('a.md', 'ba', 'X', true);
  assert.equal(r2.replaceCount, 2); // 'baz bar foo' 中 'ba' 共两处(index 0/4)
  assert.equal(await m.readText('a.md'), 'Xz Xr foo');
  await assert.rejects(m.editText('a.md', 'zzz', 'q', false), /Text not found in a\.md/);
  await assert.rejects(m.editText('a.md', '', 'q', false), /old_text must not be empty/);
  // replacement 中 $& 字面(JS replace 特殊序列陷阱)
  const r3 = await m.editText('a.md', 'foo', '$&$', false);
  assert.equal(r3.replaceCount, 1);
  assert.equal(await m.readText('a.md'), 'Xz Xr $&$');
});

test('move:同父 rename/跨父 copy+delete/目录递归/三错误逐字', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'notes/a.md', 'aaa');
  seed(fs, 'notes/sub/b.md', 'bbb');
  const m: PosixWorkspaceManager = makeManager(fs);
  // 同父 rename
  const r1 = await m.move('notes/a.md', 'notes/renamed.md');
  assert.deepEqual(r1, {
    path: 'notes/renamed.md', name: 'renamed.md', directory: false,
    sizeBytes: 3, mimeType: 'text/markdown',
  });
  assert.ok(!fs.exists(`${ROOT}/notes/a.md`));
  // 跨父目录移动(copy + delete)
  const r2 = await m.move('notes/sub', 'archive/sub');
  assert.equal(r2.directory, true);
  assert.equal(await m.readText('archive/sub/b.md'), 'bbb');
  assert.ok(!fs.exists(`${ROOT}/notes/sub`));
  await assert.rejects(m.move('notes/renamed.md', 'notes/renamed.md'),
    /Source and target are the same path: notes\/renamed\.md/);
  await assert.rejects(m.move('archive', 'archive/inner/x'),
    /Moving a path into itself is not allowed: archive -> archive\/inner\/x/);
  await assert.rejects(m.move('notes/renamed.md', 'archive/sub/b.md'),
    /Target path already exists: archive\/sub\/b\.md/);
  await assert.rejects(m.move('ghost.md', 'x.md'), /Path not found: ghost\.md/);
});

test('search:首命中行 + 行号 + 240 截尾 + maxResults + 空 query 抛', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'a.md', 'no hit\nSecond Hit here\ntail');
  seed(fs, 'sub/b.md', `prefix ${'x'.repeat(300)} needle`);
  const m: PosixWorkspaceManager = makeManager(fs);
  // ignoreCase=true(WorkspaceManager.kt:219):'HIT' 命中首行 'no hit'
  const hits = await m.search('HIT', '.', 50);
  assert.equal(hits.length, 1);
  assert.deepEqual(hits[0], { path: 'a.md', lineNumber: 1, preview: 'no hit' });
  const hits2 = await m.search('needle', '.', 50);
  assert.equal(hits2.length, 1);
  assert.equal(hits2[0].path, 'sub/b.md');
  assert.equal(hits2[0].preview.length, 240);
  // 单文件起点
  const hits3 = await m.search('tail', 'a.md', 50);
  assert.equal(hits3.length, 1);
  assert.equal(hits3[0].lineNumber, 3);
  await assert.rejects(m.search('  ', '.', 50), /query is required/);
  await assert.rejects(m.search('x', 'ghost', 50), /Path not found: ghost/);
});

// ===== createWorkspaceTools =====

const findTool = (tools: AgentTool[], name: string): AgentTool => {
  const hit: AgentTool | undefined = tools.find((t: AgentTool): boolean => t.name === name);
  assert.ok(hit !== undefined, `tool ${name} present`);
  return hit as AgentTool;
};

test('createWorkspaceTools:六件齐 + 审批标记(file_write/edit/move 需审批禁自动)', () => {
  const tools: AgentTool[] =
    createWorkspaceTools(makeManager(new MemWsFs()), new AgentToolActivityStore());
  assert.deepEqual(tools.map((t: AgentTool): string => t.name),
    ['file_list', 'file_read', 'file_write', 'file_edit', 'file_search', 'file_move']);
  ['file_write', 'file_edit', 'file_move'].forEach((n: string): void => {
    const t: AgentTool = findTool(tools, n);
    assert.equal(t.needsApproval, true, n);
    assert.equal(t.allowsAutoApproval, false, n);
  });
  ['file_list', 'file_read', 'file_search'].forEach((n: string): void => {
    assert.equal(findTool(tools, n).needsApproval, false, n);
  });
});

test('file_read:max_chars 默认/钳制/必填校验', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'a.md', 'hello world');
  const tools: AgentTool[] = createWorkspaceTools(makeManager(fs), new AgentToolActivityStore());
  const t: AgentTool = findTool(tools, 'file_read');
  await assert.rejects(t.execute({}), /path is required/);
  const parts: UIMessagePart[] = await t.execute({ path: 'a.md', max_chars: 5 });
  if (parts[0].type !== 'text') throw new Error('text part');
  assert.equal(JSON.parse(parts[0].text)['content'], 'hello');
});

test('file_write + file_edit + file_move:全链路 + activity 事件三段', async () => {
  const fs: MemWsFs = new MemWsFs();
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  const tools: AgentTool[] = createWorkspaceTools(makeManager(fs), store);
  const w: UIMessagePart[] = await findTool(tools, 'file_write')
    .execute({ path: 'notes/a.md', content: 'v1' });
  if (w[0].type !== 'text') throw new Error('text part');
  assert.equal(JSON.stringify(JSON.parse(w[0].text)), '{"path":"notes/a.md","size_bytes":2}');
  // 事件:startTool('SAF workspace','/workspace') + complete
  const activity = store.sandboxActivity;
  assert.equal(activity?.toolName, 'file_write');
  assert.equal(activity?.title, '写入文件');
  assert.equal(activity?.runtime, 'SAF workspace');
  assert.equal(activity?.workspace, '/workspace');
  assert.equal(activity?.status, 'succeeded');
  assert.equal(activity?.inputPreview, '{"path":"notes/a.md","append":false,"content_chars":2}');

  const e: UIMessagePart[] = await findTool(tools, 'file_edit')
    .execute({ path: 'notes/a.md', old_text: 'v1', new_text: 'v2' });
  if (e[0].type !== 'text') throw new Error('text part');
  assert.equal(JSON.stringify(JSON.parse(e[0].text)),
    '{"path":"notes/a.md","replace_count":1}');
  assert.equal(fs.readText(`${ROOT}/notes/a.md`), 'v2');

  const mv: UIMessagePart[] = await findTool(tools, 'file_move')
    .execute({ source_path: 'notes/a.md', target_path: 'b.md' });
  if (mv[0].type !== 'text') throw new Error('text part');
  assert.equal(JSON.stringify(JSON.parse(mv[0].text)), '{"path":"b.md","directory":false}');
});

test('file_search:payload + 失败路径 activity fail 事件', async () => {
  const fs: MemWsFs = new MemWsFs();
  seed(fs, 'a.md', 'needle here');
  const store: AgentToolActivityStore = new AgentToolActivityStore();
  const tools: AgentTool[] = createWorkspaceTools(makeManager(fs), store);
  const parts: UIMessagePart[] = await findTool(tools, 'file_search')
    .execute({ query: 'needle' });
  if (parts[0].type !== 'text') throw new Error('text part');
  assert.equal(JSON.stringify(JSON.parse(parts[0].text)),
    '{"results":[{"path":"a.md","line_number":1,"preview":"needle here"}]}');
  // 失败:query 缺失 → track 包装下 fail 事件 + 抛
  await assert.rejects(findTool(tools, 'file_search').execute({}), /query is required/);
  assert.equal(store.sandboxActivity?.status, 'failed');
  assert.equal(store.sandboxActivity?.toolName, 'file_search');
});
