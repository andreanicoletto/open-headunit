package com.andrerinas.openheadunit.aap

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.decoder.video.DecoderStopPolicy
import com.andrerinas.openheadunit.utils.Settings

/**
 * Vito fork test screen: shows the cluster stream full screen. The decoder belongs to the app
 * (AppComponent.clusterDecoder), so leaving this screen only detaches the Surface; the stream keeps
 * running and being acked. The label toggles the cluster setting, a long press also cycles the
 * display type (both used at the next connect).
 */
class ClusterActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var label: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surface = SurfaceView(this).also { it.holder.addCallback(this) }
        label = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x80000000.toInt())
            setPadding(16, 8, 16, 8)
            setOnClickListener {
                val settings = App.provide(this@ClusterActivity).settings
                settings.clusterStream = !settings.clusterStream
                updateLabel()
            }
            // Long press cycles off -> cluster -> aux-map -> off.
            setOnLongClickListener {
                val settings = App.provide(this@ClusterActivity).settings
                when {
                    !settings.clusterStream -> {
                        settings.clusterStream = true
                        settings.clusterDisplayType = Settings.CLUSTER_DISPLAY_CLUSTER
                    }
                    settings.clusterDisplayType == Settings.CLUSTER_DISPLAY_CLUSTER ->
                        settings.clusterDisplayType = Settings.CLUSTER_DISPLAY_AUX_MAP
                    else -> settings.clusterStream = false
                }
                updateLabel()
                true
            }
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        })
        updateLabel()
    }

    private fun updateLabel() {
        val settings = App.provide(this).settings
        val state = if (settings.clusterStream) "ON, ${settings.clusterDisplayType}" else "OFF"
        label.text = "Cluster stream $state (tap: on/off, long press: off/cluster/aux-map; next connect)"
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(ClusterStream.TAG, "surface attached ${width}x$height")
        App.provide(this).clusterDecoder.setSurface(holder.surface)
        // A codec started mid-stream sees only P-frames until the next keyframe.
        ClusterStream.active?.requestKeyframe("surface attached")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(ClusterStream.TAG, "surface detached")
        // A surface-lifecycle reason keeps the SPS and size, so a reopen decodes at 800x480.
        App.provide(this).clusterDecoder.detachSurfaceIfCurrent(holder.surface, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
    }
}
