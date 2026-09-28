import type { UIMessagePartTool } from '../main/ets/chat/message.ts';
import { it } from 'node:test';
import assert from 'node:assert/strict';
import { toolCategory, toolRiskProfile, toolConcurrencySafe, toolOutputBudgetChars } from '../main/ets/chat/tool_policy.ts';
import { PROFILE_CODING_CATEGORIES } from '../main/ets/chat/tool_profile_filter.ts';
import { isSandboxActivityTool, toolDefaultRuntime, toolActivityStatus, toolActivityOutputTail, toolOutputJson } from '../main/ets/chat/tool_activity.ts';

it('local Python is independently discoverable code with approved serial execution', () => {
  assert.equal(toolCategory('python_execute'), 'python');
  assert.deepEqual(toolRiskProfile('python_execute'), { risk: 'sensitive', explicit: true });
  assert.equal(toolConcurrencySafe('python_execute'), false);
  assert.ok(PROFILE_CODING_CATEGORIES.includes('python'));
  assert.ok(toolOutputBudgetChars('python_execute') >= 128 * 1024 * 6 + 2048);
  assert.equal(isSandboxActivityTool('python_execute'), true);
  assert.equal(toolDefaultRuntime('python_execute'), 'embedded_python');
});
it('Mosh session read and execution retain the real runtime in Activity', () => {
  for (const action of ['start', 'exec', 'read', 'stop']) {
    const name = 'terminal_mosh_session_' + action;
    assert.equal(toolCategory(name), 'terminal');
    assert.equal(isSandboxActivityTool(name), true);
    assert.equal(toolDefaultRuntime(name), 'remote_mosh');
  }
  assert.equal(toolDefaultRuntime('terminal_session_start'), 'remote_ssh');
});

it('persisted Python outcomes retain status and the bounded native text after reload', () => {
  for (const [status, expected] of [['cancelled', 'cancelled'], ['interrupted', 'cancelled'], ['timed_out', 'failed']]) {
    const json = {runtime:'embedded_python',status,exit_code:null,stdout:'先前输出',stderr:'异常输出',error_code:status};
    const part: UIMessagePartTool = {type:'tool',toolCallId:'python-result',toolName:'python_execute',input:'{}',
      output:[{type:'text',text:JSON.stringify(json),metadata:null}],approvalState:{type:'auto'},metadata:null};
    assert.equal(toolActivityStatus(part,false,toolOutputJson(part)),expected);
    assert.equal(toolActivityOutputTail(part,toolOutputJson(part)),'先前输出\n异常输出');
  }
  const json = {runtime:'embedded_python',status:'failed',exit_code:1,stdout:'x'.repeat(100000),stderr:'end',error_code:'execution_error'};
  const part: UIMessagePartTool = {type:'tool',toolCallId:'python-large',toolName:'python_execute',input:'{}',
    output:[{type:'text',text:JSON.stringify(json),metadata:null}],approvalState:{type:'auto'},metadata:null};
  assert.equal(toolOutputJson(part)['status'],'failed');
  assert.equal(toolActivityStatus(part,false,toolOutputJson(part)),'failed');
  assert.ok(toolActivityOutputTail(part,toolOutputJson(part)).endsWith('\nend'));
});
it('persisted Python output tail does not split an emoji at the 1600 boundary', () => {
  const json = {runtime:'embedded_python',status:'completed',exit_code:0,stdout:'😀'+'x'.repeat(1599),stderr:'',error_code:null};
  const part: UIMessagePartTool = {type:'tool',toolCallId:'python-unicode',toolName:'python_execute',input:'{}',
    output:[{type:'text',text:JSON.stringify(json),metadata:null}],approvalState:{type:'auto'},metadata:null};
  assert.equal(toolActivityOutputTail(part,toolOutputJson(part)),'x'.repeat(1599));
});
