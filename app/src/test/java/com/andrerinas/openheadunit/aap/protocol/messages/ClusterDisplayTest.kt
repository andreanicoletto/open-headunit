package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterDisplayTest {

    private val video = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480

    @Test
    fun `nothing is announced when the cluster is off`() {
        assertTrue(ClusterDisplay.services(enabled = false).isEmpty())
    }

    @Test
    fun `the cluster sink is display 1, type CLUSTER, H264 baseline 800x480 at 30 fps and 160 dpi`() {
        val service = ClusterDisplay.services(enabled = true).single { it.hasMediaSinkService() }
        assertEquals(Channel.ID_CLU_VID, service.id)
        val sink = service.mediaSinkService
        assertEquals(1, sink.displayId)
        assertEquals(Control.Service.MediaSinkService.DisplayType.DISPLAY_TYPE_CLUSTER, sink.displayType)
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP, sink.availableType)
        assertFalse(sink.hasAudioType())
        val config = sink.videoConfigsList.single()
        assertEquals(video, config.codecResolution)
        assertEquals(Control.Service.MediaSinkService.VideoConfiguration.VideoFrameRateType._30, config.frameRate)
        assertEquals(160, config.density)
        assertEquals(160, config.realDensity)
        assertEquals(10000, config.pixelAspectRatioE4)
        assertEquals(Media.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP, config.videoCodecType)
    }

    @Test
    fun `the cluster sink carries a full ui config with margins split per side`() {
        val config = ClusterDisplay.services(enabled = true).single { it.hasMediaSinkService() }
            .mediaSinkService.videoConfigsList.single()
        assertTrue(config.hasUiConfig())
        val ui = config.uiConfig
        assertEquals(config.marginHeight / 2, ui.margins.top)
        assertEquals(config.marginHeight / 2, ui.margins.bottom)
        assertEquals(config.marginWidth / 2, ui.margins.left)
        assertEquals(config.marginWidth / 2, ui.margins.right)
        assertTrue(ui.hasContentInsets())
        assertTrue(ui.hasStableContentInsets())
        assertTrue(ui.hasUiTheme())
        assertEquals(0, ui.uiTheme)
    }

    @Test
    fun `a matching input service for display 1 with the DPAD keycodes and no touch`() {
        val service = ClusterDisplay.services(enabled = true).single { it.hasInputSourceService() }
        assertEquals(Channel.ID_CLU_INP, service.id)
        val input = service.inputSourceService
        assertEquals(1, input.displayId)
        assertEquals(listOf(19, 20, 21, 22, 23), input.keycodesSupportedList)
        assertFalse(input.hasTouchscreen())
        assertFalse(input.hasTouchpad())
    }

    @Test
    fun `the wire bytes use the display field numbers the phone reads`() {
        // MediaSinkService display_id = 6, display_type = 7; InputSourceService display_id = 5.
        val sink = ClusterDisplay.services(enabled = true).single { it.hasMediaSinkService() }.mediaSinkService
        val sinkFields = com.google.protobuf.UnknownFieldSet.parseFrom(sink.toByteArray()).asMap()
        assertEquals(listOf(1L), sinkFields.getValue(6).varintList)
        assertEquals(listOf(1L), sinkFields.getValue(7).varintList)
        val input = ClusterDisplay.services(enabled = true).single { it.hasInputSourceService() }.inputSourceService
        assertEquals(listOf(1L), com.google.protobuf.UnknownFieldSet.parseFrom(input.toByteArray()).asMap().getValue(5).varintList)
    }

    @Test
    fun `a CLUSTER sink carries no initial content keycode`() {
        val sink = ClusterDisplay.services(enabled = true).single { it.hasMediaSinkService() }.mediaSinkService
        assertFalse(sink.hasInitialContentKeycode())
    }

    @Test
    fun `aux-map is an AUXILIARY sink asking for the navigation map, everything else as the cluster`() {
        val cluster = ClusterDisplay.services(enabled = true, auxMap = false)
        val aux = ClusterDisplay.services(enabled = true, auxMap = true)
        val sink = aux.single { it.hasMediaSinkService() }.mediaSinkService
        assertEquals(Control.Service.MediaSinkService.DisplayType.DISPLAY_TYPE_AUXILIARY, sink.displayType)
        assertEquals(65538, sink.initialContentKeycode)
        // Wire: display_type = 7 is 2, initial_content_keycode = 8 is 65538.
        val fields = com.google.protobuf.UnknownFieldSet.parseFrom(sink.toByteArray()).asMap()
        assertEquals(listOf(2L), fields.getValue(7).varintList)
        assertEquals(listOf(65538L), fields.getValue(8).varintList)
        val clusterSink = cluster.single { it.hasMediaSinkService() }.mediaSinkService
        assertEquals(clusterSink.toBuilder().clearDisplayType().build(),
            sink.toBuilder().clearDisplayType().clearInitialContentKeycode().build())
        assertEquals(cluster.single { it.hasInputSourceService() }, aux.single { it.hasInputSourceService() })
    }

    @Test
    fun `the cluster channels do not collide with the existing ones`() {
        val used = setOf(Channel.ID_CTR, Channel.ID_SEN, Channel.ID_VID, Channel.ID_INP, Channel.ID_AU1,
            Channel.ID_AU2, Channel.ID_AUD, Channel.ID_MIC, Channel.ID_BTH, Channel.ID_MPB, Channel.ID_NAV,
            Channel.ID_NOT, Channel.ID_PHONE, Channel.ID_WIFI)
        assertFalse(Channel.ID_CLU_VID in used)
        assertFalse(Channel.ID_CLU_INP in used)
        assertTrue(Channel.ID_CLU_VID != Channel.ID_CLU_INP)
    }

    @Test
    fun `margins and density from settings reach the config and the ui_config, clamped`() {
        val config = ClusterDisplay.services(enabled = true, marginWidth = 228, marginHeight = 0, density = 240)
            .single { it.hasMediaSinkService() }.mediaSinkService.getVideoConfigs(0)
        assertEquals(228, config.marginWidth)
        assertEquals(240, config.density)
        assertEquals(114, config.uiConfig.margins.left)
        assertEquals(114, config.uiConfig.margins.right)
        val wild = ClusterDisplay.services(enabled = true, marginWidth = 5000, marginHeight = -3, density = 9999)
            .single { it.hasMediaSinkService() }.mediaSinkService.getVideoConfigs(0)
        assertEquals(640, wild.marginWidth)
        assertEquals(0, wild.marginHeight)
        assertEquals(640, wild.density)
    }

    @Test
    fun `content rectangle is centred inside the margins`() {
        assertEquals(listOf(800, 480, 114, 0, 686, 480), ClusterDisplay.contentRect(228, 0).toList())
        assertEquals(listOf(800, 480, 0, 0, 800, 480), ClusterDisplay.contentRect(0, 0).toList())
        assertEquals(listOf(800, 480, 320, 0, 480, 480), ClusterDisplay.contentRect(5000, -3).toList())
    }
}
