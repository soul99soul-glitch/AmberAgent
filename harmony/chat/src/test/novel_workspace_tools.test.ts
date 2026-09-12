// novel_workspace_tools.test.ts — C5 小说工作区工具目录

import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  createNovelWorkspaceTools,
} from '../main/ets/chat/novel_workspace_tools.ts';
import type {
  NovelWorkspaceToolPort,
} from '../main/ets/chat/novel_workspace_tools.ts';
import type { JsonObject } from '../main/ets/chat/json.ts';
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

  it('publishes complete object schemas, including discriminated write/delete patches', () => {
    const tools: AgentTool[] = createNovelWorkspaceTools(makePort([]));
    assert.deepEqual(tool(tools, 'novel_workspace_list').parameters(), {
      type: 'object',
      properties: {
        path: {
          type: 'string',
          description: 'Optional workspace-relative directory. Defaults to the active branch root.',
        },
      },
      required: [],
      additionalProperties: false,
    });
    assert.deepEqual(tool(tools, 'novel_workspace_read').parameters(), {
      type: 'object',
      properties: {
        path: { type: 'string', description: 'Workspace-relative text file path to read.' },
      },
      required: ['path'],
      additionalProperties: false,
    });
    assert.deepEqual(tool(tools, 'novel_workspace_grep').parameters(), {
      type: 'object',
      properties: {
        query: { type: 'string', description: 'Text to search for in workspace text files.' },
        path: {
          type: 'string',
          description: 'Optional workspace-relative directory or file. Defaults to the active branch root.',
        },
        max_results: {
          type: 'integer',
          minimum: 1,
          description: 'Optional maximum number of matching lines to return.',
        },
      },
      required: ['query'],
      additionalProperties: false,
    });
    assert.deepEqual(tool(tools, 'novel_workspace_status').parameters(), {
      type: 'object', properties: {}, required: [], additionalProperties: false,
    });
    assert.deepEqual(tool(tools, 'novel_workspace_write').parameters(), {
      type: 'object',
      properties: {
        proposal_id: {
          type: 'string',
          description: 'Stable proposal id. Reuse the same value when retrying the same proposed change.',
        },
        patches: {
          type: 'array',
          minItems: 1,
          items: {
            description: 'A proposed patch. write requires text content; delete requires content to be null.',
            oneOf: [
              {
                type: 'object',
                properties: {
                  operation: { type: 'string', enum: ['write'] },
                  path: { type: 'string', description: 'Allowed workspace-relative path for this proposal.' },
                  content: { type: 'string', description: 'Complete UTF-8 text to write.' },
                },
                required: ['operation', 'path', 'content'],
                additionalProperties: false,
              },
              {
                type: 'object',
                properties: {
                  operation: { type: 'string', enum: ['delete'] },
                  path: { type: 'string', description: 'Allowed workspace-relative path for this proposal.' },
                  content: { type: 'null', description: 'Must be null when deleting a path.' },
                },
                required: ['operation', 'path', 'content'],
                additionalProperties: false,
              },
            ],
          },
          description: 'One or more proposed writes or deletes. This tool stages a proposal; it never applies it directly.',
        },
      },
      required: ['proposal_id', 'patches'],
      additionalProperties: false,
    });
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

  it('propagates port failures for dispatcher normalization', async () => {
    const port: NovelWorkspaceToolPort = {
      ...makePort([]),
      read(): Promise<JsonObject> {
        return Promise.reject(new Error('workspace path is unavailable'));
      },
    };
    await assert.rejects(
      tool(createNovelWorkspaceTools(port), 'novel_workspace_read').execute({ path: 'missing.md' }),
      /workspace path is unavailable/,
    );
  });
});
