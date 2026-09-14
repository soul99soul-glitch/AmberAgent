// Real Chrome + Lexical regression for WebMount's standard editing events.
// This fixture sends only to an in-memory array, never a remote ZCode account.
// Requires installed Chrome and NODE_PATH packages: playwright, esbuild,
// lexical, @lexical/rich-text. Verified with Lexical 0.50.0.
// Unlike a mocked beforeinput listener, the real editor must accept each edit.
const { chromium }=require('playwright');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const os=require('node:os');
const {pathToFileURL}=require('node:url');
const {buildSync}=require('esbuild');
const work=fs.mkdtempSync(path.join(os.tmpdir(),'amber-zcode-lexical-'));
const assets=path.resolve(__dirname,'../app/src/main/assets/webmount');

const editorSource="import {createEditor,$getRoot,$createParagraphNode} from LEXICAL_MODULE;\nimport {registerRichText} from RICH_TEXT_MODULE;\nconst input=document.querySelector('[data-testid=\"v4-composer-input\"]');\nconst send=document.querySelector('[data-testid=\"v4-composer-send\"]');\nconst editor=createEditor({namespace:'ZCode-input-verification',onError:e=>{window.qaError=String(e);throw e;}});\neditor.setRootElement(input);registerRichText(editor);\neditor.update(()=>{$getRoot().append($createParagraphNode());});\nwindow.qaEditorText=()=>editor.getEditorState().read(()=>$getRoot().getTextContent());\nwindow.qaSent=[];\neditor.registerUpdateListener(()=>{send.disabled=!window.qaEditorText().trim();});\ndocument.querySelector('form').addEventListener('submit',e=>{e.preventDefault();window.qaSent.push(window.qaEditorText());});\nwindow.AmberWM={onNetworkEvent(){},resolve(id,v){window.qaPending.get(id).resolve(JSON.parse(v));window.qaPending.delete(id);},reject(id,e){window.qaPending.get(id).reject(new Error(e));window.qaPending.delete(id);}};\nwindow.qaPending=new Map();window.qaCall=(method,args={})=>new Promise((resolve,reject)=>{const id=Math.random().toString();window.qaPending.set(id,{resolve,reject});window.__amberWm_call(method,JSON.stringify(args),id);});\n".replace('LEXICAL_MODULE',JSON.stringify(require.resolve('lexical'))).replace('RICH_TEXT_MODULE',JSON.stringify(require.resolve('@lexical/rich-text')));
fs.writeFileSync(path.join(work,'editor-fixture.html'),"<!doctype html><html><head><meta charset=\"utf-8\"><title>ZCode input QA</title><style>body{font:16px sans-serif;padding:24px}[contenteditable]{min-height:100px;border:1px solid #999;padding:12px;width:320px}button{margin-top:12px;padding:8px}</style></head><body><header data-testid=\"workspace-header\"><h1 data-testid=\"workspace-title\">Isolated input verification</h1></header><div data-testid=\"v4-session-pane-qa\" data-session-id=\"qa-task\"><div class=\"chat-composer-region\" data-testid=\"v4-composer\" data-input-routing=\"startNow\"><form><div data-testid=\"v4-composer-input\" data-lexical-editor=\"true\" role=\"textbox\" contenteditable=\"true\"></div><button type=\"submit\" data-testid=\"v4-composer-send\" disabled>Send locally</button></form></div></div><script src=\"editor-fixture.bundle.js\"></script><script src=\"zcode-ui-tree.js\"></script><script src=\"bridge.js\"></script></body></html>");
buildSync({stdin:{contents:editorSource,resolveDir:work,sourcefile:'editor-fixture.js'},bundle:true,outfile:path.join(work,'editor-fixture.bundle.js')});
for(const name of ['bridge.js','zcode-ui-tree.js'])fs.copyFileSync(path.join(assets,name),path.join(work,name));
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}});
  await page.goto(pathToFileURL(path.join(work,'editor-fixture.html')).href);
  await page.waitForFunction(()=>window.qaEditorText&&document.querySelector('[data-lexical-editor]').firstChild);
  const cases=[['first',true,'Amber 输入验证','Amber 输入验证'],['append',false,' second','Amber 输入验证 second'],['replace',true,'替换内容','替换内容'],['clear',true,'','']];
  for(const [name,clear,text,expected] of cases){
   const result=await page.evaluate(async({text,clear})=>{const r=await qaCall('zcode_read');const receipt=await qaCall('type',{target:r.zcode.composer_target.ref,snapshot_id:r.zcode.snapshot_id,text,clear});await new Promise(r=>setTimeout(r,150));return {receipt,model:qaEditorText(),dom:document.querySelector('[data-lexical-editor]').textContent,error:window.qaError};},{text,clear});
   console.log(JSON.stringify({name,model:result.model,dom:result.dom,ok:result.receipt.ok,error:result.error}));
   assert.equal(result.model,expected,name+' model');assert.equal(result.dom,expected,name+' DOM');assert.equal(result.receipt.ok,true);
  }
  const sent=await page.evaluate(async()=>{const r=await qaCall('zcode_read');const p=await qaCall('zcode_prepare',{input_target:r.zcode.composer_target.ref,send_target:r.zcode.send_target.ref,snapshot_id:r.zcode.snapshot_id,remote_task_id:r.zcode.remote_task_id,text:'第一行\n第二行\n\n第三行'});await new Promise(r=>setTimeout(r,300));const a=await qaCall('zcode_send',{ticket:p.ticket});const b=await qaCall('zcode_send',{ticket:p.ticket});return {prepared:p.ok,first:a,second:b,sent:qaSent,model:qaEditorText()};});
  console.log(JSON.stringify(sent));assert.equal(sent.prepared,true);assert.equal(sent.first.ok,true);assert.equal(sent.second.ok,false);assert.deepEqual(sent.sent,['第一行\n第二行\n\n第三行']);
  console.log('Chrome + Lexical input and single-send QA: PASS');
 }finally{await browser.close();fs.rmSync(work,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
