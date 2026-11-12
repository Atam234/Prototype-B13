package com.zeroinstall.relay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroinstall.relay.crypto.PayloadCrypto
import com.zeroinstall.relay.data.MessageEntity
import com.zeroinstall.relay.data.MessageStatus
import com.zeroinstall.relay.hotspot.HotspotMode
import com.zeroinstall.relay.hotspot.WifiQr
import com.zeroinstall.relay.service.RelayEvent
import com.zeroinstall.relay.service.RelayPhase
import com.zeroinstall.relay.service.RelayRuntimeState
import com.zeroinstall.relay.ui.theme.StatusDelivered
import com.zeroinstall.relay.ui.theme.StatusPending
import com.zeroinstall.relay.ui.theme.StatusRelayed
import java.text.DateFormat
import java.util.Date

@Composable
fun RelayScreen(
    viewModel: MainViewModel,
    onStartLocalHotspot: () -> Unit,
    onStartManualHotspot: (ssid: String, passphrase: String) -> Unit,
    onStopRelay: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val relay by viewModel.relayState.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val endpoint by viewModel.cloudEndpoint.collectAsStateWithLifecycle()
    val encrypting by viewModel.isEncrypting.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.notices.collect { text ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(text)
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { HeaderSection(messages) }
            item {
                ComposeMessageCard(isEncrypting = encrypting) { recipient, body, secret, clear ->
                    viewModel.queueMessage(recipient, body, secret, clear)
                }
            }
            item {
                RelayControlCard(
                    relay = relay,
                    cloudEndpoint = endpoint,
                    onSaveEndpoint = viewModel::saveCloudEndpoint,
                    onStartLocalHotspot = onStartLocalHotspot,
                    onStartManualHotspot = onStartManualHotspot,
                    onStopRelay = onStopRelay,
                    onOpenHotspotSettings = onOpenHotspotSettings,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Messages", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::syncNow) { Text("Sync now") }
                    TextButton(onClick = viewModel::clearDelivered) { Text("Clear delivered") }
                }
            }
            if (messages.isEmpty()) {
                item {
                    Text(
                        "No messages yet. Compose one above — it is encrypted and stored as PENDING until a helper relays it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(messages, key = { it.id }) { MessageRow(it) }
            }
            item { ActivityLogCard(events) }
        }
    }
}

@Composable
private fun HeaderSection(messages: List<MessageEntity>) {
    val pending = messages.count { it.status == MessageStatus.PENDING }
    val relayed = messages.count { it.status == MessageStatus.RELAYED_VIA_HELPER }
    val delivered = messages.count { it.status == MessageStatus.DELIVERED }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("ZeroInstall Relay", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Send encrypted messages through any nearby phone's mobile data — the helper only needs a browser.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusBadge("$pending pending", StatusPending)
            StatusBadge("$relayed relayed", StatusRelayed)
            StatusBadge("$delivered delivered", StatusDelivered)
        }
    }
}

@Composable
private fun ComposeMessageCard(
    isEncrypting: Boolean,
    onQueue: (recipient: String, body: String, secret: String, onQueued: () -> Unit) -> Unit,
) {
    var recipient by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    // Deliberately not rememberSaveable: the secret must not be written into saved-state bundles.
    var secret by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }

    SectionCard(title = "1 · Compose (offline)") {
        OutlinedTextField(
            value = recipient,
            onValueChange = { recipient = it.take(128) },
            label = { Text("Recipient ID") },
            placeholder = { Text("e.g. +15551234567 or user@example.com") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = body,
            onValueChange = { body = it.take(4_000) },
            label = { Text("Message") },
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text("Shared secret with recipient") },
            supportingText = {
                Text("End-to-end key (min ${PayloadCrypto.MIN_SECRET_LENGTH} chars). Helpers and the cloud never see it.")
            },
            singleLine = true,
            visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide" else "Show") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onQueue(recipient, body, secret) { body = "" } },
            enabled = !isEncrypting && recipient.isNotBlank() && body.isNotBlank() &&
                secret.length >= PayloadCrypto.MIN_SECRET_LENGTH,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isEncrypting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Encrypting…")
            } else {
                Text("Encrypt & Queue Message")
            }
        }
    }
}

@Composable
private fun RelayControlCard(
    relay: RelayRuntimeState,
    cloudEndpoint: String,
    onSaveEndpoint: (String) -> Boolean,
    onStartLocalHotspot: () -> Unit,
    onStartManualHotspot: (String, String) -> Unit,
    onStopRelay: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
) {
    var endpointDraft by rememberSaveable(cloudEndpoint) { mutableStateOf(cloudEndpoint) }
    var manualMode by rememberSaveable { mutableStateOf(false) }
    var manualSsid by rememberSaveable { mutableStateOf("") }
    var manualPass by remember { mutableStateOf("") }
    val running = relay.isActiveOrStarting

    SectionCard(title = "2 · Relay through a nearby phone") {
        OutlinedTextField(
            value = endpointDraft,
            onValueChange = { endpointDraft = it.trim() },
            label = { Text("Cloud endpoint (helper uploads here)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            trailingIcon = {
                if (endpointDraft != cloudEndpoint) {
                    TextButton(onClick = { onSaveEndpoint(endpointDraft) }) { Text("Save") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use Portable Hotspot (manual)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Fallback if the device blocks LocalOnlyHotspot",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = manualMode, onCheckedChange = { manualMode = it }, enabled = !running)
        }

        if (manualMode && !running) {
            Text(
                "Turn on Portable Hotspot in system settings, then enter the same name and password here so " +
                    "the QR code matches.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onOpenHotspotSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Open hotspot settings")
            }
            OutlinedTextField(
                value = manualSsid,
                onValueChange = { manualSsid = it.take(32) },
                label = { Text("Hotspot name (SSID)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = manualPass,
                onValueChange = { manualPass = it.take(63) },
                label = { Text("Hotspot password (blank = open)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (running) {
            OutlinedButton(onClick = onStopRelay, modifier = Modifier.fillMaxWidth()) { Text("Stop Relay Hotspot") }
        } else {
            Button(
                onClick = {
                    if (manualMode) onStartManualHotspot(manualSsid, manualPass) else onStartLocalHotspot()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Start Relay Hotspot") }
        }

        RelayStatusLine(relay)

        relay.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (relay.hotspot == null && relay.serverRunning) {
                Text(
                    "The local server is still running. Stop the relay, enable Portable Hotspot and switch to manual mode.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        relay.hotspot?.let { hotspot ->
            HorizontalDivider()
            LabeledValue("Wi-Fi name (SSID)", hotspot.ssid)
            LabeledValue("Password", hotspot.passphrase.ifEmpty { "(open network)" })
            LabeledValue(
                "Security / mode",
                "${hotspot.security.label} · " + if (hotspot.mode == HotspotMode.LOCAL_ONLY) "LocalOnlyHotspot" else "Portable Hotspot",
            )
            Text("Step 1 — helper scans to join Wi-Fi", style = MaterialTheme.typography.titleSmall)
            QrCodeImage(
                content = WifiQr.build(hotspot),
                contentDescription = "Wi-Fi QR code for ${hotspot.ssid}",
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            val urls = relay.localUrls
            if (urls.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Waiting for the hotspot's IP address…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text("Step 2 — helper opens the relay page", style = MaterialTheme.typography.titleSmall)
                QrCodeImage(
                    content = urls.first(),
                    contentDescription = "Relay page URL QR code",
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                LabeledValue(if (urls.size == 1) "Relay URL" else "Relay URLs (try in order)", urls.joinToString("\n"))
            }
        }
    }
}

@Composable
private fun RelayStatusLine(relay: RelayRuntimeState) {
    val (label, color) = when (relay.phase) {
        RelayPhase.STOPPED -> "Relay stopped" to MaterialTheme.colorScheme.onSurfaceVariant
        RelayPhase.STARTING -> "Starting hotspot and server…" to StatusPending
        RelayPhase.ACTIVE -> "Relay active on port ${relay.serverPort}" to StatusDelivered
        RelayPhase.ERROR -> "Relay needs attention" to MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(50), color = color, modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun MessageRow(message: MessageEntity) {
    val time = remember(message.createdAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(message.createdAt))
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("To: ${message.recipientId}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "#${message.id.take(8)} · $time · ${message.plaintextLength} chars, encrypted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusBadge(message.status.label, statusColor(message.status))
            }
            StatusTimeline(message.status)
            if (message.relayAttempts > 0 && message.status != MessageStatus.DELIVERED) {
                Text("Handed to helpers ${message.relayAttempts}×", style = MaterialTheme.typography.bodySmall)
            }
            message.deliveredVia?.let {
                Text("Delivered via $it", style = MaterialTheme.typography.bodySmall, color = StatusDelivered)
            }
            message.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun StatusTimeline(status: MessageStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MessageStatus.entries.forEachIndexed { index, step ->
            val reached = step.ordinal <= status.ordinal
            Text(
                step.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (step == status) FontWeight.Bold else FontWeight.Normal,
                color = if (reached) statusColor(step) else MaterialTheme.colorScheme.outline,
            )
            if (index < MessageStatus.entries.lastIndex) {
                Text(" → ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun ActivityLogCard(events: List<RelayEvent>) {
    val formatter = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    SectionCard(title = "Activity") {
        if (events.isEmpty()) {
            Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            events.take(25).forEach { event ->
                Text(
                    "${formatter.format(Date(event.timestamp))}  ${event.text}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.14f)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

private fun statusColor(status: MessageStatus): Color = when (status) {
    MessageStatus.PENDING -> StatusPending
    MessageStatus.RELAYED_VIA_HELPER -> StatusRelayed
    MessageStatus.DELIVERED -> StatusDelivered
}
