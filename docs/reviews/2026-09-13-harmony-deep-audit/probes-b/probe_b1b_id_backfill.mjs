// B1b: confirm upstream ID backfill for the two "blank id is expected" parsers.
// (a) Claude: content_block_start tool_use carries a real id; input_json_delta is
//     blank but carries stream index -> accumulator must merge it onto the real id.
// (b) OpenAI: first delta blank, later delta with id -> mergeTool must adopt it.
import { parseClaudeStreamEvent }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/claude_parse.ts';
import { parseOpenAiStreamEventData }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/openai_parse.ts';
import { MessageStreamAccumulator }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/stream_accumulator.ts';
import { makeUIMessage }
  from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/chat/src/main/ets/chat/message.ts';

const dump = (msgs) => msgs.at(-1).parts.filter((p) => p.type === 'tool')
  .map((t) => ({ id: t.toolCallId, name: t.toolName, input: t.input }));

// (a) Claude
const accA = new MessageStreamAccumulator([makeUIMessage('assistant', [])]);
const startBlock = { index: 0, content_block: { type: 'tool_use', id: 'toolu_123', name: 'file_write', input: {} } };
const delta1 = { index: 0, delta: { type: 'input_json_delta', partial_json: '{"a":' } };
const delta2 = { index: 0, delta: { type: 'input_json_delta', partial_json: '1}' } };
const claudeEvents = [
  ['content_block_start', JSON.stringify(startBlock)],
  ['content_block_delta', JSON.stringify(delta1)],
  ['content_block_delta', JSON.stringify(delta2)],
];
for (const [type, data] of claudeEvents) {
  const r = parseClaudeStreamEvent(type, 'e', data);
  if (r.chunk !== null) accA.append(r.chunk);
}
console.log('claude merged =', JSON.stringify(dump(accA.snapshot())));

// (b) OpenAI
const accB = new MessageStreamAccumulator([makeUIMessage('assistant', [])]);
const first = { id: 'c', choices: [{ index: 0, delta: { role: 'assistant', tool_calls: [{ index: 0, type: 'function', function: { name: 'file_write', arguments: '{"a"' } }] } }] };
const second = { id: 'c', choices: [{ index: 0, delta: { role: 'assistant', tool_calls: [{ index: 0, id: 'call_real', type: 'function', function: { arguments: ':1}' } }] } }] };
for (const obj of [first, second]) {
  for (const chunk of parseOpenAiStreamEventData(JSON.stringify(obj)).chunks) accB.append(chunk);
}
console.log('openai merged =', JSON.stringify(dump(accB.snapshot())));
