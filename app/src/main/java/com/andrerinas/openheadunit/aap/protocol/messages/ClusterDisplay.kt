package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/**
 * Vito fork: the instrument-cluster display announced next to the main screen.
 *
 * One CLUSTER (or AUXILIARY map, see [services]) sink (display 1) plus the input service the phone requires for every display
 * (its topology check refuses a display without one). Values follow the checklist in the launcher
 * repo, docs/aa-receiver/cluster-gmaps.md section 6, after aa-proxy-rs create_media_sink_service.
 */
internal object ClusterDisplay {
    const val DISPLAY_ID = 1
    const val WIDTH = 800
    const val HEIGHT = 480
    const val DENSITY = 160
    const val VIEWING_DISTANCE = 500
    // 0/0 = the whole 800x480. Margins shape the panel; change both here and the ui_config follows.
    const val MARGIN_WIDTH = 0
    const val MARGIN_HEIGHT = 0

    /** DPAD up/down/left/right/center, as aa-proxy-rs announces for a cluster. */
    val KEYCODES = listOf(19, 20, 21, 22, 23)

    /** KEYCODE_NAVIGATION: on an AUXILIARY display it asks the phone for the map, not a turn card. */
    const val KEYCODE_NAVIGATION = 65538

    /**
     * [auxMap] false: a CLUSTER sink, the phone alone picks map or turn card. True: an AUXILIARY
     * sink with initial_content_keycode NAVIGATION (upstream draft PR #1097's default). All else equal.
     */
    fun services(enabled: Boolean, auxMap: Boolean = false, marginWidth: Int = MARGIN_WIDTH, marginHeight: Int = MARGIN_HEIGHT, density: Int = DENSITY): List<Control.Service> =
        if (enabled) listOf(videoSink(auxMap, marginWidth.coerceIn(0, WIDTH - 160), marginHeight.coerceIn(0, HEIGHT - 120), density.coerceIn(80, 640)), inputSource()) else emptyList()

    /** Frame width and height, then the content rectangle left, top, right, bottom for these margins (clamped like [services]). */
    fun contentRect(marginWidth: Int, marginHeight: Int): IntArray {
        val mw = marginWidth.coerceIn(0, WIDTH - 160)
        val mh = marginHeight.coerceIn(0, HEIGHT - 120)
        return intArrayOf(WIDTH, HEIGHT, mw / 2, mh / 2, WIDTH - mw / 2, HEIGHT - mh / 2)
    }

    private fun insets(top: Int, bottom: Int, left: Int, right: Int): Media.Insets =
        Media.Insets.newBuilder().setTop(top).setBottom(bottom).setLeft(left).setRight(right).build()

    private fun videoSink(auxMap: Boolean, marginWidth: Int, marginHeight: Int, density: Int): Control.Service {
        val uiConfig = Media.UiConfig.newBuilder()
            .setMargins(insets(marginHeight / 2, marginHeight / 2, marginWidth / 2, marginWidth / 2))
            .setContentInsets(insets(0, 0, 0, 0))
            .setStableContentInsets(insets(0, 0, 0, 0))
            .setUiTheme(0) // automatic
            .build()
        val config = Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
            .setCodecResolution(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480)
            .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
            .setMarginWidth(marginWidth)
            .setMarginHeight(marginHeight)
            .setDensity(density)
            .setRealDensity(density)
            .setDecoderAdditionalDepth(0)
            .setViewingDistance(VIEWING_DISTANCE)
            .setPixelAspectRatioE4(10000)
            .setVideoCodecType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .setUiConfig(uiConfig)
            .build()
        val sink = Control.Service.MediaSinkService.newBuilder()
            .setAvailableType(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP)
            .addVideoConfigs(config)
            .setDisplayId(DISPLAY_ID)
            .apply {
                if (auxMap) {
                    displayType = Control.Service.MediaSinkService.DisplayType.DISPLAY_TYPE_AUXILIARY
                    initialContentKeycode = KEYCODE_NAVIGATION
                } else {
                    displayType = Control.Service.MediaSinkService.DisplayType.DISPLAY_TYPE_CLUSTER
                }
            }
            .build()
        return Control.Service.newBuilder().setId(Channel.ID_CLU_VID).setMediaSinkService(sink).build()
    }

    private fun inputSource(): Control.Service {
        val input = Control.Service.InputSourceService.newBuilder()
            .addAllKeycodesSupported(KEYCODES)
            .setDisplayId(DISPLAY_ID)
            .build()
        return Control.Service.newBuilder().setId(Channel.ID_CLU_INP).setInputSourceService(input).build()
    }
}
