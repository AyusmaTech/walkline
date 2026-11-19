package com.tobenamed.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tobenamed.app.live.AddressCandidate
import com.tobenamed.nav.live.FixQuality
import com.tobenamed.nav.live.ManeuverCue
import com.tobenamed.nav.route.RouteStatus
import kotlin.math.roundToInt

internal val NavigationInk = Color(0xFF101C1B)
internal val NavigationLime = Color(0xFFBCF477)
internal val NavigationAmber = Color(0xFFFFD28E)

@Composable
internal fun SceneAction(label: String, icon: NavIcon, onClick: () -> Unit,
    prominent: Boolean = false, iconOnly: Boolean = false) {
    val background = if (prominent) NavigationLime else NavigationInk.copy(alpha = .94f)
    val foreground = if (prominent) NavigationInk else Color(0xFFF2F4E9)
    Surface(color = background, shape = RoundedCornerShape(16.dp), shadowElevation = 2.dp) {
        TextButton(onClick, modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .semantics(mergeDescendants = true) { if (iconOnly) contentDescription = label }) {
            NavigationIcon(icon, foreground, Modifier.size(20.dp))
            if (!iconOnly) {
                Spacer(Modifier.width(7.dp))
                Text(label, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun SceneStatusPill(text: String, attention: Boolean = false) {
    val color = if (attention) NavigationAmber else Color(0xFFC7D7CF)
    Row(Modifier.background(NavigationInk.copy(alpha = .86f), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(5.dp).background(if (attention) NavigationAmber else NavigationLime, RoundedCornerShape(3.dp)))
        Text(text, color = color, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun DestinationHeading(destination: AddressCandidate, onDetails: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).background(NavigationAmber.copy(alpha = .12f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            NavigationIcon(NavIcon.HOUSE, NavigationAmber, Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "Destination, ${destination.address}" },
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("DESTINATION", fontSize = 9.sp, letterSpacing = 1.3.sp, color = MaterialTheme.colorScheme.secondary)
            Text(destination.address.substringBefore(','), maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        TextButton(onDetails, Modifier.size(48.dp).semantics { contentDescription = "Walk details" }) {
            NavigationIcon(NavIcon.DETAILS, MaterialTheme.colorScheme.secondary, Modifier.size(22.dp))
        }
    }
}

@Composable
internal fun StreetGuidanceCard(state: WalkUiState) {
    val progress = state.live.progress
    val usable = progress?.quality == FixQuality.GOOD
    val status = progress?.route?.status
    val maneuver = ManeuverCue.fromProvider(progress?.maneuver ?: "")
    val attention = !usable || status == RouteStatus.WRONG_WAY || status == RouteStatus.OFF_ROUTE || progress.joiningRoute
    val tint = if (attention || status == RouteStatus.ARRIVED) NavigationAmber else NavigationLime
    val title = when {
        !usable -> if (progress?.quality == FixQuality.WEAK) "GPS is uncertain" else "Waiting for GPS"
        progress.joiningRoute -> "Join walking route"
        status == RouteStatus.WRONG_WAY -> "Turn around"
        status == RouteStatus.OFF_ROUTE -> if (state.live.busy) "Updating route" else "Off the route"
        status == RouteStatus.ARRIVED -> "You're outside"
        else -> maneuver.title
    }
    val subtitle = when {
        !usable -> "Guidance is paused"
        progress.joiningRoute -> "Find a safe path to the route"
        status == RouteStatus.WRONG_WAY -> "You're moving away from the route"
        status == RouteStatus.OFF_ROUTE -> "Check your surroundings and the map"
        status == RouteStatus.ARRIVED -> "Check the building number"
        else -> ManeuverCue.streetLabel(progress.instruction) ?: if (maneuver == ManeuverCue.FOLLOW_ROUTE)
            progress.instruction else "Follow the walking route"
    }
    val icon = when {
        !usable -> NavIcon.PAUSE
        progress.joiningRoute -> NavIcon.JOIN
        status == RouteStatus.WRONG_WAY -> NavIcon.U_TURN
        status == RouteStatus.OFF_ROUTE -> NavIcon.WARNING
        status == RouteStatus.ARRIVED -> NavIcon.CHECK
        else -> NavIcon.forManeuver(maneuver)
    }
    val distance = if (usable && (progress.joiningRoute || progress.instructionIsUpcoming && maneuver != ManeuverCue.STRAIGHT) &&
        status != RouteStatus.ARRIVED && status != RouteStatus.WRONG_WAY && status != RouteStatus.OFF_ROUTE)
        progress.distanceToInstructionM else null
    Surface(color = NavigationInk.copy(alpha = .95f), shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = .18f)), shadowElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(46.dp).background(tint, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                NavigationIcon(icon, NavigationInk, Modifier.size(29.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (distance != null) Column(horizontalAlignment = Alignment.End) {
                Text("${distance.roundToInt()} m", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = tint)
                Text(if (progress?.joiningRoute == true) "to route" else "to turn", fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}
