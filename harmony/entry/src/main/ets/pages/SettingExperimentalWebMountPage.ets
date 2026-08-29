// SettingExperimentalWebMountPage — WebMount 站点管理页
// 用户视角 = 授权边界管理:哪些网站允许 Agent 操控、如何添加;
//   工具集(wm_*)是 Agent 内部接口,不在本页呈现。
// 语义对齐 Android LocalTools.kt:153-166(全局开关)+ UserSiteRegistry(站点表):
//   - 站点 = 授权边界:wm_open 仅允许命中已注册站点(域/子域匹配,工具层校验)
//   - Agent 亦可经 wm_site_add/wm_site_remove 提议增删(需逐次审批)
//   - wm_eval 另有独立开关,且每次调用仍强制人工确认(工具层 mandatoryApproval)

import { BG, INK, INK2, INK3, ERROR, ACCENT, BODY, BODY2, META, LABEL, SURFACE, SURFACE2, LINE, RADIUS } from '../design/tokens.ets';
import { PageTitleBar, SectionHeader, PaperSeparator, PageSpacer, BtnFill } from '../components/PageTemplate.ets';
import { getChatKvStore } from '../di/AppContainer.ets';
import { loadWebMountSites, saveWebMountSites, makeUserSite, hostOf } from '../platform_impl/WebMountSites.ets';
import type { UserSiteEntry } from '../platform_impl/WebMountSites.ets';

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

  aboutToAppear(): void {
    this.refresh();
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
                      Text(s.name.length > 0 ? s.name : '未命名站点')
                        .fontSize(BODY).fontColor(INK)
                        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
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
