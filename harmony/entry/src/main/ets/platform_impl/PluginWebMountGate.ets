import type { PluginWebMountPort } from '@amber/chat-domain';
import type { WebMountHookBinding, WebMountSession } from './WebMountSession.ets';
import { checkWebMountUrl } from './WebMountSites.ets';
import type { WebMountUrlCheck } from './WebMountSites.ets';

const allowedUrl = (raw: string, domains: string[]): boolean => {
  const check: WebMountUrlCheck = checkWebMountUrl(raw);
  return check.ok && domains.some((domain: string): boolean =>
    check.hostname === domain || check.hostname.endsWith(`.${domain}`));
};

class EntryPluginWebMountPort implements PluginWebMountPort {
  private readonly session: WebMountSession;

  constructor(session: WebMountSession) {
    this.session = session;
  }

  async withScope<T>(networkDomains: string[], operation: () => Promise<T>, requireCurrent: boolean = true): Promise<T> {
    const domains: string[] = networkDomains.slice();
    const current: string = this.session.getUrl();
    if (requireCurrent && current.length > 0 && current !== 'about:blank' && !allowedUrl(current, domains)) {
      throw new Error('network_domain_denied: 当前 WebMount 页面不在插件声明的域内。');
    }
    // Site refresh cannot replace this separate gate. Unbind releases the
    // active owner; the gate stays with the loaded page for later navigation.
    const binding: WebMountHookBinding = this.session.bindPluginUrlGate(
      (target: string): boolean => allowedUrl(target, domains));
    try {
      const result: T = await operation();
      const finalUrl: string = this.session.getUrl();
      if (finalUrl.length > 0 && finalUrl !== 'about:blank' && !allowedUrl(finalUrl, domains)) {
        this.session.resetToBlank();
        throw new Error('network_domain_denied: WebMount 的实际终态 URL 不在插件声明的域内。');
      }
      return result;
    } catch (error) {
      // open/reload reject on cancellation before pageEnd. Discard that
      // pending page before releasing its navigation gate.
      if (!this.session.isReady()) this.session.resetToBlank();
      throw error as Error;
    } finally {
      binding.unbind();
    }
  }
}

export const createEntryPluginWebMountPort = (session: WebMountSession): PluginWebMountPort =>
  new EntryPluginWebMountPort(session);
