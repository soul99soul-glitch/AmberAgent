import type { AbortSignalLike, SearchHit } from '@amber/deepread-domain';
import { WebMountSession } from './WebMountSession.ets';
import type { WebMountHookBinding } from './WebMountSession.ets';
import { checkWebMountUrl } from './WebMountSites.ets';

interface GoogleResult { title: string; url: string; snippet: string; }
interface GoogleResultEnvelope { ok: boolean; value?: GoogleResult[]; error?: string; }
interface GoogleHost { session: WebMountSession; }
const hosts: Map<string, GoogleHost> = new Map<string, GoogleHost>();

export const googleSearchPageAllowed = (raw: string): boolean => {
  const checked = checkWebMountUrl(raw);
  return checked.ok && (checked.hostname === 'google.com' || checked.hostname.endsWith('.google.com'));
};

// Each article supplies its own controller; returning to another article never takes Chat's browser owner.
export const bindDeepReadGoogleHost = (topicId: string, session: WebMountSession): WebMountHookBinding => {
  const host: GoogleHost = { session };
  hosts.set(topicId, host);
  return { unbind: (): void => { if (hosts.get(topicId) === host) hosts.delete(topicId); } };
};

export const googleResultsScript = (limit: number): string => `(function(){
  var results=[], seen={};
  var headings=document.querySelectorAll('a h3');
  for(var i=0;i<headings.length && results.length<${Math.max(1, Math.min(50, limit))};i++){
    var heading=headings[i], anchor=heading.closest('a');
    if(!anchor)continue;
    var target=new URL(anchor.href,location.href);
    if(target.hostname==='www.google.com' && target.pathname==='/url'){
      var redirected=target.searchParams.get('q')||target.searchParams.get('url');
      if(!redirected)continue;
      target=new URL(redirected);
    }
    if(!/^https?:$/.test(target.protocol)||target.hostname==='google.com'||/\.google\.com$/.test(target.hostname))continue;
    var title=(heading.innerText||'').trim();
    if(!title||seen[target.href])continue;
    seen[target.href]=true;
    var block=anchor.parentElement;
    results.push({title:title,url:target.href,snippet:(block?block.innerText:'').slice(0,1200)});
  }
  return results;
})()`;

export const searchDeepReadGoogle = async (
  topicId: string, query: string, resultSize: number, signal?: AbortSignalLike,
): Promise<SearchHit[]> => {
  if (signal?.aborted) throw new Error('Google 搜索已取消');
  const host: GoogleHost | undefined = hosts.get(topicId);
  if (host === undefined) throw new Error('Google 网页搜索需要当前文章的浏览器页面');
  const stop: () => void = (): void => { host.session.getController().stop(); };
  signal?.addEventListener?.('abort', stop);
  try {
    await host.session.open(`https://www.google.com/search?q=${encodeURIComponent(query)}&hl=zh-CN`, signal);
    if (signal?.aborted || hosts.get(topicId) !== host) throw new Error('Google 搜索页面已离开或请求已取消');
    const raw: string = await host.session.evalJson(googleResultsScript(resultSize));
    if (signal?.aborted || hosts.get(topicId) !== host) throw new Error('Google 搜索页面已离开或请求已取消');
    const envelope: GoogleResultEnvelope = JSON.parse(raw) as GoogleResultEnvelope;
    if (!envelope.ok || !Array.isArray(envelope.value)) throw new Error('Google 页面未提供可读取的搜索结果');
    const hits: SearchHit[] = [];
    for (const item of envelope.value) {
      if (typeof item.title !== 'string' || typeof item.url !== 'string') continue;
      const checked = checkWebMountUrl(item.url);
      if (!checked.ok) continue;
      hits.push({ title: item.title, url: checked.url, snippet: typeof item.snippet === 'string' ? item.snippet : '', source: 'Google' });
    }
    return hits;
  } finally {
    signal?.removeEventListener?.('abort', stop);
  }
};
