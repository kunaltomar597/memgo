package com.remu.app.core.format

/** Field checks for the billing form and staff PINs. The hub validates again and is the authority. */
object Validation {
    enum class PhoneError { EMPTY, INVALID }
    enum class AmountError { EMPTY, INVALID }

    private val indianMobile = Regex("^[6-9][0-9]{9}$")
    private val billNo = Regex("^[A-Za-z0-9][A-Za-z0-9/_.-]{0,39}$")

    const val MAX_ITEMS = 200
    const val MAX_OCCASION = 100
    const val MAX_NOTES = 500
    const val MAX_NAME = 80
    const val MAX_LABEL = 60

    /** A 10-digit Indian mobile, optionally with +91, 91 or 0 in front; spaces and dashes ignored. */
    fun normalisePhone(input: String): String? {
        val digits = input.filter { it.isDigit() }
        val national = when {
            input.trim().startsWith("+91") && digits.length == 12 -> digits.drop(2)
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
        if (input.any { !(it.isDigit() || it == ' ' || it == '-' || it == '+') }) return null
        return if (indianMobile.matches(national)) national else null
    }

    /** E.164 for the wire (docs/API_CONTRACT.md conventions): "+919876543210". */
    fun toE164(input: String): String? = normalisePhone(input)?.let { "+91$it" }

    fun phoneError(input: String): PhoneError? = when {
        input.isBlank() -> PhoneError.EMPTY
        normalisePhone(input) == null -> PhoneError.INVALID
        else -> null
    }

    /** Rupees, at most two decimals, commas allowed; zero is a valid bill. */
    fun parseAmount(input: String): Double? {
        val cleaned = input.replace(",", "").replace("₹", "").trim()
        if (!Regex("^[0-9]{1,9}(\\.[0-9]{1,2})?$").matches(cleaned)) return null
        return cleaned.toDouble()
    }

    fun amountError(input: String): AmountError? = when {
        input.isBlank() -> AmountError.EMPTY
        parseAmount(input) == null -> AmountError.INVALID
        else -> null
    }

    fun isValidBillNo(input: String): Boolean = billNo.matches(input.trim())

    /** Staff PIN: 4 to 6 digits (docs/API_CONTRACT.md section 1). */
    fun isValidPin(pin: String): Boolean = Regex("^[0-9]{4,6}$").matches(pin)

    /** "Rajesh Sharma" -> "Sharma family"; the template comes from the current language. */
    fun defaultHouseholdLabel(displayName: String, template: String = "%1\$s family"): String {
        val surname = displayName.trim().split(Regex("\\s+")).lastOrNull().orEmpty()
        return if (surname.isEmpty()) "" else String.format(template, surname)
    }
}
