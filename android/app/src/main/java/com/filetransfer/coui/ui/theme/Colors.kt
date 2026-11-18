package com.filetransfer.coui.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** 浅色配色（Material3 ColorScheme） */
val AppLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4F0),
    onPrimaryContainer = Color(0xFF001F3F),

    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00201C),

    tertiary = Color(0xFF6200EE),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEADDFF),
    onTertiaryContainer = Color(0xFF21005D),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF1C1B1F),

    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFEEF0F3),
    onSurfaceVariant = Color(0xFF44474B),

    outline = Color(0xFF73777C),
    outlineVariant = Color(0xFFE0E2E6),

    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Color(0xFFB0CFFD),

    surfaceTint = Color(0xFF1A73E8),
    scrim = Color.Black,
)

/** 深色配色（Material3 ColorScheme） */
val AppDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF00335E),
    primaryContainer = Color(0xFF004B87),
    onPrimaryContainer = Color(0xFFD6E4F0),

    secondary = Color(0xFF80CBC4),
    onSecondary = Color(0xFF00332E),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFB2DFDB),

    tertiary = Color(0xFFBB86FC),
    onTertiary = Color(0xFF2B0057),
    tertiaryContainer = Color(0xFF42008A),
    onTertiaryContainer = Color(0xFFEADDFF),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    background = Color(0xFF121212),
    onBackground = Color(0xFFE6E1E5),

    surface = Color(0xFF1B1B1E),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2A2C30),
    onSurfaceVariant = Color(0xFFC3C6CC),

    outline = Color(0xFF8E9196),
    outlineVariant = Color(0xFF44474B),

    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF313033),
    inversePrimary = Color(0xFF1A73E8),

    surfaceTint = Color(0xFF90CAF9),
    scrim = Color.Black,
)
