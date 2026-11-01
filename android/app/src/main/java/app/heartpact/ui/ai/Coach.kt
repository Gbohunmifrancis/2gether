package app.heartpact.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heartpact.data.ApiResult
import app.heartpact.data.CoachNote
import app.heartpact.i18n.tr
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import kotlinx.coroutines.delay

/** The coach's note on a finished dare, as a screen shows it. */
sealed interface CoachUi {
    /** Off, not written, or nothing to say: nothing shows, not even a gap. */
    data object Hidden : CoachUi
    data object Writing : CoachUi
    data class Ready(val text: String) : CoachUi
}

/** Pure: asks for the note until it's written, a few times while someone else's request is writing it. */
object CoachLoader {
    const val TRIES = 6
    const val WAIT_MS = 2_500L

    suspend fun load(fetch: suspend () -> ApiResult<CoachNote>, show: (CoachUi) -> Unit, wait: suspend (Long) -> Unit = { delay(it) }) {
        show(CoachUi.Writing)
        repeat(TRIES) { attempt ->
            when (val result = fetch()) {
                is ApiResult.Ok -> when (result.value.state) {
                    "ready" -> {
                        val text = result.value.text?.trim().orEmpty()
                        show(if (text.isEmpty()) CoachUi.Hidden else CoachUi.Ready(text))
                        return
                    }
                    "pending" -> if (attempt < TRIES - 1) wait(WAIT_MS)
                    else -> { show(CoachUi.Hidden); return }
                }
                // Off (404), offline, or an older server: the screen simply has no note.
                else -> { show(CoachUi.Hidden); return }
            }
        }
        show(CoachUi.Hidden)
    }
}

/** A well under the result: the coach's words, or soft lines while they're being written. */
@Composable
fun CoachNoteCard(coach: CoachUi, heading: String, modifier: Modifier = Modifier) {
    if (coach == CoachUi.Hidden) return
    val colors = Heartpact.colors
    val actor = Heartpact.actor
    val writing = tr("Your coach is writing a note")
    Column(
        modifier.fillMaxWidth().clip(Shapes.Well).background(actor.tint).padding(14.dp)
            .then(if (coach == CoachUi.Writing) Modifier.semantics { contentDescription = writing } else Modifier),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.SportsScore, null, Modifier.size(18.dp), tint = actor.onSoft)
            Text(heading, style = Heartpact.type.labelSmall, color = actor.onSoft)
        }
        when (coach) {
            is CoachUi.Ready -> Text(coach.text, style = Heartpact.type.body, color = colors.ink)
            else -> ShimmerLines(3)
        }
    }
}
