package com.remu.app.core.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.remu.app.R

/** Inter for Latin and digits (docs/DESIGN.md section 2). */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Noto Sans Devanagari, bundled because the system font breaks matras on old phones. */
val DevanagariFamily = FontFamily(
    Font(R.font.noto_sans_devanagari_regular, FontWeight.Normal),
    Font(R.font.noto_sans_devanagari_medium, FontWeight.Medium),
    Font(R.font.noto_sans_devanagari_semibold, FontWeight.SemiBold),
    Font(R.font.noto_sans_devanagari_bold, FontWeight.Bold),
)

/** True when the text holds any Devanagari code point (U+0900 to U+097F, U+A8E0 to U+A8FF). */
fun containsDevanagari(text: CharSequence): Boolean =
    text.any { it in 'ऀ'..'ॿ' || it in '꣠'..'ꣿ' }

/**
 * Picks the bundled family for a string: Noto Sans Devanagari when it holds Devanagari (its
 * Latin glyphs cover mixed text such as "STOP लिखकर"), Inter otherwise. Line height 1.5 for
 * Devanagari and 1.4 for Latin.
 */
fun TextStyle.forText(text: CharSequence): TextStyle =
    if (containsDevanagari(text)) copy(fontFamily = DevanagariFamily, lineHeight = 1.5.em)
    else copy(fontFamily = InterFamily, lineHeight = 1.4.em)

object RemuType {
    val alertName = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold)
    val screenTitle = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    val scriptLine = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium)
    val section = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal)
    val button = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val chip = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
}
