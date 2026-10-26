// The installed skill library owns its one catalog cache and mutation lifecycle.
import type { SkillMetadata, SkillScanIssue } from './skill_metadata.ts';
import { makeSkillMetadata, skillParseFrontmatter, skillEnsureDescription,
  skillResolveDescription, skillIsPlaceholderDescription, skillExtractBody } from './skill_metadata.ts';
import type { SkillFilePort, BuiltinSkillAssetPort, SkillDirEntry } from './skill_package.ts';
import { resolveSkillDirPath, resolveSkillFilePath, collectSkillFilesAtDirectory,
  readCappedSkillText, replaceSkillPackageAtomically } from './skill_package.ts';
import { utf8Decode } from './zip_archive.ts';

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

  // Replace the package before publishing a fresh catalog on the next read.
  saveSkillFilesAtomically(skillName: string, files: Map<string, string>): boolean {
    const skillsDir: string = this.getSkillsDir();
    const targetDir: string | null = this.resolveSkillDir(skillName);
    if (targetDir === null) return false;
    return replaceSkillPackageAtomically(
      this.deps.port, skillsDir, targetDir, skillName, files,
      (): void => { this.invalidateSkillCache(); });
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

  // Validation/export reads a package without handing file-system ownership to tools.
  collectFilesFromDirectory(dir: string | null): Map<string, string> {
    return collectSkillFilesAtDirectory(this.deps.port, dir);
  }

  // Reading an installed instruction/reference file shares the same path and size
  // contract as package import. Missing/invalid paths remain distinguishable by
  // the caller's resolveSkillFile check when constructing the tool error.
  readSkillFileText(skillName: string, relativePath: string): string | null {
    const target: string | null = this.resolveSkillFile(skillName, relativePath);
    if (target === null || !this.deps.port.exists(target)) return null;
    const lastSlash: number = target.lastIndexOf('/');
    const fileName: string = lastSlash < 0 ? target : target.substring(lastSlash + 1);
    return readCappedSkillText(this.deps.port, target, fileName);
  }

  listSkillFiles(skillName: string, max: number = 20): string[] {
    const dir: string | null = this.getSkillDir(skillName);
    if (dir === null || !this.deps.port.exists(dir) || !this.deps.port.isDirectory(dir)) return [];
    return this.deps.port.walkFiles(dir).slice(0, max);
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

