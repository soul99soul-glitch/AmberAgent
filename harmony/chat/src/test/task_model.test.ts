// task_model 测试(D-087)
// 锚点:MemoryExtractor.kt:185-195 / MemoryDreamPlanner.kt:102-120 / DefaultProviders.kt:95
import assert from 'node:assert/strict';
import test from 'node:test';
import type { TaskModelWorkerGate } from '../main/ets/chat/task_model.ts';
import {
  daydreamModelCandidates,
  memoryWorkerModelCandidates,
  pickDaydreamModelId,
  pickMemoryWorkerModelId,
  DEFAULT_TASK_MODEL_WORKER_GATE,
} from '../main/ets/chat/task_model.ts';

const COMPRESS = 'compress-uuid';
const CHAT = 'chat-uuid';
const WORKER_MODEL = 'worker-model-uuid';
const DAYDREAM_MODEL = 'daydream-model-uuid';

const gate = (over: Partial<TaskModelWorkerGate>): TaskModelWorkerGate => ({
  ...DEFAULT_TASK_MODEL_WORKER_GATE, ...over,
});

test('worker 链:modelId≠AUTO 优先 → followCompress → chat(MemoryExtractor.kt:186-193)', () => {
  assert.equal(pickMemoryWorkerModelId(gate({ modelId: WORKER_MODEL }), COMPRESS, CHAT),
    WORKER_MODEL);
  // modelId≠AUTO 时 followCompress 不生效
  assert.equal(pickMemoryWorkerModelId(
    gate({ modelId: WORKER_MODEL, followCompressModel: true }), COMPRESS, CHAT), WORKER_MODEL);
  assert.equal(pickMemoryWorkerModelId(gate({}), COMPRESS, CHAT), COMPRESS); // 默认 follow=true
  assert.equal(pickMemoryWorkerModelId(gate({ followCompressModel: false }), COMPRESS, CHAT), CHAT);
  // 完整候选序 = [pick, chat](外层 ?: chat)
  assert.deepEqual(memoryWorkerModelCandidates(gate({}), COMPRESS, CHAT), [COMPRESS, CHAT]);
  assert.deepEqual(
    memoryWorkerModelCandidates(gate({ followCompressModel: false }), COMPRESS, CHAT),
    [CHAT, CHAT]);
});

test('daydream 链:五支序(MemoryDreamPlanner.kt:104-117)+ 外层两级回退', () => {
  // 1. daydreamModelId≠AUTO
  assert.equal(pickDaydreamModelId(gate({ daydreamModelId: DAYDREAM_MODEL }), COMPRESS, CHAT),
    DAYDREAM_MODEL);
  // 2. daydreamFollowCompressModel(默认 true)
  assert.equal(pickDaydreamModelId(gate({}), COMPRESS, CHAT), COMPRESS);
  // 3. daydreamFollow=false 后 worker.modelId≠AUTO
  assert.equal(pickDaydreamModelId(
    gate({ daydreamFollowCompressModel: false, modelId: WORKER_MODEL }), COMPRESS, CHAT),
    WORKER_MODEL);
  // 4. worker.followCompressModel
  assert.equal(pickDaydreamModelId(
    gate({ daydreamFollowCompressModel: false, followCompressModel: true }), COMPRESS, CHAT),
    COMPRESS);
  // 5. else chat
  assert.equal(pickDaydreamModelId(
    gate({ daydreamFollowCompressModel: false, followCompressModel: false }), COMPRESS, CHAT),
    CHAT);
  // 完整候选序 = [pick, compress, chat](外层 ?: compress ?: chat)
  assert.deepEqual(daydreamModelCandidates(
    gate({ daydreamFollowCompressModel: false, followCompressModel: false }), COMPRESS, CHAT),
    [CHAT, COMPRESS, CHAT]);
  assert.deepEqual(daydreamModelCandidates(gate({}), COMPRESS, CHAT),
    [COMPRESS, COMPRESS, CHAT]);
});
