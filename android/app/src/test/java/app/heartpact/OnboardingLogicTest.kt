package app.heartpact

import app.heartpact.data.Countries
import app.heartpact.data.Gender
import app.heartpact.data.InviteKeeping
import app.heartpact.ui.auth.PasswordStrength
import app.heartpact.ui.auth.SignUpForm
import app.heartpact.ui.auth.SignUpForm.Field
import app.heartpact.ui.pair.InviteExpiry
import app.heartpact.ui.pair.InviteInput
import app.heartpact.ui.pair.InviteLeft
import app.heartpact.ui.pair.InviteParsing
import app.heartpact.ui.pair.InviteShare
import app.heartpact.ui.pair.LinkedTimeline
import app.heartpact.ui.pair.QrMatrix
import app.heartpact.ui.pair.QrReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Signing up in two steps, the country guess, and every way a partner's invite reaches the phone. */
class OnboardingLogicTest {
    private val token = "3f9a0c2b7d41e8a6b5c90d1e2f3a4b5c6d7e8f9a0b1c2d3e"

    // Country pre-fill

    @Test fun `the country comes from the SIM, then the network, then the language setting`() {
        assertEquals("NG", Countries.pick("ng", "gh", "US"))
        assertEquals("GH", Countries.pick("", "gh", "US"))
        assertEquals("GH", Countries.pick(null, " gh ", "US"))
        assertEquals("FR", Countries.pick(null, null, "FR"))
        // No SIM, no network, and a language without a country ("en" alone, or Latin American Spanish's 419).
        assertNull(Countries.pick(null, "", ""))
        assertNull(Countries.pick("zz", null, "419"))
    }

    // Steps

    private val today = LocalDate.of(2026, 11, 1)
    private val filled = SignUpForm("Maya", "maya@example.com", "longenough", LocalDate.of(1996, 5, 14), Gender.Female, "NG")

    @Test fun `step 1 checks only the account, step 2 only about you`() {
        val blank = SignUpForm()
        assertEquals(setOf(Field.Name, Field.Email, Field.Password), blank.errors(1, today).keys)
        assertEquals(setOf(Field.DateOfBirth, Field.Gender, Field.Country), blank.errors(2, today).keys)
        assertTrue(filled.copy(dateOfBirth = null, gender = null).errors(1, today).isEmpty())
        assertTrue(filled.copy(name = "", email = "x").errors(2, today).isEmpty())
    }

    @Test fun `step 2 keeps the 18 and over rule`() {
        assertTrue(Field.DateOfBirth in filled.copy(dateOfBirth = today.minusYears(18).plusDays(1)).errors(2, today))
        assertFalse(Field.DateOfBirth in filled.copy(dateOfBirth = today.minusYears(18)).errors(2, today))
    }

    @Test fun `the password hint grows with the password and never blocks one of eight`() {
        assertEquals(PasswordStrength.TooShort, PasswordStrength.of("short"))
        assertEquals(PasswordStrength.Weak, PasswordStrength.of("password1"))
        assertEquals(PasswordStrength.Weak, PasswordStrength.of("aaaaaaaa"))
        assertEquals(PasswordStrength.Weak, PasswordStrength.of("abcdefgh"))
        assertEquals(PasswordStrength.Fair, PasswordStrength.of("maya1996"))
        assertEquals(PasswordStrength.Strong, PasswordStrength.of("Maya&Theo1996"))
        assertEquals(PasswordStrength.Strong, PasswordStrength.of("we keep our promises"))
        assertTrue(filled.copy(password = "aaaaaaaa").errors(1, today).isEmpty())
    }

    // Codes from links, QR codes and the clipboard

    @Test fun `a link gives its token, however it was written`() {
        for (text in listOf(
            "https://heartpact.me/invite/$token",
            "http://www.heartpact.me/invite/$token/",
            "HTTPS://HEARTPACT.ME/invite/$token?utm_source=whatsapp",
            "heartpact.me/invite/$token",
        )) assertEquals(text, InviteInput.Link(token), InviteParsing.parse(text))
        // Not ours, or not a token.
        assertNull(InviteParsing.parse("https://example.com/invite/$token"))
        assertNull(InviteParsing.linkToken("https://heartpact.me/invite/short"))
        assertNull(InviteParsing.linkToken("https://heartpact.me/invite/${token}zz"))
    }

    @Test fun `the whole share message pasted gives the link before the code`() {
        val message = InviteShare.message(token, "482031")
        assertEquals(InviteInput.Link(token), InviteParsing.parse(message))
        assertEquals("482031", InviteParsing.code(message))
    }

    @Test fun `a code is found when it is the only one`() {
        assertEquals(InviteInput.Code("482031"), InviteParsing.parse("482031"))
        assertEquals(InviteInput.Code("482031"), InviteParsing.parse("  482 031 "))
        assertEquals(InviteInput.Code("482031"), InviteParsing.parse("Code: 482-031"))
        assertEquals(InviteInput.Code("007310"), InviteParsing.parse("Oye, mi código es 007 310 :)"))
        // The same code twice is still one code.
        assertEquals("482031", InviteParsing.code("482031 (again: 482 031)"))
        // Two different codes, part of a longer number, or too short: nothing to offer.
        assertNull(InviteParsing.parse("482031 or 111222"))
        assertNull(InviteParsing.parse("Call me on 08034820311"))
        assertNull(InviteParsing.parse("48203"))
        assertNull(InviteParsing.parse(null))
        // A token's hex can hold six digits in a row; they are not a code.
        assertNull(InviteParsing.code("https://heartpact.me/invite/aaaa123456aaaabbbbccccdddd"))
    }

    @Test fun `an opened link's path gives the token only for the invite path`() {
        assertEquals(token, InviteParsing.tokenFromPath("heartpact.me", listOf("invite", token)))
        assertEquals(token, InviteParsing.tokenFromPath("www.heartpact.me", listOf("invite", token)))
        assertNull(InviteParsing.tokenFromPath("heartpact.me", listOf("invite")))
        assertNull(InviteParsing.tokenFromPath("heartpact.me", listOf("privacy", token)))
        assertNull(InviteParsing.tokenFromPath("evil.example", listOf("invite", token)))
        assertNull(InviteParsing.tokenFromPath("heartpact.me", listOf("invite", "NOT-HEX")))
    }

    @Test fun `the share message puts the link on a line of its own and spaces the code`() {
        val lines = InviteShare.message(token, "482031").lines()
        assertEquals(3, lines.size)
        assertTrue(lines[1].endsWith(" https://heartpact.me/invite/$token"))
        // The code stays left to right inside Arabic text: an isolate around it.
        assertTrue(lines[2].endsWith("\u2066482 031\u2069"))
        assertFalse(lines.joinToString().contains("!"))
    }

    // The countdown

    private val now = Instant.parse("2026-11-01T10:00:00Z")

    @Test fun `under an hour it counts down by the minute, rounded up`() {
        assertEquals(InviteLeft.Later, InviteLeft.of(now.plusSeconds(3_600), now))
        assertEquals(InviteLeft.Minutes(60), InviteLeft.of(now.plusSeconds(3_599), now))
        assertEquals(InviteLeft.Minutes(12), InviteLeft.of(now.plusSeconds(11 * 60 + 5), now))
        assertEquals(InviteLeft.Minutes(1), InviteLeft.of(now.plusSeconds(20), now))
        assertEquals(InviteLeft.Expired, InviteLeft.of(now, now))
        assertEquals(InviteLeft.Expired, InviteLeft.of(now.minusSeconds(1), now))
    }

    @Test fun `the line under the code says what is left`() {
        assertEquals("Works once · expires in 12 minutes", InviteExpiry.text("2026-11-01T10:12:00Z", now))
        assertEquals("Works once · expires in 1 minute", InviteExpiry.text("2026-11-01T10:00:30", now))
        assertEquals("This code has expired", InviteExpiry.text("2026-11-01T09:59:00Z", now))
        // Newer JDKs put a narrow no-break space before AM.
        assertEquals("Works once · expires Tuesday at 10:00 AM", InviteExpiry.text("2026-11-03T10:00:00Z", now, ZoneOffset.UTC).replace('\u202F', ' '))
        assertTrue(InviteExpiry.ticking("2026-11-01T10:12:00Z", now))
        assertFalse(InviteExpiry.ticking("2026-11-03T10:00:00Z", now))
        assertTrue(InviteExpiry.expired("2026-11-01T09:00:00Z", now))
    }

    @Test fun `a kept invite is reused until its last minute`() {
        val ms = now.toEpochMilli()
        assertTrue(InviteKeeping.stillGood("2026-11-01T10:05:00Z", ms))
        assertFalse(InviteKeeping.stillGood("2026-11-01T10:00:59Z", ms))
        assertFalse(InviteKeeping.stillGood("not a date", ms))
    }

    // QR

    @Test fun `the QR code made on the phone reads back as the invite link`() {
        val link = InviteParsing.link(token)
        val matrix = QrMatrix.of(link)
        val scale = 6
        val quiet = 4 * scale
        val side = matrix.size * scale + quiet * 2
        val luma = ByteArray(side * side) { 0xFF.toByte() }
        for (y in matrix.indices) for (x in matrix.indices) if (matrix[y][x]) {
            for (dy in 0 until scale) for (dx in 0 until scale) luma[(quiet + y * scale + dy) * side + quiet + x * scale + dx] = 0
        }
        assertEquals(link, QrReading.decode(luma, side, side, side))
        assertEquals(InviteInput.Link(token), InviteParsing.parse(QrReading.decode(luma, side, side, side)))
        // A blank frame has nothing in it.
        assertNull(QrReading.decode(ByteArray(64 * 64) { 0x7F }, 64, 64, 64))
    }

    @Test fun `the halves meet at the end of the walk and slow as they arrive`() {
        assertEquals(1f, LinkedTimeline.apart(0), 0f)
        assertEquals(0f, LinkedTimeline.apart(LinkedTimeline.WALK_MS), 0f)
        assertTrue(LinkedTimeline.apart(LinkedTimeline.WALK_MS / 2) < 0.5f)
        assertFalse(LinkedTimeline.joined(LinkedTimeline.WALK_MS - 1))
        assertTrue(LinkedTimeline.joined(LinkedTimeline.WALK_MS))
    }
}
