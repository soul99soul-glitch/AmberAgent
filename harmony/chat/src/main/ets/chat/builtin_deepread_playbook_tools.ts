// builtin_deepread_playbook_tools — Deep Read Playbook 工具四件(D-127)
//
// Android 基准(逐字锚点):
//   core/ai/tools/DeepReadPlaybookTools.kt(全文 111 行):
//     deep_read_playbook_read(:19-28)/update(:30-54)/restore_default
//     (:56-66)/restore_previous(:68-78)+ toJson(:81-88)/toToolJson
//     (:90-100)/stringProp(:102-105)/objectOrEmpty(:107-108)/string
//     (:110-111)
//   LocalTools.kt:187 — 无条件 addAll(deepReadPlaybookTools.getTools())
//   feature/board/impl/.../DeepReadPlaybookRepository.kt — 仓库本体在
//     deepread HAR(agent/playbook.ts,D-127);本文件仅工具面 + 端口契约
//
// 偏差适配登记:
//   - repository(仓库实现)→ DeepReadPlaybookPort 注入(entry 将 deepread
//     HAR 仓库结构直配为端口;可空字段单结构形状两侧一致 — ArkTS 判别
//     联合收窄限制,登记)
//   - Result.fold → DeepReadPlaybookResult 判别联合;onFailure error =
//     exception.message(error::class.simpleName 兜底无对应,仓库侧消息
//     恒非空,登记)
//   - JsonObject.string(trim + takeIf isNotBlank)→ inputNonBlankString;
//     orEmpty() 空串落 repository(冲突/过短错误路径同 Android)

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';

// ===== 端口契约(deepread HAR agent/playbook.ts 结构镜像) =====

export interface DeepReadPlaybookSnapshot {
  revision: string;
  markdown: string;
  updatedAt: number;
}

export type DeepReadPlaybookResult = {
  ok: boolean;
  snapshot: DeepReadPlaybookSnapshot | null;
  error: string | null;
};

export interface DeepReadPlaybookPort {
  read(): Promise<DeepReadPlaybookSnapshot>;
  update(
    baseRevision: string, changeSummary: string, updatedMarkdown: string,
  ): Promise<DeepReadPlaybookResult>;
  restoreDefault(): Promise<DeepReadPlaybookSnapshot>;
  restorePrevious(): Promise<DeepReadPlaybookResult>;
}

// ===== 输入解析(:107-111) =====

const asObject = (input: JsonValue): JsonObject => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return {};
  return input as JsonObject;
};

// JsonObject.string(name):contentOrNull?.trim()?.takeIf { isNotBlank() }
const inputNonBlankString = (input: JsonValue, key: string): string | null => {
  const v: JsonValue | undefined = asObject(input)[key];
  if (typeof v !== 'string') return null;
  const trimmed: string = v.trim();
  return trimmed.length > 0 ? trimmed : null;
};

// ===== payload(toJson :81-88 / toToolJson :90-100) =====

const snapshotJson = (
  snap: DeepReadPlaybookSnapshot, status: string = 'ok',
): JsonObject => ({
  status,
  revision: snap.revision,
  updated_at: snap.updatedAt,
  markdown: snap.markdown,
});

// 可空字段单结构(ArkTS 判别联合收窄限制,同 deepread HAR 登记)
const resultJson = (r: DeepReadPlaybookResult, successStatus: string): JsonObject => {
  if (r.ok && r.snapshot !== null) return snapshotJson(r.snapshot, successStatus);
  return { status: 'rejected', error: r.error ?? '' };
};

const textPart = (payload: JsonObject): UIMessagePart[] => [
  { type: 'text', text: JSON.stringify(payload), metadata: null },
];

// ===== 四件(DeepReadPlaybookTools.kt:17 getTools 序) =====

export const createDeepReadPlaybookTools = (
  repository: DeepReadPlaybookPort,
): AgentTool[] => [
  // :19-28
  makeAgentTool({
    name: 'deep_read_playbook_read',
    description: 'Read the local Deep Read Playbook markdown and revision. Use when the user asks to inspect Deep Read rules or preferences.',
    parameters: () => makeInputSchemaObj({}),
    allowsAutoApproval: true,
    execute: async (): Promise<UIMessagePart[]> => {
      const snapshot: DeepReadPlaybookSnapshot = await repository.read();
      return textPart(snapshotJson(snapshot));
    },
  }),
  // :30-54
  makeAgentTool({
    name: 'deep_read_playbook_update',
    description: 'Update the local Deep Read Playbook only after the user explicitly asks to change Deep Read rules. Requires base_revision, change_summary, and full updated_markdown.',
    parameters: () => makeInputSchemaObj(
      {
        base_revision: {
          type: 'string',
          description: 'Revision returned by deep_read_playbook_read.',
        },
        change_summary: {
          type: 'string',
          description: 'Short explanation of the requested change.',
        },
        updated_markdown: {
          type: 'string',
          description: 'Full replacement markdown for the Playbook.',
        },
      },
      ['base_revision', 'change_summary', 'updated_markdown'],
    ),
    needsApproval: true,
    allowsAutoApproval: false,
    execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
      const result: DeepReadPlaybookResult = await repository.update(
        inputNonBlankString(input, 'base_revision') ?? '',
        inputNonBlankString(input, 'change_summary') ?? '',
        inputNonBlankString(input, 'updated_markdown') ?? '',
      );
      return textPart(resultJson(result, 'updated'));
    },
  }),
  // :56-66
  makeAgentTool({
    name: 'deep_read_playbook_restore_default',
    description: 'Restore the built-in default Deep Read Playbook after explicit user request.',
    parameters: () => makeInputSchemaObj({}),
    needsApproval: true,
    allowsAutoApproval: false,
    execute: async (): Promise<UIMessagePart[]> => {
      const snapshot: DeepReadPlaybookSnapshot = await repository.restoreDefault();
      return textPart(snapshotJson(snapshot, 'restored_default'));
    },
  }),
  // :68-78
  makeAgentTool({
    name: 'deep_read_playbook_restore_previous',
    description: 'Restore the latest previous Deep Read Playbook snapshot after explicit user request.',
    parameters: () => makeInputSchemaObj({}),
    needsApproval: true,
    allowsAutoApproval: false,
    execute: async (): Promise<UIMessagePart[]> => {
      const result: DeepReadPlaybookResult = await repository.restorePrevious();
      return textPart(resultJson(result, 'restored_previous'));
    },
  }),
];
