package com.multiview.tv.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.multiview.tv.R
import com.multiview.tv.data.Settings

/**
 * The options menu used by both players:
 * quality, audio track, subtitles, aspect ratio, stream info, external player, picture-in-picture.
 */
@OptIn(UnstableApi::class)
object PlayerMenu {

    fun show(
        activity: Activity,
        player: ExoPlayer,
        playerView: PlayerView,
        url: String,
        extra: List<Pair<String, () -> Unit>> = emptyList(),
    ) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += extra
        items += activity.getString(R.string.menu_quality) to { quality(activity, player) }
        items += activity.getString(R.string.menu_audio) to { audio(activity, player) }
        items += activity.getString(R.string.menu_subtitles) to { subtitles(activity, player) }
        items += activity.getString(R.string.menu_aspect) to { aspect(activity, playerView) }
        items += activity.getString(R.string.menu_info) to { info(activity, player) }
        items += activity.getString(R.string.menu_external) to { external(activity, url) }
        if (pipSupported(activity)) items += activity.getString(R.string.menu_pip) to { enterPip(activity) }

        AlertDialog.Builder(activity)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    // ---------- tracks ----------

    private data class Track(val group: Tracks.Group, val index: Int, val format: Format)

    private fun tracks(player: ExoPlayer, type: Int): List<Track> =
        player.currentTracks.groups.filter { it.type == type }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { Track(g, it, g.getTrackFormat(it)) }
        }

    fun quality(activity: Activity, player: ExoPlayer) {
        val list = tracks(player, C.TRACK_TYPE_VIDEO).sortedByDescending { it.format.height }
        if (list.size <= 1) {
            val f = list.firstOrNull()?.format
            val res = if (f != null && f.height > 0) " (${f.width}×${f.height})" else ""
            Toast.makeText(activity, activity.getString(R.string.quality_single) + res, Toast.LENGTH_LONG).show()
            return
        }
        val labels = listOf(activity.getString(R.string.quality_auto)) + list.map {
            val kbps = if (it.format.bitrate > 0) " · ${it.format.bitrate / 1000} kbps" else ""
            "${it.format.height}p$kbps"
        }
        val selected = list.indexOfFirst { it.group.isTrackSelected(it.index) }
        val hasOverride = player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
        AlertDialog.Builder(activity)
            .setTitle(R.string.menu_quality)
            .setSingleChoiceItems(labels.toTypedArray(), if (hasOverride) selected + 1 else 0) { d, which ->
                val b = player.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                } else {
                    val t = list[which - 1]
                    b.setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                }
                player.trackSelectionParameters = b.build()
                d.dismiss()
            }
            .show()
    }

    fun audio(activity: Activity, player: ExoPlayer) {
        val list = tracks(player, C.TRACK_TYPE_AUDIO)
        if (list.isEmpty()) { Toast.makeText(activity, R.string.no_tracks, Toast.LENGTH_SHORT).show(); return }
        val labels = list.mapIndexed { i, t ->
            val name = t.format.label ?: t.format.language ?: activity.getString(R.string.track_n, i + 1)
            val ch = if (t.format.channelCount > 0) " · ${t.format.channelCount}ch" else ""
            name + ch
        }
        val selected = list.indexOfFirst { it.group.isTrackSelected(it.index) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.menu_audio)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { d, which ->
                val t = list[which]
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                    .build()
                d.dismiss()
            }
            .show()
    }

    fun subtitles(activity: Activity, player: ExoPlayer) {
        val list = tracks(player, C.TRACK_TYPE_TEXT)
        if (list.isEmpty()) { Toast.makeText(activity, R.string.no_subtitles, Toast.LENGTH_SHORT).show(); return }
        val labels = listOf(activity.getString(R.string.subtitles_off)) + list.mapIndexed { i, t ->
            t.format.label ?: t.format.language ?: activity.getString(R.string.track_n, i + 1)
        }
        val disabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val selected = if (disabled) 0 else list.indexOfFirst { it.group.isTrackSelected(it.index) } + 1
        AlertDialog.Builder(activity)
            .setTitle(R.string.menu_subtitles)
            .setSingleChoiceItems(labels.toTypedArray(), selected.coerceAtLeast(0)) { d, which ->
                val b = player.trackSelectionParameters.buildUpon()
                if (which == 0) {
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                } else {
                    val t = list[which - 1]
                    b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(t.group.mediaTrackGroup, t.index))
                }
                player.trackSelectionParameters = b.build()
                d.dismiss()
            }
            .show()
    }

    // ---------- picture ----------

    private val resizeModes = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )

    fun aspect(activity: Activity, playerView: PlayerView) {
        val labels = arrayOf(
            activity.getString(R.string.aspect_fit),
            activity.getString(R.string.aspect_fill),
            activity.getString(R.string.aspect_zoom),
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.menu_aspect)
            .setSingleChoiceItems(labels, resizeModes.indexOf(playerView.resizeMode).coerceAtLeast(0)) { d, which ->
                playerView.resizeMode = resizeModes[which]
                Settings.setResizeMode(activity, resizeModes[which])
                d.dismiss()
            }
            .show()
    }

    fun applySavedResizeMode(activity: Activity, playerView: PlayerView) {
        val m = Settings.resizeMode(activity)
        if (m in resizeModes) playerView.resizeMode = m
    }

    fun info(activity: Activity, player: ExoPlayer) {
        val v = player.videoFormat
        val a = player.audioFormat
        val lines = mutableListOf<String>()
        if (v != null) {
            lines += activity.getString(R.string.info_video, "${v.width}×${v.height}")
            if (v.frameRate > 0) lines += activity.getString(R.string.info_fps, "%.2f".format(v.frameRate))
            lines += activity.getString(R.string.info_codec, v.codecs ?: v.sampleMimeType ?: "-")
            if (v.bitrate > 0) lines += activity.getString(R.string.info_bitrate, "${v.bitrate / 1000} kbps")
        }
        if (a != null) {
            val codec = a.sampleMimeType?.removePrefix(MimeTypes.BASE_TYPE_AUDIO + "/") ?: "-"
            lines += activity.getString(R.string.info_audio, "$codec · ${a.channelCount}ch · ${a.sampleRate} Hz")
        }
        lines += activity.getString(R.string.info_decoder,
            activity.getString(if (Settings.decoder(activity) == "sw") R.string.decoder_sw else R.string.decoder_hw))
        AlertDialog.Builder(activity)
            .setTitle(R.string.menu_info)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    fun external(activity: Activity, url: String) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
        try {
            activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.menu_external)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.no_external_player, Toast.LENGTH_LONG).show()
        }
    }

    fun pipSupported(activity: Activity) =
        Build.VERSION.SDK_INT >= 26 && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enterPip(activity: Activity) {
        if (Build.VERSION.SDK_INT >= 26) {
            activity.enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }
}
