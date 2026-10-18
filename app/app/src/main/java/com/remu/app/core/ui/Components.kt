package com.remu.app.core.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remu.app.R
import com.remu.app.core.theme.Remu
import com.remu.app.core.theme.RemuType
import com.remu.app.core.theme.forText

/**
 * Every visible string goes through RemuText so Devanagari always renders with the bundled Noto
 * Sans Devanagari and Latin with Inter (docs/DESIGN.md section 2). Text wraps, never truncates.
 */
@Composable
fun RemuText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = RemuType.body,
    color: Color = Remu.colors.text,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        style = style.forText(text),
        color = color,
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

private val ButtonShape = RoundedCornerShape(12.dp)

@Composable
private fun ButtonLabel(label: String, working: Boolean, color: Color) {
    if (working) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = color, strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        RemuText(stringResource(R.string.working_sending), style = RemuType.button, color = color, maxLines = 1)
    } else {
        RemuText(label, style = RemuType.button, color = color, maxLines = 1)
    }
}

/** Filled primary button, 56dp, one per screen. Shows "Sending…" with a spinner while working. */
@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    working: Boolean = false,
) {
    val c = Remu.colors
    Button(
        onClick = onClick,
        enabled = enabled && !working,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled || working) 1f else 0.38f),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary, contentColor = c.onPrimary,
            disabledContainerColor = c.primary, disabledContentColor = c.onPrimary,
        ),
    ) { ButtonLabel(label, working, c.onPrimary) }
}

/** 2dp primary outline on surface. */
@Composable
fun SecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    working: Boolean = false,
) {
    val c = Remu.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !working,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).alpha(if (enabled || working) 1f else 0.38f),
        shape = ButtonShape,
        border = BorderStroke(2.dp, c.primary),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = c.primary),
    ) { ButtonLabel(label, working, c.primary) }
}

/** 2dp danger outline. Only Remove member and Remove staff. */
@Composable
fun DestructiveButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Remu.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = ButtonShape,
        border = BorderStroke(2.dp, c.danger),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = c.danger),
    ) { RemuText(label, style = RemuType.button, color = c.danger, maxLines = 1) }
}

/** The filled danger button inside a confirm sheet; its label says what will happen. */
@Composable
fun DangerFilledButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, working: Boolean = false) {
    val c = Remu.colors
    Button(
        onClick = onClick,
        enabled = !working,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.danger, contentColor = Color.White,
            disabledContainerColor = c.danger, disabledContentColor = Color.White,
        ),
    ) { ButtonLabel(label, working, Color.White) }
}

@Composable
fun RemuTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        RemuText(label, style = RemuType.chip, color = Remu.colors.primary)
    }
}

/** Surface fill, 1dp border, 12dp radius, 16dp padding, no elevation. */
@Composable
fun RemuCard(
    modifier: Modifier = Modifier,
    stripe: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Remu.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(12.dp))
            .border(1.dp, c.border, RoundedCornerShape(12.dp)),
    ) {
        if (stripe != null) {
            Box(Modifier.width(4.dp).heightIn(min = 48.dp).background(stripe, RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)))
        }
        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/** 32dp chip with a dot, 15 percent tint fill and text in the state colour. */
@Composable
fun StatusChip(label: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .background(color.copy(alpha = 0.15f), CircleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        RemuText(label, style = RemuType.chip, color = color, maxLines = 1)
    }
}

enum class BannerKind { WARNING, DANGER, INFO }

/** Full width under the app bar, 56dp minimum, icon plus one sentence. */
@Composable
fun Banner(text: String, kind: BannerKind, modifier: Modifier = Modifier, testTag: String = "banner") {
    val c = Remu.colors
    val color = when (kind) {
        BannerKind.WARNING -> c.warning
        BannerKind.DANGER -> c.danger
        BannerKind.INFO -> c.muted
    }
    val icon: ImageVector = if (kind == BannerKind.INFO) Icons.Filled.Info else Icons.Filled.Warning
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Spacer(Modifier.width(12.dp))
        RemuText(text, style = RemuType.body, color = c.text)
    }
}

/** Inline error with a Retry button. Never a code. */
@Composable
fun InlineError(text: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = Remu.colors
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, c.danger, RoundedCornerShape(12.dp))
            .padding(16.dp)
            .testTag("inline_error"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemuText(text, color = c.danger)
        if (onRetry != null) SecondaryButton(stringResource(R.string.action_retry), onRetry)
    }
}

/** Skeleton rows for list loading. Never a full-screen spinner. */
@Composable
fun SkeletonRows(count: Int = 3) {
    val c = Remu.colors
    Column(Modifier.fillMaxWidth().testTag("skeleton"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) {
            Box(Modifier.fillMaxWidth().height(72.dp).background(c.border.copy(alpha = 0.5f), RoundedCornerShape(12.dp)))
        }
    }
}

@Composable
fun EmptyState(text: String) {
    RemuText(text, color = Remu.colors.muted, modifier = Modifier.padding(vertical = 24.dp).testTag("empty"))
}

@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    RemuText(text, style = RemuType.section, modifier = modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemuTopBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val c = Remu.colors
    TopAppBar(
        title = { RemuText(title, style = RemuType.screenTitle, color = c.onPrimary, maxLines = 1) },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = c.primary, titleContentColor = c.onPrimary,
            actionIconContentColor = c.onPrimary, navigationIconContentColor = c.onPrimary,
        ),
    )
}

@Composable
fun RemuField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    tag: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
) {
    val c = Remu.colors
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { RemuText(label, style = RemuType.chip, color = c.muted) },
            modifier = Modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier),
            isError = error != null,
            singleLine = singleLine,
            enabled = enabled,
            visualTransformation = visualTransformation,
            textStyle = RemuType.body.forText(value).copy(color = c.text),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.primary, unfocusedBorderColor = c.border,
                focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
                errorBorderColor = c.danger,
            ),
        )
        if (error != null) {
            RemuText(error, style = RemuType.caption, color = c.danger, modifier = Modifier.padding(top = 4.dp).semantics { contentDescription = error })
        }
    }
}

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Sets FLAG_SECURE while this screen is shown (docs/SECURITY.md section 3.2). */
@Composable
fun SecureScreen() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** The counter phone is wall-powered on a stand; its screen stays on in Cashier mode. */
@Composable
fun KeepScreenOn() {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/** A bottom sheet over a scrim. Used for confirmations and short forms; never during a sale. */
@Composable
fun RemuSheet(onDismiss: () -> Unit, testTag: String = "sheet", content: @Composable ColumnScope.() -> Unit) {
    val c = Remu.colors
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x991C1917))
            .clickable(onClick = onDismiss, indication = null, interactionSource = remember { MutableInteractionSource() }),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(c.surface, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .clickable(onClick = {}, indication = null, interactionSource = remember { MutableInteractionSource() })
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(16.dp)
                .testTag(testTag),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** Status chip for a member, with the shopkeeper's words (docs/DESIGN.md section 5). */
@Composable
fun MemberStatusChip(status: String, hasTemplate: Boolean, modifier: Modifier = Modifier) {
    val c = Remu.colors
    val (label, color) = when {
        !hasTemplate && status != "withdrawn" && status != "expired" -> stringResource(R.string.status_phone_only) to c.muted
        status == "pending_notice" -> stringResource(R.string.status_pending) to c.warning
        status == "active" -> stringResource(R.string.status_active) to c.success
        status == "withdrawn" -> stringResource(R.string.status_withdrawn) to c.muted
        status == "expired" -> stringResource(R.string.status_expired) to c.muted
        else -> status to c.muted
    }
    StatusChip(label, color, modifier.testTag("status_$status"))
}
