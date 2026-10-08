const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const ts=require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const {canCloneCollectedMessage}=require('../../../deepread/src/main/ets/novel/collected_candidates.ts');
function methods(names){const source=fs.readFileSync(path.join(__dirname,'../main/ets/pages/NovelWorkspacePage.ets'),'utf8');return names.map(name=>{const m=new RegExp('^  (?:private )?(?:async )?'+name+'\\(','m').exec(source);assert.ok(m,name);let depth=1,end=source.indexOf('{',m.index)+1;for(;depth&&end<source.length;end++){if(source[end]==='{')depth++;if(source[end]==='}')depth--;}return source.slice(m.index,end);}).join('\n');}
function page(creation={}){const dialogs=[];const code=ts.transpileModule('class Page{'+methods(['toggleDiscussionArchive','confirmCloneCollected','cloneCollectedForCollection','refreshCollectedAnalysis','materialAnalysisFailureText','reanalyze'])+'}\nreturn Page;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;const Page=new Function('getNovelCreation','canCloneCollectedMessage','AlertDialog',code)(()=>creation,canCloneCollectedMessage,{show:dialog=>dialogs.push(dialog)});const source={id:'old',role:'assistant',mode:'write',content:'已收录正文',collectedChapterId:'c',candidate:{kind:'write',branchId:'main'}};const instance=new Page();Object.assign(instance,{projectId:'p',project:{messages:[source],discussionArchives:[{id:'a'}]},workspaceStatus:{activeBranchId:'main',cas:{branchId:'main',head:'h'}},pageAlive:true,busy:false,errorMsg:'',expandedArchiveIds:[],collectionFeedback:'',collectionFeedbackToken:1,hasBlockingNovelRun:()=>false,reload:async()=>{}});return{instance,dialogs,source};}
test('archive projection uses exact discussion IDs; prose, partial and other discussion remain visible',()=>{const source=fs.readFileSync(path.join(__dirname,'../main/ets/components/NovelArchivePresentation.ets'),'utf8');const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;const exports={};new Function('exports',code)(exports);const archive={id:'a',sourceMessageIds:['d1','d2','p']};const msg=id=>({id,mode:id==='p'?'write':'discuss',interrupted:true});assert.equal(exports.discussionArchiveAtMessage([archive],msg('d1')),archive);assert.equal(exports.discussionArchiveAtMessage([archive],msg('d2')),null);assert.equal(exports.discussionMessageVisible([archive],[],msg('d2')),false);assert.equal(exports.discussionMessageVisible([archive],['a'],msg('d2')),true);assert.equal(exports.discussionMessageVisible([archive],[],msg('p')),true);assert.equal(exports.discussionMessageVisible([archive],[],msg('d3')),true);});
test('archive expansion is local, immutable and only toggles an actual archive',()=>{const{instance}=page();const before=instance.expandedArchiveIds;instance.toggleDiscussionArchive('a');assert.notEqual(instance.expandedArchiveIds,before);assert.deepEqual(instance.expandedArchiveIds,['a']);instance.toggleDiscussionArchive('a');assert.deepEqual(instance.expandedArchiveIds,[]);instance.toggleDiscussionArchive('unknown');assert.deepEqual(instance.expandedArchiveIds,[]);});
test('clone freezes author CAS, preserves original receipt and opens the returned new candidate',async()=>{let args;const{instance,dialogs,source}=page({cloneCollectedMessage:async(...a)=>{args=a;return{id:'clone'};}});instance.reload=async()=>{instance.project.messages.push({id:'clone',collectedChapterId:null});};let opened;instance.openCollect=id=>{opened=id;};instance.confirmCloneCollected(source);instance.workspaceStatus.cas={branchId:'main',head:'later'};await dialogs[0].secondaryButton.action();await new Promise(setImmediate);assert.deepEqual(args,['p','old',{branchId:'main',head:'h'}]);assert.equal(opened,'clone');assert.equal(source.collectedChapterId,'c');assert.equal(instance.busy,false);});
test('clone confirmation rejects another branch and write failure stays visible without opening collection',async()=>{let writes=0;const{instance,dialogs,source}=page({cloneCollectedMessage:async()=>{writes++;throw Error('工作区变化');}});instance.openCollect=()=>assert.fail('must not open');instance.confirmCloneCollected(source);instance.workspaceStatus.activeBranchId='other';await dialogs[0].secondaryButton.action();assert.equal(writes,0);instance.workspaceStatus.activeBranchId='main';instance.confirmCloneCollected(source);await dialogs[1].secondaryButton.action();assert.equal(writes,1);assert.match(instance.errorMsg,/变化/);});
test('collection feedback separates persisted manuscript success from analysis warnings and ignores old owner',async()=>{const{instance}=page();await instance.refreshCollectedAnalysis('p','main',1,{count:2,warning:null,suggestions:[]});assert.match(instance.collectionFeedback,/2/);await instance.refreshCollectedAnalysis('p','main',1,{count:0,warning:'分析失败',suggestions:[]});assert.match(instance.collectionFeedback,/正文已收录/);assert.match(instance.collectionFeedback,/分析失败/);instance.collectionFeedback='new';await instance.refreshCollectedAnalysis('p','other',1,{count:9,warning:null});assert.equal(instance.collectionFeedback,'new');await instance.refreshCollectedAnalysis('p','main',0,{count:9,warning:null});assert.equal(instance.collectionFeedback,'new');});

test('actual reload failure after clone does not open an empty collection sheet from the old project',async()=>{const source=methods(['cloneCollectedForCollection','reload']);const code=ts.transpileModule('class Page{'+source+'}\nreturn Page;',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;const Page=new Function('getNovelCreation',code)(()=>({cloneCollectedMessage:async()=>({id:'new-clone'}),open:async()=>{throw Error('实际读取失败');}}));const instance=new Page();Object.assign(instance,{projectId:'p',project:{messages:[{id:'original',collectedChapterId:'c'}]},workspaceStatus:{activeBranchId:'main'},pageAlive:true,pageHiddenOnce:false,busy:false,hasBlockingNovelRun:()=>false,reloadToken:0,errorMsg:'',openCollect:()=>assert.fail('old snapshot must not open collection')});await instance.cloneCollectedForCollection('original',{branchId:'main',head:'h'});assert.match(instance.errorMsg,/实际读取失败/);assert.equal(instance.project.messages[0].id,'original');assert.equal(instance.busy,false);});


test('collection analysis failure gives a quiet retry path and stored format diagnostics read naturally', async () => {
  const { instance } = page();
  await instance.refreshCollectedAnalysis('p', 'main', 1, null);
  assert.match(instance.collectionFeedback, /正文已收录/);
  assert.match(instance.collectionFeedback, /设定.*重新分析/);
  assert.doesNotMatch(instance.collectionFeedback, /JSON|suggestions|NovelError|分析完成/);
  assert.equal(instance.materialAnalysisFailureText('NovelError: 资料建议解析失败：JSON 格式无效'),
    '模型返回的资料建议格式不完整，请重新分析此章。');
  assert.equal(instance.materialAnalysisFailureText('Error: 模型服务连接失败'), '模型服务连接失败');
});


test('manual reanalysis clears prior collection failure and invalidates its late feedback', async () => {
  const { instance } = page({ refreshMaterialSuggestions: async () => ({ count: 1, suggestions: [], warning: null }) });
  instance.project.chapters = [{ id: 'chapter' }];
  instance.collectionFeedback = '正文已收录。资料分析未完成';
  instance.errorMsg = '旧格式错误';
  const oldToken = instance.collectionFeedbackToken;
  await instance.reanalyze('chapter');
  assert.equal(instance.errorMsg, '');
  assert.equal(instance.collectionFeedback, '');
  assert.equal(instance.busy, false);
  await instance.refreshCollectedAnalysis('p', 'main', oldToken, null);
  assert.equal(instance.collectionFeedback, '');
});
