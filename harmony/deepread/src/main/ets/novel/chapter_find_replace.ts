// Chapter editor find/replace: literal, case-sensitive and confined to the supplied body.
export const findNovelChapterMatches = (content: string, query: string): number[] => {
  const matches: number[] = [];
  if (query.length === 0) return matches;
  let offset: number = content.indexOf(query);
  while (offset >= 0) {
    matches.push(offset);
    offset = content.indexOf(query, offset + query.length);
  }
  return matches;
};

// null replaces all matches; an index replaces only the selected non-overlapping occurrence.
export const replaceNovelChapterMatch = (
  content: string, query: string, replacement: string, matchIndex: number | null,
): string => {
  const matches: number[] = findNovelChapterMatches(content, query);
  if (matches.length === 0) return content;
  if (matchIndex === null) return content.split(query).join(replacement);
  if (!Number.isInteger(matchIndex) || matchIndex < 0 || matchIndex >= matches.length) return content;
  const offset: number = matches[matchIndex];
  return content.slice(0, offset) + replacement + content.slice(offset + query.length);
};
