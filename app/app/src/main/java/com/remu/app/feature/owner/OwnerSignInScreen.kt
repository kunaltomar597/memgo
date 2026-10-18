package com.remu.app.feature.owner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuField
import com.remu.app.core.ui.RemuTopBar
import com.remu.app.feature.members.BackIcon

@Composable
fun OwnerSignInScreen(vm: OwnerSignInViewModel, onBack: () -> Unit, onNext: (SignInNext) -> Unit) {
    val s by vm.state.collectAsState()
    val c = Remu.colors
    LaunchedEffect(s.next) { s.next?.let { onNext(it); vm.consumeNext() } }
    Column(Modifier.fillMaxSize().background(c.background)) {
        RemuTopBar(stringResource(R.string.owner_signin_title), navigationIcon = { BackIcon(onBack) })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RemuField(s.email, vm::onEmail, stringResource(R.string.field_email), keyboardType = KeyboardType.Email, tag = "signin_email")
            RemuField(s.password, vm::onPassword, stringResource(R.string.field_password), keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(), tag = "signin_password")
            s.error?.let {
                val text = when (it) {
                    SignInError.WRONG -> stringResource(R.string.owner_signin_failed)
                    SignInError.UNREACHABLE -> stringResource(R.string.error_cloud_no_reply)
                    SignInError.NOT_CONFIGURED -> stringResource(R.string.owner_signin_not_configured)
                }
                InlineError(text, if (it == SignInError.UNREACHABLE) vm::signIn else null)
            }
        }
        PrimaryButton(stringResource(R.string.owner_sign_in), vm::signIn, working = s.working,
            enabled = s.email.isNotBlank() && s.password.isNotEmpty(), modifier = Modifier.padding(16.dp).testTag("signin_submit"))
    }
}
