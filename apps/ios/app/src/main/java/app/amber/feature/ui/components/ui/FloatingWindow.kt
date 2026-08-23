package app.amber.feature.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxGravity
import com.petterp.floatingx.listener.control.IFxAppControl
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.compose.LocalSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.amber.feature.ui.theme.AmberAgentTheme

@Composable
fun FloatingWindow(
    tag: String,
    visibility: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewModelStoreOwner = LocalViewModelStoreOwner.current
    val savedStateRegistryOwner = LocalSavedStateRegistryOwner.current
    val latestContent by rememberUpdatedState(content)
    var window: IFxAppControl? by remember { mutableStateOf(null) }

    DisposableEffect(
        context,
        lifecycleOwner,
        viewModelStoreOwner,
        savedStateRegistryOwner,
        visibility,
    ) {
        if (!visibility) {
            window?.cancel()
            window = null
            return@DisposableEffect onDispose {}
        }

        window = FloatingX.install {
            setTag(tag)
            setContext(context)
            setGravity(FxGravity.LEFT_OR_BOTTOM)
            setOffsetXY(20f, -20f)
            setEnableAnimation(true)
            setLayoutView(ComposeView(context).apply {
                setViewTreeLifecycleOwner(lifecycleOwner)
                viewModelStoreOwner?.let { setViewTreeViewModelStoreOwner(it) }
                setViewTreeSavedStateRegistryOwner(savedStateRegistryOwner)
                setContent {
                    AmberAgentTheme {
                        latestContent()
                    }
                }
            })
        }
        window?.show()
        onDispose {
            window?.cancel()
            window = null
        }
    }
}
