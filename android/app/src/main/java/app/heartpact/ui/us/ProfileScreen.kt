package app.heartpact.ui.us

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.HeartBroken
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.heartpact.data.Countries
import app.heartpact.data.Gender
import app.heartpact.i18n.tr
import app.heartpact.ui.auth.CountrySheet
import app.heartpact.ui.auth.GENDERS
import app.heartpact.ui.components.Avatar
import app.heartpact.ui.components.AvatarPrep
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.Face
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.SectionHeader
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.graphics.Icon3D
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.ThemeMode

/**
 * Your profile: the things that are only about you, all in one place and all yours to change: your picture (your
 * partner sees it beside theirs), your name, country, gender, how the app looks, your notifications, and the ways out.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    ui: UsUi,
    me: Face,
    country: String?,
    gender: Gender,
    partnerName: String,
    themeMode: ThemeMode,
    onBack: () -> Unit,
    onPicture: (ByteArray?) -> Unit,
    onName: (String) -> Unit,
    onCountry: (String) -> Unit,
    onGender: (Gender) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onNotifications: () -> Unit,
    onSignOut: () -> Unit,
    onEndPact: () -> Unit,
    onDeleteAccount: (String) -> Unit,
    onClearDeleteError: () -> Unit,
) {
    val colors = Heartpact.colors
    val context = LocalContext.current
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickingCountry by remember { mutableStateOf(false) }
    var pickingGender by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { AvatarPrep.fromUri(context, it)?.let(onPicture) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(tr("Your profile"), onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(me, 132.dp)
                Text(me.name, style = Heartpact.type.headline, color = colors.ink, modifier = Modifier.padding(top = 14.dp))
                Text(tr("{partner} sees this next to theirs, on Us and on the map.", "partner" to partnerName), style = Heartpact.type.small, color = colors.ink2,
                    modifier = Modifier.padding(top = 4.dp), textAlign = TextAlign.Center)
                FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeartButton(if (me.version > 0) tr("Change photo") else tr("Add a photo"), { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, fillWidth = false, height = 44.dp, busy = ui.busy)
                    if (me.version > 0) HeartButton(tr("Remove"), { onPicture(null) }, kind = ButtonKind.Text, fillWidth = false, height = 44.dp)
                }
            }
            ui.note?.let { NoteCard(it, Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) }

            SectionHeader(tr("About you"))
            StickerCard(Modifier.padding(horizontal = 12.dp)) {
                CardRow(tr("Name"), subtitle = me.name, icon = Icons.Outlined.Badge, onClick = { editingName = true }) { Chevron() }
                CardRow(tr("Country"), subtitle = country?.let { "${Countries.flag(it)}  ${Countries.name(it)}" } ?: tr("Not set yet. Tell us, for photos and food tips from home"),
                    icon = Icons.Outlined.Public, divider = true, onClick = { pickingCountry = true }) { Chevron() }
                CardRow(tr("Gender"), subtitle = GENDERS.firstOrNull { it.first == gender }?.second ?: tr("Not set"),
                    icon = Icons.Outlined.Person, divider = true, onClick = { pickingGender = true }) { Chevron() }
            }

            SectionHeader(tr("Appearance"))
            StickerCard(Modifier.padding(horizontal = 12.dp)) {
                FlowRow(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.System to tr("Match phone"), ThemeMode.Light to tr("Light"), ThemeMode.Dark to tr("Dark"))
                        .forEach { (option, label) -> HeartChip(label, themeMode == option, { onThemeMode(option) }) }
                }
            }

            SectionHeader(tr("Notifications"))
            StickerCard(Modifier.padding(horizontal = 12.dp)) {
                CardRow(tr("Notifications"), subtitle = UsText.notifications(ui.preferences), icon3d = Icon3D.Bell, onClick = onNotifications) { Chevron() }
            }

            SectionHeader(tr("Account"))
            StickerCard(Modifier.padding(horizontal = 12.dp)) {
                CardRow(tr("Sign out"), subtitle = tr("Your pact and photos stay; sign back in any time"), icon = Icons.AutoMirrored.Rounded.Logout, onClick = onSignOut)
                CardRow(tr("End our pact"), subtitle = tr("Unlinks you both and stops every dare"), icon = Icons.Outlined.HeartBroken, divider = true, onClick = { confirmEnd = true })
                CardRow(tr("Delete my account"), subtitle = tr("Everything of yours, for good"), icon = Icons.Outlined.PersonRemove, divider = true, onClick = { confirmDelete = true })
            }
            LegalCard(Modifier.padding(horizontal = 12.dp).padding(top = 14.dp))
        }
    }

    if (editingName) {
        var name by remember { mutableStateOf(me.name) }
        AlertDialog(
            onDismissRequest = { editingName = false },
            title = { Text(tr("Your name")) },
            text = { HeartField(tr("Name"), name, { name = it.take(40) }, keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words)) },
            confirmButton = { TextButton(onClick = { onName(name); editingName = false }, enabled = name.isNotBlank()) { Text(tr("Save")) } },
            dismissButton = { TextButton(onClick = { editingName = false }) { Text(tr("Cancel")) } },
        )
    }
    if (pickingCountry) CountrySheet(country, remember { Countries.guess(context) }, onPick = { onCountry(it); pickingCountry = false }, onDismiss = { pickingCountry = false })
    if (pickingGender) AlertDialog(
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
