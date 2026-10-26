import { image } from '@kit.ImageKit';
import { util } from '@kit.ArkTS';
import { renderThreeSceneCover } from '@amber/chat-domain';
import type { ThreeSceneSpec } from '@amber/chat-domain';
import { INK3 } from '../design/tokens.ets';

@Component
export struct NativeThreeCover {
  @Prop @Watch('invalidateCover') scene: ThreeSceneSpec | null = null;
  @Prop @Watch('invalidateCover') dark: boolean = false;
  @Prop @Watch('invalidateCover') snapshot: string = '';
  @State private pixelMap: image.PixelMap | undefined = undefined;
  @State private failed: boolean = false;
  private alive: boolean = false;
  private visible: boolean = false;
  private version: number = 0;
  private attemptedVersion: number = -1;

  aboutToAppear(): void {
    this.alive = true;
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.visible = false;
    this.version++;
    this.releaseCover();
  }

  private releaseCover(): void {
    const previous: image.PixelMap | undefined = this.pixelMap;
    this.pixelMap = undefined;
    if (previous !== undefined) {
      previous.release().catch((): void => {
        console.warn('[NativeThreeCover] PixelMap release failed');
      });
    }
  }

  private invalidateCover(): void {
    this.version++;
    this.failed = false;
    this.ensureCover();
  }

  private visibilityChanged(visible: boolean): void {
    this.visible = visible;
    this.ensureCover();
  }

  private ensureCover(): void {
    if (!this.alive || (!this.visible && this.pixelMap === undefined) || this.attemptedVersion === this.version) return;
    if (this.snapshot.length === 0 && this.scene === null) return;
    this.attemptedVersion = this.version;
    this.decodeCover(this.version);
  }

  private async decodeCover(version: number): Promise<void> {
    let source: image.ImageSource | undefined = undefined;
    let decoded: image.PixelMap | undefined = undefined;
    try {
      const png: boolean = this.snapshot.length > 0;
      if (this.snapshot.startsWith('file://')) {
        source = image.createImageSource(this.snapshot.substring(7));
      } else {
        let bytes: Uint8Array;
        if (png) {
          const prefix: string = 'data:image/png;base64,';
          if (!this.snapshot.startsWith(prefix)) throw new Error('Invalid 3D snapshot');
          bytes = new util.Base64Helper().decodeSync(this.snapshot.substring(prefix.length));
        } else {
          if (this.scene === null) return;
          // ImageSource needs intrinsic dimensions for the initial lightweight SVG placeholder.
          const svg: string = renderThreeSceneCover(this.scene, this.dark)
            .replace('<svg width="100%"', '<svg width="680" height="320"');
          bytes = util.TextEncoder.create('utf-8').encodeInto(svg);
        }
        const buffer: ArrayBuffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
        source = image.createImageSource(buffer);
      }
      let width: number = 680;
      let height: number = 320;
      if (png) {
        const info: image.ImageInfo = await source.getImageInfo();
        if (info.size.width <= 0 || info.size.height <= 0) throw new Error('Invalid 3D snapshot size');
        const scale: number = Math.min(1, 680 / info.size.width, 560 / info.size.height);
        width = Math.max(1, Math.round(info.size.width * scale));
        height = Math.max(1, Math.round(info.size.height * scale));
      }
      if (!this.alive || version !== this.version) return;
      decoded = await source.createPixelMap({ desiredSize: { width: width, height: height } });
      if (!this.alive || version !== this.version) return;
      const previous: image.PixelMap | undefined = this.pixelMap;
      this.pixelMap = decoded;
      decoded = undefined;
      if (previous !== undefined) await previous.release();
    } catch (_) {
      if (this.alive && version === this.version) this.failed = true;
    } finally {
      if (decoded !== undefined) {
        await decoded.release().catch((): void => {
          console.warn('[NativeThreeCover] Stale PixelMap release failed');
        });
      }
      if (source !== undefined) {
        await source.release().catch((): void => {
          console.warn('[NativeThreeCover] ImageSource release failed');
        });
      }
    }
  }

  build(): void {
    Stack() {
      if (this.pixelMap !== undefined) {
        Image(this.pixelMap)
          .width('100%').height(280)
          .objectFit(ImageFit.Contain)
      } else {
        Text(this.failed ? '封面暂不可用，点按操控查看' : '3D 场景预览')
          .fontSize(13).fontColor(INK3)
          .padding(20)
      }
    }
    .width('100%').height(280)
    .onVisibleAreaChange([0], (visible: boolean, ratio: number): void => {
      this.visibilityChanged(visible && ratio > 0);
    })
  }
}
