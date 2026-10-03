// 小说工作区公开格式。此文件只负责 wire contract 与不可信相对路径校验，
// 不访问文件系统，也不承载 branch/commit 业务规则。

import { invalidInput } from './error.ts';

export const NOVEL_WORKSPACE_FORMAT: string = 'amber.novel.workspace';
export const NOVEL_WORKSPACE_VERSION: number = 1;

export interface NovelWorkspaceManifest {
  format: string;
  version: number;
  projectId: string;
  title: string;
  activeBranch: string;
  createdAt: number;
  updatedAt: number;
  mainBranch?: string;
  dialect?: 'harmony' | 'ios';
}

const yamlString = (value: string): string => JSON.stringify(value);

export const serializeNovelWorkspaceManifest = (manifest: NovelWorkspaceManifest): string => {
  validateManifest(manifest);
  return [
    `format: ${yamlString(manifest.format)}`,
    `version: ${manifest.version}`,
    `project_id: ${yamlString(manifest.projectId)}`,
    `title: ${yamlString(manifest.title)}`,
    `active_branch: ${yamlString(manifest.activeBranch)}`,
    `created_at: ${manifest.createdAt}`,
    `updated_at: ${manifest.updatedAt}`,
    '',
  ].join('\n');
};

const scalar = (source: string): string => {
  const value: string = source.trim();
  if (value.startsWith('"')) {
    const parsed: unknown = JSON.parse(value);
    if (typeof parsed !== 'string') throw invalidInput('manifest 字段必须是字符串');
    return parsed;
  }
  return value;
};

const finiteInt = (value: string, name: string): number => {
  const parsed: number = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) throw invalidInput(`manifest ${name} 无效`);
  return parsed;
};

export const parseNovelWorkspaceMapping = (raw: string): Map<string, string> => {
  const values: Map<string, string> = new Map<string, string>();
  let parent: string = '';
  for (const original of raw.replace(/\r\n/g, '\n').split('\n')) {
    const line: string = original.trim();
    if (line.length === 0 || line.startsWith('#')) continue;
    if (line.startsWith('- ')) continue; // Markdown alias/extension lists remain verbatim in passthrough.
    const colon: number = line.indexOf(':');
    if (colon <= 0) throw invalidInput('工作区 YAML 格式无效');
    const leaf: string = line.slice(0, colon).trim();
    const source: string = line.slice(colon + 1).trim();
    if (!original.startsWith(' ') && source.length === 0) { parent = leaf; continue; }
    const key: string = original.startsWith(' ') && parent.length > 0 ? `${parent}.${leaf}` : leaf;
    if (!original.startsWith(' ')) parent = '';
    if (values.has(key)) throw invalidInput(`工作区重复字段: ${key}`);
    values.set(key, scalar(source));
  }
  return values;
};

export const parseNovelWorkspaceManifest = (raw: string, projectMarkdown: string = ''): NovelWorkspaceManifest => {
  const values: Map<string, string> = parseNovelWorkspaceMapping(raw);
  const required = (key: string): string => {
    const value: string | undefined = values.get(key);
    if (value === undefined || value.trim().length === 0) throw invalidInput(`manifest 缺少字段: ${key}`);
    return value;
  };
  const ios: boolean = values.has('formatVersion');
  if (ios && values.has('version') && required('formatVersion') !== required('version')) {
    throw invalidInput('manifest 版本字段冲突');
  }
  const projectId: string = ios ? values.get('source.projectID') ?? required('projectID') : required('project_id');
  if (values.has('project_id') && required('project_id') !== projectId) throw invalidInput('manifest 项目标识冲突');
  if (values.has('projectID') && required('projectID') !== projectId) throw invalidInput('manifest 项目标识冲突');
  let projectTitle: string = '';
  if (projectMarkdown.startsWith('---\n')) {
    const end: number = projectMarkdown.indexOf('\n---', 4);
    if (end > 0) projectTitle = parseNovelWorkspaceMapping(projectMarkdown.slice(4, end)).get('title') ?? '';
  }
  const activeBranch: string = ios ? values.get('activeBranch') ?? required('mainBranch') : required('active_branch');
  const manifest: NovelWorkspaceManifest = {
    format: required('format'),
    version: finiteInt(required(ios ? 'formatVersion' : 'version'), 'version'),
    projectId,
    title: values.get('title') ?? (projectTitle || (ios ? projectId : required('title'))),
    activeBranch,
    createdAt: values.has('created_at') ? finiteInt(required('created_at'), 'created_at') : 0,
    updatedAt: values.has('updated_at') ? finiteInt(required('updated_at'), 'updated_at') : 0,
  };
  if (ios) { manifest.mainBranch = required('mainBranch'); manifest.dialect = 'ios'; }
  return validateManifest(manifest);
};

export const serializeNovelWorkspacePublicManifest = (manifest: NovelWorkspaceManifest): string => {
  validateManifest(manifest);
  return [
    `format: ${yamlString(manifest.format)}`, 'formatVersion: 1',
    `mainBranch: ${yamlString(manifest.mainBranch ?? manifest.activeBranch)}`,
    `activeBranch: ${yamlString(manifest.activeBranch)}`,
    `title: ${yamlString(manifest.title)}`, `created_at: ${manifest.createdAt}`, `updated_at: ${manifest.updatedAt}`,
    'source:', `  projectID: ${yamlString(manifest.projectId)}`, '',
  ].join('\n');
};

const validateManifest = (manifest: NovelWorkspaceManifest): NovelWorkspaceManifest => {
  if (manifest.format !== NOVEL_WORKSPACE_FORMAT || manifest.version !== NOVEL_WORKSPACE_VERSION) {
    throw invalidInput('小说工作区版本不受支持');
  }
  if (manifest.projectId.trim().length === 0 || manifest.title.trim().length === 0 ||
    manifest.activeBranch.trim().length === 0) {
    throw invalidInput('小说工作区标识无效');
  }
  validateNovelWorkspaceSegment(manifest.projectId, 'project_id');
  validateNovelWorkspaceSegment(manifest.activeBranch, 'active_branch');
  if (manifest.mainBranch !== undefined) validateNovelWorkspaceSegment(manifest.mainBranch, 'mainBranch');
  return manifest;
};

const validateNovelWorkspaceSegment = (segment: string, field: string): void => {
  if (segment.length === 0 || segment === '.' || segment === '..' || segment.startsWith('.') ||
    segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0 || segment.indexOf(':') >= 0) {
    throw invalidInput(`${field} 包含非法路径字符`);
  }
};

export const validateNovelWorkspacePath = (path: string): string => {
  if (path.length === 0 || path !== path.trim() || path.startsWith('/') || path.startsWith('\\') ||
    path.indexOf('\\') >= 0) {
    throw invalidInput(`工作区路径无效: ${path}`);
  }
  const segments: string[] = path.split('/');
  for (let i: number = 0; i < segments.length; i++) {
    const segment: string = segments[i];
    if (segment.length === 0 || segment === '.' || segment === '..' || segment.startsWith('.') ||
      segment.indexOf(':') >= 0 || segment.indexOf('\0') >= 0) {
      throw invalidInput(`工作区路径无效: ${path}`);
    }
  }
  return segments.join('/');
};

const safeChapterTitle = (title: string): string => {
  const clean: string = title.trim()
    .replace(/[\\/:*?"<>|]/g, '-')
    .replace(/\s+/g, ' ')
    .replace(/\s*-\s*/g, '-')
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '');
  return clean.length > 0 ? clean.slice(0, 80) : '未命名章节';
};

export const chapterFileName = (ordinal: number, title: string): string => {
  if (!Number.isInteger(ordinal) || ordinal < 1 || ordinal > 999) {
    throw invalidInput('章节序号必须在 1-999 之间');
  }
  const digits: string = ordinal.toString().padStart(3, '0');
  return `${digits}-${safeChapterTitle(title)}.md`;
};
