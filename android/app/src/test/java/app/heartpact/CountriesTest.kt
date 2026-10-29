package app.heartpact

import app.heartpact.data.Countries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class CountriesTest {
    @Test fun `every assigned ISO country, once`() {
        assertEquals(249, Countries.codes.size)
        assertEquals(Countries.codes.size, Countries.codes.toSet().size)
        assertTrue(Countries.codes.all { it.matches(Regex("[A-Z]{2}")) })
    }

    @Test fun `the same list the server accepts`() {
        // Gradle runs unit tests from android/app; the server's list is in the same repository.
        val server = File("../../src/Twogether.Core/Users/ContentRegions.cs")
        if (!server.exists()) return
        val block = server.readText().substringAfter("Countries = new HashSet").substringBefore("};")
        val codes = Regex("\"([A-Z]{2})\"").findAll(block).map { it.groupValues[1] }.toSet()
        assertEquals(codes, Countries.codes.toSet())
    }

    @Test fun `known codes ignore case, made-up ones don't pass`() {
        assertTrue(Countries.isKnown("ng"))
        assertFalse(Countries.isKnown("XX"))
        assertFalse(Countries.isKnown("Nigeria"))
        assertFalse(Countries.isKnown(null))
    }

    @Test fun `names and flags`() {
        assertEquals("Nigeria", Countries.name("NG", Locale.ENGLISH))
        assertEquals("🇳🇬", Countries.flag("NG"))
        assertEquals("🇮🇳", Countries.flag("in"))
    }

    @Test fun `search matches any word, the code, and ignores accents`() {
        assertTrue(Countries.matches("GB", "kingdom", Locale.ENGLISH))
        assertTrue(Countries.matches("US", "united", Locale.ENGLISH))
        assertTrue(Countries.matches("CI", "cote", Locale.ENGLISH))
        assertTrue(Countries.matches("NG", "ng", Locale.ENGLISH))
        assertTrue(Countries.matches("NG", "  ", Locale.ENGLISH))
        assertFalse(Countries.matches("NG", "ghana", Locale.ENGLISH))
        // A Nigerian with a French phone still finds home by its English name.
        assertTrue(Countries.matches("NG", "niger", Locale.FRENCH))
    }

    @Test fun `the list is in alphabetical order for the phone's language`() {
        val names = Countries.all(Locale.ENGLISH).map { it.second }
        assertEquals("Afghanistan", names.first())
        assertTrue(names.indexOf("Nigeria") < names.indexOf("Norway"))
    }
}
