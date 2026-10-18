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
    /**
     * Household only, indexed by face (selection order). The name and phone fields above belong to
     * the payer; every other adult has their own phone here, which the hub requires so each
     * adult's written notice goes to their own number (docs/API_CONTRACT.md 7.5). Names are optional.
     */
    val adultNames: List<String> = emptyList(),
    val adultPhones: List<String> = emptyList(),
)

enum class FieldError {
    PHONE_EMPTY, PHONE_INVALID, AMOUNT_EMPTY, AMOUNT_INVALID, BILL_NO, NAME_EMPTY, TOO_LONG, HOUSEHOLD_LABEL,
    ADULT_PHONE_INVALID, ADULT_PHONE_REQUIRED, ADULT_PHONE_SAME,
}

/** Per-field errors; empty means the form can be sent. */
data class FormErrors(val byField: Map<String, FieldError> = emptyMap()) {
    val ok: Boolean get() = byField.isEmpty()
    operator fun get(field: String): FieldError? = byField[field]
}

/** Builds the `POST /enrol` body from the form and the in-memory crops (docs/API_CONTRACT.md 1). */
object EnrolPayloadBuilder {
    /** The form key of face [i]'s own phone field in a household. */
    fun adultPhoneKey(i: Int) = "adultPhone$i"

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
            val payer = form.payerIndex.coerceIn(0, faceCount - 1)
            val seen = mutableSetOf<String>()
            Validation.normalisePhone(form.phone)?.let { seen += it }
            for (i in 0 until faceCount) {
                if (i == payer) continue
                val p = form.adultPhones.getOrNull(i).orEmpty()
                val national = Validation.normalisePhone(p)
                when {
                    p.isBlank() -> e[adultPhoneKey(i)] = FieldError.ADULT_PHONE_REQUIRED
                    national == null -> e[adultPhoneKey(i)] = FieldError.ADULT_PHONE_INVALID
                    !seen.add(national) -> e[adultPhoneKey(i)] = FieldError.ADULT_PHONE_SAME
                }
            }
        }
        return FormErrors(e)
    }

    /**
     * [crops] in selection order: crop 0 is adult 1. With zero crops this is a phone member (bill
     * only, no consent record for recognition). Household fields go only with two or more faces;
     * then every face carries its `phone`: the payer's is the main phone, every other adult's is
     * their own. A non-payer's name falls back to the top-level name on the hub when left empty.
     */
    fun build(form: BillingForm, crops: List<FaceCrop>, staffId: String): EnrolRequest {
        val b64 = Base64.getEncoder()
        val household = crops.size >= 2
        val payer = if (household) form.payerIndex.coerceIn(0, crops.size - 1) else 0
        val faces = crops.mapIndexed { i, crop ->
            val name = if (!household || i == payer) null else form.adultNames.getOrNull(i)?.trim()?.ifEmpty { null }
            val phone = when {
                !household -> null
                i == payer -> Validation.toE164(form.phone)
                else -> form.adultPhones.getOrNull(i)?.let { Validation.toE164(it) }
            }
            EnrolFace(cropJpegB64 = b64.encodeToString(crop.jpeg), landmarks = crop.landmarks, displayName = name, phone = phone)
        }
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
            payerIndex = if (household) payer else null,
            faces = faces,
        )
    }
}
