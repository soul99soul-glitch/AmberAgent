// WorkspaceFileSheet — workspace 文件管理 sheet(G6-S1,对齐 Android WorkspaceFileSheet.kt:600 行)
// Android 基准: ModalBottomSheet + HeaderRow + Tab(最近/目录) + HorizontalPager
//   RecentFilesList / DirectoryBrowser / Breadcrumb / FileRow / FolderRow / ExtBadge / EmptyState
//   长按 → AlertDialog(分享/删除) → 删除确认 AlertDialog
// Harmony 降级: 无 SAF/分享意图/Pager → 单列表 + 长按 ContextMenu(删除确认)
//   搜索(按 name)+ 排序(name/size/date)+ 上传占位(无真实文件选择器)
// ArkUI 规则:
//   - 无 as unknown / 无对象展开 / 无 any/unknown
//   - @Prop 无保留字(name/path/size → 父用字段名,本组件 @Prop 为 files/show)
//   - ForEach/@Builder 内不声明 const/let(UI 树优先;辅助逻辑外移到 VM 纯函数)
//   - 排序/过滤结果用 @State 缓存,在事件回调里重算(不放在 build 内)

import { ACCENT, ACCENT_LIGHT, BODY, ERROR, INK, INK2, INK3, INK4, LABEL, LINE, META, OVERLAY, RADIUS, SURFACE, SURFACE2, TITLE } from '../design/tokens.ets';
import {
  type WorkspaceFileInfo, type WorkspaceFileSortKey,
  searchFiles, sortFiles, formatFileSize, formatFileTime, extOf,
} from './WorkspaceFileVM.ets';
import { Icon } from './ui/Icon.ets';

// 文件类型图标(对齐 Android File02 + extColor;HarmonyOS 用 emoji 占位)
function fileIcon(name: string): string {
  const ext: string = extOf(name);
  if (ext === 'txt' || ext === 'md' || ext === 'markdown' || ext === 'log') return '📄';
  if (ext === 'csv' || ext === 'xlsx' || ext === 'xls') return '📊';
  if (ext === 'pdf') return '📑';
  if (ext === 'png' || ext === 'jpg' || ext === 'jpeg' || ext === 'gif' || ext === 'webp') return '🖼️';
  if (ext === 'zip' || ext === 'gz' || ext === 'tar' || ext === 'rar') return '📦';
  if (ext === 'json') return '📝';
  if (ext === 'html' || ext === 'htm' || ext === 'xml' || ext === 'yml' || ext === 'yaml') return '📄';
  return '📄';
}

// 文件类型徽章色(对齐 Android extColor)
function extBadgeColor(name: string): string {
  const ext: string = extOf(name);
  if (ext === 'md') return ACCENT;
  if (ext === 'json') return '#7A5BA6';
  if (ext === 'html' || ext === 'htm') return '#3B7DD8';
  if (ext === 'png' || ext === 'jpg' || ext === 'jpeg' || ext === 'gif' || ext === 'webp') return '#3B7DD8';
  if (ext === 'pdf') return ERROR;
  return INK3;
}

@Component
export struct WorkspaceFileSheet {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @Prop @Watch('recompute') files: WorkspaceFileInfo[] = [];
  @Prop show: boolean = false;
  @Prop showUploadPlaceholder: boolean = true;
  onDismiss: () => void = (): void => {};
  onOpenFile: (file: WorkspaceFileInfo) => void = (_f: WorkspaceFileInfo): void => {};
  onDeleteFile: (file: WorkspaceFileInfo) => void = (_f: WorkspaceFileInfo): void => {};

  @State query: string = '';
  @State sortBy: WorkspaceFileSortKey = 'date';
  @State sortMenuOpen: boolean = false;
  @State visibleFiles: WorkspaceFileInfo[] = [];
  @State pendingDelete: WorkspaceFileInfo | null = null;

  // 父级归档/删除后更新 files 引用，保持列表与磁盘刷新结果一致。
  private recompute(): void {
    const filtered: WorkspaceFileInfo[] = searchFiles(this.files, this.query);
    this.visibleFiles = sortFiles(filtered, this.sortBy);
  }

  aboutToAppear(): void {
    this.recompute();
  }

  // 保留已有手动刷新入口；正常刷新由 files watcher 驱动。
  refresh(): void {
    this.recompute();
  }

  private onQueryChange(v: string): void {
    this.query = v;
    this.recompute();
  }

  private onSortChange(key: WorkspaceFileSortKey): void {
    this.sortBy = key;
    this.sortMenuOpen = false;
    this.recompute();
  }

  private sortLabel(): string {
    if (this.sortBy === 'name') return '名称';
    if (this.sortBy === 'size') return '大小';
    return '日期';
  }

  private confirmDelete(): void {
    const target: WorkspaceFileInfo | null = this.pendingDelete;
    this.pendingDelete = null;
    if (target !== null) {
      this.onDeleteFile(target);
    }
  }

  private cancelDelete(): void {
    this.pendingDelete = null;
  }

  @Builder
  HeaderRow(): void {
    Row() {
      Text('文件')
        .fontSize(TITLE)
        .fontWeight(FontWeight.Medium)
        .fontColor(INK)
        .layoutWeight(1)
      Text('✕')
        .fontSize(16)
        .fontColor(INK3)
        .width(32).height(32)
        .textAlign(TextAlign.Center)
        .onClick((): void => { this.onDismiss(); })
    }
    .width('100%')
    .padding({ left: 16, right: 12, top: 12, bottom: 8 })
  }

  @Builder
  ToolBar(): void {
    Column() {
      // 搜索 + 排序
      Row() {
        TextInput({ text: this.query, placeholder: '搜索文件名' })
          .fontSize(BODY)
          .fontColor(INK)
          .backgroundColor(SURFACE2)
          .borderRadius(8)
          .layoutWeight(1)
          .padding({ top: 8, bottom: 8, left: 12, right: 12 })
          .onChange((v: string): void => { this.onQueryChange(v); })

        Text(`排序: ${this.sortLabel()} ▾`)
          .fontSize(META)
          .fontColor(ACCENT)
          .padding({ left: 10, right: 10, top: 8, bottom: 8 })
          .backgroundColor(ACCENT_LIGHT)
          .borderRadius(50)
          .margin({ left: 8 })
          .onClick((): void => { this.sortMenuOpen = !this.sortMenuOpen; })
      }
      .width('100%')

      // 排序下拉菜单
      if (this.sortMenuOpen) {
        Column() {
          this.sortItem('name', '名称')
          this.sortItem('size', '大小')
          this.sortItem('date', '日期')
        }
        .width(120)
        .backgroundColor(SURFACE)
        .borderRadius(8)
        .border({ width: 1, color: LINE })
        .margin({ top: 4, bottom: 4 })
        .alignItems(HorizontalAlign.Start)
      }
    }
    .width('100%')
    .padding({ left: 16, right: 16, bottom: 8 })
  }

  @Builder
  sortItem(key: WorkspaceFileSortKey, label: string): void {
    Row() {
      Text(label)
        .fontSize(META)
        .fontColor(this.sortBy === key ? ACCENT : INK2)
        .layoutWeight(1)
      if (this.sortBy === key) {
        Text('✓').fontSize(META).fontColor(ACCENT)
      }
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 8, bottom: 8 })
    .onClick((): void => { this.onSortChange(key); })
  }

  @Builder
  FileRow(f: WorkspaceFileInfo): void {
    Row() {
      // 类型图标
      Text(fileIcon(f.name))
        .fontSize(20)
        .width(32).height(32)
        .textAlign(TextAlign.Center)

      // 文件名 + 元信息
      Column() {
        Text(f.name)
          .fontSize(BODY)
          .fontColor(INK)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
        Text(`${formatFileSize(f.size)} · ${formatFileTime(f.modifiedAt)}`)
          .fontSize(LABEL)
          .fontColor(INK3)
          .maxLines(1)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .margin({ top: 2 })
      }
      .layoutWeight(1)
      .margin({ left: 10, right: 8 })
      .alignItems(HorizontalAlign.Start)

      // 扩展名徽章
      this.ExtBadge(f.name)
    }
    .width('100%')
    .padding({ left: 12, right: 12, top: 10, bottom: 10 })
    .backgroundColor(SURFACE)
    .borderRadius(8)
    .margin({ bottom: 6 })
    .onClick((): void => { this.onOpenFile(f); })
    .bindContextMenu(this.RowContextMenu(f), ResponseType.LongPress)
  }

  @Builder
  ExtBadge(name: string): void {
    if (extOf(name).length > 0) {
      Text(extOf(name).toUpperCase().substring(0, Math.min(4, extOf(name).length)))
        .fontSize(LABEL)
        .fontColor(extBadgeColor(name))
        .padding({ left: 6, right: 6, top: 2, bottom: 2 })
        .backgroundColor(SURFACE2)
        .borderRadius(4)
    }
  }

  @Builder
  RowContextMenu(f: WorkspaceFileInfo): void {
    Menu() {
      MenuItem({ content: '删除' })
        .onClick((): void => { this.pendingDelete = f; })
    }
  }

  @Builder
  EmptyState(): void {
    Column() {
      Icon({ name: 'folder', iconSize: 40, color: INK4 })
      Text(this.files.length === 0 ? '还没有 workspace 文件' : '无匹配文件')
        .fontSize(BODY)
        .fontColor(INK3)
        .margin({ top: 12 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Center)
    .padding({ top: 48, bottom: 48 })
  }

  @Builder
  DeleteConfirm(): void {
    if (this.pendingDelete !== null) {
      Stack() {
        Column() {
          Text('删除文件')
            .fontSize(BODY)
            .fontWeight(FontWeight.Medium)
            .fontColor(INK)
            .width('100%')
            .margin({ bottom: 8 })
          Text(`将从 workspace 中永久删除「${this.pendingDelete!.name}」,无法撤销。`)
            .fontSize(META)
            .fontColor(INK2)
            .width('100%')
            .lineHeight(20)
            .margin({ bottom: 16 })

          Row() {
            Text('取消')
              .fontSize(BODY)
              .fontColor(INK3)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 10, bottom: 10 })
              .onClick((): void => { this.cancelDelete(); })
            Text('删除')
              .fontSize(BODY)
              .fontColor(ERROR)
              .fontWeight(FontWeight.Medium)
              .layoutWeight(1)
              .textAlign(TextAlign.Center)
              .padding({ top: 10, bottom: 10 })
              .onClick((): void => { this.confirmDelete(); })
          }
          .width('100%')
          .border({ width: { top: 0.5 }, color: LINE })
        }
        .width('80%')
        .backgroundColor(SURFACE)
        .borderRadius(14)
        .padding({ left: 20, right: 20, top: 20, bottom: 0 })
      }
      .width('100%')
      .height('100%')
      .backgroundColor(OVERLAY)
      .alignContent(Alignment.Center)
    }
  }

  build(): void {
    if (this.themeEpoch >= 0 && this.show) {
      Stack() {
        // 底层 sheet
        Column() {
          this.HeaderRow()
          this.ToolBar()

          // 列表 / 空态
          Scroll() {
            Column() {
              if (this.visibleFiles.length === 0) {
                this.EmptyState()
              }
              ForEach(this.visibleFiles, (f: WorkspaceFileInfo): void => {
                this.FileRow(f)
              }, (f: WorkspaceFileInfo): string => f.path)
            }
            .width('100%')
          }
        .align(Alignment.TopStart)
          .layoutWeight(1)
          .padding({ left: 12, right: 12, bottom: 8 })

          // 旧宿主占位开关；真实 Chat 文件面板不展示无功能入口。
          if (this.showUploadPlaceholder) {
          Row() {
            Row() {
              Icon({ name: 'upload', iconSize: BODY, color: INK3 })
              Text(' 上传文件(占位)')
                .fontSize(BODY)
                .fontColor(INK3)
            }
            .padding({ left: 14, right: 14, top: 10, bottom: 10 })
            .backgroundColor(SURFACE2)
            .borderRadius(50)
          }
          .width('100%')
          .justifyContent(FlexAlign.Center)
          .padding({ top: 4, bottom: 16 })
          }
        }
        .width('100%')
        .height('78%')
        .backgroundColor(SURFACE)
        .borderRadius({ topLeft: 16, topRight: 16 })

        // 顶层:删除确认对话框
        this.DeleteConfirm()
      }
      .width('100%')
      .height('100%')
      .backgroundColor(OVERLAY)
      .alignContent(Alignment.Bottom)
    }
  }
}
