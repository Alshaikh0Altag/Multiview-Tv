package com.multiview.tv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.multiview.tv.data.Http
import com.multiview.tv.data.Settings

@OptIn(UnstableApi::class)
object PlayerFactory {

    /** Software decoders first (for channels that stutter or show green frames on hardware). */
    private val softwareFirst = MediaCodecSelector { mime, secure, tunneling ->
        MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedByDescending { it.softwareOnly }
    }

    /**
     * @param lowLatency smaller buffers — used for the multiview tiles so 4 players fit in memory.
     */
    fun create(context: Context, lowLatency: Boolean = false): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)

        val loadControl = if (lowLatency) {
            DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 15_000, 1_500, 2_500).build()
        } else {
            DefaultLoadControl.Builder().setBufferDurationsMs(10_000, 40_000, 2_000, 4_000).build()
        }

        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        if (Settings.decoder(context) == "sw") renderers.setMediaCodecSelector(softwareFirst)

        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(http))
            .setLoadControl(loadControl)
            .build()
        player.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)

        // "Data saver": cap the resolution when the stream offers several qualities (HLS)
        val maxH = if (lowLatency && Settings.maxHeight(context) == 0) 720 else Settings.maxHeight(context)
        if (maxH > 0) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(maxH * 16 / 9, maxH)
                .build()
        }
        return player
    }

    fun mediaItem(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(url))
        val path = Uri.parse(url).path.orEmpty().lowercase()
        when {
            path.endsWith(".m3u8") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            path.endsWith(".ts") -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        b.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(5_000).build())
        return b.build()
    }

    fun ExoPlayer.playUrl(url: String) {
        setMediaItem(mediaItem(url))
        prepare()
        playWhenReady = true
    }
}
