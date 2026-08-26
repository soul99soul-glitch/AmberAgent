package app.amber.feature.ui.pages.share.handler

import androidx.lifecycle.ViewModel

class ShareHandlerVM(
    text: String,
) : ViewModel() {
    val shareText = checkNotNull(text)
}
