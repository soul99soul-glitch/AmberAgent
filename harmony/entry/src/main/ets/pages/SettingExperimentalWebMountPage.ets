// SettingExperimentalWebMountPage — WebMount 站点管理页
// 用户视角 = 授权边界管理:哪些网站允许 Agent 操控、如何添加;
//   工具集(wm_*)是 Agent 内部接口,不在本页呈现。
// 语义对齐 Android LocalTools.kt:153-166(全局开关)+ UserSiteRegistry(站点表):
//   - 站点 = 授权边界:wm_open 仅允许命中已注册站点(域/子域匹配,工具层校验)
//   - Agent 亦可经 wm_site_add/wm_site_remove 提议增删(需逐次审批)
//   - wm_eval 另有独立开关,且每次调用仍强制人工确认(工具层 mandatoryApproval)
// 2026-09-27 二级页 iOS 对齐(基准 IMG_1992「站点」):仅换视觉骨架
// (CenteredTitleBar/PlainSection/CardGroup r18+描边/CardToggleRow/FooterNote),逻辑零改动。

import { BG, INK, INK3, ERROR, ACCENT, LINE, SURFACE2, accentSoftBg, lineHeightForSize } from '../design/tokens.ets';
import { FONT_SANS_MEDIUM } from '../design/fonts.ets';
import {
  CenteredTitleBar, PlainSection, CardGroup, CardToggleRow, FooterNote, BtnFill, PageSpacer,
} from '../components/PageTemplate.ets';
import { Icon } from '../components/ui/Icon.ets';
import { common } from '@kit.AbilityKit';
import { getChatKvStore, getAppContainer } from '../di/AppContainer.ets';
import { loadWebMountSites, saveWebMountSites, makeUserSite, hostOf, isSeedSite, restoreMissingSeedSites, SEED_SITES } from '../platform_impl/WebMountSites.ets';
import type { UserSiteEntry } from '../platform_impl/WebMountSites.ets';
import { setWebMountGlobalEnabled } from '../platform_impl/WebMountSession.ets';
import {
  makeFeishuPkce, exchangeFeishuCode, createKvWebMountTokenStore, feishuTokenStatusText,
  type FeishuExchangeInput,
} from '../platform_impl/WebMountOAuthSupport.ets';
import { buildFeishuAuthorizationUrl, type WebMountPkce, type WebMountOAuthToken } from '@amber/chat-domain';

/** 飞书回调粘贴解析结果：裸 code 时 state 为 null */
interface FeishuCodePaste {
  code: string;
  state: string | null;
}

@Entry
@Component
struct SettingExperimentalWebMountPage {
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  @State loaded: boolean = false;
  @State globalEnabled: boolean = false;
  @State evalEnabled: boolean = false;
  @State sites: UserSiteEntry[] = [];
  // 添加表单
  @State addOpen: boolean = false;
  @State addName: string = '';
  @State addUrl: string = '';
  @State addError: string = '';
  // 飞书 OAuth
  @State feishuAppId: string = '';
  @State feishuAppSecret: string = '';
  @State feishuAuthCode: string = '';
  @State feishuAuthUrl: string = '';
  @State feishuStatus: string = '未登录';
  @State feishuBusy: boolean = false;
  private feishuPkce: WebMountPkce | null = null;
  private feishuToken: WebMountOAuthToken | null = null;

  aboutToAppear(): void {
    this.refresh();
    this.refreshFeishu();
  }

  private refreshFeishu(): void {
    createKvWebMountTokenStore(getChatKvStore()).get().then((token: WebMountOAuthToken | null): void => {
      this.feishuToken = token;
      this.feishuStatus = feishuTokenStatusText(token);
    }).catch((): void => { this.feishuStatus = '未登录'; });
  }

  private refresh(): void {
    const kv = getChatKvStore();
    kv.get('webmount_global_enabled').then((raw: string | null): void => {
      this.globalEnabled = raw === 'true';
    }).catch((): void => {});
    kv.get('webmount_eval_enabled').then((raw: string | null): void => {
      this.evalEnabled = raw === 'true';
    }).catch((): void => {});
    loadWebMountSites(kv).then((sites: UserSiteEntry[]): void => {
      this.sites = sites;
      this.loaded = true;
    }).catch((): void => { this.loaded = true; });
  }

  private setToggle(key: string, value: boolean): void {
    getChatKvStore().put(key, value ? 'true' : 'false').catch((): void => {});
  }

  private async startFeishuAuth(): Promise<void> {
    if (this.feishuAppId.trim().length === 0) {
      this.feishuStatus = '请填写 App ID';
      return;
    }
    this.feishuBusy = true;
    try {
      const pkce: WebMountPkce = await makeFeishuPkce();
      this.feishuPkce = pkce;
      this.feishuAuthUrl = buildFeishuAuthorizationUrl({
        appId: this.feishuAppId.trim(),
        state: pkce.state,
        codeChallenge: pkce.codeChallenge,
      });
      this.feishuStatus = '已打开浏览器；登录后粘贴 code';
      this.openExternalUrl(this.feishuAuthUrl);
    } catch (e) {
      this.feishuStatus = `生成失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.feishuBusy = false;
    }
  }

  private openExternalUrl(url: string): void {
    try {
      const ctx = getContext(this) as common.UIAbilityContext;
      ctx.startAbility({
        action: 'ohos.want.action.viewData',
        entities: ['entity.system.browsable'],
        uri: url,
      }).catch((): void => {
        this.feishuStatus = '无法打开浏览器，请手动复制链接';
      });
    } catch (_e) {
      this.feishuStatus = '无法打开浏览器，请手动复制链接';
    }
  }

  private async exchangeFeishu(): Promise<void> {
    if (this.feishuBusy) return;
    if (this.feishuPkce === null || this.feishuAuthCode.trim().length === 0) {
      this.feishuStatus = '请先生成链接并填写 code';
      return;
    }
    if (this.feishuAppSecret.trim().length === 0) {
      this.feishuStatus = '请填写 App Secret';
      return;
    }
    const paste = this.parseFeishuCodePaste(this.feishuAuthCode);
    if (paste.code.length === 0) {
      this.feishuStatus = '未能从粘贴内容中解析出 code';
      return;
    }
    // 粘贴内容带 state 时必须匹配本次会话(CSRF);裸 code 手动流程放行,仍靠 PKCE
    if (paste.state !== null && paste.state.length > 0
      && paste.state !== this.feishuPkce.state) {
      this.feishuStatus = 'state 不匹配：请确认使用本次生成的授权链接后重试';
      return;
    }
    this.feishuBusy = true;
    this.feishuStatus = '兑换中…';
    try {
      const exchangeInput: FeishuExchangeInput = {
        appId: this.feishuAppId.trim(),
        appSecret: this.feishuAppSecret.trim(),
        code: paste.code,
        codeVerifier: this.feishuPkce.codeVerifier,
      };
      const token = await exchangeFeishuCode(getAppContainer().httpClient, exchangeInput);
      await createKvWebMountTokenStore(getChatKvStore()).set(token);
      this.feishuToken = token;
      this.feishuStatus = feishuTokenStatusText(token);
    } catch (e) {
      this.feishuStatus = `兑换失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.feishuBusy = false;
    }
  }

  /** 裸 code 或完整回调 URL；URL 带 state 时交由调用方比对 */
  private parseFeishuCodePaste(paste: string): FeishuCodePaste {
    const trimmed: string = paste.trim();
    if (trimmed.indexOf('code=') < 0) {
      const bare: FeishuCodePaste = { code: trimmed, state: null };
      return bare;
    }
    let query: string = trimmed;
    const qIdx: number = query.indexOf('?');
    if (qIdx >= 0) {
      query = query.substring(qIdx + 1);
    }
    const hashIdx: number = query.indexOf('#');
    if (hashIdx >= 0) {
      query = query.substring(0, hashIdx);
    }
    let code: string = '';
    let state: string | null = null;
    const parts: string[] = query.split('&');
    for (const part of parts) {
      const eq: number = part.indexOf('=');
      if (eq < 0) continue;
      const key: string = part.substring(0, eq);
      const value: string = part.substring(eq + 1);
      if (key === 'code') {
        code = value;
      } else if (key === 'state') {
        state = value;
      }
    }
    const parsed: FeishuCodePaste = {
      code: code.length > 0 ? code : trimmed,
      state: state,
    };
    return parsed;
  }

  private async logoutFeishu(): Promise<void> {
    await createKvWebMountTokenStore(getChatKvStore()).set(null);
    this.feishuToken = null;
    this.feishuStatus = '已退出';
  }

  // 添加站点:URL 合法性(host 可解析)与去重(host 不重复)
  private confirmAdd(): void {
    const name: string = this.addName.trim();
    const url: string = this.addUrl.trim();
    const host: string = hostOf(url);
    if (name.length === 0 || host.length === 0) {
      this.addError = '请填写名称与合法的网站地址(如 https://example.com)';
      return;
    }
    const dup: boolean = this.sites.some((s: UserSiteEntry): boolean => hostOf(s.url) === host);
    if (dup) {
      this.addError = `站点已存在:${host}`;
      return;
    }
    const entry: UserSiteEntry = makeUserSite(name, url);
    saveWebMountSites(getChatKvStore(), [...this.sites, entry]).then((): void => {
      this.sites = [...this.sites, entry];
      this.addOpen = false;
      this.addName = '';
      this.addUrl = '';
      this.addError = '';
    }).catch((): void => {
      this.addError = '保存失败,请重试';
    });
  }

  private cancelAdd(): void {
    this.addOpen = false;
    this.addName = '';
    this.addUrl = '';
    this.addError = '';
  }

  private removeSite(id: string): void {
    const next: UserSiteEntry[] = this.sites.filter((s: UserSiteEntry): boolean => s.id !== id);
    saveWebMountSites(getChatKvStore(), next).then((): void => {
      this.sites = next;
    }).catch((): void => {});
  }

  // 被移除的内置站数(恢复按钮的显隐条件)
  private missingSeeds(): number {
    return SEED_SITES.filter(
      (s: UserSiteEntry): boolean => !this.sites.some((c: UserSiteEntry): boolean => c.id === s.id)).length;
  }

  // ── 卡片内容(@Builder:组件构造必须在 builder 上下文,裸 lambda 直接 new 组件会炸进程)──

  @Builder
  SwitchesCard(): void {
    Column() {
      CardToggleRow({
        label: '启用 WebMount',
        desc: '开启后 Agent 可在已授权站点上阅读与操作网页',
        checked: this.globalEnabled,
        onToggle: (): void => {
          const v: boolean = !this.globalEnabled;
          this.globalEnabled = v;
          this.setToggle('webmount_global_enabled', v);
          // 显式关闭立即终止当前会话(复位页面、阻止后续操作);
          //   下次合法建 tools 重新按 OR(global||助手勾选)求值,非总禁用
          if (!v) setWebMountGlobalEnabled(false);
        },
      })
      CardToggleRow({
        label: '允许任意脚本(wm_eval)',
        desc: '在已授权站点页面内执行任意 JavaScript;每次调用仍需逐次确认',
        checked: this.evalEnabled,
        isLast: true,
        onToggle: (): void => {
          const v: boolean = !this.evalEnabled;
          this.evalEnabled = v;
          this.setToggle('webmount_eval_enabled', v);
        },
      })
    }
    .width('100%')
  }

  @Builder
  FeishuCard(): void {
    Column() {
      Row() {
        Text('登录状态')
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .lineHeight(lineHeightForSize(16))
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK)
        Blank()
        Text(this.feishuStatus)
          .fontSize(12)
          .lineHeight(lineHeightForSize(12))
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK3)
          .maxLines(2)
          .textOverflow({ overflow: TextOverflow.Ellipsis })
          .textAlign(TextAlign.End)
          .margin({ left: 12 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 10, bottom: 10 })

      Row() {
        Row().width('100%').height(1).backgroundColor(LINE)
      }
      .width('100%')
      .padding({ left: 14, right: 14 })

      TextInput({ text: this.feishuAppId, placeholder: 'App ID' })
        .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
        .backgroundColor(SURFACE2).borderRadius(8)
        .padding({ left: 12, right: 12 })
        .margin({ left: 14, right: 14, top: 12, bottom: 8 })
        .onChange((v: string): void => { this.feishuAppId = v; })
      TextInput({ text: this.feishuAppSecret, placeholder: 'App Secret' })
        .type(InputType.Password)
        .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
        .backgroundColor(SURFACE2).borderRadius(8)
        .padding({ left: 12, right: 12 })
        .margin({ left: 14, right: 14, bottom: 8 })
        .onChange((v: string): void => { this.feishuAppSecret = v; })
      if (this.feishuAuthUrl.length > 0) {
        Text(this.feishuAuthUrl)
          .fontSize(11).fontColor(INK3)
          .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
          .width('100%')
          .padding({ left: 14, right: 14, bottom: 8 })
      }
      TextInput({ text: this.feishuAuthCode, placeholder: '粘贴授权 code 或回调 URL' })
        .height(44).fontSize(15).fontColor(INK).placeholderColor(INK3)
        .backgroundColor(SURFACE2).borderRadius(8)
        .padding({ left: 12, right: 12 })
        .margin({ left: 14, right: 14, bottom: 12 })
        .onChange((v: string): void => { this.feishuAuthCode = v; })
      Row({ space: 8 }) {
        BtnFill({
          label: '生成授权链接',
          onTap: (): void => { this.startFeishuAuth(); },
        })
        BtnFill({
          label: '兑换 Token',
          onTap: (): void => { this.exchangeFeishu(); },
        })
        if (this.feishuToken !== null) {
          BtnFill({
            label: '退出',
            onTap: (): void => { this.logoutFeishu(); },
          })
        }
      }
      .width('100%')
      .padding({ left: 14, right: 14, bottom: 14 })
      .justifyContent(FlexAlign.Start)
    }
    .width('100%')
  }

  @Builder
  SiteRow(s: UserSiteEntry, isLast: boolean): void {
    Column() {
      Row() {
        Row() {
          Icon({ name: 'globe', iconSize: 15, color: this.accentColorStore })
        }
        .width(28).height(28)
        .borderRadius(7)
        .backgroundColor(accentSoftBg(this.accentColorStore))
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .margin({ right: 12 })

        Column() {
          Row() {
            Text(s.name.length > 0 ? s.name : '未命名站点')
              .fontSize(16)
              .fontWeight(FontWeight.Medium)
              .lineHeight(lineHeightForSize(16))
              .fontFamily(FONT_SANS_MEDIUM)
              .fontColor(INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            if (isSeedSite(s.id)) {
              Text('内置')
                .fontSize(10)
                .fontWeight(FontWeight.Medium)
                .fontFamily(FONT_SANS_MEDIUM)
                .fontColor(INK3)
                .padding({ left: 6, right: 6, top: 1, bottom: 1 })
                .borderRadius(4)
                .border({ width: 0.5, color: LINE })
                .margin({ left: 8 })
            }
          }
          .alignItems(VerticalAlign.Center)

          Text(hostOf(s.url).length > 0 ? hostOf(s.url) : s.url)
            .fontSize(12)
            .lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .maxLines(2)
            .textOverflow({ overflow: TextOverflow.Ellipsis })
            .margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)

        Row() {
          Icon({ name: 'trash', iconSize: 18, color: ERROR })
        }
        .constraintSize({ minWidth: 44, minHeight: 44 })
        .justifyContent(FlexAlign.Center)
        .alignItems(VerticalAlign.Center)
        .margin({ left: 8 })
        .accessibilityText('移除站点')
        .onClick((): void => { this.removeSite(s.id); })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .constraintSize({ minHeight: 52 })
      .padding({ left: 14, right: 14, top: 8, bottom: 8 })

      if (!isLast) {
        Row() {
          Row().width('100%').height(1).backgroundColor(LINE)
        }
        .width('100%')
        // 分隔线起点对齐标题文字(行 padding 14 + 图标座 28 + 间距 12)
        .padding({ left: 54, right: 14 })
      }
    }
    .width('100%')
  }

  @Builder
  RestoreRow(): void {
    Row() {
      Row() {
        Icon({ name: 'retry', iconSize: 15, color: this.accentColorStore })
      }
      .width(28).height(28)
      .borderRadius(7)
      .backgroundColor(accentSoftBg(this.accentColorStore))
      .justifyContent(FlexAlign.Center)
      .alignItems(VerticalAlign.Center)
      .margin({ right: 12 })

      Text(`恢复内置站点(${this.missingSeeds()})`)
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .lineHeight(lineHeightForSize(16))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(this.accentColorStore)
        .layoutWeight(1)
    }
    .width('100%')
    .alignItems(VerticalAlign.Center)
    .constraintSize({ minHeight: 52 })
    .padding({ left: 14, right: 14, top: 8, bottom: 8 })
    .onClick((): void => {
      restoreMissingSeedSites(getChatKvStore()).then((): void => { this.refresh(); });
    })
  }

  @Builder
  SitesCard(): void {
    Column() {
      if (this.sites.length === 0) {
        // 空态:iOS 白卡内两行文案(标题 + 说明)
        Column() {
          Text('还没有授权任何网站')
            .fontSize(16)
            .fontWeight(FontWeight.Medium)
            .lineHeight(lineHeightForSize(16))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK)
          Text('Agent 只能打开列表中的网站;点右上角 + 添加一个站点(如 https://news.ycombinator.com)')
            .fontSize(12)
            .lineHeight(lineHeightForSize(12))
            .fontFamily(FONT_SANS_MEDIUM)
            .fontColor(INK3)
            .margin({ top: 2 })
        }
        .alignItems(HorizontalAlign.Start)
        .width('100%')
        .padding({ left: 14, right: 14, top: 14, bottom: 14 })
      } else {
        ForEach(this.sites, (s: UserSiteEntry, i: number): void => {
          this.SiteRow(s, i >= this.sites.length - 1);
        }, (s: UserSiteEntry): string => s.id)
      }
      if (this.missingSeeds() > 0) {
        if (this.sites.length > 0) {
          Row() {
            Row().width('100%').height(1).backgroundColor(LINE)
          }
          .width('100%')
          .padding({ left: 54, right: 14 })
        }
        this.RestoreRow()
      }
    }
    .width('100%')
  }

  @Builder
  AddFormCard(): void {
    Column() {
      Text('站点名称')
        .fontSize(12)
        .lineHeight(lineHeightForSize(12))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK3)
        .width('100%')
        .padding({ left: 14, right: 14, top: 14 })
      TextInput({ text: this.addName, placeholder: '如 Hacker News' })
        .fontSize(15).height(44).fontColor(INK).placeholderColor(INK3)
        .backgroundColor(SURFACE2).borderRadius(8)
        .margin({ left: 14, right: 14, top: 6 })
        .onChange((v: string): void => { this.addName = v; })
      Text('网站地址')
        .fontSize(12)
        .lineHeight(lineHeightForSize(12))
        .fontFamily(FONT_SANS_MEDIUM)
        .fontColor(INK3)
        .width('100%')
        .padding({ left: 14, right: 14, top: 10 })
      TextInput({ text: this.addUrl, placeholder: 'https://news.ycombinator.com' })
        .fontSize(15).height(44).fontColor(INK).placeholderColor(INK3)
        .backgroundColor(SURFACE2).borderRadius(8)
        .margin({ left: 14, right: 14, top: 6 })
        .onChange((v: string): void => { this.addUrl = v; })
      if (this.addError.length > 0) {
        Text(this.addError)
          .fontSize(12)
          .lineHeight(lineHeightForSize(12))
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(ERROR)
          .width('100%')
          .padding({ left: 14, right: 14, top: 8 })
      }
      Row() {
        Text('取消')
          .fontSize(15)
          .lineHeight(lineHeightForSize(15))
          .fontFamily(FONT_SANS_MEDIUM)
          .fontColor(INK3)
          .constraintSize({ minWidth: 44, minHeight: 44 })
          .onClick((): void => { this.cancelAdd(); })
        Blank()
        BtnFill({ label: '添加', onTap: (): void => { this.confirmAdd(); } })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .padding({ left: 14, right: 14, top: 12, bottom: 14 })
    }
    .width('100%')
  }

  build(): void {
    Column() {
      CenteredTitleBar({
        title: '站点',
        subtitle: this.loaded ? `${this.sites.length} 个站点` : '',
        actionIcon: 'plus',
        onAction: (): void => {
          this.addOpen = true;
          this.addError = '';
        },
      })

      if (!this.loaded) {
        Text('加载中…').fontSize(12).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            PlainSection({ label: '开关' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.SwitchesCard(); } })
              .margin({ left: 16, right: 16 })
            FooterNote({
              text: '关闭「启用 WebMount」会立即结束后台会话页面,但已单独勾选 WebMount 的助手仍可继续使用',
            })

            PlainSection({ label: '飞书账号（OAuth）' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.FeishuCard(); } })
              .margin({ left: 16, right: 16 })

            PlainSection({ label: '站点' })
            CardGroup({ radius: 18, stroke: true, content: (): void => { this.SitesCard(); } })
              .margin({ left: 16, right: 16 })
            if (this.addOpen) {
              CardGroup({ radius: 18, stroke: true, content: (): void => { this.AddFormCard(); } })
                .margin({ left: 16, right: 16, top: 12 })
            }
            FooterNote({
              text: '授权按域名匹配(含子域)。Agent 请求添加新站点时会在聊天中向你确认;'
                + '点击类操作(wm_click)与站点增删均需逐次批准。',
            })

            PageSpacer()
          }
          .width('100%')
        }
        .align(Alignment.TopStart)
        .layoutWeight(1)
      }
    }
    .width('100%').height('100%').backgroundColor(BG)
  }
}
