const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const filename = path.resolve(__dirname, '../../../entry/src/main/ets/platform_impl/HotListAggregator.ets');
const exportsObject = {};
vm.runInNewContext(ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText, { exports: exportsObject }, { filename });
const { aggregateHotTopics } = exportsObject;
const plain = value => JSON.parse(JSON.stringify(value));
const item = (title, url, rank = 1, displayTitle) => ({ rank, title, url, heat: '', displayTitle });
const section = (sourceId, items) => ({ sourceId, sourceName: sourceId, items, fetchedAt: 123, error: null });
const aggregate = items => aggregateHotTopics(items.map((entry, index) => section('source_' + index, [entry])), 20);

// Exact titles returned by the official RSS feeds on 2026-10-01. These four URLs
// were also present together in the device's P6-infoq-workspace research input.
const complaint = item(
  'Distributional sentiment modeling and anomaly detection for consumer complaint assessment',
  'https://arxiv.org/abs/2609.31653', 6,
);
const cohorts = item(
  'InfoQ Online Cohorts Address AI Security and Coding Agent Verification',
  'https://www.infoq.com/news/2026/09/onlinecohorts-ai-certifications/', 1,
);
const conference = item(
  'From Agent Authorization to AI Production Evaluation: QCon AI New York 2026',
  'https://www.infoq.com/news/2026/09/qcon-ai-newyork-2026-sessions/', 11,
);
const podcast = item(
  'Podcast: The Future of AI: from Enterprise Adoption to Open Source Sovereignty',
  'https://www.infoq.com/podcasts/enterprise-adoption-open-source-sovereignty/', 12,
);

test('真实 RSS 消费投诉论文与认证新闻不因英文字符碎片合并', () => {
  assert.equal(aggregate([complaint, cohorts]).length, 2);
});

test('真实 RSS 认证新闻与会议议程不因 AI/agent 两个类别词合并', () => {
  assert.equal(aggregate([cohorts, conference]).length, 2);
});

test('真实 RSS 会议议程与企业播客不因 AI/to 两词合并', () => {
  assert.equal(aggregate([conference, podcast]).length, 2);
});

test('真实四 URL 聚合各为独立话题，认证话题只保留自己的研究 seed', () => {
  const topics = aggregateHotTopics([
    section('arxiv_ai', [complaint]),
    section('infoq_ai', [cohorts, conference, podcast]),
  ], 20);
  assert.equal(topics.length, 4);
  const topic = topics.find(entry => entry.sources.some(source => source.url === cohorts.url));
  assert.equal(topic.id, cohorts.url);
  assert.deepEqual(plain(topic.sources.map(source => source.url)), [cohorts.url]);
  assert.equal(topic.sourceCount, 1);
});

test('同一英文发布事件的不同报道仍聚合并保留各自原 URL/排名', () => {
  const first = item('OpenAI launches GPT-5 coding model', 'https://one.test/model', 2);
  const second = item('OpenAI unveils GPT-5 coding model', 'https://two.test/model', 4);
  const topics = aggregate([first, second]);
  assert.equal(topics.length, 1);
  assert.equal(topics[0].sourceCount, 2);
  assert.equal(topics[0].bestRank, 2);
  assert.deepEqual(plain(topics[0].sources.map(source => source.url)), [first.url, second.url]);
});

test('同一中文事件的不同报道仍聚合', () => {
  const topics = aggregate([
    item('马斯克起诉 OpenAI', 'https://cn.test/lawsuit'),
    item('马斯克起诉 OpenAI 的新进展', 'https://another.test/lawsuit'),
  ]);
  assert.equal(topics.length, 1);
  assert.equal(topics[0].sourceCount, 2);
});

test('真实认证/会议准确译文只用于显示，开启翻译不改变分组与研究 seeds', () => {
  const translatedCohorts = Object.assign({}, cohorts, {
    displayTitle: 'InfoQ 在线学习课程聚焦 AI 安全与 Agent 编程验证',
  });
  const translatedConference = Object.assign({}, conference, {
    displayTitle: 'QCon 纽约大会讨论 Agent 授权与 AI 生产评估',
  });
  const identity = topics => plain(topics.map(topic => ({
    id: topic.id, title: topic.title, seeds: topic.sources.map(source => source.url),
  })));
  const original = aggregate([cohorts, conference]);
  const translated = aggregate([translatedCohorts, translatedConference]);
  assert.equal(translated.length, 2);
  assert.deepEqual(identity(translated), identity(original));
  assert.equal(translated[0].displayTitle, translatedCohorts.displayTitle);
  assert.equal(translated[1].sources[0].displayTitle, translatedConference.displayTitle);
});

test('同一原文事件的不同译文仍合并，来源原文/译文/URL完整保留', () => {
  const first = item('OpenAI launches GPT-5 coding model', 'https://one.test/code', 1, 'OpenAI 发布 GPT-5 编码模型');
  const second = item('OpenAI unveils GPT-5 coding model', 'https://two.test/code', 2, 'GPT-5 编程模型由 OpenAI 推出');
  const topics = aggregate([first, second]);
  assert.equal(topics.length, 1);
  assert.deepEqual(plain(topics[0].sources.map(source => [source.title, source.displayTitle, source.url])), [
    [first.title, first.displayTitle, first.url],
    [second.title, second.displayTitle, second.url],
  ]);
});

// The later live NewsNow response (2026-09-30T18:31Z) also placed these original
// Chinese titles in the InfoQ group. No translations or cached topics are used.
const v2exOpinion = item(
  'AI Agent 的能力上限由模型决定，行为的可靠性与一致性由规则体系决定。',
  'https://www.v2ex.com/t/1245935', 1,
);
const intelToolkit = item(
  '财联社10月1日电，英特尔将英伟达OpenShell策略层加入其AI智能体工具包。',
  'https://www.cls.cn/detail/2496707', 1,
);
const trade = item(
  '财联社10月1日电，美国贸易代表格里尔称，与加拿大同行保持相当频繁的联系，还有10项贸易协议即将达成。',
  'https://www.cls.cn/detail/2496705', 3,
);
const financialItems = [trade,
  item('财联社10月1日电，美国10年期国债收益率触及5.305%，为2002年5月以来的最高水平。',
    'https://www.cls.cn/detail/2496704', 4),
  item('财联社10月1日电，周三（9月30日），美联储隔夜逆回购协议（RRP）使用规模为115.39亿美元。',
    'https://www.cls.cn/detail/2496700', 7),
  item('财联社10月1日电，美联储主席沃什称，美联储将落实监察长（IG）的建议。',
    'https://www.cls.cn/detail/2496698', 9),
  item('财联社10月1日电，拉加德称，不排除在欧洲央行行长任期结束前几个月离任的可能性。',
    'https://www.cls.cn/detail/2496696', 11),
  item('财联社10月1日电，SpaceXAI计划很快推出四种Grok定价方案。',
    'https://www.cls.cn/detail/2496695', 12),
];

test('live 原文 InfoQ 认证与 V2EX 观点不因跨语 AI/Agent 合并', () => {
  assert.equal(aggregate([cohorts, v2exOpinion]).length, 2);
});

test('live 原文 InfoQ 认证与英特尔工具包新闻不因跨语 AI/智能体 合并', () => {
  assert.equal(aggregate([cohorts, intelToolkit]).length, 2);
});

test('live 两篇中文 AI 新闻不因 AI/智能体 类别词合并', () => {
  assert.equal(aggregate([v2exOpinion, intelToolkit]).length, 2);
});

test('live 不同财联社事件不因出版社/电讯前缀两个实体合并', () => {
  assert.equal(aggregate([intelToolkit, trade]).length, 2);
});

test('live 十文章传递链全部保持独立，认证 seed 正确且原 URL 无丢失', () => {
  const sections = [
    section('v2ex-share', [v2exOpinion]),
    section('cls-telegraph', [intelToolkit, ...financialItems]),
    section('infoq_ai', [cohorts, conference]),
  ];
  const topics = aggregateHotTopics(sections, 20);
  assert.equal(topics.length, 10);
  const topic = topics.find(entry => entry.sources.some(source => source.url === cohorts.url));
  assert.equal(topic.id, cohorts.url);
  assert.equal(topic.sourceCount, 1);
  assert.deepEqual(plain(topic.sources.map(source => source.url)), [cohorts.url]);
  assert.deepEqual(plain(topics.flatMap(entry => entry.sources.map(source => source.url))).sort(),
    sections.flatMap(entry => entry.items.map(source => source.url)).sort());
});
