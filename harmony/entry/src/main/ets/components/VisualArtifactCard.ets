import type { VisualArtifact } from '@amber/chat-domain';
import { VisualArtifactPreview } from './VisualArtifactPreview.ets';
import { DotLoading } from './ui/DotLoading.ets';
import { INK, INK3, LINE, META, LABEL, SURFACE, ACCENT } from '../design/tokens.ets';

@Component
export struct VisualArtifactCard {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('accentColor') accent: string = ACCENT;
  @Prop @Watch('onArtifactChanged') artifact: VisualArtifact;
  @Prop streaming: boolean = false;
  @Prop revealKey: string = '';
  @Prop revealEligible: boolean = false;
  onContentGrow: () => void = (): void => {};
  onArtifactArea: (id: string, area: Area) => void = (_id: string, _area: Area): void => {};
  onValidation: (snapshot: string, ok: boolean, error: string) => void =
    (_snapshot: string, _ok: boolean, _error: string): void => {};
  @State sourceEpoch: number = 0;
  private previousRaw: string = '';

  aboutToAppear(): void { this.previousRaw = this.artifact.raw; }

  private onArtifactChanged(): void {
    // A replaced source must not keep a preview from the previous definition.
    if (!this.artifact.raw.startsWith(this.previousRaw)) this.sourceEpoch++;
    this.previousRaw = this.artifact.raw;
  }

  build(): void {
    Column({ space: 6 }) {
      Row() {
        Text(this.artifact.type === 'svg' ? '矢量图' : '三维场景')
          .fontSize(META).fontColor(INK).layoutWeight(1)
        if (!this.artifact.closed && this.streaming) {
          DotLoading({ color: this.accent, dotSize: 3 }).width(28).height(14)
          Text('生成中').fontSize(LABEL).fontColor(INK3).margin({ left: 6 })
        }
      }.width('100%').padding({ left: 12, right: 12, top: 10 })
      if (this.artifact.snapshot !== null) {
        ForEach([this.sourceEpoch], (_epoch: number): void => {
          VisualArtifactPreview({ html: this.artifact.snapshot ?? '',
            renderer: this.artifact.type === 'three' ? 'threejs' : 'svg',
            scene: this.artifact.scene, complete: this.artifact.closed && this.artifact.status === 'ready',
            revealKey: this.revealKey, revealEligible: this.revealEligible,
            strictSvg: this.artifact.type === 'svg',
            onContentGrow: (): void => { this.onContentGrow(); },
            onValidation: (ok: boolean, error: string): void => {
              this.onValidation(this.artifact.snapshot ?? '', ok, error);
            } })
        }, (epoch: number): string => `${epoch}`)
      } else {
        Text(this.streaming ? '正在生成图示…' : '图示内容未完整生成')
          .fontSize(META).fontColor(INK3).width('100%').padding(16)
      }
      if (!this.artifact.closed && !this.streaming && this.artifact.snapshot !== null) {
        Text('内容未完整生成，显示已收到的部分')
          .fontSize(LABEL).fontColor(INK3).width('100%').padding({ left: 12, right: 12, bottom: 8 })
      }
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1).width('100%').alignItems(HorizontalAlign.Start)
    .backgroundColor(SURFACE).borderRadius(16).border({ width: 1, color: LINE }).clip(true).margin({ bottom: 8 })
    .onAreaChange((before: Area, after: Area): void => {
      this.onArtifactArea(this.artifact.id, after);
      if (before.height !== after.height) this.onContentGrow();
    })
  }
}
