// deepread_playbook_tools 测试 — D-127 四件工具钉住
// Android 锚点:DeepReadPlaybookTools.kt(行号见 builtin_deepread_playbook_tools.ts 头注)
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createDeepReadPlaybookTools } from '../main/ets/chat/builtin_deepread_playbook_tools.ts';
import type {
  DeepReadPlaybookPort, DeepReadPlaybookResult, DeepReadPlaybookSnapshot,
} from '../main/ets/chat/builtin_deepread_playbook_tools.ts';
import type { AgentTool } from '../main/ets/chat/tool.ts';
import type { UIMessagePart } from '../main/ets/chat/message.ts';

const SNAP: DeepReadPlaybookSnapshot = {
  revision: 'rev123',
  markdown: '# Playbook\n\n规则。',
  updatedAt: 1700000000000,
};

class SpyPort implements DeepReadPlaybookPort {
  updateArgs: string[] = [];
  readResult: DeepReadPlaybookSnapshot = SNAP;
  updateResult: DeepReadPlaybookResult = { ok: true, snapshot: SNAP, error: null };
  restorePreviousResult: DeepReadPlaybookResult = { ok: true, snapshot: SNAP, error: null };

  read(): Promise<DeepReadPlaybookSnapshot> {
    return Promise.resolve(this.readResult);
  }

  update(base: string, summary: string, md: string): Promise<DeepReadPlaybookResult> {
    this.updateArgs = [base, summary, md];
    return Promise.resolve(this.updateResult);
  }

  restoreDefault(): Promise<DeepReadPlaybookSnapshot> {
    return Promise.resolve(SNAP);
  }

  restorePrevious(): Promise<DeepReadPlaybookResult> {
    return Promise.resolve(this.restorePreviousResult);
  }
}

const findTool = (tools: AgentTool[], name: string): AgentTool => {
  const t: AgentTool | undefined = tools.find((x: AgentTool): boolean => x.name === name);
  if (t === undefined) throw new Error(`missing tool ${name}`);
  return t;
};

const textOf = async (t: AgentTool, input?: object): Promise<string> => {
  const parts: UIMessagePart[] = await t.execute((input ?? {}) as never);
  if (parts[0].type !== 'text') throw new Error('text part');
  return parts[0].text;
};

test('getTools:四件序 + 审批标志逐字(:17/:24/:43-44/:61-62/:72-73)', () => {
  const tools: AgentTool[] = createDeepReadPlaybookTools(new SpyPort());
  assert.deepEqual(tools.map((t: AgentTool): string => t.name), [
    'deep_read_playbook_read',
    'deep_read_playbook_update',
    'deep_read_playbook_restore_default',
    'deep_read_playbook_restore_previous',
  ]);
  // read:allowsAutoApproval=true,needsApproval 默认 false
  assert.equal(tools[0].needsApproval, false);
  assert.equal(tools[0].allowsAutoApproval, true);
  // 后三件:needsApproval=true 且 allowsAutoApproval=false
  for (let i = 1; i < 4; i++) {
    assert.equal(tools[i].needsApproval, true);
    assert.equal(tools[i].allowsAutoApproval, false);
  }
});

test('read:payload 键序 status/revision/updated_at/markdown,默认 status=ok', async () => {
  const t: AgentTool = findTool(createDeepReadPlaybookTools(new SpyPort()), 'deep_read_playbook_read');
  const text: string = await textOf(t);
  assert.equal(
    text,
    JSON.stringify({
      status: 'ok', revision: 'rev123', updated_at: 1700000000000, markdown: '# Playbook\n\n规则。',
    }));
  assert.equal(Object.keys(JSON.parse(text)).join(','), 'status,revision,updated_at,markdown');
});

test('update:参数 trim 取值(orEmpty)+ 成功 status=updated', async () => {
  const port: SpyPort = new SpyPort();
  const t: AgentTool = findTool(createDeepReadPlaybookTools(port), 'deep_read_playbook_update');
  const text: string = await textOf(t, {
    base_revision: '  rev123 ',
    change_summary: ' 改了规则 ',
    updated_markdown: '# New',
  });
  assert.deepEqual(port.updateArgs, ['rev123', '改了规则', '# New']);
  assert.equal(JSON.parse(text)['status'], 'updated');
});

test('update:空/缺参数 → orEmpty 空串落仓库(string takeIf isNotBlank)', async () => {
  const port: SpyPort = new SpyPort();
  const t: AgentTool = findTool(createDeepReadPlaybookTools(port), 'deep_read_playbook_update');
  await textOf(t, { base_revision: '   ', updated_markdown: 42 as never });
  assert.deepEqual(port.updateArgs, ['', '', '']);
});

test('update:仓库失败 → {status:rejected,error} 两键(toToolJson onFailure)', async () => {
  const port: SpyPort = new SpyPort();
  port.updateResult = { ok: false, snapshot: null, error: 'Playbook revision conflict: current=a, base=b' };
  const t: AgentTool = findTool(createDeepReadPlaybookTools(port), 'deep_read_playbook_update');
  const text: string = await textOf(t, { base_revision: 'b' });
  assert.equal(
    text,
    JSON.stringify({
      status: 'rejected', error: 'Playbook revision conflict: current=a, base=b',
    }));
});

test('restore_default:status=restored_default;restore_previous:成功=restored_previous/失败=rejected', async () => {
  const port: SpyPort = new SpyPort();
  const tools: AgentTool[] = createDeepReadPlaybookTools(port);
  const def: string = await textOf(findTool(tools, 'deep_read_playbook_restore_default'));
  assert.equal(JSON.parse(def)['status'], 'restored_default');
  assert.equal(JSON.parse(def)['revision'], 'rev123');
  const prev: AgentTool = findTool(tools, 'deep_read_playbook_restore_previous');
  const okText: string = await textOf(prev);
  assert.equal(JSON.parse(okText)['status'], 'restored_previous');
  port.restorePreviousResult = { ok: false, snapshot: null, error: 'No previous playbook snapshot' };
  const rejText: string = await textOf(prev);
  assert.equal(rejText, JSON.stringify({ status: 'rejected', error: 'No previous playbook snapshot' }));
});
