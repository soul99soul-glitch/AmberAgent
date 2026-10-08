import { test } from 'node:test';
import assert from 'node:assert/strict';
import type { UIMessagePartTool } from '../main/ets/chat/message.ts';
import type { SubAgentTranscriptPort } from '../main/ets/chat/subagent_transcript.ts';
import { readSubAgentDisplayTextFromTranscript } from '../main/ets/chat/subagent_transcript.ts';
import { subAgentNameFromTools, subAgentObjectiveFromTools, subAgentWorkSummary, subAgentStatusFromTools, subAgentStatusLabel, subAgentFinalTextFromTools, readSubAgentHistoricalText } from '../main/ets/chat/subagent_display.ts';

const tool = (runId: string, result: string, name = 'subagent_wait'): UIMessagePartTool => ({
  type: 'tool', toolCallId: runId, toolName: name, input: JSON.stringify({ run_id: runId }),
  output: [{ type: 'text', text: JSON.stringify({ run_id: runId, result }), metadata: null }],
  approvalState: { type: 'auto' }, metadata: null,
});

test('restored tool results decode manager JSON strings and stay scoped to the requested run', () => {
  const tools = [tool('run-1', '{"summary":"较早结果"}'), tool('run-1', '{"summary":"最终结果"}'),
    tool('run-2', '{"summary":"另一个运行"}'), tool('run-1', '{broken')];
  assert.equal(subAgentFinalTextFromTools('run-1', tools), '最终结果');
  assert.equal(subAgentFinalTextFromTools('missing', tools), '');
  assert.equal(subAgentFinalTextFromTools('run-1', [tool('run-1', '{"summary":"其他工具"}', 'search_web')]), '');
});

test('a run failing before its first token restores the actual result error instead of an empty state', async () => {
  const result = { status: 'failed', summary: '', error: 'Provider HTTP 503: unavailable' };
  const tools = [tool('failed-run', JSON.stringify(result))];
  const running: UIMessagePartTool = { ...tool('failed-run', '', 'subagent_start'), output: [{
    type: 'text', text: JSON.stringify({ run_id: 'failed-run', status: 'running' }), metadata: null,
  }] };
  let displayText = '';
  const files: SubAgentTranscriptPort = {
    canonicalPath: async (path) => path, appendText: async () => {}, exists: async () => true,
    isRegularFile: async () => true, isPathInside: async () => true,
    readTail: async () => ({ text: JSON.stringify({ event: 'finished', payload: {
      run_id: 'failed-run', status: 'failed', result: JSON.stringify(result), display_text: displayText,
    } }) + '\n', startsAfterFileStart: false }),
  };
  assert.equal(await readSubAgentDisplayTextFromTranscript('/runs/failed-run.jsonl', '/runs', files), '');
  assert.equal(await readSubAgentHistoricalText('failed-run', tools, '/runs', files), result.error);
  assert.equal(await readSubAgentHistoricalText('failed-run', [running], '/runs', files), result.error);
  displayText = '# 已收到的正文';
  assert.equal(await readSubAgentHistoricalText('failed-run', tools, '/runs', files), displayText);
});

test('process-restart display restores the bounded transcript before falling back to the tool summary', async () => {
  let transcript = JSON.stringify({ event: 'finished', payload: { display_text: '# 完整运行\n\n历史输出' } }) + '\n';
  const reads: Array<{ path: string; maxBytes: number }> = [];
  const files: SubAgentTranscriptPort = {
    canonicalPath: async (path) => path,
    appendText: async () => {},
    exists: async () => true,
    isRegularFile: async () => true,
    isPathInside: async (root, path) => path.startsWith(root + '/'),
    readTail: async (path, maxBytes) => {
      reads.push({ path, maxBytes });
      return { text: transcript, startsAfterFileStart: false };
    },
  };
  const tools = [tool('run-1', '{"summary":"结果摘要"}')];
  assert.equal(await readSubAgentHistoricalText('run-1', tools, '/runs', files), '# 完整运行\n\n历史输出');
  assert.deepEqual(reads, [{ path: '/runs/run-1.jsonl', maxBytes: 256 * 1024 }]);
  transcript = '{unfinished';
  assert.equal(await readSubAgentHistoricalText('run-1', tools, '/runs', files), '结果摘要');
  assert.equal(await readSubAgentHistoricalText('../foreign', [], '/runs', files), '');
  assert.equal(reads.length, 2, 'a run ID containing a path must not read another transcript');
});


test('capsule and detail resolve the same identity and full objective while showing actual terminal state', () => {
  const start: UIMessagePartTool = { ...tool('run-1', '', 'subagent_start'), input: JSON.stringify({
    custom_subagent: { name: '  Web Scout  ' }, task: { objective: '完整任务\n第二段不应出现在正文胶囊' },
  }), output: [{ type: 'text', text: JSON.stringify({ status: 'running' }), metadata: null }] };
  const ended: UIMessagePartTool = { ...tool('run-1', ''), output: [{
    type: 'text', text: JSON.stringify({ status: 'failed' }), metadata: null,
  }] };
  assert.equal(subAgentNameFromTools([start, ended]), 'Web Scout');
  assert.equal(subAgentObjectiveFromTools([start, ended]), '完整任务\n第二段不应出现在正文胶囊');
  assert.equal(subAgentStatusFromTools([start, ended]), 'failed');
  assert.equal(subAgentStatusLabel(subAgentStatusFromTools([start, ended])), '执行失败');
  assert.equal(subAgentStatusLabel('cancelled'), '已取消');
  assert.equal(subAgentStatusLabel('completed'), '已完成');
});


test('capsule work hints follow iOS keyword precedence and never expose a long objective', () => {
  for (const [objective, expected] of [
    ['Search the web for current HarmonyOS findings with sources', '搜索资料'],
    ['检查代码并修复 bug', '修复问题'], ['核对文章来源并总结', '核对来源'],
    ['Write a short Chinese micro-essay under 400 characters', '处理任务'],
    ['校对', '校对'], ['第一段非常长的任务要求，搜索资料', '处理任务'],
  ]) {
    const start: UIMessagePartTool = { ...tool('run-1', '', 'subagent_start'),
      input: JSON.stringify({ task: { objective } }) };
    assert.equal(subAgentWorkSummary([start]), expected);
  }
});


test('wait-only result cards use the persisted receipt identity and objective', () => {
  const ended: UIMessagePartTool = { ...tool('run-1', ''), output: [{ type: 'text', text: JSON.stringify({
    status: 'completed', subagent_name: 'grace_5', task_objective: 'Search the web for latest articles',
  }), metadata: null }] };
  assert.equal(subAgentNameFromTools([ended]), 'grace_5');
  assert.equal(subAgentObjectiveFromTools([ended]), 'Search the web for latest articles');
  assert.equal(subAgentWorkSummary([ended]), '搜索资料');
});
