---
name: deep-read-fact-check
version: 1.0.0
description: Use when verifying a long article, news analysis, social-media claim, or technical factual claim before publishing a Deep Read or other high-stakes factual report.
---

# Deep Read Fact Check

Use this skill before finalizing a Deep Read article or any long factual report.

## Language Rules

- If the user asks in Chinese, write the verification report in Chinese.
- Keep source URLs and citations in their original form.
- If a fact is uncertain, say `不确定` and explain why. Do not use vague wording like `可能` to imply confidence.

## AmberAgent Tool Mapping

Hermes-style tool names map to AmberAgent tools as follows:

- `web_search` -> `search_web`
- `web_extract` -> `scrape_web`
- Browser rendering fallback -> use AmberAgent browser/webview tools only when they are explicitly available in the current tool list.
- Terminal/package/GitHub checks -> use terminal/workspace tools only when they are explicitly available in the current tool list.

When only `search_web` and `scrape_web` are available, do not pretend a browser or terminal check was performed.

## Long Article Verification

1. Use `scrape_web` to extract full text for the key source URLs whenever snippets are insufficient.
2. Break the draft into all core sub-claims, usually 5-15 claims.
3. Sort claims by impact. Verify the most important 3-5 first.
4. If a critical sub-claim is falsified, downgrade the whole article's credibility sharply.
5. Do not treat an AI-generated summary as a source.
6. Do not count multiple reports that cite the same original source as independent confirmation.
7. Always check the timeline. A 2024 source cannot verify a 2026 fact.

## Social Media Link Verification

1. If a dedicated social-media reader tool is available, use it first for original post, quoted post, author, time, metrics, and expanded URLs.
2. Then try direct access to the original content when browser tools are available, especially for images, quote chains, and threads.
3. If the original content is behind a login wall, search for secondary reports quoting it.
4. Clearly label `未能访问原始内容，基于二手来源` when relying on secondary sources.

## Dynamic Or Anti-Bot Pages

1. If `scrape_web` returns empty content or a misleading block message, do not immediately give up.
2. Use browser/webview extraction only if those tools are available.
3. If no rendered browser tool is available, report the extraction limitation and seek alternative sources.
4. For forums or long discussions, search and extract with targeted keywords before deciding whether to go deeper.

## Technical Claim Verification

1. Prefer GitHub repository metadata, commit history, release tags, and contributor records.
2. For packages, verify version timelines with package registry tools or terminal commands when available.
3. Compare code structure through raw files when available.

## Anti-Patterns

- Do not answer factual questions from model memory.
- Do not treat AI summaries as sources.
- Do not count duplicated syndication as independent evidence.
- Do not use `可能` / `应该` to imply certainty when evidence is insufficient.
- Do not skip contrary-evidence searches.
- Do not treat self-media or low-authority reposts as equal to primary or authoritative sources.
- Do not ignore chronology.
