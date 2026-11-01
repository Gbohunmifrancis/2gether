package app.heartpact.data

import android.content.Context
import android.telephony.TelephonyManager
import java.text.Normalizer
import java.util.Locale

/**
 * Countries people can choose at sign-up (ISO 3166-1 alpha-2). The same list as ContentRegions on the server, which
 * maps each to the region whose content the app shows (docs/regional-images.md).
 */
object Countries {
    val codes: List<String> = """
        AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ
        CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR
        GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP
        KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT
        MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW
        SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG
        UM US UY UZ VA VC VE VG VI VN VU WF WS YE YT ZA ZM ZW
    """.trim().split(Regex("\\s+"))

    private val known = codes.toHashSet()

    fun isKnown(code: String?): Boolean = code != null && code.uppercase(Locale.ROOT) in known

    /** The country's name in the phone's language, e.g. "Nigeria", "Nigéria". */
    fun name(code: String, locale: Locale = Locale.getDefault()): String =
        Locale.Builder().setRegion(code).build().getDisplayCountry(locale).ifBlank { code }

    /** The flag emoji, drawn from the two regional-indicator letters. */
    fun flag(code: String): String = code.uppercase(Locale.ROOT).map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    /** Every country, sorted by its name in the phone's language. */
    fun all(locale: Locale = Locale.getDefault()): List<Pair<String, String>> {
        val collator = java.text.Collator.getInstance(locale)
        return codes.map { it to name(it, locale) }.sortedWith { a, b -> collator.compare(a.second, b.second) }
    }

    /** Matches the start of any word in the local or English name, or the code, ignoring case and accents. */
    fun matches(code: String, query: String, locale: Locale = Locale.getDefault()): Boolean {
        val q = fold(query.trim())
        if (q.isEmpty()) return true
        if (code.equals(q, ignoreCase = true)) return true
        return listOf(name(code, locale), name(code, Locale.ENGLISH)).any { name ->
            fold(name).split(Regex("[\\s\\-(),.']+")).any { it.startsWith(q) } || fold(name).startsWith(q)
        }
    }

    /**
     * A first guess for the picker: the SIM's country, then the mobile network's, then the phone's language
     * setting (often en-US whatever the country, so it comes last). Read on the phone only; nothing is looked up.
     */
    fun guess(context: Context): String? {
        val phone = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
        return pick(runCatching { phone?.simCountryIso }.getOrNull(), runCatching { phone?.networkCountryIso }.getOrNull(), Locale.getDefault().country)
    }

    /** The first of SIM, network and language-setting countries that is a real country ("us", "", "ZZ" and "419" aren't). */
    fun pick(sim: String?, network: String?, locale: String?): String? =
        listOf(sim, network, locale).firstNotNullOfOrNull { it?.trim()?.uppercase(Locale.ROOT)?.takeIf(::isKnown) }

    private fun fold(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
}
