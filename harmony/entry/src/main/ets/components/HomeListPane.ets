// HomeListPane — 首页主体(原 ChatListPage),iOS ConversationsView 1:1 复刻(点阵·陶土主题)
//
// 对齐 iOS PlaceholderViews.swift ConversationsView:
//   头部: 点阵 AMBER + 搜索胶囊(点击展开全宽玻璃条) + 齿轮(accent) + 头像
//   控制卡: 继续(单候选) + 五快捷入口, 白卡 22 圆角 + 暖棕投影
//   会话: 一体卡(顶/中/底切片), 圆形图标座(语义映射 + 稳定哈希), meta = 相对时间 · N 条
//   右下: 新对话胶囊(accent, 铅笔 + 文案)
// 数据逻辑保留: repo.list/删除/撤销/置顶/导出; 继续候选复用 Index 聚合管线取第 1 条
//   (iOS HomeContinueCardModel.resolve 同样只取最优一卡)。
//
// 宿主:单栏时 ChatListPage 整页;两栏时(design/adaptive.ets)ChatListPage 与 ChatPage
//   都把它放在左侧窄栏。组件拿不到页面生命周期,宿主经 hostVisible 转发 onPageShow/onPageHide。

import { router, promptAction, curves } from '@kit.ArkUI';
import { picker } from '@kit.CoreFileKit';
import { pasteboard } from '@kit.BasicServicesKit';
import { util } from '@kit.ArkTS';
import { fileIo } from '@kit.CoreFileKit';
import type { Context } from '@kit.AbilityKit';
import type { Conversation, ConversationSummary, DisplaySetting, ContinueCandidate, AgentCronTask } from '@amber/chat-domain';
import type { ModelCouncilRun, DeepReadScheduler } from '@amber/deepread-domain';
import { loadDisplaySetting, exportConversationMarkdown, exportFileNameFor, mergeContinueCandidates, patchConversation } from '@amber/chat-domain';
import {
  getChatKvStore, getChatRepository, getNovelCreation, getAgentCronManager, getRepository,
  getCouncilManager, getConversationRunService, getDeepReadScheduler,
} from '../di/AppContainer.ets';
import { cleanupDeletedConversation, getFilesRepository } from '../platform_impl/ManagedFileStore.ets';
import { beginConversationRestore } from '../platform_impl/ConversationRunGuard.ets';
import { getMiniAppRepositorySync } from '../platform_impl/MiniAppPlatform.ets';
import { loadDeepReadContinueCandidates } from '../platform_impl/DeepReadContinueSupport.ets';
import { Icon } from './ui/Icon.ets';
import { AmberWordmark } from './ui/AmberWordmark.ets';
import { PressGlowButton } from './ui/PressGlowButton.ets';
import { SubAgentActivityStrip } from './SubAgentActivityStrip.ets';
import { RunningIndicator } from './ChatListIndicators.ets';
import { ACCENT, ACCENT_INK, ERROR, HOME_CARD, HOME_CARD2, HOME_GLASS_BOTTOM,
  HOME_GLASS_EDGE, HOME_GLASS_SHADOW, HOME_GLASS_TOP, HOME_INK, HOME_INK2, HOME_MUTED, HOME_MUTED2,
  HOME_PRESS, HOME_ROWLINE, HOME_SEP, HOME_SHADOW, MOTION_ENTER, MOTION_STAGGER, OVERLAY } from '../design/tokens.ets';
import { canvasGradientColors, currentCanvasGradientAngle } from '../design/theme_packs.ets';
import { DesignCanvasLayer } from '../design/DesignCanvasLayer.ets';
import { FONT_SANS, FONT_SANS_MEDIUM, FONT_SANS_SEMIBOLD } from '../design/fonts.ets';

interface ActionMenuResult {
  index: number;
}

const SNACK_BG: string = '#32302B';
const SNACK_DURATION_MS: number = 4000;
// F08:会话预览 getById 并发上限,避免 N 个无界并发
const PREVIEW_CONCURRENCY: number = 8;
// 最后打开会话(KV):复刻 iOS currentConversation 激活行染色
const LAST_OPEN_KEY: string = 'home_last_open_conversation';
// iOS homeCardRadius 默认 22
const HOME_RADIUS: number = 22;

// accent 透明度混色(iOS rgba 语法;ArkUI 直接收 '#AARRGGBB' 也受支持但令牌是 #RRGGBB)
const accentAlpha = (hex: string, alpha: number): string => {
  const value: string = hex.replace('#', '');
  if (value.length !== 6) return hex;
  const r: number = parseInt(value.substring(0, 2), 16);
  const g: number = parseInt(value.substring(2, 4), 16);
  const b: number = parseInt(value.substring(4, 6), 16);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
};

// ── 会话行图标:iOS HomeConversationIcon 移植 ──
// 哈希池 = iOS llmCatalog 顺序(稳定英文 slug → ph_ 字形);关键词表顺序即优先级。
const ICON_POOL: string[] = [
  'ph_moon', 'ph_sun', 'ph_wine', 'ph_coffee', 'ph_sword', 'ph_crown', 'ph_castleTurret',
  'ph_list', 'ph_listChecks', 'ph_musicNotes', 'ph_headphones', 'ph_mapPin', 'ph_globe',
  'ph_airplane', 'ph_car', 'ph_train', 'ph_pill', 'ph_heartbeat', 'ph_scales', 'ph_gavel',
  'ph_bookOpen', 'ph_books', 'ph_notebook', 'ph_pencil', 'ph_code', 'ph_robot', 'ph_brain',
  'ph_lightbulb', 'ph_flask', 'ph_gameController', 'ph_trophy', 'ph_football', 'ph_basketball',
  'ph_heart', 'ph_smiley', 'ph_fire', 'ph_lightning', 'ph_drop', 'ph_snowflake', 'ph_mountains',
  'ph_tree', 'ph_flower', 'ph_dog', 'ph_cat', 'ph_fish', 'ph_forkKnife', 'ph_pizza',
  'ph_hamburger', 'ph_cake', 'ph_house', 'ph_buildings', 'ph_wallet', 'ph_currencyCny',
  'ph_chartLineUp', 'ph_shoppingCart', 'ph_gift', 'ph_calendar', 'ph_clock', 'ph_graduationCap',
  'ph_student', 'ph_camera', 'ph_filmSlate', 'ph_videoCamera', 'ph_phone', 'ph_envelope',
  'ph_bell', 'ph_lock', 'ph_key', 'ph_translate', 'ph_quotes', 'ph_users', 'ph_baby',
  'ph_rocket', 'ph_briefcase', 'ph_handshake', 'ph_yinYang', 'ph_ghost', 'ph_alien',
  'ph_maskHappy', 'ph_palette', 'ph_paintBrush', 'ph_magnifyingGlass', 'ph_gear', 'ph_chatCircle',
];

interface SemanticIconEntry {
  icon: string;
  keywords: string[];
}

// iOS semanticIcon 关键词表逐字移植(顺序即优先级)
const SEMANTIC_ICONS: SemanticIconEntry[] = [
  { icon: 'ph_moon', keywords: ['月光', '月亮', '夜色', '夜晚', '晚安', '晚上', '星空', '半夜', '晚年', '梦', '失眠'] },
  { icon: 'ph_sun', keywords: ['白天', '阳光', '日出', '日落', '晴天', '夏天', '夏日'] },
  { icon: 'ph_wine', keywords: ['酒', '酿', '醉', '干杯', '白酒', '红酒', '啤酒', '威士忌'] },
  { icon: 'ph_coffee', keywords: ['咖啡', '拿铁', '美式', 'espresso', 'café'] },
  { icon: 'ph_sword', keywords: ['剑', '武侠', '江湖', '打仗', '战争', '战役', '战术', '兵法', '武将', '将军', '军队', '项羽', '吕布', '关羽'] },
  { icon: 'ph_crown', keywords: ['皇帝', '帝王', '王冠', '君主', '国王', '女王', '皇后', '皇室', '王位', '登基', '在位', '宋太祖', '赵匡胤', '天子'] },
  { icon: 'ph_castleTurret', keywords: ['宫殿', '城堡', '王朝', '帝国', '朝廷'] },
  { icon: 'ph_list', keywords: ['顺序', '排行', '清单', '列表', '目录', '步骤', '流程', '时间表', '年表'] },
  { icon: 'ph_listChecks', keywords: ['todo', '待办', 'checklist', '勾选', '任务列表'] },
  { icon: 'ph_musicNotes', keywords: ['音乐', '歌曲', '歌单', 'bgm', '配乐', '旋律', '专辑', '歌手', '歌词', '钢琴', '吉他', 'rap'] },
  { icon: 'ph_headphones', keywords: ['耳机', '播客', 'podcast', '听歌'] },
  { icon: 'ph_mapPin', keywords: ['在哪', '哪里', '哪儿', '地址', '地图', '路线', '都城', '城市', '旅行', '旅游', '景点', '定位'] },
  { icon: 'ph_globe', keywords: ['世界', '国际', '全球', '地球', '国家', '海外', '跨国'] },
  { icon: 'ph_airplane', keywords: ['飞机', '航班', '机场', '航空', '出差', '飞去'] },
  { icon: 'ph_car', keywords: ['开车', '汽车', '驾车', '高速', '路况', '停车'] },
  { icon: 'ph_train', keywords: ['火车', '高铁', '地铁', '动车', '站台'] },
  { icon: 'ph_pill', keywords: ['药', '症状', '治疗', '医院', '看病', '疾病', '感冒', '发烧', '痛风', '健康', '养生'] },
  { icon: 'ph_heartbeat', keywords: ['心脏', '血压', '体检', '心率'] },
  { icon: 'ph_scales', keywords: ['谁', '对比', '比较', '哪个好', '排名', '评价', '厉害', '更强', '哪个厉害'] },
  { icon: 'ph_gavel', keywords: ['法律', '法院', '律师', '判决', '合同', '合规'] },
  { icon: 'ph_bookOpen', keywords: ['小说', '读书', '阅读', '章节', '写书', '连载', '故事', '剧本', '剧情'] },
  { icon: 'ph_books', keywords: ['历史', '史料', '文献', '典籍', '通史'] },
  { icon: 'ph_notebook', keywords: ['笔记', '备忘', '日记', '手账'] },
  { icon: 'ph_pencil', keywords: ['写作', '作文', '改写', '润色', '文案', '起草'] },
  { icon: 'ph_code', keywords: ['代码', '编程', '程序', 'bug', 'api', 'swift', 'python', '前端', '后端', '算法', '模型', '训练', 'llm', 'gpt'] },
  { icon: 'ph_robot', keywords: ['机器人', 'ai', '人工智能', '智能体', 'agent'] },
  { icon: 'ph_brain', keywords: ['思考', '推理', '认知', '心理', '脑'] },
  { icon: 'ph_lightbulb', keywords: ['想法', '创意', '灵感', '点子', '方案'] },
  { icon: 'ph_flask', keywords: ['化学', '实验', '科学', '物理', '公式'] },
  { icon: 'ph_gameController', keywords: ['游戏', '电玩', '通关', '副本', 'rpg', 'steam'] },
  { icon: 'ph_trophy', keywords: ['冠军', '夺冠', '奖杯', '比赛', '胜负'] },
  { icon: 'ph_football', keywords: ['足球', '世界杯', '联赛'] },
  { icon: 'ph_basketball', keywords: ['篮球', 'nba', '扣篮'] },
  { icon: 'ph_heart', keywords: ['爱情', '恋爱', '喜欢', '表白', '女朋友', '男朋友', '结婚', '暗恋'] },
  { icon: 'ph_smiley', keywords: ['开心', '搞笑', '笑话', '幽默', '段子'] },
  { icon: 'ph_fire', keywords: ['火', '热门', '爆', '燃', '热情'] },
  { icon: 'ph_lightning', keywords: ['闪电', '速度', '性能', '加速'] },
  { icon: 'ph_drop', keywords: ['水', '下雨', '喝水', '饮水', '海洋'] },
  { icon: 'ph_snowflake', keywords: ['雪', '冬天', '冰冷', '霜'] },
  { icon: 'ph_mountains', keywords: ['山', '登山', '爬山', '高原'] },
  { icon: 'ph_tree', keywords: ['树', '森林', '植物', '环保'] },
  { icon: 'ph_flower', keywords: ['花', '玫瑰', '花园'] },
  { icon: 'ph_dog', keywords: ['狗', '犬', '汪'] },
  { icon: 'ph_cat', keywords: ['猫', '喵'] },
  { icon: 'ph_fish', keywords: ['鱼', '海鲜', '钓鱼', '三文鱼', '帝王鲑'] },
  { icon: 'ph_pizza', keywords: ['披萨', 'pizza'] },
  { icon: 'ph_hamburger', keywords: ['汉堡', '快餐'] },
  { icon: 'ph_cake', keywords: ['蛋糕', '生日', '甜品'] },
  { icon: 'ph_forkKnife', keywords: ['美食', '餐厅', '做饭', '菜谱', '吃什么', '下厨'] },
  { icon: 'ph_house', keywords: ['家', '房子', '居住', '装修', '租房'] },
  { icon: 'ph_buildings', keywords: ['公司', '办公', '写字楼', '都市'] },
  { icon: 'ph_wallet', keywords: ['钱', '理财', '存款', '消费', '省钱'] },
  { icon: 'ph_currencyCny', keywords: ['人民币', '汇率', '日元', '美元', '炒股', '股票', '基金'] },
  { icon: 'ph_chartLineUp', keywords: ['增长', '趋势', '数据', '分析', '报表', 'kpi'] },
  { icon: 'ph_shoppingCart', keywords: ['购物', '网购', '下单', '淘宝', '买东西'] },
  { icon: 'ph_gift', keywords: ['礼物', '送礼', '红包'] },
  { icon: 'ph_calendar', keywords: ['日程', '日历', '约会', '会议', '安排'] },
  { icon: 'ph_clock', keywords: ['时间', '几点', '迟到', '闹钟', '倒计时'] },
  { icon: 'ph_graduationCap', keywords: ['考试', '高考', '考研', '留学', '大学', '学习', '课程', '作业'] },
  { icon: 'ph_student', keywords: ['学生', '同学', '老师', '上课'] },
  { icon: 'ph_camera', keywords: ['拍照', '摄影', '相机', '照片'] },
  { icon: 'ph_filmSlate', keywords: ['电影', '影视', '剧集', '追剧', '导演'] },
  { icon: 'ph_videoCamera', keywords: ['视频', '直播', '剪辑'] },
  { icon: 'ph_phone', keywords: ['电话', '手机', '通话'] },
];

const conversationIcon = (title: string): string => {
  const normalized: string = title.toLowerCase();
  for (const entry of SEMANTIC_ICONS) {
    for (const keyword of entry.keywords) {
      if (normalized.includes(keyword)) return entry.icon;
    }
  }
  // 稳定哈希(iOS djb2 变体;ArkTS 无双精度 UInt64,模 2^31 逐步约减保持确定性)
  let hash: number = 5381;
  const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(title);
  for (let i = 0; i < bytes.length; i++) {
    hash = (127 * hash + bytes[i]) % 2147483648;
  }
  return ICON_POOL[hash % ICON_POOL.length];
};

// 相对时间(iOS RelativeDateTimeFormatter abbreviated zh 口径)
const relativeTimeMs = (ms: number): string => {
  if (isNaN(ms) || ms <= 0) return '';
  const diff: number = Date.now() - ms;
  if (diff < 60 * 1000) return '刚刚';
  const minutes: number = Math.floor(diff / 60000);
  if (minutes < 60) return `${minutes}分钟前`;
  const hours: number = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}小时前`;
  const days: number = Math.floor(hours / 24);
  if (days < 30) return `${days}天前`;
  const months: number = Math.floor(days / 30);
  if (months < 12) return `${months}个月前`;
  return `${Math.floor(months / 12)}年前`;
};

const relativeTimeIso = (iso: string): string => {
  const ms: number = new Date(iso).getTime();
  if (isNaN(ms)) return iso.length > 10 ? iso.substring(0, 10) : iso;
  return relativeTimeMs(ms);
};

// 两栏里切换会话会连同左栏一起新建 ChatPage:用上一份列表、条数与滚动位置播种,
//   新左栏不闪"加载中"、不重播入场、不跳回顶部(模块级,进程内有效)。
let cachedItems: ConversationSummary[] | null = null;
let cachedCounts: Record<string, number> = {};
// 条数按 updateAt 记戳:没变的会话不再 getById 全量读消息
let cachedCountStamps: Record<string, string> = {};
let cachedScrollY: number = 0;
let cachedContinue: ContinueCandidate | null = null;
let cachedContinueReady: boolean = false;
let cachedNickname: string = '';
let cachedAvatar: string = '';
let cachedSearchQuery: string = '';
// 撤销窗内的待删会话:发起删除的实例可能先被换会话销毁,别的左栏刷新时也要排除它;
//   落库(或放弃)后递增列表版本,通知所有存活的左栏重刷
let pendingDeleteId: string = '';
const HOME_LIST_VERSION_KEY: string = 'homeListVersion';

const bumpHomeListVersion = (): void => {
  AppStorage.setOrCreate<number>(HOME_LIST_VERSION_KEY, (AppStorage.get<number>(HOME_LIST_VERSION_KEY) ?? 0) + 1);
};

@Component
export struct HomeListPane {
  @StorageProp('themeEpoch') themeEpoch: number = 0; // 主题重建订阅(勿删):epoch 递增即重读令牌
  @StorageProp('statusBarHeight') statusBarHeight: number = 44;
  @StorageProp('navBarHeight') navBarHeight: number = 0;
  @StorageProp('darkMode') darkMode: boolean = false;
  @StorageProp('accentColor') accentColorStore: string = ACCENT;
  // 主题包(C2):包切换 → 本页重建重读 HOME_* 令牌;画布纹理由 DesignCanvasLayer 自绘
  @StorageProp('themePackId') themePackId: string = '';
  // 宿主页可见性:宿主在 onPageShow/onPageHide 里翻转(组件收不到页面生命周期)
  @Prop @Watch('onHostVisibleChange') hostVisible: boolean = false;
  // 两栏左栏:新对话收进顶栏,不画右下胶囊(底部只留右侧输入栏,不重复)
  @Prop twoPane: boolean = false;
  // 嵌在 ChatPage 左栏:打开会话 = 原地替换右侧聊天页,而不是再压一页
  @Prop inChat: boolean = false;
  // 两栏时高亮右侧正在打开的会话(空 = 不高亮);单栏沿用"最后打开"
  @Prop @Watch('onActiveIdChange') activeId: string = '';
  @StorageProp(HOME_LIST_VERSION_KEY) @Watch('onListVersionChange') listVersion: number = 0;
  @State items: ConversationSummary[] = [];
  @State runningIds: string[] = [];
  @State loaded: boolean = false;
  @State snackVisible: boolean = false;
  @State pressedId: string = '';
  @State pressedFeature: string = '';
  @State pressedContinue: boolean = false;
  @State userNickname: string = '';
  @State userAvatar: string = '';
  @State msgCounts: Record<string, number> = {};
  @State lastOpenedId: string = '';
  @State continueItem: ContinueCandidate | null = null;
  // 列表级重命名(iOS/Android 均在列表出重命名)
  @State renameOpen: boolean = false;
  @State renameId: string = '';
  @State renameInput: string = '';
  // 展开搜索(iOS homeSearch morph:胶囊 → 全宽玻璃条,实时过滤标题)
  @State searchExpanded: boolean = false;
  @State searchQuery: string = '';
  @State searchFocused: boolean = false;
  // 入场级联(iOS homeCascade:opacity + y8,60/100/140/180/220ms,0.48s cubic(0.2,.8,.2,1))
  @State cascadeHeader: boolean = false;
  @State cascadeCard: boolean = false;
  @State cascadeSection: boolean = false;
  @State cascadeRows: boolean = false;
  @State cascadeFab: boolean = false;
  private pendingDeleted: Conversation | null = null;
  private snackTimer: number = -1;
  private pageAlive: boolean = false;
  private unsubscribeRunning: (() => void) | null = null;
  private refreshSeq: number = 0;
  private continueSeq: number = 0;
  private firstShowDone: boolean = false;
  private cascadeDone: boolean = false;
  private navCooldownUntil: number = 0;
  private scroller: Scroller = new Scroller();

  private onHostVisibleChange(): void {
    if (!this.hostVisible) {
      this.stopRunningSubscription();
      return;
    }
    this.observeRunning();
    if (this.firstShowDone) {
      this.refresh();
      this.loadContinue();
      this.loadLastOpened();
      // 两栏返回首页:左栏换回本实例,对齐聊天页左栏最后的滚动位置
      if (this.twoPane) this.restoreScroll();
    } else {
      this.firstShowDone = true;
    }
    this.loadUserProfile();
  }

  // 右侧打开了列表里还没有的会话(新会话首次落库):补一次刷新;已有的只换高亮
  private onActiveIdChange(): void {
    if (!this.pageAlive || this.activeId.length === 0) return;
    if (!this.items.some((item: ConversationSummary): boolean => item.id === this.activeId)) this.refresh();
  }

  private onListVersionChange(): void {
    if (this.pageAlive) this.refresh();
  }

  aboutToAppear(): void {
    this.pageAlive = true;
    // 宿主已可见时才建出来(如聊天页里转成横屏):首个 hostVisible=true 不会再触发 Watch
    if (this.hostVisible) this.firstShowDone = true;
    const seeded: boolean = this.inChat && cachedItems !== null;
    if (seeded) {
      this.items = cachedItems as ConversationSummary[];
      this.msgCounts = cachedCounts;
      this.loaded = true;
      this.continueItem = cachedContinue;
      this.userNickname = cachedNickname;
      this.userAvatar = cachedAvatar;
      if (cachedSearchQuery.length > 0) {
        this.searchExpanded = true;
        this.searchQuery = cachedSearchQuery;
      }
    }
    this.observeRunning();
    this.refresh();
    if (!seeded || !cachedContinueReady) this.loadContinue();
    this.loadLastOpened();
    this.loadUserProfile();
    this.playCascade();
  }

  private loadUserProfile(): void {
    loadDisplaySetting(getChatKvStore()).then((setting: DisplaySetting): void => {
      if (!this.pageAlive) return;
      this.userNickname = setting.userNickname;
      this.userAvatar = setting.userAvatar;
      cachedNickname = setting.userNickname;
      cachedAvatar = setting.userAvatar;
    }).catch((): void => {});
  }

  // 等 List 首次布局完成再滚,否则偏移可能被夹到 0
  private restoreScroll(): void {
    setTimeout((): void => {
      if (this.pageAlive) this.scroller.scrollTo({ xOffset: 0, yOffset: cachedScrollY });
    }, 0);
  }

  // 级联只播一次:组件实例存活期间返回本页不重播(iOS cascadeComplete 同语义);
  //   聊天页左栏是换会话时的重建,不算入场
  private playCascade(): void {
    if (this.cascadeDone || this.inChat) {
      this.cascadeDone = true;
      this.cascadeHeader = true;
      this.cascadeCard = true;
      this.cascadeSection = true;
      this.cascadeRows = true;
      this.cascadeFab = true;
      return;
    }
    this.cascadeDone = true;
    const curve: ICurve = curves.cubicBezierCurve(0.2, 0.8, 0.2, 1);
    const run = (delay: number, apply: () => void): void => {
      setTimeout((): void => {
        if (!this.pageAlive) return;
        animateTo({ duration: 480, curve: curve }, apply);
      }, delay);
    };
    run(60, (): void => { this.cascadeHeader = true; });
    run(100, (): void => { this.cascadeCard = true; });
    run(140, (): void => { this.cascadeSection = true; });
    run(180, (): void => { this.cascadeRows = true; });
    run(220, (): void => { this.cascadeFab = true; });
  }

  aboutToDisappear(): void {
    this.pageAlive = false;
    this.stopRunningSubscription();
    if (this.pendingDeleted !== null) this.commitPendingDelete();
  }

  private observeRunning(): void {
    if (!this.pageAlive || this.unsubscribeRunning !== null) return;
    this.unsubscribeRunning = getConversationRunService().subscribeRunning((ids: string[]): void => {
      if (!this.pageAlive) return;
      const removed: boolean = this.runningIds.some((id: string): boolean => ids.indexOf(id) < 0);
      // 两栏时右侧新会话首条消息即开跑:列表里还没有它,补一次刷新让左栏出现该行
      const added: boolean = ids.some((id: string): boolean =>
        !this.items.some((item: ConversationSummary): boolean => item.id === id));
      this.runningIds = ids;
      if (removed || added) this.refresh();
    });
  }

  private stopRunningSubscription(): void {
    const unsubscribe: (() => void) | null = this.unsubscribeRunning;
    this.unsubscribeRunning = null;
    if (unsubscribe !== null) unsubscribe();
  }

  private refresh(): void {
    const repo = getChatRepository();
    const seq: number = ++this.refreshSeq;
    const future: Promise<ConversationSummary[]> = repo.list();
    future.then((list: ConversationSummary[]): void => {
      if (seq !== this.refreshSeq || !this.pageAlive) return;
      const pendingId: string = this.pendingDeleted !== null ? this.pendingDeleted.id : '';
      const visible: ConversationSummary[] = list.filter(
        (item: ConversationSummary): boolean => item.id !== pendingId && item.id !== pendingDeleteId);
      cachedItems = visible;
      // 首次填充走 animateTo,让列表项交错入场 transition 生效;后续刷新保持即时
      if (!this.loaded && visible.length > 0) {
        animateTo({ duration: MOTION_ENTER, curve: Curve.EaseOut }, (): void => {
          this.items = visible;
          this.loaded = true;
        });
      } else {
        this.items = visible;
        this.loaded = true;
      }

      // 会话行条数: F08 限并发 getById(8) + 聚合后一次批量提交。
      this.loadCounts(visible, seq);
    });
  }

  // F08:受限并发拉取条数;结果先聚合到局部 Record,全部完成后按 seq 校验一次提交。
  private loadCounts(list: ConversationSummary[], seq: number): void {
    const repo = getChatRepository();
    const nextCounts: Record<string, number> = {};
    for (const key of Object.keys(this.msgCounts)) nextCounts[key] = this.msgCounts[key];
    let cursor: number = 0;
    const worker = async (): Promise<void> => {
      while (cursor < list.length) {
        if (seq !== this.refreshSeq || !this.pageAlive) return;
        const summary: ConversationSummary = list[cursor];
        cursor++;
        if (nextCounts[summary.id] !== undefined && cachedCountStamps[summary.id] === summary.updateAt) continue;
        let conversation: Conversation | null = null;
        try {
          conversation = await repo.getById(summary.id);
        } catch (_e) {
          continue;
        }
        if (conversation === null || seq !== this.refreshSeq) continue;
        let count: number = 0;
        for (const node of conversation.messageNodes) {
          if (node.messages.length === 0) continue;
          count++;
        }
        nextCounts[summary.id] = count;
        cachedCountStamps[summary.id] = summary.updateAt;
      }
    };
    const workers: Promise<void>[] = [];
    const workerCount: number = Math.min(PREVIEW_CONCURRENCY, list.length);
    for (let w = 0; w < workerCount; w++) workers.push(worker());
    Promise.all(workers).then((): void => {
      if (seq !== this.refreshSeq || !this.pageAlive) return;
      this.msgCounts = nextCounts;
      cachedCounts = nextCounts;
    }).catch((): void => {});
  }

  // 首页「继续」:小说项目 + 到期/运行中 cron + 深读 + 小应用,合并后取第 1 条
  // (iOS HomeContinueCardModel.resolve 单卡语义;聚合管线与 Index 首页一致)
  private loadContinue(): void {
    const seq: number = ++this.continueSeq;
    const novelList: Promise<ContinueCandidate[]> = getNovelCreation().listProjects()
      .then((projects): ContinueCandidate[] => projects.slice(0, 3).map((p): ContinueCandidate => ({
        sourceKind: 'novel',
        sourceId: p.id,
        route: { kind: 'novel', projectId: p.id },
        title: p.name.length > 0 ? p.name : '未命名小说',
        summary: `${p.chapters.length} 章 · ${p.messages.length} 条讨论`,
        lastUpdatedAt: p.updatedAt,
        status: p.chapters.length > 0 ? 'draft' : 'waiting_user',
        priority: 0,
        isRunning: false,
      })))
      .catch((_err: Error): ContinueCandidate[] => []);
    const cronList: Promise<ContinueCandidate[]> = getAgentCronManager()
      .then((m): Promise<AgentCronTask[]> => m.listTasksSnapshot())
      .then((tasks: AgentCronTask[]): ContinueCandidate[] => {
        const out: ContinueCandidate[] = [];
        for (const t of tasks) {
          if (!t.enabled) continue;
          if (t.lastStatus !== 'Failed' && t.lastStatus !== 'Running') continue;
          if (out.length >= 3) break;
          out.push({
            sourceKind: 'cron',
            sourceId: t.id,
            route: { kind: 'cron', taskId: t.id },
            title: t.title,
            summary: t.lastStatus === 'Failed' ? '上次失败，可重试' : '运行中',
            lastUpdatedAt: t.updatedAtMs,
            status: t.lastStatus === 'Failed' ? 'failed_resumable' : 'running',
            priority: 0,
            isRunning: t.lastStatus === 'Running',
          });
        }
        return out;
      })
      .catch((_err: Error): ContinueCandidate[] => []);
    const deepList: Promise<ContinueCandidate[]> = getDeepReadScheduler()
      .then((scheduler: DeepReadScheduler): Promise<ContinueCandidate[]> =>
        loadDeepReadContinueCandidates(getRepository(), scheduler))
      .catch((_err: Error): ContinueCandidate[] => []);
    const miniList: Promise<ContinueCandidate[]> = getMiniAppRepositorySync().listAll()
      .then((apps): ContinueCandidate[] => apps.slice(0, 2).map((app): ContinueCandidate => ({
        sourceKind: 'miniapp',
        sourceId: app.id,
        route: { kind: 'miniapp', appId: app.id },
        title: app.title,
        summary: '已生成，尚未打开',
        lastUpdatedAt: app.updatedAt,
        status: 'draft',
        priority: 0,
        isRunning: false,
      })))
      .catch((_err: Error): ContinueCandidate[] => []);
    // 议会:运行中的 run(内存态;无持久任务系统,进程存活期内可继续)
    const councilList: Promise<ContinueCandidate[]> = Promise.resolve().then((): ContinueCandidate[] => {
      const mgr = getCouncilManager();
      if (mgr === null) return [];
      return mgr.listActive().map((run: ModelCouncilRun): ContinueCandidate => ({
        sourceKind: 'council',
        sourceId: run.runId,
        route: { kind: 'council', conversationId: '', runId: run.runId },
        title: run.task.objective.length > 0 ? run.task.objective : '模型议会',
        summary: '讨论进行中',
        lastUpdatedAt: run.updatedAtMs,
        status: 'running',
        priority: 0,
        isRunning: true,
      }));
    }).catch((_err: Error): ContinueCandidate[] => []);
    // 生图:最近一条 chat_images 产物 → 直达来源会话(产物即任务实体;
    //   鸿蒙生图无独立任务注册表,诚实以产物为源)
    const imageList: Promise<ContinueCandidate[]> = getFilesRepository().listByFolder('chat_images')
      .then((rows): ContinueCandidate[] => {
        if (rows.length === 0) return [];
        const parts: string[] = rows[0].relativePath.split('/');
        if (parts.length < 3) return [];
        return [{
          sourceKind: 'image',
          sourceId: rows[0].relativePath,
          route: { kind: 'chat', conversationId: parts[1] },
          title: '生成的图片',
          summary: '已生成，可查看或继续修改',
          lastUpdatedAt: rows[0].createdAt,
          status: 'draft',
          priority: 0,
          isRunning: false,
        }];
      })
      .catch((_err: Error): ContinueCandidate[] => []);
    Promise.all([novelList, cronList, deepList, miniList, councilList, imageList]).then((lists): void => {
      if (seq !== this.continueSeq || !this.pageAlive) return;
      const merged: ContinueCandidate[] = mergeContinueCandidates(lists, 6);
      this.continueItem = merged.length > 0 ? merged[0] : null;
      cachedContinue = this.continueItem;
      cachedContinueReady = true;
    }).catch((): void => {
      if (seq !== this.continueSeq || !this.pageAlive) return;
      this.continueItem = null;
    });
  }

  private openContinue(item: ContinueCandidate): void {
    if (item.route.kind === 'novel') {
      this.pushOnce({
        url: 'pages/NovelWorkspacePage',
        params: { projectId: item.route.projectId },
      });
    } else if (item.route.kind === 'cron') {
      this.pushOnce({ url: 'pages/SettingCronTasksPage' });
    } else if (item.route.kind === 'miniapp') {
      this.pushOnce({
        url: 'pages/MiniAppRunnerPage',
        params: { appId: item.route.appId },
      });
    } else if (item.route.kind === 'deepread') {
      this.pushOnce({
        url: 'pages/DeepReadArticlePage',
        params: { topicId: item.route.topicId, title: item.route.title },
      });
    } else if (item.route.kind === 'council') {
      const runId: string = item.route.runId ?? '';
      if (runId.length > 0) {
        this.pushOnce({ url: 'pages/CouncilRunPage', params: { runId: runId } });
      }
    } else if (item.route.kind === 'chat') {
      this.openConversation(item.route.conversationId);
    }
  }

  // 继续卡展示模型(iOS: 主行实体名,次行 功能 · 状态/相对时间)
  private continueIcon(item: ContinueCandidate): string {
    if (item.sourceKind === 'novel') return 'ph_notebook';
    if (item.sourceKind === 'cron') return 'ph_clock';
    if (item.sourceKind === 'deepread') return 'ph_bookOpen';
    if (item.sourceKind === 'council') return 'ph_users';
    if (item.sourceKind === 'image') return 'ph_imageSquare';
    return 'ph_squaresFour';
  }

  private continueTitle(item: ContinueCandidate): string {
    if (item.sourceKind === 'novel') return `《${item.title}》`;
    if (item.sourceKind === 'miniapp') return '小应用';
    return item.title;
  }

  private continueMeta(item: ContinueCandidate): string {
    if (item.sourceKind === 'novel') return `小说创作 · ${relativeTimeMs(item.lastUpdatedAt)}`;
    if (item.sourceKind === 'cron') return `定时任务 · ${item.summary}`;
    if (item.sourceKind === 'deepread') return `深度阅读 · ${item.summary}`;
    if (item.sourceKind === 'council') return `模型议会 · ${item.summary}`;
    if (item.sourceKind === 'image') return `图片生成 · ${item.summary}`;
    return `「${item.title}」 · ${item.summary}`;
  }

  private continueCta(item: ContinueCandidate): string {
    if (item.sourceKind === 'novel') return item.isRunning ? '查看' : '继续';
    if (item.sourceKind === 'cron') return item.isRunning ? '查看' : '继续';
    if (item.sourceKind === 'deepread') return item.isRunning ? '查看' : '继续';
    if (item.sourceKind === 'council') return '查看';
    if (item.sourceKind === 'image') return '查看';
    return '打开';
  }

  private loadLastOpened(): void {
    getChatKvStore().get(LAST_OPEN_KEY).then((value: string | null): void => {
      if (!this.pageAlive) return;
      this.lastOpenedId = value === null ? '' : value;
    }).catch((): void => {});
  }

  private openConversation(id: string): void {
    this.lastOpenedId = id;
    getChatKvStore().put(LAST_OPEN_KEY, id).catch((): void => {});
    const params: Record<string, string> = {};
    params['conversationId'] = id;
    if (this.inChat) {
      if (id !== this.activeId) this.navigateOnce({ url: 'pages/ChatPage', params: params }, true);
      return;
    }
    this.pushOnce({ url: 'pages/ChatPage', params: params });
  }

  private expandSearch(): void {
    animateTo({ duration: 320, curve: curves.cubicBezierCurve(0.2, 0.8, 0.2, 1) }, (): void => {
      this.searchExpanded = true;
    });
    // iOS 展开后 170ms 聚焦;离场/收起前取消由状态守卫兜底
    setTimeout((): void => {
      if (!this.pageAlive || !this.searchExpanded) return;
      this.getUIContext().getFocusController().requestFocus('homeSearch');
    }, 170);
  }

  private collapseSearch(): void {
    cachedSearchQuery = '';
    if (!this.searchExpanded) return;
    animateTo({ duration: 320, curve: curves.cubicBezierCurve(0.2, 0.8, 0.2, 1) }, (): void => {
      this.searchExpanded = false;
      this.searchQuery = '';
    });
  }

  private submitSearch(): void {
    const query: string = this.searchQuery.trim();
    this.collapseSearch();
    const params: Record<string, string> = {};
    if (query.length > 0) params['query'] = query;
    this.pushOnce({ url: 'pages/SearchPage', params: params });
  }

  private newConversation(): void {
    this.collapseSearch();
    // 右侧已是还没落库的空白新对话:不必重建整页
    if (this.inChat && this.loaded
      && !this.items.some((item: ConversationSummary): boolean => item.id === this.activeId)) return;
    this.navigateOnce({ url: 'pages/ChatPage' }, this.inChat);
  }

  private pushOnce(options: router.RouterOptions): void {
    this.navigateOnce(options, false);
  }

  // 防连点叠页:300ms 冷却
  private navigateOnce(options: router.RouterOptions, replace: boolean): void {
    const now: number = Date.now();
    if (now < this.navCooldownUntil) return;
    this.navCooldownUntil = now + 300;
    const pending: Promise<void> = replace ? router.replaceUrl(options) : router.pushUrl(options);
    pending.catch((): void => { this.navCooldownUntil = 0; });
  }

  private togglePin(item: ConversationSummary): void {
    getConversationRunService().mutateStoredConversation(item.id, (latest: Conversation): Conversation =>
      patchConversation(latest, { isPinned: !latest.isPinned })).then((updated: Conversation | null): void => {
      if (updated !== null) this.refresh();
    });
  }

  private onDelete(item: ConversationSummary): void {
    if (this.snackVisible && this.pendingDeleted !== null) {
      this.commitPendingDelete();
    }
    const repo = getChatRepository();
    repo.getById(item.id).then((fullConversation: Conversation | null): void => {
      if (fullConversation === null) return;
      this.pendingDeleted = fullConversation;
      pendingDeleteId = fullConversation.id;
      animateTo({ duration: 220, curve: Curve.EaseOut }, (): void => {
        this.snackVisible = true;
      });
      this.refresh();
      this.snackTimer = setTimeout((): void => {
        this.commitPendingDelete();
      }, SNACK_DURATION_MS);
      // 两栏删的是右侧正打开的会话:右栏换成新对话,不能停在待删会话上继续写
      //   (换页会销毁本左栏并立即提交删除,撤销条随之消失)
      if (this.inChat && fullConversation.id === this.activeId) {
        this.navigateOnce({ url: 'pages/ChatPage' }, true);
      }
    }).catch((error: Error): void => {
      promptAction.showToast({ message: `删除失败：${error.message}`, duration: 1800 });
    });
  }

  private clearSnackTimer(): void {
    if (this.snackTimer >= 0) {
      clearTimeout(this.snackTimer);
      this.snackTimer = -1;
    }
  }

  private undoDelete(): void {
    this.pendingDeleted = null;
    pendingDeleteId = '';
    this.clearSnackTimer();
    animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
      this.snackVisible = false;
    });
    this.refresh();
  }

  private commitPendingDelete(): void {
    const fullConversation: Conversation | null = this.pendingDeleted;
    this.pendingDeleted = null;
    this.clearSnackTimer();
    animateTo({ duration: 200, curve: Curve.EaseIn }, (): void => {
      this.snackVisible = false;
    });
    if (fullConversation === null) return;
    const settle = (): void => {
      if (pendingDeleteId === fullConversation.id) pendingDeleteId = '';
      bumpHomeListVersion();
    };
    if (getConversationRunService().isBusy(fullConversation.id)) {
      if (this.pageAlive) {
        promptAction.showToast({ message: '该对话仍在运行，请等待任务结束后再删除', duration: 2000 });
      }
      settle();
      return;
    }
    const repo = getChatRepository();
    const commit = async (): Promise<void> => {
      const release: () => void = beginConversationRestore([fullConversation.id]);
      try {
        await repo.delete(fullConversation.id);
        getConversationRunService().forgetDeletedConversation(fullConversation.id);
        await cleanupDeletedConversation(fullConversation, repo, getChatKvStore());
      } finally { release(); }
    };
    commit()
      .then((): void => { settle(); })
      .catch((error: Error): void => {
        settle();
        if (!this.pageAlive) return;
        promptAction.showToast({ message: `删除清理失败：${error.message}`, duration: 2000 });
      });
  }

  private previewFilter(list: ConversationSummary[]): ConversationSummary[] {
    const query: string = this.searchQuery.trim().toLowerCase();
    if (query.length === 0) return list;
    return list.filter((item: ConversationSummary): boolean => {
      const title: string = item.title.length > 0 ? item.title : '新对话';
      return title.toLowerCase().includes(query);
    });
  }

  private messageCount(id: string): number {
    const count: number | undefined = this.msgCounts[id];
    return count === undefined ? 0 : count;
  }

  private isActive(id: string): boolean {
    const activeId: string = this.twoPane ? this.activeId : this.lastOpenedId;
    return activeId.length > 0 && activeId === id;
  }

  // 行间 hairline:末行不画;当前行与下一行若为激活行也让位(iOS:避免与 accent 色带打架)
  private hideRowLine(idx: number): boolean {
    const list: ConversationSummary[] = this.previewFilter(this.items);
    if (idx >= list.length - 1) return true;
    if (this.isActive(list[idx].id)) return true;
    return this.isActive(list[idx + 1].id);
  }

  private onFeature(id: string): void {
    if (id === 'reader') this.pushOnce({ url: 'pages/BoardPage' });
    else if (id === 'novel') this.pushOnce({ url: 'pages/NovelProjectsPage' });
    else if (id === 'council') this.pushOnce({ url: 'pages/CouncilPage' });
    else if (id === 'apps') this.pushOnce({ url: 'pages/MiniAppListPage' });
    else if (id === 'webmount') this.pushOnce({ url: 'pages/SettingExperimentalWebMountPage' });
    else promptAction.showToast({ message: '该功能即将上线', duration: 1500 });
  }

  private showRowMenu(item: ConversationSummary): void {
    promptAction.showActionMenu({
      buttons: [
        { text: item.isPinned ? '取消置顶' : '置顶', color: HOME_INK },
        { text: '重命名', color: HOME_INK },
        { text: '复制 Markdown', color: HOME_INK },
        { text: '导出 Markdown 文件', color: HOME_INK },
        { text: '删除', color: ERROR },
      ],
    }).then((result: ActionMenuResult): void => {
      if (result.index === 0) this.togglePin(item);
      else if (result.index === 1) this.openRename(item);
      else if (result.index === 2) this.exportMarkdown(item);
      else if (result.index === 3) this.exportMarkdownFile(item);
      else if (result.index === 4) this.onDelete(item);
    }).catch((): void => {});
  }

  private openRename(item: ConversationSummary): void {
    this.renameId = item.id;
    this.renameInput = item.title;
    this.renameOpen = true;
  }

  private submitRename(): void {
    const title: string = this.renameInput.trim();
    const id: string = this.renameId;
    this.renameOpen = false;
    if (title.length === 0 || id.length === 0) return;
    getConversationRunService().mutateStoredConversation(id, (latest: Conversation): Conversation =>
      patchConversation(latest, { title })).then((updated: Conversation | null): void => {
      if (updated !== null && this.pageAlive) this.refresh();
    }).catch((): void => {});
  }

  // 会话导出 Markdown → 剪贴板
  private exportMarkdown(item: ConversationSummary): void {
    getChatRepository().getById(item.id).then((conv: Conversation | null): void => {
      if (conv === null) {
        promptAction.showToast({ message: '会话不存在', duration: 1600 });
        return;
      }
      const md: string = exportConversationMarkdown(conv, { fallbackTitle: '未命名对话' });
      const data = pasteboard.createData(pasteboard.MIMETYPE_TEXT_PLAIN, md);
      const pb: pasteboard.SystemPasteboard = pasteboard.getSystemPasteboard();
      pb.setData(data).then((): void => {
        promptAction.showToast({ message: '已复制 Markdown 到剪贴板', duration: 1800 });
      }).catch((e: Error): void => {
        promptAction.showToast({ message: `导出失败：${e.message}`, duration: 2000 });
      });
    }).catch((e: Error): void => {
      promptAction.showToast({ message: `导出失败：${e.message}`, duration: 2000 });
    });
  }

  // 会话导出 Markdown 文件(DocumentViewPicker;失败回落 filesDir/exports)
  private exportMarkdownFile(item: ConversationSummary): void {
    getChatRepository().getById(item.id).then((conv: Conversation | null): void => {
      if (conv === null) {
        promptAction.showToast({ message: '会话不存在', duration: 1600 });
        return;
      }
      const md: string = exportConversationMarkdown(conv, { fallbackTitle: '未命名对话' });
      const fileName: string = exportFileNameFor(conv, 'md');
      const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
      const docPicker = new picker.DocumentViewPicker(ctx);
      docPicker.save({ newFileNames: [fileName] }).then((uris: string[]): void => {
        if (uris.length > 0) {
          this.writeExportFile(uris[0], md, fileName);
        } else {
          this.exportToAppDir(md, fileName);
        }
      }).catch((): void => {
        this.exportToAppDir(md, fileName);
      });
    }).catch((e: Error): void => {
      promptAction.showToast({ message: `导出失败：${e.message}`, duration: 2000 });
    });
  }

  private writeExportFile(uri: string, content: string, fileName: string): void {
    try {
      const file: fileIo.File = fileIo.openSync(
        uri, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: `已导出 ${fileName}`, duration: 2000 });
    } catch (e) {
      const msg: string = (e as Error).message;
      promptAction.showToast({ message: `导出失败：${msg}`, duration: 2000 });
    }
  }

  private exportToAppDir(content: string, fileName: string): void {
    const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
    const dir: string = `${ctx.filesDir}/exports`;
    try {
      fileIo.mkdirSync(dir, true);
    } catch {
      // 已存在
    }
    const path: string = `${dir}/${fileName}`;
    try {
      const file: fileIo.File = fileIo.openSync(
        path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.writeSync(file.fd, util.TextEncoder.create('utf-8').encodeInto(content));
      fileIo.closeSync(file.fd);
      promptAction.showToast({ message: `已保存到应用目录 ${fileName}`, duration: 2200 });
    } catch (e) {
      const msg: string = (e as Error).message;
      promptAction.showToast({ message: `导出失败：${msg}`, duration: 2000 });
    }
  }


  @Builder
  SearchCapsuleContent(): void {
    Row() {
      Icon({ name: 'ph_magnifyingGlass', iconSize: 14, color: HOME_MUTED })
      // 两栏收成圆钮,只留放大镜
      if (!this.twoPane) {
        Text('搜索')
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .letterSpacing(0.26)
          .fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(HOME_MUTED)
          .margin({ left: 6 })
      }
    }
  }

  @Builder
  GearContent(): void {
    Icon({ name: 'ph_gear', iconSize: 20, color: this.accentColorStore })
  }

  @Builder
  AvatarContent(): void {
    if (this.userAvatar.length > 0) {
      Image(this.userAvatar.startsWith('http') || this.userAvatar.startsWith('data:')
        || this.userAvatar.startsWith('file://') || this.userAvatar.startsWith('resource:')
        ? this.userAvatar : `file://${this.userAvatar}`)
        .width(38)
        .height(38)
        .borderRadius(19)
        .objectFit(ImageFit.Cover)
    } else {
      Stack() {
        Text((this.userNickname.length > 0 ? this.userNickname : 'A').charAt(0).toUpperCase())
          .fontSize(14.5)
          .fontWeight(FontWeight.Medium)
          .fontFamily(FONT_SANS_SEMIBOLD)
          .fontColor(HOME_MUTED2)
      }
      .width(38)
      .height(38)
      .borderRadius(19)
      .backgroundColor(HOME_CARD2)
    }
  }

  @Builder
  NewChatIconContent(): void {
    Icon({ name: 'ph_pencil', iconSize: 18, color: ACCENT_INK })
  }

  @Builder
  FabContent(): void {
    Row() {
      Icon({ name: 'ph_pencil', iconSize: 14, color: ACCENT_INK })
      Text('新对话')
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .letterSpacing(0.2)
        .fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(ACCENT_INK)
        .margin({ left: 6 })
    }
    .padding({ left: 14, right: 14 })
  }

  @Builder
  HeaderBlock(): void {
    Column() {
      Row() {
        // 两栏左栏窄(320–400vp):词标缩一档、搜索收成圆钮,给顶栏新对话让位
        AmberWordmark({ color: HOME_INK, cell: this.twoPane ? 3.5 : 4 })
        Blank()
        if (!this.searchExpanded) {
          PressGlowButton({
            accentColor: this.accentColorStore,
            radius: 19,
            btnWidth: this.twoPane ? 38 : 78,
            btnHeight: 38,
            onTap: (): void => { this.expandSearch(); },
            content: (): void => { this.SearchCapsuleContent(); },
          })
            .margin({ right: this.twoPane ? 6 : 8 })
            .accessibilityText('搜索')
        }

        // 齿轮(iOS: glyph 随 accent)
        PressGlowButton({
          accentColor: this.accentColorStore,
          radius: 19,
          btnWidth: 38,
          btnHeight: 38,
          onTap: (): void => {
            this.collapseSearch();
            router.pushUrl({ url: 'pages/SettingPage' });
          },
          content: (): void => { this.GearContent(); },
        })
          .accessibilityText('设置')

        // 账户头像 38(iOS: 图或昵称首字,avatarIdle 底)
        PressGlowButton({
          accentColor: this.accentColorStore,
          radius: 19,
          bgMode: 'plain',
          btnWidth: 38,
          btnHeight: 38,
          onTap: (): void => {
            this.collapseSearch();
            router.pushUrl({ url: 'pages/ProfilePage' });
          },
          content: (): void => { this.AvatarContent(); },
        })
          .margin({ left: this.twoPane ? 6 : 10 })
          .accessibilityText('我的账户')

        // 两栏:新对话从右下胶囊收进顶栏(对齐 Android SessionHome onNewSession)
        if (this.twoPane) {
          PressGlowButton({
            accentColor: this.accentColorStore,
            radius: 19,
            bgMode: 'solid',
            btnWidth: 38,
            btnHeight: 38,
            onTap: (): void => { this.newConversation(); },
            content: (): void => { this.NewChatIconContent(); },
          })
            .margin({ left: 6 })
            .accessibilityText('新建聊天')
        }
      }
      .width('100%')
      .constraintSize({ minHeight: 38 })
      .alignItems(VerticalAlign.Center)
      .padding({ left: 16, right: 16 })

      // 展开的全宽搜索条(高 41,圆角 14,聚焦环 accent)
      // 横向内缩走外层 Column padding:width('100%') + margin 在本布局下会溢出右缘
      if (this.searchExpanded) {
        Column() {
        Row() {
          Icon({ name: 'ph_magnifyingGlass', iconSize: 14, color: HOME_MUTED2 })
          TextInput({ text: this.searchQuery, placeholder: '搜索会话' })
            .id('homeSearch')
            .layoutWeight(1)
            .fontSize(17)
            .fontFamily(FONT_SANS)
            .fontColor(HOME_INK)
            .placeholderColor(HOME_MUTED2)
            .backgroundColor(Color.Transparent)
            .padding({ left: 14 })
            .onChange((value: string): void => {
              this.searchQuery = value;
              cachedSearchQuery = value;
            })
            .onFocus((): void => { this.searchFocused = true; })
            .onBlur((): void => { this.searchFocused = false; })
            .onSubmit((): void => { this.submitSearch(); })
          Text('取消')
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .letterSpacing(0.26)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(HOME_MUTED)
            .padding({ left: 12, right: 2, top: 6, bottom: 6 })
            .onClick((): void => { this.collapseSearch(); })
        }
        .width('100%')
        .height(41)
        .alignItems(VerticalAlign.Center)
        .padding({ left: 16, right: 16 })
        .borderRadius(14)
        .linearGradient({
          direction: GradientDirection.Bottom,
          colors: [[HOME_GLASS_TOP, 0], [HOME_GLASS_BOTTOM, 1]],
        })
        .border({
          width: this.searchFocused ? 1.5 : 0.5,
          color: this.searchFocused ? accentAlpha(this.accentColorStore, 0.55) : HOME_GLASS_EDGE,
        })
        .shadow({ radius: 10, color: HOME_GLASS_SHADOW, offsetY: 4 })
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 15 })
      .transition(TransitionEffect.OPACITY
        .combine(TransitionEffect.translate({ y: -8 }))
        .animation({ duration: 320, curve: curves.cubicBezierCurve(0.2, 0.8, 0.2, 1) }))
      }
    }
    .width('100%')
    .padding({ top: 10, bottom: 4 })
    .opacity(this.cascadeHeader ? 1 : 0)
    .translate({ y: this.cascadeHeader ? 0 : 8 })
  }

  @Builder
  ContinueRow(): void {
    if (this.continueItem !== null) {
      // 内缩走外层 Column padding:Row 直接 width('100%')+padding 会溢出卡片右缘
      Column() {
        Row() {
          // 功能图标座:44 圆角方,accent 浅染底 + accent 墨
          Row() {
            Icon({ name: this.continueIcon(this.continueItem), iconSize: 22, color: this.accentColorStore })
          }
          .width(44)
          .height(44)
          .borderRadius(15)
          .backgroundColor(accentAlpha(this.accentColorStore, this.darkMode ? 0.32 : 0.18))
          .justifyContent(FlexAlign.Center)
          .alignItems(VerticalAlign.Center)

          Column() {
            Text(this.continueTitle(this.continueItem))
              .fontSize(15)
              .fontWeight(FontWeight.Medium)
              .lineHeight(20)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(HOME_INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(this.continueMeta(this.continueItem))
              .fontSize(11)
              .lineHeight(14)
              .letterSpacing(0.11)
              .fontFamily(FONT_SANS)
              .fontColor(HOME_MUTED)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
              .margin({ top: 7 })
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          .margin({ left: 14, right: 12 })

          // 续接 CTA:浅强调底 + 主墨字(次级动作;主强调留给右下新对话)
          Text(this.continueCta(this.continueItem))
            .fontSize(13)
            .fontWeight(FontWeight.Medium)
            .letterSpacing(0.26)
            .fontFamily(FONT_SANS_SEMIBOLD)
            .fontColor(HOME_INK)
            .padding({ left: 18, right: 18, top: 7, bottom: 7 })
            .constraintSize({ minHeight: 32 })
            .borderRadius(999)
            .backgroundColor(accentAlpha(this.accentColorStore, 0.12))
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .padding({ left: 16, right: 16, top: 18, bottom: 18 })
      .backgroundColor(this.pressedContinue ? HOME_PRESS : Color.Transparent)
      .onTouch((event: TouchEvent): void => {
        if (event.type === TouchType.Down) this.pressedContinue = true;
        else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressedContinue = false;
      })
      .onClick((): void => {
        if (this.continueItem !== null) this.openContinue(this.continueItem);
      })
    }
  }

  @Builder
  ShortcutItem(id: string, label: string, icon: string): void {
    Column() {
      Icon({
        name: icon,
        iconSize: 20,
        color: this.pressedFeature === id ? HOME_INK : HOME_MUTED,
      })
      Text(label)
        .fontSize(11)
        .fontWeight(FontWeight.Medium)
        .lineHeight(14)
        .letterSpacing(0.11)
        .fontFamily(FONT_SANS_SEMIBOLD)
        .fontColor(this.pressedFeature === id ? HOME_INK : HOME_MUTED)
        .maxLines(1)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .margin({ top: 6 })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .scale({ x: this.pressedFeature === id ? 1.06 : 1, y: this.pressedFeature === id ? 1.06 : 1 })
    .animation({ duration: 300, curve: curves.springCurve(0, 1, 200, 22) })
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.pressedFeature = id;
      else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressedFeature = '';
    })
    .onClick((): void => { this.onFeature(id); })
  }

  @Builder
  ControlCard(): void {
    // 关键:卡片自身禁 margin — ArkUI 的 margin 只缩视觉盒,子级 100% 仍按
    // margin 前宽度解析(继续 CTA 溢出右缘根因);内缩一律走外层 Column padding
    Column() {
      Column() {
        if (this.continueItem !== null) {
          this.ContinueRow()
          Row()
            .width('100%')
            .height(0.5)
            .backgroundColor(HOME_SEP)
            .margin({ left: 16, right: 16 })
        }
        Row() {
          Column() { this.ShortcutItem('reader', '深度阅读', 'ph_bookOpen') }.height('100%').layoutWeight(1)
          Column() { this.ShortcutItem('novel', '小说创作', 'ph_notebook') }.height('100%').layoutWeight(1)
          Column() { this.ShortcutItem('council', '模型议会', 'ph_chatCircleDots') }.height('100%').layoutWeight(1)
          Column() { this.ShortcutItem('apps', '小应用', 'ph_squaresFour') }.height('100%').layoutWeight(1)
          Column() { this.ShortcutItem('webmount', '站点', 'ph_globe') }.height('100%').layoutWeight(1)
        }
        .width('100%')
        .height(64)
        .padding({ top: 9, bottom: 11 })
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .backgroundColor(HOME_CARD)
      .borderRadius(HOME_RADIUS)
      .shadow({ radius: 7, color: HOME_SHADOW, offsetY: 5 })
    }
    .width('100%')
    .padding({ left: 16, right: 16, top: 20 })
    .opacity(this.cascadeCard ? 1 : 0)
    .translate({ y: this.cascadeCard ? 0 : 8 })
  }

  @Builder
  ConversationRow(item: ConversationSummary, isFirst: boolean, isLast: boolean, hideLine: boolean): void {
    Column() {
      // 内容内缩走外层 Column padding(width('100%')+padding 会溢出卡片右缘)
      Column() {
        Row() {
          // 图标座:40 圆,置顶 = 图钉(iOS: pin 替换主题图标,不另挂角标)
          Stack() {
            Icon({
              name: item.isPinned ? 'ph_pushPin' : conversationIcon(item.title.length > 0 ? item.title : '新对话'),
              iconSize: 20,
              color: this.isActive(item.id) ? this.accentColorStore : HOME_MUTED2,
            })
          }
          .width(40)
          .height(40)
          .borderRadius(20)
          .backgroundColor(this.isActive(item.id)
            ? accentAlpha(this.accentColorStore, this.darkMode ? 0.32 : 0.18)
            : HOME_CARD2)

          Column() {
            Text(item.title.length > 0 ? item.title : '新对话')
              .fontSize(16)
              .fontWeight(FontWeight.Medium)
              .lineHeight(21)
              .letterSpacing(-0.08)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(HOME_INK)
              .maxLines(1)
              .textOverflow({ overflow: TextOverflow.Ellipsis })
            Row() {
              Text(relativeTimeIso(item.updateAt))
                .fontSize(11)
                .letterSpacing(0.11)
                .fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED)
                .maxLines(1)
              Text('·')
                .fontSize(11)
                .fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED2)
                .margin({ left: 6, right: 6 })
              Text(`${this.messageCount(item.id)} 条`)
                .fontSize(11)
                .letterSpacing(0.11)
                .fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED)
                .maxLines(1)
            }
            .margin({ top: 7 })
          }
          .layoutWeight(1)
          .alignItems(HorizontalAlign.Start)
          .margin({ left: 13 })
          if (this.runningIds.indexOf(item.id) >= 0) {
            RunningIndicator().margin({ left: 8 }).accessibilityText('运行中')
          }
        }
        .width('100%')
        .alignItems(VerticalAlign.Center)
      }
      .width('100%')
      .padding({ left: 17, right: 16, top: 12, bottom: 12 })
      .constraintSize({ minHeight: 72 })

      // 卡内 hairline:左缩进 70 对齐标题(iOS 同款);内缩走外层 padding,
      //   width('100%') + margin 会越出卡片右缘
      if (!hideLine) {
        Column() {
          Row()
            .width('100%')
            .height(0.5)
            .backgroundColor(HOME_ROWLINE)
        }
        .width('100%')
        .padding({ left: 70, right: 16 })
      }
    }
    .width('100%')
    .backgroundColor(this.pressedId === item.id
      ? HOME_PRESS
      : (this.isActive(item.id) ? accentAlpha(this.accentColorStore, this.darkMode ? 0.16 : 0.09) : HOME_CARD))
    .borderRadius({
      topLeft: isFirst ? HOME_RADIUS : 0,
      topRight: isFirst ? HOME_RADIUS : 0,
      bottomLeft: isLast ? HOME_RADIUS : 0,
      bottomRight: isLast ? HOME_RADIUS : 0,
    })
    .shadow((isFirst || isLast) ? { radius: 7, color: HOME_SHADOW, offsetY: 5 } : { radius: 0, color: Color.Transparent })
    .onTouch((event: TouchEvent): void => {
      if (event.type === TouchType.Down) this.pressedId = item.id;
      else if (event.type === TouchType.Up || event.type === TouchType.Cancel) this.pressedId = '';
    })
    .onClick((): void => { this.openConversation(item.id); })
    .gesture(
      LongPressGesture()
        .onAction((): void => { this.showRowMenu(item); })
    )
  }

  @Builder
  SwipePin(item: ConversationSummary): void {
    Column() {
      Icon({ name: 'ph_pushPin', iconSize: 20, color: this.accentColorStore })
      Text(item.isPinned ? '取消置顶' : '置顶')
        .fontSize(12)
        .fontColor(this.accentColorStore)
        .margin({ top: 6 })
    }
    .width(88)
    .height('100%')
    .backgroundColor(HOME_CARD2)
    .borderRadius({ topLeft: 0, bottomLeft: 0, topRight: HOME_RADIUS, bottomRight: HOME_RADIUS })
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .onClick((): void => { this.togglePin(item); })
  }

  @Builder
  SwipeDelete(item: ConversationSummary): void {
    Column() {
      Icon({ name: 'trash', iconSize: 20, color: ACCENT_INK })
      Text('删除')
        .fontSize(12)
        .fontColor(ACCENT_INK)
        .margin({ top: 6 })
    }
    .width(88)
    .height('100%')
    .backgroundColor(this.accentColorStore)
    .borderRadius({ topLeft: HOME_RADIUS, bottomLeft: HOME_RADIUS, topRight: 0, bottomRight: 0 })
    .justifyContent(FlexAlign.Center)
    .alignItems(HorizontalAlign.Center)
    .onClick((): void => { this.onDelete(item); })
  }

  build(): void {
    Stack({ alignContent: Alignment.Bottom }) {
      // 主题纪元重挂载(勿拆):epoch 变化 → 整页子树销毁重建,全量重读令牌
      ForEach([this.themeEpoch], (_e: number): void => {
      Stack({ alignContent: Alignment.BottomEnd }) {
        // 画布纹理层(patterns/点阵,压在渐变上、内容下;共享组件自绘)
        DesignCanvasLayer({ surface: 'home' })

        List({ scroller: this.scroller }) {
          ListItem() {
            this.HeaderBlock()
          }

          ListItem() {
            this.ControlCard()
          }

          // 子代理跨会话活动条(C5-4;运行中才渲染)
          ListItem() {
            SubAgentActivityStrip()
          }

          ListItem() {
            Text('会话')
              .fontSize(11)
              .fontWeight(FontWeight.Medium)
              .letterSpacing(0.11)
              .fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(HOME_INK2)
              .width('100%')
              .textAlign(TextAlign.Start)
              .padding({ left: 16, right: 16, top: 26, bottom: 12 })
              .opacity(this.cascadeSection ? 1 : 0)
              .translate({ y: this.cascadeSection ? 0 : 8 })
              .onClick((): void => { this.collapseSearch(); })
          }

          if (!this.loaded) {
            ListItem() {
              Text('加载会话中…')
                .fontSize(13)
                .fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED)
                .width('100%')
                .textAlign(TextAlign.Center)
                .padding({ top: 48, bottom: 48 })
            }
          } else if (this.previewFilter(this.items).length === 0) {
            ListItem() {
              Column() {
                Text(this.searchQuery.trim().length > 0 ? '没有匹配的会话' : '还没有会话')
                  .fontSize(15)
                  .fontFamily(FONT_SANS)
                  .fontColor(HOME_MUTED)
                  .width('100%')
                  .textAlign(TextAlign.Center)
                  .padding({ left: 24, right: 24, top: 20, bottom: 20 })
                  .constraintSize({ minHeight: 120 })
                  .backgroundColor(HOME_CARD)
                  .borderRadius(HOME_RADIUS)
                  .shadow({ radius: 7, color: HOME_SHADOW, offsetY: 5 })
              }
              .width('100%')
              .padding({ left: 16, right: 16 })
              .opacity(this.cascadeRows ? 1 : 0)
              .translate({ y: this.cascadeRows ? 0 : 8 })
            }
          } else {
            ForEach(this.previewFilter(this.items), (item: ConversationSummary, idx: number): void => {
              ListItem() {
                Column() {
                  this.ConversationRow(
                    item, idx === 0, idx === this.previewFilter(this.items).length - 1,
                    this.hideRowLine(idx))
                }
                .width('100%')
                .padding({ left: 16, right: 16 })
              }
              .swipeAction({
                start: (): void => { this.SwipePin(item); },
                end: (): void => { this.SwipeDelete(item); },
                edgeEffect: SwipeEdgeEffect.None,
              })
              // 聊天页左栏是换会话时的重建,不重播逐行入场
              .transition(this.inChat ? TransitionEffect.IDENTITY : TransitionEffect.OPACITY
                .combine(TransitionEffect.translate({ y: 16 }))
                .animation({ duration: MOTION_ENTER, curve: Curve.EaseOut,
                  delay: Math.min(idx * MOTION_STAGGER, 240) }))
            }, (item: ConversationSummary): string => item.id + (this.darkMode ? '-dark' : '-light'))
          }

          // 末行让过右下胶囊(高 42 + 余量 36;iOS homeNewChatCapsuleListClearance);两栏无胶囊
          ListItem() {
            Column().width('100%').height((this.twoPane ? 24 + this.navBarHeight : 78) + (this.snackVisible ? 60 : 0))
          }
        }
        .width('100%')
        .height('100%')
        .padding({ top: this.statusBarHeight })
        .onDidScroll((): void => { cachedScrollY = this.scroller.currentOffset().yOffset; })
        .onAppear((): void => { if (this.inChat) this.restoreScroll(); })
        .scrollBar(BarState.Off)
        .divider({ strokeWidth: 0, color: Color.Transparent })
        .onClick((): void => { this.collapseSearch(); })

        // 右下拇指区:新对话胶囊(accent 底 + on-accent 墨;iOS amberProminentGlass 语义)
        // 按住光晕随 accent(同色透亮,非白按钮绿边情形),辐射半径加大
        if (!this.twoPane) {
        PressGlowButton({
          accentColor: this.accentColorStore,
          radius: 21,
          bgMode: 'solid',
          btnHeight: 42,
          haloColor: accentAlpha(this.accentColorStore, 0.9),
          haloRadius: 32,
          onTap: (): void => { this.newConversation(); },
          content: (): void => { this.FabContent(); },
        })
          .margin({ right: 28, bottom: 52 })
          .opacity(this.cascadeFab ? 1 : 0)
          .translate({ y: this.cascadeFab ? 0 : 8 })
          .accessibilityText('新建聊天')
        }
      }
      .width('100%')
      .height('100%')

      if (this.snackVisible) {
        Row() {
          Text('对话已删除').fontSize(12).fontColor(Color.White).fontFamily(FONT_SANS)
          Blank()
          Text('撤销')
            .fontSize(12)
            .fontColor(this.accentColorStore)
            .fontFamily(FONT_SANS_MEDIUM)
            .padding({ left: 16, top: 6, bottom: 6 })
            .onClick((): void => { this.undoDelete(); })
        }
        .width('92%')
        .padding({ left: 16, right: 16, top: 10, bottom: 10 })
        .backgroundColor(SNACK_BG)
        .borderRadius(8)
        .shadow({ radius: 12, color: '#33000000', offsetY: 4 })
        .margin({ bottom: 24 + this.navBarHeight })
        .transition(TransitionEffect.OPACITY
          .combine(TransitionEffect.translate({ y: 14 }))
          .animation({ duration: 220, curve: Curve.EaseOut }))
      }

      // 重命名对话框叠层(与小说项目库同名交互对齐)
      if (this.renameOpen) {
        Column() {
          Column() {
            Text('重命名会话')
              .fontSize(17).fontWeight(600).fontFamily(FONT_SANS_SEMIBOLD)
              .fontColor(HOME_INK).margin({ bottom: 16 })
            TextInput({ text: this.renameInput, placeholder: '会话标题' })
              .height(46).fontSize(15).fontFamily(FONT_SANS)
              .fontColor(HOME_INK).placeholderColor(HOME_MUTED)
              .backgroundColor(HOME_CARD2).borderRadius(10).padding({ left: 14, right: 14 })
              .onChange((v: string): void => { this.renameInput = v; })
            Row() {
              Text('取消').fontSize(14).fontFamily(FONT_SANS)
                .fontColor(HOME_MUTED).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick((): void => { this.renameOpen = false; })
              Text('保存').fontSize(14).fontFamily(FONT_SANS_MEDIUM)
                .fontColor(this.accentColorStore).layoutWeight(1).textAlign(TextAlign.Center)
                .onClick((): void => { this.submitRename(); })
            }
            .width('100%').margin({ top: 20 })
          }
          .width('80%').padding(22).backgroundColor(HOME_CARD).borderRadius(16)
          .shadow({ radius: 24, color: HOME_SHADOW, offsetY: 8 })
        }
        .width('100%').height('100%').backgroundColor(OVERLAY)
        .justifyContent(FlexAlign.Center)
        .transition(TransitionEffect.OPACITY
          .animation({ duration: 220, curve: Curve.EaseOut }))
        .onClick((): void => { this.renameOpen = false; })
      }
    
      }, (e: number): string => `theme-${e}`)
}
      .opacity(this.themeEpoch >= 0 ? 1 : 1)
    .width('100%')
    .height('100%')
    // 画布底色:包渐变(无渐变时 canvasGradientColors 退化为 HOME_BG 平涂;角度 iOS→ArkUI 已换算)
    .linearGradient({
      angle: currentCanvasGradientAngle(),
      colors: canvasGradientColors(),
    })
  }
}
