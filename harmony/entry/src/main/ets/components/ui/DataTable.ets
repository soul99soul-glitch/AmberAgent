// DataTable — 数据表格(table/DataTable.kt:289 行)
// Android 基准: SubcomposeLayout 两阶段测量 + horizontalScroll + 行内等高 + zebra
// ArkUI 差异: 无 SubcomposeLayout → 固定列宽估算 + Scroll(horizontal) + Row/Column 网格
//   列宽 = max(表头/单元格文本长度) * 字宽 + padding;可超出视口触发横向滚动
//   斑马纹: 奇数行 SURFACE2 / 偶数行 SURFACE;表头 SURFACE2 + 1dp 底边
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, ACCENT, BODY, META, LABEL } from '../../design/tokens.ets';

@Component
export struct DataTable {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop headers: string[] = [];
  @Prop rows: string[][] = [];
  @Prop sortable: boolean = false;
  onSort: (columnIndex: number) => void = (_i: number): void => {};
  @State sortColumn: number = -1;
  @State sortAsc: boolean = true;

  // 列序号数组(headers 长度为列数;保证表头/数据行同宽对齐)
  private columnIndices(): number[] {
    const arr: number[] = [];
    for (let i = 0; i < this.headers.length; i++) arr.push(i);
    return arr;
  }

  // 列宽估算: 文本最长者 * 9px + 24px padding,夹在 [80, 280]
  private columnWidth(idx: number): number {
    let maxLen: number = idx < this.headers.length ? this.headers[idx].length : 0;
    for (let r = 0; r < this.rows.length; r++) {
      if (idx < this.rows[r].length) {
        const cell: string = this.rows[r][idx];
        if (cell.length > maxLen) maxLen = cell.length;
      }
    }
    const w: number = maxLen * 9 + 24;
    if (w < 80) return 80;
    if (w > 280) return 280;
    return w;
  }

  private tableWidth(): number {
    let sum: number = 0;
    for (let c = 0; c < this.headers.length; c++) sum += this.columnWidth(c);
    return sum;
  }

  private sortIndicator(idx: number): string {
    if (this.sortColumn !== idx) return ' ↕';
    return this.sortAsc ? ' ▲' : ' ▼';
  }

  private onHeaderTap(idx: number): void {
    if (!this.sortable) return;
    if (this.sortColumn === idx) {
      this.sortAsc = !this.sortAsc;
    } else {
      this.sortColumn = idx;
      this.sortAsc = true;
    }
    this.onSort(idx);
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.headers.length === 0) {
      Column() {
        Text('暂无数据').fontSize(META).fontColor(INK3)
      }
      .width('100%').padding(16)
      .alignItems(HorizontalAlign.Center)
    } else {
      Scroll() {
        Column() {
          // 表头行
          Row() {
            ForEach(this.columnIndices(), (idx: number): void => {
              Row() {
                Text(this.headers[idx])
                  .fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK2)
                  .layoutWeight(1)
                  .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                if (this.sortable) {
                  Text(this.sortIndicator(idx))
                    .fontSize(LABEL).fontColor(this.sortColumn === idx ? ACCENT : INK4)
                }
              }
              .width(this.columnWidth(idx))
              .padding({ left: 10, right: 10, top: 10, bottom: 10 })
              .border({ width: { right: 0.5, bottom: 1 }, color: { right: LINE, bottom: LINE } })
              .onClick((): void => { this.onHeaderTap(idx); })
            }, (idx: number): string => `h-${idx}`)
          }
          .width(this.tableWidth())
          .backgroundColor(SURFACE2)

          // 数据行(斑马纹)
          ForEach(this.rows, (row: string[], rIdx: number): void => {
            Row() {
              ForEach(this.columnIndices(), (cIdx: number): void => {
                Text(cIdx < row.length ? row[cIdx] : '')
                  .fontSize(BODY).fontColor(INK)
                  .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                  .width(this.columnWidth(cIdx))
                  .padding({ left: 10, right: 10, top: 8, bottom: 8 })
                  .border({ width: { right: 0.5, bottom: 0.5 }, color: { right: LINE, bottom: LINE } })
              }, (cIdx: number): string => `c-${rIdx}-${cIdx}`)
            }
            .width(this.tableWidth())
            .backgroundColor(rIdx % 2 === 1 ? SURFACE2 : SURFACE)
          }, (row: string[], rIdx: number): string => `r-${rIdx}`)
        }
        .width(this.tableWidth())
      }
      .scrollable(ScrollDirection.Horizontal)
      .scrollBar(BarState.Auto)
      .width('100%')
      .border({ width: 1, color: LINE })
      .borderRadius(8)
      .clip(true)
    }
  }
}
