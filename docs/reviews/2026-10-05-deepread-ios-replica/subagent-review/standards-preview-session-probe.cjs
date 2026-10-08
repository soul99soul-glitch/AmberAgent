const fs=require('node:fs');
const ts=require(process.cwd()+'/harmony/deepread/node_modules/typescript');
const vm=require('node:vm');
const source=fs.readFileSync('harmony/entry/src/main/ets/components/deepread/DeepReadTemplateWorkbench.ets','utf8');
function method(name){
 const start=source.search(new RegExp('^  (?:private\\s+)?(?:async\\s+)?'+name+'\\(', 'm'));const open=source.indexOf('{',start);let d=1,e=open+1;
 while(d&&e<source.length){if(source[e]==='{')d++;if(source[e]==='}')d--;e++;}return source.slice(start,e);
}
const calls=[];const context={module:{exports:{}},validateTemplateHtml:()=>'',getProductKind:()=> 'deepread',SAMPLE_TITLE:'示例',buildSampleTemplateOutput:()=>({}),renderCustomTemplateHtml:()=>'<html>content</html>',D:{paper:'#fff',ink:'#000',muted:'#aaa',card:'#fff',rule:'#ccc',accent:'#f00'}};
const text=`module.exports=class PreviewProbe {${['openPreview','attachPreviewWeb','onPreviewPageEnd','loadPreview'].map(method).join('\n')}}`;
vm.runInNewContext(ts.transpileModule(text,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText,context);
const p=new context.module.exports();Object.assign(p,{alive:true,previewOpen:false,previewId:0,themeEpoch:1,webAttached:false,webInitialized:false,editHtml:'valid',fontScale:1,fontSerif:false,webController:{loadData(...args){calls.push(args)}}});
p.openPreview();const first=p.previewId;p.attachPreviewWeb(first,1);p.onPreviewPageEnd('about:blank',first,1);
const firstLoads=calls.length;
// Exactly the existing bindSheet onDisappear reset, then the production open method.
p.previewOpen=false;p.webAttached=false;p.webInitialized=false;p.openPreview();const second=p.previewId;
p.attachPreviewWeb(first,1);p.onPreviewPageEnd('about:blank',first,1);
const oldCallbackState={loads:calls.length,attached:p.webAttached,initialized:p.webInitialized,open:p.previewOpen};
p.loadPreview();const noAttachLoads=calls.length;
p.attachPreviewWeb(second,1);p.onPreviewPageEnd('about:blank',second,1);
console.log(JSON.stringify({first,second,firstLoads,oldCallbackState,noAttachLoads,freshCallbackLoads:calls.length},null,2));
