package app.heartpact

import app.heartpact.data.Gender
import app.heartpact.ui.auth.SignUpForm
import app.heartpact.ui.auth.SignUpForm.Field
import app.heartpact.ui.auth.isAdult
import app.heartpact.ui.auth.looksLikeEmail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SignUpFormTest {
    private val today = LocalDate.of(2026, 10, 11)
    private val valid = SignUpForm("Maya", "maya@example.com", "longenough", LocalDate.of(1996, 5, 14), Gender.Female, "NG")

    @Test fun `a complete form has no errors`() = assertEquals(emptyMap<Field, String>(), valid.errors(today))

    @Test fun `the password needs eight characters, as the API does`() {
        assertTrue(Field.Password in valid.copy(password = "1234567").errors(today))
        assertFalse(Field.Password in valid.copy(password = "12345678").errors(today))
    }

    @Test fun `a birth date in the future or before 1900 is refused`() {
        assertTrue(Field.DateOfBirth in valid.copy(dateOfBirth = today.plusDays(1)).errors(today))
        assertTrue(Field.DateOfBirth in valid.copy(dateOfBirth = LocalDate.of(1899, 12, 31)).errors(today))
        assertTrue(Field.DateOfBirth in valid.copy(dateOfBirth = null).errors(today))
    }

    @Test fun `you need to be 18 or over, as the API does`() {
        assertFalse(Field.DateOfBirth in valid.copy(dateOfBirth = today.minusYears(18)).errors(today))
        assertTrue(Field.DateOfBirth in valid.copy(dateOfBirth = today.minusYears(18).plusDays(1)).errors(today))
        // Born on 29 February: 18 on 1 March in a year without one.
        assertFalse(isAdult(LocalDate.of(2008, 2, 29), LocalDate.of(2026, 2, 28)))
        assertTrue(isAdult(LocalDate.of(2008, 2, 29), LocalDate.of(2026, 3, 1)))
    }

    @Test fun `name and gender are required`() {
        val errors = valid.copy(name = "  ", gender = null).errors(today)
        assertTrue(Field.Name in errors && Field.Gender in errors)
    }

    @Test fun `a real country is required, as the app serves content by it`() {
        assertTrue(Field.Country in valid.copy(country = null).errors(today))
        assertTrue(Field.Country in valid.copy(country = "Nigeria").errors(today))
        assertTrue(Field.Country in valid.copy(country = "XX").errors(today))
        assertFalse(Field.Country in valid.copy(country = "IN").errors(today))
    }

    @Test fun `email shape check`() {
        listOf("maya@example.com", " maya@mail.example.org ").forEach { assertTrue(it, looksLikeEmail(it)) }
        listOf("", "maya", "maya@", "@example.com", "maya@example", "maya@@example.com", "maya@example.", "ma ya@example.com").forEach { assertFalse(it, looksLikeEmail(it)) }
    }
}
