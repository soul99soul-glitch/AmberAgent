import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelChapter, makeNovelMaterial } from '../main/ets/novel/models.ts';
import { buildNovelWorkspaceImportPlan, buildNovelWorkspacePublicFiles } from '../main/ets/novel/workspace_interop.ts';
import type { NovelNativeBackupImport } from '../main/ets/novel/native_backup.ts';
const root='amberagent/novel-workspace/p';
const backup='amberagent/novel-workspace/.trash/p';
const setup=async()=>{
  const fs=createMemoryFileStore(); const repo=createFileNovelRepository(fs);
  await repo.createProject({...makeNovelProject({id:'p',name:'小说',now:1000}), chapters:[makeNovelChapter({id:'c',title:'开端',content:'原文。',now:1000})],materials:[makeNovelMaterial({id:'m',kind:'world',title:'世界',content:'第一世界',now:1000})]});
  return {fs,repo};
};
const native=async(repo:ReturnType<typeof createFileNovelRepository>):Promise<NovelNativeBackupImport>=>{
  const snapshot=await repo.nativeBackupSnapshot('p');
  return {projectId:'p',files:snapshot.files,manifest:{...snapshot.metadata,format:'amber.novel.native-backup',version:1,checksumAlgorithm:'fnv1a32',entries:[]}};
};
test('bad live inventory preserves valid trash and confirmed recovery retains corrupt bytes',async()=>{
  const {fs,repo}=await setup();const saved=await repo.nativeBackupSnapshot('p');
  for(const file of saved.files) await fs.writeBytes(backup+'/'+file.path,file.bytes);
  await fs.writeText(root+'/branches/main/chapters/001-开端.md','坏正文');
  const inventory=await repo.listProjectInventory();
  assert.equal(inventory.projects.length,0);assert.equal(inventory.failures[0].id,'p');
  assert.equal(await fs.exists(backup),true);
  const preview=await repo.inspectProjectRecovery('p');assert.equal(preview?.source,'trash');
  const restored=await repo.restoreProjectRecovery(preview!);assert.equal(restored.chapters[0].content,'原文。');
  const retained=await fs.list('amberagent/novel-workspace/.retained/p');
  assert.ok(retained.length>=2);
  assert.equal(await fs.readText('amberagent/novel-workspace/.retained/p/'+retained[0]+'/branches/main/chapters/001-开端.md'),'坏正文');
});
test('recovery preview rejects changed source without replacing live',async()=>{
  const {fs,repo}=await setup();await fs.writeText(root+'/branches/main/chapters/001-开端.md','坏正文');
  const preview=await repo.inspectProjectRecovery('p');assert.equal(preview?.source,'head_snapshot');
  await fs.writeText(root+'/unknown.bin','new');
  await assert.rejects(repo.restoreProjectRecovery(preview!),/重新预览/);
  assert.equal(await fs.readText(root+'/branches/main/chapters/001-开端.md'),'坏正文');
});
test('head snapshot recovery restores damaged chapter and retains original',async()=>{
  const {fs,repo}=await setup();await fs.writeText(root+'/branches/main/chapters/001-开端.md','坏正文');
  const preview=await repo.inspectProjectRecovery('p');const restored=await repo.restoreProjectRecovery(preview!);
  assert.equal(restored.chapters[0].content,'原文。');assert.equal((await repo.listProjects()).length,1);
});
test('native restore preserves complete private tree, branches, undo and opaque bytes',async()=>{
  const {fs,repo}=await setup();let cas=(await repo.workspaceStatus('p')).cas;
  await repo.commitProject('p',cas,'edit','manual_edit',p=>({...p,chapters:p.chapters.map(c=>({...c,content:'编辑正文'}))}));
  cas=(await repo.workspaceStatus('p')).cas;
  await repo.createBranch('p','支线',cas,'fork');
  await fs.writeBytes(root+'/opaque.bin',new Uint8Array([255,0,200,31]));
  const input=await native(repo);const fs2=createMemoryFileStore();const repo2=createFileNovelRepository(fs2);
  const preview=await repo2.inspectNativeRestore(input);assert.equal(preview.replaceExisting,false);
  await repo2.installNativeBackup(input,preview);
  const second=await repo2.nativeBackupSnapshot('p');assert.deepEqual(second.files,input.files);
  assert.equal((await repo2.workspaceStatus('p')).canUndo,false);
  const main=(await repo2.workspaceStatus('p')).branches.find(b=>b.isMain)!;
  await repo2.switchBranch('p',main.id,(await repo2.workspaceStatus('p')).cas);
  assert.equal((await repo2.workspaceStatus('p')).canUndo,true);
});
test('native confirmation rejects target changes and bad inactive branch without writes',async()=>{
  const {fs,repo}=await setup();const input=await native(repo);const preview=await repo.inspectNativeRestore(input);
  await fs.writeBytes(root+'/opaque.bin',new Uint8Array([1]));
  await assert.rejects(repo.installNativeBackup(input,preview),/重新预览/);
  assert.deepEqual(await fs.readBytes(root+'/opaque.bin'),new Uint8Array([1]));
  await repo.createBranch('p','支线',(await repo.workspaceStatus('p')).cas,'fork');
  const branched=await native(repo);const broken={...branched,files:branched.files.filter(f=>f.path!=='.amber/branches/main.json')};
  await assert.rejects(repo.inspectNativeRestore(broken),/分支快照缺失/);
});
test('public plan restores two independent branches, plans, author plot and material override',async()=>{
  const {repo}=await setup();let cas=(await repo.workspaceStatus('p')).cas;
  await repo.commitProject('p',cas,'settings','branch_settings_change',p=>({...p,branchSettings:{...p.branchSettings,thisChapterPlan:'主线计划'}}));
  const main=(await repo.workspaceStatus('p')).activeBranchId;
  await repo.createBranch('p','支线',(await repo.workspaceStatus('p')).cas,'fork');
  await repo.commitProject('p',(await repo.workspaceStatus('p')).cas,'branch-edit','material_edit',p=>({...p,chapters:p.chapters.map(c=>({...c,ordinal:7,content:'支线正文'})),materials:p.materials.map(m=>({...m,content:'支线世界'})),branchSettings:{...p.branchSettings,thisChapterPlan:'支线计划'}}));
  const plan=await repo.publicExportPlan('p');const publicFiles=buildNovelWorkspacePublicFiles(plan);
  const manifest=(await import('../main/ets/novel/workspace_contract.ts')).parseNovelWorkspaceManifest(new TextDecoder().decode(publicFiles.find(f=>f.path==='manifest.yaml')!.bytes));
  const imported=buildNovelWorkspaceImportPlan(manifest,publicFiles);const repo2=createFileNovelRepository(createMemoryFileStore());
  const p=await repo2.installWorkspacePlan(imported);
  assert.equal(p.chapters[0].content,'支线正文');assert.equal(p.chapters[0].ordinal,7);
  assert.equal(p.materials[0].content,'支线世界');assert.equal(p.branchSettings.thisChapterPlan,'支线计划');
  await repo2.switchBranch('p',main,(await repo2.workspaceStatus('p')).cas);
  const mainP=await repo2.loadProject('p');assert.equal(mainP.chapters[0].content,'原文。');assert.equal(mainP.materials[0].content,'第一世界');assert.equal(mainP.branchSettings.thisChapterPlan,'主线计划');
});
test('memory bytes text semantics decode Unicode and reject invalid UTF8',async()=>{
  const fs=createMemoryFileStore();await fs.writeBytes('t',new TextEncoder().encode('中文📖'));
  assert.equal(await fs.readText('t'),'中文📖');await fs.writeBytes('bad',new Uint8Array([255]));
  await assert.rejects(fs.readText('bad'),/UTF-8/);
});
test('running job blocks backup while paused job and durable proposals survive native restore',async()=>{
  const {repo}=await setup();await repo.commitProject('p',(await repo.workspaceStatus('p')).cas,'plan','branch_settings_change',p=>({...p,branchSettings:{...p.branchSettings,thisChapterPlan:'下一章计划'}}));
  await repo.createProposal('p',(await repo.workspaceStatus('p')).cas,'proposal',[{operation:'write',path:'branches/main/plan/this-chapter.md',content:'作者候选计划'}],1001);
  let job=await repo.startGhostwriteJob('p',(await repo.workspaceStatus('p')).cas,'job','plan',1,1002);
  await assert.rejects(repo.nativeBackupSnapshot('p'),/暂停/);
  job=await repo.claimGhostwriteJob('p','job','token',1003,60000);
  await repo.pauseGhostwriteJob('p','job',{token:job.claim!.token,epoch:job.claim!.epoch},1004);
  const input=await native(repo);const repo2=createFileNovelRepository(createMemoryFileStore());
  await repo2.installNativeBackup(input,await repo2.inspectNativeRestore(input));
  assert.equal((await repo2.loadGhostwriteJob('p','job')).stage,'paused');
  assert.equal((await repo2.workspaceProposals('p'))[0].proposalId,'proposal');
});
test('invalid startup trash is retained instead of promoted over missing live',async()=>{
  const {fs,repo}=await setup();await fs.rename(root,backup);await fs.writeText(backup+'/.amber/project-state.json','{bad');
  await assert.rejects(repo.loadProject('p'));assert.equal(await fs.exists(root),false);
  assert.equal(await fs.readText(backup+'/.amber/project-state.json'),'{bad');
});
test('native stage swap failure rolls original tree back byte-for-byte',async()=>{
  const {fs,repo}=await setup();const input=await native(repo);
  await repo.commitProject('p',(await repo.workspaceStatus('p')).cas,'edit-live','manual_edit',p=>({...p,chapters:p.chapters.map(c=>({...c,content:'不可丢失的现场'}))}));
  const before=await repo.nativeBackupSnapshot('p');
  let fail=true;
  const failing=new Proxy(fs,{get(target,property){
    if(property==='rename') return async(from:string,to:string)=>{
      if(fail&&from==='amberagent/novel-workspace/.staging/p'&&to===root){fail=false;throw new Error('模拟阶段提交失败');}
      return target.rename(from,to);
    };
    const value=Reflect.get(target,property);return typeof value==='function'?value.bind(target):value;
  }});
  const replacementRepo=createFileNovelRepository(failing);const preview=await replacementRepo.inspectNativeRestore(input);
  await assert.rejects(replacementRepo.installNativeBackup(input,preview),/阶段提交失败/);
  const after=await repo.nativeBackupSnapshot('p');assert.deepEqual(after.files,before.files);
  assert.equal((await repo.loadProject('p')).chapters[0].content,'不可丢失的现场');
});
test('inactive branch corruption never erases valid trash during inventory',async()=>{
  const {fs,repo}=await setup();await repo.createBranch('p','支线',(await repo.workspaceStatus('p')).cas,'fork');
  const saved=await repo.nativeBackupSnapshot('p');for(const file of saved.files)await fs.writeBytes(backup+'/'+file.path,file.bytes);
  await fs.writeText(root+'/branches/main/chapters/001-开端.md','主线损坏');
  const inventory=await repo.listProjectInventory();assert.equal(inventory.projects.length,0);
  assert.equal(inventory.failures.length,1);assert.equal(await fs.exists(backup),true);
  const preview=await repo.inspectProjectRecovery('p');assert.equal(preview?.source,'trash');
  await repo.restoreProjectRecovery(preview!);
  await repo.switchBranch('p','main',(await repo.workspaceStatus('p')).cas);
  assert.equal((await repo.loadProject('p')).chapters[0].content,'原文。');
});
test('public import preserves different discarded bodies with the same chapter ID in two branches',async()=>{
  const {repo}=await setup();await repo.createBranch('p','支线',(await repo.workspaceStatus('p')).cas,'fork');
  const plan=await repo.publicExportPlan('p');
  plan.branches=plan.branches.map((b,index)=>({...b,project:{...b.project,chapters:b.project.chapters.map(c=>({...c,discarded:true,content:'丢弃正文-'+index}))}}));
  const repo2=createFileNovelRepository(createMemoryFileStore());await repo2.installWorkspacePlan(plan);
  for(let i=0;i<plan.branches.length;i++){
    await repo2.switchBranch('p',plan.branches[i].id,(await repo2.workspaceStatus('p')).cas);
    assert.equal((await repo2.loadProject('p')).chapters[0].content,'丢弃正文-'+i);
  }
  const snapshot=await repo2.nativeBackupSnapshot('p');
  assert.equal(snapshot.files.filter(f=>f.path.startsWith('.amber/discarded/')).length,2);
});
test('inactive corruption in startup trash is retained before promotion',async()=>{
  const {fs,repo}=await setup();await repo.createBranch('p','支线',(await repo.workspaceStatus('p')).cas,'fork');
  await fs.rename(root,backup);await fs.writeText(backup+'/branches/main/chapters/001-开端.md','损坏');
  await assert.rejects(repo.loadProject('p'),/head/);
  assert.equal(await fs.exists(root),false);assert.equal(await fs.exists(backup),true);
});
test('restored private opaque bytes survive resume, private checkpoints, switch and ordinary commit',async()=>{
  const {fs,repo}=await setup();await repo.createBranch('p','支线',(await repo.workspaceStatus('p')).cas,'fork');
  await repo.commitProject('p',(await repo.workspaceStatus('p')).cas,'plan','branch_settings_change',p=>({...p,branchSettings:{...p.branchSettings,thisChapterPlan:'下一章计划'}}));
  let job=await repo.startGhostwriteJob('p',(await repo.workspaceStatus('p')).cas,'job','plan',1,1002);
  job=await repo.claimGhostwriteJob('p','job','token',1003,60000);
  await repo.pauseGhostwriteJob('p','job',{token:job.claim!.token,epoch:job.claim!.epoch},1004);
  const bytes=new Uint8Array([255,0,200,31]);await fs.writeBytes(root+'/.amber/opaque.bin',bytes);
  await fs.writeBytes(root+'/unknown.md',bytes);
  const input=await native(repo);const targetFS=createMemoryFileStore();const target=createFileNovelRepository(targetFS);
  await target.installNativeBackup(input,await target.inspectNativeRestore(input));
  await target.resumeGhostwriteJob('p','job',1005);
  job=await target.claimGhostwriteJob('p','job','token2',1006,60000);
  await target.cancelGhostwriteJob('p','job',{token:job.claim!.token,epoch:job.claim!.epoch},1007);
  await target.switchBranch('p','main',(await target.workspaceStatus('p')).cas);
  await target.commitProject('p',(await target.workspaceStatus('p')).cas,'normal','manual_edit',p=>({...p,chapters:p.chapters.map(c=>({...c,content:'恢复后编辑'}))}));
  assert.deepEqual(await targetFS.readBytes(root+'/.amber/opaque.bin'),bytes);
  assert.deepEqual(await targetFS.readBytes(root+'/unknown.md'),bytes);
});
test('confirmed native replacement can repair unreadable old jobs but refuses valid running jobs',async()=>{
  const {fs,repo}=await setup();const input=await native(repo);
  await fs.writeText(root+'/.amber/jobs.json','{损坏旧任务记录');
  const old=await fs.readBytes(root+'/.amber/jobs.json');
  await repo.installNativeBackup(input,await repo.inspectNativeRestore(input));
  assert.equal((await repo.loadProject('p')).chapters[0].content,'原文。');
  const retained=await fs.list('amberagent/novel-workspace/.retained/p');
  assert.deepEqual(await fs.readBytes('amberagent/novel-workspace/.retained/p/'+retained[0]+'/.amber/jobs.json'),old);
  await repo.commitProject('p',(await repo.workspaceStatus('p')).cas,'plan','branch_settings_change',p=>({...p,branchSettings:{...p.branchSettings,thisChapterPlan:'下一章计划'}}));
  await repo.startGhostwriteJob('p',(await repo.workspaceStatus('p')).cas,'live-job','plan',1,1001);
  const before=await fs.readBytes(root+'/.amber/jobs.json');
  await assert.rejects(repo.installNativeBackup(input,await repo.inspectNativeRestore(input)),/暂停/);
  assert.deepEqual(await fs.readBytes(root+'/.amber/jobs.json'),before);
});
test('existing real durable driver blocks native replacement even when its old job store becomes unreadable',async()=>{
  const {fs,repo}=await setup();const input=await native(repo);
  await repo.commitProject('p',(await repo.workspaceStatus('p')).cas,'plan','branch_settings_change',p=>({...p,branchSettings:{...p.branchSettings,thisChapterPlan:'下一章计划'}}));
  let subscriber:((event:import('../main/ets/novel/model_running.ts').NovelModelEvent)=>void)|null=null;
  let ready:()=>void=()=>{};const started=new Promise<void>(r=>{ready=r;});
  const model:import('../main/ets/novel/model_running.ts').NovelModelRunning={
    async validate(){},start(){return {subscribe(cb){subscriber=cb;ready();return ()=>{};}};},
    cancel(){subscriber?.({kind:'failed',message:'测试结束'});},
  };
  const creation=(await import('../main/ets/novel/creation.ts')).createNovelCreation({repository:repo,modelRunning:model,nowMs:()=>1100});
  await creation.startGhostwrite('p',1);await started;
  await fs.writeText(root+'/.amber/jobs.json','{损坏');
  const preview=await creation.inspectNativeRestore(input);
  await assert.rejects(creation.installNativeBackup(input,preview),e=>e instanceof Error&&e.message.includes('生成'));
  assert.equal(await fs.readText(root+'/.amber/jobs.json'),'{损坏');
  model.cancel('cleanup');await new Promise<void>(r=>setTimeout(r,0));
});
test('real UUID branch identities preserve immutable wire headers and source metadata through edit and switch',async()=>{
  const enc=new TextEncoder();const dec=new TextDecoder();
  const md=(fields:Record<string,string>,body:string)=>'---\n'+Object.entries(fields).map(([k,v])=>k+': '+JSON.stringify(v)).join('\n')+'\n---\n\n'+body;
  const main='80d727aa-8fea-48f0-9370-4993de5c5720',side='09a9a197-701c-4b86-8126-62cb729433b6';
  const wire=[
    {path:'manifest.yaml',bytes:enc.encode('format: amber.novel.workspace\nformatVersion: 1\nmainBranch: main\nsource:\n  projectID: p\n  platform: ios\n  wireTag: custom\n')},
    {path:'project.md',bytes:enc.encode(md({id:'p',kind:'project',title:'小说'},'作者项目说明'))},
    {path:'branches/main/branch.md',bytes:enc.encode(md({id:main,kind:'branch',title:'主线',syncStatus:'synchronized',futureFlag:'保留'},''))},
    {path:'branches/side/branch.md',bytes:enc.encode(md({id:side,kind:'branch',title:'支线',syncStatus:'synchronized'},''))},
    {path:'branches/main/chapters/001-开端.md',bytes:enc.encode(md({id:'chapter',kind:'chapter',title:'开端',ordinal:'1',pluginFlag:'保留'},'原正文'))},
    {path:'branches/side/chapters/001-开端.md',bytes:enc.encode(md({id:'chapter',kind:'chapter',title:'开端',ordinal:'1'},'支线正文'))},
    {path:'setting/world/世界.md',bytes:enc.encode(md({id:'world',kind:'material',materialKind:'world',title:'世界',injection:'smart',extensionFlag:'保留'},'世界设定'))},
    {path:'branches/main/notes/unknown.bin',bytes:new Uint8Array([255,0,2])},
  ];
  const manifest=(await import('../main/ets/novel/workspace_contract.ts')).parseNovelWorkspaceManifest(dec.decode(wire[0].bytes),dec.decode(wire[1].bytes));
  const fs=createMemoryFileStore();const repo=createFileNovelRepository(fs);await repo.installWorkspacePlan(buildNovelWorkspaceImportPlan(manifest,wire));
  await repo.commitProject('p',(await repo.workspaceStatus('p')).cas,'edit','manual_edit',p=>({...p,chapters:p.chapters.map(c=>({...c,content:'作者实际改稿'}))}));
  await repo.switchBranch('p',side,(await repo.workspaceStatus('p')).cas);await repo.switchBranch('p',main,(await repo.workspaceStatus('p')).cas);
  const exported=buildNovelWorkspacePublicFiles(await repo.publicExportPlan('p'));
  const output=(path:string)=>dec.decode(exported.find(f=>f.path===path)!.bytes);
  assert.match(output('project.md'),/作者项目说明/);
  assert.match(output('branches/main/chapters/001-开端.md'),/pluginFlag: "保留"/);
  assert.match(output('branches/main/chapters/001-开端.md'),/作者实际改稿/);
  assert.match(output('branches/main/branch.md'),/futureFlag: "保留"/);
  assert.match(output('setting/world/world.md'),/injection: "smart"/);assert.match(output('setting/world/world.md'),/extensionFlag: "保留"/);
  assert.match(output('manifest.yaml'),/platform: "ios"/);assert.match(output('manifest.yaml'),/wireTag: "custom"/);
  assert.deepEqual(exported.find(f=>f.path==='branches/main/notes/unknown.bin')!.bytes,new Uint8Array([255,0,2]));
  assert.equal(await fs.exists(root+'/.amber/public-originals/branches/main/notes/unknown.bin'),false);
  assert.equal(await fs.exists(root+'/branches/main/branch.md'),false);
  const nextManifest=(await import('../main/ets/novel/workspace_contract.ts')).parseNovelWorkspaceManifest(output('manifest.yaml'),output('project.md'));
  const next=buildNovelWorkspaceImportPlan(nextManifest,exported);assert.equal(next.branches.length,2);
  assert.equal(next.branches.find(b=>b.id===main)!.project.chapters[0].content,'作者实际改稿');
});
