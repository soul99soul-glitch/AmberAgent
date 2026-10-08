const fs=require('fs'),path=require('path'),{createRequire}=require('module');
const root='/Users/arquiel/Downloads/AI/amber-harmony-preview';
const fixture=path.join(root,'harmony/entry/src/test/deepread_template_ui.test.cjs');
const localRequire=createRequire(fixture); const ts=localRequire('../../../chat/node_modules/typescript');
const prefix=fs.readFileSync(fixture,'utf8').split("test('actual new")[0];
const {template,page}=new Function('require','__dirname',prefix+'\nreturn {template,page};')(localRequire,path.dirname(fixture));
const canonical='<html><head><style>{{font_css}}</style></head><body><h1>{{title}}</h1><p>{{summary}}</p><section>{{analysis_html}}</section><section>{{extended_reading_html}}</section></body></html>';
const rendered=template.renderCustomTemplateHtml(canonical,'Spec Test',template.buildSampleTemplateOutput(),{fontScale:1,fontSerif:true});
const ip=path.join(root,'harmony/deepread/src/main/ets/domain/input_sources.ts'); const sourceExports={};
new Function('exports',ts.transpileModule(fs.readFileSync(ip,'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText)(sourceExports);
const writes=[];let created=0;
const composer=page('components/deepread/DeepReadComposerSheet.ets',['createArticle'],{
 sourceInputs:sourceExports.sourceInputs,makeEmptyDeepReadOutput:()=>({}),captureDeepReadTemplate:async()=>({id:'none'}),
 getAppContainer:()=>({storage:{}}),getRepository:()=>({upsert:async e=>writes.push(e)}),newId:()=> 'spec-invalid-url',
 DeepReadHaptics:{success:()=>{}}
});Object.assign(composer,{busy:false,templatesLoading:false,pendingTopicId:'',title:'有效主题',text:'',urls:'ftp://invalid.example',files:[],templateId:'none',pickerToken:0,alive:true,onCreated:async()=>{created++}});
(async()=>{
 await composer.createArticle();
 const search=page('ChatSearchSettingsPage.ets',['confirmDelete'],{});
 let deletes=0;Object.assign(search,{editIndex:0,s:{searchServices:[{id:'last-invalid'}]},saveEditorPatch:()=>{deletes++}});search.confirmDelete();
 const output={template:{validation:template.validateTemplateHtml(canonical),unresolvedPlaceholders:rendered.match(/\{\{[^}]+\}\}/g)},invalidUrl:{entriesSaved:writes.length,articleCallback:created,error:composer.error,savedSource:writes[0]?.output.inputSources[0]},deleteLastSearchService:{deleteWrites:deletes}};
 console.log(JSON.stringify(output,null,2));fs.writeFileSync('/tmp/deepread-spec-probe-result-20261005.json',JSON.stringify(output,null,2));
})();
