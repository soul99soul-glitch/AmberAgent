// ToolDiffPreviewSupport — P1-3 工具审批 Diff 预览装载与 `.undo/` 撤销动作桥接

import type { JsonObject, JsonValue, MemoryRecord, UIMessagePartTool, WorkspaceUndoResult } from '@amber/chat-domain';
import {
  toolInputAsJson,
  buildWorkspaceDiffPreview,
  buildMemoryDiffPreview,
  extractUndoReceiptFromToolPart,
} from '@amber/chat-domain';
import { createEntryWorkspaceManager, createEntryWorkspaceUndoStore } from './WorkspaceStore.ets';
import { getMemoryLibraryOps } from './MemoryStore.ets';

export const loadToolDiffPreviewAsync = async (part: UIMessagePartTool): Promise<string | null> => {
  const name: string = part.toolName;
  const input: JsonObject = toolInputAsJson(part);

  if (name === 'file_write' || name === 'file_edit' || name === 'file_move'
    || name === 'file_delete' || name === 'workspace_file_delete') {
    const rawPath: JsonValue | undefined = input['path'] ?? input['source_path'];
    const targetPath: string = typeof rawPath === 'string' ? rawPath.trim() : '';
    let existing: string | null = null;
    if (targetPath.length > 0 && name !== 'file_move') {
      try {
        existing = await createEntryWorkspaceManager().readText(targetPath);
      } catch (_e) {
        existing = null;
      }
    }
    return buildWorkspaceDiffPreview(name, input, (): string | null => existing);
  }

  if (name === 'memory_write' || name === 'memory_delete' || name === 'memory_tool') {
    const rawId: JsonValue | undefined = input['id'];
    const targetId: number | null = typeof rawId === 'number' && Number.isFinite(rawId) ? Math.floor(rawId) : null;
    let existingContent: string | null = null;
    if (targetId !== null) {
      try {
        const records: MemoryRecord[] = await getMemoryLibraryOps().getAllRecords();
        const found: MemoryRecord | undefined = records.find((r: MemoryRecord): boolean => r.id === targetId);
        existingContent = found !== undefined ? found.content : null;
      } catch (_e) {
        existingContent = null;
      }
    }
    return buildMemoryDiffPreview(name, input, (): string | null => existingContent);
  }

  return null;
};

export const undoWorkspaceToolChange = async (part: UIMessagePartTool): Promise<WorkspaceUndoResult> => {
  const receipt = extractUndoReceiptFromToolPart(part);
  if (receipt === null) {
    return { ok: false, error: '该工具调用未生成 .undo 快照回执' };
  }
  return createEntryWorkspaceUndoStore().undoLastChange(receipt.path, receipt.snapshotId);
};
