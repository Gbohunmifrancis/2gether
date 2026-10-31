package app.heartpact

import androidx.compose.ui.text.LinkAnnotation
import app.heartpact.ui.components.LegalPages
import app.heartpact.ui.components.linkedText
import org.junit.Assert.assertEquals
import org.junit.Test

class LegalPagesTest {
    @Test fun englishPagesSitAtTheRoot() {
        assertEquals("https://heartpact.me/privacy/", LegalPages.privacy("en"))
        assertEquals("https://heartpact.me/terms/", LegalPages.terms("en"))
    }

    @Test fun translatedPagesSitUnderTheirLanguage() {
        for (language in listOf("fr", "pt", "es", "ar", "hi")) {
            assertEquals("https://heartpact.me/$language/privacy/", LegalPages.privacy(language))
            assertEquals("https://heartpact.me/$language/terms/", LegalPages.terms(language))
        }
    }

    @Test fun otherLanguagesGetEnglish() {
        assertEquals("https://heartpact.me/privacy/", LegalPages.privacy("de"))
        assertEquals("https://heartpact.me/terms/", LegalPages.terms(""))
    }

    @Test fun taggedWordsBecomeLinks() {
        val urls = mapOf("terms" to "https://t", "privacy" to "https://p")
        val text = linkedText("Agree to the <terms>Terms</terms> and the <privacy>Privacy Policy</privacy>.", urls) {}
        assertEquals("Agree to the Terms and the Privacy Policy.", text.text)
        val links = text.getLinkAnnotations(0, text.length).map { text.text.substring(it.start, it.end) to (it.item as LinkAnnotation.Url).url }
        assertEquals(listOf("Terms" to "https://t", "Privacy Policy" to "https://p"), links)
    }

    @Test fun unknownTagsAndPlainTextStayText() {
        val text = linkedText("No <x>links</x> here", emptyMap()) {}
        assertEquals("No links here", text.text)
        assertEquals(0, text.getLinkAnnotations(0, text.length).size)
        assertEquals("Plain", linkedText("Plain", emptyMap()) {}.text)
    }
}
