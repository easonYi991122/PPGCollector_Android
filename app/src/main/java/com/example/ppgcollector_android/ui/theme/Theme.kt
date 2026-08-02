package com.example.ppgcollector_android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = CollectorBlueDark,
    onPrimary = Color.White,
    tertiary = CollectorGreenDark,
    error = CollectorRedDark,
    background = GroupedBackgroundDark,
    onBackground = CollectorOnSurfaceDark,
    surface = GroupedSurfaceDark,
    onSurface = CollectorOnSurfaceDark,
    surfaceVariant = GroupedSurfaceVariantDark,
    onSurfaceVariant = CollectorSecondaryTextDark,
    outline = Color(0xFF636366),
    outlineVariant = Color(0xFF38383A),
)

private val LightColorScheme = lightColorScheme(
    primary = CollectorBlue,
    onPrimary = Color.White,
    tertiary = CollectorGreen,
    error = CollectorRed,
    background = GroupedBackground,
    onBackground = CollectorOnSurface,
    surface = GroupedSurface,
    onSurface = CollectorOnSurface,
    surfaceVariant = GroupedSurfaceVariant,
    onSurfaceVariant = CollectorSecondaryText,
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFD1D1D6),
)

@Composable
fun PPGCollector_AndroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Keep the collector identity stable by default; callers may opt into dynamic color.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
