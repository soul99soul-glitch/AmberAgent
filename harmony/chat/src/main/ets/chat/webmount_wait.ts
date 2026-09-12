// webmount_wait — wait 条件纯逻辑(可单测;entry WebMountTools 复用)

export interface WebMountWaitConditions {
  urlIncludes?: string;
  titleIncludes?: string;
  selector?: string;
  textIncludes?: string;
}

export interface WebMountWaitProbe {
  url: string;
  title: string;
  bodyText: string;
  selectorExists: boolean;
}

export interface WebMountWaitResult {
  matched: boolean;
  reason: 'ok' | 'url' | 'title' | 'selector' | 'text' | 'no_condition';
}

export const evaluateWebMountWait = (
  conditions: WebMountWaitConditions,
  probe: WebMountWaitProbe,
): WebMountWaitResult => {
  const u = conditions.urlIncludes ?? '';
  const t = conditions.titleIncludes ?? '';
  const s = conditions.selector ?? '';
  const x = conditions.textIncludes ?? '';
  if (u.length === 0 && t.length === 0 && s.length === 0 && x.length === 0) {
    return { matched: false, reason: 'no_condition' };
  }
  if (u.length > 0 && probe.url.indexOf(u) < 0) return { matched: false, reason: 'url' };
  if (t.length > 0 && probe.title.indexOf(t) < 0) return { matched: false, reason: 'title' };
  if (s.length > 0 && !probe.selectorExists) return { matched: false, reason: 'selector' };
  if (x.length > 0 && probe.bodyText.indexOf(x) < 0) return { matched: false, reason: 'text' };
  return { matched: true, reason: 'ok' };
};

export const parseWaitProbeJson = (raw: string): { matched: boolean } => {
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed === 'object' && parsed !== null) {
      const obj = parsed as { ok?: boolean; value?: { matched?: boolean } };
      return { matched: obj.ok === true && obj.value !== undefined && obj.value.matched === true };
    }
  } catch {
    // fall through
  }
  return { matched: false };
};
