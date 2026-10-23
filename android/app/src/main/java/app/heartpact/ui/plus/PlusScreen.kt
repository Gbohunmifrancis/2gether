package app.heartpact.ui.plus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.heartpact.billing.PlanOption
import app.heartpact.billing.PlusOffers
import app.heartpact.data.Entitlement
import app.heartpact.data.PlanLimits
import app.heartpact.data.utc
import app.heartpact.i18n.tr
import app.heartpact.ui.components.ButtonKind
import app.heartpact.ui.components.CardRow
import app.heartpact.ui.components.HeartButton
import app.heartpact.ui.components.NoteCard
import app.heartpact.ui.components.StickerCard
import app.heartpact.ui.graphics.Character
import app.heartpact.ui.graphics.HalfState
import app.heartpact.ui.graphics.HeartSticker
import app.heartpact.ui.graphics.Pose
import app.heartpact.ui.graphics.Who
import app.heartpact.ui.theme.Heartpact
import app.heartpact.ui.theme.Shapes
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the Plus screen says, kept apart from layout so it can be tested as text. */
object PlusText {
    private val day = DateTimeFormatter.ofPattern("d MMM yyyy")

    fun date(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = day.format(utc(iso).atZone(zone))

    fun subtitle(entitlement: Entitlement?, partnerName: String): String {
        val subscription = entitlement?.subscription
        return when {
            entitlement == null || entitlement.plan == app.heartpact.data.Plan.Free -> tr("One plan covers both of you. Either of you can buy it.")
            subscription != null && subscription.purchasedByMe -> tr("You have Plus. It covers you and {partner}.", "partner" to partnerName)
            subscription != null -> tr("{partner} pays for Plus. It covers you both.", "partner" to partnerName)
            entitlement.complimentary -> entitlement.complimentaryUntilUtc?.let { tr("Plus is on us until {date}. Subscribe to keep it after that.", "date" to date(it)) } ?: tr("Plus is on us. It covers you both.")
            else -> tr("You both have Plus.")
        }
    }

    /** The line under a paid subscription: renews, ends, or needs a payment fixed. */
    fun subscriptionStatus(entitlement: Entitlement?, partnerName: String): String? {
        val subscription = entitlement?.subscription ?: return null
        val until = date(subscription.expiresAtUtc)
        val plan = PlusOffers.planName(subscription.productId).lowercase()
        val mine = subscription.purchasedByMe
        return when {
            subscription.status == "inGracePeriod" ->
                if (mine) tr("Google couldn't take the last payment. Fix it in Google Play by {date} to keep Plus.", "date" to until)
                else tr("Google couldn't take {partner}'s last payment. Plus stays until {date} while they fix it.", "partner" to partnerName, "date" to until)
            !subscription.autoRenewing || subscription.status == "canceled" ->
                if (mine) tr("Your {plan} plan is set not to renew. Plus ends on {date}.", "plan" to plan, "date" to until)
                else tr("{partner}'s {plan} plan is set not to renew. Plus ends on {date}.", "partner" to partnerName, "plan" to plan, "date" to until)
            else ->
                if (mine) tr("Your {plan} plan renews on {date}.", "plan" to plan, "date" to until)
                else tr("{partner}'s {plan} plan renews on {date}.", "partner" to partnerName, "plan" to plan, "date" to until)
        }
    }

    /** The Us row's subtitle. */
    fun usRow(entitlement: Entitlement?, partnerName: String): String {
        val subscription = entitlement?.subscription
        return when {
            entitlement == null -> tr("More food scans, album space and history")
            entitlement.plan == app.heartpact.data.Plan.Free -> tr("Free plan · more scans, album space and history with Plus")
            subscription != null && subscription.purchasedByMe -> tr("Plus · you pay, it covers you both")
            subscription != null -> tr("Plus · {partner} pays, it covers you both", "partner" to partnerName)
            else -> entitlement.complimentaryUntilUtc?.let { tr("Plus · on us until {date}", "date" to date(it)) } ?: tr("Plus · on us")
        }
    }

    val FOOTER get() = tr("Cancel anytime in the Play Store. Deleting your account doesn't cancel it.")
}

/** Heartpact Plus (mockup 18): what you both get, the plans, and Google Play's purchase sheet. */
@Composable
fun PlusScreen(
    ui: PlusUi,
    partnerName: String,
    onClose: () -> Unit,
    onSelect: (String) -> Unit,
    onBuy: () -> Unit,
    onRestore: () -> Unit,
    onManage: (String) -> Unit,
) {
    val colors = Heartpact.colors
    val entitlement = ui.entitlement
    val subscription = entitlement?.subscription
    val showPlans = entitlement != null && entitlement.canPurchase && subscription == null
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, tr("Close"), tint = colors.ink2) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Heartpact Plus", style = Heartpact.type.headline, color = colors.ink)
                    Text(PlusText.subtitle(entitlement, partnerName), style = Heartpact.type.bodyLarge, color = colors.ink2, modifier = Modifier.padding(top = 6.dp))
                }
                Character(Who.Both, Pose.Cheer, 104.dp)
            }
            if (ui.welcomed) NoteCard(tr("Welcome to Plus. {partner} has it too, from now on.", "partner" to partnerName), Modifier.padding(top = 16.dp))
            ui.note?.let { NoteCard(it, Modifier.padding(top = 16.dp)) }
            if (ui.pending) NoteCard(PlusViewModel.PENDING, Modifier.padding(top = 16.dp))

            if (ui.loading) {
                Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Heartpact.actor.action) }
            } else if (entitlement != null) {
                Comparison(entitlement.freeLimits, entitlement.plusLimits, Modifier.padding(top = 18.dp))
                PlusText.subscriptionStatus(entitlement, partnerName)?.let { status ->
                    StickerCard(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        CardRow(status, subtitle = if (subscription?.purchasedByMe == true) tr("Managed in Google Play") else tr("{partner} manages it in Google Play", "partner" to partnerName))
                    }
                }
                if (showPlans) {
                    Text(tr("Choose a plan"), style = Heartpact.type.label, color = colors.ink, modifier = Modifier.padding(top = 20.dp))
                    if (ui.options.isEmpty()) {
                        Text(ui.storeMessage ?: tr("Loading plans from Google Play…"), style = Heartpact.type.body, color = colors.ink2, modifier = Modifier.padding(top = 8.dp))
                    }
                    ui.options.forEach { option ->
                        PlanCard(option, selected = option.productId == ui.selected, onClick = { onSelect(option.productId) }, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
            when {
                subscription?.purchasedByMe == true -> HeartButton(tr("Manage in Google Play"), onClick = { onManage(subscription.productId) }, kind = ButtonKind.Tonal)
                showPlans -> HeartButton(tr("Continue with Google Play"), onClick = onBuy, enabled = ui.selectedOption != null && !ui.pending, busy = ui.busy)
                else -> HeartButton(tr("Done"), onClick = onClose, kind = ButtonKind.Tonal)
            }
            if (showPlans) {
                Text(PlusText.FOOTER, style = Heartpact.type.small, color = colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                HeartButton(tr("Restore a purchase"), onClick = onRestore, kind = ButtonKind.Text, enabled = !ui.busy, height = 40.dp)
            }
        }
    }
}

@Composable
private fun Comparison(free: PlanLimits?, plus: PlanLimits?, modifier: Modifier = Modifier) {
    if (free == null || plus == null) return
    val colors = Heartpact.colors
    StickerCard(modifier.fillMaxWidth()) {
        CardRow(tr("What you both get"), trailing = {
            Text(tr("Free"), style = Heartpact.type.labelSmall, color = colors.ink3, textAlign = TextAlign.End, modifier = Modifier.width(66.dp))
            Text("Plus", style = Heartpact.type.labelSmall, color = Heartpact.actor.action, textAlign = TextAlign.End, modifier = Modifier.width(66.dp))
        })
        PlusOffers.comparison(free, plus).forEach { (label, freeValue, plusValue) ->
            CardRow(label, divider = true, trailing = {
                Text(freeValue, style = Heartpact.type.body, color = colors.ink2, textAlign = TextAlign.End, modifier = Modifier.width(66.dp))
                Text(plusValue, style = Heartpact.type.body.copy(fontWeight = FontWeight.Bold), color = colors.ink, textAlign = TextAlign.End, modifier = Modifier.width(66.dp))
            })
        }
    }
}

@Composable
private fun PlanCard(option: PlanOption, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Heartpact.colors
    var m = modifier.fillMaxWidth()
    if (selected) m = m.border(2.5.dp, Heartpact.actor.action, Shapes.Card)
    StickerCard(m.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Chosen: the whole heart, stuck. Not chosen: the dashed slot it would go in.
            if (selected) HeartSticker(HalfState.Stuck, HalfState.Stuck, 30.dp) else HeartSticker(HalfState.Slot, HalfState.Slot, 30.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(option.name, style = Heartpact.type.label, color = colors.ink)
                    option.savings?.let {
                        Text(it, style = Heartpact.type.labelSmall, color = Heartpact.actor.onSoft,
                            modifier = Modifier.background(Heartpact.actor.soft, Shapes.Pill).padding(horizontal = 9.dp, vertical = 3.dp))
                    }
                }
                Text(option.priceLine, style = Heartpact.type.small, color = colors.ink2)
            }
        }
    }
}
