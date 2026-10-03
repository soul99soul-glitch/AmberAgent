// DeepReadImageScorer — 图片质量评分
// MVP port of Android DeepReadImageScorer.kt
//
// confidence:hero(可用作文章头图)/ inline(可用作文中插图)/ reject(丢弃)
//
// 评分模型是 MVP(简化版),但 reject 路径尽量贴合 Android:
// - URL-based risk flags(照搬 Android urlRiskFlags)
// - 已知尺寸 hard-reject 阈值照搬 Android(< 320 宽 或 < 160 高);0 表示未知
// - hard reject flag 集合照搬 Android hardRejectRisks
// 这样 hero gate(P1-1)的 reject 决策与 Android 一致。

import { IMAGE_CONFIDENCE } from '../domain/enums.ts';

export type ImageConfidence = 'hero' | 'inline' | 'reject';

export interface ImageCandidate {
  url: string;
  width: number;
  height: number;
  altText: string;
  sourceUrl: string;     // 图片所在的页面 URL
  byteSize?: number;
}

export interface ScoredImageCandidate extends ImageCandidate {
  confidence: ImageConfidence;
  score: number;
  riskFlags: string[];
  selectionReason: string;
}

// 照搬 Android hardRejectRisks — 命中任意 → 直接 reject
const HARD_REJECT_RISKS = new Set<string>([
  'non_http_url',
  'site_brand_asset',
  'avatar_asset',
  'tracking_or_spacer',
  'sprite_or_badge',
  'icon_format',
  'not_image_content_type',
  'too_small',
  'tiny_asset',
  'small_square_brand_asset',
  'tiny_file',
]);

// 照搬 Android urlRiskFlags(DeepReadImageScorer.kt:109-121)
const urlRiskFlags = (url: string): string[] => {
  const lower = url.toLowerCase();
  const flags: string[] = [];
  if (!lower.startsWith('http://') && !lower.startsWith('https://')) flags.push('non_http_url');
  if (['favicon', 'logo', 'site-icon', 'apple-touch-icon', 'brand', 'watermark'].some(s => lower.includes(s))) {
    flags.push('site_brand_asset');
  }
  if (['avatar', 'profile_photo', 'userpic'].some(s => lower.includes(s))) flags.push('avatar_asset');
  if (['pixel', 'tracking', 'spacer', 'blank.gif', '1x1'].some(s => lower.includes(s))) flags.push('tracking_or_spacer');
  if (['sprite', 'iconfont', 'badge'].some(s => lower.includes(s))) flags.push('sprite_or_badge');
  // 文件扩展名 icon_format(.ico/.svg)
  const noQuery = lower.split('?')[0];
  const ext = noQuery.substring(noQuery.lastIndexOf('.') + 1);
  if (ext === 'ico' || ext === 'svg') flags.push('icon_format');
  return flags;
};

// 照搬 Android qualityRiskFlags(DeepReadImageScorer.kt:123-140)
const qualityRiskFlags = (width: number, height: number, byteSize: number | undefined): string[] => {
  const flags: string[] = [];
  const smaller = Math.min(width, height);
  const larger = Math.max(width, height);
  if ((width > 0 && width < 320) || (height > 0 && height < 160)) flags.push('too_small');
  if (width > 0 && height > 0) {
    if (smaller <= 160 && larger <= 320) flags.push('tiny_asset');
    if (larger / smaller > 4.5) flags.push('sprite_or_banner_strip');
    if (Math.abs(width - height) <= 16 && width <= 640) flags.push('small_square_brand_asset');
  }
  if (byteSize !== undefined && byteSize >= 1 && byteSize <= 4096) flags.push('tiny_file');
  return flags;
};

const rejectCandidate = (
  c: ImageCandidate,
  riskFlags: string[],
  reason: string,
): ScoredImageCandidate => ({
  ...c,
  confidence: 'reject',
  score: 0,
  riskFlags,
  selectionReason: reason,
});

export const scoreImageCandidate = (c: ImageCandidate, topicTitle: string): ScoredImageCandidate => {
  const risks = new Set<string>([...urlRiskFlags(c.url), ...qualityRiskFlags(c.width, c.height, c.byteSize)]);

  // alt 文本 logo 检测(MVP 增强:Android 用 URL 子串,但 alt 含 logo/icon 也是强信号)
  const altLower = c.altText.toLowerCase();
  if (/\b(logo|icon|favicon|sprite|button|brand)\b/.test(altLower)) risks.add('site_brand_asset');

  const sortedRisks = Array.from(risks).sort();
  const hardReject = sortedRisks.some(r => HARD_REJECT_RISKS.has(r));
  if (hardReject) {
    return rejectCandidate(c, sortedRisks, `hard reject: ${sortedRisks.join(', ')}`);
  }

  // 计算 score(0..100, MVP 模型)
  const reasons: string[] = [];
  let score = 30;
  const minDim = Math.min(c.width, c.height);
  if (minDim >= 400) { score += 20; reasons.push('large min dimension'); }
  if (minDim >= 800) score += 10;
  if ((c.byteSize ?? 0) > 30_000) score += 10;
  if (c.altText.trim().length >= 8) { score += 10; reasons.push('meaningful alt text'); }
  // 与话题标题相关性(简化:alt 含标题字符)
  if (topicTitle.length > 0 && c.altText.length > 0) {
    const titleChars = new Set(topicTitle.toLowerCase());
    let overlap = 0;
    for (const ch of c.altText.toLowerCase()) if (titleChars.has(ch)) overlap++;
    if (overlap >= 4) { score += 15; reasons.push('alt text overlaps topic title'); }
  }

  // 宽高比
  const knownDimensions = c.width > 0 && c.height > 0;
  const ratio = knownDimensions ? c.width / c.height : 0;
  const remainingRisks: string[] = [];
  let confidence: ImageConfidence = 'inline';
  if (!knownDimensions) {
    remainingRisks.push('unknown_dimensions');
    reasons.push('unknown dimensions; inline only');
  } else if (ratio >= 0.75 && ratio <= 2.0 && score >= 60) {
    confidence = 'hero';
    reasons.push('hero aspect ratio + high score');
  } else if (ratio > 3 || ratio < 0.3) {
    remainingRisks.push('extreme_aspect_ratio');
    score -= 10;
  }

  return {
    ...c,
    confidence,
    score: Math.max(0, Math.min(100, score)),
    riskFlags: remainingRisks,
    selectionReason: reasons.join('; ') || 'default inline candidate',
  };
};

// 批量评分 + 去重(同 URL 取高分)
export const scoreAndDedup = (
  candidates: ImageCandidate[],
  topicTitle: string,
): ScoredImageCandidate[] => {
  const scored = candidates.map(c => scoreImageCandidate(c, topicTitle));
  const byUrl = new Map<string, ScoredImageCandidate>();
  for (const s of scored) {
    const existing = byUrl.get(s.url);
    if (!existing || s.score > existing.score) byUrl.set(s.url, s);
  }
  return Array.from(byUrl.values()).sort((a, b) => b.score - a.score);
};

// 重新导出 confidence 常量(方便外部用)
export { IMAGE_CONFIDENCE };
