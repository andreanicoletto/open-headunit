package com.andrerinas.openheadunit.aap

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.Settings

/**
 * Vito fork: the cluster video channel of one session ([Channel.ID_CLU_VID]).
 *
 * Reuses [AapVideo] for fragment assembly and a second, app-level [VideoDecoder] (AppComponent),
 * so the picture survives the projection activity. The decoder draws into whatever Surface the
 * Cluster screen hands it; without one it drops frames, and every frame is still acked.
 * Focus rules: [ClusterFocusPolicy]. Log tag [TAG].
 */
internal class ClusterStream(
    private val transport: AapTransport,
    private val decoder: VideoDecoder,
    private val settings: Settings,
) {
    private val focus = ClusterFocusPolicy(pauseHidden = settings.clusterPauseHidden, hidden = !decoder.hasSurface(),
        mainMayNeverShow = settings.vitoNoAutoRaise)
    /** This session's display type, fixed at connect: AUXILIARY map instead of CLUSTER. */
    val auxMap = settings.clusterDisplayType == Settings.CLUSTER_DISPLAY_AUX_MAP
    private val video = AapVideo(decoder, settings, forceSoftware = settings.clusterSoftwareDecoder) { requestKeyframe("corrupt access unit") }
        .also { it.recordAnnouncedCodec(VideoDecoder.CodecType.H264) }
    private val keyframeCallback: () -> Unit = { requestKeyframe("decoder needs a keyframe") }
    private val errorCallback: (String) -> Unit = { requestKeyframe("decoder error: $it") }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var released = false
    private val backlog = java.util.concurrent.atomic.AtomicInteger(0)
    private val grantTimerSet = java.util.concurrent.atomic.AtomicBoolean(false)
    // Surface attach time and the render count then, for the "attach to first picture" log; 0 = not waiting.
    @Volatile private var attachMs = 0L
    @Volatile private var attachRendered = 0L

    // Cluster thread only.
    private var frames = 0L
    private var windowStartMs = 0L
    private var windowFrames = 0L

    init {
        synchronized(decoder) {
            decoder.onKeyframeStarved = keyframeCallback
            decoder.onFrameDropped = keyframeCallback
            decoder.onDecoderError = errorCallback
            decoder.beginSession()
        }
        active = this
    }

    fun onDiscoverySent() = log("discovery sent (pause-hidden=${settings.clusterPauseHidden} software=${settings.clusterSoftwareDecoder} margins=${settings.clusterMarginWidth}x${settings.clusterMarginHeight} dpi=${settings.clusterDensity}) with ${if (auxMap) "AUXILIARY (map, keycode NAVIGATION)" else "CLUSTER"} sink ch=${Channel.ID_CLU_VID} and input ch=${Channel.ID_CLU_INP}")

    /** Every main-channel picture message, from the read thread. */
    fun onMainVideoData() {
        focus.onMainVideoData(SystemClock.elapsedRealtime())
        if (grant("main video ran 1.5 s")) return
        // A still main screen sends no further frames, so the grant needs its own timer (one per session).
        if (!focus.granted && grantTimerSet.compareAndSet(false, true)) {
            handler()?.postDelayed({ grant("timer, 1.5 s after the first main frame") }, GRANT_TIMER_MS)
        }
    }

    // Policy check and send under one lock, so a nudge cannot go out after the answer to a phone NATIVE.
    private fun grant(why: String): Boolean = synchronized(focus) {
        if (released || !focus.tryGrant(SystemClock.elapsedRealtime())) return false
        sendFocus(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED, unsolicited = true, why = why)
        true
    }

    /** Called after the cluster Config went out. */
    fun onSetup() {
        log("setup answered")
        focus.onClusterSetup(SystemClock.elapsedRealtime())
        if (!grant("setup after main video ran")) grantLater()
    }

    /** The phone's VideoFocusRequest on the cluster: answered at once with the mode it asked for. */
    fun onFocusRequest(request: Media.VideoFocusRequestNotification) {
        log("focus request mode=${request.mode} reason=${request.reason}")
        synchronized(focus) {
            if (ClusterFocusPolicy.isProjected(request.mode.number)) focus.onGrantedOnRequest()
            else focus.onReleasedOnRequest()
            sendFocus(request.mode, unsolicited = false, why = "answer")
        }
    }

    fun onStart(sessionId: Int) {
        log("start session=$sessionId")
        // The phone starts the stream, so it wants it: a release it asked for earlier is over.
        focus.onClusterStart()
    }

    fun onStop() = log("stop")

    /** Unit going to sleep: let go of the hardware codec, like the main decoder. The next frame starts it again. */
    fun pauseForSleep() = decoder.stop(com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy.REASON_SCREEN_OFF_SLEEP)

    /** The Cluster screen or ClusterSurfaceService (the launcher) attached a Surface to the decoder. */
    fun onSurfaceAttached() {
        attachRendered = decoder.sessionHealth().framesRendered
        attachMs = SystemClock.elapsedRealtime()
        val ask = synchronized(focus) {
            val back = focus.onSurfaceBack(attachMs)
            if (back) sendFocus(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED, unsolicited = true, why = "surface back")
            back
        }
        // Not paused: first grant if it was waiting for a Surface, else a codec started mid-stream needs a keyframe.
        if (!ask && !grant("surface attached")) { requestKeyframe("surface attached"); grantLater() }
    }

    // The grant without main video is due a few seconds after setup; nothing else would trigger the check.
    private fun grantLater() {
        if (!focus.granted) handler()?.postDelayed({ grant("surface waiting, main screen never shown") }, GRANT_NO_MAIN_MS)
    }

    fun onSurfaceDetached() {
        attachMs = 0L
        synchronized(focus) {
            if (focus.onSurfaceLost()) sendFocus(Media.VideoFocusMode.VIDEO_FOCUS_NATIVE, unsolicited = true, why = "surface lost, pause when hidden")
        }
    }

    /** Rate-limited unsolicited PROJECTED, the only keyframe request used on the cluster. */
    fun requestKeyframe(reason: String) {
        synchronized(focus) {
            if (released || !focus.tryKeyframeRequest(SystemClock.elapsedRealtime())) return
            sendFocus(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED, unsolicited = true, why = "keyframe: $reason")
        }
    }

    /**
     * Hands a cluster-channel message to the cluster thread; true when it was picture. Like
     * [AapTransport.dispatchVideo], the ack goes out after the work, with the session id at receipt.
     */
    fun dispatch(message: AapMessage): Boolean {
        val isPayload = video.isPayload(message)
        val acks = AapMessageFraming.completesMediaData(message.type, message.flags.toInt())
        val session = transport.getSessionId(message.channel)
        val handler = handler() ?: return isPayload
        if (backlog.get() >= BACKLOG_LIMIT) {
            // Decoder behind: shed this message, mark the fragment run holed so a partial access
            // unit is not decoded, still ack so the window stays open, and ask a keyframe.
            if (isPayload) handler.post { video.onFragmentRunHoled(true) }
            if (acks) transport.sendMediaAck(message.channel, session)
            requestKeyframe("backlog full, shed a message")
            return isPayload
        }
        // shortcut: one copy per message (the read buffer is reused); fine at 30 fps 800x480, pool it if it shows in GC.
        val queued = AapMessage(message.channel, message.flags, message.type, message.dataOffset, message.size,
            message.data.copyOf(message.size))
        backlog.incrementAndGet()
        handler.post {
            try {
                if (!released) video.process(queued)
            } catch (e: Exception) {
                Log.e(TAG, "process failed", e)
            } finally {
                backlog.decrementAndGet()
                if (acks) {
                    transport.sendMediaAck(queued.channel, session)
                    countFrame()
                }
            }
        }
        return isPayload
    }

    fun release() {
        val t = synchronized(this) {
            released = true
            handler = null
            thread.also { thread = null }
        }
        t?.quitSafely()
        // Wait for an in-flight process(): a decode after stop() below would start a new codec
        // after the session ended. The main path joins its video thread the same way.
        if (t != null && Thread.currentThread() !== t) {
            try { t.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        synchronized(decoder) {
            if (decoder.onKeyframeStarved === keyframeCallback) decoder.onKeyframeStarved = null
            if (decoder.onFrameDropped === keyframeCallback) decoder.onFrameDropped = null
            if (decoder.onDecoderError === errorCallback) decoder.onDecoderError = null
        }
        video.release()
        // Next session starts the codec again on its first keyframe; the Surface stays attached.
        decoder.stop("cluster session end")
        // The join timed out on a slow decode: wait it out and stop the codec it may have started.
        if (t != null && t.isAlive && Thread.currentThread() !== t) {
            try { t.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            decoder.stop("cluster session end, late decode")
        }
        if (active === this) active = null
        log("released after $frames frames")
    }

    @Synchronized
    private fun handler(): Handler? {
        if (released) return null
        handler?.let { return it }
        val t = HandlerThread("AapTransport:Handler::ClusterVideo", Process.THREAD_PRIORITY_DISPLAY)
        t.start()
        thread = t
        return Handler(t.looper).also { handler = it }
    }

    private fun countFrame() {
        frames++
        val now = SystemClock.elapsedRealtime()
        if (frames == 1L) {
            log("first frame, surface=${decoder.hasSurface()}")
            windowStartMs = now
            windowFrames = 0
        }
        windowFrames++
        val attached = attachMs
        if (attached != 0L && decoder.sessionHealth().framesRendered > attachRendered) {
            attachMs = 0L
            log("attach to first rendered frame: ${now - attached} ms")
        }
        val elapsed = now - windowStartMs
        if (elapsed >= FPS_LOG_INTERVAL_MS) {
            val health = decoder.sessionHealth()
            log("fps=%.1f frames=$frames rendered=${health.framesRendered} dropped=${health.framesDropped} surface=${decoder.hasSurface()}"
                .format(windowFrames * 1000.0 / elapsed))
            windowStartMs = now
            windowFrames = 0
        }
    }

    private fun sendFocus(mode: Media.VideoFocusMode, unsolicited: Boolean, why: String) {
        log("focus send mode=$mode unsolicited=$unsolicited ($why)")
        transport.send(AapMessage(Channel.ID_CLU_VID, Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE,
            Media.VideoFocusNotification.newBuilder().setMode(mode).setUnsolicited(unsolicited).build()))
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    companion object {
        const val TAG = "VITOCL"
        private const val FPS_LOG_INTERVAL_MS = 10_000L
        // Messages queued for the cluster thread before new ones are shed (window is 4 frames).
        private const val BACKLOG_LIMIT = 64
        // Just past ClusterFocusPolicy's 1.5 s.
        private const val GRANT_TIMER_MS = 1600L
        // Just past ClusterFocusPolicy's 3 s.
        private const val GRANT_NO_MAIN_MS = 3100L

        /** The running session's stream, for the Cluster screen to ask a keyframe when its surface arrives. */
        @Volatile
        var active: ClusterStream? = null
            private set
    }
}
