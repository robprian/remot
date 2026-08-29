package com.robrion.remot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robrion.remot.BuildConfig
import com.robrion.remot.MainViewModel
import com.robrion.remot.Screen
import com.robrion.remot.network.EndpointState
import com.robrion.remot.network.NetworkHealth
import com.robrion.remot.services.ServiceState
import com.robrion.remot.services.ServiceStatus
import com.robrion.remot.ui.components.NetworkHealthCard
import com.robrion.remot.ui.components.ServiceCard
import com.robrion.remot.ui.components.SectionHeading
import com.robrion.remot.ui.components.StatusDot
import com.robrion.remot.ui.components.StatusIndicator
import com.robrion.remot.ui.components.StatusTone
import com.robrion.remot.ui.components.statusColor
import com.robrion.remot.update.UpdateInfoState
import com.robrion.remot.signaling.SignalingDebugLog
import com.robrion.remot.webrtc.LinkState
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single-activity Compose root. Renders the current [Screen] inside a
 * system-inset-aware container and overlays the consent dialog whenever an
 * incoming controller is awaiting host approval.
 *
 * Insets: the app runs edge-to-edge (Android 15/16 enforce this), so the root
 * applies WindowInsets.safeDrawing — content is never hidden under the status
 * bar, display cutout, or gesture/navigation bar.
 */
@Composable
fun AppRoot(
    vm: MainViewModel,
    onRequestNotifications: () -> Unit,
    onAllowIncoming: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        // safeDrawing = status bar + display cutout (top) and nav bar (bottom).
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            when (vm.screen) {
                Screen.HOME -> HomeScreen(vm, onRequestNotifications, onOpenAccessibilitySettings, onOpenNotificationSettings)
                Screen.HOST_CODE -> HostCodeScreen(vm)
                Screen.JOIN -> JoinScreen(vm)
                Screen.SCAN -> QrScannerScreen(
                    onCode = { raw -> vm.onScanned(raw) },
                    onManual = { vm.goJoin() },
                    onCancel = { vm.goJoin() },
                )
                Screen.PAIRED -> PairedDevicesScreen(vm)
                Screen.PAIR_QR -> PairQrScreen(vm)
                Screen.SAFETY_NUMBER -> SafetyNumberScreen(vm)
                Screen.SESSION -> SessionScreen(vm)
                Screen.SERVICES -> ServicesScreen(vm, onOpenAccessibilitySettings, onOpenNotificationSettings)
            }
        }
    }

    // In-app update overlay — non-blocking, only when a newer release exists.
    vm.updateState?.let { state ->
        RemotUpdateDialog(state = state, onDismiss = { vm.dismissUpdate() }, onUpdate = { vm.downloadAndInstallUpdate() })
    }

    // Consent overlay (host side) — the compliance-critical gate.
    vm.pendingControllerId?.let { controllerId ->
        ConsentDialog(
            controllerId = controllerId,
            unattended = vm.pendingUnattended,
            onAllow = onAllowIncoming,     // launches MediaProjection dialog, then approveIncoming()
            onDeny = { vm.declineIncoming() }
        )
    }
}

// ---------------------------------------------------------------------------
// HOME — the dashboard
// ---------------------------------------------------------------------------

@Composable
private fun HomeScreen(
    vm: MainViewModel,
    onRequestNotifications: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header — clearly separated from the status bar by safeDrawing insets.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remot", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Secure Android-to-Android remote control",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Diagnostics shortcut (small, quiet).
            IconButton(onClick = { vm.goServices() }) {
                Icon(Icons.Default.Speed, contentDescription = "System services & diagnostics",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Infrastructure health — real STUN/TURN checks, not faked.
        NetworkHealthCard(health = vm.networkHealth, onRefresh = { vm.refreshNetworkHealth() })

        // Remote session state (distinct from infrastructure!).
        SessionStateRow(vm)

        // Primary actions.
        ElevatedCard(
            Modifier.fillMaxWidth().clickable { vm.becomeHost() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.TouchApp, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Share my screen", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Generate a code so another device can view & control this phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }

        ElevatedCard(
            Modifier.fillMaxWidth().clickable { vm.goJoin() },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QrCode2, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Connect to a device", style = MaterialTheme.typography.titleMedium)
                    Text("Enter a 6-digit code to control another phone",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Secondary actions.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.goPaired() }, modifier = Modifier.weight(1f)) {
                Text("Paired devices")
            }
        }

        // Services status (accessibility + notifications).
        SectionHeading("Services")
        SetupStatusRow(
            accessibilityState = vm.accessibilityState,
            notificationState = vm.notificationState,
            onEnableAccessibility = onOpenAccessibilitySettings,
            onEnableNotifications = onOpenNotificationSettings,
            onRequestNotifications = onRequestNotifications,
        )
    }
}

@Composable
private fun SessionStateRow(vm: MainViewModel) {
    val (text, color) = when (vm.linkState) {
        LinkState.CONNECTING -> "Connecting…" to statusColor(StatusTone.NEUTRAL)
        LinkState.CONNECTED -> "Connected" to statusColor(StatusTone.SUCCESS)
        LinkState.RECOVERING -> "Reconnecting…" to statusColor(StatusTone.WARNING)
        LinkState.CLOSED -> "No device connected" to statusColor(StatusTone.NEUTRAL)
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Remote session", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun SetupStatusRow(
    accessibilityState: ServiceState,
    notificationState: ServiceState,
    onEnableAccessibility: () -> Unit,
    onEnableNotifications: () -> Unit,
    onRequestNotifications: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ServiceCard(
            title = "Accessibility",
            subtitle = "Required for remote touch & text input",
            state = accessibilityState,
            icon = Icons.Default.TouchApp,
            onManage = onEnableAccessibility,
        )
        ServiceCard(
            title = "Notification Access",
            subtitle = "Lets Remot surface notifications during a session",
            state = notificationState,
            icon = Icons.Default.Notifications,
            onManage = onEnableNotifications,
        )
    }
}

// ---------------------------------------------------------------------------
// SERVICES — accessibility + notification listener + diagnostics
// ---------------------------------------------------------------------------

@Composable
private fun ServicesScreen(
    vm: MainViewModel,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.goHome() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("System services", style = MaterialTheme.typography.headlineSmall)
        }

        // Accessibility
        ServiceCard(
            title = "Accessibility",
            subtitle = if (vm.accessibilityState == ServiceState.CONNECTED)
                "Remote gesture control ready" else "Remote control needs this permission",
            state = vm.accessibilityState,
            icon = Icons.Default.TouchApp,
            onManage = onOpenAccessibilitySettings,
        )
        when (vm.accessibilityState) {
            ServiceState.INSTALLED -> HelpNote(
                "Remot Control Service is installed but not enabled. Open Accessibility " +
                    "Settings and enable “Remot Control Service”."
            )
            ServiceState.ENABLED -> HelpNote("Enabled — waiting for the system to connect the service.")
            else -> {}
        }

        // Notification listener
        ServiceCard(
            title = "Notification Access",
            subtitle = "Shows host notifications to an active session",
            state = vm.notificationState,
            icon = Icons.Default.Notifications,
            onManage = onOpenNotificationSettings,
        )
        when (vm.notificationState) {
            ServiceState.INSTALLED -> HelpNote(
                "Remot Notifications is installed but access is not granted. Open " +
                    "Notification Access settings and enable Remot."
            )
            else -> {}
        }

        // Diagnostics
        SectionHeading("Diagnostics")
        DiagnosticsCard(vm)
        SignalingDebugCard()

        // Device / app info
        SectionHeading("Device")
        InfoCard(vm)

        // Developer / build info
        SectionHeading("Developer")
        DeveloperInfoCard(vm.networkHealth)
    }
}

@Composable
private fun HelpNote(text: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun DiagnosticsCard(vm: MainViewModel) {
    val h = vm.networkHealth
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusIndicator("Internet", h.internet)
            // Connection (WS up) is shown separately from Registration (identity
            // authenticated) — a connected-but-unregistered socket is NOT ready.
            StatusIndicator("Signaling", h.signaling)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val regTone = when {
                    h.signalingAuthFailed -> StatusTone.ERROR
                    h.signalingRegistered -> StatusTone.SUCCESS
                    h.signaling == EndpointState.OFFLINE -> StatusTone.NEUTRAL
                    else -> StatusTone.NEUTRAL
                }
                val regText = when {
                    h.signalingAuthFailed -> "Auth Failed"
                    h.signalingRegistered -> "Authenticated"
                    h.signaling == EndpointState.ONLINE -> "Pending…"
                    else -> "—"
                }
                StatusDot(statusColor(regTone))
                Spacer(Modifier.width(8.dp))
                Text("Registration", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(regText, style = MaterialTheme.typography.labelMedium, color = statusColor(regTone))
            }
            h.signalingUrl?.let {
                Row {
                    Text("Signaling endpoint", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            h.signalingLatencyMs?.let { ms ->
                Row {
                    Text("Signaling ping", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("$ms ms", style = MaterialTheme.typography.labelLarge)
                }
            }
            h.signalingError?.let {
                Text("Reason: $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                Text("Remot retries alternate endpoints automatically when the primary is unreachable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusIndicator("STUN", h.stun)
            StatusIndicator("TURN", h.turn)
            if (h.stun == EndpointState.UNKNOWN && h.turn == EndpointState.UNKNOWN) {
                Text("STUN/TURN credentials come from the signaling server once it connects.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            h.turnHost?.let {
                Row {
                    Text("TURN host", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
            h.latencyMs?.let {
                Row {
                    Text("TURN latency", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("$it ms", style = MaterialTheme.typography.labelLarge)
                }
            }
            // ICE route of an active session (host / srflx / relay).
            vm.iceRoute?.let {
                Row {
                    Text("ICE route", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(routeLabel(it), style = MaterialTheme.typography.labelLarge,
                        color = if (it == "relay") statusColor(StatusTone.WARNING) else statusColor(StatusTone.SUCCESS))
                }
            }
        }
    }
}

private fun routeLabel(route: String): String = when (route) {
    "relay" -> "TURN Relay"
    "srflx" -> "STUN (server-reflexive)"
    "host" -> "Direct (LAN)"
    else -> route
}

/**
 * Live log of the signaling WebSocket connection attempts + failures. This is
 * the on-device source of truth for "Signaling Unreachable" — the exact
 * OS-level error, the endpoint tried, and a copy-to-clipboard so it can be
 * pasted to the developer. (Chucker can't see WebSockets, hence this card.)
 */
@Composable
private fun SignalingDebugCard() {
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val entries = SignalingDebugLog.snapshot()
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Signaling debug log", style = MaterialTheme.typography.titleSmall)
            Text("WebSocket connect attempts recorded on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (entries.isEmpty()) {
                Text("No signaling activity recorded yet. If Signaling shows " +
                        "Unreachable, the exact attempt + error will appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                entries.take(8).forEach { e ->
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        val label = "${fmt.format(Date(e.time))} [${e.event}]"
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (e.event) {
                                "CONNECTED" -> statusColor(StatusTone.SUCCESS)
                                "FAILED", "CLOSED", "REGISTER-FAILED" -> statusColor(StatusTone.ERROR)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Text(e.endpoint, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (e.detail.isNotBlank()) {
                            Text(e.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            SignalingDebugLog.lastError?.let {
                Text("Last failure: $it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ copySignalingLog(context) }) { Text("Copy log") }
                TextButton({ openHttpInspector(context) }) { Text("HTTP inspector") }
            }
        }
    }
}

private fun copySignalingLog(context: Context) {
    val text = SignalingDebugLog.dump()
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("Remot signaling log", text))
    }
    Toast.makeText(context, "Signaling log copied (${SignalingDebugLog.snapshot().size} events)", Toast.LENGTH_SHORT).show()
}

private fun openHttpInspector(context: Context) {
    val started = runCatching {
        val cls = Class.forName("com.chuckerteam.chucker.ui.MainActivity")
        val intent = Intent().setClassName(context.packageName, cls.name)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.isSuccess
    if (!started) {
        Toast.makeText(context, "HTTP inspector unavailable — open Chucker from its notification", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun InfoCard(vm: MainViewModel) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            InfoRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
            InfoRow("App version", BuildConfig.VERSION_NAME)
            InfoRow("Device ID", vm.deviceFingerprint)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Developer-facing build/signing/endpoint info surfaced for support & debugging. */
@Composable
private fun DeveloperInfoCard(health: NetworkHealth) {
    val uriHandler = LocalUriHandler.current
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow("Build", "${BuildConfig.VERSION_NAME} · ${BuildConfig.BUILD_TYPE}")
            InfoRow("versionCode", BuildConfig.VERSION_CODE.toString())
            InfoRow("applicationId", BuildConfig.APPLICATION_ID)
            InfoRow("Signaling", health.signalingUrl ?: "—")
            InfoRow("TURN host", health.turnHost ?: "—")
            // Maintainer — tappable link straight to the repo.
            Row(
                Modifier.clickable { uriHandler.openUri(GITHUB_REPO_URL) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Developer", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(
                    "@robprian ↗",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

}

private const val GITHUB_REPO_URL = "https://github.com/robprian/remot"

// ---------------------------------------------------------------------------
// HOST CODE / JOIN / PAIRED / SAFETY NUMBER — restyled consistently
// ---------------------------------------------------------------------------

@Composable
private fun HostCodeScreen(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Scan this code, or enter it manually", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        val payload = vm.sessionQrPayload()
        if (payload != null) {
            QrImage(text = payload, sizePx = 560, modifier = Modifier.size(220.dp))
            Spacer(Modifier.height(16.dp))
        }
        Text(
            vm.sessionCode ?: "······",
            style = MaterialTheme.typography.displayMedium,
            letterSpacing = 8.sp
        )
        Spacer(Modifier.height(8.dp))
        Text("Expires in 5 minutes", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = { vm.cancelHost() }) { Text("Cancel") }
    }
}

@Composable
private fun JoinScreen(vm: MainViewModel) {
    var code by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(48.dp))
        Text("Enter connection code", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6 && it.all(Char::isDigit)) code = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            textStyle = TextStyle(fontSize = 32.sp, letterSpacing = 8.sp, textAlign = TextAlign.Center),
            singleLine = true,
            enabled = !vm.connecting
        )
        vm.joinError?.let {
            Spacer(Modifier.height(8.dp))
            Text(joinErrorMessage(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))

        if (vm.connecting) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Waiting for the host to accept…", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.cancelConnecting() }) { Text("Cancel") }
        } else {
            Button(onClick = { vm.connectWithCode(code) }, enabled = code.length == 6) { Text("Connect") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.goScan() }) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Scan QR code")
            }
            TextButton(onClick = { vm.goHome() }) { Text("Back") }
        }
    }
}

private fun joinErrorMessage(reason: String): String = when (reason) {
    "invalid-code" -> "That code isn't valid. Enter the 6 digits shown on the host."
    "not-paired" -> "This device isn't paired with that host."
    "host-offline" -> "The host is offline. Ask them to open Share Screen again."
    "declined" -> "The host declined the connection."
    "timeout" -> "No response — the host may have declined or gone offline."
    "unreadable-qr" -> "That QR code didn't contain a valid session code."
    "rate-limited" -> "Too many attempts. Wait a minute and try again."
    "no-target", "invalid" -> "The session code has expired. Ask the host for a new one."
    else -> "Connection failed ($reason)."
}

@Composable
private fun PairedDevicesScreen(vm: MainViewModel) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Paired devices", style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.height(16.dp))

        val peers = vm.pairedPeers()
        if (peers.isEmpty()) {
            Text("No paired devices yet.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        peers.forEach { peer ->
            ListItem(
                headlineContent = { Text(peer.peerName) },
                supportingContent = {
                    Column {
                        Text("Fingerprint: ${peer.shortFingerprint}", style = MaterialTheme.typography.bodySmall)
                        Text("State: ${peer.state}", style = MaterialTheme.typography.bodySmall)
                    }
                },
                trailingContent = {
                    Row {
                        TextButton(onClick = { vm.connectToPaired(peer.peerId) }) { Text("Connect") }
                        TextButton(onClick = { vm.revokePeer(peer) }) { Text("Remove") }
                    }
                }
            )
            HorizontalDivider()
        }

        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { vm.startPairing() }) {
            Icon(Icons.Default.QrCode2, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Pair a new device — show my QR")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.goScan() }) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan a pairing code")
        }
    }
}

/** Host side of pairing: shows the signed PairingOffer QR the other device scans. */
@Composable
private fun PairQrScreen(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Pair a new device", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Have the other device open Remot and scan this QR code.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        val payload = vm.pairingQrPayload()
        if (payload != null) {
            QrImage(text = payload, sizePx = 560, modifier = Modifier.size(240.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text("Expires in 2 minutes", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        OutlinedButton(onClick = { vm.cancelPairing() }) { Text("Cancel") }
    }
}

@Composable
private fun SafetyNumberScreen(vm: MainViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Verify this code matches on BOTH devices", textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Text(
            vm.safetyNumber ?: "",
            style = MaterialTheme.typography.headlineMedium,
            letterSpacing = 4.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Read it aloud to the other person. Only confirm if they match exactly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { vm.rejectPairing() }) { Text("They don't match") }
            Button(onClick = { vm.confirmPairing() }) { Text("They match") }
        }
    }
}

// ---------------------------------------------------------------------------
// SESSION
// ---------------------------------------------------------------------------

@Composable
private fun SessionScreen(vm: MainViewModel) {
    var showKeyboard by remember { mutableStateOf(false) }
    var textInput by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Connection-state banner.
        val (statusText, statusColor) = when (vm.linkState) {
            LinkState.CONNECTING -> "Connecting…" to statusColor(StatusTone.NEUTRAL)
            LinkState.CONNECTED -> "Connected" to statusColor(StatusTone.SUCCESS)
            LinkState.RECOVERING -> "Reconnecting…" to statusColor(StatusTone.WARNING)
            LinkState.CLOSED -> "Session ended" to statusColor(StatusTone.ERROR)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (vm.linkState == LinkState.RECOVERING || vm.linkState == LinkState.CONNECTING) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(statusText, style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold, color = statusColor)
            // Route badge when known.
            vm.iceRoute?.let {
                Spacer(Modifier.width(12.dp))
                Text(routeLabel(it), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(8.dp))

        // Live remote screen: tap / long-press / swipe to control.
        RemoteVideoSurface(
            eglBase = vm.eglBase,
            track = vm.remoteVideo,
            onTap = { nx, ny -> vm.sendTap(nx, ny) },
            onLongPress = { nx, ny -> vm.sendLongPress(nx, ny) },
            onSwipe = { x1, y1, x2, y2, ms -> vm.sendSwipe(x1, y1, x2, y2, ms) },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        // Navigation + keyboard toolbar.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = { vm.sendKey("BACK") }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Press Back on remote device")
            }
            FilledTonalIconButton(onClick = { vm.sendKey("HOME") }) {
                Icon(Icons.Default.Home, contentDescription = "Press Home on remote device")
            }
            FilledTonalIconButton(onClick = { vm.sendKey("RECENTS") }) {
                Icon(Icons.Default.History, contentDescription = "Open recent apps on remote device")
            }
            FilledTonalIconButton(onClick = { showKeyboard = !showKeyboard }) {
                Icon(Icons.Default.Keyboard, contentDescription = "Toggle keyboard input")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (showKeyboard) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type text for the remote device") },
                    singleLine = true
                )
                Button(
                    onClick = { vm.sendText(textInput); textInput = "" },
                    enabled = textInput.isNotEmpty()
                ) { Text("Send") }
            }
            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = { vm.endSession() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth()
        ) { Text("End session") }
    }

    // Retry dialog when recovery exhausts and the link closes unexpectedly.
    if (vm.linkState == LinkState.CLOSED) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Connection lost") },
            text = { Text("The session ended. You can try to reconnect or return home.") },
            confirmButton = { Button(onClick = { vm.retryConnect() }) { Text("Reconnect") } },
            dismissButton = { TextButton(onClick = { vm.endSession() }) { Text("Home") } }
        )
    }
}

@Composable
private fun ConsentDialog(
    controllerId: String,
    unattended: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text(if (unattended) "Trusted device connecting" else "Allow remote control?") },
        text = {
            Column {
                Text("Device \"${controllerId.take(12)}…\" is requesting to VIEW and CONTROL this phone.")
                Spacer(Modifier.height(12.dp))
                Text("• They will see your screen live", style = MaterialTheme.typography.bodySmall)
                Text("• They can tap, swipe, and type", style = MaterialTheme.typography.bodySmall)
                Text("• A notification stays visible while active", style = MaterialTheme.typography.bodySmall)
                Text("• You can end the session anytime", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = onAllow) { Text("Allow") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } }
    )
}

// ---------------------------------------------------------------------------
// IN-APP UPDATE
// ---------------------------------------------------------------------------

/** Non-blocking update prompt shown when a newer GitHub release exists. */
@Composable
private fun RemotUpdateDialog(
    state: UpdateInfoState,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
) {
    when (state) {
        is UpdateInfoState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.Download, contentDescription = null) },
            title = { Text("Update available") },
            text = {
                Text(
                    "Remot ${state.release.versionName} is available — you're on ${BuildConfig.VERSION_NAME}. " +
                        "Download the latest APK from GitHub and install it."
                )
            },
            confirmButton = { Button(onClick = onUpdate) { Text("Download & Update") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
        )

        is UpdateInfoState.Downloading -> AlertDialog(
            onDismissRequest = {},
            icon = { Icon(Icons.Default.Download, contentDescription = null) },
            title = { Text("Downloading update…") },
            text = {
                Column {
                    Text("Remot ${state.release.versionName}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { state.progress / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${state.progress}%", style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = {}
        )

        is UpdateInfoState.InstallStarted -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update ready") },
            text = {
                Text(
                    "The APK is downloaded. Follow the system prompt to install " +
                        "Remot ${state.release.versionName}. You can return to the app afterwards."
                )
            },
            confirmButton = { Button(onClick = onDismiss) { Text("OK") } }
        )

        is UpdateInfoState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Update failed") },
            text = { Text(state.message) },
            confirmButton = { Button(onClick = onUpdate) { Text("Retry") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
        )
    }
}
