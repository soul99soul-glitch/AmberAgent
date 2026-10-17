import type { SmartUiNode, SmartUiComponentRow, SmartUiOption } from '@amber/chat-domain';
import { ACCENT, INK, INK3, LINE, SURFACE2, accentSoftBg } from '../design/tokens.ets';
import { FONT_SANS, FONT_MONO } from '../design/fonts.ets';

const EMPTY_NODE: SmartUiNode = { id: 'empty', parent: null, type: 'column' };
const EMPTY_ROW: SmartUiComponentRow = { label: '' };

@Component
export struct NativeSmartUiData {
  @Prop node: SmartUiNode = EMPTY_NODE;
  @Prop fontSize: number = 16;
  @Prop fontFamily: string = FONT_SANS;
  @Prop ink: string = INK;
  @Prop secondaryInk: string = INK3;
  @Prop accent: string = ACCENT;
  @Prop surface: string = SURFACE2;
  @State private chartWidth: number = 240;

  private kind(): string { return this.node.component?.kind ?? ''; }
  private textSize(base: number): number { return Math.max(10, base * this.fontSize / 16); }
  private rows(): SmartUiComponentRow[] {
    const rows: SmartUiComponentRow[] = this.node.component?.rows ?? [];
    if (rows.length > 0 || this.kind() !== 'list') return rows;
    return (this.node.component?.items ?? []).map((label: string): SmartUiComponentRow => ({ label }));
  }
  private row(index: number): SmartUiComponentRow { return this.rows()[index] ?? EMPTY_ROW; }
  private indices(): number[] { return this.rows().map((_row: SmartUiComponentRow, index: number): number => index); }
  private cells(index: number): string[] {
    const row: SmartUiComponentRow = this.row(index);
    return row.cells ?? [row.label, row.detail ?? (row.value === undefined ? '' : String(row.value))];
  }
  private cellWidth(): number { return Math.max(100, this.textSize(110)); }
  private cellsWidth(): number {
    let count: number = this.node.component?.items?.length ?? 2;
    for (const row of this.rows()) count = Math.max(count, row.cells?.length ?? 2);
    return count * this.cellWidth();
  }
  private options(): SmartUiOption[] { return this.node.options ?? []; }
  private option(index: number): SmartUiOption { return this.options()[index] ?? { label: '', value: '' }; }
  private optionIndices(): number[] { return this.options().map((_option: SmartUiOption, index: number): number => index); }
  private chartMin(): number {
    let minimum: number = 0;
    for (const row of this.rows()) minimum = Math.min(minimum, row.value ?? 0);
    return minimum;
  }
  private chartMax(): number {
    let maximum: number = 0;
    for (const row of this.rows()) maximum = Math.max(maximum, row.value ?? 0);
    return maximum;
  }
  private ratio(value: number): number {
    const range: number = this.chartMax() - this.chartMin();
    return range === 0 ? 0.5 : (value - this.chartMin()) / range;
  }
  private points(): number[][] {
    const result: number[][] = [];
    const rows: SmartUiComponentRow[] = this.rows();
    for (let index = 0; index < rows.length; index++) {
      const x: number = rows.length === 1 ? this.chartWidth / 2 : 8 + index * (this.chartWidth - 16) / (rows.length - 1);
      result.push([x, 140 - this.ratio(rows[index].value ?? 0) * 128]);
    }
    return result;
  }
  private barWidth(index: number): string {
    const value: number = this.row(index).value ?? 0;
    const range: number = this.chartMax() - this.chartMin();
    return `${range === 0 ? 0 : Math.abs(value) / range * 100}%`;
  }
  private barLeft(index: number): string {
    const value: number = this.row(index).value ?? 0;
    const left: number = Math.min(this.ratio(0), this.ratio(value));
    return `${left * 100}%`;
  }
  private chartDescription(): string {
    return this.rows().map((row: SmartUiComponentRow): string => `${row.label}：${row.value ?? 0}`).join('，');
  }

  @Builder
  private CellRow(cells: string[], heading: boolean): void {
    Row() {
      ForEach(cells, (cell: string, index: number): void => {
        Text(cell).width(this.cellWidth()).padding(10).fontSize(this.textSize(heading ? 12 : 14))
          .fontFamily(this.fontFamily).lineHeight(this.textSize(20)).fontColor(heading ? this.secondaryInk : this.ink)
          .fontWeight(heading ? FontWeight.Medium : FontWeight.Normal);
      }, (_cell: string, index: number): string => String(index));
    }.width(this.cellsWidth()).alignItems(VerticalAlign.Top).backgroundColor(heading ? this.surface : Color.Transparent);
  }

  @Builder
  private Table(): void {
    Scroll() {
      Column() {
        if ((this.node.component?.items ?? []).length > 0) this.CellRow(this.node.component?.items ?? [], true);
        ForEach(this.indices(), (index: number): void => {
          this.CellRow(this.cells(index), false);
          Divider().color(LINE);
        }, (index: number): string => `${this.node.id}:${index}`);
      }.width(this.cellsWidth()).alignItems(HorizontalAlign.Start);
    }.width('100%').scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Auto)
      .border({ width: 1, color: LINE }).borderRadius(8);
  }

  @Builder
  private DataRows(): void {
    ForEach(this.indices(), (index: number): void => {
      Row({ space: 10 }) {
        if (this.kind() === 'timeline' || this.kind() === 'steps') {
          Column({ space: 4 }) {
            Text(this.kind() === 'steps' ? String(index + 1) : '•')
              .width(28).height(28).textAlign(TextAlign.Center).fontSize(this.textSize(15)).fontColor(this.accent)
              .backgroundColor(accentSoftBg(this.accent)).borderRadius(14);
            if (index < this.rows().length - 1) {
              Divider().vertical(true).height(20).color(LINE);
            }
          }.flexShrink(0);
        }
        Column({ space: 4 }) {
          Text(this.row(index).label).width('100%').fontSize(this.textSize(15)).fontFamily(this.fontFamily)
            .fontColor(this.ink).lineHeight(this.textSize(21));
          if ((this.row(index).detail ?? '').length > 0) {
            Text(this.row(index).detail ?? '').width('100%').fontSize(this.textSize(13)).fontFamily(this.fontFamily)
              .fontColor(this.secondaryInk).lineHeight(this.textSize(19));
          }
          if (this.row(index).value !== undefined) {
            Text(String(this.row(index).value ?? 0)).fontSize(this.textSize(13)).fontFamily(FONT_MONO).fontColor(this.accent);
          }
        }.layoutWeight(1).alignItems(HorizontalAlign.Start);
      }.width('100%').alignItems(VerticalAlign.Top).padding({ top: 8, bottom: 8 });
      if (this.kind() === 'list' && index < this.rows().length - 1) Divider().color(LINE);
    }, (index: number): string => `${this.node.id}:${index}`);
  }

  @Builder
  private Comparison(): void {
    ForEach(this.optionIndices(), (index: number): void => {
      Column({ space: 6 }) {
        Text(this.option(index).label).width('100%').fontSize(this.textSize(15)).fontWeight(FontWeight.Medium)
          .fontFamily(this.fontFamily).fontColor(this.ink).lineHeight(this.textSize(21));
        if ((this.option(index).detail ?? '').length > 0) {
          Text(this.option(index).detail ?? '').width('100%').fontSize(this.textSize(13)).fontFamily(this.fontFamily)
            .fontColor(this.secondaryInk).lineHeight(this.textSize(19));
        }
      }.width('100%').padding(12).backgroundColor(this.surface).borderRadius(10).alignItems(HorizontalAlign.Start);
    }, (index: number): string => `${this.node.id}:option:${index}`);
    if (this.options().length === 0) this.DataRows();
  }

  @Builder
  private Chart(): void {
    if (this.kind() === 'lineChart') {
      Stack({ alignContent: Alignment.TopStart }) {
        Polyline({ width: this.chartWidth, height: 152 }).points(this.points())
          .fillOpacity(0).stroke(this.accent).strokeWidth(2);
        ForEach(this.indices(), (index: number): void => {
          Row().width(6).height(6).borderRadius(3).backgroundColor(this.accent)
            .position({ x: this.points()[index][0] - 3, y: this.points()[index][1] - 3 });
        }, (index: number): string => `${this.node.id}:point:${index}`);
      }.width('100%').height(152).border({ width: { bottom: 1 }, color: LINE })
        .onAreaChange((_before: Area, after: Area): void => { this.chartWidth = Math.max(40, Number(after.width)); })
        .accessibilityGroup(true).accessibilityText(this.chartDescription());
      Text(this.rows().map((row: SmartUiComponentRow): string => row.label).join(' → '))
        .width('100%').fontSize(this.textSize(12)).fontColor(this.secondaryInk);
    } else {
      ForEach(this.indices(), (index: number): void => {
        Column({ space: 4 }) {
          Row() {
            Text(this.row(index).label).layoutWeight(1).fontSize(this.textSize(13)).fontColor(this.ink);
            Text(String(this.row(index).value ?? 0)).fontSize(this.textSize(13)).fontFamily(FONT_MONO).fontColor(this.accent);
          }.width('100%');
          Stack({ alignContent: Alignment.TopStart }) {
            Row().width(this.barWidth(index)).height(12).borderRadius(3).backgroundColor(this.accent)
              .position({ x: this.barLeft(index), y: 0 });
            Divider().vertical(true).height(12).color(this.secondaryInk).position({ x: `${this.ratio(0) * 100}%`, y: 0 });
          }.width('100%').height(12).borderRadius(3).backgroundColor(this.surface);
        }.width('100%').alignItems(HorizontalAlign.Start).accessibilityGroup(true)
          .accessibilityText(`${this.row(index).label}：${this.row(index).value ?? 0}`);
      }, (index: number): string => `${this.node.id}:bar:${index}`);
    }
    Row() {
      Text(String(this.chartMin())).fontSize(this.textSize(11)).fontColor(this.secondaryInk);
      Blank();
      Text(String(this.chartMax())).fontSize(this.textSize(11)).fontColor(this.secondaryInk);
    }.width('100%');
  }

  build(): void {
    Column({ space: 8 }) {
      if (this.kind() === 'metric') {
        Text(this.node.component?.value === undefined ? this.node.component?.text ?? '' : String(this.node.component?.value))
          .width('100%').fontSize(this.textSize(32)).fontFamily(FONT_MONO).fontWeight(FontWeight.Medium).fontColor(this.ink);
        if (this.node.component?.value !== undefined && (this.node.component?.text ?? '').length > 0) {
          Text(this.node.component?.text ?? '').fontSize(this.textSize(12)).fontColor(this.secondaryInk);
        }
      } else if (this.kind() === 'table') {
        this.Table();
      } else if (this.kind() === 'comparison') {
        this.Comparison();
      } else if (this.kind() === 'barChart' || this.kind() === 'lineChart') {
        this.Chart();
      } else {
        this.DataRows();
      }
    }.width('100%').alignItems(HorizontalAlign.Start);
  }
}
