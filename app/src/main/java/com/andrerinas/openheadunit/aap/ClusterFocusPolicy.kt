package com.andrerinas.openheadunit.aap

/**
 * Vito fork: when the cluster channel may get video focus, and how often a keyframe may be asked.
 *
 * The first unsolicited PROJECTED goes out only once the phone has set the cluster up and the main
 * screen has been streaming for [grantAfterMainMs]: granting during the handshake left the main
 * screen on "Android Auto is starting" in DiAuto (PR #44). A keyframe is asked with an unsolicited
 * PROJECTED at most once per [keyframeGapMs], never with a NATIVE/PROJECTED toggle.
 *
 * Times are elapsedRealtime milliseconds. One instance per transport, so per session.
 */
internal class ClusterFocusPolicy(
    private val grantAfterMainMs: Long = 1500L,
    private val keyframeGapMs: Long = 2000L,
    /** With pause when hidden and a Surface waiting: grant this long after setup even if the main screen never streamed. */
    private val grantWithoutMainMs: Long = 3000L,
    /** Only a session that keeps its main screen away (vito-no-auto-raise) may grant without main video. */
    private val mainMayNeverShow: Boolean = true,
    /** Pause when hidden: no focus while there is no Surface. [hidden] is the state at session start. */
    private val pauseHidden: Boolean = false,
    private var hidden: Boolean = false,
) {
    private var mainDataSinceMs = -1L
    private var clusterSetUp = false
    private var setUpAtMs = 0L
    var granted = false
        @Synchronized get
        private set
    private var lastKeyframeMs = Long.MIN_VALUE
    // The phone asked NATIVE on the cluster: no unsolicited PROJECTED until it asks PROJECTED again.
    private var phoneReleased = false

    @Synchronized
    fun onMainVideoData(nowMs: Long) {
        if (mainDataSinceMs < 0) mainDataSinceMs = nowMs
    }

    @Synchronized
    fun onClusterSetup(nowMs: Long = 0L) {
        clusterSetUp = true
        setUpAtMs = nowMs
    }

    /** The phone asked for PROJECTED on the cluster and we answered it: no unsolicited grant needed. */
    @Synchronized
    fun onGrantedOnRequest() {
        granted = true
        phoneReleased = false
    }

    /** The phone sent Start on the cluster: it wants the stream, so an earlier release is over. */
    @Synchronized
    fun onClusterStart() {
        phoneReleased = false
    }

    /** The phone asked for a non-projected mode on the cluster and we answered it. */
    @Synchronized
    fun onReleasedOnRequest() {
        phoneReleased = true
    }

    /** The Surface went away. True when focus must be given back now (unsolicited NATIVE). */
    @Synchronized
    fun onSurfaceLost(): Boolean {
        hidden = true
        return pauseHidden && granted && !phoneReleased
    }

    /** A Surface arrived. True when focus must be asked back now (unsolicited PROJECTED, brings a keyframe). */
    @Synchronized
    fun onSurfaceBack(nowMs: Long): Boolean {
        val wasHidden = hidden
        hidden = false
        if (!pauseHidden || !wasHidden || !granted || phoneReleased) return false
        lastKeyframeMs = nowMs
        return true
    }

    /** True exactly once, when the unsolicited cluster grant is due. */
    @Synchronized
    fun tryGrant(nowMs: Long): Boolean {
        if (granted || phoneReleased || !clusterSetUp) return false
        if (pauseHidden && hidden) return false
        // A session started without its screen (the launcher in front) never streams main video; the Surface that
        // is waiting is then the reason to ask. Without pause when hidden the old rule stands.
        val afterMain = mainDataSinceMs >= 0 && nowMs - mainDataSinceMs >= grantAfterMainMs
        val withoutMain = pauseHidden && mainMayNeverShow && mainDataSinceMs < 0 && nowMs - setUpAtMs >= grantWithoutMainMs
        if (!afterMain && !withoutMain) return false
        granted = true
        return true
    }

    /** True when a keyframe request may go now; records it. Only after the cluster has focus. */
    @Synchronized
    fun tryKeyframeRequest(nowMs: Long): Boolean {
        if (!granted || phoneReleased || (pauseHidden && hidden)) return false
        if (lastKeyframeMs != Long.MIN_VALUE && nowMs - lastKeyframeMs < keyframeGapMs) return false
        lastKeyframeMs = nowMs
        return true
    }

    companion object {
        /** VideoFocusMode numbers that mean "show it": PROJECTED (1) and PROJECTED_NO_INPUT_FOCUS (4). */
        fun isProjected(mode: Int) = mode == 1 || mode == 4
    }
}
