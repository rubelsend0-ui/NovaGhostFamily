@file:OptIn(ExperimentalMaterial3Api::class)

package com.novaghost.family.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.novaghost.family.core.*
import com.novaghost.family.share.ScreenViewer
import org.webrtc.SurfaceViewRenderer

@Composable
private fun Header(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack) { Icon(Icons.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
fun MoreScreen(vm: AppViewModel, s: UiState, open: (String) -> Unit) {
    val parent = s.role == Role.PARENT
    @Composable fun Item(title: String, body: String, key: String) {
        GlassCard(Modifier.clickable { open(key) }) { Text(title, style = MaterialTheme.typography.titleMedium); Muted(body) }
    }
    Page {
        Text("More", style = MaterialTheme.typography.headlineMedium)
        Item("Weekly analytics & timeline", "Trends, averages and a family activity feed.", "analytics")
        if (parent) {
            Item("Room permissions", "What each child device has agreed to share.", "perms")
            Item("Live screen view", "Request an optional, consent-based live view.", "share")
            Item("Audit log", "Append-only record of sensitive actions.", "audit")
        }
        Item("Settings & privacy", "Theme, accessibility, notifications, data deletion.", "settings")
    }
}

@Composable
fun CommandScreen(vm: AppViewModel, s: UiState, nav: (Tab) -> Unit, open: (String) -> Unit, back: () -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val parent = s.role == Role.PARENT
    class Cmd(val title: String, val hint: String, val run: () -> Unit)
    val cmds = buildList {
        add(Cmd("Go to Core", "Dashboard") { nav(Tab.CORE) })
        add(Cmd("Go to Rules", "Limits & schedules") { nav(Tab.RULES) })
        add(Cmd("Refresh usage", "Re-read this device") { vm.refreshLocal(); back() })
        add(Cmd("Weekly analytics", "Trends") { open("analytics") })
        add(Cmd("Settings", "Theme & privacy") { open("settings") })
        if (parent) {
            add(Cmd("Go to Family", "Devices & invites") { nav(Tab.FAMILY) })
            add(Cmd("Go to Alerts", "Send reminders") { nav(Tab.ALERTS) })
            add(Cmd("Room permissions", "What children share") { open("perms") })
            add(Cmd("Request screen view", "Child must accept") { open("share") })
            add(Cmd("Quick: take a break", "Send now") { vm.sendReminder("Stop gaming and take a break", 0); back() })
            add(Cmd("Quick: study time", "Send now") { vm.sendReminder("Study time", 0); back() })
            add(Cmd("Quick: time for bed", "Send now") { vm.sendReminder("Time for bed", 0); back() })
        } else {
            add(Cmd("Consent & permissions", "What you share") { nav(if (s.selfManaged) Tab.CONSENT else Tab.CONSENT) })
            add(Cmd("Lock screen now", "Needs Device Admin") { vm.lockNow() })
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header("Ghost Command", back)
        OutlinedTextField(
            value = q,
            onValueChange = { newValue -> q = newValue },
            label = { Text("Type a command…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Page {
            cmds.filter { command ->
                command.title.contains(q, ignoreCase = true) ||
                    command.hint.contains(q, ignoreCase = true)
            }.forEach { command ->
                GlassCard(Modifier.clickable { command.run() }) {
                    Text(command.title, style = MaterialTheme.typography.titleMedium)
                    Muted(command.hint)
                }
            }
        }
    }
}

@Composable
fun AnalyticsScreen(s: UiState, back: () -> Unit) {
    val week = s.usageWeek
    Column(Modifier.fillMaxSize()) {
        Header("Weekly analytics", back)
        Page {
            if (week.isEmpty()) EmptyState("No weekly data yet", "Weekly charts need usage data from the last days. ${if (s.role == Role.PARENT) "The child must enable usage reports." else "Grant Usage access first."}")
            else {
                val total = week.sumOf { it.totalMin }
                val peak = week.maxByOrNull { it.totalMin }
                GlassCard {
                    SectionTitle("Screen time per day")
                    BarChart(week.map { it.totalMin }, week.map { it.date.takeLast(5) })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatPill("DAILY AVG", Fmt.minutes(total / week.size), Modifier.weight(1f))
                    StatPill("PEAK", peak?.let { it.date.takeLast(5) } ?: "-", Modifier.weight(1f))
                    StatPill("GAMING", if (total == 0) "0%" else "${week.sumOf { it.gamingMin } * 100 / total}%", Modifier.weight(1f))
                }
                Muted("Days without data are not shown. Android keeps detailed usage events for a limited time only.")
            }
            SectionTitle("Family activity timeline")
            data class Ev(val at: Long, val text: String)
            val events = buildList {
                s.reminders.forEach { add(Ev(maxOf(it.createdAt, it.deliveredAt), "Reminder ${it.status}: ${it.message}")) }
                s.audit.forEach { add(Ev(it.at, it.action)) }
                s.inbox.forEach { add(Ev(it.receivedAt, "Received: ${it.message}")) }
            }.sortedByDescending { it.at }.take(40)
            if (events.isEmpty()) EmptyState("No activity yet", "Reminders, rule changes and pairing events show up here.")
            events.forEach { GlassCard { Text(it.text); Muted(Fmt.ago(it.at)) } }
        }
    }
}

@Composable
fun AuditScreen(s: UiState, back: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("Audit log", back)
        Page {
            Muted("Append-only. Entries cannot be edited or deleted by any app user.")
            if (s.audit.isEmpty()) EmptyState("Empty", "Sensitive actions will be recorded here.")
            s.audit.forEach {
                GlassCard {
                    Text(it.action, style = MaterialTheme.typography.titleMedium)
                    Muted("by ${it.actorUid.take(6)}… · ${it.target.take(24)} · ${Fmt.ago(it.at)}")
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: AppViewModel, s: UiState, back: () -> Unit) {
    val look by vm.look.collectAsState()
    var confirmUnlink by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Header("Settings & privacy", back)
        Page {
            GlassCard {
                SectionTitle("Theme")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Accent.values().forEach { a -> FilterChip(look.accent == a, { vm.setAccent(a) }, label = { Text(a.label) }) }
                }
            }
            GlassCard {
                SectionTitle("Accessibility")
                SwitchRow("Reduce motion", "Stops the Ghost Core animation.", look.reduceMotion) { vm.setReduceMotion(it) }
                SwitchRow("Large text", "Scales text up by 30%.", look.largeText) { vm.setLargeText(it) }
                SwitchRow("High contrast", "Brighter borders and secondary text.", look.highContrast) { vm.setHighContrast(it) }
            }
            if (s.role == Role.CHILD) GlassCard {
                SectionTitle("Notifications on this device")
                SwitchRow("Limit alerts", "Goal and gaming limit warnings.", look.notifyLimits) { vm.setNotifyLimits(it) }
                SwitchRow("Bedtime & study alerts", "Schedule reminders.", look.notifyBedtime) { vm.setNotifyBedtime(it) }
            }
            GlassCard {
                SectionTitle("Privacy & data")
                Muted("Local settings are stored encrypted with the Android Keystore. API keys are never stored in this app; the backend enforces access.")
                NeonButton("CLEAR LOCAL DATA", { vm.clearLocalData() }, Modifier.fillMaxWidth(), outlined = true)
                if (s.role == Role.CHILD && !s.selfManaged) {
                    NeonButton("UNLINK THIS DEVICE & DELETE ITS DATA", { confirmUnlink = true }, Modifier.fillMaxWidth(), danger = true, outlined = true)
                }
                if (s.selfManaged) NeonButton("LEAVE LOCAL MODE", { vm.exitLocalMode() }, Modifier.fillMaxWidth(), outlined = true)
                if (s.signedIn) NeonButton("SIGN OUT", { vm.signOut() }, Modifier.fillMaxWidth(), outlined = true)
            }
            if (!s.configured) NotConfiguredCard()
        }
    }
    if (confirmUnlink) AlertDialog(onDismissRequest = { confirmUnlink = false }, title = { Text("Unlink this device?") },
        text = { Text("Your parent will stop receiving data and the device's usage history on the server is deleted.") },
        confirmButton = { TextButton({ confirmUnlink = false; vm.unlinkSelf() }) { Text("UNLINK", color = NovaColors.Danger) } },
        dismissButton = { TextButton({ confirmUnlink = false }) { Text("CANCEL") } })
}

@Composable
fun ShareScreen(vm: AppViewModel, s: UiState, back: () -> Unit) {
    val dev = s.selectedDevice
    Column(Modifier.fillMaxSize()) {
        Header("Live screen view", back)
        if (dev == null) { Page { EmptyState("Select a device", "Link a child device first.") }; return@Column }
        ShareBody(vm, s, dev)
    }
}

@Composable
private fun ShareBody(vm: AppViewModel, s: UiState, dev: DeviceInfo) {
    val ctx = LocalContext.current
    val viewer = remember(dev.id) { vm.shareRef(dev.id)?.let { ScreenViewer(ctx, it) { } } }
    DisposableEffect(viewer) { viewer?.start(); onDispose { viewer?.release() } }
    Page {
        GlassCard {
            SectionTitle("Consent-based, visible, stoppable")
            Muted("${dev.name} gets a notification, must approve Android's screen-capture dialog, sees a persistent 'sharing' notification and can stop instantly. Audio and camera are never used.")
            Muted("Status: ${s.share.status.uppercase()}")
            if (!dev.consent.screenShare) Muted("⚠ ${dev.name} has not allowed screen sharing in their Consent tab.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NeonButton("REQUEST VIEW", { vm.requestShare() }, enabled = dev.consent.screenShare && s.share.status != "active" && s.share.status != "requested")
                NeonButton("STOP", { viewer?.stopRemote() }, outlined = true, danger = true, enabled = s.share.status == "active" || s.share.status == "requested")
            }
            Muted("Uses peer-to-peer WebRTC (STUN only by default). On strict networks you may need a TURN server; see README.")
        }
        if (s.share.status == "active") {
            AndroidView(factory = { c -> SurfaceViewRenderer(c).also { r -> viewer?.attach(r) } },
                modifier = Modifier.fillMaxWidth().height(460.dp))
        }
    }
}
