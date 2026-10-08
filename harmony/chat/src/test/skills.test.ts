// skills 测试 — D-123 skills 子系统域层钉住
// Android 锚点:SkillManager.kt/SkillPaths.kt/SkillsTools.kt(行号见 skills.ts 头注)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { inflateRawSync } from 'node:zlib';
import {
  SkillManager,
  skillParseFrontmatter,
  skillEnsureDescription,
  skillResolveDescription,
  skillExtractBody,
  resolveSkillDirPath,
  resolveSkillFilePath,
  canonicalSkillFileName,
  createSkillTools,
  collectSkillFilesFromDirectory,
  collectWorkspaceSkillFiles,
  unzipSkillFiles,
} from '../main/ets/chat/skills.ts';
import type {
  SkillFilePort,
  SkillDirEntry,
  SkillWorkspacePort,
  SkillWorkspaceEntry,
  SkillMetadata,
  BuiltinSkillAssetPort,
} from '../main/ets/chat/skills.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';
import type {
  CreateSkillToolsDeps,
} from '../main/ets/chat/skills.ts';

const te = new TextEncoder();

// ===== 内存 SkillFilePort(目录树:Set dirs + Map files) =====

const norm = (p: string): string => {
  const parts: string[] = [];
  p.split('/').forEach((seg: string): void => {
    if (seg === '' || seg === '.') return;
    if (seg === '..') {
      parts.pop();
      return;
    }
    parts.push(seg);
  });
  return '/' + parts.join('/');
};

class MemFs implements SkillFilePort {
  dirs: Set<string> = new Set<string>(['/']);
  files: Map<string, string> = new Map<string, string>();

  listDir(path: string): SkillDirEntry[] {
    const dir: string = norm(path);
    if (!this.dirs.has(dir)) return [];
    const out: SkillDirEntry[] = [];
    const seen: Set<string> = new Set<string>();
    const collect = (p: string, isDir: boolean): void => {
      if (!p.startsWith(dir === '/' ? '/' : dir + '/')) return;
      const rest: string = p.substring(dir === '/' ? 1 : dir.length + 1);
      const seg: string = rest.split('/')[0];
      if (seg.length === 0 || seen.has(seg)) return;
      seen.add(seg);
      const child: string = (dir === '/' ? '' : dir) + '/' + seg;
      out.push({ name: seg, isDirectory: isDir || this.dirs.has(child) });
    };
    this.dirs.forEach((d: string): void => {
      if (d !== dir) collect(d, true);
    });
    this.files.forEach((_v: string, f: string): void => collect(f, false));
    out.sort((a: SkillDirEntry, b: SkillDirEntry): number => a.name < b.name ? -1 : 1);
    return out;
  }

  exists(path: string): boolean {
    const p: string = norm(path);
    return this.dirs.has(p) || this.files.has(p);
  }

  isDirectory(path: string): boolean {
    return this.dirs.has(norm(path));
  }

  mkdirs(path: string): boolean {
    const p: string = norm(path);
    if (this.dirs.has(p)) return false;
    // mkdirs:递归建全链
    const segs: string[] = p.split('/').filter((s: string): boolean => s.length > 0);
    let cur: string = '';
    segs.forEach((seg: string): void => {
      cur += '/' + seg;
      this.dirs.add(cur);
    });
    return true;
  }

  readBytes(path: string): Uint8Array {
    const p: string = norm(path);
    const content: string | undefined = this.files.get(p);
    if (content === undefined) throw new Error(`NoSuchFile: ${p}`);
    return te.encode(content);
  }

  writeText(path: string, content: string): void {
    const p: string = norm(path);
    const parent: string = p.substring(0, p.lastIndexOf('/')) || '/';
    if (!this.dirs.has(parent)) throw new Error(`NoSuchDirectory: ${parent}`);
    this.files.set(p, content);
  }

  // D-126:SkillFilePort.writeBytes — 与 writeText 同语义,载体换字节
  writeBytes(path: string, bytes: Uint8Array): void {
    const p: string = norm(path);
    const parent: string = p.substring(0, p.lastIndexOf('/')) || '/';
    if (!this.dirs.has(parent)) throw new Error(`NoSuchDirectory: ${parent}`);
    this.files.set(p, new TextDecoder().decode(bytes));
  }

  deleteRecursively(path: string): boolean {
    const p: string = norm(path);
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

  renameTo(from: string, to: string): boolean {
    const f: string = norm(from);
    const t: string = norm(to);
    if (!this.exists(f)) return false;
    // POSIX rename 语义(与 Android/Linux File.renameTo 实测一致):目标已存在时,
    //   文件 → 覆盖;空目录 → 覆盖;非空目录 → 失败
    if (this.exists(t)) {
      if (this.dirs.has(t)) {
        if (!this.dirs.has(f)) return false; // 文件 → 目录不可覆盖
        const tPrefix: string = t + '/';
        const tEmpty: boolean = !this.exists('/nonexistent-probe') &&
          Array.from(this.files.keys()).every((k: string): boolean => !k.startsWith(tPrefix)) &&
          Array.from(this.dirs).every((d: string): boolean => !d.startsWith(tPrefix));
        if (!tEmpty) return false;
        this.dirs.delete(t);
      } else {
        if (this.dirs.has(f)) return false; // 目录 → 文件不可覆盖
        this.files.delete(t);
      }
    }
    const tParent: string = t.substring(0, t.lastIndexOf('/')) || '/';
    if (!this.dirs.has(tParent)) return false;
    const prefix: string = f + '/';
    const movePath = (p: string): string => t + p.substring(f.length);
    const movedFiles: Array<[string, string]> = [];
    this.files.forEach((v: string, k: string): void => {
      if (k === f || k.startsWith(prefix)) movedFiles.push([movePath(k), v]);
    });
    const movedDirs: string[] = [];
    this.dirs.forEach((d: string): void => {
      if (d === f || d.startsWith(prefix)) movedDirs.push(movePath(d));
    });
    Array.from(this.files.keys()).forEach((k: string): void => {
      if (k === f || k.startsWith(prefix)) this.files.delete(k);
    });
    Array.from(this.dirs).forEach((d: string): void => {
      if (d === f || d.startsWith(prefix)) this.dirs.delete(d);
    });
    movedFiles.forEach((pair: [string, string]): void => {
      this.files.set(pair[0], pair[1]);
    });
    movedDirs.forEach((d: string): void => {
      this.dirs.add(d);
    });
    return true;
  }

  walkFiles(root: string): string[] {
    const r: string = norm(root);
    const prefix: string = r + '/';
    const out: string[] = [];
    this.files.forEach((_v: string, f: string): void => {
      if (f.startsWith(prefix)) out.push(f.substring(prefix.length));
    });
    out.sort();
    return out;
  }
}

const nodeInflate = (data: Uint8Array, _expected: number): Promise<Uint8Array> =>
  Promise.resolve(new Uint8Array(inflateRawSync(data)));

const makeManager = (fs: MemFs, onDeleted?: (name: string) => void): SkillManager =>
  new SkillManager({
    port: fs,
    skillsRoot: '/files/skills',
    onSkillDeleted: onDeleted,
  });

const seedSkill = (fs: MemFs, name: string, skillMd: string, extra?: Map<string, string>): void => {
  fs.mkdirs(`/files/skills/${name}`);
  fs.writeText(`/files/skills/${name}/SKILL.md`, skillMd);
  if (extra !== undefined) {
    extra.forEach((content: string, rel: string): void => {
      const dir: string = rel.substring(0, rel.lastIndexOf('/'));
      if (dir.length > 0) fs.mkdirs(`/files/skills/${name}/${dir}`);
      fs.writeText(`/files/skills/${name}/${rel}`, content);
    });
  }
};

const GOOD_MD: string = '---\nname: demo\ndescription: "A demo skill"\n---\n\n# Demo\n\nDo things.';

// ===== SkillFrontmatterParser =====

test('parse:基本 frontmatter(name/description/compatibility/allowed-tools)', () => {
  const fm: Record<string, string> = skillParseFrontmatter(
    '---\nname: demo\ndescription: "A demo"\ncompatibility: android\nallowed-tools: a b\n---\n\nbody');
  assert.equal(fm['name'], 'demo');
  assert.equal(fm['description'], 'A demo');
  assert.equal(fm['compatibility'], 'android');
  assert.equal(fm['allowed-tools'], 'a b');
});

test('parse:无 --- 前缀/无结束/空值跳过/冒号在首列跳过', () => {
  assert.deepEqual(skillParseFrontmatter('no frontmatter'), {});
  assert.deepEqual(skillParseFrontmatter('---\nname: x'), {});
  const fm: Record<string, string> = skillParseFrontmatter('---\nname:\n:bad\nok: v\n---\n');
  assert.deepEqual(fm, { ok: 'v' });
});

test('parse:\\r\\n frontmatter;extractBody 去前导 \\r\\n', () => {
  const fm: Record<string, string> = skillParseFrontmatter('---\r\nname: d\r\n---\r\n\r\nbody');
  assert.equal(fm['name'], 'd');
  assert.equal(skillExtractBody('---\r\nname: d\r\n---\r\n\r\nbody'), 'body');
  assert.equal(skillExtractBody('plain'), 'plain');
  // 无 frontmatter 结束 → 原文返回
  assert.equal(skillExtractBody('---\nname: d'), '---\nname: d');
});

test('ensureDescription:无 frontmatter → 包头 + 推断描述', () => {
  const out: string = skillEnsureDescription('# Heading\n\nUse when testing things.', 'my-skill');
  assert.ok(out.startsWith('---\nname: my-skill\ndescription: "Use when testing things."\n---\n\n'));
  assert.ok(out.endsWith('# Heading\n\nUse when testing things.'));
});

test('ensureDescription:占位 description → 原位替换(name 行后)', () => {
  const src: string = '---\nname: d\ndescription: tbd\nother: x\n---\n\nUse when you need d.';
  const out: string = skillEnsureDescription(src, 'd');
  assert.ok(out.indexOf('name: d\ndescription: "Use when you need d."\nother: x') >= 0);
});

test('ensureDescription:无 description 行 → 插到 name 行后;通用标题跳过', () => {
  const src: string = '---\nname: d\n---\n\n# Instructions\n\nDo the d thing.';
  const out: string = skillEnsureDescription(src, 'd');
  assert.ok(out.indexOf('name: d\ndescription: "Do the d thing."') >= 0);
});

test('inferDescription:invocation cue 优先于首行;120 截断 + …', () => {
  const long: string = 'x'.repeat(200);
  const src1: string = `---\nname: d\n---\n\nFirst line.\n\nUse when the user asks for d.`;
  assert.equal(skillResolveDescription(src1, {}, 'd'), 'Use when the user asks for d.');
  const src2: string = `---\nname: d\n---\n\n${long}`;
  const resolved: string = skillResolveDescription(src2, {}, 'd');
  assert.equal(resolved.length, 120);
  assert.ok(resolved.endsWith('…'));
});

// ===== SkillPaths =====

test('resolveSkillDirPath:blank/./../含分隔符 → null;合法 → root/name', () => {
  assert.equal(resolveSkillDirPath('/files/skills', ''), null);
  assert.equal(resolveSkillDirPath('/files/skills', '.'), null);
  assert.equal(resolveSkillDirPath('/files/skills', '..'), null);
  assert.equal(resolveSkillDirPath('/files/skills', 'a/b'), null);
  assert.equal(resolveSkillDirPath('/files/skills', 'a\\b'), null);
  assert.equal(resolveSkillDirPath('/files/skills', 'demo'), '/files/skills/demo');
});

test('resolveSkillFilePath:blank/逃逸 → null;嵌套/点段 → 归一', () => {
  assert.equal(resolveSkillFilePath('/files/skills/demo', ''), null);
  assert.equal(resolveSkillFilePath('/files/skills/demo', '../other/x.md'), null);
  assert.equal(resolveSkillFilePath('/files/skills/demo', 'a/../../x.md'), null);
  assert.equal(
    resolveSkillFilePath('/files/skills/demo', 'refs/./a.md'), '/files/skills/demo/refs/a.md');
  assert.equal(
    resolveSkillFilePath('/files/skills/demo', 'SKILL.md'), '/files/skills/demo/SKILL.md');
});

// ===== canonicalSkillFileName / isLikelyTextSkillFile =====

test('canonicalSkillFileName:skill.txt→SKILL.md;.md.txt 去尾;反斜杠归一', () => {
  assert.equal(canonicalSkillFileName('skill.txt'), 'SKILL.md');
  assert.equal(canonicalSkillFileName('sub/skill.txt'), 'sub/SKILL.md');
  assert.equal(canonicalSkillFileName('SKILL.TXT'), 'SKILL.md');
  // :466-468 — skill.md.txt 仅 dropLast(4),不归一为 SKILL.md(quirk 钉住)
  assert.equal(canonicalSkillFileName('skill.md.txt'), 'skill.md');
  assert.equal(canonicalSkillFileName('sub/skill.md.txt'), 'sub/skill.md');
  assert.equal(canonicalSkillFileName('notes.md.txt'), 'notes.md');
  assert.equal(canonicalSkillFileName('a\\b\\c.md'), 'a/b/c.md');
  assert.equal(canonicalSkillFileName('/leading.md'), 'leading.md');
  assert.equal(canonicalSkillFileName('plain.json'), 'plain.json');
});

// ===== SkillManager =====

test('listSkills:扫描 + 缓存 + saveSkill 失效重扫;无 name 解析失败跳过', () => {
  const fs: MemFs = new MemFs();
  seedSkill(fs, 'demo', GOOD_MD);
  seedSkill(fs, 'broken', '---\ndescription: no name here\n---\n\nbody');
  const m: SkillManager = makeManager(fs);
  const first: SkillMetadata[] = m.listSkills();
  assert.equal(first.length, 1);
  assert.equal(first[0].name, 'demo');
  assert.equal(first[0].description, 'A demo skill');
  assert.equal(first[0].skillDir, '/files/skills/demo');
  // 缓存:再种一个不重扫
  seedSkill(fs, 'later', GOOD_MD.replace('demo', 'later'));
  assert.equal(m.listSkills().length, 1);
  // saveSkill → 缓存失效
  m.saveSkill('fresh', '# Fresh\n\nUse when fresh.');
  const names: string[] = m.listSkills().map((s: SkillMetadata): string => s.name);
  assert.deepEqual(names.sort(), ['demo', 'fresh', 'later']);
});

test('listSkillIssues:缺 SKILL.md/缺 name/占位 description 三类', () => {
  const fs: MemFs = new MemFs();
  fs.mkdirs('/files/skills/nofile');
  seedSkill(fs, 'noname', '---\ndescription: x\n---\n\nbody');
  seedSkill(fs, 'placeholder', '---\nname: p\ndescription: |\n---\n\nbody');
  seedSkill(fs, 'good', GOOD_MD);
  const m: SkillManager = makeManager(fs);
  const issues = m.listSkillIssues();
  const byDir: Record<string, string> = {};
  issues.forEach((i): void => {
    byDir[i.directoryName] = i.reason;
  });
  assert.equal(byDir['nofile'], '缺少 SKILL.md');
  assert.equal(byDir['noname'], 'SKILL.md 缺少 name');
  assert.equal(byDir['placeholder'], 'SKILL.md 缺少有效 description');
  assert.equal(byDir['good'], undefined);
});

test('readSkillBody/readSkillContent:body 抽取 vs 原文;不存在 → null', () => {
  const fs: MemFs = new MemFs();
  seedSkill(fs, 'demo', GOOD_MD);
  const m: SkillManager = makeManager(fs);
  assert.equal(m.readSkillBody('demo'), '# Demo\n\nDo things.');
  assert.equal(m.readSkillContent('demo'), GOOD_MD);
  assert.equal(m.readSkillBody('ghost'), null);
  assert.equal(m.readSkillBody('../escape'), null);
});

test('saveSkill:占位描述自动补齐;deleteSkill:级联 + onSkillDeleted 回调', () => {
  const fs: MemFs = new MemFs();
  const deleted: string[] = [];
  const m: SkillManager = makeManager(fs, (name: string): void => {
    deleted.push(name);
  });
  const meta: SkillMetadata | null = m.saveSkill('new-skill', '# X\n\nUse when x.');
  assert.ok(meta !== null);
  assert.equal(meta?.description, 'Use when x.');
  assert.ok(m.readSkillContent('new-skill')?.startsWith('---\nname: new-skill\n'));
  m.listSkills();
  assert.ok(m.deleteSkill('new-skill'));
  assert.deepEqual(deleted, ['new-skill']);
  assert.equal(m.readSkillContent('new-skill'), null);
  assert.ok(!m.deleteSkill('new-skill'));
  assert.ok(!m.deleteSkill('../bad'));
});

test('saveSkillFile/deleteSkillFile:嵌套路径 + 逃逸拒绝', () => {
  const fs: MemFs = new MemFs();
  seedSkill(fs, 'demo', GOOD_MD);
  const m: SkillManager = makeManager(fs);
  assert.ok(m.saveSkillFile('demo', 'refs/a.md', 'ref content'));
  assert.equal(m.resolveSkillFile('demo', 'refs/a.md'), '/files/skills/demo/refs/a.md');
  assert.ok(!m.saveSkillFile('demo', '../evil.md', 'x'));
  assert.ok(m.deleteSkillFile('demo', 'refs/a.md'));
  assert.equal(fs.exists('/files/skills/demo/refs/a.md'), false);
});

test('saveSkillFilesAtomically:新建 + 覆盖(backup 恢复路径)+ 缺 SKILL.md → false', () => {
  const fs: MemFs = new MemFs();
  const m: SkillManager = makeManager(fs);
  const files: Map<string, string> = new Map<string, string>();
  files.set('SKILL.md', '---\nname: pack\ndescription: packed\n---\n\nbody');
  files.set('refs/r.md', 'ref');
  assert.ok(m.saveSkillFilesAtomically('pack', files));
  assert.equal(fs.files.get('/files/skills/pack/refs/r.md'), 'ref');
  // 覆盖:旧文件不再包内 → 整目录替换
  const files2: Map<string, string> = new Map<string, string>();
  files2.set('SKILL.md', '---\nname: pack\ndescription: v2\n---\n\nbody2');
  assert.ok(m.saveSkillFilesAtomically('pack', files2));
  assert.equal(fs.exists('/files/skills/pack/refs/r.md'), false);
  assert.equal(fs.files.get('/files/skills/pack/SKILL.md'), '---\nname: pack\ndescription: v2\n---\n\nbody2');
  // 无 staging 残留
  assert.ok(!fs.exists('/files/skills/.pack.staging.0.tmp'));
  // 缺 SKILL.md → false,目录不建
  const bad: Map<string, string> = new Map<string, string>();
  bad.set('readme.md', 'x');
  assert.ok(!m.saveSkillFilesAtomically('badpack', bad));
  assert.ok(!fs.exists('/files/skills/badpack'));
});

test('repairMissingDescriptions:占位补齐计数 + 缓存失效', () => {
  const fs: MemFs = new MemFs();
  seedSkill(fs, 'demo', '---\nname: demo\ndescription: tbd\n---\n\nUse when demo.');
  const m: SkillManager = makeManager(fs);
  m.listSkills();
  assert.equal(m.repairMissingDescriptions(), 1);
  assert.ok(m.readSkillContent('demo')?.indexOf('description: "Use when demo."') !== -1);
  assert.equal(m.repairMissingDescriptions(), 0);
});

// ===== unzipSkillFiles / collectWorkspaceSkillFiles =====

interface FixtureEntry { name: string; text: string; }

const buildStoredZip = (entries: FixtureEntry[]): Uint8Array => {
  const parts: number[] = [];
  const cd: Array<{ e: FixtureEntry; local: number; plain: Uint8Array }> = [];
  entries.forEach((e: FixtureEntry): void => {
    const name: Uint8Array = te.encode(e.name);
    const plain: Uint8Array = te.encode(e.text);
    const local: number = parts.length;
    parts.push(
      0x50, 0x4b, 0x03, 0x04, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      plain.length & 0xff, (plain.length >> 8) & 0xff, 0, 0,
      plain.length & 0xff, (plain.length >> 8) & 0xff, 0, 0,
      name.length & 0xff, (name.length >> 8) & 0xff, 0, 0,
      ...name, ...plain);
    cd.push({ e, local, plain });
  });
  const cdOff: number = parts.length;
  cd.forEach(({ e, local, plain }): void => {
    const name: Uint8Array = te.encode(e.name);
    parts.push(
      0x50, 0x4b, 0x01, 0x02, 20, 0, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      plain.length & 0xff, (plain.length >> 8) & 0xff, 0, 0,
      plain.length & 0xff, (plain.length >> 8) & 0xff, 0, 0,
      name.length & 0xff, (name.length >> 8) & 0xff,
      0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
      local & 0xff, (local >> 8) & 0xff, (local >> 16) & 0xff, (local >> 24) & 0xff,
      ...name);
  });
  const cdSize: number = parts.length - cdOff;
  const count: number = entries.length;
  parts.push(
    0x50, 0x4b, 0x05, 0x06, 0, 0, 0, 0,
    count & 0xff, (count >> 8) & 0xff, count & 0xff, (count >> 8) & 0xff,
    cdSize & 0xff, (cdSize >> 8) & 0xff, (cdSize >> 16) & 0xff, (cdSize >> 24) & 0xff,
    cdOff & 0xff, (cdOff >> 8) & 0xff, (cdOff >> 16) & 0xff, (cdOff >> 24) & 0xff,
    0, 0);
  return new Uint8Array(parts);
};

test('unzipSkillFiles:首段剥离/目录跳过/非文本过滤/skill.txt 归一', async () => {
  const zip: Uint8Array = buildStoredZip([
    { name: 'pkg/', text: '' },
    { name: 'pkg/skill.txt', text: '---\nname: z\ndescription: zipped\n---\n\nbody' },
    { name: 'pkg/refs/r.md', text: 'ref' },
    { name: 'pkg/logo.png', text: 'binary' },
    { name: 'pkg/mcp.json', text: '{"mcpServers":{}}' },
  ]);
  const files: Map<string, string> = await unzipSkillFiles(zip, nodeInflate);
  assert.equal(files.get('SKILL.md'), '---\nname: z\ndescription: zipped\n---\n\nbody');
  assert.equal(files.get('refs/r.md'), 'ref');
  assert.equal(files.get('mcp.json'), '{"mcpServers":{}}');
  assert.ok(!files.has('logo.png'));
  assert.equal(files.size, 3);
});

test('collectWorkspaceSkillFiles:单文件/前缀剥离/空路径报错', async () => {
  const ws: SkillWorkspacePort = {
    readBytes: (p: string): Promise<Uint8Array> => {
      if (p === 'skill-pack/SKILL.md') return Promise.resolve(te.encode(GOOD_MD));
      return Promise.reject(new Error('not a file'));
    },
    list: (): Promise<SkillWorkspaceEntry[]> => Promise.resolve([]),
  };
  const one: Map<string, string> =
    await collectWorkspaceSkillFiles(ws, '/workspace/skill-pack/SKILL.md', nodeInflate);
  assert.equal(one.get('SKILL.md'), GOOD_MD);
  await assert.rejects(
    collectWorkspaceSkillFiles(ws, '   ', nodeInflate),
    /workspace_path must not be empty/);
});

test('collectWorkspaceSkillFiles:目录递归(文本过滤 + 相对键)', async () => {
  const tree: Record<string, string> = {
    'pack/SKILL.md': GOOD_MD,
    'pack/notes/a.md': 'note a',
    'pack/bin/x.png': 'png',
  };
  const ws: SkillWorkspacePort = {
    readBytes: (p: string): Promise<Uint8Array> => {
      const hit: string | undefined = tree[p];
      if (hit === undefined) return Promise.reject(new Error('not a file'));
      return Promise.resolve(te.encode(hit));
    },
    list: (dir: string): Promise<SkillWorkspaceEntry[]> => {
      if (dir === 'pack') {
        return Promise.resolve([
          { path: 'pack/SKILL.md', name: 'SKILL.md', directory: false },
          { path: 'pack/notes', name: 'notes', directory: true },
          { path: 'pack/bin', name: 'bin', directory: true },
        ]);
      }
      if (dir === 'pack/notes') {
        return Promise.resolve([{ path: 'pack/notes/a.md', name: 'a.md', directory: false }]);
      }
      if (dir === 'pack/bin') {
        return Promise.resolve([{ path: 'pack/bin/x.png', name: 'x.png', directory: false }]);
      }
      return Promise.resolve([]);
    },
  };
  const files: Map<string, string> = await collectWorkspaceSkillFiles(ws, 'pack', nodeInflate);
  assert.equal(files.get('SKILL.md'), GOOD_MD);
  assert.equal(files.get('notes/a.md'), 'note a');
  assert.equal(files.size, 2);
});

// ===== collectSkillFilesFromDirectory =====

test('collectSkillFilesFromDirectory:null/非目录 → 空;canonical + 文本过滤', () => {
  const fs: MemFs = new MemFs();
  seedSkill(fs, 'demo', GOOD_MD, new Map<string, string>([
    ['refs/a.md', 'ref a'],
    ['img/p.png', 'png'],
    ['skill.txt.bak.md.txt', 'bak'],
  ]));
  const m: SkillManager = makeManager(fs);
  assert.equal(collectSkillFilesFromDirectory(m, null).size, 0);
  assert.equal(collectSkillFilesFromDirectory(m, '/files/skills/ghost').size, 0);
  const files: Map<string, string> =
    collectSkillFilesFromDirectory(m, '/files/skills/demo');
  assert.equal(files.get('SKILL.md'), GOOD_MD);
  assert.equal(files.get('refs/a.md'), 'ref a');
  assert.equal(files.get('skill.txt.bak.md'), 'bak');
  assert.ok(!files.has('img/p.png'));
});

// ===== createSkillTools =====

interface EnableCall { name: string; enable: boolean; }

const makeToolDeps = (fs: MemFs, enabled: string[], calls: EnableCall[]): CreateSkillToolsDeps => {
  const manager: SkillManager = makeManager(fs);
  const allSkills: SkillMetadata[] = manager.listSkills();
  return {
    enabledSkills: enabled,
    allSkills,
    skillManager: manager,
    setSkillEnabled: (name: string, enable: boolean): Promise<void> => {
      calls.push({ name, enable });
      return Promise.resolve();
    },
    workspace: {
      readBytes: (): Promise<Uint8Array> => Promise.reject(new Error('none')),
      list: (): Promise<SkillWorkspaceEntry[]> => Promise.resolve([]),
    },
    inflateRaw: nodeInflate,
  };
};

const findTool = (tools: AgentTool[], name: string): AgentTool => {
  const hit: AgentTool | undefined = tools.find((t: AgentTool): boolean => t.name === name);
  assert.ok(hit !== undefined, `tool ${name} present`);
  return hit as AgentTool;
};

test('use_skill:name 必填/未启用/未安装错误文案逐字', async () => {
  const fs: MemFs = new MemFs();
  seedSkill(fs, 'demo', GOOD_MD);
  const calls: EnableCall[] = [];
  const tools: AgentTool[] = createSkillTools(makeToolDeps(fs, ['demo'], calls));
  const t: AgentTool = findTool(tools, 'use_skill');
  await assert.rejects(t.execute({}), /name is required/);
  await assert.rejects(t.execute({ name: 'other' }),
    /Skill 'other' is not enabled\. Call skills_list to see installed and enabled skills\./);
  // 'ghost' 在 enabledSkills(过闸)但未安装 → resolve 出路径但文件不在 → not found
  //   (available 需非空才有 use_skill 工具 → 种 demo 陪跑)
  const fs2: MemFs = new MemFs();
  seedSkill(fs2, 'demo', GOOD_MD);
  const tools2: AgentTool[] = createSkillTools(makeToolDeps(fs2, ['demo', 'ghost'], []));
  const t2: AgentTool = findTool(tools2, 'use_skill');
  await assert.rejects(t2.execute({ name: 'ghost' }), /Skill 'ghost' not found/);
});

test('use_skill:SKILL.md body 抽取 + 移动运行时包装;path 子文件读取', async () => {
  const fs: MemFs = new MemFs();
  seedSkill(fs, 'demo', GOOD_MD, new Map<string, string>([['refs/a.md', 'REF BODY']]));
  const tools: AgentTool[] = createSkillTools(makeToolDeps(fs, ['demo'], []));
  const t: AgentTool = findTool(tools, 'use_skill');
  const parts: UIMessagePart[] = await t.execute({ name: 'demo' });
  if (parts[0].type !== 'text') throw new Error('text part');
  assert.ok(parts[0].text.indexOf('Skill: demo  (SKILL.md)') >= 0);
  assert.ok(parts[0].text.indexOf('begins ---\n# Demo\n\nDo things.\n--- skill content ends') >= 0);
  const sub: UIMessagePart[] = await t.execute({ name: 'demo', path: 'refs/a.md' });
  if (sub[0].type !== 'text') throw new Error('text part');
  assert.ok(sub[0].text.indexOf('Skill: demo  (refs/a.md)') >= 0);
  assert.ok(sub[0].text.indexOf('begins ---\nREF BODY\n--- skill content ends') >= 0);
});

test('use_skill:路径逃逸/缺失文件提示(文件清单 + 仅 SKILL.md 两分支)', async () => {
  const fs: MemFs = new MemFs();
  seedSkill(fs, 'demo', GOOD_MD, new Map<string, string>([['refs/a.md', 'x']]));
  // 'bare' = 空目录(walkFiles 空 → available blank → 第二分支;:137-141)
  fs.mkdirs('/files/skills/bare');
  seedSkill(fs, 'demo2', GOOD_MD.replace('demo', 'demo2'));
  const tools: AgentTool[] = createSkillTools(makeToolDeps(fs, ['demo', 'bare', 'demo2'], []));
  const t: AgentTool = findTool(tools, 'use_skill');
  await assert.rejects(t.execute({ name: 'demo', path: '../escape.md' }),
    /Path '\.\.\/escape\.md' is outside the skill directory/);
  await assert.rejects(t.execute({ name: 'demo', path: 'refs/missing.md' }),
    /File 'refs\/missing\.md' not found in skill 'demo'\. This skill only ships with these files: SKILL\.md, refs\/a\.md\./);
  await assert.rejects(t.execute({ name: 'bare', path: 'refs/m.md' }),
    /This skill ships with only SKILL\.md\. Re-read it/);
});

test('skill_import:needsApproval + workspace 目录导入全链路(落盘 + enable 回调)', async () => {
  const fs: MemFs = new MemFs();
  const calls: EnableCall[] = [];
  const deps = makeToolDeps(fs, [], calls);
  deps.workspace = {
    readBytes: (p: string): Promise<Uint8Array> => {
      if (p === 'pack/SKILL.md') return Promise.resolve(te.encode(GOOD_MD));
      return Promise.reject(new Error('not a file'));
    },
    list: (dir: string): Promise<SkillWorkspaceEntry[]> => dir === 'pack'
      ? Promise.resolve([{ path: 'pack/SKILL.md', name: 'SKILL.md', directory: false }])
      : Promise.resolve([]),
  };
  const tools: AgentTool[] = createSkillTools(deps);
  const t: AgentTool = findTool(tools, 'skill_import');
  assert.equal(t.needsApproval, true);
  await assert.rejects(t.execute({}), /workspace_path is required/);
  const parts: UIMessagePart[] = await t.execute({ workspace_path: 'pack' });
  if (parts[0].type !== 'text') throw new Error('text part');
  const payload = JSON.parse(parts[0].text);
  assert.deepEqual(payload, {
    success: true, name: 'demo', file_count: 1, enabled: true, contains_mcp_config: false,
  });
  assert.equal(fs.files.get('/files/skills/demo/SKILL.md'), GOOD_MD);
  assert.deepEqual(calls, [{ name: 'demo', enable: true }]);
});

test('skill_import:zip 导入(stored)+ 缺 SKILL.md/缺 name 错误逐字', async () => {
  const fs: MemFs = new MemFs();
  const calls: EnableCall[] = [];
  const zip: Uint8Array = buildStoredZip([
    { name: 'pkg/SKILL.md', text: GOOD_MD },
    { name: 'pkg/mcp.json', text: '{"mcpServers":{}}' },
  ]);
  const deps = makeToolDeps(fs, [], calls);
  deps.workspace = {
    readBytes: (p: string): Promise<Uint8Array> => p === 'pack.zip'
      ? Promise.resolve(zip) : Promise.reject(new Error('none')),
    list: (): Promise<SkillWorkspaceEntry[]> => Promise.resolve([]),
  };
  const t: AgentTool = findTool(createSkillTools(deps), 'skill_import');
  const parts: UIMessagePart[] = await t.execute({ workspace_path: 'pack.zip' });
  if (parts[0].type !== 'text') throw new Error('text part');
  const payload = JSON.parse(parts[0].text);
  assert.equal(payload['success'], true);
  assert.equal(payload['name'], 'demo');
  assert.equal(payload['file_count'], 2);
  assert.equal(payload['contains_mcp_config'], true);

  const deps2 = makeToolDeps(new MemFs(), [], []);
  deps2.workspace = {
    readBytes: (): Promise<Uint8Array> => Promise.resolve(te.encode('just text')),
    list: (): Promise<SkillWorkspaceEntry[]> => Promise.resolve([]),
  };
  const t2: AgentTool = findTool(createSkillTools(deps2), 'skill_import');
  await assert.rejects(t2.execute({ workspace_path: 'lonely.txt' }),
    /Skill package does not contain SKILL\.md/);

  const noNameZip: Uint8Array = buildStoredZip([
    { name: 'pkg/SKILL.md', text: '---\ndescription: x\n---\n\nbody' },
  ]);
  const deps3 = makeToolDeps(new MemFs(), [], []);
  deps3.workspace = {
    readBytes: (): Promise<Uint8Array> => Promise.resolve(noNameZip),
    list: (): Promise<SkillWorkspaceEntry[]> => Promise.resolve([]),
  };
  const t3: AgentTool = findTool(createSkillTools(deps3), 'skill_import');
  await assert.rejects(t3.execute({ workspace_path: 'p.zip' }), /SKILL\.md missing name/);
});

test('skill_enable/skill_disable:needsApproval + payload + 回调逐字', async () => {
  const fs: MemFs = new MemFs();
  const calls: EnableCall[] = [];
  const tools: AgentTool[] = createSkillTools(makeToolDeps(fs, [], calls));
  const enable: AgentTool = findTool(tools, 'skill_enable');
  const disable: AgentTool = findTool(tools, 'skill_disable');
  assert.equal(enable.needsApproval, true);
  assert.equal(disable.needsApproval, true);
  await assert.rejects(enable.execute({}), /name is required/);
  const on: UIMessagePart[] = await enable.execute({ name: 'demo' });
  if (on[0].type !== 'text') throw new Error('text part');
  assert.deepEqual(JSON.parse(on[0].text), { success: true, name: 'demo', enabled: true });
  const off: UIMessagePart[] = await disable.execute({ name: 'demo' });
  if (off[0].type !== 'text') throw new Error('text part');
  assert.deepEqual(JSON.parse(off[0].text), { success: true, name: 'demo', enabled: false });
  assert.deepEqual(calls, [
    { name: 'demo', enable: true },
    { name: 'demo', enable: false },
  ]);
});

// ===== D-126 installBuiltinSkillsIfMissing(SkillManager.kt:66-87 + :244-263) =====

// 内存 BuiltinSkillAssetPort:Map 路径 → 文本;目录 list → 直属子名;文件 list → []
class MemAssets implements BuiltinSkillAssetPort {
  files: Map<string, string> = new Map<string, string>();
  failOn: string | null = null;

  list(path: string): string[] {
    if (this.files.has(path)) return []; // 文件 → []
    const prefix: string = path + '/';
    const out: string[] = [];
    this.files.forEach((_v: string, f: string): void => {
      if (!f.startsWith(prefix)) return;
      const rest: string = f.substring(prefix.length);
      const seg: string = rest.split('/')[0];
      if (out.indexOf(seg) < 0) out.push(seg);
    });
    out.sort();
    return out;
  }

  readBytes(path: string): Uint8Array {
    if (this.failOn !== null && path.indexOf(this.failOn) >= 0) {
      throw new Error(`asset read failed: ${path}`);
    }
    const content: string | undefined = this.files.get(path);
    if (content === undefined) throw new Error(`NoSuchAsset: ${path}`);
    return te.encode(content);
  }
}

const seedAssets = (assets: MemAssets, name: string, skillMd: string): void => {
  assets.files.set(`builtin-skills/${name}/SKILL.md`, skillMd);
};

test('installBuiltinSkillsIfMissing:缺失技能全量安装(递归嵌套 + 缓存失效重扫)', async () => {
  const fs: MemFs = new MemFs();
  fs.mkdirs('/files/skills');
  const m: SkillManager = makeManager(fs);
  assert.deepEqual(m.listSkills(), []); // 缓存空
  const assets: MemAssets = new MemAssets();
  seedAssets(assets, 'deep-read-fact-check', GOOD_MD);
  seedAssets(assets, 'skill-creator', GOOD_MD);
  assets.files.set('builtin-skills/skill-creator/scripts/gen.py', 'print(1)'); // 嵌套子目录
  assets.files.set('builtin-skills/skill-creator/scripts/lib/util.py', 'x=1'); // 二层嵌套
  (m as unknown as { deps: { assets: MemAssets } }); // 类型旁路说明:直接重建带 assets 的 manager
  const m2: SkillManager = new SkillManager({
    port: fs,
    skillsRoot: '/files/skills',
    assets,
  });
  await m2.installBuiltinSkillsIfMissing();
  assert.equal(fs.readBytes('/files/skills/deep-read-fact-check/SKILL.md').length > 0, true);
  assert.equal(
    new TextDecoder().decode(fs.readBytes('/files/skills/skill-creator/SKILL.md')), GOOD_MD);
  assert.equal(
    new TextDecoder().decode(fs.readBytes('/files/skills/skill-creator/scripts/gen.py')),
    'print(1)');
  assert.equal(
    new TextDecoder().decode(fs.readBytes('/files/skills/skill-creator/scripts/lib/util.py')),
    'x=1');
  // installedAny → invalidateSkillCache → listSkills 重扫到两件
  const names: string[] = m2.listSkills().map((s: SkillMetadata): string => s.name).sort();
  assert.deepEqual(names, ['demo', 'demo']); // 两件 GOOD_MD name 都是 demo
  assert.equal(m2.listSkills().length, 2);
});

test('installBuiltinSkillsIfMissing:SKILL.md 已存在 → 跳过不覆盖', async () => {
  const fs: MemFs = new MemFs();
  seedSkill(fs, 'skill-creator', '---\nname: custom\ndescription: "mine"\n---\n\nbody');
  const assets: MemAssets = new MemAssets();
  seedAssets(assets, 'skill-creator', GOOD_MD);
  const m: SkillManager = new SkillManager({ port: fs, skillsRoot: '/files/skills', assets });
  await m.installBuiltinSkillsIfMissing();
  // 原内容未被内置版本覆盖
  assert.equal(
    new TextDecoder().decode(fs.readBytes('/files/skills/skill-creator/SKILL.md')),
    '---\nname: custom\ndescription: "mine"\n---\n\nbody');
});

test('installBuiltinSkillsIfMissing:拷贝失败 → log + deleteRecursively 回滚,其余继续', async () => {
  const fs: MemFs = new MemFs();
  const logs: string[] = [];
  const assets: MemAssets = new MemAssets();
  seedAssets(assets, 'bad-skill', GOOD_MD);
  assets.files.set('builtin-skills/bad-skill/data/x.txt', 'x');
  assets.failOn = 'data/x.txt'; // 递归拷贝到子文件时炸
  seedAssets(assets, 'good-skill', GOOD_MD);
  const m: SkillManager = new SkillManager({
    port: fs,
    skillsRoot: '/files/skills',
    assets,
    log: (msg: string): void => { logs.push(msg); },
  });
  await m.installBuiltinSkillsIfMissing();
  // 失败技能整目录回滚
  assert.equal(fs.exists('/files/skills/bad-skill'), false);
  // log 记录 SkillManager.kt:82 文案骨架
  assert.equal(logs.length, 1);
  assert.equal(
    logs[0].startsWith('installBuiltinSkillsIfMissing: Failed to install bad-skill:'), true);
  // 其余技能照常安装(逐个 try,互不影响)
  assert.equal(fs.exists('/files/skills/good-skill/SKILL.md'), true);
});
