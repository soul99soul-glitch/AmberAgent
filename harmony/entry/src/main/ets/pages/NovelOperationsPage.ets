import { router, KeyboardAvoidMode } from '@kit.ArkUI';
import { ACCENT, BG, ERROR, INK, INK3, SURFACE2 } from '../design/tokens.ets';
import { PageTitleBar } from '../components/PageTemplate.ets';
import { NovelApprovalShell } from '../components/NovelApprovalShell.ets';
import { getNovelCreation, getNovelWorkspaceService } from '../di/AppContainer.ets';
import { novelChapterOrdinal, novelChapterParagraphs, materialSuggestionChapterDigest } from '@amber/deepread-domain';
import type { NovelChapter, NovelProject, NovelWorkspaceCas, NovelWorkspaceSnapshot, DurableWorkspaceProposal, NovelChapterParagraph, WorkspaceProposalPreview } from '@amber/deepread-domain';
import type { NovelManuscriptOperationArgs, NovelManuscriptOperationKind } from '../platform_impl/NovelWorkspaceService.ets';

interface OperationsParams { projectId?: string; chapterId?: string; proposalId?: string; }

@Entry
@Component
struct NovelOperationsPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @State project: NovelProject | null = null;
  @State proposals: DurableWorkspaceProposal[] = [];
  @State mode: NovelManuscriptOperationKind = 'revise_chapter';
  @State chapterId: string = '';
  @State focusedProposalId: string = '';
  @State selectedIds: string[] = [];
  @State startText: string = '1';
  @State endText: string = '1';
  @State replacement: string = '';
  @State countText: string = '1';
  @State busy: boolean = false;
  @State loading: boolean = true;
  @State error: string = '';
  @State notice: string = '';
  private projectId: string = '';
  private cas: NovelWorkspaceCas | null = null;
  private alive: boolean = false;
  private hidden: boolean = false;
  private token: number = 0;
  private keyboardActive: boolean = false;
  private keyboardMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;
  private replacementController: TextAreaController = new TextAreaController();
  private startController: TextInputController = new TextInputController();
  private endController: TextInputController = new TextInputController();
  private countController: TextInputController = new TextInputController();

  aboutToAppear(): void {
    this.alive = true;
    const params: OperationsParams = router.getParams() as OperationsParams;
    this.projectId = params?.projectId ?? '';
    this.chapterId = params?.chapterId ?? '';
    this.focusedProposalId = params?.proposalId ?? '';
    this.reload();
  }
  onPageShow(): void {
    if (!this.keyboardActive) {
      this.keyboardMode = this.getUIContext().getKeyboardAvoidMode();
      this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
      this.keyboardActive = true;
    }
    if (this.hidden) { this.hidden = false; this.reload(); }
  }
  onPageHide(): void { this.hidden = true; this.token++; this.restoreKeyboard(); }
  aboutToDisappear(): void { this.alive = false; this.token++; this.restoreKeyboard(); }
  onBackPress(): boolean { return this.busy; }
  private restoreKeyboard(): void {
    if (this.keyboardActive) this.getUIContext().setKeyboardAvoidMode(this.keyboardMode);
    this.keyboardActive = false;
  }
  private async reload(): Promise<void> {
    const token: number = ++this.token;
    this.loading = true;
    try {
      const snapshot: NovelWorkspaceSnapshot = await getNovelCreation().readWorkspaceSnapshot(this.projectId);
      const proposals: DurableWorkspaceProposal[] = await getNovelCreation().workspaceProposals(this.projectId);
      if (!this.alive || token !== this.token) return;
      const oldChapter: NovelChapter | null = this.selectedChapter();
      const nextChapter: NovelChapter | undefined = snapshot.project.chapters.find((item: NovelChapter): boolean => item.id === this.chapterId);
      if (this.project !== null && (this.replacement.length > 0 || this.selectedIds.length > 0) &&
        (this.cas?.branchId !== snapshot.status.cas.branchId || (this.replacement.length > 0 && oldChapter?.content !== nextChapter?.content))) {
        throw new Error('分支或改写原文已变化，输入已保留。请重新读取并核对操作范围。');
      }
      this.project = snapshot.project;
      this.cas = snapshot.status.cas;
      this.proposals = proposals.filter((item: DurableWorkspaceProposal): boolean => item.review !== undefined);
      if (!this.chapters().some((item: NovelChapter): boolean => item.id === this.chapterId)) this.chapterId = this.chapters()[0]?.id ?? '';
      this.selectedIds = this.selectedIds.filter((id: string): boolean => this.chapters().some((item: NovelChapter): boolean => item.id === id));
    } catch (error) { if (this.alive && token === this.token) this.error = String(error); }
    finally { if (this.alive && token === this.token) this.loading = false; }
  }
  private confirmReload(): void {
    if (this.busy) return;
    if (this.replacement.length === 0 && this.selectedIds.length === 0) { this.error = ''; this.reload(); return; }
    AlertDialog.show({ title: '重新读取正文？', message: '当前未提交的替换正文与章节选择将清空。已生成的修改建议保留。',
      primaryButton: { value: '继续编辑', action: (): void => {} },
      secondaryButton: { value: '重新读取', action: (): void => {
        if (!this.alive || this.hidden || this.busy) return;
        this.replacement = ''; this.selectedIds = []; this.error = ''; this.reload();
      } } });
  }
  private chapters(): NovelChapter[] { return this.project?.chapters.filter((item: NovelChapter): boolean => !item.discarded) ?? []; }
  private selectedChapter(): NovelChapter | null { return this.chapters().find((item: NovelChapter): boolean => item.id === this.chapterId) ?? null; }
  private paragraphs(): NovelChapterParagraph[] { return novelChapterParagraphs(this.selectedChapter()?.content ?? ''); }
  private validOrdinal(text: string): number { return /^[1-9][0-9]*$/.test(text.trim()) ? Number(text.trim()) : 0; }
  private selectedSource(): string {
    const first: NovelChapterParagraph | undefined = this.paragraphs().find((item: NovelChapterParagraph): boolean => item.index === this.validOrdinal(this.startText));
    const last: NovelChapterParagraph | undefined = this.paragraphs().find((item: NovelChapterParagraph): boolean => item.index === this.validOrdinal(this.endText));
    if (first === undefined || last === undefined || last.index < first.index) return '';
    return (this.selectedChapter()?.content ?? '').slice(first.start, last.end);
  }
  private ready(): boolean {
    if (this.busy || this.loading || this.cas === null) return false;
    if (this.mode === 'delete_chapters') return this.selectedIds.length > 0;
    if (this.mode === 'revert_recent_chapters') return this.validOrdinal(this.countText) > 0 && this.validOrdinal(this.countText) <= this.chapters().length;
    return this.selectedSource().length > 0 && this.replacement.trim().length > 0;
  }
  private stage(): void {
    if (this.busy || this.loading) return;
    this.startController.stopEditing(); this.endController.stopEditing();
    this.countController.stopEditing(); this.replacementController.stopEditing();
    const token: number = this.token;
    setTimeout((): void => { if (this.alive && token === this.token && this.ready()) this.submit(); }, 0);
  }
  private async submit(): Promise<void> {
    if (!this.ready() || this.cas === null) return;
    const args: NovelManuscriptOperationArgs = {};
    if (this.mode === 'delete_chapters') args.chapter_ids = this.selectedIds.slice();
    else if (this.mode === 'revert_recent_chapters') args.chapter_count = this.validOrdinal(this.countText);
    else {
      const chapter: NovelChapter | null = this.selectedChapter();
      if (chapter === null) return;
      args.chapter_id = chapter.id; args.start_paragraph = this.validOrdinal(this.startText);
      args.end_paragraph = this.validOrdinal(this.endText); args.new_text = this.replacement;
      args.expected_text = this.selectedSource(); args.source_digest = materialSuggestionChapterDigest(chapter);
    }
    this.busy = true; this.error = ''; this.notice = '';
    try {
      await getNovelWorkspaceService().proposeManuscriptOperation(this.projectId, this.mode, args, this.cas);
      if (this.alive) { await this.reload(); this.notice = '建议已生成，请核对下方原文与替换内容后确认。'; }
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private async resolve(proposal: DurableWorkspaceProposal, accept: boolean): Promise<void> {
    if (this.busy || proposal.status !== 'pending') return;
    this.busy = true; this.error = '';
    try {
      const resolved: DurableWorkspaceProposal = await getNovelCreation().resolveWorkspaceProposal(this.projectId, proposal.proposalId, accept);
      if (this.alive) this.proposals = this.proposals.map((item: DurableWorkspaceProposal): DurableWorkspaceProposal =>
        item.proposalId === resolved.proposalId ? resolved : item);
      if (accept && proposal.operation?.kind === 'revise_chapter' && proposal.operation.args.chapter_id === this.chapterId &&
        proposal.operation.args.new_text === this.replacement && proposal.operation.args.expected_text === this.selectedSource()) {
        this.replacement = '';
      }
      if (accept && proposal.operation?.kind === 'delete_chapters' &&
        JSON.stringify(proposal.operation.args.chapter_ids) === JSON.stringify(this.selectedIds)) this.selectedIds = [];
      if (this.alive) { await this.reload(); this.notice = accept ? '已采纳修改，正文与关联状态已更新。' : '已拒绝，正文保持原样。'; }
    } catch (error) { if (this.alive) this.error = String(error); }
    finally { if (this.alive) this.busy = false; }
  }
  private chapterLabel(chapter: NovelChapter): string { return `第 ${novelChapterOrdinal(chapter, (this.project?.chapters.indexOf(chapter) ?? 0) + 1)} 章 · ${chapter.title}`; }
  private previewKey(preview: WorkspaceProposalPreview, index: number): string {
    return `${preview.chapterId ?? ''}:${preview.label}:${preview.start ?? ''}:${preview.end ?? ''}:${preview.startParagraph ?? ''}:${preview.endParagraph ?? ''}:${index}`;
  }
  @Builder private ProposalBody(proposal: DurableWorkspaceProposal): void {
    Column({ space: 10 }) {
      Text(proposal.review?.summary ?? '').fontSize(13).fontColor(INK).lineHeight(20).width('100%')
      ForEach(proposal.review?.previews ?? [], (preview: WorkspaceProposalPreview): void => {
        Column({ space: 6 }) {
          Text(preview.label).fontSize(14).fontColor(INK).width('100%')
          Text(`原文${preview.startParagraph === undefined ? '' : ` · 第 ${preview.startParagraph}–${preview.endParagraph} 段`}\n${preview.oldText.length > 0 ? preview.oldText : '（无）'}`)
            .fontSize(13).fontColor(INK3).lineHeight(20).width('100%').padding(12).backgroundColor(SURFACE2).borderRadius(10)
          Text(`替换后\n${preview.newText.length > 0 ? preview.newText : '（移除）'}`)
            .fontSize(13).fontColor(INK).lineHeight(20).width('100%').padding(12).backgroundColor(SURFACE2).borderRadius(10)
        }.width('100%').alignItems(HorizontalAlign.Start)
      }, (preview: WorkspaceProposalPreview, index: number): string => this.previewKey(preview, index))
    }.width('100%').alignItems(HorizontalAlign.Start)
  }
  @Builder private ProposalActions(proposal: DurableWorkspaceProposal): void {
    Row({ space: 20 }) {
      Text('拒绝').fontSize(14).fontColor(INK3).height(44).constraintSize({ minWidth: 44 }).enabled(!this.busy).onClick((): void => { this.resolve(proposal, false); })
      Text('确认采纳').fontSize(14).fontColor(ACCENT).height(44).enabled(!this.busy).onClick((): void => { this.resolve(proposal, true); })
    }.width('100%')
  }
  @Builder private Approval(proposal: DurableWorkspaceProposal): void {
    NovelApprovalShell({ title: '工作区修改建议', subtitle: proposal.review?.summary ?? '',
      statusLabel: proposal.status === 'pending' ? '待确认' : proposal.status === 'accepted' ? '已采纳' : '已拒绝',
      showActions: proposal.status === 'pending', content: (): void => { this.ProposalBody(proposal); },
      actions: (): void => { this.ProposalActions(proposal); } })
  }
  build(): void {
    Column() {
    ForEach([this.themeEpoch], (_epoch: number): void => {
      Column() {
        PageTitleBar({ title: '正文操作与修改建议', actionText: '重新读取', onAction: (): void => { this.confirmReload(); }, onBack: (): void => { if (!this.busy) router.back(); } })
        Scroll() {
          Column({ space: 14 }) {
            Text(this.project?.name ?? '正在读取项目…').fontSize(16).fontColor(INK).width('100%')
            Text('操作仅作用于当前分支。生成建议后先检查原文、替换内容和章节范围，再确认采纳。').fontSize(13).fontColor(INK3).lineHeight(20).width('100%')
            ForEach(this.proposals.filter((item: DurableWorkspaceProposal): boolean => item.proposalId === this.focusedProposalId), (proposal: DurableWorkspaceProposal): void => {
              this.Approval(proposal)
            }, (proposal: DurableWorkspaceProposal): string => `${proposal.proposalId}:${proposal.status}`)
            Flex({ wrap: FlexWrap.Wrap }) {
              ForEach(['revise_chapter', 'revert_recent_chapters', 'delete_chapters'], (mode: NovelManuscriptOperationKind): void => {
                Text(mode === 'revise_chapter' ? '按段改写' : mode === 'delete_chapters' ? '删除多章' : '回退最近章节')
                  .fontSize(14).fontColor(this.mode === mode ? ACCENT : INK3).constraintSize({ minHeight: 44 })
                  .padding({ left: 12, right: 12 }).enabled(!this.busy).onClick((): void => { this.mode = mode; })
              }, (mode: string): string => mode)
            }.width('100%')
            if (this.mode === 'revert_recent_chapters') {
              TextInput({ text: this.countText, placeholder: '回退最近几章（正整数）', controller: this.countController }).height(48)
                .fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).type(InputType.Number).enabled(!this.busy).onChange((text: string): void => { this.countText = text; })
              Text('将正文及关联剧情状态回到这些章节之前；完整影响范围见生成后的建议。').fontSize(13).fontColor(INK3).lineHeight(20).width('100%')
            } else {
              ForEach(this.chapters(), (chapter: NovelChapter): void => {
                Text(`${this.mode === 'delete_chapters' ? this.selectedIds.includes(chapter.id) ? '☑ ' : '☐ ' : this.chapterId === chapter.id ? '● ' : '○ '}${this.chapterLabel(chapter)}`)
                  .fontSize(14).fontColor(INK).constraintSize({ minHeight: 44 }).width('100%').padding(8).enabled(!this.busy)
                  .onClick((): void => {
                    if (this.mode === 'delete_chapters') this.selectedIds = this.selectedIds.includes(chapter.id)
                      ? this.selectedIds.filter((id: string): boolean => id !== chapter.id) : this.selectedIds.concat(chapter.id);
                    else this.chapterId = chapter.id;
                  })
              }, (chapter: NovelChapter): string => chapter.id)
              if (this.mode === 'revise_chapter') {
                Text(`本章共 ${this.paragraphs().length} 段，段落编号从 1 开始。`).fontSize(13).fontColor(INK3).width('100%')
                Row({ space: 12 }) {
                  TextInput({ text: this.startText, placeholder: '起始段', controller: this.startController }).layoutWeight(1).height(48)
                    .fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).type(InputType.Number).enabled(!this.busy).onChange((text: string): void => { this.startText = text; })
                  TextInput({ text: this.endText, placeholder: '结束段', controller: this.endController }).layoutWeight(1).height(48)
                    .fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).type(InputType.Number).enabled(!this.busy).onChange((text: string): void => { this.endText = text; })
                }.width('100%')
                Text(`选中原文\n${this.selectedSource() || '请选择有效段落范围'}`).fontSize(13).fontColor(INK3).lineHeight(20).width('100%')
                  .padding(12).backgroundColor(SURFACE2).borderRadius(10)
                TextArea({ text: this.replacement, placeholder: '输入替换这些段落的新正文', controller: this.replacementController })
                  .height(180).fontSize(14).fontColor(INK).placeholderColor(INK3).backgroundColor(SURFACE2).enabled(!this.busy).onChange((text: string): void => { this.replacement = text; })
              }
            }
            Text(this.busy ? '处理中…' : '生成修改建议').fontSize(14).fontColor(ACCENT).height(44).width('100%')
              .enabled(this.ready()).opacity(this.ready() ? 1 : 0.45).onClick((): void => { this.stage(); })
            if (this.error.length > 0) { Text(this.error).fontSize(13).fontColor(ERROR).lineHeight(20).width('100%') }
            if (this.notice.length > 0) { Text(this.notice).fontSize(13).fontColor(INK3).lineHeight(20).width('100%') }
            ForEach(this.proposals.filter((item: DurableWorkspaceProposal): boolean => item.proposalId !== this.focusedProposalId), (proposal: DurableWorkspaceProposal): void => {
              this.Approval(proposal)
            }, (proposal: DurableWorkspaceProposal): string => `${proposal.proposalId}:${proposal.status}`)
          }.width('100%').alignItems(HorizontalAlign.Start).padding({ left: 20, right: 20, top: 8, bottom: this.navBarHeight + 24 })
        }.layoutWeight(1).width('100%').align(Alignment.TopStart).scrollBar(BarState.Auto)
      }.width('100%').height('100%').backgroundColor(BG)
    }, (epoch: number): string => `theme-${epoch}`)
    }.width('100%').height('100%').expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.TOP, SafeAreaEdge.BOTTOM])
  }
}
