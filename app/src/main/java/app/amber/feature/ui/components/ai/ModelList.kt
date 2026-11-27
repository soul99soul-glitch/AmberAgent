package app.amber.feature.ui.components.ai

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import app.amber.ai.provider.Modality
import app.amber.ai.provider.Model
import app.amber.ai.provider.ModelType
import app.amber.ai.provider.ProviderSetting
import app.amber.ai.provider.providers.isCodexOAuthReviewModel
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Search
import app.amber.agent.R
import app.amber.feature.ui.components.ui.icons.HeartIcon
import app.amber.feature.ui.theme.LocalAmberTokens
import app.amber.feature.ui.theme.LocalAmberType
import app.amber.feature.ui.theme.extendColors
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

@Composable
internal fun ColumnScope.ModelList(
    currentModel: Uuid? = null,
    providers: List<ProviderSetting>,
    modelType: ModelType,
    preferredInputModality: Modality? = null,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    favoriteModelIds: List<Uuid>,
    onFavoriteModelsChange: (List<Uuid>) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val modelIndex = remember(providers, modelType) {
        providers.buildModelProviderIndex(modelType)
    }
    val allFavoriteModels = remember(favoriteModelIds, modelIndex) {
        favoriteModelIds.mapNotNull { modelId ->
            val entry = modelIndex[modelId] ?: return@mapNotNull null
            if (entry.provider.isCodexOAuthProvider() && entry.model.isCodexOAuthReviewModel()) return@mapNotNull null
            entry.model to entry.provider
        }
    }

    var searchKeywords by remember { mutableStateOf("") }
    val favoriteModels = remember(allFavoriteModels, searchKeywords) {
        allFavoriteModels.filter { (model, _) ->
            model.displayName.contains(searchKeywords, ignoreCase = true)
        }
    }

    val typeFilteredModelsByProvider = remember(providers, modelType, preferredInputModality) {
        providers.pickerModelsByProvider(modelType, preferredInputModality)
    }

    val searchFilteredModelsByProvider = remember(typeFilteredModelsByProvider, searchKeywords) {
        typeFilteredModelsByProvider.mapValues { (_, models) ->
            models.fastFilter {
                it.displayName.contains(searchKeywords, ignoreCase = true)
            }
        }
    }
    val hasSearchResults = favoriteModels.isNotEmpty() ||
        searchFilteredModelsByProvider.values.any { it.isNotEmpty() }

    // Keep accordion state outside LazyList items so every model can remain an
    // independently composed Lazy item. Search starts with matching groups open;
    // an explicit tap then owns that provider's state until the query changes.
    var expandedProviders by remember { mutableStateOf<Map<Uuid, Boolean>>(emptyMap()) }
    LaunchedEffect(searchKeywords, providers) {
        expandedProviders = emptyMap()
    }

    // 计算当前选中模型的位置
    val selectedModelPosition = remember(
        currentModel,
        favoriteModels,
        providers,
        searchFilteredModelsByProvider,
        expandedProviders,
        searchKeywords,
        hasSearchResults,
    ) {
        if (currentModel == null) return@remember 0

        var position = 0

        // 跳过无providers提示
        if (providers.isEmpty()) {
            position += 1
        }
        if (providers.isNotEmpty() && searchKeywords.isNotBlank() && !hasSearchResults) {
            position += 1 // search-empty
        }

        // 检查是否在收藏列表中
        val favoriteIndex = favoriteModels.indexOfFirst { it.first.id == currentModel }
        if (favoriteIndex >= 0) {
            if (favoriteModels.isNotEmpty()) {
                position += 1 // favorite header
            }
            position += favoriteIndex
            return@remember position
        }

        // 跳过收藏列表
        if (favoriteModels.isNotEmpty()) {
            position += 1 // favorite header
            position += favoriteModels.size
        }

        // 在 providers 中查找。这里必须和下面 LazyColumn 的实际 emission 保持一致：
        // 非当前 provider 默认折叠，只计 header（以及 group gap），不能把隐藏的模型行
        // 也计入 initialFirstVisibleItemIndex，否则当前模型在后一个 provider 时会被滚出视口。
        for (providerIndex in providers.indices) {
            val provider = providers[providerIndex]
            val models = searchFilteredModelsByProvider[provider.id].orEmpty()
            if (models.isEmpty()) continue
            if (providerIndex > 0 || favoriteModels.isNotEmpty()) {
                position += 1 // group gap
            }
            position += 1 // provider header
            val expanded = expandedProviders[provider.id]
                ?: (
                    searchKeywords.isNotBlank() ||
                        models.any { it.id == currentModel } ||
                        currentModel == null
                    )
            if (expanded) {
                val modelIndex = models.indexOfFirst { it.id == currentModel }
                if (modelIndex >= 0) {
                    position += modelIndex
                    return@remember position
                }
                position += models.size
            }
        }

        0
    }

    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedModelPosition
    )
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        if (searchKeywords.isBlank()) {
            // 计算favorite models在列表中的位置偏移
            var favoriteStartIndex = 0
            if (providers.isEmpty()) {
                favoriteStartIndex = 1 // no providers item
            }
            if (favoriteModels.isNotEmpty()) {
                favoriteStartIndex += 1 // favorite header
            }

            val fromIndex = from.index - favoriteStartIndex
            val toIndex = to.index - favoriteStartIndex

            // 只处理favorite models范围内的拖拽
            if (fromIndex >= 0 && toIndex >= 0 &&
                fromIndex < favoriteModels.size && toIndex < favoriteModels.size
            ) {
                val newFavoriteModels = favoriteModelIds.toMutableList().apply {
                    add(toIndex, removeAt(fromIndex))
                }
                onFavoriteModelsChange(newFavoriteModels)
            }
        }
    }
    val haptic = LocalHapticFeedback.current

    val providerPositions = remember(
        currentModel,
        providers,
        favoriteModels,
        searchFilteredModelsByProvider,
        expandedProviders,
        searchKeywords,
    ) {
        var currentIndex = 0
        if (providers.isEmpty()) {
            currentIndex = 1 // no providers item
        }
        if (favoriteModels.isNotEmpty()) {
            currentIndex += 1 // favorite header
            currentIndex += favoriteModels.size // favorite models
        }

        providers.mapIndexedNotNull { providerIndex, provider ->
            val groupModels = searchFilteredModelsByProvider[provider.id].orEmpty()
            if (groupModels.isEmpty()) return@mapIndexedNotNull null
            // Match the LazyColumn's original provider index, including filtered groups.
            if (providerIndex > 0 || favoriteModels.isNotEmpty()) currentIndex += 1 // group gap
            val position = currentIndex
            currentIndex += 1 // provider header
            val expanded = expandedProviders[provider.id]
                ?: (
                    searchKeywords.isNotBlank() ||
                        groupModels.any { it.id == currentModel } ||
                        currentModel == null
                    )
            if (expanded) currentIndex += groupModels.size
            provider.id to position
        }.toMap()
    }

    val tokens = LocalAmberTokens.current
    val amberType = LocalAmberType.current
    // Quiet search capsule matching Amber's current sheet surface and hairline treatment.
    Surface(
        shape = CircleShape,
        color = tokens.surface,
        border = BorderStroke(0.5.dp, tokens.line),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 12.dp, bottom = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .heightIn(min = 42.dp)
                .padding(start = 14.dp, end = if (searchKeywords.isNotEmpty()) 4.dp else 14.dp),
        ) {
            Icon(
                imageVector = Lucide.Search,
                contentDescription = null,
                tint = tokens.ink3,
                modifier = Modifier.size(16.dp),
            )
            BasicTextField(
                value = searchKeywords,
                onValueChange = { searchKeywords = it },
                singleLine = true,
                textStyle = amberType.body.copy(fontSize = 14.sp, color = tokens.ink),
                cursorBrush = SolidColor(tokens.accent),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (searchKeywords.isEmpty()) {
                            Text(
                                text = stringResource(R.string.model_list_search_placeholder),
                                style = amberType.body.copy(fontSize = 14.sp),
                                color = tokens.ink3,
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 24.dp),
            )
            if (searchKeywords.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .clickable { searchKeywords = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.X,
                        contentDescription = stringResource(R.string.cancel),
                        tint = tokens.ink3,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
    }

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        if (providers.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.model_list_no_providers),
                    style = amberType.secondary.copy(fontSize = 13.5.sp),
                    color = tokens.ink3,
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                )
            }
        }

        if (providers.isNotEmpty() && searchKeywords.isNotBlank() && !hasSearchResults) {
            item(key = "search-empty") {
                Text(
                    text = stringResource(R.string.model_list_no_matches),
                    style = amberType.secondary.copy(fontSize = 13.5.sp),
                    color = tokens.ink3,
                    modifier = Modifier.padding(vertical = 20.dp, horizontal = 4.dp),
                )
            }
        }

        if (favoriteModels.isNotEmpty()) {
            item(key = "favorite-header") {
                val tokens = LocalAmberTokens.current
                Text(
                    text = stringResource(R.string.model_list_favorite),
                    style = LocalAmberType.current.secondary.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = tokens.ink3,
                    modifier = Modifier.padding(start = 4.dp, bottom = 6.dp, top = 4.dp),
                )
            }

            itemsIndexed(
                items = favoriteModels,
                key = { _, entry -> "favorite:" + entry.first.id.toString() }
            ) { index, (model, provider) ->
                PickerModelRow(
                    model = model,
                    providerSetting = provider,
                    isActive = model.id == currentModel,
                    onSelect = onSelect,
                    onDismiss = onDismiss,
                    isFirst = index == 0,
                    isLast = index == favoriteModels.lastIndex,
                    favorite = true,
                    onToggleFavorite = {
                        onFavoriteModelsChange(favoriteModelIds.filter { it != model.id })
                    },
                )
            }
        }

        // Provider groups use one neutral card surface with hairline-separated
        // model rows. Keep every model in its own Lazy item for long catalogs.
        providers.forEachIndexed { providerIndex, providerSetting ->
            val groupModels = searchFilteredModelsByProvider[providerSetting.id].orEmpty()
            if (groupModels.isEmpty()) return@forEachIndexed

            val providerActive = groupModels.fastAny { it.id == currentModel }
            val expanded = expandedProviders[providerSetting.id]
                ?: (
                    searchKeywords.isNotBlank() ||
                        providerActive ||
                        currentModel == null
                    )
            if (providerIndex > 0 || favoriteModels.isNotEmpty()) {
                item(key = "group-gap:${providerSetting.id}") {
                    Spacer(Modifier.height(8.dp))
                }
            }

            item(key = "group:${providerSetting.id}") {
                val tokens = LocalAmberTokens.current
                Column(modifier = Modifier.animateItem()) {
                    PickerProviderHeader(
                        provider = providerSetting,
                        modelCount = groupModels.size,
                        active = providerActive,
                        expanded = expanded,
                        onClick = {
                            expandedProviders = expandedProviders +
                                (providerSetting.id to !expanded)
                        },
                        showTopCorners = true,
                    )
                }
            }
            if (expanded) {
                itemsIndexed(
                    items = groupModels,
                    key = { _, model -> "group:${providerSetting.id}:model:${model.id}" },
                ) { index, model ->
                    val isActive = model.id == currentModel
                    val favorite = favoriteModelIds.contains(model.id)
                    PickerModelRow(
                        model = model,
                        providerSetting = providerSetting,
                        isActive = isActive,
                        onSelect = onSelect,
                        onDismiss = onDismiss,
                        isLast = index == groupModels.lastIndex,
                        favorite = favorite,
                        onToggleFavorite = {
                            onFavoriteModelsChange(
                                if (favorite) {
                                    favoriteModelIds.filter { it != model.id }
                                } else {
                                    favoriteModelIds + model.id
                                }
                            )
                        },
                    )
                }
            }
        }
    }

    // 供应商Badge行
    val providerBadgeListState = rememberLazyListState()
    LaunchedEffect(lazyListState, providerPositions, providers) {
        // 当LazyColumn滚动时，LazyRow也跟随滚动
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(100) // 防抖处理
            .collect { index ->
                if (index > 0) {
                    val currentProvider = providerPositions.entries.findLast {
                        index >= it.value
                    }
                    val index = providers.indexOfFirst { it.id == currentProvider?.key }
                    if (index >= 0) {
                        providerBadgeListState.animateScrollToItem(index)
                    } else {
                        providerBadgeListState.requestScrollToItem(0)
                    }
                } else {
                    providerBadgeListState.requestScrollToItem(0)
                }
            }
    }
    if (providers.size > 1) {
        val tokens = LocalAmberTokens.current
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                state = providerBadgeListState,
            ) {
                items(providers) { provider ->
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tokens.surface)
                            .border(0.5.dp, tokens.line, CircleShape)
                            .clickable {
                                val position = providerPositions[provider.id] ?: 0
                                coroutineScope.launch {
                                    lazyListState.animateScrollToItem(position)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = provider.name,
                            style = LocalAmberType.current.secondary.copy(fontSize = 12.5.sp),
                            color = tokens.ink2,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
