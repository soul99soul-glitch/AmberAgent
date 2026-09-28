// SoulTools — agent soul(agentSoulMarkdown)导入三件套(P2-07;Android SoulTools.kt 对齐)
//
// soul_preview(只读):读 workspace/SOUL.md → diff + 影响面 + 绑定 digest;
// soul_import(需审批,禁自动批准):preview_digest 绑定,应用前重读双方(CAS),
//   被替换的 soul 保留一次回滚;soul_rollback(需审批):回滚上一次导入。
// 手工编辑路径(灵魂与记忆页)不受影响;本工具不触碰 skills/MCP/recipe。

import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { AgentTool, JsonValue, UIMessagePart, InputSchemaObj, JsonObject } from '@amber/chat-domain';
import { makeAgentTool, makeInputSchemaObj, inputStringValue } from '@amber/chat-domain';
import { getChatKvStore } from '../di/AppContainer.ets';
import { loadAgentRuntimeSnapshot, updateAgentSoulMarkdown } from './AgentRuntimePrefs.ets';
import { workspaceRootAbs } from './WorkspaceStore.ets';
import { sha256HexUtf8 } from './EntryLocalToolPorts.ets';

const DEFAULT_SOUL_FILE: string = 'SOUL.md';
const MAX_SOUL_CHARS: number = 64 * 1024;
const SOUL_PREVIOUS_KEY: string = 'soul_previous_markdown';

// 灵魂与记忆页共用:回退可用性查询/执行(与 soul_import/soul_rollback 同一存储键,单次有效)
export const readPreviousSoulMarkdown = async (): Promise<string | null> => {
  const kv = getChatKvStore();
  return kv.get(SOUL_PREVIOUS_KEY);
};

export const rollbackSoulToPrevious = async (): Promise<boolean> => {
  const kv = getChatKvStore();
  const previous: string | null = await kv.get(SOUL_PREVIOUS_KEY);
  if (previous === null) return false;
  await updateAgentSoulMarkdown(kv, previous);
  await kv.delete(SOUL_PREVIOUS_KEY);
  return true;
};

const textPart = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

const soulPath = (workspacePath: string): string => {
  const rel: string = workspacePath.length > 0 ? workspacePath : DEFAULT_SOUL_FILE;
  return `${workspaceRootAbs()}/${rel}`;
};

// 读取候选(>64k 拒绝,iOS 同口径)
const readCandidate = (workspacePath: string): string => {
  const path: string = soulPath(workspacePath);
  let content: string;
  try {
    const file: fileIo.File = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
    const stat: fileIo.Stat = fileIo.statSync(file.fd);
    const buf: ArrayBuffer = new ArrayBuffer(stat.size);
    fileIo.readSync(file.fd, buf);
    fileIo.closeSync(file.fd);
    content = new util.TextDecoder('utf-8').decode(new Uint8Array(buf));
  } catch (e) {
    throw new Error(`无法读取 soul 候选:${path}`);
  }
  if (content.length > MAX_SOUL_CHARS) {
    throw new Error(`soul 候选超过 64k 字符上限(${content.length})`);
  }
  if (content.trim().length === 0) {
    throw new Error('soul 候选为空');
  }
  return content;
};

const digestOf = (text: string): string => sha256HexUtf8(text).substring(0, 12);

const loadCurrentSoul = async (): Promise<string> => {
  const snap = await loadAgentRuntimeSnapshot(getChatKvStore());
  return snap.agentSoulMarkdown;
};

interface SoulDiffSummary {
  addedLines: number;
  removedLines: number;
  preview: string;
}

// 最小诚实 diff:行计数差异 + 首处不同的上下文(不实现完整 LCS,够模型判断)
const diffSummary = (base: string, candidate: string): SoulDiffSummary => {
  if (base === candidate) return { addedLines: 0, removedLines: 0, preview: '（无变化）' };
  const baseLines: string[] = base.split('\n');
  const candLines: string[] = candidate.split('\n');
  let firstDiff: number = 0;
  const minLen: number = Math.min(baseLines.length, candLines.length);
  while (firstDiff < minLen && baseLines[firstDiff] === candLines[firstDiff]) firstDiff++;
  const removed: number = baseLines.length - firstDiff;
  const added: number = candLines.length - firstDiff;
  const context: string[] = [];
  const start: number = Math.max(0, firstDiff - 1);
  for (let i = start; i < Math.min(baseLines.length, firstDiff + 2); i++) {
    context.push(`- ${baseLines[i]}`);
  }
  for (let i = start; i < Math.min(candLines.length, firstDiff + 2); i++) {
    context.push(`+ ${candLines[i]}`);
  }
  return {
    addedLines: Math.max(0, added),
    removedLines: Math.max(0, removed),
    preview: context.join('\n').substring(0, 600),
  };
};

const soulPreviewPayload = async (workspacePath: string): Promise<JsonObject> => {
  const candidate: string = readCandidate(workspacePath);
  const base: string = await loadCurrentSoul();
  const baseDigest: string = digestOf(base);
  const candidateDigest: string = digestOf(candidate);
  const diff: SoulDiffSummary = diffSummary(base, candidate);
  return {
    status: 'ready',
    source: workspacePath.length > 0 ? workspacePath : DEFAULT_SOUL_FILE,
    char_count: candidate.length,
    base_digest: baseDigest,
    candidate_digest: candidateDigest,
    digest: `${baseDigest}->${candidateDigest}`,
    identical: base === candidate,
    added_lines: diff.addedLines,
    removed_lines: diff.removedLines,
    diff_preview: diff.preview,
    import_instruction: '用户确认后,带 workspace_path 与 preview_digest 调用 soul_import 应用。',
  };
};

export const createSoulTools = (): AgentTool[] => [
  makeAgentTool({
    name: 'soul_preview',
    description: 'Preview an agent soul (app-level behavior guide injected into every '
      + 'conversation) candidate from workspace/SOUL.md or another workspace file: diff vs '
      + 'the current soul and the bound approval digest. Read-only — nothing is changed.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'workspace_path': {
        type: 'string',
        description: 'Workspace path of the soul candidate. Defaults to SOUL.md at the workspace root.',
      },
    } as JsonObject, null),
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const workspacePath: string = (inputStringValue(input, 'workspace_path') ?? '').trim();
      return textPart(await soulPreviewPayload(workspacePath));
    },
  }),
  makeAgentTool({
    name: 'soul_import',
    description: 'Apply a soul candidate after soul_preview. preview_digest must match; the '
      + 'update is rejected as stale if the soul or candidate changed in the meantime. The '
      + 'previous soul is kept for one rollback.',
    parameters: (): InputSchemaObj | null => makeInputSchemaObj({
      'workspace_path': {
        type: 'string',
        description: 'Workspace path of the soul candidate. Defaults to SOUL.md.',
      },
      'preview_digest': {
        type: 'string',
        description: 'The digest returned by soul_preview for this candidate (base->candidate).',
      },
    } as JsonObject, ['preview_digest']),
    needsApproval: true,
    allowsAutoApproval: false,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const workspacePath: string = (inputStringValue(input, 'workspace_path') ?? '').trim();
      const previewDigest: string = (inputStringValue(input, 'preview_digest') ?? '').trim();
      if (previewDigest.length === 0) throw new Error('preview_digest is required');
      // CAS:重读双方,绑定 digest 失配即拒绝(soul 或候选中途被改)
      const candidate: string = readCandidate(workspacePath);
      const base: string = await loadCurrentSoul();
      const expect: string = `${digestOf(base)}->${digestOf(candidate)}`;
      if (expect !== previewDigest) {
        throw new Error(`soul 预览已过期(${expect} != ${previewDigest}),请重新 soul_preview`);
      }
      if (base === candidate) {
        return textPart({ status: 'noop', message: '候选与当前 soul 完全一致,无需导入' });
      }
      const kv = getChatKvStore();
      await kv.put(SOUL_PREVIOUS_KEY, base);
      await updateAgentSoulMarkdown(kv, candidate);
      return textPart({
        status: 'ok',
        char_count: candidate.length,
        rollback_instruction: '如需撤销,调用 soul_rollback(仅一次)。',
      });
    },
  }),
  makeAgentTool({
    name: 'soul_rollback',
    description: 'Roll back the last soul import once, restoring the replaced soul. Fails '
      + 'honestly when there is nothing to roll back.',
    execute: async (_input: JsonValue): Promise<UIMessagePart[]> => {
      const kv = getChatKvStore();
      const previous: string | null = await kv.get(SOUL_PREVIOUS_KEY);
      if (previous === null) {
        throw new Error('没有可回滚的 soul 导入记录');
      }
      const current: string = await loadCurrentSoul();
      await updateAgentSoulMarkdown(kv, previous);
      await kv.delete(SOUL_PREVIOUS_KEY);
      return textPart({
        status: 'ok',
        restored_chars: previous.length,
        replaced_chars: current.length,
      });
    },
  }),
];
