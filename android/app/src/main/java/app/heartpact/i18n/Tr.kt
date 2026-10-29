package app.heartpact.i18n

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/**
 * The app's words in the phone's language (docs/translation.md). English is written in the code and is also the key:
 * `tr("Create account")`. Each other language is one file, `assets/i18n/<language>.json`, mapping the English to
 * the translation. Anything missing falls back to English, so a half-translated language still works.
 */
object Tr {
    /** Languages with a file in assets/i18n; also listed in res/xml/locales_config.xml for Android's per-app setting. */
    val LANGUAGES = listOf("en", "fr", "pt", "es", "ar", "hi")

    @Volatile private var loaded: String? = null
    @Volatile private var words: Map<String, String> = emptyMap()
    @Volatile private var plurals: Map<String, Map<String, String>> = emptyMap()

    /** Reads the file for the current language. Cheap to call again: it only rereads when the language changed. */
    fun load(context: Context, locale: Locale = Locale.getDefault()) {
        val language = locale.language.ifEmpty { "en" }
        if (language == loaded) return
        val text = if (language == "en" || language !in LANGUAGES) null
        else runCatching { context.assets.open("i18n/$language.json").bufferedReader().use { it.readText() } }.getOrNull()
        use(language, text)
    }

    /** Installs a catalog from its JSON text (null for English); split out so tests can load one without assets. */
    fun use(language: String, json: String?) {
        val newWords = HashMap<String, String>()
        val newPlurals = HashMap<String, Map<String, String>>()
        if (json != null) {
            for ((key, value) in Json.parseToJsonElement(json).jsonObject) {
                // "@..." keys are notes for translators, not words.
                if (key.startsWith("@")) continue
                when (value) {
                    is JsonPrimitive -> if (value.isString && value.content.isNotEmpty()) newWords[key] = value.content
                    is JsonObject -> newPlurals[key] = value.mapValues { it.value.jsonPrimitive.content }
                    else -> Unit
                }
            }
        }
        words = newWords
        plurals = newPlurals
        loaded = language
    }

    internal fun word(source: String): String = words[source] ?: source

    internal fun plural(count: Long, one: String, other: String): String {
        val forms = plurals["$one|$other"]
        if (forms == null || forms.isEmpty()) return if (count == 1L) one else other
        return forms[category(loaded ?: "en", count)] ?: forms["other"] ?: other
    }

    /** CLDR plural categories for whole numbers, for the languages we ship (android.icu isn't there in unit tests). */
    internal fun category(language: String, count: Long): String {
        val n = kotlin.math.abs(count)
        return when (language) {
            // French, Portuguese (Brazil) and Hindi treat 0 like 1.
            "fr", "pt", "hi" -> if (n <= 1) "one" else "other"
            "ar" -> when {
                n == 0L -> "zero"
                n == 1L -> "one"
                n == 2L -> "two"
                n % 100 in 3..10 -> "few"
                n % 100 in 11..99 -> "many"
                else -> "other"
            }
            else -> if (n == 1L) "one" else "other"
        }
    }
}

/**
 * Translates [source], then fills `{name}` placeholders from [args]: `tr("Hi {name}", "name" to name)`.
 * [source] must be one plain string literal, so the catalog check can find it.
 */
fun tr(source: String, vararg args: Pair<String, Any?>): String = fill(Tr.word(source), args)

/** A count in words: `trn(n, "{count} day", "{count} days")`. `{count}` is filled in, as are any [args]. */
fun trn(count: Int, one: String, other: String, vararg args: Pair<String, Any?>): String = trn(count.toLong(), one, other, *args)

fun trn(count: Long, one: String, other: String, vararg args: Pair<String, Any?>): String =
    fill(Tr.plural(count, one, other), arrayOf("count" to count, *args))

private fun fill(text: String, args: Array<out Pair<String, Any?>>): String {
    if (args.isEmpty() || '{' !in text) return text
    var out = text
    for ((name, value) in args) out = out.replace("{$name}", value?.toString() ?: "")
    return out
}
