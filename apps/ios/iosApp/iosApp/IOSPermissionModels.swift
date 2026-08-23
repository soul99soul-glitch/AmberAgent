import Foundation

enum IOSCapabilityStatus: String, CaseIterable, Identifiable {
    case supported
    case degraded
    case requiresEntitlement
    case requiresExtensionTarget
    case requiresSystemSettings
    case unsupported

    var id: String { rawValue }

    var title: String {
        switch self {
        case .supported: "Supported"
        case .degraded: "Requires foreground system UI"
        case .requiresEntitlement: "Requires entitlement"
        case .requiresExtensionTarget: "Requires extension"
        case .requiresSystemSettings: "Requires Settings"
        case .unsupported: "Unavailable on iOS"
        }
    }
}

enum IOSCapabilityRisk: String, CaseIterable, Identifiable {
    case normal
    case sensitive
    case high

    var id: String { rawValue }

    var title: String {
        switch self {
        case .normal: "Normal"
        case .sensitive: "Sensitive"
        case .high: "High"
        }
    }
}

enum IOSCapabilityDomain: String, CaseIterable, Identifiable {
    case agentMemory
    case filesAndPhotos
    case healthAndMotion
    case location
    case contacts
    case calendarAndReminders
    case cameraAndMicrophone
    case speechAndMedia
    case notifications
    case networkAndConnectivity
    case identityAndAuthentication
    case homeAndNearby
    case extensionsAndEntitlements
    case externalApps
    case unavailableOnIOS

    var id: String { rawValue }

    var title: String {
        switch self {
        case .agentMemory: "Agent Memory"
        case .filesAndPhotos: "Files & Photos"
        case .healthAndMotion: "Health & Motion"
        case .location: "Location"
        case .contacts: "Contacts"
        case .calendarAndReminders: "Calendar & Reminders"
        case .cameraAndMicrophone: "Camera & Microphone"
        case .speechAndMedia: "Speech & Media"
        case .notifications: "Notifications"
        case .networkAndConnectivity: "Network & Connectivity"
        case .identityAndAuthentication: "Identity & Authentication"
        case .homeAndNearby: "Home & Nearby"
        case .extensionsAndEntitlements: "Extensions & Entitlements"
        case .externalApps: "External Apps & Sharing"
        case .unavailableOnIOS: "Unavailable on iOS"
        }
    }
}

enum IOSPermissionRequestKind: String, CaseIterable, Identifiable {
    case directSystemPrompt
    case foregroundSystemUI
    case foregroundSession
    case authenticationOperation
    case picker
    case settingsOnly
    case entitlementRequired
    case extensionTargetRequired
    case entitlementAndExtensionRequired
    case diagnosticOnly
    case unsupported

    var id: String { rawValue }

    var title: String {
        switch self {
        case .directSystemPrompt: "Direct system prompt"
        case .foregroundSystemUI: "Foreground system UI"
        case .foregroundSession: "Foreground session"
        case .authenticationOperation: "Authentication operation"
        case .picker: "System picker"
        case .settingsOnly: "Settings only"
        case .entitlementRequired: "Entitlement required"
        case .extensionTargetRequired: "Extension target required"
        case .entitlementAndExtensionRequired: "Entitlement and extension required"
        case .diagnosticOnly: "Diagnostic only"
        case .unsupported: "Unsupported"
        }
    }

    var canRequestInApp: Bool {
        switch self {
        case .directSystemPrompt, .foregroundSystemUI, .foregroundSession, .authenticationOperation, .picker:
            true
        case .settingsOnly,
             .entitlementRequired,
             .extensionTargetRequired,
             .entitlementAndExtensionRequired,
             .diagnosticOnly,
             .unsupported:
            false
        }
    }
}

enum IOSAgentPermissionPolicy: String, CaseIterable, Identifiable {
    case disabled
    case askEveryTime
    case allowOncePerRun
    case autoApprove
    case autoApproveHighRisk

    var id: String { rawValue }

    var title: String {
        switch self {
        case .disabled: "Disabled"
        case .askEveryTime: "Ask every time"
        case .allowOncePerRun: "Allow once per run"
        case .autoApprove: "Auto approve"
        case .autoApproveHighRisk: "Auto approve (high risk)"
        }
    }
}

struct IOSPlatformToolGate: Hashable {
    let requiresFreshUserPresence: Bool
    let allowRunScopedReuse: Bool
    let allowGlobalAutoApproval: Bool
}

struct IOSPlatformCapability: Identifiable, Hashable {
    let id: String
    let title: String
    let summary: String
    let domain: IOSCapabilityDomain
    let status: IOSCapabilityStatus
    let risk: IOSCapabilityRisk
    let requestKind: IOSPermissionRequestKind
    let requestEntryPoint: String
    let uiActionNames: [String]
    let modelToolNames: [String]
    let blockedToolNames: [String]
    let unavailableReason: String?
    let requiredInfoPlistKeys: [String]
    let requiredEntitlements: [String]
    let requiredBackgroundModes: [String]
    let requiredExtensionTargets: [String]
    let defaultEnabled: Bool
    let canOpenSettings: Bool
    let gate: IOSPlatformToolGate

    var canRequestInApp: Bool {
        requestKind.canRequestInApp && status != .unsupported
    }
}

struct IOSCapabilityRegistry {
    static let capabilities: [IOSPlatformCapability] = [
        capability(
            id: "ios.agent.memory_write",
            title: "记忆写入",
            summary: "新建、编辑或删除记忆",
            domain: .agentMemory,
            status: .supported,
            risk: .high,
            requestKind: .foregroundSession,
            requestEntryPoint: "Chat memory_tool write approval",
            modelToolNames: ["memory_tool"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.agent.subagent_dispatch",
            title: "子代理调度",
            summary: "委托任务给子代理角色",
            domain: .networkAndConnectivity,
            status: .supported,
            risk: .sensitive,
            requestKind: .foregroundSession,
            requestEntryPoint: "Chat subagent_dispatch tool",
            modelToolNames: ["subagent_dispatch"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.agent.model_council_run",
            title: "模型议会",
            summary: "发起议会讨论并返回结果",
            domain: .networkAndConnectivity,
            status: .supported,
            risk: .sensitive,
            requestKind: .foregroundSession,
            requestEntryPoint: "Chat model_council_run tool",
            modelToolNames: ["model_council_run"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.network.search_tools",
            title: "搜索与网页读取",
            summary: "联网搜索和网页抓取",
            domain: .networkAndConnectivity,
            status: .supported,
            risk: .sensitive,
            requestKind: .foregroundSession,
            requestEntryPoint: "Chat search approval",
            modelToolNames: ["search_web", "scrape_web"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.mcp.tool_call",
            title: "MCP 调用",
            summary: "调用已配置的 MCP 服务",
            domain: .networkAndConnectivity,
            status: .supported,
            risk: .high,
            requestKind: .foregroundSession,
            requestEntryPoint: "Chat MCP approval",
            modelToolNames: ["mcp_call", "mcp_test", "mcp_import_from_skill"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.files.selected_read",
            title: "文件选取读取",
            summary: "读取用户选取的文件",
            domain: .filesAndPhotos,
            status: .supported,
            risk: .sensitive,
            requestKind: .picker,
            requestEntryPoint: "SwiftUI fileImporter / UIDocumentPickerViewController",
            uiActionNames: ["file_pick"],
            modelToolNames: ["file_read_selected"],
            requiredInfoPlistKeys: [],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.workspace.file_read",
            title: "Workspace 读取",
            summary: "读取工作区文件和产出",
            domain: .filesAndPhotos,
            status: .supported,
            risk: .sensitive,
            requestKind: .foregroundSession,
            requestEntryPoint: "Chat Workspace read approval",
            modelToolNames: ["workspace_file_read", "workspace_file_list", "workspace_file_search", "workspace_artifact_read"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.workspace.file_write",
            title: "Workspace 写入",
            summary: "写入文件或删除产出",
            domain: .filesAndPhotos,
            status: .supported,
            risk: .high,
            requestKind: .foregroundSession,
            requestEntryPoint: "Chat Workspace write approval",
            modelToolNames: ["workspace_file_write", "workspace_file_edit", "workspace_file_move", "workspace_artifact_delete"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.files.security_scoped",
            title: "安全范围文件",
            summary: "通过安全书签访问文件",
            domain: .filesAndPhotos,
            status: .degraded,
            risk: .high,
            requestKind: .picker,
            requestEntryPoint: "UIDocumentPickerViewController with security-scoped URL",
            uiActionNames: ["file_pick_security_scoped"],
            blockedToolNames: ["external_file_list", "external_file_read", "external_file_write", "external_file_delete"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.files.export",
            title: "文件导出",
            summary: "导出或分享文件副本",
            domain: .filesAndPhotos,
            status: .degraded,
            risk: .high,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "UIDocumentPickerViewController / UIActivityViewController",
            uiActionNames: ["file_export", "share_file"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.photos.library_read",
            title: "相册读取",
            summary: "访问照片和视频",
            domain: .filesAndPhotos,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "PHPhotoLibrary.requestAuthorization(for: .readWrite)",
            uiActionNames: ["request_photos_read", "photo_library_status"],
            blockedToolNames: ["media_search"],
            requiredInfoPlistKeys: ["NSPhotoLibraryUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.photos.library_add",
            title: "相册写入",
            summary: "保存图片或视频到相册",
            domain: .filesAndPhotos,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "PHPhotoLibrary.requestAuthorization(for: .addOnly)",
            uiActionNames: ["request_photos_add"],
            requiredInfoPlistKeys: ["NSPhotoLibraryAddUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.photos.limited_library_management",
            title: "受限相册管理",
            summary: "更新受限相册选择",
            domain: .filesAndPhotos,
            status: .degraded,
            risk: .sensitive,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "PHPhotoLibrary.presentLimitedLibraryPicker",
            uiActionNames: ["open_limited_photo_library_picker"],
            requiredInfoPlistKeys: ["NSPhotoLibraryUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.photos.picker",
            title: "照片视频选取",
            summary: "选取照片或视频",
            domain: .filesAndPhotos,
            status: .supported,
            risk: .sensitive,
            requestKind: .picker,
            requestEntryPoint: "PhotosPicker / PHPickerViewController",
            uiActionNames: ["photo_pick", "video_pick"],
            blockedToolNames: ["media_search"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.files.external_storage_capture",
            title: "外部存储",
            summary: "Request authorization to capture media directly onto a connected external storage device.",
            domain: .filesAndPhotos,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "AVExternalStorageDevice.requestAccess()",
            uiActionNames: ["request_external_storage_capture", "external_storage_capture_status"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.image_capture.contents",
            title: "Image Capture 内容",
            summary: "Request access to contents on an attached external media device.",
            domain: .filesAndPhotos,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "ICDeviceBrowser.requestContentsAuthorization",
            uiActionNames: ["request_image_capture_contents", "image_capture_contents_status"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.image_capture.control",
            title: "Image Capture 控制",
            summary: "Request authorization to control an attached camera device through ImageCaptureCore.",
            domain: .filesAndPhotos,
            status: .supported,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "ICDeviceBrowser.requestControlAuthorization",
            uiActionNames: ["request_image_capture_control", "image_capture_control_status"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.journaling_suggestions.picker",
            title: "日记建议",
            summary: "Present Apple's Journaling Suggestions picker for user-selected suggestions.",
            domain: .filesAndPhotos,
            status: .degraded,
            risk: .sensitive,
            requestKind: .picker,
            requestEntryPoint: "JournalingSuggestionsPicker",
            uiActionNames: ["open_journaling_suggestions_picker"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),

        capability(
            id: "ios.location.when_in_use",
            title: "使用时定位",
            summary: "Request foreground location access for current location, regions, and beacons.",
            domain: .location,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "CLLocationManager.requestWhenInUseAuthorization()",
            uiActionNames: ["request_location_when_in_use", "location_authorization_status"],
            blockedToolNames: ["location_current"],
            requiredInfoPlistKeys: ["NSLocationWhenInUseUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.location.always",
            title: "始终定位",
            summary: "Request always/background location access.",
            domain: .location,
            status: .supported,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "CLLocationManager.requestAlwaysAuthorization()",
            uiActionNames: ["request_location_always"],
            blockedToolNames: ["location_background"],
            requiredInfoPlistKeys: ["NSLocationAlwaysAndWhenInUseUsageDescription"],
            requiredBackgroundModes: ["location"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.location.temporary_precise",
            title: "临时精确定位",
            summary: "Request temporary full-accuracy location when the user has granted approximate location.",
            domain: .location,
            status: .supported,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "CLLocationManager.requestTemporaryFullAccuracyAuthorization(withPurposeKey:)",
            uiActionNames: ["request_location_temporary_precise"],
            requiredInfoPlistKeys: ["NSLocationTemporaryUsageDescriptionDictionary"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.location.wilderness_safety",
            title: "野外安全定位",
            summary: "Location capability for wilderness safety features when the entitlement is provisioned.",
            domain: .location,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "CoreLocation wilderness safety entitlement",
            uiActionNames: ["location_wilderness_safety_status"],
            requiredInfoPlistKeys: ["NSLocationWildernessSafetyUsageDescription"],
            requiredEntitlements: ["com.apple.developer.corelocation.wilderness-safety"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),

        capability(
            id: "ios.camera.capture",
            title: "相机",
            summary: "Request access to camera capture for photos, video, scanning, and AR camera feed.",
            domain: .cameraAndMicrophone,
            status: .supported,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "AVCaptureDevice.requestAccess(for: .video)",
            uiActionNames: ["request_camera"],
            blockedToolNames: ["camera_capture"],
            requiredInfoPlistKeys: ["NSCameraUsageDescription"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.microphone.record",
            title: "麦克风",
            summary: "Request microphone access for audio recording, speech input, and audio/video capture.",
            domain: .cameraAndMicrophone,
            status: .supported,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "AVCaptureDevice.requestAccess(for: .audio)",
            uiActionNames: ["request_microphone"],
            blockedToolNames: ["audio_record_once"],
            requiredInfoPlistKeys: ["NSMicrophoneUsageDescription"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.camera.multitasking",
            title: "多任务相机",
            summary: "Entitlement-gated AVFoundation multitasking camera access.",
            domain: .cameraAndMicrophone,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "AVFoundation entitlement",
            uiActionNames: ["camera_multitasking_status"],
            requiredEntitlements: ["com.apple.developer.avfoundation.multitasking-camera-access"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.camera.external_uvc",
            title: "外接摄像头",
            summary: "Use external USB Video Class camera devices when the entitlement is provisioned.",
            domain: .cameraAndMicrophone,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "AVFoundation UVC device entitlement",
            uiActionNames: ["external_uvc_camera_status"],
            requiredEntitlements: ["com.apple.developer.avfoundation.uvc-device-access"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),

        capability(
            id: "ios.speech.recognition",
            title: "语音识别",
            summary: "Request Apple Speech framework authorization for speech-to-text.",
            domain: .speechAndMedia,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "SFSpeechRecognizer.requestAuthorization",
            uiActionNames: ["request_speech_recognition"],
            requiredInfoPlistKeys: ["NSSpeechRecognitionUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.speech.personal_voice",
            title: "个人声音",
            summary: "Request authorization to use the user's Personal Voice for speech synthesis.",
            domain: .speechAndMedia,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "AVSpeechSynthesizer.requestPersonalVoiceAuthorization",
            uiActionNames: ["request_personal_voice"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.media.apple_music",
            title: "Apple Music 媒体库",
            summary: "Request access to Apple Music and the user's media library.",
            domain: .speechAndMedia,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "MPMediaLibrary.requestAuthorization",
            uiActionNames: ["request_media_library"],
            requiredInfoPlistKeys: ["NSAppleMusicUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.video_subscriber.account",
            title: "视频订阅",
            summary: "Request TV provider subscription account access.",
            domain: .speechAndMedia,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "VSAccountManager",
            uiActionNames: ["request_video_subscriber_account"],
            requiredInfoPlistKeys: ["NSVideoSubscriberAccountUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),

        capability(
            id: "ios.contacts.full",
            title: "通讯录",
            summary: "Request Contacts framework access for reading, creating, and updating contacts.",
            domain: .contacts,
            status: .supported,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "CNContactStore.requestAccess(for: .contacts)",
            uiActionNames: ["request_contacts"],
            blockedToolNames: ["contacts_search", "contacts_write", "contacts_create_draft"],
            requiredInfoPlistKeys: ["NSContactsUsageDescription"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.contacts.limited",
            title: "通讯录受限访问",
            summary: "Represent iOS limited contacts authorization when the user grants a subset of contacts.",
            domain: .contacts,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "CNContactStore.requestAccess(for: .contacts)",
            uiActionNames: ["request_contacts_limited", "contacts_authorization_status"],
            requiredInfoPlistKeys: ["NSContactsUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.contacts.picker",
            title: "通讯录选取",
            summary: "Let the user select one or more contacts through a foreground system picker without broad contacts access.",
            domain: .contacts,
            status: .degraded,
            risk: .sensitive,
            requestKind: .picker,
            requestEntryPoint: "CNContactPickerViewController",
            uiActionNames: ["open_contacts_picker"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),

        capability(
            id: "ios.calendar.full",
            title: "日历完全访问",
            summary: "Request full EventKit access to read and write calendar events.",
            domain: .calendarAndReminders,
            status: .supported,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "EKEventStore.requestFullAccessToEvents()",
            uiActionNames: ["request_calendar_full"],
            blockedToolNames: ["calendar_list", "calendar_create", "calendar_create_draft"],
            requiredInfoPlistKeys: ["NSCalendarsFullAccessUsageDescription"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.calendar.write_only",
            title: "日历仅写入",
            summary: "Request EventKit write-only access to create events without reading full calendars.",
            domain: .calendarAndReminders,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "EKEventStore.requestWriteOnlyAccessToEvents()",
            uiActionNames: ["request_calendar_write_only"],
            blockedToolNames: ["calendar_create", "calendar_create_draft"],
            requiredInfoPlistKeys: ["NSCalendarsWriteOnlyAccessUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.reminders.full",
            title: "提醒事项",
            summary: "Request full EventKit access to reminders.",
            domain: .calendarAndReminders,
            status: .supported,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "EKEventStore.requestFullAccessToReminders()",
            uiActionNames: ["request_reminders_full"],
            requiredInfoPlistKeys: ["NSRemindersFullAccessUsageDescription"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),

        capability(
            id: "ios.health.read",
            title: "健康数据读取",
            summary: "Request read authorization for selected HealthKit sample types.",
            domain: .healthAndMotion,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "HKHealthStore.requestAuthorization(toShare:read:)",
            uiActionNames: ["request_health_read", "health_authorization_status"],
            blockedToolNames: ["health_step_count_summary"],
            requiredInfoPlistKeys: ["NSHealthShareUsageDescription"],
            requiredEntitlements: ["com.apple.developer.healthkit"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.health.write",
            title: "健康数据写入",
            summary: "Request write authorization for selected HealthKit sample types.",
            domain: .healthAndMotion,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "HKHealthStore.requestAuthorization(toShare:read:)",
            uiActionNames: ["request_health_write"],
            requiredInfoPlistKeys: ["NSHealthUpdateUsageDescription"],
            requiredEntitlements: ["com.apple.developer.healthkit"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.motion.fitness",
            title: "运动与健身",
            summary: "Request access to Core Motion activity and pedometer data.",
            domain: .healthAndMotion,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "CMMotionActivityManager / CMPedometer",
            uiActionNames: ["request_motion_fitness", "motion_authorization_status"],
            blockedToolNames: ["motion_pedometer_summary"],
            requiredInfoPlistKeys: ["NSMotionUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.workoutkit.scheduler",
            title: "健身计划",
            summary: "Request authorization to schedule workouts through WorkoutKit.",
            domain: .healthAndMotion,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "WorkoutScheduler.requestAuthorization()",
            uiActionNames: ["request_workoutkit_scheduler", "workoutkit_scheduler_status"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.motion.fall_detection",
            title: "跌倒检测",
            summary: "Receive Apple Watch Fall Detection events from a watchOS app target when Apple grants the entitlement.",
            domain: .healthAndMotion,
            status: .requiresExtensionTarget,
            risk: .high,
            requestKind: .entitlementAndExtensionRequired,
            requestEntryPoint: "CMFallDetectionManager.requestAuthorizationWithHandler",
            uiActionNames: ["fall_detection_status"],
            unavailableReason: "CMFallDetectionManager is watchOS-only and unavailable to the iOS app target.",
            requiredEntitlements: ["Fall Detection entitlement"],
            requiredExtensionTargets: ["watchOS App"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.finance.financekit",
            title: "财务数据",
            summary: "Request entitlement-gated access to supported financial data.",
            domain: .healthAndMotion,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "FinanceStore.requestAuthorization()",
            uiActionNames: ["request_financekit", "financekit_status"],
            requiredEntitlements: ["com.apple.developer.financekit"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.alarmkit.alarms",
            title: "闹钟",
            summary: "Request authorization to schedule alarms and timers through AlarmKit.",
            domain: .healthAndMotion,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "AlarmManager.requestAuthorization()",
            uiActionNames: ["request_alarmkit", "alarmkit_status"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),

        capability(
            id: "ios.notifications.alerts",
            title: "通知",
            summary: "Request alert, sound, and badge notification authorization.",
            domain: .notifications,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "UNUserNotificationCenter.requestAuthorization",
            uiActionNames: ["request_notifications"],
            blockedToolNames: ["notification_post", "notification_schedule"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.notifications.provisional",
            title: "临时通知",
            summary: "Request provisional notification authorization.",
            domain: .notifications,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "UNAuthorizationOptions.provisional",
            uiActionNames: ["request_notifications_provisional"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.notifications.critical",
            title: "紧急警报",
            summary: "Request critical alert authorization when the entitlement is provisioned.",
            domain: .notifications,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "UNAuthorizationOptions.criticalAlert",
            uiActionNames: ["request_notifications_critical"],
            requiredEntitlements: ["com.apple.developer.usernotifications.critical-alerts"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.notifications.time_sensitive",
            title: "时效性通知",
            summary: "Inspect and use Time Sensitive notification settings when available.",
            domain: .notifications,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .diagnosticOnly,
            requestEntryPoint: "UNNotificationSettings.timeSensitiveSetting",
            uiActionNames: ["notifications_time_sensitive_status"],
            requiredEntitlements: ["com.apple.developer.usernotifications.time-sensitive"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.notifications.live_activities",
            title: "实时活动",
            summary: "Inspect Live Activities availability and settings for ActivityKit.",
            domain: .notifications,
            status: .supported,
            risk: .sensitive,
            requestKind: .diagnosticOnly,
            requestEntryPoint: "ActivityKit.ActivityAuthorizationInfo",
            uiActionNames: ["live_activities_status"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.notifications.communication",
            title: "通信通知",
            summary: "Entitlement-gated communication notification features.",
            domain: .notifications,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "Communication Notifications entitlement",
            uiActionNames: ["communication_notifications_status"],
            requiredEntitlements: ["com.apple.developer.usernotifications.communication"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.messages.critical_sms",
            title: "紧急短信",
            summary: "Request per-recipient authorization for Critical SMS messaging.",
            domain: .notifications,
            status: .degraded,
            risk: .high,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "MSCriticalSMSMessenger.requestAuthorization(for:)",
            uiActionNames: ["critical_sms_recipient_authorization"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),

        capability(
            id: "ios.bluetooth.ble",
            title: "蓝牙",
            summary: "Request Bluetooth authorization for BLE scanning, connection, and peripheral interaction.",
            domain: .networkAndConnectivity,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "CBCentralManager authorization/state",
            uiActionNames: ["request_bluetooth"],
            requiredInfoPlistKeys: ["NSBluetoothAlwaysUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.network.local",
            title: "本地网络",
            summary: "Request local network access by starting a foreground Bonjour/NWBrowser probe.",
            domain: .networkAndConnectivity,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "NWBrowser / Bonjour local network prompt",
            uiActionNames: ["request_local_network"],
            requiredInfoPlistKeys: ["NSLocalNetworkUsageDescription", "NSBonjourServices"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.network.wifi_sharing",
            title: "Wi-Fi 共享",
            summary: "Request Wi-Fi sharing authorization for a selected AccessorySetupKit accessory.",
            domain: .networkAndConnectivity,
            status: .degraded,
            risk: .sensitive,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "WINetworkSharingController.requestAuthorization()",
            uiActionNames: ["wifi_sharing_authorization"],
            requiredEntitlements: ["com.apple.developer.accessory-setup.wifi-sharing"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.network.wifi_info",
            title: "Wi-Fi 信息",
            summary: "Read current SSID/BSSID when the Wi-Fi information entitlement is provisioned.",
            domain: .networkAndConnectivity,
            status: .requiresEntitlement,
            risk: .sensitive,
            requestKind: .entitlementRequired,
            requestEntryPoint: "CNCopyCurrentNetworkInfo / NEHotspotNetwork",
            uiActionNames: ["wifi_info_status"],
            requiredEntitlements: ["com.apple.developer.networking.wifi-info"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.network.hotspot_configuration",
            title: "热点配置",
            summary: "Configure Wi-Fi hotspots when the entitlement is provisioned.",
            domain: .networkAndConnectivity,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "NEHotspotConfigurationManager",
            uiActionNames: ["hotspot_configuration_status"],
            requiredEntitlements: ["com.apple.developer.networking.HotspotConfiguration"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.network.hotspot_helper",
            title: "热点助手",
            summary: "Special entitlement-gated Wi-Fi hotspot helper integration.",
            domain: .networkAndConnectivity,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "NEHotspotHelper",
            uiActionNames: ["hotspot_helper_status"],
            requiredEntitlements: ["com.apple.developer.networking.HotspotHelper"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.network_extension.vpn_dns_filter",
            title: "网络扩展",
            summary: "VPN, DNS proxy, app proxy, and content filter capabilities.",
            domain: .networkAndConnectivity,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementAndExtensionRequired,
            requestEntryPoint: "NetworkExtension managers and system settings",
            uiActionNames: ["network_extension_status"],
            requiredEntitlements: ["com.apple.developer.networking.networkextension"],
            requiredExtensionTargets: ["Network Extension"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.network.multipath",
            title: "多路径网络",
            summary: "Use Multipath TCP when the entitlement is provisioned.",
            domain: .networkAndConnectivity,
            status: .requiresEntitlement,
            risk: .sensitive,
            requestKind: .entitlementRequired,
            requestEntryPoint: "Network entitlement",
            uiActionNames: ["multipath_networking_status"],
            requiredEntitlements: ["com.apple.developer.networking.multipath"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.nfc.reader",
            title: "NFC",
            summary: "Use foreground NFC reader sessions for supported tags.",
            domain: .networkAndConnectivity,
            status: .requiresEntitlement,
            risk: .sensitive,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "NFCNDEFReaderSession / NFCTagReaderSession",
            uiActionNames: ["nfc_reader_status", "open_nfc_reader"],
            requiredInfoPlistKeys: ["NFCReaderUsageDescription"],
            requiredEntitlements: ["com.apple.developer.nfc.readersession.formats"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),

        capability(
            id: "ios.tracking.app_tracking",
            title: "跟踪许可",
            summary: "Request tracking authorization for IDFA and cross-app/site tracking.",
            domain: .identityAndAuthentication,
            status: .supported,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "ATTrackingManager.requestTrackingAuthorization",
            uiActionNames: ["request_app_tracking"],
            requiredInfoPlistKeys: ["NSUserTrackingUsageDescription"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.authentication.face_id",
            title: "面容 ID",
            summary: "Request local biometric authentication with Face ID.",
            domain: .identityAndAuthentication,
            status: .supported,
            risk: .sensitive,
            requestKind: .authenticationOperation,
            requestEntryPoint: "LAContext.evaluatePolicy",
            uiActionNames: ["request_face_id"],
            requiredInfoPlistKeys: ["NSFaceIDUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.focus.status",
            title: "专注状态",
            summary: "Request authorization to access and share the user's Focus status.",
            domain: .identityAndAuthentication,
            status: .supported,
            risk: .sensitive,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "INFocusStatusCenter.requestAuthorization",
            uiActionNames: ["request_focus_status"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.authentication.passkeys_platform_credentials",
            title: "通行密钥",
            summary: "Request authorization for browser-class access to platform public key credentials.",
            domain: .identityAndAuthentication,
            status: .degraded,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "ASAuthorizationWebBrowserPublicKeyCredentialManager.requestAuthorizationForPublicKeyCredentials",
            uiActionNames: ["request_platform_passkeys", "platform_passkeys_status"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.identity",
            title: "身份认证",
            summary: "Identity-related entitlement-gated APIs.",
            domain: .identityAndAuthentication,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "Identity framework entitlement",
            uiActionNames: ["identity_status"],
            requiredInfoPlistKeys: ["NSIdentityUsageDescription"],
            requiredEntitlements: ["com.apple.developer.identity"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.game_center.friends",
            title: "Game Center 好友",
            summary: "Request access to the Game Center friend list.",
            domain: .identityAndAuthentication,
            status: .supported,
            risk: .sensitive,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "GameKit local player authentication / friends APIs",
            uiActionNames: ["request_game_center_friends"],
            requiredInfoPlistKeys: ["NSGKFriendListUsageDescription"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.screen_time.family_controls",
            title: "家庭控制",
            summary: "Request Screen Time / Family Controls authorization.",
            domain: .identityAndAuthentication,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .directSystemPrompt,
            requestEntryPoint: "FamilyControls.AuthorizationCenter",
            uiActionNames: ["request_family_controls"],
            requiredEntitlements: ["com.apple.developer.family-controls"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.sensitive_content_analysis",
            title: "敏感内容分析",
            summary: "Entitlement-gated sensitive content analysis capability.",
            domain: .identityAndAuthentication,
            status: .requiresEntitlement,
            risk: .sensitive,
            requestKind: .entitlementRequired,
            requestEntryPoint: "SensitiveContentAnalysis entitlement",
            uiActionNames: ["sensitive_content_analysis_status"],
            requiredEntitlements: ["com.apple.developer.sensitivecontentanalysis.client"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),

        capability(
            id: "ios.home.homekit",
            title: "HomeKit",
            summary: "Access HomeKit homes, rooms, accessories, and automations.",
            domain: .homeAndNearby,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "HMHomeManager / HomeKit entitlement",
            uiActionNames: ["homekit_status"],
            requiredInfoPlistKeys: ["NSHomeKitUsageDescription"],
            requiredEntitlements: ["com.apple.developer.homekit"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.nearby.interaction",
            title: "近距离交互",
            summary: "Use UWB/Nearby Interaction sessions where supported.",
            domain: .homeAndNearby,
            status: .requiresEntitlement,
            risk: .sensitive,
            requestKind: .entitlementRequired,
            requestEntryPoint: "NearbyInteraction entitlement / NISession",
            uiActionNames: ["nearby_interaction_status"],
            requiredInfoPlistKeys: ["NSNearbyInteractionUsageDescription"],
            requiredEntitlements: ["com.apple.developer.nearby-interaction"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.matter.setup",
            title: "Matter 配对",
            summary: "Use Matter setup payload capability when provisioned.",
            domain: .homeAndNearby,
            status: .requiresEntitlement,
            risk: .sensitive,
            requestKind: .entitlementRequired,
            requestEntryPoint: "Matter entitlement",
            uiActionNames: ["matter_setup_status"],
            requiredEntitlements: ["com.apple.developer.matter.allow-setup-payload"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.accessory.setup",
            title: "配件设置",
            summary: "Use accessory setup discovery and transport extensions.",
            domain: .homeAndNearby,
            status: .requiresEntitlement,
            risk: .sensitive,
            requestKind: .entitlementAndExtensionRequired,
            requestEntryPoint: "Accessory setup / transport extensions",
            uiActionNames: ["accessory_setup_status"],
            requiredEntitlements: [
                "com.apple.developer.accessory-setup-discovery-extension",
                "com.apple.developer.accessory-transport-extension"
            ],
            requiredExtensionTargets: ["Accessory setup extension", "Accessory transport extension"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.accessory.media_device_discovery",
            title: "媒体设备发现",
            summary: "Discover media devices through an entitlement-gated extension.",
            domain: .homeAndNearby,
            status: .requiresEntitlement,
            risk: .sensitive,
            requestKind: .entitlementAndExtensionRequired,
            requestEntryPoint: "Media Device Discovery extension",
            uiActionNames: ["media_device_discovery_status"],
            requiredEntitlements: ["com.apple.developer.media-device-discovery-extension"],
            requiredExtensionTargets: ["Media Device Discovery Extension"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),

        capability(
            id: "ios.siri.shortcuts",
            title: "Siri 与快捷指令",
            summary: "Integrate with SiriKit, App Intents, and Shortcuts.",
            domain: .extensionsAndEntitlements,
            status: .supported,
            risk: .normal,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "SiriKit / App Intents / Shortcuts",
            uiActionNames: ["siri_shortcuts_status"],
            requiredInfoPlistKeys: ["NSSiriUsageDescription"],
            defaultEnabled: true,
            gate: normalGate
        ),
        capability(
            id: "ios.call_directory",
            title: "来电拦截",
            summary: "Provide caller identification and blocking through a Call Directory extension.",
            domain: .extensionsAndEntitlements,
            status: .requiresExtensionTarget,
            risk: .high,
            requestKind: .extensionTargetRequired,
            requestEntryPoint: "Call Directory extension and Settings",
            uiActionNames: ["call_directory_status"],
            requiredExtensionTargets: ["Call Directory Extension"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.sms_filter",
            title: "短信过滤",
            summary: "Filter messages from unknown senders through a Message Filter extension.",
            domain: .extensionsAndEntitlements,
            status: .requiresExtensionTarget,
            risk: .high,
            requestKind: .extensionTargetRequired,
            requestEntryPoint: "Message Filter extension and Settings",
            uiActionNames: ["sms_filter_status"],
            requiredExtensionTargets: ["Message Filter Extension"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.keyboard.full_access",
            title: "键盘完全访问",
            summary: "Allow a third-party keyboard extension to request open access.",
            domain: .extensionsAndEntitlements,
            status: .requiresExtensionTarget,
            risk: .high,
            requestKind: .extensionTargetRequired,
            requestEntryPoint: "Keyboard extension RequestsOpenAccess and Settings",
            uiActionNames: ["keyboard_full_access_status"],
            requiredExtensionTargets: ["Keyboard Extension"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.replaykit.record",
            title: "屏幕录制",
            summary: "Use ReplayKit recording or broadcast picker from foreground UI.",
            domain: .extensionsAndEntitlements,
            status: .degraded,
            risk: .high,
            requestKind: .foregroundSession,
            requestEntryPoint: "RPScreenRecorder / RPSystemBroadcastPickerView",
            uiActionNames: ["open_replaykit_recorder", "open_replaykit_broadcast_picker"],
            requiredExtensionTargets: ["Broadcast Upload Extension for broadcast flows"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.wallet.pass_library",
            title: "钱包凭证",
            summary: "Request or inspect access to Wallet pass library features where supported.",
            domain: .extensionsAndEntitlements,
            status: .supported,
            risk: .sensitive,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "PKPassLibrary authorization / pass sheets",
            uiActionNames: ["pass_library_status", "open_add_pass_sheet"],
            defaultEnabled: true,
            gate: reusableSensitiveGate
        ),
        capability(
            id: "ios.wallet.apple_pay",
            title: "Apple Pay",
            summary: "Use Apple Pay merchant payment authorization when the merchant entitlement is provisioned.",
            domain: .extensionsAndEntitlements,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "PKPaymentAuthorizationController",
            uiActionNames: ["apple_pay_status"],
            requiredEntitlements: ["com.apple.developer.in-app-payments"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.system_extension",
            title: "系统扩展",
            summary: "System-extension style capabilities where the platform supports them.",
            domain: .extensionsAndEntitlements,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementAndExtensionRequired,
            requestEntryPoint: "System Extension entitlement and extension target",
            uiActionNames: ["system_extension_status"],
            requiredInfoPlistKeys: ["NSSystemExtensionUsageDescription"],
            requiredExtensionTargets: ["System Extension"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.autofill_credential_provider",
            title: "自动填充密码",
            summary: "Provide credentials through the AutoFill credential provider extension.",
            domain: .extensionsAndEntitlements,
            status: .requiresExtensionTarget,
            risk: .high,
            requestKind: .extensionTargetRequired,
            requestEntryPoint: "AutoFill Credential Provider extension",
            uiActionNames: ["autofill_credential_provider_status"],
            requiredEntitlements: ["com.apple.developer.authentication-services.autofill-credential-provider"],
            requiredExtensionTargets: ["AutoFill Credential Provider Extension"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.push_to_talk",
            title: "一键通话",
            summary: "Entitlement-gated Push to Talk capability.",
            domain: .extensionsAndEntitlements,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "PushToTalk entitlement",
            uiActionNames: ["push_to_talk_status"],
            requiredEntitlements: ["com.apple.developer.push-to-talk"],
            requiredBackgroundModes: ["voip"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.location_push",
            title: "定位推送",
            summary: "Entitlement-gated location push service capability.",
            domain: .extensionsAndEntitlements,
            status: .requiresEntitlement,
            risk: .high,
            requestKind: .entitlementRequired,
            requestEntryPoint: "Location push entitlement",
            uiActionNames: ["location_push_status"],
            requiredEntitlements: ["com.apple.developer.location.push"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),

        capability(
            id: "ios.embedded.ish_runtime",
            title: "内置 iSH Runtime",
            summary: "Run short Linux shell commands inside AmberAgent's experimental embedded iSH runtime and return stdout/stderr/exit code. Linked only in the ExperimentalGPL target.",
            domain: .networkAndConnectivity,
            status: IOSEmbeddedIshToolCatalog.capabilityStatus,
            risk: .high,
            requestKind: IOSEmbeddedIshToolCatalog.supportedToolNames.isEmpty ? .unsupported : .foregroundSession,
            requestEntryPoint: "Chat ios_ish_execute approval",
            modelToolNames: Array(IOSEmbeddedIshToolCatalog.supportedToolNames).sorted(),
            unavailableReason: IOSEmbeddedIshToolCatalog.unavailableReason,
            defaultEnabled: true,
            canOpenSettings: false,
            gate: freshHighRiskGate
        ),

        capability(
            id: "ios.external.sms_compose",
            title: "短信草稿",
            summary: "Present a system message composer. iOS keeps final send confirmation with the user.",
            domain: .externalApps,
            status: .degraded,
            risk: .high,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "MFMessageComposeViewController",
            uiActionNames: ["sms_compose_draft"],
            blockedToolNames: ["sms_send"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.external.phone_dialer",
            title: "拨打电话",
            summary: "Open a tel URL. iOS keeps final call confirmation with the user.",
            domain: .externalApps,
            status: .degraded,
            risk: .high,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "UIApplication.open(tel:)",
            uiActionNames: ["phone_open_dialer"],
            blockedToolNames: ["call_phone"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.external.share",
            title: "外部分享",
            summary: "Open a foreground system share sheet or whitelisted URL scheme.",
            domain: .externalApps,
            status: .degraded,
            risk: .high,
            requestKind: .foregroundSystemUI,
            requestEntryPoint: "UIActivityViewController / UIApplication.open",
            uiActionNames: ["share_text", "share_file", "app_open_url"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.external.ish_handoff",
            title: "iSH 交接",
            summary: "Prepare a paste-ready command or script for manual execution in iSH. AmberAgent cannot control iSH, run it silently, or read stdout/stderr from its sandbox.",
            domain: .externalApps,
            status: .degraded,
            risk: .high,
            requestKind: .foregroundSession,
            requestEntryPoint: "Chat ish_handoff approval",
            modelToolNames: Array(IOSIshToolCatalog.supportedToolNames).sorted(),
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.webmount.browser",
            title: "WebMount 浏览器",
            summary: "Use a local WKWebView session for allowlisted WebMount stations. Cookie values, tokens, Authorization headers, arbitrary JavaScript, OAuth, and signed fetch are not exposed.",
            domain: .networkAndConnectivity,
            status: .supported,
            risk: .high,
            requestKind: .foregroundSession,
            requestEntryPoint: "WebMount settings and per-site WKWebView",
            uiActionNames: ["webmount_open_site", "webmount_clear_session"],
            modelToolNames: Array(IOSWebMountToolCatalog.supportedToolNames).sorted(),
            blockedToolNames: Array(IOSWebMountToolCatalog.unsupportedToolNames).sorted(),
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),
        capability(
            id: "ios.remote.command",
            title: "远程命令",
            summary: "Run a single command on a trusted Remote SSH profile from foreground UI. Model-dispatched terminal tools remain blocked on iOS.",
            domain: .networkAndConnectivity,
            status: .supported,
            risk: .high,
            requestKind: .foregroundSession,
            requestEntryPoint: "Remote Execution screen",
            uiActionNames: ["remote_command_run", "remote_command_cancel"],
            defaultEnabled: true,
            gate: freshHighRiskGate
        ),

        unsupported(
            id: "android.sms.read",
            title: "短信数据库读取",
            toolNames: ["sms_list", "sms_read"],
            reason: "iOS does not allow third-party apps to read the SMS database."
        ),
        unsupported(
            id: "android.call_log.read",
            title: "通话记录读取",
            toolNames: ["call_log_list"],
            reason: "iOS does not allow third-party apps to read call history."
        ),
        unsupported(
            id: "android.phone_state",
            title: "电话与 SIM 状态",
            toolNames: ["device_phone_state"],
            reason: "iOS has no equivalent public API for SIM, phone number, or call state inspection."
        ),
        unsupported(
            id: "android.notification.listener",
            title: "其他应用通知",
            toolNames: ["notification_list"],
            reason: "iOS apps cannot read notifications from other apps."
        ),
        unsupported(
            id: "android.usage_stats",
            title: "应用使用统计",
            toolNames: ["usage_stats_list"],
            reason: "iOS has no general UsageStats equivalent for third-party apps."
        ),
        unsupported(
            id: "android.manage_all_files",
            title: "所有文件访问",
            toolNames: ["external_file_list", "external_file_read", "external_file_write", "external_file_delete"],
            reason: "需要用户选择文件或导出副本后才能访问。"
        ),
        unsupported(
            id: "android.terminal",
            title: "外部终端进程",
            toolNames: ["terminal_execute", "terminal_job_start", "terminal_session_exec"],
            reason: "iOS does not support Termux-style external process execution."
        ),
        unsupported(
            id: "android.screen_automation",
            title: "跨应用屏幕自动化",
            toolNames: ["screen_capture", "screen_screenshot", "screen_click", "screen_tap", "screen_type", "vlm_task"],
            reason: "iOS does not provide production cross-app accessibility automation to third-party apps."
        ),
        unsupported(
            id: "android.installed_apps",
            title: "已安装应用列表",
            toolNames: ["apps_installed_list"],
            reason: "iOS does not allow third-party apps to enumerate all installed apps."
        ),
        unsupported(
            id: "android.overlay",
            title: "跨应用悬浮窗",
            toolNames: [],
            reason: "iOS does not allow apps to draw floating controls above other apps."
        )
    ]

    static let blockedToolNames: Set<String> = Set(
        capabilities
            .flatMap(\.blockedToolNames)
    )

    static let executableToolNames: Set<String> = Set(
        capabilities
            .filter { $0.status != .unsupported }
            .flatMap(\.modelToolNames)
    )

    static let unsupportedToolNames = blockedToolNames

    static let requestableCapabilities: [IOSPlatformCapability] = capabilities.filter {
        $0.status != .unsupported
    }

    static let directInAppRequestCapabilities: [IOSPlatformCapability] = capabilities.filter {
        $0.requestKind == .directSystemPrompt && ($0.status == .supported || $0.status == .degraded)
    }

    static func capability(forToolName toolName: String) -> IOSPlatformCapability? {
        capabilities
            .filter { $0.modelToolNames.contains(toolName) || $0.blockedToolNames.contains(toolName) }
            .max { lhs, rhs in
                lhs.enforcementRank < rhs.enforcementRank
            }
    }

    static func capability(forUIActionName actionName: String) -> IOSPlatformCapability? {
        capabilities
            .filter { $0.uiActionNames.contains(actionName) }
            .max { lhs, rhs in
                lhs.enforcementRank < rhs.enforcementRank
            }
    }

    static let compositeActionRequirements: [String: Set<String>] = [
        "video_record": ["ios.camera.capture", "ios.microphone.record"],
        "camera_capture_with_audio": ["ios.camera.capture", "ios.microphone.record"],
        "photo_pick_and_add": ["ios.photos.picker", "ios.photos.library_add"],
        "calendar_event_with_alarm": ["ios.calendar.full", "ios.notifications.alerts"]
    ]

    static func capabilities(forActionName actionName: String) -> [IOSPlatformCapability] {
        let directMatches = capabilities.filter {
            $0.uiActionNames.contains(actionName) ||
                $0.modelToolNames.contains(actionName) ||
                $0.blockedToolNames.contains(actionName)
        }
        let compositeIds = compositeActionRequirements[actionName] ?? []
        let compositeMatches = capabilities.filter { compositeIds.contains($0.id) }
        return Array(Set(directMatches + compositeMatches))
            .sorted { lhs, rhs in
                if lhs.enforcementRank == rhs.enforcementRank {
                    lhs.id < rhs.id
                } else {
                    lhs.enforcementRank > rhs.enforcementRank
                }
            }
    }

    private static func capability(
        id: String,
        title: String,
        summary: String,
        domain: IOSCapabilityDomain,
        status: IOSCapabilityStatus,
        risk: IOSCapabilityRisk,
        requestKind: IOSPermissionRequestKind,
        requestEntryPoint: String,
        uiActionNames: [String] = [],
        modelToolNames: [String] = [],
        blockedToolNames: [String] = [],
        unavailableReason: String? = nil,
        requiredInfoPlistKeys: [String] = [],
        requiredEntitlements: [String] = [],
        requiredBackgroundModes: [String] = [],
        requiredExtensionTargets: [String] = [],
        defaultEnabled: Bool,
        canOpenSettings: Bool = true,
        gate: IOSPlatformToolGate
    ) -> IOSPlatformCapability {
        IOSPlatformCapability(
            id: id,
            title: title,
            summary: summary,
            domain: domain,
            status: status,
            risk: risk,
            requestKind: requestKind,
            requestEntryPoint: requestEntryPoint,
            uiActionNames: uiActionNames,
            modelToolNames: modelToolNames,
            blockedToolNames: blockedToolNames,
            unavailableReason: unavailableReason,
            requiredInfoPlistKeys: requiredInfoPlistKeys,
            requiredEntitlements: requiredEntitlements,
            requiredBackgroundModes: requiredBackgroundModes,
            requiredExtensionTargets: requiredExtensionTargets,
            defaultEnabled: defaultEnabled,
            canOpenSettings: canOpenSettings,
            gate: gate
        )
    }

    private static func unsupported(
        id: String,
        title: String,
        toolNames: [String],
        reason: String
    ) -> IOSPlatformCapability {
        capability(
            id: id,
            title: title,
            summary: reason,
            domain: .unavailableOnIOS,
            status: .unsupported,
            risk: .high,
            requestKind: .unsupported,
            requestEntryPoint: "No public iOS API",
            blockedToolNames: toolNames,
            unavailableReason: reason,
            defaultEnabled: false,
            canOpenSettings: false,
            gate: freshHighRiskGate
        )
    }

    private static let normalGate = IOSPlatformToolGate(
        requiresFreshUserPresence: false,
        allowRunScopedReuse: true,
        allowGlobalAutoApproval: false
    )

    private static let reusableSensitiveGate = IOSPlatformToolGate(
        requiresFreshUserPresence: false,
        allowRunScopedReuse: true,
        allowGlobalAutoApproval: false
    )

    private static let freshHighRiskGate = IOSPlatformToolGate(
        requiresFreshUserPresence: true,
        allowRunScopedReuse: false,
        allowGlobalAutoApproval: false
    )
}

private extension IOSPlatformCapability {
    var enforcementRank: Int {
        var rank = 0
        if risk == .sensitive { rank += 10 }
        if risk == .high { rank += 20 }
        if gate.requiresFreshUserPresence { rank += 8 }
        if !gate.allowGlobalAutoApproval { rank += 4 }
        if requestKind == .entitlementRequired || requestKind == .entitlementAndExtensionRequired { rank += 18 }
        if requestKind == .extensionTargetRequired { rank += 18 }
        if status == .unsupported { rank += 100 }
        return rank
    }
}

enum IOSToolApprovalAction: String, Codable, Equatable, Identifiable {
    case allowed
    case denied
    case asked

    var id: String { rawValue }

    var title: String {
        switch self {
        case .allowed: "Allowed"
        case .denied: "Denied"
        case .asked: "Asked"
        }
    }
}

struct IOSToolApprovalRecord: Codable, Equatable, Identifiable {
    var id: String
    var capabilityId: String
    var toolName: String
    var action: IOSToolApprovalAction
    var reason: String
    var runId: String
    var scopeDigest: String
    var payloadDigest: String
    var createdAt: Date
}

@MainActor
@Observable
final class IOSPermissionStore {
    var policies: [String: IOSAgentPermissionPolicy]
    var approvalRecords: [IOSToolApprovalRecord]
    private let capabilities: [IOSPlatformCapability]
    private let userDefaults: UserDefaults
    private let storageKey: String
    private let approvalStorageKey: String
    @ObservationIgnored private let taskStore: IOSAdvancedTaskStore?

    init(
        capabilities: [IOSPlatformCapability] = IOSCapabilityRegistry.capabilities,
        userDefaults: UserDefaults = .standard,
        storageKey: String = "app.amber.ios.permissionPolicies.v2",
        approvalStorageKey: String = "app.amber.ios.toolApprovalRecords.v1",
        taskStore: IOSAdvancedTaskStore? = IOSAdvancedTaskStore.shared
    ) {
        self.capabilities = capabilities
        self.userDefaults = userDefaults
        self.storageKey = storageKey
        self.approvalStorageKey = approvalStorageKey
        self.taskStore = taskStore

        let defaults = Dictionary(
            uniqueKeysWithValues: capabilities.map { capability in
                return (capability.id, Self.defaultPolicy(for: capability))
            }
        )
        let allowedIds = Set(capabilities.map(\.id))
        let savedRaw = userDefaults.dictionary(forKey: storageKey) as? [String: String] ?? [:]
        var loaded = defaults
        var didDropUnknown = false

        for (id, rawPolicy) in savedRaw {
            guard allowedIds.contains(id) else {
                didDropUnknown = true
                continue
            }
            guard let capability = capabilities.first(where: { $0.id == id }),
                  let policy = IOSAgentPermissionPolicy(rawValue: rawPolicy),
                  let normalizedPolicy = Self.normalizedPolicy(policy, for: capability) else {
                didDropUnknown = true
                continue
            }
            loaded[id] = normalizedPolicy
            if normalizedPolicy != policy {
                didDropUnknown = true
            }
        }

        policies = loaded
        approvalRecords = Self.loadApprovalRecords(from: userDefaults, key: approvalStorageKey)
        if didDropUnknown || savedRaw.isEmpty == false {
            persist()
        }
    }

    func policy(for capability: IOSPlatformCapability) -> IOSAgentPermissionPolicy {
        let policy = policies[capability.id] ?? Self.defaultPolicy(for: capability)
        return Self.normalizedPolicy(policy, for: capability) ?? Self.defaultPolicy(for: capability)
    }

    func setPolicy(_ policy: IOSAgentPermissionPolicy, for capability: IOSPlatformCapability) {
        guard let normalizedPolicy = Self.normalizedPolicy(policy, for: capability) else { return }
        policies[capability.id] = normalizedPolicy
        persist()
    }

    @discardableResult
    func recordApproval(
        capabilityId: String,
        toolName: String,
        action: IOSToolApprovalAction,
        reason: String,
        runId: String = "",
        scopeDigest: String = "",
        payloadDigest: String = "",
        now: Date = Date()
    ) -> IOSToolApprovalRecord {
        let record = IOSToolApprovalRecord(
            id: UUID().uuidString,
            capabilityId: capabilityId,
            toolName: IOSAdvancedTaskStore.redacted(toolName),
            action: action,
            reason: IOSAdvancedTaskStore.redacted(reason),
            runId: IOSAdvancedTaskStore.redacted(runId),
            scopeDigest: IOSAdvancedTaskStore.redacted(scopeDigest),
            payloadDigest: IOSAdvancedTaskStore.redacted(payloadDigest),
            createdAt: now
        )
        approvalRecords.insert(record, at: 0)
        approvalRecords = Array(approvalRecords.prefix(120))
        persistApprovalRecords()
        let task = taskStore?.startTask(
            kind: .toolApproval,
            title: "Approval · \(record.toolName)",
            objective: record.reason,
            toolScope: [record.toolName],
            sourceToolName: record.toolName,
            metadata: [
                "capability_id": capabilityId,
                "action": action.rawValue
            ],
            now: now
        )
        _ = taskStore?.updateTask(
            id: task?.id ?? record.id,
            status: action == .denied ? .failed : .completed,
            resultSummary: "\(action.title): \(record.reason)",
            retryable: false,
            cancelCapability: false,
            now: now
        )
        return record
    }

    func latestApproval(for capability: IOSPlatformCapability) -> IOSToolApprovalRecord? {
        approvalRecords.first { $0.capabilityId == capability.id }
    }

    func availablePolicies(for capability: IOSPlatformCapability) -> [IOSAgentPermissionPolicy] {
        Self.availablePolicies(for: capability)
    }

    static func availablePolicies(for capability: IOSPlatformCapability) -> [IOSAgentPermissionPolicy] {
        if capability.status == .unsupported {
            return [.disabled]
        }
        if capability.risk == .high || capability.gate.requiresFreshUserPresence {
            // 高风险工具：禁用 / 每次询问 / 高风险自动批准
            return [.disabled, .askEveryTime, .autoApproveHighRisk]
        }
        // 普通工具：禁用 / 每次询问 / 自动批准
        return [.disabled, .askEveryTime, .autoApprove]
    }

    static func defaultPolicy(for capability: IOSPlatformCapability) -> IOSAgentPermissionPolicy {
        let preferred: IOSAgentPermissionPolicy = capability.defaultEnabled ? .askEveryTime : .disabled
        return availablePolicies(for: capability).contains(preferred)
            ? preferred
            : (availablePolicies(for: capability).first ?? .disabled)
    }

    static func normalizedPolicy(
        _ policy: IOSAgentPermissionPolicy,
        for capability: IOSPlatformCapability
    ) -> IOSAgentPermissionPolicy? {
        let supportedPolicies = availablePolicies(for: capability)
        if supportedPolicies.contains(policy) {
            return policy
        }
        if policy == .allowOncePerRun, supportedPolicies.contains(.askEveryTime) {
            return .askEveryTime
        }
        return nil
    }

    func decisionSummary(
        for capability: IOSPlatformCapability,
        globalAutoApproval: Bool = false,
        highRiskAutoApproval: Bool = false
    ) -> String {
        let policy = policy(for: capability)
        if capability.status == .unsupported {
            return "Unavailable: \(capability.unavailableReason ?? "not available on iOS")"
        }
        if capability.requestKind == .entitlementRequired {
            return "Requires entitlement before system request"
        }
        if capability.requestKind == .extensionTargetRequired {
            return "Requires extension target"
        }
        if capability.requestKind == .entitlementAndExtensionRequired {
            return "Requires entitlement and extension target"
        }
        if capability.requestKind == .settingsOnly {
            return "Managed in system Settings"
        }
        if policy == .disabled {
            return "Agent use disabled; system request still visible"
        }
        if capability.gate.requiresFreshUserPresence {
            return "Requires foreground user action"
        }
        if !capability.gate.allowGlobalAutoApproval && (globalAutoApproval || highRiskAutoApproval) {
            return "Auto-approval ignored"
        }
        return "Ask before agent use"
    }

    private func persist() {
        let currentCapabilityIds = Set(capabilities.map(\.id))
        let raw = policies
            .filter { currentCapabilityIds.contains($0.key) }
            .mapValues(\.rawValue)
        userDefaults.set(raw, forKey: storageKey)
    }

    private func persistApprovalRecords() {
        if let data = try? JSONEncoder().encode(approvalRecords) {
            userDefaults.set(data, forKey: approvalStorageKey)
        }
    }

    private static func loadApprovalRecords(from defaults: UserDefaults, key: String) -> [IOSToolApprovalRecord] {
        guard let data = defaults.data(forKey: key),
              let decoded = try? JSONDecoder().decode([IOSToolApprovalRecord].self, from: data) else {
            return []
        }
        return Array(decoded.sorted { $0.createdAt > $1.createdAt }.prefix(120))
    }
}
