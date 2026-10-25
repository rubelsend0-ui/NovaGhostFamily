@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.novaghost.family.ui

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.novaghost.family.admin.DeviceControl
import com.novaghost.family.core.*
import com.novaghost.family.data.InboxEntity
import com.novaghost.family.data.UsageCacheEntity
import com.novaghost.family.data.UsageRepo
import com.novaghost.family.nova
import com.novaghost.family.work.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class UiState(
    val configured: Boolean,
    val booting: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    val signedIn: Boolean = false,
    val role: Role? = null,
    val selfManaged: Boolean = false,
    val room: RoomInfo? = null,
    val myDeviceId: String? = null,
    val devices: List<DeviceInfo> = emptyList(),
    val selected: String? = null,
    val policy: Policy = Policy(),
    val usageToday: DailyUsage? = null,
    val usageWeek: List<DailyUsage> = emptyList(),
    val usageSource: String = "none", // device | cloud | cached | none
    val reminders: List<ReminderItem> = emptyList(),
    val audit: List<AuditItem> = emptyList(),
    val inbox: List<InboxEntity> = emptyList(),
    val consent: Consent = Consent(),
    val invite: Invite? = null,
    val perms: LocalPerms = LocalPerms(),
    val online: Boolean = true,
    val share: ShareInfo = ShareInfo("idle", null)
) {
    val selectedDevice: DeviceInfo? get() = devices.firstOrNull { it.id == selected }
}

data class Look(
    val accent: Accent = Accent.GHOST, val reduceMotion: Boolean = false, val largeText: Boolean = false,
    val highContrast: Boolean = false, val notifyLimits: Boolean = true, val notifyBedtime: Boolean = true
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val nova = app.nova
    private val cloud = nova.cloud
    private val prefs = nova.prefs
    private val usage = UsageRepo(app)
    private val control = DeviceControl(app)

    private val _ui = MutableStateFlow(UiState(configured = cloud.configured))
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _look = MutableStateFlow(readLook())
    val look: StateFlow<Look> = _look.asStateFlow()

    private val jobs = mutableListOf<Job>()
    private val selected = MutableStateFlow<String?>(null)

    private fun readLook() = Look(
        Accent.values().firstOrNull { it.name == prefs.accent } ?: Accent.GHOST,
        prefs.reduceMotion, prefs.largeText, prefs.highContrast, prefs.notifyLimits, prefs.notifyBedtime
    )

    init {
        viewModelScope.launch { nova.network.online.collect { o -> _ui.update { it.copy(online = o) } } }
        boot()
    }

    // ---------- bootstrap ----------
    fun boot() {
        viewModelScope.launch {
            _ui.update { it.copy(booting = true) }
            try {
                if (cloud.configured && cloud.uid != null) enterCloud()
                else if (prefs.selfManaged) enterLocal()
            } catch (t: Throwable) { fail(t) }
            _ui.update { it.copy(booting = false) }
        }
    }

    private suspend fun enterCloud() {
        val role = cloud.role()
        if (role == null) { cloud.signOut(); _ui.update { it.copy(message = "Account has no role. Please sign up again.") }; return }
        val found = cloud.findRoom(role)
        val room = if (role == Role.CHILD && prefs.deviceId == null) null else found
        prefs.roomId = room?.id
        _ui.update { it.copy(signedIn = true, role = role, room = room, selfManaged = false,
            myDeviceId = prefs.deviceId, consent = prefs.consent, policy = prefs.policy) }
        if (room != null) startCollectors(role, room)
        refreshLocal()
    }

    private fun enterLocal() {
        prefs.selfManaged = true
        _ui.update { it.copy(signedIn = false, role = Role.CHILD, selfManaged = true, policy = prefs.policy, consent = prefs.consent) }
        jobs.forEach { it.cancel() }; jobs.clear()
        jobs += viewModelScope.launch { nova.db.dao().inbox().collect { l -> _ui.update { it.copy(inbox = l) } } }
        SyncScheduler.ensure(getApplication<Application>())
        refreshLocal()
    }

    private fun startCollectors(role: Role, room: RoomInfo) {
        jobs.forEach { it.cancel() }; jobs.clear()
        if (role == Role.PARENT) {
            jobs += viewModelScope.launch {
                cloud.devices(room.id).catch { fail(it) }.collect { list ->
                    val sel = _ui.value.selected?.takeIf { id -> list.any { it.id == id } } ?: list.firstOrNull()?.id
                    _ui.update { it.copy(devices = list, selected = sel) }
                    selected.value = sel
                }
            }
            jobs += viewModelScope.launch {
                selected.filterNotNull().flatMapLatest { cloud.policy(room.id, it) }.catch { fail(it) }
                    .collect { p -> _ui.update { it.copy(policy = p) } }
            }
            jobs += viewModelScope.launch {
                selected.filterNotNull().flatMapLatest { cloud.usage(room.id, it) }.catch { fail(it) }.collect { l ->
                    val sorted = l.sortedBy { it.date }
                    _ui.update { it.copy(usageWeek = sorted, usageToday = sorted.firstOrNull { u -> u.date == LocalDate.now().toString() },
                        usageSource = if (sorted.isEmpty()) "none" else "cloud") }
                }
            }
            jobs += viewModelScope.launch {
                selected.filterNotNull().flatMapLatest { cloud.shareStatus(room.id, it) }.catch { fail(it) }
                    .collect { s -> _ui.update { it.copy(share = s) } }
            }
            jobs += viewModelScope.launch { cloud.reminders(room.id).catch { fail(it) }.collect { l -> _ui.update { it.copy(reminders = l) } } }
            jobs += viewModelScope.launch { cloud.audit(room.id).catch { fail(it) }.collect { l -> _ui.update { it.copy(audit = l) } } }
        } else {
            val dev = prefs.deviceId ?: return
            jobs += viewModelScope.launch {
                cloud.policy(room.id, dev).catch { fail(it) }.collect { p -> prefs.policy = p; _ui.update { it.copy(policy = p) } }
            }
            jobs += viewModelScope.launch {
                cloud.shareStatus(room.id, dev).catch { fail(it) }.collect { s -> _ui.update { it.copy(share = s) } }
            }
            jobs += viewModelScope.launch { nova.db.dao().inbox().collect { l -> _ui.update { it.copy(inbox = l) } } }
            SyncScheduler.ensure(getApplication<Application>())
        }
    }

    private fun fail(t: Throwable) {
        _ui.update { it.copy(busy = false, message = t.localizedMessage ?: "Unexpected error") }
    }

    fun clearMessage() { _ui.update { it.copy(message = null) } }

    private fun launchBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            try { block() } catch (t: Throwable) { fail(t) }
            _ui.update { it.copy(busy = false) }
        }
    }

    // ---------- local device state ----------
    fun refreshLocal() {
        viewModelScope.launch(Dispatchers.IO) {
            val nm = NotificationManagerCompat.from(getApplication<Application>())
            val perms = LocalPerms(usage.hasAccess(), nm.areNotificationsEnabled(), control.isAdmin(), control.isDeviceOwner())
            _ui.update { it.copy(perms = perms) }
            if (_ui.value.role != Role.CHILD) return@launch
            if (perms.usageAccess) {
                val week = usage.week()
                val today = week.lastOrNull { it.date == LocalDate.now().toString() }
                if (today != null) nova.db.dao().putUsage(UsageCacheEntity("local|${today.date}", today.toJson(), System.currentTimeMillis()))
                _ui.update { it.copy(usageToday = today, usageWeek = week, usageSource = "device") }
            } else {
                val cached = nova.db.dao().latestUsage()?.let { DailyUsage.fromJson(it.json) }
                _ui.update { it.copy(usageToday = cached, usageWeek = emptyList(), usageSource = if (cached != null) "cached" else "none") }
            }
        }
    }

    // ---------- auth ----------
    fun signIn(email: String, pw: String) = launchBusy { cloud.signIn(email.trim(), pw); enterCloud() }
    fun signUp(email: String, pw: String, role: Role) = launchBusy { cloud.signUp(email.trim(), pw, role); enterCloud() }
    fun continueLocal() { enterLocal() }

    fun signOut() {
        jobs.forEach { it.cancel() }; jobs.clear()
        cloud.signOut()
        prefs.selfManaged = false
        prefs.clearPairing()
        SyncScheduler.cancel(getApplication<Application>())
        _ui.value = UiState(configured = cloud.configured, booting = false, online = _ui.value.online)
    }

    // ---------- rooms & pairing ----------
    fun createRoom(name: String) = launchBusy { cloud.createRoom(name.trim()); enterCloud() }
    fun createInvite() = launchBusy { val r = _ui.value.room ?: return@launchBusy; _ui.update { it.copy(invite = cloud.createInvite(r.id)) } }

    fun join(rawCode: String, deviceName: String) = launchBusy {
        val code = InvitePayload.parse(rawCode) ?: error("That is not a valid invitation code")
        val res = cloud.redeemInvite(code, deviceName.trim().ifBlank { "Child device" })
        prefs.roomId = res.roomId
        prefs.deviceId = res.deviceId
        prefs.consent = Consent() // nothing is shared until the child opts in
        runCatching { cloud.saveToken(res.roomId, res.deviceId, cloud.fcmToken()) }
        enterCloud()
        SyncScheduler.ensure(getApplication<Application>())
    }

    fun unlinkDevice(deviceId: String) = launchBusy {
        val r = _ui.value.room ?: return@launchBusy
        cloud.unlinkDevice(r.id, deviceId)
        _ui.update { it.copy(message = "Device unlinked and its data deleted") }
    }

    fun unlinkSelf() = launchBusy {
        val r = _ui.value.room ?: return@launchBusy
        val d = prefs.deviceId ?: return@launchBusy
        cloud.unlinkDevice(r.id, d)
        prefs.clearPairing()
        SyncScheduler.cancel(getApplication<Application>())
        enterCloud()
    }

    fun deleteRoom() = launchBusy {
        val r = _ui.value.room ?: return@launchBusy
        cloud.deleteRoom(r.id)
        prefs.clearPairing()
        enterCloud()
    }

    // ---------- policy / reminders ----------
    fun selectDevice(id: String) { selected.value = id; _ui.update { it.copy(selected = id) } }

    fun savePolicy(p: Policy) = launchBusy {
        val s = _ui.value
        if (s.role == Role.PARENT) {
            val r = s.room ?: return@launchBusy
            val d = s.selected ?: error("Select a device first")
            cloud.setPolicy(r.id, d, p)
        } else if (s.selfManaged) {
            prefs.policy = p
            _ui.update { it.copy(policy = p) }
        }
        _ui.update { it.copy(message = "Rules saved") }
    }

    fun sendReminder(message: String, delayMin: Int) = launchBusy {
        val s = _ui.value
        val r = s.room ?: return@launchBusy
        val d = s.selectedDevice ?: error("Select a device first")
        if (!d.consent.reminders) error("${d.name} has not allowed reminders yet")
        cloud.sendReminder(r.id, d.id, d.childUid, message.trim(), System.currentTimeMillis() + delayMin * 60_000L)
        _ui.update { it.copy(message = if (delayMin == 0) "Reminder sent" else "Reminder scheduled") }
    }

    // ---------- consent (child only) ----------
    fun setConsent(c: Consent) {
        prefs.consent = c
        _ui.update { it.copy(consent = c) }
        val r = prefs.roomId; val d = prefs.deviceId
        if (cloud.configured && r != null && d != null) {
            val (bat, chg) = usage.battery()
            cloud.heartbeat(r, d, bat, chg, c)
            cloud.audit(r, "consent.update", d)
        }
    }

    // ---------- screen share ----------
    fun requestShare() = launchBusy {
        val s = _ui.value
        val r = s.room ?: return@launchBusy
        val d = s.selectedDevice ?: error("Select a device first")
        if (!d.consent.screenShare) error("${d.name} has not allowed screen sharing")
        cloud.requestShare(r.id, d.id)
    }

    fun declineShare() {
        val r = prefs.roomId; val d = prefs.deviceId
        if (r != null && d != null) cloud.setShareStatus(r, d, "declined")
    }

    fun shareRef(deviceId: String?) = _ui.value.room?.let { r -> deviceId?.let { cloud.shareRef(r.id, it) } }

    fun isOwner(): Boolean = _ui.value.room?.ownerUid == cloud.uid

    // ---------- device management ----------
    fun lockNow() { if (!control.lockNow()) _ui.update { it.copy(message = "Device admin is not active") } }
    fun deactivateAdmin() { control.deactivate(); refreshLocal() }
    fun adminIntent() = control.activationIntent()

    // ---------- look & privacy ----------
    fun setAccent(a: Accent) { prefs.accent = a.name; _look.update { it.copy(accent = a) } }
    fun setReduceMotion(v: Boolean) { prefs.reduceMotion = v; _look.update { it.copy(reduceMotion = v) } }
    fun setLargeText(v: Boolean) { prefs.largeText = v; _look.update { it.copy(largeText = v) } }
    fun setHighContrast(v: Boolean) { prefs.highContrast = v; _look.update { it.copy(highContrast = v) } }
    fun setNotifyLimits(v: Boolean) { prefs.notifyLimits = v; _look.update { it.copy(notifyLimits = v) } }
    fun setNotifyBedtime(v: Boolean) { prefs.notifyBedtime = v; _look.update { it.copy(notifyBedtime = v) } }

    fun clearLocalData() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { nova.db.clearAllTables() }
            _ui.update { it.copy(usageToday = null, usageWeek = emptyList(), inbox = emptyList(), usageSource = "none", message = "Local data cleared") }
        }
    }

    fun exitLocalMode() {
        prefs.selfManaged = false
        SyncScheduler.cancel(getApplication<Application>())
        _ui.value = UiState(configured = cloud.configured, booting = false, online = _ui.value.online)
    }
}
