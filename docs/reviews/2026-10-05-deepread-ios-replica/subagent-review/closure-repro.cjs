const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=process.cwd(),ts=require(root+'/harmony/chat/node_modules/typescript');
const board=fs.readFileSync(root+'/harmony/entry/src/main/ets/pages/BoardPage.ets','utf8');
function method(source,name){const match=new RegExp('^  (?:private )?(?:async )?'+name+'\\(','m').exec(source);assert(match);let end=source.indexOf('{',match.index)+1,depth=1;for(;depth&&end<source.length;end++){if(source[end]==='{')depth++;if(source[end]==='}')depth--;}return source.slice(match.index,end);}
const code=ts.transpileModule('class Page {\n'+method(board,'openOriginal')+'\n}\nexports.Page=Page',{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText;
(async()=>{
 for(const sync of [false,true]){
  const notices=[],clip=[];const exports={};vm.runInNewContext(code,{exports,getContext:()=>({startAbility:()=>sync?(()=>{throw Error('browser unavailable')})():Promise.reject(Error('browser unavailable'))}),promptAction:{showToast:v=>notices.push(v.message)},pasteboard:{getSystemPasteboard:()=>({setDataSync:x=>clip.push(x)})},Error,Promise});
  new exports.Page().openOriginal('https://example.test/a');for(let i=0;i<6;i++)await Promise.resolve();
  assert(notices.some(x=>x.includes('已复制链接')));assert.equal(clip.length,0);console.log(JSON.stringify({case:sync?'synchronous browser exception':'browser rejected',notices,clipboardWrites:clip.length}));
 }
 const sources=fs.readFileSync(root+'/harmony/entry/src/main/ets/pages/DeepReadSourcesPage.ets','utf8');
 const expr=sources.match(/source\.kind === 'text' && this\.inputText\.length > 0 \? this\.inputText\.substring\(0, 40000\) : source\.content/);assert(expr);
 const inputText='来源 A 的正文\n\n来源 B 的正文',a={kind:'text',content:'来源 A 的正文'},b={kind:'text',content:'来源 B 的正文'};
 const f=new Function('source','return '+expr[0]);const shownA=f.call({inputText},a),shownB=f.call({inputText},b);
 assert.equal(shownA,shownB);assert.notEqual(shownA,a.content);console.log(JSON.stringify({case:'actual source body expression',inputText,shownA,shownB,expectedA:a.content,expectedB:b.content}));
})().catch(e=>{console.error(e);process.exit(1)});
