// novel_workspace_tools.test.ts — C5 小说工作区工具目录

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  createNovelWorkspaceTools,
  novelAuditToolResult,
} from '../main/ets/chat/novel_workspace_tools.ts';
import type {
  NovelWorkspaceToolPort,
} from '../main/ets/chat/novel_workspace_tools.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';
import type { NovelAuditReport, AbortSignalLike } from '@amber/deepread-domain';
import type { UIMessagePart, UIMessagePartText } from '../main/ets/chat/message.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';

const tool = (tools: AgentTool[], name: string): AgentTool => {
  const found: AgentTool | undefined = tools.find((candidate: AgentTool): boolean => candidate.name === name);
  assert.ok(found !== undefined, `${name} is registered`);
  return found as AgentTool;
};

const jsonOutput = (parts: UIMessagePart[]): JsonObject => {
  const part: UIMessagePartText = parts[0] as UIMessagePartText;
  return JSON.parse(part.text) as JsonObject;
};

interface PortCall {
  name: string;
  input: JsonObject | null;
}

const makePort = (calls: PortCall[]): NovelWorkspaceToolPort => ({
  list(input): Promise<JsonObject> {
    calls.push({ name: 'list', input: input as JsonObject });
    return Promise.resolve({ operation: 'list' });
  },
  read(input): Promise<JsonObject> {
    calls.push({ name: 'read', input: input as unknown as JsonObject });
    return Promise.resolve({ operation: 'read' });
  },
  grep(input): Promise<JsonObject> {
    calls.push({ name: 'grep', input: input as unknown as JsonObject });
    return Promise.resolve({ operation: 'grep' });
  },
  status(): Promise<JsonObject> {
    calls.push({ name: 'status', input: null });
    return Promise.resolve({ operation: 'status' });
  },
  write(input): Promise<JsonObject> {
    calls.push({ name: 'write', input: input as unknown as JsonObject });
    return Promise.resolve({ operation: 'write' });
  },
  audit(): Promise<JsonObject> {
    calls.push({ name: 'audit', input: null });
    return Promise.resolve({ operation: 'audit', ok: true, issue_count: 0, issues: [] });
  },
  ingestSettingProposals(sourceMessageId, text): Promise<JsonObject> {
    calls.push({
      name: 'ingestSettingProposals',
      input: { source_message_id: sourceMessageId, text } as JsonObject,
    });
    return Promise.resolve({ operation: 'ingest', ingested: 0, titles: [] });
  },
});

describe('createNovelWorkspaceTools', () => {
  it('registers C5 workspace tools plus continuity audit and setting proposal ingest', () => {
    const tools: AgentTool[] = createNovelWorkspaceTools(makePort([]));
    assert.deepEqual(tools.map((item: AgentTool): string => item.name), [
      'novel_workspace_list',
      'novel_workspace_read',
      'novel_workspace_grep',
      'novel_workspace_status',
      'novel_workspace_write',
      'novel_continuity_audit',
      'novel_ingest_setting_proposals',
    ]);
    ['novel_workspace_list', 'novel_workspace_read', 'novel_workspace_grep', 'novel_workspace_status']
      .forEach((name: string): void => {
        const item: AgentTool = tool(tools, name);
        assert.equal(item.needsApproval, false, name);
        assert.equal(item.allowsAutoApproval, true, name);
        assert.equal(item.mandatoryApproval, false, name);
      });
    const write: AgentTool = tool(tools, 'novel_workspace_write');
    assert.equal(write.needsApproval, true);
    assert.equal(write.allowsAutoApproval, false);
    assert.equal(write.mandatoryApproval, false);
    const ingest: AgentTool = tool(tools, 'novel_ingest_setting_proposals');
    assert.equal(ingest.needsApproval, true);
    assert.equal(ingest.allowsAutoApproval, false);
  });

  it('delegates each call to the bound port and emits the existing JSON text payload', async () => {
    const calls: PortCall[] = [];
    const tools: AgentTool[] = createNovelWorkspaceTools(makePort(calls));
    const listInput: JsonObject = { path: 'branches/main/chapters' };
    const readInput: JsonObject = { path: 'branches/main/chapters/001-open.md' };
    const grepInput: JsonObject = { query: '雨', max_results: 5 };
    const writeInput: JsonObject = {
      proposal_id: 'proposal-001',
      patches: [{
        operation: 'write', path: 'branches/main/chapters/001-open.md', content: 'new prose',
      }],
    };
    assert.deepEqual(jsonOutput(await tool(tools, 'novel_workspace_list').execute(listInput)), { operation: 'list' });
    assert.deepEqual(jsonOutput(await tool(tools, 'novel_workspace_read').execute(readInput)), { operation: 'read' });
    assert.deepEqual(jsonOutput(await tool(tools, 'novel_workspace_grep').execute(grepInput)), { operation: 'grep' });
    assert.deepEqual(jsonOutput(await tool(tools, 'novel_workspace_status').execute({})), { operation: 'status' });
    assert.deepEqual(jsonOutput(await tool(tools, 'novel_workspace_write').execute(writeInput)), { operation: 'write' });
    assert.deepEqual(calls, [
      { name: 'list', input: listInput },
      { name: 'read', input: readInput },
      { name: 'grep', input: grepInput },
      { name: 'status', input: null },
      { name: 'write', input: writeInput },
    ]);
  });
});

describe('novel continuity audit tool report', () => {
  const report = (): NovelAuditReport => ({
    ok: true, issues: [], raw: '{"issues":[]}',
    coverage: { checkedChars: 100, totalChars: 100, complete: true },
    cancelled: false, invalidEvidenceCount: 0,
    blocks: [{ chapterId: 'chapter-7', sourceDigest: 'digest', start: 0, end: 100, status: 'checked', error: null }],
  });
  const executeAudit = async (value: NovelAuditReport): Promise<JsonObject> => {
    const port: NovelWorkspaceToolPort = { ...makePort([]), audit: async (): Promise<JsonObject> => novelAuditToolResult(value) };
    return jsonOutput(await tool(createNovelWorkspaceTools(port), 'novel_continuity_audit').execute({}));
  };
  it('keeps failed block causes and partial coverage when the valid issue list is empty', async () => {
    const value = report(); value.ok = false;
    value.coverage = { checkedChars: 100, totalChars: 200, complete: false };
    value.blocks.push({ chapterId: 'chapter-8', sourceDigest: 'd2', start: 0, end: 100, status: 'failed', error: 'provider disconnected' });
    const output = await executeAudit(value);
    assert.equal(output.ok, false); assert.deepEqual(output.issues, []); assert.equal(output.issue_count, 0);
    assert.deepEqual(output.coverage, { checked_chars: 100, total_chars: 200, complete: false });
    assert.deepEqual(output.failed_blocks, [{ id: 'chapter-8', start: 0, end: 100, status: 'failed', error: 'provider disconnected' }]);
  });
  it('keeps invalid evidence as an explicit cause rather than an empty passed audit', async () => {
    const value = report(); value.ok = false; value.invalidEvidenceCount = 2;
    const output = await executeAudit(value);
    assert.equal(output.ok, false); assert.deepEqual(output.issues, []);
    assert.equal(output.invalid_evidence_count, 2); assert.deepEqual(output.failed_blocks, []);
  });
  it('keeps cancelled and unexamined ranges in actual JSON tool output', async () => {
    const value = report(); value.ok = false; value.cancelled = true;
    value.coverage = { checkedChars: 0, totalChars: 100, complete: false };
    value.blocks[0].status = 'cancelled'; value.blocks[0].error = '审校已取消，该范围尚未检查';
    const output = await executeAudit(value);
    assert.equal(output.ok, false); assert.equal(output.cancelled, true); assert.deepEqual(output.issues, []);
    assert.deepEqual(output.failed_blocks, [{ id: 'chapter-7', start: 0, end: 100, status: 'cancelled', error: '审校已取消，该范围尚未检查' }]);
  });
  it('preserves existing string issue formatting and a genuinely complete empty report', async () => {
    const value = report();
    const emptyOutput = await executeAudit(value);
    assert.equal(emptyOutput.ok, true); assert.deepEqual(emptyOutput.issues, []);
    assert.deepEqual(emptyOutput.coverage, { checked_chars: 100, total_chars: 100, complete: true });
    assert.equal(emptyOutput.cancelled, false); assert.equal(emptyOutput.invalid_evidence_count, 0);
    value.ok = false;
    value.issues = [{ severity: 'major', chapterRef: '第7章', summary: '名字不一致', suggestion: '统一名字',
      chapterId: 'chapter-7', sourceDigest: 'digest', quote: '原文', start: 0, end: 2 }];
    const output = await executeAudit(value);
    assert.deepEqual(output.issues, ['[major] 第7章: 名字不一致 → 统一名字']);
  });
});

it('continuity audit tool forwards the exact parent cancellation signal to its bound host', async () => {
  const signal: AbortSignalLike = {
    aborted: false,
    addEventListener: (): void => {},
    removeEventListener: (): void => {},
  };
  let received: AbortSignalLike | undefined;
  const port: NovelWorkspaceToolPort = {
    ...makePort([]),
    audit: async (parentSignal?: AbortSignalLike): Promise<JsonObject> => {
      received = parentSignal;
      return { ok: false, cancelled: parentSignal?.aborted ?? false };
    },
  };
  const audit = tool(createNovelWorkspaceTools(port), 'novel_continuity_audit');
  await audit.execute({}, signal);
  assert.equal(received, signal);
});
