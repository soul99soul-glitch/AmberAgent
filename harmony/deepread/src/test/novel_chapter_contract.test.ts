import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createProject } from '../main/ets/novel/mutations.ts';
import { makeNovelChapterContract, withNovelChapterContract, withNovelUpcomingArc } from '../main/ets/novel/chapter_contract.ts';
import { buildNovelContext } from '../main/ets/novel/context_builder.ts';
const input = { outlinePlacement:'第三章转折',goalAndConflict:'寻找来信，受到阻拦',mustHappen:['发现来信'],mustNotHappen:['揭露真凶'],endingHook:'陌生电话',visibleFacts:['主角只知道邮戳'] };
test('draft contract never enters writing context; author confirmation injects full contract and stable digest',()=>{
 const project=createProject('合同',1);
 const draft=withNovelChapterContract(project,input,'draft','main',2);
 assert.equal(buildNovelContext(draft,'write','whole_chapter').sections.some(s=>s.key==='chapter_plan'),false);
 const confirmed=withNovelChapterContract(draft,input,'confirmed','main',3);
 const plan=buildNovelContext(confirmed,'write','whole_chapter').sections.find(s=>s.key==='chapter_plan')!;
 assert.match(plan.text,/发现来信/);assert.match(plan.text,/揭露真凶/);assert.match(plan.text,/主角只知道邮戳/);
 assert.equal(confirmed.branchSettings.chapterContract!.contentDigest,makeNovelChapterContract(input,'draft','main',5).contentDigest);
});
test('upcoming arc follows actual iOS normalization and remains future direction',()=>{
 const p=withNovelUpcomingArc(createProject('arc',1),[' ', 'A','a',...Array.from({length:10},(_,i)=>`第${i}步${'x'.repeat(200)}`)],2);
 assert.equal(p.branchSettings.upcomingArc!.beats.length,8);
 assert.equal(p.branchSettings.upcomingArc!.beats[1].length,160);
 assert.equal(p.branchSettings.upcomingArc!.beats.filter(b=>b.toLowerCase()==='a').length,1);
 assert.match(buildNovelContext(p,'write','whole_chapter').sections.find(s=>s.key==='future_plan')!.text,/不替代本章计划/);
});

import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { buildNovelWorkspacePublicFiles, buildNovelWorkspaceImportPlan } from '../main/ets/novel/workspace_interop.ts';
import { parseNovelWorkspaceManifest } from '../main/ets/novel/workspace_contract.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/agent/message.ts';
import { NovelAuditController } from '../main/ets/novel/continuity_audit.ts';
import type { NovelModelRunning, NovelModelRequest } from '../main/ets/novel/model_running.ts';
const unusedModel:NovelModelRunning={async validate(){},start(){throw new Error('unused');},cancel(){}};

test('author confirmed contract and draft status survive real native/public installation and legacy edit supersedes contract',async()=>{
 for(const status of ['draft','confirmed'] as const){
  const repository=createFileNovelRepository(createMemoryFileStore());
  const creation=createNovelCreation({repository,modelRunning:unusedModel});
  const project=await creation.create('往返合同');
  await creation.setChapterContract(project.id,{...input,mustHappen:['发现来信\n读出邮戳'],visibleFacts:['只知道邮戳\r\n尚未见过寄信人']},status,(await repository.workspaceStatus(project.id)).cas);
  await creation.setUpcomingArc(project.id,['渡口','来信'],(await repository.workspaceStatus(project.id)).cas);
  if (status === 'confirmed') await repository.commitProject(project.id, (await repository.workspaceStatus(project.id)).cas, 'prepare-count', 'branch_settings_change', p => ({ ...p, branchSettings: { ...p.branchSettings, suggestedChapterCount: 3 } }));
  const original=await repository.loadProject(project.id);
  const saved=await repository.nativeBackupSnapshot(project.id);
  const native=createFileNovelRepository(createMemoryFileStore());
  const backup={projectId:project.id,files:saved.files,manifest:{...saved.metadata,format:'amber.novel.native-backup' as const,version:1 as const,checksumAlgorithm:'fnv1a32' as const,entries:[]}};
  await native.installNativeBackup(backup,await native.inspectNativeRestore(backup));
  assert.deepEqual((await native.loadProject(project.id)).branchSettings,original.branchSettings);
  const files=buildNovelWorkspacePublicFiles(await repository.publicExportPlan(project.id));
  const text=(path:string)=>new TextDecoder().decode(files.find(file=>file.path===path)!.bytes);
  const plan=buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(text('manifest.yaml'),text('project.md')),files);
  const publicRepo=createFileNovelRepository(createMemoryFileStore());
  await publicRepo.installWorkspacePlan(plan);
  assert.deepEqual((await publicRepo.loadProject(project.id)).branchSettings,original.branchSettings);
  const current=await publicRepo.workspaceStatus(project.id);
  if(status==='draft')await assert.rejects(publicRepo.startGhostwriteJob(project.id,current.cas,'job','plan',1,50),/本章计划/);
  else {
   const job=await publicRepo.startGhostwriteJob(project.id,current.cas,'job','plan',1,50);
   assert.match(job.frozenPlan!.content,/Status: confirmed/);assert.match(job.frozenPlan!.content,/可见事实/);
   await publicRepo.cancelGhostwriteJob(project.id,job.jobId,null,51);
  }
  await publicRepo.commitProject(project.id,(await publicRepo.workspaceStatus(project.id)).cas,'legacy-edit','branch_settings_change',p=>({...p,branchSettings:{...p.branchSettings,thisChapterPlan:'作者手写新版',futurePlan:'新的方向'}}));
  const changed=await publicRepo.loadProject(project.id);
  assert.equal(changed.branchSettings.chapterContract,undefined);assert.equal(changed.branchSettings.upcomingArc,undefined);assert.equal(changed.branchSettings.suggestedChapterCount,undefined);
  assert.match(buildNovelContext(changed,'write','whole_chapter').sections.find(s=>s.key==='chapter_plan')!.text,/作者手写新版/);
 }
});

test('readonly actual model proposal awaits author confirmation; cancel stops provider without committing',async()=>{
 const repository=createFileNovelRepository(createMemoryFileStore());let request:NovelModelRequest|undefined;let cancelled='';
 const model:NovelModelRunning={async validate(){},start(req){request=req;return {subscribe(callback){const timer=setTimeout(()=>{
  callback({kind:'snapshot',messages:[makeAssistantMessage(JSON.stringify(input))],generationActive:false,textDeltasLive:false,transport:'live'});callback({kind:'completed'});
 },5);return ()=>clearTimeout(timer);}};},cancel(id){cancelled=id;}};
 const creation=createNovelCreation({repository,modelRunning:model});const project=await creation.create('模型合同');
 const initial=(await repository.workspaceStatus(project.id)).cas;
 const proposal=await creation.proposeChapterContract(project.id,'暂不公开真凶',initial);
 assert.equal(request!.toolProfile,'none');assert.equal(proposal.contract.status,'draft');assert.deepEqual((await repository.workspaceStatus(project.id)).cas,initial);
 assert.equal((await repository.loadProject(project.id)).branchSettings.chapterContract,undefined);
 await creation.setChapterContract(project.id,proposal.contract,'confirmed',proposal.expectedCas);
 assert.match((await repository.loadProject(project.id)).branchSettings.thisChapterPlan,/Status: confirmed/);
 await assert.rejects(creation.setUpcomingArc(project.id,['过期'],initial),/工作区已变化/);
 const control=new NovelAuditController();const waiting=creation.proposeChapterContract(project.id,'',undefined,control);
 await new Promise(resolve=>setTimeout(resolve,1));control.cancel();await assert.rejects(waiting,/已取消/);assert.equal(cancelled,request!.runId);
});

test('targeted actual regenerate freezes chapter source and CAS, permits fact fixes, leaves old chapter until collection',async()=>{
 const repository=createFileNovelRepository(createMemoryFileStore());const requests:NovelModelRequest[]=[];let unavailable=false;
 const model:NovelModelRunning={async validate(){if(unavailable)throw new Error('模型不可用');},start(request){requests.push(request);return {subscribe(callback){const timer=setTimeout(()=>{void(async()=>{
  if(request.operation.kind!=='turn')return;
  const messages=request.history.concat([makeUserMessage(request.operation.userPrompt),makeAssistantMessage('来信其实来自母亲')]);
  await request.checkpoint(messages);callback({kind:'snapshot',messages,generationActive:false,textDeltasLive:false,transport:'live'});callback({kind:'completed'});
 })();},0);return ()=>clearTimeout(timer);}};},cancel(){}};
 const creation=createNovelCreation({repository,modelRunning:model});const project=await creation.create('事实改写');
 const chapter=await creation.saveChapter(project.id,null,'第三章','来信来自父亲');
 const cas=(await repository.workspaceStatus(project.id)).cas;
 unavailable=true;await assert.rejects(creation.reviseChapter(project.id,chapter.id,'母亲才是寄信人',cas),/模型不可用/);
 assert.equal((await repository.loadProject(project.id)).chapters[0].content,'来信来自父亲');unavailable=false;
 const run=await creation.reviseChapter(project.id,chapter.id,'母亲才是寄信人',cas);
 await new Promise<void>((resolve,reject)=>run.subscribe(event=>{if(event.kind==='completed')resolve();else if(event.kind==='failed')reject(new Error(event.message));}));
 const request=requests[0];assert.match(request.systemPrompt,/允许改变情节/);assert.equal(request.toolProfile,'none');
 if(request.operation.kind==='turn'){assert.match(request.operation.userPrompt,/母亲才是寄信人/);assert.match(request.operation.userPrompt,/来信来自父亲/);}
 const result=await repository.loadProject(project.id);assert.equal(result.chapters[0].content,'来信来自父亲');
 const candidate=result.messages.filter(message=>message.role==='assistant').at(-1)!;
 assert.equal(candidate.candidate!.sourceChapterId,chapter.id);assert.equal(candidate.candidate!.kind,'regenerate');assert.equal(candidate.candidate!.branchId,cas.branchId);
 await assert.rejects(creation.regenerateChapter(project.id,chapter.id,'过期改稿',cas),/工作区已变化/);assert.equal(requests.length,1);
});

test('confirmed contract freezes through real writer and joint reviewer, then atomic adoption consumes it',async()=>{
 const repository=createFileNovelRepository(createMemoryFileStore());const requests:NovelModelRequest[]=[];
 const model:NovelModelRunning={async validate(){},start(request){requests.push(request);return{subscribe(callback){const timer=setTimeout(()=>{
  let response:string;
  if(request.systemPrompt.includes('小说正文写作者')) response=JSON.stringify({title:'发现',content:'他找到信封，尚不知凶手，陌生电话响起。'});
  else {
   const data=JSON.parse(request.operation.kind==='turn'?request.operation.userPrompt:'{}');
   response=JSON.stringify({candidateId:data.candidateId,candidateDigest:data.candidateDigest,planId:data.planId,planDigest:data.planDigest,
    findings:[],blocking:false,rewriteRequired:false,rewriteInstructions:'',nextPlan:null,stateDelta:{plotState:'找到来信',chapterHighlight:'电话'}});
  }
  callback({kind:'snapshot',messages:[makeAssistantMessage(response)],generationActive:false,textDeltasLive:false,transport:'live'});callback({kind:'completed'});
 },0);return ()=>clearTimeout(timer);}};},cancel(){}};
 const creation=createNovelCreation({repository,modelRunning:model});const project=await creation.create('合同实际代笔');
 await creation.setChapterContract(project.id,input,'draft');await assert.rejects(creation.startGhostwrite(project.id,1),/本章计划/);
 await creation.setChapterContract(project.id,input,'confirmed');
 await repository.commitProject(project.id,(await repository.workspaceStatus(project.id)).cas,'prepare-explicit-count','branch_settings_change',p=>({...p,branchSettings:{...p.branchSettings,suggestedChapterCount:1}}));
 await creation.setUpcomingArc(project.id,['第六章返航']);
 const plan=(await repository.loadProject(project.id)).branchSettings.thisChapterPlan;
 const started=await creation.startGhostwrite(project.id,1);
 for(let i=0;i<100;i++){if((await repository.loadGhostwriteJob(project.id,started.jobId)).stage==='completed')break;await new Promise(resolve=>setTimeout(resolve,2));}
 const completed=await repository.loadGhostwriteJob(project.id,started.jobId);assert.equal(completed.stage,'completed');assert.equal(requests.length,2);
 for(const request of requests){assert.equal(request.toolProfile,'read_only');if(request.operation.kind==='turn'){assert.match(request.operation.userPrompt,/主角只知道邮戳/);assert.match(request.operation.userPrompt,/揭露真凶/);assert.match(request.operation.userPrompt,/第六章返航/);}}
 assert.equal(started.frozenPlan!.content,plan);assert.equal(completed.progress.length,1);
 const reopened=await repository.loadProject(project.id);assert.equal(reopened.chapters.length,1);assert.equal(reopened.branchSettings.chapterContract,undefined);assert.equal(reopened.branchSettings.thisChapterPlan,'');assert.equal(reopened.branchSettings.suggestedChapterCount,undefined);
});

test('new and historical forks bind inherited confirmed contracts to their own branch without mutating source',async()=>{
 const repository=createFileNovelRepository(createMemoryFileStore());const creation=createNovelCreation({repository,modelRunning:unusedModel});
 const project=await creation.create('合同分支');await creation.setChapterContract(project.id,input,'confirmed');
 const source=await repository.readWorkspaceSnapshot(project.id);const original=source.project.branchSettings.chapterContract!;
 const fork=await creation.createBranch(project.id,'新支线');const newBranch=(await repository.workspaceStatus(project.id)).activeBranchId;
 assert.equal(fork.branchSettings.chapterContract!.branchId,newBranch);
 assert.equal(fork.branchSettings.chapterContract!.contentDigest,original.contentDigest);
 await creation.switchBranch(project.id,source.status.activeBranchId);
 assert.deepEqual((await repository.loadProject(project.id)).branchSettings.chapterContract,original);
 const historical=await creation.forkFromHistory(project.id,source.status.cas.head,'历史支线');
 assert.equal(historical.branchSettings.chapterContract!.branchId,(await repository.workspaceStatus(project.id)).activeBranchId);
 assert.equal(historical.branchSettings.chapterContract!.contentDigest,original.contentDigest);
});


test('ordinary author save clears prepared batch count instead of silently retaining an old suggestion',async()=>{
 const repository=createFileNovelRepository(createMemoryFileStore());const creation=createNovelCreation({repository,modelRunning:unusedModel});
 const project=await creation.create('准备建议寿命');await creation.setChapterContract(project.id,input,'confirmed');
 await repository.commitProject(project.id,(await repository.workspaceStatus(project.id)).cas,'batch-count','branch_settings_change',p=>({...p,branchSettings:{...p.branchSettings,suggestedChapterCount:3}}));
 await creation.setChapterContract(project.id,{...input,endingHook:'作者修改结尾'},'confirmed');
 assert.equal((await repository.loadProject(project.id)).branchSettings.suggestedChapterCount,undefined);
});

test('two-chapter durable writing freezes the arc into actual planner and next writer requests',async()=>{
 const repository=createFileNovelRepository(createMemoryFileStore());const requests:NovelModelRequest[]=[];
 const model:NovelModelRunning={async validate(){},start(request){requests.push(request);return{subscribe(callback){const timer=setTimeout(()=>{
  const data=request.operation.kind==='turn'?request.operation.userPrompt:'';let response:string;
  if(request.systemPrompt.includes('小说正文写作者')) response=JSON.stringify({title:'来信',content:'邮差离开渡口。'});
  else if(request.systemPrompt.includes('下一章规划员')) response=JSON.stringify({nextPlan:'第二章追踪邮路'});
  else {const review=JSON.parse(data);response=JSON.stringify({candidateId:review.candidateId,candidateDigest:review.candidateDigest,planId:review.planId,planDigest:review.planDigest,
   findings:[],blocking:false,rewriteRequired:false,rewriteInstructions:'',nextPlan:null,stateDelta:{plotState:'出发',chapterHighlight:'离开渡口'}});}
  callback({kind:'snapshot',messages:[makeAssistantMessage(response)],generationActive:false,textDeltasLive:false,transport:'live'});callback({kind:'completed'});
 },0);return ()=>clearTimeout(timer);}};},cancel(){}};
 const creation=createNovelCreation({repository,modelRunning:model});const project=await creation.create('跨章冻结方向');
 await creation.setChapterContract(project.id,input,'confirmed');await creation.setUpcomingArc(project.id,['远港追踪','第六章返航']);
 const job=await creation.startGhostwrite(project.id,2);
 for(let i=0;i<100;i++){const current=await repository.loadGhostwriteJob(project.id,job.jobId);if(current.stage==='completed'||current.stage==='failed')break;await new Promise(resolve=>setTimeout(resolve,2));}
 const completed=await repository.loadGhostwriteJob(project.id,job.jobId);assert.equal(completed.stage,'completed');assert.equal(completed.progress.length,2);
 assert.equal(requests.length,5);assert.equal(requests.filter(request=>request.systemPrompt.includes('下一章规划员')).length,1);
 for(const request of requests)if(request.operation.kind==='turn')assert.match(request.operation.userPrompt,/第六章返航/);
 assert.deepEqual(job.frozenPlan!.upcomingArc,['远港追踪','第六章返航']);
});

test('clearing an imported contract and arc cannot resurrect consumed frontmatter on another public roundtrip',async()=>{
 const repository=createFileNovelRepository(createMemoryFileStore());const creation=createNovelCreation({repository,modelRunning:unusedModel});
 const project=await creation.create('已清除的外来合同');await creation.setChapterContract(project.id,input,'confirmed');await creation.setUpcomingArc(project.id,['返航']);
 const transfer=async(repo:ReturnType<typeof createFileNovelRepository>)=>{const files=buildNovelWorkspacePublicFiles(await repo.publicExportPlan(project.id));const text=(path:string)=>new TextDecoder().decode(files.find(file=>file.path===path)!.bytes);return buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(text('manifest.yaml'),text('project.md')),files);};
 const imported=createFileNovelRepository(createMemoryFileStore());await imported.installWorkspacePlan(await transfer(repository));
 const importedCreation=createNovelCreation({repository:imported,modelRunning:unusedModel});await importedCreation.clearChapterContract(project.id);await importedCreation.setUpcomingArc(project.id,[]);
 const again=await transfer(imported);const settings=again.branches.find(branch=>branch.id===again.activeBranchId)!.project.branchSettings;
 assert.equal(settings.chapterContract,undefined);assert.equal(settings.upcomingArc,undefined);assert.equal(settings.thisChapterPlan,'');assert.equal(settings.futurePlan,'');
});
