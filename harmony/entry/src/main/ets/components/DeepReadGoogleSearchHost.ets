import { WebMountSession } from '../platform_impl/WebMountSession.ets';
import type { WebMountHookBinding } from '../platform_impl/WebMountSession.ets';
import { bindDeepReadGoogleHost, googleSearchPageAllowed } from '../platform_impl/DeepReadGoogleSearch.ets';

@Component
export struct DeepReadGoogleSearchHost {
  @Prop topicId: string = '';
  @State mounted: boolean = false;
  private session: WebMountSession = new WebMountSession(false);
  private requestBinding: WebMountHookBinding | null = null;
  private mountBinding: WebMountHookBinding | null = null;

  aboutToAppear(): void {
    this.session.setUrlGate(googleSearchPageAllowed);
    this.mountBinding = this.session.bindHooks({ onMountNeeded: (): void => { this.mounted = true; } });
    this.requestBinding = bindDeepReadGoogleHost(this.topicId, this.session);
  }

  aboutToDisappear(): void {
    this.requestBinding?.unbind();
    this.mountBinding?.unbind();
    if (this.mounted) this.session.getController().stop();
    this.session.markControllerDetached();
  }

  build(): void {
    if (this.mounted) {
      Web({ src: 'about:blank', controller: this.session.getController() })
        .width(360).height(720).position({ x: -2000, y: 0 })
        .onControllerAttached((): void => { this.session.markControllerAttached(); })
        .onLoadIntercept((event: OnLoadInterceptEvent): boolean =>
          event.data.isMainFrame() && this.session.shouldBlockUrl(event.data.getRequestUrl()))
        .onPageEnd((event: OnPageEndEvent): void => { this.session.markPageEnd(event.url); })
        .onDetach((): void => { this.session.markControllerDetached(); })
    }
  }
}
