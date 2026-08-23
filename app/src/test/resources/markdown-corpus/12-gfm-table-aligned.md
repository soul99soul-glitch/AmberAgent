## GFM Tables — Alignment Markers

GFM tables support three column alignment markers in the delimiter row:

- `:---` — left-aligned (default)
- `:---:` — center-aligned
- `---:` — right-aligned

This table exercises all three:

| Algorithm | Time Complexity | Space |
| :--- | :---: | ---: |
| Bubble Sort | $O(n^2)$ | $O(1)$ |
| Merge Sort | $O(n \log n)$ | $O(n)$ |
| Quick Sort | $O(n \log n)$ avg | $O(\log n)$ |
| Heap Sort | $O(n \log n)$ | $O(1)$ |
| Counting Sort | $O(n + k)$ | $O(k)$ |
| Radix Sort | $O(nk)$ | $O(n + k)$ |

The first column is left-aligned (algorithm name — text benefits from left alignment), the second is centered (the complexity expression looks balanced centered), and the third is right-aligned (numeric values align better on the right).

### HTTP Status Codes

| Status Code | Name | Meaning |
| ---: | :---: | :--- |
| 200 | OK | Request succeeded |
| 201 | Created | Resource was created |
| 204 | No Content | Success, no body |
| 301 | Moved Permanently | Redirect, update bookmarks |
| 400 | Bad Request | Client sent invalid data |
| 401 | Unauthorized | Authentication required |
| 403 | Forbidden | Authenticated but not allowed |
| 404 | Not Found | Resource doesn't exist |
| 429 | Too Many Requests | Rate-limited |
| 500 | Internal Server Error | Server-side failure |
| 502 | Bad Gateway | Upstream error |
| 503 | Service Unavailable | Server overloaded or down |

In the status-code table the first column (number) is right-aligned, the middle column (name) is centered, and the description is left-aligned.

### Alignment with Inline Markup

| Feature | **Supported** | Notes |
| :--- | :---: | :--- |
| Bold in cells | ✓ | Renders `**...**` inside cell |
| Inline code | ✓ | `` `...` `` works in cells |
| Links | ✓ | `[text](url)` inside cell |
| Images | partial | Inline images may overflow |
| Nested tables | ✗ | Not supported by GFM spec |
