// SkillDetailPage — 技能详情编辑页
// 编辑技能字段(名称/描述/触发词/内容/启用),保存/删除
// Android 基准: feature/ui/pages/skill/SkillDetailPage.kt
import { router } from '@kit.ArkUI';
import { AmberSwitch } from '../design/components.ets';
import { INK, INK2, INK3, INK4, SURFACE, SURFACE2, LINE, BG, ACCENT, BODY, META, LABEL, TITLE, ERROR } from '../design/tokens.ets';

const DANGER: string = 'ERROR';

interface SkillDetail {
  id: string;
  name: string;
  description: string;
  trigger: string;
  content: string;
  enabled: boolean;
}

interface SkillParams {
  skillId: string;
}

function makeDetail(id: string, name: string, description: string, trigger: string, content: string, enabled: boolean): SkillDetail {
  return { id: id, name: name, description: description, trigger: trigger, content: content, enabled: enabled };
}

function placeholderLoadDetail(id: string): Promise<SkillDetail> {
  if (id.length === 0 || id === 'new') {
    return Promise.resolve(makeDetail('', '', '', '', '', false));
  }
  if (id === 'skill-summary') {
    return Promise.resolve(makeDetail('skill-summary', '摘要总结', '为长文本生成结构化摘要与要点提取', '/summary', '你是摘要助手,擅长从长文本中提炼结构化要点。', true));
  }
  if (id === 'skill-translate') {
    return Promise.resolve(makeDetail('skill-translate', '多语翻译', '中英日韩互译,保留术语与格式', '/translate', '你是专业翻译助手,遵循信达雅原则,保留术语与格式。', true));
  }
  if (id === 'skill-code-review') {
    return Promise.resolve(makeDetail('skill-code-review', '代码审查', '静态分析代码质量、安全与规范', '/review', '你是代码审查助手,关注质量、安全与规范。', false));
  }
  if (id === 'skill-meeting') {
    return Promise.resolve(makeDetail('skill-meeting', '会议纪要', '从转录文本提炼议题、决议与待办', '/meeting', '你是会议纪要助手,提炼议题、决议与待办事项。', true));
  }
  return Promise.resolve(makeDetail(id, id, '', '', '', false));
}

@Entry
@Component
struct SkillDetailPage {
  @State skillId: string = '';
  @State name: string = '';
  @State description: string = '';
  @State trigger: string = '';
  @State content: string = '';
  // 注:enabled 与 CustomComponent 基类方法同名会触发 arkts 编译冲突,
  //   故重命名为 fieldEnabled(SkillDetail.enabled 接口字段保持不变)。
  @State fieldEnabled: boolean = false;
  @State loaded: boolean = false;
  @State saving: boolean = false;

  aboutToAppear(): void {
    const rawParams: object = router.getParams() as object;
    if (rawParams === null || rawParams === undefined) {
      this.loaded = true;
      return;
    }
    const params: SkillParams = rawParams as SkillParams;
    const id: string = params.skillId ?? '';
    placeholderLoadDetail(id).then((d: SkillDetail): void => {
      this.skillId = d.id;
      this.name = d.name;
      this.description = d.description;
      this.trigger = d.trigger;
      this.content = d.content;
      this.fieldEnabled = d.enabled;
      this.loaded = true;
    });
  }

  private save(): void {
    if (this.name.trim().length === 0) return;
    this.saving = true;
    setTimeout((): void => {
      this.saving = false;
      router.back();
    }, 300);
  }

  private deleteSkill(): void {
    AlertDialog.show({
      title: '删除技能',
      message: '确定要删除此技能吗？此操作无法撤销。',
      primaryButton: {
        value: '取消',
        action: (): void => {},
      },
      secondaryButton: {
        value: '删除',
        fontColor: DANGER,
        action: (): void => { router.back(); },
      },
    });
  }

  build(): void {
    Column() {
      // 标题栏
      Row() {
        Text('←').fontSize(24).fontColor(INK).onClick((): void => { router.back(); })
        Text(this.skillId.length > 0 ? '编辑技能' : '新技能').fontSize(TITLE).fontWeight(FontWeight.Bold).fontColor(INK)
          .margin({ left: 12 }).layoutWeight(1)
      }
      .width('100%').padding({ left: 14, right: 14, top: 2, bottom: 11 }).border({ width: { bottom: 1 }, color: { bottom: LINE } }).backgroundColor(BG)

      if (!this.loaded) {
        Text('加载中…').fontSize(BODY).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            // 名称
            Text('名称').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
              .width('100%').margin({ bottom: 6 })
            TextInput({ text: this.name, placeholder: '技能名称' })
              .fontSize(BODY).fontColor(INK)
              .backgroundColor(SURFACE).borderRadius(8)
              .width('100%').padding({ left: 12, right: 12, top: 10, bottom: 10 })
              .onChange((v: string): void => { this.name = v; })

            // 描述
            Text('描述').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
              .width('100%').margin({ top: 16, bottom: 6 })
            TextArea({ text: this.description, placeholder: '简要描述技能用途' })
              .fontSize(BODY).fontColor(INK)
              .backgroundColor(SURFACE).borderRadius(8)
              .width('100%').height(80).padding({ left: 12, right: 12, top: 10, bottom: 10 })
              .onChange((v: string): void => { this.description = v; })

            // 触发词
            Text('触发词').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
              .width('100%').margin({ top: 16, bottom: 6 })
            TextInput({ text: this.trigger, placeholder: '如 /summary' })
              .fontSize(BODY).fontColor(INK)
              .backgroundColor(SURFACE).borderRadius(8)
              .width('100%').padding({ left: 12, right: 12, top: 10, bottom: 10 })
              .onChange((v: string): void => { this.trigger = v; })

            // 内容
            Text('内容').fontSize(META).fontWeight(FontWeight.Medium).fontColor(INK3)
              .width('100%').margin({ top: 16, bottom: 6 })
            TextArea({ text: this.content, placeholder: '技能的 prompt 内容…' })
              .fontSize(BODY).fontColor(INK)
              .backgroundColor(SURFACE).borderRadius(8)
              .width('100%').height(160).padding({ left: 12, right: 12, top: 10, bottom: 10 })
              .onChange((v: string): void => { this.content = v; })

            // 启用
            Row() {
              Column() {
                Text('启用').fontSize(BODY).fontColor(INK)
                Text('启用后可在对话中调用此技能').fontSize(LABEL).fontColor(INK3).margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)
              AmberSwitch({ checked: this.fieldEnabled, onChange: (v: boolean): void => { this.fieldEnabled = v; } })
            }
            .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(12)
            .margin({ top: 16 })

            // 按钮
            Row() {
              Text(this.saving ? '保存中…' : '保存').fontSize(BODY).fontColor(SURFACE)
                .layoutWeight(1).textAlign(TextAlign.Center)
                .padding({ top: 14, bottom: 14 })
                .backgroundColor(ACCENT).borderRadius(12)
                .onClick((): void => { this.save(); })
              Text('删除').fontSize(BODY).fontColor(DANGER)
                .layoutWeight(1).textAlign(TextAlign.Center)
                .padding({ top: 14, bottom: 14 })
                .backgroundColor(SURFACE).border({ width: 1, color: LINE }).borderRadius(12)
                .margin({ left: 12 })
                .onClick((): void => { this.deleteSkill(); })
            }
            .width('100%').margin({ top: 24 })

            Column() {}.height(40)
          }
          .width('100%').padding({ left: 16, right: 16, top: 16 })
        }
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
