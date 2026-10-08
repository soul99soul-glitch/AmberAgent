import type { DeepReadDiagram as DiagramModel, DiagramNode, DiagramEdge } from '@amber/deepread-domain';
import { INK, INK3, ACCENT, SURFACE2, LINE } from '../design/tokens.ets';
import { FONT_SANS, FONT_SERIF } from '../design/fonts.ets';
import { DeepReadDesign as D } from '../design/DeepReadDesign.ets';
import { getProductKind } from '../platform_impl/ProductIdentity.ets';

/** 图解以节点和关联的完整文字表达呈现，保留作者的分组与说明。 */
@Component
export struct DeepReadDiagram {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @Prop diagram: DiagramModel | null = null;
  @Prop fontScale: number = 1;
  @Prop fontSerif: boolean = false;

  private family(): string {
    return this.fontSerif ? FONT_SERIF : FONT_SANS;
  }

  private nodeLabel(diagram: DiagramModel, id: string): string | null {
    const node: DiagramNode | undefined = diagram.nodes.find((item: DiagramNode) => item.id === id);
    return node === undefined ? null : node.label;
  }

  private visibleEdges(diagram: DiagramModel): DiagramEdge[] {
    return diagram.edges.filter((edge: DiagramEdge) =>
      this.nodeLabel(diagram, edge.from) !== null && this.nodeLabel(diagram, edge.to) !== null);
  }

  private relationship(diagram: DiagramModel, edge: DiagramEdge): string {
    return `${this.nodeLabel(diagram, edge.from)} → ${this.nodeLabel(diagram, edge.to)}`;
  }

  @Builder
  DiagramBody(diagram: DiagramModel): void {
    Column({ space: 12 }) {
      Text(diagram.title)
        .width('100%')
        .fontSize(20 * this.fontScale)
        .fontFamily(this.family())
        .fontWeight(FontWeight.Medium)
        .fontColor(getProductKind() === 'deepread' ? D.ink : INK)
        .lineHeight(28 * this.fontScale)
      if (diagram.reason !== null && diagram.reason.trim().length > 0) {
        Text(diagram.reason)
          .width('100%')
          .fontSize(14 * this.fontScale)
          .fontFamily(this.family())
          .fontColor(getProductKind() === 'deepread' ? D.muted : INK3)
          .lineHeight(22 * this.fontScale)
      }
      ForEach(diagram.nodes, (node: DiagramNode, index: number) => {
        Row({ space: 12 }) {
          Text(`${index + 1}`)
            .fontSize(14 * this.fontScale)
            .fontFamily(this.family())
            .fontColor(getProductKind() === 'deepread' ? D.accent : ACCENT)
            .width(28 * this.fontScale)
            .height(28 * this.fontScale)
            .textAlign(TextAlign.Center)
            .borderRadius(14 * this.fontScale)
            .border({ width: 1, color: getProductKind() === 'deepread' ? D.rule : LINE })
          Column({ space: 6 }) {
            Text(node.label)
              .width('100%')
              .fontSize(16 * this.fontScale)
              .fontFamily(this.family())
              .fontWeight(FontWeight.Medium)
              .fontColor(getProductKind() === 'deepread' ? D.ink : INK)
              .lineHeight(24 * this.fontScale)
            if (node.group !== null && node.group.trim().length > 0) {
              Text(node.group)
                .width('100%')
                .fontSize(13 * this.fontScale)
                .fontFamily(this.family())
                .fontColor(getProductKind() === 'deepread' ? D.accent : ACCENT)
                .lineHeight(20 * this.fontScale)
            }
            if (node.note !== null && node.note.trim().length > 0) {
              Text(node.note)
                .width('100%')
                .fontSize(14 * this.fontScale)
                .fontFamily(this.family())
                .fontColor(getProductKind() === 'deepread' ? D.muted : INK3)
                .lineHeight(22 * this.fontScale)
            }
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
        }
        .width('100%')
        .alignItems(VerticalAlign.Top)
        .padding(getProductKind() === 'deepread' ? { left: 0, right: 0, top: 10, bottom: 10 } : { left: 14, right: 14, top: 14, bottom: 14 })
        .backgroundColor(getProductKind() === 'deepread' ? Color.Transparent : SURFACE2)
        .borderRadius(getProductKind() === 'deepread' ? 0 : 12)
      }, (node: DiagramNode) => node.id)
      if (this.visibleEdges(diagram).length > 0) {
        Column({ space: 10 }) {
          ForEach(this.visibleEdges(diagram), (edge: DiagramEdge) => {
            Column({ space: 4 }) {
              Text(this.relationship(diagram, edge))
                .width('100%')
                .fontSize(14 * this.fontScale)
                .fontFamily(this.family())
                .fontColor(getProductKind() === 'deepread' ? D.ink : INK)
                .lineHeight(22 * this.fontScale)
              if (edge.label !== null && edge.label.trim().length > 0) {
                Text(edge.label)
                  .width('100%')
                  .fontSize(14 * this.fontScale)
                  .fontFamily(this.family())
                  .fontColor(getProductKind() === 'deepread' ? D.muted : INK3)
                  .lineHeight(22 * this.fontScale)
              }
            }
            .width('100%')
            .alignItems(HorizontalAlign.Start)
          }, (edge: DiagramEdge, index: number) => `${edge.from}:${edge.to}:${index}`)
        }
        .width('100%')
        .padding({ left: 14, right: 14, top: 12, bottom: 12 })
        .border({ width: 1, color: getProductKind() === 'deepread' ? D.rule : LINE })
        .borderRadius(getProductKind() === 'deepread' ? 0 : 12)
        .alignItems(HorizontalAlign.Start)
      }
      if (diagram.caption !== null && diagram.caption.trim().length > 0) {
        Text(diagram.caption)
          .width('100%')
          .fontSize(13 * this.fontScale)
          .fontFamily(this.family())
          .fontColor(getProductKind() === 'deepread' ? D.muted : INK3)
          .lineHeight(20 * this.fontScale)
      }
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.diagram !== null) {
      this.DiagramBody(this.diagram)
    }
  }
}
