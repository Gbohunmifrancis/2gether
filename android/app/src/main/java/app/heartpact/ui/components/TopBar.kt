package app.heartpact.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.heartpact.i18n.tr
import app.heartpact.ui.theme.Heartpact

/** The small top app bar: back, then the title, and [trailing] at the end (the live mark, on sign-up screens). */
@Composable
fun TopBar(title: String = "", onBack: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, tr("Back"), tint = Heartpact.colors.ink2) }
        Text(title, style = Heartpact.type.title, color = Heartpact.colors.ink, modifier = Modifier.padding(start = 4.dp).weight(1f))
        trailing?.let { Box(Modifier.padding(end = 12.dp)) { it() } }
    }
}
