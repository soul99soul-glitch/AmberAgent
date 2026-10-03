// Static structured previews, matching the mobile renderer/spec wire shapes.
import type { JsonObject, JsonValue } from './json.ts';
const obj = (v: JsonValue | undefined): JsonObject | null =>
  v !== null && typeof v === 'object' && !Array.isArray(v) ? v : null;
const str = (v: JsonValue | undefined): string => typeof v === 'string' ? v : '';
const arr = (v: JsonValue | undefined): JsonValue[] => Array.isArray(v) ? v : [];
const escape = (v: string): string => v.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
interface Series { name: string; values: number[]; }
const colors = ['#2563eb', '#16a34a', '#ea580c', '#9333ea'];

const chart = (spec: JsonObject, vchart: boolean): string | null => {
  let labels = arr(spec.x ?? spec.labels).map((v): string => str(v));
  let series: Series[] = arr(spec.series).map((v): Series => {
    const row = obj(v);
    return { name: str(row?.name), values: arr(row?.data).map((n): number => typeof n === 'number' && Number.isFinite(n) ? n : 0) };
  });
  if (vchart) {
    // The static preview is only a single ungrouped dataset, never a substitute
    // for VChart's stacking/grouping/normalization semantics.
    if ((spec.seriesField !== undefined && spec.seriesField !== null && spec.seriesField !== '') ||
      (spec.stack !== undefined && spec.stack !== false) || spec.percent === true ||
      arr(spec.series).length > 0 || arr(spec.data).length !== 1) return null;
    const rows = arr(obj(arr(spec.data)[0])?.values);
    const xField = str(spec.xField);
    const yField = str(spec.yField);
    if (xField.length === 0 || yField.length === 0 || rows.length === 0) return null;
    if (rows.some((value): boolean => {
      const row = obj(value);
      return row === null || (typeof row[xField] !== 'string' && typeof row[xField] !== 'number') ||
        typeof row[yField] !== 'number' || !Number.isFinite(row[yField]);
    })) return null;
    labels = rows.map((v): string => { const value = obj(v)?.[xField]; return typeof value === 'number' ? String(value) : str(value); });
    if (new Set(labels).size !== labels.length) return null;
    series = [{ name: yField, values: rows.map((v): number => { const n = obj(v)?.[yField]; return typeof n === 'number' && Number.isFinite(n) ? n : 0; }) }];
  }
  labels = labels.slice(0, 24); series = series.filter((s): boolean => s.values.length > 0).slice(0, 4);
  if (labels.length === 0 || series.length === 0) return null;
  const type = str(spec.type).toLowerCase();
  if (!['bar', 'column', 'line', 'pie', 'donut', 'area', ''].includes(type)) return null;
  const values = series.flatMap((s): number[] => s.values.slice(0, labels.length));
  const lo = Math.min(0, ...values); const hi = Math.max(0, ...values);
  const span = hi - lo || 1;
  const y = (value: number): number => 30 + 210 * (hi - value) / span;
  const slot = 570 / labels.length;
  let shapes = `<path d="M55 30V240H625" stroke="#9ca3af" fill="none"/><path d="M55 ${y(0)}H625" stroke="#d1d5db"/>`;
  if (type === 'pie' || type === 'donut') {
    const total = series[0].values.slice(0, labels.length).reduce((sum, n): number => sum + Math.max(0, n), 0);
    if (total <= 0) return null;
    let x = 55;
    shapes = '';
    labels.forEach((label, index): void => {
      const fraction = Math.max(0, series[0].values[index] ?? 0) / total;
      shapes += `<rect x="${x}" y="45" width="${570 * fraction}" height="50" fill="${colors[index % colors.length]}"/>`;
      shapes += `<text x="55" y="${125 + 20 * index}" font-size="13" fill="${colors[index % colors.length]}">${escape(label)} ${(100 * fraction).toFixed(1)}%</text>`;
      x += 570 * fraction;
    });
    return `<svg width="100%" viewBox="0 0 680 ${Math.max(200, 150 + 20 * labels.length)}" xmlns="http://www.w3.org/2000/svg">${shapes}</svg>`;
  }
  series.forEach((s, si): void => {
    const color = colors[si];
    if (type === 'line' || type === 'area') {
      const points = s.values.slice(0, labels.length).map((n, i): string => `${55 + slot * (i + .5)},${y(n)}`).join(' ');
      shapes += `<polyline points="${points}" fill="none" stroke="${color}" stroke-width="3"/>`;
    } else {
      s.values.slice(0, labels.length).forEach((n, i): void => {
        const width = slot * .7 / series.length;
        const top = Math.min(y(n), y(0));
        shapes += `<rect x="${55 + slot * (i + .15) + si * width}" y="${top}" width="${width}" height="${Math.abs(y(n) - y(0))}" rx="2" fill="${color}"/>`;
      });
    }
    const legendFont = Math.max(13, Math.min(24, Math.floor(145 * .9 / Math.max(1, s.name.length))));
    shapes += `<text class="amber-chart-legend-label" x="${55 + 145 * si}" y="295" font-size="${legendFont}" fill="${color}">${escape(s.name)}</text>`;
  });
  const maxLabelLength = Math.max(1, ...labels.map((label): number => label.slice(0, 10).length));
  // Dense charts keep their existing size; sparse charts use the available slot
  // and truncated label length, within the current SVG's fixed geometry.
  const labelFont = slot < 48 ? 11 : Math.max(11, Math.min(24, Math.floor(slot * .85 / maxLabelLength)));
  labels.forEach((label, i): void => {
    shapes += `<text class="amber-chart-axis-label" x="${55 + slot * (i + .5)}" y="265" text-anchor="middle" font-size="${labelFont}" fill="#6b7280">${escape(label.slice(0, 10))}</text>`;
  });
  return `<svg width="100%" viewBox="0 0 680 320" xmlns="http://www.w3.org/2000/svg">${shapes}</svg>`;
};

const valueText = (value: JsonValue): string => {
  if (typeof value === 'string' || typeof value === 'number') return String(value);
  const item = obj(value);
  if (item === null) return '';
  return ['title', 'label', 'name', 'value', 'description', 'detail', 'text'].map((key): string =>
    typeof item[key] === 'number' ? String(item[key]) : str(item[key])).filter((v): boolean => v.length > 0).join(' · ');
};
const textItems = (items: JsonValue[]): string => items.map(valueText).filter((v): boolean => v.length > 0)
  .map((v): string => `<li>${escape(v)}</li>`).join('');

const slideContentFields = ['content', 'body', 'items', 'bullets', 'text', 'description', 'points',
  'summary', 'takeaways', 'sections', 'cards', 'metrics', 'columns', 'left', 'right', 'primary', 'secondary',
  'details', 'notes', 'detail', 'timeline'];
const slideMetadataFields = ['title', 'subtitle', 'label', 'name', 'value', 'number', 'metric', 'caption', 'takeaway', 'note',
  'layout', 'style', 'theme', 'visual', 'tone', 'accent', 'accentColor', 'color', 'fontPack', 'id', 'eyebrow', 'footer'];
const limitedSlideNotice = '<p><small>部分内容格式不支持，当前为受限静态预览</small></p>';
const slideValue = (value: JsonValue, depth: number = 0): string => {
  if (typeof value === 'string' || typeof value === 'number') return `<p>${escape(String(value))}</p>`;
  if (depth >= 4) return limitedSlideNotice;
  if (Array.isArray(value)) return value.map((v): string => slideValue(v, depth + 1)).join('');
  const item = obj(value);
  if (item === null) return '';
  let out = '';
  const title = str(item.title) || str(item.label) || str(item.name);
  if (title.length > 0) out += `<h3>${escape(title)}</h3>`;
  for (const key of ['subtitle', 'value', 'number', 'metric', 'caption', 'takeaway', 'note', 'eyebrow', 'footer', ...slideContentFields]) {
    if (item[key] !== undefined && item[key] !== null) out += slideValue(item[key], depth + 1);
  }
  if (Object.keys(item).some((key): boolean => !slideContentFields.includes(key) &&
    !slideMetadataFields.includes(key) && item[key] !== null)) out += limitedSlideNotice;
  return out;
};

export const renderWidgetSpec = (renderer: string, specValue: JsonValue): string | null => {
  const spec = obj(specValue);
  if ((renderer === 'chart' || renderer === 'vchart') && spec !== null) return chart(spec, renderer === 'vchart');
  if (renderer === 'diagram' && spec !== null) {
    const items = arr(spec.nodes ?? spec.items ?? spec.rows);
    if (items.length === 0) return null;
    return `<section><ol>${textItems(items)}</ol></section>`;
  }
  if (renderer === 'slides') {
    const pages = Array.isArray(specValue) ? specValue : arr(spec?.slides ?? spec?.pages);
    if (pages.length === 0 || pages.length > 24) return null;
    return '<div id="deck">' + pages.map((value, index): string => {
      return `<section class="slide"><small>${index + 1} / ${pages.length}</small>` +
        slideValue(value) + '</section>';
    }).join('') + '</div>';
  }
  return null;
};
