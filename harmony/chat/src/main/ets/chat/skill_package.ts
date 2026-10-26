// Installed and Workspace skill packages share their path, text and replacement
// rules here. The file ports are injected; no catalog cache or tool state lives here.
import { readZipEntries, readZipEntryText, utf8Decode } from './zip_archive.ts';
import type { InflateRawPort } from './zip_archive.ts';
import { skillEnsureDescription } from './skill_metadata.ts';

export const MAX_SKILL_FILE_BYTES: number = 4 * 1024 * 1024;

// ===== SkillPaths(SkillPaths.kt 全文;File → path 串词法归一) =====

// 词法归一:'\\' → '/','.'/'..' 展开,合并多余 '/'
const normalizeLexical = (path: string): string => {
  const abs: boolean = path.startsWith('/');
  const parts: string[] = [];
  path.replace(/\\/g, '/').split('/').forEach((seg: string): void => {
    if (seg === '' || seg === '.') return;
    if (seg === '..') {
      parts.pop();
      return;
    }
    parts.push(seg);
  });
  return (abs ? '/' : '') + parts.join('/');
};

const isSameOrInsidePath = (path: string, root: string): boolean =>
  path === root || path.startsWith(root + '/');

// :6-19
export const resolveSkillDirPath = (skillsRoot: string, skillName: string): string | null => {
  if (skillName.trim().length === 0) return null;
  if (skillName === '.' || skillName === '..') return null;
  if (skillName.indexOf('/') >= 0 || skillName.indexOf('\\') >= 0) return null;

  const canonicalRoot: string = normalizeLexical(skillsRoot);
  const canonicalDir: string = normalizeLexical(canonicalRoot + '/' + skillName);
  const parent: string = canonicalDir.substring(0, canonicalDir.lastIndexOf('/'));

  if (parent !== canonicalRoot) return null;
  if (!isSameOrInsidePath(canonicalDir, canonicalRoot)) return null;
  return canonicalDir;
};

// :21-28
export const resolveSkillFilePath = (skillDir: string, relativePath: string): string | null => {
  if (relativePath.trim().length === 0) return null;
  const canonicalSkillDir: string = normalizeLexical(skillDir);
  const canonicalTarget: string = normalizeLexical(canonicalSkillDir + '/' + relativePath);
  return isSameOrInsidePath(canonicalTarget, canonicalSkillDir) ? canonicalTarget : null;
};

// ===== SkillFilePort(Context.filesDir 文件系统抽象;entry = fileIo 同步 API) =====

export interface SkillDirEntry {
  name: string;
  isDirectory: boolean;
}

export interface SkillFilePort {
  // File.listFiles():目录不存在/不可读 → [](Android null → emptyList)
  listDir(path: string): SkillDirEntry[];
  exists(path: string): boolean;
  isDirectory(path: string): boolean;
  // File.mkdirs() 语义:新建成功 → true;已存在/失败 → false(不抛)
  mkdirs(path: string): boolean;
  readBytes(path: string): Uint8Array;
  writeText(path: string, content: string): void;
  // D-126:二进制写(copyAssetDirectory input.copyTo(output) 载体)
  writeBytes(path: string, bytes: Uint8Array): void;
  deleteRecursively(path: string): boolean;
  renameTo(from: string, to: string): boolean;
  // File.walkTopDown().filter{isFile} → 相对 root 的 '/' 分隔路径(稳定序)
  walkFiles(root: string): string[];
}

// D-126:内置技能资产面(Context.assets → harmony rawfile;entry =
//   resourceManager getRawFileListSync/getRawFileContentSync)
//   assets.list(path):目录 → 子名数组;文件 → [](copyAssetDirectory 判据)
export interface BuiltinSkillAssetPort {
  list(path: string): string[];
  readBytes(path: string): Uint8Array;
}

// ===== 文本文件判定/文件名规范化(SkillsTools.kt:437-474) =====

// :437-456 逐字
export const isLikelyTextSkillFile = (name: string): boolean => {
  const lower: string = name.toLowerCase();
  return lower === 'skill.md' ||
    lower === 'skill.txt' ||
    lower === 'mcp.json' ||
    lower.endsWith('.md') ||
    lower.endsWith('.md.txt') ||
    lower.endsWith('.json') ||
    lower.endsWith('.html') ||
    lower.endsWith('.htm') ||
    lower.endsWith('.css') ||
    lower.endsWith('.txt') ||
    lower.endsWith('.yaml') ||
    lower.endsWith('.yml') ||
    lower.endsWith('.js') ||
    lower.endsWith('.mjs') ||
    lower.endsWith('.ts') ||
    lower.endsWith('.py') ||
    lower.endsWith('.sh');
};

// :458-474 逐字
export const canonicalSkillFileName = (raw: string): string => {
  const normalized: string = raw.replace(/^\/+|\/+$/g, '').replace(/\\/g, '/');
  const lower: string = normalized.toLowerCase();
  if (lower === 'skill.txt' || lower.endsWith('/skill.txt')) {
    const idx: number = normalized.lastIndexOf('/');
    const parent: string = idx < 0 ? '' : normalized.substring(0, idx);
    return parent.length === 0 ? 'SKILL.md' : `${parent}/SKILL.md`;
  }
  if (lower === 'skill.md.txt' || lower.endsWith('/skill.md.txt')) {
    return normalized.substring(0, normalized.length - 4);
  }
  if (lower.endsWith('.md.txt')) {
    return normalized.substring(0, normalized.length - 4);
  }
  return normalized;
};

// ===== 收集/解包(SkillsTools.kt:352-433) =====

// WorkspaceManager 读面抽象(SkillWorkspacePort;entry = POSIX 镜像目录)
export interface SkillWorkspaceEntry {
  path: string;
  name: string;
  directory: boolean;
}

export interface SkillWorkspacePort {
  // workspaceManager.readBytes(读取失败由调用侧 try/catch → null,:355)
  readBytes(path: string): Promise<Uint8Array>;
  list(dir: string): Promise<SkillWorkspaceEntry[]>;
}

// :380-400 — installed 目录收集(walkFiles 相对路径;词法同根由端口构造保证)
export const collectSkillFilesAtDirectory = (
  port: SkillFilePort, dir: string | null,
): Map<string, string> => {
  const files: Map<string, string> = new Map<string, string>();
  if (dir === null) return files;
  if (!port.exists(dir) || !port.isDirectory(dir)) return files;
  port.walkFiles(dir).forEach((relative: string): void => {
    const name: string = canonicalSkillFileName(relative);
    if (name.trim().length === 0 || name.indexOf('..') >= 0 || !isLikelyTextSkillFile(name)) {
      return;
    }
    files.set(name, readCappedSkillText(port, dir + '/' + relative, name));
  });
  return files;
};

// readBytesWithinLimit(:421-433):> 4MB 抛(文案逐字,"Skill archive entry"
//   对普通文件同用 = Android quirk 保留)
export const readCappedSkillText = (port: SkillFilePort, path: string, entryName: string): string => {
  const bytes: Uint8Array = port.readBytes(path);
  if (bytes.length > MAX_SKILL_FILE_BYTES) {
    throw new Error(`Skill archive entry ${entryName} exceeds ${MAX_SKILL_FILE_BYTES} bytes`);
  }
  return utf8Decode(bytes);
};

// :402-415 — zip 解包(中央目录序;目录条目跳过;首段剥离;文本过滤;4MB 预检)
export const unzipSkillFiles = async (
  bytes: Uint8Array, inflateRaw: InflateRawPort,
): Promise<Map<string, string>> => {
  const files: Map<string, string> = new Map<string, string>();
  const entries = readZipEntries(bytes);
  for (const entry of entries) {
    if (entry.name.endsWith('/')) continue; // entry.isDirectory
    const trimmed: string = entry.name.replace(/^\/+|\/+$/g, '');
    let clean: string = trimmed.indexOf('/') >= 0 ? trimmed.substring(trimmed.indexOf('/') + 1) : trimmed;
    if (clean.trim().length === 0) {
      const lastSlash: number = entry.name.lastIndexOf('/');
      clean = lastSlash < 0 ? entry.name : entry.name.substring(lastSlash + 1);
    }
    const name: string = canonicalSkillFileName(clean);
    if (name.trim().length === 0 || name.indexOf('..') >= 0 || !isLikelyTextSkillFile(name)) {
      continue;
    }
    if (entry.uncompressedSize > MAX_SKILL_FILE_BYTES) {
      throw new Error(
        `Skill archive entry ${entry.name} exceeds ${MAX_SKILL_FILE_BYTES} bytes`);
    }
    files.set(name, await readZipEntryText(bytes, entry, inflateRaw));
  }
  return files;
};

// :352-378 — workspace 收集(单文件/zip/目录递归)
export const collectWorkspaceSkillFiles = async (
  workspace: SkillWorkspacePort, workspacePath: string, inflateRaw: InflateRawPort,
): Promise<Map<string, string>> => {
  const normalized: string =
    workspacePath.trim().replace(/^\/workspace\//, '').replace(/^\/+|\/+$/g, '');
  if (normalized.trim().length === 0) {
    throw new Error('workspace_path must not be empty');
  }
  let bytes: Uint8Array | null = null;
  try {
    bytes = await workspace.readBytes(normalized);
  } catch (_e) {
    bytes = null; // runCatching{}.getOrNull()
  }
  if (bytes !== null) {
    if (normalized.toLowerCase().endsWith('.zip')) {
      return unzipSkillFiles(bytes, inflateRaw);
    }
    const lastSlash: number = normalized.lastIndexOf('/');
    let relativeName: string = lastSlash < 0 ? normalized : normalized.substring(lastSlash + 1);
    if (relativeName.trim().length === 0) relativeName = 'SKILL.md';
    const files: Map<string, string> = new Map<string, string>();
    if (bytes.length > MAX_SKILL_FILE_BYTES) {
      throw new Error(`Skill file ${normalized} exceeds ${MAX_SKILL_FILE_BYTES} bytes`);
    }
    files.set(canonicalSkillFileName(relativeName), utf8Decode(bytes));
    return files;
  }

  const files: Map<string, string> = new Map<string, string>();
  const walk = async (dir: string, root: string): Promise<void> => {
    const entries: SkillWorkspaceEntry[] = await workspace.list(dir);
    for (const entry of entries) {
      let relative: string = entry.path.startsWith(root)
        ? entry.path.substring(root.length) : entry.path;
      relative = relative.replace(/^\/+|\/+$/g, '');
      if (entry.directory) {
        await walk(entry.path, root);
      } else if (isLikelyTextSkillFile(entry.name)) {
        const key: string = relative.trim().length === 0 ? entry.name : relative;
        // 单文件与 zip 条目同口径 4MB 上限(防超大文件整读入内存)
        const data: Uint8Array = await workspace.readBytes(entry.path);
        if (data.length > MAX_SKILL_FILE_BYTES) {
          throw new Error(`Skill file ${entry.path} exceeds ${MAX_SKILL_FILE_BYTES} bytes`);
        }
        files.set(canonicalSkillFileName(key), utf8Decode(data));
      }
    }
  };
  await walk(normalized, normalized);
  return files;
};

// :234-242 — .{name}.{suffix}.{attempt}.tmp,repeat(100)
const createTempSkillDir = (
  port: SkillFilePort, skillsRoot: string, skillName: string, suffix: string,
): string | null => {
  for (let attempt: number = 0; attempt < 100; attempt++) {
    const candidate: string = `${skillsRoot}/.${skillName}.${suffix}.${attempt}.tmp`;
    if (!port.exists(candidate) && port.mkdirs(candidate)) {
      return candidate;
    }
  }
  return null;
};

// Commit is reported before final cleanup so the library invalidates its cache
// at the original successful-replacement point, even if later cleanup throws.
export const replaceSkillPackageAtomically = (
  port: SkillFilePort, skillsDir: string, targetDir: string, skillName: string,
  files: Map<string, string>, onCommitted: () => void,
): boolean => {
  const stagingDir: string | null = createTempSkillDir(port, skillsDir, skillName, 'staging');
  if (stagingDir === null) return false;
  let backupDir: string | null = null;

  try {
    // ArkTS 禁解构声明 → Map.forEach((value, key))(插入序 = linkedMapOf 序)
    files.forEach((content: string, relativePath: string): void => {
      const target: string | null = resolveSkillFilePath(stagingDir, relativePath);
      if (target === null) {
        throw new Error('skill path escaped staging');
      }
      const parent: string = target.substring(0, target.lastIndexOf('/'));
      port.mkdirs(parent);
      port.writeText(target,
        relativePath === 'SKILL.md' ? skillEnsureDescription(content, skillName) : content);
    });

    if (!port.exists(stagingDir + '/SKILL.md')) return false;

    if (port.exists(targetDir)) {
      backupDir = createTempSkillDir(port, skillsDir, skillName, 'backup');
      if (backupDir === null) return false;
      if (!port.renameTo(targetDir, backupDir)) return false;
    }

    if (!port.renameTo(stagingDir, targetDir)) {
      if (backupDir !== null && !port.exists(targetDir)) {
        port.renameTo(backupDir, targetDir);
      }
      return false;
    }

    if (backupDir !== null) {
      port.deleteRecursively(backupDir);
    }
    onCommitted();
    return true;
  } catch (_e) {
    if (backupDir !== null && !port.exists(targetDir)) {
      port.renameTo(backupDir, targetDir);
    }
    return false;
  } finally {
    if (port.exists(stagingDir)) {
      port.deleteRecursively(stagingDir);
    }
    if (backupDir !== null && port.exists(backupDir)
      && port.exists(targetDir)) {
      port.deleteRecursively(backupDir);
    }
  }
};
