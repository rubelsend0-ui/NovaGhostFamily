@file:OptIn(ExperimentalMaterial3Api::class)

package com.novaghost.family.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.novaghost.family.core.InvitePayload
import com.novaghost.family.core.Role

enum class Tab(val label: String) { CORE("Core"), FAMILY("Family"), RULES("Rules"), ALERTS("Alerts"), CONSENT("Consent"), INBOX("Inbox"), MORE("More") }

@Composable
fun NovaRoot(vm: AppViewModel) {
    val s by vm.ui.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(NovaColors.Midnight, NovaColors.Deep)))) {
        when {
            s.booting -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingState("Waking the ghost…") }
            !s.signedIn && !s.selfManaged -> AuthScreen(vm, s)
            s.role == Role.PARENT && s.room == null -> CreateRoomScreen(vm, s)
            s.role == Role.CHILD && !s.selfManaged && s.room == null -> JoinScreen(vm, s)
            else -> Shell(vm, s)
        }
    }
}

@Composable
private fun Shell(vm: AppViewModel, s: UiState) {
    val parent = s.role == Role.PARENT
    val tabs = if (parent) listOf(Tab.CORE, Tab.FAMILY, Tab.RULES, Tab.ALERTS, Tab.MORE)
    else listOf(Tab.CORE, Tab.RULES, Tab.CONSENT, Tab.INBOX, Tab.MORE)
    var tab by rememberSaveable { mutableStateOf(Tab.CORE) }
    var overlay by rememberSaveable { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshLocal() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.clearMessage() } }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar(containerColor = NovaColors.Panel.copy(alpha = 0.92f)) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && overlay == null, onClick = { tab = t; overlay = null },
                        icon = { Icon(tabIcon(t), t.label) }, label = { Text(t.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = NovaColors.Midnight,
                            indicatorColor = MaterialTheme.colorScheme.primary, unselectedIconColor = NovaColors.Muted,
                            selectedTextColor = MaterialTheme.colorScheme.primary, unselectedTextColor = NovaColors.Muted)
                    )
                }
            }
        },
        floatingActionButton = {
            if (overlay != "command") ExtendedFloatingActionButton(
                onClick = { overlay = "command" }, containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = NovaColors.Midnight
            ) { Icon(Icons.Filled.Search, null); Spacer(Modifier.width(6.dp)); Text("GHOST", style = MaterialTheme.typography.labelLarge) }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!s.online) OfflineBanner()
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("NOVA GHOST", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(if (parent) "PARENT" else if (s.selfManaged) "LOCAL" else "CHILD", style = MaterialTheme.typography.labelSmall, color = NovaColors.Muted)
            }
            Box(Modifier.weight(1f)) {
                val nav = { t: Tab -> tab = t; overlay = null }
                val open = { o: String -> overlay = o }
                val back = { overlay = null }
                when (overlay) {
                    "command" -> CommandScreen(vm, s, nav, open, back)
                    "analytics" -> AnalyticsScreen(s, back)
                    "audit" -> AuditScreen(s, back)
                    "settings" -> SettingsScreen(vm, s, back)
                    "share" -> ShareScreen(vm, s, back)
                    "perms" -> ConsentScreen(vm, s)
                    else -> when (tab) {
                        Tab.CORE -> CoreScreen(vm, s, open)
                        Tab.FAMILY -> FamilyScreen(vm, s)
                        Tab.RULES -> RulesScreen(vm, s)
                        Tab.ALERTS -> AlertsScreen(vm, s)
                        Tab.CONSENT -> ConsentScreen(vm, s)
                        Tab.INBOX -> InboxScreen(s)
                        Tab.MORE -> MoreScreen(vm, s, open)
                    }
                }
            }
        }
    }
}

private fun tabIcon(t: Tab) = when (t) {
    Tab.CORE -> Icons.Filled.Home
    Tab.FAMILY -> Icons.Filled.Face
    Tab.RULES -> Icons.Filled.Lock
    Tab.ALERTS -> Icons.Filled.Send
    Tab.CONSENT -> Icons.Filled.Check
    Tab.INBOX -> Icons.Filled.Notifications
    Tab.MORE -> Icons.Filled.List
}

@Composable
fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        content()
        Spacer(Modifier.height(88.dp))
    }
}

@Composable
private fun AuthScreen(vm: AppViewModel, s: UiState) {
    var email by rememberSaveable { mutableStateOf("") }
    var pw by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Role.PARENT) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.clearMessage() } }
    Scaffold(containerColor = Color.Transparent, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Spacer(Modifier.height(24.dp))
            GhostCore(0.72f, "NOVA", "GHOST FAMILY", modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally))
            Muted("Transparent, consent-based family screen-time tools. Nothing is hidden: every feature is visible to the child.")
            if (!s.configured) {
                NotConfiguredCard()
                NeonButton("CONTINUE IN LOCAL MODE", { vm.continueLocal() }, Modifier.fillMaxWidth())
                Muted("Local mode manages this one device: usage stats, your own limits, and reminders from your own rules. No account, no cloud.")
            } else {
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(pw, { pw = it }, label = { Text("Password (min 8 chars)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(role == Role.PARENT, { role = Role.PARENT }, label = { Text("I'm a parent") })
                    FilterChip(role == Role.CHILD, { role = Role.CHILD }, label = { Text("This is a child device") })
                }
                if (s.busy) LoadingState("Contacting server…") else {
                    NeonButton("SIGN IN", { vm.signIn(email, pw) }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && pw.length >= 8)
                    NeonButton("CREATE ACCOUNT", { vm.signUp(email, pw, role) }, Modifier.fillMaxWidth(), enabled = email.isNotBlank() && pw.length >= 8, outlined = true)
                }
            }
        }
    }
}

@Composable
private fun CreateRoomScreen(vm: AppViewModel, s: UiState) {
    var name by rememberSaveable { mutableStateOf("") }
    Page {
        Spacer(Modifier.height(24.dp))
        Text("Create your family room", style = MaterialTheme.typography.headlineMedium)
        Muted("You become the verified owner. Child devices join only with an invitation you generate, and each child chooses what to share.")
        OutlinedTextField(name, { name = it }, label = { Text("Room name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (s.busy) LoadingState() else NeonButton("CREATE ROOM", { vm.createRoom(name) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
        NeonButton("SIGN OUT", { vm.signOut() }, Modifier.fillMaxWidth(), outlined = true)
    }
}

@Composable
private fun JoinScreen(vm: AppViewModel, s: UiState) {
    val ctx = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf(Build.MODEL ?: "Child device") }
    Page {
        Spacer(Modifier.height(16.dp))
        Text("Join a family room", style = MaterialTheme.typography.headlineMedium)
        Muted("Ask a parent for an invitation QR or 8-character code. Codes expire after 15 minutes and work once.")
        GlassCard {
            SectionTitle("What linking means")
            Muted("• Your parent can see this device's name, battery and last connection.")
            Muted("• Usage reports, reminders, downtime and screen sharing stay OFF until YOU switch them on in the Consent tab.")
            Muted("• Screen sharing always shows a notification and asks Android for permission each time.")
            Muted("• You can unlink and delete this device's data at any time.")
        }
        OutlinedTextField(name, { name = it }, label = { Text("Device name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it }, label = { Text("Invitation code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        NeonButton("SCAN QR", {
            GmsBarcodeScanning.getClient(ctx).startScan()
                .addOnSuccessListener { b -> b.rawValue?.let { code = InvitePayload.parse(it) ?: it } }
        }, Modifier.fillMaxWidth(), outlined = true)
        if (s.busy) LoadingState("Linking…") else NeonButton("LINK THIS DEVICE", { vm.join(code, name) }, Modifier.fillMaxWidth(), enabled = code.isNotBlank())
        NeonButton("SIGN OUT", { vm.signOut() }, Modifier.fillMaxWidth(), outlined = true)
    }
}
