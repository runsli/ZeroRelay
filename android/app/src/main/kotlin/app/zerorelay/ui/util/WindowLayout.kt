package app.zerorelay.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** M3-inspired width breakpoints for adaptive layouts. */
enum class AppWidthClass {
    Compact,
    Medium,
    Expanded,
}

@Composable
fun rememberAppWidthClass(): AppWidthClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) {
        when {
            widthDp >= 840 -> AppWidthClass.Expanded
            widthDp >= 600 -> AppWidthClass.Medium
            else -> AppWidthClass.Compact
        }
    }
}

val AppWidthClass.supportsListDetail: Boolean
    get() = this == AppWidthClass.Expanded

val AppWidthClass.useNavigationRail: Boolean
    get() = this != AppWidthClass.Compact
