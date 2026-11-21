// tool_safety_net — P1-3 工具安全网纯逻辑：
//   1. Workspace / Memory 待授权操作 Diff 预览生成（有界行差异）；
//   2. `.undo/` 快照持久化与回滚（单文件 5 MiB 上限、每路径最多 3 份、24h 过期清理、SHA-256 冲突拒绝）；
//   3. 中断恢复 side-effect 工具标记为 `outcome_unknown`（禁止自动重放）及 3 态人工核对收口。

import type { Conversation, MessageNode } from './conversation.ts';
import { patchConversation } from './conversation.ts';
import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessage, UIMessagePart, UIMessagePartTool } from './message.ts';
import type { ToolApprovalPartLocator } from './tool_approval.ts';
import type { WorkspaceFsPort } from './workspace.ts';
import { normalizeWorkspacePath } from './workspace.ts';

export const WORKSPACE_UNDO_MAX_BYTES: number = 5 * 1024 * 1024;
export const WORKSPACE_UNDO_MAX_PER_PATH: number = 3;
export const WORKSPACE_UNDO_TTL_MS: number = 24 * 60 * 60 * 1000;
export const DIFF_PREVIEW_MAX_LINES: number = 40;
export const DIFF_PREVIEW_MAX_CHARS: number = 4000;

export const TOOL_OUTCOME_METADATA_KEY: string = 'amber_tool_outcome';
export type ToolOutcomeState = 'outcome_unknown' | 'reconciled_applied' | 'reconciled_not_applied' | 'recheck_requested';
export type ToolOutcomeResolution = 'not_applied' | 'applied' | 'recheck_requested';

const SIDE_EFFECT_TOOL_SET: Set<string> = new Set<string>([
  'file_write', 'file_edit', 'file_move', 'file_delete', 'workspace_file_delete', 'artifact_delete',
  'memory_write', 'memory_delete', 'memory_tool',
  'ssh_exec', 'ssh_profile_create', 'ssh_profile_update', 'ssh_profile_delete', 'ssh_host_trust',
  'mosh_exec', 'python_exec', 'terminal_exec',
  'calendar_event_create', 'calendar_event_update', 'calendar_event_delete',
  'reminder_create', 'reminder_update', 'reminder_delete', 'reminder_complete',
  'notification_schedule', 'notification_cancel',
  'wm_act', 'cron_create', 'cron_update', 'cron_delete', 'skill_import',
  'feishu_docs_write', 'external_file_write', 'external_file_delete',
]);

export const isSideEffectToolName = (toolName: string): boolean => {
  if (SIDE_EFFECT_TOOL_SET.has(toolName)) return true;
  if (toolName.startsWith('mcp__')) return true;
  return false;
};

// ===== 1. Diff 预览生成 =====

const truncateDiffText = (text: string): string => {
  if (text.length <= DIFF_PREVIEW_MAX_CHARS) return text;
  return `${text.slice(0, DIFF_PREVIEW_MAX_CHARS)}\n...`;
};

const utf8BytesOf = (text: string): Uint8Array => {
  const bytes: number[] = [];
  for (let i: number = 0; i < text.length; i++) {
    let code: number = text.charCodeAt(i);
    if (code < 0x80) {
      bytes.push(code);
    } else if (code < 0x800) {
      bytes.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
    } else if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
      const next: number = text.charCodeAt(++i);
      const cp: number = 0x10000 + (((code - 0xd800) << 10) | (next - 0xdc00));
      bytes.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    } else {
      bytes.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
    }
  }
  return new Uint8Array(bytes);
};

export const buildUnifiedLineDiff = (
  beforeText: string | null,
  afterText: string | null,
  displayPath: string,
): string => {
  if (beforeText === null && afterText === null) return '';
  if (beforeText === null && afterText !== null) {
    const lines: string[] = afterText.split('\n');
    const out: string[] = [
      '--- /dev/null',
      `+++ ${displayPath}`,
      `@@ -0,0 +1,${lines.length} @@`,
    ];
    for (let i: number = 0; i < Math.min(lines.length, DIFF_PREVIEW_MAX_LINES); i++) {
      out.push(`+${lines[i]}`);
    }
    if (lines.length > DIFF_PREVIEW_MAX_LINES) {
      out.push(`... (${lines.length - DIFF_PREVIEW_MAX_LINES} more lines)`);
    }
    return truncateDiffText(out.join('\n'));
  }
  if (beforeText !== null && afterText === null) {
    const lines: string[] = beforeText.split('\n');
    const out: string[] = [
      `--- ${displayPath}`,
      '+++ /dev/null',
      `@@ -1,${lines.length} +0,0 @@`,
    ];
    for (let i: number = 0; i < Math.min(lines.length, DIFF_PREVIEW_MAX_LINES); i++) {
      out.push(`-${lines[i]}`);
    }
    if (lines.length > DIFF_PREVIEW_MAX_LINES) {
      out.push(`... (${lines.length - DIFF_PREVIEW_MAX_LINES} more lines)`);
    }
    return truncateDiffText(out.join('\n'));
  }
  const oldLines: string[] = (beforeText ?? '').split('\n');
  const newLines: string[] = (afterText ?? '').split('\n');
  let prefix: number = 0;
  while (prefix < oldLines.length && prefix < newLines.length && oldLines[prefix] === newLines[prefix]) {
    prefix++;
  }
  let suffix: number = 0;
  while (
    suffix < oldLines.length - prefix
    && suffix < newLines.length - prefix
    && oldLines[oldLines.length - 1 - suffix] === newLines[newLines.length - 1 - suffix]
  ) {
    suffix++;
  }
  const oldMid: string[] = oldLines.slice(prefix, oldLines.length - suffix);
  const newMid: string[] = newLines.slice(prefix, newLines.length - suffix);
  if (oldMid.length === 0 && newMid.length === 0) return '';
  const out: string[] = [
    `--- ${displayPath}`,
    `+++ ${displayPath}`,
    `@@ -${prefix + 1},${Math.max(0, oldMid.length)} +${prefix + 1},${Math.max(0, newMid.length)} @@`,
  ];
  let rendered: number = 0;
  if (prefix > 0 && rendered < DIFF_PREVIEW_MAX_LINES) {
    out.push(` ${oldLines[prefix - 1]}`);
    rendered++;
  }
  for (const l of oldMid) {
    if (rendered >= DIFF_PREVIEW_MAX_LINES) break;
    out.push(`-${l}`);
    rendered++;
  }
  for (const l of newMid) {
    if (rendered >= DIFF_PREVIEW_MAX_LINES) break;
    out.push(`+${l}`);
    rendered++;
  }
  if (suffix > 0 && rendered < DIFF_PREVIEW_MAX_LINES) {
    out.push(` ${oldLines[oldLines.length - suffix]}`);
  }
  const totalMid: number = oldMid.length + newMid.length;
  if (totalMid > rendered) {
    out.push(`... (${totalMid - rendered} more lines)`);
  }
  return truncateDiffText(out.join('\n'));
};

const jsonStringField = (obj: JsonObject, key: string): string | null => {
  const val: JsonValue | undefined = obj[key];
  return typeof val === 'string' ? val : null;
};

const jsonBoolField = (obj: JsonObject, key: string): boolean => obj[key] === true;

const jsonIntField = (obj: JsonObject, key: string): number | null => {
  const val: JsonValue | undefined = obj[key];
  return typeof val === 'number' && Number.isFinite(val) ? Math.floor(val) : null;
};

export const buildWorkspaceDiffPreview = (
  toolName: string,
  input: JsonObject,
  readExistingText: (path: string) => string | null,
): string | null => {
  if (toolName === 'file_write') {
    const path: string | null = jsonStringField(input, 'path');
    const content: string | null = jsonStringField(input, 'content');
    if (path === null || content === null) return null;
    const before: string | null = readExistingText(path);
    const append: boolean = jsonBoolField(input, 'append');
    const after: string = append && before !== null ? `${before}${content}` : content;
    const diff: string = buildUnifiedLineDiff(before, after, `/workspace/${path.replace(/^\/+/, '')}`);
    return diff.length > 0 ? diff : null;
  }
  if (toolName === 'file_edit') {
    const path: string | null = jsonStringField(input, 'path');
    const oldText: string | null = jsonStringField(input, 'old_text');
    const newText: string | null = jsonStringField(input, 'new_text');
    if (path === null || oldText === null || newText === null || oldText.length === 0) return null;
    const before: string | null = readExistingText(path);
    if (before !== null) {
      const replaceAll: boolean = jsonBoolField(input, 'replace_all');
      const idx: number = before.indexOf(oldText);
      if (idx >= 0) {
        const after: string = replaceAll
          ? before.split(oldText).join(newText)
          : before.slice(0, idx) + newText + before.slice(idx + oldText.length);
        const diff: string = buildUnifiedLineDiff(before, after, `/workspace/${path.replace(/^\/+/, '')}`);
        return diff.length > 0 ? diff : null;
      }
    }
    const fallback: string = buildUnifiedLineDiff(oldText, newText, `/workspace/${path.replace(/^\/+/, '')}`);
    return fallback.length > 0 ? fallback : null;
  }
  if (toolName === 'file_move') {
    const src: string | null = jsonStringField(input, 'source_path');
    const dst: string | null = jsonStringField(input, 'target_path');
    if (src === null || dst === null) return null;
    return `--- /workspace/${src.replace(/^\/+/, '')}\n+++ /workspace/${dst.replace(/^\/+/, '')}\n@@ rename / move @@\n-${src}\n+${dst}`;
  }
  if (toolName === 'file_delete' || toolName === 'workspace_file_delete') {
    const path: string | null = jsonStringField(input, 'path');
    if (path === null) return null;
    const before: string | null = readExistingText(path);
    return buildUnifiedLineDiff(before ?? '(file)', null, `/workspace/${path.replace(/^\/+/, '')}`);
  }
  return null;
};

export const buildMemoryDiffPreview = (
  toolName: string,
  input: JsonObject,
  readMemoryById: (id: number) => string | null,
): string | null => {
  if (toolName === 'memory_write') {
    const content: string | null = jsonStringField(input, 'content');
    const scope: string = jsonStringField(input, 'type') ?? 'long_term';
    if (content === null) return null;
    return buildUnifiedLineDiff(null, content, `memory/${scope}`);
  }
  if (toolName === 'memory_delete') {
    const id: number | null = jsonIntField(input, 'id');
    if (id === null) return null;
    const before: string | null = readMemoryById(id);
    return buildUnifiedLineDiff(before ?? `(memory #${id})`, null, `memory/#${id}`);
  }
  if (toolName === 'memory_tool') {
    const action: string | null = jsonStringField(input, 'action');
    if (action === 'create') {
      const content: string | null = jsonStringField(input, 'content');
      const scope: string = jsonStringField(input, 'scope') ?? 'long_term';
      if (content === null) return null;
      return buildUnifiedLineDiff(null, content, `memory/${scope}`);
    }
    if (action === 'edit') {
      const id: number | null = jsonIntField(input, 'id');
      const content: string | null = jsonStringField(input, 'content');
      if (id === null || content === null) return null;
      const before: string | null = readMemoryById(id);
      return buildUnifiedLineDiff(before, content, `memory/#${id}`);
    }
    if (action === 'delete') {
      const id: number | null = jsonIntField(input, 'id');
      if (id === null) return null;
      const before: string | null = readMemoryById(id);
      return buildUnifiedLineDiff(before ?? `(memory #${id})`, null, `memory/#${id}`);
    }
  }
  return null;
};

// ===== 2. `.undo/` 快照与安全回滚 =====

export type WorkspaceUndoOperation = 'write' | 'edit' | 'move';

export interface WorkspaceUndoSnapshot {
  id: string;
  workspacePath: string;
  previousPath?: string;
  operation: WorkspaceUndoOperation;
  existedBefore: boolean;
  previousSha256: string | null;
  afterSha256: string;
  payloadFileName: string | null;
  payloadBytes: number;
  createdAtMs: number;
}

export interface WorkspaceUndoPreState {
  targetPath: string;
  previousPath?: string;
  operation: WorkspaceUndoOperation;
  existedBefore: boolean;
  previousBytes: Uint8Array | null;
  previousSha256: string | null;
}

export interface WorkspaceUndoResult {
  ok: boolean;
  undone?: boolean;
  conflict?: boolean;
  path?: string;
  operation?: WorkspaceUndoOperation;
  restoredPreviousPath?: string;
  expectedSha256?: string;
  actualSha256?: string;
  error?: string;
}

export interface WorkspaceUndoStoreDeps {
  port: WorkspaceFsPort;
  rootAbs: string;
  sha256Hex: (bytes: Uint8Array) => string;
  nowMs?: () => number;
  makeId?: () => string;
}

export class WorkspaceUndoStore {
  private readonly port: WorkspaceFsPort;
  private readonly rootAbs: string;
  private readonly undoDirAbs: string;
  private readonly indexPathAbs: string;
  private readonly sha256Hex: (bytes: Uint8Array) => string;
  private readonly nowMs: () => number;
  private readonly makeId: () => string;

  constructor(deps: WorkspaceUndoStoreDeps) {
    this.port = deps.port;
    this.rootAbs = deps.rootAbs.replace(/\/+$/, '');
    this.undoDirAbs = `${this.rootAbs}/.undo`;
    this.indexPathAbs = `${this.undoDirAbs}/index.json`;
    this.sha256Hex = deps.sha256Hex;
    this.nowMs = deps.nowMs ?? ((): number => Date.now());
    this.makeId = deps.makeId ?? ((): string => `undo-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`);
  }

  private absForWorkspacePath(normalized: string): string {
    return normalized === '.' ? this.rootAbs : `${this.rootAbs}/${normalized}`;
  }

  private ensureUndoDir(): void {
    this.port.mkdirs(this.undoDirAbs);
  }

  private loadSnapshots(): WorkspaceUndoSnapshot[] {
    if (!this.port.isFile(this.indexPathAbs)) return [];
    try {
      const parsed: Object = JSON.parse(this.port.readText(this.indexPathAbs)) as Object;
      return Array.isArray(parsed) ? (parsed as WorkspaceUndoSnapshot[]) : [];
    } catch (_e) {
      return [];
    }
  }

  private saveSnapshots(snapshots: WorkspaceUndoSnapshot[]): void {
    this.ensureUndoDir();
    this.port.writeText(this.indexPathAbs, JSON.stringify(snapshots, null, 2), false);
  }

  private pruneExpiredInternal(snapshots: WorkspaceUndoSnapshot[], now: number): WorkspaceUndoSnapshot[] {
    const kept: WorkspaceUndoSnapshot[] = [];
    for (const s of snapshots) {
      if (now - s.createdAtMs > WORKSPACE_UNDO_TTL_MS) {
        if (s.payloadFileName !== null) {
          this.port.deleteRecursively(`${this.undoDirAbs}/${s.payloadFileName}`);
        }
      } else {
        kept.push(s);
      }
    }
    return kept;
  }

  private enforcePerPathLimit(path: string, snapshots: WorkspaceUndoSnapshot[]): WorkspaceUndoSnapshot[] {
    const next: WorkspaceUndoSnapshot[] = snapshots.slice();
    let indices: number[] = [];
    for (let i: number = 0; i < next.length; i++) {
      if (next[i].workspacePath === path) indices.push(i);
    }
    while (indices.length > WORKSPACE_UNDO_MAX_PER_PATH) {
      const evictIdx: number = indices[0];
      const evicted: WorkspaceUndoSnapshot = next[evictIdx];
      next.splice(evictIdx, 1);
      if (evicted.payloadFileName !== null) {
        this.port.deleteRecursively(`${this.undoDirAbs}/${evicted.payloadFileName}`);
      }
      indices = [];
      for (let i: number = 0; i < next.length; i++) {
        if (next[i].workspacePath === path) indices.push(i);
      }
    }
    return next;
  }

  pruneExpiredSnapshots(): number {
    const now: number = this.nowMs();
    const existing: WorkspaceUndoSnapshot[] = this.loadSnapshots();
    if (existing.length === 0) return 0;
    const kept: WorkspaceUndoSnapshot[] = this.pruneExpiredInternal(existing, now);
    if (kept.length !== existing.length) {
      this.saveSnapshots(kept);
    }
    return existing.length - kept.length;
  }

  listSnapshots(rawPath?: string): WorkspaceUndoSnapshot[] {
    const all: WorkspaceUndoSnapshot[] = this.loadSnapshots();
    if (rawPath === undefined) return all;
    const normalized: string = normalizeWorkspacePath(rawPath);
    return all.filter((s: WorkspaceUndoSnapshot): boolean => s.workspacePath === normalized);
  }

  capturePreState(
    rawTargetPath: string,
    operation: WorkspaceUndoOperation,
    rawPreviousPath?: string,
  ): WorkspaceUndoPreState | null {
    const targetPath: string = normalizeWorkspacePath(rawTargetPath);
    const previousPath: string | undefined = rawPreviousPath !== undefined
      ? normalizeWorkspacePath(rawPreviousPath) : undefined;
    if (targetPath === '.undo' || targetPath.startsWith('.undo/')) return null;

    if (operation === 'move' && previousPath !== undefined) {
      const srcAbs: string = this.absForWorkspacePath(previousPath);
      if (!this.port.isFile(srcAbs)) return null;
      const bytes: Uint8Array = this.port.readBytes(srcAbs);
      return {
        targetPath,
        previousPath,
        operation,
        existedBefore: true,
        previousBytes: bytes.length <= WORKSPACE_UNDO_MAX_BYTES ? bytes : null,
        previousSha256: this.sha256Hex(bytes),
      };
    }

    const targetAbs: string = this.absForWorkspacePath(targetPath);
    if (!this.port.isFile(targetAbs)) {
      return {
        targetPath,
        operation,
        existedBefore: false,
        previousBytes: null,
        previousSha256: null,
      };
    }
    const size: number = this.port.fileSize(targetAbs);
    if (size > WORKSPACE_UNDO_MAX_BYTES) {
      return {
        targetPath,
        operation,
        existedBefore: true,
        previousBytes: null,
        previousSha256: null,
      };
    }
    const bytes: Uint8Array = this.port.readBytes(targetAbs);
    return {
      targetPath,
      operation,
      existedBefore: true,
      previousBytes: bytes,
      previousSha256: this.sha256Hex(bytes),
    };
  }

  commitSnapshot(pre: WorkspaceUndoPreState | null): WorkspaceUndoSnapshot | null {
    if (pre === null) return null;
    const now: number = this.nowMs();
    let snapshots: WorkspaceUndoSnapshot[] = this.pruneExpiredInternal(this.loadSnapshots(), now);

    const targetAbs: string = this.absForWorkspacePath(pre.targetPath);
    if (!this.port.isFile(targetAbs)) return null;
    const afterSize: number = this.port.fileSize(targetAbs);
    if (afterSize > WORKSPACE_UNDO_MAX_BYTES) {
      this.saveSnapshots(snapshots);
      return null;
    }
    if (pre.existedBefore && pre.previousBytes === null) {
      // 前置文件超过 5 MiB 上限，跳过快照落盘
      this.saveSnapshots(snapshots);
      return null;
    }

    const afterBytes: Uint8Array = this.port.readBytes(targetAbs);
    const id: string = this.makeId();
    let payloadFileName: string | null = null;
    let payloadBytes: number = 0;

    if (pre.operation !== 'move' && pre.existedBefore && pre.previousBytes !== null) {
      this.ensureUndoDir();
      payloadFileName = `${id}.bin`;
      this.port.writeBytes(`${this.undoDirAbs}/${payloadFileName}`, pre.previousBytes, false);
      payloadBytes = pre.previousBytes.length;
    }

    const snapshot: WorkspaceUndoSnapshot = {
      id,
      workspacePath: pre.targetPath,
      previousPath: pre.previousPath,
      operation: pre.operation,
      existedBefore: pre.existedBefore,
      previousSha256: pre.previousSha256,
      afterSha256: this.sha256Hex(afterBytes),
      payloadFileName,
      payloadBytes,
      createdAtMs: now,
    };
    snapshots.push(snapshot);
    snapshots = this.enforcePerPathLimit(pre.targetPath, snapshots);
    this.saveSnapshots(snapshots);
    return snapshot;
  }

  undoLastChange(rawPath: string, expectedSnapshotId?: string): WorkspaceUndoResult {
    const now: number = this.nowMs();
    let snapshots: WorkspaceUndoSnapshot[] = this.pruneExpiredInternal(this.loadSnapshots(), now);

    let targetPath: string;
    try {
      targetPath = normalizeWorkspacePath(rawPath);
    } catch (_e) {
      this.saveSnapshots(snapshots);
      return { ok: false, error: '工作区路径无效' };
    }

    let matchIndex: number = -1;
    for (let i: number = snapshots.length - 1; i >= 0; i--) {
      if (snapshots[i].workspacePath === targetPath) {
        matchIndex = i;
        break;
      }
    }
    if (matchIndex < 0) {
      this.saveSnapshots(snapshots);
      return { ok: false, path: `/workspace/${targetPath}`, error: '该文件暂无可用的 .undo 快照（可能已过期或被清理）' };
    }

    const snapshot: WorkspaceUndoSnapshot = snapshots[matchIndex];
    if (expectedSnapshotId !== undefined && expectedSnapshotId !== snapshot.id) {
      this.saveSnapshots(snapshots);
      return { ok: false, conflict: true, path: `/workspace/${targetPath}`, error: '该文件已有更新的修改记录，拒绝旧快照撤销' };
    }

    const targetAbs: string = this.absForWorkspacePath(snapshot.workspacePath);
    if (!this.port.isFile(targetAbs)) {
      this.saveSnapshots(snapshots);
      return { ok: false, conflict: true, path: `/workspace/${snapshot.workspacePath}`, error: '目标文件已不存在，拒绝撤销' };
    }

    const currentBytes: Uint8Array = this.port.readBytes(targetAbs);
    const actualSha: string = this.sha256Hex(currentBytes);
    if (actualSha !== snapshot.afterSha256) {
      this.saveSnapshots(snapshots);
      return {
        ok: false,
        conflict: true,
        path: `/workspace/${snapshot.workspacePath}`,
        expectedSha256: snapshot.afterSha256,
        actualSha256: actualSha,
        error: '目标文件在 Agent 写入后已被外部修改，SHA-256 校验不一致，拒绝覆盖',
      };
    }

    if (snapshot.operation === 'move' && snapshot.previousPath !== undefined) {
      const srcAbs: string = this.absForWorkspacePath(snapshot.previousPath);
      if (this.port.exists(srcAbs)) {
        this.saveSnapshots(snapshots);
        return {
          ok: false,
          conflict: true,
          path: `/workspace/${snapshot.workspacePath}`,
          error: `原路径 /workspace/${snapshot.previousPath} 已存在，拒绝撤销移动`,
        };
      }
      const parentIdx: number = srcAbs.lastIndexOf('/');
      if (parentIdx > 0) this.port.mkdirs(srcAbs.slice(0, parentIdx));
      this.port.rename(targetAbs, srcAbs);
    } else if (snapshot.existedBefore) {
      if (snapshot.payloadFileName === null || !this.port.isFile(`${this.undoDirAbs}/${snapshot.payloadFileName}`)) {
        return { ok: false, conflict: true, path: `/workspace/${snapshot.workspacePath}`, error: '快照载荷已丢失' };
      }
      const prevBytes: Uint8Array = this.port.readBytes(`${this.undoDirAbs}/${snapshot.payloadFileName}`);
      if (snapshot.previousSha256 !== null && this.sha256Hex(prevBytes) !== snapshot.previousSha256) {
        return { ok: false, conflict: true, path: `/workspace/${snapshot.workspacePath}`, error: '快照载荷完整性校验失败' };
      }
      this.port.writeBytes(targetAbs, prevBytes, false);
    } else {
      this.port.deleteRecursively(targetAbs);
    }

    if (snapshot.payloadFileName !== null) {
      this.port.deleteRecursively(`${this.undoDirAbs}/${snapshot.payloadFileName}`);
    }
    snapshots.splice(matchIndex, 1);
    this.saveSnapshots(snapshots);

    return {
      ok: true,
      undone: true,
      operation: snapshot.operation,
      path: `/workspace/${snapshot.workspacePath}`,
      restoredPreviousPath: snapshot.previousPath !== undefined ? `/workspace/${snapshot.previousPath}` : undefined,
    };
  }
}

export const extractUndoReceiptFromToolPart = (part: UIMessagePartTool): { snapshotId: string; path: string } | null => {
  if (part.approvalState.type !== 'approved') return null;
  for (const out of part.output) {
    if (out.type !== 'text') continue;
    try {
      const parsed: JsonObject = JSON.parse(out.text) as JsonObject;
      const snapId: JsonValue | undefined = parsed['undo_snapshot_id'];
      const pathVal: JsonValue | undefined = parsed['path'];
      if (typeof snapId === 'string' && snapId.length > 0 && typeof pathVal === 'string' && pathVal.length > 0) {
        return { snapshotId: snapId, path: pathVal };
      }
    } catch (_e) {
      // 非 JSON 输出忽略
    }
  }
  return null;
};

// ===== 3. outcome_unknown 中断工具状态与人工三态恢复 =====

export const OUTCOME_UNKNOWN_OUTPUT_JSON: string =
  '{"status":"outcome_unknown","error":"Action interrupted before confirmation; automatic replay forbidden."}';

export const OUTCOME_UNKNOWN_REASON: string = '操作结果未知，禁止自动重放';

export const readToolOutcomeState = (part: UIMessagePartTool): ToolOutcomeState | null => {
  const val: JsonValue | undefined = part.metadata?.[TOOL_OUTCOME_METADATA_KEY];
  if (val === 'outcome_unknown' || val === 'reconciled_applied' || val === 'reconciled_not_applied' || val === 'recheck_requested') {
    return val;
  }
  return null;
};

/**
 * 中断恢复收口：对已获批（approved）且无终态输出的副作用工具，严禁自动重放或伪造失败，
 * 标记为 `outcome_unknown` 交由用户三态核对（未生效 / 核查当前状态 / 已生效）。
 */
export const markInterruptedSideEffectToolsUnknown = (message: UIMessage): { message: UIMessage; changed: boolean } => {
  let changed: boolean = false;
  const nextParts: UIMessagePart[] = message.parts.map((part: UIMessagePart): UIMessagePart => {
    if (part.type !== 'tool') return part;
    const tool: UIMessagePartTool = part;
    if (tool.output.length > 0) return part;
    if (tool.approvalState.type !== 'approved' || !isSideEffectToolName(tool.toolName)) return part;
    changed = true;
    return {
      type: 'tool',
      toolCallId: tool.toolCallId,
      toolName: tool.toolName,
      input: tool.input,
      output: [{ type: 'text', text: OUTCOME_UNKNOWN_OUTPUT_JSON, metadata: null }],
      approvalState: { type: 'denied', reason: OUTCOME_UNKNOWN_REASON },
      metadata: {
        ...(tool.metadata ?? {}),
        [TOOL_OUTCOME_METADATA_KEY]: 'outcome_unknown',
      },
    };
  });
  if (!changed) return { message, changed: false };
  return {
    changed: true,
    message: {
      id: message.id,
      role: message.role,
      parts: nextParts,
      annotations: message.annotations,
      createdAt: message.createdAt,
      finishedAt: message.finishedAt,
      modelId: message.modelId,
      usage: message.usage,
      translation: message.translation,
    },
  };
};

export const applyToolOutcomeResolutionToConversation = (
  conv: Conversation,
  toolCallId: string,
  resolution: ToolOutcomeResolution,
  locator?: ToolApprovalPartLocator,
): Conversation => {
  const nextOutcome: ToolOutcomeState = resolution === 'applied'
    ? 'reconciled_applied'
    : resolution === 'recheck_requested'
      ? 'recheck_requested'
      : 'reconciled_not_applied';
  const noteText: string = resolution === 'applied'
    ? '{"status":"reconciled_applied","note":"用户已确认该操作实际生效"}'
    : resolution === 'recheck_requested'
      ? '{"status":"recheck_requested","note":"用户要求先核查当前状态"}'
      : '{"status":"reconciled_not_applied","note":"用户已确认该操作未生效"}';

  let changed: boolean = false;
  const nextNodes: MessageNode[] = conv.messageNodes.map((node: MessageNode): MessageNode => {
    let nodeChanged: boolean = false;
    const nextMessages: UIMessage[] = node.messages.map((msg: UIMessage): UIMessage => {
      if (locator !== undefined && msg.id !== locator.messageId) return msg;
      let msgChanged: boolean = false;
      const nextParts: UIMessagePart[] = msg.parts.map((part: UIMessagePart, idx: number): UIMessagePart => {
        if (part.type !== 'tool') return part;
        const matched: boolean = locator !== undefined
          ? idx === locator.partIndex && (toolCallId.length === 0 || part.toolCallId === toolCallId)
          : part.toolCallId === toolCallId;
        if (!matched) return part;
        msgChanged = true;
        return {
          type: 'tool',
          toolCallId: part.toolCallId,
          toolName: part.toolName,
          input: part.input,
          output: [{ type: 'text', text: noteText, metadata: null }],
          approvalState: part.approvalState,
          metadata: {
            ...(part.metadata ?? {}),
            [TOOL_OUTCOME_METADATA_KEY]: nextOutcome,
          },
        };
      });
      if (!msgChanged) return msg;
      nodeChanged = true;
      changed = true;
      return {
        id: msg.id,
        role: msg.role,
        parts: nextParts,
        annotations: msg.annotations,
        createdAt: msg.createdAt,
        finishedAt: msg.finishedAt,
        modelId: msg.modelId,
        usage: msg.usage,
        translation: msg.translation,
      };
    });
    return nodeChanged ? { id: node.id, messages: nextMessages, selectIndex: node.selectIndex } : node;
  });

  if (!changed) return conv;
  return patchConversation(conv, { messageNodes: nextNodes });
};

export const utf8BytesForUndoSha = utf8BytesOf;
