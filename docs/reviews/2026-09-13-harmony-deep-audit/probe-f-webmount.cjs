const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../..');
const ts = require(path.join(root, 'harmony/chat/node_modules/typescript'));
const cache = new Map();
const kv = { get: async () => JSON.stringify([{id:'github',name:'GitHub',url:'https://github.com/login'}]), put: async () => {} };
function load(relative) {
  const filename = path.join(root, relative);
  if (cache.has(filename)) return cache.get(filename);
  const source = fs.readFileSync(filename, 'utf8');
  const js = ts.transpileModule(source, {compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
  const module = {exports:{}};
  const toolPath = 'harmony/chat/src/main/ets/chat/tool.ts';
  function dependency(name) {
    if (name === '@amber/chat-domain') return {...load(toolPath),newId:()=> 'synthetic-id'};
    if (name.includes('AppContainer')) return {getChatKvStore:()=>kv};
    if (name.endsWith('WebMountSites.ets')) return load('harmony/entry/src/main/ets/platform_impl/WebMountSites.ets');
    throw new Error('Unexpected runtime dependency: '+name);
  }
  vm.runInNewContext('(function(require,module,exports){'+js+'\n})', {console,setTimeout,clearTimeout}, {filename})(dependency,module,module.exports);
  cache.set(filename,module.exports); return module.exports;
}
(async()=>{
 const sites=load('harmony/entry/src/main/ets/platform_impl/WebMountSites.ets');
 const registered=[{id:'github',name:'GitHub',url:'https://github.com/login'}];
 const deceptive='https://github.com:synthetic-password@outside.invalid/private';
 assert.equal(sites.siteAllowsUrl(registered,deceptive),true);
 assert.equal(new URL(deceptive).hostname,'outside.invalid');
 console.log('F-02 CONFIRMED: gate accepts github.com userinfo while standard URL host is outside.invalid');
 const context={window:{__audit:0},document:{querySelector:selector=>{assert.ok(selector.includes('data-x'));return null;}}};
 const session={evalJson:async expression=>JSON.stringify(vm.runInNewContext(expression,context))};
 const tools=load('harmony/entry/src/main/ets/platform_impl/WebMountTools.ets').createWebMountTools(session,false);
 assert.equal(tools.some(t=>t.name==='wm_eval'),false);
 const click=tools.find(t=>t.name==='wm_click');
 const target=`[data-x="x'+(window.__audit=1)+'"]`;
 await click.execute({target});
 assert.equal(context.window.__audit,1);
 assert.equal(click.allowsAutoApproval,true);
 assert.equal(click.mandatoryApproval,false);
 console.log('F-01 CONFIRMED: actual wm_click.execute runs injected expression with wm_eval omitted; no browser/network or user data used');
 console.log('All probes import/transpile current source, not copied implementation. SDK/WebView dispatch remains unexecuted.');
})().catch(e=>{console.error(e);process.exitCode=1;});
