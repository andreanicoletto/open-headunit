package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterFocusPolicyTest {

    @Test
    fun `no grant before the cluster is set up, however long main has run`() {
        val policy = ClusterFocusPolicy()
        policy.onMainVideoData(1_000)
        assertFalse(policy.tryGrant(10_000))
    }

    @Test
    fun `no grant before main video has flowed`() {
        val policy = ClusterFocusPolicy()
        policy.onClusterSetup()
        assertFalse(policy.tryGrant(10_000))
    }

    @Test
    fun `grant once, 1_5 s after the first main frame`() {
        val policy = ClusterFocusPolicy()
        policy.onClusterSetup()
        policy.onMainVideoData(1_000)
        policy.onMainVideoData(2_000) // later frames do not move the start
        assertFalse(policy.tryGrant(2_499))
        assertTrue(policy.tryGrant(2_500))
        assertFalse(policy.tryGrant(9_000))
    }

    @Test
    fun `a phone request already granted it, so no unsolicited grant follows`() {
        val policy = ClusterFocusPolicy()
        policy.onClusterSetup()
        policy.onGrantedOnRequest()
        policy.onMainVideoData(0)
        assertFalse(policy.tryGrant(5_000))
    }

    @Test
    fun `keyframe requests only after the grant, and at most one per 2 s`() {
        val policy = ClusterFocusPolicy()
        assertFalse(policy.tryKeyframeRequest(0))
        policy.onClusterSetup()
        policy.onMainVideoData(0)
        assertTrue(policy.tryGrant(1_500))
        assertTrue(policy.tryKeyframeRequest(1_600))
        assertFalse(policy.tryKeyframeRequest(3_599))
        assertTrue(policy.tryKeyframeRequest(3_600))
    }
}
