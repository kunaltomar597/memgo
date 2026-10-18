package com.remu.app.feature.enrol

import com.remu.app.core.format.Validation
import com.remu.app.network.hub.EnrolFace
import com.remu.app.network.hub.EnrolRequest
import java.util.Base64

/** What the cashier typed. Kept in SavedStateHandle so it survives rotation and process death. */
data class BillingForm(
    val name: String = "",
    val phone: String = "",
    val billNo: String = "",
    val amount: String = "",
    val items: String = "",
    val occasion: String = "",
    val notes: String = "",
    val householdLabel: String = "",
    val payerIndex: Int = 0,
    /** Optional names and phones for adults 2 to 6 in a household. */
    val adultNames: List<String> = emptyList(),
    val adultPhones: List<String> = emptyList(),
)

enum class FieldError { PHONE_EMPTY, PHONE_INVALID, AMOUNT_EMPTY, AMOUNT_INVALID, BILL_NO, NAME_EMPTY, TOO_LONG, HOUSEHOLD_LABEL, ADULT_PHONE_INVALID }

/** Per-field errors; empty means the form can be sent. */
data class FormErrors(val byField: Map<String, FieldError> = emptyMap()) {
    val ok: Boolean get() = byField.isEmpty()
    operator fun get(field: String): FieldError? = byField[field]
}

/** Builds the `POST /enrol` body from the form and the in-memory crops (docs/API_CONTRACT.md 1). */
object EnrolPayloadBuilder {
    fun validate(form: BillingForm, faceCount: Int): FormErrors {
        val e = mutableMapOf<String, FieldError>()
        when (Validation.phoneError(form.phone)) {
            Validation.PhoneError.EMPTY -> e["phone"] = FieldError.PHONE_EMPTY
            Validation.PhoneError.INVALID -> e["phone"] = FieldError.PHONE_INVALID
            null -> Unit
        }
        when (Validation.amountError(form.amount)) {
            Validation.AmountError.EMPTY -> e["amount"] = FieldError.AMOUNT_EMPTY
            Validation.AmountError.INVALID -> e["amount"] = FieldError.AMOUNT_INVALID
            null -> Unit
        }
        if (!Validation.isValidBillNo(form.billNo)) e["billNo"] = FieldError.BILL_NO
        if (faceCount > 0 && form.name.isBlank()) e["name"] = FieldError.NAME_EMPTY
        if (form.name.length > Validation.MAX_NAME) e["name"] = FieldError.TOO_LONG
        if (form.items.length > Validation.MAX_ITEMS) e["items"] = FieldError.TOO_LONG
        if (form.occasion.length > Validation.MAX_OCCASION) e["occasion"] = FieldError.TOO_LONG
        if (form.notes.length > Validation.MAX_NOTES) e["notes"] = FieldError.TOO_LONG
        if (faceCount > 1) {
            if (form.householdLabel.isBlank()) e["householdLabel"] = FieldError.HOUSEHOLD_LABEL
            if (form.householdLabel.length > Validation.MAX_LABEL) e["householdLabel"] = FieldError.TOO_LONG
            for (i in 1 until faceCount) {
                val p = form.adultPhones.getOrNull(i - 1).orEmpty()
                if (p.isNotBlank() && Validation.normalisePhone(p) == null) e["adultPhone$i"] = FieldError.ADULT_PHONE_INVALID
            }
        }
        return FormErrors(e)
    }

    /**
     * [crops] in selection order: crop 0 is adult 1. With zero crops this is a phone member (bill
     * only, no consent record for recognition). Household fields go only with two or more faces.
     */
    fun build(form: BillingForm, crops: List<FaceCrop>, staffId: String): EnrolRequest {
        val b64 = Base64.getEncoder()
        val faces = crops.mapIndexed { i, crop ->
            val name = if (i == 0) null else form.adultNames.getOrNull(i - 1)?.trim()?.ifEmpty { null }
            val phone = if (i == 0) null else form.adultPhones.getOrNull(i - 1)?.let { Validation.toE164(it) }
            EnrolFace(cropJpegB64 = b64.encodeToString(crop.jpeg), landmarks = crop.landmarks, displayName = name, phone = phone)
        }
        val household = crops.size >= 2
        return EnrolRequest(
            staffId = staffId,
            phone = Validation.toE164(form.phone) ?: form.phone.trim(),
            displayName = form.name.trim().ifEmpty { null },
            billNo = form.billNo.trim(),
            amount = Validation.parseAmount(form.amount) ?: 0.0,
            items = form.items.trim().ifEmpty { null },
            occasion = form.occasion.trim().ifEmpty { null },
            notes = form.notes.trim().ifEmpty { null },
            householdLabel = if (household) form.householdLabel.trim() else null,
            payerIndex = if (household) form.payerIndex.coerceIn(0, crops.size - 1) else null,
            faces = faces,
        )
    }
}
