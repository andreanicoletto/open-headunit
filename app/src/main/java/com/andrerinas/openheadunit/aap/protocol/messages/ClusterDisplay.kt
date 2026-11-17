package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/**
 * Vito fork: the instrument-cluster display announced next to the main screen.
 *
 * One CLUSTER sink (display 1) plus the input service the phone requires for every display
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

    fun services(enabled: Boolean): List<Control.Service> =
        if (enabled) listOf(videoSink(), inputSource()) else emptyList()

    private fun insets(top: Int, bottom: Int, left: Int, right: Int): Media.Insets =
        Media.Insets.newBuilder().setTop(top).setBottom(bottom).setLeft(left).setRight(right).build()

    private fun videoSink(): Control.Service {
        val uiConfig = Media.UiConfig.newBuilder()
            .setMargins(insets(MARGIN_HEIGHT / 2, MARGIN_HEIGHT / 2, MARGIN_WIDTH / 2, MARGIN_WIDTH / 2))
            .setContentInsets(insets(0, 0, 0, 0))
            .setStableContentInsets(insets(0, 0, 0, 0))
            .setUiTheme(0) // automatic
            .build()
        val config = Control.Service.MediaSinkService.VideoConfiguration.newBuilder()
            .setCodecResolution(Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480)
            .setFrameRate(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30)
            .setMarginWidth(MARGIN_WIDTH)
            .setMarginHeight(MARGIN_HEIGHT)
            .setDensity(DENSITY)
            .setRealDensity(DENSITY)
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
            .setDisplayType(Control.Service.MediaSinkService.DisplayType.DISPLAY_TYPE_CLUSTER)
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
