const fs = require('node:fs');
const vm = require('node:vm');
const ts = require(process.cwd() + '/harmony/deepread/node_modules/typescript');
const settings = fs.readFileSync('harmony/entry/src/main/ets/pages/DeepReadDiscoverySettingsPage.ets', 'utf8');
const board = fs.readFileSync('harmony/entry/src/main/ets/pages/BoardPage.ets', 'utf8');
function method(src, name) {
 const start=src.search(new RegExp('^  (?:private\\s+)?(?:async\\s+)?'+name+'\\(', 'm'));
 if(start<0)throw Error(name);
 const open=src.indexOf('{',start);let depth=1,end=open+1;
 while(depth&&end<src.length){if(src[end]==='{')depth++;if(src[end]==='}')depth--;end++;}
 return src.slice(start,end);
}
const compile=text=>ts.transpileModule(text,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
let release;const hold=new Promise(resolve=>release=resolve);const values={deepread_focus_keywords:'旧关注',deepread_focus_mode:'focus_only'};
const storage={async get(key,fallback){return Object.hasOwn(values,key)?values[key]:fallback;},async set(key,value){await hold;values[key]=value;}};
const context={module:{exports:{}},getProductKind:()=> 'deepread',getAppContainer:()=>({storage}),DeepReadHaptics:{selection(){}},
 HOTLIST_PRESETS:[],parseHotListFocusKeywords:text=>text.split(','),createEntryAbortController:()=>({abort(){}})};
vm.runInNewContext(compile(`module.exports.Settings=class {${['persistPreference','aboutToDisappear'].map(n=>method(settings,n)).join('\n')}};
module.exports.Board=class {${method(board,'loadHotlist')}};`),context);
(async()=>{
 const s=new context.module.exports.Settings();Object.assign(s,{alive:true,settingsLoaded:true,loading:false,writeQueue:Promise.resolve(),pendingWrites:0,writeFailure:'',loadToken:1});
 s.persistPreference('deepread_focus_keywords','新关注');
 await Promise.resolve();s.aboutToDisappear();
 const b=new context.module.exports.Board();Object.assign(b,{loading:false,refreshToken:0,pageVisible:true,cacheLoaded:true,rawSections:[],applyProjection(){},requestController:null});
 await b.loadHotlist(false);
 const before=b.focusKeywords.slice();release();await s.writeQueue;
 console.log(JSON.stringify({firstReturnApplied:before,afterQueuePersisted:values.deepread_focus_keywords,boardStillApplied:b.focusKeywords,pendingWrites:s.pendingWrites},null,2));
})().catch(e=>{console.error(e);process.exitCode=1;});
