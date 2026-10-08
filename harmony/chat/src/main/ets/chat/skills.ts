// skills — skills 子系统域层(SkillManager.kt/SkillPaths.kt/SkillsTools.kt 移植)
//
// Android 基准(逐字锚点):
//   app/core/files/SkillManager.kt(470 行)— SkillManager 缓存扫描/读写/原子落盘
//     + SkillMetadata(:285-293)+ SkillScanIssue(:295-298)
//     + SkillFrontmatterParser(:300-470 parse/ensureDescription/resolveDescription/
//       isPlaceholderDescription/extractBody/inferDescription 全套)
//   app/core/files/SkillPaths.kt(35 行)— resolveSkillDir/resolveSkillFile 路径安全
//   app/core/ai/tools/SkillsTools.kt(480 行)— createSkillTools 六件:
//     skills_list(:37-83)/skill_validate(:206-248)/skill_import(:250-288)/
//     skill_enable·skill_disable(:290-319)/use_skill(:92-151,available 空→不出)
//     + buildSkillMobileRuntimePrompt(:159-194,含 guizang 两特化块)
//     + collectWorkspaceSkillFiles(:352-378)/collectSkillFilesFromDirectory
//       (:383-400)/unzipSkillFiles(:402-415)/canonicalSkillFileName(:458-474)/
//       isLikelyTextSkillFile(:437-456)/readBytesWithinLimit(:421-433)
//   GuizangHtmlDeckValidator.kt:18-24(RENDERER/LOCAL_MOTION_URL/LOCAL_LUCIDE_URL)
//
// 偏差适配登记:
//   - java.io.File/canonicalFile 符号链接解析 → 词法归一('.'/'..' 展开;skills 根
//     在应用沙箱私有目录内,无符号链接攻击面;walkFiles 由端口实现物理遍历,
//     同根保证由构造成立)
//   - Context.filesDir/assets → SkillFilePort 注入(entry = fileIo 同步 API);
//     installBuiltinSkillsIfMissing(assets/builtin-skills 四件)= rawfile 打包
//     决策 → P1 登记(本切片不装内置技能)
//   - WorkspaceManager(SAF DocumentFile)→ SkillWorkspacePort 注入(entry =
//     filesDir/amberagent/workspace-mirror POSIX 目录;SAF 持久授权层 HarmonyOS
//     无对应物,登记)
//   - ZipInputStream 流式遍历(本地头序)→ readZipEntries 中央目录序(D-113
//     既有;两者通常一致,登记);解压经 InflateRawPort 注入
//   - 4MB 上限在流式读取中抛出 → 解压前按 uncompressedSize 预检(同报错文案,
//     同结果)
//   - settingsStore.update 当前 assistant enabledSkills 变更 → setSkillEnabled
//     回调注入(entry 闭包绑定 rt.assistant.id);deleteSkill 全 assistants 清理
//     → onSkillDeleted 回调注入
//   - Log.w 告警日志 → 域层无日志(端口层可记 hilog;不吞错:解析失败仍按
//     Android 语义返回 null/false)

import type { JsonObject, JsonValue } from './json.ts';
import { makeAgentTool } from './tool.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import type { UIMessagePart } from './message.ts';
import { readZipEntries, readZipEntryText, utf8Decode } from './zip_archive.ts';
import type { InflateRawPort } from './zip_archive.ts';

export const MAX_SKILL_FILE_BYTES: number = 4 * 1024 * 1024;

// ===== SkillMetadata/SkillScanIssue(SkillManager.kt:285-298;skillDir File → path 串) =====

export interface SkillMetadata {
  name: string;
  description: string;
  compatibility: string | null;
  allowedTools: string[];
  skillDir: string;
}

export interface SkillScanIssue {
  directoryName: string;
  reason: string;
}

export const makeSkillMetadata = (
  name: string, description: string, skillDir: string,
  compatibility: string | null = null, allowedTools: string[] = [],
): SkillMetadata => ({
  name, description, compatibility, allowedTools, skillDir,
});

// ===== SkillFrontmatterParser(SkillManager.kt:300-470 逐字) =====

// :301 — Regex("""\r?\n---(?:\r?\n|$)""");find(content, startIndex=3)
const FRONTMATTER_END_REGEX: RegExp = /\r?\n---(?:\r?\n|$)/;

// :302-318 占位描述集合(逐字)
const PLACEHOLDER_DESCRIPTIONS: string[] = [
  '|', '｜', '-', '--', '---', '.', '...', '…', 'todo', 'tbd', 'none', 'n/a',
  'null', 'description', '描述',
];

// :388-391
const findFrontmatterEnd = (content: string): { first: number; last: number } | null => {
  if (!content.startsWith('---')) return null;
  const m: RegExpExecArray | null = FRONTMATTER_END_REGEX.exec(content.slice(3));
  if (m === null) return null;
  const first: number = 3 + m.index;
  return { first, last: first + m[0].length - 1 };
};

// Kotlin String.lines()/lineSequence():\r\n|\r|\n 三分
const kotlinLines = (s: string): string[] => s.split(/\r\n|\r|\n/);

// :320-336
export const skillParseFrontmatter = (content: string): Record<string, string> => {
  const result: Record<string, string> = {};
  if (!content.startsWith('---')) return result;
  const end = findFrontmatterEnd(content);
  if (end === null) return result;
  const yaml: string = content.substring(3, end.first).trim();
  kotlinLines(yaml).forEach((line: string): void => {
    const colonIdx: number = line.indexOf(':');
    if (colonIdx > 0) {
      const key: string = line.substring(0, colonIdx).trim();
      // removeSurrounding("\"")
      let value: string = line.substring(colonIdx + 1).trim();
      if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
        value = value.substring(1, value.length - 1);
      }
      if (key.length > 0 && value.length > 0) {
        result[key] = value;
      }
    }
  });
  return result;
};

// :373-380 — trim('"','\'') = 去首尾引号字符集
export const skillIsPlaceholderDescription = (description: string | null): boolean => {
  const normalized: string = (description ?? '')
    .trim()
    .replace(/^["']+|["']+$/g, '')
    .toLowerCase();
  return normalized.length === 0 || PLACEHOLDER_DESCRIPTIONS.indexOf(normalized) >= 0;
};

// :382-386
export const skillExtractBody = (content: string): string => {
  if (!content.startsWith('---')) return content;
  const end = findFrontmatterEnd(content);
  if (end === null) return content;
  // trimStart('\r','\n')
  return content.substring(end.last + 1).replace(/^[\r\n]+/, '');
};

// :417-431
const cleanDescriptionCandidate = (s: string): string => s
  .trim()
  .replace(/^﻿/, '') // removePrefix("﻿")(BOM,仅去首个)
  .replace(/^#{1,6}\s*/, '')
  .replace(/^[-*+]\s+/, '')
  .replace(/^\d+[.)]\s+/, '')
  .replace(/^\[[ xX]]\s+/, '')
  .replace(/`/g, '')
  .replace(/\*/g, '')
  .replace(/_/g, '')
  .replace(/\s+/g, ' ')
  .trim()
  .replace(/^[|｜]+|[|｜]+$/g, '')
  .trim();

// :433-449
const GENERIC_SKILL_HEADINGS: string[] = [
  'instructions', 'instruction', 'overview', 'description', 'usage', 'workflow',
  'steps', 'skill', 'skill instructions', '说明', '使用说明', '工作流程',
];

const isGenericSkillHeading = (s: string): boolean =>
  GENERIC_SKILL_HEADINGS.indexOf(s.toLowerCase()) >= 0;

// :451-463
const INVOCATION_CUES: string[] = [
  'use when', 'use this skill', 'when the user', '用于', '适用于', '当用户',
  '当需要', '使用场景',
];

const hasInvocationCue = (s: string): boolean => {
  const lower: string = s.toLowerCase();
  return INVOCATION_CUES.some((cue: string): boolean => lower.indexOf(cue) >= 0);
};

// :465-467 — take(maxChars-1).trimEnd() + '…'(JVM char = UTF-16 单元 → slice 同语义)
const takeDescriptionChars = (s: string, maxChars: number): string =>
  s.length <= maxChars ? s : s.slice(0, maxChars - 1).trimEnd() + '…';

// :409-415 — skillName.any { it.code > 127 }(UTF-16 单元 → charCodeAt)
const fallbackDescription = (skillName: string): string => {
  let nonAscii: boolean = false;
  for (let i: number = 0; i < skillName.length; i++) {
    if (skillName.charCodeAt(i) > 127) {
      nonAscii = true;
      break;
    }
  }
  return nonAscii
    ? `用于处理「${skillName}」相关任务。`
    : `Use when the user asks AmberAgent to work with ${skillName}.`;
};

// :393-407
const inferDescription = (content: string, skillName: string): string => {
  const body: string = skillExtractBody(content);
  const candidates: string[] = kotlinLines(body)
    .map(cleanDescriptionCandidate)
    .filter((s: string): boolean => s.length > 0)
    .filter((s: string): boolean => s.toLowerCase() !== skillName.toLowerCase())
    .filter((s: string): boolean => !isGenericSkillHeading(s));
  const preferred: string | undefined =
    candidates.find(hasInvocationCue) ?? (candidates.length > 0 ? candidates[0] : undefined);
  return preferred !== undefined
    ? takeDescriptionChars(preferred, 120)
    : fallbackDescription(skillName);
};

// :368-371
export const skillResolveDescription = (
  content: string, frontmatter: Record<string, string>, skillName: string,
): string => {
  const description: string | undefined = frontmatter['description'];
  return skillIsPlaceholderDescription(description ?? null)
    ? inferDescription(content, skillName)
    : (description as string).trim();
};

// :469
const escapeYamlString = (s: string): string =>
  s.replace(/\\/g, '\\\\').replace(/"/g, '\\"');

// :338-366
export const skillEnsureDescription = (content: string, skillNameHint: string | null = null): string => {
  const frontmatter: Record<string, string> = skillParseFrontmatter(content);
  const fmName: string | undefined = frontmatter['name'];
  const name: string = (fmName !== undefined && fmName.trim().length > 0) ? fmName
    : (skillNameHint !== null && skillNameHint.trim().length > 0) ? skillNameHint
    : 'custom-skill';
  const currentDescription: string | undefined = frontmatter['description'];
  if (!skillIsPlaceholderDescription(currentDescription ?? null)) return content;

  const description: string = inferDescription(content, name);
  const line: string = `description: "${escapeYamlString(description)}"`;
  const end = findFrontmatterEnd(content);
  if (!content.startsWith('---') || end === null) {
    return `---\nname: ${escapeYamlString(name)}\n${line}\n---\n\n${content.trimStart()}`;
  }

  const yaml: string = content.substring(3, end.first).trim();
  const lines: string[] = kotlinLines(yaml);
  // substringBefore(':').trim():无冒号 → 整串
  const keyOf = (l: string): string => {
    const idx: number = l.indexOf(':');
    return (idx < 0 ? l : l.substring(0, idx)).trim();
  };
  const descriptionIndex: number = lines.findIndex((l: string): boolean => keyOf(l) === 'description');
  if (descriptionIndex >= 0) {
    lines[descriptionIndex] = line;
  } else {
    const nameIndex: number = lines.findIndex((l: string): boolean => keyOf(l) === 'name');
    const insertIndex: number = nameIndex >= 0 ? nameIndex + 1 : lines.length;
    lines.splice(insertIndex, 0, line);
  }

  const body: string = content.substring(end.last + 1).replace(/^[\r\n]+/, '');
  return `---\n${lines.join('\n')}\n---\n\n${body}`;
};

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

// ===== SkillManager(SkillManager.kt:10-283;缓存 + 扫描 + 原子落盘) =====

export interface SkillManagerDeps {
  port: SkillFilePort;
  // getSkillsDir() = filesDir/skills(FileFolders.SKILLS = "skills",FilesManager.kt:651)
  skillsRoot: string;
  // deleteSkill 后清理所有 assistants 的 enabledSkills(:114-125)→ entry 注入
  onSkillDeleted?: (name: string) => void;
  // D-126:内置技能 rawfile 资产(缺省 → installBuiltinSkillsIfMissing 空转)
  assets?: BuiltinSkillAssetPort;
  // Log.w 告警(:82)→ entry 注入 hilog
  log?: (message: string) => void;
}

export class SkillManager {
  private readonly deps: SkillManagerDeps;
  private cachedSkills: SkillMetadata[] | null = null;

  constructor(deps: SkillManagerDeps) {
    this.deps = deps;
  }

  // :22-26 — if (!dir.exists()) dir.mkdirs()(结果忽略,与 Android 同)
  getSkillsDir(): string {
    if (!this.deps.port.exists(this.deps.skillsRoot)) {
      this.deps.port.mkdirs(this.deps.skillsRoot);
    }
    return this.deps.skillsRoot;
  }

  // :28-40
  listSkills(): SkillMetadata[] {
    if (this.cachedSkills !== null) return this.cachedSkills;
    const skillsDir: string = this.getSkillsDir();
    const skills: SkillMetadata[] = [];
    this.deps.port.listDir(skillsDir)
      .filter((e: SkillDirEntry): boolean => e.isDirectory)
      .forEach((e: SkillDirEntry): void => {
        const dir: string = skillsDir + '/' + e.name;
        const skillFile: string = dir + '/SKILL.md';
        if (!this.deps.port.exists(skillFile)) return;
        const meta: SkillMetadata | null = this.parseSkillFile(skillFile, dir);
        if (meta !== null) skills.push(meta);
      });
    this.cachedSkills = skills;
    return skills;
  }

  // D-126::66-87(installBuiltinSkillsIfMissing)+ :244-263(copyAssetDirectory)
  //   BUILTIN_SKILLS_ASSET_DIR = 'builtin-skills'(:16);SKILL.md 存在即跳过;
  //   失败 → Log.w(→ deps.log)+ deleteRecursively 回滚;装过 → 缓存失效
  async installBuiltinSkillsIfMissing(): Promise<void> {
    const assets: BuiltinSkillAssetPort | undefined = this.deps.assets;
    if (assets === undefined) return;
    const builtinSkillNames: string[] = assets.list('builtin-skills');
    let installedAny: boolean = false;
    for (const skillName of builtinSkillNames) {
      const targetDir: string | null = resolveSkillDirPath(this.deps.skillsRoot, skillName);
      if (targetDir === null) continue;
      if (this.deps.port.exists(targetDir + '/SKILL.md')) continue;
      try {
        this.deps.port.mkdirs(targetDir);
        this.copyAssetDirectory(assets, `builtin-skills/${skillName}`, targetDir);
        installedAny = true;
      } catch (e) {
        if (this.deps.log !== undefined) {
          this.deps.log(`installBuiltinSkillsIfMissing: Failed to install ${skillName}: ${String(e)}`);
        }
        this.deps.port.deleteRecursively(targetDir);
      }
    }
    if (installedAny) this.invalidateSkillCache();
  }

  // :244-263 — children 空 → 文件拷贝(父目录 mkdirs 结果忽略);否则递归
  private copyAssetDirectory(
    assets: BuiltinSkillAssetPort, assetPath: string, targetDir: string,
  ): void {
    const children: string[] = assets.list(assetPath);
    if (children.length === 0) {
      const parent: string = targetDir.substring(0, targetDir.lastIndexOf('/'));
      this.deps.port.mkdirs(parent);
      this.deps.port.writeBytes(targetDir, assets.readBytes(assetPath));
      return;
    }
    this.deps.port.mkdirs(targetDir);
    children.forEach((child: string): void => {
      this.copyAssetDirectory(assets, `${assetPath}/${child}`, `${targetDir}/${child}`);
    });
  }

  // :42-64
  listSkillIssues(): SkillScanIssue[] {
    const skillsDir: string = this.getSkillsDir();
    const issues: SkillScanIssue[] = [];
    this.deps.port.listDir(skillsDir)
      .filter((e: SkillDirEntry): boolean => e.isDirectory)
      .forEach((e: SkillDirEntry): void => {
        const dir: string = skillsDir + '/' + e.name;
        const skillFile: string = dir + '/SKILL.md';
        if (!this.deps.port.exists(skillFile)) {
          issues.push({ directoryName: e.name, reason: '缺少 SKILL.md' });
          return;
        }
        try {
          const frontmatter: Record<string, string> =
            skillParseFrontmatter(this.readFileText(skillFile));
          const name: string | undefined = frontmatter['name'];
          if (name === undefined || name.trim().length === 0) {
            issues.push({ directoryName: e.name, reason: 'SKILL.md 缺少 name' });
          } else if (skillIsPlaceholderDescription(frontmatter['description'] ?? null)) {
            issues.push({ directoryName: e.name, reason: 'SKILL.md 缺少有效 description' });
          }
        } catch (_e) {
          issues.push({ directoryName: e.name, reason: 'SKILL.md 解析失败' });
        }
      });
    return issues;
  }

  // :89-93
  readSkillBody(skillName: string): string | null {
    const dir: string | null = this.resolveSkillDir(skillName);
    if (dir === null) return null;
    const skillFile: string = dir + '/SKILL.md';
    if (!this.deps.port.exists(skillFile)) return null;
    return skillExtractBody(this.readFileText(skillFile));
  }

  // :95-99
  readSkillContent(skillName: string): string | null {
    const dir: string | null = this.resolveSkillDir(skillName);
    if (dir === null) return null;
    const skillFile: string = dir + '/SKILL.md';
    if (!this.deps.port.exists(skillFile)) return null;
    return this.readFileText(skillFile);
  }

  // :101-108
  saveSkill(name: string, content: string): SkillMetadata | null {
    const skillDir: string | null = this.resolveSkillDir(name);
    if (skillDir === null) return null;
    this.deps.port.mkdirs(skillDir);
    const skillFile: string = skillDir + '/SKILL.md';
    this.deps.port.writeText(skillFile, skillEnsureDescription(content, name));
    this.invalidateSkillCache();
    return this.parseSkillFile(skillFile, skillDir);
  }

  // :110-128
  deleteSkill(name: string): boolean {
    const skillDir: string | null = this.resolveSkillDir(name);
    if (skillDir === null) return false;
    const deleted: boolean = this.deps.port.deleteRecursively(skillDir);
    if (deleted) {
      this.invalidateSkillCache();
      if (this.deps.onSkillDeleted !== undefined) {
        this.deps.onSkillDeleted(name);
      }
    }
    return deleted;
  }

  // :130
  getSkillDir(skillName: string): string | null {
    return this.resolveSkillDir(skillName);
  }

  // :132-139
  saveSkillFile(skillName: string, relativePath: string, content: string): boolean {
    const skillDir: string | null = this.resolveSkillDir(skillName);
    if (skillDir === null) return false;
    const target: string | null = resolveSkillFilePath(skillDir, relativePath);
    if (target === null) return false;
    const parent: string = target.substring(0, target.lastIndexOf('/'));
    this.deps.port.mkdirs(parent);
    this.deps.port.writeText(target, content);
    this.invalidateSkillCache();
    return true;
  }

  // :141-188 — staging/backup rename 原子落盘
  saveSkillFilesAtomically(skillName: string, files: Map<string, string>): boolean {
    const skillsDir: string = this.getSkillsDir();
    const targetDir: string | null = this.resolveSkillDir(skillName);
    if (targetDir === null) return false;
    const stagingDir: string | null = this.createTempSkillDir(skillsDir, skillName, 'staging');
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
        this.deps.port.mkdirs(parent);
        this.deps.port.writeText(target,
          relativePath === 'SKILL.md' ? skillEnsureDescription(content, skillName) : content);
      });

      if (!this.deps.port.exists(stagingDir + '/SKILL.md')) return false;

      if (this.deps.port.exists(targetDir)) {
        backupDir = this.createTempSkillDir(skillsDir, skillName, 'backup');
        if (backupDir === null) return false;
        if (!this.deps.port.renameTo(targetDir, backupDir)) return false;
      }

      if (!this.deps.port.renameTo(stagingDir, targetDir)) {
        if (backupDir !== null && !this.deps.port.exists(targetDir)) {
          this.deps.port.renameTo(backupDir, targetDir);
        }
        return false;
      }

      if (backupDir !== null) {
        this.deps.port.deleteRecursively(backupDir);
      }
      this.invalidateSkillCache();
      return true;
    } catch (_e) {
      if (backupDir !== null && !this.deps.port.exists(targetDir)) {
        this.deps.port.renameTo(backupDir, targetDir);
      }
      return false;
    } finally {
      if (this.deps.port.exists(stagingDir)) {
        this.deps.port.deleteRecursively(stagingDir);
      }
      if (backupDir !== null && this.deps.port.exists(backupDir)
        && this.deps.port.exists(targetDir)) {
        this.deps.port.deleteRecursively(backupDir);
      }
    }
  }

  // :190-196
  deleteSkillFile(skillName: string, relativePath: string): boolean {
    const skillDir: string | null = this.resolveSkillDir(skillName);
    if (skillDir === null) return false;
    const target: string | null = resolveSkillFilePath(skillDir, relativePath);
    if (target === null) return false;
    const deleted: boolean = this.deps.port.deleteRecursively(target);
    if (deleted) this.invalidateSkillCache();
    return deleted;
  }

  // :198-201
  resolveSkillFile(skillName: string, relativePath: string): string | null {
    const skillDir: string | null = this.resolveSkillDir(skillName);
    if (skillDir === null) return null;
    return resolveSkillFilePath(skillDir, relativePath);
  }

  // :203-224
  repairMissingDescriptions(): number {
    const skillsDir: string = this.getSkillsDir();
    let repaired: number = 0;
    this.deps.port.listDir(skillsDir)
      .filter((e: SkillDirEntry): boolean => e.isDirectory)
      .forEach((e: SkillDirEntry): void => {
        const dir: string = skillsDir + '/' + e.name;
        const skillFile: string = dir + '/SKILL.md';
        if (!this.deps.port.exists(skillFile)) return;
        try {
          const original: string = this.readFileText(skillFile);
          const updated: string = skillEnsureDescription(original, e.name);
          if (updated !== original) {
            this.deps.port.writeText(skillFile, updated);
            repaired++;
          }
        } catch (_e) {
          // Log.w 路径(域层无日志;修复失败跳过该目录,与 Android runCatching 同)
        }
      });
    if (repaired > 0) this.invalidateSkillCache();
    return repaired;
  }

  // mcp_import_from_skill/buildSkillArray 用:任意路径存在性 + 文本读
  fileExists(path: string): boolean {
    return this.deps.port.exists(path);
  }

  readFileText(path: string): string {
    return utf8Decode(this.deps.port.readBytes(path));
  }

  // :226-228
  private invalidateSkillCache(): void {
    this.cachedSkills = null;
  }

  // :230-232
  private resolveSkillDir(skillName: string): string | null {
    return resolveSkillDirPath(this.getSkillsDir(), skillName);
  }

  // :234-242 — .{name}.{suffix}.{attempt}.tmp,repeat(100)
  private createTempSkillDir(skillsRoot: string, skillName: string, suffix: string): string | null {
    for (let attempt: number = 0; attempt < 100; attempt++) {
      const candidate: string = `${skillsRoot}/.${skillName}.${suffix}.${attempt}.tmp`;
      if (!this.deps.port.exists(candidate) && this.deps.port.mkdirs(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  // :265-282
  private parseSkillFile(skillFile: string, skillDir: string): SkillMetadata | null {
    try {
      const content: string = this.readFileText(skillFile);
      const frontmatter: Record<string, string> = skillParseFrontmatter(content);
      const name: string | undefined = frontmatter['name'];
      if (name === undefined || name.trim().length === 0) return null;
      const description: string = skillResolveDescription(content, frontmatter, name);
      const rawAllowed: string | undefined = frontmatter['allowed-tools'];
      const allowedTools: string[] = rawAllowed !== undefined
        ? rawAllowed.split(' ').filter((t: string): boolean => t.trim().length > 0)
        : [];
      return makeSkillMetadata(
        name, description, skillDir,
        frontmatter['compatibility'] ?? null, allowedTools);
    } catch (_e) {
      return null;
    }
  }
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

// ===== 移动运行时包装(SkillsTools.kt:155-194 逐字;GuizangHtmlDeckValidator.kt:18-24) =====

const GUIZANG_RENDERER: string = 'full_html';
const GUIZANG_LOCAL_MOTION_URL: string = 'https://amberagent.local/full-html/motion.min.js';
const GUIZANG_LOCAL_LUCIDE_URL: string = 'https://amberagent.local/full-html/lucide.min.js';

export const buildSkillMobileRuntimePrompt = (
  skillName: string, filePath: string | null, body: string,
): string => {
  const pathLabel: string =
    (filePath !== null && filePath.trim().length > 0) ? filePath : 'SKILL.md';
  let out: string = '';
  const line = (s: string): void => {
    out += s + '\n';
  };
  line('[AmberAgent Mobile Runtime — applies to the skill content below]');
  line('You are running inside AmberAgent on a HarmonyOS phone/tablet — NOT desktop Claude Code, NOT Codex, NOT a CLI environment.');
  line('These mobile constraints OVERRIDE any conflicting instruction in the skill body:');
  line('- The user has no physical keyboard, no mouse, no system shell. Never write "press ← →", "F for fullscreen", "S for speaker mode", "Ctrl+C to quit", or any keyboard/mouse hint into your visible reply or into widget content.');
  line('- There is no system browser to open .html / .pdf / .pptx files for preview. Visual previews should render inside the chat as show-widget blocks (SVG, HTML, vchart, slides) so they appear as cards in the conversation timeline.');
  line(`- For multi-page presentations / decks / PPT / 幻灯片 / 演示文稿, emit one final show-widget deck preview only after the deck HTML is complete. Use renderer "${GUIZANG_RENDERER}" with the complete static deck HTML in spec.html. During generation, use short progress text or a tiny SVG widget_code cover/status; never emit partial spec.html. Do NOT generate a .pptx file as the only deliverable; do NOT pack a multi-page deck into a single SVG grid; do NOT turn PPT requests into MiniApps.`);
  line('- HarmonyOS widget previews are static and offline: JavaScript, external resources, native bridges and automatic actions do not run. Use inline HTML/CSS and SVG; every full_html slide must remain visible without scripts, with no canvas/WebGL or touch-handler dependency. Native actions only fill the input draft after a user tap.');
  line('- Structured vchart previews support one ungrouped dataset with numeric yField and unique xField labels only; seriesField, stacking and percent/group normalization are unsupported. For grouped charts, produce the correct static SVG directly instead of an unsupported vchart spec.');
  line('- Use the available execution runtime accurately: terminal tools run on the approved Remote SSH host; python_execute uses embedded CPython for local text/data processing. Do not assume Alpine, package installation, direct Python file access, or automatic Workspace synchronization.');
  line('- File outputs go to /workspace via file_write; users browse them through the in-app file sheet, not through Finder/Explorer.');
  line('- If the skill describes a desktop-only workflow, translate it into the mobile equivalent: replace "open in PowerPoint" with "emit a show-widget deck preview", "open in browser" with "emit the appropriate show-widget renderer", etc.');
  line('- IMPORTANT about use_skill paths: many skills installed via download only ship the SKILL.md file — the references/, scripts/, assets/ subfolders mentioned in the SKILL.md links may NOT exist locally. Do NOT chain a second use_skill(path=...) call just because SKILL.md links to it; treat SKILL.md as self-contained instructions and only retry with a path if a previous call confirms that file exists.');
  if (skillName.toLowerCase().indexOf('guizang-ppt') >= 0) {
    line(`- guizang-ppt-skill SPECIAL MOBILE ADAPTER: the default and preferred final output is a complete \`show-widget\` using \`renderer:"${GUIZANG_RENDERER}"\`, not renderer:"slides", not widget_code HTML, not a MiniApp, and not a standalone saved HTML page. Give the user an inline PPT preview card in the chat after the HTML is complete; while generating, show only concise progress or a tiny SVG cover/status.`);
    line('- Required full_html skeleton: `spec.html` contains one `<div id="deck">` wrapper and 6-10 `<section class="slide ..." data-animate="...">...</section>` pages copied from/adapted to the guizang template style. `widget_code` is only a tiny static SVG cover.');
    line(`- HarmonyOS does NOT load runtime scripts, including Lucide at ${GUIZANG_LOCAL_LUCIDE_URL} or Motion at ${GUIZANG_LOCAL_MOTION_URL}; these are not available to its static preview. Use inline SVG icons and CSS/SVG visuals, never script imports or CDN assets.`);
    line('- Keep the skill\'s visual style in static HTML/CSS: magazine/Swiss typography, grid layout and inline SVG backgrounds/icons. Do not depend on canvas/WebGL, Motion, Lucide initialization or swipe JavaScript. Pages are browsed by vertical scrolling. Do NOT add bridge calls, popups, downloads, file/content/intent/android_asset URLs, flat non-deck pages, or keyboard-only instructions.');
  }
  if (skillName.toLowerCase().indexOf('guizang-social-card') >= 0) {
    line(`- guizang-social-card-skill SPECIAL MOBILE ADAPTER: default chat preview output is one complete \`show-widget\` using \`renderer:"${GUIZANG_RENDERER}"\`. Do not output raw HTML as ordinary Markdown/code, a MiniApp, or a standalone saved HTML page as the only deliverable.`);
    line('- Required social-card full_html skeleton: `spec.html` contains one `<div id="deck">` wrapper. Each card is one deck page: `<section class="slide social-card poster xhs">...</section>`, `<section class="slide social-card poster wide">...</section>`, or `<section class="slide social-card poster square">...</section>`. A single social card is a one-slide deck; a carousel is a multi-slide deck.');
    line('- `widget_code` is only a tiny static SVG cover/status thumbnail. Keep progress visible with concise text or the tiny cover; emit the full_html show-widget only after `spec.html` is complete, and never emit a partial or truncated `spec.html`.');
    line('- Preview and export are separate: for normal "show me / preview / make a card" requests, render the inline full_html preview in chat. Only create /workspace files, PNG/JPG exports, or screenshot workflows when the user explicitly asks to export, save, share, or download image files.');
    line('- Social-card HTML must stay mobile-native: inline CSS, touch-friendly sizing, no external CSS/CDN/runtime scripts, no iframe/form/nav/download/popups, no file/content/intent/android_asset URLs, and no desktop/browser/keyboard instructions.');
  }
  line('');
  line(`Skill: ${skillName}  (${pathLabel})`);
  line('--- skill content begins ---');
  out += body;
  if (!body.endsWith('\n')) out += '\n';
  line('--- skill content ends ---');
  line('');
  line('Reminder: the mobile constraints above take priority. If the skill says "open in a browser" or "add keyboard shortcuts", you ignore that part and use the AmberAgent-native equivalent.');
  return out;
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
export const collectSkillFilesFromDirectory = (
  manager: SkillManager, dir: string | null,
): Map<string, string> => {
  const files: Map<string, string> = new Map<string, string>();
  if (dir === null) return files;
  const port: SkillFilePort = managerPort(manager);
  if (!port.exists(dir) || !port.isDirectory(dir)) return files;
  port.walkFiles(dir).forEach((relative: string): void => {
    const name: string = canonicalSkillFileName(relative);
    if (name.trim().length === 0 || name.indexOf('..') >= 0 || !isLikelyTextSkillFile(name)) {
      return;
    }
    files.set(name, readCappedText(port, dir + '/' + relative, name));
  });
  return files;
};

// SkillManager 私有端口访问(域内同模块,避免暴露 public)
const managerPort = (manager: SkillManager): SkillFilePort =>
  (manager as unknown as { deps: SkillManagerDeps }).deps.port;

// readBytesWithinLimit(:421-433):> 4MB 抛(文案逐字,"Skill archive entry"
//   对普通文件同用 = Android quirk 保留)
const readCappedText = (port: SkillFilePort, path: string, entryName: string): string => {
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

// :196-204 — 缺失文件提示用短清单(take(20).joinToString(", "))
const listSkillFilesShort = (manager: SkillManager, name: string, max: number = 20): string => {
  const dir: string | null = manager.getSkillDir(name);
  if (dir === null) return '';
  const port: SkillFilePort = managerPort(manager);
  if (!port.exists(dir) || !port.isDirectory(dir)) return '';
  return port.walkFiles(dir).slice(0, max).join(', ');
};

// ===== kotlinx 取值语义(jsonPrimitive.content / contentOrNull) =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

// .jsonPrimitive.content:缺 → null;primitive → 串;object/array → 抛
const jsonContent = (v: JsonValue | undefined): string | null => {
  if (v === undefined || v === null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  throw new Error('Element is not a JsonPrimitive');
};

// .jsonPrimitive.contentOrNull:缺/JsonNull → null;primitive → 串;object/array → null
const jsonContentOrNull = (v: JsonValue | undefined): string | null => {
  if (v === undefined || v === null) return null;
  if (typeof v === 'string') return v;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  return null;
};

// ===== createSkillTools(SkillsTools.kt:24-153) =====

export interface CreateSkillToolsDeps {
  // assistant.enabledSkills(:25;createRunTools 时快照)
  enabledSkills: string[];
  // skillManager.listSkills()(:41;createRunTools 时快照)
  allSkills: SkillMetadata[];
  skillManager: SkillManager;
  // updateEnabledSkill(:339-350)→ 当前 assistant enabledSkills RMW(entry 闭包)
  setSkillEnabled: (name: string, enable: boolean) => Promise<void>;
  workspace: SkillWorkspacePort;
  inflateRaw: InflateRawPort;
}

// :321-337 — buildSkillArray(contains_mcp_config = mcp.json 存在性)
const buildSkillArray = (manager: SkillManager, skills: SkillMetadata[]): JsonObject[] =>
  skills.map((skill: SkillMetadata): JsonObject => {
    const obj: JsonObject = {
      name: skill.name,
      description: skill.description,
    };
    if (skill.compatibility !== null && skill.compatibility.trim().length > 0) {
      obj['compatibility'] = skill.compatibility;
    }
    obj['allowed_tools'] = skill.allowedTools as unknown as JsonValue;
    obj['contains_mcp_config'] = manager.fileExists(skill.skillDir + '/mcp.json');
    return obj;
  });

export const createSkillTools = (deps: CreateSkillToolsDeps): AgentTool[] => {
  const available: SkillMetadata[] =
    deps.allSkills.filter((s: SkillMetadata): boolean => deps.enabledSkills.indexOf(s.name) >= 0);
  const installedNames: string[] = deps.allSkills.map((s: SkillMetadata): string => s.name);
  const missingEnabled: string[] = deps.enabledSkills
    .filter((n: string): boolean => installedNames.indexOf(n) < 0)
    .sort();
  const disabled: SkillMetadata[] =
    deps.allSkills.filter((s: SkillMetadata): boolean => deps.enabledSkills.indexOf(s.name) < 0);

  const tools: AgentTool[] = [];

  // :37-83 — skills_list
  tools.push(makeAgentTool({
    name: 'skills_list',
    description: 'List AmberAgent skills and their load status. Use this first when you are ' +
      'unsure which skills are installed, enabled, disabled, or missing.',
    parameters: (): InputSchemaObj => ({ type: 'object', properties: {}, required: null }),
    systemPrompt: (): string => {
      let out: string = '';
      const line = (s: string): void => {
        out += s + '\n';
      };
      line('**Skill library status**');
      line(`Installed skills: ${deps.allSkills.length}. Enabled skills: ${available.length}.`);
      line('If you are unsure which skill is available, call `skills_list` before choosing `use_skill`.');
      if (available.length > 0) {
        line('<available_skills>');
        available.forEach((skill: SkillMetadata): void => {
          line('  <skill>');
          line(`    <name>${skill.name}</name>`);
          line(`    <description>${skill.description}</description>`);
          line('  </skill>');
        });
        line('</available_skills>');
      }
      if (disabled.length > 0) {
        line('Some installed skills are disabled. `use_skill` can only load enabled skills.');
      }
      if (missingEnabled.length > 0) {
        line('Some configured enabled skills are missing from disk. Call `skills_list` for details.');
      }
      return out;
    },
    execute: (): Promise<UIMessagePart[]> => {
      const payload: JsonObject = {
        installed_count: deps.allSkills.length,
        enabled_count: available.length,
        configured_enabled_count: deps.enabledSkills.length,
        available_skills: buildSkillArray(deps.skillManager, available) as unknown as JsonValue,
        disabled_installed_skills: buildSkillArray(deps.skillManager, disabled) as unknown as JsonValue,
        missing_enabled_skills: missingEnabled as unknown as JsonValue,
      };
      return Promise.resolve([{ type: 'text', text: JSON.stringify(payload), metadata: null }]);
    },
  }));

  // :206-248 — skill_validate
  tools.push(makeAgentTool({
    name: 'skill_validate',
    description: 'Validate an installed skill by name or a /workspace skill folder/zip before import.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Installed skill name.' },
        workspace_path: {
          type: 'string',
          description: 'Workspace skill folder, SKILL.md, or zip archive.',
        },
      },
      required: null,
    }),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const name: string | null = jsonContentOrNull(obj['name']);
      const workspacePath: string | null = jsonContentOrNull(obj['workspace_path']);
      let files: Map<string, string>;
      if (name !== null && name.trim().length > 0) {
        files = collectSkillFilesFromDirectory(deps.skillManager,
          deps.skillManager.getSkillDir(name));
      } else if (workspacePath !== null && workspacePath.trim().length > 0) {
        files = await collectWorkspaceSkillFiles(deps.workspace, workspacePath, deps.inflateRaw);
      } else {
        throw new Error('name or workspace_path is required');
      }
      const skillMd: string = files.get('SKILL.md') ?? '';
      const frontmatter: Record<string, string> = skillParseFrontmatter(skillMd);
      const issues: string[] = [];
      if (skillMd.trim().length === 0) issues.push('缺少 SKILL.md');
      const fmName: string | undefined = frontmatter['name'];
      if (fmName === undefined || fmName.trim().length === 0) issues.push('SKILL.md 缺少 name');
      const fmDesc: string | undefined = frontmatter['description'];
      if (fmDesc === undefined || fmDesc.trim().length === 0) issues.push('SKILL.md 缺少 description');
      const payload: JsonObject = {
        valid: issues.length === 0,
        name: fmName ?? '',
        description: fmDesc ?? '',
        file_count: files.size,
        contains_mcp_config: files.has('mcp.json'),
        issues: issues as unknown as JsonValue,
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }));

  // :250-288 — skill_import(needsApproval)
  tools.push(makeAgentTool({
    name: 'skill_import',
    description: 'Import a skill folder, SKILL.md file, or zip archive from /workspace. ' +
      'Imported skills are enabled by default.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        workspace_path: {
          type: 'string',
          description: 'Workspace path to a skill folder, SKILL.md, or zip archive.',
        },
      },
      required: ['workspace_path'],
    }),
    needsApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const workspacePath: string | null = jsonContentOrNull(obj['workspace_path']);
      if (workspacePath === null) throw new Error('workspace_path is required');
      const files: Map<string, string> =
        await collectWorkspaceSkillFiles(deps.workspace, workspacePath, deps.inflateRaw);
      const skillMd: string | undefined = files.get('SKILL.md');
      if (skillMd === undefined) throw new Error('Skill package does not contain SKILL.md');
      const frontmatter: Record<string, string> = skillParseFrontmatter(skillMd);
      const fmName: string | undefined = frontmatter['name'];
      if (fmName === undefined || fmName.trim().length === 0) {
        throw new Error('SKILL.md missing name');
      }
      const name: string = fmName;
      const fmDesc: string | undefined = frontmatter['description'];
      if (fmDesc === undefined || fmDesc.trim().length === 0) {
        throw new Error('SKILL.md missing description');
      }
      const saved: boolean = deps.skillManager.saveSkillFilesAtomically(name, files);
      if (!saved) throw new Error('Failed to save skill files');
      await deps.setSkillEnabled(name, true);
      const payload: JsonObject = {
        success: true,
        name: name,
        file_count: files.size,
        enabled: true,
        contains_mcp_config: files.has('mcp.json'),
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  }));

  // :290-319 — skill_enable / skill_disable(needsApproval)
  const makeEnableTool = (enable: boolean): AgentTool => makeAgentTool({
    name: enable ? 'skill_enable' : 'skill_disable',
    description: enable
      ? 'Enable an installed skill for the default AmberAgent.'
      : 'Disable an installed skill for the default AmberAgent.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        name: { type: 'string', description: 'Skill name.' },
      },
      required: ['name'],
    }),
    needsApproval: true,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const name: string | null = jsonContentOrNull(obj['name']);
      if (name === null) throw new Error('name is required');
      await deps.setSkillEnabled(name, enable);
      const payload: JsonObject = {
        success: true,
        name: name,
        enabled: enable,
      };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    },
  });
  tools.push(makeEnableTool(true));
  tools.push(makeEnableTool(false));

  // :90 — available 空 → 不出 use_skill
  if (available.length === 0) return tools;

  // :92-151 — use_skill
  tools.push(makeAgentTool({
    name: 'use_skill',
    description: 'Load and apply a skill to get specialized instructions or capabilities.\n' +
      'Call this tool when the user\'s request matches one of the available skills.',
    parameters: (): InputSchemaObj => ({
      type: 'object',
      properties: {
        name: { type: 'string', description: 'The name of the skill to use' },
        path: {
          type: 'string',
          description: 'Optional relative path to a file inside the skill directory. ' +
            'Omit to read the default SKILL.md instructions. Only use paths extracted from ' +
            'Markdown links in the SKILL.md content. Do NOT guess or infer paths.',
        },
      },
      required: ['name'],
    }),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj: JsonObject = isObj(input) ? input : {};
      const nameRaw: string | null = jsonContent(obj['name']);
      if (nameRaw === null) throw new Error('name is required');
      const name: string = nameRaw;
      if (deps.enabledSkills.indexOf(name) < 0) {
        throw new Error(
          `Skill '${name}' is not enabled. Call skills_list to see installed and enabled skills.`);
      }
      const path: string | null = jsonContentOrNull(obj['path']);
      const port: SkillFilePort = managerPort(deps.skillManager);
      let content: string;
      if (path === null || path.trim().length === 0) {
        const skillFile: string | null = deps.skillManager.resolveSkillFile(name, 'SKILL.md');
        if (skillFile === null) throw new Error(`Skill '${name}' not found`);
        if (!port.exists(skillFile)) throw new Error(`Skill '${name}' not found`);
        const skillMd: string = readCappedText(port, skillFile, 'SKILL.md');
        content = skillExtractBody(skillMd);
      } else {
        const target: string | null = deps.skillManager.resolveSkillFile(name, path);
        if (target === null) {
          throw new Error(`Path '${path}' is outside the skill directory`);
        }
        if (!port.exists(target)) {
          const availableFiles: string = listSkillFilesShort(deps.skillManager, name);
          const hint: string = availableFiles.trim().length > 0
            ? `This skill only ships with these files: ${availableFiles}. Do not retry with ` +
              'other paths from SKILL.md links — those reference files were not bundled. ' +
              'Re-read SKILL.md (omit the path argument) and follow its inline instructions directly.'
            : 'This skill ships with only SKILL.md. Re-read it (omit the path argument) and ' +
              'follow its inline instructions without fetching sub-files.';
          throw new Error(`File '${path}' not found in skill '${name}'. ${hint}`);
        }
        const lastSlash: number = target.lastIndexOf('/');
        const targetName: string = lastSlash < 0 ? target : target.substring(lastSlash + 1);
        content = readCappedText(port, target, targetName);
      }
      return Promise.resolve([{
        type: 'text',
        text: buildSkillMobileRuntimePrompt(name, path, content),
        metadata: null,
      }]);
    },
  }));
  return tools;
};
