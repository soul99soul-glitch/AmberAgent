import SwiftUI
import UIKit
import Shared
import ChatLayout

enum ChatListAction {
    case regenerate(messageId: String)
    case edit(messageId: String, newText: String)
    case delete(messageId: String)
    case selectVariant(messageId: String, variantIndex: Int)
    case generativeWidget(prompt: String)
    case modifyGeneratedImage(urlString: String, prompt: String, aspectRatio: String)
    case primaryConfiguration
    case modelDefaults
}

struct ChatCollectionMessageList: UIViewControllerRepresentable {
    var signal: ChatMessageUpdateSignal
    var configurationIssue: ChatConfigurationIssue?
    var isGenerationActive: Bool
    var isLoading: Bool
    var isRecognizingImages: Bool
    var contextCompactState: ChatContextCompactState
    var followGeneration: Bool
    var displaySetting: DisplaySetting
    var generativeUiSetting: GenerativeUiSetting
    var reasoningLevelLabel: String?
    var workspaceStore: IOSWorkspaceStore
    var scrollToBottomTrigger: Int
    var messagesProvider: () -> [UIMessage]
    var variantInfoProvider: (String) -> IOSConversationStore.VariantInfo?
    var onAction: (ChatListAction) -> Void
    var onViewportStateChange: (ChatViewportState) -> Void

    func makeUIViewController(context: Context) -> ChatCollectionViewController {
        let controller = ChatCollectionViewController()
        controller.messagesProvider = messagesProvider
        controller.variantInfoProvider = variantInfoProvider
        controller.onAction = onAction
        controller.onViewportStateChange = onViewportStateChange
        return controller
    }

    func updateUIViewController(_ controller: ChatCollectionViewController, context: Context) {
        controller.messagesProvider = messagesProvider
        controller.variantInfoProvider = variantInfoProvider
        controller.onAction = onAction
        controller.onViewportStateChange = onViewportStateChange
        controller.update(
            signal: signal,
            configurationIssue: configurationIssue,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading,
            isRecognizingImages: isRecognizingImages,
            contextCompactState: contextCompactState,
            followGeneration: followGeneration,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: reasoningLevelLabel,
            workspaceStore: workspaceStore,
            scrollToBottomTrigger: scrollToBottomTrigger
        )
    }
}

final class ChatCollectionViewController: UIViewController {
    var messagesProvider: (() -> [UIMessage])?
    var variantInfoProvider: ((String) -> IOSConversationStore.VariantInfo?)?
    var onAction: ((ChatListAction) -> Void)?
    var onViewportStateChange: ((ChatViewportState) -> Void)?

    private let store = ChatListControllerStore()
    private let collectionViewLayout = CollectionViewChatLayout()
    private lazy var collectionView = UICollectionView(frame: .zero, collectionViewLayout: collectionViewLayout)
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>?
    private var latestConfiguration = ChatCollectionConfiguration()
    private var lastScrollToBottomTrigger = 0
    private var isApplyingSnapshot = false
    private var keyboardBottomInset: CGFloat = 0
    private var keyboardFollowGeneration = 0
    private var lastPublishedViewportState: ChatViewportState?

    override func viewDidLoad() {
        super.viewDidLoad()
        configureLayout()
        configureCollectionView()
        configureDataSource()
        observeKeyboard()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    func update(
        signal: ChatMessageUpdateSignal,
        configurationIssue: ChatConfigurationIssue?,
        isGenerationActive: Bool,
        isLoading: Bool,
        isRecognizingImages: Bool,
        contextCompactState: ChatContextCompactState,
        followGeneration: Bool,
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting,
        reasoningLevelLabel: String?,
        workspaceStore: IOSWorkspaceStore,
        scrollToBottomTrigger: Int
    ) {
        latestConfiguration = ChatCollectionConfiguration(
            signal: signal,
            configurationIssue: configurationIssue,
            isGenerationActive: isGenerationActive,
            isLoading: isLoading,
            isRecognizingImages: isRecognizingImages,
            contextCompactState: contextCompactState,
            followGeneration: followGeneration,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            reasoningLevelLabel: reasoningLevelLabel,
            workspaceStore: workspaceStore
        )
        applyCurrentSnapshot()

        if scrollToBottomTrigger != lastScrollToBottomTrigger {
            lastScrollToBottomTrigger = scrollToBottomTrigger
            store.viewportState.followPaused = false
            scrollToBottom(animated: true)
            publishViewportGeometry()
        }
    }

    private func configureLayout() {
        collectionViewLayout.delegate = self
        collectionViewLayout.keepContentOffsetAtBottomOnBatchUpdates = true
        collectionViewLayout.keepContentAtBottomOfVisibleArea = true
        collectionViewLayout.settings.interItemSpacing = 14
        collectionViewLayout.settings.additionalInsets = UIEdgeInsets(
            top: 12,
            left: ChatLayout.contentHorizontalInset,
            bottom: 0,
            right: ChatLayout.contentHorizontalInset
        )
    }

    private func configureCollectionView() {
        view.backgroundColor = .clear
        collectionView.backgroundColor = .clear
        collectionView.alwaysBounceVertical = true
        collectionView.keyboardDismissMode = .onDrag
        collectionView.showsHorizontalScrollIndicator = false
        collectionView.delegate = self
        collectionView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(collectionView)
        NSLayoutConstraint.activate([
            collectionView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            collectionView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            collectionView.topAnchor.constraint(equalTo: view.topAnchor),
            collectionView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])

        let tap = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboardFromListTap))
        tap.cancelsTouchesInView = false
        collectionView.addGestureRecognizer(tap)
    }

    private func configureDataSource() {
        let registration = UICollectionView.CellRegistration<UICollectionViewCell, String> { [weak self] cell, _, itemID in
            Self.resetCellAnimationState(cell)
            guard let self,
                  let renderModel = self.store.renderModelsByID[itemID],
                  let displaySetting = self.latestConfiguration.displaySetting,
                  let generativeUiSetting = self.latestConfiguration.generativeUiSetting,
                  let workspaceStore = self.latestConfiguration.workspaceStore else {
                cell.contentConfiguration = nil
                return
            }
            cell.backgroundColor = .clear
            cell.contentView.backgroundColor = .clear
            cell.contentConfiguration = UIHostingConfiguration {
                ChatListHostedItemView(
                    renderModel: renderModel,
                    displaySetting: displaySetting,
                    generativeUiSetting: generativeUiSetting,
                    workspaceStore: workspaceStore,
                    reasoningLevelLabel: self.latestConfiguration.reasoningLevelLabel,
                    onAction: { [weak self] action in self?.onAction?(action) }
                )
            }
            .margins(.all, 0)
        }

        dataSource = UICollectionViewDiffableDataSource<Int, String>(collectionView: collectionView) { collectionView, indexPath, itemID in
            collectionView.dequeueConfiguredReusableCell(using: registration, for: indexPath, item: itemID)
        }
    }

    private func applyCurrentSnapshot() {
        guard let dataSource,
              let messages = messagesProvider?() else { return }

        let event = latestConfiguration.signal.event
        store.rememberStreamedRendererState(for: event, messages: messages)
        if event != .userMessageAppended {
            store.clearAnimatedInsertions()
        }
        guard let displaySetting = latestConfiguration.displaySetting,
              let generativeUiSetting = latestConfiguration.generativeUiSetting else { return }

        if applyTailStreamDeltaIfPossible(
            messages: messages,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting
        ) {
            return
        }

        let build = ChatListSnapshotBuilder.build(
            messages: messages,
            signal: latestConfiguration.signal,
            configurationIssue: latestConfiguration.configurationIssue,
            isGenerationActive: latestConfiguration.isGenerationActive,
            isLoading: latestConfiguration.isLoading,
            isRecognizingImages: latestConfiguration.isRecognizingImages,
            contextCompactState: latestConfiguration.contextCompactState,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            viewportState: store.viewportState,
            streamedMessageIDs: store.streamedMessageIDs,
            renderStateStore: store.renderStateStore,
            previousSignatures: store.signatures,
            variantInfoProvider: variantInfoProvider ?? { _ in nil }
        )

        let previousItemIDs = dataSource.snapshot().itemIdentifiers
        let animatedInsertionIDs = ChatInsertionAnimationPolicy.animatedInsertionItemIDs(
            previousItemIDs: previousItemIDs,
            rows: build.rows
        )
        let commands = ChatViewportReducer.reduce(
            event: latestConfiguration.signal.event,
            state: &store.viewportState,
            environment: ChatViewportEnvironment(
                followEnabled: latestConfiguration.followGeneration,
                generationActive: latestConfiguration.isStreamingFollowActive
            )
        )

        store.items = build.items
        store.renderModelsByID = build.renderModelsByID
        store.rowHeightCache.invalidate(itemIDs: build.changedIDs)
        store.signatures = build.signatures
        store.queueAnimatedInsertions(animatedInsertionIDs)

        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(build.items.map { $0.id }, toSection: 0)

        let currentIDs = Set(previousItemIDs)
        let reloadIDs = build.changedIDs.filter { currentIDs.contains($0) }
        if !reloadIDs.isEmpty {
            snapshot.reloadItems(reloadIDs)
        }

        isApplyingSnapshot = true
        dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
            guard let self else { return }
            self.isApplyingSnapshot = false
            self.collectionView.layoutIfNeeded()
            self.execute(commands: commands, event: self.latestConfiguration.signal.event)
            self.publishViewportGeometry()
        }
    }

    private func applyTailStreamDeltaIfPossible(
        messages: [UIMessage],
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting
    ) -> Bool {
        guard latestConfiguration.signal.event == .assistantStreamDelta,
              let dataSource,
              let lastMessage = messages.last,
              lastMessage.role == MessageRole.assistant else { return false }

        let messageID = ChatMessageProjector.messageId(for: lastMessage)
        let itemID = "message-\(messageID)"
        var snapshot = dataSource.snapshot()
        guard snapshot.indexOfItem(itemID) != nil,
              store.items.contains(where: { $0.id == itemID }) else {
            return false
        }

        let row = ChatMessageRowModel(
            rowId: messageID,
            messageId: messageID,
            message: lastMessage,
            role: lastMessage.role,
            parts: lastMessage.parts,
            index: max(0, messages.count - 1),
            isLast: true,
            isStreaming: true,
            hasEverStreamed: true,
            canAnimateInsertion: false
        )
        let renderState = store.renderStateStore.stateForRow(
            row,
            isLiveRenderingFarFromBottom: store.viewportState.liveRenderingFarFromBottom
        )
        let signature = ChatListSnapshotBuilder.signatureForRow(
            row,
            renderState: renderState,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting
        )
        store.renderModelsByID[itemID] = .message(
            ChatListMessageRenderModel(
                row: row,
                variantInfo: variantInfoProvider?(messageID),
                renderState: renderState,
                isGenerationActive: latestConfiguration.isGenerationActive,
                renderIdentity: "\(row.messageId)-\(signature)"
            )
        )
        store.signatures[itemID] = signature
        store.rowHeightCache.invalidate(itemIDs: [itemID])

        let commands = ChatViewportReducer.reduce(
            event: latestConfiguration.signal.event,
            state: &store.viewportState,
            environment: ChatViewportEnvironment(
                followEnabled: latestConfiguration.followGeneration,
                generationActive: latestConfiguration.isStreamingFollowActive
            )
        )

        snapshot.reloadItems([itemID])
        isApplyingSnapshot = true
        dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
            guard let self else { return }
            self.isApplyingSnapshot = false
            self.collectionView.layoutIfNeeded()
            self.execute(commands: commands, event: self.latestConfiguration.signal.event)
            self.publishViewportGeometry()
        }
        return true
    }

    private func execute(commands: [ChatViewportScrollCommand], event: ChatEvent) {
        for command in commands {
            switch command {
            case .none:
                break
            case .initialAnchor, .resetForConversationSwitch:
                scrollToBottom(animated: false)
            case let .followBottom(animated, _, deferred):
                guard !store.viewportState.userDragging else { return }
                let action = { [weak self] in
                    let useLinearFollow = animated && event == .assistantStreamDelta
                    self?.scrollToBottom(animated: animated, linear: useLinearFollow)
                }
                if deferred {
                    Task { @MainActor in action() }
                } else {
                    action()
                }
            case let .showBottomButton(visible):
                store.viewportState.showScrollToBottom = visible
            }
        }
        publishViewportStateIfNeeded()
    }

    private func scrollToBottom(animated: Bool, linear: Bool = false) {
        guard let dataSource,
              let itemID = store.items.last(where: { $0.kind == .bottomSpacer })?.id,
              let indexPath = dataSource.indexPath(for: itemID) else { return }

        let scroll = {
            self.collectionView.scrollToItem(at: indexPath, at: .bottom, animated: false)
        }

        if animated {
            if linear {
                UIView.animate(withDuration: 0.08, delay: 0, options: [.curveLinear, .allowUserInteraction]) {
                    scroll()
                }
            } else {
                UIView.animate(withDuration: 0.18, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
                    scroll()
                }
            }
        } else {
            UIView.performWithoutAnimation(scroll)
        }
    }

    private func publishViewportGeometry() {
        let visibleHeight = max(1, collectionView.bounds.height - collectionView.adjustedContentInset.top - collectionView.adjustedContentInset.bottom)
        let visibleMaxY = collectionView.contentOffset.y + collectionView.adjustedContentInset.top + visibleHeight
        let contentHeight = collectionView.contentSize.height
        let distanceToBottom = max(0, contentHeight - visibleMaxY)
        let liveRenderingThreshold = max(
            ChatLayout.liveRenderingLODMinDistance,
            visibleHeight * ChatLayout.liveRenderingLODScreenFactor
        )
        let geometry = ChatViewportGeometrySnapshot(
            atBottom: distanceToBottom <= ChatLayout.bottomStickThreshold,
            isContentScrollable: contentHeight > visibleHeight + ChatLayout.bottomStickThreshold,
            liveRenderingFarFromBottom: distanceToBottom > liveRenderingThreshold
        )
        let commands = ChatViewportReducer.reduceGeometry(
            geometry,
            hasMessages: store.hasMessageItems,
            state: &store.viewportState,
            environment: ChatViewportEnvironment(
                followEnabled: latestConfiguration.followGeneration,
                generationActive: latestConfiguration.isStreamingFollowActive
            )
        )
        if commands.isEmpty {
            publishViewportStateIfNeeded()
        } else {
            execute(commands: commands, event: latestConfiguration.signal.event)
        }
    }

    private func publishViewportStateIfNeeded() {
        guard lastPublishedViewportState != store.viewportState else { return }
        lastPublishedViewportState = store.viewportState
        onViewportStateChange?(store.viewportState)
    }

    private func observeKeyboard() {
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(keyboardWillChangeFrame(_:)),
            name: UIResponder.keyboardWillChangeFrameNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(keyboardDidChangeFrame(_:)),
            name: UIResponder.keyboardDidChangeFrameNotification,
            object: nil
        )
    }

    @objc private func keyboardWillChangeFrame(_ notification: Notification) {
        updateKeyboardInset(from: notification, settleDelay: keyboardAnimationDuration(from: notification) + 0.16)
    }

    @objc private func keyboardDidChangeFrame(_ notification: Notification) {
        updateKeyboardInset(from: notification, settleDelay: 0.08)
    }

    private func updateKeyboardInset(from notification: Notification, settleDelay: TimeInterval) {
        guard let frameValue = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? NSValue else { return }
        let keyboardFrameInView = view.convert(frameValue.cgRectValue, from: nil)
        let overlap = max(0, view.bounds.maxY - keyboardFrameInView.minY)
        keyboardBottomInset = overlap
        collectionView.contentInset.bottom = overlap
        collectionView.verticalScrollIndicatorInsets.bottom = overlap
        guard store.viewportState.isAtBottom || !store.viewportState.followPaused else { return }
        keyboardFollowGeneration &+= 1
        let generation = keyboardFollowGeneration
        DispatchQueue.main.asyncAfter(deadline: .now() + settleDelay) { [weak self] in
            guard let self, self.keyboardFollowGeneration == generation else { return }
            self.scrollToBottom(animated: true)
            self.publishViewportGeometry()
        }
    }

    private func keyboardAnimationDuration(from notification: Notification) -> TimeInterval {
        (notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
    }

    @objc private func dismissKeyboardFromListTap() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    private static func resetCellAnimationState(_ cell: UICollectionViewCell) {
        cell.layer.removeAllAnimations()
        cell.alpha = 1
        cell.transform = .identity
    }
}

extension ChatCollectionViewController: UICollectionViewDelegate {
    func scrollViewWillBeginDragging(_ scrollView: UIScrollView) {
        if isApplyingSnapshot {
            collectionView.layer.removeAllAnimations()
        }
        store.viewportState.userDragging = true
        store.viewportState.followPaused = true
        publishViewportStateIfNeeded()
    }

    func scrollViewDidScroll(_ scrollView: UIScrollView) {
        publishViewportGeometry()
    }

    func scrollViewDidEndDragging(_ scrollView: UIScrollView, willDecelerate decelerate: Bool) {
        if !decelerate {
            store.viewportState.userDragging = false
            publishViewportGeometry()
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        store.viewportState.userDragging = false
        publishViewportGeometry()
    }

    func collectionView(_ collectionView: UICollectionView, willDisplay cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        guard let itemID = dataSource?.itemIdentifier(for: indexPath) else { return }
        store.rowHeightCache.set(
            height: cell.bounds.height,
            for: itemID,
            signature: store.signatures[itemID],
            width: collectionView.bounds.width
        )
        if let messageID = store.messageID(for: itemID) {
            store.renderStateStore.markVisible(messageID, liveRenderingEnabled: true)
        }
        if store.consumeAnimatedInsertion(itemID) {
            animateInsertedUserMessageCell(cell)
        }
    }

    func collectionView(_ collectionView: UICollectionView, didEndDisplaying cell: UICollectionViewCell, forItemAt indexPath: IndexPath) {
        Self.resetCellAnimationState(cell)
        guard let itemID = dataSource?.itemIdentifier(for: indexPath),
              let messageID = store.messageID(for: itemID) else { return }
        store.renderStateStore.freeze(
            messageID: messageID,
            latestText: store.latestText(for: itemID)
        )
    }

    private func animateInsertedUserMessageCell(_ cell: UICollectionViewCell) {
        Self.resetCellAnimationState(cell)
        guard !UIAccessibility.isReduceMotionEnabled else { return }
        cell.alpha = 0
        cell.transform = CGAffineTransform(translationX: 0, y: 18)
            .scaledBy(x: 0.965, y: 0.965)
        UIView.animate(
            withDuration: 0.34,
            delay: 0,
            usingSpringWithDamping: 0.82,
            initialSpringVelocity: 0.18,
            options: [.allowUserInteraction, .beginFromCurrentState]
        ) {
            cell.alpha = 1
            cell.transform = .identity
        }
    }
}

extension ChatCollectionViewController: ChatLayoutDelegate {
    func collectionView(
        _ collectionView: UICollectionView,
        layout collectionViewLayout: CollectionViewChatLayout,
        sizeForItemAt indexPath: IndexPath
    ) -> ItemSize {
        guard let itemID = dataSource?.itemIdentifier(for: indexPath),
              let item = store.item(id: itemID) else {
            return .estimated(CGSize(width: collectionView.bounds.width, height: 44))
        }
        let width = collectionView.bounds.width
        switch item.kind {
        case .bottomSpacer:
            return .exact(CGSize(width: width, height: ChatLayout.bottomRestGap))
        case .emptyState:
            return .estimated(CGSize(width: width, height: 260))
        case .configurationIssue:
            return .estimated(CGSize(width: width, height: 150))
        case .message:
            let estimate = store.rowHeightCache.height(
                for: itemID,
                signature: store.signatures[itemID],
                width: width
            ) ?? 110
            return .estimated(CGSize(width: width, height: estimate))
        case .pendingAssistant:
            return .estimated(CGSize(width: width, height: 72))
        case .visionRecognition:
            return .estimated(CGSize(width: width, height: 54))
        case .contextMarker:
            return .estimated(CGSize(width: width, height: 118))
        }
    }
}

private struct ChatCollectionConfiguration {
    var signal: ChatMessageUpdateSignal
    var configurationIssue: ChatConfigurationIssue?
    var isGenerationActive: Bool
    var isLoading: Bool
    var isRecognizingImages: Bool
    var contextCompactState: ChatContextCompactState
    var followGeneration: Bool
    var displaySetting: DisplaySetting?
    var generativeUiSetting: GenerativeUiSetting?
    var reasoningLevelLabel: String?
    var workspaceStore: IOSWorkspaceStore?

    init(
        signal: ChatMessageUpdateSignal = ChatMessageUpdateSignal(reason: .initialLoad),
        configurationIssue: ChatConfigurationIssue? = nil,
        isGenerationActive: Bool = false,
        isLoading: Bool = false,
        isRecognizingImages: Bool = false,
        contextCompactState: ChatContextCompactState = .idle,
        followGeneration: Bool = true,
        displaySetting: DisplaySetting? = nil,
        generativeUiSetting: GenerativeUiSetting? = nil,
        reasoningLevelLabel: String? = nil,
        workspaceStore: IOSWorkspaceStore? = nil
    ) {
        self.signal = signal
        self.configurationIssue = configurationIssue
        self.isGenerationActive = isGenerationActive
        self.isLoading = isLoading
        self.isRecognizingImages = isRecognizingImages
        self.contextCompactState = contextCompactState
        self.followGeneration = followGeneration
        self.displaySetting = displaySetting
        self.generativeUiSetting = generativeUiSetting
        self.reasoningLevelLabel = reasoningLevelLabel
        self.workspaceStore = workspaceStore
    }

    var isStreamingFollowActive: Bool {
        isGenerationActive || isLoading
    }
}

private struct ChatListHostedItemView: View {
    let renderModel: ChatListRenderModel
    let displaySetting: DisplaySetting
    let generativeUiSetting: GenerativeUiSetting
    let workspaceStore: IOSWorkspaceStore
    let reasoningLevelLabel: String?
    let onAction: (ChatListAction) -> Void

    var body: some View {
        switch renderModel {
        case .emptyState:
            ChatEmptyState()
        case let .configurationIssue(issue, compact):
            ChatConfigurationNoticeCard(
                issue: issue,
                compact: compact,
                onPrimary: { onAction(.primaryConfiguration) },
                onModelDefaults: { onAction(.modelDefaults) }
            )
            .padding(.top, compact ? 0 : 72)
            .padding(.bottom, compact ? 0 : 150)
        case let .message(model):
            MessageBubbleView(
                message: model.row.message,
                messageIndex: model.row.index,
                variantInfo: model.variantInfo,
                displaySetting: displaySetting,
                generativeUiSetting: generativeUiSetting,
                onRegenerate: { onAction(.regenerate(messageId: model.row.messageId)) },
                onEdit: { newText in onAction(.edit(messageId: model.row.messageId, newText: newText)) },
                onDelete: { onAction(.delete(messageId: model.row.messageId)) },
                onSelectVariant: { variantIndex in
                    onAction(.selectVariant(messageId: model.row.messageId, variantIndex: variantIndex))
                },
                onGenerativeWidgetAction: { prompt in onAction(.generativeWidget(prompt: prompt)) },
                onModifyGeneratedImage: { imageURL, prompt, aspectRatio in
                    onAction(.modifyGeneratedImage(urlString: imageURL, prompt: prompt, aspectRatio: aspectRatio))
                },
                isGenerating: model.row.isLast && model.isGenerationActive,
                isLastMessage: model.row.isLast,
                hasEverStreamed: model.renderState.hasEverStreamed,
                liveMarkdownRenderingEnabled: model.renderState.liveRenderingEnabled,
                reasoningLevelLabel: reasoningLevelLabel
            )
            .environment(workspaceStore)
            .id(model.renderIdentity)
        case .pendingAssistant:
            ChatAssistantPendingResponseView()
        case .visionRecognition:
            VisionRecognitionIndicator()
        case let .contextMarker(state):
            ContextCompactTimelineMarker(state: state)
        case .bottomSpacer:
            Color.clear
                .frame(height: ChatLayout.bottomRestGap)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
    }
}

private enum ChatListRenderModel {
    case emptyState
    case configurationIssue(ChatConfigurationIssue, compact: Bool)
    case message(ChatListMessageRenderModel)
    case pendingAssistant
    case visionRecognition
    case contextMarker(ChatContextCompactState)
    case bottomSpacer
}

private struct ChatListMessageRenderModel {
    let row: ChatMessageRowModel
    let variantInfo: IOSConversationStore.VariantInfo?
    let renderState: ChatRenderState
    let isGenerationActive: Bool
    let renderIdentity: String
}

private struct ChatListBuildResult {
    let items: [ChatListItem]
    let renderModelsByID: [String: ChatListRenderModel]
    let signatures: [String: String]
    let changedIDs: [String]
    let rows: [ChatMessageRowModel]
}

private enum ChatListSnapshotBuilder {
    static func build(
        messages: [UIMessage],
        signal: ChatMessageUpdateSignal,
        configurationIssue: ChatConfigurationIssue?,
        isGenerationActive: Bool,
        isLoading: Bool,
        isRecognizingImages: Bool,
        contextCompactState: ChatContextCompactState,
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting,
        viewportState: ChatViewportState,
        streamedMessageIDs: Set<String>,
        renderStateStore: ChatRenderStateStore,
        previousSignatures: [String: String],
        variantInfoProvider: (String) -> IOSConversationStore.VariantInfo?
    ) -> ChatListBuildResult {
        var items: [ChatListItem] = []
        var models: [String: ChatListRenderModel] = [:]
        var signatures: [String: String] = [:]
        var rows: [ChatMessageRowModel] = []

        func append(_ item: ChatListItem, model: ChatListRenderModel, signature: String) {
            items.append(item)
            models[item.id] = model
            signatures[item.id] = signature
        }

        if messages.isEmpty {
            if let configurationIssue {
                append(
                    ChatListItem(id: "configuration-issue-empty", kind: .configurationIssue),
                    model: .configurationIssue(configurationIssue, compact: false),
                    signature: "configuration-empty-\(configurationIssue.title)-\(configurationIssue.message)"
                )
            } else {
                append(
                    ChatListItem(id: "empty-state", kind: .emptyState),
                    model: .emptyState,
                    signature: "empty"
                )
            }
        } else {
            if let configurationIssue {
                append(
                    ChatListItem(id: "configuration-issue-compact", kind: .configurationIssue),
                    model: .configurationIssue(configurationIssue, compact: true),
                    signature: "configuration-compact-\(configurationIssue.title)-\(configurationIssue.message)"
                )
            }

            rows = ChatMessageProjector.rows(
                messages: messages,
                event: signal.event,
                streamedMessageIDs: streamedMessageIDs
            )
            for row in rows {
                let itemID = "message-\(row.messageId)"
                let renderState = renderStateStore.stateForRow(
                    row,
                    isLiveRenderingFarFromBottom: viewportState.liveRenderingFarFromBottom
                )
                let signature = signatureForRow(
                    row,
                    renderState: renderState,
                    displaySetting: displaySetting,
                    generativeUiSetting: generativeUiSetting
                )
                append(
                    ChatListItem(id: itemID, kind: .message(messageId: row.messageId)),
                    model: .message(
                        ChatListMessageRenderModel(
                            row: row,
                            variantInfo: variantInfoProvider(row.messageId),
                            renderState: renderState,
                            isGenerationActive: isGenerationActive,
                            renderIdentity: "\(row.messageId)-\(signature)"
                        )
                    ),
                    signature: signature
                )
            }

            if isGenerationActive || isLoading,
               messages.last?.role == MessageRole.user {
                append(
                    ChatListItem(id: "assistant-pending-response", kind: .pendingAssistant),
                    model: .pendingAssistant,
                    signature: "pending"
                )
            }

            if isRecognizingImages {
                append(
                    ChatListItem(id: "vision-recognition-indicator", kind: .visionRecognition),
                    model: .visionRecognition,
                    signature: "vision"
                )
            }

            if contextCompactState.isVisible {
                let id = "context-compact-\(String(describing: contextCompactState.status))-\(contextCompactState.updatedAt.timeIntervalSince1970)"
                append(
                    ChatListItem(id: id, kind: .contextMarker),
                    model: .contextMarker(contextCompactState),
                    signature: "\(id)-\(contextCompactState.summary)"
                )
            }
        }

        append(
            ChatListItem(id: ChatLayout.bottomAnchorID, kind: .bottomSpacer),
            model: .bottomSpacer,
            signature: "bottom-\(ChatLayout.bottomRestGap)"
        )

        let changedIDs = signatures.compactMap { id, signature in
            previousSignatures[id] == nil || previousSignatures[id] == signature ? nil : id
        }
        return ChatListBuildResult(
            items: items,
            renderModelsByID: models,
            signatures: signatures,
            changedIDs: changedIDs,
            rows: rows
        )
    }

    static func signatureForRow(
        _ row: ChatMessageRowModel,
        renderState: ChatRenderState,
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting
    ) -> String {
        var hasher = Hasher()
        hasher.combine(row.messageId)
        hasher.combine(String(describing: row.role))
        hasher.combine(row.message.toText())
        hasher.combine(row.parts.count)
        for part in row.parts {
            hasher.combine(String(describing: type(of: part)))
            hasher.combine(String(describing: part))
        }
        hasher.combine(row.index)
        hasher.combine(row.isLast)
        hasher.combine(row.isStreaming)
        hasher.combine(row.hasEverStreamed)
        hasher.combine(renderState.rendererMode.rawValue)
        hasher.combine(renderState.liveRenderingEnabled)
        hasher.combine(String(describing: displaySetting))
        hasher.combine(String(describing: generativeUiSetting))
        return String(hasher.finalize())
    }
}

private struct ChatListItem: Hashable {
    let id: String
    let kind: ChatListItemKind
}

private enum ChatListItemKind: Hashable {
    case emptyState
    case configurationIssue
    case message(messageId: String)
    case pendingAssistant
    case visionRecognition
    case contextMarker
    case bottomSpacer
}

private final class ChatListControllerStore {
    var items: [ChatListItem] = []
    var renderModelsByID: [String: ChatListRenderModel] = [:]
    var signatures: [String: String] = [:]
    var streamedMessageIDs: Set<String> = []
    var viewportState = ChatViewportState()
    let renderStateStore = ChatRenderStateStore()
    let rowHeightCache = ChatRowHeightCache()
    private var animatedInsertionItemIDs: Set<String> = []

    var hasMessageItems: Bool {
        items.contains { item in
            if case .message = item.kind { return true }
            return false
        }
    }

    func item(id: String) -> ChatListItem? {
        items.first { $0.id == id }
    }

    func messageID(for itemID: String) -> String? {
        guard let item = item(id: itemID),
              case let .message(messageID) = item.kind else { return nil }
        return messageID
    }

    func latestText(for itemID: String) -> String? {
        guard let renderModel = renderModelsByID[itemID],
              case let .message(messageModel) = renderModel else { return nil }
        return messageModel.row.message.toText()
    }

    func queueAnimatedInsertions(_ itemIDs: [String]) {
        animatedInsertionItemIDs.formUnion(itemIDs)
    }

    func clearAnimatedInsertions() {
        animatedInsertionItemIDs.removeAll()
    }

    func consumeAnimatedInsertion(_ itemID: String) -> Bool {
        animatedInsertionItemIDs.remove(itemID) != nil
    }

    func rememberStreamedRendererState(for event: ChatEvent, messages: [UIMessage]) {
        switch event {
        case .conversationLoaded, .conversationSwitched, .branchChanged:
            streamedMessageIDs.removeAll()
            renderStateStore.removeAll()
            animatedInsertionItemIDs.removeAll()
        default:
            break
        }

        let currentIDs = Set(messages.map(ChatMessageProjector.messageId(for:)))
        streamedMessageIDs = streamedMessageIDs.intersection(currentIDs)
        if event.remembersStreamingRenderer,
           let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) {
            streamedMessageIDs.insert(ChatMessageProjector.messageId(for: lastAssistant))
        }
        renderStateStore.retain(ids: currentIDs)
    }
}

private enum ChatRendererMode: String {
    case staticMarkdown
    case streamingMarkdown
    case frozen
}

private struct ChatRenderState: Equatable {
    var rendererMode: ChatRendererMode
    var hasEverStreamed: Bool
    var liveRenderingEnabled: Bool
}

private final class ChatRenderStateStore {
    private var visibleMessageIDs: Set<String> = []
    private var frozenMessageIDs: Set<String> = []
    private var frozenMarkdownByMessageID: [String: String] = [:]

    func stateForRow(_ row: ChatMessageRowModel, isLiveRenderingFarFromBottom: Bool) -> ChatRenderState {
        let frozen = frozenMessageIDs.contains(row.messageId)
        let live = row.isLast &&
            row.role == MessageRole.assistant &&
            !isLiveRenderingFarFromBottom &&
            !frozen
        if row.hasEverStreamed || row.isStreaming {
            return ChatRenderState(
                rendererMode: live ? .streamingMarkdown : .frozen,
                hasEverStreamed: true,
                liveRenderingEnabled: live
            )
        }
        return ChatRenderState(
            rendererMode: .staticMarkdown,
            hasEverStreamed: false,
            liveRenderingEnabled: true
        )
    }

    func markVisible(_ messageID: String, liveRenderingEnabled: Bool) {
        visibleMessageIDs.insert(messageID)
        if liveRenderingEnabled {
            frozenMessageIDs.remove(messageID)
            frozenMarkdownByMessageID.removeValue(forKey: messageID)
        }
    }

    func freeze(messageID: String, latestText: String?) {
        visibleMessageIDs.remove(messageID)
        frozenMessageIDs.insert(messageID)
        if let latestText {
            frozenMarkdownByMessageID[messageID] = latestText
        }
    }

    func retain(ids: Set<String>) {
        visibleMessageIDs = visibleMessageIDs.intersection(ids)
        frozenMessageIDs = frozenMessageIDs.intersection(ids)
        frozenMarkdownByMessageID = frozenMarkdownByMessageID.filter { ids.contains($0.key) }
    }

    func removeAll() {
        visibleMessageIDs.removeAll()
        frozenMessageIDs.removeAll()
        frozenMarkdownByMessageID.removeAll()
    }
}

private final class ChatRowHeightCache {
    private var heights: [String: CGFloat] = [:]

    func height(for itemID: String, signature: String?, width: CGFloat) -> CGFloat? {
        heights[key(itemID: itemID, signature: signature, width: width)]
    }

    func set(height: CGFloat, for itemID: String, signature: String?, width: CGFloat) {
        guard height > 0 else { return }
        heights[key(itemID: itemID, signature: signature, width: width)] = height
    }

    func invalidate(itemIDs: [String]) {
        guard !itemIDs.isEmpty else { return }
        let prefixes = itemIDs.map { "\($0)-" }
        heights = heights.filter { key, _ in
            !prefixes.contains { key.hasPrefix($0) }
        }
    }

    private func key(itemID: String, signature: String?, width: CGFloat) -> String {
        "\(itemID)-s\(signature ?? "none")-w\(Int(width.rounded()))"
    }
}
