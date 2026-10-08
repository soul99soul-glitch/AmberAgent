import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JevContextRuntime, splitJevContextBlocks } from '../main/ets/chat/jev_context.ts';
import type { JevContextPolicy } from '../main/ets/chat/jev_context.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';
import type { UIMessage, UIMessagePartTool } from '../main/ets/chat/message.ts';
import { editPreparedContext, compactConversation, createMemoryCompactStore, prepareContext } from '../main/ets/chat/context_engine.ts';
import { makeConversation, makeMessageNode } from '../main/ets/chat/conversation.ts';
import { buildFileReadJson } from '../main/ets/chat/workspace.ts';
import { makeCompactPolicy } from '../main/ets/chat/context_compact.ts';
import { makeJevSettings } from '../main/ets/chat/jev_models.ts';
import type { JevEvaluateResult, JevQuestion } from '../main/ets/chat/jev_models.ts';
const policy = (patch: Partial<JevContextPolicy> = {}): JevContextPolicy => ({ selectionMode: 'active', retentionMode: 'off', selectionAllowed: true, retentionAllowed: true, selectionTaskText:true, selectionToolMetadata:true, retentionTaskText:true, retentionToolMetadata:true, selectionConsentKey: 'one',retentionConsentKey:'one', ...patch });
const text = (role: 'user'|'assistant', value: string): UIMessage => makeUIMessage(role, [{ type:'text', text:value, metadata:null }]);
const output = (id: string, value: string, input: string='{"path":"a.txt","limit":50000}', name='file_read'): UIMessage => makeUIMessage('assistant', [{ type:'tool', toolCallId:id, toolName:name, input, output:[{type:'text',text:value,metadata:null}], metadata:null, approvalState:{type:'auto'} }]);
const tool = (m: UIMessage): UIMessagePartTool => m.parts[0] as UIMessagePartTool;
const outputText = (m: UIMessage): string => tool(m).output.map(p=>p.type==='text'?p.text:'').join('');
const longText = (): string => ['UNRELATED '+ 'a'.repeat(1800), 'NEEDED '+ 'b'.repeat(1800), 'extra '+ 'c'.repeat(6000)].join('\n\n');
const choose = (questions: Record<string,JevQuestion>, value='0'): JevEvaluateResult => ({ ok:true, reason:'ok', shadow:false, evaluation:{model:'test',usage:null,answers:Object.fromEntries(Object.entries(questions).map(([id,q])=>[id,q.kind==='choice'?{kind:'choice',selected:value}:{kind:'noul',probability:.9}]))} });
const controller = (): AbortController => new AbortController();
test('active projection hides irrelevant paragraphs without editing canonical history and keeps atomic structures', async () => {
  let calls=0; const runtime=new JevContextRuntime({loadPolicy:async()=>policy(),createController:controller,evaluate:async(_purpose,_state,qs)=>{calls++;return choose(qs);}});
  const raw=[text('user','分析需要的部分'),output('one',longText())]; const before=JSON.stringify(raw);
  const got=await runtime.beginRun('c','r').prepare(raw,4);
  assert.match(outputText(got.messages[1]),/omitted_tool_context/); assert.equal(JSON.stringify(raw),before); assert.equal(calls,1);
  const blocks=splitJevContextBlocks('intro\n\n```ts\nconst a=1;\n\nconst b=2;\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\nend');
  assert.ok(blocks.some(b=>b.text.includes('const a=1')&&b.text.includes('const b=2')));
  assert.ok(blocks.some(b=>b.text.includes('|---|---|')&&b.text.includes('| 1 | 2 |')));
});
test('off, denied scope, shadow and explicit full text leave output intact; off/full text have zero network', async () => {
  for(const p of [policy({selectionMode:'off'}),policy({selectionAllowed:false}),policy({selectionMode:'shadow'})]) {
    let calls=0; const runtime=new JevContextRuntime({loadPolicy:async()=>p,createController:controller,evaluate:async(_p,_s,qs)=>{calls++;return choose(qs);}});
    const raw=[text('user','分析'),output('one',longText())]; assert.deepEqual((await runtime.beginRun('c','r').prepare(raw,4)).messages,raw);
    assert.equal(calls,p.selectionMode==='shadow'?1:0);
  }
  let calls=0; const runtime=new JevContextRuntime({loadPolicy:async()=>policy({retentionMode:'active'}),createController:controller,evaluate:async(_p,_s,qs)=>{calls++;return choose(qs);}});
  const raw=[text('user','请逐字保留原文'),output('one',longText()),text('assistant','已读')]; assert.deepEqual((await runtime.beginRun('c','r').prepare(raw,4)).messages,raw); assert.equal(calls,0);
});
test('structured failure, unknown action, approval, TODO and pagination blocks stay while ordinary keywords can be filtered', async () => {
  const protectedText='{"status":"unknown_after_action","may_have_applied":true}\n\nTODO: 尚未处理\n\n{"next_cursor":"opaque"}\n\n需要审批：写入\n\nERROR: broken';
  const runtime=new JevContextRuntime({loadPolicy:async()=>policy(),createController:controller,evaluate:async(_p,_s,qs)=>choose(qs)});
  const got=await runtime.beginRun('c','r').prepare([text('user','分析'),output('one',protectedText+'\n\nordinary error cursor todo '+ 'x'.repeat(9000))],4);
  for(const phrase of ['unknown_after_action','TODO:','next_cursor','需要审批','ERROR:']) assert.ok(outputText(got.messages[1]).includes(phrase));
  assert.match(outputText(got.messages[1]),/omitted_tool_context/);
});
test('legal canonical same-argument reread stays full across turns and changed output invalidates only its decision', async () => {
  let calls=0; const runtime=new JevContextRuntime({loadPolicy:async()=>policy(),createController:controller,evaluate:async(_p,_s,qs)=>{calls++;return choose(qs);}});
  const first=output('one',longText()); const user=text('user','分析'); await runtime.beginRun('c','r1').prepare([user,first],4);
  const second=output('two',longText(),'{"limit":50000,"path":"a.txt"}'); const raw=[user,first,text('assistant','需要全文'),second];
  let got=await runtime.beginRun('c','r2').prepare(raw,4); assert.equal(outputText(got.messages[3]),longText()); assert.equal(calls,1);
  got=await runtime.beginRun('c','r3').prepare([...raw,text('assistant','继续')],4); assert.equal(outputText(got.messages[3]),longText()); assert.equal(calls,1);
  const changed={...first,parts:[{...tool(first),output:[{type:'text' as const,text:longText()+'changed',metadata:null}]}]};
  await runtime.beginRun('c','r4').prepare([user,changed],4); assert.equal(calls,2);
});
test('side effect and mixed multimodal outputs are never selected',async()=>{
 let calls=0; const runtime=new JevContextRuntime({loadPolicy:async()=>policy(),createController:controller,evaluate:async(_p,_s,qs)=>{calls++;return choose(qs);}});
 const write=output('w',longText(),'{}','file_write'); const mixed=output('m',longText()); tool(mixed).output.push({type:'image',url:'file://a.png',metadata:null});
 const raw=[text('user','分析'),write,mixed]; assert.deepEqual((await runtime.beginRun('c','r').prepare(raw,4)).messages,raw); assert.equal(calls,0);
});
test('retention runs in background, keeps decided raw text after leaving window and respects only active',async()=>{
 let resolve!: (v:JevEvaluateResult)=>void; const pending=new Promise<JevEvaluateResult>(r=>resolve=r);
 let qs:Record<string,JevQuestion>={}; let current=policy({selectionMode:'off',retentionMode:'active'});
 const runtime=new JevContextRuntime({loadPolicy:async()=>current,createController:controller,evaluate:async(_p,_s,q)=>{qs=q;return pending;}});
 const raw=[text('user','继续'),output('one','x'.repeat(6000)),text('assistant','已使用')]; const run=runtime.beginRun('c','r');
 const before=await run.prepare(raw,4); assert.equal(before.retainedToolCallIds.size,0); assert.ok(Object.keys(qs).length>0);
 resolve(choose(qs)); await run.waitForPending();
 const after=await run.prepare([...raw,text('user','继续'),text('assistant','继续'),text('user','继续')],2); assert.ok(after.retainedToolCallIds.has('one'));
 assert.equal(outputText(editPreparedContext(after.messages,2,after.retainedToolCallIds).messages[1]),'x'.repeat(6000));
 current=policy({selectionMode:'off',retentionMode:'shadow'}); assert.equal((await run.prepare(after.messages,2)).retainedToolCallIds.size,0);
});
test('unresolved retention freezes old clearing on first exit and late result cannot flip',async()=>{
 let resolve!: (v:JevEvaluateResult)=>void;const pending=new Promise<JevEvaluateResult>(r=>resolve=r);let qs:Record<string,JevQuestion>={};
 const runtime=new JevContextRuntime({loadPolicy:async()=>policy({selectionMode:'off',retentionMode:'active'}),createController:controller,evaluate:async(_p,_s,q)=>{qs=q;return pending;}});
 const raw=[text('user','继续'),output('one','x'.repeat(6000)),text('assistant','已使用')];const run=runtime.beginRun('c','r');await run.prepare(raw,4);
 const old=[...raw,text('user','继续'),text('assistant','继续'),text('user','继续')];assert.equal((await run.prepare(old,2)).retainedToolCallIds.size,0);
 resolve(choose(qs));await run.waitForPending();assert.equal((await run.prepare(old,2)).retainedToolCallIds.size,0);
});
test('new run, abort and changed consent reject old asynchronous projection and retention',async()=>{
 for(const kind of ['newrun','abort','consent']){
 let resolve!: (v:JevEvaluateResult)=>void;const pending=new Promise<JevEvaluateResult>(r=>resolve=r);let qs:Record<string,JevQuestion>={};let current=policy();const ctrl=new AbortController();
 const runtime=new JevContextRuntime({loadPolicy:async()=>current,createController:controller,evaluate:async(_p,_s,q)=>{qs=q;return pending;}});
 const run=runtime.beginRun('c','r1',ctrl.signal);const raw=[text('user','分析'),output('one',longText())];const result=run.prepare(raw,4); await new Promise(r=>setImmediate(r));
 if(kind==='newrun')runtime.beginRun('c','r2');else if(kind==='abort')ctrl.abort();else current=policy({selectionConsentKey:'changed'});
 resolve(choose(qs));assert.deepEqual((await result).messages,raw);
 }
});
test('manual compression consumes projected source while preserving source identity and original storage',async()=>{
 const raw=[text('user','分析'),output('one',longText()),...Array.from({length:6},(_,i)=>text(i%2===0?'user':'assistant','step '+i))];
 const conversation=makeConversation('a',raw.map(m=>makeMessageNode([m])));const before=JSON.stringify(conversation);const runtime=new JevContextRuntime({loadPolicy:async()=>policy(),createController:controller,evaluate:async(_p,_s,qs)=>choose(qs)});const run=runtime.beginRun('a','manual');let prompt='';
 const store=createMemoryCompactStore();const result=await compactConversation(conversation,makeCompactPolicy({keepRecentTurns:2}),128000,'manual_compact','',true,{store,prepareToolResults:(messages,keep)=>run.prepare(messages,keep),provider:{streamText:async(messages,onChunk)=>{prompt=messages.map(m=>m.parts.map(p=>p.type==='text'?p.text:'').join('')).join('');onChunk({id:'c',model:'m',usage:null,choices:[{index:0,finishReason:null,message:null,delta:text('assistant',JSON.stringify({schema_version:2,timeline_summary:'第一句。第二句。第三句。第四句。',handoff_markdown:'## Goal\n'+'保留执行状态。'.repeat(20)}))}]});}}});
 assert.equal(result.status,'completed');assert.match(prompt,/omitted_tool_context/);assert.equal(JSON.stringify(conversation),before);assert.deepEqual((await store.getCompacts('a'))[0].sourceMessageIds,raw.slice(0,4).map(m=>m.id));
});

test('output-only permission sends no task or tool metadata, and shadow observations do not repeat',async()=>{
 let calls=0;const states:string[]=[];const runtime=new JevContextRuntime({loadPolicy:async()=>policy({selectionMode:'shadow',retentionMode:'shadow',selectionTaskText:false,selectionToolMetadata:false,retentionTaskText:false,retentionToolMetadata:false}),createController:controller,evaluate:async(_p,state,qs)=>{calls++;states.push(JSON.stringify(state));return choose(qs);}});
 const raw=[text('user','PRIVATE TASK'),output('one',longText()),text('assistant','PRIVATE ANSWER')];const run=runtime.beginRun('c','r');await run.prepare(raw,4);await run.waitForPending();await run.prepare(raw,4);await run.waitForPending();
 assert.equal(calls,2);for(const state of states){assert.doesNotMatch(state,/PRIVATE|file_read|\"task\"|\"outline\"|\"tool\"/);}
});

test('real prepared context preserves selected and re-read outputs while off keeps existing editor behavior',async()=>{
 const raw=[text('user','分析'),output('one',longText()),...Array.from({length:6},(_,i)=>text(i%2===0?'user':'assistant','step '+i))];const conversation=makeConversation('a',raw.map(m=>makeMessageNode([m])));
 for(const mode of ['off','active'] as const){
  const runtime=new JevContextRuntime({loadPolicy:async()=>policy({selectionMode:mode}),createController:controller,evaluate:async(_p,_s,qs)=>choose(qs)});const run=runtime.beginRun('a','r');
  const result=await prepareContext(conversation,makeCompactPolicy({enabled:true,notifyOnly:true,keepRecentTurns:2}),128000,raw,0,{store:createMemoryCompactStore(),prepareToolResults:(messages,keep)=>run.prepare(messages,keep),provider:{streamText:async()=>{throw new Error('should not compress')}}});
  assert.match(outputText(result.messages[1]),mode==='off'?/cleared_tool_result/:/omitted_tool_context/);
 }
});
test('manual compression with Jev off preserves original full source text',async()=>{
 const marker='ORIGINAL_SOURCE_TOKEN';const raw=[text('user','分析'),output('one',marker+'x'.repeat(9000)),...Array.from({length:6},(_,i)=>text(i%2===0?'user':'assistant','step '+i))];const conversation=makeConversation('a',raw.map(m=>makeMessageNode([m])));
 const runtime=new JevContextRuntime({loadPolicy:async()=>policy({selectionMode:'off'}),createController:controller,evaluate:async()=>{throw new Error('must not call')}});const run=runtime.beginRun('a','manual');let prompt='';
 await compactConversation(conversation,makeCompactPolicy({keepRecentTurns:2}),128000,'manual_compact','',true,{store:createMemoryCompactStore(),prepareToolResults:(messages,keep)=>run.prepare(messages,keep),provider:{streamText:async(messages,onChunk)=>{prompt=messages.map(m=>m.parts.map(p=>p.type==='text'?p.text:'').join('')).join('');onChunk({id:'c',model:'m',usage:null,choices:[{index:0,finishReason:null,message:null,delta:text('assistant',JSON.stringify({schema_version:2,timeline_summary:'第一句。第二句。第三句。第四句。',handoff_markdown:'## Goal\n'+'保留执行状态。'.repeat(20)}))}]});}}});
 assert.ok(prompt.includes(marker));assert.doesNotMatch(prompt,/cleared_tool_result/);
});

test('same assistant message later tool part is a full reread, not hidden again',async()=>{
 let calls=0;const runtime=new JevContextRuntime({loadPolicy:async()=>policy(),createController:controller,evaluate:async(_p,_s,qs)=>{calls++;return choose(qs)}});const run=runtime.beginRun('c','r');
 const original=output('one',longText());const first=[text('user','分析'),original];await run.prepare(first,4);
 const reread={...original,parts:[tool(original),tool(output('two',longText(),'{"limit":50000,"path":"a.txt"}'))]};const result=await run.prepare([first[0],reread],4);
 const rereadText=(result.messages[1].parts[1] as UIMessagePartTool).output[0];assert.equal(rereadText.type==='text'?rereadText.text:'',longText());assert.equal(calls,1);assert.ok(result.retainedToolCallIds.has('two'));
});

test('explicit full text bypasses evaluation but off/shadow preserve the original prepared editor behavior',async()=>{
 const raw=[text('user','分析'),output('one',longText()),text('assistant','读过'),text('user','step'),text('assistant','step'),text('user','请保留全文')];
 for(const mode of ['off','shadow','active'] as const){
  const runtime=new JevContextRuntime({loadPolicy:async()=>policy({selectionMode:mode}),createController:controller,evaluate:async()=>{throw new Error('full text should not evaluate')}});const run=runtime.beginRun('c','r');const projected=await run.prepare(raw,4);const got=editPreparedContext(projected.messages,4,projected.retainedToolCallIds);
  assert.equal(outputText(got.messages[1]).includes('cleared_tool_result'),mode!=='active');
 }
});

test('retention recognizes final assistant text appended after the tool result in the same message',async()=>{
 let calls=0;const runtime=new JevContextRuntime({loadPolicy:async()=>policy({selectionMode:'off',retentionMode:'active'}),createController:controller,evaluate:async(_p,_s,qs)=>{calls++;return choose(qs)}});
 const carrier=output('one','x'.repeat(6000));carrier.parts.push({type:'text',text:'已使用该结果',metadata:null});const run=runtime.beginRun('c','r');const raw=[text('user','分析'),carrier];await run.prepare(raw,4);await run.waitForPending();
 assert.equal(calls,1);const later=await run.prepare([...raw,...Array.from({length:5},()=>text('user','继续'))],4);assert.ok(later.retainedToolCallIds.has('one'));
});

test('calls already present before the first hide are not retroactively classified as rereads',async()=>{
 let calls=0;const runtime=new JevContextRuntime({loadPolicy:async()=>policy(),createController:controller,evaluate:async(_p,_s,qs)=>{calls++;return choose(qs)}});const run=runtime.beginRun('c','r');const carrier=output('one',longText());carrier.parts.push(tool(output('two',longText())));const raw=[text('user','分析'),carrier];await run.prepare(raw,4);const again=await run.prepare(raw,4);const second=(again.messages[1].parts[1] as UIMessagePartTool).output[0];assert.match(second.type==='text'?second.text:'',/omitted_tool_context/);assert.equal(calls,1);
});

test('production file_read JSON content and session_read transcript are projected without dropping envelope fields',async()=>{
 for(const name of ['file_read','session_read']){
  let calls=0;const runtime=new JevContextRuntime({loadPolicy:async()=>policy(),createController:controller,evaluate:async(_p,state,qs)=>{calls++;assert.match(JSON.stringify(state),/UNRELATED/);return choose(qs)}});
  const payload=name==='file_read'?buildFileReadJson('notes/a.txt',longText(),65536):{status:'ok',session:{id:'historical'},message_count:8,max_chars:60000,truncated:false,transcript:longText()};const raw=JSON.stringify(payload);const messages=[text('user','分析'),output('one',raw,'{}',name)];const got=await runtime.beginRun('c','r').prepare(messages,4);const projected=JSON.parse(outputText(got.messages[1]));
  assert.equal(calls,1);assert.match(projected[name==='file_read'?'content':'transcript'],/omitted_tool_context/);assert.equal(projected.truncated,false);assert.equal(projected[name==='file_read'?'path':'message_count'],name==='file_read'?'notes/a.txt':8);assert.equal(outputText(messages[1]),raw);
 }
});

test('scope snapshot used to build each purpose state reaches the shared pre-dispatch consent gate',async()=>{
 const snapshot=makeJevSettings({mode:'active',apiKey:'old',model:'model'});const seen:string[]=[];const runtime=new JevContextRuntime({loadPolicy:async()=>policy({retentionMode:'active',settingsSnapshot:snapshot}),createController:controller,evaluate:async(p,_s,qs,_signal,expected)=>{assert.equal(expected,snapshot);seen.push(p);return choose(qs)}});const raw=[text('user','分析'),output('one',longText()),text('assistant','已使用')];const run=runtime.beginRun('c','r');await run.prepare(raw,4);await run.waitForPending();assert.deepEqual(seen.sort(),['context_retention','tool_context_selection']);
});
