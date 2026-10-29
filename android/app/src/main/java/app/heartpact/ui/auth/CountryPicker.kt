package app.heartpact.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.heartpact.data.Countries
import app.heartpact.i18n.tr
import app.heartpact.ui.components.HeartField
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes

internal val COUNTRY_HINT get() = tr("So the photos and food tips you see feel like home.")

/** Looks like the other fields; tapping it opens the list. */
@Composable
fun CountryField(code: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier, error: String? = null, suggested: String? = null) {
    var open by remember { mutableStateOf(false) }
    val shown = code?.let { "${Countries.flag(it)}  ${Countries.name(it)}" } ?: ""
    Box(modifier) {
        HeartField(tr("Country"), shown, {}, error = error, hint = if (error == null) COUNTRY_HINT else null,
            trailing = { Icon(Icons.Rounded.ExpandMore, null, tint = Heartpact.colors.ink2, modifier = Modifier.padding(end = 12.dp)) })
        Box(
            Modifier.matchParentSize().clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = if (code == null) tr("Country, not chosen. Choose") else tr("Country, {country}. Change", "country" to Countries.name(code)) },
        )
    }
    if (open) CountrySheet(code, suggested ?: code, onPick = { onPick(it); open = false }, onDismiss = { open = false })
}

/** Every country, searchable, with the phone's best guess at the top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountrySheet(current: String?, suggested: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Heartpact.colors.sticker, shape = Shapes.Sheet) {
        CountryList(current, suggested, onPick, Modifier.fillMaxHeight(0.85f).imePadding())
    }
}

/** The sheet's contents: a search box over every country. */
@Composable
fun CountryList(current: String?, suggested: String?, onPick: (String) -> Unit, modifier: Modifier = Modifier, initialQuery: String = "") {
    val colors = Heartpact.colors
    var query by remember { mutableStateOf(initialQuery) }
    val all = remember { Countries.all() }
    val shown = remember(query) { all.filter { (code, _) -> Countries.matches(code, query) } }
    val top = suggested?.takeIf { query.isBlank() && Countries.isKnown(it) }
    Column(modifier) {
        Text(tr("Where do you live?"), style = Heartpact.type.title, color = colors.ink, modifier = Modifier.padding(horizontal = 20.dp))
        HeartField(tr("Search"), query, { query = it }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            keyboard = KeyboardOptions(imeAction = ImeAction.Search))
        LazyColumn(Modifier.fillMaxWidth()) {
            if (top != null) {
                item { Text(tr("Suggested"), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp)) }
                item { CountryRow(top, Countries.name(top), top == current) { onPick(top) } }
                item { Text(tr("All countries"), style = Heartpact.type.small, color = colors.ink2, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 2.dp)) }
            }
            items(shown, key = { it.first }) { (code, name) -> CountryRow(code, name, code == current) { onPick(code) } }
            if (shown.isEmpty()) item {
                Text(tr("No country matches “{query}”.", "query" to query), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(20.dp))
            }
        }
    }
}

@Composable
private fun CountryRow(code: String, name: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Heartpact.colors
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected }
            .then(if (selected) Modifier.background(colors.page) else Modifier)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Countries.flag(code), fontSize = 22.sp, modifier = Modifier.padding(end = 14.dp))
        Text(name, style = Heartpact.type.body, color = colors.ink, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, null, tint = Heartpact.actor.action)
    }
}
