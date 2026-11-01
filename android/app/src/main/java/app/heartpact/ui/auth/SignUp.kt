package app.heartpact.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.heartpact.data.Countries
import app.heartpact.data.Gender
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ConsentLine
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.components.rememberStill
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.LiveLogo
import app.heartpact.ui.graphics.LogoMood
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val GENDERS get() = listOf(Gender.Female to tr("Woman"), Gender.Male to tr("Man"), Gender.NonBinary to tr("Non-binary"), Gender.PreferNotToSay to tr("Prefer not to say"))

/**
 * Signing up in two short steps: the account (name, email, password), then about you (birthday, country, gender).
 * Back from step 2 keeps everything typed. Google skips step 1; its finish screen is step 2 alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignUpScreen(
    state: SignUpState,
    onEdit: ((SignUpForm) -> SignUpForm) -> Unit,
    onNext: () -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    onGoogle: (() -> Unit)? = null,
    googleBusy: Boolean = false,
    googleNote: String? = null,
    /** A partner's invite link is waiting: "You'll be linked with Maya". */
    invitedBy: String? = null,
) {
    val colors = Heartpact.colors
    val errors = state.fieldErrors
    val context = LocalContext.current
    val guess = remember { Countries.guess(context) }
    LaunchedEffect(guess) { if (state.form.country == null && guess != null) onEdit { it.copy(country = guess) } }
    val still = rememberStill()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(if (state.step == 1) tr("Create your account") else tr("About you"), onBack) {
            LiveLogo(30.dp, if (errors.isNotEmpty() || state.note != null) LogoMood.Worried else LogoMood.Happy, night = colors.isNight)
        }
        StepIndicator(state.step, 2, Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp))
        AnimatedContent(
            state.step, Modifier.weight(1f), label = "sign-up-step",
            transitionSpec = {
                if (still) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else {
                    val forward = targetState > initialState
                    (slideInHorizontally(tween(320)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(260))) togetherWith
                        (slideOutHorizontally(tween(320)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(200)))
                }
            },
        ) { step ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step == 1) {
                    invitedBy?.let { InvitedCard(tr("You'll be linked with {name} as soon as your account is ready.", "name" to it)) }
                    // The quickest way in first: Google fills the name and email, and only step 2 is left.
                    onGoogle?.let {
                        GoogleButton(it, Modifier.padding(top = 4.dp), GoogleAction.SignUp, busy = googleBusy)
                        googleNote?.let { note -> NoteCard(note) }
                        OrDivider(tr("or sign up with email"))
                    }
                    AccountFields(state.form, onEdit, errors, onNext)
                } else {
                    Text(tr("Three quick things and you're in."), style = Heartpact.type.bodyLarge, color = colors.ink2)
                    AboutYouFields(
                        dateOfBirth = state.form.dateOfBirth, onDateOfBirth = { date -> onEdit { it.copy(dateOfBirth = date) } },
                        country = state.form.country, onCountry = { code -> onEdit { it.copy(country = code) } },
                        gender = state.form.gender, onGender = { g -> onEdit { it.copy(gender = g) } },
                        errors = errors, suggestedCountry = guess,
                    )
                }
                state.note?.let { NoteCard(it) }
            }
        }
        // Above the keyboard when it is up, so the next step is always one tap away.
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) {
            if (state.step == 1) HeartButton(tr("Continue"), onNext)
            else HeartButton(tr("Create account"), onSubmit, busy = state.busy)
            if (!WindowInsets.isImeVisible) ConsentLine(Modifier.padding(top = 10.dp).fillMaxWidth())
        }
    }
}

/** Step 1: what the account is. Next moves down the fields; Done on the password goes on to step 2. */
@Composable
private fun AccountFields(form: SignUpForm, onEdit: ((SignUpForm) -> SignUpForm) -> Unit, errors: Map<SignUpForm.Field, String>, onNext: () -> Unit) {
    HeartField(tr("Your name"), form.name, { v -> onEdit { it.copy(name = v) } }, error = errors[SignUpForm.Field.Name],
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next), contentType = ContentType.PersonFullName)
    HeartField(tr("Email"), form.email, { v -> onEdit { it.copy(email = v.trim()) } }, error = errors[SignUpForm.Field.Email],
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
        contentType = ContentType.NewUsername + ContentType.EmailAddress)
    HeartField(tr("Password"), form.password, { v -> onEdit { it.copy(password = v) } }, password = true,
        error = errors[SignUpForm.Field.Password], hint = strengthHint(form.password) ?: tr("At least 8 characters. A short sentence is easy to remember."),
        keyboard = KeyboardOptions(imeAction = ImeAction.Done), actions = KeyboardActions(onDone = { onNext() }), contentType = ContentType.NewPassword)
    StrengthBar(form.password)
}

/** Four short bars under the password: how hard it would be to guess. Only a hint; eight characters is the rule. */
@Composable
private fun StrengthBar(password: String) {
    if (password.isEmpty()) return
    val colors = Heartpact.colors
    val strength = PasswordStrength.of(password)
    val filled = strength.ordinal + 1
    val tint = when (strength) {
        PasswordStrength.TooShort, PasswordStrength.Weak -> colors.error
        PasswordStrength.Fair -> Heartpact.actor.action.copy(alpha = 0.7f)
        PasswordStrength.Strong -> Heartpact.actor.action
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(4) { i -> Box(Modifier.weight(1f).height(4.dp).clip(Shapes.Pill).background(if (i < filled) tint else colors.rule2)) }
    }
}

/**
 * Step 2, shared with Google's finish: date of birth (18 and over), the country (the phone's guess, changeable) and
 * which describes you. [askName] adds the name first, for a Google account that came without one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutYouFields(
    dateOfBirth: LocalDate?,
    onDateOfBirth: (LocalDate) -> Unit,
    country: String?,
    onCountry: (String) -> Unit,
    gender: Gender?,
    onGender: (Gender) -> Unit,
    errors: Map<SignUpForm.Field, String>,
    suggestedCountry: String?,
    dateError: String? = null,
) {
    val colors = Heartpact.colors
    var picking by remember { mutableStateOf(false) }
    val shown = dateOfBirth?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) ?: ""
    Box {
        HeartField(tr("Date of birth"), shown, {}, error = dateError ?: errors[SignUpForm.Field.DateOfBirth], hint = tr("You need to be 18 or over"),
            trailing = { Icon(Icons.Rounded.CalendarMonth, null, tint = colors.ink2, modifier = Modifier.padding(end = 12.dp)) })
        // The whole field opens the calendar, not just its icon.
        Box(Modifier.matchParentSize().clickable(role = Role.Button) { picking = true }
            .semantics { contentDescription = if (dateOfBirth == null) tr("Date of birth, not chosen. Choose") else tr("Date of birth, {date}. Change", "date" to shown) })
    }
    CountryField(country, onCountry, error = errors[SignUpForm.Field.Country], suggested = suggestedCountry)
    Text(tr("Which describes you?"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GENDERS.forEach { (g, label) -> HeartChip(label, gender == g, { onGender(g) }) }
    }
    Text(
        errors[SignUpForm.Field.Gender] ?: tr("Choosing Woman turns on cycle tracking. It stays private until you share it."),
        style = Heartpact.type.small, color = if (errors[SignUpForm.Field.Gender] != null) colors.error else colors.ink2,
    )
    if (picking) BirthDatePicker(dateOfBirth, onPicked = { onDateOfBirth(it); picking = false }, onDismiss = { picking = false })
}

/** "Step 1 of 2" over two short bars, the done ones filled. */
@Composable
fun StepIndicator(step: Int, of: Int, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    val label = tr("Step {step} of {total}", "step" to step.toString(), "total" to of.toString())
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label }) {
        Text(label, style = Heartpact.type.small, color = colors.ink2)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(of) { i -> Box(Modifier.weight(1f).height(5.dp).clip(Shapes.Pill).background(if (i < step) Heartpact.actor.action else colors.rule2)) }
        }
    }
}

/** A partner's invite is waiting: their half already in place, ours a slot. */
@Composable
fun InvitedCard(text: String, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    StickerCard(modifier.fillMaxWidth(), lift = 4.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeartSticker(HalfState.Slot, HalfState.Stuck, 40.dp)
            Text(text, style = Heartpact.type.body, color = colors.ink, modifier = Modifier.weight(1f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthDatePicker(current: LocalDate?, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialDisplayedMonthMillis = (current ?: today.minusYears(25)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 1900..today.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) { Text(tr("Done")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel")) } },
    ) { DatePicker(state) }
}

/** A rule each side of a small "or", between Google and the email form. */
@Composable
internal fun OrDivider(text: String) {
    val colors = Heartpact.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = colors.rule2)
        Text(text, style = Heartpact.type.small, color = colors.ink3)
        HorizontalDivider(Modifier.weight(1f), color = colors.rule2)
    }
}
