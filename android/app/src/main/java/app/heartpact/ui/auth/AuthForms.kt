package app.heartpact.ui.auth

import app.heartpact.data.Countries
import app.heartpact.data.Gender
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import java.time.LocalDate

/** What the sign-up form holds. Validation mirrors the API so most mistakes never leave the phone. */
data class SignUpForm(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val dateOfBirth: LocalDate? = null,
    val gender: Gender? = null,
    /** ISO 3166-1 alpha-2. The screen fills in the phone's best guess; it can be changed. */
    val country: String? = null,
) {
    fun errors(today: LocalDate = LocalDate.now()): Map<Field, String> = buildMap {
        if (name.isBlank()) put(Field.Name, tr("Add the name your partner knows you by."))
        else if (name.trim().length > 60) put(Field.Name, tr("Keep it under 60 characters."))
        if (!looksLikeEmail(email)) put(Field.Email, tr("Enter an email like name@example.com."))
        if (password.length < MIN_PASSWORD) put(Field.Password, trn(MIN_PASSWORD, "Use at least {count} character.", "Use at least {count} characters."))
        when {
            dateOfBirth == null -> put(Field.DateOfBirth, tr("Add your date of birth."))
            dateOfBirth.isAfter(today) || dateOfBirth.year < 1900 -> put(Field.DateOfBirth, tr("That date doesn't look right."))
        }
        if (gender == null) put(Field.Gender, tr("Choose the option that best describes you."))
        if (!Countries.isKnown(country)) put(Field.Country, tr("Choose the country you live in."))
    }

    enum class Field { Name, Email, Password, DateOfBirth, Gender, Country }

    companion object {
        const val MIN_PASSWORD = 8
    }
}

fun looksLikeEmail(value: String): Boolean {
    val v = value.trim()
    val at = v.indexOf('@')
    return at > 0 && at == v.lastIndexOf('@') && v.indexOf('.', at) > at + 1 && !v.endsWith('.') && ' ' !in v
}
