import { ACCENT, ERROR, INK, INK2, INK3, LINE, SURFACE, SURFACE2 } from '../design/tokens.ets';
import { MATERIAL_KIND_LABELS, normalizeNovelMaterialFields } from '@amber/deepread-domain';
import type { NovelMaterial } from '@amber/deepread-domain';
import { materialInjectionLabel } from './NovelMaterialMetadataFields.ets';

@Component
export struct NovelMaterialCard {
  @Prop material: NovelMaterial = { id: '', kind: 'world', title: '', content: '', enabled: true, createdAt: 0, updatedAt: 0 };
  @Prop origin: string = 'branch';
  @Prop sharedExists: boolean = false;
  @Prop expanded: boolean = false;
  @Prop busy: boolean = false;
  onExpand: () => void = (): void => {};
  onEditBranch: () => void = (): void => {};
  onEditShared: () => void = (): void => {};
  onDelete: () => void = (): void => {};
  onRestore: () => void = (): void => {};

  private originLabel(): string {
    return this.origin === 'shared' ? '继承共享底稿' : this.origin === 'override' ? '当前分支覆盖' : '当前分支资料';
  }

  build(): void {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Text(this.material.title).fontSize(15).fontWeight(FontWeight.Medium).fontColor(INK).layoutWeight(1)
        Text(this.expanded ? '收起' : '展开').fontSize(12).fontColor(INK3).height(44).padding({ left: 8, right: 8 })
      }.width('100%').constraintSize({ minHeight: 44 }).onClick((): void => { this.onExpand(); })
      Text(`${MATERIAL_KIND_LABELS[this.material.kind]}${this.material.customKind ? ` · ${this.material.customKind}` : ''} · ${this.originLabel()} · ${materialInjectionLabel(normalizeNovelMaterialFields(this.material, this.material.enabled).injectionMode)}`)
        .fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
      if (this.expanded) {
        if ((this.material.aliases?.length ?? 0) > 0) {
          Text(`别名：${this.material.aliases?.join('、') ?? ''}`).fontSize(12).fontColor(INK2).lineHeight(18).width('100%')
        }
        if ((this.material.tags?.length ?? 0) > 0) {
          Text(`标签：${this.material.tags?.join('、') ?? ''}`).fontSize(12).fontColor(INK3).lineHeight(18).width('100%')
        }
        Text(this.material.content).fontSize(13).fontColor(INK2).lineHeight(20).width('100%')
        Flex({ wrap: FlexWrap.Wrap }) {
          Text('编辑当前分支').fontSize(13).fontColor(ACCENT).height(44).padding({ left: 8, right: 8 })
            .enabled(!this.busy).onClick((): void => { this.onEditBranch(); })
          Text(this.sharedExists ? '编辑共享底稿' : '设为共享底稿').fontSize(13).fontColor(ACCENT).height(44).padding({ left: 8, right: 8 })
            .enabled(!this.busy).onClick((): void => { this.onEditShared(); })
          if (this.origin === 'override') {
            Text('恢复继承').fontSize(13).fontColor(ACCENT).height(44).padding({ left: 8, right: 8 })
              .enabled(!this.busy).onClick((): void => { this.onRestore(); })
          }
          Text(this.sharedExists ? '当前分支移除' : '删除').fontSize(13).fontColor(ERROR).height(44).padding({ left: 8, right: 8 })
            .enabled(!this.busy).onClick((): void => { this.onDelete(); })
        }.width('100%')
      }
    }.width('100%').alignItems(HorizontalAlign.Start).padding(14).backgroundColor(SURFACE).borderRadius(12)
      .border({ width: 1, color: LINE }).margin({ bottom: 10 })
  }
}

@Component
export struct NovelHiddenSharedMaterials {
  @Prop materials: NovelMaterial[] = [];
  @Prop busy: boolean = false;
  onRestore: (material: NovelMaterial) => void = (_material: NovelMaterial): void => {};

  build(): void {
    if (this.materials.length > 0) {
      Column({ space: 8 }) {
        Text('当前分支隐藏的共享资料').fontSize(13).fontColor(INK3).width('100%')
        ForEach(this.materials, (material: NovelMaterial): void => {
          Row() {
            Text(material.title).fontSize(14).fontColor(INK).layoutWeight(1)
            Text('恢复继承').fontSize(13).fontColor(ACCENT).height(44).padding({ left: 12, right: 12 })
              .enabled(!this.busy).onClick((): void => { this.onRestore(material); })
          }.width('100%').constraintSize({ minHeight: 44 })
        }, (material: NovelMaterial): string => material.id)
      }.width('100%').alignItems(HorizontalAlign.Start).padding(12).backgroundColor(SURFACE2).borderRadius(10)
    }
  }
}
