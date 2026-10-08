import { test } from 'node:test';
import assert from 'node:assert/strict';
import { runContinuityAudit, NovelAuditController, CONTINUITY_AUDIT_SYSTEM_PROMPT } from '../main/ets/novel/continuity_audit.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent } from '../main/ets/novel/model_running.ts';
import { makeNovelProject, makeNovelChapter } from '../main/ets/novel/models.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
const project = (bodies: string[]) => ({ ...makeNovelProject({id:'book',name:'book',now:1}),
  chapters: bodies.map((content,index) => makeNovelChapter({id:`c${index}`,title:`chapter${index}`,content,now:1})) });
const fixture = (respond: (request: NovelModelRequest,index:number) => string | Error | null,
  budget=5500) => {
  const requests: NovelModelRequest[] = [];
  const cancelled: string[] = [];
  let callback: ((event: NovelModelEvent)=>void)|null = null;
  const model: NovelModelRunning = {
    validate: async()=>{}, inputBudgetTokens: async(_target,id,cap)=>{assert.equal(id,'book');assert.equal(cap,8192);return budget;},
    estimateInputTokens:(system,user)=>system.length+user.length+16,
    cancel: id => { cancelled.push(id); },
    start: request => {
      const index = requests.length; requests.push(request);
      return {subscribe: cb => {
        callback = cb;
        queueMicrotask(()=>{
          const output = respond(request,index);
          if (output===null)return;
          if (output instanceof Error) cb({kind:'failed',message:output.message});
          else {cb({kind:'snapshot',messages:[makeAssistantMessage(output)],generationActive:false,textDeltasLive:false,transport:'live'});cb({kind:'completed'});}
        });
        return ()=>{callback=null;};
      }};
    },
  };
  return {model,requests,cancelled,hasSubscriber:()=>callback!==null};
};
test('audit covers >120k manuscript and >18k chapter from start through end without gaps',async()=>{
  const book=project(['FIRST-BEFORE-18K'+ '甲'.repeat(46000)+'END1', '乙'.repeat(85000)+'LAST-AFTER-120K']);
  const f=fixture(()=>'{"issues":[]}');
  book.materials=[{id:'fact',kind:'character',title:'name',content:'主角名字：林青',enabled:true,createdAt:1,updatedAt:1}];
  book.branchSettings.preferences='禁止主角死亡';
  book.branchSettings.confirmedDecisions=[{id:'decision',title:'年龄',content:'主角二十岁',confirmedAt:1}];
  const report=await runContinuityAudit(f.model,book,{kind:'global'});
  assert.equal(report.ok,true);assert.equal(report.coverage?.complete,true);
  assert.equal(report.coverage?.checkedChars,book.chapters.reduce((sum,c)=>sum+c.content.length,0));
  assert.ok(f.requests.length>20);
  for(const chapter of book.chapters){
    let end=0;
    for(const block of report.blocks!.filter(b=>b.chapterId===chapter.id)){assert.equal(block.start,end);end=block.end;}
    assert.equal(end,chapter.content.length);
  }
  const first=f.requests[0].operation; assert.equal(first.kind,'turn');
  if(first.kind==='turn')assert.ok(first.userPrompt.includes('FIRST-BEFORE-18K'));
  const last=f.requests.at(-1)!.operation;
  if(last.kind==='turn')assert.ok(last.userPrompt.includes('LAST-AFTER-120K'));
  for(const request of f.requests){assert.equal(request.toolProfile,'none');if(request.operation.kind==='turn'){
    assert.ok(request.operation.userPrompt.includes('主角名字：林青'));assert.ok(request.operation.userPrompt.includes('禁止主角死亡'));
    assert.ok(request.operation.userPrompt.includes('主角二十岁'));
    assert.ok(CONTINUITY_AUDIT_SYSTEM_PROMPT.length+request.operation.userPrompt.length+16<=5500);
  }}
});
test('audit failed block preserves partial report and does not mark full coverage',async()=>{
  const f=fixture((_r,index)=>index===1?new Error('bad provider'):'{"issues":[]}');
  const report=await runContinuityAudit(f.model,project(['甲'.repeat(14000)]),{kind:'global'});
  assert.equal(report.ok,false);assert.equal(report.coverage?.complete,false);
  assert.equal(report.blocks![1].error,'bad provider');
  assert.ok(report.coverage!.checkedChars>0);assert.ok(report.coverage!.checkedChars<report.coverage!.totalChars);
  assert.ok(report.blocks!.at(-1)!.status==='checked');
});
test('only exact quote in primary block with matching chapter ID and source digest is a verified issue',async()=>{
  const f=fixture(request=>{
    const prompt=request.operation.kind==='turn'?request.operation.userPrompt:'';
    const digest=/sourceDigest=(.*)\n/.exec(prompt)![1];
    return JSON.stringify({issues:[
      {summary:'valid',chapterId:'c0',sourceDigest:digest,quote:'真实证据'},
      {summary:'bad quote',chapterId:'c0',sourceDigest:digest,quote:'虚构证据'},
      {summary:'bad id',chapterId:'other',sourceDigest:digest,quote:'真实证据'},
      {summary:'bad digest',chapterId:'c0',sourceDigest:'old',quote:'真实证据'},
    ]});
  });
  const report=await runContinuityAudit(f.model,project(['开始真实证据结束']),{kind:'global'});
  assert.equal(report.issues.length,1);assert.equal(report.invalidEvidenceCount,3);assert.equal(report.ok,false);
  assert.equal(report.issues[0].start,2);assert.equal(report.issues[0].end,6);
});
test('cancel aborts current provider immediately and never schedules later chunks',async()=>{
  const f=fixture(()=>null);
  const control=new NovelAuditController();
  const result=runContinuityAudit(f.model,project(['甲'.repeat(20000)]),{kind:'global'},120000,control);
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(f.requests.length,1);control.cancel();
  const report=await result;
  assert.equal(report.cancelled,true);assert.equal(report.ok,false);assert.equal(report.coverage?.complete,false);
  assert.deepEqual(f.cancelled,[f.requests[0].runId]);assert.equal(f.requests.length,1);assert.equal(f.hasSubscriber(),false);
});
test('oversized author facts fail explicitly without dropping facts or sending truncated input',async()=>{
  const book=project(['原文']);book.branchSettings.preferences='约束'.repeat(10000);
  const f=fixture(()=>'{"issues":[]}');
  await assert.rejects(runContinuityAudit(f.model,book,{kind:'global'}),/作者必要事实/);
  assert.equal(f.requests.length,0);
});
test('UTF16 surrogate pairs remain intact at every chunk boundary',async()=>{
  const book=project(['😀'.repeat(10000)]);const f=fixture(()=>'{"issues":[]}');
  const report=await runContinuityAudit(f.model,book,{kind:'global'});
  assert.equal(report.ok,true);for(const block of report.blocks!){assert.equal(block.start%2,0);assert.equal(block.end%2,0);}
});
test('audit preserves explicit imported chapter ordinal and excludes discarded primary ranges',async()=>{
  const book=project(['discarded','正文证据']);book.chapters[0].discarded=true;book.chapters[1].ordinal=23;
  const f=fixture(request=>{
    const prompt=request.operation.kind==='turn'?request.operation.userPrompt:'';
    assert.ok(prompt.includes('本块：第23章'));
    return JSON.stringify({issues:[{summary:'issue',chapterId:'c1',sourceDigest:/sourceDigest=(.*)\n/.exec(prompt)![1],quote:'正文证据'}]});
  });
  const report=await runContinuityAudit(f.model,book,{kind:'global'});
  assert.equal(report.blocks.length,1);assert.equal(report.blocks[0].chapterId,'c1');
  assert.equal(report.issues[0].chapterRef,'第23章 chapter1');assert.equal(report.coverage.totalChars,4);
});
