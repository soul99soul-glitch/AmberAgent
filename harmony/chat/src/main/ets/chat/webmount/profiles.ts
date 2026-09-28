// webmount/profiles — 九个固定只读站点 profile + DOM 提取脚本(E12 冻结合同)
//
// 选择器/origins 来自 Android app/src/main/assets/webmount/profiles/*.json 的
// interactive_selectors 只读子集:只取 DOM 可见文本与公开链接,不读输入 value、
// cookie/storage、HTML 或任意页面函数。九份是九个真实执行的 DOM adapter,
// 不是九个 stub;真实第三方 DOM 是否仍匹配属设备/网络验收层。

export interface WebMountSiteField { name: string; selector: string; }

export interface WebMountSiteProfile {
  siteId: string;
  origins: string[];
  fields: WebMountSiteField[];
}

const field = (name: string, selector: string): WebMountSiteField => ({ name: name, selector: selector });

export const WEBMOUNT_READONLY_SITE_PROFILES: WebMountSiteProfile[] = [
  {
    siteId: 'hackernews',
    origins: ['https://news.ycombinator.com'],
    fields: [
      field('story_title', 'tr.athing td.title a.titlelink, tr.athing td.title a.storylink'),
      field('comment_tree', 'table.comment-tree, .comment-tree'),
      field('user_link', 'a.hnuser'),
    ],
  },
  {
    siteId: 'reddit',
    origins: ['https://www.reddit.com', 'https://old.reddit.com', 'https://reddit.com'],
    fields: [
      field('post', 'shreddit-post, div[data-testid=post-container], .Post'),
      field('comment', 'shreddit-comment, div[data-testid=comment]'),
      field('subreddit_link', 'a[data-click-id=subreddit]'),
    ],
  },
  {
    siteId: 'github',
    origins: ['https://github.com', 'https://gist.github.com'],
    fields: [
      field('repo_readme', 'article.markdown-body'),
      field('code_blob', 'table.highlight'),
      field('issue_title', 'bdi.js-issue-title'),
      field('comment_body', 'td.comment-body, .markdown-body'),
    ],
  },
  {
    siteId: 'bilibili',
    origins: ['https://www.bilibili.com', 'https://space.bilibili.com', 'https://search.bilibili.com', 'https://t.bilibili.com'],
    fields: [
      field('video_title', 'h1.video-title, .video-title-cur'),
      field('uploader', '.up-name, a.up-name__text'),
    ],
  },
  {
    siteId: 'x_com',
    origins: ['https://x.com', 'https://twitter.com', 'https://mobile.twitter.com'],
    fields: [
      field('timeline_item', "article[data-testid='tweet']"),
      field('tweet_text', "div[data-testid='tweetText']"),
      field('author_name', "div[data-testid='User-Name']"),
    ],
  },
  {
    siteId: 'weibo',
    origins: ['https://m.weibo.cn', 'https://weibo.com'],
    fields: [
      field('feed_item', '.card, .m-container-max .card'),
      field('post_text', '.weibo-text, .txt, article'),
      field('author_name', '.m-text-cut, .name'),
    ],
  },
  {
    siteId: 'juejin',
    origins: ['https://juejin.cn'],
    fields: [
      field('article_content', 'article.markdown-body, .article-content'),
      field('article_title', 'h1.article-title'),
      field('author_name', '.author-name-text, .username'),
    ],
  },
  {
    siteId: 'zhihu',
    origins: ['https://www.zhihu.com', 'https://zhuanlan.zhihu.com'],
    fields: [
      field('answer_content', 'div.RichContent-inner, .Post-RichTextContainer'),
      field('question_title', 'h1.QuestionHeader-title'),
      field('author_name', '.AuthorInfo-name a'),
    ],
  },
  {
    siteId: 'feishu_docs',
    origins: ['https://www.feishu.cn', 'https://feishu.cn'],
    fields: [
      field('doc_title', ".doc-title, .docs-doc-title, [data-testid='doc-title'], h1"),
      field('doc_block', '[data-block-id], [data-oid], .block, .docx-block, .suite-block'),
      field('block_text', ".text-editor-inner, [contenteditable='true'], .docx-text-block"),
    ],
  },
];

export const webMountSiteProfile = (siteId: string): WebMountSiteProfile | null =>
  WEBMOUNT_READONLY_SITE_PROFILES.find((profile: WebMountSiteProfile): boolean => profile.siteId === siteId) ?? null;

// profile origins 与 Stations 授权同时匹配才允许执行(宿主两侧都要查)
export const webMountSiteMatchesUrl = (profile: WebMountSiteProfile, currentUrl: string): boolean => {
  const originMatch: RegExpExecArray | null = /^(https?:\/\/[a-z0-9.-]+(:\d{1,5})?)/i.exec(currentUrl.trim());
  return originMatch !== null && profile.origins.includes(originMatch[1].toLowerCase());
};

export const WEBMOUNT_SITE_ADAPTER_DEFAULT_LIMIT: number = 20;
export const WEBMOUNT_SITE_ADAPTER_MAX_LIMIT: number = 100;

// 生成在该页面执行的只读提取脚本:全部参数 JSON 编码注入,产出真实
// fields/matched/truncated;不可见元素与超预算元素按 limit 截断
export const buildWebMountSiteAdapterScript = (siteId: string, limit?: number): string => {
  const profile: WebMountSiteProfile | null = webMountSiteProfile(siteId);
  if (profile === null) throw new Error(`unknown WebMount site adapter: ${siteId}`);
  const capped: number = Math.max(1, Math.min(limit ?? WEBMOUNT_SITE_ADAPTER_DEFAULT_LIMIT, WEBMOUNT_SITE_ADAPTER_MAX_LIMIT));
  const spec: string = JSON.stringify({ fields: profile.fields, limit: capped });
  return '(() => {\n'
    + `  const spec = ${spec};\n`
    + '  const out = {};\n'
    + '  let matched = 0;\n'
    + '  let truncated = false;\n'
    + '  for (const f of spec.fields) {\n'
    + '    let nodes = [];\n'
    + '    try { nodes = Array.from(document.querySelectorAll(f.selector)); } catch (e) { nodes = []; }\n'
    + '    const visible = nodes.filter((el) => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length));\n'
    + '    matched += visible.length;\n'
    + '    const items = [];\n'
    + '    for (const el of visible) {\n'
    + '      if (items.length >= spec.limit) { truncated = true; break; }\n'
    + "      const text = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 500);\n"
    + '      let href = null;\n'
    + "      const a = el.tagName === 'A' ? el : el.querySelector('a[href]');\n"
    + "      if (a && /^https?:/i.test(a.href || '')) href = a.href;\n"
    + '      if (text.length > 0 || href) items.push(href ? { text: text, href: href } : { text: text });\n'
    + '    }\n'
    + '    out[f.name] = items;\n'
    + '  }\n'
    + '  return JSON.stringify({ fields: out, matched: matched, truncated: truncated });\n'
    + '})()';
};
