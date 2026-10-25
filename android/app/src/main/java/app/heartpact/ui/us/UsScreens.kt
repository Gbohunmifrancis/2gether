package app.heartpact.ui.us

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.Countries
import app.heartpact.data.DailyQuestion
import app.heartpact.data.Gender
import app.heartpact.data.NotificationPreferences
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import app.heartpact.ui.auth.CountrySheet
import app.heartpact.ui.auth.GENDERS
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.AdaptivePage
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.SectionHeader
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.LocalActor
import app.heartpact.ui.theme.Side
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
private fun Heading(text: String, modifier: Modifier = Modifier) =
    Text(text, style = Heartpact.type.titleSmall, color = Heartpact.colors.ink, modifier = modifier.padding(top = 20.dp, bottom = 10.dp))

@Composable
fun Chevron() = Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Heartpact.colors.ink2)

/** Us (mockup 12): the two of you, the daily question, notifications, and the account. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsTab(
    ui: UsUi,
    myName: String,
    partnerName: String,
    togetherSince: String?,
    onQuestion: () -> Unit,
    onNotifications: () -> Unit,
    onSignOut: () -> Unit,
    onEndPact: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    onClearDeleteError: () -> Unit,
    extraRows: @Composable () -> Unit = {},
    location: @Composable () -> Unit = {},
    /** ISO code, or null for accounts made before the app asked. The row shows only with [onCountry]. */
    country: String? = null,
    onCountry: ((String) -> Unit)? = null,
    /** The row shows only with [onGender]. */
    gender: Gender? = null,
    onGender: ((Gender) -> Unit)? = null,
) {
    val colors = Heartpact.colors
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingCountry by remember { mutableStateOf(false) }
    var pickingGender by remember { mutableStateOf(false) }
    // With room, the two of you and the question sit beside the account settings.
    AdaptivePage(header = {
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("Us"), style = Heartpact.type.headline, color = colors.ink)
                Text(listOfNotNull(tr("{me} and {partner}", "me" to myName, "partner" to partnerName), togetherSince).joinToString(" · "), style = Heartpact.type.body.copy(fontSize = 13.5.sp), color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
            }
            Character(Who.Both, Pose.Hug, 96.dp)
        }
    }, second = {
        SectionHeader(tr("Account"))
        StickerCard(Modifier.padding(horizontal = 12.dp)) {
            if (onCountry != null) {
                CardRow(tr("Country"), subtitle = country?.let { "${Countries.flag(it)}  ${Countries.name(it)}" } ?: tr("Not set yet. Tell us, for photos and food tips from home"),
                    icon = Icons.Outlined.Public, onClick = { pickingCountry = true }) { Chevron() }
            }
            if (onGender != null) {
                CardRow(tr("Gender"), subtitle = GENDERS.firstOrNull { it.first == gender }?.second ?: tr("Not set"),
                    icon = Icons.Outlined.Person, divider = onCountry != null, onClick = { pickingGender = true }) { Chevron() }
            }
            CardRow(tr("Sign out"), divider = onCountry != null || onGender != null, subtitle = tr("Your pact and photos stay; sign back in any time"), icon = Icons.AutoMirrored.Rounded.Logout, onClick = onSignOut)
            CardRow(tr("End our pact"), subtitle = tr("Unlinks you both and stops every dare"), icon = Icons.Outlined.HeartBroken, divider = true, onClick = { confirmEnd = true })
            CardRow(tr("Delete my account"), subtitle = tr("Everything of yours, for good"), icon = Icons.Outlined.PersonRemove, divider = true, onClick = { confirmDelete = true })
        }
    }) {
        ui.note?.let { NoteCard(it, Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) }
        location()
        StickerCard(Modifier.padding(horizontal = 12.dp)) {
            CardRow(tr("Today's question"), subtitle = UsText.questionStatus(ui.question, partnerName), icon = Icons.Outlined.Forum, onClick = onQuestion) { Chevron() }
            extraRows()
        }
        StickerCard(Modifier.padding(horizontal = 12.dp).padding(top = 14.dp)) {
            CardRow(tr("Notifications"), subtitle = UsText.notifications(ui.preferences), icon = Icons.Outlined.NotificationsNone, onClick = onNotifications) { Chevron() }
        }
    }
    if (pickingCountry && onCountry != null) {
        val context = LocalContext.current
        CountrySheet(country, remember { Countries.guess(context) }, onPick = { onCountry(it); pickingCountry = false }, onDismiss = { pickingCountry = false })
    }
    if (pickingGender && onGender != null) AlertDialog(
        onDismissRequest = { pickingGender = false },
        title = { Text(tr("Which describes you?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GENDERS.forEach { (option, label) -> HeartChip(label, gender == option, { onGender(option); pickingGender = false }) }
                }
                Text(tr("Choosing Woman turns on cycle tracking. It stays private until you share it."), style = Heartpact.type.small, color = colors.ink2)
            }
        },
        confirmButton = { TextButton(onClick = { pickingGender = false }) { Text(tr("Close")) } },
    )
    if (confirmEnd) AlertDialog(
        onDismissRequest = { confirmEnd = false },
        title = { Text(tr("End your pact with {partner}?", "partner" to partnerName)) },
        text = { Text(tr("You'll both be unlinked at once. Dares stop ringing, and {partner} is told. Shared photos stay downloadable for 30 days, then the album is deleted.", "partner" to partnerName)) },
        confirmButton = { TextButton(onClick = { confirmEnd = false; onEndPact() }) { Text(tr("End it")) } },
        dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text(tr("Keep it")) } },
    )
    if (confirmDelete) {
        var password by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { onClearDeleteError() }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete your account?")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr("Your account, your half of every pact, your food log and your photos are deleted. {partner} is unlinked and told. This can't be undone.", "partner" to partnerName))
                    HeartField(tr("Your password"), password, { password = it }, password = true, error = ui.deleteError)
                }
            },
            confirmButton = { TextButton(onClick = { onDeleteAccount(password) }, enabled = password.isNotEmpty() && !ui.busy) { Text(tr("Delete for good")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel")) } },
        )
    }
}

/** Today's question (mockup 16): answer to unlock your partner's. */
@Composable
fun QuestionScreen(ui: QuestionUi, partnerName: String, onDraft: (String) -> Unit, onSend: () -> Unit, onEdit: () -> Unit, onBack: () -> Unit, today: LocalDate = LocalDate.now()) {
    val colors = Heartpact.colors
    val q = ui.today
    val mine = LocalActor.current
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(tr("Today's question"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            if (q == null) {
                ui.note?.let { NoteCard(it) } ?: Text(tr("Finding today's question…"), style = Heartpact.type.body, color = colors.ink2)
                return@Column
            }
            Text(q.text, style = Heartpact.type.headline.copy(fontSize = 30.sp, lineHeight = 34.sp), color = colors.ink, modifier = Modifier.padding(top = 6.dp))
            Text(UsText.dayLine(q), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(top = 10.dp))
            StatusCard(q, partnerName, mine)
            val answered = q.myAnswer != null && !ui.editing
            if (answered) {
                AnswerCard(tr("You"), q.myAnswer!!.body, mine)
                q.partnerAnswer?.let { AnswerCard(partnerName, it.body, if (mine == Side.Left) Side.Right else Side.Left) }
                HeartButton(tr("Change my answer"), onEdit, Modifier.padding(top = 4.dp), kind = ButtonKind.Text, fillWidth = false, height = 44.dp)
            } else {
                HeartField(tr("Your answer"), ui.draft, onDraft, Modifier.padding(top = 16.dp), minLines = 5, maxLength = 1000,
                    keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
                ui.note?.let { NoteCard(it, Modifier.padding(top = 10.dp)) }
                HeartButton(if (q.myAnswer != null) tr("Save my answer") else tr("Send my answer"), onSend, Modifier.padding(top = 14.dp), enabled = ui.draft.isNotBlank(), busy = ui.busy)
            }
            if (ui.earlier.isNotEmpty()) {
                Heading(tr("Earlier"))
                ui.earlier.take(14).forEach { EarlierRow(it, partnerName, today, mine) }
            }
        }
    }
}

@Composable
private fun StatusCard(q: DailyQuestion, partner: String, mine: Side) {
    val theirs = if (q.partnerAnswered) HalfState.Stuck else HalfState.Slot
    val my = if (q.myAnswer != null) HalfState.Stuck else HalfState.Slot
    val text = when {
        q.myAnswer == null && q.partnerAnswered -> {
            val at = q.partnerAnswer?.answeredAtUtc
            if (at != null) tr("{partner} answered at {time}. Send yours and you'll both see each other's.", "partner" to partner,
                "time" to utc(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)))
            else tr("{partner} answered. Send yours and you'll both see each other's.", "partner" to partner)
        }
        q.myAnswer == null -> tr("Neither of you has answered yet. Whoever goes first sees the other's when they answer too.")
        q.partnerAnswer != null -> tr("You've both answered.")
        else -> tr("Sent. You'll see {partner}'s when they answer.", "partner" to partner)
    }
    StickerCard(Modifier.padding(top = 18.dp).fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (mine == Side.Left) HeartSticker(my, theirs, 40.dp) else HeartSticker(theirs, my, 40.dp)
            Text(text, style = Heartpact.type.body, color = Heartpact.colors.ink)
        }
    }
}

@Composable
private fun AnswerCard(who: String, body: String, side: Side) {
    StickerCard(Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (side == Side.Left) HeartSticker(HalfState.Stuck, HalfState.Empty, 18.dp) else HeartSticker(HalfState.Empty, HalfState.Stuck, 18.dp)
                Text(who, style = Heartpact.type.label, color = Heartpact.colors.ink)
            }
            Text(body, style = Heartpact.type.bodyLarge, color = Heartpact.colors.ink, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun EarlierRow(q: DailyQuestion, partner: String, today: LocalDate, mine: Side) {
    var open by remember { mutableStateOf(false) }
    val my = if (q.myAnswer != null) HalfState.Stuck else HalfState.Slot
    val theirs = if (q.partnerAnswered) HalfState.Stuck else HalfState.Slot
    StickerCard(Modifier.padding(bottom = 10.dp).fillMaxWidth().clickable { open = !open }) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (mine == Side.Left) HeartSticker(my, theirs, 30.dp) else HeartSticker(theirs, my, 30.dp)
            Column(Modifier.weight(1f)) {
                Text(q.text, style = Heartpact.type.label, color = Heartpact.colors.ink)
                Text(UsText.earlierLine(q, today, partner), style = Heartpact.type.small, color = Heartpact.colors.ink2)
            }
        }
        if (open) Column(Modifier.padding(start = 58.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            q.myAnswer?.let { Text(tr("You: {answer}", "answer" to it.body), style = Heartpact.type.body, color = Heartpact.colors.ink) }
            q.partnerAnswer?.let { Text("$partner: ${it.body}", style = Heartpact.type.body, color = Heartpact.colors.ink) }
            if (q.myAnswer == null && q.partnerAnswered) Text(tr("{partner}'s answer stays hidden: you didn't answer that day.", "partner" to partner), style = Heartpact.type.small, color = Heartpact.colors.ink2)
        }
    }
}

/** What reaches the phone, and quiet hours. The dare alarm and couple changes always come through. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(prefs: NotificationPreferences?, note: String?, onChange: ((NotificationPreferences) -> NotificationPreferences) -> Unit, onBack: () -> Unit) {
    val colors = Heartpact.colors
    var picking by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(tr("Notifications"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 24.dp)) {
            note?.let { NoteCard(it, Modifier.padding(bottom = 12.dp)) }
            if (prefs == null) return@Column
            StickerCard {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.NotificationsNone, null, tint = colors.ink2)
                    Text(tr("A dare's alarm always rings, and you'll always hear if your pact changes. You can still silence an alarm on your phone."), style = Heartpact.type.body, color = colors.ink)
                }
            }
            Heading(tr("Quiet hours"), Modifier.padding(horizontal = 8.dp))
            StickerCard {
                Toggle(tr("Quiet hours"), if (prefs.quietNow) tr("On now: notifications arrive silently") else tr("Notifications arrive silently, without sound"), prefs.quietHoursEnabled) { v -> onChange { it.copy(quietHoursEnabled = v) } }
                if (prefs.quietHoursEnabled) {
                    CardRow(tr("From"), subtitle = prefs.quietStart, divider = true, onClick = { picking = "start" }) { Chevron() }
                    CardRow(tr("Until"), subtitle = prefs.quietEnd, divider = true, onClick = { picking = "end" }) { Chevron() }
                }
            }
            Heading(tr("What reaches your phone"), Modifier.padding(horizontal = 8.dp))
            StickerCard {
                Toggle(tr("Dares"), tr("Done, missed, called off or changed"), prefs.exercise) { v -> onChange { it.copy(exercise = v) } }
                Toggle(tr("Today's question"), tr("When your partner answers"), prefs.dailyQuestions, divider = true) { v -> onChange { it.copy(dailyQuestions = v) } }
                Toggle(tr("Album"), tr("Photos added, and a daily nudge when yours are waiting"), prefs.album, divider = true) { v -> onChange { it.copy(album = v) } }
                Toggle(tr("Messages"), null, prefs.messages, divider = true) { v -> onChange { it.copy(messages = v) } }
                Toggle(tr("Games"), null, prefs.games, divider = true) { v -> onChange { it.copy(games = v) } }
                Toggle(tr("Cycle"), null, prefs.cycle, divider = true) { v -> onChange { it.copy(cycle = v) } }
                Toggle(tr("Pregnancy"), null, prefs.pregnancy, divider = true) { v -> onChange { it.copy(pregnancy = v) } }
            }
        }
    }
    picking?.let { which ->
        val current = LocalTime.parse(if (which == "start") prefs!!.quietStart else prefs!!.quietEnd)
        val state = rememberTimePickerState(current.hour, current.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    val value = "%02d:%02d".format(state.hour, state.minute)
                    onChange { if (which == "start") it.copy(quietStart = value) else it.copy(quietEnd = value) }
                    picking = null
                }) { Text(tr("Set")) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text(tr("Cancel")) } },
        )
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, divider: Boolean = false, onChange: (Boolean) -> Unit) {
    CardRow(title, subtitle = subtitle, divider = divider, onClick = { onChange(!checked) }) {
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Heartpact.actor.action))
    }
}
