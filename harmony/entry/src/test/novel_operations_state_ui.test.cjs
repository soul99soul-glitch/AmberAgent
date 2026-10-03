const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const { novelChapterParagraphs } = require('../../../deepread/src/main/ets/novel/specialized_operations.ts');
const { verifyAuditCanonicalReferences } = require('../../../deepread/src/main/ets/novel/continuity_audit.ts');
const { materialSuggestionChapterDigest } = require('../../../deepread/src/main/ets/novel/material_adoption.ts');
const { defaultGhostwriteDigest } = require('../../../deepread/src/main/ets/novel/ghostwrite.ts');
function methods(file, names) {
 let source=fs.readFileSync(path.join(__dirname,'../main/ets',file),'utf8');
 if(file.includes('NovelWorkspaceService')) source=source.slice(source.indexOf('class EntryNovelWorkspaceService'));
 return names.map(name=>{
  const m=new RegExp('^  (?:private )?(?:async )?'+name+'\\(','m').exec(source);assert.ok(m,name);
  let depth=1,end=source.indexOf('{',m.index)+1;
  for(;depth&&end<source.length;end++){if(source[end]==='{')depth++;if(source[end]==='}')depth--;}
  return source.slice(m.index,end);
 }).join('\n');
}
const cas={branchId:'main',head:'h1',treeDigest:'tree'};
function operations(service={},creation={}) {
 const code=ts.transpileModule('class Page {'+methods('pages/NovelOperationsPage.ets',['reload','chapters','selectedChapter','paragraphs','validOrdinal','selectedSource','ready','submit','resolve'])+'}\nreturn Page;', {compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const Page=new Function('getNovelCreation','getNovelWorkspaceService','novelChapterParagraphs','materialSuggestionChapterDigest',code)(()=>creation,()=>service,novelChapterParagraphs,materialSuggestionChapterDigest);
 const page=new Page();Object.assign(page,{projectId:'p',project:{chapters:[{id:'c',content:'  第一段\r\n同段续行\r\n\r\n 第二段  ',discarded:false}]},chapterId:'c',cas,alive:true,loading:false,busy:false,mode:'revise_chapter',startText:'1',endText:'1',replacement:'新的第一段',countText:'1',selectedIds:[],error:'',notice:'',token:0,proposals:[]});page.reload=async()=>{};return page;
}
test('paragraph preview and proposal use domain UTF16 spans and original untrimmed text',async()=>{
 let args;const page=operations({proposeManuscriptOperation:async(...a)=>{args=a;}});
 assert.equal(page.selectedSource(),'  第一段\r\n同段续行');await page.submit();
 assert.equal(args[0],'p');assert.equal(args[1],'revise_chapter');assert.equal(args[2].expected_text,page.selectedSource());assert.equal(args[2].source_digest,materialSuggestionChapterDigest(page.project.chapters[0]));assert.equal(args[3],cas);assert.equal(page.busy,false);
});
test('reversed or fractional range does not produce a write and keeps author input',async()=>{
 let writes=0;const page=operations({proposeManuscriptOperation:async()=>{writes++;}});
 for(const [start,end] of [['2','1'],['1.5','2'],['1','3']]){page.startText=start;page.endText=end;await page.submit();assert.equal(page.ready(),false);}
 assert.equal(writes,0);assert.equal(page.replacement,'新的第一段');
});
test('delete/revert proposals retain actual chapter IDs and explicit integer count',async()=>{
 const calls=[];const page=operations({proposeManuscriptOperation:async(...a)=>{calls.push(a);}});
 page.mode='delete_chapters';page.selectedIds=['c'];await page.submit();assert.deepEqual(calls[0][2],{chapter_ids:['c']});
 page.mode='revert_recent_chapters';page.countText='1.2';await page.submit();assert.equal(calls.length,1);
 page.countText='1';await page.submit();assert.deepEqual(calls[1][2],{chapter_count:1});
});
test('CAS failure remains visible; accept follows durable proposal resolution and does not write raw chapter',async()=>{
 const page=operations({proposeManuscriptOperation:async()=>{throw Error('CAS 已变化');}});await page.submit();assert.match(page.error,/CAS/);assert.equal(page.notice,'');
 let accepted;const next=operations({}, {resolveWorkspaceProposal:async(...args)=>{accepted=args;return {proposalId:args[1],status:'accepted'};}});await next.resolve({proposalId:'proposal',status:'pending'},true);assert.deepEqual(accepted,['p','proposal',true]);
});
function state(creation={}) {
 const code=ts.transpileModule('class Page {'+methods('pages/NovelStatePage.ets',['unlisten','subscribe','rebuild','cancel','resolveIdentity','evidence'])+'}\nreturn Page;', {compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const pushes=[];const Page=new Function('getNovelCreation','router','defaultGhostwriteDigest',code)(()=>creation,{pushUrl:async x=>{pushes.push(x);}},defaultGhostwriteDigest);
 const page=new Page();Object.assign(page,{projectId:'p',cas,alive:true,visible:true,loading:false,busy:false,active:false,error:'',notice:'',unsubscribe:null,reload:async()=>{}});return{page,pushes};
}
test('state progress subscription detaches and ignores callbacks after hide',()=>{
 let callback,unsub=0;const{page}=state({observeStateOperation:(_id,cb)=>{callback=cb;return()=>{unsub++;};}});let reads=0;page.reload=()=>{reads++;};page.subscribe();callback({cursor:1});assert.equal(reads,1);page.visible=false;callback({cursor:2});assert.equal(reads,1);page.unlisten();assert.equal(unsub,1);
});
test('identity decision retains the CAS frozen at author confirmation and surfaces failure',async()=>{
 let args;const{page}=state({resolveIdentityClarification:async(...a)=>{args=a;throw Error('工作区变化');}});page.cas={...cas,branchId:'other'};await page.resolveIdentity('阿远','merge','person',cas);assert.equal(args[4],cas);assert.match(page.error,/变化/);assert.equal(page.busy,false);
});
test('evidence requires exact source and visible page before routing',async()=>{
 let release;const pending=new Promise(r=>{release=r;});const{page,pushes}=state({readWorkspaceSnapshot:()=>pending});const content='真实原文';const done=page.evidence({chapterId:'c',quote:content,sourceDigest:defaultGhostwriteDigest(content)});page.visible=false;release({project:{chapters:[{id:'c',content}]},status:{cas,activeBranchId:'main'}});await done;assert.equal(pushes.length,0);
});
test('state rescue runtime failure is visible and retry delegates persisted cursor',async()=>{
 let retries=0;const{page}=state({retryStateRebuild:async()=>{retries++;throw Error('模型解析失败');}});await page.rebuild(true);assert.equal(retries,1);assert.match(page.error,/解析/);assert.equal(page.busy,false);assert.equal(page.notice,'');
});
test('persisted failed rescue result never claims completed',async()=>{
 const{page}=state({startStateRebuild:async()=>({status:'failed',error:'严格 JSON 失败'})});await page.rebuild(false);assert.equal(page.notice,'');
});
function workspace(creation={}) {
 const code=ts.transpileModule('class Page {'+methods('pages/NovelWorkspacePage.ets',['openGhostwriteBrief','submitGhostwriteBrief','repairAuditIssue','repairAuditIssues','cancelAuditRepair','openAuditEvidence'])+'}\nreturn Page;', {compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const pushes=[];const Page=new Function('getNovelCreation','router','verifyAuditCanonicalReferences','defaultGhostwriteDigest',code)(()=>creation,{pushUrl:async x=>{pushes.push(x);}},verifyAuditCanonicalReferences,defaultGhostwriteDigest);
 const page=new Page();Object.assign(page,{projectId:'p',pageAlive:true,pageHiddenOnce:false,ghostwriteBusy:false,ghostwriteBriefOpenToken:0,ghostwriteJob:{jobId:'j',branchId:'main',stage:'paused',currentChapterOrdinal:2,candidate:{digest:'candidate'}},ghostwriteBriefOpen:false,ghostwriteBriefCas:null,ghostwritePollToken:0,ghostwriteBriefError:'',auditRepairBusy:false,auditRepairToken:0,auditBranchId:'main',busy:false,hasBlockingNovelRun:()=>false,auditReportStale:()=>false,reload:async()=>{},scheduleGhostwritePoll:()=>{},showWorkspaceOperationError:(_label,e)=>{page.error=String(e);}});return{page,pushes};
}
test('ghostwrite brief freezes job candidate and snapshot CAS and failed submit preserves sheet',async()=>{
 let args;const{page}=workspace({readWorkspaceSnapshot:async()=>({status:{cas,activeBranchId:'main',activeBranchName:'主线'}}),reviseGhostwriteWithBrief:async(...a)=>{args=a;throw Error('候选已变化');}});
 await page.openGhostwriteBrief();assert.equal(page.ghostwriteBriefOpen,true);assert.match(page.ghostwriteBriefDetail,/第 2 章/);await page.submitGhostwriteBrief('明确改写人物决定');assert.deepEqual(args,['p','j','明确改写人物决定',cas,'candidate']);assert.equal(page.ghostwriteBriefOpen,true);assert.match(page.ghostwriteBriefError,/候选已变化/);
});
test('late ghostwrite dialog and audit repair result cannot open on hidden page',async()=>{
 let release;let pending=new Promise(r=>{release=r;});const{page,pushes}=workspace({readWorkspaceSnapshot:()=>pending,repairContinuityIssues:()=>pending});let done=page.openGhostwriteBrief();page.ghostwriteBriefOpenToken++;release({status:{cas,activeBranchId:'main',activeBranchName:'主线'}});await done;assert.equal(page.ghostwriteBriefOpen,false);
 pending=new Promise(r=>{release=r;});done=page.repairAuditIssue({chapterId:'c'});page.pageHiddenOnce=true;page.auditRepairToken++;release({proposalId:'repair'});await done;assert.equal(pushes.length,0);
});

test('external branch change cannot rebind unsent paragraph draft to a fresh CAS',async()=>{
 const creation={readWorkspaceSnapshot:async()=>({project:{id:'p',chapters:[{id:'c',content:'另一分支正文',discarded:false}]},status:{cas:{...cas,branchId:'other'}}}),workspaceProposals:async()=>[]};
 const page=operations({},creation);delete page.reload;await page.reload();assert.equal(page.cas,cas);assert.equal(page.project.chapters[0].content,'  第一段\r\n同段续行\r\n\r\n 第二段  ');assert.match(page.error,/输入已保留/);assert.equal(page.replacement,'新的第一段');
});
const {createNovelCreation}=require('../../../deepread/src/main/ets/novel/creation.ts');
const {createFileNovelRepository}=require('../../../deepread/src/main/ets/novel/repository.ts');
const {createMemoryFileStore}=require('../../../deepread/src/main/ets/platform/files.ts');
function actualService(creation) {
 const code=ts.transpileModule('class Service {'+methods('platform_impl/NovelWorkspaceService.ets',['proposeManuscriptOperation'])+'}\nreturn Service;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const Service=new Function(code)();const service=new Service();service.creation=creation;return service;
}
test('real UI -> thin service -> repository stages and approves paragraph revision; actual reload closes current pending card',async()=>{
 const creation=createNovelCreation({repository:createFileNovelRepository(createMemoryFileStore()),modelRunning:{validate:async()=>{},start:()=>{throw Error('no model');},cancel(){}}});
 const project=await creation.create('正文操作闭环');const chapter=await creation.saveChapter(project.id,null,'章节','  第一段\r\n同段续行\r\n\r\n 第二段  ');
 const page=operations(actualService(creation),creation);page.projectId=project.id;page.project=null;page.chapterId=chapter.id;page.replacement='';delete page.reload;await page.reload();page.replacement='新的第一段';
 await page.submit();assert.equal(page.error,'');assert.equal(page.proposals.length,1);assert.equal(page.proposals[0].status,'pending');const previousCas=page.cas;
 await page.resolve(page.proposals[0],true);assert.equal(page.error,'');assert.equal(page.replacement,'');assert.equal(page.proposals[0].status,'accepted');assert.notEqual(page.cas.head,previousCas.head);assert.equal(page.project.chapters[0].content,'新的第一段\r\n\r\n 第二段  ');
});
test('manual service rejects ordinary active generation while existing proposal resolver remains available',async()=>{
 let writes=0;const service=actualService({activeRun:()=>({runKind:'discuss'}),proposeProjectOperation:async()=>{writes++;}});await assert.rejects(service.proposeManuscriptOperation('p','delete_chapters',{chapter_ids:['c']},cas),/创作正在进行/);assert.equal(writes,0);
});

test('approving proposal A after editing draft B updates accepted card but retains B with its original source/CAS',async()=>{
 const creation=createNovelCreation({repository:createFileNovelRepository(createMemoryFileStore()),modelRunning:{validate:async()=>{},start:()=>{throw Error('no model');},cancel(){}}});
 const project=await creation.create('保留未提交稿');const chapter=await creation.saveChapter(project.id,null,'章节','原文');
 const page=operations(actualService(creation),creation);page.projectId=project.id;page.project=null;page.chapterId=chapter.id;page.replacement='';delete page.reload;await page.reload();page.replacement='提案 A';await page.submit();assert.equal(page.error,'');
 const before=page.cas;const proposal=page.proposals[0];page.replacement='尚未提交的 B';await page.resolve(proposal,true);
 assert.equal(page.proposals[0].status,'accepted');assert.equal(page.replacement,'尚未提交的 B');assert.equal(page.cas,before);assert.equal(page.project.chapters[0].content,'原文');assert.match(page.error,/输入已保留/);assert.equal((await creation.open(project.id)).chapters[0].content,'提案 A');
});

test('all audit issues use a single proposal and user cancellation waits for the real model cancellation without routing',async()=>{
 let release,finishCancel;const pending=new Promise(r=>{release=r;});const cancelled=new Promise(r=>{finishCancel=r;});let args;
 const {page,pushes}=workspace({repairContinuityIssues:async(...a)=>{args=a;return pending;},cancelContinuityRepair:async()=>cancelled});
 const issues=[{chapterId:'c1'},{chapterId:'c2'}];const repair=page.repairAuditIssues(issues);assert.deepEqual(args,['p',issues]);assert.equal(page.auditRepairBusy,true);
 const stop=page.cancelAuditRepair();release({proposalId:'never-open'});await repair;assert.equal(page.auditRepairBusy,true);assert.equal(pushes.length,0);finishCancel();await stop;assert.equal(page.auditRepairBusy,false);assert.equal(page.error,undefined);
});
test('actual Timeline builder renders unassigned environmental events in chapter order alongside person events',()=>{
 const code=ts.transpileModule('class Page {'+methods('pages/NovelStatePage.ets',['timelineEvents','Timeline'])+'}\nreturn Page;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const labels=[],rendered=[];const text=value=>{labels.push(value);const chain=new Proxy({}, {get:()=>()=>chain});return chain;};
 const Page=new Function('Text','ForEach','INK','INK3',code)(text,(items,render)=>items.forEach(render),'#ink','#muted');const page=new Page();
 const ambient={id:'ambient',chapterId:'c1',entityRefs:[],summary:'城中断电',quote:'灯光同时熄灭',sourceDigest:'d1'};
 const person={id:'person',chapterId:'c2',entityRefs:['hero'],summary:'主角出城',quote:'他走出城门',sourceDigest:'d2'};
 const removed={id:'discarded',chapterId:'gone',entityRefs:[],summary:'旧稿事件',quote:'旧稿',sourceDigest:'old'};
 page.project={chapters:[{id:'c1'},{id:'c2'},{id:'gone',discarded:true}],structuredState:{events:[person,removed,ambient]}};
 page.EventRow=event=>rendered.push(event);page.Timeline();assert.deepEqual(rendered,[ambient,person]);assert.ok(labels.includes('当前分支事件时间线'));assert.equal(page.project.structuredState.events[0],person,'render does not mutate persisted event ordering');
});
test('actual preview keys retain both same-chapter repairs and duplicate nonchapter labels',()=>{
 const code=ts.transpileModule('class Page {'+methods('pages/NovelOperationsPage.ets',['previewKey'])+'}\nreturn Page;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const Page=new Function(code)();const page=new Page();const previews=[{chapterId:'c',label:'修复第1章',start:2,end:4},{chapterId:'c',label:'修复第1章',start:8,end:10}];
 const rendered=new Map();previews.forEach((preview,index)=>rendered.set(page.previewKey(preview,index),preview));assert.deepEqual([...rendered.values()],previews);
 assert.notEqual(page.previewKey({label:'计划'},0),page.previewKey({label:'计划'},1));
 const source=fs.readFileSync(path.join(__dirname,'../main/ets/pages/NovelOperationsPage.ets'),'utf8');assert.match(source,/\(preview: WorkspaceProposalPreview, index: number\): string => this\.previewKey\(preview, index\)/);
});
test('identity confirmation accurately describes future extraction and never promises immediate old-event relinking',()=>{
 const code=ts.transpileModule('class Page {'+methods('pages/NovelStatePage.ets',['characters','confirmIdentity'])+'}\nreturn Page;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const dialogs=[];const Page=new Function('AlertDialog',code)({show:dialog=>dialogs.push(dialog)});const page=new Page();Object.assign(page,{busy:false,active:false,cas,project:{materials:[{id:'hero',kind:'character',title:'林远'}]}});
 page.confirmIdentity('阿远','create',null);page.confirmIdentity('阿远','merge','hero');assert.equal(dialogs.length,2);for(const dialog of dialogs){assert.match(dialog.message,/供后续状态提取和人物经历关联使用/);assert.doesNotMatch(dialog.message,/已提取事件|明确关联其经历/);}
});

test('canonical c2 -> c3 evidence routes to earlier chapter with its exact quote/range and current branch',async()=>{
 const chapters=[{id:'c2',ordinal:2,title:'先文',content:'前缀。先文已确认的事实。尾声'},{id:'c3',ordinal:3,title:'后文',content:'矛盾的后文'}];const quote='先文已确认的事实。';const start=chapters[0].content.indexOf(quote);
 const reference={chapterId:'c2',sourceDigest:defaultGhostwriteDigest(chapters[0].content),quote,start,end:start+quote.length,chapterOrdinal:2,chapterTitle:'先文'};
 const{page,pushes}=workspace({readWorkspaceSnapshot:async()=>({project:{chapters},status:{cas,activeBranchId:'main'}})});await page.openAuditEvidence(reference,'c3');assert.equal(pushes.length,1);assert.deepEqual(pushes[0].params,{projectId:'p',chapterId:'c2',evidenceQuote:quote,evidenceStart:start,evidenceEnd:start+quote.length,evidenceDigest:reference.sourceDigest,evidenceBranchId:'main'});
});
test('canonical stale chapter, changed range or different branch refuses navigation and reports visible error',async()=>{
 const original='先文事实';const reference={chapterId:'c2',sourceDigest:defaultGhostwriteDigest(original),quote:original,start:0,end:original.length,chapterOrdinal:2,chapterTitle:'先文'};
 for(const variant of ['digest','range','branch']){
  const chapters=[{id:'c2',title:'先文',content:variant==='digest'?'已经重写':original},{id:'c3',title:'后文',content:'后文'}];
  const {page,pushes}=workspace({readWorkspaceSnapshot:async()=>({project:{chapters},status:{cas:variant==='branch'?{...cas,branchId:'other'}:cas,activeBranchId:variant==='branch'?'other':'main'}})});
  await page.openAuditEvidence(variant==='range'?{...reference,start:1}:reference,'c3');assert.equal(pushes.length,0);assert.match(page.error??page.errorMsg,/变化|证据|旧稿/);
 }
});
test('actual canonical evidence builder displays earlier ordinal/title/quote and delegates its source pointer',()=>{
 const code=ts.transpileModule('class View {'+methods('components/NovelAuditReportView.ets',['CanonicalEvidence'])+'}\nreturn View;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const texts=[],clicks=[];const Text=value=>{texts.push(value);const chain=new Proxy({}, {get:(_o,key)=>key==='onClick'?action=>{clicks.push(action);return chain;}:()=>chain});return chain;};
 const View=new Function('Text','ForEach','INK','INK3','ACCENT','SURFACE2',code)(Text,(items,render)=>items.forEach(render),'ink','muted','accent','surface');const view=new View();const reference={chapterId:'c2',chapterOrdinal:2,chapterTitle:'先文标题',quote:'原始先文事实',start:0,end:6};const issue={chapterId:'c3',canonicalReferences:[reference]};let opened;
 view.onCanonicalEvidence=(target,source)=>{opened=[target,source];};view.CanonicalEvidence(issue);assert.ok(texts.some(t=>t.includes('第 2 章')&&t.includes('先文标题')));assert.ok(texts.includes(reference.quote));clicks[0]();assert.deepEqual(opened,[issue,reference]);
});
test('actual experience card key refreshes completed rescue events and merged aliases, while identical reload remains stable',()=>{
 const code=ts.transpileModule('class Page {'+methods('pages/NovelStatePage.ets',['experienceKey'])+'}\nreturn Page;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const Page=new Function('defaultGhostwriteDigest',code)(defaultGhostwriteDigest);const page=new Page();
 const initial={materialId:'rhea',title:'Rhea',aliases:['CaptainRhea'],events:[]};
 const completed={...initial,events:[{id:'c2-event',chapterId:'c2',sourceDigest:'d2',summary:'检查风暴地图',quote:'Rhea checks the map.',entityRefs:['rhea']},{id:'c3-event',chapterId:'c3',sourceDigest:'d3',summary:'带地图回家',quote:'Rhea brings the map home.',entityRefs:['rhea']}]};
 const merged={...completed,aliases:['CaptainRhea','Rider']};const retainedCards=new Map();
 function render(experience){const key=page.experienceKey(experience);if(!retainedCards.has(key))retainedCards.set(key,JSON.parse(JSON.stringify(experience)));return retainedCards.get(key);}
 assert.equal(render(initial).events.length,0);assert.equal(render(completed).events.length,2);assert.deepEqual(render(merged).aliases,['CaptainRhea','Rider']);assert.equal(retainedCards.size,3);
 assert.equal(page.experienceKey(merged),page.experienceKey(JSON.parse(JSON.stringify(merged))));render(JSON.parse(JSON.stringify(merged)));assert.equal(retainedCards.size,3,'unchanged reload retains same card identity');
 assert.notEqual(page.experienceKey(merged),page.experienceKey({...merged,title:'Captain Rhea'}));
 const source=fs.readFileSync(path.join(__dirname,'../main/ets/pages/NovelStatePage.ets'),'utf8');assert.match(source,/\(experience: NovelCharacterExperience\): string => this\.experienceKey\(experience\)/);
});

test('actual Timeline builder changes key for same-id new summary, and retains key for identical event DTO',()=>{
 const code=ts.transpileModule('class Page {'+methods('pages/NovelStatePage.ets',['timelineEvents','timelineEventKey','Timeline'])+'}\nreturn Page;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const keys=[],summaries=[];const Text=()=>{const chain=new Proxy({}, {get:()=>()=>chain});return chain;};
 const Page=new Function('Text','ForEach','INK','INK3','defaultGhostwriteDigest',code)(Text,(items,render,key)=>items.forEach((event,index)=>{keys.push(key(event,index));render(event);}), 'ink','muted',defaultGhostwriteDigest);
 const page=new Page();const original={id:'same-event',chapterId:'c2',sourceDigest:'same-body-digest',quote:'same source text',summary:'Local verification',entityRefs:['rhea']};page.project={chapters:[{id:'c2'}],structuredState:{events:[original]}};page.EventRow=event=>summaries.push(event.summary);
 page.Timeline();const updated={...original,summary:'P4-LIVE-REFRESH: updated summary'};page.project={...page.project,structuredState:{events:[updated]}};page.Timeline();page.project={...page.project,structuredState:{events:[JSON.parse(JSON.stringify(updated))]}};page.Timeline();
 assert.notEqual(keys[0],keys[1]);assert.equal(keys[1],keys[2]);assert.deepEqual(summaries,['Local verification','P4-LIVE-REFRESH: updated summary','P4-LIVE-REFRESH: updated summary']);
});

test('author ghostwrite preview hides only exact confirmed model headers and preserves raw legacy Digest text',()=>{
 const {makeNovelChapterContract,confirmedChapterPlanText,chapterContractMarkdown}=require('../../../deepread/src/main/ets/novel/chapter_contract.ts');
 const code=ts.transpileModule('class Page {'+methods('pages/NovelWorkspacePage.ets',['ghostwriteAuthorPlanPreview'])+'}\nreturn Page;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;
 const Page=new Function('confirmedChapterPlanText','chapterContractMarkdown',code)(confirmedChapterPlanText,chapterContractMarkdown);const page=new Page();
 const contract=makeNovelChapterContract({outlinePlacement:'第二幕',goalAndConflict:'找出使者',mustHappen:['收到来信'],mustNotHappen:['揭露主谋'],endingHook:'敲门声',visibleFacts:['Digest: 是作者主动写的线索']},'confirmed','main',100,'contract');
 const settings={chapterContract:contract,thisChapterPlan:''};settings.thisChapterPlan=confirmedChapterPlanText(settings);const modelPlan=settings.thisChapterPlan;page.project={branchSettings:settings};page.ghostwritePreview={planContent:modelPlan};
 assert.equal(page.ghostwriteAuthorPlanPreview(),'本章计划已确认\n\n'+chapterContractMarkdown(contract));assert.doesNotMatch(page.ghostwriteAuthorPlanPreview(),/Status: confirmed|Digest: [0-9a-f]{8}/);assert.match(page.ghostwriteAuthorPlanPreview(),/Digest: 是作者主动写的线索/);assert.equal(page.ghostwritePreview.planContent,modelPlan,'model and frozen source remain unchanged');
 const raw='Status: confirmed\nDigest: 作者自己写的编号\n原有完整计划';page.project={branchSettings:{thisChapterPlan:raw}};page.ghostwritePreview={planContent:raw};assert.equal(page.ghostwriteAuthorPlanPreview(),raw);
 page.project={branchSettings:settings};page.ghostwritePreview={planContent:raw};assert.equal(page.ghostwriteAuthorPlanPreview(),raw,'typed plan mismatch cannot rewrite author text');
});
