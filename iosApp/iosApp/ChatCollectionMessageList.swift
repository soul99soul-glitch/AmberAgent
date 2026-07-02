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
    var variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo?
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
    var variantInfoProvider: ((Int) -> IOSConversationStore.VariantInfo?)?
    var onAction: ((ChatListAction) -> Void)?
    var onViewportStateChange: ((ChatViewportState) -> Void)?

    private let store = ChatListControllerStore()
    private let collectionViewLayout = CollectionViewChatLayout()
    private lazy var collectionView = UICollectionView(frame: .zero, collectionViewLayout: collectionViewLayout)
    private var dataSource: UICollectionViewDiffableDataSource<Int, String>?
    private var latestConfiguration = ChatCollectionConfiguration()
    private var lastScrollToBottomTrigger = 0
    /// 计数器而非 Bool:tail-delta 与 viewport 刷新的 apply 可能重叠,
    /// Bool 会被先完成的 completion 提前清掉。
    private var applyingSnapshotCount = 0
    private var keyboardBottomInset: CGFloat = 0
    private var keyboardFollowGeneration = 0
    private var lastPublishedViewportState: ChatViewportState?
    private var lastAppliedUpdateKey: ChatCollectionUpdateKey?

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
        let nextConfiguration = ChatCollectionConfiguration(
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
        latestConfiguration = nextConfiguration
        let updateKey = ChatCollectionUpdateKey(configuration: nextConfiguration)
        // 只有快照真正应用成功才记住 key:首次 update 可能早于 viewDidLoad
        // (dataSource 还没建),那次 no-op 若也记 key,同 key 的后续 update
        // 会被防重放拦住,列表就一直空白。
        if updateKey != lastAppliedUpdateKey, applyCurrentSnapshot() {
            lastAppliedUpdateKey = updateKey
        }

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
        collectionViewLayout.keepContentAtBottomOfVisibleArea = false
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

    @discardableResult
    private func applyCurrentSnapshot() -> Bool {
        guard let dataSource,
              let messages = messagesProvider?() else { return false }

        let event = latestConfiguration.signal.event
        store.rememberStreamedRendererState(for: event, messages: messages)
        if event != .userMessageAppended {
            store.clearAnimatedInsertions()
        }
        guard let displaySetting = latestConfiguration.displaySetting,
              let generativeUiSetting = latestConfiguration.generativeUiSetting else { return false }

        if applyTailStreamDeltaIfPossible(
            messages: messages,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting
        ) {
            return true
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
            reasoningLevelLabel: latestConfiguration.reasoningLevelLabel,
            viewportState: store.viewportState,
            streamedMessageIDs: store.streamedMessageIDs,
            renderStateStore: store.renderStateStore,
            previousDigests: store.digests,
            contentHashCache: store.contentHashCache,
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
        store.rowHeightCache.invalidate(itemIDs: build.heightChangedIDs)
        store.digests = build.digests
        store.queueAnimatedInsertions(animatedInsertionIDs)

        var snapshot = NSDiffableDataSourceSnapshot<Int, String>()
        snapshot.appendSections([0])
        snapshot.appendItems(build.items.map { $0.id }, toSection: 0)

        let currentIDs = Set(previousItemIDs)
        let reconfigureIDs = build.changedIDs.filter { currentIDs.contains($0) }
        if !reconfigureIDs.isEmpty {
            snapshot.reconfigureItems(reconfigureIDs)
        }

        applyingSnapshotCount += 1
        dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
            guard let self else { return }
            self.applyingSnapshotCount -= 1
            self.invalidateLayoutForItemIDs(reconfigureIDs)
            self.collectionView.layoutIfNeeded()
            self.execute(commands: commands, event: self.latestConfiguration.signal.event)
            self.publishViewportGeometry()
        }
        return true
    }

    private func applyTailStreamDeltaIfPossible(
        messages: [UIMessage],
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting
    ) -> Bool {
        guard latestConfiguration.signal.event == .assistantStreamDelta,
              let lastMessage = messages.last,
              lastMessage.role == MessageRole.assistant else { return false }

        // .assistantStreamDelta 走 reduce 的 default 分支,只产出命令、不改 state,
        // 因此提前到 guard 之外计算是安全的(行不在快照里时 fallthrough 全量 build 会再算一次)。
        let commands = ChatViewportReducer.reduce(
            event: latestConfiguration.signal.event,
            state: &store.viewportState,
            environment: ChatViewportEnvironment(
                followEnabled: latestConfiguration.followGeneration,
                generationActive: latestConfiguration.isStreamingFollowActive
            )
        )

        return reconfigureLastAssistantRow(
            lastMessage: lastMessage,
            messagesCount: messages.count,
            isStreaming: true,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            skipIfSignatureUnchanged: false
        ) { controller in
            controller.execute(commands: commands, event: controller.latestConfiguration.signal.event)
            controller.publishViewportGeometry()
        }
    }

    /// tail-delta 与 viewport 刷新共用的「最后一条 assistant 行重建 + reconfigure」路径。
    /// row 的字段口径必须与 ChatMessageProjector.rows 对最后一条 assistant 的产出一致,
    /// 否则 signature 会在这里与全量 build 之间来回翻转。
    @discardableResult
    private func reconfigureLastAssistantRow(
        lastMessage: UIMessage,
        messagesCount: Int,
        isStreaming: Bool,
        displaySetting: DisplaySetting,
        generativeUiSetting: GenerativeUiSetting,
        skipIfSignatureUnchanged: Bool,
        onApplied: @escaping (ChatCollectionViewController) -> Void
    ) -> Bool {
        guard let dataSource else { return false }
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
            index: max(0, messagesCount - 1),
            isLast: true,
            isStreaming: isStreaming,
            hasEverStreamed: isStreaming || store.streamedMessageIDs.contains(messageID),
            canAnimateInsertion: false
        )
        let renderState = store.renderStateStore.stateForRow(
            row,
            isLiveRenderingFarFromBottom: store.viewportState.liveRenderingFarFromBottom
        )
        let variantInfo = variantInfoProvider?(row.index)
        let digest = ChatRowDigests.digest(
            row: row,
            renderState: renderState,
            contentHash: store.contentHashCache.contentHash(for: row),
            isGenerationActive: latestConfiguration.isGenerationActive,
            displaySettingSignature: String(describing: displaySetting),
            generativeUiSettingSignature: String(describing: generativeUiSetting),
            hasMultipleVariants: variantInfo?.hasMultipleVariants == true,
            reasoningLevelLabel: latestConfiguration.reasoningLevelLabel
        )
        // 渲染状态没有实际变化时(比如行在底部附近反复进出视口)不要 reconfigure:
        // 高度缓存被无谓清掉后 sizeForItemAt 会退回估算值,造成 contentSize 抖动。
        let previousDigest = store.digests[itemID]
        if skipIfSignatureUnchanged, previousDigest == digest {
            return false
        }
        store.renderModelsByID[itemID] = .message(
            ChatListMessageRenderModel(
                row: row,
                variantInfo: variantInfo,
                renderState: renderState,
                isGenerationActive: latestConfiguration.isGenerationActive,
                renderIdentity: ChatListSnapshotBuilder.renderIdentityForRow(row, renderState: renderState)
            )
        )
        store.digests[itemID] = digest
        // 高度只依赖 layout:presentation(index)变化不使高度失效。
        if previousDigest?.layout != digest.layout {
            store.rowHeightCache.invalidate(itemIDs: [itemID])
        }

        snapshot.reconfigureItems([itemID])
        applyingSnapshotCount += 1
        dataSource.apply(snapshot, animatingDifferences: false) { [weak self] in
            guard let self else { return }
            self.applyingSnapshotCount -= 1
            self.invalidateLayoutForItemIDs([itemID])
            self.collectionView.layoutIfNeeded()
            onApplied(self)
        }
        return true
    }

    private func execute(commands: [ChatViewportScrollCommand], event: ChatEvent) {
        for command in commands {
            switch command {
            case .none:
                break
            case .initialAnchor, .resetForConversationSwitch:
                anchorToBottomConverged()
            case let .followBottom(animated, _, deferred):
                // 只跳过这一条 followBottom,不能 return:否则同批后续命令
                // 和末尾的 publishViewportStateIfNeeded() 会被一起吞掉。
                guard !store.viewportState.userDragging else { continue }
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

    /// 进会话锚定:列表此时几乎全是估算高度,一次 scrollToItem 会落在"假底部",
    /// 落点后尾屏 cell 实测修正又把真实底部推远。滚动→布局→校验,直到距底≤1pt
    /// 或达到上限(尾屏行实测化后第二轮通常即收敛)。
    private func anchorToBottomConverged() {
        scrollToBottom(animated: false)
        guard collectionView.bounds.height > 0 else { return }
        for _ in 0..<2 {
            collectionView.layoutIfNeeded()
            let visibleHeight = collectionView.bounds.height - collectionView.adjustedContentInset.top - collectionView.adjustedContentInset.bottom
            let visibleMaxY = collectionView.contentOffset.y + collectionView.adjustedContentInset.top + visibleHeight
            let distanceToBottom = collectionView.contentSize.height - visibleMaxY
            if distanceToBottom <= 1 { break }
            scrollToBottom(animated: false)
        }
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
        let previousLiveRenderingFarFromBottom = store.viewportState.liveRenderingFarFromBottom
        let geometry = ChatViewportGeometrySnapshot(
            atBottom: distanceToBottom <= ChatLayout.bottomStickThreshold,
            isContentScrollable: contentHeight > visibleHeight + ChatLayout.bottomStickThreshold,
            liveRenderingFarFromBottom: distanceToBottom > liveRenderingThreshold,
            userScrollActive: collectionView.isTracking || collectionView.isDragging || collectionView.isDecelerating
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
        if previousLiveRenderingFarFromBottom != store.viewportState.liveRenderingFarFromBottom {
            refreshLastAssistantRenderState()
        }
    }

    private func publishViewportStateIfNeeded() {
        guard lastPublishedViewportState != store.viewportState else { return }
        lastPublishedViewportState = store.viewportState
        onViewportStateChange?(store.viewportState)
    }

    private func refreshLastAssistantRenderState() {
        guard let messages = messagesProvider?(),
              let displaySetting = latestConfiguration.displaySetting,
              let generativeUiSetting = latestConfiguration.generativeUiSetting,
              let lastMessage = messages.last,
              lastMessage.role == MessageRole.assistant else { return }

        reconfigureLastAssistantRow(
            lastMessage: lastMessage,
            messagesCount: messages.count,
            isStreaming: latestConfiguration.signal.event == .assistantStreamDelta,
            displaySetting: displaySetting,
            generativeUiSetting: generativeUiSetting,
            skipIfSignatureUnchanged: true
        ) { controller in
            controller.publishViewportStateIfNeeded()
        }
    }

    private func invalidateLayoutForItemIDs(_ itemIDs: [String]) {
        guard !itemIDs.isEmpty else { return }
        let indexPaths = itemIDs.compactMap { dataSource?.indexPath(for: $0) }
        guard !indexPaths.isEmpty else { return }
        let context = collectionViewLayout.invalidationContext(forBoundsChange: collectionView.bounds)
        context.invalidateItems(at: indexPaths)
        collectionViewLayout.invalidateLayout(with: context)
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
        if applyingSnapshotCount > 0 {
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
            signature: store.digests[itemID]?.layout,
            width: collectionView.bounds.width
        )
        if let messageID = store.messageID(for: itemID) {
            store.renderStateStore.markVisible(messageID)
            if store.isLastAssistantItem(itemID) {
                DispatchQueue.main.async { [weak self] in
                    self?.refreshLastAssistantRenderState()
                }
            }
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
                signature: store.digests[itemID]?.layout,
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

private struct ChatCollectionUpdateKey: Equatable {
    let signal: ChatMessageUpdateSignal
    let configurationIssue: ChatConfigurationIssue?
    let isGenerationActive: Bool
    let isLoading: Bool
    let isRecognizingImages: Bool
    let contextCompactState: ChatContextCompactState
    let followGeneration: Bool
    let displaySettingSignature: String
    let generativeUiSettingSignature: String
    let reasoningLevelLabel: String?
    let workspaceStoreID: ObjectIdentifier?

    init(configuration: ChatCollectionConfiguration) {
        signal = configuration.signal
        configurationIssue = configuration.configurationIssue
        isGenerationActive = configuration.isGenerationActive
        isLoading = configuration.isLoading
        isRecognizingImages = configuration.isRecognizingImages
        contextCompactState = configuration.contextCompactState
        followGeneration = configuration.followGeneration
        displaySettingSignature = String(describing: configuration.displaySetting)
        generativeUiSettingSignature = String(describing: configuration.generativeUiSetting)
        reasoningLevelLabel = configuration.reasoningLevelLabel
        workspaceStoreID = configuration.workspaceStore.map(ObjectIdentifier.init)
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
                frozenMarkdownSnapshot: model.renderState.frozenMarkdownSnapshot,
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
    let digests: [String: ChatRowDigest]
    let changedIDs: [String]
    let heightChangedIDs: [String]
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
        reasoningLevelLabel: String?,
        viewportState: ChatViewportState,
        streamedMessageIDs: Set<String>,
        renderStateStore: ChatRenderStateStore,
        previousDigests: [String: ChatRowDigest],
        contentHashCache: ChatRowContentHashCache,
        variantInfoProvider: (Int) -> IOSConversationStore.VariantInfo?
    ) -> ChatListBuildResult {
        var items: [ChatListItem] = []
        var models: [String: ChatListRenderModel] = [:]
        var digests: [String: ChatRowDigest] = [:]
        var rows: [ChatMessageRowModel] = []
        let displaySettingSignature = String(describing: displaySetting)
        let generativeUiSettingSignature = String(describing: generativeUiSetting)

        func append(_ item: ChatListItem, model: ChatListRenderModel, digest: ChatRowDigest) {
            items.append(item)
            models[item.id] = model
            digests[item.id] = digest
        }

        if messages.isEmpty {
            if let configurationIssue {
                append(
                    ChatListItem(id: "configuration-issue-empty", kind: .configurationIssue),
                    model: .configurationIssue(configurationIssue, compact: false),
                    digest: ChatRowDigest(
                        layout: "configuration-empty-\(configurationIssue.title)-\(configurationIssue.message)",
                        presentation: ""
                    )
                )
            } else {
                append(
                    ChatListItem(id: "empty-state", kind: .emptyState),
                    model: .emptyState,
                    digest: ChatRowDigest(layout: "empty", presentation: "")
                )
            }
        } else {
            if let configurationIssue {
                append(
                    ChatListItem(id: "configuration-issue-compact", kind: .configurationIssue),
                    model: .configurationIssue(configurationIssue, compact: true),
                    digest: ChatRowDigest(
                        layout: "configuration-compact-\(configurationIssue.title)-\(configurationIssue.message)",
                        presentation: ""
                    )
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
                let variantInfo = variantInfoProvider(row.index)
                let digest = ChatRowDigests.digest(
                    row: row,
                    renderState: renderState,
                    contentHash: contentHashCache.contentHash(for: row),
                    isGenerationActive: isGenerationActive,
                    displaySettingSignature: displaySettingSignature,
                    generativeUiSettingSignature: generativeUiSettingSignature,
                    hasMultipleVariants: variantInfo?.hasMultipleVariants == true,
                    reasoningLevelLabel: reasoningLevelLabel
                )
                append(
                    ChatListItem(id: itemID, kind: .message(messageId: row.messageId)),
                    model: .message(
                        ChatListMessageRenderModel(
                            row: row,
                            variantInfo: variantInfo,
                            renderState: renderState,
                            isGenerationActive: isGenerationActive,
                            renderIdentity: renderIdentityForRow(row, renderState: renderState)
                        )
                    ),
                    digest: digest
                )
            }

            if isGenerationActive || isLoading,
               messages.last?.role == MessageRole.user {
                append(
                    ChatListItem(id: "assistant-pending-response", kind: .pendingAssistant),
                    model: .pendingAssistant,
                    digest: ChatRowDigest(layout: "pending", presentation: "")
                )
            }

            if isRecognizingImages {
                append(
                    ChatListItem(id: "vision-recognition-indicator", kind: .visionRecognition),
                    model: .visionRecognition,
                    digest: ChatRowDigest(layout: "vision", presentation: "")
                )
            }

            if contextCompactState.isVisible {
                let id = "context-compact-\(String(describing: contextCompactState.status))-\(contextCompactState.updatedAt.timeIntervalSince1970)"
                append(
                    ChatListItem(id: id, kind: .contextMarker),
                    model: .contextMarker(contextCompactState),
                    digest: ChatRowDigest(layout: "\(id)-\(contextCompactState.summary)", presentation: "")
                )
            }
        }

        append(
            ChatListItem(id: ChatLayout.bottomAnchorID, kind: .bottomSpacer),
            model: .bottomSpacer,
            digest: ChatRowDigest(layout: "bottom-\(ChatLayout.bottomRestGap)", presentation: "")
        )

        let changedIDs = digests.compactMap { id, digest -> String? in
            guard let previous = previousDigests[id] else { return nil }
            return previous == digest ? nil : id
        }
        let heightChangedIDs = digests.compactMap { id, digest -> String? in
            guard let previous = previousDigests[id] else { return nil }
            return previous.layout == digest.layout ? nil : id
        }
        return ChatListBuildResult(
            items: items,
            renderModelsByID: models,
            digests: digests,
            changedIDs: changedIDs,
            heightChangedIDs: heightChangedIDs,
            rows: rows
        )
    }

    static func renderIdentityForRow(_ row: ChatMessageRowModel, renderState: ChatRenderState) -> String {
        "\(row.messageId)-\(renderState.rendererMode.rawValue)"
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
    var digests: [String: ChatRowDigest] = [:]
    var streamedMessageIDs: Set<String> = []
    var viewportState = ChatViewportState()
    let renderStateStore = ChatRenderStateStore()
    let rowHeightCache = ChatRowHeightCache()
    let contentHashCache = ChatRowContentHashCache()
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

    /// 冻结快照只在「恰好一个非空 text part」时被消费(MessageBubbleView 的
    /// nonEmptyTextPartCount == 1 门槛),所以这里必须返回那个 part 自己的文本。
    /// 不能用 message.toText():它把 Reasoning/Tool 等 part 映射成空串再用 "\n" 拼接,
    /// 带思考的消息会多出前导空行,与 live 渲染不一致,冻结/解冻切换时产生跳变。
    func latestText(for itemID: String) -> String? {
        guard let renderModel = renderModelsByID[itemID],
              case let .message(messageModel) = renderModel else { return nil }
        let textParts = messageModel.row.message.parts.compactMap { part -> String? in
            guard let text = part as? UIMessagePart.Text, !text.text.isEmpty else { return nil }
            return text.text
        }
        guard textParts.count == 1 else { return nil }
        return textParts[0]
    }

    func isLastAssistantItem(_ itemID: String) -> Bool {
        guard let renderModel = renderModelsByID[itemID],
              case let .message(messageModel) = renderModel else { return false }
        return messageModel.row.isLast && messageModel.row.role == MessageRole.assistant
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
        let currentIDs = Set(messages.map(ChatMessageProjector.messageId(for:)))
        switch event {
        case .conversationLoaded, .conversationSwitched, .branchChanged:
            streamedMessageIDs.removeAll()
            renderStateStore.removeAll()
            contentHashCache.removeAll()
            animatedInsertionItemIDs.removeAll()
            // 高度缓存条目只会因"消息被移除"而变陈旧,而移除只发生在这三类事件;
            // retain 的前缀过滤是 O(条目×消息数),不能挂在每个流式 tick 上。
            rowHeightCache.retain(messageItemIDs: Set(currentIDs.map { "message-\($0)" }))
        default:
            break
        }

        streamedMessageIDs = streamedMessageIDs.intersection(currentIDs)
        if event.remembersStreamingRenderer,
           let lastAssistant = messages.last(where: { $0.role == MessageRole.assistant }) {
            streamedMessageIDs.insert(ChatMessageProjector.messageId(for: lastAssistant))
        }
        renderStateStore.retain(ids: currentIDs)
        contentHashCache.retain(ids: currentIDs)
    }
}

enum ChatRendererMode: String {
    case staticMarkdown
    case streamingMarkdown
    case frozen
}

struct ChatRenderState: Equatable {
    var rendererMode: ChatRendererMode
    var hasEverStreamed: Bool
    var liveRenderingEnabled: Bool
    var frozenMarkdownSnapshot: String?
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
                liveRenderingEnabled: live,
                frozenMarkdownSnapshot: live ? nil : frozenMarkdownByMessageID[row.messageId]
            )
        }
        return ChatRenderState(
            rendererMode: .staticMarkdown,
            hasEverStreamed: false,
            liveRenderingEnabled: true,
            frozenMarkdownSnapshot: nil
        )
    }

    /// 行进入视口即解冻并丢弃冻结快照:可见的行必须显示真实内容,
    /// 不能停在离屏时冻结的旧文本上。
    func markVisible(_ messageID: String) {
        visibleMessageIDs.insert(messageID)
        frozenMessageIDs.remove(messageID)
        frozenMarkdownByMessageID.removeValue(forKey: messageID)
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

/// 消息内容哈希缓存:全量 build 对每行做 toText() + 逐 part describing 的
/// O(全文) 哈希(跨 KMP 桥接)是长 session 卡顿主源,历史消息基本不可变,按行记忆化。
/// 已核实的原地变更路径只有 tool output 空→非空回填(后台补全/审批恢复),
/// 用 parts.count + tool output 计数指纹捕获;最后一条/流式行永不缓存。
/// internal(非 private)是有意的:契约由 `ChatRowContentHashCacheTests` 通过
/// `@testable import` 直接覆盖,`private`/`fileprivate` 会让测试文件完全看不到
/// 这个类型。
final class ChatRowContentHashCache {
    private struct Entry {
        let partsCount: Int
        let toolOutputFingerprint: Int
        let hash: Int
    }

    private var entries: [String: Entry] = [:]

    func contentHash(for row: ChatMessageRowModel) -> Int {
        let fingerprint = Self.toolOutputFingerprint(row.parts)
        if let entry = entries[row.messageId],
           entry.partsCount == row.parts.count,
           entry.toolOutputFingerprint == fingerprint {
            return entry.hash
        }
        var hasher = Hasher()
        hasher.combine(row.message.toText())
        hasher.combine(row.parts.count)
        for part in row.parts {
            hasher.combine(String(describing: type(of: part)))
            hasher.combine(String(describing: part))
        }
        let hash = hasher.finalize()
        if !row.isLast && !row.isStreaming {
            entries[row.messageId] = Entry(
                partsCount: row.parts.count,
                toolOutputFingerprint: fingerprint,
                hash: hash
            )
        }
        return hash
    }

    /// 每个 Tool part 记 (非空标志 + output 条数),空→非空回填必然改变指纹。
    private static func toolOutputFingerprint(_ parts: [UIMessagePart]) -> Int {
        var fingerprint = 0
        for part in parts {
            guard let tool = part as? UIMessagePart.Tool else { continue }
            fingerprint = fingerprint &* 31 &+ (tool.output.isEmpty ? 1 : 2 &+ tool.output.count)
        }
        return fingerprint
    }

    func retain(ids: Set<String>) {
        entries = entries.filter { ids.contains($0.key) }
    }

    func removeAll() {
        entries.removeAll()
    }
}

private final class ChatRowHeightCache {
    private var heights: [String: CGFloat] = [:]
    /// 每个 item 的最近一次实测高度(按宽度),签名失效后作为估算兜底:
    /// 旧实测顶多差一两行,110 默认估算对巨型消息会差几千 pt,
    /// 造成 contentSize 塌陷(完成后大片空白/上滑跳没的根因)。
    private var latestHeights: [String: (width: CGFloat, height: CGFloat)] = [:]

    func height(for itemID: String, signature: String?, width: CGFloat) -> CGFloat? {
        if let exact = heights[key(itemID: itemID, signature: signature, width: width)] {
            return exact
        }
        if let latest = latestHeights[itemID], Int(latest.width.rounded()) == Int(width.rounded()) {
            return latest.height
        }
        return nil
    }

    func set(height: CGFloat, for itemID: String, signature: String?, width: CGFloat) {
        guard height > 0 else { return }
        heights[key(itemID: itemID, signature: signature, width: width)] = height
        latestHeights[itemID] = (width: width, height: height)
    }

    func invalidate(itemIDs: [String]) {
        guard !itemIDs.isEmpty else { return }
        let prefixes = itemIDs.map { "\($0)-" }
        heights = heights.filter { key, _ in
            !prefixes.contains { key.hasPrefix($0) }
        }
        // 有意不清 latestHeights:invalidate 的语义是"精确值不再可信",
        // 兜底估算仍然比 110 默认值准得多。
    }

    /// 会话/分支切换后收口:只保留仍存在的 message 行与非 message 行(bottomSpacer 等
    /// 固定 id),防止长期使用无界增长。
    func retain(messageItemIDs: Set<String>) {
        heights = heights.filter { key, _ in
            !key.hasPrefix("message-") || messageItemIDs.contains(where: { key.hasPrefix("\($0)-") })
        }
        latestHeights = latestHeights.filter { itemID, _ in
            !itemID.hasPrefix("message-") || messageItemIDs.contains(itemID)
        }
    }

    private func key(itemID: String, signature: String?, width: CGFloat) -> String {
        "\(itemID)-s\(signature ?? "none")-w\(Int(width.rounded()))"
    }
}
