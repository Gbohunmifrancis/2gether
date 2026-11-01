package app.heartpact.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heartpact.data.WeeklyRecap
import app.heartpact.data.WeeklyRecapStats
import app.heartpact.i18n.Dates
import app.heartpact.i18n.tr
import app.heartpact.i18n.trn
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import java.time.LocalDate

/** Pure: the words around a recap that the app writes itself. */
object RecapText {
    /** "19–25 Oct": the seven days the recap covers, ending on its Sunday. */
    fun range(recap: WeeklyRecap): String {
        val end = runCatching { LocalDate.parse(recap.weekEnding) }.getOrNull() ?: return ""
        val start = end.minusDays(6)
        return if (start.month == end.month) "${start.format(Dates.formatter("d"))}–${end.format(Dates.formatter("d MMM"))}"
        else "${start.format(Dates.formatter("d MMM"))} – ${end.format(Dates.formatter("d MMM"))}"
    }

    /** The week in numbers, both of you where it's about one of you; a zero is left out unless everything is. */
    fun tiles(stats: WeeklyRecapStats, partnerName: String): List<Pair<String, String>> = buildList {
        add("${stats.heartsMade}" to trn(stats.heartsMade, "heart made", "hearts made"))
        if (stats.myDaresDone + stats.partnerDaresDone > 0) {
            add("${stats.myDaresDone}" to trn(stats.myDaresDone, "dare you did", "dares you did"))
            add("${stats.partnerDaresDone}" to trn(stats.partnerDaresDone, "dare {partner} did", "dares {partner} did", "partner" to partnerName))
        }
        if (stats.myStreakDays > 0) add("${stats.myStreakDays}" to trn(stats.myStreakDays, "day in your streak", "days in your streak"))
        if (stats.partnerStreakDays > 0) add("${stats.partnerStreakDays}" to trn(stats.partnerStreakDays, "day in {partner}'s streak", "days in {partner}'s streak", "partner" to partnerName))
        if (stats.gamesPlayed > 0) add("${stats.gamesPlayed}" to trn(stats.gamesPlayed, "game played", "games played"))
        if (stats.photosAdded > 0) add("${stats.photosAdded}" to trn(stats.photosAdded, "photo added", "photos added"))
        if (stats.questionsAnswered > 0) add("${stats.questionsAnswered}" to trn(stats.questionsAnswered, "question answered", "questions answered"))
    }
}

/** At the top of Us: this week's recap, one tap from the whole of it. */
@Composable
fun WeeklyRecapCard(recap: WeeklyRecap, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    StickerCard(modifier) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HeartSticker(HalfState.Stuck, HalfState.Stuck, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(tr("Your week together · {dates}", "dates" to RecapText.range(recap)), style = Heartpact.type.labelSmall, color = colors.ink2)
                Text(recap.title, style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(top = 2.dp))
                recap.lines.firstOrNull()?.let { Text(it, style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 2.dp), maxLines = 2) }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, tr("See your week"), tint = colors.ink2)
        }
    }
}

/** The whole recap: the words written for you, the numbers they came from, and a light idea for next week. */
@Composable
fun WeeklyRecapScreen(recap: WeeklyRecap, partnerName: String, onBack: () -> Unit, onPlan: (() -> Unit)? = null) {
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(tr("Your week together"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(RecapText.range(recap), style = Heartpact.type.label, color = colors.ink2)
            Text(recap.title, style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 6.dp))
            StickerCard(Modifier.padding(top = 16.dp).fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    recap.lines.forEach { line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            HeartSticker(HalfState.Stuck, HalfState.Stuck, 18.dp, Modifier.padding(top = 2.dp))
                            Text(line, style = Heartpact.type.bodyLarge, color = colors.ink)
                        }
                    }
                }
            }
            if (recap.suggestion.isNotBlank()) {
                Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(Shapes.Well).background(actor.tint).padding(16.dp)) {
                    Text(tr("For next week"), style = Heartpact.type.labelSmall, color = actor.onSoft)
                    Text(recap.suggestion, style = Heartpact.type.body, color = colors.ink, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Text(tr("Your week in numbers"), style = Heartpact.type.titleSmall, color = colors.ink, modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
            RecapText.tiles(recap.stats, partnerName).chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (value, label) ->
                        Column(Modifier.weight(1f).clip(Shapes.Well).background(colors.sticker).padding(14.dp)) {
                            Text(value, style = Heartpact.type.headline, color = colors.ink)
                            Text(label, style = Heartpact.type.small, color = colors.ink2)
                        }
                    }
                    if (row.size == 1) Column(Modifier.weight(1f)) {}
                }
            }
            onPlan?.let { HeartButton(tr("Plan something together"), it, Modifier.padding(top = 8.dp), kind = ButtonKind.Tonal) }
        }
    }
}
