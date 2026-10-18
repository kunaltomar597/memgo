package com.remu.app.feature.members

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.remu.app.R
import com.remu.app.core.theme.RemuType
import com.remu.app.core.ui.InlineError
import com.remu.app.core.ui.PrimaryButton
import com.remu.app.core.ui.RemuField
import com.remu.app.core.ui.RemuSheet
import com.remu.app.core.ui.RemuText
import com.remu.app.core.ui.SecondaryButton

/** Add a bill to an existing member: `POST /members/{id}/purchases`. */
@Composable
fun BillSheet(
    memberName: String,
    draft: BillDraft,
    tried: Boolean,
    sending: Boolean,
    failed: Boolean,
    onChange: (BillDraft) -> Unit,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
) {
    RemuSheet(onDismiss, testTag = "bill_sheet") {
        RemuText(stringResource(R.string.add_bill_title, memberName), style = RemuType.section)
        RemuField(draft.billNo, { onChange(draft.copy(billNo = it)) }, stringResource(R.string.field_bill_no),
            error = if (tried && !draft.billNoOk) stringResource(R.string.error_bill_no) else null, tag = "bill_no")
        RemuField(draft.amount, { onChange(draft.copy(amount = it)) }, stringResource(R.string.field_amount),
            error = if (tried && !draft.amountOk) stringResource(R.string.error_amount_invalid) else null,
            keyboardType = KeyboardType.Decimal, tag = "bill_amount")
        RemuField(draft.items, { onChange(draft.copy(items = it)) }, stringResource(R.string.field_items), tag = "bill_items")
        RemuField(draft.occasion, { onChange(draft.copy(occasion = it)) }, stringResource(R.string.field_occasion), tag = "bill_occasion")
        if (failed) InlineError(stringResource(R.string.error_hub_no_reply), null)
        PrimaryButton(stringResource(R.string.add_bill), onSend, working = sending, modifier = Modifier.testTag("bill_send"))
        SecondaryButton(stringResource(R.string.action_cancel), onDismiss)
    }
}
