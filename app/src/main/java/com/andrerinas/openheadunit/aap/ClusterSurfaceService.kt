package com.andrerinas.openheadunit.aap

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.IClusterSurface
import com.andrerinas.openheadunit.aap.protocol.messages.ClusterDisplay
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy

/**
 * Vito fork: lends the cluster decoder to another app's Surface (our launcher's mini map), so the
 * AGPL code stays in this process and the picture needs no copy. Same path as the Cluster screen:
 * the last Surface attached wins, a stale detach is ignored. Bound only; guarded by the manifest
 * permission and by [ALLOWED_PACKAGES].
 */
class ClusterSurfaceService : Service() {
    private var surface: Surface? = null
    private var client: IBinder? = null
    private val death = IBinder.DeathRecipient { Log.i(ClusterStream.TAG, "surface client died"); detachCurrent() }

    private val binder = object : IClusterSurface.Stub() {
        override fun version() = 1

        override fun content(): IntArray {
            checkCaller()
            val settings = App.provide(this@ClusterSurfaceService).settings
            return ClusterDisplay.contentRect(settings.clusterMarginWidth, settings.clusterMarginHeight)
        }

        override fun attach(s: Surface?, c: IBinder?) {
            checkCaller()
            if (s == null || c == null || !s.isValid) return
            synchronized(this@ClusterSurfaceService) {
                detachCurrent()
                try { c.linkToDeath(death, 0) } catch (_: android.os.RemoteException) { return }   // already dead
                surface = s
                client = c
                Log.i(ClusterStream.TAG, "surface attached by uid ${Binder.getCallingUid()}")
                App.provide(this@ClusterSurfaceService).clusterDecoder.setSurface(s)
                ClusterStream.active?.onSurfaceAttached()
            }
        }

        override fun detach(c: IBinder?) {
            checkCaller()
            synchronized(this@ClusterSurfaceService) { if (c != null && c === client) detachCurrent() }
        }
    }

    private fun checkCaller() {
        val callers = packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (callers.none { it in ALLOWED_PACKAGES }) throw SecurityException("not allowed: ${callers.joinToString()}")
    }

    @Synchronized
    private fun detachCurrent() {
        val s = surface ?: return
        client?.unlinkToDeath(death, 0)
        surface = null
        client = null
        Log.i(ClusterStream.TAG, "surface detached (service)")
        // Only when it is still the decoder's Surface: the Cluster screen may have taken over meanwhile.
        if (App.provide(this).clusterDecoder.detachSurfaceIfCurrent(s, DecoderStopPolicy.REASON_SURFACE_DESTROYED)) {
            ClusterStream.active?.onSurfaceDetached()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        detachCurrent()
        return false
    }

    override fun onDestroy() {
        detachCurrent()
        super.onDestroy()
    }

    companion object {
        /** Who may lend a Surface. Different signing keys, so no signature permission: checked by package of the calling uid. */
        val ALLOWED_PACKAGES = setOf("camper.vito.launcher")
    }
}
