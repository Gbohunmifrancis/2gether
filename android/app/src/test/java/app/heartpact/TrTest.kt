package app.heartpact

import app.heartpact.i18n.Tr
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TrTest {
    @After fun english() = Tr.use("en", null)

    @Test fun `english is the code's own text, with placeholders filled`() {
        Tr.use("en", null)
        assertEquals("Hi Maya", tr("Hi {name}", "name" to "Maya"))
        assertEquals("1 day", trn(1, "{count} day", "{count} days"))
        assertEquals("0 days", trn(0, "{count} day", "{count} days"))
    }

    @Test fun `a catalog translates words and counts, and falls back to english`() {
        Tr.use("fr", """{"@note": "x", "Hi {name}": "Salut {name}", "{count} day|{count} days": {"one": "{count} jour", "other": "{count} jours"}}""")
        assertEquals("Salut Maya", tr("Hi {name}", "name" to "Maya"))
        assertEquals("0 jour", trn(0, "{count} day", "{count} days"))
        assertEquals("3 jours", trn(3, "{count} day", "{count} days"))
        assertEquals("Not in the file", tr("Not in the file"))
    }

    @Test fun `arabic has six counting forms`() {
        assertEquals(listOf("zero", "one", "two", "few", "many", "other"), listOf(0L, 1, 2, 5, 11, 100).map { Tr.category("ar", it) })
        assertEquals(listOf("one", "one", "other"), listOf(0L, 1, 2).map { Tr.category("hi", it) })
        assertEquals(listOf("other", "one", "other"), listOf(0L, 1, 2).map { Tr.category("es", it) })
    }

    @Test fun `every shipped catalog parses`() {
        File("src/main/assets/i18n").listFiles { f -> f.extension == "json" }.orEmpty().forEach { Tr.use(it.nameWithoutExtension, it.readText()) }
    }
}
