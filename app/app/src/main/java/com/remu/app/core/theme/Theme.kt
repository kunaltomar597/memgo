package com.remu.app.core.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/**
 * The Remu theme. Cashier mode is always light, because the counter is bright; Owner mode
 * follows the phone's dark mode setting (docs/DESIGN.md section 3).
 */
@Composable
fun RemuTheme(forceLight: Boolean = false, content: @Composable () -> Unit) {
    val dark = !forceLight && isSystemInDarkTheme()
    val c = if (dark) DarkRemuColors else LightRemuColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, onSecondary = c.onAccent,
            background = c.background, onBackground = c.text, surface = c.surface, onSurface = c.text,
            error = c.danger, outline = c.border, surfaceVariant = c.surface, onSurfaceVariant = c.muted,
        )
    } else {
        lightColorScheme(
            primary = c.primary, onPrimary = c.onPrimary, secondary = c.accent, onSecondary = c.onAccent,
            background = c.background, onBackground = c.text, surface = c.surface, onSurface = c.text,
            error = c.danger, outline = c.border, surfaceVariant = c.surface, onSurfaceVariant = c.muted,
        )
    }
    val base = Typography()
    val typography = base.copy(
        bodyLarge = RemuType.body.copy(fontFamily = InterFamily),
        bodyMedium = RemuType.body.copy(fontFamily = InterFamily),
        labelLarge = RemuType.button.copy(fontFamily = InterFamily),
        titleLarge = RemuType.screenTitle.copy(fontFamily = InterFamily),
    )
    val shapes = Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(12.dp),
    )
    CompositionLocalProvider(LocalRemuColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}

object Remu {
    val colors: RemuColors
        @Composable get() = LocalRemuColors.current
}
