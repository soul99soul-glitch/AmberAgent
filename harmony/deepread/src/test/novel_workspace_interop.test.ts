import { strict as assert } from 'node:assert';
import { test } from 'node:test';
import { parseNovelWorkspaceManifest } from '../main/ets/novel/workspace_contract.ts';
import { buildNovelWorkspaceImportPlan, buildNovelWorkspacePublicFiles } from '../main/ets/novel/workspace_interop.ts';
import type { NovelWorkspaceArchiveFile } from '../main/ets/novel/workspace_exchange.ts';
const encoder = new TextEncoder();
const file = (path: string, raw: string): NovelWorkspaceArchiveFile => ({path, bytes: encoder.encode(raw)});
const md = (fields: Record<string,string>, body: string): string => `---\n${Object.entries(fields).map(([k,v]) => `${k}: ${JSON.stringify(v)}`).join('\n')}\n---\n\n${body}`;
const projectId = '02a9f2fb-0058-4a4b-95ae-5e7580e53ef3';
const ios = (): NovelWorkspaceArchiveFile[] => [
  file('manifest.yaml', `format: amber.novel.workspace\nformatVersion: 1\nmainBranch: Main\nsource:\n  projectID: ${projectId}\n`),
  file('project.md', md({id:projectId, kind:'project', title:'双线小说', polishPreference:'克制'}, '')),
  file('branches/Main/branch.md',md({id:'branch-main',title:'主线',syncStatus:'synchronized',kind:'branch',futureFlag:'retain'},'')),
  file('branches/Alt/branch.md',md({id:'branch-alt',title:'另一线',syncStatus:'needsSync',kind:'branch'},'')),
  file('branches/Main/chapters/001-开篇.md',md({id:'chapter-shared',title:'开篇',ordinal:'1',kind:'chapter',pluginFlag:'retain'},'主线正文')),
  file('branches/Alt/chapters/001-开篇.md',md({id:'chapter-shared',title:'开篇',ordinal:'1',kind:'chapter'},'支线正文')),
  file('branches/Main/discarded/删除稿.md',md({id:'chapter-discarded',title:'删除稿',kind:'chapter'},'废稿')),
  file('setting/characters/人物.md',md({id:'person-1',materialKind:'character',title:'人物',injection:'always'},'原人物').replace('\n---\n', '\naliases:\n  - 甲\n  - \"乙\"\npluginMeta:\n  id: preserve-nested\n---\n')),
  file('branches/Alt/setting/characters/人物.md',md({id:'person-1',materialKind:'character',title:'人物',override:'true'},'支线人物')),
  file('setting/writing/写作.md',md({id:'req-1',materialKind:'writingRequirements',title:'限制',injection:'off'},'不要跳戏')),
  file('setting/relationships/关系.md',md({id:'rel-1',materialKind:'relationship',title:'关系'},'朋友')),
  file('setting/custom/自定义.md',md({id:'custom-1',materialKind:'custom',title:'自定义',customName:'妖怪'},'资料')),
  file('branches/Main/plan/this-chapter.md',md({id:'plan-main',kind:'plan',status:'approved'},'## 目标\n调查')),
  file('branches/Alt/plan/this-chapter.md',md({id:'plan-alt',kind:'plan'},'不同计划')),
  file('branches/Main/plan/upcoming.md',md({id:'arc-main',kind:'plan'},'- 出发\n- 相遇')),
  file('branches/Main/plot/current.md',md({id:'state-main',kind:'plot'},'当前事实')),
  file('branches/Main/plot/outline.md',md({id:'state-main',kind:'plot',pluginPlot:'stay'},'分支大纲')),
  file('branches/Main/plot/events.md',md({id:'state-main',kind:'plot'},'- 事件')),
  file('branches/Main/plot/chapters/001-开篇.md',md({id:'chapter-shared',kind:'plot',stale:'false'},'模型语义摘要不冒充正文指针')),
  file('branches/Alt/plot/chapters/001-开篇.md',md({id:'chapter-shared',kind:'plot',stale:'true'},'支线旧摘要')),
  file('inbox/新设定.md',md({id:'proposal-1',materialKind:'custom',title:'新设定',pluginProposal:'preserve'},'待作者确认')),
  {path:'assets/unknown.bin',bytes:new Uint8Array([0,255,128,42])},
  file('setting/future-kind/资料.md',md({id:'future-1',materialKind:'newFutureKind'},'不能默认为世界观')),
];
const plan = (files: NovelWorkspaceArchiveFile[]) => buildNovelWorkspaceImportPlan(
  parseNovelWorkspaceManifest(new TextDecoder().decode(files.find(f=>f.path==='manifest.yaml')!.bytes), new TextDecoder().decode(files.find(f=>f.path==='project.md')!.bytes)), files);

test('actual iOS public dialect restores both branch domain states, overrides, plans, plot, inbox and opaque bytes',()=>{
  const imported = plan(ios());
  assert.equal(imported.manifest.projectId,projectId);
  assert.equal(imported.activeBranchId,'branch-main');
  assert.equal(imported.branches.length,2);
  const main = imported.branches.find(b=>b.id==='branch-main')!;
  const alt = imported.branches.find(b=>b.id==='branch-alt')!;
  assert.equal(main.project.name,'双线小说');
  assert.equal(main.project.chapters[0].id,'chapter-shared');
  assert.equal(main.project.chapters[0].content,'主线正文');
  assert.equal(alt.project.chapters[0].content,'支线正文');
  assert.equal(main.project.chapters[1].discarded,true);
  assert.equal(main.project.materials.find(m=>m.id==='person-1')!.content,'原人物');
  assert.equal(alt.project.materials.find(m=>m.id==='person-1')!.content,'支线人物');
  assert.equal(main.project.materials.find(m=>m.id==='req-1')!.kind,'requirement');
  assert.equal(main.project.materials.find(m=>m.id==='req-1')!.enabled,false);
  assert.equal(main.project.materials.find(m=>m.id==='rel-1')!.kind,'relationship');
  assert.equal(main.project.branchSettings.thisChapterPlan,'## 目标\n调查');
  assert.equal(main.project.branchSettings.futurePlan,'- 出发\n- 相遇');
  assert.equal(main.plotContent,'当前事实\n\n分支大纲\n\n- 事件');
  assert.equal(main.project.chapterPlots[0].text,'开篇\n主线正文');
  assert.equal(alt.unresolvedFromChapterOrdinal,1);
  assert.equal(alt.plotStale,true);
  assert.equal(main.project.settingProposals[0].status,'pending');
  assert.deepEqual(imported.unsupportedPaths.sort(),['assets/unknown.bin','setting/future-kind/资料.md'].sort());
});

test('public export emits iOS keys/frontmatter and roundtrips branch snapshots and unknown bytes',()=>{
  const imported=plan(ios());
  const exported=buildNovelWorkspacePublicFiles(imported);
  const raw=new TextDecoder().decode(exported.find(f=>f.path==='manifest.yaml')!.bytes);
  assert.match(raw,/formatVersion: 1/); assert.match(raw,/source:\n  projectID:/);assert.match(raw,/mainBranch: "Main"/);
  const next=plan(exported);
  assert.equal(next.branches.find(b=>b.id==='branch-alt')!.project.chapters[0].content,'支线正文');
  assert.equal(next.branches.find(b=>b.id==='branch-main')!.plotContent,imported.branches[0].plotContent);
  assert.equal(next.branches.find(b=>b.id==='branch-alt')!.project.materials.find(m=>m.id==='person-1')!.content,'支线人物');
  assert.deepEqual(exported.find(f=>f.path==='assets/unknown.bin')!.bytes,new Uint8Array([0,255,128,42]));
  assert.match(new TextDecoder().decode(exported.find(f=>f.path==='branches/Main/chapters/001-开篇.md')!.bytes),/pluginFlag: "retain"/);
  assert.match(new TextDecoder().decode(exported.find(f=>f.path==='inbox/proposal-1.md')!.bytes),/pluginProposal: "preserve"/);
  assert.equal(next.branches[0].project.materials.find(m=>m.id==='req-1')!.enabled,false);
  assert.match(new TextDecoder().decode(exported.find(f=>f.path==='setting/characters/person-1.md')!.bytes),/aliases:\n  - \"甲\"\n  - \"乙\"/);
  assert.match(new TextDecoder().decode(exported.find(f=>f.path==='setting/characters/person-1.md')!.bytes),/pluginMeta:\n  id: preserve-nested/);
  assert.match(new TextDecoder().decode(exported.find(f=>f.path==='branches/Main/plot/outline.md')!.bytes),/pluginPlot: \"stay\"/);
});

test('old Harmony manifests and branch-local domain restore without frontmatter',()=>{
  const files=[file('manifest.yaml','format: amber.novel.workspace\nversion: 1\nproject_id: old\ntitle: 旧小说\nactive_branch: main\n'),file('project.md','# 旧小说\n'),
    file('branches/main/chapters/004-空缺序号.md','原始正文'),file('branches/main/setting/character/npc.md','NPC'),
    file('branches/main/plan/plot.md','作者剧情'),file('branches/main/plan/this-chapter.md','本章'),file('branches/main/plan/future.md','后续'),
    file('branches/main/setting/catalog.json','{"foreshadows":[],"confirmedDecisions":[]}')];
  const imported=plan(files);const branch=imported.branches[0];
  assert.equal(branch.project.chapters[0].ordinal,4);assert.equal(branch.project.chapters[0].id,'chapter-004');assert.equal(branch.project.chapters[0].content,'原始正文');
  assert.equal(branch.project.materials[0].id,'npc');assert.equal(branch.plotContent,'作者剧情');
  assert.equal(branch.project.branchSettings.futurePlan,'后续');
});

test('reject identity conflicts, duplicate IDs/ordinals, missing branches and unsafe paths before installation',()=>{
  let files=ios();files.push(file('branches/Main/chapters/002-另章.md',md({id:'chapter-shared',ordinal:'2'},'重复')));assert.throws(()=>plan(files),/重复/);
  files=ios();files.push(file('branches/Main/chapters/001-冲突.md',md({id:'another',ordinal:'1'},'冲突')));assert.throws(()=>plan(files),/重复/);
  files=ios();files[1]=file('project.md',md({id:'different',title:'错'},''));assert.throws(()=>plan(files),/冲突/);
  files=ios();files.push(file('../outside','x'));assert.throws(()=>plan(files),/路径/);
  assert.throws(()=>parseNovelWorkspaceManifest('format: amber.novel.workspace\nformatVersion: 1\nversion: 2\nsource:\n  projectID: p\nmainBranch: main\n'),/冲突/);
});


test('UUID chapter IDs retain explicit ordinal seven across public roundtrip and raw plans remain exact',()=>{
  const files=ios();
  const uuid='626c6568-8313-49fb-a382-6eef80a65d9b';
  const index=files.findIndex(f=>f.path==='branches/Main/chapters/001-开篇.md');
  files[index]=file('branches/Main/chapters/007-开篇.md',md({id:uuid,title:'开篇',ordinal:'7',kind:'chapter'},'第七章正文'));
  files.splice(files.findIndex(f=>f.path==='branches/Main/plot/chapters/001-开篇.md'),1);
  const imported=plan(files);
  const main=imported.branches.find(b=>b.id==='branch-main')!;
  main.project.branchSettings.thisChapterPlan='作者原始Markdown\n无结构章节标题';
  const exported=buildNovelWorkspacePublicFiles(imported);
  assert.ok(exported.some(f=>f.path==='branches/Main/chapters/007-开篇.md'));
  const raw=new TextDecoder().decode(exported.find(f=>f.path==='branches/Main/plan/this-chapter.md')!.bytes);
  assert.match(raw,/## 目标与冲突/);
  const next=plan(exported).branches.find(b=>b.id==='branch-main')!;
  assert.equal(next.project.chapters[0].id,uuid);assert.equal(next.project.chapters[0].ordinal,7);
  assert.equal(next.project.branchSettings.thisChapterPlan,main.project.branchSettings.thisChapterPlan);
});


test('branch-local material deletion and inbox cannot reappear from main globals during public roundtrip',()=>{
  const imported=plan(ios());const alt=imported.branches.find(b=>b.id==='branch-alt')!;
  alt.project.materials=alt.project.materials.filter(m=>m.id!=='person-1');
  alt.project.hiddenMaterialIds=['person-1'];
  alt.project.materialOverrides=alt.project.materialOverrides?.filter(m=>m.id!=='person-1');
  alt.project.settingProposals=[];
  const next=plan(buildNovelWorkspacePublicFiles(imported));const restored=next.branches.find(b=>b.id==='branch-alt')!;
  assert.equal(restored.project.materials.some(m=>m.id==='person-1'),false);
  assert.equal(restored.project.settingProposals.length,0);
  assert.equal(next.branches.find(b=>b.id==='branch-main')!.project.settingProposals.length,1);
});

test('empty declared old Harmony workspace remains a valid empty project',()=>{
  const files=[file('manifest.yaml','format: amber.novel.workspace\nversion: 1\nproject_id: empty\ntitle: 空项目\nactive_branch: main\n'),file('project.md','# 空项目')];
  const imported=plan(files);assert.equal(imported.branches.length,1);assert.equal(imported.branches[0].project.chapters.length,0);
});

test('catalog rejects malformed actual domain entries before project installation',()=>{
  for (const bad of ['{"foreshadows":[null],"confirmedDecisions":[]}', '{"foreshadows":[],"confirmedDecisions":[null]}',
    '{"foreshadows":[{"id":"f","title":"x","content":"y","status":"bad","createdAt":0,"resolvedAt":null}],"confirmedDecisions":[]}']) {
    const files=ios();files.push(file('branches/Main/setting/catalog.json',bad));assert.throws(()=>plan(files),/条目无效/);
  }
});

test('source manifest provenance, smart mode and export snapshot reference time survive public export',()=>{
  const files=ios();files[0]=file('manifest.yaml',new TextDecoder().decode(files[0].bytes)+'  projectRevision: 12\n  schemaVersion: 9\nexportedAt: 2026-09-30T00:00:00Z\npluginFlag: preserve\n');
  const char=files.findIndex(f=>f.path==='setting/characters/人物.md');
  files[char]=file(files[char].path,new TextDecoder().decode(files[char].bytes).replace('injection: "always"','injection: "smart"'));
  const imported=plan(files);const exported=buildNovelWorkspacePublicFiles(imported);
  const raw=new TextDecoder().decode(exported.find(f=>f.path==='manifest.yaml')!.bytes);
  const manifest=parseNovelWorkspaceManifest(raw,new TextDecoder().decode(exported.find(f=>f.path==='project.md')!.bytes));
  assert.equal(manifest.projectId,projectId);assert.match(raw,/projectRevision: "12"/);assert.match(raw,/schemaVersion: "9"/);
  assert.match(raw,/exportedAt: "2026-09-30T00:00:00Z"/);assert.match(raw,/pluginFlag: "preserve"/);
  assert.equal(imported.branches[0].project.createdAt,Date.parse('2026-09-30T00:00:00Z'));
  assert.match(new TextDecoder().decode(exported.find(f=>f.path==='setting/characters/person-1.md')!.bytes),/injection: "smart"/);
});

test('raw Markdown with preface and unknown ## headings remains fully inside actual iOS goal section and restores exactly',()=>{
  const imported=plan(ios());const main=imported.branches.find(b=>b.id==='branch-main')!;
  const raw='作者前言\n\n## 场景\n调查\n\n## 目标与冲突\n追踪';main.project.branchSettings.thisChapterPlan=raw;
  const exported=buildNovelWorkspacePublicFiles(imported);
  const planText=new TextDecoder().decode(exported.find(f=>f.path==='branches/Main/plan/this-chapter.md')!.bytes);
  const body=planText.slice(planText.indexOf('\n---\n')+5).trim();
  // Actual iOS sections() recognizes only unindented lines beginning "## ".
  assert.deepEqual(body.split('\n').filter(line=>line.startsWith('## ')),['## 目标与冲突']);
  assert.match(body,/作者前言/);assert.match(body,/## 场景/);assert.match(body,/追踪/);
  assert.equal(plan(exported).branches.find(b=>b.id==='branch-main')!.project.branchSettings.thisChapterPlan,raw);
  // Actual iOS importer flush -> planMarkdown -> render trims the goal/body on both crossings.
  const goal=body.slice('## 目标与冲突'.length).trim();
  const header=planText.slice(0,planText.indexOf('\n---\n')+5);
  const afterIOS=exported.map(f=>f.path==='branches/Main/plan/this-chapter.md'
    ? file(f.path,header+'\n'+('## 目标与冲突\n\n'+goal).trim()+'\n'):f);
  assert.equal(plan(afterIOS).branches.find(b=>b.id==='branch-main')!.project.branchSettings.thisChapterPlan,raw);
});

test('material metadata handling preserves same-named unknown extensions on chapter and project documents', () => {
  const files = ios();
  for (const path of ['project.md', 'branches/Main/chapters/001-开篇.md']) {
    const item = files.find(file => file.path === path)!;
    item.bytes = encoder.encode(new TextDecoder().decode(item.bytes).replace('\n---\n', '\naliases:\n  - unknown-extension\ntags:\n  - retain\n---\n'));
  }
  const exported = buildNovelWorkspacePublicFiles(plan(files));
  for (const path of ['project.md', 'branches/Main/chapters/001-开篇.md']) {
    const raw = new TextDecoder().decode(exported.find(file => file.path === path)!.bytes);
    assert.match(raw, /aliases:\n  - unknown-extension/);
    assert.match(raw, /tags:\n  - retain/);
  }
});
