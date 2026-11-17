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

/**
 * Vito fork test screen: shows the cluster stream full screen. The decoder belongs to the app
 * (AppComponent.clusterDecoder), so leaving this screen only detaches the Surface; the stream keeps
 * running and being acked. The label toggles the cluster setting (used at the next connect).
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
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(surface, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        })
        updateLabel()
    }

    private fun updateLabel() {
        val on = App.provide(this).settings.clusterStream
        label.text = "Cluster stream ${if (on) "ON" else "OFF"} (tap to change, applies at next connect)"
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
        App.provide(this).clusterDecoder.detachSurfaceIfCurrent(holder.surface, "cluster surface destroyed")
    }
}
