// patchAssistant 测试(ArkTS entry 编辑页用,语义 = Kotlin data class copy)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { makeAssistant } from '../main/ets/index.ts';
import { patchAssistant } from '../main/ets/chat/assistant.ts';

describe('patchAssistant', () => {
  it('覆盖指定字段,其余保留', () => {
    const base = makeAssistant({ name: 'A', systemPrompt: 'SP', temperature: 0.7 });
    const out = patchAssistant(base, { name: 'B', temperature: null });
    assert.equal(out.name, 'B');
    assert.equal(out.temperature, null);
    assert.equal(out.systemPrompt, 'SP');
    assert.equal(out.id, base.id);
    assert.equal(out.contextMessageSize, base.contextMessageSize);
  });

  it('空 patch → 字段全保留(新对象)', () => {
    const base = makeAssistant({ name: 'A' });
    const out = patchAssistant(base, {});
    assert.notEqual(out, base);
    assert.deepEqual(out, base);
  });
});
