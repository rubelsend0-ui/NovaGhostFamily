package com.novaghost.family.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import com.novaghost.family.core.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * Firebase access. Every method requires [configured]; callers must check it first.
 * Authorization is enforced server-side by firestore.rules and Cloud Functions, never by this client.
 */
class Cloud(private val ctx: Context) {
    val configured: Boolean get() = FirebaseApp.getApps(ctx).isNotEmpty()
    private val auth get() = FirebaseAuth.getInstance()
    private val fs get() = FirebaseFirestore.getInstance()
    private val fn get() = FirebaseFunctions.getInstance()
    val uid: String? get() = if (configured) auth.currentUser?.uid else null

    private fun room(id: String) = fs.collection("rooms").document(id)
    fun shareRef(roomId: String, deviceId: String): DocumentReference =
        room(roomId).collection("shares").document(deviceId)

    // ---- auth ----
    suspend fun signIn(email: String, pw: String) { auth.signInWithEmailAndPassword(email, pw).await() }

    suspend fun signUp(email: String, pw: String, role: Role) {
        val r = auth.createUserWithEmailAndPassword(email, pw).await()
        val id = r.user?.uid ?: error("Account was not created")
        fs.collection("users").document(id)
            .set(mapOf("role" to role.name, "createdAt" to FieldValue.serverTimestamp())).await()
    }

    fun signOut() { if (configured) auth.signOut() }

    suspend fun role(): Role? {
        val u = uid ?: return null
        val s = fs.collection("users").document(u).get().await().getString("role") ?: return null
        return runCatching { Role.valueOf(s) }.getOrNull()
    }

    // ---- rooms ----
    suspend fun createRoom(name: String): String {
        val u = uid ?: error("Not signed in")
        val ref = fs.collection("rooms").document()
        ref.set(mapOf(
            "name" to name, "ownerUid" to u, "parents" to listOf(u),
            "children" to emptyList<String>(), "createdAt" to FieldValue.serverTimestamp()
        )).await()
        audit(ref.id, "room.create", name)
        return ref.id
    }

    suspend fun findRoom(role: Role): RoomInfo? {
        val u = uid ?: return null
        val field = if (role == Role.PARENT) "parents" else "children"
        val q = fs.collection("rooms").whereArrayContains(field, u).limit(1).get().await()
        val d = q.documents.firstOrNull() ?: return null
        return RoomInfo(d.id, d.getString("name") ?: "Room", d.getString("ownerUid") ?: "")
    }

    private suspend fun call(name: String, data: Map<String, Any?>): Map<String, Any?> {
        val res = fn.getHttpsCallable(name).call(data).await()
        @Suppress("UNCHECKED_CAST")
        return (res.data as? Map<String, Any?>) ?: emptyMap()
    }

    suspend fun createInvite(roomId: String): Invite {
        val m = call("createInvite", mapOf("roomId" to roomId, "ttlMinutes" to 15))
        return Invite(m["code"] as String, (m["expiresAt"] as Number).toLong())
    }

    suspend fun redeemInvite(code: String, deviceName: String): JoinResult {
        val m = call("redeemInvite", mapOf("code" to code, "deviceName" to deviceName))
        return JoinResult(m["roomId"] as String, m["deviceId"] as String, (m["roomName"] as? String) ?: "Room")
    }

    suspend fun unlinkDevice(roomId: String, deviceId: String) { call("unlinkDevice", mapOf("roomId" to roomId, "deviceId" to deviceId)) }
    suspend fun deleteRoom(roomId: String) { call("deleteRoom", mapOf("roomId" to roomId)) }

    suspend fun fcmToken(): String = FirebaseMessaging.getInstance().token.await()

    suspend fun saveToken(roomId: String, deviceId: String, token: String) {
        room(roomId).collection("tokens").document(deviceId)
            .set(mapOf("token" to token, "updatedAt" to FieldValue.serverTimestamp())).await()
    }

    // ---- flows ----
    private fun Query.snaps(): Flow<QuerySnapshot> = callbackFlow {
        val reg = addSnapshotListener { s, e -> if (e != null) close(e) else if (s != null) trySend(s) }
        awaitClose { reg.remove() }
    }

    private fun DocumentReference.snaps(): Flow<DocumentSnapshot> = callbackFlow {
        val reg = addSnapshotListener { s, e -> if (e != null) close(e) else if (s != null) trySend(s) }
        awaitClose { reg.remove() }
    }

    private fun DocumentSnapshot.ts(f: String): Long = getTimestamp(f)?.toDate()?.time ?: 0L

    fun devices(roomId: String): Flow<List<DeviceInfo>> =
        room(roomId).collection("devices").snaps().map { q ->
            q.documents.map { d ->
                DeviceInfo(d.id, d.getString("name") ?: "Device", d.getString("childUid") ?: "",
                    (d.getLong("battery") ?: -1L).toInt(), d.getBoolean("charging") ?: false, d.ts("lastSeen"),
                    Consent.fromMap(d.get("consent") as? Map<String, Any?>))
            }
        }

    fun policy(roomId: String, deviceId: String): Flow<Policy> =
        room(roomId).collection("policies").document(deviceId).snaps().map { Policy.fromMap(it.data) }

    suspend fun fetchPolicy(roomId: String, deviceId: String): Policy? {
        val d = room(roomId).collection("policies").document(deviceId).get().await()
        return if (d.exists()) Policy.fromMap(d.data) else null
    }

    suspend fun setPolicy(roomId: String, deviceId: String, p: Policy) {
        room(roomId).collection("policies").document(deviceId).set(p.toMap()).await()
        audit(roomId, "policy.update", deviceId)
    }

    fun usage(roomId: String, deviceId: String): Flow<List<DailyUsage>> =
        room(roomId).collection("usage").whereEqualTo("deviceId", deviceId)
            .orderBy("date", Query.Direction.DESCENDING).limit(7).snaps().map { q ->
                q.documents.map { d ->
                    @Suppress("UNCHECKED_CAST")
                    val apps = (d.get("topApps") as? List<Map<String, Any?>> ?: emptyList()).map {
                        AppUsage(it["pkg"] as? String ?: "", it["label"] as? String ?: "",
                            (it["min"] as? Number)?.toInt() ?: 0, it["cat"] as? String ?: "other")
                    }
                    DailyUsage(d.getString("date") ?: "", (d.getLong("totalMin") ?: 0).toInt(),
                        (d.getLong("gamingMin") ?: 0).toInt(), (d.getLong("entertainmentMin") ?: 0).toInt(),
                        apps, d.ts("updatedAt"))
                }
            }

    /** Fire-and-forget: Firestore queues the write offline and sends it when connectivity returns. */
    fun uploadUsage(roomId: String, deviceId: String, u: DailyUsage) {
        val data = HashMap<String, Any>(u.toMap())
        data["deviceId"] = deviceId
        data["updatedAt"] = FieldValue.serverTimestamp()
        room(roomId).collection("usage").document("${deviceId}_${u.date}").set(data)
    }

    fun heartbeat(roomId: String, deviceId: String, battery: Int, charging: Boolean, consent: Consent) {
        room(roomId).collection("devices").document(deviceId).update(mapOf(
            "battery" to battery, "charging" to charging,
            "lastSeen" to FieldValue.serverTimestamp(), "consent" to consent.toMap()
        ))
    }

    fun reminders(roomId: String): Flow<List<ReminderItem>> =
        room(roomId).collection("reminders").orderBy("createdAt", Query.Direction.DESCENDING).limit(50).snaps().map { q ->
            q.documents.map { d ->
                ReminderItem(d.id, d.getString("deviceId") ?: "", d.getString("message") ?: "",
                    d.ts("scheduledAt"), d.getString("status") ?: "pending", d.ts("createdAt"), d.ts("deliveredAt"))
            }
        }

    suspend fun sendReminder(roomId: String, deviceId: String, childUid: String, message: String, at: Long) {
        val u = uid ?: error("Not signed in")
        room(roomId).collection("reminders").add(mapOf(
            "deviceId" to deviceId, "targetChildUid" to childUid, "message" to message,
            "scheduledAt" to Timestamp(Date(at)), "status" to "pending",
            "createdBy" to u, "createdAt" to FieldValue.serverTimestamp()
        )).await()
        audit(roomId, "reminder.send", deviceId)
    }

    fun markDelivered(roomId: String, reminderId: String) {
        room(roomId).collection("reminders").document(reminderId)
            .update(mapOf("status" to "delivered", "deliveredAt" to FieldValue.serverTimestamp()))
    }

    fun audit(roomId: String): Flow<List<AuditItem>> =
        room(roomId).collection("audit").orderBy("at", Query.Direction.DESCENDING).limit(100).snaps().map { q ->
            q.documents.map { AuditItem(it.id, it.getString("actorUid") ?: "", it.getString("action") ?: "",
                it.getString("target") ?: "", it.ts("at")) }
        }

    /** Append-only audit trail; rules forbid update/delete. */
    fun audit(roomId: String, action: String, target: String) {
        val u = uid ?: return
        room(roomId).collection("audit").add(mapOf(
            "actorUid" to u, "action" to action, "target" to target, "at" to FieldValue.serverTimestamp()))
    }

    // ---- screen share signalling status ----
    fun shareStatus(roomId: String, deviceId: String): Flow<ShareInfo> =
        shareRef(roomId, deviceId).snaps().map { ShareInfo(it.getString("status") ?: "idle", it.getString("session")) }

    suspend fun requestShare(roomId: String, deviceId: String) {
        val u = uid ?: error("Not signed in")
        shareRef(roomId, deviceId).set(mapOf(
            "status" to "requested", "requestedBy" to u, "requestedAt" to FieldValue.serverTimestamp())).await()
        audit(roomId, "share.request", deviceId)
    }

    fun setShareStatus(roomId: String, deviceId: String, status: String) {
        shareRef(roomId, deviceId).set(mapOf("status" to status), com.google.firebase.firestore.SetOptions.merge())
    }
}
