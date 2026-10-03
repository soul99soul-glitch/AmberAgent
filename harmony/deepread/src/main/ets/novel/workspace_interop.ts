import { parseNovelStructuredState, NOVEL_STATE_PROTOCOL_VERSION } from './structured_state.ts';
import { makeNovelChapterContract, chapterContractMarkdown, confirmedChapterPlanText, withNovelUpcomingArc } from './chapter_contract.ts';
import type { NovelChapterContractInput } from './chapter_contract.ts';
import { normalizeNovelMaterialFields, optionalNovelMaterialFields } from './material_fields.ts';
import type { NovelMaterialFields, NovelMaterialInjectionMode } from './models.ts';
// Public Markdown dialects -> real branch snapshots. Original bytes remain the passthrough carrier.
import type { NovelWorkspaceManifest } from './workspace_contract.ts';
import { parseNovelWorkspaceMapping, serializeNovelWorkspacePublicManifest, validateNovelWorkspacePath, chapterFileName } from './workspace_contract.ts';
import type { NovelWorkspaceArchiveFile } from './workspace_exchange.ts';
import { decodeNovelWorkspaceUtf8 } from './workspace_exchange.ts';
import type { NovelProject, NovelChapter, NovelMaterial, NovelMaterialKind, NovelBranch, NovelSettingProposal } from './models.ts';
import { makeNovelProject, novelChapterOrdinal } from './models.ts';
import { rebuildChapterPlots, chapterPlotSourceDigest, firstStaleChapterOrdinal } from './plot_projection.ts';
import { invalidInput } from './error.ts';

export interface NovelWorkspaceBranchImport {
  id: string;
  pathName: string;
  name: string;
  project: NovelProject;
  plotContent: string | null;
  plotStale: boolean;
  unresolvedFromChapterOrdinal: number | null;
}
export interface NovelWorkspaceImportPlan {
  manifest: NovelWorkspaceManifest;
  activeBranchId: string;
  branches: NovelWorkspaceBranchImport[];
  files: NovelWorkspaceArchiveFile[];
  unsupportedPaths: string[];
}
interface MarkdownDocument { fields: Map<string, string>; body: string; header: string; }
interface OrderedChapter { ordinal: number; chapter: NovelChapter; }

const markdown = (raw: string): MarkdownDocument => {
  const text: string = raw.replace(/\r\n/g, '\n');
  if (!text.startsWith('---\n')) return { fields: new Map(), body: raw, header: '' };
  const end: number = text.indexOf('\n---\n', 4);
  if (end < 0) throw invalidInput('工作区 Markdown frontmatter 未闭合');
  const header: string = text.slice(4, end);
  return { fields: parseNovelWorkspaceMapping(header), body: text.slice(end + 5).replace(/^\n/, ''), header };
};
const textAt = (files: Map<string, NovelWorkspaceArchiveFile>, path: string): MarkdownDocument | null => {
  const file: NovelWorkspaceArchiveFile | undefined = files.get(path);
  return file === undefined ? null : markdown(decodeNovelWorkspaceUtf8(file.bytes));
};
const leafTitle = (path: string): string => path.split('/').pop()!.replace(/\.md$/, '').replace(/^\d+-/, '');
const safeIdentity = (id: string): string => { validateNovelWorkspacePath(id); if (id.includes('/')) throw invalidInput('工作区实体 id 包含路径'); return id; };
const inferredId = (prefix: string, path: string): string => `${prefix}-${chapterPlotSourceDigest(path)}`;
const materialKind = (path: string, explicit?: string): NovelMaterialKind | null => {
  const value: string = explicit ?? path.split('/')[path.split('/').length - 2];
  if (value === 'world') return 'world';
  if (value === 'character' || value === 'characters') return 'character';
  if (value === 'masterOutline' || value === 'outline') return 'outline';
  if (value === 'writingRequirements' || value === 'writing' || value === 'requirement') return 'requirement';
  if (value === 'relationship' || value === 'relationships') return 'relationship';
  if (['custom', 'other', 'decisionLog', 'log'].includes(value)) return 'other';
  return null;
};
const materialFields = (doc: MarkdownDocument, enabled: boolean = true): NovelMaterialFields => {
  const list = (key: string): string[] => {
    const inline: string | undefined = doc.fields.get(key);
    if (inline?.startsWith('[')) {
      const values: string[] = JSON.parse(inline) as string[];
      return values;
    }
    const values: string[] = [];
    let selected: boolean = false;
    for (const line of doc.header.split('\n')) {
      if (!line.startsWith(' ')) { selected = line.trim() === `${key}:`; continue; }
      if (!selected || !line.trim().startsWith('- ')) continue;
      const value: string = line.trim().slice(2).trim();
      values.push(value.startsWith('"') ? JSON.parse(value) as string :
        value.startsWith("'") && value.endsWith("'") ? value.slice(1, -1).replace(/''/g, "'") : value);
    }
    return values;
  };
  const rawMode: string | undefined = doc.fields.get('injection') ?? doc.fields.get('injectionMode');
  const injectionMode: NovelMaterialInjectionMode = rawMode === 'smart' ? 'smart' :
    rawMode === 'off' || rawMode === 'never' || !enabled ? 'off' : 'always';
  return normalizeNovelMaterialFields({ aliases: list('aliases'), tags: list('tags'),
    customKind: doc.fields.get('customName') ?? doc.fields.get('customKind'), injectionMode }, enabled);
};

const proposalFields = (doc: MarkdownDocument): NovelMaterialFields => {
  const normalized = materialFields(doc);
  return optionalNovelMaterialFields({
    aliases: /^aliases\s*:/m.test(doc.header) ? normalized.aliases : undefined,
    tags: /^tags\s*:/m.test(doc.header) ? normalized.tags : undefined,
    customKind: doc.fields.has('customName') || doc.fields.has('customKind') ? normalized.customKind : undefined,
    injectionMode: doc.fields.has('injection') || doc.fields.has('injectionMode') ? normalized.injectionMode : undefined,
  });
};

const material = (path: string, doc: MarkdownDocument, manifest: NovelWorkspaceManifest): NovelMaterial | null => {
  const kind: NovelMaterialKind | null = materialKind(path, doc.fields.get('materialKind'));
  if (kind === null) return null;
  const fields = materialFields(doc, doc.fields.get('enabled') !== 'false');
  return { id: safeIdentity(doc.fields.get('id') ?? path.split('/').pop()!.slice(0, -3)), kind,
    title: doc.fields.get('title') ?? leafTitle(path), content: doc.body,
    ...fields, enabled: fields.injectionMode !== 'off',
    createdAt: manifest.createdAt, updatedAt: manifest.updatedAt };
};
const assertUnique = (ids: string[], label: string): void => {
  const seen: Set<string> = new Set();
  for (const id of ids) { if (seen.has(id)) throw invalidInput(`工作区 ${label} 重复: ${id}`); seen.add(id); }
};

export const buildNovelWorkspaceImportPlan = (manifest: NovelWorkspaceManifest,
  sourceFiles: NovelWorkspaceArchiveFile[], importedAt: number = Date.now()): NovelWorkspaceImportPlan => {
  const sourceManifest: NovelWorkspaceArchiveFile | undefined = sourceFiles.find((f): boolean => f.path === 'manifest.yaml');
  const sourceExportedAt: string | undefined = sourceManifest === undefined || manifest.dialect !== 'ios' ? undefined
    : parseNovelWorkspaceMapping(decodeNovelWorkspaceUtf8(sourceManifest.bytes)).get('exportedAt');
  const exportedTime: number = sourceExportedAt === undefined ? NaN : Date.parse(sourceExportedAt);
  const referenceTime: number = Number.isFinite(exportedTime) && exportedTime > 0 ? exportedTime : importedAt;
  if (!Number.isSafeInteger(referenceTime) || referenceTime < 0) throw invalidInput('导入参考时间无效');
  // iOS does not export original creation dates; this is the export/import snapshot reference time.
  manifest = { ...manifest, createdAt: manifest.createdAt || referenceTime, updatedAt: manifest.updatedAt || referenceTime };
  const files: Map<string, NovelWorkspaceArchiveFile> = new Map();
  const used: Set<string> = new Set(['manifest.yaml', 'project.md']);
  for (const file of sourceFiles) {
    validateNovelWorkspacePath(file.path);
    if (files.has(file.path)) throw invalidInput(`工作区路径重复: ${file.path}`);
    files.set(file.path, { path: file.path, bytes: new Uint8Array(file.bytes) });
  }
  const projectDoc: MarkdownDocument | null = textAt(files, 'project.md');
  if (projectDoc === null) throw invalidInput('工作区缺少 project.md');
  if (projectDoc.fields.has('id') && projectDoc.fields.get('id') !== manifest.projectId) throw invalidInput('项目与 manifest 标识冲突');
  const names: Set<string> = new Set();
  for (const path of files.keys()) if (path.startsWith('branches/')) { const pieces: string[] = path.split('/'); if (pieces.length >= 3) names.add(pieces[1]); }
  if (names.size === 0 && manifest.dialect !== 'ios') names.add(manifest.activeBranch);
  if (!names.has(manifest.activeBranch)) throw invalidInput('工作区缺少当前分支');
  const mainPath: string = manifest.mainBranch ?? manifest.activeBranch;
  if (!names.has(mainPath)) throw invalidInput('工作区缺少主分支');
  const descriptors: NovelBranch[] = [];
  const branchPaths: Map<string, string> = new Map();
  for (const pathName of names) {
    const path: string = `branches/${pathName}/branch.md`;
    const doc: MarkdownDocument | null = textAt(files, path);
    if (doc !== null) used.add(path);
    const id: string = safeIdentity(doc?.fields.get('id') ?? pathName);
    descriptors.push({ id, name: doc?.fields.get('title') ?? pathName, lifecycle: 'active',
      isMain: pathName === mainPath, forkFromChapterId: null, createdAt: manifest.createdAt });
    branchPaths.set(pathName, id);
  }
  assertUnique(descriptors.map((b: NovelBranch): string => b.id), '分支 id');
  const globalMaterials: NovelMaterial[] = [];
  for (const path of files.keys()) {
    if (!/^setting\/[^/]+\/[^/]+\.md$/.test(path)) continue;
    const item: NovelMaterial | null = material(path, textAt(files, path)!, manifest);
    if (item !== null) { globalMaterials.push(item); used.add(path); }
  }
  assertUnique(globalMaterials.map((m: NovelMaterial): string => m.id), '全局资料 id');
  const globalProposals: NovelSettingProposal[] = [];
  for (const path of files.keys()) {
    if (!/^inbox\/[^/]+\.md$/.test(path)) continue;
    const doc: MarkdownDocument = textAt(files, path)!;
    const kind: NovelMaterialKind | null = materialKind(path, doc.fields.get('materialKind') ?? 'custom');
    if (kind === null) continue;
    globalProposals.push({ id: safeIdentity(doc.fields.get('id') ?? inferredId('inbox', path)), sourceMessageId: '',
      kind, ...proposalFields(doc), title: doc.fields.get('title') ?? leafTitle(path), content: doc.body, status: 'pending',
      createdAt: manifest.createdAt, resolvedAt: null }); used.add(path);
  }
  assertUnique(globalProposals.map((p: NovelSettingProposal): string => p.id), 'inbox id');
  const branches: NovelWorkspaceBranchImport[] = [];
  for (const pathName of names) {
    const prefix: string = `branches/${pathName}/`;
    const branch: NovelBranch = descriptors.find((b: NovelBranch): boolean => b.id === branchPaths.get(pathName))!;
    const project: NovelProject = makeNovelProject({ id: manifest.projectId,
      name: projectDoc.fields.get('title') ?? manifest.title, now: manifest.createdAt });
    project.updatedAt = manifest.updatedAt; project.branches = descriptors.map((b: NovelBranch): NovelBranch => ({ ...b }));
    project.baseMaterials = globalMaterials.map((m: NovelMaterial): NovelMaterial => ({ ...m }));
    project.materialOverrides = [];
    project.hiddenMaterialIds = [];
    project.polishPreference = projectDoc.fields.get('polishPreference') ?? '';
    const stateReasoning = projectDoc.fields.get('stateSyncReasoningEnabled');
    if (stateReasoning !== undefined && stateReasoning !== 'true' && stateReasoning !== 'false') throw invalidInput('状态同步思考开关无效');
    project.stateSyncReasoningEnabled = stateReasoning === 'true';
    project.materials = globalMaterials.map((m: NovelMaterial): NovelMaterial => ({ ...m }));
    const branchMetadata: MarkdownDocument | null = textAt(files, prefix + 'branch.md');
    project.settingProposals = branchMetadata?.fields.get('harmonyInboxComplete') === 'true' ? []
      : globalProposals.map((p: NovelSettingProposal): NovelSettingProposal => ({ ...p }));
    const ordered: OrderedChapter[] = [];
    const overrides: Set<string> = new Set();
    for (const path of files.keys()) {
      if (!path.startsWith(prefix)) continue;
      const relative: string = path.slice(prefix.length);
      if (/^(chapters|discarded)\/[^/]+\.md$/.test(relative)) {
        const doc: MarkdownDocument = textAt(files, path)!;
        const discarded: boolean = relative.startsWith('discarded/');
        const digits: RegExpMatchArray | null = relative.match(/\/(\d+)-/);
        const ordinal: number = Number(doc.fields.get('ordinal') ?? digits?.[1] ?? (discarded ? 100000 + ordered.length : '0'));
        if (!Number.isSafeInteger(ordinal) || ordinal < 1) throw invalidInput(`章节序号无效: ${path}`);
        const fallbackId: string = !discarded && digits !== null ? `chapter-${digits[1].padStart(3, '0')}` : inferredId('discarded', path);
        ordered.push({ ordinal, chapter: { id: safeIdentity(doc.fields.get('id') ?? fallbackId),
          title: doc.fields.get('title') ?? leafTitle(path), content: doc.body, discarded, ordinal: discarded ? undefined : ordinal,
          createdAt: manifest.createdAt, updatedAt: manifest.updatedAt } }); used.add(path);
      } else if (/^inbox\/[^/]+\.md$/.test(relative)) {
        const doc: MarkdownDocument = textAt(files, path)!;
        const kind: NovelMaterialKind | null = materialKind(path, doc.fields.get('materialKind') ?? 'custom');
        if (kind === null) continue;
        const id: string = safeIdentity(doc.fields.get('id') ?? inferredId('inbox', path));
        project.settingProposals = project.settingProposals.filter((p: NovelSettingProposal): boolean => p.id !== id);
        project.settingProposals.push({ id, kind, ...proposalFields(doc), sourceMessageId: '', title: doc.fields.get('title') ?? leafTitle(path),
          content: doc.body, status: 'pending', createdAt: manifest.createdAt, resolvedAt: null }); used.add(path);
      } else if (/^setting\/[^/]+\/[^/]+\.md$/.test(relative)) {
        const doc: MarkdownDocument = textAt(files, path)!;
        if (doc.fields.get('harmonyDeleted') === 'true') {
          const id: string = safeIdentity(doc.fields.get('id') ?? '');
          project.hiddenMaterialIds.push(id);
          project.materials = project.materials.filter((m: NovelMaterial): boolean => m.id !== id); used.add(path); continue;
        }
        const item: NovelMaterial | null = material(path, doc, manifest);
        if (item === null) continue;
        if (overrides.has(item.id)) throw invalidInput(`分支资料 id 重复: ${item.id}`);
        overrides.add(item.id);
        project.materialOverrides.push(item);
        const existing: number = project.materials.findIndex((m: NovelMaterial): boolean => m.id === item.id);
        if (existing >= 0) project.materials[existing] = item; else project.materials.push(item);
        used.add(path);
      }
    }
    ordered.sort((a: OrderedChapter, b: OrderedChapter): number => a.ordinal - b.ordinal);
    assertUnique(ordered.map((c: OrderedChapter): string => c.chapter.id), '章节 id');
    assertUnique(ordered.filter((c: OrderedChapter): boolean => !c.chapter.discarded).map((c: OrderedChapter): string => String(c.ordinal)), '章节序号');
    project.chapters = ordered.map((c: OrderedChapter): NovelChapter => c.chapter);
    const evidencePath = prefix + 'state/evidence.json';
    const evidenceFile = files.get(evidencePath);
    if (evidenceFile !== undefined) {
      let evidence: { protocolVersion?: unknown; state?: unknown };
      try { evidence = JSON.parse(decodeNovelWorkspaceUtf8(evidenceFile.bytes)); } catch { throw invalidInput('结构化状态文件 JSON 无效'); }
      if (evidence === null || evidence.protocolVersion !== NOVEL_STATE_PROTOCOL_VERSION || !Object.prototype.hasOwnProperty.call(evidence, 'state')) throw invalidInput('结构化状态文件协议无效');
      if (evidence.state !== null) project.structuredState = parseNovelStructuredState(JSON.stringify(evidence.state), project.chapters, project.materials);
      used.add(evidencePath);
    }
    const readBody = (relative: string): string | null => { const path: string = prefix + relative; const doc: MarkdownDocument | null = textAt(files, path); if (doc !== null) used.add(path); return doc?.body ?? null; };
    project.branchSettings.thisChapterPlan = readBody('plan/this-chapter.md') ?? '';
    const planDoc: MarkdownDocument | null = textAt(files, prefix + 'plan/this-chapter.md');
    if (planDoc?.fields.get('harmonyRawPlan') === 'bounded-v1') {
      const carrier: string = project.branchSettings.thisChapterPlan.replace(/^## 目标与冲突\n\n/, '');
      const lines: string[] = carrier.trim().split('\n');
      if (lines.shift() !== '<!-- amber:raw-plan:start -->' || lines.pop() !== '<!-- amber:raw-plan:end -->' ||
        lines.some((line: string): boolean => !line.startsWith('  '))) throw invalidInput('原始计划载体格式无效');
      project.branchSettings.thisChapterPlan = lines.map((line: string): string => line.slice(2)).join('\n');
    }
    if (planDoc !== null && (planDoc.fields.get('status') === 'draft' || planDoc.fields.get('status') === 'confirmed' || planDoc.body.trimStart().startsWith('## 位置'))) {
      const parts: Map<string, string> = new Map();
      const pattern = /^## (位置|目标与冲突|必须发生|不可发生|收束|可见事实)\s*$/gm;
      const headings = Array.from(planDoc.body.matchAll(pattern));
      headings.forEach((heading, index) => parts.set(heading[1], planDoc.body.slice((heading.index ?? 0) + heading[0].length,
        index + 1 < headings.length ? headings[index + 1].index : undefined).trim()));
      const bullets = (key: string): string[] => (parts.get(key) ?? '').split('\n')
        .map(line => line.replace(/^\s*[-*]\s+/, '').trim()).filter(Boolean);
      const input: NovelChapterContractInput = { outlinePlacement: parts.get('位置') ?? '', goalAndConflict: parts.get('目标与冲突') ?? '',
        mustHappen: bullets('必须发生'), mustNotHappen: bullets('不可发生'), endingHook: parts.get('收束') ?? '', visibleFacts: bullets('可见事实') };
      const status = planDoc.fields.get('status') ?? 'draft';
      if (status !== 'draft' && status !== 'confirmed') throw invalidInput('本章合同状态无效');
      const updatedAt = Number(planDoc.fields.get('harmonyUpdatedAt') ?? manifest.createdAt);
      if (!Number.isFinite(updatedAt)) throw invalidInput('本章合同更新时间无效');
      project.branchSettings.chapterContract = makeNovelChapterContract(input, status, branch.id, updatedAt,
        planDoc.fields.get('id') ?? `plan-${branch.id}`);
      const confirmedAt = planDoc.fields.get('harmonyConfirmedAt');
      if (status === 'confirmed' && confirmedAt !== undefined) {
        if (!Number.isFinite(Number(confirmedAt))) throw invalidInput('本章合同确认时间无效');
        project.branchSettings.chapterContract.confirmedAt = Number(confirmedAt);
      }
      project.branchSettings.thisChapterPlan = confirmedChapterPlanText(project.branchSettings);
      const suggestedCount = planDoc.fields.get('harmonySuggestedChapterCount');
      if (suggestedCount !== undefined) {
        const count = Number(suggestedCount);
        if (!Number.isInteger(count) || count < 1 || count > 10) throw invalidInput('建议代笔章数必须在 1-10');
        project.branchSettings.suggestedChapterCount = count;
      }
    }
    const future: string | null = readBody('plan/future.md');
    const upcoming: string | null = readBody('plan/upcoming.md');
    if (future !== null && upcoming !== null && future !== upcoming) throw invalidInput('往后几章计划文件冲突');
    project.branchSettings.futurePlan = future ?? upcoming ?? '';
    const arcDoc = textAt(files, prefix + 'plan/upcoming.md');
    if (upcoming !== null && (arcDoc?.fields.has('harmonyUpcomingArc') || upcoming.trim().startsWith('- '))) {
      const updatedAt = Number(arcDoc?.fields.get('harmonyUpdatedAt') ?? manifest.createdAt);
      if (!Number.isFinite(updatedAt)) throw invalidInput('往后几章计划更新时间无效');
      project.branchSettings = withNovelUpcomingArc(project, upcoming.split('\n').map(line => line.replace(/^\s*[-*]\s+/, '')), updatedAt).branchSettings;
    }
    project.branchSettings.preferences = readBody('setting/preferences.md') ?? '';
    const catalogPath: string = prefix + 'setting/catalog.json';
    const catalogFile: NovelWorkspaceArchiveFile | undefined = files.get(catalogPath);
    if (catalogFile !== undefined) {
      const catalog: { foreshadows?: NovelProject['branchSettings']['foreshadows']; confirmedDecisions?: NovelProject['branchSettings']['confirmedDecisions'] } = JSON.parse(decodeNovelWorkspaceUtf8(catalogFile.bytes));
      if (!Array.isArray(catalog.foreshadows) || !Array.isArray(catalog.confirmedDecisions)) throw invalidInput('设定目录格式无效');
      for (const item of catalog.foreshadows) {
        if (item === null || typeof item !== 'object' || typeof item.id !== 'string' || typeof item.title !== 'string' ||
          typeof item.content !== 'string' || (item.status !== 'open' && item.status !== 'resolved') ||
          !Number.isSafeInteger(item.createdAt) || item.createdAt < 0 ||
          (item.resolvedAt !== null && (!Number.isSafeInteger(item.resolvedAt) || item.resolvedAt < 0))) throw invalidInput('伏笔目录条目无效');
        safeIdentity(item.id);
      }
      for (const item of catalog.confirmedDecisions) {
        if (item === null || typeof item !== 'object' || typeof item.id !== 'string' || typeof item.title !== 'string' ||
          typeof item.content !== 'string' || !Number.isSafeInteger(item.confirmedAt) || item.confirmedAt < 0) throw invalidInput('确认决策目录条目无效');
        safeIdentity(item.id);
      }
      assertUnique(catalog.foreshadows.map((item): string => item.id), '伏笔 id');
      assertUnique(catalog.confirmedDecisions.map((item): string => item.id), '确认决策 id');
      project.branchSettings.foreshadows = catalog.foreshadows; project.branchSettings.confirmedDecisions = catalog.confirmedDecisions; used.add(catalogPath);
    }
    const legacyPlot: string | null = readBody('plan/plot.md');
    const plotParts: string[] = [];
    for (const relative of ['plot/current.md', 'plot/outline.md', 'plot/events.md']) {
      const body: string | null = readBody(relative); if (body !== null && body.trim().length > 0) plotParts.push(body);
    }
    const publicPlot: string | null = plotParts.length > 0 ? plotParts.join('\n\n') : null;
    if (legacyPlot !== null && publicPlot !== null && legacyPlot !== publicPlot) throw invalidInput('剧情文件冲突');
    const plotContent: string | null = legacyPlot ?? publicPlot;
    project.chapterPlots = rebuildChapterPlots(project.chapters);
    for (const path of files.keys()) {
      if (!path.startsWith(prefix + 'plot/chapters/') || !path.endsWith('.md')) continue;
      const doc: MarkdownDocument = textAt(files, path)!;
      const digits: RegExpMatchArray | null = path.match(/\/(\d+)-[^/]+\.md$/);
      const chapter: NovelChapter | undefined = doc.fields.has('id') ? project.chapters.find((c: NovelChapter): boolean => c.id === doc.fields.get('id') && !c.discarded)
        : digits === null ? undefined : project.chapters.find((c: NovelChapter, index: number): boolean => !c.discarded && novelChapterOrdinal(c, index + 1) === Number(digits[1]));
      if (chapter === undefined) throw invalidInput(`剧情指针没有对应章节: ${path}`);
      const pointer = project.chapterPlots.find((p): boolean => p.chapterId === chapter.id)!;
      if (used.has(`pointer:${chapter.id}:${branch.id}`)) throw invalidInput('章节剧情指针重复');
      used.add(`pointer:${chapter.id}:${branch.id}`); used.add(path);
      pointer.stale = doc.fields.get('stale') === 'true';
    }
    const branchDoc: MarkdownDocument | null = textAt(files, prefix + 'branch.md');
    const syncStatus: string = branchDoc?.fields.get('syncStatus') ?? '';
    const unresolved: number | null = firstStaleChapterOrdinal(project.chapters, project.chapterPlots);
    branches.push({ id: branch.id, pathName, name: branch.name, project, plotContent,
      plotStale: unresolved !== null || syncStatus === 'stale' || syncStatus === 'syncing' || syncStatus === 'needsSync', unresolvedFromChapterOrdinal: unresolved });
  }
  return { manifest, activeBranchId: branchPaths.get(manifest.activeBranch)!, branches,
    files: Array.from(files.values()), unsupportedPaths: Array.from(files.keys()).filter((p: string): boolean => !used.has(p)) };
};

const encodeUtf8 = (text: string): Uint8Array => {
  const bytes: number[] = [];
  for (const char of text) {
    const point: number = char.codePointAt(0)!;
    if (point < 0x80) bytes.push(point);
    else if (point < 0x800) bytes.push(0xC0 | (point >> 6), 0x80 | (point & 0x3F));
    else if (point < 0x10000) bytes.push(0xE0 | (point >> 12), 0x80 | ((point >> 6) & 0x3F), 0x80 | (point & 0x3F));
    else bytes.push(0xF0 | (point >> 18), 0x80 | ((point >> 12) & 0x3F), 0x80 | ((point >> 6) & 0x3F), 0x80 | (point & 0x3F));
  }
  return new Uint8Array(bytes);
};
const publicMaterialFolder = (kind: NovelMaterialKind): string => kind === 'character' ? 'characters'
  : kind === 'relationship' ? 'relationships' : kind === 'requirement' ? 'writing' : kind === 'other' ? 'custom' : kind;
const publicMaterialKind = (kind: NovelMaterialKind): string => kind === 'outline' ? 'masterOutline'
  : kind === 'requirement' ? 'writingRequirements' : kind === 'other' ? 'custom' : kind;
const knownFrontmatter: Set<string> = new Set(['id', 'kind', 'title', 'ordinal', 'sourceVersionID',
  'materialKind', 'injection', 'enabled', 'override', 'stale', 'syncStatus', 'harmonyRawPlan', 'harmonyInboxComplete', 'harmonyDeleted']);
const planMetadataKeys: Set<string> = new Set(['harmonyUpdatedAt', 'harmonyConfirmedAt', 'harmonySuggestedChapterCount', 'harmonyUpcomingArc']);
const materialMetadataKeys: Set<string> = new Set(['aliases', 'tags', 'customName', 'customKind', 'injectionMode']);
const render = (fields: Map<string, string>, body: string, original: MarkdownDocument | null, lists?: Map<string, string[]>): string => {
  const lines: string[] = ['---'];
  for (const pair of fields) lines.push(`${pair[0]}: ${JSON.stringify(pair[1])}`);
  if (lists !== undefined) for (const pair of lists) {
    if (pair[1].length === 0) lines.push(`${pair[0]}: []`);
    else { lines.push(`${pair[0]}:`); for (const value of pair[1]) lines.push(`  - ${JSON.stringify(value)}`); }
  }
  if (original !== null) {
    let preserveBlock: boolean = false;
    for (const line of original.header.split('\n')) {
      if (line.startsWith(' ') || line.trim().startsWith('- ')) {
        if (preserveBlock) lines.push(line);
        continue;
      }
      const colon: number = line.indexOf(':');
      const key: string = line.slice(0, colon).trim();
      const materialMetadata: boolean = fields.get('kind') === 'material' && materialMetadataKeys.has(key);
      const planMetadata: boolean = fields.get('kind') === 'plan' && (planMetadataKeys.has(key)
        || (key === 'status' && ['draft', 'confirmed'].includes(original.fields.get('status') ?? '')));
      preserveBlock = colon > 0 && !knownFrontmatter.has(key) && !materialMetadata && !planMetadata && !fields.has(key);
      if (preserveBlock) lines.push(line);
    }
  }
  lines.push('---', '', body);
  return lines.join('\n');
};
const fieldMap = (pairs: [string, string][]): Map<string, string> => new Map(pairs);

const materialLists = (fields: NovelMaterialFields, preserveAbsentFields: boolean = false): Map<string, string[]> => {
  const normalized = normalizeNovelMaterialFields(fields);
  const lists: Map<string, string[]> = new Map();
  if (!preserveAbsentFields || fields.aliases !== undefined) lists.set('aliases', normalized.aliases);
  if (!preserveAbsentFields || fields.tags !== undefined) lists.set('tags', normalized.tags);
  return lists;
};
const materialPublicFields = (pairs: [string, string][], item: NovelMaterialFields, enabled: boolean = true,
  preserveAbsentFields: boolean = false): Map<string, string> => {
  const fields: Map<string, string> = fieldMap(pairs);
  const normalized = normalizeNovelMaterialFields(item, enabled);
  if (!preserveAbsentFields || item.injectionMode !== undefined) fields.set('injection', normalized.injectionMode);
  if (normalized.customKind.length > 0 || preserveAbsentFields && item.customKind !== undefined) {
    fields.set('customName', normalized.customKind);
  }
  return fields;
};

export const buildNovelWorkspacePublicFiles = (plan: NovelWorkspaceImportPlan): NovelWorkspaceArchiveFile[] => {
  if (plan.branches.length === 0) throw invalidInput('没有可导出的小说分支');
  const active: NovelWorkspaceBranchImport | undefined = plan.branches.find((b): boolean => b.id === plan.activeBranchId);
  const main: NovelWorkspaceBranchImport | undefined = plan.branches.find((b): boolean =>
    b.project.branches.some((d: NovelBranch): boolean => d.id === b.id && d.isMain));
  if (active === undefined || main === undefined) throw invalidInput('导出工作区缺少当前或主分支');
  const originals: Map<string, NovelWorkspaceArchiveFile> = new Map(plan.files.map((f): [string, NovelWorkspaceArchiveFile] => [f.path, f]));
  const output: Map<string, NovelWorkspaceArchiveFile> = new Map();
  for (const path of plan.unsupportedPaths) {
    const file: NovelWorkspaceArchiveFile | undefined = originals.get(path);
    if (file !== undefined) output.set(path, { path, bytes: new Uint8Array(file.bytes) });
  }
  const put = (path: string, raw: string): void => {
    validateNovelWorkspacePath(path);
    if (output.has(path)) throw invalidInput(`导出文件与保留文件冲突: ${path}`);
    output.set(path, { path, bytes: encodeUtf8(raw) });
  };
  const originalFor = (scope: string, id: string, folder: string): MarkdownDocument | null => {
    for (const file of plan.files) {
      if (!file.path.startsWith(scope + folder) || !file.path.endsWith('.md')) continue;
      const doc: MarkdownDocument = markdown(decodeNovelWorkspaceUtf8(file.bytes));
      if (doc.fields.get('id') === id) return doc;
    }
    return null;
  };
  const manifest: NovelWorkspaceManifest = { ...plan.manifest, title: main.project.name,
    activeBranch: active.pathName, mainBranch: main.pathName };
  let manifestText: string = serializeNovelWorkspacePublicManifest(manifest);
  const originalManifest: NovelWorkspaceArchiveFile | undefined = originals.get('manifest.yaml');
  if (originalManifest !== undefined) {
    const originalFields: Map<string, string> = parseNovelWorkspaceMapping(decodeNovelWorkspaceUtf8(originalManifest.bytes));
    const consumed: Set<string> = new Set(['format', 'version', 'formatVersion', 'project_id', 'projectID',
      'source.projectID', 'title', 'active_branch', 'activeBranch', 'mainBranch', 'created_at', 'updated_at']);
    const sourceExtras: string[] = []; const extras: string[] = [];
    for (const pair of originalFields) {
      if (consumed.has(pair[0])) continue;
      if (pair[0].startsWith('source.')) sourceExtras.push(`  ${pair[0].slice(7)}: ${JSON.stringify(pair[1])}`);
      else extras.push(`${pair[0]}: ${JSON.stringify(pair[1])}`);
    }
    manifestText += [...sourceExtras, ...extras, ''].join('\n');
  }
  put('manifest.yaml', manifestText);
  put('project.md', render(fieldMap([['id', manifest.projectId], ['kind', 'project'], ['title', main.project.name],
    ['polishPreference', main.project.polishPreference ?? ''], ['stateSyncReasoningEnabled', String(main.project.stateSyncReasoningEnabled ?? false)]]), textAt(originals, 'project.md')?.body ?? '', textAt(originals, 'project.md')));
  const baseMaterials: NovelMaterial[] = main.project.baseMaterials ?? main.project.materials;
  for (const item of baseMaterials) {
    const original: MarkdownDocument | null = originalFor('setting/', item.id, '') ?? originalFor(`branches/${main.pathName}/setting/`, item.id, '');
    const materialName: string = item.kind === 'other' && !item.customKind && original?.fields.get('materialKind') === 'decisionLog'
        ? 'decisionLog' : publicMaterialKind(item.kind);
    const folder: string = materialName === 'relationship' ? 'relationships' : materialName === 'decisionLog' ? 'log' : publicMaterialFolder(item.kind);
    put(`setting/${folder}/${item.id}.md`, render(materialPublicFields([['id', item.id], ['kind', 'material'],
      ['title', item.title], ['materialKind', materialName]], item, item.enabled), item.content, original, materialLists(item)));
  }
  for (const branch of plan.branches) {
    const prefix: string = `branches/${branch.pathName}/`;
    put(prefix + 'branch.md', render(fieldMap([['id', branch.id], ['kind', 'branch'], ['title', branch.name],
      ['syncStatus', branch.plotStale ? 'needsSync' : 'synchronized'], ['harmonyInboxComplete', 'true']]), '', textAt(originals, prefix + 'branch.md')));
    let ordinal: number = 0;
    for (const chapter of branch.project.chapters) {
      if (!chapter.discarded) ordinal++;
      const path: string = prefix + (chapter.discarded ? `discarded/${chapter.id}.md` : `chapters/${chapterFileName(novelChapterOrdinal(chapter, ordinal), chapter.title)}`);
      put(path, render(fieldMap([['id', chapter.id], ['kind', 'chapter'], ['title', chapter.title],
        ['ordinal', String(chapter.discarded ? 100000 + branch.project.chapters.indexOf(chapter) : novelChapterOrdinal(chapter, ordinal))]]), chapter.content,
      originalFor(prefix, chapter.id, chapter.discarded ? 'discarded/' : 'chapters/')));
    }
    for (const item of branch.project.materialOverrides ?? branch.project.materials) {
      const original: MarkdownDocument | null = originalFor(prefix + 'setting/', item.id, '') ?? originalFor('setting/', item.id, '');
      const materialName: string = item.kind === 'other' && !item.customKind && original?.fields.get('materialKind') === 'decisionLog'
        ? 'decisionLog' : publicMaterialKind(item.kind);
      const folder: string = materialName === 'relationship' ? 'relationships' : materialName === 'decisionLog' ? 'log' : publicMaterialFolder(item.kind);
      put(prefix + `setting/${folder}/${item.id}.md`, render(materialPublicFields([['id', item.id], ['kind', 'material'],
        ['title', item.title], ['materialKind', materialName], ['override', 'true']], item, item.enabled), item.content, original, materialLists(item)));
    }
    for (const base of baseMaterials) {
      if (branch.project.hiddenMaterialIds !== undefined ? !branch.project.hiddenMaterialIds.includes(base.id) :
        branch.project.materials.some((m: NovelMaterial): boolean => m.id === base.id)) continue;
      put(prefix + `setting/${publicMaterialFolder(base.kind)}/${base.id}.md`, render(fieldMap([
        ['id', base.id], ['kind', 'material'], ['title', base.title], ['materialKind', publicMaterialKind(base.kind)],
        ['override', 'true'], ['injection', 'off'], ['harmonyDeleted', 'true']]), '', null));
    }
    for (const proposal of branch.project.settingProposals) {
      if (proposal.status !== 'pending') continue;
      put(prefix + `inbox/${proposal.id}.md`, render(materialPublicFields([['id', proposal.id], ['kind', 'material'],
        ['title', proposal.title], ['materialKind', publicMaterialKind(proposal.kind)]], proposal, true, true), proposal.content,
        originalFor(prefix + 'inbox/', proposal.id, '') ?? originalFor('inbox/', proposal.id, ''), materialLists(proposal, true)));
    }
    put(prefix + 'state/evidence.json', JSON.stringify({ protocolVersion: NOVEL_STATE_PROTOCOL_VERSION, state: branch.project.structuredState ?? null }));
    const contract = branch.project.branchSettings.chapterContract;
    const rawPlan: string = contract === undefined ? branch.project.branchSettings.thisChapterPlan : chapterContractMarkdown(contract);
    const headings: string[] = rawPlan.split('\n').filter((line: string): boolean => line.startsWith('## '));
    const hasIOSSections: boolean = rawPlan.trimStart().startsWith('## ') && headings.length > 0 &&
      headings.every((line: string): boolean => /^## (位置|目标与冲突|必须发生|不可发生|收束|可见事实)\s*$/.test(line)) &&
      new Set(headings.map((line: string): string => line.trim())).size === headings.length;
    const planFields: Map<string, string> = fieldMap([['id', contract?.id ?? `plan-${branch.id}`], ['kind', 'plan'], ['title', '本章计划']]);
    if (contract !== undefined) {
      planFields.set('status', contract.status); planFields.set('harmonyUpdatedAt', String(contract.updatedAt));
      if (contract.confirmedAt !== null) planFields.set('harmonyConfirmedAt', String(contract.confirmedAt));
      if (branch.project.branchSettings.suggestedChapterCount !== undefined)
        planFields.set('harmonySuggestedChapterCount', String(branch.project.branchSettings.suggestedChapterCount));
    } else if (!hasIOSSections) planFields.set('harmonyRawPlan', 'bounded-v1');
    put(prefix + 'plan/this-chapter.md', render(planFields, hasIOSSections ? rawPlan : `## 目标与冲突\n\n<!-- amber:raw-plan:start -->\n${rawPlan.split('\n').map((line: string): string => '  ' + line).join('\n')}\n<!-- amber:raw-plan:end -->`, textAt(originals, prefix + 'plan/this-chapter.md')));
    const arc = branch.project.branchSettings.upcomingArc;
    const arcFields = fieldMap([['id', `upcoming-${branch.id}`], ['kind', 'plan'], ['title', '往后几章']]);
    if (arc !== undefined) { arcFields.set('harmonyUpcomingArc', 'true'); arcFields.set('harmonyUpdatedAt', String(arc.updatedAt)); }
    put(prefix + 'plan/upcoming.md', render(arcFields, arc === undefined ? branch.project.branchSettings.futurePlan : arc.beats.map(beat => '- ' + beat).join('\n'),
      textAt(originals, prefix + 'plan/upcoming.md') ?? textAt(originals, prefix + 'plan/future.md')));
    put(prefix + 'setting/preferences.md', branch.project.branchSettings.preferences);
    put(prefix + 'setting/catalog.json', JSON.stringify({ foreshadows: branch.project.branchSettings.foreshadows,
      confirmedDecisions: branch.project.branchSettings.confirmedDecisions }));
    if (branch.plotContent !== null) {
      const originalPlotDocs: Array<MarkdownDocument | null> = ['current', 'outline', 'events'].map(
        (name: string): MarkdownDocument | null => textAt(originals, prefix + `plot/${name}.md`));
      const originalCombined: string = originalPlotDocs.filter((doc): boolean => doc !== null && doc.body.trim().length > 0)
        .map((doc): string => doc!.body).join('\n\n');
      const unchanged: boolean = originalCombined === branch.plotContent;
      put(prefix + 'plot/current.md', render(fieldMap([['id', `plot-${branch.id}`], ['kind', 'plot'], ['title', '当前剧情']]),
        unchanged ? originalPlotDocs[0]?.body ?? '' : branch.plotContent,
        originalPlotDocs[0] ?? textAt(originals, prefix + 'plan/plot.md')));
      for (const index of [1, 2]) {
        const original: MarkdownDocument | null = originalPlotDocs[index];
        if (original === null) continue;
        const name: string = index === 1 ? 'outline' : 'events';
        put(prefix + `plot/${name}.md`, render(fieldMap([['id', `plot-${branch.id}`], ['kind', 'plot'], ['title', name]]),
          unchanged ? original.body : '', original));
      }
    }
    let pointerOrdinal: number = 0;
    for (const chapter of branch.project.chapters) {
      if (chapter.discarded) continue;
      pointerOrdinal++;
      const pointer = branch.project.chapterPlots.find((p): boolean => p.chapterId === chapter.id);
      if (pointer === undefined) continue;
      put(prefix + `plot/chapters/${chapterFileName(novelChapterOrdinal(chapter, pointerOrdinal), chapter.title)}`,
        render(fieldMap([['id', chapter.id], ['kind', 'plot'], ['title', chapter.title], ['stale', String(pointer.stale)]]),
          pointer.text, originalFor(prefix + 'plot/', chapter.id, 'chapters/')));
    }
  }
  for (const proposal of main.project.settingProposals) {
    if (proposal.status !== 'pending') continue;
    put(`inbox/${proposal.id}.md`, render(fieldMap([['id', proposal.id], ['kind', 'material'], ['title', proposal.title],
      ['materialKind', publicMaterialKind(proposal.kind)]]), proposal.content, originalFor('inbox/', proposal.id, '')));
  }
  return Array.from(output.values());
};
