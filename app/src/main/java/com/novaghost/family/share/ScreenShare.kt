package com.novaghost.family.share

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.novaghost.family.R
import com.novaghost.family.core.Notifier
import com.novaghost.family.nova
import kotlinx.coroutines.flow.MutableStateFlow
import org.webrtc.*
import java.util.UUID

object Rtc {
    private var factory: PeerConnectionFactory? = null
    val egl: EglBase by lazy { EglBase.create() }

    @Synchronized
    fun factory(ctx: Context): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext).createInitializationOptions())
        val f = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    /** STUN only. Add your own TURN server here for restrictive NATs (see README). */
    fun config() = PeerConnection.RTCConfiguration(
        listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()))
}

open class SdpObs : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}

open class PcObs : PeerConnection.Observer {
    override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(b: Boolean) {}
    override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(c: IceCandidate?) {}
    override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
    override fun onAddStream(s: MediaStream?) {}
    override fun onRemoveStream(s: MediaStream?) {}
    override fun onDataChannel(d: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
}

private fun candMap(session: String, c: IceCandidate): Map<String, Any?> =
    mapOf("session" to session, "sdpMid" to c.sdpMid, "sdpMLineIndex" to c.sdpMLineIndex, "sdp" to c.sdp)

private fun toCandidate(m: Map<String, Any?>): IceCandidate =
    IceCandidate(m["sdpMid"] as? String, (m["sdpMLineIndex"] as? Number)?.toInt() ?: 0, m["sdp"] as? String ?: "")

/** CHILD side. Created only after the child accepted the system MediaProjection consent dialog. */
class ScreenSharer(
    private val ctx: Context,
    private val doc: DocumentReference,
    private val projection: Intent,
    private val onEnded: (String) -> Unit
) {
    private val session = UUID.randomUUID().toString()
    private val regs = mutableListOf<ListenerRegistration>()
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var pc: PeerConnection? = null
    private var closed = false
    private var remoteSet = false
    private val pending = mutableListOf<IceCandidate>()

    fun start() {
        val factory = Rtc.factory(ctx)
        val cap = ScreenCapturerAndroid(projection, object : MediaProjection.Callback() {
            override fun onStop() { stop("Screen capture ended") }
        })
        capturer = cap
        val h = SurfaceTextureHelper.create("nova-capture", Rtc.egl.eglBaseContext)
        helper = h
        val src = factory.createVideoSource(true)
        source = src
        cap.initialize(h, ctx, src.capturerObserver)
        val dm = ctx.resources.displayMetrics
        val scale = minOf(1f, 720f / minOf(dm.widthPixels, dm.heightPixels))
        cap.startCapture((dm.widthPixels * scale).toInt() and 1.inv(), (dm.heightPixels * scale).toInt() and 1.inv(), 15)
        val t = factory.createVideoTrack("nova-screen", src)
        track = t

        val conn = factory.createPeerConnection(Rtc.config(), object : PcObs() {
            override fun onIceCandidate(c: IceCandidate?) {
                if (c != null) doc.collection("childCandidates").add(candMap(session, c))
            }
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                if (s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED) stop("Connection ended")
            }
        })
        if (conn == null) { stop("Could not create connection"); return }
        pc = conn
        conn.addTrack(t, listOf("nova-stream"))

        regs += doc.addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            if (snap.getString("status") == "stopped") { stop("Stopped by parent"); return@addSnapshotListener }
            val ans = snap.get("answer") as? Map<*, *>
            if (ans != null && snap.getString("session") == session && !remoteSet) {
                remoteSet = true
                conn.setRemoteDescription(object : SdpObs() {
                    override fun onSetSuccess() { pending.forEach { conn.addIceCandidate(it) }; pending.clear() }
                }, SessionDescription(SessionDescription.Type.ANSWER, ans["sdp"] as? String ?: ""))
            }
        }
        regs += doc.collection("parentCandidates").addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            for (ch in snap.documentChanges) {
                if (ch.type != DocumentChange.Type.ADDED || ch.document.getString("session") != session) continue
                val c = toCandidate(ch.document.data)
                if (remoteSet) conn.addIceCandidate(c) else pending.add(c)
            }
        }
        conn.createOffer(object : SdpObs() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                conn.setLocalDescription(SdpObs(), d)
                doc.set(mapOf(
                    "status" to "active", "session" to session,
                    "offer" to mapOf("type" to d.type.canonicalForm(), "sdp" to d.description),
                    "answer" to FieldValue.delete(), "startedAt" to FieldValue.serverTimestamp()
                ), SetOptions.merge())
            }
        }, MediaConstraints())
    }

    @Synchronized
    fun stop(reason: String) {
        if (closed) return
        closed = true
        regs.forEach { it.remove() }
        try { doc.set(mapOf("status" to "stopped"), SetOptions.merge()) } catch (e: Throwable) {}
        try { capturer?.stopCapture() } catch (e: Throwable) {}
        try { capturer?.dispose() } catch (e: Throwable) {}
        try { pc?.close() } catch (e: Throwable) {}
        try { track?.dispose() } catch (e: Throwable) {}
        try { source?.dispose() } catch (e: Throwable) {}
        try { helper?.dispose() } catch (e: Throwable) {}
        onEnded(reason)
    }
}

/** Foreground service with a persistent, un-dismissable notification and a Stop action. */
class ScreenShareService : Service() {
    companion object {
        const val ACTION_START = "nova.share.start"
        const val ACTION_STOP = "nova.share.stop"
        private const val NOTIF_ID = 7001
        val active = MutableStateFlow(false)

        fun start(ctx: Context, resultCode: Int, data: Intent, roomId: String, deviceId: String) {
            val i = Intent(ctx, ScreenShareService::class.java).setAction(ACTION_START)
                .putExtra("code", resultCode).putExtra("data", data)
                .putExtra("room", roomId).putExtra("device", deviceId)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScreenShareService::class.java).setAction(ACTION_STOP))
        }
    }

    private var sharer: ScreenSharer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { sharer?.stop("Stopped by you"); if (sharer == null) stopSelf() }
            ACTION_START -> begin(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun begin(intent: Intent) {
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        val room = intent.getStringExtra("room")
        val dev = intent.getStringExtra("device")
        if (data == null || room == null || dev == null || !nova.cloud.configured) { stopSelf(); return }

        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, Notifier.CH_SHARE)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle("Screen sharing is ON")
            .setContentText("A parent can see this screen. Tap Stop to end it.")
            .setOngoing(true)
            .addAction(0, "STOP SHARING", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)

        active.value = true
        val s = ScreenSharer(applicationContext, nova.cloud.shareRef(room, dev), data) { stopSelf() }
        sharer = s
        try { s.start() } catch (e: Exception) { s.stop("Could not start: ${e.message}") }
    }

    override fun onDestroy() {
        active.value = false
        sharer?.stop("Service destroyed")
        super.onDestroy()
    }
}

/** PARENT side. Receives the child's offer, answers, and renders the video track. */
class ScreenViewer(private val ctx: Context, private val doc: DocumentReference, private val onState: (String) -> Unit) {
    private val regs = mutableListOf<ListenerRegistration>()
    private var pc: PeerConnection? = null
    private var session: String? = null
    private var renderer: SurfaceViewRenderer? = null
    private var track: VideoTrack? = null
    private var remoteSet = false
    private val pending = mutableListOf<IceCandidate>()

    fun attach(r: SurfaceViewRenderer) {
        renderer = r
        r.init(Rtc.egl.eglBaseContext, null)
        r.setEnableHardwareScaler(true)
        track?.addSink(r)
    }

    fun start() {
        regs += doc.addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            val st = snap.getString("status") ?: "idle"
            onState(st)
            val offer = snap.get("offer") as? Map<*, *>
            val sess = snap.getString("session")
            if (st == "active" && offer != null && sess != null && sess != session) {
                session = sess
                begin(sess, offer["sdp"] as? String ?: "")
            }
            if (st == "stopped") closePc()
        }
    }

    private fun begin(sess: String, sdp: String) {
        closePc()
        remoteSet = false
        val conn = Rtc.factory(ctx).createPeerConnection(Rtc.config(), object : PcObs() {
            override fun onIceCandidate(c: IceCandidate?) {
                if (c != null) doc.collection("parentCandidates").add(candMap(sess, c))
            }
            override fun onTrack(transceiver: RtpTransceiver?) {
                val t = transceiver?.receiver?.track()
                if (t is VideoTrack) { track = t; renderer?.let { t.addSink(it) } }
            }
        }) ?: return
        pc = conn
        regs += doc.collection("childCandidates").addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            for (ch in snap.documentChanges) {
                if (ch.type != DocumentChange.Type.ADDED || ch.document.getString("session") != sess) continue
                val c = toCandidate(ch.document.data)
                if (remoteSet) conn.addIceCandidate(c) else pending.add(c)
            }
        }
        conn.setRemoteDescription(object : SdpObs() {
            override fun onSetSuccess() {
                remoteSet = true
                pending.forEach { conn.addIceCandidate(it) }; pending.clear()
                conn.createAnswer(object : SdpObs() {
                    override fun onCreateSuccess(d: SessionDescription?) {
                        if (d == null) return
                        conn.setLocalDescription(SdpObs(), d)
                        doc.update("answer", mapOf("type" to "answer", "sdp" to d.description))
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun closePc() {
        try { renderer?.let { track?.removeSink(it) } } catch (e: Throwable) {}
        track = null
        try { pc?.close() } catch (e: Throwable) {}
        pc = null
    }

    fun stopRemote() {
        doc.set(mapOf("status" to "stopped"), SetOptions.merge())
        closePc()
    }

    fun release() {
        regs.forEach { it.remove() }
        closePc()
        try { renderer?.release() } catch (e: Throwable) {}
        renderer = null
    }
}
