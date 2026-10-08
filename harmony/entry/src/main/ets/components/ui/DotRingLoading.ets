// Eight flat dots rotate as one native animation; no JS timer.
import { INK3 } from '../../design/tokens.ets';

interface DotPosition {
  x: number;
  y: number;
}

@Component
export struct DotRingLoading {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop color: string = INK3;
  @Prop ringSize: number = 14;
  @State angle: number = 0;

  private dotPosition(index: number): DotPosition {
    const dotSize: number = this.ringSize / 6;
    const radius: number = (this.ringSize - dotSize) / 2;
    const radians: number = index * Math.PI / 4 - Math.PI / 2;
    return {
      x: radius + Math.cos(radians) * radius,
      y: radius + Math.sin(radians) * radius,
    };
  }

  private startRotation(): void {
    animateTo({ duration: 900, curve: Curve.Linear, iterations: -1, playMode: PlayMode.Normal }, (): void => {
      this.angle = 360;
    });
  }

  build(): void {
    Stack({ alignContent: Alignment.TopStart }) {
      ForEach([0, 1, 2, 3, 4, 5, 6, 7], (index: number): void => {
        Circle({ width: this.ringSize / 6, height: this.ringSize / 6 })
          .fill(this.color)
          .opacity(0.2 + index * 0.8 / 7)
          .position(this.dotPosition(index))
      }, (index: number): string => `${index}`)
    }
    .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width(this.ringSize).height(this.ringSize)
    .rotate({ angle: this.angle })
    .onAppear((): void => { this.startRotation(); })
  }
}
