package com.remu.app.feature.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.locale.LanguageManager
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.core.ui.SecondaryButton

/** First screen on an unpaired phone: set up the shop, join as the counter phone, or pair. */
@Composable
fun WelcomeScreen(
    language: LanguageManager,
    setupInProgress: Boolean,
    onSetup: () -> Unit,
    onJoinCounter: () -> Unit,
    onPairOwner: () -> Unit,
) {
    val c = Remu.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(stringResource(R.string.app_name))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RemuText(stringResource(R.string.welcome_title), style = RemuType.alertName)
            RemuText(stringResource(R.string.welcome_body))
            PrimaryButton(stringResource(if (setupInProgress) R.string.setup_resume else R.string.welcome_setup_shop), onSetup, modifier = Modifier.testTag("welcome_setup"))
            SecondaryButton(stringResource(R.string.welcome_join_counter), onJoinCounter, modifier = Modifier.testTag("welcome_counter"))
            SecondaryButton(stringResource(R.string.welcome_pair_owner), onPairOwner, modifier = Modifier.testTag("welcome_pair_owner"))
            LanguageSwitch(language)
        }
    }
}
