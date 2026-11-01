package app.heartpact.ui.auth

import app.heartpact.data.Countries
import app.heartpact.data.Gender
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import java.time.LocalDate
import java.time.Period

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
            !isAdult(dateOfBirth, today) -> put(Field.DateOfBirth, TOO_YOUNG)
        }
        if (gender == null) put(Field.Gender, tr("Choose the option that best describes you."))
        if (!Countries.isKnown(country)) put(Field.Country, tr("Choose the country you live in."))
    }

    /** The errors of one step only: step 1 is the account, step 2 is about you. */
    fun errors(step: Int, today: LocalDate = LocalDate.now()): Map<Field, String> = errors(today).filterKeys { it.step == step }

    /** Fields in the order they appear, each on its step. */
    enum class Field(val step: Int) { Name(1), Email(1), Password(1), DateOfBirth(2), Country(2), Gender(2) }

    companion object {
        const val MIN_PASSWORD = 8
    }
}

/** Heartpact is for adults, 18 and over (the server's AgeRules). Born on 29 February: a year older on 1 March. */
const val MINIMUM_AGE = 18

fun isAdult(birth: LocalDate, today: LocalDate = LocalDate.now()): Boolean = Period.between(birth, today).years >= MINIMUM_AGE

val TOO_YOUNG: String get() = tr("Heartpact is for adults. You need to be 18 or over to sign up.")

fun looksLikeEmail(value: String): Boolean {
    val v = value.trim()
    val at = v.indexOf('@')
    return at > 0 && at == v.lastIndexOf('@') && v.indexOf('.', at) > at + 1 && !v.endsWith('.') && ' ' !in v
}

/** A light hint while choosing a password; the only rule is the length the server needs. */
enum class PasswordStrength { TooShort, Weak, Fair, Strong;
    companion object {
        fun of(password: String): PasswordStrength {
            if (password.length < SignUpForm.MIN_PASSWORD) return TooShort
            val kinds = listOf(password.any(Char::isLowerCase), password.any(Char::isUpperCase), password.any(Char::isDigit),
                password.any { !it.isLetterOrDigit() }).count { it }
            val repeats = password.toSet().size <= 3
            return when {
                repeats || COMMON.any { password.lowercase().contains(it) } && password.length < 14 -> Weak
                password.length >= 14 || (password.length >= 10 && kinds >= 3) -> Strong
                kinds >= 2 -> Fair
                else -> Weak
            }
        }

        private val COMMON = listOf("password", "12345678", "qwerty", "iloveyou", "heartpact", "abcdefg")
    }
}

/** The words under the password field for [strength]; null while it is empty. */
fun strengthHint(password: String): String? = if (password.isEmpty()) null else when (PasswordStrength.of(password)) {
    PasswordStrength.TooShort -> trn(SignUpForm.MIN_PASSWORD, "At least {count} character", "At least {count} characters")
    PasswordStrength.Weak -> tr("Easy to guess. A few more words would help.")
    PasswordStrength.Fair -> tr("Good. Longer is even better.")
    PasswordStrength.Strong -> tr("Strong password")
}
