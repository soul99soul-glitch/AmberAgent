// Independent H10 probe: real scheduler; runManager.run fast, no prefetch delay.
// Checks: (a) successful run -> is the completed notification immediately cancelled?
// (b) replace while first run still in prefetch -> does late finally cancel B's running?
import { createScheduler, isInterruptedPhase } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/agent/scheduler.ts';
import type { RunResult, RunManagerDeps } from '/Users/mi/Downloads/AI/AmberAgent-harmony-preview/harmony/deepread/src/main/ets/agent/run_manager.ts';

const log: string[] = [];
class AC { aborted=false; ls:Array<()=>void>=[];
  signal={aborted:false,addEventListener:(_t:string,l:()=>void)=>{this.ls.push(l);},removeEventListener:(_t:string,l:()=>void)=>{const i=this.ls.indexOf(l);if(i>=0)this.ls.splice(i,1);}};
  abort(){this.aborted=true;this.signal.aborted=true;for(const l of this.ls)l();}}
const store=new Map<string,any>();
const mkDeps=(collectDelay:number):RunManagerDeps=>({
  prefetcher:{collect:async()=>{await new Promise(r=>setTimeout(r,collectDelay));return [{sourceId:'s1',url:'https://e.com/a',title:'t',source:'m',evidenceText:'body '.repeat(20),credibility:'high',freshness:'unknown',publishedAt:null,imageCandidates:[]}];}} as any,
  collectRun:async()=>[], aiClient:{generateText:async()=>[]} as any, model:'m', playbookMarkdown:'', nowIso:()=>'x',
  repository:{get:(id)=>store.get(id)??null,save:(id,_t,o)=>{store.set(id,o);},clear:(id)=>{store.delete(id);}},
});
const notifier={
  notifyRunning:async(id:string)=>{log.push(`running:${id}`);},
  notifyCompleted:async(id:string,_t:string,c:boolean)=>{log.push(`completed:${id}`);},
  cancelRunning:async(id:string)=>{log.push(`cancel:${id}`);},
};
// (a) fast run
const sc1=createScheduler({runManager:mkDeps(0),observeOutput:()=>({subscribe:()=>()=>{},getCurrent:()=>undefined}),isInterruptedPhase,createAbortController:()=>new AC() as any,notifier});
await sc1.run('topic-A','T',{});
console.log('A) single successful run notifier log =',JSON.stringify(log.splice(0)));

// (b) replace during prefetch
const sc2=createScheduler({runManager:mkDeps(5500),observeOutput:()=>({subscribe:()=>()=>{},getCurrent:()=>undefined}),isInterruptedPhase,createAbortController:()=>new AC() as any,notifier});
const p1=sc2.run('topic-B','T',{});
await new Promise(r=>setTimeout(r,80));
const p2=sc2.run('topic-B','T',{force:true});
const res=await Promise.allSettled([p1,p2]);
console.log('B) replace notifier log =',JSON.stringify(log.splice(0)));
console.log('B) p1=',res[0].status,'p2=',res[1].status);
