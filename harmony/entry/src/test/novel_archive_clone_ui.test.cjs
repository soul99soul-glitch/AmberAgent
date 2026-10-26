const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const ts=require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const {makeNovelMessage}=require('../../../deepread/src/main/ets/novel/models.ts');
function methods(names,filename='../main/ets/platform_impl/novel/NovelDiscussionSession.ets'){const source=fs.readFileSync(path.join(__dirname,filename),'utf8');return names.map(name=>{const m=new RegExp('^  (?:private )?(?:async )?'+name+'\\(','m').exec(source);assert.ok(m,name);let depth=1,end=source.indexOf('{',m.index)+1;for(;depth&&end<source.length;end++){if(source[end]==='{')depth++;if(source[end]==='}')depth--;}return source.slice(m.index,end);}).join('\n');}
const { loadSession } = require('./novel_workspace_sessions_fixture.cjs');
function page(creation = {}) {
  const dialogs = [];
  const source = makeNovelMessage({ id: 'old', role: 'assistant', mode: 'write', content: '已收录正文',
    createdAt: 1, collectedChapterId: 'c', candidate: { kind: 'write', branchId: 'main' } });
  const state = { projectId: 'p', project: { messages: [source], discussionArchives: [{ id: 'a' }], chapters: [] },
    workspaceStatus: { activeBranchId: 'main', cas: { branchId: 'main', head: 'h' } },
    pageAlive: true, pageHiddenOnce: false, busy: false, errorMsg: '',
    hasBlockingNovelRun: () => false, reload: async () => {} };
  const instance = loadSession('NovelManuscriptSession', state, creation, dialogs);
  instance.collectionFeedbackToken = 1;
  return { instance, state, dialogs, source };
}
test('archive projection uses exact discussion IDs; prose, partial and other discussion remain visible',()=>{const source=fs.readFileSync(path.join(__dirname,'../main/ets/components/NovelArchivePresentation.ets'),'utf8');const code=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;const exports={};new Function('exports',code)(exports);const archive={id:'a',sourceMessageIds:['d1','d2','p']};const msg=id=>({id,mode:id==='p'?'write':'discuss',interrupted:true});assert.equal(exports.discussionArchiveAtMessage([archive],msg('d1')),archive);assert.equal(exports.discussionArchiveAtMessage([archive],msg('d2')),null);assert.equal(exports.discussionMessageVisible([archive],[],msg('d2')),false);assert.equal(exports.discussionMessageVisible([archive],['a'],msg('d2')),true);assert.equal(exports.discussionMessageVisible([archive],[],msg('p')),true);assert.equal(exports.discussionMessageVisible([archive],[],msg('d3')),true);});
test('archive expansion is local, immutable and only toggles an actual archive',()=>{const code=ts.transpileModule('class Discussion{'+methods(['toggleDiscussionArchive'])+'} return new Discussion();',{compilerOptions:{target:ts.ScriptTarget.ES2022}}).outputText;const instance=new Function(code)();instance.project={discussionArchives:[{id:'a'}]};instance.expandedArchiveIds=[];const before=instance.expandedArchiveIds;instance.toggleDiscussionArchive('a');assert.notEqual(instance.expandedArchiveIds,before);assert.deepEqual(instance.expandedArchiveIds,['a']);instance.toggleDiscussionArchive('a');assert.deepEqual(instance.expandedArchiveIds,[]);instance.toggleDiscussionArchive('unknown');assert.deepEqual(instance.expandedArchiveIds,[]);});
test('clone freezes author CAS, preserves original receipt and opens the returned new candidate',async()=>{let args;const{instance,state,dialogs,source}=page({cloneCollectedMessage:async(...a)=>{args=a;return{id:'clone'};}});state.reload=async()=>{state.project.messages.push({id:'clone',collectedChapterId:null});};let opened;instance.openCollect=id=>{opened=id;};instance.confirmCloneCollected(source);state.workspaceStatus.cas={branchId:'main',head:'later'};await dialogs[0].secondaryButton.action();await new Promise(setImmediate);assert.deepEqual(args,['p','old',{branchId:'main',head:'h'}]);assert.equal(opened,'clone');assert.equal(source.collectedChapterId,'c');assert.equal(state.busy,false);});
test('clone confirmation rejects another branch and write failure stays visible without opening collection',async()=>{let writes=0;const{instance,state,dialogs,source}=page({cloneCollectedMessage:async()=>{writes++;throw Error('工作区变化');}});instance.openCollect=()=>assert.fail('must not open');instance.confirmCloneCollected(source);state.workspaceStatus.activeBranchId='other';await dialogs[0].secondaryButton.action();assert.equal(writes,0);state.workspaceStatus.activeBranchId='main';instance.confirmCloneCollected(source);await dialogs[1].secondaryButton.action();assert.equal(writes,1);assert.match(state.errorMsg,/变化/);});
test('collection feedback separates persisted manuscript success from analysis warnings and ignores old owner',async()=>{const{instance,state}=page();await instance.refreshCollectedAnalysis('p','main',1,{count:2,warning:null,suggestions:[]});assert.match(instance.collectionFeedback,/2/);await instance.refreshCollectedAnalysis('p','main',1,{count:0,warning:'分析失败',suggestions:[]});assert.match(instance.collectionFeedback,/正文已收录/);assert.match(instance.collectionFeedback,/分析失败/);instance.collectionFeedback='new';await instance.refreshCollectedAnalysis('p','other',1,{count:9,warning:null});assert.equal(instance.collectionFeedback,'new');await instance.refreshCollectedAnalysis('p','main',0,{count:9,warning:null});assert.equal(instance.collectionFeedback,'new');});

test('actual reload failure after clone does not open an empty collection sheet from the old project', async () => {
  const creation = { cloneCollectedMessage: async () => ({ id: 'new-clone' }),
    open: async () => { throw Error('实际读取失败'); } };
  const { instance, state } = page(creation);
  const code = ts.transpileModule('class Page{'+methods(['reload'], '../main/ets/pages/NovelWorkspacePage.ets')+'} return Page.prototype.reload;',
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  const reload = new Function('getNovelCreation', code)(() => creation);
  state.reloadToken = 0;
  state.reload = () => reload.call(state);
  instance.openCollect = () => assert.fail('old snapshot must not open collection');
  await instance.cloneCollectedForCollection('old', { branchId: 'main', head: 'h' });
  assert.match(state.errorMsg, /实际读取失败/);
  assert.equal(state.project.messages[0].id, 'old');
  assert.equal(state.busy, false);
});

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
  const { instance, state } = page({ refreshMaterialSuggestions: async () => ({ count: 1, suggestions: [], warning: null }) });
  state.project.chapters = [{ id: 'chapter' }];
  instance.collectionFeedback = '正文已收录。资料分析未完成';
  state.errorMsg = '旧格式错误';
  const oldToken = instance.collectionFeedbackToken;
  await instance.reanalyze('chapter');
  assert.equal(state.errorMsg, '');
  assert.equal(instance.collectionFeedback, '');
  assert.equal(state.busy, false);
  await instance.refreshCollectedAnalysis('p', 'main', oldToken, null);
  assert.equal(instance.collectionFeedback, '');
});

test('collection commits the final IME text before persisting the selected manuscript target', async () => {
  let collected;
  const { instance, state } = page({ collectMessage: async (...args) => {
    collected = args;
    return { analysisFinished: Promise.resolve({ count: 0, warning: null }) };
  } });
  instance.openCollect('old');
  instance.collectDraftController.stopEditing = () => {
    setTimeout(() => { instance.collectDraft = '输入法最终正文'; }, 0);
  };
  instance.collectTitleController.stopEditing = () => {
    setTimeout(() => { instance.collectTitle = '输入法最终标题'; }, 0);
  };
  const collectedCallbacks = [];
  state.onCollected = (...args) => collectedCallbacks.push(args);
  await instance.submitCollect();
  assert.deepEqual(collected, ['p', 'old', { kind: 'new_chapter', title: '输入法最终标题' }, '输入法最终正文']);
  assert.equal(instance.collectMessageId, '');
  assert.equal(state.busy, false);
  assert.deepEqual(collectedCallbacks, [['p', 'main', 1]]);
});

test('collection discard sees deferred IME changes and keeps the draft until the user discards it', async () => {
  const { instance, dialogs } = page();
  instance.openCollect('old');
  instance.collectDraftController.stopEditing = () => {
    setTimeout(() => { instance.collectDraft = '最后才提交的改动'; }, 0);
  };
  instance.confirmDiscardCollect();
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(instance.collectMessageId, 'old');
  assert.equal(dialogs.length, 1);
  assert.equal(dialogs[0].title, '放弃收录编辑？');
  dialogs[0].secondaryButton.action();
  assert.equal(instance.collectMessageId, '');
});

test('chapter discard sees deferred IME changes and does not close a dirty editor', async () => {
  const { instance, dialogs } = page();
  instance.openNewChapter();
  instance.chapterContentController.stopEditing = () => {
    setTimeout(() => { instance.chapterContent = '输入法最后一段'; }, 0);
  };
  instance.confirmDiscardChapter();
  await new Promise(resolve => setTimeout(resolve, 5));
  assert.equal(instance.chapterEditorOpen, true);
  assert.equal(dialogs.length, 1);
  assert.equal(dialogs[0].title, '放弃编辑？');
  dialogs[0].primaryButton.action();
  assert.equal(instance.chapterEditorOpen, true);
});
