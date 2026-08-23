package app.amber.feature.ui.pages.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.amber.core.storage.CleanupDryRun
import app.amber.core.storage.CleanupResult
import app.amber.core.storage.SessionCleanupManager
import app.amber.core.storage.StorageAnalyzer
import app.amber.core.storage.StorageBreakdown
import app.amber.core.utils.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * P7-03 存储占用与按时间清理会话（设置 → 存储）。
 *
 * 流程：展示分类占用（数据库 / 消息正文 / 附件 / 缓存）→ 选择清理 N 天前
 * 会话（默认排除 pinned）→ dry run 展示将删除的会话数/消息数/附件数/估算空间
 * → 确认后事务删除（附件引用记录与会话同事务，先删物理附件；失败可重试）。
 */
class StorageVM(
    private val storageAnalyzer: StorageAnalyzer,
    private val cleanupManager: SessionCleanupManager,
) : ViewModel() {

    val breakdown = MutableStateFlow<UiState<StorageBreakdown>>(UiState.Loading)
    val dryRun = MutableStateFlow<UiState<CleanupDryRun>>(UiState.Idle)
    val cleanupResult = MutableStateFlow<UiState<CleanupResult>>(UiState.Idle)
    val days = MutableStateFlow(30)
    /** 是否正在执行清理（防重复点击）。 */
    val cleaning = MutableStateFlow(false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            breakdown.value = UiState.Loading
            breakdown.value = runCatching { storageAnalyzer.analyze() }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )
        }
    }

    fun selectDays(value: Int) {
        days.value = value
    }

    fun previewCleanup() {
        viewModelScope.launch {
            dryRun.value = UiState.Loading
            dryRun.value = runCatching { cleanupManager.dryRun(cutoffFor(days.value)) }
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it) },
                )
        }
    }

    fun dismissDryRun() {
        dryRun.value = UiState.Idle
    }

    /** 确认执行：dry run 结果只作展示，实际删除以当前状态重新计算（幂等）。 */
    fun confirmCleanup() {
        val plan = (dryRun.value as? UiState.Success)?.data ?: return
        if (cleaning.value) return
        cleaning.value = true
        viewModelScope.launch {
            cleanupResult.value = UiState.Loading
            cleanupResult.value = runCatching { cleanupManager.execute(plan) }
                .fold(
                    onSuccess = {
                        dryRun.value = UiState.Idle
                        UiState.Success(it)
                    },
                    onFailure = {
                        dryRun.value = UiState.Idle
                        UiState.Error(it)
                    },
                )
            cleaning.value = false
            refresh()
        }
    }

    fun dismissResult() {
        cleanupResult.value = UiState.Idle
    }

    private fun cutoffFor(days: Int): Long =
        System.currentTimeMillis() - days * DAY_MILLIS

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
