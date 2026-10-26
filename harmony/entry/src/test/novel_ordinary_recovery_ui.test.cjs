const {test}=require('node:test');const assert=require('node:assert/strict');const fs=require('node:fs');const path=require('node:path');const ts=require('../../../chat/node_modules/typescript');require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const {latestAssistantText}=require('../../../deepread/src/main/ets/agent/message.ts');
const {ordinaryRunHistoryCount,ordinaryRunMessages}=require('../../../deepread/src/main/ets/novel/ordinary_run.ts');
const models = require('../../../deepread/src/main/ets/novel/models.ts');
const transcript = require('../../../deepread/src/main/ets/novel/transcript.ts');
const composer = require('../main/ets/novel/NovelComposerState.ts');

// Execute the complete owner; SDK surfaces are replaced while recovery and attach stay production code.
const discussionSource = fs.readFileSync(path.join(__dirname,
  '../main/ets/platform_impl/novel/NovelDiscussionSession.ets'), 'utf8');
const discussionCode = ts.transpileModule(discussionSource, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;

function discussionSession(creation, state, copies = []) {
  const exported = {};
  new Function('exports', 'require', 'Observed', 'Scroller', discussionCode)(exported, name => {
    if (name === '@amber/deepread-domain') return {
      ...models, ...transcript, latestAssistantText, ordinaryRunHistoryCount, ordinaryRunMessages,
    };
    if (name === '@amber/chat-domain') return {};
    if (name === '../../novel/NovelComposerState') return composer;
    if (name === '@kit.ArkUI') return { router: {}, promptAction: { showToast() {} } };
    if (name === '@kit.BasicServicesKit') return { pasteboard: {
      MIMETYPE_TEXT_PLAIN: 'text', createData: (_mime, text) => text,
      getSystemPasteboard: () => ({ setDataSync: text => copies.push(text) }),
    } };
    if (name.endsWith('/NativeTimelineFollow.ets') || name.endsWith('/NovelArchivePresentation.ets')
      || name.endsWith('/NovelSettingProposalProjection.ets')) return {};
    throw new Error('Unexpected discussion dependency: ' + name);
  }, constructor => constructor, class {});
  const session = new exported.NovelDiscussionSession();
  session.start({
    creation, projectId: () => state.projectId, currentProject: () => state.project,
    currentStatus: () => state.workspaceStatus, isBusy: () => state.busy,
    setBusy: value => { state.busy = value; }, error: value => { state.errorMsg = value; },
    operationError: (action, error) => { state.errorMsg = `${action}: ${error}`; },
    hasBlockingNovelRun: () => false, jobLocksWorkspace: () => false,
    currentGhostwriteJob: () => null, currentGhostwriteReport: () => null,
    reload: async () => {}, reloadToken: () => 0, selectedTab: () => 0,
    backgrounded: () => false, closeControl() {}, uiContext: () => ({}),
    composerDraftStore: { load: async () => ({ text: '', mode: 'whole_chapter' }), save: async () => {} },
  });
  // Native scrolling is outside the recovery contract, as in the original test fixture.
  session.beginNovelGenerationFollow = () => {};
  session.followNovelStreamingUpdate = () => {};
  return session;
}

function page(creation = {}) {
  const copies = [];
  const record = { id: 'run', branchId: 'main', mode: 'write', granularity: 'whole_chapter',
    runKind: 'regenerate', status: 'running', originalRequest: { history: [], operation: { kind: 'turn', userPrompt: '' } },
    checkpointMessages: [], transcriptPrefix: [{ id: 'prefix' }], userText: '改变结局' };
  const state = { projectId: 'p', project: { messages: [1, 2, 3], chapters: [] },
    workspaceStatus: { activeBranchId: 'main', activeBranchName: '主线' }, busy: false, errorMsg: '' };
  const instance = discussionSession(creation, state, copies);
  instance.ordinaryRecovery = { record, active: false, retryAllowed: true, resumeAllowed: false };
  return { instance, record, copies };
}
test('cold running record appears only on its branch without active driver; completed/waiting records are excluded',()=>{const{instance,record}=page();assert.equal(instance.ordinaryRecoveryRecord(),record);instance.ordinaryRecovery.active=true;assert.equal(instance.ordinaryRecoveryRecord(),null);instance.ordinaryRecovery.active=false;for(const status of ['completed','waiting_user']){record.status=status;assert.equal(instance.ordinaryRecoveryRecord(),null);}record.status='failed';instance.workspaceStatus.activeBranchId='other';assert.equal(instance.ordinaryRecoveryRecord(),null);});
test('partial preview excludes original assistant context; copy retains full real partial and refuses stale record',()=>{const{instance,record,copies}=page();const old={id:'old',role:'assistant',parts:[{type:'text',text:'原模型历史正文'}]};record.originalRequest.history=[old];record.checkpointMessages=[old];assert.equal(instance.ordinaryPartialText(record),'');const partial={id:'partial',role:'assistant',parts:[{type:'text',text:'当前未完成正文\n完整尾部'}]};record.checkpointMessages.push(partial);const text=instance.ordinaryPartialText(record);assert.equal(text,'当前未完成正文\n完整尾部');instance.copyOrdinaryPartial('run',text);assert.deepEqual(copies,[text]);instance.copyOrdinaryPartial('old-run',text);assert.equal(copies.length,1);});
test('explicit retry/resume use exact record ID and attach existing run; disabled resume never falls back to retry',()=>{const calls=[];const retry={id:'new-retry'},resume={id:'same-response'};const{instance}=page({retryOrdinaryRun:(...args)=>{calls.push(['retry',...args]);return retry;},resumeOrdinaryRun:(...args)=>{calls.push(['resume',...args]);return resume;}});const attached=[];instance.attachNovelRun=(...args)=>attached.push(args);instance.recoverOrdinaryRun('run','resume');assert.equal(calls.length,0);instance.recoverOrdinaryRun('other-run','retry');assert.equal(calls.length,0);instance.recoverOrdinaryRun('run','retry');assert.deepEqual(calls[0],['retry','p','run']);assert.equal(attached[0][0],retry);assert.equal(attached[0][1],3);instance.ordinaryRecovery.resumeAllowed=true;instance.recoverOrdinaryRun('run','resume');assert.deepEqual(calls[1],['resume','p','run']);assert.equal(attached[1][0],resume);assert.equal(attached[1][1],1);instance.busy=true;instance.recoverOrdinaryRun('run','retry');assert.equal(calls.length,2);});
test('recovery description states original request scope without exposing provider cursor or internal paths',()=>{const{instance,record}=page();assert.match(instance.ordinaryRecoveryDetail(record),/主线.*章节重生成/);record.runKind=null;assert.match(instance.ordinaryRecoveryDetail(record),/整章创作/);record.mode='discuss';assert.match(instance.ordinaryRecoveryDetail(record),/讨论/);record.mode='write';record.granularity='continuation';assert.match(instance.ordinaryRecoveryDetail(record),/片段创作/);});

test('actual attach slices rebased ordinary snapshots at retry/current prefix or resume/original prefix',()=>{const {ordinaryRunMessages}=require('../../../deepread/src/main/ets/novel/ordinary_run.ts');for(const kind of ['retry','resume']){let listener;const run={id:'active',subscribe:cb=>{listener=cb;return()=>{};}};const original=[{id:'context1'},{id:'context2'},{id:'context3'}],current=[...original,{id:'saved-user'},{id:'saved-assistant'}];const record={id:'old',branchId:'main',status:'failed',transcriptPrefix:original,originalRequest:{history:original,operation:{kind:'turn',userPrompt:''}}};const page=discussionSession({retryOrdinaryRun:()=>run,resumeOrdinaryRun:()=>run},{projectId:'p',project:{messages:current},workspaceStatus:{activeBranchId:'main'},busy:false});page.ordinaryRecovery={record,active:false,retryAllowed:true,resumeAllowed:true};page.recoverOrdinaryRun('old',kind);const emitted=ordinaryRunMessages({...record,transcriptPrefix:kind==='retry'?current:original},[...original,{id:kind==='retry'?'fresh-user':'saved-user'}, {id:kind==='retry'?'fresh-assistant':'saved-assistant',role:'assistant',parts:[{type:'text',text:'新流式正文'}]}]);listener({kind:'snapshot',messages:emitted,textDeltasLive:true,transport:'live'});assert.equal(page.activeHistoryCount,kind==='retry'?5:3);assert.equal(page.activeMessages.at(-1).parts[0].text,'新流式正文');assert.equal(page.activeMessages.length,2);}});

test('tool continuation same-ID checkpoint preview and copy contain only new partial, not earlier discussion',()=>{
  const {instance,record,copies}=page();
  const old={id:'assistant-tool',role:'assistant',parts:[{type:'text',text:'已讨论的旧内容。'},{type:'tool',toolCallId:'ask'}]};
  const prefix=[{id:'user',role:'user',parts:[]},old];
  record.mode='discuss';record.originalRequest.operation={kind:'tool_continuation',toolCallId:'ask',verdict:{kind:'answered',answer:'已保存的作者回答'}};
  record.transcriptPrefix=prefix;
  const updated={...old,parts:[...old.parts,{type:'text',text:'新增部分正文。完整尾部'}]};
  record.originalRequest.history=prefix;record.checkpointMessages=[prefix[0],updated];
  assert.equal(instance.ordinaryPartialText(record),'新增部分正文。完整尾部');
  instance.copyOrdinaryPartial(record.id,instance.ordinaryPartialText(record));assert.deepEqual(copies,['新增部分正文。完整尾部']);
  record.transcriptPrefix=[prefix[0],updated];
  assert.equal(instance.ordinaryPartialText(record),'新增部分正文。完整尾部','second failure with same partial must still use immutable original request before, not prior presentation text');
  record.checkpointMessages=prefix;assert.equal(instance.ordinaryPartialText(record),'');
  record.checkpointMessages=[...prefix,{id:'new-assistant',role:'assistant',parts:[{type:'text',text:'已讨论的旧内容。这是新回复正文'}]}];
  assert.equal(instance.ordinaryPartialText(record),'已讨论的旧内容。这是新回复正文','new message ID keeps full text even when its words share an old prefix');
  assert.match(instance.ordinaryRecoveryDetail(record),/已保存的回答与工具结果/);
  assert.match(instance.ordinaryRecoveryDetail(record),/重试会替换本次工具续接的部分内容.*先复制/);
});

test('first tool continuation and cold retry/resume attach at the same assistant index and display its updated tail',()=>{
  const {makeNovelMessage}=require('../../../deepread/src/main/ets/novel/models.ts');
  const {novelMessageUi}=require('../../../deepread/src/main/ets/novel/transcript.ts');
  for(const entry of ['first','retry','resume']){
    let listener,called;const run={id:'live',subscribe:cb=>{listener=cb;return()=>{};}};
    const creation={continueTool:(...args)=>{called=args;return run;},retryOrdinaryRun:()=>run,resumeOrdinaryRun:()=>run};
    const user={id:'user',role:'user',parts:[{type:'text',text:'原讨论请求'}]};const assistant={id:'same-assistant',role:'assistant',parts:[{type:'text',text:'旧讨论。'},{type:'tool',toolCallId:'ask',toolName:'ask_user',input:'{}',output:'',approvalState:{type:'pending'}}]};
    const record={id:'record',branchId:'main',status:'failed',runKind:null,transcriptPrefix:[user,assistant],originalRequest:{history:[user,assistant],operation:{kind:'tool_continuation',toolCallId:'ask',verdict:{kind:'answered',answer:'保存的回答'}}}};
    const page=discussionSession(creation,{projectId:'p',project:{messages:[user,assistant].map(ui=>makeNovelMessage({id:ui.id,role:ui.role,mode:'discuss',uiMessage:ui}))},workspaceStatus:{activeBranchId:'main'},busy:false});page.ordinaryRecovery={record,active:false,retryAllowed:true,resumeAllowed:true};
    if(entry==='first'){page.continueNovelTool('ask',record.originalRequest.operation.verdict,{messageId:'same-assistant',partIndex:1});assert.deepEqual(called,['p','ask',record.originalRequest.operation.verdict]);}else page.recoverOrdinaryRun('record',entry);
    const updated={...assistant,parts:[...assistant.parts,{type:'text',text:'新增正文及完整末尾。'}]};
    listener({kind:'snapshot',messages:ordinaryRunMessages(record,[user,updated]),textDeltasLive:true,transport:'live'});
    assert.equal(page.activeHistoryCount,1);assert.equal(page.activeMessages.length,1);assert.equal(page.activeMessages[0].id,'same-assistant');assert.equal(page.activeMessages[0].parts.at(-1).text,'新增正文及完整末尾。');
  }
});
