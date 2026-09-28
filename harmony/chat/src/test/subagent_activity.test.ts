import assert from 'node:assert/strict';
import { test } from 'node:test';
import { makeSubAgentDefinition } from '../main/ets/chat/agent_prompt_config.ts';
import {
  makeSubAgentRun, makeSubAgentTaskSpec, subAgentActivityPresentation,
} from '../main/ets/chat/subagent_models.ts';
import type { SubAgentRun, SubAgentRunStatus } from '../main/ets/chat/subagent_models.ts';

const run = (status: SubAgentRunStatus): SubAgentRun => makeSubAgentRun({
  runId: `run-${status}`, parentConversationId: 'chat',
  definition: makeSubAgentDefinition({
    id: 'reviewer', name: '审查', description: '检查代码', systemPrompt: '只读审查', toolAllowlist: [],
  }),
  task: makeSubAgentTaskSpec({ objective: '检查代码', outputFormat: '', toolsAndSources: '', boundaries: '' }),
  status, transcriptPath: '', startedAtMs: 10,
});

test('activity terminal presentation distinguishes success, failure and user cancellation', () => {
  assert.deepEqual(subAgentActivityPresentation([run('completed')]), {
    label: '子代理已完成', tone: 'success', icon: 'check',
  });
  assert.deepEqual(subAgentActivityPresentation([run('failed')]), {
    label: '子代理失败', tone: 'error', icon: 'close',
  });
  assert.deepEqual(subAgentActivityPresentation([run('cancelled')]), {
    label: '子代理已取消', tone: 'warning', icon: 'close',
  });
});

test('mixed terminal runs retain each result count and failure color', () => {
  assert.deepEqual(subAgentActivityPresentation([run('completed'), run('failed'), run('cancelled')]), {
    label: '子代理：已完成 1 个 · 失败 1 个 · 已取消 1 个', tone: 'error', icon: 'close',
  });
  assert.equal(subAgentActivityPresentation([run('timed_out')]).label, '子代理超时');
  assert.equal(subAgentActivityPresentation([run('interrupted')]).label, '子代理已中断');
  assert.equal(subAgentActivityPresentation([run('approval_required')]).label, '子代理等待审批');
});
