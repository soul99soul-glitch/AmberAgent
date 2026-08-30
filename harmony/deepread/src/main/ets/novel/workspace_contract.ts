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

export const parseNovelWorkspaceManifest = (raw: string): NovelWorkspaceManifest => {
  const values: Map<string, string> = new Map<string, string>();
  const lines: string[] = raw.replace(/\r\n/g, '\n').split('\n');
  for (let i: number = 0; i < lines.length; i++) {
    const line: string = lines[i].trim();
    if (line.length === 0 || line.startsWith('#')) continue;
    const colon: number = line.indexOf(':');
    if (colon <= 0) throw invalidInput('manifest YAML 格式无效');
    const key: string = line.slice(0, colon).trim();
    if (values.has(key)) throw invalidInput(`manifest 重复字段: ${key}`);
    values.set(key, line.slice(colon + 1));
  }
  const required = (key: string): string => {
    const value: string | undefined = values.get(key);
    if (value === undefined || value.trim().length === 0) {
      throw invalidInput(`manifest 缺少字段: ${key}`);
    }
    return value;
  };
  const manifest: NovelWorkspaceManifest = {
    format: scalar(required('format')),
    version: finiteInt(required('version'), 'version'),
    projectId: scalar(required('project_id')),
    title: scalar(required('title')),
    activeBranch: scalar(required('active_branch')),
    createdAt: values.has('created_at') ? finiteInt(required('created_at'), 'created_at') : 0,
    updatedAt: values.has('updated_at') ? finiteInt(required('updated_at'), 'updated_at') : 0,
  };
  return validateManifest(manifest);
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
