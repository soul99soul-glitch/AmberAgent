import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  MISSING_TOOL_IMAGE_TEXT, applyToolImageRequestBudget, countToolImageParts,
  toolImagePlaceholderText,
} from '../main/ets/chat/tool_image_budget.ts';
import { CHAT_BLOB_MISSING_METADATA_KEY, CHAT_BLOB_MISSING_URL } from '../main/ets/chat/conversation_blobs.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePart } from '../main/ets/chat/message.ts';

const text = (value: string): UIMessagePart => ({ type: 'text', text: value, metadata: null });
const image = (url: string, missing: boolean = false): UIMessagePart => ({
  type: 'image', url, metadata: missing ? { [CHAT_BLOB_MISSING_METADATA_KEY]: true } : null,
});
const tool = (id: string, output: UIMessagePart[]): UIMessagePart => ({
  type: 'tool', toolCallId: id, toolName: 'wm_screenshot', input: `{"step":"${id}"}`,
  output, approvalState: { type: 'auto' }, metadata: null,
});
const message = (parts: UIMessagePart[]): UIMessage => makeUIMessage('assistant', parts);

const toolOutput = (value: UIMessage): UIMessagePart[] =>
  (value.parts[0] as { output: UIMessagePart[] }).output;

test('only the latest two image-bearing tool results keep images; older ones get stable step placeholders', () => {
  const messages: UIMessage[] = [
    makeUIMessage('user', [image('data:image/png;base64,user')]),
    message([tool('s1', [text('one'), image('data:image/png;base64,one')])]),
    message([tool('s2', [image('data:image/png;base64,two')])]),
    message([tool('s3', [image('data:image/png;base64,three')])]),
    message([tool('s4', [image('data:image/png;base64,four')])]),
  ];
  const bounded = applyToolImageRequestBudget(messages, 2);
  assert.deepEqual(toolOutput(bounded[1]), [text('one'), text(toolImagePlaceholderText(1))]);
  assert.deepEqual(toolOutput(bounded[2]), [text(toolImagePlaceholderText(2))]);
  assert.equal((toolOutput(bounded[3])[0] as { type: string }).type, 'image');
  assert.equal((toolOutput(bounded[4])[0] as { type: string }).type, 'image');
  assert.equal((bounded[0].parts[0] as { type: string }).type, 'image', 'user attachment unchanged');
  assert.equal(countToolImageParts(bounded), 2);

  const again = applyToolImageRequestBudget(bounded, 2);
  assert.equal(again, bounded, 'already bounded history is returned by reference');
  assert.deepEqual(toolOutput(again[1]), [text('one'), text(toolImagePlaceholderText(1))]);
});

test('missing blobs become text and do not consume the kept window', () => {
  const messages: UIMessage[] = [
    message([tool('missing', [image(CHAT_BLOB_MISSING_URL, true), text('old')])]),
    message([tool('s1', [image('data:image/png;base64,one')])]),
    message([tool('s2', [image('data:image/png;base64,two')])]),
  ];
  const bounded = applyToolImageRequestBudget(messages, 2);
  assert.deepEqual(toolOutput(bounded[0]), [text(MISSING_TOOL_IMAGE_TEXT), text('old')]);
  assert.equal((toolOutput(bounded[1])[0] as { type: string }).type, 'image');
  assert.equal((toolOutput(bounded[2])[0] as { type: string }).type, 'image');
  assert.equal(countToolImageParts(bounded), 2);
});

test('non-image tool output and top-level non-image parts are untouched', () => {
  const messages: UIMessage[] = [
    message([tool('plain', [text('no screenshot')])]),
    makeUIMessage('user', [text('question')]),
  ];
  assert.equal(applyToolImageRequestBudget(messages, 0), messages);
});
