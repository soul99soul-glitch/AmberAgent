const fs=require('node:fs'); const path=require('node:path'); const vm=require('node:vm'); const assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../..'); const ts=require(path.join(root,'harmony/chat/node_modules/typescript'));
const file=path.join(root,'harmony/entry/src/main/ets/platform_impl/WebMountSession.ets');
const full=fs.readFileSync(file,'utf8');
// Compile the actual platform class; omit only the trailing ArkUI struct syntax unsupported by TypeScript.
const source=full.slice(0,full.indexOf('@Component'));
const js=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
let loads=0,ticks=0,mounts=0;const moduleObject={exports:{}};
const imports=name=>name==='@kit.ArkWeb'?{webview:{WebviewController:class{loadUrl(){loads++}}}}:name==='@kit.PerformanceAnalysisKit'?{hilog:{info(){}}}:{};
vm.runInNewContext('(function(require,module,exports){'+js+'\n})',{setTimeout:callback=>{ticks++;queueMicrotask(callback);return ticks},clearTimeout(){},console},{filename:file})(imports,moduleObject,moduleObject.exports);
(async()=>{
 const session=new moduleObject.exports.WebMountSession();
 session.bindHooks({onMountNeeded(){mounts++}});
 await assert.rejects(session.open('https://example.invalid'),/host not mounted/);
 assert.equal(mounts,1);assert.equal(ticks,50);assert.equal(loads,0);
 console.log('F-03 CONFIRMED: actual session.open takes all 50 mount-wait ticks then rejects; loadUrl never invoked. Clock accelerated, no SDK/UI/network.');
 console.log('Source call-site search separately confirms markHostMounted has definition only, including WebMountHost.');
})().catch(e=>{console.error(e);process.exitCode=1});
