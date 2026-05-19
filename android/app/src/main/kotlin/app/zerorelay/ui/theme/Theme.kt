package app.zerorelay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun brandedLightScheme(): ColorScheme = expressiveLightColorScheme().copy(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = BlueGrey40,
    onSecondary = Color.White,
    secondaryContainer = Grey90,
    onSecondaryContainer = Grey10,
    tertiary = Green20,
    onTertiary = Color.White,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun brandedDarkScheme(): ColorScheme = darkColorScheme(
    primary = Green80,
    onPrimary = Green10,
    primaryContainer = Green20,
    onPrimaryContainer = Green90,
    secondary = BlueGrey80,
    onSecondary = Grey20,
    secondaryContainer = Grey20,
    onSecondaryContainer = Grey90,
    tertiary = Green80,
    onTertiary = Green10,
    background = Grey10,
    onBackground = Grey90,
    surface = Grey10,
    onSurface = Grey90,
    surfaceVariant = Grey20,
    onSurfaceVariant = Grey80,
    surfaceContainerLow = Grey10,
    surfaceContainerLowest = Grey10,
    surfaceContainer = Grey20,
    surfaceContainerHigh = Grey20,
    surfaceContainerHighest = Grey20,
    outline = BlueGrey40,
    outlineVariant = BlueGrey40,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZeroRelayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> brandedDarkScheme()
        else -> brandedLightScheme()
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = ZeroRelayTypography,
        shapes = ZeroRelayShapes,
        motionScheme = ZeroRelayMotionScheme,
        content = content,
    )
}
