// SettingExperimentalWebMountPage — WebMount 站点管理页
// 用户视角 = 授权边界管理:哪些网站允许 Agent 操控、如何添加;
//   工具集(wm_*)是 Agent 内部接口,不在本页呈现。
// 语义对齐 Android LocalTools.kt:153-166(全局开关)+ UserSiteRegistry(站点表):
//   - 站点 = 授权边界:wm_open 仅允许命中已注册站点(域/子域匹配,工具层校验)
//   - Agent 亦可经 wm_site_add/wm_site_remove 提议增删(需逐次审批)
//   - wm_eval 另有独立开关,且每次调用仍强制人工确认(工具层 mandatoryApproval)

import { BG, INK, INK2, INK3, ERROR, ACCENT, BODY, BODY2, META, LABEL, SURFACE, SURFACE2, LINE, RADIUS } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PaperSeparator, PageSpacer, BtnFill } from '../components/PageTemplate.ets';
import { common } from '@kit.AbilityKit';
import { getChatKvStore, getAppContainer } from '../di/AppContainer.ets';
import { loadWebMountSites, saveWebMountSites, makeUserSite, hostOf, isSeedSite, restoreMissingSeedSites, SEED_SITES } from '../platform_impl/WebMountSites.ets';
import type { UserSiteEntry } from '../platform_impl/WebMountSites.ets';
import {
  makeFeishuPkce, exchangeFeishuCode, createKvWebMountTokenStore, feishuTokenStatusText,
} from '../platform_impl/WebMountOAuthSupport.ets';
import { buildFeishuAuthorizationUrl, type WebMountPkce, type WebMountOAuthToken } from '@amber/chat-domain';

@Entry
@Component
struct SettingExperimentalWebMountPage {
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
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
    this.feishuBusy = true;
    this.feishuStatus = '兑换中…';
    try {
      const token = await exchangeFeishuCode(getAppContainer().httpClient, {
        appId: this.feishuAppId.trim(),
        appSecret: this.feishuAppSecret.trim(),
        code: this.feishuAuthCode.trim(),
        codeVerifier: this.feishuPkce.codeVerifier,
      });
      await createKvWebMountTokenStore(getChatKvStore()).set(token);
      this.feishuToken = token;
      this.feishuStatus = feishuTokenStatusText(token);
    } catch (e) {
      this.feishuStatus = `兑换失败：${e instanceof Error ? e.message : String(e)}`;
    } finally {
      this.feishuBusy = false;
    }
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

  build(): void {
    Column() {
      PageTitleBar({ title: 'WebMount 站点' })

      if (!this.loaded) {
        Text('加载中…').fontSize(META).fontColor(INK3).padding({ top: 40 })
      } else {
        Scroll() {
          Column() {
            SectionHeader({ label: '开关' })

            Row() {
              Column() {
                Text('启用 WebMount').fontSize(BODY).fontColor(INK)
                Text('开启后 Agent 可在已授权站点上阅读与操作网页')
                  .fontSize(LABEL).fontColor(INK3).margin({ top: 3 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 12 })
              Toggle({ type: ToggleType.Switch, isOn: this.globalEnabled })
                .selectedColor(ACCENT)
                .onChange((v: boolean): void => {
                  this.globalEnabled = v;
                  this.setToggle('webmount_global_enabled', v);
                })
            }
            .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)

            PaperSeparator()

            Row() {
              Column() {
                Text('允许执行任意脚本(wm_eval)').fontSize(BODY).fontColor(INK)
                Text('在已授权站点页面内执行任意 JavaScript;每次调用仍需逐次确认')
                  .fontSize(LABEL).fontColor(INK3).margin({ top: 3 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 12 })
              Toggle({ type: ToggleType.Switch, isOn: this.evalEnabled })
                .selectedColor(ACCENT)
                .onChange((v: boolean): void => {
                  this.evalEnabled = v;
                  this.setToggle('webmount_eval_enabled', v);
                })
            }
            .width('100%').padding({ left: 16, right: 16, top: 14, bottom: 14 })
            .backgroundColor(SURFACE)

            PaperSeparator()

            SectionHeader({ label: '飞书账号（OAuth）' })
            Text(this.feishuStatus).fontSize(LABEL).fontColor(INK3)
              .width('100%').padding({ left: 16, right: 16, top: 8, bottom: 4 })
            TextInput({ text: this.feishuAppId, placeholder: 'App ID' })
              .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK3)
              .backgroundColor(SURFACE2).borderRadius(RADIUS)
              .padding({ left: 12, right: 12 })
              .margin({ left: 16, right: 16, top: 8, bottom: 6 })
              .onChange((v: string): void => { this.feishuAppId = v; })
            TextInput({ text: this.feishuAppSecret, placeholder: 'App Secret' })
              .type(InputType.Password)
              .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK3)
              .backgroundColor(SURFACE2).borderRadius(RADIUS)
              .padding({ left: 12, right: 12 })
              .margin({ left: 16, right: 16, bottom: 6 })
              .onChange((v: string): void => { this.feishuAppSecret = v; })
            if (this.feishuAuthUrl.length > 0) {
              Text(this.feishuAuthUrl).fontSize(11).fontColor(INK3)
                .maxLines(3).textOverflow({ overflow: TextOverflow.Ellipsis })
                .width('100%').padding({ left: 16, right: 16, bottom: 6 })
            }
            TextInput({ text: this.feishuAuthCode, placeholder: '粘贴授权 code' })
              .height(44).fontSize(BODY).fontColor(INK).placeholderColor(INK3)
              .backgroundColor(SURFACE2).borderRadius(RADIUS)
              .padding({ left: 12, right: 12 })
              .margin({ left: 16, right: 16, bottom: 8 })
              .onChange((v: string): void => { this.feishuAuthCode = v; })
            Row() {
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
            .width('100%').padding({ left: 16, right: 16, bottom: 12 })
            .justifyContent(FlexAlign.Start)

            PaperSeparator()

            SectionHeader({ label: `已授权站点 · ${this.sites.length}` })

            Column() {
              if (this.sites.length === 0) {
                Column() {
                  Text('还没有授权任何网站').fontSize(BODY2).fontColor(INK3).margin({ top: 2 })
                  Text('Agent 只能打开下方列表中的网站;先添加一个站点(如 https://news.ycombinator.com)')
                    .fontSize(LABEL).fontColor(INK3).margin({ top: 6 })
                    .textAlign(TextAlign.Center)
                }
                .alignItems(HorizontalAlign.Center)
                .width('100%').padding({ left: 16, right: 16, top: 20, bottom: 20 })
              }
              ForEach(this.sites, (s: UserSiteEntry, i: number): void => {
                Column() {
                  Row() {
                    Column() {
                      Row() {
                        Text(s.name.length > 0 ? s.name : '未命名站点')
                          .fontSize(BODY).fontColor(INK)
                          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                        if (isSeedSite(s.id)) {
                          Text('内置')
                            .fontSize(LABEL).fontColor(INK3)
                            .padding({ left: 6, right: 6, top: 1, bottom: 1 })
                            .borderRadius(4)
                            .border({ width: 0.5, color: LINE })
                            .margin({ left: 8 })
                        }
                      }
                      Text(hostOf(s.url).length > 0 ? hostOf(s.url) : s.url)
                        .fontSize(META).fontColor(INK3).margin({ top: 3 })
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                    }
                    .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ right: 10 })
                    Text('移除').fontSize(META).fontColor(ERROR)
                      .padding({ left: 10, right: 4, top: 10, bottom: 10 })
                      .onClick((): void => { this.removeSite(s.id); })
                  }
                  .width('100%').alignItems(VerticalAlign.Center)
                  .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                  if (i < this.sites.length - 1) {
                    PaperSeparator()
                  }
                }.width('100%')
              }, (s: UserSiteEntry): string => s.id)

              // 添加站点(展开式表单)
              if (!this.addOpen) {
                Row() {
                  if (this.missingSeeds() > 0) {
                    Text('恢复内置站点').fontSize(BODY2).fontColor(INK3)
                      .padding({ left: 14, right: 14, top: 12, bottom: 12 })
                      .onClick((): void => {
                        restoreMissingSeedSites(getChatKvStore()).then((): void => { this.refresh(); });
                      })
                  }
                  Text('+ 添加网站').fontSize(BODY2).fontColor(ACCENT)
                    .padding({ left: 16, right: 16, top: 12, bottom: 12 })
                    .onClick((): void => {
                      this.addOpen = true;
                      this.addError = '';
                    })
                }.width('100%').justifyContent(FlexAlign.Center)
              } else {
                Column() {
                  Text('站点名称').fontSize(LABEL).fontColor(INK3).margin({ bottom: 4 })
                  TextInput({ text: this.addName, placeholder: '如 Hacker News' })
                    .fontSize(BODY).height(44)
                    .backgroundColor(SURFACE2).borderRadius(8)
                    .onChange((v: string): void => { this.addName = v; })
                  Text('网站地址').fontSize(LABEL).fontColor(INK3).margin({ top: 10, bottom: 4 })
                  TextInput({ text: this.addUrl, placeholder: 'https://news.ycombinator.com' })
                    .fontSize(BODY).height(44)
                    .backgroundColor(SURFACE2).borderRadius(8)
                    .onChange((v: string): void => { this.addUrl = v; })
                  if (this.addError.length > 0) {
                    Text(this.addError).fontSize(LABEL).fontColor(ERROR).margin({ top: 6 })
                  }
                  Row() {
                    Text('取消').fontSize(BODY2).fontColor(INK3)
                      .padding({ left: 14, right: 14, top: 10, bottom: 10 })
                      .onClick((): void => {
                        this.addOpen = false;
                        this.addName = '';
                        this.addUrl = '';
                        this.addError = '';
                      })
                    Blank()
                    BtnFill({ label: '添加', onTap: (): void => { this.confirmAdd(); } })
                  }
                  .width('100%').margin({ top: 10 })
                }
                .width('100%')
                .padding({ left: 16, right: 16, top: 14, bottom: 16 })
              }
            }
            .width('100%').backgroundColor(SURFACE).borderRadius(RADIUS)
            .border({ width: 1, color: LINE })
            .margin({ left: 16, right: 16, bottom: 8 })

            Text('授权按域名匹配(含子域)。Agent 请求添加新站点时会在聊天中向你确认;'
              + '点击类操作(wm_click)与站点增删均需逐次批准。')
              .fontSize(LABEL).fontColor(INK3).width('100%')
              .padding({ left: 16, right: 16, top: 4, bottom: 10 })

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
