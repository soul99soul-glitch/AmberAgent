// 预置占位内容 — 让 UI 能直接看到杂志排版效果
// 无需网络也能验证视觉层级、留白、衬线、hairline 排版

import type { HotItem, SourceSection } from '../platform_impl/NewsNowProvider.ets';

export const MOCK_COMBINED: HotItem[] = [
  { rank: 1, title: '量子计算突破:Google 发布新一代纠错芯片,量子比特错误率降至 0.1%', url: 'https://example.com/q1', heat: '1.2M 热度' },
  { rank: 2, title: '苹果 Vision Pro 2 或将搭载 M4 芯片,重量减轻 20%', url: 'https://example.com/q2', heat: '856K 热度' },
  { rank: 3, title: '央行降息 25 基点,A 股应声上涨 2.3%,成交额突破 1.8 万亿', url: 'https://example.com/q3', heat: '742K 热度' },
  { rank: 4, title: 'OpenAI 发布 GPT-5 技术报告:多模态推理能力提升 40%', url: 'https://example.com/q4', heat: '698K 热度' },
  { rank: 5, title: '特斯拉上海工厂二期投产,Model Y 产能提升 50%', url: 'https://example.com/q5', heat: '534K 热度' },
  { rank: 6, title: '诺贝尔奖得主团队发现全新 CRISPR 变体,基因编辑精度提升 10 倍', url: 'https://example.com/q6', heat: '489K 热度' },
  { rank: 7, title: 'SpaceX 星舰第七次试飞成功,回收准确率 98%', url: 'https://example.com/q7', heat: '412K 热度' },
  { rank: 8, title: '抖音电商 GMV 突破 3 万亿,直播带货占比 60%', url: 'https://example.com/q8', heat: '378K 热度' },
];

export const MOCK_SECTIONS: SourceSection[] = [
  {
    sourceId: 'zhihu',
    sourceName: '知乎热榜',
    items: [
      { rank: 1, title: '如何看待 2025 年 AI 大模型竞赛进入"推理元年"?', url: 'https://example.com/z1', heat: '3.4M 热度' },
      { rank: 2, title: '量子计算突破对密码学意味着什么?', url: 'https://example.com/z2', heat: '2.1M 热度' },
      { rank: 3, title: '为什么越来越多的年轻人选择"慢就业"?', url: 'https://example.com/z3', heat: '1.8M 热度' },
      { rank: 4, title: '国产芯片国产化率突破 70%,你怎么看?', url: 'https://example.com/z4', heat: '1.5M 热度' },
      { rank: 5, title: '你认为 AI 会取代程序员吗?', url: 'https://example.com/z5', heat: '1.2M 热度' },
      { rank: 6, title: '2025 年最值得关注的科技趋势有哪些?', url: 'https://example.com/z6', heat: '980K 热度' },
    ],
    error: null,
  },
  {
    sourceId: 'weibo',
    sourceName: '微博热搜',
    items: [
      { rank: 1, title: '小米 SU7 Ultra 首发:零百加速 1.98 秒', url: 'https://example.com/w1', heat: '5.2M 热度' },
      { rank: 2, title: '华为 Mate 70 系列发布,首发麒麟 9100 芯片', url: 'https://example.com/w2', heat: '4.1M 热度' },
      { rank: 3, title: '国足世预赛 3:1 战胜韩国,历史性突破', url: 'https://example.com/w3', heat: '3.8M 热度' },
      { rank: 4, title: '北京房价连续三个月上涨,成交量回升', url: 'https://example.com/w4', heat: '2.9M 热度' },
      { rank: 5, title: '《流浪地球 3》定档 2026 年春节,预告片播放量破亿', url: 'https://example.com/w5', heat: '2.4M 热度' },
      { rank: 6, title: '李佳琦直播间 GMV 破 20 亿,复播首秀引爆全网', url: 'https://example.com/w6', heat: '1.9M 热度' },
    ],
    error: null,
  },
  {
    sourceId: 'douyin',
    sourceName: '抖音热搜',
    items: [
      { rank: 1, title: '全网爆火的"人生重启模拟器"游戏,玩家已破千万', url: 'https://example.com/d1', heat: '6.7M 热度' },
      { rank: 2, title: '李子柒回归首条视频播放量破 2 亿', url: 'https://example.com/d2', heat: '5.3M 热度' },
      { rank: 3, title: '东北虎下山进村庄,村民围观拍照', url: 'https://example.com/d3', heat: '4.1M 热度' },
      { rank: 4, title: 'AI 翻唱《晴天》走红,版权争议引发讨论', url: 'https://example.com/d4', heat: '3.2M 热度' },
      { rank: 5, title: '贵州"村 BA"篮球赛火爆全网,观众超 10 万', url: 'https://example.com/d5', heat: '2.8M 热度' },
      { rank: 6, title: '国产大飞机 C919 商业首飞,旅客体验评测', url: 'https://example.com/d6', heat: '2.1M 热度' },
    ],
    error: null,
  },
];

export const MOCK_ARTICLE: string =
  '量子计算突破:Google 发布新一代纠错芯片\n\n' +
  'Google Quantum AI 团队今日宣布推出全新一代纠错芯片 Willow,' +
  '实现了量子比特错误率的历史性突破,将错误率从 5% 降至 0.1%。' +
  '这一进展标志着实用化量子计算机距离现实又近了一步。\n\n' +
  '据团队负责人介绍,Willow 芯片采用了全新的表面码纠错架构,' +
  '能够在保持量子相干性的同时,大幅降低计算过程中的误差累积。' +
  '该芯片在标准基准测试中达到了 99.99% 的保真度,' +
  '为构建容错量子计算机奠定了关键基础。\n\n' +
  '业内专家认为,这一突破将加速量子计算在药物研发、材料科学、' +
  '密码学等领域的实际应用,预计 5-8 年内实现商业化落地。';
