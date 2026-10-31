package app.heartpact.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.core.net.toUri
import app.heartpact.i18n.tr
import app.heartpact.ui.theme.Heartpact
import java.util.Locale

/** The Privacy Policy and Terms of Use on heartpact.me, in the app's language (website/i18n, docs/website.md). */
object LegalPages {
    private const val SITE = "https://heartpact.me"

    /** The website's languages besides English; English pages sit at the root. Keep in step with website/i18n/build.py. */
    private val SITE_LANGUAGES = setOf("fr", "pt", "es", "ar", "hi")

    fun privacy(language: String = Locale.getDefault().language) = page("privacy", language)
    fun terms(language: String = Locale.getDefault().language) = page("terms", language)

    internal fun page(path: String, language: String) = if (language in SITE_LANGUAGES) "$SITE/$language/$path/" else "$SITE/$path/"
}

/** Opens [url] in the browser. Without one, a toast says where the page is instead of crashing. */
fun openInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, tr("There's no browser on this phone to open it. The page is at {url}", "url" to url), Toast.LENGTH_LONG).show()
    }
}

private val TAG = Regex("<([a-z]+)>(.*?)</\\1>")

/**
 * A translated sentence whose `<name>words</name>` parts become links to [urls] by name, so translators keep the
 * whole sentence and choose which words carry the link. Unknown tags keep their words as plain text.
 */
fun linkedText(text: String, urls: Map<String, String>, styles: TextLinkStyles? = null, onOpen: (String) -> Unit): AnnotatedString =
    buildAnnotatedString {
        var at = 0
        for (match in TAG.findAll(text)) {
            append(text, at, match.range.first)
            val (name, words) = match.destructured
            val url = urls[name]
            if (url == null) append(words)
            else withLink(LinkAnnotation.Url(url, styles) { onOpen(url) }) { append(words) }
            at = match.range.last + 1
        }
        append(text, at, text.length)
    }

/** "By continuing you agree to the Terms and the Privacy Policy.", with both opening in the browser. */
@Composable
fun ConsentLine(modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val context = LocalContext.current
    val text = tr("By continuing you agree to the <terms>Terms</terms> and the <privacy>Privacy Policy</privacy>.")
    val linkStyles = TextLinkStyles(SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline))
    val annotated = remember(text, linkStyles) {
        linkedText(text, mapOf("terms" to LegalPages.terms(), "privacy" to LegalPages.privacy()), linkStyles) { openInBrowser(context, it) }
    }
    Text(annotated, style = Heartpact.type.small, color = colors.ink2, textAlign = TextAlign.Center, modifier = modifier)
}
