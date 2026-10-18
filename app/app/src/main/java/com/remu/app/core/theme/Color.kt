package com.remu.app.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Colour tokens from docs/DESIGN.md section 3. */
@Immutable
data class RemuColors(
    val primary: Color,
    val onPrimary: Color,
    val accent: Color,
    val onAccent: Color,
    val background: Color,
    val surface: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
)

val LightRemuColors = RemuColors(
    primary = Color(0xFF7A1F2E),
    onPrimary = Color(0xFFFFFFFF),
    accent = Color(0xFFE8A317),
    onAccent = Color(0xFF1C1917),
    background = Color(0xFFFBF7F2),
    surface = Color(0xFFFFFFFF),
    border = Color(0xFFE7DED6),
    text = Color(0xFF1C1917),
    muted = Color(0xFF6B5F58),
    success = Color(0xFF1B7A3E),
    warning = Color(0xFFB45309),
    danger = Color(0xFFB91C1C),
    isDark = false,
)

val DarkRemuColors = RemuColors(
    primary = Color(0xFFF0A0AE),
    onPrimary = Color(0xFF3B0A14),
    accent = Color(0xFFF2B33D),
    onAccent = Color(0xFF1C1917),
    background = Color(0xFF171311),
    surface = Color(0xFF241E1B),
    border = Color(0xFF3A312C),
    text = Color(0xFFF5EFE9),
    muted = Color(0xFFB8ACA4),
    success = Color(0xFF6CCF95),
    warning = Color(0xFFF4A261),
    danger = Color(0xFFF47C7C),
    isDark = true,
)

val LocalRemuColors = staticCompositionLocalOf { LightRemuColors }
