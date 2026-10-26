import { util } from '@kit.ArkTS';

// accent 透明度混色(iOS rgba 语法;ArkUI 直接收 '#AARRGGBB' 也受支持但令牌是 #RRGGBB)
export const accentAlpha = (hex: string, alpha: number): string => {
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

export const conversationIcon = (title: string): string => {
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
export const relativeTimeMs = (ms: number): string => {
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

export const relativeTimeIso = (iso: string): string => {
  const ms: number = new Date(iso).getTime();
  if (isNaN(ms)) return iso.length > 10 ? iso.substring(0, 10) : iso;
  return relativeTimeMs(ms);
};

