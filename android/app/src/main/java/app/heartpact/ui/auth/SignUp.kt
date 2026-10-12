package app.heartpact.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.heartpact.data.Gender
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.HeartChip
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.TopBar
import app.heartpact.ui.theme.Heartpact
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val GENDERS = listOf(Gender.Female to "Woman", Gender.Male to "Man", Gender.NonBinary to "Non-binary", Gender.PreferNotToSay to "Prefer not to say")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SignUpScreen(state: SignUpState, onEdit: ((SignUpForm) -> SignUpForm) -> Unit, onSubmit: () -> Unit, onBack: () -> Unit) {
    val colors = Heartpact.colors
    val form = state.form
    val errors = state.fieldErrors
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar("Create your account", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeartField("Your name", form.name, { v -> onEdit { it.copy(name = v) } }, error = errors[SignUpForm.Field.Name],
                keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next))
            HeartField("Email", form.email, { v -> onEdit { it.copy(email = v) } }, error = errors[SignUpForm.Field.Email],
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
            HeartField("Password", form.password, { v -> onEdit { it.copy(password = v) } }, password = true,
                error = errors[SignUpForm.Field.Password], hint = "At least ${SignUpForm.MIN_PASSWORD} characters",
                keyboard = KeyboardOptions(imeAction = ImeAction.Done), actions = KeyboardActions(onDone = { picking = form.dateOfBirth == null }))
            HeartField(
                "Date of birth",
                form.dateOfBirth?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)) ?: "",
                {},
                error = errors[SignUpForm.Field.DateOfBirth],
                trailing = { IconButton(onClick = { picking = true }) { Icon(Icons.Rounded.CalendarMonth, "Choose date of birth", tint = colors.ink2) } },
            )
            Text("Which describes you?", style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GENDERS.forEach { (gender, label) -> HeartChip(label, form.gender == gender, { onEdit { it.copy(gender = gender) } }) }
            }
            Text(
                errors[SignUpForm.Field.Gender] ?: "Choosing Woman turns on cycle tracking. It stays private until you share it.",
                style = Heartpact.type.small, color = if (errors[SignUpForm.Field.Gender] != null) colors.error else colors.ink2,
            )
            state.note?.let { NoteCard(it) }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) {
            HeartButton("Create account", onSubmit, busy = state.busy)
            Text("By continuing you agree to the Terms and the Privacy Policy.", style = Heartpact.type.small, color = colors.ink2,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp).fillMaxWidth())
        }
    }
    if (picking) BirthDatePicker(form.dateOfBirth, onPicked = { date -> onEdit { it.copy(dateOfBirth = date) }; picking = false }, onDismiss = { picking = false })
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
            TextButton(onClick = { state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss() }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state) }
}
