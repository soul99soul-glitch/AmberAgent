// agent_loop_budget.test.ts — 工具循环预算提示(D-056 TDD)
//
// Android 基准: AgentLoopBudgetPrompt.kt(全文 53 行)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  agentLoopBudgetStage, buildAgentLoopBudgetPrompt, agentLoopShouldHideTools,
} from '../main/ets/chat/agent_loop_budget.ts';

describe('agentLoopBudgetStage(:22-40)', () => {
  it('小循环(maxSteps<=4):remaining<=1 FINAL,<=2 且 step>0 TIGHT,<=3 且 step>0 WARN', () => {
    // maxSteps=4
    assert.equal(agentLoopBudgetStage(0, 4), null);      // remaining 4
    assert.equal(agentLoopBudgetStage(1, 4), 'warn');    // remaining 3
    assert.equal(agentLoopBudgetStage(2, 4), 'tight');   // remaining 2
    assert.equal(agentLoopBudgetStage(3, 4), 'final');   // remaining 1
    assert.equal(agentLoopBudgetStage(4, 4), 'final');   // remaining 0
    // stepIndex=0 时 WARN/TIGHT 不触发(remaining<=2/<=3 均要求 stepIndex>0),详见下个用例
  });
  it('小循环边界:maxSteps=2,step0 → remaining2 → FINAL? 逐行核对', () => {
    // Android:remaining<=0 → FINAL;remaining<=1 → FINAL;remaining<=2 && stepIndex>0 → TIGHT
    // remaining=2,stepIndex=0 → 三个分支全不落 → null? 但 <=1 不满足,故 null
    assert.equal(agentLoopBudgetStage(0, 2), null);
    assert.equal(agentLoopBudgetStage(1, 2), 'final');   // remaining 1
    assert.equal(agentLoopBudgetStage(0, 3), null);      // remaining 3,step 0
    assert.equal(agentLoopBudgetStage(1, 3), 'tight');   // remaining 2
  });
  it('大循环(maxSteps>4):remaining<=2 FINAL,<=6 TIGHT,<=12 WARN', () => {
    // maxSteps=20
    assert.equal(agentLoopBudgetStage(0, 20), null);     // remaining 20
    assert.equal(agentLoopBudgetStage(8, 20), 'warn');   // remaining 12
    assert.equal(agentLoopBudgetStage(14, 20), 'tight'); // remaining 6
    assert.equal(agentLoopBudgetStage(18, 20), 'final'); // remaining 2
    assert.equal(agentLoopBudgetStage(20, 20), 'final'); // remaining 0
    assert.equal(agentLoopBudgetStage(25, 20), 'final'); // remaining 钳 0
  });
});

describe('buildAgentLoopBudgetPrompt(:4-17 文案逐字)', () => {
  it('null stage → 空串', () => {
    assert.equal(buildAgentLoopBudgetPrompt(0, 20), '');
  });
  it('WARN/TIGHT/FINAL 文案', () => {
    assert.equal(
      buildAgentLoopBudgetPrompt(8, 20),
      'Agent loop budget: 12 steps remain. Avoid starting new branches; reuse gathered evidence and call only tools that clearly unblock the final answer.');
    assert.equal(
      buildAgentLoopBudgetPrompt(14, 20),
      'Agent loop budget: 6 steps remain. Start converging now; call only essential tools and prepare a final answer from the evidence already gathered.');
    assert.equal(
      buildAgentLoopBudgetPrompt(18, 20),
      'Agent loop budget: 2 steps remain. Do not start more tool work; provide the final answer now and explicitly note any unfinished items.');
  });
});

describe('agentLoopShouldHideTools(:19-20)', () => {
  it('FINAL 且无 resumable 工具 → true;有 resumable 恒 false', () => {
    assert.equal(agentLoopShouldHideTools(18, 20, false), true);
    assert.equal(agentLoopShouldHideTools(18, 20, true), false);
    assert.equal(agentLoopShouldHideTools(8, 20, false), false);
  });
});
