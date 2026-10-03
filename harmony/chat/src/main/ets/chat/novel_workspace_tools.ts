// novel_workspace_tools — C5 小说工作区 Agent 工具目录。
//
// 工具只定义模型可见契约并委托宿主 port；不持有 Entry、repository 或 C4 存储细节。
// 宿主应为当前项目/分支绑定 port，并由 C4 durable proposal/receipt 路径处理 write。

import type { JsonObject, JsonValue } from './json.ts';
import type { NovelAuditReport, AbortSignalLike } from '@amber/deepread-domain';
import type { UIMessagePart } from './message.ts';
import type { AgentTool, InputSchemaObj } from './tool.ts';
import { makeAgentTool } from './tool.ts';
import { createNovelProjectOperationTools } from './novel_project_operations.ts';
import type { NovelProjectOperationPort } from './novel_project_operations.ts';

export interface NovelWorkspaceListToolInput {
  path?: string;
}

export interface NovelWorkspaceReadToolInput {
  path: string;
}

export interface NovelWorkspaceGrepToolInput {
  query: string;
  path?: string;
  max_results?: number;
}

export interface NovelWorkspaceWritePatch {
  operation: 'write' | 'delete';
  path: string;
  content: string | null;
}

export interface NovelWorkspaceWriteToolInput {
  proposal_id: string;
  patches: NovelWorkspaceWritePatch[];
}

// 该 port 是当前工作区的窄适配边界：调用者在构造时已绑定 project/active branch。
// 返回值是工具既有的 JSON text payload，错误原样交给 dispatcher 归一化。
export interface NovelWorkspaceToolPort {
  list(input: NovelWorkspaceListToolInput): Promise<JsonObject>;
  read(input: NovelWorkspaceReadToolInput): Promise<JsonObject>;
  grep(input: NovelWorkspaceGrepToolInput): Promise<JsonObject>;
  status(): Promise<JsonObject>;
  write(input: NovelWorkspaceWriteToolInput): Promise<JsonObject>;
  /** LLM 全文连续性审计(iOS 对齐);宿主委托 NovelCreation.runContinuityAudit */
  audit(signal?: AbortSignalLike): Promise<JsonObject>;
  /** 解析 assistant 正文中的设定提案并入库;返回 ingested 条数 */
  ingestSettingProposals(sourceMessageId: string, text: string): Promise<JsonObject>;
  /** Optional specialized project tools; existing workspace-only callers keep their catalog. */
  projectOperation?: NovelProjectOperationPort;
}

interface CompleteInputSchema extends InputSchemaObj {
  additionalProperties: false;
}

const objectSchema = (properties: JsonObject, required: string[]): CompleteInputSchema => ({
  type: 'object',
  properties,
  required,
  additionalProperties: false,
});

const stringSchema = (description: string): JsonObject => ({ type: 'string', description });

const textPart = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

const listSchema = (): CompleteInputSchema => objectSchema({
  path: stringSchema('Optional workspace-relative directory. Defaults to the active branch root.'),
}, []);

const readSchema = (): CompleteInputSchema => objectSchema({
  path: stringSchema('Workspace-relative text file path to read.'),
}, ['path']);

const grepSchema = (): CompleteInputSchema => objectSchema({
  query: stringSchema('Text to search for in workspace text files.'),
  path: stringSchema('Optional workspace-relative directory or file. Defaults to the active branch root.'),
  max_results: {
    type: 'integer',
    minimum: 1,
    description: 'Optional maximum number of matching lines to return.',
  },
}, ['query']);

const writePatchSchema = (): JsonObject => ({
  description: 'A proposed patch. write requires text content; delete requires content to be null.',
  oneOf: [
    {
      type: 'object',
      properties: {
        operation: { type: 'string', enum: ['write'] },
        path: stringSchema('Allowed workspace-relative path for this proposal.'),
        content: stringSchema('Complete UTF-8 text to write.'),
      },
      required: ['operation', 'path', 'content'],
      additionalProperties: false,
    },
    {
      type: 'object',
      properties: {
        operation: { type: 'string', enum: ['delete'] },
        path: stringSchema('Allowed workspace-relative path for this proposal.'),
        content: { type: 'null', description: 'Must be null when deleting a path.' },
      },
      required: ['operation', 'path', 'content'],
      additionalProperties: false,
    },
  ],
});

const writeSchema = (): CompleteInputSchema => objectSchema({
  proposal_id: stringSchema(
    'Stable proposal id. Reuse the same value when retrying the same proposed change.'),
  patches: {
    type: 'array',
    minItems: 1,
    items: writePatchSchema(),
    description: 'One or more proposed writes or deletes. This tool stages a proposal; it never applies it directly.',
  },
}, ['proposal_id', 'patches']);

// 保留工具既有字符串问题列表，同时说明未完成或无效证据，避免空列表被误读为通过。
export const novelAuditToolResult = (report: NovelAuditReport): JsonObject => ({
  ok: report.ok,
  issue_count: report.issues.length,
  issues: report.issues.map((issue): string =>
    `[${issue.severity}] ${issue.chapterRef}: ${issue.summary}`
    + (issue.suggestion.length > 0 ? ` → ${issue.suggestion}` : '')),
  coverage: {
    checked_chars: report.coverage.checkedChars,
    total_chars: report.coverage.totalChars,
    complete: report.coverage.complete,
  },
  cancelled: report.cancelled,
  invalid_evidence_count: report.invalidEvidenceCount,
  failed_blocks: report.blocks.filter((block): boolean => block.status !== 'checked')
    .map((block): JsonObject => ({
      id: block.chapterId, start: block.start, end: block.end, status: block.status, error: block.error,
    })),
});

export const createNovelWorkspaceTools = (port: NovelWorkspaceToolPort): AgentTool[] => [
  makeAgentTool({
    name: 'novel_workspace_list',
    description: 'List files and directories in the active novel workspace branch.',
    parameters: listSchema,
    execute: (input: JsonValue): Promise<UIMessagePart[]> =>
      port.list(input as NovelWorkspaceListToolInput).then(textPart),
  }),
  makeAgentTool({
    name: 'novel_workspace_read',
    description: 'Read a text file from the active novel workspace branch.',
    parameters: readSchema,
    execute: (input: JsonValue): Promise<UIMessagePart[]> =>
      port.read(input as unknown as NovelWorkspaceReadToolInput).then(textPart),
  }),
  makeAgentTool({
    name: 'novel_workspace_grep',
    description: 'Search text files in the active novel workspace branch.',
    parameters: grepSchema,
    execute: (input: JsonValue): Promise<UIMessagePart[]> =>
      port.grep(input as unknown as NovelWorkspaceGrepToolInput).then(textPart),
  }),
  makeAgentTool({
    name: 'novel_workspace_status',
    description: 'Inspect durable active-branch workspace status, including proposal and consistency state.',
    parameters: (): CompleteInputSchema => objectSchema({}, []),
    execute: (): Promise<UIMessagePart[]> => port.status().then(textPart),
  }),
  makeAgentTool({
    name: 'novel_workspace_write',
    description: 'Stage a durable, approval-required novel workspace proposal. It never applies patches directly.',
    parameters: writeSchema,
    needsApproval: true,
    allowsAutoApproval: false,
    execute: (input: JsonValue): Promise<UIMessagePart[]> =>
      port.write(input as unknown as NovelWorkspaceWriteToolInput).then(textPart),
  }),
  makeAgentTool({
    name: 'novel_continuity_audit',
    description: 'Run an LLM continuity audit over the whole novel (plot, names, timeline). '
      + 'Returns JSON issues, coverage, failed blocks and invalid evidence count; incomplete or cancelled audits do not pass. Uses the stateSync model.',
    parameters: (): CompleteInputSchema => objectSchema({}, []),
    execute: (_input: JsonValue, signal?: AbortSignalLike): Promise<UIMessagePart[]> => port.audit(signal).then(textPart),
  }),
  makeAgentTool({
    name: 'novel_ingest_setting_proposals',
    description: 'Parse novel_setting_proposal JSON from an assistant message body and stage them '
      + 'as pending setting proposals on the project (does not apply them).',
    parameters: (): CompleteInputSchema => objectSchema({
      source_message_id: stringSchema('Assistant message id that contained the proposal JSON.'),
      text: stringSchema('Full assistant text containing fenced or raw novel_setting_proposal JSON.'),
    }, ['source_message_id', 'text']),
    needsApproval: true,
    allowsAutoApproval: false,
    execute: (input: JsonValue): Promise<UIMessagePart[]> => {
      const obj = input as { source_message_id?: string; text?: string };
      const sourceMessageId: string = String(obj?.source_message_id ?? '');
      const text: string = String(obj?.text ?? '');
      if (sourceMessageId.length === 0 || text.length === 0) {
        throw new Error('source_message_id and text are required');
      }
      return port.ingestSettingProposals(sourceMessageId, text).then(textPart);
    },
  }),
  ...(port.projectOperation === undefined ? [] : createNovelProjectOperationTools(port.projectOperation)),
];
