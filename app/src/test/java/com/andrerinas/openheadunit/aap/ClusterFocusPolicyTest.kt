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

    @Test
    fun `after the phone releases the cluster, no unsolicited PROJECTED until it asks again`() {
        val policy = ClusterFocusPolicy()
        policy.onClusterSetup()
        policy.onMainVideoData(0)
        assertTrue(policy.tryGrant(1_500))
        policy.onReleasedOnRequest()
        assertFalse(policy.tryKeyframeRequest(10_000))
        policy.onGrantedOnRequest()
        assertTrue(policy.tryKeyframeRequest(10_001))
    }

    @Test
    fun `a release before the grant also holds back the unsolicited grant`() {
        val policy = ClusterFocusPolicy()
        policy.onClusterSetup()
        policy.onReleasedOnRequest()
        policy.onMainVideoData(0)
        assertFalse(policy.tryGrant(5_000))
        assertFalse(policy.tryKeyframeRequest(5_000))
    }

    @Test
    fun `PROJECTED_NO_INPUT_FOCUS counts as projected, NATIVE modes do not`() {
        assertTrue(ClusterFocusPolicy.isProjected(1))
        assertTrue(ClusterFocusPolicy.isProjected(4))
        assertFalse(ClusterFocusPolicy.isProjected(2))
        assertFalse(ClusterFocusPolicy.isProjected(3))
    }

    @Test
    fun `a cluster Start ends a release the phone asked for`() {
        val policy = ClusterFocusPolicy()
        policy.onClusterSetup()
        policy.onMainVideoData(0)
        assertTrue(policy.tryGrant(1_500))
        policy.onReleasedOnRequest()
        policy.onClusterStart()
        assertTrue(policy.tryKeyframeRequest(10_000))
    }

    @Test
    fun `pause when hidden - no grant without a Surface, grant when it arrives`() {
        val policy = ClusterFocusPolicy(pauseHidden = true, hidden = true)
        policy.onClusterSetup()
        policy.onMainVideoData(0)
        assertFalse(policy.tryGrant(5_000))
        assertFalse(policy.onSurfaceBack(5_000)) // nothing to ask back, the first grant follows
        assertTrue(policy.tryGrant(5_001))
    }

    @Test
    fun `pause when hidden - focus given back on Surface loss and asked again on return`() {
        val policy = ClusterFocusPolicy(pauseHidden = true)
        policy.onClusterSetup()
        policy.onMainVideoData(0)
        assertTrue(policy.tryGrant(1_500))
        assertTrue(policy.onSurfaceLost())
        assertFalse(policy.tryKeyframeRequest(10_000))
        assertTrue(policy.onSurfaceBack(20_000))
        assertFalse(policy.tryKeyframeRequest(20_500)) // the return counted as a keyframe request
        assertTrue(policy.tryKeyframeRequest(22_000))
    }

    @Test
    fun `pause when hidden - a release the phone asked for is not overridden by a Surface`() {
        val policy = ClusterFocusPolicy(pauseHidden = true)
        policy.onClusterSetup()
        policy.onMainVideoData(0)
        assertTrue(policy.tryGrant(1_500))
        policy.onReleasedOnRequest()
        assertFalse(policy.onSurfaceLost())
        assertFalse(policy.onSurfaceBack(20_000))
    }

    @Test
    fun `pause off - Surface changes send nothing`() {
        val policy = ClusterFocusPolicy()
        policy.onClusterSetup()
        policy.onMainVideoData(0)
        assertTrue(policy.tryGrant(1_500))
        assertFalse(policy.onSurfaceLost())
        assertFalse(policy.onSurfaceBack(20_000))
        assertTrue(policy.tryKeyframeRequest(20_001))
    }

    @Test
    fun `pause when hidden - a waiting Surface gets the grant 3 s after setup when main never streamed`() {
        val policy = ClusterFocusPolicy(pauseHidden = true, hidden = true)
        policy.onClusterSetup(10_000)
        assertFalse(policy.tryGrant(20_000))            // no Surface yet
        policy.onSurfaceBack(20_000)
        assertTrue(policy.tryGrant(20_001))
    }

    @Test
    fun `pause when hidden - not before 3 s after setup, and never without pause when hidden`() {
        val policy = ClusterFocusPolicy(pauseHidden = true)
        policy.onClusterSetup(10_000)
        assertFalse(policy.tryGrant(12_999))
        assertTrue(policy.tryGrant(13_000))
        val plain = ClusterFocusPolicy()
        plain.onClusterSetup(10_000)
        assertFalse(plain.tryGrant(60_000))
    }
}
