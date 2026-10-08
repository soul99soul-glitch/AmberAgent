// pending_queue.test.ts — FOLLOWUP 发送排队纯逻辑(TDD 先行)
//
// Android 基准:
//   PendingUserMessage.kt(全文 66 行)
//   ConversationSession.kt enqueue/dequeue/cancel/move/convert/clear(:110-232)
//   ChatService.kt preparePendingMessageForDispatch(:1057-1073)
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  MAX_PENDING_USER_MESSAGES,
  makePendingUserMessage,
  isCollectablePending,
  pendingAsFollowup,
  pendingPreviewText,
  buildCollectedPendingUserMessage,
  enqueuePendingUserMessage,
  dequeueSteerPendingUserMessages,
  dequeueLeadingCollectableMessages,
  cancelPendingUserMessage,
  movePendingUserMessage,
  convertSteerToFollowup,
  preparePendingMessageForDispatch,
} from '../main/ets/chat/pending_queue.ts';
import type {
  PendingUserMessage,
} from '../main/ets/chat/pending_queue.ts';

const textMsg = (id: string, text: string, mode: 'FOLLOWUP' | 'STEER' | 'COLLECT' = 'FOLLOWUP')
  : PendingUserMessage => makePendingUserMessage({
  id,
  parts: [{ type: 'text', text, metadata: null }],
  mode,
  createdAtMs: 1000,
});

describe('isCollectablePending(PendingUserMessage.kt:24-25)', () => {
  it('COLLECT 但含图片 → false', () => {
    const m = makePendingUserMessage({
      id: 'a', mode: 'COLLECT',
      parts: [
        { type: 'text', text: 'hi', metadata: null },
        { type: 'image', url: 'data:image/png;base64,x', metadata: null },
      ],
    });
    assert.equal(isCollectablePending(m), false);
  });
});

describe('pendingAsFollowup(:27-29)', () => {
  it('STEER → copy mode=FOLLOWUP', () => {
    const m = textMsg('a', 'hi', 'STEER');
    const out = pendingAsFollowup(m);
    assert.equal(out.mode, 'FOLLOWUP');
    assert.equal(out.id, 'a');
  });
});

describe('pendingPreviewText(:53-65)', () => {
  it('多 part 以 \\n 拼接并 trim', () => {
    const m = makePendingUserMessage({
      id: 'a',
      parts: [
        { type: 'text', text: '你好', metadata: null },
        { type: 'image', url: 'data:x', metadata: null },
        { type: 'document', url: 'file://a.pdf', fileName: 'a.pdf', mime: 'application/pdf', metadata: null },
      ],
    });
    assert.equal(pendingPreviewText(m), '你好\n[图片]\n[文件] a.pdf');
  });
  it('超过 maxChars 截断 + trimEnd + ...', () => {
    const m = textMsg('a', 'x'.repeat(200));
    const out = pendingPreviewText(m);
    assert.equal(out, 'x'.repeat(180) + '...');
  });
  it('video/audio 占位符', () => {
    const m = makePendingUserMessage({
      id: 'a',
      parts: [
        { type: 'video', url: 'file://v.mp4', mime: 'video/mp4', metadata: null },
        { type: 'audio', url: 'file://a.mp3', fileName: '', mime: 'audio/mpeg', metadata: null },
      ],
    });
    assert.equal(pendingPreviewText(m), '[视频]\n[音频]');
  });
});

describe('buildCollectedPendingUserMessage(:31-51)', () => {
  it('多条 → 合并文本/id+/answer any/createdAtMs min', () => {
    const a = makePendingUserMessage({
      id: 'a', mode: 'COLLECT', answer: false, createdAtMs: 300,
      parts: [{ type: 'text', text: '第一条', metadata: null }],
    });
    const b = makePendingUserMessage({
      id: 'b', mode: 'COLLECT', answer: true, createdAtMs: 100,
      parts: [{ type: 'text', text: '第二条', metadata: null }],
    });
    const out = buildCollectedPendingUserMessage([a, b]);
    assert.equal(out.id, 'a+b');
    assert.equal(out.mode, 'FOLLOWUP');
    assert.equal(out.answer, true);
    assert.equal(out.createdAtMs, 100);
    assert.equal(out.parts.length, 1);
    assert.equal(out.parts[0].type, 'text');
    const text = (out.parts[0] as { type: 'text'; text: string }).text;
    assert.equal(
      text,
      '下面是用户在上一轮运行时连续排队补充的消息，请按顺序处理：\n'
      + '\nQueued #1:\n第一条\n'
      + '\nQueued #2:\n第二条',
    );
  });
});

describe('enqueuePendingUserMessage(ConversationSession.kt:110-132)', () => {
  it('FOLLOWUP 追加尾部', () => {
    const cur = [textMsg('a', '1')];
    const r = enqueuePendingUserMessage(cur, textMsg('b', '2'));
    assert.equal(r.accepted, true);
    assert.deepEqual(r.messages.map((m) => m.id), ['a', 'b']);
  });
  it('STEER 插入既有 STEER 前缀之后、首个非 STEER 之前', () => {
    const cur = [textMsg('s1', '1', 'STEER'), textMsg('f1', '2'), textMsg('f2', '3')];
    const r = enqueuePendingUserMessage(cur, textMsg('s2', '4', 'STEER'));
    assert.deepEqual(r.messages.map((m) => m.id), ['s1', 's2', 'f1', 'f2']);
  });
  it('满 20 条拒绝(原数组引用不变)', () => {
    const cur: PendingUserMessage[] = [];
    for (let i = 0; i < MAX_PENDING_USER_MESSAGES; i++) cur.push(textMsg(`m${i}`, 'x'));
    const r = enqueuePendingUserMessage(cur, textMsg('overflow', 'x'));
    assert.equal(r.accepted, false);
    assert.equal(r.messages, cur);
  });
});

describe('dequeue 系列(:134-168)', () => {
  it('dequeueSteer 取 STEER 前缀', () => {
    const cur = [textMsg('s1', '1', 'STEER'), textMsg('s2', '2', 'STEER'), textMsg('f1', '3')];
    const { consumed, rest } = dequeueSteerPendingUserMessages(cur);
    assert.deepEqual(consumed.map((m) => m.id), ['s1', 's2']);
    assert.deepEqual(rest.map((m) => m.id), ['f1']);
  });
  it('dequeueLeadingCollectable 取 collectable 前缀(遇非 collectable 停)', () => {
    const cur = [
      textMsg('c1', '1', 'COLLECT'), textMsg('c2', '2', 'COLLECT'), textMsg('f1', '3'),
      textMsg('c3', '4', 'COLLECT'),
    ];
    const { consumed, rest } = dequeueLeadingCollectableMessages(cur);
    assert.deepEqual(consumed.map((m) => m.id), ['c1', 'c2']);
    assert.deepEqual(rest.map((m) => m.id), ['f1', 'c3']);
  });
});

describe('cancel/move/convert(:170-226)', () => {
  it('cancel 命中删除,未命中 changed=false', () => {
    const cur = [textMsg('a', '1'), textMsg('b', '2')];
    const hit = cancelPendingUserMessage(cur, 'a');
    assert.equal(hit.changed, true);
    assert.deepEqual(hit.messages.map((m) => m.id), ['b']);
    const miss = cancelPendingUserMessage(cur, 'zzz');
    assert.equal(miss.changed, false);
    assert.equal(miss.messages, cur);
  });
  it('move offset clamp 到 [0,lastIndex]', () => {
    const cur = [textMsg('a', '1'), textMsg('b', '2'), textMsg('c', '3')];
    const up = movePendingUserMessage(cur, 'c', -1);
    assert.deepEqual(up.messages.map((m) => m.id), ['a', 'c', 'b']);
    const clamped = movePendingUserMessage(cur, 'a', -99);
    assert.deepEqual(clamped.messages.map((m) => m.id), ['a', 'b', 'c']);
    assert.equal(clamped.changed, false);
  });
  it('convertSteerToFollowup 全部 STEER → FOLLOWUP', () => {
    const cur = [textMsg('s', '1', 'STEER'), textMsg('f', '2')];
    const r = convertSteerToFollowup(cur);
    assert.equal(r.changed, true);
    assert.deepEqual(r.messages.map((m) => m.mode), ['FOLLOWUP', 'FOLLOWUP']);
  });
});

describe('preparePendingMessageForDispatch(ChatService.kt:1057-1073)', () => {
  it('collectable 头 → 合并后续 leading collectable', () => {
    const head = textMsg('h', '头', 'COLLECT');
    const queue = [textMsg('c1', '续1', 'COLLECT'), textMsg('f1', '停')];
    const { dispatch, rest } = preparePendingMessageForDispatch(queue, head);
    assert.equal(dispatch.id, 'h+c1');
    assert.equal(dispatch.mode, 'FOLLOWUP');
    assert.deepEqual(rest.map((m) => m.id), ['f1']);
  });
  it('STEER 头 → asFollowup,队列不动', () => {
    const head = textMsg('s', '头', 'STEER');
    const queue = [textMsg('f1', '续')];
    const { dispatch, rest } = preparePendingMessageForDispatch(queue, head);
    assert.equal(dispatch.mode, 'FOLLOWUP');
    assert.equal(dispatch.id, 's');
    assert.deepEqual(rest.map((m) => m.id), ['f1']);
  });
});
