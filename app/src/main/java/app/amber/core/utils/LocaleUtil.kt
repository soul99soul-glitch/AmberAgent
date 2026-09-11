package app.amber.core.utils

import android.content.Context
import java.util.Locale

/**
 * Returns the first locale from this context's Android configuration.
 *
 * Android per-app languages are carried by the context configuration. The
 * JVM default locale can still describe the device/global language, so it is
 * not a substitute for this value when an app-localized context is available.
 */
fun Context.appLocale(): Locale {
    val locales = resources.configuration.locales
    return if (locales.isEmpty) Locale.ROOT else locales[0]
}

fun Context.appLocaleDisplayName(): String {
    val locale = appLocale()
    return locale.getDisplayName(locale)
}
