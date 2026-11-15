package com.multiview.tv.player

import android.app.Activity
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.multiview.tv.data.Settings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Auto frame rate (AFR): switches the TV's refresh rate to match the video
 * (e.g. 23.976 / 25 / 50 / 59.94 fps) to remove judder. Restores the default on release.
 */
@OptIn(UnstableApi::class)
class FrameRateMatcher(private val activity: Activity, private val player: ExoPlayer) : Player.Listener {

    private var lastFps = 0f

    fun attach() {
        if (Settings.autoFrameRate(activity)) player.addListener(this)
    }

    fun detach() {
        player.removeListener(this)
        val lp = activity.window.attributes
        if (lp.preferredDisplayModeId != 0) {
            lp.preferredDisplayModeId = 0
            activity.window.attributes = lp
        }
    }

    override fun onVideoSizeChanged(videoSize: VideoSize) {
        val fps = player.videoFormat?.frameRate ?: return
        if (fps == Format.NO_VALUE.toFloat() || fps <= 0f || abs(fps - lastFps) < 0.01f) return
        lastFps = fps
        val display: Display = activity.window.decorView.display ?: return
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        // Best mode: refresh rate that is an exact multiple of the video fps (24→24/48, 25→50, 29.97→59.94)
        val best = candidates
            .filter { m -> val ratio = m.refreshRate / fps; abs(ratio - ratio.roundToInt()) < 0.01f && ratio.roundToInt() >= 1 }
            .minByOrNull { it.refreshRate }
            ?: return
        if (best.modeId == current.modeId) return
        val lp = activity.window.attributes
        lp.preferredDisplayModeId = best.modeId
        activity.window.attributes = lp
    }
}
