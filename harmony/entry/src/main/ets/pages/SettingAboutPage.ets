// SettingAboutPage — 关于页(真实版本值,卡容器对齐设置体系)
// Android 基准: SettingAboutPage.kt — 版本/构建/开源说明/许可协议。
//   版本号经 bundleManager 取真值(不写死)。
// 许可:仓库 LICENSE 为用户分段双重许可(AGPL v3 或商业许可),本页与仓库根 LICENSE
//   及 oh-package.json5 的 license 字段保持一致;此前只写「开源社区项目」而无任何许可
//   声明,与双许可条款冲突。
import { bundleManager, common } from '@kit.AbilityKit';
import { promptAction } from '@kit.ArkUI';
import { INK, INK3, BG, BODY, TITLE, TEXT_STYLES } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM, FONT_SANS_BOLD } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardRow, FooterNote, PageSpacer,
} from '../components/PageTemplate.ets';

const REPOSITORY_URL = 'https://github.com/soul99soul-glitch/AmberAgent';
const LICENSE_URL = `${REPOSITORY_URL}/blob/main/LICENSE`;

@Entry
@Component
struct SettingAboutPage {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @State versionLabel: string = '';

  aboutToAppear(): void {
    try {
      const info = bundleManager.getBundleInfoForSelfSync(bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
      this.versionLabel = `${info.versionName} (${info.versionCode})`;
    } catch (e) {
      this.versionLabel = '';
    }
  }

  private openUrl(url: string): void {
    try {
      const ctx: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
      ctx.startAbility({
        action: 'ohos.want.action.viewData',
        entities: ['entity.system.browsable'],
        uri: url,
      }).catch((_e: Error): void => {
        promptAction.showToast({ message: '无法打开浏览器', duration: 1400 });
      });
    } catch (_e) {
      promptAction.showToast({ message: '无法打开浏览器', duration: 1400 });
    }
  }

  build(): void {
    Column() {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      CenteredTitleBar({ title: '关于' })

      Scroll() {
        Column() {
          // 应用信息
          Column() {
            Text('AmberAgent')
              .fontSize(TITLE).fontWeight(FontWeight.Bold).lineHeight(TEXT_STYLES.screenTitle.lineHeight).fontFamily(FONT_SANS_BOLD).fontColor(INK)
            Text('鸿蒙原生版')
              .fontSize(BODY).fontWeight(FontWeight.Normal).lineHeight(TEXT_STYLES.body.lineHeight)
              .fontFamily(FONT_SANS_MEDIUM).fontColor(INK3).margin({ top: 4 })
          }
          .width('100%')
          .padding({ top: 24, bottom: 0 })
          .alignItems(HorizontalAlign.Center)

          PlainSection({ label: '应用信息' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.InfoCard(); } })
            .margin({ left: 16, right: 16 })

          PlainSection({ label: '数据与隐私' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.PrivacyCard(); } })
            .margin({ left: 16, right: 16 })

          PlainSection({ label: '开源与许可' })
          CardGroup({ radius: 18, stroke: true, content: (): void => { this.LegalCard(); } })
            .margin({ left: 16, right: 16 })

          FooterNote({ text: 'Powered by multiple AI providers.' })

          PageSpacer()
        }
        .width('100%')
      }
        .align(Alignment.TopStart)
      .layoutWeight(1)
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%').height('100%').backgroundColor(BG)
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,铁律 4b)──

  @Builder
  InfoCard(): void {
    Column() {
      CardRow({ label: '版本', valueText: this.versionLabel, showChevron: false })
      CardRow({ label: '构建', valueText: 'HarmonyOS NEXT / ArkTS', isLast: true, showChevron: false })
    }
    .width('100%')
  }

  @Builder
  PrivacyCard(): void {
    Column() {
      CardRow({
        label: '凭据加密',
        desc: 'API Key、SSH 凭据与网关令牌通过系统 Asset Store 主密钥 + AES-GCM 加密存储，明文不落偏好键值库',
        showChevron: false,
      })
      CardRow({
        label: '网络与外发边界',
        desc: '对话与检索请求仅发往你自行配置的模型、搜索、MCP 或网关端点；设置备份与会话导出默认剥离密钥',
        isLast: true,
        showChevron: false,
      })
    }
    .width('100%')
  }

  @Builder
  LegalCard(): void {
    Column() {
      CardRow({
        label: '许可协议',
        desc: 'AGPL v3；商业使用需取得商业许可',
        onRowClick: (): void => { this.openUrl(LICENSE_URL); },
      })
      CardRow({
        label: '项目主页',
        desc: 'github.com/soul99soul-glitch/AmberAgent',
        isLast: true,
        onRowClick: (): void => { this.openUrl(REPOSITORY_URL); },
      })
    }
    .width('100%')
  }
}
