import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  resolveModelDefaults, resolveModelContextWindow, resolveEffectiveModelContextWindow,
} from '../main/ets/chat/model_registry.ts';
import { makeProviderModel } from '../main/ets/chat/provider_settings.ts';

describe('model_registry (2026-11 frontier & domestic open-source defaults)', () => {
  it('Qwen 3.7 / 3.8 / 4 default to multimodal vision + tool + reasoning + 1M context', () => {
    for (const id of ['qwen3.8-flash', 'qwen3.8-max', 'qwen3.8-max-0902', 'qwen3.7-plus', 'qwen3.7-max', 'qwen4-max', 'qwen4-max-preview']) {
      const m = makeProviderModel({ modelId: id });
      assert.deepEqual(m.inputModalities, ['text', 'image'], id);
      assert.deepEqual(m.abilities, ['tool', 'reasoning'], id);
      assert.equal(m.contextWindowTokens, 1_000_000, id);
    }
    const omni = makeProviderModel({ modelId: 'qwen3.8-omni-flash' });
    assert.deepEqual(omni.inputModalities, ['text', 'image', 'audio']);
    assert.equal(omni.contextWindowTokens, 65_536);
  });

  it('DeepSeek V4.1-Flash & deepseek-flash are multimodal while V4-Pro stays text-only (all 1M)', () => {
    for (const id of ['deepseek-v4.1-flash', 'deepseek-v4.1-flash-1017', 'deepseek-flash']) {
      const m = makeProviderModel({ modelId: id });
      assert.deepEqual(m.inputModalities, ['text', 'image'], id);
      assert.deepEqual(m.abilities, ['tool', 'reasoning'], id);
      assert.equal(m.contextWindowTokens, 1_000_000, id);
    }
    for (const id of ['deepseek-v4-pro', 'deepseek-v4-pro-0813', 'deepseek-v4-flash-0731']) {
      const m = makeProviderModel({ modelId: id });
      assert.deepEqual(m.inputModalities, ['text'], id);
      assert.deepEqual(m.abilities, ['tool', 'reasoning'], id);
      assert.equal(m.contextWindowTokens, 1_000_000, id);
    }
  });

  it('GLM 5.2 / 5.3 (1M text) vs GLM 5.3-Flash (1M vision) vs GLM 5V (200K vision)', () => {
    for (const id of ['glm-5.3', 'glm-5.2']) {
      const m = makeProviderModel({ modelId: id });
      assert.deepEqual(m.inputModalities, ['text'], id);
      assert.equal(m.contextWindowTokens, 1_000_000, id);
      assert.deepEqual(m.abilities, ['tool', 'reasoning'], id);
    }
    const flash = makeProviderModel({ modelId: 'glm-5.3-flash' });
    assert.deepEqual(flash.inputModalities, ['text', 'image']);
    assert.equal(flash.contextWindowTokens, 1_000_000);
    const v5 = makeProviderModel({ modelId: 'glm-5v-turbo' });
    assert.deepEqual(v5.inputModalities, ['text', 'image']);
    assert.equal(v5.contextWindowTokens, 200_000);
  });

  it('Kimi K3.1 / K2.5+, MiniMax M3 / M2.7, MiMo V2.6, Doubao Seed 2.1 match official specs', () => {
    const k31 = makeProviderModel({ modelId: 'kimi-k3.1-pro' });
    assert.deepEqual(k31.inputModalities, ['text', 'image']);
    assert.equal(k31.contextWindowTokens, 1_048_576);

    const k27 = makeProviderModel({ modelId: 'kimi-k2.7' });
    assert.deepEqual(k27.inputModalities, ['text', 'image']);
    assert.equal(k27.contextWindowTokens, 262_144);

    const m3 = makeProviderModel({ modelId: 'MiniMax-M3' });
    assert.deepEqual(m3.inputModalities, ['text', 'image']);
    assert.equal(m3.contextWindowTokens, 1_000_000);
    assert.deepEqual(m3.abilities, ['tool', 'reasoning']);

    const m27 = makeProviderModel({ modelId: 'minimax-m2.7' });
    assert.deepEqual(m27.inputModalities, ['text']);
    assert.equal(m27.contextWindowTokens, 204_800);

    const mimo26 = makeProviderModel({ modelId: 'mimo-v2.6-pro' });
    assert.deepEqual(mimo26.inputModalities, ['text', 'image', 'audio']);
    assert.equal(mimo26.contextWindowTokens, 1_048_576);

    const seed21 = makeProviderModel({ modelId: 'doubao-seed-2.1-pro' });
    assert.deepEqual(seed21.inputModalities, ['text', 'image', 'audio']);
    assert.equal(seed21.contextWindowTokens, 1_000_000);
  });

  it('Frontier models: GPT-6 / 5.4+, Claude 5.5 / Fable 5.2 / 4.6 / Haiku 4.5, Gemini 4 / 3.1, Grok 4.7 / 4.20', () => {
    for (const id of ['gpt-6.2-astra', 'gpt-6.1-sol', 'gpt-6-luna']) {
      const m = makeProviderModel({ modelId: id });
      assert.deepEqual(m.inputModalities, ['text', 'image'], id);
      assert.equal(m.contextWindowTokens, 1_047_576, id);
      assert.deepEqual(m.abilities, ['tool', 'reasoning'], id);
    }
    for (const id of ['claude-fable-5-2', 'claude-opus-5-5', 'claude-sonnet-5-5', 'claude-haiku-5-5', 'claude-opus-4-7', 'claude-sonnet-4-6']) {
      const m = makeProviderModel({ modelId: id });
      assert.deepEqual(m.inputModalities, ['text', 'image'], id);
      assert.equal(m.contextWindowTokens, 1_000_000, id);
      assert.deepEqual(m.abilities, ['tool', 'reasoning'], id);
    }
    const haiku45 = makeProviderModel({ modelId: 'claude-haiku-4-5' });
    assert.equal(haiku45.contextWindowTokens, 200_000);

    for (const id of ['gemini-4-carbon', 'gemini-4-neon', 'gemini-4-argon', 'gemini-4-hydrogen', 'gemini-3.1-pro', 'gemini-3.8-flash']) {
      const m = makeProviderModel({ modelId: id });
      assert.deepEqual(m.inputModalities, ['text', 'image', 'audio'], id);
      assert.equal(m.contextWindowTokens, 1_048_576, id);
      assert.deepEqual(m.abilities, ['tool', 'reasoning'], id);
    }

    assert.equal(resolveModelContextWindow('grok-4.7'), 500_000);
    assert.equal(resolveModelContextWindow('grok-4.20'), 1_000_000);
    assert.deepEqual(resolveModelDefaults('grok-4.7').inputModalities, ['text', 'image']);
  });

  it('preserves explicit user overrides and safely falls back for unknown models', () => {
    const overridden = makeProviderModel({
      modelId: 'qwen3.8-flash',
      inputModalities: ['text'],
      contextWindowTokens: 32_000,
      abilities: ['tool'],
    });
    assert.deepEqual(overridden.inputModalities, ['text']);
    assert.equal(overridden.contextWindowTokens, 32_000);
    assert.deepEqual(overridden.abilities, ['tool']);

    const unknown = makeProviderModel({ modelId: 'my-custom-private-model' });
    assert.deepEqual(unknown.inputModalities, ['text']);
    assert.deepEqual(unknown.abilities, ['tool']);
    assert.equal(unknown.contextWindowTokens, null);

    const legacySaved = makeProviderModel({ modelId: 'qwen3.8-flash', contextWindowTokens: null });
    assert.equal(resolveEffectiveModelContextWindow(legacySaved), 1_000_000);
  });

  it('auto-detects image generation models like wan2.7-image and qwen-image-3.0-pro', () => {
    for (const id of ['wan2.7-image', 'wan2.7-image-pro', 'qwen-image-3.0-pro', 'gpt-image-2']) {
      const m = makeProviderModel({ modelId: id });
      assert.equal(m.type, 'image', id);
      assert.deepEqual(m.outputModalities, ['text', 'image'], id);
    }
  });
});
