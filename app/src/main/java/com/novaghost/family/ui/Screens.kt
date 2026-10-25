@file:OptIn(ExperimentalMaterial3Api::class)

package com.novaghost.family.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novaghost.family.core.*
import com.novaghost.family.share.ScreenShareService

private fun usageSettings(ctx: android.content.Context) =
    ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

// ------------------------------------------------------------------ CORE

@Composable
fun CoreScreen(vm: AppViewModel, s: UiState, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val parent = s.role == Role.PARENT
    val sharing by ScreenShareService.active.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        val room = s.room?.id
        val dev = s.myDeviceId
        if (r.resultCode == Activity.RESULT_OK && d != null && room != null && dev != null) {
            ScreenShareService.start(ctx, r.resultCode, d, room, dev)
        } else vm.declineShare()
    }
    Page {
        if (!parent && sharing) {
            GlassCard {
                Text("● SCREEN IS BEING SHARED", style = MaterialTheme.typography.titleMedium, color = NovaColors.Danger)
                Muted("A parent can see your screen right now.")
                NeonButton("STOP SHARING NOW", { ScreenShareService.stop(ctx) }, Modifier.fillMaxWidth(), danger = true)
            }
        }
        if (!parent && !sharing && s.share.status == "requested" && s.consent.screenShare) {
            GlassCard {
                Text("Screen sharing requested", style = MaterialTheme.typography.titleMedium)
                Muted("A parent asked to view your screen. Android will ask you to confirm. You can stop any time.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("ALLOW", {
                        launcher.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
                    })
                    NeonButton("DECLINE", { vm.declineShare() }, outlined = true)
                }
            }
        }
        if (parent) {
            if (s.devices.isEmpty()) {
                EmptyState("No child devices linked", "Open the Family tab, create an invitation, and let your child scan it.")
                return@Page
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                s.devices.forEach { d -> FilterChip(d.id == s.selected, { vm.selectDevice(d.id) }, label = { Text(d.name) }) }
            }
            s.selectedDevice?.let { d ->
                GlassCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatPill("BATTERY", if (d.battery < 0) "?" else "${d.battery}%" + if (d.charging) "⚡" else "")
                        StatPill("LAST SEEN", Fmt.ago(d.lastSeen))
                    }
                    FreshnessChip(Fmt.freshness(d.lastSeen, System.currentTimeMillis()), "last connection ${Fmt.ago(d.lastSeen)}")
                }
            }
        }
        UsagePanel(vm, s)
    }
}

@Composable
private fun UsagePanel(vm: AppViewModel, s: UiState) {
    val ctx = LocalContext.current
    val parent = s.role == Role.PARENT
    val u = s.usageToday
    val goal = s.policy.dailyGoalMin

    if (u == null) {
        when {
            parent && s.selectedDevice?.consent?.usageReports != true ->
                StateCard(Icons.Filled.Lock, "Usage reports are off", "${s.selectedDevice?.name ?: "This device"} has not allowed usage reports. Only the child can switch this on.")
            parent -> StateCard(Icons.Filled.Info, "Waiting for first sync", "No usage received yet. The child device reports about every 15 minutes while it has a connection.")
            !s.perms.usageAccess -> PermissionState("Usage access needed",
                "Android requires you to grant 'Usage access' in Settings so this app can read screen time. It is only used on this device${if (s.selfManaged) "." else " and shared only if you enable usage reports."}",
                "OPEN USAGE ACCESS") { usageSettings(ctx) }
            else -> LoadingState("Reading usage…")
        }
        return
    }

    val fresh = when (s.usageSource) {
        "cached" -> Freshness.DELAYED
        else -> Fmt.freshness(u.updatedAt, System.currentTimeMillis())
    }
    val src = when (s.usageSource) {
        "device" -> "read from this device"
        "cloud" -> "reported by child device"
        "cached" -> "cached copy — usage access is off"
        else -> ""
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        GhostCore(if (goal > 0) u.totalMin / goal.toFloat() else 0f, Fmt.minutes(u.totalMin),
            if (goal > 0) "OF ${Fmt.minutes(goal)} GOAL" else "SCREEN TIME TODAY", alert = goal > 0 && u.totalMin >= goal)
    }
    FreshnessChip(fresh, "$src · updated ${Fmt.ago(u.updatedAt)}")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatPill("TOTAL", Fmt.minutes(u.totalMin), Modifier.weight(1f))
        StatPill("GAMING", Fmt.minutes(u.gamingMin), Modifier.weight(1f))
        StatPill("ENTERTAIN", Fmt.minutes(u.entertainmentMin), Modifier.weight(1f))
    }
    if (u.topApps.isNotEmpty()) GlassCard {
        SectionTitle("Top apps today")
        u.topApps.forEach { a ->
            Row(Modifier.fillMaxWidth()) {
                Text(a.label, Modifier.weight(1f), maxLines = 1)
                Text(Fmt.minutes(a.minutes), color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
            }
        }
        Muted("Apps under 1 minute are omitted. Launcher time is excluded.")
    }
    if (s.usageWeek.size > 1) GlassCard {
        SectionTitle("Last 7 days")
        BarChart(s.usageWeek.map { it.totalMin }, s.usageWeek.map { it.date.takeLast(5) })
    }
    if (!parent && !s.selfManaged && !s.consent.usageReports) {
        Muted("Your parent cannot see this yet. Enable usage reports in the Consent tab to share it.")
    }
}

// ------------------------------------------------------------------ FAMILY

@Composable
fun FamilyScreen(vm: AppViewModel, s: UiState) {
    var unlink by remember { mutableStateOf<DeviceInfo?>(null) }
    var wipe by remember { mutableStateOf(false) }
    Page {
        GlassCard {
            SectionTitle("Room")
            Text(s.room?.name ?: "", style = MaterialTheme.typography.titleLarge)
            Muted(if (vm.isOwner()) "Verified owner: you" else "Co-parent")
        }
        GlassCard {
            SectionTitle("Invite a child device")
            val inv = s.invite
            if (inv != null && inv.expiresAt > System.currentTimeMillis()) {
                val bmp = remember(inv.code) { qrBitmap(InvitePayload.encode(inv.code)).asImageBitmap() }
                Image(bmp, "Invitation QR code", Modifier.size(200.dp).align(Alignment.CenterHorizontally).background(Color.White).padding(8.dp))
                Text(inv.code, Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Muted("Expires in ${((inv.expiresAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)} min · one use. Anyone with this code can join, so share it only with your child.")
            } else Muted("Codes expire after 15 minutes and can be used once.")
            if (s.busy) LoadingState() else NeonButton("NEW INVITATION", { vm.createInvite() }, Modifier.fillMaxWidth())
        }
        SectionTitle("Child devices")
        if (s.devices.isEmpty()) EmptyState("Nothing linked yet", "Generate an invitation above.")
        s.devices.forEach { d ->
            GlassCard {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Muted("Battery ${if (d.battery < 0) "unknown" else "${d.battery}%"} · last seen ${Fmt.ago(d.lastSeen)}")
                Muted("Shared: " + listOf("usage" to d.consent.usageReports, "reminders" to d.consent.reminders,
                    "downtime" to d.consent.downtime, "screen" to d.consent.screenShare).joinToString(" · ") { (n, on) -> "$n ${if (on) "✓" else "✗"}" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("SELECT", { vm.selectDevice(d.id) }, outlined = true, enabled = d.id != s.selected)
                    NeonButton("UNLINK", { unlink = d }, danger = true, outlined = true)
                }
            }
        }
        if (vm.isOwner()) {
            SectionTitle("Danger zone")
            NeonButton("DELETE ROOM & ALL DATA", { wipe = true }, Modifier.fillMaxWidth(), danger = true, outlined = true)
        }
    }
    unlink?.let { d ->
        AlertDialog(onDismissRequest = { unlink = null }, title = { Text("Unlink ${d.name}?") },
            text = { Text("This removes the device from the room and deletes its usage history and reminders. This cannot be undone.") },
            confirmButton = { TextButton({ vm.unlinkDevice(d.id); unlink = null }) { Text("UNLINK", color = NovaColors.Danger) } },
            dismissButton = { TextButton({ unlink = null }) { Text("CANCEL") } })
    }
    if (wipe) AlertDialog(onDismissRequest = { wipe = false }, title = { Text("Delete this room?") },
        text = { Text("All devices, usage data, reminders and the audit log are permanently deleted from the server.") },
        confirmButton = { TextButton({ vm.deleteRoom(); wipe = false }) { Text("DELETE", color = NovaColors.Danger) } },
        dismissButton = { TextButton({ wipe = false }) { Text("CANCEL") } })
}

// ------------------------------------------------------------------ RULES

@Composable
fun RulesScreen(vm: AppViewModel, s: UiState) {
    val parent = s.role == Role.PARENT
    val editable = parent || s.selfManaged
    var d by remember(s.policy, s.selected) { mutableStateOf(s.policy) }
    fun step(v: Int, delta: Int) = (v + delta + 1440) % 1440
    Page {
        if (parent && s.selected == null) { EmptyState("Select a device", "Link a child device first."); return@Page }
        Muted(when {
            parent -> "Rules for ${s.selectedDevice?.name ?: "device"}. The child device applies them and shows alerts."
            s.selfManaged -> "Local mode: these rules apply to this device only."
            else -> "These rules were set by your parent. You can read them but not change them."
        })
        GlassCard {
            SectionTitle("Daily limits")
            Stepper("Daily goal", if (d.dailyGoalMin == 0) "OFF" else Fmt.minutes(d.dailyGoalMin),
                { d = d.copy(dailyGoalMin = (d.dailyGoalMin - 15).coerceAtLeast(0)) },
                { d = d.copy(dailyGoalMin = (d.dailyGoalMin + 15).coerceAtMost(1440)) }, editable)
            Stepper("Gaming limit", if (d.gamingLimitMin == 0) "OFF" else Fmt.minutes(d.gamingLimitMin),
                { d = d.copy(gamingLimitMin = (d.gamingLimitMin - 15).coerceAtLeast(0)) },
                { d = d.copy(gamingLimitMin = (d.gamingLimitMin + 15).coerceAtMost(1440)) }, editable)
            Muted("Alerts at 80% and 100%.")
        }
        GlassCard {
            SectionTitle("Bedtime")
            SwitchRow("Bedtime schedule", "Reminder 30 min before, and again when downtime starts.", d.bedtimeEnabled, editable) { d = d.copy(bedtimeEnabled = it) }
            if (d.bedtimeEnabled) {
                Stepper("Starts", Fmt.clock(d.bedtimeStart), { d = d.copy(bedtimeStart = step(d.bedtimeStart, -15)) }, { d = d.copy(bedtimeStart = step(d.bedtimeStart, 15)) }, editable)
                Stepper("Ends", Fmt.clock(d.bedtimeEnd), { d = d.copy(bedtimeEnd = step(d.bedtimeEnd, -15)) }, { d = d.copy(bedtimeEnd = step(d.bedtimeEnd, 15)) }, editable)
                SwitchRow("Lock screen during bedtime",
                    "Needs Device Admin activated on the child device and the child's downtime consent. Re-locks about every 15 min.",
                    d.downtimeLock, editable) { d = d.copy(downtimeLock = it) }
            }
        }
        GlassCard {
            SectionTitle("Study time")
            SwitchRow("Study reminder", "A reminder when the study block begins.", d.studyEnabled, editable) { d = d.copy(studyEnabled = it) }
            if (d.studyEnabled) {
                Stepper("Starts", Fmt.clock(d.studyStart), { d = d.copy(studyStart = step(d.studyStart, -15)) }, { d = d.copy(studyStart = step(d.studyStart, 15)) }, editable)
                Stepper("Ends", Fmt.clock(d.studyEnd), { d = d.copy(studyEnd = step(d.studyEnd, -15)) }, { d = d.copy(studyEnd = step(d.studyEnd, 15)) }, editable)
            }
        }
        GlassCard {
            SectionTitle("How enforcement works")
            Muted("Checks run about every 15 minutes (Android's WorkManager minimum) so alerts can be a few minutes late. " +
                "Android does not let ordinary apps block other apps. Hard limits (suspending games) only work if this device is set up as a managed device (Device Owner); screen lock needs Device Admin.")
            if (!parent) Muted("This device: admin ${if (s.perms.adminActive) "ACTIVE" else "off"} · device owner ${if (s.perms.deviceOwner) "YES" else "no"}")
        }
        if (editable) {
            if (s.busy) LoadingState("Saving…") else NeonButton("SAVE RULES", { vm.savePolicy(d) }, Modifier.fillMaxWidth(), enabled = d != s.policy)
        }
    }
}

// ------------------------------------------------------------------ ALERTS (parent)

@Composable
fun AlertsScreen(vm: AppViewModel, s: UiState) {
    var msg by rememberSaveable { mutableStateOf("") }
    var delay by rememberSaveable { mutableStateOf(0) }
    val dev = s.selectedDevice
    Page {
        if (dev == null) { EmptyState("Select a device", "Link a child device first."); return@Page }
        GlassCard {
            SectionTitle("Send reminder to ${dev.name}")
            if (!dev.consent.reminders) Muted("⚠ ${dev.name} has not allowed reminders yet. Sending is disabled until the child opts in.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Stop gaming and take a break", "Study time", "Time for bed").forEach { p ->
                    AssistChip(onClick = { msg = p }, label = { Text(p) })
                }
            }
            OutlinedTextField(msg, { msg = it.take(200) }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "Now", 15 to "+15 min", 30 to "+30 min", 60 to "+1 h", 120 to "+2 h").forEach { (m, l) ->
                    FilterChip(delay == m, { delay = m }, label = { Text(l) })
                }
            }
            if (s.busy) LoadingState("Sending…") else NeonButton(if (delay == 0) "SEND NOW" else "SCHEDULE",
                { vm.sendReminder(msg, delay); msg = "" }, Modifier.fillMaxWidth(), enabled = msg.isNotBlank() && dev.consent.reminders)
        }
        SectionTitle("History")
        if (s.reminders.isEmpty()) EmptyState("No reminders yet", "Sent and scheduled reminders appear here with their delivery status.")
        s.reminders.forEach { r ->
            GlassCard {
                Text(r.message)
                val c = when (r.status) { "delivered" -> NovaColors.Mint; "failed", "blocked" -> NovaColors.Danger; "sent" -> MaterialTheme.colorScheme.primary; else -> NovaColors.Warn }
                Text(r.status.uppercase() + if (r.status == "delivered") " · ${Fmt.ago(r.deliveredAt)}" else "",
                    style = MaterialTheme.typography.labelSmall, color = c)
                Muted("to ${s.devices.firstOrNull { it.id == r.deviceId }?.name ?: "device"} · scheduled ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(r.scheduledAt))}")
            }
        }
    }
}

// ------------------------------------------------------------------ CONSENT / PERMISSIONS

@Composable
fun ConsentScreen(vm: AppViewModel, s: UiState) {
    val ctx = LocalContext.current
    val parent = s.role == Role.PARENT
    val c = s.consent
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refreshLocal() }
    val adminLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.refreshLocal() }
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else ctx.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    }
    Page {
        Text("Room permissions", style = MaterialTheme.typography.headlineMedium)
        if (parent) {
            val d = s.selectedDevice
            if (d == null) { EmptyState("Select a device", "Link a child device first."); return@Page }
            Muted("What ${d.name} has chosen to share. Only the child can change these.")
            GlassCard {
                listOf(
                    Triple("Usage reports", d.consent.usageReports, "Daily app-usage totals and screen time."),
                    Triple("Reminders", d.consent.reminders, "Show your messages as notifications."),
                    Triple("Downtime", d.consent.downtime, "Allow the bedtime lock to run."),
                    Triple("Screen sharing", d.consent.screenShare, "Allow you to REQUEST live view; the child still confirms each time.")
                ).forEach { (t, on, b) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.titleMedium); Muted(b) }
                        Text(if (on) "ALLOWED" else "OFF", color = if (on) NovaColors.Mint else NovaColors.Muted, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            return@Page
        }
        if (!s.selfManaged) {
            Muted("You decide what your parent can use. Everything starts OFF.")
            GlassCard {
                SwitchRow("Usage reports", "Share daily screen time and app totals. Needs Usage access in Settings.", c.usageReports) {
                    vm.setConsent(c.copy(usageReports = it)); if (it && !s.perms.usageAccess) usageSettings(ctx)
                }
                HorizontalDivider()
                SwitchRow("Reminders", "Let your parent send you notifications.", c.reminders) {
                    vm.setConsent(c.copy(reminders = it)); if (it && !s.perms.notifications) askNotifications()
                }
                HorizontalDivider()
                SwitchRow("Downtime", "Allow the bedtime screen lock. Needs Device Admin below.", c.downtime) {
                    vm.setConsent(c.copy(downtime = it)); if (it && !s.perms.adminActive) adminLauncher.launch(vm.adminIntent())
                }
                HorizontalDivider()
                SwitchRow("Screen sharing", "Lets a parent REQUEST a live view. Android asks you each time, a notification stays visible, and you can stop instantly.", c.screenShare) {
                    vm.setConsent(c.copy(screenShare = it))
                }
            }
        }
        SectionTitle("Android permissions on this device")
        GlassCard {
            PermRow("Usage access", s.perms.usageAccess, "Required to read screen time.", "OPEN") { usageSettings(ctx) }
            PermRow("Notifications", s.perms.notifications, "Required for limit alerts and reminders.", "ALLOW") { askNotifications() }
            PermRow("Device admin", s.perms.adminActive, "Optional. Only allows locking the screen.",
                if (s.perms.adminActive) "REMOVE" else "ACTIVATE") {
                if (s.perms.adminActive) vm.deactivateAdmin() else adminLauncher.launch(vm.adminIntent())
            }
            PermRow("Device owner", s.perms.deviceOwner, "Optional, advanced. Cannot be granted by installing the APK.", null) {}
            Muted("Device Owner needs a factory-reset or freshly provisioned device (see README → Managed devices). Installing the APK never grants it.")
        }
    }
}

@Composable
private fun PermRow(title: String, ok: Boolean, body: String, action: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$title · ${if (ok) "GRANTED" else "NOT GRANTED"}", style = MaterialTheme.typography.titleMedium,
                color = if (ok) NovaColors.Mint else NovaColors.Warn, fontSize = 14.sp)
            Muted(body)
        }
        if (action != null) TextButton(onClick) { Text(action) }
    }
}

@Composable
fun InboxScreen(s: UiState) {
    Page {
        Text("Reminders", style = MaterialTheme.typography.headlineMedium)
        if (s.inbox.isEmpty()) EmptyState("Nothing here", "Reminders from your parent will be listed here.")
        s.inbox.forEach {
            GlassCard { Text(it.message); Muted("${it.source} · ${Fmt.ago(it.receivedAt)}") }
        }
    }
}
