const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const harness = async () => {
  const domain = await import('../main/ets/index.ts');
  const user = domain.makeUIMessage('user', [{type:'text',text:'请记住我喜欢简洁回答',metadata:null}], {id:'user-source',createdAt:1000});
  const snapshot = domain.makeConversation('conversation', [domain.toMessageNode(user)]);
  let live = JSON.parse(JSON.stringify(snapshot));
  const worker = {enabled:true,extractionEnabled:true};
  const imports = {'@amber/chat-domain':domain,
    '../di/AppContainer.ets':{getChatKvStore:()=>({}),getChatRepository:()=>({getById:async()=>live})},
    './AgentRuntimePrefs.ets':{loadAgentRuntimeSnapshot:async()=>({memoryWorker:worker})}};
  const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/MemoryExtractionCommitGuard.ets');
  const code = ts.transpileModule(fs.readFileSync(filename,'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
  const exports = {};
  vm.runInNewContext(code,{exports,require:name=>{assert.ok(imports[name]);return imports[name];},JSON,Promise},{filename});
  return {domain,snapshot,worker,getLive:()=>live,setLive:value=>{live=value;},check:()=>exports.canCommitMemoryExtraction(snapshot)};
};
test('unchanged committed user source may commit; revoked settings and deleted conversations cannot',async()=>{
  const h=await harness(); assert.equal(await h.check(),true);
  h.worker.extractionEnabled=false; assert.equal(await h.check(),false);
  h.worker.extractionEnabled=true; h.worker.enabled=false; assert.equal(await h.check(),false);
  h.worker.enabled=true; h.setLive(null); assert.equal(await h.check(),false);
});
test('edited user text and changed branch cannot commit an old model response',async()=>{
  const h=await harness();
  h.getLive().messageNodes[0].messages[0].parts[0].text='请记住我喜欢详细回答';
  assert.equal(await h.check(),false);
  h.setLive(h.domain.makeConversation('conversation',[h.domain.toMessageNode(h.domain.makeUIMessage('user',
    [{type:'text',text:'请记住我喜欢简洁回答',metadata:null}],{id:'another-branch',createdAt:1000}))]));
  assert.equal(await h.check(),false);
});
test('external tool content added while extraction runs blocks the old response',async()=>{
  const h=await harness();
  h.getLive().messageNodes.push(h.domain.toMessageNode(h.domain.makeUIMessage('tool',
    [{type:'tool',toolName:'search_web',toolCallId:'web-call',arguments:{},output:'external result',metadata:null}],{id:'external'})));
  assert.equal(await h.check(),false);
});
