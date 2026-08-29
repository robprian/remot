package com.robrion.remot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.robrion.remot.network.EndpointState
import com.robrion.remot.network.NetworkHealth
import com.robrion.remot.services.ServiceState

/** Subtle colored dot + label used for every status line. */
@Composable
fun StatusIndicator(
    label: String,
    state: EndpointState,
    modifier: Modifier = Modifier,
) {
    val (color, text) = when (state) {
        EndpointState.ONLINE -> statusColor(StatusTone.SUCCESS) to "Connected"
        EndpointState.CHECKING -> statusColor(StatusTone.NEUTRAL) to "Checking…"
        EndpointState.OFFLINE -> statusColor(StatusTone.ERROR) to "Unreachable"
        EndpointState.UNKNOWN -> statusColor(StatusTone.NEUTRAL) to "Unknown"
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** Colored dot used by [StatusIndicator]. */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}

enum class StatusTone { SUCCESS, WARNING, ERROR, NEUTRAL }

/** Resolve a semantic tone into a theme-aware color (only used for status accents). */
@Composable
fun statusColor(tone: StatusTone): Color = when (tone) {
    StatusTone.SUCCESS -> MaterialTheme.colorScheme.primary
    StatusTone.WARNING -> androidx.compose.material3.MaterialTheme.colorScheme.tertiary
    StatusTone.ERROR -> MaterialTheme.colorScheme.error
    StatusTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Service-state (accessibility / notification listener) row with a Manage action. */
@Composable
fun ServiceCard(
    title: String,
    subtitle: String,
    state: ServiceState,
    icon: ImageVector,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (color, text) = when (state) {
                        ServiceState.CONNECTED -> statusColor(StatusTone.SUCCESS) to "● Connected"
                        ServiceState.ENABLED -> statusColor(StatusTone.WARNING) to "● Enabled"
                        ServiceState.INSTALLED -> statusColor(StatusTone.NEUTRAL) to "○ Disabled"
                        ServiceState.NOT_INSTALLED -> statusColor(StatusTone.ERROR) to "⚠ Not installed"
                    }
                    StatusDot(color)
                    Spacer(Modifier.width(6.dp))
                    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
                }
            }
            if (state != ServiceState.CONNECTED) {
                androidx.compose.material3.TextButton(onClick = onManage) { Text("Manage") }
            }
        }
    }
}

/** Compact infrastructure health card for the home screen. */
@Composable
fun NetworkHealthCard(
    health: NetworkHealth,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connection health", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onRefresh) { Text("Refresh") }
            }
            Spacer(Modifier.height(4.dp))
            StatusIndicator("Internet", health.internet)
            StatusIndicator("Signaling", health.signaling)
            // Registration is distinct from the socket connect: a connected-but-
            // unauthenticated signaling link is NOT ready for sessions.
            if (health.signalingRegistered || health.signalingAuthFailed) {
                val (tone, text) = when {
                    health.signalingAuthFailed -> StatusTone.ERROR to "Auth Failed"
                    else -> StatusTone.SUCCESS to "Authenticated"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor(tone))
                    Spacer(Modifier.width(8.dp))
                    Text("Registration", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Text(text, style = MaterialTheme.typography.labelMedium, color = statusColor(tone))
                }
            }
            StatusIndicator("STUN", health.stun)
            StatusIndicator("TURN", health.turn)

            // Signaling ping (app-level heartbeat round-trip, ms) — shown
            // separately from the TURN latency below; they measure different paths.
            health.signalingLatencyMs?.let { ms ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Signaling ping", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Text("${ms} ms", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold, color = latencyQualityColor(ms))
                }
            }
            // Measured TURN latency — a real STUN/TURN round-trip, never a guess.
            health.latencyMs?.let { ms ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("TURN latency", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f))
                    Text(
                        "${ms} ms",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = latencyQualityColor(ms)
                    )
                }
            }
            health.turnHost?.let {
                Spacer(Modifier.height(4.dp))
                Text("TURN · $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Latency quality classification (subtle, single accent). */
@Composable
private fun latencyQualityColor(ms: Long): Color = when {
    ms < 50 -> statusColor(StatusTone.SUCCESS)
    ms < 100 -> statusColor(StatusTone.SUCCESS)
    ms < 200 -> statusColor(StatusTone.WARNING)
    else -> statusColor(StatusTone.WARNING)
}

/** Section heading used across screens. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}
