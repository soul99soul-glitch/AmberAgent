# AmberAgent Memory Recall Eval Fixtures

Status: Draft · 2026-06-05

Scope: deterministic memory recall and projection checks for the OpenAI Dreaming-inspired Phase 1 memory work.

## Fixtures

| ID | Type | Setup memory | Query | Expected |
| --- | --- | --- | --- | --- |
| E1-photo | Carry forward | `LONG_TERM/REFERENCE`: User's underwater setup is Sony A7R V + Nauticam housing + Inon strobes. | `Will this TTL trigger work with my underwater setup?` | Relevant setup memory appears in Top-N. |
| E1-project | Carry forward | `SHORT_TERM/PROJECT`: 当前项目是 AmberAgent 记忆系统升级，需要继续实现召回和 Summary。 | `继续 AmberAgent 记忆系统项目` | Active short-term project appears in Top-N. |
| E2-vegan | Preference | `LONG_TERM/USER`: 用户是素食者，不吃肉。 | `今晚吃什么？` | Durable user preference is recalled without keyword overlap. |
| E2-style | Preference | `LONG_TERM/USER`: 用户偏好中文简洁回复。 | `帮我看下这个错误` | Reply-style preference is recalled without exact keyword overlap. |
| E3-ascii-month | Stay current | `LONG_TERM/USER`: 用户计划 2026-07 去新加坡。 | `今晚点什么外卖？` with now = `2026-08-01` | Memory is time-decayed, not deleted. |
| E3-cn-month | Stay current | `LONG_TERM/USER`: 用户计划 2026年7月 去新加坡。 | `今晚点什么外卖？` with now = `2026-08-01` | Memory is time-decayed, not deleted. |
| E3-history | Stay current | `LONG_TERM/USER`: 用户 2026-07 去过新加坡。 | `帮我写一段旅行回忆` | Historical memory is not time-decayed. |
| E4-negative | Preference | `LONG_TERM/FEEDBACK`: 不要在回答里提 Stan。 | `帮我写一段团队介绍` | Feedback memory is recalled even without keyword overlap. |
| E5-noise | Precision | `LONG_TERM/NOTE`: 一条无关的长期备注。 | `今晚吃什么？` | Note does not become always-eligible. |
| E5-sensitive-summary | Summary safety | Active record containing password/passport/身份证 style sensitive content. | Memory Library Summary render. | Sensitive record is excluded or masked in Summary projection. |

## Deterministic Checks

- `MemoryRecallStoreTest` covers scorer eligibility, ranking, freshness decay, and durable auto-write guardrails.
- `MemoryDreamPlannerTest` continues to cover local cleanup planning with injectable `now`.
- LLM answer quality is intentionally out of CI scope for Phase 1.
