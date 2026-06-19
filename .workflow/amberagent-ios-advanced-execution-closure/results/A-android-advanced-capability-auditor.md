# Packet A Result: Android Advanced Capability Auditor

Status: completed.

Key evidence:
- Android keeps separate `SubAgentRun`, `ModelCouncilRun`, terminal job, and generic task snapshot models.
- SubAgent state uses running/completed/approval_required/failed/cancelled/timed_out/interrupted.
- Council state uses running/completed/partial_failed/failed/cancelled/timed_out/interrupted, with partial failure treated as completed task plus warnings/result.
- Permission traces include risk, mutates, needs approval, auto approval, mandatory approval, always-ask, concurrency, speculative eligibility, and output budget.
- iOS should keep stricter foreground user presence and unsupported Android capabilities blocked.

Accepted decisions:
- Do not collapse all advanced features into opaque strings; use typed iOS task records with kind-specific metadata.
- Keep Termux-style terminal tools blocked on iOS; remote SSH tasks use foreground UI and existing SSH runtime.
- Use unified task records only for display/retry/recovery, not as a substitute for each runner's source model.
