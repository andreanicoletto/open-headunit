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
) {
    private var mainDataSinceMs = -1L
    private var clusterSetUp = false
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
    fun onClusterSetup() {
        clusterSetUp = true
    }

    /** The phone asked for PROJECTED on the cluster and we answered it: no unsolicited grant needed. */
    @Synchronized
    fun onGrantedOnRequest() {
        granted = true
        phoneReleased = false
    }

    /** The phone asked for a non-projected mode on the cluster and we answered it. */
    @Synchronized
    fun onReleasedOnRequest() {
        phoneReleased = true
    }

    /** True exactly once, when the unsolicited cluster grant is due. */
    @Synchronized
    fun tryGrant(nowMs: Long): Boolean {
        if (granted || phoneReleased || !clusterSetUp || mainDataSinceMs < 0) return false
        if (nowMs - mainDataSinceMs < grantAfterMainMs) return false
        granted = true
        return true
    }

    /** True when a keyframe request may go now; records it. Only after the cluster has focus. */
    @Synchronized
    fun tryKeyframeRequest(nowMs: Long): Boolean {
        if (!granted || phoneReleased) return false
        if (lastKeyframeMs != Long.MIN_VALUE && nowMs - lastKeyframeMs < keyframeGapMs) return false
        lastKeyframeMs = nowMs
        return true
    }
}
