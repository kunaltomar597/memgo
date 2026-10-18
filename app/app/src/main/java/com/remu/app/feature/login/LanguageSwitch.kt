package com.remu.app.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.locale.AppLanguage
import com.remu.app.core.locale.LanguageManager
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.RemuText

/** Hindi or English for this phone. On the login screen and in Settings in both modes. */
@Composable
fun LanguageSwitch(language: LanguageManager, modifier: Modifier = Modifier) {
    val c = Remu.colors
    var current by remember { mutableStateOf(language.current()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RemuText(stringResource(R.string.language_label), style = RemuType.chip, color = c.muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AppLanguage.ENGLISH to stringResource(R.string.language_english), AppLanguage.HINDI to stringResource(R.string.language_hindi))
                .forEach { (lang, label) ->
                    val on = current == lang
                    Box(
                        Modifier
                            .heightIn(min = 48.dp)
                            .background(if (on) c.accent else c.surface, CircleShape)
                            .clickable { current = lang; language.set(lang) }
                            .testTag("lang_${lang.tag}")
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { RemuText(label, style = RemuType.chip, color = if (on) c.onAccent else c.text) }
                }
        }
    }
}
