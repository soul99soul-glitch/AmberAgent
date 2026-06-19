# Packet B: iOS Runtime/Security Auditor

Objective:
Read only iOS SubAgent, Council, Runtime, Permissions, LocalToolExecutor, and ChatViewModel code to identify safe implementation anchors.

Files / sources:
- iosApp/iosApp SubAgentsView, SubAgentRoleView, SubAgentRunner
- CouncilView, CouncilSettingsView, CouncilChatRuntimeView, CouncilRunner
- ExecutionSettingsView, RuntimeEnvironmentView
- IOSLocalToolExecutor, PermissionsApprovalView, ChatViewModel

Do:
- Identify existing models, persistence patterns, executor hooks, and likely minimal edit points.
- Flag risky areas where real remote/model/API execution would violate constraints.

Do not:
- Edit files.

Expected output:
Concise implementation map with recommended low-conflict files.
