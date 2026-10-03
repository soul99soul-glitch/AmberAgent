// novel/error — 小说创作错误类型(移植自 Android NovelError.kt)
// 用 Error 子类 + code 判别,便于 throw/catch 与 UI 展示中文消息。

export type NovelErrorCode =
  | 'invalid_input' | 'not_found' | 'project_busy'
  | 'already_collected' | 'provider' | 'output_too_large' | 'invalid_output';

const ENTITY_NAMES: Record<string, string> = {
  project: '项目',
  chapter: '章节',
  material: '资料',
  message: '消息',
  suggestion: '建议',
  setting_proposal: '设定提案',
};

export class NovelError extends Error {
  readonly code: NovelErrorCode;
  readonly entityId: string;

  constructor(code: NovelErrorCode, message: string, entityId: string = '') {
    super(message);
    this.name = 'NovelError';
    this.code = code;
    this.entityId = entityId;
  }
}

export const isNovelError = (e: unknown): e is NovelError =>
  e instanceof NovelError;

export const invalidInput = (msg: string): NovelError =>
  new NovelError('invalid_input', msg);

export const notFound = (entity: string, id: string): NovelError => {
  const name: string = ENTITY_NAMES[entity] !== undefined ? ENTITY_NAMES[entity] : entity;
  return new NovelError('not_found', `${name}不存在`, id);
};

export const projectBusy = (projectId: string): NovelError =>
  new NovelError('project_busy', '项目正在生成内容，请先停止后再试', projectId);

export const alreadyCollected = (messageId: string): NovelError =>
  new NovelError('already_collected', '该回复已收进章节', messageId);

export const providerError = (msg: string): NovelError =>
  new NovelError('provider', `模型生成失败：${msg}`);

export const invalidModelOutput = (msg: string): NovelError =>
  new NovelError('invalid_output', msg);

export const outputTooLarge = (limit: number): NovelError =>
  new NovelError('output_too_large', `输出超过上限 ${limit} 字`);
